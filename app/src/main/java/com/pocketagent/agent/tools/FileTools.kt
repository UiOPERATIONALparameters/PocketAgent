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
        // H15 FIX: walk back to the previous UTF-8 boundary before creating the String.
        // Was: String(raw, 0, maxBytes, UTF_8) — could split multi-byte chars mid-character,
        // producing ? replacement chars or throwing on some JVMs.
        val text = if (truncated) {
            val safeEnd = findUtf8Boundary(raw, maxBytes)
            String(raw, 0, safeEnd, Charsets.UTF_8)
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
    private val workspace: Workspace,
    private val settings: com.pocketagent.storage.prefs.SettingsRepository
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
        // H14 FIX: explicit case-insensitive boolean parsing
        val append = obj["append"]?.jsonPrimitive?.contentOrNull?.equals("true", ignoreCase = true) == true

        val file = try {
            workspace.resolve(pathStr)
        } catch (e: SecurityException) {
            return ToolResult.Error(e.message ?: "Invalid path")
        }

        // M13 FIX: read quota from user's settings (was hardcoded 2048MB).
        val quotaMb = settings.settings.value.workspaceQuotaMb
        val projectedBytes = (if (append) file.length() + content.toByteArray().size else content.toByteArray().size).toLong()
        if (workspace.wouldExceedQuota(projectedBytes, quotaMb)) {
            return ToolResult.Error("Workspace quota exceeded (${quotaMb}MB). Delete files in the Files browser or raise the quota in Settings.")
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
        // C1 FIX: consume the sequence ONCE. Was: take(500).toList() then take(501).count()
        // (Kotlin Sequence is single-use; second call returned 0, so truncated was always false)
        val allFiles = walk.take(501).toList()  // 1 extra to detect truncation
        val limited = allFiles.take(500)
        val truncated = allFiles.size > 500
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

/**
 * Find the largest offset <= maxBytes that ends on a UTF-8 character boundary.
 * UTF-8 continuation bytes have the bit pattern 10xxxxxx (0x80-0xBF).
 * Walk back from maxBytes until we find a byte that is NOT a continuation byte.
 * Used by FileReadTool to avoid splitting multi-byte characters on truncation (H15).
 */
private fun findUtf8Boundary(bytes: ByteArray, maxBytes: Int): Int {
    if (maxBytes >= bytes.size) return bytes.size
    var i = maxBytes
    while (i > 0) {
        val b = bytes[i - 1].toInt() and 0xFF
        // If this byte is a continuation byte (0x80-0xBF), it's mid-character — keep walking back
        if (b and 0xC0 == 0x80) {
            i--
        } else {
            // This is a leading byte (or ASCII); i is now a valid boundary
            break
        }
    }
    return i
}
