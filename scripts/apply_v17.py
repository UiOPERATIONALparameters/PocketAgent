#!/usr/bin/env python3
"""Apply all v1.7.0 fixes — /tmp, apt paths, UI improvements, efficiency."""

import os, re
os.chdir('/home/z/my-project/PocketAgent')

def read(path):
    with open(path, 'r') as f: return f.read()

def write(path, content):
    with open(path, 'w') as f: f.write(content)

def replace(path, old, new):
    content = read(path)
    if old in content:
        content = content.replace(old, new)
        write(path, content)
        return True
    return False

# ============================================================
# 1. BootstrapInstaller: Add /tmp symlink + fix init script
# ============================================================
content = read('app/src/main/java/com/pocketagent/sandbox/BootstrapInstaller.kt')

# Add createTmpSymlink call after createShellConfigs
if 'createTmpSymlink()' not in content:
    content = content.replace(
        '            createShellConfigs()\n',
        '            createShellConfigs()\n\n            // CRITICAL: Create /tmp symlink so tools that hardcode /tmp work\n            createTmpSymlink()\n\n            // CRITICAL: Remove init-termux-properties.sh (causes permission errors)\n            removeBrokenInitScripts()\n'
    )
    print("OK: Added createTmpSymlink + removeBrokenInitScripts calls")

# Add the functions before createShellConfigs
if 'private fun createTmpSymlink' not in content:
    insert_before = '    /**\n     * Create .profile'
    new_funcs = '''    /**
     * CRITICAL: Create /tmp symlink.
     * Many tools (pip, compilers, etc.) hardcode /tmp.
     * We can't write to system /tmp, but we can create a symlink
     * from /tmp to our workspace tmp directory.
     * If symlink fails (permission), set TMPDIR env var (already done).
     */
    private fun createTmpSymlink() {
        // Try to create /tmp -> ~/tmp symlink
        // This may fail on some Android versions — that's OK, TMPDIR is set
        val tmpLink = java.io.File("/tmp")
        if (!tmpLink.exists()) {
            try {
                java.nio.file.Files.createSymbolicLink(
                    tmpLink.toPath(),
                    workspace.tmpDir.toPath()
                )
            } catch (_: Exception) {
                // Can't create /tmp symlink — TMPDIR env var is the fallback
            }
        }
    }

    /**
     * Remove scripts that try to write to /data/data/com.termux/ paths.
     * These cause "Permission denied" errors on every login.
     */
    private fun removeBrokenInitScripts() {
        val profileDir = java.io.File(usrDir, "etc/profile.d")
        if (!profileDir.exists()) return

        // Remove init-termux-properties.sh — it writes to /data/data/com.termux/
        val brokenScripts = listOf(
            "init-termux-properties.sh",
            "termux-proot.sh"
        )
        for (scriptName in brokenScripts) {
            val script = java.io.File(profileDir, scriptName)
            if (script.exists()) {
                script.delete()
            }
        }

        // Also patch any remaining profile.d scripts that reference com.termux
        profileDir.listFiles { f -> f.name.endsWith(".sh") }?.forEach { script ->
            try {
                val text = script.readText()
                if (text.contains("/data/data/com.termux/")) {
                    script.writeText(text.replace(
                        "/data/data/com.termux/files/usr",
                        usrDir.absolutePath
                    ).replace(
                        "/data/data/com.termux/files/home",
                        workspace.homeDir.absolutePath
                    ))
                }
            } catch (_: Exception) {}
        }
    }

    '''
    content = content.replace(insert_before, new_funcs + insert_before)
    print("OK: Added createTmpSymlink + removeBrokenInitScripts functions")

write('app/src/main/java/com/pocketagent/sandbox/BootstrapInstaller.kt', content)

# ============================================================
# 2. ChatViewModel: Don't kill ongoing chat when creating new one
# ============================================================
content = read('app/src/main/java/com/pocketagent/ui/chat/ChatViewModel.kt')

# Add a warning state for when agent is running
if 'showNewChatWarning' not in content:
    content = content.replace(
        'val sidebarOpen: Boolean = false',
        'val sidebarOpen: Boolean = false,\n    val showNewChatWarning: Boolean = false'
    )
    print("OK: Added showNewChatWarning state")

# Update newConversation to check if agent is running
old_new = '''    fun newConversation(): String {
        val id = UUID.randomUUID().toString()
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
        return id
    }'''

new_new = '''    fun newConversation(): String {
        val id = UUID.randomUUID().toString()
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
                pendingAttachments = emptyList(),
                showNewChatWarning = false
            )
        }
        return id
    }

    /**
     * Request new chat — shows warning if agent is running.
     */
    fun requestNewChat() {
        if (_state.value.isAgentRunning) {
            _state.update { it.copy(showNewChatWarning = true) }
        } else {
            newConversation()
        }
    }

    /**
     * Force new chat even if agent is running.
     */
    fun forceNewChat() {
        stopAgent()
        newConversation()
    }

    /**
     * Cancel new chat warning.
     */
    fun cancelNewChat() {
        _state.update { it.copy(showNewChatWarning = false) }
    }'''

if old_new in content:
    content = content.replace(old_new, new_new)
    print("OK: Updated newConversation with warning system")
else:
    print("SKIP: newConversation pattern not found")

write('app/src/main/java/com/pocketagent/ui/chat/ChatViewModel.kt', content)

# ============================================================
# 3. ChatScreen: Smart auto-scroll + collapse tool calls + new chat warning
# ============================================================
content = read('app/src/main/java/com/pocketagent/ui/chat/ChatScreen.kt')

# Add smart scroll tracking — stop auto-scrolling when user scrolls up
if 'userScrolledUp' not in content:
    content = content.replace(
        'val listState = rememberLazyListState()',
        '''val listState = rememberLazyListState()
    var userScrolledUp by remember { mutableStateOf(false) }

    // Track if user scrolled up — stop auto-scroll if so
    LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) {
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        val totalItems = listState.layoutInfo.totalItemsCount
        userScrolledUp = totalItems > 0 && lastVisible < totalItems - 2
    }'''
    )
    print("OK: Added smart scroll tracking")

# Fix auto-scroll — only scroll if user hasn't scrolled up
old_scroll = '''    // Auto-scroll to bottom on new messages
    LaunchedEffect(state.messages.size, state.streamingContent, state.streamingToolCalls.size) {
        val target = state.messages.size + (if (state.streamingContent.isNotEmpty() || state.streamingToolCalls.isNotEmpty()) 1 else 0) - 1
        if (target >= 0) {
            listState.animateScrollToItem(target.coerceAtLeast(0))
        }
    }'''

new_scroll = '''    // Smart auto-scroll — only scroll if user hasn't scrolled up
    LaunchedEffect(state.messages.size, state.streamingContent, state.streamingToolCalls.size) {
        if (!userScrolledUp) {
            val target = state.messages.size + (if (state.streamingContent.isNotEmpty() || state.streamingToolCalls.isNotEmpty()) 1 else 0) - 1
            if (target >= 0) {
                listState.animateScrollToItem(target.coerceAtLeast(0))
            }
        }
    }'''

if old_scroll in content:
    content = content.replace(old_scroll, new_scroll)
    print("OK: Fixed auto-scroll to be smart")
else:
    print("SKIP: Auto-scroll pattern not found")

# Fix tool call cards — auto-collapse after completion
# The ToolCallCard already auto-collapses when isRunning=false via:
# var expanded by remember(toolName, arguments, result, isRunning) { mutableStateOf(isRunning) }
# But we should make the summary even more minimal

# Add new chat warning dialog
if 'showNewChatWarning' not in content:
    # Find the end of the Box composable and add the dialog before it
    content = content.replace(
        '''        // Error banner
        AnimatedVisibility(''',
        '''        // New chat warning dialog
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

        // Error banner
        AnimatedVisibility('''
    )
    print("OK: Added new chat warning dialog")

# Update the new chat button to use requestNewChat
content = content.replace(
    'viewModel.newConversation()\n                    onNewConversation()',
    'viewModel.requestNewChat()\n                    if (!state.isAgentRunning) onNewConversation()'
)
print("OK: New chat button now uses requestNewChat")

write('app/src/main/java/com/pocketagent/ui/chat/ChatScreen.kt', content)

# ============================================================
# 4. BashTool: Reduce max output for token efficiency
# ============================================================
content = read('app/src/main/java/com/pocketagent/agent/tools/BashTool.kt')
# Reduce from 256KB to 30KB for token efficiency
content = content.replace('maxOutputBytes: Int = 256_000', 'maxOutputBytes: Int = 30_000')
write('app/src/main/java/com/pocketagent/agent/tools/BashTool.kt', content)
print("OK: Reduced bash output from 256KB to 30KB for token efficiency")

# ============================================================
# 5. ShellExecutor: Reduce max output
# ============================================================
content = read('app/src/main/java/com/pocketagent/sandbox/ShellExecutor.kt')
content = content.replace('maxOutputBytes: Int = 256_000', 'maxOutputBytes: Int = 30_000')
write('app/src/main/java/com/pocketagent/sandbox/ShellExecutor.kt', content)
print("OK: Reduced ShellExecutor output from 256KB to 30KB")

# ============================================================
# 6. AgentLoop: Better system prompt with efficiency guidance
# ============================================================
content = read('app/src/main/java/com/pocketagent/agent/AgentLoop.kt')

old_prompt_start = 'const val DEFAULT_SYSTEM_PROMPT = """You are PocketAgent'
old_prompt_end = 'The user can see every tool call you make. Be transparent."""'

# Find and replace the entire system prompt
idx_start = content.find(old_prompt_start)
idx_end = content.find(old_prompt_end) + len(old_prompt_end)

if idx_start >= 0 and idx_end > idx_start:
    new_prompt = '''const val DEFAULT_SYSTEM_PROMPT = """You are PocketAgent, an AI agent on the user's Android phone with a full Linux environment.

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
- /tmp issues → use $TMPDIR or ~/tmp instead

You have TOTAL FREEDOM. Create, delete, install, build anything.
The user sees every tool call. Be transparent but concise."""'''

    content = content[:idx_start] + new_prompt + content[idx_end:]
    print("OK: Updated system prompt with efficiency guidance")
else:
    print("SKIP: System prompt not found")

write('app/src/main/java/com/pocketagent/agent/AgentLoop.kt', content)

# ============================================================
# 7. Bump version
# ============================================================
content = read('app/build.gradle.kts')
content = content.replace('versionCode = 17', 'versionCode = 18')
content = content.replace('versionName = "1.6.0"', 'versionName = "1.7.0"')
write('app/build.gradle.kts', content)
print("OK: Version bumped to 1.7.0")

print("\n=== ALL v1.7.0 FIXES APPLIED ===")
