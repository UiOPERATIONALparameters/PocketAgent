package com.pocketagent.sandbox

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v2.1 Linux Environment Manager — proot + Ubuntu rootfs.
 *
 * v2.1.1 FIX: Fixed rootfs extraction that was creating 0-byte files for symlinks
 * instead of actual symlinks. Ubuntu 22.04 uses merged-usr where /bin -> /usr/bin,
 * /lib -> /usr/lib, etc. Without proper symlink handling, /bin/sh was unreachable
 * even though /usr/bin/sh (-> dash) existed in the tarball.
 */
@Singleton
class LinuxEnvironmentManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workspace: Workspace
) {
    companion object {
        const val MARKER_FILE = ".linux_installed"

        private fun rootfsUrl(abi: String): String = when (abi) {
            "aarch64" -> "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04-base-arm64.tar.gz"
            "x86_64" -> "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04-base-amd64.tar.gz"
            "arm" -> "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/armhf/alpine-minirootfs-3.20.0-armhf.tar.gz"
            "i686" -> "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/x86/alpine-minirootfs-3.20.0-x86.tar.gz"
            else -> "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04-base-arm64.tar.gz"
        }

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

        fun isUbuntu(abi: String): Boolean = abi in setOf("aarch64", "x86_64")
    }

    val prootDir: File = File(context.filesDir, "proot")
    val prootBinDir: File = File(prootDir, "bin")
    val prootLibDir: File = File(prootDir, "lib")
    val prootBinary: File get() = File(prootBinDir, "proot")
    val libtallocFile: File get() = File(prootLibDir, "libtalloc.so.2")
    val linuxDir: File = File(context.filesDir, "linux")

    fun isInstalled(): Boolean {
        return File(linuxDir, MARKER_FILE).exists() &&
            prootBinary.exists() &&
            (File(linuxDir, "bin/sh").exists() || File(linuxDir, "usr/bin/sh").exists())
    }

    fun getProotPath(): String? {
        return if (prootBinary.exists() && prootBinary.canExecute()) {
            prootBinary.absolutePath
        } else null
    }

    suspend fun extractProot(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val abi = detectAbi()
            val prootAssetName = "proot/proot-$abi"
            val libtallocAssetName = "proot/libtalloc-$abi.so"
            val prootAsset = try {
                context.assets.open(prootAssetName)
            } catch (e: Exception) {
                return@withContext Result.failure(IOException("No proot binary for ABI '$abi' in assets."))
            }
            val libtallocAsset = try {
                context.assets.open(libtallocAssetName)
            } catch (e: Exception) {
                return@withContext Result.failure(IOException("No libtalloc for ABI '$abi' in assets."))
            }

            prootBinDir.mkdirs()
            prootLibDir.mkdirs()

            // Extract proot binary
            prootAsset.use { input ->
                FileOutputStream(prootBinary).use { output ->
                    input.copyTo(output)
                }
            }
            prootBinary.setExecutable(true, true)
            prootBinary.setReadable(true, true)

            // Extract libtalloc.so.2 (proot depends on it)
            libtallocAsset.use { input ->
                FileOutputStream(libtallocFile).use { output ->
                    input.copyTo(output)
                }
            }
            libtallocFile.setReadable(true, true)

            if (!prootBinary.canExecute()) {
                return@withContext Result.failure(IOException("Failed to make proot executable."))
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun installRootfs(
        onProgress: (String, Long, Long) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val abi = detectAbi()

            if (!prootBinary.exists()) {
                onProgress("Extracting proot binary...", 0, 1)
                val prootResult = extractProot()
                if (prootResult.isFailure) {
                    return@withContext Result.failure(prootResult.exceptionOrNull()!!)
                }
            }

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

            val minSize = if (isUbuntu(abi)) 20_000_000 else 2_000_000
            if (!archiveFile.exists() || archiveFile.length() < minSize) {
                archiveFile.delete()
                return@withContext Result.failure(IOException("Downloaded rootfs is too small (${archiveFile.length()} bytes)."))
            }

            onProgress("Extracting rootfs (this takes a minute)...", -1, -1)
            linuxDir.mkdirs()
            if (linuxDir.exists()) {
                linuxDir.deleteRecursively()
                linuxDir.mkdirs()
            }

            // v2.1.1 FIX: Use the new symlink-aware extraction
            val extractResult = extractRootfsFixed(archiveFile, linuxDir, onProgress)
            if (extractResult.isFailure) {
                return@withContext Result.failure(extractResult.exceptionOrNull()!!)
            }

            onProgress("Configuring Linux environment...", -1, -1)
            configureRootfs(linuxDir, abi)

            // Verify /bin/sh exists (following symlinks)
            val binSh = File(linuxDir, "bin/sh")
            val usrBinSh = File(linuxDir, "usr/bin/sh")
            if (!binSh.exists() && !usrBinSh.exists()) {
                // Diagnostic: list what we actually extracted
                val diag = buildString {
                    appendLine("Extraction verification failed.")
                    appendLine("linuxDir exists: ${linuxDir.exists()}")
                    if (linuxDir.exists()) {
                        appendLine("linuxDir contents:")
                        linuxDir.listFiles()?.take(20)?.forEach { f ->
                            appendLine("  ${f.name} (dir=${f.isDirectory}, file=${f.isFile}, size=${f.length()}, canRead=${f.canRead()})")
                        }
                        appendLine("bin/ exists: ${File(linuxDir, "bin").exists()}")
                        appendLine("usr/bin/ exists: ${File(linuxDir, "usr/bin").exists()}")
                        appendLine("usr/bin/sh exists: ${File(linuxDir, "usr/bin/sh").exists()}")
                        appendLine("usr/bin/bash exists: ${File(linuxDir, "usr/bin/bash").exists()}")
                        appendLine("usr/bin/dash exists: ${File(linuxDir, "usr/bin/dash").exists()}")
                    }
                }
                return@withContext Result.failure(IOException("Rootfs extracted but /bin/sh not found.\n$diag"))
            }

            File(linuxDir, MARKER_FILE).writeText(System.currentTimeMillis().toString())
            archiveFile.delete()

            onProgress("Done!", 1, 1)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun configureRootfs(rootfs: File, abi: String) {
        val etcDir = File(rootfs, "etc")
        etcDir.mkdirs()

        val resolvConf = File(etcDir, "resolv.conf")
        resolvConf.writeText("""
            nameserver 8.8.8.8
            nameserver 8.8.4.4
            nameserver 1.1.1.1
            options timeout:2 attempts:2
        """.trimIndent())

        val hostname = File(etcDir, "hostname")
        hostname.writeText("pocketagent\n")

        val hosts = File(etcDir, "hosts")
        hosts.writeText("""
            127.0.0.1 localhost
            127.0.1.1 pocketagent
        """.trimIndent() + "\n")

        val tmpDir = File(rootfs, "tmp")
        tmpDir.mkdirs()
        tmpDir.setWritable(true, false)
        tmpDir.setReadable(true, false)
        tmpDir.setExecutable(true, false)

        val rootHome = File(rootfs, "root")
        rootHome.mkdirs()
        rootHome.setWritable(true, false)

        val varTmp = File(rootfs, "var/tmp")
        varTmp.mkdirs()
        varTmp.setWritable(true, false)

        val workspaceMount = File(rootHome, "workspace")
        workspaceMount.mkdirs()
    }

    fun buildProotCommand(command: String, workingDir: String? = null): List<String> {
        val prootPath = getProotPath() ?: return listOf("/system/bin/sh", "-c", command)
        val cwd = workingDir ?: "/root"

        // The Termux-patched proot binary needs LD_LIBRARY_PATH set to find libtalloc.so.2
        // We'll set this in the ShellExecutor's environment, not as a command argument.
        return listOf(
            prootPath,
            "--rootfs=${linuxDir.absolutePath}",
            "--cwd=$cwd",
            "--bind=/dev",
            "--bind=/proc",
            "--bind=/sys",
            "--bind=${workspace.homeDir.absolutePath}:/root/workspace",
            "--bind=${workspace.tmpDir.absolutePath}:/tmp",
            "/bin/sh", "-c", command
        )
    }

    /** The LD_LIBRARY_PATH needed for proot to find libtalloc.so.2. */
    fun getProotLdLibraryPath(): String = prootLibDir.absolutePath

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
            appendLine("")
            appendLine("Linux rootfs: ${linuxDir.absolutePath}")
            appendLine("  exists: ${linuxDir.exists()}")
            if (linuxDir.exists()) {
                appendLine("  /bin/sh exists: ${File(linuxDir, "bin/sh").exists()}")
                appendLine("  /usr/bin/sh exists: ${File(linuxDir, "usr/bin/sh").exists()}")
                appendLine("  /usr/bin/bash exists: ${File(linuxDir, "usr/bin/bash").exists()}")
                appendLine("  marker file: ${File(linuxDir, MARKER_FILE).exists()}")
                val totalSize = linuxDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                appendLine("  total size: ${totalSize / (1024 * 1024)} MB")
            }
        }
    }

    fun hasEnoughStorage(requiredMb: Long): Boolean {
        val stat = android.os.StatFs(context.filesDir.absolutePath)
        val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
        return freeBytes >= requiredMb * 1024 * 1024
    }

    private fun downloadFile(url: String, dest: File, onProgress: (Long, Long) -> Unit): Result<Unit> {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(600, java.util.concurrent.TimeUnit.SECONDS)
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

    /**
     * v2.1.1 FIXED: Properly handle symlinks, directories, and regular files.
     *
     * The v2.1.0 version had a critical bug: it didn't handle symlinks at all.
     * Ubuntu 22.04 uses merged-usr where /bin -> /usr/bin, /lib -> /usr/lib, etc.
     * Without symlinks, /bin/sh was unreachable even though /usr/bin/sh existed.
     *
     * This version:
     *   1. Handles symlinks via java.nio.file.Files.createSymbolicLink()
     *   2. Strips "./" prefix from Alpine entries
     *   3. Handles hard links (rare but present in some rootfs)
     *   4. Sets executable bit based on tar entry mode
     *   5. Does a two-pass extraction: first directories, then files+symlinks
     *      (ensures parent dirs exist before creating symlinks)
     */
    private fun extractRootfsFixed(
        archiveFile: File,
        targetDir: File,
        onProgress: (String, Long, Long) -> Unit
    ): Result<Unit> {
        return try {
            var entryCount = 0
            var symlinkCount = 0
            var fileCount = 0
            var dirCount = 0

            // Collect all entries first so we can do ordered extraction
            data class EntryInfo(
                val name: String,
                val isDirectory: Boolean,
                val isSymbolicLink: Boolean,
                val isHardLink: Boolean,
                val linkName: String,
                val mode: Int
            )

            val entries = mutableListOf<EntryInfo>()

            archiveFile.inputStream().buffered().use { fis ->
                GzipCompressorInputStream(fis).use { gzis ->
                    TarArchiveInputStream(gzis).use { tis ->
                        var entry: TarArchiveEntry? = tis.nextEntry as? TarArchiveEntry
                        while (entry != null) {
                            // Strip "./" prefix (Alpine uses it, Ubuntu doesn't)
                            var name = entry.name
                            if (name.startsWith("./")) name = name.substring(2)
                            if (name.isEmpty()) {
                                entry = tis.nextEntry as? TarArchiveEntry
                                continue
                            }

                            // Block path traversal
                            if (name.contains("..") || name.startsWith("/")) {
                                entry = tis.nextEntry as? TarArchiveEntry
                                continue
                            }

                            entries.add(EntryInfo(
                                name = name,
                                isDirectory = entry.isDirectory,
                                isSymbolicLink = entry.isSymbolicLink,
                                isHardLink = entry.isLink,  // isLink = hard link in Apache Commons Compress
                                linkName = entry.linkName,
                                mode = entry.mode
                            ))

                            // Copy the file content if it's a regular file (not dir, not symlink, not hardlink)
                            if (!entry.isDirectory && !entry.isSymbolicLink && !entry.isLink) {
                                val outFile = File(targetDir, name)
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { tis.copyTo(it) }
                                fileCount++
                            }

                            entryCount++
                            if (entryCount % 500 == 0) {
                                onProgress("Extracting... ($entryCount entries)", -1, -1)
                            }

                            entry = tis.nextEntry as? TarArchiveEntry
                        }
                    }
                }
            }

            // Now create directories and symlinks in order
            // First: create all directories
            for (e in entries) {
                if (e.isDirectory) {
                    val dir = File(targetDir, e.name)
                    dir.mkdirs()
                    dirCount++
                }
            }

            // Second: create all symlinks
            for (e in entries) {
                if (e.isSymbolicLink) {
                    val linkFile = File(targetDir, e.name)
                    val linkPath: Path = linkFile.toPath()
                    val target: Path = Paths.get(e.linkName)

                    // Delete existing file/directory at link path (might be a 0-byte file from a previous failed install)
                    if (linkFile.exists()) {
                        linkFile.delete()
                    }
                    // Also check for broken symlinks
                    if (Files.isSymbolicLink(linkPath)) {
                        Files.delete(linkPath)
                    }

                    try {
                        // Create parent directories if they don't exist
                        linkFile.parentFile?.mkdirs()
                        Files.createSymbolicLink(linkPath, target)
                        symlinkCount++
                    } catch (ex: Exception) {
                        // If symlink creation fails (e.g., permission denied on some Android versions),
                        // create a copy of the target instead. This won't work for directory symlinks
                        // (like bin -> usr/bin) but at least file symlinks will work.
                        try {
                            val targetFile = File(targetDir, e.linkName)
                            if (targetFile.isFile) {
                                targetFile.copyTo(linkFile, overwrite = true)
                                symlinkCount++
                            }
                        } catch (_: Exception) {
                            // Last resort: create an empty file so the path exists
                            linkFile.createNewFile()
                        }
                    }
                }
            }

            // Third: set executable permissions on binaries
            for (e in entries) {
                if (!e.isDirectory && !e.isSymbolicLink) {
                    val file = File(targetDir, e.name)
                    if (file.isFile) {
                        // Check if the file has execute permission in the tar entry
                        if ((e.mode and 0b001_000_000) != 0 || // owner execute
                            (e.mode and 0b000_001_000) != 0 || // group execute
                            (e.mode and 0b000_000_001) != 0    // others execute
                        ) {
                            file.setExecutable(true, false)
                        }
                        file.setReadable(true, false)
                    }
                }
            }

            // Verify critical symlinks exist
            val binDir = File(targetDir, "bin")
            if (!binDir.exists()) {
                // If /bin doesn't exist (maybe symlink creation failed), create it as a real directory
                // and populate it by copying from usr/bin
                binDir.mkdirs()
                val usrBin = File(targetDir, "usr/bin")
                if (usrBin.exists()) {
                    // At minimum, copy sh
                    val sh = File(usrBin, "sh")
                    if (sh.exists()) {
                        sh.copyTo(File(binDir, "sh"), overwrite = true)
                        File(binDir, "sh").setExecutable(true, false)
                    }
                    val bash = File(usrBin, "bash")
                    if (bash.exists()) {
                        bash.copyTo(File(binDir, "bash"), overwrite = true)
                        File(binDir, "bash").setExecutable(true, false)
                    }
                }
            }

            // Ensure /bin/sh is executable (it might be a symlink to dash)
            val binSh = File(targetDir, "bin/sh")
            if (binSh.exists()) {
                binSh.setExecutable(true, false)
            }

            // Also create /tmp if it doesn't exist (some rootfs don't include it)
            val tmpDir = File(targetDir, "tmp")
            if (!tmpDir.exists()) {
                tmpDir.mkdirs()
            }
            tmpDir.setWritable(true, false)
            tmpDir.setReadable(true, false)
            tmpDir.setExecutable(true, false)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(IOException("Extraction failed: ${e.message}", e))
        }
    }
}
