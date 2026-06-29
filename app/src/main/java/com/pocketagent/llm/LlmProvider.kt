package com.pocketagent.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * A single chat message in the conversation.
 *
 * Content can be either a plain string (for text-only messages) or a list of
 * content parts (for multimodal messages with text + images).
 */
@Serializable
data class ChatMessage(
    val role: Role,
    val content: String? = null,
    val contentParts: List<ContentPart>? = null,  // for multimodal (vision)
    val reasoning: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,  // set when role == Tool
    val name: String? = null
) {
    enum class Role { System, User, Assistant, Tool }

    @Serializable
    data class ToolCall(
        val id: String,
        val name: String,
        val arguments: String  // raw JSON arguments
    )

    @Serializable
    sealed class ContentPart {
        @Serializable
        data class Text(val text: String) : ContentPart()

        @Serializable
        data class Image(
            val base64: String,        // base64-encoded image data (no data: prefix)
            val mimeType: String,      // e.g. "image/png", "image/jpeg"
            val detail: String = "auto"  // "auto", "low", "high" — OpenAI spec
        ) : ContentPart()
    }
}

/**
 * A tool/function the model can call.
 */
@Serializable
data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: String  // raw JSON schema string
)

/**
 * Streaming delta from the model.
 */
sealed class StreamDelta {
    data class Content(val text: String) : StreamDelta()
    data class Reasoning(val text: String) : StreamDelta()
    data class ToolCall(val index: Int, val id: String?, val name: String?, val argumentsChunk: String) : StreamDelta()
    data class Finish(val reason: String?) : StreamDelta()
    data class Usage(val promptTokens: Int, val completionTokens: Int) : StreamDelta()
    data class Error(val message: String, val cause: Throwable? = null) : StreamDelta()
}

/**
 * A fetched model entry from /models.
 */
@Serializable
data class ModelInfo(
    val id: String,
    val displayName: String? = null,
    val ownedBy: String? = null,
    val maxInputTokens: Int? = null,
    val maxOutputTokens: Int? = null,
    val inputModalities: List<String> = emptyList(),
    val outputModalities: List<String> = emptyList()
) {
    val supportsVision: Boolean get() = "image" in inputModalities
}

/**
 * Configuration for an LLM call.
 */
data class LlmRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val temperature: Float = 0.7f,
    val maxTokens: Int? = null,
    val topP: Float = 1.0f
)

/**
 * Unified LLM provider interface.
 * Each backend (OpenAI-compatible, Anthropic, Gemini) implements this.
 */
interface LlmProvider {
    /** Provider ID for persistence. */
    val id: String

    /** Human-readable provider name. */
    val displayName: String

    /** Test the connection and return available models. Throws on failure. */
    suspend fun listModels(): List<ModelInfo>

    /** Stream a chat completion. Emits StreamDelta events until terminal delta. */
    fun stream(request: LlmRequest): Flow<StreamDelta>
}
