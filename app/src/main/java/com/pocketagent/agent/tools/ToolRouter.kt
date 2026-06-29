package com.pocketagent.agent.tools

import com.pocketagent.llm.ToolSpec
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes tool calls to their implementations.
 * All tools are injected via Hilt and registered here.
 */
@Singleton
class ToolRouter @Inject constructor(
    private val bashTool: BashTool,
    private val fileReadTool: FileReadTool,
    private val fileWriteTool: FileWriteTool,
    private val fileListTool: FileListTool,
    private val webFetchTool: WebFetchTool
) {
    private val tools: Map<String, AgentTool> = mapOf(
        bashTool.name to bashTool,
        fileReadTool.name to fileReadTool,
        fileWriteTool.name to fileWriteTool,
        fileListTool.name to fileListTool,
        webFetchTool.name to webFetchTool
    )

    fun specs(): List<ToolSpec> = listOf(
        bashTool.toSpec(),
        fileReadTool.toSpec(),
        fileWriteTool.toSpec(),
        fileListTool.toSpec(),
        webFetchTool.toSpec()
    )

    suspend fun execute(toolName: String, arguments: JsonElement): ToolResult {
        val tool = tools[toolName]
            ?: return ToolResult.Error("Unknown tool: $toolName")
        return try {
            tool.execute(arguments)
        } catch (e: Exception) {
            ToolResult.Error("Tool execution failed: ${e.message}")
        }
    }
}
