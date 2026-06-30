package com.pocketagent.agent.tools

import com.pocketagent.llm.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * web_reader tool — fetches a URL and extracts clean article text.
 *
 * Unlike web_fetch (which returns raw HTML), web_reader strips out navigation,
 * ads, scripts, and styling — returning only the main article content.
 *
 * This is modeled after z.ai's web-reader skill and Mozilla Readability.
 * Uses a simple HTML-to-text extraction (no external dependency needed).
 */
@Singleton
class WebReaderTool @Inject constructor(
    private val httpClient: OkHttpClient
) : AgentTool {

    override val name = "web_reader"
    override val description = """
        Fetch a URL and extract clean article text (strips HTML, ads, navigation).
        Returns title + main content as plain text. Best for reading articles, docs, blog posts.
        For raw HTML or API responses, use web_fetch instead.
    """.trimIndent()

    override val parametersSchema = """
        {
          "type": "object",
          "properties": {
            "url": {
              "type": "string",
              "description": "The URL to read"
            }
          },
          "required": ["url"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: kotlinx.serialization.json.JsonElement): ToolResult {
        val obj = arguments as? JsonObject
            ?: return ToolResult.Error("Arguments must be a JSON object")
        val url = obj["url"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("Missing 'url' parameter")

        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml,text/plain,*/*")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return ToolResult.Error("HTTP ${response.code}: ${response.message}")
                }

                val html = response.body?.string() ?: ""
                val contentType = response.header("Content-Type") ?: "text/html"

                // If it's already plain text, return as-is
                if (contentType.contains("text/plain") || contentType.contains("application/json")) {
                    val truncated = if (html.length > 50_000) html.substring(0, 50_000) + "\n...[truncated]" else html
                    val output = buildJsonObject {
                        put("url", url)
                        put("title", extractTitle(html))
                        put("content", truncated)
                        put("content_type", JsonPrimitive(contentType))
                        put("chars", JsonPrimitive(html.length))
                    }
                    return ToolResult.Success(output, truncated.take(500))
                }

                // Extract clean text from HTML
                val title = extractTitle(html)
                val cleanText = extractMainContent(html)

                val truncated = if (cleanText.length > 50_000) {
                    cleanText.substring(0, 50_000) + "\n...[truncated]"
                } else {
                    cleanText
                }

                val output = buildJsonObject {
                    put("url", url)
                    put("title", title)
                    put("content", truncated)
                    put("content_type", JsonPrimitive(contentType))
                    put("chars", JsonPrimitive(cleanText.length))
                    put("truncated", JsonPrimitive(cleanText.length > 50_000))
                }
                val display = "$title\n$url\n(${cleanText.length} chars)"
                return ToolResult.Success(output, display)
            }
        } catch (e: java.net.MalformedURLException) {
            return ToolResult.Error("Invalid URL: ${e.message}. Make sure to include https://")
        } catch (e: java.net.UnknownHostException) {
            return ToolResult.Error("Unknown host: ${e.message}. Check the URL and network connection.")
        } catch (e: java.net.SocketTimeoutException) {
            return ToolResult.Error("Request timed out: ${e.message}")
        } catch (e: Exception) {
            return ToolResult.Error("Failed to read page: ${e.message ?: e::class.simpleName}")
        }
    }

    /** Extract the <title> from HTML. */
    private fun extractTitle(html: String): String {
        val titleRegex = Regex("<title[^>]*>(.*?)</title>", RegexOption.DOT_MATCHES_ALL or RegexOption.IGNORE_CASE)
        val match = titleRegex.find(html)
        return match?.groupValues?.get(1)
            ?.replace(Regex("<[^>]+>"), "")
            ?.replace("&amp;", "&")
            ?.replace("&lt;", "<")
            ?.replace("&gt;", ">")
            ?.replace("&quot;", "\"")
            ?.replace("&#39;", "'")
            ?.trim()
            ?: ""
    }

    /**
     * Extract main content from HTML, stripping scripts, styles, navigation, etc.
     * Simple heuristic approach (no external dependency needed).
     */
    private fun extractMainContent(html: String): String {
        var text = html

        // Remove everything before <body> if present
        val bodyMatch = Regex("<body[^>]*>(.*)</body>", RegexOption.DOT_MATCHES_ALL and RegexOption.IGNORE_CASE).find(text)
        if (bodyMatch != null) {
            text = bodyMatch.groupValues[1]
        }

        // Remove script and style blocks
        text = text.replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL and RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL and RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("<nav[^>]*>.*?</nav>", RegexOption.DOT_MATCHES_ALL and RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("<header[^>]*>.*?</header>", RegexOption.DOT_MATCHES_ALL and RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("<footer[^>]*>.*?</footer>", RegexOption.DOT_MATCHES_ALL and RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("<aside[^>]*>.*?</aside>", RegexOption.DOT_MATCHES_ALL and RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("<noscript[^>]*>.*?</noscript>", RegexOption.DOT_MATCHES_ALL and RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

        // Try to find <article> or <main> content
        val articleMatch = Regex("<article[^>]*>(.*?)</article>", RegexOption.DOT_MATCHES_ALL and RegexOption.IGNORE_CASE).find(text)
        if (articleMatch != null) {
            text = articleMatch.groupValues[1]
        } else {
            val mainMatch = Regex("<main[^>]*>(.*?)</main>", RegexOption.DOT_MATCHES_ALL and RegexOption.IGNORE_CASE).find(text)
            if (mainMatch != null) {
                text = mainMatch.groupValues[1]
            }
        }

        // Convert <p>, <br>, <div> to newlines
        text = text.replace(Regex("</(p|div|h[1-6]|li|tr|blockquote)>", RegexOption.IGNORE_CASE), "\n")
        text = text.replace(Regex("<br\s*/?>", RegexOption.IGNORE_CASE), "\n")
        text = text.replace(Regex("<li[^>]*>", RegexOption.IGNORE_CASE), "\n• ")

        // Remove all remaining tags
        text = text.replace(Regex("<[^>]+>"), "")

        // Decode HTML entities
        text = text.replace("&amp;", "&")
        text = text.replace("&lt;", "<")
        text = text.replace("&gt;", ">")
        text = text.replace("&quot;", "\"")
        text = text.replace("&#39;", "'")
        text = text.replace("&nbsp;", " ")
        text = text.replace("&mdash;", "—")
        text = text.replace("&ndash;", "–")
        text = text.replace("&hellip;", "…")

        // Clean up whitespace
        text = text.replace(Regex("[ \t]+"), " ")
        text = text.replace(Regex("\n[ \t]+"), "\n")
        text = text.replace(Regex("\n{3,}"), "\n\n")
        text = text.trim()

        return text
    }

    fun toSpec(): ToolSpec = ToolSpec(name, description, parametersSchema)
}
