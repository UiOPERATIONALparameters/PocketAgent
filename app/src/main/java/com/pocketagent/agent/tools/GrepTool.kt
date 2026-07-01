package com.pocketagent.agent.tools

import com.pocketagent.bridge.TermuxBridge
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
 * v6: grep tool routes through the Termux daemon's real `grep` binary.
 * The daemon has real ripgrep-aware grep; we use ripgrep if available, else grep -rHn.
 * Returns up to 50 matches with file:line:content.
 */
@Singleton
class GrepTool @Inject constructor(
    private val bridge: TermuxBridge
) : AgentTool {

    override val name = "grep"
    override val description = """
        Search file contents with regex under a path in $HOME.
        Returns matching lines with file:line:content format.
        Caps at 50 results. Uses ripgrep if installed (fast), else grep -rHn.
        Searches dotfiles by default; skips only .git/ directory.
    """.trimIndent()

    override val parametersSchema = """
        {"type":"object","properties":{"pattern":{"type":"string","description":"Regex pattern"},"path":{"type":"string","default":"."},"glob":{"type":"string","default":"*"},"case_insensitive":{"type":"boolean","default":false}},"required":["pattern"]}
    """.trimIndent()

    override suspend fun execute(arguments: kotlinx.serialization.json.JsonElement): ToolResult {
        val obj = arguments as? JsonObject ?: return ToolResult.Error("Arguments must be a JSON object", "Pass a JSON object with at least 'pattern'.")
        val pattern = obj["pattern"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("Missing 'pattern'", "Provide a 'pattern' field with the regex.")
        val pathStr = obj["path"]?.jsonPrimitive?.contentOrNull ?: "."
        val glob = obj["glob"]?.jsonPrimitive?.contentOrNull ?: "*"
        val caseInsensitive = obj["case_insensitive"]?.jsonPrimitive?.contentOrNull?.equals("true", ignoreCase = true) == true

        if (!bridge.state.isConnected) {
            return ToolResult.Error("Termux daemon not connected", "Start the daemon: `pocketagent-daemon` in Termux.")
        }

        // Use ripgrep if available, else grep -rHn
        // rg is faster and produces file:line:content by default
        val cmd = buildString {
            append("if command -v rg >/dev/null 2>&1; then")
            append("  rg ")
            if (caseInsensitive) append("-i ")
            append("--no-heading --line-number --max-count=50 ")
            append("-g '")
            append(glob.replace("'", "'\\''"))
            append("' --glob '!/.git/' ")
            append("'")
            append(pattern.replace("'", "'\\''"))
            append("' '")
            append(pathStr.replace("'", "'\\''"))
            append("' 2>/dev/null;")
            append("else")
            append("  grep -rHn ")
            if (caseInsensitive) append("-i ")
            append("--include='")
            append(glob.replace("'", "'\\''"))
            append("' --exclude-dir=.git --max-count=50 ")
            append("'")
            append(pattern.replace("'", "'\\''"))
            append("' '")
            append(pathStr.replace("'", "'\\''"))
            append("' 2>/dev/null;")
            append("fi")
        }

        val result = bridge.exec(cmd, timeout = 30)
        val response = result.getOrElse { e ->
            return ToolResult.Error("Bridge error: ${e.message}", "Check Termux daemon is running.")
        }

        // Parse file:line:content output
        val matches = mutableListOf<JsonObject>()
        val maxResults = 50
        for (line in response.stdout.lines()) {
            if (line.isBlank()) continue
            if (matches.size >= maxResults) break
            // Match format: file:line:content
            val colonIdx1 = line.indexOf(':')
            if (colonIdx1 < 0) continue
            val colonIdx2 = line.indexOf(':', colonIdx1 + 1)
            if (colonIdx2 < 0) continue
            val file = line.substring(0, colonIdx1)
            val lineNumStr = line.substring(colonIdx1 + 1, colonIdx2)
            val content = line.substring(colonIdx2 + 1)
            val lineNum = lineNumStr.toIntOrNull() ?: continue
            matches.add(buildJsonObject {
                put("file", JsonPrimitive(file))
                put("line", JsonPrimitive(lineNum))
                put("content", JsonPrimitive(content.trim()))
            })
        }

        val output = buildJsonObject {
            put("pattern", pattern)
            put("path", pathStr)
            put("matches", JsonPrimitive(matches.size))
            put("truncated", JsonPrimitive(matches.size >= maxResults))
            put("results", JsonArray(matches))
        }
        val display = if (matches.isEmpty()) "No matches for: $pattern"
        else matches.joinToString("\n") { m ->
            "${m["file"]?.jsonPrimitive?.contentOrNull ?: ""}:${m["line"]?.jsonPrimitive?.contentOrNull ?: ""}: ${m["content"]?.jsonPrimitive?.contentOrNull ?: ""}"
        }
        return ToolResult.Success(output, display)
    }

    fun toSpec(): ToolSpec = ToolSpec(name, description, parametersSchema)
}
