package com.pocketagent.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketagent.agent.AgentLoop
import com.pocketagent.agent.tools.ToolRouter
import com.pocketagent.llm.ChatMessage
import com.pocketagent.storage.ActiveProviderHolder
import com.pocketagent.storage.db.ConversationDao
import com.pocketagent.storage.db.ConversationEntity
import com.pocketagent.storage.db.MessageDao
import com.pocketagent.storage.db.MessageEntity
import com.pocketagent.storage.db.ToolRunDao
import com.pocketagent.storage.db.ToolRunEntity
import com.pocketagent.storage.prefs.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject

data class ChatMessageUi(
    val id: String,
    val role: String,            // user/assistant/tool
    val content: String,
    val reasoning: String? = null,
    val toolName: String? = null,
    val toolArguments: String? = null,
    val toolResult: String? = null,
    val toolDisplay: String? = null,
    val isStreaming: Boolean = false,
    val createdAt: Long
)

data class ChatUiState(
    val conversationId: String? = null,
    val title: String = "New Chat",
    val messages: List<ChatMessageUi> = emptyList(),
    val conversations: List<ConversationEntity> = emptyList(),
    val inputText: String = "",
    val isAgentRunning: Boolean = false,
    val streamingContent: String = "",
    val streamingReasoning: String = "",
    val streamingToolCalls: List<VisibleToolCall> = emptyList(),
    val activeToolName: String? = null,
    val error: String? = null,
    val sidebarOpen: Boolean = false,
    val pendingAttachments: List<Attachment> = emptyList()
) {
    data class VisibleToolCall(
        val toolCallId: String,
        val toolName: String,
        val arguments: String,
        val result: String? = null,
        val isRunning: Boolean = false
    )

    data class Attachment(
        val uri: String,
        val mimeType: String,
        val displayName: String,
        val base64: String? = null  // populated when sent
    )
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val toolRunDao: ToolRunDao,
    private val settings: SettingsRepository,
    private val activeProviderHolder: ActiveProviderHolder,
    private val agentLoop: AgentLoop,
    private val toolRouter: ToolRouter
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var agentJob: Job? = null
    private var messageObserverJob: Job? = null
    private val json = Json { ignoreUnknownKeys = true }

    init {
        viewModelScope.launch {
            // load() is idempotent — safe to call from any ViewModel
            settings.load()
            // Observe conversations list for sidebar
            conversationDao.observeAll().collect { convs ->
                _state.update { it.copy(conversations = convs) }
            }
        }
    }

    /**
     * Load a conversation by ID. Cancels any previous message observer.
     * If the conversation doesn't exist, treats it as a new chat (doesn't crash).
     */
    fun loadConversation(id: String) {
        messageObserverJob?.cancel()
        viewModelScope.launch {
            settings.load()
            val conv = conversationDao.getById(id)
            if (conv == null) {
                // Conversation doesn't exist yet — treat as new chat
                _state.update {
                    it.copy(
                        conversationId = null,
                        title = "New Chat",
                        messages = emptyList(),
                        streamingContent = "",
                        streamingReasoning = "",
                        streamingToolCalls = emptyList(),
                        isAgentRunning = false
                    )
                }
                return@launch
            }
            _state.update {
                it.copy(
                    conversationId = id,
                    title = conv.title,
                    messages = emptyList(),
                    streamingContent = "",
                    streamingReasoning = "",
                    streamingToolCalls = emptyList(),
                    isAgentRunning = false
                )
            }
            // Observe messages for this conversation
            messageObserverJob = viewModelScope.launch {
                messageDao.observeForConversation(id).collect { messages ->
                    val uiMessages = withContext(Dispatchers.IO) {
                        messages.map { msg -> msg.toUiWithToolRuns() }
                    }
                    _state.update { it.copy(messages = uiMessages) }
                }
            }
        }
    }

    fun onInputTextChange(text: String) {
        _state.update { it.copy(inputText = text) }
    }

    fun toggleSidebar() {
        _state.update { it.copy(sidebarOpen = !it.sidebarOpen) }
    }

    fun closeSidebar() {
        _state.update { it.copy(sidebarOpen = false) }
    }

    fun addAttachment(uri: String, mimeType: String, displayName: String) {
        _state.update {
            it.copy(pendingAttachments = it.pendingAttachments + ChatUiState.Attachment(uri, mimeType, displayName))
        }
    }

    fun removeAttachment(uri: String) {
        _state.update {
            it.copy(pendingAttachments = it.pendingAttachments.filterNot { att -> att.uri == uri })
        }
    }

    fun sendMessage() {
        val text = _state.value.inputText.trim()
        val attachments = _state.value.pendingAttachments
        if ((text.isEmpty() && attachments.isEmpty()) || _state.value.isAgentRunning) return

        val provider = activeProviderHolder.get()
        if (provider == null) {
            _state.update { it.copy(error = "No provider configured. Tap Settings to add your API key.") }
            return
        }
        val modelId = settings.settings.value.activeModelId
        if (modelId == null) {
            _state.update { it.copy(error = "No model selected. Tap Settings to pick a model.") }
            return
        }

        // Note: Image attachments to non-vision models will cause API errors.
        // The error is caught by the stream error handler and shown to the user.
        // We could pre-check model vision support, but the model list isn't always available.

        viewModelScope.launch {
            // Create conversation if needed
            var conversationId = _state.value.conversationId
            if (conversationId == null) {
                conversationId = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                val title = text.take(40).ifEmpty { if (attachments.isNotEmpty()) "Image chat" else "New Chat" }
                conversationDao.upsert(
                    ConversationEntity(
                        id = conversationId,
                        title = title,
                        createdAt = now,
                        updatedAt = now,
                        providerId = settings.getActiveProvider()?.id,
                        modelId = modelId
                    )
                )
                _state.update { it.copy(conversationId = conversationId, title = title) }
                // Start observing this new conversation's messages
                messageObserverJob?.cancel()
                messageObserverJob = viewModelScope.launch {
                    messageDao.observeForConversation(conversationId).collect { messages ->
                        val uiMessages = withContext(Dispatchers.IO) {
                            messages.map { msg -> msg.toUiWithToolRuns() }
                        }
                        _state.update { it.copy(messages = uiMessages) }
                    }
                }
            }

            // Read attachments to base64 (in IO dispatcher)
            val contentParts = withContext(Dispatchers.IO) {
                if (attachments.isEmpty()) null
                else {
                    val parts = mutableListOf<ChatMessage.ContentPart>()
                    if (text.isNotEmpty()) {
                        parts.add(ChatMessage.ContentPart.Text(text))
                    }
                    var attachErrors = mutableListOf<String>()
                    for (att in attachments) {
                        try {
                            val uri = android.net.Uri.parse(att.uri)
                            val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            if (bytes != null && bytes.isNotEmpty()) {
                                val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                                parts.add(ChatMessage.ContentPart.Image(
                                    base64 = base64,
                                    mimeType = att.mimeType,
                                    detail = "auto"
                                ))
                            } else {
                                attachErrors.add("Could not read image: ${att.displayName}")
                            }
                        } catch (e: Exception) {
                            attachErrors.add("Failed to process image ${att.displayName}: ${e.message}")
                        }
                    }
                    // If we couldn't load any images and no text, send an error message as text
                    if (parts.isEmpty() && attachErrors.isNotEmpty()) {
                        parts.add(ChatMessage.ContentPart.Text("[Image loading failed: ${attachErrors.joinToString("; ")}]"))
                    }
                    // Return null if no parts (shouldn't happen, but safety)
                    if (parts.isEmpty()) null else parts
                }
            }

            // Persist user message
            val userMsgId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val displayContent = if (contentParts != null) {
                // For DB storage, serialize the parts to JSON
                json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(ChatMessage.ContentPart.serializer()),
                    contentParts
                )
            } else text
            messageDao.upsert(
                MessageEntity(
                    id = userMsgId,
                    conversationId = conversationId,
                    role = "user",
                    content = if (contentParts != null) text.ifEmpty { null } else text,
                    reasoning = null,
                    toolCallsJson = if (contentParts != null) displayContent else null,
                    toolCallId = null,
                    toolName = null,
                    createdAt = now
                )
            )

            _state.update {
                it.copy(
                    inputText = "",
                    pendingAttachments = emptyList(),
                    isAgentRunning = true,
                    streamingContent = "",
                    streamingReasoning = "",
                    streamingToolCalls = emptyList(),
                    activeToolName = null,
                    error = null
                )
            }

            // Build message history from DB
            val history = withContext(Dispatchers.IO) {
                messageDao.getForConversation(conversationId).map { it.toLlm() }
            }

            // Track tool calls for persistence — maps toolCallId to assistant message ID
            val pendingToolCalls = mutableMapOf<String, PendingToolCall>()
            // Track the current iteration's assistant message ID (for tool_calls persistence)
            var currentAssistantMsgId: String? = null

            // Start foreground service to keep the app alive when backgrounded.
            // This prevents "stream was reset" errors when the user minimizes the app.
            startAgentService(text.ifEmpty { "Working…" })

            agentJob = viewModelScope.launch {
                try {
                    agentLoop.run(
                        provider = provider,
                        modelId = modelId,
                        messages = history,
                        systemPrompt = settings.settings.value.systemPrompt.ifBlank { AgentLoop.DEFAULT_SYSTEM_PROMPT },
                        maxIterations = settings.settings.value.maxToolIterations
                    ).collect { event ->
                        when (event.type) {
                            AgentLoop.Event.Type.CONTENT_DELTA -> {
                                _state.update {
                                    it.copy(streamingContent = it.streamingContent + event.content)
                                }
                            }
                            AgentLoop.Event.Type.REASONING_DELTA -> {
                                _state.update {
                                    it.copy(streamingReasoning = it.streamingReasoning + event.reasoning)
                                }
                            }
                            AgentLoop.Event.Type.TOOL_CALLS_READY -> {
                                // CRITICAL: Persist the assistant message WITH tool_calls array.
                                // This MUST happen before tool result messages are persisted,
                                // so the conversation history sent to the LLM includes:
                                //   assistant { tool_calls: [...] }
                                //   tool { tool_call_id: X }
                                // Without this, the API rejects with "tool call id not found".
                                val toolCalls = event.toolCalls ?: emptyList()
                                val assistantMsgId = UUID.randomUUID().toString()
                                currentAssistantMsgId = assistantMsgId
                                val toolCallsJson = json.encodeToString(
                                    kotlinx.serialization.builtins.ListSerializer(ChatMessage.ToolCall.serializer()),
                                    toolCalls
                                )
                                messageDao.upsert(
                                    MessageEntity(
                                        id = assistantMsgId,
                                        conversationId = conversationId,
                                        role = "assistant",
                                        content = event.content.ifEmpty { null },
                                        reasoning = event.reasoning.ifEmpty { null },
                                        toolCallsJson = toolCallsJson,
                                        toolCallId = null,
                                        toolName = null,
                                        createdAt = System.currentTimeMillis(),
                                        isStreaming = false
                                    )
                                )
                                // Clear streaming content since we've persisted it
                                _state.update {
                                    it.copy(
                                        streamingContent = "",
                                        streamingReasoning = ""
                                    )
                                }
                            }
                            AgentLoop.Event.Type.TOOL_CALL_START -> {
                                val tcId = event.toolCallId ?: return@collect
                                val msgId = UUID.randomUUID().toString()
                                pendingToolCalls[tcId] = PendingToolCall(
                                    messageId = msgId,
                                    toolName = event.toolName ?: "unknown",
                                    arguments = event.toolArguments ?: "{}",
                                    assistantMsgId = currentAssistantMsgId
                                )
                                // Add to visible streaming tool calls
                                _state.update {
                                    it.copy(
                                        streamingToolCalls = it.streamingToolCalls + ChatUiState.VisibleToolCall(
                                            toolCallId = tcId,
                                            toolName = event.toolName ?: "unknown",
                                            arguments = event.toolArguments ?: "{}",
                                            isRunning = true
                                        ),
                                        activeToolName = event.toolName
                                    )
                                }
                                // Persist a placeholder tool message (will be updated with result)
                                messageDao.upsert(
                                    MessageEntity(
                                        id = msgId,
                                        conversationId = conversationId,
                                        role = "tool",
                                        content = null,
                                        reasoning = null,
                                        toolCallsJson = null,
                                        toolCallId = tcId,
                                        toolName = event.toolName,
                                        createdAt = System.currentTimeMillis(),
                                        isStreaming = true
                                    )
                                )
                            }
                            AgentLoop.Event.Type.TOOL_RESULT -> {
                                val tcId = event.toolCallId ?: return@collect
                                val pending = pendingToolCalls[tcId] ?: return@collect
                                val toolRunId = UUID.randomUUID().toString()
                                toolRunDao.upsert(
                                    ToolRunEntity(
                                        id = toolRunId,
                                        messageId = pending.messageId,
                                        toolName = pending.toolName,
                                        argumentsJson = pending.arguments,
                                        resultJson = event.toolResult,
                                        status = if (event.toolResult?.contains("\"error\"") == true) "error" else "success",
                                        durationMs = null,
                                        createdAt = System.currentTimeMillis()
                                    )
                                )
                                messageDao.upsert(
                                    MessageEntity(
                                        id = pending.messageId,
                                        conversationId = conversationId,
                                        role = "tool",
                                        content = event.toolResult,
                                        reasoning = null,
                                        toolCallsJson = null,
                                        toolCallId = tcId,
                                        toolName = pending.toolName,
                                        createdAt = System.currentTimeMillis(),
                                        isStreaming = false
                                    )
                                )
                                // Update visible streaming tool calls
                                _state.update {
                                    it.copy(
                                        streamingToolCalls = it.streamingToolCalls.map { vtc ->
                                            if (vtc.toolCallId == tcId) vtc.copy(result = event.toolResult, isRunning = false)
                                            else vtc
                                        },
                                        activeToolName = null
                                    )
                                }
                            }
                            AgentLoop.Event.Type.FINISHED -> {
                                // Persist the assistant's final content as a message
                                val content = _state.value.streamingContent
                                val reasoning = _state.value.streamingReasoning
                                if (content.isNotEmpty() || reasoning.isNotEmpty()) {
                                    val assistantMsgId = UUID.randomUUID().toString()
                                    messageDao.upsert(
                                        MessageEntity(
                                            id = assistantMsgId,
                                            conversationId = conversationId,
                                            role = "assistant",
                                            content = content,
                                            reasoning = reasoning.ifEmpty { null },
                                            toolCallsJson = null,
                                            toolCallId = null,
                                            toolName = null,
                                            createdAt = System.currentTimeMillis()
                                        )
                                    )
                                }
                                // Update conversation timestamp
                                conversationDao.getById(conversationId)?.let { conv ->
                                    conversationDao.update(conv.copy(updatedAt = System.currentTimeMillis()))
                                }
                                _state.update {
                                    it.copy(
                                        isAgentRunning = false,
                                        streamingContent = "",
                                        streamingReasoning = "",
                                        streamingToolCalls = emptyList(),
                                        activeToolName = null
                                    )
                                }
                                stopAgentService()
                            }
                            AgentLoop.Event.Type.ERROR -> {
                                _state.update {
                                    it.copy(
                                        isAgentRunning = false,
                                        streamingContent = "",
                                        streamingReasoning = "",
                                        streamingToolCalls = emptyList(),
                                        activeToolName = null,
                                        error = event.error
                                    )
                                }
                                stopAgentService()
                            }
                        }
                    }
                } catch (e: Exception) {
                    _state.update {
                        it.copy(
                            isAgentRunning = false,
                            error = e.message ?: e::class.simpleName
                        )
                    }
                }
            }
        }
    }

    fun stopAgent() {
        agentJob?.cancel()
        _state.update { it.copy(isAgentRunning = false, activeToolName = null) }
        stopAgentService()
    }

    /**
     * Start the foreground service to keep the app alive when backgrounded.
     */
    private fun startAgentService(statusText: String) {
        try {
            val intent = android.content.Intent(appContext, com.pocketagent.service.AgentForegroundService::class.java)
            intent.putExtra("status_text", statusText)
            appContext.startService(intent)
        } catch (_: Exception) {
            // Foreground service might fail on some devices — not critical
        }
    }

    /**
     * Stop the foreground service.
     */
    private fun stopAgentService() {
        try {
            val intent = android.content.Intent(appContext, com.pocketagent.service.AgentForegroundService::class.java)
            appContext.stopService(intent)
        } catch (_: Exception) {}
    }

    override fun onCleared() {
        super.onCleared()
        stopAgentService()
    }

    /**
     * Start a new conversation. Clears current state but does NOT navigate
     * to a fake URL — the conversation is created when the user sends the
     * first message.
     */
    fun newConversation() {
        messageObserverJob?.cancel()
        agentJob?.cancel()
        _state.update {
            it.copy(
                conversationId = null,
                title = "New Chat",
                messages = emptyList(),
                inputText = "",
                streamingContent = "",
                streamingReasoning = "",
                streamingToolCalls = emptyList(),
                isAgentRunning = false,
                activeToolName = null,
                pendingAttachments = emptyList()
            )
        }
    }

    /**
     * Delete a conversation. If it's the current one, clears state.
     */
    fun deleteConversation(id: String) {
        viewModelScope.launch {
            conversationDao.deleteById(id)
            if (_state.value.conversationId == id) {
                newConversation()
            }
        }
    }

    /**
     * Rename a conversation.
     */
    fun renameConversation(id: String, newTitle: String) {
        viewModelScope.launch {
            conversationDao.updateTitle(id, newTitle, System.currentTimeMillis())
            if (_state.value.conversationId == id) {
                _state.update { it.copy(title = newTitle) }
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /**
     * Export the current conversation as markdown.
     */
    suspend fun exportConversation(): String? {
        val convId = _state.value.conversationId ?: return null
        val messages = withContext(Dispatchers.IO) {
            messageDao.getForConversation(convId)
        }
        val conv = conversationDao.getById(convId) ?: return null
        val sb = StringBuilder()
        sb.appendLine("# ${conv.title}")
        sb.appendLine()
        sb.appendLine("_Exported from PocketAgent on ${java.text.SimpleDateFormat("MMM d, yyyy 'at' HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}_")
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()
        for (msg in messages) {
            when (msg.role) {
                "user" -> {
                    sb.appendLine("## You")
                    sb.appendLine()
                    sb.appendLine(msg.content ?: "")
                    sb.appendLine()
                }
                "assistant" -> {
                    sb.appendLine("## PocketAgent")
                    sb.appendLine()
                    if (!msg.reasoning.isNullOrEmpty()) {
                        sb.appendLine("<details><summary>Reasoning</summary>")
                        sb.appendLine()
                        sb.appendLine("```")
                        sb.appendLine(msg.reasoning)
                        sb.appendLine("```")
                        sb.appendLine()
                        sb.appendLine("</details>")
                        sb.appendLine()
                    }
                    sb.appendLine(msg.content ?: "")
                    sb.appendLine()
                }
                "tool" -> {
                    val toolName = msg.toolName ?: "tool"
                    sb.appendLine("<details><summary>Tool: $toolName</summary>")
                    sb.appendLine()
                    sb.appendLine("```")
                    sb.appendLine(msg.content ?: "")
                    sb.appendLine("```")
                    sb.appendLine()
                    sb.appendLine("</details>")
                    sb.appendLine()
                }
            }
        }
        return sb.toString()
    }

    /**
     * Export conversation with callback (for UI thread).
     */
    fun exportConversation(onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val result = exportConversation()
            onResult(result)
        }
    }

    /**
     * Export a specific conversation by ID (for sidebar export).
     */
    fun exportConversationForId(conversationId: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val messages = withContext(Dispatchers.IO) {
                messageDao.getForConversation(conversationId)
            }
            val conv = conversationDao.getById(conversationId)
            if (conv == null) {
                onResult(null)
                return@launch
            }
            val sb = StringBuilder()
            sb.appendLine("# ${conv.title}")
            sb.appendLine()
            sb.appendLine("_Exported from PocketAgent on ${java.text.SimpleDateFormat("MMM d, yyyy 'at' HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}_")
            sb.appendLine()
            sb.appendLine("---")
            sb.appendLine()
            for (msg in messages) {
                when (msg.role) {
                    "user" -> {
                        sb.appendLine("## You")
                        sb.appendLine()
                        sb.appendLine(msg.content ?: "")
                        sb.appendLine()
                    }
                    "assistant" -> {
                        sb.appendLine("## PocketAgent")
                        sb.appendLine()
                        if (!msg.reasoning.isNullOrEmpty()) {
                            sb.appendLine("<details><summary>Reasoning</summary>")
                            sb.appendLine()
                            sb.appendLine("```")
                            sb.appendLine(msg.reasoning)
                            sb.appendLine("```")
                            sb.appendLine()
                            sb.appendLine("</details>")
                            sb.appendLine()
                        }
                        sb.appendLine(msg.content ?: "")
                        sb.appendLine()
                    }
                    "tool" -> {
                        val toolName = msg.toolName ?: "tool"
                        sb.appendLine("<details><summary>Tool: $toolName</summary>")
                        sb.appendLine()
                        sb.appendLine("```")
                        sb.appendLine(msg.content ?: "")
                        sb.appendLine("```")
                        sb.appendLine()
                        sb.appendLine("</details>")
                        sb.appendLine()
                    }
                }
            }
            onResult(sb.toString())
        }
    }

    private suspend fun MessageEntity.toUiWithToolRuns(): ChatMessageUi {
        val runs = toolRunDao.getForMessage(id)
        val firstRun = runs.firstOrNull()
        return ChatMessageUi(
            id = id,
            role = role,
            content = content ?: "",
            reasoning = reasoning,
            toolName = toolName ?: firstRun?.toolName,
            toolArguments = firstRun?.argumentsJson ?: toolCallsJson,
            toolResult = firstRun?.resultJson ?: content,
            toolDisplay = firstRun?.resultJson ?: content,
            isStreaming = isStreaming,
            createdAt = createdAt
        )
    }

    private fun MessageEntity.toLlm(): ChatMessage = when (role) {
        "user" -> {
            // Check if there are multimodal content parts stored in toolCallsJson (repurposed field)
            val parts = toolCallsJson?.let { jsonStr ->
                try {
                    json.decodeFromString(
                        kotlinx.serialization.builtins.ListSerializer(ChatMessage.ContentPart.serializer()),
                        jsonStr
                    )
                } catch (_: Exception) { null }
            }
            if (parts != null) {
                ChatMessage(
                    role = ChatMessage.Role.User,
                    content = content,
                    contentParts = parts
                )
            } else {
                ChatMessage(role = ChatMessage.Role.User, content = content)
            }
        }
        "assistant" -> {
            // CRITICAL: Reconstruct tool_calls array from toolCallsJson
            // This is needed so the LLM API sees: assistant { tool_calls: [...] }
            // before the tool result messages.
            val toolCalls = toolCallsJson?.let { jsonStr ->
                try {
                    json.decodeFromString(
                        kotlinx.serialization.builtins.ListSerializer(ChatMessage.ToolCall.serializer()),
                        jsonStr
                    )
                } catch (_: Exception) { null }
            }
            if (toolCalls != null && toolCalls.isNotEmpty()) {
                ChatMessage(
                    role = ChatMessage.Role.Assistant,
                    content = content,
                    reasoning = reasoning,
                    toolCalls = toolCalls
                )
            } else {
                ChatMessage(
                    role = ChatMessage.Role.Assistant,
                    content = content,
                    reasoning = reasoning
                )
            }
        }
        "tool" -> ChatMessage(
            role = ChatMessage.Role.Tool,
            content = content,
            toolCallId = toolCallId,
            name = toolName
        )
        "system" -> ChatMessage(role = ChatMessage.Role.System, content = content)
        else -> ChatMessage(role = ChatMessage.Role.User, content = content ?: "")
    }

    private data class PendingToolCall(
        val messageId: String,
        val toolName: String,
        val arguments: String,
        val assistantMsgId: String?
    )
}
