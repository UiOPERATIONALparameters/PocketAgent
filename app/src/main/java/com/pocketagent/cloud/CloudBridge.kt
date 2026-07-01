package com.pocketagent.cloud

import com.pocketagent.storage.prefs.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Talks to the PocketAgent daemon running in a GitHub Codespace.
 *
 * v7 replaces v6's CloudBridge with CloudBridge. Same interface, different backend:
 *   - v6: HTTP to localhost:8765 (Termux daemon on phone)
 *   - v7: HTTPS to https://<codespace>-8765.app.github.dev (cloud daemon)
 *
 * The cloud gives real Linux (glibc), real apt, real everything. No Android fighting.
 */
@Singleton
class CloudBridge @Inject constructor(
    private val settings: SettingsRepository,
    val state: CloudState
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = false }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val streamClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Base URL — the codespace's public port URL. */
    private val baseUrl: String?
        get() {
            val url = settings.settings.value.cloudUrl?.trimEnd('/')
            return if (url.isNullOrBlank()) null else url
        }

    private val authToken: String?
        get() = settings.settings.value.cloudToken?.ifBlank { null }

    // ─── Public API ──────────────────────────────────────────────

    suspend fun checkHealth(): Result<HealthResponse> = withContextSafe {
        val url = baseUrl ?: return@withContextSafe Result.failure(CloudException("No cloud URL configured"))
        val req = buildRequest("$url/health").get().build()
        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful) return@withContextSafe Result.failure(CloudException("Health check failed: HTTP ${resp.code}"))
        val body = resp.body?.string() ?: throw CloudException("Empty response")
        Result.success(json.decodeFromString(HealthResponse.serializer(), body))
    }

    suspend fun refreshState() {
        if (baseUrl == null) { state.setNoCodespace(); return }
        state.setConnecting()
        try {
            val health = checkHealth().getOrThrow()
            state.setConnected(health)
        } catch (e: Exception) {
            state.setError(e.message ?: e::class.simpleName ?: "Unknown error")
        }
    }

    suspend fun exec(command: String, timeout: Int = 120, cwd: String? = null): Result<ExecResponse> = withContextSafe {
        val url = baseUrl ?: return@withContextSafe Result.failure(CloudException("No cloud URL"))
        val body = json.encodeToString(ExecRequest.serializer(), ExecRequest(command, timeout, cwd))
        val req = buildRequest("$url/exec").post(body.toRequestBody(JSON)).build()
        val resp = httpClient.newCall(req).execute()
        val respBody = resp.body?.string() ?: throw CloudException("Empty response")
        if (!resp.isSuccessful) return@withContextSafe Result.failure(CloudException("Exec failed: HTTP ${resp.code}: $respBody"))
        Result.success(json.decodeFromString(ExecResponse.serializer(), respBody))
    }

    fun stream(command: String, cwd: String? = null): Flow<StreamMessage> = flow {
        val url = baseUrl ?: return@flow
        val encodedCmd = URLEncoder.encode(command, "UTF-8")
        val reqBuilder = Request.Builder().url("$url/stream?command=$encodedCmd").header("Accept", "application/x-ndjson")
        authToken?.let { reqBuilder.header("X-PocketAgent-Token", it) }
        val req = reqBuilder.get().build()
        try {
            streamClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) { emit(StreamMessage(type = "error", message = "Stream failed: HTTP ${resp.code}")); return@use }
                val reader = BufferedReader(resp.body?.charStream() ?: return@use)
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    try { emit(json.decodeFromString(StreamMessage.serializer(), line)) } catch (_: Exception) {}
                }
            }
        } catch (e: IOException) {
            emit(StreamMessage(type = "error", message = e.message ?: "Network error"))
        }
    }.flowOn(Dispatchers.IO)

    suspend fun listProcesses(): Result<ProcessListResponse> = withContextSafe {
        val url = baseUrl ?: return@withContextSafe Result.failure(CloudException("No cloud URL"))
        val req = buildRequest("$url/proc/list").post("{}".toRequestBody(JSON)).build()
        val resp = httpClient.newCall(req).execute()
        Result.success(json.decodeFromString(ProcessListResponse.serializer(), resp.body?.string() ?: "{}"))
    }

    suspend fun killProcess(pid: Long): Result<KillResponse> = withContextSafe {
        val url = baseUrl ?: return@withContextSafe Result.failure(CloudException("No cloud URL"))
        val body = json.encodeToString(KillRequest.serializer(), KillRequest(pid))
        val req = buildRequest("$url/proc/kill").post(body.toRequestBody(JSON)).build()
        val resp = httpClient.newCall(req).execute()
        Result.success(json.decodeFromString(KillResponse.serializer(), resp.body?.string() ?: "{}"))
    }

    suspend fun readFile(path: String): Result<FileReadResponse> = withContextSafe {
        val url = baseUrl ?: return@withContextSafe Result.failure(CloudException("No cloud URL"))
        val body = json.encodeToString(FileReadRequest.serializer(), FileReadRequest(path))
        val req = buildRequest("$url/files/read").post(body.toRequestBody(JSON)).build()
        val resp = httpClient.newCall(req).execute()
        Result.success(json.decodeFromString(FileReadResponse.serializer(), resp.body?.string() ?: "{}"))
    }

    suspend fun writeFile(path: String, content: String): Result<FileWriteResponse> = withContextSafe {
        val url = baseUrl ?: return@withContextSafe Result.failure(CloudException("No cloud URL"))
        val body = json.encodeToString(FileWriteRequest.serializer(), FileWriteRequest(path, content))
        val req = buildRequest("$url/files/write").post(body.toRequestBody(JSON)).build()
        val resp = httpClient.newCall(req).execute()
        Result.success(json.decodeFromString(FileWriteResponse.serializer(), resp.body?.string() ?: "{}"))
    }

    suspend fun listFiles(path: String = "~"): Result<FileListResponse> = withContextSafe {
        val url = baseUrl ?: return@withContextSafe Result.failure(CloudException("No cloud URL"))
        val body = json.encodeToString(FileListRequest.serializer(), FileListRequest(path))
        val req = buildRequest("$url/files/list").post(body.toRequestBody(JSON)).build()
        val resp = httpClient.newCall(req).execute()
        Result.success(json.decodeFromString(FileListResponse.serializer(), resp.body?.string() ?: "{}"))
    }

    suspend fun statFile(path: String): Result<FileStatResponse> = withContextSafe {
        val url = baseUrl ?: return@withContextSafe Result.failure(CloudException("No cloud URL"))
        val body = json.encodeToString(FileStatRequest.serializer(), FileStatRequest(path))
        val req = buildRequest("$url/files/stat").post(body.toRequestBody(JSON)).build()
        val resp = httpClient.newCall(req).execute()
        Result.success(json.decodeFromString(FileStatResponse.serializer(), resp.body?.string() ?: "{}"))
    }

    suspend fun mkdir(path: String): Result<FileMkdirResponse> = withContextSafe {
        val url = baseUrl ?: return@withContextSafe Result.failure(CloudException("No cloud URL"))
        val body = json.encodeToString(FileMkdirRequest.serializer(), FileMkdirRequest(path))
        val req = buildRequest("$url/files/mkdir").post(body.toRequestBody(JSON)).build()
        val resp = httpClient.newCall(req).execute()
        Result.success(json.decodeFromString(FileMkdirResponse.serializer(), resp.body?.string() ?: "{}"))
    }

    suspend fun deleteFile(path: String): Result<FileDeleteResponse> = withContextSafe {
        val url = baseUrl ?: return@withContextSafe Result.failure(CloudException("No cloud URL"))
        val body = json.encodeToString(FileDeleteRequest.serializer(), FileDeleteRequest(path))
        val req = buildRequest("$url/files/delete").post(body.toRequestBody(JSON)).build()
        val resp = httpClient.newCall(req).execute()
        Result.success(json.decodeFromString(FileDeleteResponse.serializer(), resp.body?.string() ?: "{}"))
    }

    // ─── Internals ───────────────────────────────────────────────

    private fun buildRequest(path: String): Request.Builder {
        val builder = Request.Builder().url(path).header("Accept", "application/json")
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

    companion object { val JSON = "application/json".toMediaType() }
}

class CloudException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
