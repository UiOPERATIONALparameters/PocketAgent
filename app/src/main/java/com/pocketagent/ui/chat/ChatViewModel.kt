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
    val activeToolName: String? = null,
    val error: String? = null,
    val sidebarOpen: Boolean = false
)

@HiltViewModel
class ChatViewModel @Inject constructor(
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
    private val json = Json { ignoreUnknownKeys = true }

    init {
        viewModelScope.launch {
            settings.load()
            // Observe conversations list
            conversationDao.observeAll().collect { convs ->
                _state.update { it.copy(conversations = convs) }
            }
        }
    }

    fun loadConversation(id: String) {
        viewModelScope.launch {
            val conv = conversationDao.getById(id) ?: return@launch
            _state.update {
                it.copy(conversationId = id, title = conv.title, messages = emptyList())
            }
            messageDao.observeForConversation(id).collect { messages ->
                val uiMessages = messages.map { it.toUi() }
                _state.update { it.copy(messages = uiMessages) }
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

    fun sendMessage() {
        val text = _state.value.inputText.trim()
        if (text.isEmpty() || _state.value.isAgentRunning) return

        val provider = activeProviderHolder.get()
        if (provider == null) {
            _state.update { it.copy(error = "No provider configured. Open Settings.") }
            return
        }
        val modelId = settings.settings.value.activeModelId
        if (modelId == null) {
            _state.update { it.copy(error = "No model selected. Open Settings.") }
            return
        }

        viewModelScope.launch {
            // Create conversation if needed
            var conversationId = _state.value.conversationId
            if (conversationId == null) {
                conversationId = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                val title = text.take(40).ifEmpty { "New Chat" }
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
            }

            // Persist user message
            val userMsgId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            messageDao.upsert(
                MessageEntity(
                    id = userMsgId,
                    conversationId = conversationId,
                    role = "user",
                    content = text,
                    reasoning = null,
                    toolCallsJson = null,
                    toolCallId = null,
                    toolName = null,
                    createdAt = now
                )
            )

            _state.update {
                it.copy(
                    inputText = "",
                    isAgentRunning = true,
                    streamingContent = "",
                    streamingReasoning = "",
                    activeToolName = null,
                    error = null
                )
            }

            // Build message history from DB
            val history = withContext(Dispatchers.IO) {
                messageDao.getForConversation(conversationId).map { it.toLlm() }
            }

            // Track tool calls for persistence
            val pendingToolCalls = mutableMapOf<String, PendingToolCall>()

            agentJob = viewModelScope.launch {
                try {
                    agentLoop.run(
                        provider = provider,
                        modelId = modelId,
                        messages = history
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
                            AgentLoop.Event.Type.TOOL_CALL_START -> {
                                val tcId = event.toolCallId ?: return@collect
                                val msgId = UUID.randomUUID().toString()
                                pendingToolCalls[tcId] = PendingToolCall(
                                    messageId = msgId,
                                    toolName = event.toolName ?: "unknown",
                                    arguments = event.toolArguments ?: "{}"
                                )
                                // Persist assistant message with tool call info
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
                                _state.update { it.copy(activeToolName = event.toolName) }
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
                                _state.update { it.copy(activeToolName = null) }
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
                                        activeToolName = null
                                    )
                                }
                            }
                            AgentLoop.Event.Type.ERROR -> {
                                _state.update {
                                    it.copy(
                                        isAgentRunning = false,
                                        streamingContent = "",
                                        streamingReasoning = "",
                                        activeToolName = null,
                                        error = event.error
                                    )
                                }
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
    }

    fun newConversation(): String {
        val id = UUID.randomUUID().toString()
        _state.update {
            it.copy(
                conversationId = null,
                title = "New Chat",
                messages = emptyList(),
                inputText = "",
                streamingContent = "",
                streamingReasoning = "",
                isAgentRunning = false
            )
        }
        return id
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            conversationDao.deleteById(id)
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    private fun MessageEntity.toUi(): ChatMessageUi = ChatMessageUi(
        id = id,
        role = role,
        content = content ?: "",
        reasoning = reasoning,
        toolName = toolName,
        toolArguments = toolRunDao.let { dao -> /* we'd need to query tool runs separately */ null },
        toolResult = content,
        toolDisplay = content,
        isStreaming = isStreaming,
        createdAt = createdAt
    )

    private suspend fun MessageEntity.toUiWithToolRuns(): ChatMessageUi {
        val runs = toolRunDao.getForMessage(id)
        val firstRun = runs.firstOrNull()
        return ChatMessageUi(
            id = id,
            role = role,
            content = content ?: "",
            reasoning = reasoning,
            toolName = toolName ?: firstRun?.toolName,
            toolArguments = firstRun?.argumentsJson,
            toolResult = firstRun?.resultJson,
            toolDisplay = firstRun?.resultJson,
            isStreaming = isStreaming,
            createdAt = createdAt
        )
    }

    private fun MessageEntity.toLlm(): ChatMessage = when (role) {
        "user" -> ChatMessage(role = ChatMessage.Role.User, content = content)
        "assistant" -> ChatMessage(
            role = ChatMessage.Role.Assistant,
            content = content,
            reasoning = reasoning
        )
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
        val arguments: String
    )
}
