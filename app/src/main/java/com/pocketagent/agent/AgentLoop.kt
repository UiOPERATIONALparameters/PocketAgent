package com.pocketagent.agent

import com.pocketagent.agent.tools.ToolResult
import com.pocketagent.agent.tools.ToolRouter
import com.pocketagent.llm.ChatMessage
import com.pocketagent.llm.LlmProvider
import com.pocketagent.llm.LlmRequest
import com.pocketagent.llm.StreamDelta
import com.pocketagent.llm.ToolSpec
import com.pocketagent.sandbox.Workspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The agent loop:
 * 1. Send messages + tool specs + workspace state to the LLM
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
 *
 * v2.0 changes:
 *  - Dynamic system prompt with tool list (H1)
 *  - maxIterations default 30 → 50 (H2)
 *  - Token save mode: don't disable tools, just truncate results (H3)
 *  - Preserve assistant content before tool calls (H4)
 *  - Workspace state injection: pwd, ls, git status (z.ai ZCode pattern)
 */
@Singleton
class AgentLoop @Inject constructor(
    private val toolRouter: ToolRouter,
    private val workspace: Workspace,
    private val nativeEnv: com.pocketagent.sandbox.NativeEnvironmentManager
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
        val toolCalls: List<ChatMessage.ToolCall>? = null,
        val error: String? = null
    ) {
        enum class Type {
            CONTENT_DELTA,
            REASONING_DELTA,
            TOOL_CALLS_READY,
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
        maxIterations: Int = 50,  // H2: was 30, too low for build tasks
        tokenSaveMode: Boolean = false,
        temperature: Float = 0.7f  // H5: configurable
    ): Flow<Event> = flow {
        // H1: build dynamic system prompt with tool list + workspace state
        val effectiveSystemPrompt = buildSystemPrompt(systemPrompt, tokenSaveMode)

        var currentMessages = mutableListOf<ChatMessage>().apply {
            add(ChatMessage(role = ChatMessage.Role.System, content = effectiveSystemPrompt))
            addAll(messages)
        }
        // H3: token save mode no longer disables tools — it just truncates results
        val tools = toolRouter.specs()
        val knownToolNames = tools.map { it.name }.toSet()

        for (iteration in 0 until maxIterations) {
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
                            // H3: skip reasoning in token save mode
                            if (!tokenSaveMode) {
                                reasoningBuilder.append(delta.text)
                                emit(Event(Event.Type.REASONING_DELTA, reasoning = delta.text))
                            }
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
                val err: String = streamError!!
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

            val assistantContent = contentBuilder.toString().ifEmpty { null }
            val assistantReasoning = reasoningBuilder.toString().ifEmpty { null }
            val assistantMsg = ChatMessage(
                role = ChatMessage.Role.Assistant,
                content = assistantContent,
                reasoning = assistantReasoning,
                toolCalls = toolCalls
            )
            currentMessages.add(assistantMsg)

            // If no tool calls, we're done
            if (toolCalls.isEmpty()) {
                emit(Event(Event.Type.FINISHED))
                return@flow
            }

            // H4: If there was content before tool calls, emit it as a final delta
            // (already done via CONTENT_DELTA above, but make sure UI sees it before tool cards)
            // The TOOL_CALLS_READY event signals the UI to flush any pending content.

            // CRITICAL: Emit TOOL_CALLS_READY so caller can persist the assistant
            // message with tool_calls BEFORE the tool result messages.
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
                    val errorJson = buildJsonObject { put("error", JsonPrimitive(errMsg)) }.toString()
                    emit(Event(Event.Type.TOOL_RESULT, toolCallId = tc.id, toolName = "(empty)", toolResult = errorJson, toolDisplay = errMsg))
                    currentMessages.add(ChatMessage(role = ChatMessage.Role.Tool, content = errorJson, toolCallId = tc.id, name = tc.name.ifBlank { "unknown" }))
                    continue
                }

                if (tc.name !in knownToolNames) {
                    val errMsg = "Unknown tool: '${tc.name}'. Available tools: ${knownToolNames.joinToString(", ")}"
                    emit(Event(Event.Type.TOOL_CALL_START, toolCallId = tc.id, toolName = tc.name, toolArguments = tc.arguments))
                    val errorJson = buildJsonObject { put("error", JsonPrimitive(errMsg)) }.toString()
                    emit(Event(Event.Type.TOOL_RESULT, toolCallId = tc.id, toolName = tc.name, toolResult = errorJson, toolDisplay = "Error: $errMsg"))
                    currentMessages.add(ChatMessage(role = ChatMessage.Role.Tool, content = errorJson, toolCallId = tc.id, name = tc.name))
                    continue
                }

                val args = try {
                    json.parseToJsonElement(tc.arguments.ifEmpty { "{}" })
                } catch (e: Exception) {
                    emit(Event(Event.Type.TOOL_CALL_START, toolCallId = tc.id, toolName = tc.name, toolArguments = tc.arguments))
                    val errMsg = "Invalid JSON arguments: ${e.message}. Raw: ${tc.arguments.take(200)}"
                    val errorJson = buildJsonObject { put("error", JsonPrimitive(errMsg)) }.toString()
                    emit(Event(Event.Type.TOOL_RESULT, toolCallId = tc.id, toolName = tc.name, toolResult = errorJson, toolDisplay = "Error: $errMsg"))
                    currentMessages.add(ChatMessage(role = ChatMessage.Role.Tool, content = errorJson, toolCallId = tc.id, name = tc.name))
                    continue
                }

                emit(Event(Event.Type.TOOL_CALL_START, toolCallId = tc.id, toolName = tc.name, toolArguments = tc.arguments))

                val result = try {
                    toolRouter.execute(tc.name, args)
                } catch (e: Exception) {
                    ToolResult.Error("Execution failed: ${e.message ?: e::class.simpleName}")
                }

                // H3: in token save mode, truncate tool results more aggressively
                val maxResultBytes = if (tokenSaveMode) 5_000 else 30_000
                val (output, display) = when (result) {
                    is ToolResult.Success -> truncateResult(result.output.toString(), maxResultBytes) to (result.display ?: "")
                    is ToolResult.Error -> truncateResult(buildJsonObject {
                        put("error", JsonPrimitive(result.message))
                    }.toString(), maxResultBytes) to (result.display ?: "Error: ${result.message}")
                }

                emit(Event(Event.Type.TOOL_RESULT, toolCallId = tc.id, toolName = tc.name, toolResult = output, toolDisplay = display))

                currentMessages.add(ChatMessage(role = ChatMessage.Role.Tool, content = output, toolCallId = tc.id, name = tc.name))
            }
            // Loop back to LLM with tool results
        }

        // Hit max iterations
        emit(Event(Event.Type.CONTENT_DELTA, content = "\n\n*[Reached maximum tool call iterations ($maxIterations). Stopping to prevent infinite loops. Type 'continue' to resume.]*"))
        emit(Event(Event.Type.FINISHED))
    }

    /**
     * H1 + workspace state injection: build a dynamic system prompt that includes:
     *  - The user's custom prompt (if set) or the default
     *  - A list of available tools with one-line descriptions (no more hardcoded "9 tools")
     *  - Current workspace state: pwd, top-level files, git status (ZCode pattern)
     *  - HONEST capability description: what's actually available (Tier 1 vs Tier 2)
     */
    private suspend fun buildSystemPrompt(userPrompt: String, tokenSaveMode: Boolean): String {
        val sb = StringBuilder()

        // Base prompt — HONEST about what's available
        val linuxInstalled = nativeEnv.isInstalled()
        val effectiveDefault = if (linuxInstalled) DEFAULT_SYSTEM_PROMPT_LINUX else DEFAULT_SYSTEM_PROMPT_LITE
        sb.append(if (userPrompt.isNotBlank() && userPrompt != DEFAULT_SYSTEM_PROMPT) {
            userPrompt
        } else {
            effectiveDefault
        })
        sb.append("\n\n")

        // H1: dynamic tool list
        val tools = toolRouter.specs()
        sb.append("## Available Tools (${tools.size} total)\n")
        for (tool in tools) {
            val oneLineDesc = tool.description.lines().firstOrNull()?.trim() ?: ""
            sb.append("- ${tool.name}: $oneLineDesc\n")
        }
        sb.append("\n")

        // Workspace state injection (skip in token save mode to save tokens)
        if (!tokenSaveMode) {
            sb.append("## Current Workspace State\n")
            sb.append(generateWorkspaceState())
            sb.append("\n")
        }

        return sb.toString()
    }

    /**
     * Generate workspace state info: pwd, top-level files, git status.
     * This is what makes the agent feel "contextually aware" like z.ai ZCode.
     */
    private fun generateWorkspaceState(): String {
        return try {
            val sb = StringBuilder()
            val home = workspace.homeDir

            // pwd
            sb.append("Working directory: ~ (workspace root)\n")

            // Top-level files (max 20)
            val entries = home.listFiles()
                ?.filter { it.name != "." && it.name != ".." && !it.name.startsWith(".state") }
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                ?.take(20)
                ?: emptyList()

            if (entries.isNotEmpty()) {
                sb.append("Top-level entries:\n")
                for (e in entries) {
                    val type = if (e.isDirectory) "[dir] " else "[file]"
                    val size = if (e.isFile) " ${e.length() / 1024}KB" else ""
                    sb.append("  $type ${e.name}$size\n")
                }
            } else {
                sb.append("(workspace is empty)\n")
            }

            // git status if there's a .git directory
            val gitDir = File(home, ".git")
            if (gitDir.exists()) {
                sb.append("Git: repository present\n")
            }

            // Bootstrap status
            val linuxInstalled = nativeEnv.isInstalled()
            if (linuxInstalled) {
                sb.append("Linux environment: INSTALLED (Termux native — bash, apt, pkg)\n")
                sb.append("Available: bash, coreutils, apt, pkg, curl, wget. Install more: 'pkg install python nodejs git gcc ffmpeg'\n")
            } else {
                sb.append("Linux environment: NOT installed (system shell only — basic coreutils)\n")
                sb.append("To get python3/node/git/gcc, the user must install Linux from Settings → Linux Environment.\n")
            }

            sb.toString()
        } catch (_: Exception) {
            "(workspace state unavailable)"
        }
    }

    /** Truncate tool result to fit within maxBytes. */
    private fun truncateResult(result: String, maxBytes: Int): String {
        if (result.length <= maxBytes) return result
        return result.substring(0, maxBytes) + "\n...[truncated, ${result.length - maxBytes} more chars]"
    }

    private data class ToolCallAccumulator(
        var id: String? = null,
        var name: String? = null,
        val argumentsBuilder: StringBuilder = StringBuilder()
    )

    companion object {
        /** System prompt when Linux is NOT installed — limited to system shell only. (Defined first so DEFAULT_SYSTEM_PROMPT can reference it) */
        val DEFAULT_SYSTEM_PROMPT_LITE = """You are PocketAgent, an AI agent on the user's Android phone.

## Your Environment
- Private workspace at ~/ with subdirectories: projects/, tmp/, downloads/
- LIMITED shell: Android's /system/bin/sh (mksh) — basic coreutils only
- Available: ls, cat, echo, grep, sed, awk, head, tail, wc, sort, uniq, tr, cut, mkdir, rm, cp, mv
- NOT available (until Linux is installed): python3, node, git, gcc, pip, npm, apt
- /tmp is NOT writable — use ~/tmp instead

## How to Get Full Linux
Tell the user: "Go to Settings → Linux Environment → Install Linux" to enable full Ubuntu access (python3, node, git, gcc, apt, anything).

## Your Current Capabilities (Limited)
You can still:
- Write and edit files (file_write, str_replace)
- Read files (file_read, file_list, grep, glob)
- Fetch URLs (web_fetch, web_search)
- Run basic shell commands (bash — but only coreutils)
- Install APKs you build (install_apk)

You CANNOT build APKs (no gradle/java), run Python, or compile C until Linux is installed.
Be honest with the user about this limitation.

## Efficiency Rules
1. PREFER str_replace over file_write for editing existing files
2. Use grep/glob to FIND things, not bash grep/find
3. Make ONE tool call at a time, wait for result, then proceed
4. Be CONCISE — don't explain what you're about to do, just do it
5. If a tool fails, read the error and fix it

## Tool Selection Guide
- Editing code? → str_replace (NOT file_write)
- Finding code? → grep (NOT bash grep)
- Finding files? → glob (NOT bash find)
- Reading files? → file_read (NOT bash cat)
- Running commands? → bash (limited — coreutils only)
- Downloading? → web_fetch
- Searching web? → web_search

Be transparent about limitations. Suggest the user install Linux for full capabilities."""

        /** System prompt when Linux (Termux native) IS installed — full capabilities. */
        val DEFAULT_SYSTEM_PROMPT_LINUX = """You are PocketAgent, an AI agent on the user's Android phone with a FULL Linux environment (Termux native).

## Your Environment
- Private workspace at ~/ with subdirectories: projects/, tmp/, downloads/
- Full bash shell with coreutils, curl, wget, git, apt, pkg
- Install ANYTHING with pkg: python, nodejs, git, gcc, ffmpeg, ImageMagick, etc.
  Example: pkg install -y python nodejs git gcc ffmpeg
- pip install for Python packages, npm install for Node packages
- /tmp is NOT available — use ~/tmp or ${'$'}TMPDIR instead

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

## Efficiency Rules (CRITICAL)
1. PREFER str_replace over file_write for editing existing files
2. Use grep/glob to FIND things, not bash grep/find
3. Make ONE tool call at a time, wait for result, then proceed
4. Be CONCISE — don't explain what you're about to do, just do it
5. Don't repeat failing commands — try a different approach immediately
6. If a tool fails, read the error and fix it — don't retry blindly
7. For long outputs, use `head -50` or `tail -50` to limit output
8. When installing packages, use 'pkg install -y <name>' (non-interactive)
9. Summarize results briefly after completing a task

## Tool Selection Guide
- Editing code? → str_replace (NOT file_write)
- Finding code? → grep (NOT bash grep)
- Finding files? → glob (NOT bash find)
- Reading files? → file_read (NOT bash cat)
- Running commands? → bash (runs in native Termux environment)
- Downloading? → web_fetch (text) or bash curl (binary)
- Searching web? → web_search
- Reading a webpage cleanly? → web_reader (extracts article text)
- Loading a skill for a specific task? → load_skill
- Built an APK? → install_apk to let the user install it

## Available Skills
Use load_skill(name) to load detailed instructions for a specific task:
- build-website, build-apk, research-topic, write-script, make-chart, debug-code

## Troubleshooting
- "command not found" → pkg install <package>
- "permission denied" → chmod +x <file>
- /tmp issues → use ~/tmp or ${'$'}TMPDIR instead
- SSL errors → check ca-certificates: pkg install ca-certificates

You have TOTAL FREEDOM. Create, delete, install, build anything.
The user sees every tool call. Be transparent but concise."""

        /** Alias for the default prompt — uses LITE since it's safe for both states. */
        val DEFAULT_SYSTEM_PROMPT = DEFAULT_SYSTEM_PROMPT_LITE
    }
}
