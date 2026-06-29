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
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val modified: Long,
    val extension: String?
)

data class FileBrowserUiState(
    val currentPath: String = ".",
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
                        // If it's a file, preview it
                        previewFile(relativePath)
                        return@withContext
                    }
                    val entries = dir.listFiles()
                        ?.filter { it.name != "." && it.name != ".." }
                        ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                        ?.map { f ->
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
                    // Only preview text files under 256KB
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
        val parent = File(current).parent ?: "."
        navigateTo(if (parent.isEmpty()) "." else parent)
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
}
