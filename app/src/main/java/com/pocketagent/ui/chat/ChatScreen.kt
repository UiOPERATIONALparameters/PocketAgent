package com.pocketagent.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketagent.design.PocketType
import com.pocketagent.design.extendedColors
import com.pocketagent.design.softShadow
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenSettings: () -> Unit,
    onNewConversation: (String) -> Unit,
    onOpenConversation: (String) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Auto-scroll to bottom on new messages
    LaunchedEffect(state.messages.size, state.streamingContent) {
        if (state.messages.isNotEmpty() || state.streamingContent.isNotEmpty()) {
            val target = if (state.streamingContent.isNotEmpty()) state.messages.size else state.messages.size - 1
            if (target >= 0) {
                listState.animateScrollToItem(target.coerceAtLeast(0))
            }
        }
    }

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
                    val id = viewModel.newConversation()
                    onNewConversation(id)
                },
                onOpenSettings = onOpenSettings
            )

            // Messages list
            if (state.messages.isEmpty() && state.streamingContent.isEmpty() && !state.isAgentRunning) {
                EmptyChatState()
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(state.messages, key = { it.id }) { msg ->
                        ChatMessageItem(msg)
                    }
                    // Streaming message
                    if (state.isAgentRunning && state.streamingContent.isNotEmpty()) {
                        item {
                            StreamingMessage(
                                content = state.streamingContent,
                                reasoning = state.streamingReasoning,
                                activeToolName = state.activeToolName
                            )
                        }
                    }
                    // If agent is running but no content yet, show thinking indicator
                    if (state.isAgentRunning && state.streamingContent.isEmpty() && state.streamingReasoning.isEmpty() && state.activeToolName == null) {
                        item { ThinkingIndicator() }
                    }
                }
            }

            // Composer
            ChatComposer(
                text = state.inputText,
                onTextChange = viewModel::onInputTextChange,
                onSend = viewModel::sendMessage,
                onStop = viewModel::stopAgent,
                isRunning = state.isAgentRunning,
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
                    val id = viewModel.newConversation()
                    viewModel.closeSidebar()
                    onNewConversation(id)
                },
                onDeleteConversation = viewModel::deleteConversation,
                onClose = viewModel::closeSidebar
            )
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
    onOpenSettings: () -> Unit
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
        "tool" -> ToolCallCard(
            toolName = msg.toolName ?: "tool",
            arguments = msg.toolArguments,
            result = msg.toolResult,
            display = msg.toolDisplay
        )
        else -> UserBubble(msg.content)
    }
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
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { expanded = !expanded }
                    )
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
        Text(
            text = text,
            style = PocketType.Body,
            color = extendedColors().bubbleAgentText,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@Composable
private fun StreamingMessage(
    content: String,
    reasoning: String,
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
        if (activeToolName != null) {
            Text(
                text = "Calling $activeToolName…",
                style = PocketType.LabelSmall,
                color = extendedColors().accent,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        if (content.isNotEmpty()) {
            Text(
                text = content,
                style = PocketType.Body,
                color = extendedColors().bubbleAgentText
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
    display: String?
) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = extendedColors().toolCardBg,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            extendedColors().toolCardBorder
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 32.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { expanded = !expanded }
            )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = toolName,
                    style = PocketType.CodeSmall,
                    color = extendedColors().accent,
                    fontWeight = FontWeight.SemiBold
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
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    if (display != null) {
                        Text(
                            text = display,
                            style = PocketType.CodeSmall,
                            color = extendedColors().textSecondary
                        )
                    } else if (result != null) {
                        Text(
                            text = result,
                            style = PocketType.CodeSmall,
                            color = extendedColors().textSecondary
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
    isRunning: Boolean,
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
            // Input field with rounded shape (Kimi's 24dp signature)
            androidx.compose.material3.TextField(
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
                colors = androidx.compose.material3.TextFieldDefaults.colors(
                    focusedContainerColor = ext.surfaceSubtle,
                    unfocusedContainerColor = ext.surfaceSubtle,
                    disabledContainerColor = ext.surfaceSubtle,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    cursorColor = ext.accent
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                maxLines = 6
            )
            Spacer(Modifier.width(8.dp))
            // Send button
            val canSend = text.isNotBlank() && !isRunning
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
    onClose: () -> Unit
) {
    val ext = extendedColors()
    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f))) {
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
                    color = ext.surface,
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, ext.divider),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onNewConversation
                        )
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
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { onConversationClick(conv.id) }
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp),
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
                            IconButton(
                                onClick = { onDeleteConversation(conv.id) },
                                modifier = Modifier.size(24.dp)
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
        // Click-outside to close
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose
                )
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
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Dismiss",
                    tint = Color.White
                )
            }
        }
    }
}


