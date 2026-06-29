package com.pocketagent.agent.tools

import com.pocketagent.llm.ToolSpec
import com.pocketagent.sandbox.PathGuard
import com.pocketagent.sandbox.Workspace
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
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileReadTool @Inject constructor(
    private val workspace: Workspace
) : AgentTool {

    override val name = "file_read"
    override val description = """
        Read the contents of a file from the agent's workspace.
        Path is relative to the workspace root (~).
    """.trimIndent()

    override val parametersSchema = """
        {
          "type": "object",
          "properties": {
            "path": {
              "type": "string",
              "description": "Path to the file, relative to workspace root"
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

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(arguments: JsonElement): ToolResult {
        val obj = arguments as? JsonObject
            ?: return ToolResult.Error("Arguments must be a JSON object")
        val pathStr = obj["path"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing 'path' parameter")
        val startLine = obj["start_line"]?.jsonPrimitive?.intOrNull ?: 1
        val endLine = obj["end_line"]?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE

        val file = try {
            workspace.resolve(pathStr)
        } catch (e: SecurityException) {
            return ToolResult.Error(e.message ?: "Invalid path")
        }

        if (!file.exists()) {
            return ToolResult.Error("File not found: $pathStr")
        }
        if (!file.isFile) {
            return ToolResult.Error("Not a file: $pathStr")
        }

        val maxBytes = 256_000
        val raw = try {
            file.readBytes()
        } catch (e: IOException) {
            return ToolResult.Error("Failed to read: ${e.message}")
        }

        val truncated = raw.size > maxBytes
        val text = if (truncated) {
            String(raw, 0, maxBytes, Charsets.UTF_8)
        } else {
            String(raw, Charsets.UTF_8)
        }

        val lines = text.lines()
        val sliced = lines.subList(
            (startLine - 1).coerceIn(0, lines.size),
            endLine.coerceAtMost(lines.size)
        )
        val content = sliced.joinToString("\n")

        val output = buildJsonObject {
            put("path", pathStr)
            put("content", content)
            put("total_lines", JsonPrimitive(lines.size))
            put("returned_lines", JsonPrimitive(sliced.size))
            put("truncated", JsonPrimitive(truncated))
            put("bytes", JsonPrimitive(raw.size))
        }
        return ToolResult.Success(output, content)
    }

    fun toSpec(): ToolSpec = ToolSpec(name, description, parametersSchema)
}

@Singleton
class FileWriteTool @Inject constructor(
    private val workspace: Workspace
) : AgentTool {

    override val name = "file_write"
    override val description = """
        Write content to a file in the agent's workspace. Creates parent
        directories if needed. Overwrites existing files by default.
    """.trimIndent()

    override val parametersSchema = """
        {
          "type": "object",
          "properties": {
            "path": {
              "type": "string",
              "description": "Path to write, relative to workspace root"
            },
            "content": {
              "type": "string",
              "description": "Content to write"
            },
            "append": {
              "type": "boolean",
              "description": "If true, append instead of overwrite",
              "default": false
            }
          },
          "required": ["path", "content"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: JsonElement): ToolResult {
        val obj = arguments as? JsonObject
            ?: return ToolResult.Error("Arguments must be a JSON object")
        val pathStr = obj["path"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing 'path' parameter")
        val content = obj["content"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing 'content' parameter")
        val append = obj["append"]?.jsonPrimitive?.contentOrNull == "true"

        val file = try {
            workspace.resolve(pathStr)
        } catch (e: SecurityException) {
            return ToolResult.Error(e.message ?: "Invalid path")
        }

        // Quota check (2GB default)
        val projectedBytes = (if (append) file.length() + content.toByteArray().size else content.toByteArray().size).toLong()
        if (workspace.wouldExceedQuota(projectedBytes, 2048)) {
            return ToolResult.Error("Workspace quota exceeded")
        }

        try {
            PathGuard.ensureParentExists(file)
            if (append) file.appendText(content) else file.writeText(content)
        } catch (e: IOException) {
            return ToolResult.Error("Failed to write: ${e.message}")
        }

        val output = buildJsonObject {
            put("path", pathStr)
            put("bytes_written", JsonPrimitive(content.toByteArray().size))
            put("appended", JsonPrimitive(append))
        }
        return ToolResult.Success(output, "Wrote ${content.toByteArray().size} bytes to $pathStr")
    }

    fun toSpec(): ToolSpec = ToolSpec(name, description, parametersSchema)
}

@Singleton
class FileListTool @Inject constructor(
    private val workspace: Workspace
) : AgentTool {

    override val name = "file_list"
    override val description = """
        List files and directories in the agent's workspace.
        Returns names, types, and sizes.
    """.trimIndent()

    override val parametersSchema = """
        {
          "type": "object",
          "properties": {
            "path": {
              "type": "string",
              "description": "Directory to list, relative to workspace root. Defaults to root.",
              "default": "."
            },
            "recursive": {
              "type": "boolean",
              "description": "If true, list recursively",
              "default": false
            }
          }
        }
    """.trimIndent()

    override suspend fun execute(arguments: JsonElement): ToolResult {
        val obj = arguments as? JsonObject ?: JsonObject(emptyMap())
        val pathStr = obj["path"]?.jsonPrimitive?.content ?: "."
        val recursive = obj["recursive"]?.jsonPrimitive?.contentOrNull == "true"

        val dir = try {
            workspace.resolve(pathStr)
        } catch (e: SecurityException) {
            return ToolResult.Error(e.message ?: "Invalid path")
        }

        if (!dir.exists()) {
            return ToolResult.Error("Directory not found: $pathStr")
        }
        if (!dir.isDirectory) {
            return ToolResult.Error("Not a directory: $pathStr")
        }

        val entries = mutableListOf<JsonObject>()
        val walk: Sequence<java.io.File> = if (recursive) {
            dir.walkTopDown()
        } else {
            (dir.listFiles()?.toList() ?: emptyList()).asSequence()
        }
        val limited = walk.take(500).toList()  // hard cap
        var truncated = false
        for (f in limited) {
            val rel = dir.toPath().relativize(f.toPath()).toString()
            if (rel.isEmpty()) continue
            entries.add(buildJsonObject {
                put("name", JsonPrimitive(rel))
                put("type", JsonPrimitive(if (f.isDirectory) "directory" else "file"))
                put("size", JsonPrimitive(if (f.isFile) f.length() else 0))
                put("modified", JsonPrimitive(f.lastModified()))
            })
        }
        truncated = walk.take(501).count() > 500

        val output = buildJsonObject {
            put("path", pathStr)
            put("entries", JsonPrimitive(entries.size))
            // Use a JsonArray of the entry objects
            val arr = JsonArray(entries)
            put("items", arr)
            put("truncated", JsonPrimitive(truncated))
        }
        return ToolResult.Success(output, "Listed ${entries.size} entries in $pathStr")
    }

    fun toSpec(): ToolSpec = ToolSpec(name, description, parametersSchema)
}
