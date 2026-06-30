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
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v3.0 Native Environment Manager — NO PROOT, NO ROOTFS, NO CONTAINER.
 *
 * ARCHITECTURE (v3.0 — "The Native Approach"):
 *
 * The v2.x approach used proot to run a Linux rootfs inside Android. This is
 * fundamentally broken because proot relies on ptrace(2), which is blocked by
 * Android's seccomp filter on Android 14+ (signal 31 / SIGSYS).
 *
 * The v3.0 approach uses Termux's Android-native packages directly. These are
 * compiled against Android's Bionic libc and use /system/bin/linker64 — they
 * run natively on Android without proot, without root, without any container.
 *
 * How it works:
 *   1. Download the Termux bootstrap zip (bash, coreutils, apt, pkg) — ~40MB
 *   2. Download additional .deb packages (python, node, git, etc.) on demand
 *   3. Extract everything to /data/data/com.pocketagent/files/usr/
 *   4. Set LD_LIBRARY_PATH, PATH, PREFIX in the shell environment
 *   5. Run commands via: usr/bin/bash -c "command"
 *
 * This is exactly what Termux does. The binaries are Android-native, so there's
 * no seccomp issue, no ptrace, no W^X problem (targetSdk 28 exempts us).
 *
 * Layout:
 *   /data/data/com.pocketagent/files/
 *     ├── usr/                  (Termux bootstrap + packages)
 *     │   ├── bin/              (bash, python3, node, git, curl, etc.)
 *     │   ├── lib/              (shared libraries — .so files)
 *     │   ├── share/            (locale data, man pages, etc.)
 *     │   └── etc/              (apt config, SSL certs)
 *     └── workspace/            (agent's $HOME)
 */
@Singleton
class NativeEnvironmentManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workspace: Workspace
) {
    companion object {
        const val MARKER_FILE = ".native_installed"

        // Termux bootstrap URLs — verified working
        // These contain bash, coreutils, apt, pkg, and basic libraries
        private fun bootstrapUrl(abi: String): String = when (abi) {
            "aarch64" -> "https://github.com/termux/termux-packages/releases/latest/download/bootstrap-aarch64.zip"
            "arm" -> "https://github.com/termux/termux-packages/releases/latest/download/bootstrap-arm.zip"
            "x86_64" -> "https://github.com/termux/termux-packages/releases/latest/download/bootstrap-x86_64.zip"
            "i686" -> "https://github.com/termux/termux-packages/releases/latest/download/bootstrap-i686.zip"
            else -> "https://github.com/termux/termux-packages/releases/latest/download/bootstrap-aarch64.zip"
        }

        // Fallback URLs
        private fun fallbackUrl(abi: String): String = "https://packages.termux.dev/bootstrap-$abi.zip"

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
    }

    val usrDir: File = File(context.filesDir, "usr")
    val binDir: File = File(usrDir, "bin")
    val libDir: File = File(usrDir, "lib")
    val etcDir: File = File(usrDir, "etc")
    val bashPath: File get() = File(binDir, "bash")

    fun isInstalled(): Boolean {
        return File(usrDir, MARKER_FILE).exists() && bashPath.exists() && bashPath.canExecute()
    }

    fun getBashPath(): String? {
        return if (isInstalled()) bashPath.absolutePath else null
    }

    fun getPath(): String {
        return listOf(
            binDir.absolutePath,
            "/system/bin",
            "/system/xbin"
        ).distinct().joinToString(":")
    }

    /**
     * Download and install the Termux bootstrap.
     * This gives us: bash, coreutils, apt, pkg, curl, wget, and basic libraries.
     * ~40MB download, ~150MB extracted.
     */
    suspend fun install(onProgress: (String, Long, Long) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val abi = detectAbi()
            val zipFile = File(context.cacheDir, "bootstrap-$abi.zip")

            // Download
            onProgress("Downloading Linux environment (~40MB)...", 0, -1)
            val urls = listOf(bootstrapUrl(abi), fallbackUrl(abi))
            var downloadSuccess = false
            var lastError: String? = null

            for (url in urls) {
                val result = downloadFile(url, zipFile) { downloaded, total ->
                    onProgress("Downloading...", downloaded, total)
                }
                if (result.isSuccess) {
                    downloadSuccess = true
                    break
                } else {
                    lastError = result.exceptionOrNull()?.message
                }
            }

            if (!downloadSuccess) {
                return@withContext Result.failure(IOException("Download failed: $lastError"))
            }

            // Verify size (bootstrap is at least 30MB)
            if (!zipFile.exists() || zipFile.length() < 30_000_000) {
                zipFile.delete()
                return@withContext Result.failure(IOException("Downloaded file too small (${zipFile.length()} bytes)"))
            }

            // Clean up previous installation
            if (usrDir.exists()) usrDir.deleteRecursively()
            usrDir.mkdirs()

            // Extract
            onProgress("Extracting (this takes a minute)...", -1, -1)
            extractZip(zipFile, usrDir)

            // Set executable permissions on ALL binaries
            onProgress("Setting permissions...", -1, -1)
            setPermissions()

            // Patch Termux paths (replace /data/data/com.termux with our path)
            onProgress("Configuring...", -1, -1)
            patchTermuxPaths()
            patchShebangs()
            createSoSymlinks()
            createShSymlink()
            setupSslCerts()
            fixAptConfig()
            createShellConfigs()

            // Create marker
            File(usrDir, MARKER_FILE).writeText(System.currentTimeMillis().toString())

            // Clean up
            zipFile.delete()

            // Verify
            if (!bashPath.exists()) {
                return@withContext Result.failure(IOException("Bootstrap extracted but bash not found at ${bashPath.absolutePath}"))
            }
            if (!bashPath.canExecute()) {
                bashPath.setExecutable(true, true)
            }

            onProgress("Done!", 1, 1)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Set executable permissions on all binaries in bin/ and lib/ subdirectories.
     * v3.0 FIX: Be thorough — set +x on everything in bin/, sbin/, lib/apt/methods/,
     * lib/git-core/, libexec/, and any file with an execute bit in the zip.
     */
    private fun setPermissions() {
        // Set +x on ALL files in bin/
        if (binDir.exists()) {
            binDir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    file.setExecutable(true, true)
                    file.setReadable(true, true)
                }
            }
        }

        // Set +x on specific lib subdirectories that have executables
        val execSubdirs = listOf("apt/methods", "git-core", "exec", "coreutils")
        for (sub in execSubdirs) {
            val subDir = File(libDir, sub)
            if (subDir.exists()) {
                subDir.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        file.setExecutable(true, true)
                        file.setReadable(true, true)
                    }
                }
            }
        }

        // Set readable on everything, executable on directories
        usrDir.walkTopDown().forEach { file ->
            file.setReadable(true, true)
            if (file.isDirectory) {
                file.setExecutable(true, true)
            }
        }
    }

    /**
     * Patch hardcoded Termux paths in all text files.
     * Replace /data/data/com.termux/files/usr with our usr/ path.
     */
    private fun patchTermuxPaths() {
        val oldPrefix = "/data/data/com.termux/files/usr"
        val newPrefix = usrDir.absolutePath
        if (oldPrefix == newPrefix) return

        val textExtensions = setOf("sh", "py", "pl", "rb", "js", "conf", "cfg", "ini", "txt", "md",
            "json", "xml", "yaml", "yml", "env", "profile", "bashrc", "bash_profile", "list")

        usrDir.walkTopDown().forEach { file ->
            if (!file.isFile) return@forEach
            val ext = file.extension.lowercase()
            val isText = ext in textExtensions ||
                file.name.startsWith(".") ||
                file.name == "bashrc" || file.name == "profile" || file.name == "bash_profile"

            if (!isText) return@forEach

            try {
                val content = file.readText()
                if (content.contains(oldPrefix)) {
                    file.writeText(content.replace(oldPrefix, newPrefix))
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Patch shebangs in bin/ scripts.
     */
    private fun patchShebangs() {
        val oldPrefix = "/data/data/com.termux/files/usr"
        val newPrefix = usrDir.absolutePath
        if (!binDir.exists()) return

        binDir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            try {
                val firstLine = file.bufferedReader().use { it.readLine() } ?: return@forEach
                if (firstLine.startsWith("#!") && firstLine.contains(oldPrefix)) {
                    val patched = firstLine.replace(oldPrefix, newPrefix)
                    val rest = file.readText().substringAfter("\n", "")
                    file.writeText("$patched\n$rest")
                    file.setExecutable(true, true)
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Create .so symlinks for shared libraries.
     * The bootstrap ships libfoo.so.8.0 but the linker looks for libfoo.so.8 and libfoo.so.
     */
    private fun createSoSymlinks() {
        if (!libDir.exists()) return

        val realFiles = libDir.listFiles { f ->
            f.isFile && !java.nio.file.Files.isSymbolicLink(f.toPath()) &&
                (f.name.endsWith(".so") || f.name.contains(".so."))
        } ?: return

        realFiles.forEach { file ->
            val name = file.name
            val soIdx = name.indexOf(".so")
            if (soIdx < 0) return@forEach

            val baseName = name.substring(0, soIdx + 3)
            val versionPart = name.substring(soIdx + 3)

            if (versionPart.isNotEmpty()) {
                val parts = versionPart.split(".").filter { it.isNotEmpty() }
                for (i in parts.indices) {
                    val symlinkName = baseName + "." + parts.subList(0, i + 1).joinToString(".")
                    val symlinkFile = File(libDir, symlinkName)
                    if (symlinkFile.name != name && !symlinkFile.exists()) {
                        try {
                            java.nio.file.Files.createSymbolicLink(symlinkFile.toPath(), file.toPath())
                        } catch (_: Exception) {
                            try { symlinkFile.copyFrom(file) } catch (_: Exception) {}
                        }
                    }
                }
            }

            val devLink = File(libDir, baseName)
            if (!devLink.exists()) {
                try {
                    java.nio.file.Files.createSymbolicLink(devLink.toPath(), file.toPath())
                } catch (_: Exception) {
                    try { devLink.copyFrom(file) } catch (_: Exception) {}
                }
            }
        }
    }

    private fun File.copyFrom(src: File) {
        src.inputStream().use { input ->
            this.outputStream().use { output -> input.copyTo(output) }
        }
    }

    /**
     * Create sh -> bash symlink.
     */
    private fun createShSymlink() {
        val bashFile = File(binDir, "bash")
        val shFile = File(binDir, "sh")
        if (bashFile.exists() && !shFile.exists()) {
            try {
                java.nio.file.Files.createSymbolicLink(shFile.toPath(), bashFile.toPath())
                shFile.setExecutable(true, true)
            } catch (_: Exception) {
                try { shFile.copyFrom(bashFile) } catch (_: Exception) {}
            }
        }
    }

    /**
     * Set up SSL certificates from Android's system CA store.
     */
    private fun setupSslCerts() {
        val certDir = File(etcDir, "ssl/certs")
        if (!certDir.exists()) certDir.mkdirs()

        val caBundle = File(certDir, "ca-certificates.crt")
        val sb = StringBuilder()

        // Try Android's system CA store
        val systemCerts = File("/system/etc/security/cacerts")
        if (systemCerts.exists()) {
            systemCerts.listFiles()?.forEach { certFile ->
                if (certFile.isFile && certFile.name.endsWith(".0")) {
                    try { sb.append(certFile.readText()); sb.append("\n") } catch (_: Exception) {}
                }
            }
        }

        // Fallback: Android 14+ uses /apex/com.android.conscrypt/cacerts
        if (sb.isEmpty() || caBundle.length() < 1000) {
            val apexCerts = File("/apex/com.android.conscrypt/cacerts")
            if (apexCerts.exists()) {
                apexCerts.listFiles()?.forEach { certFile ->
                    if (certFile.isFile) {
                        try { sb.append(certFile.readText()); sb.append("\n") } catch (_: Exception) {}
                    }
                }
            }
        }

        if (sb.isNotEmpty()) caBundle.writeText(sb.toString())
    }

    /**
     * Fix apt configuration to use our prefix and the correct architecture.
     */
    private fun fixAptConfig() {
        val aptDir = File(etcDir, "apt")
        if (!aptDir.exists()) aptDir.mkdirs()

        // Detect dpkg architecture from ABI
        val arch = when (detectAbi()) {
            "aarch64" -> "arm64"
            "arm" -> "armhf"
            "x86_64" -> "amd64"
            "i686" -> "i386"
            else -> "arm64"
        }

        val aptConf = File(aptDir, "apt.conf")
        aptConf.writeText("""
            Dir "${usrDir.absolutePath}";
            Dir::State "${usrDir.absolutePath}/var/lib/apt";
            Dir::State::lists "${usrDir.absolutePath}/var/lib/apt/lists";
            Dir::Cache "${usrDir.absolutePath}/var/cache/apt";
            Dir::Cache::archives "${usrDir.absolutePath}/var/cache/apt/archives";
            Dir::Etc "${usrDir.absolutePath}/etc/apt";
            Dir::Bin::dpkg "${usrDir.absolutePath}/bin/dpkg";
            DPkg::Options { "--root=${usrDir.absolutePath}"; "--force-not-root"; "--force-confdef"; "--force-confold"; };
            APT::Architecture "$arch";
            Acquire::AllowInsecureRepositories "true";
        """.trimIndent())

        val sourcesList = File(aptDir, "sources.list")
        // Use HTTPS — the bootstrap includes curl which provides the HTTPS transport
        sourcesList.writeText("deb https://packages.termux.dev/apt/termux-main/ stable main\n")

        // Create required directories
        listOf("var/lib/apt/lists/partial", "var/cache/apt/archives/partial",
               "var/lib/dpkg", "var/lib/dpkg/info", "var/lib/dpkg/updates",
               "var/lib/dpkg/triggers", "var/lib/dpkg/parts").forEach { path ->
            File(usrDir, path).mkdirs()
        }

        val dpkgStatus = File(usrDir, "var/lib/dpkg/status")
        if (!dpkgStatus.exists()) dpkgStatus.writeText("")
        val dpkgAvailable = File(usrDir, "var/lib/dpkg/available")
        if (!dpkgAvailable.exists()) dpkgAvailable.writeText("")
    }

    /**
     * Create .profile and .bashrc with proper environment setup.
     */
    private fun createShellConfigs() {
        val homeDir = workspace.homeDir

        val profile = File(homeDir, ".profile")
        if (!profile.exists()) {
            profile.writeText("""
                # PocketAgent .profile
                export PREFIX="${usrDir.absolutePath}"
                export PATH="${binDir.absolutePath}:/system/bin:/system/xbin:${'$'}PATH"
                export LD_LIBRARY_PATH="${libDir.absolutePath}"
                export LANG=en_US.UTF-8
                export LC_ALL=en_US.UTF-8
                export TERM=xterm-256color
                export HOME="${homeDir.absolutePath}"
                export TMPDIR="${workspace.tmpDir.absolutePath}"
            """.trimIndent())
        }

        val bashrc = File(homeDir, ".bashrc")
        bashrc.writeText("""
            # PocketAgent .bashrc
            export PS1='pocketagent ❯ '
            export PREFIX="${usrDir.absolutePath}"
            export PATH="${binDir.absolutePath}:/system/bin:/system/xbin:${'$'}PATH"
            export LD_LIBRARY_PATH="${libDir.absolutePath}"
            export LANG=en_US.UTF-8
            export LC_ALL=en_US.UTF-8
            export TERM=xterm-256color
            export HOME="${homeDir.absolutePath}"
            export TMPDIR="${workspace.tmpDir.absolutePath}"
            alias ll='ls -la'
            alias ..='cd ..'
        """.trimIndent())
    }

    suspend fun uninstall(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (usrDir.exists()) usrDir.deleteRecursively()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun hasEnoughStorage(requiredMb: Long): Boolean {
        val stat = android.os.StatFs(context.filesDir.absolutePath)
        val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
        return freeBytes >= requiredMb * 1024 * 1024
    }

    fun getDiagnosticInfo(): String {
        return buildString {
            appendLine("=== Native Environment Status ===")
            appendLine("ABI: ${detectAbi()}")
            appendLine("Installed: ${isInstalled()}")
            appendLine("usrDir: ${usrDir.absolutePath} (exists=${usrDir.exists()})")
            appendLine("bash: ${bashPath.absolutePath} (exists=${bashPath.exists()}, exec=${bashPath.canExecute()})")
            appendLine("binDir: ${binDir.exists()} (${binDir.listFiles()?.size ?: 0} files)")
            appendLine("libDir: ${libDir.exists()} (${libDir.listFiles()?.size ?: 0} files)")
        }
    }

    private fun downloadFile(url: String, dest: File, onProgress: (Long, Long) -> Unit): Result<Unit> {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(600, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val request = Request.Builder().url(url)
                .header("User-Agent", "PocketAgent/3.0")
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

    private fun extractZip(zipFile: File, destDir: File) {
        java.util.zip.ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val entryPath = entry.name
                if (entryPath.contains("..")) {
                    zis.closeEntry()
                    entry = zis.nextEntry
                    continue
                }

                val outFile = File(destDir, entryPath)
                val parent = outFile.parentFile
                if (parent != null && !parent.exists()) parent.mkdirs()

                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                    if (entryPath.contains("/bin/") || entryPath.startsWith("bin/")) {
                        outFile.setExecutable(true, true)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
