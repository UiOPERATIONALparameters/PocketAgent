package com.pocketagent.sandbox

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages proot-distro installations of full Linux distributions (Debian, Ubuntu, Arch, etc.)
 * inside the app's private storage.
 *
 * proot-distro uses proot (user-space chroot via ptrace) to run a complete Linux rootfs
 * without root access. This gives the agent access to apt/dpkg/yum and any package
 * (ffmpeg, ImageMagick, LaTeX, Node LTS, build-essential, etc.).
 *
 * Layout:
 *   /data/data/com.pocketagent/files/
 *     ├── usr/                  (Termux bootstrap — bash, proot, proot-distro)
 *     └── proot-distro/         (installed distros)
 *       └── debian/             (rootfs for Debian)
 *
 * The agent can:
 *   - Install:   proot-distro install debian
 *   - Run:       proot-distro login debian -- <command>
 *   - List:      proot-distro list
 *   - Remove:    proot-distro remove debian
 */

/** Metadata for a supported Linux distro. */
data class DistroInfo(
    val name: String,
    val displayName: String,
    val urlTemplate: String,  // %s = abi
    val approximateSizeMb: Int
)

@Singleton
class ProotDistroManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bootstrapInstaller: BootstrapInstaller
) {
    companion object {
        const val DISTRO_DEBIAN = "debian"
        const val DISTRO_UBUNTU = "ubuntu"
        const val DISTRO_ARCH = "archlinux"
        const val DISTRO_ALPINE = "alpine"

        // Distros that work well on all Android ABIs
        val SUPPORTED_DISTROS = listOf(DISTRO_DEBIAN, DISTRO_UBUNTU, DISTRO_ARCH, DISTRO_ALPINE)

        val DISTRO_INFO = mapOf(
            DISTRO_DEBIAN to DistroInfo(
                name = "debian",
                displayName = "Debian (stable)",
                urlTemplate = "https://github.com/termux/proot-distro/releases/latest/download/debian-rootfs-%s.tar.xz",
                approximateSizeMb = 80
            ),
            DISTRO_UBUNTU to DistroInfo(
                name = "ubuntu",
                displayName = "Ubuntu (22.04 LTS)",
                urlTemplate = "https://github.com/termux/proot-distro/releases/latest/download/ubuntu-rootfs-%s.tar.xz",
                approximateSizeMb = 95
            ),
            DISTRO_ARCH to DistroInfo(
                name = "archlinux",
                displayName = "Arch Linux (rolling)",
                urlTemplate = "https://github.com/termux/proot-distro/releases/latest/download/archlinux-rootfs-%s.tar.xz",
                approximateSizeMb = 150
            ),
            DISTRO_ALPINE to DistroInfo(
                name = "alpine",
                displayName = "Alpine Linux (minimal)",
                urlTemplate = "https://github.com/termux/proot-distro/releases/latest/download/alpine-rootfs-%s.tar.xz",
                approximateSizeMb = 30
            )
        )
    }

    val installRoot: File = File(context.filesDir, "proot-distro")

    /** Directory for a specific distro's rootfs. */
    fun distroDir(distro: String): File = File(installRoot, distro)

    /** True if the distro has been installed (rootfs exists and has /bin/sh). */
    fun isInstalled(distro: String): Boolean {
        val dir = distroDir(distro)
        return dir.exists() && File(dir, "bin/sh").exists()
    }

    /** List of installed distros. */
    fun installedDistros(): List<String> {
        if (!installRoot.exists()) return emptyList()
        return installRoot.listFiles { f -> f.isDirectory && File(f, "bin/sh").exists() }
            ?.map { it.name }
            ?: emptyList()
    }

    /**
     * Install a distro. Requires the Termux bootstrap to be installed first
     * (proot-distro is part of the bootstrap).
     *
     * This is a long-running operation (~50-200MB download + extraction).
     */
    suspend fun install(
        distro: String,
        onProgress: (String, Long, Long) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!bootstrapInstaller.isInstalled()) {
            return@withContext Result.failure(IllegalStateException("Termux bootstrap must be installed first"))
        }
        if (distro !in SUPPORTED_DISTROS) {
            return@withContext Result.failure(IllegalArgumentException("Unsupported distro: $distro"))
        }
        if (isInstalled(distro)) {
            return@withContext Result.failure(IllegalStateException("$distro is already installed"))
        }

        try {
            val abi = detectAbiForDistro()
            val distroInfo = DISTRO_INFO[distro] ?: return@withContext Result.failure(IllegalArgumentException("Unknown distro: $distro"))
            val url = distroInfo.urlTemplate.format(abi)

            installRoot.mkdirs()
            val targetDir = distroDir(distro)
            targetDir.mkdirs()

            // Download the rootfs zip
            val zipFile = File(context.cacheDir, "${distro}-rootfs-${abi}.zip")
            onProgress("Downloading $distro ($abi)...", 0, -1)
            val downloadResult = downloadWithProgress(url, zipFile) { downloaded, total ->
                onProgress("Downloading $distro...", downloaded, total)
            }
            if (downloadResult.isFailure) {
                return@withContext Result.failure(IOException("Download failed: ${downloadResult.exceptionOrNull()?.message}"))
            }

            // Verify minimum size (avoid partial downloads)
            if (!zipFile.exists() || zipFile.length() < 10_000_000) {
                zipFile.delete()
                return@withContext Result.failure(IOException("Downloaded rootfs is too small — likely a partial download"))
            }

            // Extract
            onProgress("Extracting $distro...", -1, -1)
            extractRootfs(zipFile, targetDir)

            // Create /tmp symlink inside the rootfs (proot makes this writable)
            val tmpDir = File(targetDir, "tmp")
            if (!tmpDir.exists()) tmpDir.mkdirs()

            // Set up resolv.conf so DNS works inside proot
            val etcDir = File(targetDir, "etc")
            etcDir.mkdirs()
            val resolvConf = File(etcDir, "resolv.conf")
            resolvConf.writeText("""
                nameserver 8.8.8.8
                nameserver 8.8.4.4
                nameserver 1.1.1.1
            """.trimIndent())

            // Clean up zip
            zipFile.delete()

            onProgress("Done", 1, 1)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Remove an installed distro. Frees disk space. */
    suspend fun uninstall(distro: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val dir = distroDir(distro)
            if (dir.exists()) dir.deleteRecursively()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Run a command inside a distro via proot-distro login.
     * Returns the command to run (caller executes via ShellExecutor).
     */
    fun loginCommand(distro: String, command: String, workingDir: String? = null): String {
        val prootDistroBin = File(bootstrapInstaller.binDir, "proot-distro")
        val parts = mutableListOf(
            prootDistroBin.absolutePath,
            "login",
            distro
        )
        if (workingDir != null) {
            parts.add("--termux-home")
            parts.add("--shared-tmp")
            parts.add("--cwd")
            parts.add(workingDir)
        } else {
            parts.add("--termux-home")
            parts.add("--shared-tmp")
        }
        parts.add("--")
        parts.add("/bin/sh")
        parts.add("-c")
        parts.add(command)
        return parts.joinToString(" ") { escapeShellArg(it) }
    }

    /** Detect the device ABI mapped to proot-distro's architecture naming. */
    private fun detectAbiForDistro(): String {
        val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        return when (primaryAbi) {
            "arm64-v8a" -> "aarch64"
            "armeabi-v7a", "armeabi" -> "arm"
            "x86_64" -> "x86_64"
            "x86" -> "i686"
            else -> "aarch64"
        }
    }

    private fun downloadWithProgress(url: String, dest: File, onProgress: (Long, Long) -> Unit): Result<Unit> {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val request = Request.Builder().url(url)
                .header("User-Agent", "PocketAgent/2.0")
                .get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(IOException("HTTP ${response.code}"))
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

    private fun extractRootfs(zipFile: File, targetDir: File) {
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val entryPath = entry.name
                // Block path traversal
                if (entryPath.contains("..")) {
                    zis.closeEntry()
                    entry = zis.nextEntry
                    continue
                }
                // Strip leading directory (proot-distro zips have a top-level dir like "debian-rootfs/")
                val relativePath = if (entryPath.contains("/")) {
                    entryPath.substringAfter("/", "")
                } else {
                    entryPath
                }
                if (relativePath.isEmpty()) {
                    zis.closeEntry()
                    entry = zis.nextEntry
                    continue
                }
                val outFile = File(targetDir, relativePath)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { zis.copyTo(it) }
                    // Preserve executable bit for binaries
                    if (relativePath.startsWith("bin/") || relativePath.startsWith("usr/bin/") ||
                        relativePath.startsWith("sbin/") || relativePath.startsWith("usr/sbin/")) {
                        outFile.setExecutable(true, true)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun escapeShellArg(s: String): String {
        return "'" + s.replace("'", "'\\''") + "'"
    }
}
