package com.pocketagent.agent.tools

import com.pocketagent.llm.ChatMessage
import com.pocketagent.llm.LlmProvider
import com.pocketagent.llm.LlmRequest
import com.pocketagent.llm.StreamDelta
import com.pocketagent.llm.ToolSpec
import com.pocketagent.storage.ActiveProviderHolder
import com.pocketagent.storage.prefs.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * spawn_subagent tool — lets the agent delegate a subtask to a sub-agent.
 *
 * Modeled after z.ai's Task tool. The sub-agent:
 *   - Gets its own LLM call with a focused system prompt
 *   - Has NO tools (it's a research/text-generation sub-agent)
 *   - Returns its response as the tool result
 *
 * Use cases:
 *   - "Research X while I work on Y" (parallel research)
 *   - "Generate a summary of this long text"
 *   - "Write a draft of section 3 while I edit section 2"
 *
 * Note: This is a simplified version. Full parallel sub-agents (like z.ai's
 * Task tool with subagent_type) will come in v4.0.
 */
@Singleton
class SpawnSubagentTool @Inject constructor(
    private val providerHolder: ActiveProviderHolder,
    private val settings: SettingsRepository
) : AgentTool {

    override val name = "spawn_subagent"
    override val description = """
        Spawn a sub-agent to handle a focused subtask. The sub-agent gets its own LLM call
        with a minimal system prompt and NO tools. Use for research, summarization, drafting,
        or any focused text task that doesn't need tool access.

        Returns the sub-agent's complete response as the tool result.

        Example: spawn_subagent(prompt="Research the history of Linux and write a 3-paragraph summary")
    """.trimIndent()

    override val parametersSchema = """
        {
          "type": "object",
          "properties": {
            "prompt": {
              "type": "string",
              "description": "The task prompt for the sub-agent"
            },
            "system_prompt": {
              "type": "string",
              "description": "Optional custom system prompt for the sub-agent. Defaults to 'You are a helpful research assistant.'"
            }
          },
          "required": ["prompt"]
        }
    """.trimIndent()

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    override suspend fun execute(arguments: kotlinx.serialization.json.JsonElement): ToolResult {
        val obj = arguments as? JsonObject
            ?: return ToolResult.Error("Arguments must be a JSON object")
        val prompt = obj["prompt"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("Missing 'prompt' parameter")
        val systemPrompt = obj["system_prompt"]?.jsonPrimitive?.contentOrNull
            ?: "You are a helpful research assistant. Be concise and accurate."

        val provider = providerHolder.get()
            ?: return ToolResult.Error("No LLM provider configured")
        val modelId = settings.settings.value.activeModelId
            ?: return ToolResult.Error("No model selected")

        return try {
            val request = LlmRequest(
                model = modelId,
                messages = listOf(
                    ChatMessage(role = ChatMessage.Role.System, content = systemPrompt),
                    ChatMessage(role = ChatMessage.Role.User, content = prompt)
                ),
                tools = emptyList(),  // sub-agents have NO tools
                temperature = 0.5f  // lower temperature for focused work
            )

            val responseBuilder = StringBuilder()
            provider.stream(request).collect { delta ->
                when (delta) {
                    is StreamDelta.Content -> responseBuilder.append(delta.text)
                    is StreamDelta.Error -> return@collect
                    else -> {}
                }
            }

            val response = responseBuilder.toString()
            if (response.isBlank()) {
                ToolResult.Error("Sub-agent returned empty response")
            } else {
                val output = buildJsonObject {
                    put("response", response)
                    put("prompt", prompt.take(200))
                    put("model", modelId)
                }
                ToolResult.Success(output, response)
            }
        } catch (e: Exception) {
            ToolResult.Error("Sub-agent failed: ${e.message ?: e::class.simpleName}")
        }
    }

    fun toSpec(): ToolSpec = ToolSpec(name, description, parametersSchema)
}
