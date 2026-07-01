package com.pocketagent.agent.tools

import com.pocketagent.cloud.CloudBridge
import com.pocketagent.llm.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v6: str_replace now routes through the Termux daemon.
 * Reads file via /files/read, modifies in-memory, writes back via /files/write.
 */
@Singleton
class StrReplaceTool @Inject constructor(
    private val cloud: CloudBridge
) : AgentTool {

    override val name = "str_replace"
    override val description = """
        Perform exact string replacements in an existing file. PREFERRED over file_write for editing.
        old_str must appear EXACTLY once (unless replace_all=true). Fails if not found.
        Path is relative to ${'$'}HOME in Termux.
    """.trimIndent()

    override val parametersSchema = """
        {"type":"object","properties":{"path":{"type":"string","description":"Path to file (under ${'$'}HOME)"},"old_str":{"type":"string","description":"Exact string to find"},"new_str":{"type":"string","description":"Replacement string"},"replace_all":{"type":"boolean","default":false}},"required":["path","old_str","new_str"]}
    """.trimIndent()

    override suspend fun execute(arguments: kotlinx.serialization.json.JsonElement): ToolResult {
        val obj = arguments as? JsonObject ?: return ToolResult.Error("Arguments must be a JSON object", "Pass a JSON object with path, old_str, new_str.")
        val pathStr = obj["path"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("Missing 'path'", "Provide a 'path' field.")
        val oldStr = obj["old_str"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("Missing 'old_str'", "Provide an 'old_str' field with the exact text to find.")
        val newStr = obj["new_str"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("Missing 'new_str'", "Provide a 'new_str' field with the replacement text.")
        val replaceAll = obj["replace_all"]?.jsonPrimitive?.contentOrNull == "true"

        if (!cloud.state.isConnected) {
            return ToolResult.Error("Termux daemon not connected", "Start the daemon: `pocketagent-daemon` in Termux.")
        }

        // Read the file
        val readResult = cloud.readFile(pathStr)
        val readResp = readResult.getOrElse { e ->
            return ToolResult.Error("Bridge error: ${e.message}", "Check Termux daemon is running.")
        }
        if (readResp.error != null) {
            return ToolResult.Error(readResp.error, "Check the path is correct.")
        }
        val content = readResp.content

        if (!content.contains(oldStr)) {
            val preview = content.lines().take(10).joinToString("\n")
            return ToolResult.Error(
                "old_str not found in $pathStr",
                "The exact string was not found. Check whitespace and indentation. Preview of file:\n$preview"
            )
        }

        val count = content.split(oldStr).size - 1
        if (count > 1 && !replaceAll) {
            return ToolResult.Error(
                "old_str appears $count times",
                "Use more context to make old_str unique, or set replace_all=true to replace all occurrences."
            )
        }

        val newContent = if (replaceAll) content.replace(oldStr, newStr)
        else {
            val idx = content.indexOf(oldStr)
            content.substring(0, idx) + newStr + content.substring(idx + oldStr.length)
        }

        // Write back
        val writeResult = cloud.writeFile(pathStr, newContent)
        val writeResp = writeResult.getOrElse { e ->
            return ToolResult.Error("Bridge error: ${e.message}", "Check Termux daemon is running.")
        }
        if (writeResp.error != null) {
            return ToolResult.Error(writeResp.error, "Failed to write file.")
        }

        val output = buildJsonObject {
            put("path", pathStr)
            put("replacements_made", JsonPrimitive(if (replaceAll) count else 1))
            put("success", JsonPrimitive(true))
        }
        return ToolResult.Success(output, "Replaced in $pathStr")
    }

    fun toSpec(): ToolSpec = ToolSpec(name, description, parametersSchema)
}
