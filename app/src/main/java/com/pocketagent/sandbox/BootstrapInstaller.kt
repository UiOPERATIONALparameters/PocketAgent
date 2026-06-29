package com.pocketagent.sandbox

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 *
 * The bootstrap is ~50MB and is downloaded on-demand (not bundled in APK)
 * to keep the APK small. After extraction, ShellExecutor automatically
 * uses the bootstrap's bash.
 *
 * Layout after install:
 *   /data/data/com.pocketagent/files/usr/
 *     ├── bin/       (bash, ls, cat, grep, python, node, git, etc.)
 *     ├── lib/       (shared libraries)
 *     ├── share/     (docs, man pages)
 *     └── etc/       (config)
 *
 * The AI can then:
 *   - apt install python node ffmpeg git
 *   - pip install requests
 *   - python script.py
 *   - node script.js
 *   - git clone https://github.com/...
 *   - curl https://...
 */
@Singleton
class BootstrapInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workspace: Workspace
) {
    companion object {
        // Termux bootstrap for aarch64 (ARM 64-bit — covers 99% of modern Android phones)
        // This is a stable release from the Termux project (Apache 2.0 license)
        private const val BOOTSTRAP_URL =
            "https://github.com/termux/termux-packages/releases/download/bootstrap-2025.01.25T08%3A33%3A00%2B00%3A00/bootstrap-aarch64.zip"

        // Alternative URLs (tried in order if primary fails)
        private val FALLBACK_URLS = listOf(
            "https://packages.termux.dev/bootstrap/aarch64/bootstrap-aarch64.zip"
        )

        const val MARKER_FILE = ".bootstrap_installed"
    }

    val usrDir: File = File(context.filesDir, "usr")
    val binDir: File = File(usrDir, "bin")
    val bashPath: File get() = File(binDir, "bash")

    /** Check if bootstrap is already installed. */
    fun isInstalled(): Boolean {
        return File(usrDir, MARKER_FILE).exists() && bashPath.exists()
    }

    /** Get the bash binary path, or null if not installed. */
    fun getBashPath(): String? {
        return if (isInstalled()) bashPath.absolutePath else null
    }

    /**
     * Download and install the bootstrap.
     * @param onProgress callback with (bytesDownloaded, totalBytes) — totalBytes may be -1 if unknown
     */
    suspend fun install(onProgress: (Long, Long) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Download
            val zipFile = File(context.cacheDir, "bootstrap-aarch64.zip")
            val downloadResult = downloadZip(BOOTSTRAP_URL, zipFile, onProgress)
            if (downloadResult.isFailure) {
                // Try fallback URLs
                var success = false
                for (url in FALLBACK_URLS) {
                    val fallbackResult = downloadZip(url, zipFile, onProgress)
                    if (fallbackResult.isSuccess) {
                        success = true
                        break
                    }
                }
                if (!success) {
                    return@withContext Result.failure(IOException("Failed to download bootstrap from all URLs"))
                }
            }

            // Extract
            onProgress(-1, -1) // Signal: extracting phase
            extractZip(zipFile, usrDir)

            // Set executable permissions on all binaries in bin/
            binDir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    file.setExecutable(true, true)
                }
            }

            // Also set +x on lib/*.so (some are loaded by executables)
            File(usrDir, "lib").listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".so")) {
                    file.setExecutable(true, true)
                }
            }

            // Create marker file
            File(usrDir, MARKER_FILE).writeText(System.currentTimeMillis().toString())

            // Clean up zip
            zipFile.delete()

            // Create default workspace structure
            workspace.projectsDir
            workspace.tmpDir
            workspace.downloadsDir

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun downloadZip(url: String, dest: File, onProgress: (Long, Long) -> Unit): Result<Unit> {
        return try {
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 300_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "PocketAgent/0.4")

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
                // Security: prevent path traversal
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
                    // Set executable for files in bin/
                    if (entryPath.contains("/bin/")) {
                        outFile.setExecutable(true, true)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /** Uninstall the bootstrap (frees ~200MB). */
    fun uninstall() {
        if (usrDir.exists()) {
            usrDir.deleteRecursively()
        }
    }

    /** Get the PATH environment variable for the bootstrap. */
    fun getPath(): String {
        return listOf(
            binDir.absolutePath,
            "/system/bin",
            "/system/xbin"
        ).joinToString(":")
    }
}
