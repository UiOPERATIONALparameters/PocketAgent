package com.pocketagent.agent.state

import com.pocketagent.llm.ChatMessage
import com.pocketagent.llm.LlmProvider
import com.pocketagent.llm.LlmRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the conversation context window.
 *
 * Responsibilities:
 *   1. Estimate token count for the current message list
 *   2. Auto-compact when approaching the model's context window limit
 *   3. Provide a manual `compact` tool the agent can call
 *
 * Token estimation: chars/4 heuristic (good enough for auto-compact triggers;
 * the actual count is enforced server-side by the LLM API).
 *
 * Compaction strategy:
 *   - When total tokens > threshold * max_input_tokens (default 70%):
 *     a. Ask the LLM to summarize all messages except the last 6 (keep recent context)
 *     b. Replace old messages with a single system-injected summary
 *     c. Persist the summary to ~/.pocketagent/scratchpad.md
 *   - The summary message is marked so we don't re-summarize it
 */
@Singleton
class ContextManager @Inject constructor(
    private val stateStore: StateStore
) {
    data class CompactionResult(
        val newMessages: List<ChatMessage>,
        val summary: String,
        val tokensBefore: Int,
        val tokensAfter: Int,
        val messagesCompacted: Int
    )

    /**
     * Estimate token count for a list of messages.
     * Heuristic: chars / 4 (works well for English; CJK is closer to chars/2).
     * Adjusts for tool calls which have JSON overhead.
     */
    fun estimateTokens(messages: List<ChatMessage>): Int {
        var total = 0
        for (msg in messages) {
            // Role + formatting overhead
            total += 4
            // Content
            msg.content?.let { total += it.length / 4 }
            // Reasoning
            msg.reasoning?.let { total += it.length / 4 }
            // Tool calls (JSON is verbose)
            for (tc in msg.toolCalls) {
                total += 8  // overhead
                total += tc.name.length / 4
                total += tc.arguments.length / 4
            }
            // Tool result
            msg.content?.let { if (msg.role == ChatMessage.Role.Tool) total += it.length / 4 }
        }
        return total
    }

    /**
     * Check if compaction is needed.
     * @param messages Current message list
     * @param maxInputTokens Model's max input token count (from /models endpoint)
     * @param threshold Compaction trigger threshold (default 0.7 = 70%)
     */
    fun needsCompaction(messages: List<ChatMessage>, maxInputTokens: Int, threshold: Float = 0.7f): Boolean {
        if (maxInputTokens <= 0) return false
        val current = estimateTokens(messages)
        return current >= (maxInputTokens * threshold).toInt()
    }

    /**
     * Compact the conversation by asking the LLM to summarize old messages.
     *
     * @param provider LLM provider for the summarization call
     * @param modelId Model to use (typically the same model, or a faster one)
     * @param messages Current messages
     * @param maxInputTokens Model's context window
     * @param keepRecent How many recent messages to keep verbatim (default 6)
     */
    suspend fun compact(
        provider: LlmProvider,
        modelId: String,
        messages: List<ChatMessage>,
        maxInputTokens: Int,
        keepRecent: Int = 6
    ): Result<CompactionResult> = withContext(Dispatchers.IO) {
        try {
            val tokensBefore = estimateTokens(messages)
            if (messages.size <= keepRecent) {
                return@withContext Result.success(CompactionResult(messages, "No compaction needed — too few messages.", tokensBefore, tokensBefore, 0))
            }

            val toCompact = messages.take(messages.size - keepRecent)
            val toKeep = messages.takeLast(keepRecent)

            // Build the summarization prompt
            val summaryPrompt = buildSummaryPrompt(toCompact)
            val summaryRequest = LlmRequest(
                model = modelId,
                messages = listOf(
                    ChatMessage(
                        role = ChatMessage.Role.System,
                        content = "You are a context compactor. Summarize the conversation so far into a structured note that preserves: (1) what the user asked for, (2) what the agent did, (3) file paths created or modified, (4) commands that worked and didn't work, (5) the current state and next steps. Be concise but complete."
                    ),
                    ChatMessage(role = ChatMessage.Role.User, content = summaryPrompt)
                ),
                tools = emptyList(),
                temperature = 0.3f,
                maxTokens = 2000
            )

            // Collect the summary (non-streaming for simplicity)
            val summaryBuilder = StringBuilder()
            provider.stream(summaryRequest).collect { delta ->
                when (delta) {
                    is com.pocketagent.llm.StreamDelta.Content -> summaryBuilder.append(delta.text)
                    else -> {}
                }
            }
            val summary = summaryBuilder.toString().ifBlank {
                return@withContext Result.failure(RuntimeException("Compaction failed: empty summary"))
            }

            // Build new message list: system summary + kept messages
            val summaryMessage = ChatMessage(
                role = ChatMessage.Role.System,
                content = "[Compacted Context]\n\n$summary\n\n[End Compacted Context]\n\nAbove is a summary of prior conversation. Continue from here."
            )
            val newMessages = listOf(summaryMessage) + toKeep

            // Persist summary to scratchpad
            val scratchpadEntry = "## Auto-Compacted Summary (${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())})\n$summary\n\n"
            val currentScratch = stateStore.getScratchpad()
            stateStore.setScratchpad(currentScratch + scratchpadEntry)

            val tokensAfter = estimateTokens(newMessages)
            Result.success(CompactionResult(newMessages, summary, tokensBefore, tokensAfter, toCompact.size))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildSummaryPrompt(messages: List<ChatMessage>): String {
        val sb = StringBuilder()
        sb.append("Summarize the following conversation. Include all file paths, commands, decisions, and the current task state.\n\n")
        sb.append("--- CONVERSATION ---\n\n")
        for (msg in messages) {
            when (msg.role) {
                ChatMessage.Role.System -> {}  // skip system messages
                ChatMessage.Role.User -> {
                    sb.append("USER: ${msg.content ?: ""}\n\n")
                }
                ChatMessage.Role.Assistant -> {
                    sb.append("ASSISTANT: ${msg.content ?: ""}\n")
                    for (tc in msg.toolCalls) {
                        sb.append("  [tool call: ${tc.name}(${tc.arguments.take(200)})]\n")
                    }
                    sb.append("\n")
                }
                ChatMessage.Role.Tool -> {
                    val content = msg.content?.take(300) ?: ""
                    sb.append("  [tool result: $content]\n\n")
                }
            }
        }
        return sb.toString()
    }
}
