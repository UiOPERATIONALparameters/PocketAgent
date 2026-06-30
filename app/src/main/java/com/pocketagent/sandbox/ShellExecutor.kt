package com.pocketagent.sandbox

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v3.0 Shell Executor — NATIVE execution, no proot.
 *
 * Runs commands using the Termux bootstrap's bash (Android-native binary).
 * No proot, no container, no ptrace — just native execution.
 *
 * Two tiers:
 *   Tier 1 (always): /system/bin/sh — basic coreutils
 *   Tier 2 (after install): usr/bin/bash — full bash + apt + pkg + anything installable
 *
 * The agent can install packages with: pkg install python nodejs git gcc ffmpeg
 */
@Singleton
class ShellExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workspace: Workspace,
    private val nativeEnv: NativeEnvironmentManager
) {

    data class Result(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val durationMs: Long,
        val timedOut: Boolean = false,
        val truncated: Boolean = false,
        val usedNative: Boolean = false
    ) {
        val isSuccess: Boolean get() = exitCode == 0 && !timedOut
    }

    suspend fun execute(
        command: String,
        timeoutSec: Int = 30,
        maxOutputBytes: Int = 30_000
    ): Result = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()

        val nativeInstalled = nativeEnv.isInstalled()
        val useNative = nativeInstalled

        // Choose shell
        val shellCommand: List<String> = if (useNative) {
            // Tier 2: use native bash with profile sourced
            val bashPath = nativeEnv.getBashPath()!!
            listOf(bashPath, "--login", "-c",
                "source \"${workspace.homeDir.absolutePath}/.profile\" 2>/dev/null\n" + command)
        } else {
            // Tier 1: Android system shell
            listOf("/system/bin/sh", "-c", command)
        }

        val pb = ProcessBuilder(shellCommand)
            .directory(workspace.homeDir)
            .redirectErrorStream(false)

        // Set up environment
        val env = pb.environment()
        env["HOME"] = workspace.homeDir.absolutePath
        env["TERM"] = "xterm-256color"
        env["LANG"] = "en_US.UTF-8"
        env["LC_ALL"] = "en_US.UTF-8"
        env["PWD"] = workspace.homeDir.absolutePath
        env["TMPDIR"] = workspace.tmpDir.absolutePath

        if (useNative) {
            // Set PATH, LD_LIBRARY_PATH, PREFIX for native environment
            env["PATH"] = nativeEnv.getPath()
            env["LD_LIBRARY_PATH"] = nativeEnv.libDir.absolutePath
            env["PREFIX"] = nativeEnv.usrDir.absolutePath

            // SSL certificate paths
            val certDir = File(nativeEnv.etcDir, "ssl/certs")
            val caBundle = File(certDir, "ca-certificates.crt")
            if (caBundle.exists()) {
                env["CURL_CA_BUNDLE"] = caBundle.absolutePath
                env["SSL_CERT_FILE"] = caBundle.absolutePath
                env["SSL_CERT_DIR"] = certDir.absolutePath
                env["REQUESTS_CA_BUNDLE"] = caBundle.absolutePath
                env["GIT_SSL_CAINFO"] = caBundle.absolutePath
            }
        } else {
            env["PATH"] = "/system/bin:/system/xbin:${env["PATH"] ?: ""}"
        }

        val process = try {
            pb.start()
        } catch (e: IOException) {
            return@withContext Result(
                stdout = "",
                stderr = "Failed to start process: ${e.message}\n\n" +
                    if (useNative) "Native environment may be corrupt. Try reinstalling from Settings."
                    else "Install the Linux Environment from Settings for full capabilities.",
                exitCode = -1,
                durationMs = System.currentTimeMillis() - start,
                usedNative = useNative
            )
        }

        val result = withTimeoutOrNull((timeoutSec * 1000L)) {
            coroutineScope {
                val stdoutJob = async { readStream(process.inputStream, maxOutputBytes) }
                val stderrJob = async { readStream(process.errorStream, maxOutputBytes) }
                Triple(stdoutJob.await(), stderrJob.await(), process.waitFor())
            }
        }

        if (result == null) {
            process.destroyForcibly()
            try { process.waitFor() } catch (_: Exception) {}
            Result(
                stdout = "",
                stderr = "Command timed out after ${timeoutSec}s",
                exitCode = -1,
                durationMs = System.currentTimeMillis() - start,
                timedOut = true,
                usedNative = useNative
            )
        } else {
            val (stdoutPair, stderrPair, exitCode) = result
            Result(
                stdout = stdoutPair.first,
                stderr = stderrPair.first,
                exitCode = exitCode,
                durationMs = System.currentTimeMillis() - start,
                truncated = stdoutPair.second || stderrPair.second,
                usedNative = useNative
            )
        }
    }

    private fun readStream(stream: java.io.InputStream, maxBytes: Int): Pair<String, Boolean> {
        val sb = StringBuilder()
        val buf = ByteArray(8192)
        var truncated = false
        var total = 0
        try {
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                if (total + n > maxBytes) {
                    val toRead = maxBytes - total
                    if (toRead > 0) sb.append(String(buf, 0, toRead, Charsets.UTF_8))
                    truncated = true
                    while (stream.read(buf) >= 0) { /* discard */ }
                    break
                }
                sb.append(String(buf, 0, n, Charsets.UTF_8))
                total += n
            }
        } catch (_: IOException) {}
        return sb.toString() to truncated
    }
}
