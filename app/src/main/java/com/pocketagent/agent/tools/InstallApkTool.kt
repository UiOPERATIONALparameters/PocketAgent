package com.pocketagent.agent.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.pocketagent.llm.ToolSpec
import com.pocketagent.sandbox.Workspace
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * install_apk tool — lets the agent install APK files it has built or downloaded.
 *
 * The user must grant REQUEST_INSTALL_PACKAGES permission (declared in manifest).
 * On first use, Android shows the "Allow install unknown apps" system dialog.
 *
 * Workflow:
 *   1. Agent builds an APK via bash (e.g. `./gradlew assembleRelease`)
 *   2. Agent calls install_apk(path="downloads/app.apk")
 *   3. User sees the system "Install app" dialog with package name + permissions
 *   4. User taps Install
 *
 * This is the final piece of the "total freedom" goal — the agent can build APKs
 * AND install them, all from the phone, no PC required.
 */
@Singleton
class InstallApkTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workspace: Workspace
) : AgentTool {

    override val name = "install_apk"
    override val description = """
        Install an APK file from the agent's workspace onto the user's Android phone.
        Use this after building an APK with gradle or downloading one via curl.
        The user will see a system "Install app" dialog and must confirm.

        Path is relative to the workspace root (~).
        The file must be a valid signed APK (.apk extension).
    """.trimIndent()

    override val parametersSchema = """
        {
          "type": "object",
          "properties": {
            "path": {
              "type": "string",
              "description": "Path to the APK file, relative to workspace root"
            }
          },
          "required": ["path"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: kotlinx.serialization.json.JsonElement): ToolResult {
        val obj = arguments as? JsonObject
            ?: return ToolResult.Error("Arguments must be a JSON object")
        val pathStr = obj["path"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("Missing 'path' parameter")

        val file = try {
            workspace.resolve(pathStr)
        } catch (e: SecurityException) {
            return ToolResult.Error(e.message ?: "Invalid path")
        }

        if (!file.exists()) return ToolResult.Error("File not found: $pathStr")
        if (!file.isFile) return ToolResult.Error("Not a file: $pathStr")
        if (!file.name.endsWith(".apk", ignoreCase = true)) {
            return ToolResult.Error("File must have .apk extension. Got: ${file.name}")
        }
        if (file.length() < 10_000) {
            return ToolResult.Error("APK file is too small (${file.length()} bytes). It may be corrupted or incomplete.")
        }

        return withContext(Dispatchers.Main) {
            try {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)

                val output = buildJsonObject {
                    put("path", pathStr)
                    put("size_bytes", JsonPrimitive(file.length()))
                    put("action", JsonPrimitive("install_dialog_shown"))
                    put("note", JsonPrimitive("System install dialog shown. User must confirm to install."))
                }
                val display = "Install dialog shown for ${file.name} (${file.length() / 1024}KB)"
                ToolResult.Success(output, display)
            } catch (e: Exception) {
                ToolResult.Error("Failed to launch installer: ${e.message ?: e::class.simpleName}")
            }
        }
    }

    fun toSpec(): ToolSpec = ToolSpec(name, description, parametersSchema)
}
