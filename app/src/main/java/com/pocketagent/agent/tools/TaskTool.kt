package com.pocketagent.agent.tools

import com.pocketagent.agent.AgentLoop
import com.pocketagent.agent.state.SubagentManager
import com.pocketagent.llm.ToolSpec
import com.pocketagent.storage.ActiveProviderHolder
import com.pocketagent.storage.prefs.SettingsRepository
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * task tool — spawn a subagent to handle a specific task.
 *
 * The subagent runs in its own context window with its own tool budget.
 * Only the subagent's final summary is returned to the parent — the parent's
 * context is not polluted by the subagent's tool calls.
 *
 * This mirrors z.ai agentic mode's Task tool and Claude Code's Task tool.
 *
 * Use cases:
 *   - "Research X and report back" — subagent does the research, returns summary
 *   - "Refactor this file" — subagent does the work, returns what changed
 *   - "Find all places that do X" — subagent searches, returns list
 *
 * The subagent has access to all the same tools (bash, file_*, grep, etc.) but
 * with a smaller tool budget (default 15 vs parent's 50).
 */
@Singleton
class TaskTool @Inject constructor(
    private val subagentManager: SubagentManager,
    private val agentLoop: AgentLoop,
    private val settings: SettingsRepository,
    private val activeProviderHolder: ActiveProviderHolder
) : AgentTool {

    override val name = "task"
    override val description = """
        Spawn a subagent to handle a specific task in its own context window.
        The subagent runs autonomously with its own tool budget (default 15 iterations).
        Only the subagent's final summary is returned — your context is not polluted.

        Use this for:
        - Research tasks (gather info, return summary)
        - Refactoring tasks (make changes, report what was done)
        - Search tasks (find things, return list)
        - Any task that would consume many tool calls

        The subagent inherits your provider+model. It has full workspace access.
        Returns: the subagent's final summary as a string.
    """.trimIndent()

    override val parametersSchema = """
        {
          "type": "object",
          "properties": {
            "description": {
              "type": "string",
              "description": "Short description of the task (shown in UI, max 60 chars)"
            },
            "prompt": {
              "type": "string",
              "description": "The full task prompt for the subagent. Be specific about what to do, what to return, and any constraints."
            },
            "max_iterations": {
              "type": "integer",
              "description": "Max tool calls the subagent can make (default 15, max 30)",
              "default": 15
            }
          },
          "required": ["description", "prompt"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: JsonElement): ToolResult {
        val obj = arguments as? JsonObject
            ?: return ToolResult.Error("Arguments must be a JSON object", "Pass a JSON object with 'description' and 'prompt'.")
        val description = obj["description"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("Missing 'description'", "Provide a short description.")
        val prompt = obj["prompt"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("Missing 'prompt'", "Provide the full task prompt.")
        val maxIter = obj["max_iterations"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(1, 30) ?: 15

        if (!settings.settings.value.enableSubagents) {
            return ToolResult.Error("Subagents disabled", "Enable subagents in Settings to use this tool.")
        }

        val provider = activeProviderHolder.get()
            ?: return ToolResult.Error("No provider configured", "Set up an LLM provider in Settings first.")
        val modelId = settings.settings.value.activeModelId
            ?: return ToolResult.Error("No model selected", "Pick a model in Settings first.")

        // We need the parent conversation ID. The TaskTool doesn't have direct access to it.
        // In v6, we pass it via a thread-local or via the AgentLoop's context.
        // For simplicity, we use a special "current conversation" holder set by ChatViewModel.
        val parentConvId = CurrentConversationHolder.get()
            ?: return ToolResult.Error("No active conversation", "This tool can only be called from within an active agent loop.")

        val result = subagentManager.spawn(
            parentConversationId = parentConvId,
            provider = provider,
            modelId = modelId,
            description = description,
            prompt = prompt,
            agentLoop = agentLoop,
            maxIterations = maxIter
        )

        val summary = result.getOrElse { e ->
            val output = buildJsonObject {
                put("status", JsonPrimitive("failed"))
                put("error", JsonPrimitive(e.message ?: e::class.simpleName ?: "Unknown error"))
            }
            return ToolResult.Error("Subagent failed: ${e.message}", "Check the subagent's error and retry with a clearer prompt.")
        }

        val output = buildJsonObject {
            put("status", JsonPrimitive("completed"))
            put("summary", JsonPrimitive(summary))
            put("description", JsonPrimitive(description))
        }
        return ToolResult.Success(output, "Subagent completed:\n$summary")
    }

    fun toSpec(): ToolSpec = ToolSpec(name, description, parametersSchema)
}

/**
 * Thread-local holder for the current conversation ID.
 * Set by ChatViewModel when the agent loop starts; read by TaskTool.
 */
object CurrentConversationHolder {
    @Volatile
    private var currentId: String? = null

    fun set(id: String?) { currentId = id }
    fun get(): String? = currentId
}
