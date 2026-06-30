package com.pocketagent.agent.tools

import com.pocketagent.llm.ToolSpec
import com.pocketagent.sandbox.Workspace
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GlobTool @Inject constructor(
    private val workspace: Workspace
) : AgentTool {

    override val name = "glob"
    override val description = """
        Find files matching a pattern (e.g. **/*.kt, *.py). Returns paths sorted by modification time. Caps at 100.
    """.trimIndent()

    override val parametersSchema = """
        {"type":"object","properties":{"pattern":{"type":"string","description":"Glob pattern"},"path":{"type":"string","default":"."}},"required":["pattern"]}
    """.trimIndent()

    override suspend fun execute(arguments: kotlinx.serialization.json.JsonElement): ToolResult {
        val obj = arguments as? JsonObject ?: return ToolResult.Error("Arguments must be a JSON object")
        val pattern = obj["pattern"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("Missing 'pattern'")
        val pathStr = obj["path"]?.jsonPrimitive?.contentOrNull ?: "."

        val searchDir = try { workspace.resolve(pathStr) } catch (e: SecurityException) { return ToolResult.Error(e.message ?: "Invalid path") }
        if (!searchDir.exists()) return ToolResult.Error("Path not found: $pathStr")

        val regexPattern = pattern.replace(".", "\\.").replace("**/", "(.*/)?").replace("**", ".*").replace("*", "[^/]*").replace("?", ".")
        val regex = try { Regex("^$regexPattern$") } catch (e: Exception) { return ToolResult.Error("Invalid pattern: ${e.message}") }

        val maxResults = 100
        val results = searchDir.walkTopDown()
            .filter { it.isFile }
            .filter { f -> val relPath = searchDir.toPath().relativize(f.toPath()).toString(); regex.matches(relPath) || regex.matches(f.name) }
            .sortedByDescending { it.lastModified() }
            .take(maxResults)
            .map { f -> val relPath = workspace.homeDir.toPath().relativize(f.toPath()).toString(); JsonObject(mapOf("path" to JsonPrimitive(relPath), "size" to JsonPrimitive(f.length()), "modified" to JsonPrimitive(f.lastModified()))) }
            .toList()

        val output = buildJsonObject {
            put("pattern", pattern)
            put("count", JsonPrimitive(results.size))
            put("truncated", JsonPrimitive(results.size >= maxResults))
            put("files", JsonArray(results))
        }
        val display = if (results.isEmpty()) "No files matching: $pattern"
        else results.joinToString("\n") { it["path"]?.jsonPrimitive?.contentOrNull ?: "" }
        return ToolResult.Success(output, display)
    }

    fun toSpec(): ToolSpec = ToolSpec(name, description, parametersSchema)
}
