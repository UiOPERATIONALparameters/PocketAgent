package com.pocketagent.ui.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import android.widget.Toast
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketagent.design.PocketType
import com.pocketagent.design.extendedColors
import com.pocketagent.design.softShadow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import androidx.compose.runtime.snapshotFlow

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenSettings: () -> Unit,
    onOpenFiles: () -> Unit,
    onNewConversation: () -> Unit,
    onOpenConversation: (String) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var userScrolledUp by remember { mutableStateOf(false) }
    var lastMessageCount by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    // v4.1 SCROLL FIX: Only auto-scroll when TRULY at the bottom.
    // The old code auto-scrolled on every new message, fighting the user.
    // New logic: check if user is at the VERY LAST item before scrolling.
    LaunchedEffect(state.messages.size) {
        if (state.messages.size > lastMessageCount) {
            // New message(s) added — only scroll if user was already at bottom
            if (!userScrolledUp) {
                val target = state.messages.size - 1
                if (target >= 0) {
                    listState.animateScrollToItem(target)
                }
            }
        }
        lastMessageCount = state.messages.size
    }

    // Detect user scroll position — only update userScrolledUp when user DRAGS
    androidx.compose.runtime.LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (scrolling) {
                    val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    val totalItems = listState.layoutInfo.totalItemsCount
                    if (totalItems > 0 && lastVisible >= totalItems - 1) {
                        userScrolledUp = false  // at very bottom — resume auto-scroll
                    } else {
                        userScrolledUp = true   // scrolled up — stop auto-scroll
                    }
                }
            }
    }

    val context = LocalContext.current

    // Image picker for vision attachments
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                // Persist permission so we can read the URI later
                val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: Exception) {
                // Some content providers don't support persistable permissions — that's OK
            }
            val mimeType = context.contentResolver.getType(uri) ?: "image/*"
            // Try to get a real display name from the content resolver
            val displayName = try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0 && cursor.moveToFirst()) {
                        cursor.getString(nameIdx)
                    } else {
                        "image_${System.currentTimeMillis()}.jpg"
                    }
                } ?: "image_${System.currentTimeMillis()}.jpg"
            } catch (_: Exception) {
                "image_${System.currentTimeMillis()}.jpg"
            }
            viewModel.addAttachment(uri.toString(), mimeType, displayName)
        }
    }

    // v4.0: Removed the streaming-content auto-scroll that fought user scrolling.
    // Auto-scroll now ONLY fires on new messages (see LaunchedEffect above).

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(extendedColors().bg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
            // Top bar
            ChatTopBar(
                title = state.title,
                onMenuClick = viewModel::toggleSidebar,
                onNewChat = {
                    viewModel.requestNewChat()
                    if (!state.isAgentRunning) onNewConversation()
                },
                onOpenSettings = onOpenSettings,
                onOpenFiles = onOpenFiles
            )

            // Messages list
            if (state.messages.isEmpty() && state.streamingContent.isEmpty() && !state.isAgentRunning) {
                // CRITICAL: Use weight(1f) so EmptyChatState doesn't consume all
                // vertical space and push the composer off-screen
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    EmptyChatState()
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // v4.6: Group consecutive tool messages into collapsed summary cards
                    val groupedMessages = remember(state.messages) { groupConsecutiveTools(state.messages) }
                    items(groupedMessages, key = { it.id }) { item ->
                        when (item) {
                            is GroupedMessage.Single -> ChatMessageItem(item.msg)
                            is GroupedMessage.ToolGroup -> ToolGroupCard(item.messages)
                        }
                    }
                    // Streaming message
                    if (state.isAgentRunning && (state.streamingContent.isNotEmpty() || state.streamingReasoning.isNotEmpty() || state.streamingToolCalls.isNotEmpty())) {
                        item {
                            StreamingMessage(
                                content = state.streamingContent,
                                reasoning = state.streamingReasoning,
                                toolCalls = state.streamingToolCalls,
                                activeToolName = state.activeToolName
                            )
                        }
                    }
                    // If agent is running but no content yet, show thinking indicator
                    if (state.isAgentRunning && state.streamingContent.isEmpty() && state.streamingReasoning.isEmpty() && state.streamingToolCalls.isEmpty()) {
                        item { ThinkingIndicator() }
                    }
                }
            }

            // Pending attachments preview
            if (state.pendingAttachments.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    state.pendingAttachments.forEach { att ->
                        Surface(
                            color = extendedColors().surfaceSubtle,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.clickable(onClick = { viewModel.removeAttachment(att.uri) })
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Show image icon + name
                                Icon(
                                    Icons.Filled.AttachFile,
                                    contentDescription = null,
                                    tint = extendedColors().accent,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    att.displayName.take(24),
                                    style = PocketType.LabelSmall,
                                    color = extendedColors().textPrimary,
                                    maxLines = 1
                                )
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Remove",
                                    tint = extendedColors().textSecondary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Composer
            ChatComposer(
                text = state.inputText,
                onTextChange = viewModel::onInputTextChange,
                onSend = viewModel::sendMessage,
                onStop = viewModel::stopAgent,
                onAttach = { imagePicker.launch("image/*") },
                isRunning = state.isAgentRunning,
                hasAttachments = state.pendingAttachments.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Sidebar
        AnimatedVisibility(
            visible = state.sidebarOpen,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            SidebarOverlay(
                conversations = state.conversations,
                activeConversationId = state.conversationId,
                onConversationClick = { id ->
                    viewModel.closeSidebar()
                    onOpenConversation(id)
                },
                onNewConversation = {
                    viewModel.newConversation()
                    viewModel.closeSidebar()
                    onNewConversation()
                },
                onDeleteConversation = viewModel::deleteConversation,
                onRenameConversation = viewModel::renameConversation,
                onExportConversation = { id ->
                    viewModel.closeSidebar()
                    viewModel.exportConversationForId(id) { markdown ->
                        if (markdown != null) {
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/markdown"
                                putExtra(android.content.Intent.EXTRA_SUBJECT, "Conversation")
                                putExtra(android.content.Intent.EXTRA_TEXT, markdown)
                            }
                            val chooser = android.content.Intent.createChooser(intent, "Export Conversation")
                            chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(chooser)
                        } else {
                            Toast.makeText(context, "Could not export", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onClose = viewModel::closeSidebar
            )
        }

        // New chat warning dialog
        if (state.showNewChatWarning) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { viewModel.cancelNewChat() },
                title = { Text("Agent is running", style = PocketType.Title, color = extendedColors().textPrimary) },
                text = {
                    Text(
                        "The AI agent is still working. Starting a new chat will stop the current task. Continue?",
                        style = PocketType.Body,
                        color = extendedColors().textSecondary
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            viewModel.forceNewChat()
                            onNewConversation()
                        }
                    ) { Text("New Chat", color = extendedColors().accent) }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(
                        onClick = { viewModel.cancelNewChat() }
                    ) { Text("Cancel", color = extendedColors().textSecondary) }
                },
                containerColor = extendedColors().surface
            )
        }

        // Scroll-to-bottom button when user scrolled up
        if (userScrolledUp) {
            val scope = rememberCoroutineScope()
            Surface(
                onClick = {
                    userScrolledUp = false
                    val target = state.messages.size + (if (state.streamingContent.isNotEmpty() || state.streamingToolCalls.isNotEmpty()) 1 else 0) - 1
                    if (target >= 0) {
                        scope.launch { listState.animateScrollToItem(target.coerceAtLeast(0)) }
                    }
                },
                color = extendedColors().accent,
                shape = RoundedCornerShape(50),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .size(44.dp)
            ) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = "Scroll to bottom",
                    tint = extendedColors().textOnAccent,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }

        // Error banner
        AnimatedVisibility(
            visible = state.error != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            state.error?.let { err ->
                ErrorBanner(message = err, onDismiss = viewModel::clearError)
            }
        }
    }
}

@Composable
private fun ChatTopBar(
    title: String,
    onMenuClick: () -> Unit,
    onNewChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFiles: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onMenuClick) {
            Icon(
                imageVector = Icons.Filled.Menu,
                contentDescription = "Menu",
                tint = extendedColors().textPrimary
            )
        }
        Text(
            text = title,
            style = PocketType.Title,
            color = extendedColors().textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onOpenFiles) {
            Icon(
                imageVector = Icons.Filled.Folder,
                contentDescription = "Files",
                tint = extendedColors().textPrimary
            )
        }
        IconButton(onClick = onNewChat) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "New chat",
                tint = extendedColors().textPrimary
            )
        }
        IconButton(onClick = onOpenSettings) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "Settings",
                tint = extendedColors().textPrimary
            )
        }
    }
}

@Composable
private fun EmptyChatState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "What can I help with?",
            style = PocketType.Display,
            color = extendedColors().textPrimary
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Your agent can run commands, write files, fetch the web.",
            style = PocketType.Body,
            color = extendedColors().textSecondary,
            modifier = Modifier.alpha(0.7f)
        )
    }
}

@Composable
private fun ChatMessageItem(msg: ChatMessageUi) {
    when (msg.role) {
        "user" -> UserBubble(msg.content)
        "assistant" -> AssistantBubble(msg.content, msg.reasoning)
        "tool" -> {
            if (msg.toolName == "todo" && msg.toolResult != null) {
                val todoItems = parseTodoResult(msg.toolResult)
                if (todoItems.isNotEmpty()) {
                    TodoListCard(todos = todoItems)
                } else {
                    ToolCallCard(toolName = msg.toolName ?: "tool", arguments = msg.toolArguments, result = msg.toolResult, display = msg.toolDisplay)
                }
            } else {
                ToolCallCard(toolName = msg.toolName ?: "tool", arguments = msg.toolArguments, result = msg.toolResult, display = msg.toolDisplay)
            }
        }
        else -> UserBubble(msg.content)
    }
}

private fun parseTodoResult(resultJson: String): List<com.pocketagent.ui.chat.TodoItem> {
    return try {
        val obj = org.json.JSONObject(resultJson)
        val todos = obj.optJSONArray("todos") ?: return emptyList()
        (0 until todos.length()).map { i ->
            val item = todos.getJSONObject(i)
            com.pocketagent.ui.chat.TodoItem(content = item.optString("content", ""), status = item.optString("status", "pending"))
        }
    } catch (_: Exception) { emptyList() }
}

@Composable
private fun UserBubble(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Surface(
            color = extendedColors().bubbleUser,
            shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Text(
                text = text,
                style = PocketType.Body,
                color = extendedColors().bubbleUserText,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun AssistantBubble(text: String, reasoning: String?) {
    if (text.isEmpty() && reasoning.isNullOrEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 32.dp)
    ) {
        // Reasoning (collapsible)
        if (!reasoning.isNullOrEmpty()) {
            var expanded by remember { mutableStateOf(false) }
            Surface(
                color = extendedColors().surfaceSubtle,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = { expanded = !expanded })
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Reasoning",
                            style = PocketType.LabelSmall,
                            color = extendedColors().textSecondary
                        )
                        Spacer(Modifier.weight(1f))
                        Icon(
                            imageVector = Icons.Filled.ChevronRight,
                            contentDescription = if (expanded) "Collapse" else "Expand",
                            tint = extendedColors().textSecondary,
                            modifier = Modifier.alpha(if (expanded) 1f else 0.5f)
                        )
                    }
                    AnimatedVisibility(visible = expanded) {
                        Text(
                            text = reasoning,
                            style = PocketType.CodeSmall,
                            color = extendedColors().textSecondary,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        // Content
        if (text.isNotEmpty()) {
            MarkdownText(
                text = text,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

@Composable
private fun StreamingMessage(
    content: String,
    reasoning: String,
    toolCalls: List<ChatUiState.VisibleToolCall>,
    activeToolName: String?
) {
    Column(modifier = Modifier.fillMaxWidth().padding(end = 32.dp)) {
        if (reasoning.isNotEmpty()) {
            Text(
                text = reasoning,
                style = PocketType.CodeSmall,
                color = extendedColors().textSecondary,
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .alpha(0.7f)
            )
        }
        // Streaming tool calls
        toolCalls.forEach { tc ->
            ToolCallCard(
                toolName = tc.toolName,
                arguments = tc.arguments,
                result = tc.result,
                display = tc.result,
                isRunning = tc.isRunning
            )
            Spacer(Modifier.height(8.dp))
        }
        if (activeToolName != null && toolCalls.isEmpty()) {
            Text(
                text = "Calling $activeToolName…",
                style = PocketType.LabelSmall,
                color = extendedColors().accent,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        if (content.isNotEmpty()) {
            MarkdownText(
                text = content,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

@Composable
private fun ThinkingIndicator() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 32.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val alpha by animateFloatAsState(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            ),
            label = "thinking"
        )
        Text(
            text = "Thinking",
            style = PocketType.Body,
            color = extendedColors().textSecondary,
            modifier = Modifier.alpha(alpha * 0.7f)
        )
        Spacer(Modifier.width(4.dp))
        repeat(3) { i ->
            Text(
                text = ".",
                style = PocketType.Body,
                color = extendedColors().textSecondary,
                modifier = Modifier.alpha(alpha * (0.4f + i * 0.2f))
            )
        }
    }
}

@Composable
private fun ToolCallCard(
    toolName: String,
    arguments: String?,
    result: String?,
    display: String?,
    isRunning: Boolean = false
) {
    val context = LocalContext.current
    // v4.1: Always collapsed by default — expand on tap only
    var expanded by remember(toolName, arguments, result) { mutableStateOf(false) }
    val ext = extendedColors()

    // Build a short summary for the collapsed view
    val summary = remember(arguments, result, isRunning) {
        buildString {
            if (toolName == "bash" && arguments != null) {
                // Extract the command from JSON
                try {
                    val obj = org.json.JSONObject(arguments)
                    val cmd = obj.optString("command", "")
                    if (cmd.isNotEmpty()) {
                        // Truncate long commands
                        append("$ ")
                        append(cmd.take(80))
                        if (cmd.length > 80) append("…")
                    }
                } catch (_: Exception) {
                    append(toolName)
                }
            } else {
                append(toolName)
            }
            if (isRunning) {
                append("  ⟳")
            } else if (result != null) {
                // Check if it was success or error
                if (result.contains("\"error\"")) {
                    append("  ✗")
                } else {
                    append("  ✓")
                }
            }
        }
    }

    Surface(
        color = ext.toolCardBg,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            ext.toolCardBorder
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = { expanded = !expanded })
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = summary,
                    style = PocketType.CodeSmall,
                    color = if (result?.contains("\"error\"") == true) ext.error else ext.textPrimary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                // Copy button (only when result is available)
                if (result != null && !isRunning) {
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("result", display ?: result))
                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = "Copy",
                            tint = ext.textTertiary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = ext.textSecondary,
                    modifier = Modifier.alpha(if (expanded) 1f else 0.5f)
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 6.dp)) {
                    if (display != null) {
                        Text(
                            text = display,
                            style = PocketType.CodeSmall,
                            color = ext.textSecondary
                        )
                    } else if (result != null) {
                        Text(
                            text = result,
                            style = PocketType.CodeSmall,
                            color = ext.textSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatComposer(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    isRunning: Boolean,
    hasAttachments: Boolean = false,
    modifier: Modifier = Modifier
) {
    val ext = extendedColors()
    Surface(
        color = ext.surface,
        modifier = modifier
            .softShadow(elevation = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .imePadding(),
            verticalAlignment = Alignment.Bottom
        ) {
            // Attach button
            IconButton(onClick = onAttach, modifier = Modifier.size(44.dp)) {
                Icon(
                    imageVector = Icons.Filled.AttachFile,
                    contentDescription = "Attach image",
                    tint = ext.textSecondary
                )
            }
            Spacer(Modifier.width(4.dp))
            // Input field with rounded shape (Kimi's 24dp signature)
            TextField(
                value = text,
                onValueChange = onTextChange,
                placeholder = {
                    Text(
                        "Ask anything, or describe a task…",
                        style = PocketType.Body,
                        color = ext.textSecondary
                    )
                },
                textStyle = PocketType.Body.copy(color = ext.textPrimary),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp, max = 160.dp),
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = ext.surfaceSubtle,
                    unfocusedContainerColor = ext.surfaceSubtle,
                    disabledContainerColor = ext.surfaceSubtle,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    cursorColor = ext.accent
                ),
                keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Default),
                maxLines = 6
            )
            Spacer(Modifier.width(8.dp))
            // Send button — enabled when there's text OR attachments
            val canSend = (text.isNotBlank() || hasAttachments) && !isRunning
            IconButton(
                onClick = { if (isRunning) onStop() else onSend() },
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (canSend || isRunning) ext.accent else ext.surfaceSubtle)
            ) {
                Icon(
                    imageVector = if (isRunning) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
                    contentDescription = if (isRunning) "Stop" else "Send",
                    tint = if (isRunning) ext.error else ext.textOnAccent
                )
            }
        }
    }
}

@Composable
private fun SidebarOverlay(
    conversations: List<com.pocketagent.storage.db.ConversationEntity>,
    activeConversationId: String?,
    onConversationClick: (String) -> Unit,
    onNewConversation: () -> Unit,
    onDeleteConversation: (String) -> Unit,
    onRenameConversation: (String, String) -> Unit,
    onExportConversation: (String) -> Unit,
    onClose: () -> Unit
) {
    val ext = extendedColors()
    var renamingId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f))) {
        // Click-outside catcher
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onClose)
        )
        Surface(
            color = ext.bg,
            modifier = Modifier
                .fillMaxHeight()
                .width(280.dp)
                .softShadow(elevation = 8.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text(
                    text = "PocketAgent",
                    style = PocketType.Headline,
                    color = ext.textPrimary,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
                // New chat button
                Surface(
                    onClick = onNewConversation,
                    color = ext.surface,
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, ext.divider),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = null,
                            tint = ext.textPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "New Chat",
                            style = PocketType.BodyMedium,
                            color = ext.textPrimary
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(conversations, key = { it.id }) { conv ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (conv.id == activeConversationId) ext.surfaceSubtle else Color.Transparent
                                )
                                .clickable(onClick = { onConversationClick(conv.id) })
                                .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = conv.title,
                                style = PocketType.BodySmall,
                                color = ext.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            // Rename button
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(50))
                                    .clickable(onClick = {
                                        renamingId = conv.id
                                        renameText = conv.title
                                    }),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Edit,
                                    contentDescription = "Rename",
                                    tint = ext.textTertiary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            // Export button
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(50))
                                    .clickable(onClick = { onExportConversation(conv.id) }),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.IosShare,
                                    contentDescription = "Export",
                                    tint = ext.textTertiary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            // Delete button
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(50))
                                    .clickable(onClick = { onDeleteConversation(conv.id) }),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = "Delete",
                                    tint = ext.textTertiary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Rename dialog
    if (renamingId != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renamingId = null },
            title = { Text("Rename conversation", style = PocketType.Title, color = ext.textPrimary) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = ext.surface,
                        unfocusedContainerColor = ext.surface,
                        focusedBorderColor = ext.accent,
                        unfocusedBorderColor = ext.divider,
                        cursorColor = ext.accent
                    )
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        val id = renamingId
                        if (id != null && renameText.isNotBlank()) {
                            onRenameConversation(id, renameText.trim())
                        }
                        renamingId = null
                    }
                ) {
                    Text("Save", color = ext.accent)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { renamingId = null }) {
                    Text("Cancel", color = ext.textSecondary)
                }
            },
            containerColor = ext.surface,
            titleContentColor = ext.textPrimary
        )
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    val ext = extendedColors()
    Surface(
        color = ext.error,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.padding(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                style = PocketType.BodySmall,
                color = Color.White,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Dismiss",
                    tint = Color.White
                )
            }
        }
    }
}

// v4.6: Tool call grouping — collapse consecutive tool messages into summary cards

sealed class GroupedMessage {
    abstract val id: String
    data class Single(val msg: ChatMessageUi) : GroupedMessage() { override val id = msg.id }
    data class ToolGroup(val messages: List<ChatMessageUi>) : GroupedMessage() { override val id = messages.firstOrNull()?.id ?: java.util.UUID.randomUUID().toString() }
}

fun groupConsecutiveTools(messages: List<ChatMessageUi>): List<GroupedMessage> {
    val result = mutableListOf<GroupedMessage>()
    var toolBuffer = mutableListOf<ChatMessageUi>()
    for (msg in messages) {
        if (msg.role == "tool") {
            toolBuffer.add(msg)
        } else {
            if (toolBuffer.isNotEmpty()) {
                result.add(GroupedMessage.ToolGroup(toolBuffer.toList()))
                toolBuffer.clear()
            }
            result.add(GroupedMessage.Single(msg))
        }
    }
    if (toolBuffer.isNotEmpty()) result.add(GroupedMessage.ToolGroup(toolBuffer.toList()))
    return result
}

@Composable
private fun ToolGroupCard(messages: List<ChatMessageUi>) {
    val ext = extendedColors()
    var expanded by remember { mutableStateOf(false) }
    val toolCounts = remember(messages) { messages.groupingBy { it.toolName ?: "tool" }.eachCount() }
    val summary = remember(messages) {
        buildString {
            val total = messages.size
            if (total == 1) {
                val msg = messages.first()
                if (msg.toolName == "bash" && msg.toolArguments != null) {
                    try { val obj = org.json.JSONObject(msg.toolArguments); val cmd = obj.optString("command", "").take(60); if (cmd.isNotEmpty()) append("$ $cmd") else append("bash") } catch (_: Exception) { append("bash") }
                } else { append(msg.toolName ?: "tool") }
            } else {
                val parts = toolCounts.map { (name, count) -> "$count\u00d7 $name" }
                append(parts.joinToString(", "))
            }
            val hasError = messages.any { it.toolResult?.contains("\"error\"") == true }
            if (hasError) append(" \u2717") else append(" \u2713")
        }
    }
    Surface(
        color = ext.toolCardBg,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, ext.toolCardBorder),
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("\u26a1", style = PocketType.CodeSmall, color = ext.textTertiary)
                Spacer(Modifier.size(6.dp))
                Text(summary, style = PocketType.CodeSmall, color = if (summary.contains("\u2717")) ext.error else ext.textSecondary, maxLines = 1, modifier = Modifier.weight(1f))
                Icon(imageVector = if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.ChevronRight, contentDescription = null, tint = ext.textTertiary, modifier = Modifier.size(16.dp))
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                messages.forEach { msg ->
                    ToolCallCard(toolName = msg.toolName ?: "tool", arguments = msg.toolArguments, result = msg.toolResult, display = msg.toolDisplay)
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}
