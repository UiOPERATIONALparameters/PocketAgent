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
 *
 * The bootstrap URL is fetched dynamically from the GitHub API to always
 * get the latest working release.
 */
@Singleton
class BootstrapInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workspace: Workspace
) {
    companion object {
        // GitHub API endpoint to find the latest bootstrap release
        private const val GITHUB_API_URL = "https://api.github.com/repos/termux/termux-packages/releases"

        // Fallback URLs (tried if API fails)
        private val FALLBACK_URLS = listOf(
            "https://github.com/termux/termux-packages/releases/latest/download/bootstrap-aarch64.zip",
            "https://packages.termux.dev/bootstrap-aarch64.zip"
        )

        const val MARKER_FILE = ".bootstrap_installed"
    }

    val usrDir: File = File(context.filesDir, "usr")
    val binDir: File = File(usrDir, "bin")
    val bashPath: File get() = File(binDir, "bash")

    fun isInstalled(): Boolean {
        return File(usrDir, MARKER_FILE).exists() && bashPath.exists()
    }

    fun getBashPath(): String? {
        return if (isInstalled()) bashPath.absolutePath else null
    }

    /**
     * Download and install the bootstrap.
     * @param onProgress callback with (bytesDownloaded, totalBytes) — totalBytes may be -1 if unknown
     */
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

            // Extract
            onProgress(-1, -1)
            extractZip(zipFile, usrDir)

            // Set executable permissions
            binDir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    file.setExecutable(true, true)
                }
            }
            File(usrDir, "lib").listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".so")) {
                    file.setExecutable(true, true)
                }
            }

            // Create marker file
            File(usrDir, MARKER_FILE).writeText(System.currentTimeMillis().toString())

            // Create default workspace structure
            workspace.projectsDir
            workspace.tmpDir
            workspace.downloadsDir

            // Clean up zip
            zipFile.delete()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Query the GitHub API to find the latest bootstrap-aarch64.zip URL.
     */
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

                // Look through releases for one with bootstrap-aarch64.zip asset
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
            connection.setRequestProperty("User-Agent", "PocketAgent/0.5")

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
                    if (entryPath.contains("/bin/")) {
                        outFile.setExecutable(true, true)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    fun uninstall() {
        if (usrDir.exists()) {
            usrDir.deleteRecursively()
        }
    }

    fun getPath(): String {
        return listOf(
            binDir.absolutePath,
            "/system/bin",
            "/system/xbin"
        ).joinToString(":")
    }
}
