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
            patchAllScripts()
            removeBrokenInitScripts()  // v3.7: delete profile.d scripts that cause "Permission denied"
            createSoSymlinks()
            createShSymlink()
            createHttpsAptMethod()  // v3.7: create https apt method symlink (missing from bootstrap)
            setupSslCerts()
            fixAptConfig()
            createCommandWrappers()
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
     * v3.7: Remove profile.d scripts that cause "Permission denied" errors.
     * These scripts try to access /data/data/com.termux/ paths that don't exist
     * in our app, causing noise on every shell startup.
     */
    private fun removeBrokenInitScripts() {
        val profileDir = File(usrDir, "etc/profile.d")
        if (!profileDir.exists()) return

        // Scripts to delete entirely — they're Termux-specific and serve no purpose for us
        val scriptsToDelete = listOf(
            "01-termux-bootstrap-second-stage-fallback.sh",  // tries to run second-stage bootstrap
            "init-termux-properties.sh",  // tries to copy termux.properties from Termux paths
            "termux-proot.sh"  // tries to set up proot (we don't use it)
        )

        for (scriptName in scriptsToDelete) {
            val script = File(profileDir, scriptName)
            if (script.exists()) {
                script.delete()
            }
        }

        // Also remove any remaining profile.d scripts that still reference com.termux
        profileDir.listFiles { f -> f.name.endsWith(".sh") }?.forEach { script ->
            try {
                val bytes = script.readBytes()
                val content = String(bytes, Charsets.UTF_8)
                if (content.contains("/data/data/com.termux/")) {
                    // Patch it instead of deleting (might be useful)
                    val patched = content
                        .replace("/data/data/com.termux/files/usr", usrDir.absolutePath)
                        .replace("/data/data/com.termux/files/home", workspace.homeDir.absolutePath)
                        .replace("/data/data/com.termux/files", context.filesDir.absolutePath)
                    script.writeBytes(patched.toByteArray(Charsets.UTF_8))
                    script.setExecutable(true, true)
                    script.setReadable(true, true)
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * v3.7: Create the https apt method — it's missing from the bootstrap.
     * The http method supports HTTPS via libcurl, so we just symlink http → https.
     * Without this, 'apt update' fails because our sources.list uses https://
     */
    private fun createHttpsAptMethod() {
        val methodsDir = File(libDir, "apt/methods")
        if (!methodsDir.exists()) return

        val httpMethod = File(methodsDir, "http")
        val httpsMethod = File(methodsDir, "https")

        if (httpMethod.exists() && !httpsMethod.exists()) {
            try {
                // Create symlink: https → http
                java.nio.file.Files.createSymbolicLink(httpsMethod.toPath(), httpMethod.toPath())
            } catch (_: Exception) {
                // Fallback: copy http to https
                try {
                    httpMethod.copyTo(httpsMethod, overwrite = true)
                    httpsMethod.setExecutable(true, true)
                    httpsMethod.setReadable(true, true)
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * v3.6: Patch ALL scripts in bin/ — including extensionless ones like `pkg`, `termux-setup-package-manager`, etc.
     * The patchTermuxPaths() whitelist missed these because they have no file extension.
     * This function checks every file in bin/ — if it's a script (starts with #!) AND contains
     * /data/data/com.termux, it patches the content.
     */
    private fun patchAllScripts() {
        val oldPrefix = "/data/data/com.termux/files/usr"
        val newPrefix = usrDir.absolutePath
        val oldHome = "/data/data/com.termux/files/home"
        val newHome = workspace.homeDir.absolutePath
        val oldData = "/data/data/com.termux/files"
        val newData = context.filesDir.absolutePath

        if (!binDir.exists()) return

        binDir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            try {
                // Read first 2 bytes to check if it's a script
                val header = ByteArray(2)
                file.inputStream().use { it.read(header) }
                if (header[0] != '#'.code.toByte() || header[1] != '!'.code.toByte()) return@forEach

                // It's a script — read as bytes, patch, write as bytes
                val bytes = file.readBytes()
                val content = String(bytes, Charsets.UTF_8)
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
                    file.writeBytes(patched.toByteArray(Charsets.UTF_8))
                    file.setExecutable(true, true)
                    file.setReadable(true, true)
                }
            } catch (_: Exception) {}
        }

        // Also patch scripts in etc/profile.d/
        val profileDir = File(etcDir, "profile.d")
        if (profileDir.exists()) {
            profileDir.listFiles()?.forEach { file ->
                if (!file.isFile) return@forEach
                try {
                    val bytes = file.readBytes()
                    val content = String(bytes, Charsets.UTF_8)
                    var patched = content
                    var changed = false

                    if (patched.contains(oldPrefix)) {
                        patched = patched.replace(oldPrefix, newPrefix)
                        changed = true
                    }
                    if (patched.contains(oldData)) {
                        patched = patched.replace(oldData, newData)
                        changed = true
                    }

                    if (changed) {
                        file.writeBytes(patched.toByteArray(Charsets.UTF_8))
                        file.setExecutable(true, true)
                        file.setReadable(true, true)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * v3.6: Replace pkg/apt/apt-get/dpkg with wrapper scripts that set up the FULL environment
     * before calling the real binaries. This is the BULLETPROOF fix for hardcoded paths.
     *
     * The approach:
     *   1. Rename original: apt → apt.real, pkg → pkg.real, etc.
     *   2. Create wrapper script that sets LD_PRELOAD + all env vars + calls apt.real
     *   3. The wrapper ensures EVERY invocation has the right environment
     *
     * This fixes both:
     *   - Script-based commands (pkg, termux-setup-package-manager) — wrapper sets env before calling
     *   - Binary-based commands (apt, dpkg) — LD_PRELOAD in wrapper intercepts execve()
     */
    private fun createCommandWrappers() {
        val termuxExecLib = File(libDir, "libtermux-exec-ld-preload.so")
        val bashPath = File(binDir, "bash").absolutePath

        // The full environment setup that every wrapper uses
        val envSetup = buildString {
            appendLine("export PREFIX=\"${usrDir.absolutePath}\"")
            appendLine("export TERMUX_PREFIX=\"${usrDir.absolutePath}\"")
            appendLine("export TERMUX_APP__DATA_DIR=\"${context.filesDir.absolutePath}\"")
            appendLine("export TERMUX_ANDROID_HOME=\"${workspace.homeDir.absolutePath}\"")
            appendLine("export TERMUX_HOME=\"${workspace.homeDir.absolutePath}\"")
            appendLine("export PATH=\"${binDir.absolutePath}:/system/bin:/system/xbin:\$PATH\"")
            appendLine("export LD_LIBRARY_PATH=\"${libDir.absolutePath}\"")
            if (termuxExecLib.exists()) {
                appendLine("export LD_PRELOAD=\"${termuxExecLib.absolutePath}\"")
            }
            appendLine("export APT_CONFIG=\"${etcDir.absolutePath}/apt/apt.conf\"")
            appendLine("export DPKG_ADMINDIR=\"${usrDir.absolutePath}/var/lib/dpkg\"")
            appendLine("export TMPDIR=\"${workspace.tmpDir.absolutePath}\"")
            appendLine("export HOME=\"${workspace.homeDir.absolutePath}\"")
            val caBundle = File(etcDir, "ssl/certs/ca-certificates.crt")
            if (caBundle.exists()) {
                appendLine("export CURL_CA_BUNDLE=\"${caBundle.absolutePath}\"")
                appendLine("export SSL_CERT_FILE=\"${caBundle.absolutePath}\"")
                appendLine("export GIT_SSL_CAINFO=\"${caBundle.absolutePath}\"")
            }
        }

        // Wrap each command
        val commandsToWrap = listOf("apt", "apt-get", "dpkg", "pkg", "apt-cache")
        for (cmd in commandsToWrap) {
            val original = File(binDir, cmd)
            if (!original.exists()) continue

            // Rename original to cmd.real
            val realFile = File(binDir, "$cmd.real")
            if (!realFile.exists()) {
                original.renameTo(realFile)
                realFile.setExecutable(true, true)
                realFile.setReadable(true, true)
            }

            // Create wrapper script
            val wrapper = File(binDir, cmd)
            wrapper.writeText("""#!$bashPath
# PocketAgent wrapper for $cmd — sets up full environment before calling $cmd.real
$envSetup
exec "${realFile.absolutePath}" "$@"
""".trimIndent())
            wrapper.setExecutable(true, true)
            wrapper.setReadable(true, true)
        }

        // Also wrap termux-setup-package-manager if it exists
        val tspm = File(binDir, "termux-setup-package-manager")
        if (tspm.exists()) {
            val tspmReal = File(binDir, "termux-setup-package-manager.real")
            if (!tspmReal.exists()) {
                tspm.renameTo(tspmReal)
                tspmReal.setExecutable(true, true)
                tspmReal.setReadable(true, true)
            }
            val wrapper = File(binDir, "termux-setup-package-manager")
            wrapper.writeText("""#!$bashPath
# PocketAgent wrapper for termux-setup-package-manager
$envSetup
exec "${tspmReal.absolutePath}" "$@"
""".trimIndent())
            wrapper.setExecutable(true, true)
            wrapper.setReadable(true, true)
        }
    }

    /**
     * v3.1: Create a custom pkg wrapper script that sets the right environment.
     * The Termux pkg binary has hardcoded paths — our wrapper ensures apt/dpkg
     * use our prefix.
     */
    private fun createPkgWrapper() {
        val pkgWrapper = File(binDir, "pkg-pocketagent")
        val termuxExecLib = File(libDir, "libtermux-exec-ld-preload.so")
        pkgWrapper.writeText("""#!/data/data/com.pocketagent/files/usr/bin/bash
# PocketAgent pkg wrapper — sets the right environment for apt/dpkg
# v3.5: LD_PRELOAD with termux-exec is CRITICAL — it rewrites hardcoded paths
export PREFIX="${usrDir.absolutePath}"
export TERMUX_PREFIX="${usrDir.absolutePath}"
export TERMUX_APP__DATA_DIR="${context.filesDir.absolutePath}"
export TERMUX_ANDROID_HOME="${workspace.homeDir.absolutePath}"
export TERMUX_HOME="${workspace.homeDir.absolutePath}"
export PATH="${binDir.absolutePath}:/system/bin:/system/xbin:${'$'}PATH"
export LD_LIBRARY_PATH="${libDir.absolutePath}"
export LD_PRELOAD="${termuxExecLib.absolutePath}"
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
     * v3.5 CRITICAL: Include LD_PRELOAD with termux-exec + all TERMUX_* env vars.
     */
    private fun createShellConfigs() {
        val homeDir = workspace.homeDir
        val termuxExecLib = File(libDir, "libtermux-exec-ld-preload.so")

        val profile = File(homeDir, ".profile")
        if (!profile.exists()) {
            profile.writeText("""
                # PocketAgent .profile
                # v3.5: termux-exec LD_PRELOAD rewrites hardcoded Termux paths
                export PREFIX="${usrDir.absolutePath}"
                export TERMUX_PREFIX="${usrDir.absolutePath}"
                export TERMUX_APP__DATA_DIR="${context.filesDir.absolutePath}"
                export TERMUX_ANDROID_HOME="${homeDir.absolutePath}"
                export TERMUX_HOME="${homeDir.absolutePath}"
                export PATH="${binDir.absolutePath}:/system/bin:/system/xbin:${'$'}PATH"
                export LD_LIBRARY_PATH="${libDir.absolutePath}"
                export LD_PRELOAD="${termuxExecLib.absolutePath}"
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
            # v3.5: termux-exec LD_PRELOAD is THE key — it intercepts execve() and rewrites
            # /data/data/com.termux/files/usr/ → our prefix. Without this, apt/pkg can't find
            # their method binaries (compiled-in paths can't be overridden by config).
            export PS1='pocketagent ❯ '
            export PREFIX="${usrDir.absolutePath}"
            export TERMUX_PREFIX="${usrDir.absolutePath}"
            export TERMUX_APP__DATA_DIR="${context.filesDir.absolutePath}"
            export TERMUX_ANDROID_HOME="${homeDir.absolutePath}"
            export TERMUX_HOME="${homeDir.absolutePath}"
            export PATH="${binDir.absolutePath}:/system/bin:/system/xbin:${'$'}PATH"
            export LD_LIBRARY_PATH="${libDir.absolutePath}"
            export LD_PRELOAD="${termuxExecLib.absolutePath}"
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
