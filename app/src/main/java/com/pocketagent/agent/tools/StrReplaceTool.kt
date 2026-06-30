package com.pocketagent.agent.tools

import com.pocketagent.llm.ToolSpec
import com.pocketagent.sandbox.Workspace
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StrReplaceTool @Inject constructor(
    private val workspace: Workspace
) : AgentTool {

    override val name = "str_replace"
    override val description = """
        Perform exact string replacements in an existing file. PREFERRED over file_write for editing.
        old_str must appear EXACTLY once (unless replace_all=true). Fails if not found.
    """.trimIndent()

    override val parametersSchema = """
        {"type":"object","properties":{"path":{"type":"string","description":"Path to file"},"old_str":{"type":"string","description":"Exact string to find"},"new_str":{"type":"string","description":"Replacement string"},"replace_all":{"type":"boolean","default":false}},"required":["path","old_str","new_str"]}
    """.trimIndent()

    override suspend fun execute(arguments: kotlinx.serialization.json.JsonElement): ToolResult {
        val obj = arguments as? JsonObject ?: return ToolResult.Error("Arguments must be a JSON object")
        val pathStr = obj["path"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("Missing 'path'")
        val oldStr = obj["old_str"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("Missing 'old_str'")
        val newStr = obj["new_str"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("Missing 'new_str'")
        val replaceAll = obj["replace_all"]?.jsonPrimitive?.contentOrNull == "true"

        val file = try { workspace.resolve(pathStr) } catch (e: SecurityException) { return ToolResult.Error(e.message ?: "Invalid path") }
        if (!file.exists()) return ToolResult.Error("File not found: $pathStr. Use file_write to create new files.")
        if (!file.isFile) return ToolResult.Error("Not a file: $pathStr")

        val content = try { file.readText() } catch (e: Exception) { return ToolResult.Error("Failed to read: ${e.message}") }
        if (!content.contains(oldStr)) {
            val preview = content.lines().take(10).joinToString("\n")
            return ToolResult.Error("old_str not found. Preview:\n$preview")
        }

        val count = content.split(oldStr).size - 1
        if (count > 1 && !replaceAll) return ToolResult.Error("old_str appears $count times. Use more context or replace_all=true.")

        val newContent = if (replaceAll) content.replace(oldStr, newStr)
        else { val idx = content.indexOf(oldStr); content.substring(0, idx) + newStr + content.substring(idx + oldStr.length) }

        try { file.writeText(newContent) } catch (e: Exception) { return ToolResult.Error("Failed to write: ${e.message}") }

        val output = buildJsonObject {
            put("path", pathStr)
            put("replacements_made", JsonPrimitive(if (replaceAll) count else 1))
            put("success", JsonPrimitive(true))
        }
        return ToolResult.Success(output, "Replaced in $pathStr")
    }

    fun toSpec(): ToolSpec = ToolSpec(name, description, parametersSchema)
}
