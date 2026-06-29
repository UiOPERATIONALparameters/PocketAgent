package com.pocketagent.agent.tools

import com.pocketagent.llm.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes tool calls to their implementations.
 * All tools are injected via Hilt and registered here.
 *
 * CRITICAL: All tool execution is forced onto the IO dispatcher to prevent
 * NetworkOnMainThreadException (web_fetch, web_search) and to keep the UI
 * responsive (bash, file operations).
 */
@Singleton
class ToolRouter @Inject constructor(
    private val bashTool: BashTool,
    private val fileReadTool: FileReadTool,
    private val fileWriteTool: FileWriteTool,
    private val fileListTool: FileListTool,
    private val webFetchTool: WebFetchTool,
    private val webSearchTool: WebSearchTool
) {
    private val tools: Map<String, AgentTool> = mapOf(
        bashTool.name to bashTool,
        fileReadTool.name to fileReadTool,
        fileWriteTool.name to fileWriteTool,
        fileListTool.name to fileListTool,
        webFetchTool.name to webFetchTool,
        webSearchTool.name to webSearchTool
    )

    fun specs(): List<ToolSpec> = listOf(
        bashTool.toSpec(),
        fileReadTool.toSpec(),
        fileWriteTool.toSpec(),
        fileListTool.toSpec(),
        webFetchTool.toSpec(),
        webSearchTool.toSpec()
    )

    /**
     * Returns specs for a subset of tools (for token save mode).
     */
    fun specsForMode(tokenSaveMode: Boolean): List<ToolSpec> {
        return if (tokenSaveMode) {
            // Token save mode: no tools, just chat
            emptyList()
        } else {
            specs()
        }
    }

    suspend fun execute(toolName: String, arguments: JsonElement): ToolResult {
        val tool = tools[toolName]
            ?: return ToolResult.Error("Unknown tool: $toolName")
        return try {
            withContext(Dispatchers.IO) {
                tool.execute(arguments)
            }
        } catch (e: Exception) {
            ToolResult.Error("Tool execution failed: ${e.message ?: e::class.simpleName}")
        }
    }
}
