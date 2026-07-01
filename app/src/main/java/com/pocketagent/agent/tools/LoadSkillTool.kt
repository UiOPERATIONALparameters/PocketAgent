package com.pocketagent.agent.tools

import android.content.Context
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * load_skill tool — loads a skill's detailed instructions into the agent's context.
 *
 * Skills are .md files in the app's assets/skills/ directory. Each skill contains
 * step-by-step instructions for a specific task (build website, build APK, research, etc.).
 *
 * This is modeled after z.ai's skill system — the agent loads relevant skills
 * when it needs expert knowledge for a specific task.
 */
@Singleton
class LoadSkillTool @Inject constructor(
    @ApplicationContext private val context: Context
) : AgentTool {

    override val name = "load_skill"
    override val description = """
        Load detailed instructions for a specific task. Skills contain step-by-step guides
        for common tasks like building websites, writing scripts, debugging code, etc.
        Call this when you need expert guidance for a task.
    """.trimIndent()

    override val parametersSchema = """
        {
          "type": "object",
          "properties": {
            "name": {
              "type": "string",
              "description": "Name of the skill to load. Available: build-website, build-apk, research-topic, write-script, make-chart, debug-code"
            }
          },
          "required": ["name"]
        }
    """.trimIndent()

    /** List of available skills (for the system prompt). */
    fun availableSkills(): List<String> = listOf(
        "build-website",
        "summarize-document",
        "convert-file",
        "data-analysis",
        "file-management",
        "install-java",
        "build-apk",
        "research-topic",
        "write-script",
        "make-chart",
        "debug-code"
    )

    override suspend fun execute(arguments: kotlinx.serialization.json.JsonElement): ToolResult {
        val obj = arguments as? JsonObject
            ?: return ToolResult.Error("Arguments must be a JSON object")
        val skillName = obj["name"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("Missing 'name' parameter")

        return withContext(Dispatchers.IO) {
            try {
                val assetPath = "skills/$skillName.md"
                val content = context.assets.open(assetPath).bufferedReader().use { it.readText() }
                val output = buildJsonObject {
                    put("skill", skillName)
                    put("content", content)
                    put("instructions", JsonPrimitive("Follow these instructions to complete the task. The skill content above is your guide."))
                }
                ToolResult.Success(output, "[Loaded skill: $skillName — ${content.take(100)}...]")
            } catch (e: Exception) {
                val available = availableSkills().joinToString(", ")
                ToolResult.Error("Skill '$skillName' not found. Available skills: $available")
            }
        }
    }

    fun toSpec(): ToolSpec = ToolSpec(name, description, parametersSchema)
}
