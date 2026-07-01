package com.pocketagent.cloud

import com.pocketagent.storage.prefs.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages GitHub Codespaces via the GitHub REST API.
 *
 * Uses the user's GitHub Personal Access Token (PAT) with `codespace` scope to:
 *   - List existing codespaces
 *   - Create a new codespace from the PocketAgent-Cloud template repo
 *   - Start/stop a codespace
 *   - Get the codespace's public URL for the daemon
 *
 * The PocketAgent-Cloud template repo: https://github.com/UiOPERATIONALparameters/PocketAgent-Cloud
 */
@Singleton
class CodespacesManager @Inject constructor(
    private val settings: SettingsRepository
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val githubToken: String?
        get() = settings.settings.value.githubToken?.ifBlank { null }

    companion object {
        const val TEMPLATE_OWNER = "UiOPERATIONALparameters"
        const val TEMPLATE_REPO = "PocketAgent-Cloud"
        const val GITHUB_API = "https://api.github.com"
        val JSON = "application/json".toMediaType()
    }


    /** List all the user's codespaces. */
    suspend fun listCodespaces(): Result<CodespaceListResponse> = withContext(Dispatchers.IO) {
        val token = githubToken ?: return@withContext Result.failure(CloudException("No GitHub token configured"))
        try {
            val req = Request.Builder()
                .url("$GITHUB_API/user/codespaces?per_page=50")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(CloudException("GitHub API error: HTTP ${resp.code} ${resp.message}"))
                }
                val body = resp.body?.string() ?: return@withContext Result.failure(CloudException("Empty response"))
                Result.success(json.decodeFromString(CodespaceListResponse.serializer(), body))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Create a codespace from the PocketAgent-Cloud template repo.
     * First forks the template to the user's account (if not already forked),
     * then creates a codespace from it.
     */
    suspend fun createCodespace(): Result<CodespaceInfo> = withContext(Dispatchers.IO) {
        val token = githubToken ?: return@withContext Result.failure(CloudException("No GitHub token configured"))
        try {
            // Step 1: Get the template repo's ID
            val templateReq = Request.Builder()
                .url("$GITHUB_API/repos/$TEMPLATE_OWNER/$TEMPLATE_REPO")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .get().build()
            val templateResp = httpClient.newCall(templateReq).execute()
            if (!templateResp.isSuccessful) {
                return@withContext Result.failure(CloudException("Template repo not found: HTTP ${templateResp.code}"))
            }
            val templateBody = templateResp.body?.string() ?: return@withContext Result.failure(CloudException("Empty response"))
            val templateJson = json.parseToJsonElement(templateBody).asObject()
            val templateId = templateJson.getLong("id")
                ?: return@withContext Result.failure(CloudException("Template repo has no ID"))

            // Step 2: Create codespace from the template
            val createBody = """{"repository_id":$templateId,"machine":"basicLinux32gb"}"""
            val createReq = Request.Builder()
                .url("$GITHUB_API/user/codespaces")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .post(createBody.toRequestBody(JSON)).build()
            httpClient.newCall(createReq).execute().use { resp ->
                val body = resp.body?.string() ?: return@withContext Result.failure(CloudException("Empty response"))
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(CloudException("Create failed: HTTP ${resp.code}: $body"))
                }
                val info = json.decodeFromString(CodespaceInfo.serializer(), body)
                Result.success(info)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Start a codespace (if stopped). */
    suspend fun startCodespace(name: String): Result<Unit> = withContext(Dispatchers.IO) {
        val token = githubToken ?: return@withContext Result.failure(CloudException("No GitHub token"))
        try {
            val req = Request.Builder()
                .url("$GITHUB_API/user/codespaces/$name/start")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .post("{}".toRequestBody(JSON)).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 202) {
                    return@withContext Result.failure(CloudException("Start failed: HTTP ${resp.code}"))
                }
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Stop a codespace. */
    suspend fun stopCodespace(name: String): Result<Unit> = withContext(Dispatchers.IO) {
        val token = githubToken ?: return@withContext Result.failure(CloudException("No GitHub token"))
        try {
            val req = Request.Builder()
                .url("$GITHUB_API/user/codespaces/$name/stop")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .post("{}".toRequestBody(JSON)).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 202) {
                    return@withContext Result.failure(CloudException("Stop failed: HTTP ${resp.code}"))
                }
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Delete a codespace. */
    suspend fun deleteCodespace(name: String): Result<Unit> = withContext(Dispatchers.IO) {
        val token = githubToken ?: return@withContext Result.failure(CloudException("No GitHub token"))
        try {
            val req = Request.Builder()
                .url("$GITHUB_API/user/codespaces/$name")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .delete().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 202) {
                    return@withContext Result.failure(CloudException("Delete failed: HTTP ${resp.code}"))
                }
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Find an existing PocketAgent codespace, or create one if none exists.
     * Returns the codespace name and its public daemon URL.
     */
    suspend fun ensureCodespace(): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        // Try to find an existing codespace from the template repo
        val listResult = listCodespaces()
        if (listResult.isSuccess) {
            val existing = listResult.getOrNull()?.codespaces?.find {
                it.repository?.full_name?.endsWith("/$TEMPLATE_REPO") == true
            }
            if (existing != null) {
                // Make sure it's started
                if (existing.state != "Available") {
                    startCodespace(existing.name)
                    // Wait for it to become available (poll a few times)
                    repeat(10) {
                        Thread.sleep(5000)
                        val updated = getCodespace(existing.name)
                        if (updated?.state == "Available") return@repeat
                    }
                }
                val url = "https://${existing.name}-8765.app.github.dev"
                return@withContext Result.success(existing.name to url)
            }
        }
        // Create a new one
        val createResult = createCodespace()
        if (createResult.isFailure) {
            return@withContext Result.failure(createResult.exceptionOrNull() ?: CloudException("Create failed"))
        }
        val info = createResult.getOrNull()!!
        // Wait for it to be ready
        repeat(12) {
            Thread.sleep(5000)
            val updated = getCodespace(info.name)
            if (updated?.state == "Available") {
                val url = "https://${info.name}-8765.app.github.dev"
                return@withContext Result.success(info.name to url)
            }
        }
        // Even if not "Available" yet, the daemon might be up — return the URL
        val url = "https://${info.name}-8765.app.github.dev"
        Result.success(info.name to url)
    }

    suspend fun getCodespace(name: String): CodespaceInfo? = withContext(Dispatchers.IO) {
        val token = githubToken ?: return@withContext null
        try {
            val req = Request.Builder()
                .url("$GITHUB_API/user/codespaces/$name")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                json.decodeFromString(CodespaceInfo.serializer(), body)
            }
        } catch (_: Exception) { null }
    }
}

// Helper to extract Long from JsonObject
private fun kotlinx.serialization.json.JsonObject.getLong(key: String): Long? {
    val el = this[key] ?: return null
    return (el as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
}

private fun kotlinx.serialization.json.JsonElement.asObject(): kotlinx.serialization.json.JsonObject =
    this as kotlinx.serialization.json.JsonObject
