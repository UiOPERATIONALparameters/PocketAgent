package com.pocketagent.sandbox

import android.content.Context
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
 * Downloads and extracts the Termux bootstrap, giving the AI a full Linux
 * userland (bash, python, node, git, ffmpeg, apt, etc.) inside the app's
 * private storage.
 */
@Singleton
class BootstrapInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workspace: Workspace
) {
    companion object {
        private const val GITHUB_API_URL = "https://api.github.com/repos/termux/termux-packages/releases"
        private val FALLBACK_URLS = listOf(
            "https://github.com/termux/termux-packages/releases/latest/download/bootstrap-aarch64.zip",
            "https://packages.termux.dev/bootstrap-aarch64.zip"
        )
        const val MARKER_FILE = ".bootstrap_installed"
    }

    val usrDir: File = File(context.filesDir, "usr")
    val binDir: File = File(usrDir, "bin")
    val libDir: File = File(usrDir, "lib")
    val bashPath: File get() = File(binDir, "bash")

    fun isInstalled(): Boolean {
        return File(usrDir, MARKER_FILE).exists() && bashPath.exists()
    }

    fun getBashPath(): String? {
        return if (isInstalled()) bashPath.absolutePath else null
    }

    /**
     * Get diagnostic info about the bootstrap installation.
     * Used by the AI to debug issues.
     */
    fun getDiagnosticInfo(): String {
        return buildString {
            appendLine("Bootstrap installed: ${isInstalled()}")
            appendLine("usrDir exists: ${usrDir.exists()}")
            appendLine("usrDir path: ${usrDir.absolutePath}")
            appendLine("binDir exists: ${binDir.exists()}")
            appendLine("bashPath exists: ${bashPath.exists()}")
            if (bashPath.exists()) {
                appendLine("bashPath executable: ${bashPath.canExecute()}")
                appendLine("bashPath size: ${bashPath.length()} bytes")
            }
            appendLine("libDir exists: ${libDir.exists()}")
            if (libDir.exists()) {
                val soFiles = libDir.listFiles { f -> f.name.endsWith(".so") }
                appendLine("libDir .so files: ${soFiles?.size ?: 0}")
            }
            if (binDir.exists()) {
                val bins = binDir.listFiles()
                appendLine("binDir file count: ${bins?.size ?: 0}")
                bins?.take(10)?.forEach { f ->
                    appendLine("  ${f.name} (exec=${f.canExecute()}, size=${f.length()})")
                }
            }
            appendLine("LD_LIBRARY_PATH should be: ${libDir.absolutePath}")
            appendLine("PATH should be: ${binDir.absolutePath}:/system/bin:/system/xbin")
        }
    }

    suspend fun install(onProgress: (Long, Long) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val zipFile = File(context.cacheDir, "bootstrap-aarch64.zip")

            // Try to find the latest bootstrap URL from GitHub API
            val urls = mutableListOf<String>()
            val apiUrl = findLatestBootstrapUrl()
            if (apiUrl != null) {
                urls.add(apiUrl)
            }
            urls.addAll(FALLBACK_URLS)

            var downloadSuccess = false
            var lastError: String? = null

            for (url in urls) {
                onProgress(-1, -1)
                val downloadResult = downloadZip(url, zipFile, onProgress)
                if (downloadResult.isSuccess) {
                    downloadSuccess = true
                    break
                } else {
                    lastError = downloadResult.exceptionOrNull()?.message
                }
            }

            if (!downloadSuccess) {
                return@withContext Result.failure(IOException("Failed to download bootstrap from all URLs. Last error: $lastError"))
            }

            // Verify the downloaded zip is valid (not a partial download)
            if (!zipFile.exists() || zipFile.length() < 1_000_000) {
                // Bootstrap should be at least ~40MB. If it's smaller, it's likely
                // a partial download from a network interruption.
                zipFile.delete()
                return@withContext Result.failure(IOException("Downloaded file is too small (${zipFile.length()} bytes) — likely a partial download. Please try again with a stable connection."))
            }

            // Clean up any previous installation
            if (usrDir.exists()) {
                usrDir.deleteRecursively()
            }
            usrDir.mkdirs()

            // Extract
            onProgress(-1, -1)
            try {
                extractZip(zipFile, usrDir)
            } catch (e: Exception) {
                // Extraction failed — zip might be corrupt
                zipFile.delete()
                usrDir.deleteRecursively()
                return@withContext Result.failure(IOException("Failed to extract bootstrap (zip may be corrupt): ${e.message}. Please try again."))
            }

            // CRITICAL: Set executable permissions on ALL files in bin/
            // Some Android versions don't preserve permissions from zip
            if (binDir.exists()) {
                binDir.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        file.setExecutable(true, true)
                    }
                }
            }

            // Also set +x on .so files (some are loaded as executables)
            if (libDir.exists()) {
                libDir.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        file.setExecutable(true, true)
                        file.setReadable(true, true)
                    }
                }
            }

            // Set read permissions on everything
            usrDir.walkTopDown().forEach { file ->
                file.setReadable(true, true)
                if (file.isDirectory) {
                    file.setExecutable(true, true)
                }
            }

            // CRITICAL: Patch hardcoded Termux paths in all binaries and scripts.
            patchTermuxPaths(usrDir)

            // CRITICAL: Create .so symlinks. The Termux bootstrap ships libraries
            // with full version numbers (e.g. libreadline.so.8.0) but the dynamic
            // linker looks for the short name (libreadline.so.8). Without these
            // symlinks, bash and other binaries fail to start.
            createSoSymlinks(libDir)

            // CRITICAL: Create symlink bridge for termux-exec path translation
            createSymlinkBridge()

            // Create .profile and improve .bashrc
            createShellConfigs()

            // Create marker file
            File(usrDir, MARKER_FILE).writeText(System.currentTimeMillis().toString())

            // Create default workspace structure
            workspace.projectsDir
            workspace.tmpDir
            workspace.downloadsDir

            // Clean up zip
            zipFile.delete()

            // Verify installation
            if (!bashPath.exists()) {
                return@withContext Result.failure(IOException("Bootstrap extracted but bash not found at ${bashPath.absolutePath}"))
            }
            if (!bashPath.canExecute()) {
                bashPath.setExecutable(true, true)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun findLatestBootstrapUrl(): String? {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url(GITHUB_API_URL)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "PocketAgent")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val releases = org.json.JSONArray(body)
                if (releases.length() == 0) return null

                for (i in 0 until releases.length()) {
                    val release = releases.optJSONObject(i) ?: continue
                    val assets = release.optJSONArray("assets") ?: continue
                    for (j in 0 until assets.length()) {
                        val asset = assets.optJSONObject(j) ?: continue
                        val name = asset.optString("name")
                        if (name == "bootstrap-aarch64.zip") {
                            return asset.optString("browser_download_url")
                        }
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun downloadZip(url: String, dest: File, onProgress: (Long, Long) -> Unit): Result<Unit> {
        return try {
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 300_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "PocketAgent/0.6")

            if (connection.responseCode != java.net.HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                return Result.failure(IOException("HTTP ${connection.responseCode}"))
            }

            val totalBytes = connection.contentLengthLong
            var downloaded = 0L
            val buffer = ByteArray(8192)

            connection.inputStream.use { input ->
                FileOutputStream(dest).use { output ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        downloaded += n
                        onProgress(downloaded, totalBytes)
                    }
                }
            }
            connection.disconnect()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractZip(zipFile: File, destDir: File) {
        if (!destDir.exists()) destDir.mkdirs()

        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
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
                    FileOutputStream(outFile).use { fos ->
                        zis.copyTo(fos)
                    }
                    if (entryPath.contains("/bin/") || entryPath.startsWith("bin/")) {
                        outFile.setExecutable(true, true)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /**
     * CRITICAL: Patch hardcoded Termux paths in all text-based files.
     * The Termux bootstrap has /data/data/com.termux/files/usr/ compiled in.
     * We replace it with our actual prefix so apt, pkg, and all tools work.
     *
     * This patches:
     * - Shell scripts (*.sh)
     * - Config files
     * - Text-based binaries (some Python scripts, etc.)
     *
     * Note: Compiled ELF binaries (.so, executables) can't be patched with sed
     * because they have checksums. For those, we rely on the symlink bridge.
     */
    private fun patchTermuxPaths(usrDir: File) {
        val oldPrefix = "/data/data/com.termux/files/usr"
        val newPrefix = usrDir.absolutePath
        if (oldPrefix == newPrefix) return  // shouldn't happen but safety

        val textExtensions = setOf("sh", "py", "pl", "rb", "js", "conf", "cfg", "ini", "txt", "md", "json", "xml", "yaml", "yml", "env", "profile", "bashrc", "bash_profile")
        var patchedCount = 0

        usrDir.walkTopDown().forEach { file ->
            if (!file.isFile) return@forEach
            val ext = file.extension.lowercase()
            val isText = ext in textExtensions ||
                file.name.startsWith(".") ||
                file.name == "bashrc" ||
                file.name == "profile" ||
                file.name == "bash_profile"

            if (!isText) return@forEach

            try {
                val content = file.readText()
                if (content.contains(oldPrefix)) {
                    val patched = content.replace(oldPrefix, newPrefix)
                    file.writeText(patched)
                    patchedCount++
                }
            } catch (_: Exception) {
                // Skip files we can't read/write
            }
        }
    }

    /**
     * CRITICAL: Create .so symlinks for all shared libraries.
     *
     * The Termux bootstrap ships libraries with full version numbers:
     *   libreadline.so.8.0
     * But the dynamic linker looks for:
     *   libreadline.so.8  (SONAME)
     *   libreadline.so    (development link)
     *
     * Without these symlinks, binaries fail with "library not found".
     */
    private fun createSoSymlinks(libDir: File) {
        if (!libDir.exists()) return

        libDir.listFiles { f -> f.name.endsWith(".so") || f.name.contains(".so.") }?.forEach { file ->
            val name = file.name
            // Skip if it's already a symlink
            if (java.nio.file.Files.isSymbolicLink(file.toPath())) return@forEach

            // Parse version from name: libfoo.so.8.0 -> libfoo.so.8, libfoo.so
            val soIdx = name.indexOf(".so")
            if (soIdx < 0) return@forEach

            val baseName = name.substring(0, soIdx + 3)  // libfoo.so
            val versionPart = name.substring(soIdx + 3)  // .8.0 or empty

            if (versionPart.isNotEmpty()) {
                // Create libfoo.so.8 symlink (first version number)
                val parts = versionPart.split(".").filter { it.isNotEmpty() }
                if (parts.isNotEmpty()) {
                    val soname = "$baseName.${parts[0]}"  // libfoo.so.8
                    val sonameFile = File(libDir, soname)
                    if (!sonameFile.exists()) {
                        try {
                            java.nio.file.Files.createSymbolicLink(sonameFile.toPath(), file.toPath())
                        } catch (_: Exception) {
                            // Fallback: copy if symlink fails (some filesystems don't support symlinks)
                            try { sonameFile.copyFrom(file) } catch (_: Exception) {}
                        }
                    }
                }
            }

            // Create libfoo.so symlink (development link) if it doesn't exist
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
     * CRITICAL: Configure termux-exec for path translation.
     *
     * libtermux-exec.so intercepts file system calls and remaps:
     *   /data/data/com.termux/files/usr/ -> our actual prefix
     *   /data/data/com.termux/files/home/ -> our home
     *
     * For this to work, it needs these EXACT environment variables:
     *   TERMUX_PREFIX           = /data/data/com.pocketagent/files/usr
     *   TERMUX_APP__DATA_DIR    = /data/data/com.pocketagent/files  (note double underscore)
     *   TERMUX_ANDROID_HOME     = /data/data/com.pocketagent/files/workspace
     *
     * Without these, termux-exec doesn't know where to redirect paths.
     */
    private fun createSymlinkBridge() {
        val wrapperDir = File(usrDir, "etc/profile.d")
        if (!wrapperDir.exists()) wrapperDir.mkdirs()

        val profileScript = File(wrapperDir, "pocketagent-paths.sh")
        profileScript.writeText("""
            # PocketAgent path configuration for termux-exec
            # These env vars tell libtermux-exec.so where to redirect /data/data/com.termux/ paths

            export PREFIX="${usrDir.absolutePath}"
            export TERMUX_PREFIX="${usrDir.absolutePath}"
            export TERMUX_APP__DATA_DIR="${context.filesDir.absolutePath}"
            export TERMUX_ANDROID_HOME="${workspace.homeDir.absolutePath}"
            export TERMUX_HOME="${workspace.homeDir.absolutePath}"

            export LD_LIBRARY_PATH="${File(usrDir, "lib").absolutePath}"
            export PATH="${binDir.absolutePath}:/system/bin:/system/xbin"

            # Suppress the profile.d permission denied warnings
            # (termux-exec redirects /data/data/com.termux/ but some files still fail)
            2>/dev/null
        """.trimIndent())
        profileScript.setExecutable(true, true)
        profileScript.setReadable(true, true)
    }

    /**
     * Create .profile, .bash_profile, and improve .bashrc.
     * This ensures login shells get configured properly.
     */
    private fun createShellConfigs() {
        val homeDir = workspace.homeDir

        // .profile — loaded by login shells
        val profile = File(homeDir, ".profile")
        if (!profile.exists()) {
            profile.writeText("""
                # PocketAgent .profile
                # Loaded by login shells

                # Set PREFIX (critical for apt/pkg)
                export PREFIX="${usrDir.absolutePath}"
                export TERMUX_PREFIX="${usrDir.absolutePath}"

                # Set PATH
                export PATH="${binDir.absolutePath}:/system/bin:/system/xbin:${'$'}PATH"

                # Set LD_LIBRARY_PATH
                export LD_LIBRARY_PATH="${File(usrDir, "lib").absolutePath}"

                # Locale settings
                export LANG=en_US.UTF-8
                export LC_ALL=en_US.UTF-8
                export LANGUAGE=en_US:en

                # Terminal settings
                export TERM=xterm-256color
                export COLORTERM=truecolor

                # Home and tmp
                export HOME="${homeDir.absolutePath}"
                export TMPDIR="${workspace.tmpDir.absolutePath}"

                # Source .bashrc if interactive
                if [ -n "${'$'}PS1" ] && [ -f "${'$'}HOME/.bashrc" ]; then
                    source "${'$'}HOME/.bashrc"
                fi

                # Source profile.d scripts
                if [ -d "${usrDir.absolutePath}/etc/profile.d" ]; then
                    for script in ${usrDir.absolutePath}/etc/profile.d/*.sh; do
                        if [ -r "${'$'}script" ]; then
                            . "${'$'}script"
                        fi
                    done
                fi
            """.trimIndent())
        }

        // .bash_profile — loaded by bash login shells
        val bashProfile = File(homeDir, ".bash_profile")
        if (!bashProfile.exists()) {
            bashProfile.writeText("""
                # PocketAgent .bash_profile
                # Source .profile
                if [ -f "${'$'}HOME/.profile" ]; then
                    source "${'$'}HOME/.profile"
                fi
            """.trimIndent())
        }

        // Improve .bashrc
        val bashrc = File(homeDir, ".bashrc")
        bashrc.writeText("""
            # PocketAgent .bashrc
            export PS1='pocketagent ❯ '
            export PREFIX="${usrDir.absolutePath}"
            export TERMUX_PREFIX="${usrDir.absolutePath}"
            export PATH="${binDir.absolutePath}:/system/bin:/system/xbin:${'$'}PATH"
            export LD_LIBRARY_PATH="${File(usrDir, "lib").absolutePath}"
            export LANG=en_US.UTF-8
            export LC_ALL=en_US.UTF-8
            export TERM=xterm-256color
            export COLORTERM=truecolor
            export HOME="${homeDir.absolutePath}"
            export TMPDIR="${workspace.tmpDir.absolutePath}"
            alias ll='ls -la'
            alias la='ls -A'
            alias l='ls -CF'
            alias ..='cd ..'
            alias ...='cd ../..'

            # pkg wrapper that uses our PREFIX
            pkg() {
                PREFIX="${usrDir.absolutePath}" command pkg "${'$'}@"
            }

            # apt wrapper that uses our PREFIX
            apt() {
                PREFIX="${usrDir.absolutePath}" command apt "${'$'}@"
            }
        """.trimIndent())
    }

    fun uninstall() {
        if (usrDir.exists()) {
            usrDir.deleteRecursively()
        }
    }

    /**
     * Repair the installation by re-setting permissions on all binaries.
     * Use this if bash exists but won't execute (permission issues).
     */
    fun repairPermissions() {
        if (!usrDir.exists()) return

        // Set executable on all bin/ files
        if (binDir.exists()) {
            binDir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    file.setExecutable(true, true)
                    file.setReadable(true, true)
                }
            }
        }

        // Set executable + readable on .so files
        if (libDir.exists()) {
            libDir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    file.setExecutable(true, true)
                    file.setReadable(true, true)
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
     * Verify the installation is complete and functional.
     * Returns null if OK, or an error message describing what's wrong.
     */
    fun verify(): String? {
        if (!usrDir.exists()) return "usr/ directory does not exist"
        if (!binDir.exists()) return "usr/bin/ directory does not exist"
        if (!bashPath.exists()) return "bash binary not found at ${bashPath.absolutePath}"
        if (!bashPath.canExecute()) {
            // Try to fix
            bashPath.setExecutable(true, true)
            if (!bashPath.canExecute()) return "bash binary exists but is not executable"
        }
        if (!libDir.exists()) return "usr/lib/ directory does not exist"
        val soCount = libDir.listFiles { f -> f.name.endsWith(".so") }?.size ?: 0
        if (soCount < 10) return "usr/lib/ has only $soCount .so files (expected 50+). Bootstrap may be incomplete."

        // Check for critical libraries
        val criticalLibs = listOf("libreadline.so.8", "libc++.so", "libandroid-support.so")
        for (lib in criticalLibs) {
            val libFile = File(libDir, lib)
            if (!libFile.exists()) {
                return "Critical library $lib not found. Bootstrap is incomplete."
            }
        }

        return null  // All good
    }

    fun getPath(): String {
        // Deduplicate PATH entries
        return listOf(
            binDir.absolutePath,
            "/system/bin",
            "/system/xbin"
        ).distinct().joinToString(":")
    }
}
