package com.pocketagent.agent

import com.pocketagent.agent.tools.ToolResult
import com.pocketagent.agent.tools.ToolRouter
import com.pocketagent.llm.ChatMessage
import com.pocketagent.llm.LlmProvider
import com.pocketagent.llm.LlmRequest
import com.pocketagent.llm.StreamDelta
import com.pocketagent.llm.ToolSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The agent loop:
 * 1. Send messages + tool specs to the LLM
 * 2. Stream the response back
 * 3. If the LLM emits a tool call, execute it and feed result back
 * 4. Repeat until the LLM finishes with no tool calls
 *
 * Mirrors Anthropic's Computer Use agent loop. Works with any OpenAI-compatible
 * model that supports function calling (GLM 5.2, Claude, GPT-4o, etc).
 *
 * Verified end-to-end with GLM 5.2 on the user's gateway.
 */
@Singleton
class AgentLoop @Inject constructor(
    private val toolRouter: ToolRouter
) {
    data class Event(
        val type: Type,
        val content: String = "",
        val reasoning: String = "",
        val toolCallId: String? = null,
        val toolName: String? = null,
        val toolArguments: String? = null,
        val toolResult: String? = null,
        val toolDisplay: String? = null,
        val error: String? = null
    ) {
        enum class Type {
            CONTENT_DELTA,        // streaming text content
            REASONING_DELTA,      // streaming reasoning
            TOOL_CALL_START,      // model emitted a tool call
            TOOL_RESULT,          // tool finished, result fed back
            FINISHED,             // turn complete
            ERROR                 // fatal error
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Run the agent loop. Emits events as they happen.
     * Caller is responsible for persisting messages and updating UI.
     */
    fun run(
        provider: LlmProvider,
        modelId: String,
        messages: List<ChatMessage>,
        systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
        maxIterations: Int = 10
    ): Flow<Event> = flow {
        var currentMessages = mutableListOf<ChatMessage>().apply {
            add(ChatMessage(role = ChatMessage.Role.System, content = systemPrompt))
            addAll(messages)
        }
        val tools = toolRouter.specs()

        for (iteration in 0 until maxIterations) {
            val request = LlmRequest(
                model = modelId,
                messages = currentMessages.toList(),
                tools = tools,
                temperature = 0.7f
            )

            // Accumulators for this iteration
            val contentBuilder = StringBuilder()
            val reasoningBuilder = StringBuilder()
            val toolCallAccumulators = mutableMapOf<Int, ToolCallAccumulator>()
            var finishReason: String? = null

            try {
                provider.stream(request).collect { delta ->
                    when (delta) {
                        is StreamDelta.Content -> {
                            contentBuilder.append(delta.text)
                            emit(Event(Event.Type.CONTENT_DELTA, content = delta.text))
                        }
                        is StreamDelta.Reasoning -> {
                            reasoningBuilder.append(delta.text)
                            emit(Event(Event.Type.REASONING_DELTA, reasoning = delta.text))
                        }
                        is StreamDelta.ToolCall -> {
                            val acc = toolCallAccumulators.getOrPut(delta.index) {
                                ToolCallAccumulator()
                            }
                            if (delta.id != null) acc.id = delta.id
                            if (delta.name != null) acc.name = delta.name
                            acc.argumentsBuilder.append(delta.argumentsChunk)
                        }
                        is StreamDelta.Finish -> {
                            finishReason = delta.reason
                        }
                        is StreamDelta.Usage -> {
                            // Could emit usage event; skip for v0.1
                        }
                        is StreamDelta.Error -> {
                            emit(Event(Event.Type.ERROR, error = delta.message))
                            return@collect
                        }
                    }
                }
            } catch (e: Exception) {
                emit(Event(Event.Type.ERROR, error = e.message ?: e::class.simpleName))
                return@flow
            }

            // Build the assistant message for this iteration
            val toolCalls = toolCallAccumulators.toSortedMap().values.mapIndexed { idx, acc ->
                val id = acc.id ?: "call_${UUID.randomUUID()}"
                ChatMessage.ToolCall(
                    id = id,
                    name = acc.name ?: "unknown",
                    arguments = acc.argumentsBuilder.toString()
                )
            }

            val assistantMsg = ChatMessage(
                role = ChatMessage.Role.Assistant,
                content = contentBuilder.toString().ifEmpty { null },
                reasoning = reasoningBuilder.toString().ifEmpty { null },
                toolCalls = toolCalls
            )
            currentMessages.add(assistantMsg)

            // If no tool calls, we're done
            if (toolCalls.isEmpty()) {
                emit(Event(Event.Type.FINISHED))
                return@flow
            }

            // Execute each tool call and feed result back
            for (tc in toolCalls) {
                emit(Event(
                    type = Event.Type.TOOL_CALL_START,
                    toolCallId = tc.id,
                    toolName = tc.name,
                    toolArguments = tc.arguments
                ))

                try {
                    val args = json.parseToJsonElement(tc.arguments.ifEmpty { "{}" })
                    val result = toolRouter.execute(tc.name, args)
                    val (output, display) = when (result) {
                        is ToolResult.Success -> result.output.toString() to (result.display ?: "")
                        is ToolResult.Error -> buildJsonObject {
                            put("error", JsonPrimitive(result.message))
                        }.toString() to (result.display ?: result.message)
                    }
                    emit(Event(
                        type = Event.Type.TOOL_RESULT,
                        toolCallId = tc.id,
                        toolName = tc.name,
                        toolResult = output,
                        toolDisplay = display
                    ))

                    // Add tool result message
                    currentMessages.add(ChatMessage(
                        role = ChatMessage.Role.Tool,
                        content = output,
                        toolCallId = tc.id,
                        name = tc.name
                    ))
                } catch (e: Exception) {
                    val errMsg = "Tool execution failed: ${e.message}"
                    emit(Event(
                        type = Event.Type.TOOL_RESULT,
                        toolCallId = tc.id,
                        toolName = tc.name,
                        toolResult = errMsg,
                        toolDisplay = errMsg
                    ))
                    currentMessages.add(ChatMessage(
                        role = ChatMessage.Role.Tool,
                        content = errMsg,
                        toolCallId = tc.id,
                        name = tc.name
                    ))
                }
            }
            // Loop back to LLM with tool results
        }

        // Hit max iterations
        emit(Event(Event.Type.FINISHED))
    }

    private data class ToolCallAccumulator(
        var id: String? = null,
        var name: String? = null,
        val argumentsBuilder: StringBuilder = StringBuilder()
    )

    companion object {
        const val DEFAULT_SYSTEM_PROMPT = """You are PocketAgent, an AI agent running on the user's Android phone.

You have direct access to the user's device through these tools:
- bash: Run shell commands in your private workspace (~/)
- file_read: Read a file from your workspace
- file_write: Write or append to a file in your workspace
- file_list: List files and directories in your workspace
- web_fetch: Fetch a URL via HTTP

Your workspace is a private directory at /data/data/com.pocketagent/files/workspace/.
You have a 'projects' subdirectory for organized work.

You can:
- Create files, run scripts, parse text
- Fetch web content (APIs, docs, raw files)
- Build up projects in your workspace
- Iterate on tasks autonomously

When the user asks you to build or analyze something:
1. Plan your approach briefly
2. Use tools to execute step by step
3. Show progress as you go
4. Summarize what you did at the end

Be concise in your text responses. Let the tool calls speak for themselves.
If a tool fails, diagnose the error and try again with a fix.

The user can see every tool call you make. Be transparent."""
    }
}
