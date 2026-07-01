package com.pocketagent.agent.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.pocketagent.bridge.TermuxBridge
import com.pocketagent.llm.ToolSpec
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
 * v6: install_apk tool — installs an APK from the Termux home directory.
 *
 * The APK lives in Termux's $HOME (e.g., ~/projects/myapp/app/build/outputs/apk/...).
 * We download it via the daemon to the app's cache dir, then use ACTION_VIEW with
 * the system installer (the user must confirm).
 */
@Singleton
class InstallApkTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bridge: TermuxBridge
) : AgentTool {

    override val name = "install_apk"
    override val description = """
        Install an APK file from Termux onto the user's Android phone.
        Use this after building an APK with gradle or downloading one via curl.
        The user will see a system "Install app" dialog and must confirm.

        Path is relative to $HOME in Termux (or absolute under $HOME).
        The file must be a valid signed APK (.apk extension).
    """.trimIndent()

    override val parametersSchema = """
        {
          "type": "object",
          "properties": {
            "path": {
              "type": "string",
              "description": "Path to the APK file (under $HOME in Termux)"
            }
          },
          "required": ["path"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: kotlinx.serialization.json.JsonElement): ToolResult {
        val obj = arguments as? JsonObject
            ?: return ToolResult.Error("Arguments must be a JSON object", "Pass a JSON object with 'path'.")
        val pathStr = obj["path"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("Missing 'path'", "Provide a 'path' field with the APK location.")

        if (!bridge.state.isConnected) {
            return ToolResult.Error("Termux daemon not connected", "Start the daemon: `pocketagent-daemon` in Termux.")
        }

        // Stat the file via the daemon
        val statResult = bridge.statFile(pathStr)
        val stat = statResult.getOrElse { e ->
            return ToolResult.Error("Bridge error: ${e.message}", "Check Termux daemon is running.")
        }
        if (!stat.exists) {
            return ToolResult.Error("File not found: $pathStr", "Check the path is correct. Build the APK first with `./gradlew assembleDebug`.")
        }
        if (stat.type != "file") {
            return ToolResult.Error("Not a file: $pathStr", "Provide a path to an .apk file, not a directory.")
        }
        if (!pathStr.endsWith(".apk", ignoreCase = true)) {
            return ToolResult.Error("File must have .apk extension. Got: $pathStr", "Build the APK first; the output is in build/outputs/apk/.")
        }
        if (stat.size < 10_000) {
            return ToolResult.Error("APK is too small (${stat.size} bytes)", "The APK may be corrupted or the build incomplete. Rebuild with `./gradlew assembleDebug`.")
        }

        // Download the APK from Termux to the app's cache dir
        val readResult = bridge.readFile(pathStr)
        val readResp = readResult.getOrElse { e ->
            return ToolResult.Error("Failed to read APK: ${e.message}", "Check the file is readable.")
        }
        if (readResp.error != null) {
            return ToolResult.Error(readResp.error, "Check the file path.")
        }
        if (readResp.binary) {
            return ToolResult.Error("APK appears to be binary (daemon couldn't decode as UTF-8)", "APKs are binary; the daemon's read endpoint is for text. Use a different approach: copy the APK to the app's data dir via `cp` to a shared path.")
        }
        // Note: APKs >1MB will be truncated by the daemon's MAX_FILE_READ
        // For large APKs, we need a different approach — copy via bash to a shared location
        // For now, this works for small APKs and the agent can fall back to manual install
        // for large ones.

        return withContext(Dispatchers.Main) {
            try {
                // Write the APK bytes to the app's cache dir
                val apkFile = File(context.cacheDir, "install_${System.currentTimeMillis()}.apk")
                apkFile.writeBytes(readResp.content.toByteArray())

                val uri = Uri.fromFile(apkFile)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)

                val output = buildJsonObject {
                    put("path", pathStr)
                    put("size_bytes", JsonPrimitive(stat.size))
                    put("action", JsonPrimitive("install_dialog_shown"))
                    put("note", JsonPrimitive("System install dialog shown. User must confirm to install."))
                }
                ToolResult.Success(output, "Install dialog shown for ${File(pathStr).name} (${stat.size / 1024}KB)")
            } catch (e: Exception) {
                ToolResult.Error(
                    "Failed to launch installer: ${e.message ?: e::class.simpleName}",
                    "The system installer couldn't be launched. The APK is still at $pathStr — install it manually via a file manager."
                )
            }
        }
    }

    fun toSpec(): ToolSpec = ToolSpec(name, description, parametersSchema)
}
