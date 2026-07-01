package com.pocketagent.agent.tools

import com.pocketagent.bridge.TermuxBridge
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
 * v6: serve_http tool — starts an HTTP server in Termux via `python3 -m http.server`.
 *
 * Much simpler than v5's in-JVM HTTP server. The server runs in the user's real
 * Termux, has access to all files, and can serve any directory under ${'$'}HOME.
 *
 * The user opens http://localhost:PORT in their phone browser to view the site.
 */
@Singleton
class ServeHttpTool @Inject constructor(
    private val bridge: TermuxBridge
) : AgentTool {

    override val name = "serve_http"
    override val description = """
        Start, stop, or list local HTTP servers in Termux.
        Servers run via `python3 -m http.server` in the background.
        The user can open http://localhost:PORT in their phone browser.

        Actions:
        - start: serve a directory (default ~)
        - stop: stop a server on a port
        - list: show running servers
    """.trimIndent()

    override val parametersSchema = """
        {
          "type": "object",
          "properties": {
            "action": {"type": "string", "description": "start, stop, or list"},
            "port": {"type": "integer", "description": "Port (default 8080)", "default": 8080},
            "directory": {"type": "string", "description": "Directory to serve (default ~)", "default": "~"}
          },
          "required": ["action"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: kotlinx.serialization.json.JsonElement): ToolResult {
        val obj = arguments as? JsonObject
            ?: return ToolResult.Error("Arguments must be a JSON object", "Pass a JSON object with 'action'.")
        val action = obj["action"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("Missing 'action'", "Provide 'action': start, stop, or list.")
        val port = obj["port"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 8080
        val directory = obj["directory"]?.jsonPrimitive?.contentOrNull ?: "~"

        if (!bridge.state.isConnected) {
            return ToolResult.Error("Termux daemon not connected", "Start the daemon: `pocketagent-daemon` in Termux.")
        }

        return when (action) {
            "start" -> {
                // Start python http.server in the background
                // nohup + & to detach, write PID to a file for later stop
                val cmd = buildString {
                    append("cd '$directory' 2>/dev/null || cd ~; ")
                    append("nohup python3 -m http.server $port >/dev/null 2>&1 & ")
                    append("echo \$!")
                }
                val result = bridge.exec(cmd, timeout = 5)
                val response = result.getOrElse { e ->
                    return ToolResult.Error("Bridge error: ${e.message}", "Check Termux daemon is running.")
                }
                val pid = response.stdout.trim().lines().lastOrNull()?.trim()?.toLongOrNull()
                val output = buildJsonObject {
                    put("action", JsonPrimitive("started"))
                    put("port", JsonPrimitive(port))
                    put("directory", JsonPrimitive(directory))
                    put("url", JsonPrimitive("http://localhost:$port"))
                    pid?.let { put("pid", JsonPrimitive(it)) }
                    put("message", JsonPrimitive("Server running at http://localhost:$port — user can open this URL in their phone browser"))
                }
                ToolResult.Success(output, "Server running at http://localhost:$port (serving $directory)")
            }
            "stop" -> {
                // Find and kill the process listening on this port
                val cmd = buildString {
                    append("pids=\$(ss -tlnp 2>/dev/null | grep ':$port ' | grep -oP 'pid=\\K[0-9]+' || true); ")
                    append("if [ -z \"\$pids\" ]; then ")
                    append("pids=\$(netstat -tlnp 2>/dev/null | grep ':$port ' | awk '{print \$NF}' | cut -d/ -f1 || true); ")
                    append("fi; ")
                    append("if [ -z \"\$pids\" ]; then echo \"no server on port $port\"; ")
                    append("else for pid in \$pids; do kill \$pid 2>/dev/null; done; echo \"stopped\"; fi")
                }
                val result = bridge.exec(cmd, timeout = 5)
                val response = result.getOrElse { e ->
                    return ToolResult.Error("Bridge error: ${e.message}", "Check Termux daemon is running.")
                }
                val stopped = response.stdout.contains("stopped")
                val output = buildJsonObject {
                    put("action", JsonPrimitive("stopped"))
                    put("port", JsonPrimitive(port))
                    put("success", JsonPrimitive(stopped))
                }
                if (stopped) ToolResult.Success(output, "Server on port $port stopped")
                else ToolResult.Error("No server running on port $port", "Use 'list' to see running servers.")
            }
            "list" -> {
                val cmd = buildString {
                    append("ss -tlnp 2>/dev/null | grep 'python' | awk '{print \$4, \$6}' || ")
                    append("netstat -tlnp 2>/dev/null | grep 'python' | awk '{print \$4, \$6}' || echo \"(none)\"")
                }
                val result = bridge.exec(cmd, timeout = 5)
                val response = result.getOrElse { e ->
                    return ToolResult.Error("Bridge error: ${e.message}", "Check Termux daemon is running.")
                }
                val output = buildJsonObject {
                    put("servers", JsonPrimitive(response.stdout.trim()))
                }
                ToolResult.Success(output, response.stdout.ifBlank { "No servers running" })
            }
            else -> ToolResult.Error("Unknown action: $action", "Use 'start', 'stop', or 'list'.")
        }
    }

    fun toSpec(): ToolSpec = ToolSpec(name, description, parametersSchema)
}
