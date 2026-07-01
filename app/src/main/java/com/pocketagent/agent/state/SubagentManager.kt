package com.pocketagent.agent.state

import com.pocketagent.agent.AgentLoop
import com.pocketagent.llm.ChatMessage
import com.pocketagent.llm.LlmProvider
import com.pocketagent.storage.db.ConversationDao
import com.pocketagent.storage.db.ConversationEntity
import com.pocketagent.storage.db.MessageDao
import com.pocketagent.storage.db.MessageEntity
import com.pocketagent.storage.db.toLlm
import com.pocketagent.storage.prefs.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages subagent conversations.
 *
 * A subagent is a separate conversation that runs in its own context window,
 * with its own tool budget. The parent conversation's context is not polluted
 * by the subagent's tool calls — only the final summary is returned.
 *
 * Pattern (mirrors z.ai agentic mode's Task tool + Claude Code's Task tool):
 *   1. Parent calls `task(description, prompt)`
 *   2. SubagentManager creates a new conversation with parentId = parent
 *   3. Subagent runs the agent loop with its own (smaller) budget
 *   4. When done, the subagent's final response is saved as conversation.summary
 *   5. A single "Subagent result" message is added to the parent conversation
 *
 * Subagents inherit the parent's provider+model. They have read-only workspace
 * access by default (configurable).
 */
@Singleton
class SubagentManager @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val settings: SettingsRepository
) {
    /** Events emitted as subagents progress. UI observes this. */
    sealed class Event {
        data class Started(val subagentId: String, val parentId: String, val description: String) : Event()
        data class Progress(val subagentId: String, val content: String) : Event()
        data class Completed(val subagentId: String, val parentId: String, val summary: String) : Event()
        data class Failed(val subagentId: String, val parentId: String, val error: String) : Event()
    }

    private val _events = MutableSharedFlow<Event>(replay = 0, extraBufferCapacity = 16)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    /**
     * Spawn a subagent.
     *
     * @param parentConversationId The parent conversation ID
     * @param provider LLM provider (inherited from parent)
     * @param modelId Model ID (inherited from parent)
     * @param description Short description (shown in UI)
     * @param prompt The actual task prompt for the subagent
     * @param maxIterations Tool budget (default 15 — smaller than parent's 50)
     * @return The subagent's final summary, or an error message
     */
    suspend fun spawn(
        parentConversationId: String,
        provider: LlmProvider,
        modelId: String,
        description: String,
        prompt: String,
        agentLoop: AgentLoop,
        maxIterations: Int = 15
    ): Result<String> = withContext(Dispatchers.IO) {
        val subagentId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        // Create the subagent conversation
        conversationDao.upsert(
            ConversationEntity(
                id = subagentId,
                title = description.take(60),
                createdAt = now,
                updatedAt = now,
                providerId = settings.getActiveProvider()?.id,
                modelId = modelId,
                parentId = parentConversationId,
                isSubagent = true,
                status = "active"
            )
        )

        // Add the user prompt as the first message
        val userMsgId = UUID.randomUUID().toString()
        messageDao.upsert(
            MessageEntity(
                id = userMsgId,
                conversationId = subagentId,
                role = "user",
                content = prompt,
                reasoning = null,
                toolCallsJson = null,
                toolCallId = null,
                toolName = null,
                createdAt = now
            )
        )

        _events.emit(Event.Started(subagentId, parentConversationId, description))

        // Run the subagent's agent loop
        val summaryBuilder = StringBuilder()
        val errors = mutableListOf<String>()

        try {
            val history = messageDao.getForConversation(subagentId).map { it.toLlm() }
            agentLoop.run(
                provider = provider,
                modelId = modelId,
                messages = history,
                systemPrompt = SUBAGENT_SYSTEM_PROMPT,
                maxIterations = maxIterations,
                temperature = 0.5f
            ).collect { event ->
                when (event.type) {
                    AgentLoop.Event.Type.CONTENT_DELTA -> {
                        summaryBuilder.append(event.content)
                        _events.emit(Event.Progress(subagentId, event.content))
                    }
                    AgentLoop.Event.Type.FINISHED -> {
                        // Loop is done
                    }
                    AgentLoop.Event.Type.ERROR -> {
                        errors.add(event.error ?: "Unknown error")
                    }
                    else -> {}
                }
            }

            val summary = summaryBuilder.toString().ifBlank {
                if (errors.isNotEmpty()) "Subagent failed: ${errors.joinToString("; ")}"
                else "Subagent completed (no summary output)"
            }

            // Update the subagent conversation
            conversationDao.updateSubagentStatus(
                id = subagentId,
                status = if (errors.isNotEmpty()) "failed" else "completed",
                summary = summary,
                updatedAt = System.currentTimeMillis()
            )

            _events.emit(Event.Completed(subagentId, parentConversationId, summary))
            Result.success(summary)
        } catch (e: Exception) {
            conversationDao.updateSubagentStatus(
                id = subagentId,
                status = "failed",
                summary = e.message,
                updatedAt = System.currentTimeMillis()
            )
            _events.emit(Event.Failed(subagentId, parentConversationId, e.message ?: e::class.simpleName ?: "Unknown error"))
            Result.failure(e)
        }
    }

    /** Get all subagents for a parent conversation. */
    suspend fun getSubagents(parentId: String): List<ConversationEntity> = withContext(Dispatchers.IO) {
        conversationDao.getSubagents(parentId)
    }

    companion object {
        const val SUBAGENT_SYSTEM_PROMPT = """You are a PocketAgent subagent. You have been spawned by a parent agent to handle a specific task.

## Your Role
- Focus on the task you were given. Don't wander.
- Be concise. The parent only sees your final summary.
- Use tools efficiently. You have a smaller tool budget than the parent.
- When done, write a clear summary of what you did, what you found, and any files you created or modified.

## Output Format
Your final message will be the summary the parent sees. Structure it as:
- What I did
- Files I created/modified (with paths)
- Key findings
- Suggested next steps (if any)

Be concise. The parent's context depends on your summary being information-dense."""
    }
}
