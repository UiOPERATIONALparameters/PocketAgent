package com.pocketagent.agent.tools

import com.pocketagent.cloud.CloudBridge
import com.pocketagent.llm.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v6: File tools route through the Termux daemon.
 * Path safety is enforced daemon-side (confined to ${'$'}HOME, .. blocked).
 */

@Singleton
class FileReadTool @Inject constructor(
    private val cloud: CloudBridge
) : AgentTool {

    override val name = "file_read"
    override val description = """
        Read the contents of a file from the Termux home directory.
        Path is relative to ${'$'}HOME. Use ~ or absolute paths under ${'$'}HOME.
        Returns up to 1MB of text content. Binary files return hex.
    """.trimIndent()

    override val parametersSchema = """
        {
          "type": "object",
          "properties": {
            "path": {
              "type": "string",
              "description": "Path to the file (relative to ${'$'}HOME or absolute under ${'$'}HOME)"
            },
            "start_line": {
              "type": "integer",
              "description": "Optional: 1-indexed line to start reading from"
            },
            "end_line": {
              "type": "integer",
              "description": "Optional: 1-indexed line to end reading at (inclusive)"
            }
          },
          "required": ["path"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: JsonElement): ToolResult {
        val obj = arguments as? JsonObject
            ?: return ToolResult.Error("Arguments must be a JSON object")
        val pathStr = obj["path"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing 'path' parameter", "Provide a 'path' field with the file to read.")
        val startLine = obj["start_line"]?.jsonPrimitive?.intOrNull ?: 1
        val endLine = obj["end_line"]?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE

        if (!cloud.state.isConnected) {
            return ToolResult.Error("Termux daemon not connected", "Start the daemon: `pocketagent-daemon` in Termux.")
        }

        val result = cloud.readFile(pathStr)

        val response = result.getOrElse { e ->
            return ToolResult.Error("Bridge error: ${e.message}", "Check Termux daemon is running.")
        }

        if (response.error != null) {
            return ToolResult.Error(response.error, "Check the path and try again.")
        }

        val lines = response.content.lines()
        val sliced = lines.subList(
            (startLine - 1).coerceIn(0, lines.size),
            endLine.coerceAtMost(lines.size)
        )
        val content = sliced.joinToString("\n")

        val output = buildJsonObject {
            put("path", response.path)
            put("content", content)
            put("total_lines", JsonPrimitive(lines.size))
            put("returned_lines", JsonPrimitive(sliced.size))
            put("truncated", JsonPrimitive(response.truncated))
            put("binary", JsonPrimitive(response.binary))
            put("bytes", JsonPrimitive(response.size))
        }
        return ToolResult.Success(output, content)
    }

    fun toSpec(): ToolSpec = ToolSpec(name, description, parametersSchema)
}

@Singleton
class FileWriteTool @Inject constructor(
    private val cloud: CloudBridge
) : AgentTool {

    override val name = "file_write"
    override val description = """
        Write content to a file in the Termux home directory.
        Creates parent directories if needed. Overwrites existing files.
        For appending, use the bash tool with `echo >> file`.
    """.trimIndent()

    override val parametersSchema = """
        {
          "type": "object",
          "properties": {
            "path": {
              "type": "string",
              "description": "Path to write (relative to ${'$'}HOME or absolute under ${'$'}HOME)"
            },
            "content": {
              "type": "string",
              "description": "Content to write"
            }
          },
          "required": ["path", "content"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: JsonElement): ToolResult {
        val obj = arguments as? JsonObject
            ?: return ToolResult.Error("Arguments must be a JSON object")
        val pathStr = obj["path"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing 'path' parameter", "Provide a 'path' field.")
        val content = obj["content"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing 'content' parameter", "Provide a 'content' field with the file contents.")

        if (!cloud.state.isConnected) {
            return ToolResult.Error("Termux daemon not connected", "Start the daemon: `pocketagent-daemon` in Termux.")
        }

        val result = cloud.writeFile(pathStr, content)

        val response = result.getOrElse { e ->
            return ToolResult.Error("Bridge error: ${e.message}", "Check Termux daemon is running.")
        }

        if (response.error != null) {
            return ToolResult.Error(response.error, "Check the path is under ${'$'}HOME.")
        }

        val output = buildJsonObject {
            put("path", response.path)
            put("bytes_written", JsonPrimitive(response.bytes))
        }
        return ToolResult.Success(output, "Wrote ${response.bytes} bytes to ${response.path}")
    }

    fun toSpec(): ToolSpec = ToolSpec(name, description, parametersSchema)
}

@Singleton
class FileListTool @Inject constructor(
    private val cloud: CloudBridge
) : AgentTool {

    override val name = "file_list"
    override val description = """
        List files and directories in a path under ${'$'}HOME.
        Returns names, types, sizes, and modification times.
        Use path="~" for home directory, or any subdirectory.
    """.trimIndent()

    override val parametersSchema = """
        {
          "type": "object",
          "properties": {
            "path": {
              "type": "string",
              "description": "Directory to list (default: ~)",
              "default": "~"
            }
          }
        }
    """.trimIndent()

    override suspend fun execute(arguments: JsonElement): ToolResult {
        val obj = arguments as? JsonObject ?: JsonObject(emptyMap())
        val pathStr = obj["path"]?.jsonPrimitive?.content ?: "~"

        if (!cloud.state.isConnected) {
            return ToolResult.Error("Termux daemon not connected", "Start the daemon: `pocketagent-daemon` in Termux.")
        }

        val result = cloud.listFiles(pathStr)

        val response = result.getOrElse { e ->
            return ToolResult.Error("Bridge error: ${e.message}", "Check Termux daemon is running.")
        }

        if (response.error != null) {
            return ToolResult.Error(response.error, "Check the path is a directory under ${'$'}HOME.")
        }

        val entriesArray = JsonArray(response.entries.map { entry ->
            buildJsonObject {
                put("name", JsonPrimitive(entry.name))
                put("type", JsonPrimitive(entry.type))
                put("size", JsonPrimitive(entry.size))
                put("modified", JsonPrimitive(entry.mtime))
                put("hidden", JsonPrimitive(entry.hidden))
            }
        })

        val output = buildJsonObject {
            put("path", response.path)
            put("count", JsonPrimitive(response.entries.size))
            put("entries", entriesArray)
        }
        return ToolResult.Success(output, "Listed ${response.entries.size} entries in ${response.path}")
    }

    fun toSpec(): ToolSpec = ToolSpec(name, description, parametersSchema)
}
