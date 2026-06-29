package com.pocketagent.agent.tools

import kotlinx.serialization.json.JsonElement

/**
 * Result of executing a tool.
 */
sealed class ToolResult {
    data class Success(val output: JsonElement, val display: String? = null) : ToolResult()
    data class Error(val message: String, val display: String? = null) : ToolResult()
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
