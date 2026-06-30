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
 * Spawns a bash/sh process to execute commands in the agent's workspace.
 *
 * CRITICAL ANDROID LINKER FIX:
 * Android's dynamic linker (linker64) is the SYSTEM linker at /system/bin/linker64.
 * When you spawn a binary from app-private storage (like our Termux bootstrap bash),
 * the system linker doesn't know about usr/lib/ — it only searches system library paths.
 * Setting LD_LIBRARY_PATH in the environment doesn't reliably work on all Android versions
 * because the linker reads it at a specific point in its initialization.
 *
 * The reliable solution (used by Termux): invoke the binary THROUGH the dynamic linker
 * with an explicit --library-path argument. This forces the linker to search our lib dir.
 *
 * Command: /system/bin/linker64 --library-path=/data/.../usr/lib /data/.../usr/bin/bash -c "command"
 *
 * However, directly invoking linker64 requires root on newer Android. So we use a fallback chain:
 * 1. Try with LD_LIBRARY_PATH + LD_PRELOAD set (works on most devices)
 * 2. If that fails, fall back to /system/bin/sh (Android's mksh — basic but works)
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
        val libDir = File(bootstrapInstaller.usrDir, "lib")
        val termuxExec = findTermuxExec(libDir)

        // Verify bootstrap is actually usable
        val canUseBootstrap = bootstrapPath != null &&
            File(bootstrapPath).exists() &&
            File(bootstrapPath).canExecute() &&
            libDir.exists() &&
            File(libDir, "libreadline.so.8").exists()

        // Choose shell command
        // CRITICAL: Use --login so .profile is loaded (sets PREFIX for apt/pkg)
        val shellCommand: List<String> = if (canUseBootstrap && bootstrapPath != null) {
            // Use bootstrap bash with login flag so .profile is sourced
            listOf(bootstrapPath, "--noprofile", "--norc", "-c", "source \"" + workspace.homeDir.absolutePath + "/.profile\" 2>/dev/null\n" + command)
        } else {
            // Fall back to Android's system shell
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

        if (canUseBootstrap && bootstrapPath != null) {
            val usrDir = bootstrapInstaller.usrDir

            // CRITICAL: Set PATH to include bootstrap bin first
            env["PATH"] = bootstrapInstaller.getPath()

            // CRITICAL: Set LD_LIBRARY_PATH — the dynamic linker searches here for shared libs
            env["LD_LIBRARY_PATH"] = libDir.absolutePath

            // CRITICAL: Set LD_PRELOAD for termux-exec (path translation)
            // This library intercepts file system calls and remaps
            // /data/data/com.termux/ -> our actual paths
            if (termuxExec != null && termuxExec.exists()) {
                env["LD_PRELOAD"] = termuxExec.absolutePath
            }

            // CRITICAL: These env vars tell termux-exec WHERE to redirect paths
            // Without them, termux-exec doesn't intercept any calls
            env["PREFIX"] = usrDir.absolutePath
            env["TERMUX_PREFIX"] = usrDir.absolutePath
            env["TERMUX_APP__DATA_DIR"] = context.filesDir.absolutePath  // note double underscore
            env["TERMUX_ANDROID_HOME"] = workspace.homeDir.absolutePath
            env["TERMUX_HOME"] = workspace.homeDir.absolutePath

            // Set TMPDIR to a writable location inside the workspace
            env["TMPDIR"] = workspace.tmpDir.absolutePath

            // Set COLORTERM for color support
            env["COLORTERM"] = "truecolor"

            // SSL certificate paths for curl/wget/git
            val certDir = File(bootstrapInstaller.usrDir, "etc/ssl/certs")
            val caBundle = File(certDir, "ca-certificates.crt")
            if (caBundle.exists()) {
                env["CURL_CA_BUNDLE"] = caBundle.absolutePath
                env["SSL_CERT_FILE"] = caBundle.absolutePath
                env["SSL_CERT_DIR"] = certDir.absolutePath
                env["REQUESTS_CA_BUNDLE"] = caBundle.absolutePath
                env["GIT_SSL_CAINFO"] = caBundle.absolutePath
            }

            // APT and dpkg config
            val aptConf = File(bootstrapInstaller.usrDir, "etc/apt/apt.conf")
            if (aptConf.exists()) env["APT_CONFIG"] = aptConf.absolutePath
            val dpkgDir = File(bootstrapInstaller.usrDir, "var/lib/dpkg")
            if (dpkgDir.exists()) env["DPKG_ADMINDIR"] = dpkgDir.absolutePath
        } else {
            env["PATH"] = "/system/bin:/system/xbin:${env["PATH"] ?: ""}"
        }

        val process = try {
            pb.start()
        } catch (e: IOException) {
            val diag = bootstrapInstaller.getDiagnosticInfo()
            return@withContext Result(
                stdout = "",
                stderr = "Failed to start process: ${e.message}\n\nDiagnostic info:\n$diag",
                exitCode = -1,
                durationMs = System.currentTimeMillis() - start,
                usedBootstrap = canUseBootstrap
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
                usedBootstrap = canUseBootstrap
            )
        } else {
            val stdoutPair = timedOut.first
            val stderrPair = timedOut.second
            val exitCode = timedOut.third
            val stdoutStr = stdoutPair.first
            val stderrStr = stderrPair.first

            // If bash failed to start (exit 127 or 255 with library errors),
            // try running with /system/bin/sh as fallback and note the issue
            var finalStdout = stdoutStr
            var finalStderr = stderrStr
            var finalUsedBootstrap = canUseBootstrap

            if (exitCode != 0 && canUseBootstrap && (
                stderrStr.contains("not found", ignoreCase = true) ||
                stderrStr.contains("Permission denied", ignoreCase = true) ||
                stderrStr.contains("No such file", ignoreCase = true) ||
                exitCode == 127 || exitCode == 255
            )) {
                // Bootstrap bash failed — try system shell as fallback
                val fallbackResult = trySystemShell(command, timeoutSec, maxOutputBytes, start)
                if (fallbackResult.isSuccess) {
                    finalStdout = fallbackResult.stdout + "\n\n[Note: Bootstrap bash failed, used system shell. Run 'Verify & Repair' in Settings → Linux Environment.]"
                    finalStderr = stderrStr  // keep original error for context
                    finalUsedBootstrap = false
                } else {
                    // Both failed — append diagnostic info
                    finalStderr = stderrStr + "\n\n--- Diagnostic Info ---\n${bootstrapInstaller.getDiagnosticInfo()}"
                }
            }

            Result(
                stdout = finalStdout,
                stderr = finalStderr,
                exitCode = exitCode,
                durationMs = System.currentTimeMillis() - start,
                truncated = stdoutPair.second || stderrPair.second,
                usedBootstrap = finalUsedBootstrap
            )
        }
    }

    /**
     * Fallback: run the command with Android's system shell.
     */
    private suspend fun trySystemShell(
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

    private fun findTermuxExec(libDir: File): File? {
        if (!libDir.exists()) return null
        val candidates = libDir.listFiles { f -> f.isFile && f.name.startsWith("libtermux-exec") } ?: return null
        return candidates.minByOrNull { it.name.length }
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
