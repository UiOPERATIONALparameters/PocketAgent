package com.pocketagent.bridge

import android.content.Context
import com.pocketagent.storage.prefs.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.BufferedReader
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Talks to the pocketagent-daemon running in the user's Termux over localhost:8765.
 *
 * This replaces v5.1's NativeEnvironmentManager (1,185 lines) + ShellExecutor (193 lines)
 * + Workspace + PathGuard — a total of ~1,500 lines of Android-OS-fighting code.
 *
 * v6 is ~250 lines of HTTP/JSON. No path patching, no LD_PRELOAD, no seccomp workarounds.
 *
 * All execution happens in the user's real Termux. The AI gets the exact same
 * environment the user has — same packages, same $PATH, same git config.
 */
@Singleton
class TermuxBridge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    val state: BridgeState
) {
    companion object {
        private const val DEFAULT_PORT = 8765
        private const val DEFAULT_HOST = "127.0.0.1"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val streamHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)  // long for streaming
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Base URL — always localhost. Daemon runs in Termux, same device. */
    private val baseUrl: String get() = "http://$DEFAULT_HOST:$DEFAULT_PORT"

    /** Auth token from settings (user pastes it during onboarding). */
    private val authToken: String? get() = settings.settings.value.termuxToken?.ifBlank { null }

    // ─── Public API ──────────────────────────────────────────────

    /** Check if the daemon is alive and the token is valid. */
    suspend fun checkHealth(): Result<HealthResponse> = withContextSafe {
        val req = buildRequest("/health").get().build()
        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful) {
            return@withContextSafe Result.failure(BridgeException("Health check failed: HTTP ${resp.code}"))
        }
        val body = resp.body?.string() ?: throw BridgeException("Empty response")
        Result.success(json.decodeFromString(HealthResponse.serializer(), body))
    }

    /** Update connection state by pinging the daemon. */
    suspend fun refreshState() {
        state.setConnecting()
        try {
            val health = checkHealth().getOrThrow()
            state.setConnected(health)
        } catch (e: Exception) {
            state.setError(e.message ?: e::class.simpleName ?: "Unknown error")
        }
    }

    /** Run a command synchronously, return when done. */
    suspend fun exec(command: String, timeout: Int = 30, cwd: String? = null): Result<ExecResponse> = withContextSafe {
        val body = json.encodeToString(ExecRequest.serializer(), ExecRequest(command, timeout, cwd))
        val req = buildRequest("/exec").post(body.toRequestBody(JSON)).build()
        val resp = httpClient.newCall(req).execute()
        val respBody = resp.body?.string() ?: throw BridgeException("Empty response")
        if (!resp.isSuccessful) {
            return@withContextSafe Result.failure(BridgeException("Exec failed: HTTP ${resp.code}: $respBody"))
        }
        Result.success(json.decodeFromString(ExecResponse.serializer(), respBody))
    }

    /**
     * Stream a command's output as a Flow of [StreamMessage]s.
     * The Flow completes when the command exits or the stream is closed.
     * Use this for long-running commands where you want live output.
     */
    fun stream(command: String, cwd: String? = null): Flow<StreamMessage> = flow {
        val encodedCmd = URLEncoder.encode(command, "UTF-8")
        val url = "$baseUrl/stream?command=$encodedCmd"
        val reqBuilder = Request.Builder().url(url).header("Accept", "application/x-ndjson")
        authToken?.let { reqBuilder.header("X-PocketAgent-Token", it) }
        val req = reqBuilder.get().build()

        try {
            streamHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    emit(StreamMessage(type = "error", message = "Stream failed: HTTP ${resp.code}"))
                    return@use
                }
                val reader = BufferedReader(resp.body?.charStream() ?: return@use)
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    try {
                        val msg = json.decodeFromString(StreamMessage.serializer(), line)
                        emit(msg)
                    } catch (_: Exception) {
                        // Skip malformed line
                    }
                }
            }
        } catch (e: IOException) {
            emit(StreamMessage(type = "error", message = e.message ?: "Network error"))
        }
    }.flowOn(Dispatchers.IO)

    /** List running processes registered with the daemon. */
    suspend fun listProcesses(): Result<ProcessListResponse> = withContextSafe {
        val req = buildRequest("/proc/list").post("{}".toRequestBody(JSON)).build()
        val resp = httpClient.newCall(req).execute()
        val body = resp.body?.string() ?: throw BridgeException("Empty response")
        Result.success(json.decodeFromString(ProcessListResponse.serializer(), body))
    }

    /** Kill a process by PID. */
    suspend fun killProcess(pid: Long): Result<KillResponse> = withContextSafe {
        val body = json.encodeToString(KillRequest.serializer(), KillRequest(pid))
        val req = buildRequest("/proc/kill").post(body.toRequestBody(JSON)).build()
        val resp = httpClient.newCall(req).execute()
        val respBody = resp.body?.string() ?: throw BridgeException("Empty response")
        Result.success(json.decodeFromString(KillResponse.serializer(), respBody))
    }

    /** Read a file (≤1MB). */
    suspend fun readFile(path: String): Result<FileReadResponse> = withContextSafe {
        val body = json.encodeToString(FileReadRequest.serializer(), FileReadRequest(path))
        val req = buildRequest("/files/read").post(body.toRequestBody(JSON)).build()
        val resp = httpClient.newCall(req).execute()
        val respBody = resp.body?.string() ?: throw BridgeException("Empty response")
        Result.success(json.decodeFromString(FileReadResponse.serializer(), respBody))
    }

    /** Write a file. */
    suspend fun writeFile(path: String, content: String): Result<FileWriteResponse> = withContextSafe {
        val body = json.encodeToString(FileWriteRequest.serializer(), FileWriteRequest(path, content))
        val req = buildRequest("/files/write").post(body.toRequestBody(JSON)).build()
        val resp = httpClient.newCall(req).execute()
        val respBody = resp.body?.string() ?: throw BridgeException("Empty response")
        Result.success(json.decodeFromString(FileWriteResponse.serializer(), respBody))
    }

    /** List directory entries. */
    suspend fun listFiles(path: String = "~"): Result<FileListResponse> = withContextSafe {
        val body = json.encodeToString(FileListRequest.serializer(), FileListRequest(path))
        val req = buildRequest("/files/list").post(body.toRequestBody(JSON)).build()
        val resp = httpClient.newCall(req).execute()
        val respBody = resp.body?.string() ?: throw BridgeException("Empty response")
        Result.success(json.decodeFromString(FileListResponse.serializer(), respBody))
    }

    /** Stat a file. */
    suspend fun statFile(path: String): Result<FileStatResponse> = withContextSafe {
        val body = json.encodeToString(FileStatRequest.serializer(), FileStatRequest(path))
        val req = buildRequest("/files/stat").post(body.toRequestBody(JSON)).build()
        val resp = httpClient.newCall(req).execute()
        val respBody = resp.body?.string() ?: throw BridgeException("Empty response")
        Result.success(json.decodeFromString(FileStatResponse.serializer(), respBody))
    }

    /** Make a directory. */
    suspend fun mkdir(path: String): Result<FileMkdirResponse> = withContextSafe {
        val body = json.encodeToString(FileMkdirRequest.serializer(), FileMkdirRequest(path))
        val req = buildRequest("/files/mkdir").post(body.toRequestBody(JSON)).build()
        val resp = httpClient.newCall(req).execute()
        val respBody = resp.body?.string() ?: throw BridgeException("Empty response")
        Result.success(json.decodeFromString(FileMkdirResponse.serializer(), respBody))
    }

    /** Delete a file or directory. */
    suspend fun deleteFile(path: String): Result<FileDeleteResponse> = withContextSafe {
        val body = json.encodeToString(FileDeleteRequest.serializer(), FileDeleteRequest(path))
        val req = buildRequest("/files/delete").post(body.toRequestBody(JSON)).build()
        val resp = httpClient.newCall(req).execute()
        val respBody = resp.body?.string() ?: throw BridgeException("Empty response")
        Result.success(json.decodeFromString(FileDeleteResponse.serializer(), respBody))
    }

    // ─── Internals ───────────────────────────────────────────────

    private fun buildRequest(path: String): Request.Builder {
        val builder = Request.Builder()
            .url("$baseUrl$path")
            .header("Accept", "application/json")
        authToken?.let { builder.header("X-PocketAgent-Token", it) }
        return builder
    }

    private suspend fun <T> withContextSafe(block: suspend () -> Result<T>): Result<T> {
        return try {
            kotlinx.coroutines.withContext(Dispatchers.IO) { block() }
        } catch (e: Exception) {
            state.setError(e.message ?: e::class.simpleName ?: "Unknown error")
            Result.failure(e)
        }
    }

    companion object {
        val JSON = "application/json".toMediaType()
    }
}

class BridgeException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Convenience: read a BufferedReader as a char stream. */
private fun java.io.InputStreamReader.charStream() = this
