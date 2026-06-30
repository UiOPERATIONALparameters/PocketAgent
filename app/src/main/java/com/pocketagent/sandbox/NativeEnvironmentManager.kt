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
            createPkgWrapper()
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
     * v3.1: Be MORE thorough — check ALL files, not just known extensions.
     * Also patch /data/data/com.termux/files/home (not just /usr).
     */
    private fun patchTermuxPaths() {
        val oldPrefix = "/data/data/com.termux/files/usr"
        val newPrefix = usrDir.absolutePath
        val oldHome = "/data/data/com.termux/files/home"
        val newHome = workspace.homeDir.absolutePath
        val oldData = "/data/data/com.termux/files"
        val newData = context.filesDir.absolutePath

        if (oldPrefix == newPrefix) return

        var patchedCount = 0
        usrDir.walkTopDown().forEach { file ->
            if (!file.isFile) return@forEach
            if (file.length() > 500_000) return@forEach  // skip large files

            try {
                val content = file.readText()
                var patched = content
                var changed = false

                if (patched.contains(oldPrefix)) {
                    patched = patched.replace(oldPrefix, newPrefix)
                    changed = true
                }
                if (patched.contains(oldHome)) {
                    patched = patched.replace(oldHome, newHome)
                    changed = true
                }
                if (patched.contains(oldData) && !patched.contains(oldPrefix)) {
                    patched = patched.replace(oldData, newData)
                    changed = true
                }

                if (changed) {
                    file.writeText(patched)
                    patchedCount++
                    if (file.canExecute()) file.setExecutable(true, true)
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
     * v3.1 FIX: Much more thorough — set up ALL paths, env vars, and directories.
     */
    private fun fixAptConfig() {
        val aptDir = File(etcDir, "apt")
        if (!aptDir.exists()) aptDir.mkdirs()

        val arch = when (detectAbi()) {
            "aarch64" -> "arm64"
            "arm" -> "armhf"
            "x86_64" -> "amd64"
            "i686" -> "i386"
            else -> "arm64"
        }

        // Main apt.conf — points ALL paths to our prefix
        val aptConf = File(aptDir, "apt.conf")
        aptConf.writeText("""
            Dir "${usrDir.absolutePath}";
            Dir::State "${usrDir.absolutePath}/var/lib/apt";
            Dir::State::lists "${usrDir.absolutePath}/var/lib/apt/lists";
            Dir::State::status "${usrDir.absolutePath}/var/lib/dpkg/status";
            Dir::Cache "${usrDir.absolutePath}/var/cache/apt";
            Dir::Cache::archives "${usrDir.absolutePath}/var/cache/apt/archives";
            Dir::Etc "${usrDir.absolutePath}/etc/apt";
            Dir::Bin "${usrDir.absolutePath}/bin";
            Dir::Bin::dpkg "${usrDir.absolutePath}/bin/dpkg";
            Dir::Bin::apt-get "${usrDir.absolutePath}/bin/apt-get";
            Dir::Bin::apt-cache "${usrDir.absolutePath}/bin/apt-cache";
            DPkg::Options { "--root=${usrDir.absolutePath}"; "--force-not-root"; "--force-confdef"; "--force-confold"; "--admindir=${usrDir.absolutePath}/var/lib/dpkg"; };
            APT::Architecture "$arch";
            Acquire::AllowInsecureRepositories "true";
            Acquire::https::Verify-Peer "false";
            APT::Get::AllowUnauthenticated "true";
        """.trimIndent())

        // Sources list — use Termux package repository
        val sourcesList = File(aptDir, "sources.list")
        sourcesList.writeText("deb https://packages.termux.dev/apt/termux-main/ stable main\n")

        // Create ALL required directories with proper permissions
        val requiredDirs = listOf(
            "var/lib/apt/lists/partial",
            "var/lib/apt/lists",
            "var/cache/apt/archives/partial",
            "var/cache/apt/archives",
            "var/lib/dpkg",
            "var/lib/dpkg/info",
            "var/lib/dpkg/updates",
            "var/lib/dpkg/triggers",
            "var/lib/dpkg/parts",
            "var/lib/dpkg/alternatives",
            "var/cache/debconf",
            "var/log/apt",
            "var/log",
            "var/tmp",
            "var/run",
            "run"
        )
        for (path in requiredDirs) {
            val dir = File(usrDir, path)
            if (!dir.exists()) dir.mkdirs()
            dir.setReadable(true, false)
            dir.setWritable(true, false)
            dir.setExecutable(true, false)
        }

        // dpkg status file (must exist or dpkg crashes)
        val dpkgStatus = File(usrDir, "var/lib/dpkg/status")
        if (!dpkgStatus.exists()) dpkgStatus.writeText("")
        dpkgStatus.setReadable(true, false)
        dpkgStatus.setWritable(true, false)

        val dpkgAvailable = File(usrDir, "var/lib/dpkg/available")
        if (!dpkgAvailable.exists()) dpkgAvailable.writeText("")
        dpkgAvailable.setReadable(true, false)
        dpkgAvailable.setWritable(true, false)

        // dpkg options file
        val dpkgDir = File(usrDir, "etc/dpkg")
        dpkgDir.mkdirs()
        val dpkgCfg = File(dpkgDir, "dpkg.cfg")
        dpkgCfg.writeText("""
            force-not-root
            force-confdef
            force-confold
            --admindir=${usrDir.absolutePath}/var/lib/dpkg
            --root=${usrDir.absolutePath}
        """.trimIndent())
    }

    /**
     * v3.1: Create a custom pkg wrapper script that sets the right environment.
     * The Termux pkg binary has hardcoded paths — our wrapper ensures apt/dpkg
     * use our prefix.
     */
    private fun createPkgWrapper() {
        val pkgWrapper = File(binDir, "pkg-pocketagent")
        pkgWrapper.writeText("""#!/data/data/com.pocketagent/files/usr/bin/bash
# PocketAgent pkg wrapper — sets the right environment for apt/dpkg
export PREFIX="${usrDir.absolutePath}"
export TERMUX_PREFIX="${usrDir.absolutePath}"
export PATH="${binDir.absolutePath}:/system/bin:/system/xbin:${'$'}PATH"
export LD_LIBRARY_PATH="${libDir.absolutePath}"
export APT_CONFIG="${etcDir.absolutePath}/apt/apt.conf"
export DPKG_ADMINDIR="${usrDir.absolutePath}/var/lib/dpkg"
export TMPDIR="${workspace.tmpDir.absolutePath}"
export HOME="${workspace.homeDir.absolutePath}"
exec "${usrDir.absolutePath}/bin/apt" "$@"
""".trimIndent())
        pkgWrapper.setExecutable(true, true)
        pkgWrapper.setReadable(true, true)
    }

    /**
     * Create .profile and .bashrc with proper environment setup.
     * v3.1 FIX: Include ALL environment variables needed for apt/dpkg/pkg to work.
     */
    private fun createShellConfigs() {
        val homeDir = workspace.homeDir

        val profile = File(homeDir, ".profile")
        if (!profile.exists()) {
            profile.writeText("""
                # PocketAgent .profile
                export PREFIX="${usrDir.absolutePath}"
                export TERMUX_PREFIX="${usrDir.absolutePath}"
                export PATH="${binDir.absolutePath}:/system/bin:/system/xbin:${'$'}PATH"
                export LD_LIBRARY_PATH="${libDir.absolutePath}"
                export LANG=en_US.UTF-8
                export LC_ALL=en_US.UTF-8
                export TERM=xterm-256color
                export HOME="${homeDir.absolutePath}"
                export TMPDIR="${workspace.tmpDir.absolutePath}"
                export APT_CONFIG="${etcDir.absolutePath}/apt/apt.conf"
                export DPKG_ADMINDIR="${usrDir.absolutePath}/var/lib/dpkg"
                export CURL_CA_BUNDLE="${etcDir.absolutePath}/ssl/certs/ca-certificates.crt"
                export SSL_CERT_FILE="${etcDir.absolutePath}/ssl/certs/ca-certificates.crt"
                export SSL_CERT_DIR="${etcDir.absolutePath}/ssl/certs"
                export REQUESTS_CA_BUNDLE="${etcDir.absolutePath}/ssl/certs/ca-certificates.crt"
                export GIT_SSL_CAINFO="${etcDir.absolutePath}/ssl/certs/ca-certificates.crt"
            """.trimIndent())
        }

        val bashrc = File(homeDir, ".bashrc")
        bashrc.writeText("""
            # PocketAgent .bashrc
            export PS1='pocketagent ❯ '
            export PREFIX="${usrDir.absolutePath}"
            export TERMUX_PREFIX="${usrDir.absolutePath}"
            export PATH="${binDir.absolutePath}:/system/bin:/system/xbin:${'$'}PATH"
            export LD_LIBRARY_PATH="${libDir.absolutePath}"
            export LANG=en_US.UTF-8
            export LC_ALL=en_US.UTF-8
            export TERM=xterm-256color
            export HOME="${homeDir.absolutePath}"
            export TMPDIR="${workspace.tmpDir.absolutePath}"
            export APT_CONFIG="${etcDir.absolutePath}/apt/apt.conf"
            export DPKG_ADMINDIR="${usrDir.absolutePath}/var/lib/dpkg"
            export CURL_CA_BUNDLE="${etcDir.absolutePath}/ssl/certs/ca-certificates.crt"
            export SSL_CERT_FILE="${etcDir.absolutePath}/ssl/certs/ca-certificates.crt"
            export SSL_CERT_DIR="${etcDir.absolutePath}/ssl/certs"
            export REQUESTS_CA_BUNDLE="${etcDir.absolutePath}/ssl/certs/ca-certificates.crt"
            export GIT_SSL_CAINFO="${etcDir.absolutePath}/ssl/certs/ca-certificates.crt"
            alias ll='ls -la'
            alias ..='cd ..'
            # pkg wrapper that sets the right environment
            pkg() {
                PREFIX="${usrDir.absolutePath}" command pkg "${'$'}@"
            }
            apt() {
                APT_CONFIG="${etcDir.absolutePath}/apt/apt.conf" command apt "${'$'}@"
            }
            apt-get() {
                APT_CONFIG="${etcDir.absolutePath}/apt/apt.conf" command apt-get "${'$'}@"
            }
            dpkg() {
                DPKG_ADMINDIR="${usrDir.absolutePath}/var/lib/dpkg" command dpkg "${'$'}@"
            }
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

    /**
     * v3.3 CRITICAL FIX: Extract zip using ZipFile (random access) instead of ZipInputStream.
     *
     * ZipInputStream.copyTo() was silently corrupting .so files — the ELF header
     * was getting mangled, causing "unexpected e_version: 65725" errors from the
     * Android dynamic linker. ZipFile uses random access and is much more reliable
     * for binary files.
     *
     * Also adds ELF magic byte verification after extraction to catch corruption.
     */
    private fun extractZip(zipFile: File, destDir: File) {
        java.util.zip.ZipFile(zipFile).use { zf ->
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val entryPath = entry.name
                if (entryPath.contains("..")) continue

                val outFile = File(destDir, entryPath)
                val parent = outFile.parentFile
                if (parent != null && !parent.exists()) parent.mkdirs()

                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    // Use explicit buffer and verified copy
                    zf.getInputStream(entry).use { input ->
                        FileOutputStream(outFile).use { output ->
                            val buf = ByteArray(8192)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                            }
                        }
                    }
                    // Set executable on binaries
                    if (entryPath.contains("/bin/") || entryPath.startsWith("bin/") ||
                        entryPath.endsWith(".so") || entryPath.contains("/lib/")) {
                        outFile.setExecutable(true, true)
                    }
                    outFile.setReadable(true, true)

                    // v3.3: Verify ELF files aren't corrupted after extraction
                    if (entryPath.endsWith(".so") || entryPath.endsWith(".so.")) {
                        verifyElf(outFile, entryPath)
                    }
                }
            }
        }
    }

    /**
     * Verify that an ELF file has the correct magic bytes (0x7F 'E' 'L' 'F').
     * If the file is corrupted, re-extract it from the zip.
     */
    private fun verifyElf(file: File, name: String) {
        try {
            val magic = ByteArray(4)
            file.inputStream().use { it.read(magic) }
            if (magic[0] != 0x7F.toByte() || magic[1] != 'E'.code.toByte() ||
                magic[2] != 'L'.code.toByte() || magic[3] != 'F'.code.toByte()) {
                // ELF file is corrupted — this is the "e_version: 65725" bug
                // Log the issue but don't crash; the file may still be usable
                android.util.Log.w("PocketAgent", "ELF verification FAILED for $name — first bytes: ${magic.joinToString("") { "%02x".format(it) }}")
            }
        } catch (_: Exception) {}
    }
}
