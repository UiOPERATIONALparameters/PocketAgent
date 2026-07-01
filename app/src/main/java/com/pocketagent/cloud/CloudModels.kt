package com.pocketagent.cloud

import kotlinx.serialization.Serializable

/**
 * DTOs for the cloud daemon (same shape as v6 Termux daemon, but for Codespaces).
 */
@Serializable data class HealthResponse(val status: String, val version: String, val user: String, val home: String, val uptime: Long, val processes: Int, val mode: String = "cloud")

@Serializable data class ExecRequest(val command: String, val timeout: Int = 120, val cwd: String? = null)
@Serializable data class ExecResponse(
    val stdout: String = "", val stderr: String = "", val exitCode: Int = -1,
    val durationMs: Long = 0, val pid: Long? = null, val timedOut: Boolean = false,
    val truncated: Boolean = false, val suggestion: String? = null, val error: String? = null
)

@Serializable data class ProcessInfo(val pid: Long, val command: String, val startedAt: Double, val durationS: Long)
@Serializable data class ProcessListResponse(val processes: List<ProcessInfo> = emptyList())
@Serializable data class KillRequest(val pid: Long)
@Serializable data class KillResponse(val killed: Boolean, val pid: Long? = null, val error: String? = null)

@Serializable data class FileReadRequest(val path: String)
@Serializable data class FileReadResponse(val content: String = "", val size: Long = 0, val truncated: Boolean = false, val binary: Boolean = false, val path: String = "", val error: String? = null)
@Serializable data class FileWriteRequest(val path: String, val content: String)
@Serializable data class FileWriteResponse(val bytes: Long = 0, val path: String = "", val error: String? = null)
@Serializable data class FileListRequest(val path: String = "~")
@Serializable data class FileEntry(val name: String, val type: String, val size: Long, val mtime: Long, val hidden: Boolean = false)
@Serializable data class FileListResponse(val entries: List<FileEntry> = emptyList(), val path: String = "", val error: String? = null)
@Serializable data class FileStatRequest(val path: String)
@Serializable data class FileStatResponse(val exists: Boolean = false, val type: String? = null, val size: Long = 0, val mtime: Long = 0, val path: String = "", val error: String? = null)
@Serializable data class FileMkdirRequest(val path: String)
@Serializable data class FileMkdirResponse(val created: Boolean = false, val path: String = "", val error: String? = null)
@Serializable data class FileDeleteRequest(val path: String)
@Serializable data class FileDeleteResponse(val deleted: Boolean = false, val path: String = "", val error: String? = null)
@Serializable data class StreamMessage(val type: String, val data: String? = null, val code: Int? = null, val durationMs: Long? = null, val message: String? = null)

// Codespaces API models
@Serializable data class CodespaceInfo(
    val name: String,
    val state: String,  // "Created", "Starting", "Available", "ShuttingDown", "Deleted"
    val repository: CodespaceRepo? = null,
    val machine: CodespaceMachine? = null,
    val display_name: String? = null,
    val url: String? = null  // codespace URL (web editor)
)

@Serializable data class CodespaceRepo(val full_name: String, val name: String, val id: Long)
@Serializable data class CodespaceMachine(val name: String, val display_name: String? = null, val cpus: Int = 0, val memory_mb: Int = 0)

@Serializable data class CreateCodespaceRequest(
    val repository_id: Long,
    val machine: String = "basicLinux32gb",
    val location: String = "EastUs"
)

@Serializable data class CodespaceListResponse(val codespaces: List<CodespaceInfo> = emptyList(), val total_count: Int = 0)
