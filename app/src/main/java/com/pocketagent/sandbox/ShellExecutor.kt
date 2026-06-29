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
 * Spawns a bash/sh process to execute commands in the agent's workspace.
 *
 * If the Termux bootstrap is installed, uses the bootstrap's bash (full Linux
 * userland: apt install, python, node, ffmpeg, git, etc.).
 * Otherwise, falls back to Android's built-in /system/bin/sh (basic POSIX).
 *
 * CRITICAL: When using the bootstrap, LD_LIBRARY_PATH MUST be set to the
 * usr/lib directory. Without it, the dynamic linker can't find shared
 * libraries (libreadline.so.8, libc++, etc.) and bash fails to start.
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

        // First, check if bootstrap is installed and bash is executable
        val bootstrapPath = bootstrapInstaller.getBashPath()
        val useBootstrap = bootstrapPath != null

        // If bootstrap claims to be installed but bash isn't actually executable,
        // verify and fall back to /system/bin/sh
        val effectiveBootstrap = if (useBootstrap) {
            val bashFile = File(bootstrapPath!!)
            if (!bashFile.exists() || !bashFile.canExecute()) {
                // Try to fix permissions
                bashFile.setExecutable(true, true)
                if (!bashFile.canExecute()) {
                    // Fall back to system shell
                    false
                } else {
                    true
                }
            } else {
                true
            }
        } else {
            false
        }

        // Choose shell
        val shell = if (effectiveBootstrap) {
            listOf(bootstrapPath!!, "-c", command)
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

        if (effectiveBootstrap) {
            val usrDir = bootstrapInstaller.usrDir
            val libDir = File(usrDir, "lib")

            // CRITICAL: Set PATH to include bootstrap bin first
            env["PATH"] = bootstrapInstaller.getPath()

            // CRITICAL: Set LD_LIBRARY_PATH so the dynamic linker finds shared libs
            // Without this, bash fails with "libreadline.so.8 not found"
            env["LD_LIBRARY_PATH"] = libDir.absolutePath

            // Also set LD_PRELOAD for termux-exec (path translation)
            val termuxExec = File(libDir, "libtermux-exec.so")
            if (termuxExec.exists()) {
                env["LD_PRELOAD"] = termuxExec.absolutePath
            }

            // Set PREFIX — Termux packages use this to find their root
            env["PREFIX"] = usrDir.absolutePath

            // Set TMPDIR to a writable location inside the workspace
            env["TMPDIR"] = workspace.tmpDir.absolutePath

            // Set COLORTERM for color support
            env["COLORTERM"] = "truecolor"
        } else {
            env["PATH"] = "/system/bin:/system/xbin:${env["PATH"] ?: ""}"
        }

        val process = try {
            pb.start()
        } catch (e: IOException) {
            return@withContext Result(
                stdout = "",
                stderr = "Failed to start process: ${e.message}\n\nDiagnostic info:\n${bootstrapInstaller.getDiagnosticInfo()}",
                exitCode = -1,
                durationMs = System.currentTimeMillis() - start,
                usedBootstrap = effectiveBootstrap
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
                usedBootstrap = effectiveBootstrap
            )
        } else {
            val stdoutPair = timedOut.first
            val stderrPair = timedOut.second
            val exitCode = timedOut.third
            val stdoutStr = stdoutPair.first
            val stderrStr = stderrPair.first
            // If bash failed to start, append diagnostic info
            val enrichedStderr = if (exitCode != 0 && effectiveBootstrap && stderrStr.contains("not found", ignoreCase = true)) {
                "$stderrStr\n\n--- Diagnostic Info ---\n${bootstrapInstaller.getDiagnosticInfo()}"
            } else {
                stderrStr
            }
            Result(
                stdout = stdoutStr,
                stderr = enrichedStderr,
                exitCode = exitCode,
                durationMs = System.currentTimeMillis() - start,
                truncated = stdoutPair.second || stderrPair.second,
                usedBootstrap = effectiveBootstrap
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
