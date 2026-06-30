package com.pocketagent.agent.tools

import com.pocketagent.llm.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * web_search tool — searches the web using DuckDuckGo's HTML endpoint.
 * No API key required. Returns search result titles, URLs, and snippets.
 *
 * The AI can use this to find current information, look up documentation,
 * find solutions to problems, etc.
 */
@Singleton
class WebSearchTool @Inject constructor(
    private val httpClient: OkHttpClient
) : AgentTool {

    override val name = "web_search"
    override val description = """
        Search the web for current information. Returns top results with titles, URLs, and snippets.
        Use this to find documentation, solutions, current events, or any information you don't already know.
        No API key required — uses DuckDuckGo.
    """.trimIndent()

    override val parametersSchema = """
        {
          "type": "object",
          "properties": {
            "query": {
              "type": "string",
              "description": "The search query"
            },
            "num_results": {
              "type": "integer",
              "description": "Number of results to return (default 5, max 10)",
              "default": 5
            }
          },
          "required": ["query"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: kotlinx.serialization.json.JsonElement): ToolResult {
        val obj = arguments as? JsonObject
            ?: return ToolResult.Error("Arguments must be a JSON object")
        val query = obj["query"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing 'query' parameter")
        val numResults = (obj["num_results"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 5)
            .coerceIn(1, 10)

        return withContext(Dispatchers.IO) {
            try {
                val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
                val url = "https://html.duckduckgo.com/html/?q=$encodedQuery"

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext ToolResult.Error("Search failed: HTTP ${response.code}")
                    }

                    val html = response.body?.string() ?: ""
                    val results = parseDuckDuckGoHtml(html, numResults)

                    // H11 FIX: 'No results' is a valid response, NOT an error.
                    // Was: return ToolResult.Error("No results found") — this confused the LLM
                    // into retrying with different queries, wasting tokens.
                    val output = buildJsonObject {
                        put("query", query)
                        put("result_count", JsonPrimitive(results.size))
                        putJsonArray("results") {
                            for (result in results) {
                                add(buildJsonObject {
                                    put("title", result.title)
                                    put("url", result.url)
                                    put("snippet", result.snippet)
                                })
                            }
                        }
                    }

                    val display = if (results.isEmpty()) {
                        "No results found for: $query"
                    } else {
                        results.mapIndexed { idx, r ->
                            "${idx + 1}. ${r.title}\n   ${r.url}\n   ${r.snippet}\n"
                        }.joinToString("\n")
                    }

                    ToolResult.Success(output, display)
                }
            } catch (e: Exception) {
                ToolResult.Error("Search failed: ${e.message ?: e::class.simpleName}")
            }
        }
    }

    private data class SearchResult(
        val title: String,
        val url: String,
        val snippet: String
    )

    /**
     * Parse DuckDuckGo HTML search results.
     * DuckDuckGo's HTML endpoint returns results in a simple format.
     */
    private fun parseDuckDuckGoHtml(html: String, maxResults: Int): List<SearchResult> {
        val results = mutableListOf<SearchResult>()

        // DuckDuckGo HTML results have this structure:
        // <a rel="nofollow" class="result__a" href="...">Title</a>
        // <a class="result__snippet" href="...">Snippet</a>

        val titleRegex = Regex("""class="result__a"[^>]*href="([^"]*)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val snippetRegex = Regex("""class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)

        val titles = titleRegex.findAll(html).toList()
        val snippets = snippetRegex.findAll(html).toList()

        for (i in titles.indices) {
            if (results.size >= maxResults) break

            val titleMatch = titles[i]
            val rawUrl = titleMatch.groupValues[1]
            val rawTitle = titleMatch.groupValues[2]

            // DuckDuckGo wraps URLs in a redirect: //duckduckgo.com/l/?uddg=ENCODED_URL
            val url = try {
                if (rawUrl.contains("uddg=")) {
                    val uddgIdx = rawUrl.indexOf("uddg=") + 5
                    val encodedUrl = rawUrl.substring(uddgIdx).split("&")[0]
                    java.net.URLDecoder.decode(encodedUrl, "UTF-8")
                } else {
                    rawUrl
                }
            } catch (_: Exception) {
                rawUrl
            }

            // Clean HTML from title
            val title = rawTitle
                .replace(Regex("<[^>]*>"), "")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .trim()

            // Get snippet if available
            val snippet = if (i < snippets.size) {
                snippets[i].groupValues[1]
                    .replace(Regex("<[^>]*>"), "")
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .replace("&#39;", "'")
                    .trim()
            } else {
                ""
            }

            if (title.isNotEmpty() && url.isNotEmpty()) {
                results.add(SearchResult(title, url, snippet))
            }
        }

        return results
    }

    fun toSpec(): ToolSpec = ToolSpec(name, description, parametersSchema)
}
