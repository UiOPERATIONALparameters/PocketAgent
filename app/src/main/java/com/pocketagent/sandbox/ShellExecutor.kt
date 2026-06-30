package com.pocketagent.sandbox

import android.content.Context
import android.os.Build
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
 * v2.1 Shell Executor — radically simpler than v2.0.
 *
 * Two-tier execution:
 *
 * Tier 1 (Lite — always available, no setup):
 *   - Uses Android's /system/bin/sh (mksh)
 *   - Has: ls, cat, echo, grep, sed, awk, head, tail, wc, sort, uniq, tr, cut, mkdir, rm, cp, mv
 *   - Has: curl, wget (if Android includes them — varies by device)
 *   - Does NOT have: python3, node, git, gcc, pip, npm
 *   - Fast, zero overhead
 *
 * Tier 2 (Full Linux — requires one-time install via LinuxEnvironmentManager):
 *   - Uses proot to run commands inside an Ubuntu 22.04 container
 *   - Has: bash, apt, python3, perl (pre-installed)
 *   - Can install: node, git, gcc, ffmpeg, ImageMagick, anything via apt
 *   - /tmp is writable (proot virtualizes it)
 *   - No path translation issues (standard Linux paths)
 *   - Slight overhead (proot ptrace-based interception)
 *
 * The tier is chosen automatically:
 *   - If Linux is installed → use Tier 2 (proot)
 *   - If not → use Tier 1 (system shell)
 *
 * The agent's system prompt is honest about which tier is active.
 */
@Singleton
class ShellExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workspace: Workspace,
    private val linuxEnv: LinuxEnvironmentManager
) {

    data class Result(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val durationMs: Long,
        val timedOut: Boolean = false,
        val truncated: Boolean = false,
        val usedLinux: Boolean = false
    ) {
        val isSuccess: Boolean get() = exitCode == 0 && !timedOut
    }

    suspend fun execute(
        command: String,
        timeoutSec: Int = 30,
        maxOutputBytes: Int = 30_000
    ): Result = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()

        // Choose execution tier
        val linuxInstalled = linuxEnv.isInstalled()
        val useLinux = linuxInstalled

        val shellCommand: List<String> = if (useLinux) {
            // Tier 2: run via proot inside the Ubuntu/Alpine container
            // Working directory is /root/workspace (bind-mounted to the agent's workspace)
            linuxEnv.buildProotCommand(command, workingDir = "/root/workspace")
        } else {
            // Tier 1: use Android's system shell
            // Working directory is the agent's workspace
            listOf("/system/bin/sh", "-c", command)
        }

        val pb = ProcessBuilder(shellCommand)
            .directory(if (useLinux) File(workspace.homeDir.absolutePath) else workspace.homeDir)
            .redirectErrorStream(false)

        // Set up environment
        val env = pb.environment()
        env["HOME"] = if (useLinux) "/root" else workspace.homeDir.absolutePath
        env["TERM"] = "xterm-256color"
        env["LANG"] = "en_US.UTF-8"
        env["LC_ALL"] = "en_US.UTF-8"
        env["PWD"] = if (useLinux) "/root/workspace" else workspace.homeDir.absolutePath

        if (!useLinux) {
            // Tier 1: add system paths
            env["PATH"] = "/system/bin:/system/xbin:${env["PATH"] ?: ""}"
            env["TMPDIR"] = workspace.tmpDir.absolutePath
        }
        // For Tier 2 (proot), the environment is set up inside the container

        val process = try {
            pb.start()
        } catch (e: IOException) {
            return@withContext Result(
                stdout = "",
                stderr = "Failed to start process: ${e.message}\n\n" +
                    if (useLinux) "Linux environment may be corrupt. Try reinstalling from Settings."
                    else "If you want full Linux capabilities (python3, node, git), install the Linux Environment from Settings.",
                exitCode = -1,
                durationMs = System.currentTimeMillis() - start,
                usedLinux = useLinux
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
            // Timed out
            process.destroyForcibly()
            try { process.waitFor() } catch (_: Exception) {}
            Result(
                stdout = "",
                stderr = "Command timed out after ${timeoutSec}s",
                exitCode = -1,
                durationMs = System.currentTimeMillis() - start,
                timedOut = true,
                usedLinux = useLinux
            )
        } else {
            val (stdoutPair, stderrPair, exitCode) = result
            val stdoutStr = stdoutPair.first
            val stderrStr = stderrPair.first

            // If proot failed (e.g., SELinux blocked ptrace), fall back to system shell
            var finalStdout = stdoutStr
            var finalStderr = stderrStr
            var finalUsedLinux = useLinux

            if (exitCode != 0 && useLinux && (
                stderrStr.contains("proot", ignoreCase = true) ||
                stderrStr.contains("ptrace", ignoreCase = true) ||
                stderrStr.contains("Permission denied", ignoreCase = true) ||
                stderrStr.contains("tracee", ignoreCase = true) ||
                exitCode == 127 || exitCode == 255
            )) {
                // proot failed — fall back to system shell
                val fallbackResult = executeSystemShell(command, timeoutSec, maxOutputBytes, start)
                finalStdout = fallbackResult.stdout +
                    "\n\n[Note: Linux environment failed, used system shell. Error was: ${stderrStr.take(200)}]"
                finalStderr = stderrStr
                finalUsedLinux = false
            }

            Result(
                stdout = finalStdout,
                stderr = finalStderr,
                exitCode = exitCode,
                durationMs = System.currentTimeMillis() - start,
                truncated = stdoutPair.second || stderrPair.second,
                usedLinux = finalUsedLinux
            )
        }
    }

    /**
     * Fallback: run with Android's system shell.
     */
    private suspend fun executeSystemShell(
        command: String,
        timeoutSec: Int,
        maxOutputBytes: Int,
        start: Long
    ): Result = withContext(Dispatchers.IO) {
        val pb = ProcessBuilder("/system/bin/sh", "-c", command)
            .directory(workspace.homeDir)
            .redirectErrorStream(false)

        val env = pb.environment()
        env["HOME"] = workspace.homeDir.absolutePath
        env["TERM"] = "xterm-256color"
        env["PATH"] = "/system/bin:/system/xbin:${env["PATH"] ?: ""}"
        env["TMPDIR"] = workspace.tmpDir.absolutePath

        val process = try {
            pb.start()
        } catch (e: IOException) {
            return@withContext Result(
                stdout = "",
                stderr = "System shell also failed: ${e.message}",
                exitCode = -1,
                durationMs = System.currentTimeMillis() - start
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
            Result(
                stdout = "",
                stderr = "Timed out",
                exitCode = -1,
                durationMs = System.currentTimeMillis() - start,
                timedOut = true
            )
        } else {
            Result(
                stdout = result.first.first,
                stderr = result.second.first,
                exitCode = result.third,
                durationMs = System.currentTimeMillis() - start,
                truncated = result.first.second || result.second.second
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
                    // Drain the rest to avoid broken pipe
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
