package com.pocketagent.agent

import com.pocketagent.agent.tools.ToolResult
import com.pocketagent.agent.tools.ToolRouter
import com.pocketagent.llm.ChatMessage
import com.pocketagent.llm.LlmProvider
import com.pocketagent.llm.LlmRequest
import com.pocketagent.llm.StreamDelta
import com.pocketagent.llm.ToolSpec
import kotlinx.coroutines.CancellationException
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
            CONTENT_DELTA,
            REASONING_DELTA,
            TOOL_CALL_START,
            TOOL_RESULT,
            FINISHED,
            ERROR
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun run(
        provider: LlmProvider,
        modelId: String,
        messages: List<ChatMessage>,
        systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
        maxIterations: Int = 15
    ): Flow<Event> = flow {
        var currentMessages = mutableListOf<ChatMessage>().apply {
            add(ChatMessage(role = ChatMessage.Role.System, content = systemPrompt))
            addAll(messages)
        }
        val tools = toolRouter.specs()
        val knownToolNames = tools.map { it.name }.toSet()

        for (iteration in 0 until maxIterations) {
            val request = LlmRequest(
                model = modelId,
                messages = currentMessages.toList(),
                tools = tools,
                temperature = 0.7f
            )

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
                            // CRITICAL FIX: Only set name from the FIRST non-null name.
                            // Some models send the name in multiple chunks or resend it
                            // with partial values. Overwriting corrupts the name.
                            if (delta.id != null && acc.id == null) {
                                acc.id = delta.id
                            }
                            if (delta.name != null && acc.name == null) {
                                // Only accept the name if it looks valid (alphanumeric + underscore)
                                val candidateName = delta.name.trim()
                                if (candidateName.isNotEmpty() && candidateName.matches(Regex("^[a-zA-Z_][a-zA-Z0-9_]*$"))) {
                                    acc.name = candidateName
                                }
                            }
                            // Accumulate arguments
                            if (delta.argumentsChunk.isNotEmpty()) {
                                acc.argumentsBuilder.append(delta.argumentsChunk)
                            }
                        }
                        is StreamDelta.Finish -> {
                            finishReason = delta.reason
                        }
                        is StreamDelta.Usage -> {}
                        is StreamDelta.Error -> {
                            emit(Event(Event.Type.ERROR, error = delta.message))
                            return@collect
                        }
                    }
                }
            } catch (e: CancellationException) {
                // Stream was cancelled (user tapped Stop, or coroutine was cancelled)
                emit(Event(Event.Type.FINISHED))
                return@flow
            } catch (e: Exception) {
                emit(Event(Event.Type.ERROR, error = "Stream error: ${e.message ?: e::class.simpleName}"))
                return@flow
            }

            // Build tool calls from accumulators
            val toolCalls = toolCallAccumulators.toSortedMap().values.mapIndexed { idx, acc ->
                val id = acc.id ?: "call_${UUID.randomUUID()}"
                val name = acc.name ?: ""
                val arguments = acc.argumentsBuilder.toString().ifEmpty { "{}" }
                ChatMessage.ToolCall(
                    id = id,
                    name = name,
                    arguments = arguments
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

            // Execute each tool call
            for (tc in toolCalls) {
                // Validate tool name
                if (tc.name.isBlank()) {
                    val errMsg = "Tool call received with empty name. Arguments: ${tc.arguments.take(200)}"
                    emit(Event(
                        type = Event.Type.TOOL_CALL_START,
                        toolCallId = tc.id,
                        toolName = "(empty)",
                        toolArguments = tc.arguments
                    ))
                    emit(Event(
                        type = Event.Type.TOOL_RESULT,
                        toolCallId = tc.id,
                        toolName = "(empty)",
                        toolResult = buildJsonObject {
                            put("error", JsonPrimitive(errMsg))
                        }.toString(),
                        toolDisplay = errMsg
                    ))
                    currentMessages.add(ChatMessage(
                        role = ChatMessage.Role.Tool,
                        content = buildJsonObject {
                            put("error", JsonPrimitive(errMsg))
                        }.toString(),
                        toolCallId = tc.id,
                        name = tc.name.ifBlank { "unknown" }
                    ))
                    continue
                }

                if (tc.name !in knownToolNames) {
                    val errMsg = "Unknown tool: '${tc.name}'. Available tools: ${knownToolNames.joinToString(", ")}"
                    emit(Event(
                        type = Event.Type.TOOL_CALL_START,
                        toolCallId = tc.id,
                        toolName = tc.name,
                        toolArguments = tc.arguments
                    ))
                    emit(Event(
                        type = Event.Type.TOOL_RESULT,
                        toolCallId = tc.id,
                        toolName = tc.name,
                        toolResult = buildJsonObject {
                            put("error", JsonPrimitive(errMsg))
                        }.toString(),
                        toolDisplay = "Error: $errMsg"
                    ))
                    currentMessages.add(ChatMessage(
                        role = ChatMessage.Role.Tool,
                        content = buildJsonObject {
                            put("error", JsonPrimitive(errMsg))
                        }.toString(),
                        toolCallId = tc.id,
                        name = tc.name
                    ))
                    continue
                }

                // Parse arguments
                val args = try {
                    json.parseToJsonElement(tc.arguments.ifEmpty { "{}" })
                } catch (e: Exception) {
                    emit(Event(
                        type = Event.Type.TOOL_CALL_START,
                        toolCallId = tc.id,
                        toolName = tc.name,
                        toolArguments = tc.arguments
                    ))
                    val errMsg = "Invalid JSON arguments: ${e.message}. Raw: ${tc.arguments.take(200)}"
                    emit(Event(
                        type = Event.Type.TOOL_RESULT,
                        toolCallId = tc.id,
                        toolName = tc.name,
                        toolResult = buildJsonObject {
                            put("error", JsonPrimitive(errMsg))
                        }.toString(),
                        toolDisplay = "Error: $errMsg"
                    ))
                    currentMessages.add(ChatMessage(
                        role = ChatMessage.Role.Tool,
                        content = buildJsonObject {
                            put("error", JsonPrimitive(errMsg))
                        }.toString(),
                        toolCallId = tc.id,
                        name = tc.name
                    ))
                    continue
                }

                // Emit tool call start
                emit(Event(
                    type = Event.Type.TOOL_CALL_START,
                    toolCallId = tc.id,
                    toolName = tc.name,
                    toolArguments = tc.arguments
                ))

                // Execute
                val result = try {
                    toolRouter.execute(tc.name, args)
                } catch (e: Exception) {
                    ToolResult.Error("Execution failed: ${e.message ?: e::class.simpleName}")
                }

                val (output, display) = when (result) {
                    is ToolResult.Success -> result.output.toString() to (result.display ?: "")
                    is ToolResult.Error -> buildJsonObject {
                        put("error", JsonPrimitive(result.message))
                    }.toString() to (result.display ?: "Error: ${result.message}")
                }

                emit(Event(
                    type = Event.Type.TOOL_RESULT,
                    toolCallId = tc.id,
                    toolName = tc.name,
                    toolResult = output,
                    toolDisplay = display
                ))

                currentMessages.add(ChatMessage(
                    role = ChatMessage.Role.Tool,
                    content = output,
                    toolCallId = tc.id,
                    name = tc.name
                ))
            }
        }

        // Hit max iterations
        emit(Event(Event.Type.CONTENT_DELTA, content = "\n\n*[Reached maximum tool call iterations. Stopping to prevent infinite loops.]*"))
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
You have 'projects', 'tmp', and 'downloads' subdirectories.

You can:
- Create files, run scripts, parse text
- Fetch web content (APIs, docs, raw files)
- Build up projects in your workspace
- Iterate on tasks autonomously

IMPORTANT RULES:
1. Always use the EXACT tool names: bash, file_read, file_write, file_list, web_fetch
2. Make ONE tool call at a time, wait for the result, then proceed
3. If a tool fails, read the error message and adjust your approach
4. Be concise in text — let tool calls speak for themselves
5. After completing a task, summarize what you did

The user can see every tool call you make. Be transparent."""
    }
}
