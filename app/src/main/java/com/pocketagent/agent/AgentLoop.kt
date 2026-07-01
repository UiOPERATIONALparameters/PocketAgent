package com.pocketagent.agent

import com.pocketagent.agent.state.ContextManager
import com.pocketagent.agent.state.StateStore
import com.pocketagent.agent.tools.AgentTool
import com.pocketagent.agent.tools.ToolResult
import com.pocketagent.agent.tools.ToolRouter
import com.pocketagent.bridge.TermuxBridge
import com.pocketagent.llm.ChatMessage
import com.pocketagent.llm.LlmProvider
import com.pocketagent.llm.LlmRequest
import com.pocketagent.llm.ModelInfo
import com.pocketagent.llm.StreamDelta
import com.pocketagent.llm.ToolSpec
import com.pocketagent.storage.ActiveProviderHolder
import com.pocketagent.storage.prefs.SettingsRepository
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
 * v6 AgentLoop — ReAct loop with:
 *   - ContextManager (auto-compact at 70% of context window)
 *   - StateStore (worklog.md + scratchpad.md injected into system prompt)
 *   - Structured tool errors (suggestion field)
 *   - Workspace state from the real Termux (via TermuxBridge)
 *
 * Loop:
 *   1. Build system prompt (default + tool list + workspace state + persistent state)
 *   2. Auto-compact if needed
 *   3. Stream LLM response
 *   4. If tool calls, execute them, feed results back
 *   5. Repeat until no tool calls or max iterations
 *
 * Cancellable via coroutine cancellation.
 * On max iterations: emit a friendly message and stop (user can type "continue").
 */
@Singleton
class AgentLoop @Inject constructor(
    private val toolRouter: ToolRouter,
    private val bridge: TermuxBridge,
    private val contextManager: ContextManager,
    private val stateStore: StateStore,
    private val activeProviderHolder: ActiveProviderHolder,
    private val settings: SettingsRepository
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
        val toolSuggestion: String? = null,
        val toolCalls: List<ChatMessage.ToolCall>? = null,
        val error: String? = null,
        val compacted: Boolean = false
    ) {
        enum class Type {
            CONTENT_DELTA,
            REASONING_DELTA,
            TOOL_CALLS_READY,
            TOOL_CALL_START,
            TOOL_RESULT,
            COMPACTED,
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
        maxIterations: Int = 50,
        tokenSaveMode: Boolean = false,
        temperature: Float = 0.7f
    ): Flow<Event> = flow {
        // Initialize state store (creates ~/.pocketagent/ if missing)
        stateStore.initialize()

        // Build dynamic system prompt with tool list, workspace state, persistent state
        val effectiveSystemPrompt = buildSystemPrompt(systemPrompt, tokenSaveMode)

        var currentMessages = mutableListOf<ChatMessage>().apply {
            add(ChatMessage(role = ChatMessage.Role.System, content = effectiveSystemPrompt))
            addAll(messages)
        }

        val tools = toolRouter.specs()
        val knownToolNames = tools.map { it.name }.toSet()

        // Try to get model's max input tokens for auto-compact
        val maxInputTokens = getModelMaxInputTokens(provider, modelId)

        for (iteration in 0 until maxIterations) {
            // Auto-compact check
            if (maxInputTokens != null && contextManager.needsCompaction(
                    currentMessages,
                    maxInputTokens,
                    autoCompactThreshold()
                )) {
                val compactResult = contextManager.compact(
                    provider = provider,
                    modelId = modelId,
                    messages = currentMessages,
                    maxInputTokens = maxInputTokens
                )
                if (compactResult.isSuccess) {
                    currentMessages = compactResult.getOrNull()!!.newMessages.toMutableList()
                    emit(Event(
                        type = Event.Type.COMPACTED,
                        content = "Context auto-compacted: ${compactResult.getOrNull()!!.messagesCompacted} messages summarized (tokens ${compactResult.getOrNull()!!.tokensBefore} → ${compactResult.getOrNull()!!.tokensAfter})."
                    ))
                }
            }

            val request = LlmRequest(
                model = modelId,
                messages = currentMessages.toList(),
                tools = tools,
                temperature = temperature
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
                            if (!tokenSaveMode) {
                                reasoningBuilder.append(delta.text)
                                emit(Event(Event.Type.REASONING_DELTA, reasoning = delta.text))
                            }
                        }
                        is StreamDelta.ToolCall -> {
                            val acc = toolCallAccumulators.getOrPut(delta.index) { ToolCallAccumulator() }
                            if (delta.id != null && acc.id == null) acc.id = delta.id
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
                        is StreamDelta.Finish -> finishReason = delta.reason
                        is StreamDelta.Usage -> {}
                        is StreamDelta.Error -> streamError = delta.message
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
                val err = streamError!!
                val helpfulError = when {
                    err.contains("400", ignoreCase = true) && err.contains("image", ignoreCase = true) ->
                        "This model doesn't support images. Pick a vision model in Settings. Error: $err"
                    err.contains("400", ignoreCase = true) && err.contains("tool", ignoreCase = true) ->
                        "This model may not support tool/function calling. Try a different model. Error: $err"
                    err.contains("400", ignoreCase = true) && err.contains("context", ignoreCase = true) ->
                        "Context length exceeded. Call the `compact` tool to summarize, then continue. Error: $err"
                    err.contains("429") ->
                        "Rate limit exceeded. Wait a moment and try again. Error: $err"
                    err.contains("401") || err.contains("403") ->
                        "Authentication failed. Check your API key in Settings. Error: $err"
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
                ChatMessage.ToolCall(id, name, arguments)
            }

            val assistantContent = contentBuilder.toString().ifEmpty { null }
            val assistantReasoning = reasoningBuilder.toString().ifEmpty { null }
            val assistantMsg = ChatMessage(
                role = ChatMessage.Role.Assistant,
                content = assistantContent,
                reasoning = assistantReasoning,
                toolCalls = toolCalls
            )
            currentMessages.add(assistantMsg)

            if (toolCalls.isEmpty()) {
                emit(Event(Event.Type.FINISHED))
                return@flow
            }

            emit(Event(
                type = Event.Type.TOOL_CALLS_READY,
                toolCalls = toolCalls,
                content = assistantContent ?: "",
                reasoning = assistantReasoning ?: ""
            ))

            // Execute each tool call
            for (tc in toolCalls) {
                if (tc.name.isBlank()) {
                    val errMsg = "Tool call received with empty name. Arguments: ${tc.arguments.take(200)}"
                    emit(Event(Event.Type.TOOL_CALL_START, toolCallId = tc.id, toolName = "(empty)", toolArguments = tc.arguments))
                    val errorJson = buildJsonObject {
                        put("error", JsonPrimitive(errMsg))
                        put("suggestion", JsonPrimitive("Check the tool name spelling. Available tools: ${knownToolNames.joinToString(", ")}"))
                    }.toString()
                    emit(Event(Event.Type.TOOL_RESULT, toolCallId = tc.id, toolName = "(empty)", toolResult = errorJson, toolDisplay = errMsg))
                    currentMessages.add(ChatMessage(role = ChatMessage.Role.Tool, content = errorJson, toolCallId = tc.id, name = "unknown"))
                    continue
                }

                if (tc.name !in knownToolNames) {
                    val errMsg = "Unknown tool: '${tc.name}'"
                    emit(Event(Event.Type.TOOL_CALL_START, toolCallId = tc.id, toolName = tc.name, toolArguments = tc.arguments))
                    val errorJson = buildJsonObject {
                        put("error", JsonPrimitive(errMsg))
                        put("available_tools", JsonPrimitive(knownToolNames.joinToString(", ")))
                        put("suggestion", JsonPrimitive("Use one of the available tools listed above."))
                    }.toString()
                    emit(Event(Event.Type.TOOL_RESULT, toolCallId = tc.id, toolName = tc.name, toolResult = errorJson, toolDisplay = "Error: $errMsg", toolSuggestion = "Use one of: ${knownToolNames.joinToString(", ")}"))
                    currentMessages.add(ChatMessage(role = ChatMessage.Role.Tool, content = errorJson, toolCallId = tc.id, name = tc.name))
                    continue
                }

                val args = try {
                    json.parseToJsonElement(tc.arguments.ifEmpty { "{}" })
                } catch (e: Exception) {
                    emit(Event(Event.Type.TOOL_CALL_START, toolCallId = tc.id, toolName = tc.name, toolArguments = tc.arguments))
                    val errMsg = "Invalid JSON arguments: ${e.message}. Raw: ${tc.arguments.take(200)}"
                    val errorJson = buildJsonObject {
                        put("error", JsonPrimitive(errMsg))
                        put("suggestion", JsonPrimitive("Fix the JSON syntax and retry."))
                    }.toString()
                    emit(Event(Event.Type.TOOL_RESULT, toolCallId = tc.id, toolName = tc.name, toolResult = errorJson, toolDisplay = "Error: $errMsg"))
                    currentMessages.add(ChatMessage(role = ChatMessage.Role.Tool, content = errorJson, toolCallId = tc.id, name = tc.name))
                    continue
                }

                emit(Event(Event.Type.TOOL_CALL_START, toolCallId = tc.id, toolName = tc.name, toolArguments = tc.arguments))

                val result = try {
                    toolRouter.execute(tc.name, args)
                } catch (e: Exception) {
                    ToolResult.Error(
                        "Execution failed: ${e.message ?: e::class.simpleName}",
                        "Try a different approach or check the error."
                    )
                }

                val maxResultBytes = if (tokenSaveMode) 5_000 else 30_000
                val (output, display, suggestion) = when (result) {
                    is ToolResult.Success -> triple(
                        truncateResult(result.output.toString(), maxResultBytes),
                        result.display ?: "",
                        null as String?
                    )
                    is ToolResult.Error -> {
                        val errorJson = buildJsonObject {
                            put("error", JsonPrimitive(result.message))
                            result.suggestion?.let { put("suggestion", JsonPrimitive(it)) }
                        }.toString()
                        triple(
                            truncateResult(errorJson, maxResultBytes),
                            result.display ?: "Error: ${result.message}",
                            result.suggestion
                        )
                    }
                }

                emit(Event(Event.Type.TOOL_RESULT, toolCallId = tc.id, toolName = tc.name, toolResult = output, toolDisplay = display, toolSuggestion = suggestion))
                currentMessages.add(ChatMessage(role = ChatMessage.Role.Tool, content = output, toolCallId = tc.id, name = tc.name))
            }
            // Loop back to LLM with tool results
        }

        // Hit max iterations
        emit(Event(Event.Type.CONTENT_DELTA, content = "\n\n*[Reached maximum tool call iterations ($maxIterations). Stopping to prevent infinite loops. Type 'continue' to resume.]*"))
        emit(Event(Event.Type.FINISHED))
    }

    /**
     * Build the dynamic system prompt:
     *   - User's custom prompt (or default)
     *   - Tool list (with one-line descriptions)
     *   - Workspace state (real Termux info via bridge)
     *   - Persistent state (scratchpad + worklog tail from StateStore)
     */
    private suspend fun buildSystemPrompt(userPrompt: String, tokenSaveMode: Boolean): String {
        val sb = StringBuilder()

        val effectiveDefault = DEFAULT_SYSTEM_PROMPT
        sb.append(if (userPrompt.isNotBlank() && userPrompt != DEFAULT_SYSTEM_PROMPT) userPrompt else effectiveDefault)
        sb.append("\n\n")

        // Tool list
        val tools = toolRouter.specs()
        sb.append("## Available Tools (${tools.size} total)\n")
        for (tool in tools) {
            val oneLineDesc = tool.description.lines().firstOrNull()?.trim() ?: ""
            sb.append("- ${tool.name}: $oneLineDesc\n")
        }
        sb.append("\n")

        // Workspace state from real Termux
        if (!tokenSaveMode) {
            sb.append("## Current Workspace State\n")
            sb.append(generateWorkspaceState())
            sb.append("\n")
        }

        // Persistent state (scratchpad + worklog) — the z.ai pattern
        if (!tokenSaveMode) {
            val persistentState = stateStore.getStateSnapshot()
            if (persistentState.isNotBlank()) {
                sb.append("## Persistent State (from previous sessions)\n")
                sb.append(persistentState)
                sb.append("\n")
            }
        }

        return sb.toString()
    }

    /**
     * Generate workspace state info from the real Termux via the bridge.
     */
    private suspend fun generateWorkspaceState(): String {
        return try {
            if (!bridge.state.isConnected) {
                return "Termux: NOT CONNECTED. Tell the user to start the daemon (`pocketagent-daemon` in Termux).\n"
            }
            val sb = StringBuilder()
            // Health gives us user + home
            sb.append("Environment: Termux (real Linux on Android)\n")
            bridge.state.current.let { s ->
                sb.append("User: ${s.termuxUser ?: "unknown"}\n")
                sb.append("Home: ${s.termuxHome ?: "~"}\n")
            }
            // Top-level files in home (max 20)
            val listResult = bridge.listFiles("~")
            listResult.getOrNull()?.let { resp ->
                val entries = resp.entries.take(20)
                if (entries.isNotEmpty()) {
                    sb.append("Top-level entries in ~:\n")
                    for (e in entries) {
                        val type = if (e.type == "dir") "[dir] " else "[file]"
                        val size = if (e.type == "file") " ${e.size / 1024}KB" else ""
                        sb.append("  $type ${e.name}$size\n")
                    }
                }
            }
            sb.toString()
        } catch (_: Exception) {
            "(workspace state unavailable)"
        }
    }

    private fun truncateResult(result: String, maxBytes: Int): String {
        if (result.length <= maxBytes) return result
        return result.substring(0, maxBytes) + "\n...[truncated, ${result.length - maxBytes} more chars]"
    }

    private fun autoCompactThreshold(): Float = settings.settings.value.autoCompactThreshold

    private suspend fun getModelMaxInputTokens(provider: LlmProvider, modelId: String): Int? {
        return try {
            val models = provider.listModels()
            models.find { it.id == modelId }?.maxInputTokens
        } catch (_: Exception) {
            null
        }
    }

    private data class ToolCallAccumulator(
        var id: String? = null,
        var name: String? = null,
        val argumentsBuilder: StringBuilder = StringBuilder()
    )

    companion object {
        val DEFAULT_SYSTEM_PROMPT = """You are PocketAgent, an AI agent on the user's Android phone with FULL access to their real Termux Linux environment.

## Your Environment
- Real Termux Linux on Android — same packages, same ${'$'}PATH, same git config as the user
- Private workspace at ~ (the user's Termux home)
- Full bash with all installed tools (python, node, gcc, git, etc.)
- Install ANYTHING with pkg: `pkg install <name>` (e.g., `pkg install python nodejs git gcc ffmpeg`)
- /tmp IS available (real Linux, not the old sandboxed environment)
- The user sees every tool call. Be transparent but concise.

## Your Capabilities
You have TOTAL FREEDOM. You can:
- Build websites (pkg install nodejs; any JS framework)
- Run Python scripts (pkg install python; pip install any package)
- Compile C/C++ (pkg install clang)
- Process media (pkg install ffmpeg imagemagick)
- Build Android APKs (pkg install openjdk-17 gradle)
- Install the APKs you build (install_apk tool)
- Run any shell command, write any file, fetch any URL
- Search the web for current information
- Spawn subagents for parallel/delegated work (task tool)
- Read/write persistent scratchpad (state tool) — survives across conversations

## Efficiency Rules (CRITICAL)
1. PREFER str_replace over file_write for editing existing files
2. Use grep/glob to FIND things, not bash grep/find
3. Make ONE tool call at a time, wait for result, then proceed
4. Be CONCISE — don't explain what you're about to do, just do it
5. Don't repeat failing commands — try a different approach immediately
6. If a tool fails, READ the suggestion field — it tells you what to do next
7. For long outputs, use `head -50` or `tail -50` to limit output
8. When installing packages, use 'pkg install -y <name>' (non-interactive)
9. Use the `task` tool for any subtask that would consume many tool calls (research, refactoring)
10. Update the `state` scratchpad when you learn something important

## Tool Selection Guide
- Editing code? → str_replace (NOT file_write)
- Finding code? → grep (uses ripgrep if installed)
- Finding files? → glob
- Reading files? → file_read
- Listing a directory? → file_list
- Running commands? → bash (real Termux environment)
- Downloading? → web_fetch (text) or bash curl (binary)
- Searching web? → web_search
- Reading a webpage cleanly? → web_reader
- Loading a skill for a specific task? → load_skill
- Built an APK? → install_apk
- Need to serve a website? → serve_http
- Tracking multi-step tasks? → todo
- Need parallel/delegated work? → task (spawns a subagent)
- Want to remember something for next session? → state
- Conversation getting long? → compact

## Recovery
- Tool errors return a `suggestion` field — follow it
- If bash fails with "command not found", install it: `pkg install <name>`
- If you hit context limits, call `compact` to summarize

You have TOTAL FREEDOM. Create, delete, install, build anything."""
    }
}

/** Helper for triple destructuring. */
private fun <A, B, C> triple(a: A, b: B, c: C): Triple<A, B, C> = Triple(a, b, c)
