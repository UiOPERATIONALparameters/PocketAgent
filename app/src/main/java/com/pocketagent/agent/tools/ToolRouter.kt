package com.pocketagent.agent.tools

import com.pocketagent.llm.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolRouter @Inject constructor(
    private val bashTool: BashTool,
    private val fileReadTool: FileReadTool,
    private val fileWriteTool: FileWriteTool,
    private val fileListTool: FileListTool,
    private val webFetchTool: WebFetchTool,
    private val webSearchTool: WebSearchTool,
    private val strReplaceTool: StrReplaceTool,
    private val grepTool: GrepTool,
    private val globTool: GlobTool,
    private val installApkTool: InstallApkTool,
    private val loadSkillTool: LoadSkillTool,
    private val webReaderTool: WebReaderTool,
    private val todoTool: TodoTool,
    private val serveHttpTool: ServeHttpTool,
    private val spawnSubagentTool: SpawnSubagentTool
) {
    private val tools: Map<String, AgentTool> = mapOf(
        bashTool.name to bashTool,
        fileReadTool.name to fileReadTool,
        fileWriteTool.name to fileWriteTool,
        fileListTool.name to fileListTool,
        webFetchTool.name to webFetchTool,
        webSearchTool.name to webSearchTool,
        strReplaceTool.name to strReplaceTool,
        grepTool.name to grepTool,
        globTool.name to globTool,
        installApkTool.name to installApkTool,
        loadSkillTool.name to loadSkillTool,
        webReaderTool.name to webReaderTool,
        todoTool.name to todoTool,
        serveHttpTool.name to serveHttpTool,
        spawnSubagentTool.name to spawnSubagentTool
    )

    fun specs(): List<ToolSpec> = listOf(
        bashTool.toSpec(),
        fileReadTool.toSpec(),
        fileWriteTool.toSpec(),
        fileListTool.toSpec(),
        strReplaceTool.toSpec(),
        grepTool.toSpec(),
        globTool.toSpec(),
        webFetchTool.toSpec(),
        webSearchTool.toSpec(),
        webReaderTool.toSpec(),
        loadSkillTool.toSpec(),
        installApkTool.toSpec(),
        todoTool.toSpec(),
        serveHttpTool.toSpec(),
        spawnSubagentTool.toSpec()
    )

    suspend fun execute(toolName: String, arguments: JsonElement): ToolResult {
        val tool = tools[toolName] ?: return ToolResult.Error("Unknown tool: $toolName")
        return try {
            withContext(Dispatchers.IO) { tool.execute(arguments) }
        } catch (e: Exception) {
            ToolResult.Error("Tool execution failed: ${e.message ?: e::class.simpleName}")
        }
    }
}
