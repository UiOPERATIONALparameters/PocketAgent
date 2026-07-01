package com.pocketagent.agent.tools

import com.pocketagent.cloud.CloudState
import com.pocketagent.llm.ToolSpec
import com.pocketagent.storage.prefs.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v7 ToolRouter — supports mode-based tool filtering.
 *
 * TASK mode: all 17 tools (bash, file_*, grep, glob, web_*, task, compact, state, etc.)
 *   - Requires cloud connection
 * CHAT mode: only web_search, web_fetch, web_reader, todo, compact, state
 *   - No cloud needed, just LLM + web
 */
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
    private val compactTool: CompactTool,
    private val taskTool: TaskTool,
    private val stateTool: StateTool,
    private val settings: SettingsRepository,
    private val cloudState: CloudState
) {
    private val allTools: Map<String, AgentTool> = mapOf(
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
        compactTool.name to compactTool,
        taskTool.name to taskTool,
        stateTool.name to stateTool
    )

    /** Tools available in CHAT mode (no cloud needed). */
    private val chatModeTools = setOf(
        "web_search", "web_fetch", "web_reader", "todo", "compact", "state"
    )

    /** Get the active mode. */
    private val isChatMode: Boolean
        get() = settings.settings.value.agentMode == "CHAT"

    /** Get specs for the current mode. */
    fun specs(): List<ToolSpec> {
        val activeNames = if (isChatMode) chatModeTools else allTools.keys
        return allTools.entries
            .filter { it.key in activeNames }
            .map { (name, tool) ->
                when (tool) {
                    is BashTool -> tool.toSpec()
                    is FileReadTool -> tool.toSpec()
                    is FileWriteTool -> tool.toSpec()
                    is FileListTool -> tool.toSpec()
                    is WebFetchTool -> tool.toSpec()
                    is WebSearchTool -> tool.toSpec()
                    is StrReplaceTool -> tool.toSpec()
                    is GrepTool -> tool.toSpec()
                    is GlobTool -> tool.toSpec()
                    is InstallApkTool -> tool.toSpec()
                    is LoadSkillTool -> tool.toSpec()
                    is WebReaderTool -> tool.toSpec()
                    is TodoTool -> tool.toSpec()
                    is ServeHttpTool -> tool.toSpec()
                    is CompactTool -> tool.toSpec()
                    is TaskTool -> tool.toSpec()
                    is StateTool -> tool.toSpec()
                    else -> ToolSpec(tool.name, tool.description, tool.parametersSchema)
                }
            }
    }

    suspend fun execute(toolName: String, arguments: JsonElement): ToolResult {
        val activeNames = if (isChatMode) chatModeTools else allTools.keys
        if (toolName !in activeNames) {
            return ToolResult.Error(
                "Tool '$toolName' not available in ${if (isChatMode) "CHAT" else "TASK"} mode",
                if (isChatMode) "Switch to TASK mode in Settings to use cloud Linux tools."
                else "Available tools: ${activeNames.joinToString(", ")}"
            )
        }
        val tool = allTools[toolName] ?: return ToolResult.Error("Unknown tool: $toolName")
        return try {
            withContext(Dispatchers.IO) { tool.execute(arguments) }
        } catch (e: Exception) {
            ToolResult.Error(
                "Tool execution failed: ${e.message ?: e::class.simpleName}",
                "Try a different approach or report the error."
            )
        }
    }
}
