package com.pocketagent.storage.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val providerId: String?,
    val modelId: String?
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversationId")]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,             // system/user/assistant/tool
    val content: String?,         // text content (nullable when only tool_calls)
    val reasoning: String?,       // reasoning_content (for thinking models)
    val toolCallsJson: String?,   // JSON-serialized list of tool calls
    val toolCallId: String?,      // set when role == tool
    val toolName: String?,        // tool name (when role == tool)
    val createdAt: Long,
    val isStreaming: Boolean = false
)

@Entity(
    tableName = "tool_runs",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("messageId")]
)
data class ToolRunEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val toolName: String,
    val argumentsJson: String,
    val resultJson: String?,
    val status: String,            // pending/success/error/timeout
    val durationMs: Long?,
    val createdAt: Long
)
