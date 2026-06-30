package com.pocketagent.ui.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketagent.sandbox.Workspace
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class FileEntry(
    val name: String,
    val path: String,          // relative to workspace home
    val isDirectory: Boolean,
    val size: Long,
    val modified: Long,
    val extension: String?
)

data class FileBrowserUiState(
    val currentPath: String = ".",       // relative to workspace home
    val entries: List<FileEntry> = emptyList(),
    val previewContent: String? = null,
    val previewName: String? = null,
    val error: String? = null,
    val totalUsedBytes: Long = 0,
    val quotaMb: Int = 2048
)

@HiltViewModel
class FileBrowserViewModel @Inject constructor(
    private val workspace: Workspace
) : ViewModel() {

    private val _state = MutableStateFlow(FileBrowserUiState())
    val state: StateFlow<FileBrowserUiState> = _state.asStateFlow()

    init {
        navigateTo(".")
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                _state.update { it.copy(totalUsedBytes = workspace.usedBytes()) }
            }
        }
    }

    /**
     * Navigate to a path relative to workspace home.
     * If the path is a file, preview it instead.
     */
    fun navigateTo(relativePath: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val dir = workspace.resolve(relativePath)
                    if (!dir.exists()) {
                        _state.update { it.copy(error = "Path not found: $relativePath") }
                        return@withContext
                    }
                    if (!dir.isDirectory) {
                        previewFile(relativePath)
                        return@withContext
                    }
                    val entries = dir.listFiles()
                        ?.filter { it.name != "." && it.name != ".." }
                        ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                        ?.map { f ->
                            // Compute path relative to HOME (not current dir)
                            // so navigateTo always works with the stored path
                            val rel = workspace.homeDir.toPath().relativize(f.toPath()).toString()
                            FileEntry(
                                name = f.name,
                                path = rel,
                                isDirectory = f.isDirectory,
                                size = if (f.isFile) f.length() else 0,
                                modified = f.lastModified(),
                                extension = if (f.isFile) f.extension else null
                            )
                        } ?: emptyList()
                    _state.update {
                        it.copy(
                            currentPath = relativePath,
                            entries = entries,
                            error = null,
                            previewContent = null,
                            previewName = null
                        )
                    }
                } catch (e: Exception) {
                    _state.update { it.copy(error = e.message ?: e::class.simpleName) }
                }
            }
        }
    }

    fun previewFile(relativePath: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val file = workspace.resolve(relativePath)
                    if (!file.exists() || !file.isFile) {
                        _state.update { it.copy(error = "Cannot preview: $relativePath") }
                        return@withContext
                    }
                    val size = file.length()
                    if (size > 256_000) {
                        _state.update {
                            it.copy(
                                previewContent = "[File too large to preview: ${size / 1024}KB]",
                                previewName = file.name
                            )
                        }
                        return@withContext
                    }
                    val ext = file.extension.lowercase()
                    val binaryExts = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "pdf", "zip", "gz", "tar", "rar", "7z", "exe", "so", "dex", "apk", "jar", "class", "o", "a")
                    if (ext in binaryExts) {
                        _state.update {
                            it.copy(
                                previewContent = "[Binary file: ${file.extension.uppercase()}, ${size / 1024}KB]",
                                previewName = file.name
                            )
                        }
                        return@withContext
                    }
                    val content = file.readText()
                    _state.update {
                        it.copy(previewContent = content, previewName = file.name)
                    }
                } catch (e: Exception) {
                    _state.update { it.copy(error = e.message ?: e::class.simpleName) }
                }
            }
        }
    }

    fun closePreview() {
        _state.update { it.copy(previewContent = null, previewName = null) }
    }

    fun deleteFile(relativePath: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val file = workspace.resolve(relativePath)
                    if (file.exists()) {
                        if (file.isDirectory) {
                            file.deleteRecursively()
                        } else {
                            file.delete()
                        }
                    }
                    // Refresh current dir
                    navigateTo(_state.value.currentPath)
                    _state.update { it.copy(totalUsedBytes = workspace.usedBytes()) }
                } catch (e: Exception) {
                    _state.update { it.copy(error = e.message ?: e::class.simpleName) }
                }
            }
        }
    }

    fun navigateUp() {
        val current = _state.value.currentPath
        if (current == "." || current.isEmpty()) return
        // Go to parent path relative to home
        val parent = current.substringBeforeLast('/', ".")
        navigateTo(if (parent.isEmpty() || parent == current) "." else parent)
    }

    /**
     * M20 FIX: save a file from the workspace to the user's Downloads directory.
     * Uses MediaStore on Android 10+ (no permission needed for Downloads);
     * falls back to writing to the public Downloads dir on Android 9 and below.
     *
     * Returns the URI of the saved file on success, null on failure.
     */
    suspend fun saveToDownloads(relativePath: String, context: android.content.Context): String? {
        return withContext(Dispatchers.IO) {
            try {
                val file = workspace.resolve(relativePath)
                if (!file.exists() || !file.isFile) return@withContext null

                val fileName = file.name
                val mimeType = guessMimeType(fileName)

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    // Android 10+ — use MediaStore.Downloads (no permission needed)
                    val resolver = context.contentResolver
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(android.provider.MediaStore.Downloads.MIME_TYPE, mimeType)
                        put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                    }
                    val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
                    val uri = resolver.insert(collection, values) ?: return@withContext null
                    resolver.openOutputStream(uri)?.use { output ->
                        file.inputStream().use { input -> input.copyTo(output) }
                    }
                    uri.toString()
                } else {
                    // Android 9 and below — write directly to public Downloads
                    val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS
                    )
                    if (!downloadsDir.exists()) downloadsDir.mkdirs()
                    val dest = java.io.File(downloadsDir, fileName)
                    file.inputStream().use { input ->
                        java.io.FileOutputStream(dest).use { output -> input.copyTo(output) }
                    }
                    dest.absolutePath
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun guessMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "txt", "md" -> "text/plain"
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js" -> "text/javascript"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "py" -> "text/x-python"
            "kt", "java" -> "text/x-java-source"
            "c", "cpp", "h" -> "text/x-c"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "gz", "tgz" -> "application/gzip"
            "tar" -> "application/x-tar"
            "apk" -> "application/vnd.android.package-archive"
            "mp3" -> "audio/mpeg"
            "mp4" -> "video/mp4"
            "wav" -> "audio/wav"
            else -> "application/octet-stream"
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
}
