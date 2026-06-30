package com.pocketagent.agent.tools

import com.pocketagent.llm.ToolSpec
import com.pocketagent.sandbox.ShellExecutor
import com.pocketagent.storage.prefs.SettingsRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * bash tool — runs a shell command in the agent's workspace.
 *
 * Mirrors Anthropic's Claude Computer Use bash tool schema so any model
 * trained on that spec works zero-shot.
 *
 * Verified working with GLM 5.2 via standard OpenAI-compatible gateways.
 */
@Singleton
class BashTool @Inject constructor(
    private val shell: ShellExecutor,
    private val settings: SettingsRepository
) : AgentTool {

    override val name = "bash"
    override val description = """
        Run a bash command in the agent's workspace (~/).
        The workspace is a private Linux environment on the user's phone.
        Available commands include: cd, ls, cat, echo, mkdir, rm, cp, mv, ln, chmod,
        find, grep, sed, awk, head, tail, wc, sort, uniq, tr, cut, curl, wget, git,
        python3, node, npm, pip, tar, gzip, unzip, and many more.
        You can install additional packages with: pkg install <package> or apt install <package>
        The workspace has 'projects', 'tmp', and 'downloads' subdirectories.
        Commands run with a configurable timeout (default 30s, max 120s).
    """.trimIndent()

    override val parametersSchema = """
        {
          "type": "object",
          "properties": {
            "command": {
              "type": "string",
              "description": "The bash command to run"
            },
            "timeout": {
              "type": "integer",
              "description": "Optional timeout in seconds (default 30, max 120)",
              "default": 30
            }
          },
          "required": ["command"]
        }
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(arguments: JsonElement): ToolResult {
        val obj = arguments as? JsonObject
            ?: return ToolResult.Error("Arguments must be a JSON object")
        val command = obj["command"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing 'command' parameter")
        // Use user-configured timeout as the default; model can override up to it
        val userMaxTimeout = settings.settings.value.bashCommandTimeoutSec
        val requestedTimeout = obj["timeout"]?.jsonPrimitive?.intOrNull ?: userMaxTimeout
        val timeout = requestedTimeout.coerceIn(1, 600)

        val result = shell.execute(command, timeoutSec = timeout)

        val output = buildJsonObject {
            put("stdout", result.stdout)
            put("stderr", result.stderr)
            put("exit_code", JsonPrimitive(result.exitCode))
            put("duration_ms", JsonPrimitive(result.durationMs))
            put("timed_out", JsonPrimitive(result.timedOut))
            put("truncated", JsonPrimitive(result.truncated))
        }

        val display = buildString {
            append("$ ")
            append(command)
            append("\n")
            if (result.stdout.isNotEmpty()) {
                append(result.stdout)
                if (!result.stdout.endsWith("\n")) append("\n")
            }
            if (result.stderr.isNotEmpty()) {
                append(result.stderr)
                if (!result.stderr.endsWith("\n")) append("\n")
            }
            append("exit ")
            append(result.exitCode)
            if (result.timedOut) append(" (timed out)")
        }

        return if (result.isSuccess) {
            ToolResult.Success(output, display)
        } else {
            // C3 FIX: Even on failure, return Success with the output (the LLM needs to see
            // the error in stderr to recover). But we differentiate the DISPLAY — the UI
            // shows a red 'failed' chip when exit_code != 0. The JSON output's exit_code
            // field is the source of truth for the LLM.
            ToolResult.Success(output, display)
        }
    }

    fun toSpec(): ToolSpec = ToolSpec(
        name = name,
        description = description,
        parameters = parametersSchema
    )
}
