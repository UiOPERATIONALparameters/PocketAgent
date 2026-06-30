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
class GrepTool @Inject constructor(
    private val workspace: Workspace
) : AgentTool {

    override val name = "grep"
    override val description = """
        Search file contents with regex. Returns matching lines with file:line:content.
        Supports glob filtering. Caps at 50 results.
    """.trimIndent()

    override val parametersSchema = """
        {"type":"object","properties":{"pattern":{"type":"string","description":"Regex pattern"},"path":{"type":"string","default":"."},"glob":{"type":"string","default":"*"},"case_insensitive":{"type":"boolean","default":false}},"required":["pattern"]}
    """.trimIndent()

    override suspend fun execute(arguments: kotlinx.serialization.json.JsonElement): ToolResult {
        val obj = arguments as? JsonObject ?: return ToolResult.Error("Arguments must be a JSON object")
        val pattern = obj["pattern"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("Missing 'pattern'")
        val pathStr = obj["path"]?.jsonPrimitive?.contentOrNull ?: "."
        val glob = obj["glob"]?.jsonPrimitive?.contentOrNull ?: "*"
        val caseInsensitive = obj["case_insensitive"]?.jsonPrimitive?.contentOrNull == "true"

        val searchDir = try { workspace.resolve(pathStr) } catch (e: SecurityException) { return ToolResult.Error(e.message ?: "Invalid path") }
        if (!searchDir.exists()) return ToolResult.Error("Path not found: $pathStr")

        val regex = try {
            if (caseInsensitive) Regex(pattern, RegexOption.IGNORE_CASE) else Regex(pattern)
        } catch (e: Exception) { return ToolResult.Error("Invalid regex: ${e.message}") }

        val results = mutableListOf<JsonObject>()
        val maxResults = 50

        val filesToSearch = if (searchDir.isDirectory) {
            searchDir.walkTopDown()
                .filter { it.isFile && !it.name.startsWith(".") && !it.path.contains("/.git/") }
                .filter { f -> if (glob == "*") true else { val gp = glob.replace(".", "\\.").replace("*", ".*"); Regex(gp).matches(f.name) } }
                .toList()
        } else listOf(searchDir)

        for (file in filesToSearch) {
            if (results.size >= maxResults) break
            try {
                val lines = file.readLines()
                for ((idx, line) in lines.withIndex()) {
                    if (results.size >= maxResults) break
                    if (regex.containsMatchIn(line)) {
                        val relPath = workspace.homeDir.toPath().relativize(file.toPath()).toString()
                        results.add(buildJsonObject {
                            put("file", relPath)
                            put("line", JsonPrimitive(idx + 1))
                            put("content", line.trim())
                        })
                    }
                }
            } catch (_: Exception) {}
        }

        val output = buildJsonObject {
            put("pattern", pattern)
            put("matches", JsonPrimitive(results.size))
            put("truncated", JsonPrimitive(results.size >= maxResults))
            put("results", JsonArray(results))
        }
        val display = if (results.isEmpty()) "No matches for: $pattern"
        else results.joinToString("\n") { m -> "${m["file"]?.jsonPrimitive?.contentOrNull ?: ""}:${m["line"]?.jsonPrimitive?.contentOrNull ?: ""}: ${m["content"]?.jsonPrimitive?.contentOrNull ?: ""}" }
        return ToolResult.Success(output, display)
    }

    fun toSpec(): ToolSpec = ToolSpec(name, description, parametersSchema)
}
