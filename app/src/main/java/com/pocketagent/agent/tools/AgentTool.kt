package com.pocketagent.agent.tools

import kotlinx.serialization.json.JsonElement

/**
 * Result of executing a tool.
 *
 * v6: Error now has an optional `suggestion` field — actionable advice for the LLM
 * on what to try next. This is the Anthropic "Writing Effective Tools" principle:
 * "Prompt-engineer your error responses to clearly communicate specific and
 * actionable improvements."
 */
sealed class ToolResult {
    data class Success(val output: JsonElement, val display: String? = null) : ToolResult()
    data class Error(
        val message: String,
        val suggestion: String? = null,
        val display: String? = null
    ) : ToolResult()
}

/**
 * A tool the agent can call. Implementations register via Hilt and are
 * discovered by ToolRouter.
 */
interface AgentTool {
    /** Tool name as exposed to the LLM. Must be lowercase, snake_case. */
    val name: String

    /** Human-readable description for the LLM. */
    val description: String

    /** JSON schema for parameters (as a string). */
    val parametersSchema: String

    /** Execute the tool with parsed arguments. */
    suspend fun execute(arguments: JsonElement): ToolResult
}
