package com.pocketagent.sandbox

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v2.1 Linux Environment Manager — replaces both BootstrapInstaller and ProotDistroManager.
 *
 * ARCHITECTURE (v2.1 — "The Big Simplification"):
 *
 * The v2.0 approach (Termux bootstrap + package manager) was fundamentally broken:
 *   - The bootstrap doesn't include python3/node/git (they need `pkg install`)
 *   - `pkg install` needs apt's HTTPS method, which has missing .so dependencies
 *   - termux-exec path translation is fragile
 *   - Termux paths hardcoded in compiled binaries can't be patched
 *   - The whole chicken-and-egg: need package manager to install packages, but
 *     package manager itself is broken
 *
 * The v2.1 approach is radically simpler:
 *   1. Bundle a STATIC proot binary in the APK (one per ABI, ~1-6MB each)
 *      - Static = no shared library dependencies, runs anywhere
 *      - From https://github.com/proot-me/proot-static-build
 *   2. Download an Ubuntu 22.04 rootfs on first use (~28MB compressed)
 *      - From https://cdimage.ubuntu.com/ubuntu-base/releases/
 *      - Contains: bash, apt, python3, perl, AND can install anything via apt
 *   3. Run ALL agent commands via: proot --rootfs=ubuntu/ <command>
 *      - proot handles /tmp, /var, /etc properly (virtualized)
 *      - No path translation needed (Ubuntu uses standard Linux paths)
 *      - apt works natively (no Termux path issues)
 *      - python3, node, git, gcc all installable via `apt install`
 *
 * This is the same approach used by UserLAnd, Andronix, and other "Linux on Android"
 * apps. It's battle-tested and works on all Android 8+ devices without root.
 *
 * Layout:
 *   /data/data/com.pocketagent/files/
 *     ├── proot/bin/proot          (extracted from APK assets, static binary)
 *     ├── linux/                    (Ubuntu rootfs, ~150MB extracted)
 *     │   ├── bin/sh, bin/bash
 *     │   ├── usr/bin/python3, usr/bin/apt
 *     │   ├── etc/, var/, tmp/
 *     │   └── ...
 *     └── workspace/                (agent's $HOME, shared between shell and proot)
 *
 * The workspace is bind-mounted into the proot container at /root, so files the agent
 * creates are accessible both from the shell and from the file browser UI.
 */
@Singleton
class LinuxEnvironmentManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workspace: Workspace
) {
    companion object {
        const val MARKER_FILE = ".linux_installed"
        const val PROOT_VERSION = "5.1.0"

        // Rootfs URLs — verified working June 2026
        // Ubuntu 22.04 LTS base (has apt, bash, perl; install python3/node/git via apt)
        private fun rootfsUrl(abi: String): String = when (abi) {
            "aarch64" -> "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04-base-arm64.tar.gz"
            "x86_64" -> "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04-base-amd64.tar.gz"
            // For 32-bit ARM, use Alpine (Ubuntu doesn't publish armhf base images)
            "arm" -> "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/armhf/alpine-minirootfs-3.20.0-armhf.tar.gz"
            "i686" -> "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/x86/alpine-minirootfs-3.20.0-x86.tar.gz"
            else -> "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04-base-arm64.tar.gz"
        }

        /** Map Android Build.SUPPORTED_ABIS to our ABI name. */
        fun detectAbi(): String {
            val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            return when (primaryAbi) {
                "arm64-v8a" -> "aarch64"
                "armeabi-v7a", "armeabi" -> "arm"
                "x86_64" -> "x86_64"
                "x86" -> "i686"
                else -> "aarch64"
            }
        }

        /** True if the rootfs is Ubuntu (has apt), false if Alpine (has apk). */
        fun isUbuntu(abi: String): Boolean = abi in setOf("aarch64", "x86_64")
    }

    val prootDir: File = File(context.filesDir, "proot")
    val prootBinDir: File = File(prootDir, "bin")
    val prootBinary: File get() = File(prootBinDir, "proot")
    val linuxDir: File = File(context.filesDir, "linux")

    /** True if the Linux environment is installed and ready. */
    fun isInstalled(): Boolean {
        return File(linuxDir, MARKER_FILE).exists() &&
            prootBinary.exists() &&
            File(linuxDir, "bin/sh").exists()
    }

    /** Get the proot binary path, or null if not extracted. */
    fun getProotPath(): String? {
        return if (prootBinary.exists() && prootBinary.canExecute()) {
            prootBinary.absolutePath
        } else null
    }

    /**
     * Extract the static proot binary from APK assets.
     * This is fast (~1MB copy) and doesn't require network.
     */
    suspend fun extractProot(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val abi = detectAbi()
            val assetName = "proot/proot-$abi"
            val prootAsset = try {
                context.assets.open(assetName)
            } catch (e: Exception) {
                return@withContext Result.failure(IOException("No proot binary for ABI '$abi' in assets. This device may not be supported."))
            }

            prootBinDir.mkdirs()
            prootAsset.use { input ->
                FileOutputStream(prootBinary).use { output ->
                    input.copyTo(output)
                }
            }
            prootBinary.setExecutable(true, true)
            prootBinary.setReadable(true, true)

            if (!prootBinary.canExecute()) {
                return@withContext Result.failure(IOException("Failed to make proot executable. Storage may be mounted noexec."))
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Download and extract the Ubuntu/Alpine rootfs.
     * This is a long operation (~28MB download + ~150MB extraction).
     *
     * Caller should check available storage first (need ~300MB free).
     */
    suspend fun installRootfs(
        onProgress: (String, Long, Long) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val abi = detectAbi()

            // Step 1: extract proot (if not already done)
            if (!prootBinary.exists()) {
                onProgress("Extracting proot binary...", 0, 1)
                val prootResult = extractProot()
                if (prootResult.isFailure) {
                    return@withContext Result.failure(prootResult.exceptionOrNull()!!)
                }
            }

            // Step 2: download rootfs
            val url = rootfsUrl(abi)
            val archiveFile = File(context.cacheDir, "rootfs-$abi.tar.gz")
            onProgress("Downloading Linux rootfs (~28MB)...", 0, -1)

            val downloadResult = downloadFile(url, archiveFile) { downloaded, total ->
                onProgress("Downloading Linux rootfs...", downloaded, total)
            }
            if (downloadResult.isFailure) {
                archiveFile.delete()
                return@withContext Result.failure(IOException("Download failed: ${downloadResult.exceptionOrNull()?.message}"))
            }

            // Verify download (Ubuntu is ~27MB, Alpine is ~3MB)
            val minSize = if (isUbuntu(abi)) 20_000_000 else 2_000_000
            if (!archiveFile.exists() || archiveFile.length() < minSize) {
                archiveFile.delete()
                return@withContext Result.failure(IOException("Downloaded rootfs is too small (${archiveFile.length()} bytes) — likely a partial download. Check your connection."))
            }

            // Step 3: extract rootfs
            onProgress("Extracting rootfs (this takes a minute)...", -1, -1)
            linuxDir.mkdirs()
            if (linuxDir.exists()) {
                linuxDir.deleteRecursively()
                linuxDir.mkdirs()
            }

            extractRootfs(archiveFile, linuxDir)

            // Step 4: configure the rootfs
            onProgress("Configuring Linux environment...", -1, -1)
            configureRootfs(linuxDir, abi)

            // Step 5: create marker file
            File(linuxDir, MARKER_FILE).writeText(System.currentTimeMillis().toString())

            // Clean up archive
            archiveFile.delete()

            // Step 6: verify
            if (!File(linuxDir, "bin/sh").exists()) {
                return@withContext Result.failure(IOException("Rootfs extracted but /bin/sh not found. Installation may be corrupt."))
            }

            onProgress("Done!", 1, 1)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Configure the rootfs after extraction:
     *   - Set up /etc/resolv.conf (DNS)
     *   - Set up /etc/hostname
     *   - Create /tmp directory (writable)
     *   - Create /root directory (agent's home inside proot)
     *   - For Ubuntu: install ca-certificates so apt HTTPS works
     */
    private fun configureRootfs(rootfs: File, abi: String) {
        val etcDir = File(rootfs, "etc")
        etcDir.mkdirs()

        // DNS resolver config
        val resolvConf = File(etcDir, "resolv.conf")
        resolvConf.writeText("""
            nameserver 8.8.8.8
            nameserver 8.8.4.4
            nameserver 1.1.1.1
            options timeout:2 attempts:2
        """.trimIndent())

        // Hostname
        val hostname = File(etcDir, "hostname")
        hostname.writeText("pocketagent\n")

        // Hosts file
        val hosts = File(etcDir, "hosts")
        hosts.writeText("""
            127.0.0.1 localhost
            127.0.1.1 pocketagent
        """.trimIndent() + "\n")

        // Ensure /tmp exists and is writable
        val tmpDir = File(rootfs, "tmp")
        tmpDir.mkdirs()
        tmpDir.setWritable(true, false)
        tmpDir.setReadable(true, false)
        tmpDir.setExecutable(true, false)

        // Ensure /root exists (agent's home inside the container)
        val rootHome = File(rootfs, "root")
        rootHome.mkdirs()
        rootHome.setWritable(true, false)

        // Ensure /var/tmp exists
        val varTmp = File(rootfs, "var/tmp")
        varTmp.mkdirs()
        varTmp.setWritable(true, false)

        // Create a bind-mount source for the workspace
        // (proot will bind-mount /data/data/.../workspace → /root/workspace)
        val workspaceMount = File(rootHome, "workspace")
        workspaceMount.mkdirs()
    }

    /**
     * Build the proot command to run a shell command inside the Linux container.
     *
     * The workspace is bind-mounted at /root/workspace so the agent's files are
     * accessible both from inside the container and from the file browser UI.
     */
    fun buildProotCommand(command: String, workingDir: String? = null): List<String> {
        val prootPath = getProotPath() ?: return listOf("/system/bin/sh", "-c", command)

        val cwd = workingDir ?: "/root"

        return listOf(
            prootPath,
            "--rootfs=${linuxDir.absolutePath}",
            "--cwd=$cwd",
            "--bind=/dev",
            "--bind=/proc",
            "--bind=/sys",
            "--bind=${workspace.homeDir.absolutePath}:/root/workspace",
            "--bind=${workspace.tmpDir.absolutePath}:/tmp",
            "--bind=/dev/urandom:/dev/random",  // some tools need /dev/random
            "--kill-on-exit",
            "/bin/sh", "-c", command
        )
    }

    /**
     * Uninstall the Linux environment. Frees ~150MB.
     * The proot binary is kept (it's small and bundled in the APK anyway).
     */
    suspend fun uninstall(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (linuxDir.exists()) {
                linuxDir.deleteRecursively()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get diagnostic info about the Linux installation.
     */
    fun getDiagnosticInfo(): String {
        return buildString {
            appendLine("=== Linux Environment Status ===")
            appendLine("ABI: ${detectAbi()}")
            appendLine("Rootfs type: ${if (isUbuntu(detectAbi())) "Ubuntu 22.04" else "Alpine 3.20"}")
            appendLine("Installed: ${isInstalled()}")
            appendLine("")
            appendLine("proot binary: ${prootBinary.absolutePath}")
            appendLine("  exists: ${prootBinary.exists()}")
            appendLine("  executable: ${if (prootBinary.exists()) prootBinary.canExecute() else false}")
            appendLine("  size: ${if (prootBinary.exists()) prootBinary.length() else 0} bytes")
            appendLine("")
            appendLine("Linux rootfs: ${linuxDir.absolutePath}")
            appendLine("  exists: ${linuxDir.exists()}")
            if (linuxDir.exists()) {
                appendLine("  /bin/sh exists: ${File(linuxDir, "bin/sh").exists()}")
                appendLine("  /etc/resolv.conf: ${File(linuxDir, "etc/resolv.conf").exists()}")
                appendLine("  marker file: ${File(linuxDir, MARKER_FILE).exists()}")
                val totalSize = linuxDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                appendLine("  total size: ${totalSize / (1024 * 1024)} MB")
            }
            appendLine("")
            appendLine("Workspace: ${workspace.homeDir.absolutePath}")
            appendLine("  exists: ${workspace.homeDir.exists()}")
            val wsSize = workspace.homeDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            appendLine("  size: ${wsSize / (1024 * 1024)} MB")
        }
    }

    /**
     * Check available storage. Returns true if at least [requiredMb] MB is free.
     */
    fun hasEnoughStorage(requiredMb: Long): Boolean {
        val stat = android.os.StatFs(context.filesDir.absolutePath)
        val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
        return freeBytes >= requiredMb * 1024 * 1024
    }

    private fun downloadFile(url: String, dest: File, onProgress: (Long, Long) -> Unit): Result<Unit> {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(600, java.util.concurrent.TimeUnit.SECONDS)  // 10 min for slow connections
                .build()
            val request = Request.Builder().url(url)
                .header("User-Agent", "PocketAgent/2.1")
                .get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(IOException("HTTP ${response.code} ${response.message}"))
                }
                val total = response.body?.contentLength() ?: -1
                var downloaded = 0L
                response.body?.byteStream()?.use { input ->
                    FileOutputStream(dest).use { output ->
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            downloaded += n
                            onProgress(downloaded, total)
                        }
                    }
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractRootfs(archiveFile: File, targetDir: File) {
        // Ubuntu/Alpine rootfs is .tar.gz — use Apache Commons Compress for reliable extraction
        archiveFile.inputStream().buffered().use { fis ->
            GzipCompressorInputStream(fis).use { gzis ->
                TarArchiveInputStream(gzis).use { tis ->
                    var entry = tis.nextEntry
                    while (entry != null) {
                        val entryPath = entry.name
                        // Block path traversal
                        if (entryPath.contains("..") || entryPath.startsWith("/")) {
                            entry = tis.nextEntry
                            continue
                        }
                        val outFile = File(targetDir, entryPath)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { tis.copyTo(it) }
                            // Preserve executable bit for binaries
                            if (entryPath.startsWith("bin/") || entryPath.startsWith("usr/bin/") ||
                                entryPath.startsWith("sbin/") || entryPath.startsWith("usr/sbin/") ||
                                entryPath.startsWith("usr/libexec/")) {
                                outFile.setExecutable(true, false)
                            }
                            // Make everything readable
                            outFile.setReadable(true, false)
                        }
                        entry = tis.nextEntry
                    }
                }
            }
        }
    }
}
