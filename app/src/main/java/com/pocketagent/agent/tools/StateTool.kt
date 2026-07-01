package com.pocketagent.agent.tools

import com.pocketagent.agent.state.StateStore
import com.pocketagent.llm.ToolSpec
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
 * state tool — read/write the persistent scratchpad.
 *
 * The scratchpad is a markdown file at ~/.pocketagent/scratchpad.md that survives
 * across conversations and app restarts. Use it to remember:
 *   - File locations you've discovered
 *   - Decisions you've made
 *   - Things you tried that didn't work
 *   - Next steps for the current task
 *
 * This is the z.ai "worklog.md" pattern: persistent memory that the agent
 * can update and that gets injected into every new conversation.
 */
@Singleton
class StateTool @Inject constructor(
    private val stateStore: StateStore
) : AgentTool {

    override val name = "state"
    override val description = """
        Read or write the persistent scratchpad at ~/.pocketagent/scratchpad.md.

        The scratchpad survives across conversations and app restarts. It is
        injected into every new conversation's system prompt.

        Use it to remember:
        - File locations you've discovered
        - Decisions you've made
        - Things you tried that didn't work
        - Next steps for the current task

        Actions:
        - "read": return the current scratchpad content
        - "write": overwrite the scratchpad with new content
        - "append": append to the scratchpad

        Use this tool whenever you learn something worth remembering for future sessions.
    """.trimIndent()

    override val parametersSchema = """
        {
          "type": "object",
          "properties": {
            "action": {
              "type": "string",
              "enum": ["read", "write", "append"],
              "description": "What to do"
            },
            "content": {
              "type": "string",
              "description": "Content to write or append (required for write/append)"
            }
          },
          "required": ["action"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: JsonElement): ToolResult {
        val obj = arguments as? JsonObject
            ?: return ToolResult.Error("Arguments must be a JSON object", "Pass a JSON object with 'action'.")
        val action = obj["action"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("Missing 'action'", "Provide an 'action' field: read, write, or append.")
        val content = obj["content"]?.jsonPrimitive?.contentOrNull

        when (action) {
            "read" -> {
                val current = stateStore.getScratchpad()
                val output = buildJsonObject {
                    put("action", JsonPrimitive("read"))
                    put("content", JsonPrimitive(current))
                    put("bytes", JsonPrimitive(current.length))
                }
                return ToolResult.Success(output, current.ifBlank { "(scratchpad is empty)" })
            }
            "write" -> {
                if (content == null) return ToolResult.Error("Missing 'content' for write action", "Provide a 'content' field.")
                stateStore.setScratchpad(content)
                val output = buildJsonObject {
                    put("action", JsonPrimitive("write"))
                    put("bytes", JsonPrimitive(content.length))
                }
                return ToolResult.Success(output, "Scratchpad updated (${content.length} bytes)")
            }
            "append" -> {
                if (content == null) return ToolResult.Error("Missing 'content' for append action", "Provide a 'content' field.")
                val current = stateStore.getScratchpad()
                val new = current + "\n\n" + content
                stateStore.setScratchpad(new)
                val output = buildJsonObject {
                    put("action", JsonPrimitive("append"))
                    put("appended_bytes", JsonPrimitive(content.length))
                    put("total_bytes", JsonPrimitive(new.length))
                }
                return ToolResult.Success(output, "Appended ${content.length} bytes to scratchpad")
            }
            else -> {
                return ToolResult.Error("Unknown action: $action", "Use 'read', 'write', or 'append'.")
            }
        }
    }

    fun toSpec(): ToolSpec = ToolSpec(name, description, parametersSchema)
}
