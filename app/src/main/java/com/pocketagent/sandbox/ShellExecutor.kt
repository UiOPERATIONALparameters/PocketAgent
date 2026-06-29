package com.pocketagent.sandbox

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Spawns a bash/sh process to execute commands in the agent's workspace.
 *
 * If the Termux bootstrap is installed, uses the bootstrap's bash (full Linux
 * userland: apt install, python, node, ffmpeg, git, etc.).
 * Otherwise, falls back to Android's built-in /system/bin/sh (basic POSIX).
 */
@Singleton
class ShellExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workspace: Workspace,
    private val bootstrapInstaller: BootstrapInstaller
) {

    data class Result(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val durationMs: Long,
        val timedOut: Boolean = false,
        val truncated: Boolean = false,
        val usedBootstrap: Boolean = false
    ) {
        val isSuccess: Boolean get() = exitCode == 0 && !timedOut
    }

    suspend fun execute(
        command: String,
        timeoutSec: Int = 30,
        maxOutputBytes: Int = 256_000
    ): Result = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val bootstrapPath = bootstrapInstaller.getBashPath()
        val useBootstrap = bootstrapPath != null

        // Choose shell: bootstrap bash if available, else /system/bin/sh
        val shell = if (useBootstrap) {
            listOf(bootstrapPath!!, "--login", "-c", command)
        } else {
            listOf("/system/bin/sh", "-c", command)
        }

        val pb = ProcessBuilder(shell)
            .directory(workspace.homeDir)
            .redirectErrorStream(false)

        // Set up environment
        val env = pb.environment()
        env["HOME"] = workspace.homeDir.absolutePath
        env["TERM"] = "xterm-256color"
        env["LANG"] = "en_US.UTF-8"
        env["LC_ALL"] = "en_US.UTF-8"
        env["PWD"] = workspace.homeDir.absolutePath

        if (useBootstrap) {
            // Set PATH to include bootstrap bin first
            env["PATH"] = bootstrapInstaller.getPath()
            // Tell the bootstrap where its root is
            env["PREFIX"] = bootstrapInstaller.usrDir.absolutePath
            env["TMPDIR"] = workspace.tmpDir.absolutePath
        } else {
            env["PATH"] = "/system/bin:/system/xbin:${env["PATH"] ?: ""}"
        }

        val process = try {
            pb.start()
        } catch (e: IOException) {
            return@withContext Result(
                stdout = "",
                stderr = "Failed to start process: ${e.message}",
                exitCode = -1,
                durationMs = System.currentTimeMillis() - start,
                usedBootstrap = useBootstrap
            )
        }

        val timedOut = withTimeoutOrNull((timeoutSec * 1000L)) {
            coroutineScope {
                val stdoutJob = async { readStream(process.inputStream, maxOutputBytes) }
                val stderrJob = async { readStream(process.errorStream, maxOutputBytes) }
                val stdout = stdoutJob.await()
                val stderr = stderrJob.await()
                val exitCode = process.waitFor()
                Triple(stdout, stderr, exitCode)
            }
        }

        if (timedOut == null) {
            process.destroyForcibly()
            try { process.waitFor() } catch (_: Exception) {}
            Result(
                stdout = "",
                stderr = "Command timed out after ${timeoutSec}s",
                exitCode = -1,
                durationMs = System.currentTimeMillis() - start,
                timedOut = true,
                usedBootstrap = useBootstrap
            )
        } else {
            val (stdout, stderr, exitCode) = timedOut
            Result(
                stdout = stdout.first,
                stderr = stderr.first,
                exitCode = exitCode,
                durationMs = System.currentTimeMillis() - start,
                truncated = stdout.second || stderr.second,
                usedBootstrap = useBootstrap
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
        } catch (_: IOException) {
            // Stream closed — fine
        }
        return sb.toString() to truncated
    }
}
