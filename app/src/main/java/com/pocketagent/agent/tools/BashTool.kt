package com.pocketagent.agent.tools

import com.pocketagent.bridge.TermuxBridge
import com.pocketagent.llm.ToolSpec
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
 * bash tool — runs a shell command via the Termux daemon.
 *
 * v6: Replaced the entire ShellExecutor + NativeEnvironmentManager (~1,500 LOC of
 * Android-OS-fighting code) with a single HTTP call to the Termux daemon.
 *
 * The AI gets the EXACT same environment the user has in Termux:
 *   - Same ${'$'}PATH, same installed packages
 *   - Same ~/.gitconfig, same ssh keys
 *   - Same ~/.bashrc, same aliases
 *   - Real bash, real coreutils, real apt/pkg
 *
 * Tool design (Anthropic "Writing Effective Tools" principles):
 *   - Structured JSON output (stdout, stderr, exit_code, suggestion)
 *   - Actionable error suggestions
 *   - Even on failure, returns Success with the output (the LLM needs to see stderr)
 */
@Singleton
class BashTool @Inject constructor(
    private val bridge: TermuxBridge,
    private val settings: SettingsRepository
) : AgentTool {

    override val name = "bash"
    override val description = """
        Run a bash command in the agent's Termux environment (real Linux on the user's phone).
        This is the user's actual Termux — same packages, same ${'$'}PATH, same git config.

        Available commands (everything the user has in Termux):
        - coreutils: ls, cat, echo, mkdir, rm, cp, mv, ln, chmod, find, head, tail, wc, sort, uniq, tr, cut
        - shells: bash, sh, zsh (if installed)
        - languages: python, python3, node, ruby, perl, php (if installed)
        - build: gcc, g++, clang, make, cmake, gradle, javac (if installed)
        - network: curl, wget, ssh, scp, git
        - package manager: pkg, apt, apt-get
        - editors: vim, nano (if installed)

        Install new packages with: pkg install <name>
        Examples: pkg install python nodejs git gcc ffmpeg

        The workspace is the user's ${'$'}HOME in Termux. All commands run there.
        Commands run with a configurable timeout (default 30s, max 600s).
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
              "description": "Optional timeout in seconds (default 30, max 600)",
              "default": 30
            },
            "cwd": {
              "type": "string",
              "description": "Optional working directory (default: ${'$'}HOME)",
              "default": "~"
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
            ?: return ToolResult.Error("Missing 'command' parameter", "Provide a 'command' field with the bash command to run.")
        val userMaxTimeout = settings.settings.value.bashCommandTimeoutSec
        val requestedTimeout = obj["timeout"]?.jsonPrimitive?.intOrNull ?: userMaxTimeout
        val timeout = requestedTimeout.coerceIn(1, 600)
        val cwd = obj["cwd"]?.jsonPrimitive?.content

        // Check connection first
        if (!bridge.state.isConnected) {
            return ToolResult.Error(
                "Termux daemon not connected",
                "Open Termux and run `pocketagent-daemon`, then retry. The daemon must be running for bash commands to work."
            )
        }

        val result = bridge.exec(command, timeout = timeout, cwd = cwd)

        val response = result.getOrElse { e ->
            return ToolResult.Error(
                "Bridge error: ${e.message}",
                "The Termux daemon connection failed. Check that Termux is running and the daemon is started (pocketagent-daemon)."
            )
        }

        // If daemon returned an error field, surface it
        if (response.error != null) {
            return ToolResult.Error(
                response.error,
                response.suggestion ?: "Check the command syntax and try again."
            )
        }

        val output = buildJsonObject {
            put("stdout", response.stdout)
            put("stderr", response.stderr)
            put("exit_code", JsonPrimitive(response.exitCode))
            put("duration_ms", JsonPrimitive(response.durationMs))
            put("timed_out", JsonPrimitive(response.timedOut))
            put("truncated", JsonPrimitive(response.truncated))
            response.pid?.let { put("pid", JsonPrimitive(it)) }
            response.suggestion?.let { put("suggestion", JsonPrimitive(it)) }
        }

        val display = buildString {
            append("$ ")
            append(command)
            append("\n")
            if (response.stdout.isNotEmpty()) {
                append(response.stdout)
                if (!response.stdout.endsWith("\n")) append("\n")
            }
            if (response.stderr.isNotEmpty()) {
                append(response.stderr)
                if (!response.stderr.endsWith("\n")) append("\n")
            }
            append("exit ")
            append(response.exitCode)
            if (response.timedOut) append(" (timed out)")
            response.suggestion?.let { append("\n💡 $it") }
        }

        // Always return Success — the LLM needs to see stderr to recover.
        // The exit_code field is the source of truth.
        return ToolResult.Success(output, display)
    }

    fun toSpec(): ToolSpec = ToolSpec(
        name = name,
        description = description,
        parameters = parametersSchema
    )
}
