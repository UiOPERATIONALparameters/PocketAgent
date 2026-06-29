package com.pocketagent.agent.tools

import com.pocketagent.llm.ToolSpec
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * web_fetch tool — fetches a URL and returns the response.
 * Limited to GET by default in v0.1; agent can use this for downloading
 * text content, simple API calls.
 *
 * For file downloads (e.g. fetching a binary), the agent should use bash
 * with `curl` once we add Termux bootstrap in v0.2.
 */
@Singleton
class WebFetchTool @Inject constructor(
    private val httpClient: OkHttpClient
) : AgentTool {

    override val name = "web_fetch"
    override val description = """
        Fetch a URL via HTTP. Returns status, headers, and body.
        Defaults to GET. For POST, pass method and body.
        Body is capped at 256KB.
    """.trimIndent()

    override val parametersSchema = """
        {
          "type": "object",
          "properties": {
            "url": {"type": "string", "description": "The URL to fetch"},
            "method": {"type": "string", "default": "GET", "description": "HTTP method"},
            "body": {"type": "string", "description": "Request body (for POST/PUT)"},
            "headers": {"type": "object", "description": "Additional headers as key-value pairs"}
          },
          "required": ["url"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: JsonElement): ToolResult {
        val obj = arguments as? JsonObject
            ?: return ToolResult.Error("Arguments must be a JSON object")
        val url = obj["url"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing 'url' parameter")
        val method = (obj["method"]?.jsonPrimitive?.contentOrNull ?: "GET").uppercase()
        val body = obj["body"]?.jsonPrimitive?.contentOrNull
        val headersObj = obj["headers"] as? JsonObject

        val reqBuilder = Request.Builder().url(url)
        headersObj?.forEach { (k, v) ->
            reqBuilder.header(k, v.jsonPrimitive.content)
        }
        // Default Accept header
        if (headersObj == null || headersObj["Accept"] == null) {
            reqBuilder.header("Accept", "text/html,application/json,text/plain,*/*")
        }
        reqBuilder.header("User-Agent", "PocketAgent/0.1 (Android)")

        when (method) {
            "GET" -> reqBuilder.get()
            "POST" -> reqBuilder.post((body ?: "").toRequestBody())
            "PUT" -> reqBuilder.put((body ?: "").toRequestBody())
            "DELETE" -> reqBuilder.delete()
            "HEAD" -> reqBuilder.head()
            else -> return ToolResult.Error("Unsupported method: $method")
        }

        try {
            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                val respBody = resp.body?.string() ?: ""
                val truncated = respBody.length > 256_000
                val capped = if (truncated) respBody.substring(0, 256_000) else respBody

                val output = buildJsonObject {
                    put("status", JsonPrimitive(resp.code))
                    put("url", url)
                    val headersJson = buildJsonObject {
                        for ((k, v) in resp.headers) {
                            put(k.lowercase(), v)
                        }
                    }
                    put("headers", headersJson)
                    put("body", capped)
                    put("truncated", JsonPrimitive(truncated))
                    put("content_type", JsonPrimitive(resp.header("Content-Type") ?: "unknown"))
                }
                val display = "${resp.code} ${resp.message} (${respBody.length} bytes) <- $method $url"
                return ToolResult.Success(output, display)
            }
        } catch (e: java.net.MalformedURLException) {
            return ToolResult.Error("Invalid URL: ${e.message ?: "malformed URL"}. Make sure to include https://")
        } catch (e: java.net.UnknownHostException) {
            return ToolResult.Error("Unknown host: ${e.message ?: "DNS resolution failed"}. Check the URL and network connection.")
        } catch (e: java.net.SocketTimeoutException) {
            return ToolResult.Error("Request timed out: ${e.message ?: "connection timeout"}")
        } catch (e: javax.net.ssl.SSLException) {
            return ToolResult.Error("SSL error: ${e.message ?: "SSL handshake failed"}")
        } catch (e: java.io.IOException) {
            return ToolResult.Error("Network error: ${e.message ?: e::class.simpleName ?: "IO error"}")
        } catch (e: Exception) {
            val msg = e.message ?: e::class.simpleName ?: "unknown error"
            return ToolResult.Error("Request failed: $msg (${e::class.simpleName ?: "Exception"})")
        }
    }

    fun toSpec(): ToolSpec = ToolSpec(name, description, parametersSchema)
}
