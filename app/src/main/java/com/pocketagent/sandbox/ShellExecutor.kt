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
 * v0.1.0 uses Android's built-in /system/bin/sh (mksh). This gives us:
 *   - cd, ls, cat, echo, mkdir, rm, cp, mv, ln, chmod, find, grep, sed, awk
 *   - pipes, redirects, environment vars, exit codes
 *   - NO package manager (no apt, no pip, no node)
 *
 * v0.2.0 will vendor Termux bootstrap for full Linux userland (apt install, python, node, etc).
 *
 * Why /system/bin/sh and not Termux bootstrap immediately:
 *   - Smaller APK (~15MB vs ~70MB)
 *   - Faster first launch (no 50MB bootstrap extraction)
 *   - Sufficient for v0.1 use cases (file ops, shell scripts, simple text processing)
 *   - The agent loop + tool schema work identically — only the available binaries differ
 */
@Singleton
class ShellExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workspace: Workspace
) {

    data class Result(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val durationMs: Long,
        val timedOut: Boolean = false,
        val truncated: Boolean = false
    ) {
        val isSuccess: Boolean get() = exitCode == 0 && !timedOut
    }

    suspend fun execute(
        command: String,
        timeoutSec: Int = 30,
        maxOutputBytes: Int = 256_000  // 256KB cap per stream
    ): Result = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val pb = ProcessBuilder("/system/bin/sh", "-c", command)
            .directory(workspace.homeDir)
            .redirectErrorStream(false)

        // Set up environment
        val env = pb.environment()
        env["HOME"] = workspace.homeDir.absolutePath
        env["TERM"] = "xterm-256color"
        env["LANG"] = "en_US.UTF-8"
        env["PATH"] = "/system/bin:/system/xbin:${env["PATH"] ?: ""}"
        env["PWD"] = workspace.homeDir.absolutePath

        val process = try {
            pb.start()
        } catch (e: IOException) {
            return@withContext Result(
                stdout = "",
                stderr = "Failed to start process: ${e.message}",
                exitCode = -1,
                durationMs = System.currentTimeMillis() - start
            )
        }

        val timedOut = withTimeoutOrNull((timeoutSec * 1000L)) {
            coroutineScope {
                // Read stdout and stderr concurrently
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
                timedOut = true
            )
        } else {
            val (stdout, stderr, exitCode) = timedOut
            Result(
                stdout = stdout.first,
                stderr = stderr.first,
                exitCode = exitCode,
                durationMs = System.currentTimeMillis() - start,
                truncated = stdout.second || stderr.second
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
                    // Drain remaining to allow process to complete
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
