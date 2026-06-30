package com.pocketagent.agent

import com.pocketagent.agent.tools.ToolResult
import com.pocketagent.agent.tools.ToolRouter
import com.pocketagent.llm.ChatMessage
import com.pocketagent.llm.LlmProvider
import com.pocketagent.llm.LlmRequest
import com.pocketagent.llm.StreamDelta
import com.pocketagent.llm.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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
 * CRITICAL: The TOOL_CALLS_READY event fires AFTER all tool calls in an
 * iteration are accumulated but BEFORE execution. The caller MUST persist
 * an assistant message with the tool_calls array at this point, so that
 * the conversation history sent to the LLM on the next turn includes:
 *   assistant { tool_calls: [...] }
 *   tool { tool_call_id: X, content: ... }
 * Without the assistant message, the API rejects with "tool call id not found".
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
        val toolCalls: List<ChatMessage.ToolCall>? = null,  // for TOOL_CALLS_READY
        val error: String? = null
    ) {
        enum class Type {
            CONTENT_DELTA,
            REASONING_DELTA,
            TOOL_CALLS_READY,   // NEW: all tool calls accumulated, about to execute
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
        maxIterations: Int = 30,
        tokenSaveMode: Boolean = false
    ): Flow<Event> = flow {
        // In token save mode: use minimal system prompt and no tools
        val effectiveSystemPrompt = if (tokenSaveMode) {
            // Minimal prompt — just the user's custom prompt if set, otherwise very short
            if (systemPrompt != DEFAULT_SYSTEM_PROMPT) systemPrompt else "You are PocketAgent, a helpful AI assistant."
        } else {
            systemPrompt
        }

        var currentMessages = mutableListOf<ChatMessage>().apply {
            add(ChatMessage(role = ChatMessage.Role.System, content = effectiveSystemPrompt))
            addAll(messages)
        }
        val tools = if (tokenSaveMode) emptyList() else toolRouter.specs()
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
            var streamError: String? = null

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
                            if (delta.id != null && acc.id == null) {
                                acc.id = delta.id
                            }
                            if (delta.name != null && acc.name == null) {
                                val candidateName = delta.name.trim()
                                if (candidateName.isNotEmpty() && candidateName.matches(Regex("^[a-zA-Z_][a-zA-Z0-9_]*$"))) {
                                    acc.name = candidateName
                                }
                            }
                            if (delta.argumentsChunk.isNotEmpty()) {
                                acc.argumentsBuilder.append(delta.argumentsChunk)
                            }
                        }
                        is StreamDelta.Finish -> {
                            finishReason = delta.reason
                        }
                        is StreamDelta.Usage -> {}
                        is StreamDelta.Error -> {
                            streamError = delta.message
                        }
                    }
                }
            } catch (e: CancellationException) {
                emit(Event(Event.Type.FINISHED))
                return@flow
            } catch (e: Exception) {
                emit(Event(Event.Type.ERROR, error = "Stream error: ${e.message ?: e::class.simpleName}"))
                return@flow
            }

            if (streamError != null) {
                // Extract to non-null local val
                val err: String = streamError!!
                // Provide helpful messages for common errors
                val helpfulError = when {
                    err.contains("400", ignoreCase = true) && err.contains("image", ignoreCase = true) ->
                        "This model doesn't support images. Please select a vision model (look for 'vision' badge in Settings). Error: $err"
                    err.contains("400", ignoreCase = true) && err.contains("tool", ignoreCase = true) ->
                        "This model may not support tool/function calling. Try a different model. Error: $err"
                    err.contains("429") ->
                        "Rate limit exceeded. Please wait a moment and try again. Error: $err"
                    err.contains("401") || err.contains("403") ->
                        "Authentication failed. Check your API key in Settings. Error: $err"
                    err.contains("variants failed", ignoreCase = true) ->
                        "The gateway couldn't process the request. This may be a temporary issue or the model may not support the request format. Error: $err"
                    else -> err
                }
                emit(Event(Event.Type.ERROR, error = helpfulError))
                return@flow
            }

            // Build tool calls from accumulators
            val toolCalls = toolCallAccumulators.toSortedMap().values.map { acc ->
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

            // CRITICAL: Emit TOOL_CALLS_READY so caller can persist the assistant
            // message with tool_calls BEFORE the tool result messages.
            // Without this, the next LLM call will fail with "tool call id not found".
            emit(Event(
                type = Event.Type.TOOL_CALLS_READY,
                toolCalls = toolCalls,
                content = contentBuilder.toString().ifEmpty { "" },
                reasoning = reasoningBuilder.toString().ifEmpty { "" }
            ))

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
                    val errorJson = buildJsonObject {
                        put("error", JsonPrimitive(errMsg))
                    }.toString()
                    emit(Event(
                        type = Event.Type.TOOL_RESULT,
                        toolCallId = tc.id,
                        toolName = "(empty)",
                        toolResult = errorJson,
                        toolDisplay = errMsg
                    ))
                    currentMessages.add(ChatMessage(
                        role = ChatMessage.Role.Tool,
                        content = errorJson,
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
                    val errorJson = buildJsonObject {
                        put("error", JsonPrimitive(errMsg))
                    }.toString()
                    emit(Event(
                        type = Event.Type.TOOL_RESULT,
                        toolCallId = tc.id,
                        toolName = tc.name,
                        toolResult = errorJson,
                        toolDisplay = "Error: $errMsg"
                    ))
                    currentMessages.add(ChatMessage(
                        role = ChatMessage.Role.Tool,
                        content = errorJson,
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
                    val errorJson = buildJsonObject {
                        put("error", JsonPrimitive(errMsg))
                    }.toString()
                    emit(Event(
                        type = Event.Type.TOOL_RESULT,
                        toolCallId = tc.id,
                        toolName = tc.name,
                        toolResult = errorJson,
                        toolDisplay = "Error: $errMsg"
                    ))
                    currentMessages.add(ChatMessage(
                        role = ChatMessage.Role.Tool,
                        content = errorJson,
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
            // Loop back to LLM with tool results
        }

        // Hit max iterations
        emit(Event(Event.Type.CONTENT_DELTA, content = "\n\n*[Reached maximum tool call iterations ($maxIterations). Stopping to prevent infinite loops.]*"))
        emit(Event(Event.Type.FINISHED))
    }

    private data class ToolCallAccumulator(
        var id: String? = null,
        var name: String? = null,
        val argumentsBuilder: StringBuilder = StringBuilder()
    )

    companion object {
        const val DEFAULT_SYSTEM_PROMPT = """You are PocketAgent, an AI agent on the user's Android phone with a full Linux environment.

## Your Environment
Private Linux workspace at ~/ with subdirectories: projects/, tmp/, downloads/
If Linux is installed (Termux bootstrap), you have: bash, python3, node, git, curl, wget, apt/pkg, gcc, clang, and more.
Install packages with: pkg install <name> or pip install <name>

## Your Tools (9 total)
- bash: Run shell commands (600s timeout). Use for everything shell-related.
- file_read: Read files (use start_line/end_line for large files)
- file_write: Create new files or append
- str_replace: **PREFERRED for editing** — surgical find-and-replace in existing files
- file_list: List directory contents
- grep: Search file contents with regex (structured results)
- glob: Find files by pattern
- web_fetch: Fetch URLs via HTTP
- web_search: Search the web via DuckDuckGo

## Efficiency Rules (CRITICAL)
1. PREFER str_replace over file_write for editing existing files
2. Use grep/glob to FIND things, not bash grep/find
3. Make ONE tool call at a time, wait for result, then proceed
4. Be CONCISE — don't explain what you're about to do, just do it
5. Don't repeat failing commands — try a different approach immediately
6. If a tool fails, read the error and fix it — don't retry blindly
7. For long outputs, use `head -50` or `tail -50` to limit output
8. Summarize results briefly after completing a task

## Tool Selection Guide
- Editing code? → str_replace (NOT file_write)
- Finding code? → grep (NOT bash grep)
- Finding files? → glob (NOT bash find)
- Reading files? → file_read (NOT bash cat)
- Running commands? → bash
- Downloading? → web_fetch or bash curl
- Searching web? → web_search

## Troubleshooting
- "Permission denied" → chmod +x <file>
- "command not found" → pkg install <package>
- "library not found" → check LD_LIBRARY_PATH, create symlinks
- /tmp issues → use ${'$'}TMPDIR or ~/tmp instead

You have TOTAL FREEDOM. Create, delete, install, build anything.
The user sees every tool call. Be transparent but concise."""
    }
}
