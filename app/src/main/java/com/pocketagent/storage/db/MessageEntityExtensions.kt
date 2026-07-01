package com.pocketagent.storage.db

import com.pocketagent.llm.ChatMessage
import kotlinx.serialization.json.Json

/**
 * Convert a MessageEntity to a ChatMessage for the LLM.
 * This is used by both ChatViewModel and SubagentManager.
 */
private val entityJson = Json { ignoreUnknownKeys = true }

fun MessageEntity.toLlm(): ChatMessage = when (role) {
    "system" -> ChatMessage(role = ChatMessage.Role.System, content = content ?: "")
    "user" -> {
        // If toolCallsJson is set, it's a multimodal message (text + images)
        if (!toolCallsJson.isNullOrBlank() && toolCallsJson!!.startsWith("[")) {
            try {
                val parts = entityJson.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(ChatMessage.ContentPart.serializer()),
                    toolCallsJson!!
                )
                ChatMessage(role = ChatMessage.Role.User, content = content, contentParts = parts)
            } catch (_: Exception) {
                ChatMessage(role = ChatMessage.Role.User, content = content ?: "")
            }
        } else {
            ChatMessage(role = ChatMessage.Role.User, content = content ?: "")
        }
    }
    "assistant" -> {
        val toolCalls = if (!toolCallsJson.isNullOrBlank()) {
            try {
                entityJson.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(ChatMessage.ToolCall.serializer()),
                    toolCallsJson!!
                )
            } catch (_: Exception) { emptyList() }
        } else emptyList()
        ChatMessage(
            role = ChatMessage.Role.Assistant,
            content = content,
            reasoning = reasoning,
            toolCalls = toolCalls
        )
    }
    "tool" -> ChatMessage(
        role = ChatMessage.Role.Tool,
        content = content,
        toolCallId = toolCallId,
        name = toolName
    )
    else -> ChatMessage(role = ChatMessage.Role.User, content = content ?: "")
}
