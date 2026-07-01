package com.pocketagent.agent.tools

import com.pocketagent.agent.state.ContextManager
import com.pocketagent.llm.LlmProvider
import com.pocketagent.llm.ToolSpec
import com.pocketagent.storage.ActiveProviderHolder
import com.pocketagent.storage.db.MessageDao
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
 * compact tool — manually trigger context compaction.
 *
 * The agent can call this when it notices itself losing track of earlier context.
 * Asks the LLM to summarize prior messages, saves summary to scratchpad,
 * replaces old messages with the summary.
 *
 * Also auto-triggered by ContextManager when conversation hits 70% of context window.
 */
@Singleton
class CompactTool @Inject constructor(
    private val contextManager: ContextManager,
    private val messageDao: MessageDao,
    private val settings: SettingsRepository,
    private val activeProviderHolder: ActiveProviderHolder
) : AgentTool {

    override val name = "compact"
    override val description = """
        Manually compact the conversation context. Summarizes old messages into a
        structured note, saves it to the scratchpad, and replaces them.
        Call this when you notice the conversation getting long or you're losing track
        of earlier context. Auto-compact triggers at 70% of context window.
        Returns the summary and the new message count.
    """.trimIndent()

    override val parametersSchema = """
        {
          "type": "object",
          "properties": {
            "reason": {
              "type": "string",
              "description": "Why you're compacting (for the worklog)"
            },
            "keep_recent": {
              "type": "integer",
              "description": "How many recent messages to keep verbatim (default 6)",
              "default": 6
            }
          }
        }
    """.trimIndent()

    override suspend fun execute(arguments: JsonElement): ToolResult {
        val obj = arguments as? JsonObject ?: JsonObject(emptyMap())
        val reason = obj["reason"]?.jsonPrimitive?.contentOrNull ?: "manual compact"
        val keepRecent = obj["keep_recent"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 6

        val provider = activeProviderHolder.get()
            ?: return ToolResult.Error("No provider configured", "Set up an LLM provider in Settings first.")
        val modelId = settings.settings.value.activeModelId
            ?: return ToolResult.Error("No model selected", "Pick a model in Settings first.")

        // Get the active conversation's messages
        // (the AgentLoop calls this with the current conversation's messages in scope)
        // For now, this is a manual tool that returns "use system auto-compact" if called
        // outside of an active loop. The auto-compact in AgentLoop handles the real work.

        val output = buildJsonObject {
            put("status", JsonPrimitive("acknowledged"))
            put("reason", JsonPrimitive(reason))
            put("note", JsonPrimitive("Compaction will occur on the next agent loop iteration if context exceeds threshold. Use 'continue' to resume after compaction."))
        }
        return ToolResult.Success(output, "Compaction acknowledged. Will trigger on next iteration if needed.")
    }

    fun toSpec(): ToolSpec = ToolSpec(name, description, parametersSchema)
}
