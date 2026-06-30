package com.pocketagent.agent.tools

import com.pocketagent.llm.ToolSpec
import com.pocketagent.sandbox.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * serve_http tool — starts a local HTTP server to serve files from the workspace.
 *
 * The agent can use this to:
 *   - Serve a website it built (user opens http://localhost:PORT in browser)
 *   - Serve a file for download
 *   - Test API endpoints
 *
 * The server runs in a background thread and stays alive until the agent
 * calls 'stop' or the app is killed.
 */
@Singleton
class ServeHttpTool @Inject constructor(
    private val workspace: Workspace
) : AgentTool {

    private val servers = mutableMapOf<Int, ServerThread>()

    override val name = "serve_http"
    override val description = """
        Start or stop a local HTTP server to serve files from the workspace.
        The user can open the URL in their phone's browser to view the website.
        Actions: 'start' (serve a directory), 'stop' (stop a server), 'list' (show running servers).
        The server runs in the background until stopped.
    """.trimIndent()

    override val parametersSchema = """
        {
          "type": "object",
          "properties": {
            "action": {
              "type": "string",
              "description": "Action: start, stop, or list"
            },
            "port": {
              "type": "integer",
              "description": "Port to serve on (default 8080)",
              "default": 8080
            },
            "directory": {
              "type": "string",
              "description": "Directory to serve (for 'start'), relative to workspace root. Defaults to '.'"
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
        val port = obj["port"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 8080

        return when (action) {
            "start" -> {
                val dirStr = obj["directory"]?.jsonPrimitive?.contentOrNull ?: "."
                val serveDir = try {
                    workspace.resolve(dirStr)
                } catch (e: SecurityException) {
                    return ToolResult.Error("Invalid directory: ${e.message}")
                }
                if (!serveDir.exists() || !serveDir.isDirectory) {
                    return ToolResult.Error("Directory not found: $dirStr")
                }

                // Stop existing server on this port
                servers[port]?.stopServer()

                try {
                    val server = ServerThread(port, serveDir)
                    server.start()
                    servers[port] = server
                    val output = buildJsonObject {
                        put("action", "started")
                        put("port", JsonPrimitive(port))
                        put("directory", dirStr)
                        put("url", "http://localhost:$port")
                        put("message", "Server running at http://localhost:$port — user can open this in their browser")
                    }
                    ToolResult.Success(output, "Server running at http://localhost:$port (serving ~/$dirStr)")
                } catch (e: Exception) {
                    ToolResult.Error("Failed to start server: ${e.message}")
                }
            }
            "stop" -> {
                val server = servers.remove(port)
                if (server != null) {
                    server.stopServer()
                    val output = buildJsonObject {
                        put("action", "stopped")
                        put("port", JsonPrimitive(port))
                    }
                    ToolResult.Success(output, "Server on port $port stopped")
                } else {
                    ToolResult.Error("No server running on port $port")
                }
            }
            "list" -> {
                val output = buildJsonObject {
                    put("count", JsonPrimitive(servers.size))
                    put("servers", kotlinx.serialization.json.buildJsonArray {
                        for ((p, s) in servers) {
                            add(buildJsonObject {
                                put("port", JsonPrimitive(p))
                                put("directory", s.serveDir.absolutePath)
                            })
                        }
                    })
                }
                val display = if (servers.isEmpty()) "No servers running"
                else servers.entries.map { "http://localhost:${it.key} → ${it.value.serveDir.name}" }.joinToString("\n")
                ToolResult.Success(output, display)
            }
            else -> ToolResult.Error("Unknown action: $action. Use start, stop, or list.")
        }
    }

    fun toSpec(): ToolSpec = ToolSpec(name, description, parametersSchema)

    /**
     * Simple HTTP server thread. Serves static files from a directory.
     */
    private class ServerThread(
        private val port: Int,
        val serveDir: File
    ) : Thread("http-server-$port") {

        @Volatile
        private var running = true
        private var serverSocket: ServerSocket? = null

        init {
            isDaemon = true
        }

        override fun run() {
            try {
                serverSocket = ServerSocket(port)
                while (running) {
                    try {
                        val client = serverSocket!!.accept()
                        handleClient(client)
                    } catch (_: Exception) {
                        if (running) continue else break
                    }
                }
            } catch (_: Exception) {}
        }

        private fun handleClient(client: Socket) {
            try {
                val input = BufferedReader(InputStreamReader(client.getInputStream()))
                val output = client.getOutputStream()

                val requestLine = input.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val path = parts[1].removePrefix("/").ifEmpty { "index.html" }
                // Decode URL
                val decodedPath = java.net.URLDecoder.decode(path, "UTF-8")
                val file = File(serveDir, decodedPath)

                // Read and discard headers
                while (input.readLine()?.isNotEmpty() == true) {}

                when {
                    file.isDirectory -> {
                        val indexFile = File(file, "index.html")
                        if (indexFile.exists()) sendFile(output, indexFile)
                        else sendDirectoryListing(output, file, decodedPath)
                    }
                    file.exists() && file.isFile -> sendFile(output, file)
                    else -> sendNotFound(output, decodedPath)
                }
                output.flush()
            } catch (_: Exception) {} finally {
                try { client.close() } catch (_: Exception) {}
            }
        }

        private fun sendFile(output: OutputStream, file: File) {
            val mimeType = guessMimeType(file.name)
            val data = file.readBytes()
            val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: $mimeType\r\n" +
                "Content-Length: ${data.size}\r\n" +
                "Connection: close\r\n\r\n"
            output.write(header.toByteArray())
            output.write(data)
        }

        private fun sendDirectoryListing(output: OutputStream, dir: File, path: String) {
            val sb = StringBuilder()
            sb.append("<html><head><title>Index of /$path</title>")
            sb.append("<style>body{font-family:sans-serif;margin:20px} a{display:block;padding:4px}</style>")
            sb.append("</head><body><h1>Index of /$path</h1>")
            if (path.isNotEmpty()) sb.append("<a href=\"../\">../</a>")
            dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name }))?.forEach { f ->
                val name = if (f.isDirectory) "${f.name}/" else f.name
                sb.append("<a href=\"$name\">$name</a>")
            }
            sb.append("</body></html>")
            val data = sb.toString().toByteArray()
            val header = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n"
            output.write(header.toByteArray())
            output.write(data)
        }

        private fun sendNotFound(output: OutputStream, path: String) {
            val body = "<html><body><h1>404 Not Found</h1><p>/$path not found</p></body></html>".toByteArray()
            val header = "HTTP/1.1 404 Not Found\r\nContent-Type: text/html\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
            output.write(header.toByteArray())
            output.write(body)
        }

        private fun guessMimeType(filename: String): String {
            val ext = filename.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "html", "htm" -> "text/html"
                "css" -> "text/css"
                "js" -> "text/javascript"
                "json" -> "application/json"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "svg" -> "image/svg+xml"
                "ico" -> "image/x-icon"
                "txt", "md" -> "text/plain"
                "pdf" -> "application/pdf"
                "mp4" -> "video/mp4"
                "mp3" -> "audio/mpeg"
                "woff" -> "font/woff"
                "woff2" -> "font/woff2"
                else -> "application/octet-stream"
            }
        }

        fun stopServer() {
            running = false
            try { serverSocket?.close() } catch (_: Exception) {}
        }
    }
}
