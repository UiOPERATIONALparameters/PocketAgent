package com.pocketagent.bridge

import kotlinx.serialization.Serializable

/**
 * Request and response DTOs for the Termux daemon.
 * All endpoints speak JSON. See termux-daemon/daemon.py for the source of truth.
 */

@Serializable
data class HealthResponse(
    val status: String,
    val version: String,
    val user: String,
    val home: String,
    val uptime: Long,
    val processes: Int
)

@Serializable
data class ExecRequest(
    val command: String,
    val timeout: Int = 30,
    val cwd: String? = null
)

@Serializable
data class ExecResponse(
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int = -1,
    val durationMs: Long = 0,
    val pid: Long? = null,
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
    val suggestion: String? = null,
    val error: String? = null
)

@Serializable
data class ProcessInfo(
    val pid: Long,
    val command: String,
    val startedAt: Double,
    val durationS: Long
)

@Serializable
data class ProcessListResponse(
    val processes: List<ProcessInfo> = emptyList()
)

@Serializable
data class KillRequest(val pid: Long)

@Serializable
data class KillResponse(
    val killed: Boolean,
    val pid: Long? = null,
    val error: String? = null
)

@Serializable
data class FileReadRequest(val path: String)

@Serializable
data class FileReadResponse(
    val content: String = "",
    val size: Long = 0,
    val truncated: Boolean = false,
    val binary: Boolean = false,
    val path: String = "",
    val error: String? = null
)

@Serializable
data class FileWriteRequest(
    val path: String,
    val content: String
)

@Serializable
data class FileWriteResponse(
    val bytes: Long = 0,
    val path: String = "",
    val error: String? = null
)

@Serializable
data class FileListRequest(val path: String = "~")

@Serializable
data class FileEntry(
    val name: String,
    val type: String,
    val size: Long,
    val mtime: Long,
    val hidden: Boolean = false
)

@Serializable
data class FileListResponse(
    val entries: List<FileEntry> = emptyList(),
    val path: String = "",
    val error: String? = null
)

@Serializable
data class FileStatRequest(val path: String)

@Serializable
data class FileStatResponse(
    val exists: Boolean = false,
    val type: String? = null,
    val size: Long = 0,
    val mtime: Long = 0,
    val path: String = "",
    val error: String? = null
)

@Serializable
data class FileMkdirRequest(val path: String)

@Serializable
data class FileMkdirResponse(
    val created: Boolean = false,
    val path: String = "",
    val error: String? = null
)

@Serializable
data class FileDeleteRequest(val path: String)

@Serializable
data class FileDeleteResponse(
    val deleted: Boolean = false,
    val path: String = "",
    val error: String? = null
)

/**
 * Stream messages from /stream endpoint (NDJSON).
 */
@Serializable
data class StreamMessage(
    val type: String,
    val data: String? = null,
    val code: Int? = null,
    val durationMs: Long? = null,
    val message: String? = null
)
