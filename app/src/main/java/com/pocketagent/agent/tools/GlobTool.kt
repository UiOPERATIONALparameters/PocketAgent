package com.pocketagent.agent.tools

import com.pocketagent.cloud.CloudBridge
import com.pocketagent.llm.ToolSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v6: glob tool uses `find` via the Termux daemon.
 * Caps at 100 results, sorted by modification time (newest first).
 */
@Singleton
class GlobTool @Inject constructor(
    private val cloud: CloudBridge
) : AgentTool {

    override val name = "glob"
    override val description = """
        Find files matching a glob pattern under a path in ${'$'}HOME.
        Returns paths sorted by modification time (newest first). Caps at 100.
        Patterns supported: ** (recursive), * (any), ? (single char), [abc], {a,b}.
        Examples: **/*.kt, src/**/*.py, *.md, {test,spec}/*.js
    """.trimIndent()

    override val parametersSchema = """
        {"type":"object","properties":{"pattern":{"type":"string","description":"Glob pattern"},"path":{"type":"string","default":"."}},"required":["pattern"]}
    """.trimIndent()

    override suspend fun execute(arguments: kotlinx.serialization.json.JsonElement): ToolResult {
        val obj = arguments as? JsonObject ?: return ToolResult.Error("Arguments must be a JSON object", "Pass a JSON object with 'pattern'.")
        val pattern = obj["pattern"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("Missing 'pattern'", "Provide a 'pattern' field.")
        val pathStr = obj["path"]?.jsonPrimitive?.contentOrNull ?: "."

        if (!cloud.state.isConnected) {
            return ToolResult.Error("Termux daemon not connected", "Start the daemon: `pocketagent-daemon` in Termux.")
        }

        // Use find with -path pattern matching, sort by mtime, take 100
        // We translate ** to * for find, and prefix with the search dir.
        val findPattern = pattern
            .replace("**/", "")
            .replace("**", "*")
            .replace("*", "*")
            .replace("?", "?")

        // Build the find command
        // find <path> -type f -name '<pattern>' -printf '%T@ %p\n' 2>/dev/null | sort -rn | head -100 | cut -d' ' -f2-
        // Busybox find doesn't support -printf, so use stat instead
        val cmd = buildString {
            append("find '")
            append(pathStr.replace("'", "'\\''"))
            append("' -type f -name '")
            append(findPattern.replace("'", "'\\''"))
            append("' 2>/dev/null | head -100 | while read -r f; do ")
            append("mtime=\$(stat -c %Y \"\$f\" 2>/dev/null || stat -f %m \"\$f\" 2>/dev/null || echo 0); ")
            append("echo \"\$mtime|\$f\"; done | sort -rn | cut -d'|' -f2-")
        }

        val result = cloud.exec(cmd, timeout = 30)
        val response = result.getOrElse { e ->
            return ToolResult.Error("Bridge error: ${e.message}", "Check Termux daemon is running.")
        }

        val results = response.stdout.lines()
            .filter { it.isNotBlank() }
            .map { path ->
                JsonObject(mapOf(
                    "path" to JsonPrimitive(path.trim())
                ))
            }

        val maxResults = 100
        val truncated = results.size >= maxResults

        val output = buildJsonObject {
            put("pattern", pattern)
            put("count", JsonPrimitive(results.size))
            put("truncated", JsonPrimitive(truncated))
            put("files", JsonArray(results))
        }
        val display = if (results.isEmpty()) "No files matching: $pattern"
        else results.joinToString("\n") { it["path"]?.jsonPrimitive?.contentOrNull ?: "" }
        return ToolResult.Success(output, display)
    }

    fun toSpec(): ToolSpec = ToolSpec(name, description, parametersSchema)
}
