package com.pocketagent.agent.tools

import com.pocketagent.llm.ToolSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import javax.inject.Inject
import javax.inject.Singleton

/**
 * todo tool — lets the agent track its own multi-step tasks.
 *
 * Modeled after z.ai's TodoWrite tool. The agent can:
 *   - Create a todo list
 *   - Update item status (pending → in_progress → completed)
 *   - View current todos
 *
 * The todos are stored in memory (not persisted) and displayed in the
 * tool result for the agent to see.
 */
@Singleton
class TodoTool @Inject constructor() : AgentTool {

    private val todos = mutableListOf<TodoItem>()

    data class TodoItem(
        val content: String,
        var status: String = "pending"  // pending, in_progress, completed
    )

    override val name = "todo"
    override val description = """
        Manage a todo list for multi-step tasks. Use this to track progress on complex tasks.
        Actions: 'create' (set the full list), 'update' (change status of one item), 'list' (view current).
        Statuses: pending, in_progress, completed.
    """.trimIndent()

    override val parametersSchema = """
        {
          "type": "object",
          "properties": {
            "action": {
              "type": "string",
              "description": "Action: create, update, or list"
            },
            "items": {
              "type": "array",
              "description": "For 'create': array of {content, status} objects",
              "items": {
                "type": "object",
                "properties": {
                  "content": {"type": "string"},
                  "status": {"type": "string", "default": "pending"}
                }
              }
            },
            "index": {
              "type": "integer",
              "description": "For 'update': which item to update (0-based)"
            },
            "status": {
              "type": "string",
              "description": "For 'update': new status"
            }
          },
          "required": ["action"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: kotlinx.serialization.json.JsonElement): ToolResult {
        val obj = arguments as? JsonObject
            ?: return ToolResult.Error("Arguments must be a JSON object")
        val action = obj["action"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("Missing 'action' parameter")

        return when (action) {
            "create" -> {
                val items = obj["items"]?.jsonArray
                    ?: return ToolResult.Error("Missing 'items' for create action")
                todos.clear()
                for (item in items) {
                    val itemObj = item.jsonObject
                    val content = itemObj["content"]?.jsonPrimitive?.contentOrNull ?: continue
                    val status = itemObj["status"]?.jsonPrimitive?.contentOrNull ?: "pending"
                    todos.add(TodoItem(content, status))
                }
                formatResult()
            }
            "update" -> {
                val index = obj["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                    ?: return ToolResult.Error("Missing or invalid 'index'")
                val status = obj["status"]?.jsonPrimitive?.contentOrNull
                    ?: return ToolResult.Error("Missing 'status'")
                if (index < 0 || index >= todos.size) {
                    return ToolResult.Error("Index $index out of range (0..${todos.size - 1})")
                }
                todos[index].status = status
                formatResult()
            }
            "list" -> formatResult()
            else -> ToolResult.Error("Unknown action: $action. Use create, update, or list.")
        }
    }

    private fun formatResult(): ToolResult {
        val display = if (todos.isEmpty()) {
            "No todos. Use action='create' to add items."
        } else {
            todos.mapIndexed { i, item ->
                val icon = when (item.status) {
                    "completed" -> "✓"
                    "in_progress" -> "→"
                    else -> "○"
                }
                "$icon ${i + 1}. ${item.content}"
            }.joinToString("\n")
        }

        val output = buildJsonObject {
            put("count", JsonPrimitive(todos.size))
            putJsonArray("todos") {
                for ((i, item) in todos.withIndex()) {
                    add(buildJsonObject {
                        put("index", JsonPrimitive(i))
                        put("content", item.content)
                        put("status", item.status)
                    })
                }
            }
        }
        return ToolResult.Success(output, display)
    }

    fun toSpec(): ToolSpec = ToolSpec(name, description, parametersSchema)
}
