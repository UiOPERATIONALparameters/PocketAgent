package com.pocketagent.ui.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketagent.cloud.CloudBridge
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val currentPath: String = "~",
    val entries: List<FileEntry> = emptyList(),
    val previewContent: String? = null,
    val previewName: String? = null,
    val error: String? = null,
    val isConnected: Boolean = false
)

@HiltViewModel
class FileBrowserViewModel @Inject constructor(
    private val cloud: CloudBridge
) : ViewModel() {

    private val _state = MutableStateFlow(FileBrowserUiState())
    val state: StateFlow<FileBrowserUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            cloud.state.state.collect { bs ->
                _state.update { it.copy(isConnected = bs.status == com.pocketagent.cloud.CloudState.Status.CONNECTED) }
                if (bs.status == com.pocketagent.cloud.CloudState.Status.CONNECTED && _state.value.entries.isEmpty()) {
                    navigateTo("~")
                }
            }
        }
    }

    fun navigateTo(relativePath: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (!cloud.state.isConnected) {
                    _state.update { it.copy(error = "Not connected to Termux daemon") }
                    return@withContext
                }
                val result = cloud.listFiles(relativePath)
                val response = result.getOrElse { e ->
                    _state.update { it.copy(error = "Failed: ${e.message}") }
                    return@withContext
                }
                if (response.error != null) {
                    _state.update { it.copy(error = response.error) }
                    return@withContext
                }
                val entries = response.entries.map { e ->
                    val path = if (relativePath.endsWith("/")) "$relativePath${e.name}" else "$relativePath/${e.name}"
                    FileEntry(
                        name = e.name,
                        path = path,
                        isDirectory = e.type == "dir",
                        size = e.size,
                        modified = e.mtime * 1000L,
                        extension = if (e.type == "file") e.name.substringAfterLast('.', "").ifBlank { null } else null
                    )
                }
                _state.update {
                    it.copy(
                        currentPath = relativePath,
                        entries = entries,
                        error = null,
                        previewContent = null,
                        previewName = null
                    )
                }
            }
        }
    }

    fun previewFile(relativePath: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (!cloud.state.isConnected) {
                    _state.update { it.copy(error = "Not connected") }
                    return@withContext
                }
                val result = cloud.readFile(relativePath)
                val response = result.getOrElse { e ->
                    _state.update { it.copy(error = "Failed: ${e.message}") }
                    return@withContext
                }
                if (response.error != null) {
                    _state.update { it.copy(error = response.error) }
                    return@withContext
                }
                if (response.binary) {
                    _state.update {
                        it.copy(
                            previewContent = "[Binary file, ${response.size / 1024}KB]",
                            previewName = relativePath.substringAfterLast('/')
                        )
                    }
                    return@withContext
                }
                if (response.truncated) {
                    _state.update {
                        it.copy(
                            previewContent = response.content + "\n\n[...truncated, ${response.size} bytes total...]",
                            previewName = relativePath.substringAfterLast('/')
                        )
                    }
                    return@withContext
                }
                _state.update {
                    it.copy(previewContent = response.content, previewName = relativePath.substringAfterLast('/'))
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
                val result = cloud.deleteFile(relativePath)
                if (result.isFailure) {
                    _state.update { it.copy(error = result.exceptionOrNull()?.message) }
                    return@withContext
                }
                navigateTo(_state.value.currentPath)
            }
        }
    }

    fun navigateUp() {
        val current = _state.value.currentPath
        if (current == "~" || current.isEmpty()) return
        val parent = current.substringBeforeLast('/', "~")
        navigateTo(if (parent.isEmpty() || parent == current) "~" else parent)
    }

    fun refresh() {
        navigateTo(_state.value.currentPath)
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
}
