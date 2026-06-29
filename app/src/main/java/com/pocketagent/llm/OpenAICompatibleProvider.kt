package com.pocketagent.llm

import com.pocketagent.storage.prefs.ProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

/**
 * OpenAI-compatible LLM provider.
 *
 * Works with:
 * - OpenAI (api.openai.com/v1)
 * - OpenRouter (openrouter.ai/api/v1)
 * - Z.ai (open.bigmodel.cn/api/paas/v4) — OpenAI-compatible endpoint
 * - User's custom gateway (api.gateway.orgn.com/v1)
 * - Local LLMs: Ollama, vLLM, LM Studio (all expose /v1/chat/completions)
 * - DeepSeek, Groq, Together, Fireworks, Anyscale, etc.
 *
 * Verified against the user's gateway with real API calls — see
 * /home/z/my-project/scripts/test_gateway_full.sh for the test transcript.
 */
class OpenAICompatibleProvider(
    private val config: ProviderConfig,
    private val httpClient: OkHttpClient = defaultHttpClient()
) : LlmProvider {

    override val id: String = "openai_compat_${config.id}"

    override val displayName: String = config.displayName

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

    private val baseUrl: String = config.baseUrl.trimEnd('/')

    override suspend fun listModels(): List<ModelInfo> {
        val req = Request.Builder()
            .url("$baseUrl/models")
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Accept", "application/json")
            .get()
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw LlmException("List models failed: HTTP ${resp.code} ${resp.message}")
            }
            val body = resp.body?.string()
                ?: throw LlmException("Empty response body")
            val parsed = json.parseToJsonElement(body).jsonObject
            val data = parsed["data"]?.jsonArray
                ?: throw LlmException("No 'data' field in /models response")
            return data.mapNotNull { el ->
                val obj = el.jsonObject
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                ModelInfo(
                    id = id,
                    displayName = obj["display_name"]?.jsonPrimitive?.contentOrNull
                        ?: obj["name"]?.jsonPrimitive?.contentOrNull
                        ?: id,
                    ownedBy = obj["owned_by"]?.jsonPrimitive?.contentOrNull,
                    maxInputTokens = obj["max_input_tokens"]?.jsonPrimitive?.intOrNull,
                    maxOutputTokens = obj["max_output_tokens"]?.jsonPrimitive?.intOrNull,
                    inputModalities = obj["input_modalities"]?.jsonArray?.mapNotNull { m ->
                        m.jsonPrimitive.contentOrNull
                    } ?: emptyList(),
                    outputModalities = obj["output_modalities"]?.jsonArray?.mapNotNull { m ->
                        m.jsonPrimitive.contentOrNull
                    } ?: emptyList()
                )
            }
        }
    }

    override fun stream(request: LlmRequest): Flow<StreamDelta> = callbackFlow {
        val payload = buildPayload(request, stream = true)
        val req = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        val factory = EventSources.createFactory(httpClient)
        val source = factory.newEventSource(req, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    trySend(StreamDelta.Finish(reason = "stop"))
                    eventSource.cancel()
                    return
                }
                try {
                    val chunk = json.parseToJsonElement(data).jsonObject
                    parseChunk(chunk)?.forEach { trySend(it) }
                } catch (e: Exception) {
                    // Ignore malformed chunks but log
                    trySend(StreamDelta.Error("Failed to parse chunk: ${e.message}", e))
                }
            }

            override fun onClosed(eventSource: EventSource) {
                if (!isClosedForSend) {
                    trySend(StreamDelta.Finish(reason = "closed"))
                }
                channel.close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val body = try { response?.body?.string() } catch (_: Exception) { null }

                // Check if this is a transient stream reset (HTTP 200 = server accepted request
                // but stream was interrupted). These are common on mobile networks and should
                // NOT be treated as fatal errors.
                val isTransientReset = response?.code == 200 &&
                    (t?.message?.contains("reset") == true ||
                     t?.message?.contains("CANCEL") == true ||
                     t?.message?.contains("closed") == true ||
                     t == null)

                if (isTransientReset) {
                    // Stream was interrupted but the request was valid.
                    // Treat as a clean finish — the caller can retry if needed.
                    if (!isClosedForSend) {
                        trySend(StreamDelta.Finish(reason = "stream_reset"))
                    }
                    channel.close()
                } else {
                    val msg = buildString {
                        append("Stream failed: ")
                        append(t?.message ?: t?.let { it::class.simpleName } ?: "unknown error")
                        if (response != null) {
                            append(" (HTTP ${response.code}")
                            if (body != null) {
                                // Truncate long error bodies
                                val truncatedBody = if (body.length > 500) body.substring(0, 500) + "…" else body
                                append(": $truncatedBody")
                            }
                            append(")")
                        }
                    }
                    channel.close(LlmException(msg, t))
                }
            }
        })

        awaitClose {
            source.cancel()
        }
    }.flowOn(Dispatchers.IO)

    private fun parseChunk(chunk: JsonObject): List<StreamDelta>? {
        val choices = chunk["choices"]?.jsonArray ?: return null
        if (choices.isEmpty()) {
            // Usage-only chunk
            val usage = chunk["usage"]?.jsonObject
            if (usage != null) {
                val pt = usage["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0
                val ct = usage["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0
                return listOf(StreamDelta.Usage(pt, ct))
            }
            return null
        }
        val choice = choices[0].jsonObject
        val delta = choice["delta"]?.jsonObject
        val finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
        val result = mutableListOf<StreamDelta>()

        if (delta != null) {
            // Regular content
            val content = delta["content"]?.jsonPrimitive?.contentOrNull
            if (!content.isNullOrEmpty()) {
                result.add(StreamDelta.Content(content))
            }
            // Reasoning content (e.g. GLM, DeepSeek-R1)
            val reasoning = (delta["reasoning_content"] ?: delta["reasoning"])?.jsonPrimitive?.contentOrNull
            if (!reasoning.isNullOrEmpty()) {
                result.add(StreamDelta.Reasoning(reasoning))
            }
            // Tool calls
            val toolCalls = delta["tool_calls"]?.jsonArray
            if (toolCalls != null) {
                for (tcEl in toolCalls) {
                    val tc = tcEl.jsonObject
                    val index = tc["index"]?.jsonPrimitive?.intOrNull ?: 0
                    val id = tc["id"]?.jsonPrimitive?.contentOrNull
                    val fn = tc["function"]?.jsonObject
                    val name = fn?.get("name")?.jsonPrimitive?.contentOrNull
                    val args = fn?.get("arguments")?.jsonPrimitive?.contentOrNull ?: ""
                    if (id != null || name != null || args.isNotEmpty()) {
                        result.add(StreamDelta.ToolCall(index, id, name, args))
                    }
                }
            }
        }

        if (finishReason != null) {
            result.add(StreamDelta.Finish(finishReason))
        }
        return result
    }

    private fun buildPayload(request: LlmRequest, stream: Boolean): String {
        val messagesArray = buildJsonArray {
            for (msg in request.messages) {
                add(buildJsonObject {
                    put("role", when (msg.role) {
                        ChatMessage.Role.System -> "system"
                        ChatMessage.Role.User -> "user"
                        ChatMessage.Role.Assistant -> "assistant"
                        ChatMessage.Role.Tool -> "tool"
                    })
                    // Content: either string, multimodal parts, or null
                    when {
                        msg.contentParts != null && msg.contentParts.isNotEmpty() -> {
                            putJsonArray("content") {
                                for (part in msg.contentParts) {
                                    add(when (part) {
                                        is ChatMessage.ContentPart.Text -> buildJsonObject {
                                            put("type", "text")
                                            put("text", part.text)
                                        }
                                        is ChatMessage.ContentPart.Image -> buildJsonObject {
                                            put("type", "image_url")
                                            putJsonObject("image_url") {
                                                put("url", "data:${part.mimeType};base64,${part.base64}")
                                                if (part.detail != "auto") put("detail", part.detail)
                                            }
                                        }
                                    })
                                }
                            }
                        }
                        msg.content != null -> put("content", msg.content)
                        else -> put("content", JsonPrimitive(null as String?))
                    }
                    if (msg.name != null) put("name", msg.name)
                    if (msg.toolCallId != null) put("tool_call_id", msg.toolCallId)
                    if (msg.toolCalls.isNotEmpty()) {
                        putJsonArray("tool_calls") {
                            for (tc in msg.toolCalls) {
                                add(buildJsonObject {
                                    put("id", tc.id)
                                    put("type", "function")
                                    putJsonObject("function") {
                                        put("name", tc.name)
                                        put("arguments", tc.arguments)
                                    }
                                })
                            }
                        }
                    }
                })
            }
        }

        val obj = buildJsonObject {
            put("model", request.model)
            put("messages", messagesArray)
            put("temperature", request.temperature)
            put("top_p", request.topP)
            if (request.maxTokens != null) put("max_tokens", request.maxTokens)
            put("stream", stream)
            if (request.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    for (tool in request.tools) {
                        add(buildJsonObject {
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", json.parseToJsonElement(tool.parameters))
                            }
                        })
                    }
                }
            }
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    companion object {
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
}

class LlmException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
