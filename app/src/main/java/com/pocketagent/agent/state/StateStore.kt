package com.pocketagent.agent.state

import com.pocketagent.bridge.TermuxBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent agent state, stored as files in ~/.pocketagent/ on the Termux side.
 *
 * This is the z.ai "worklog.md + scratchpad.md" pattern:
 *   - worklog.md   — append-only log of every task the agent has done
 *   - scratchpad.md — running notes the agent is encouraged to update
 *   - todos.json   — current task list, survives app restarts
 *
 * These files are injected into every system prompt as "Current State",
 * so the agent boots up aware of what's been done before.
 *
 * Stored on the Termux side (not app-side) so they survive app reinstalls.
 */
@Singleton
class StateStore @Inject constructor(
    private val bridge: TermuxBridge
) {
    companion object {
        const val WORKLOG_PATH = "~/.pocketagent/worklog.md"
        const val SCRATCHPAD_PATH = "~/.pocketagent/scratchpad.md"
        const val TODOS_PATH = "~/.pocketagent/todos.json"
        const val STATE_DIR = "~/.pocketagent"
    }

    /**
     * Initialize the state directory and files.
     * Called once on first connection.
     */
    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        // mkdir
        bridge.mkdir(STATE_DIR)
        // Create empty worklog if missing
        val worklog = bridge.readFile(WORKLOG_PATH)
        if (worklog.isFailure || worklog.getOrNull()?.content.isNullOrEmpty()) {
            bridge.writeFile(WORKLOG_PATH, "# PocketAgent Worklog\n\nThis file is a persistent log of everything the agent has done.\nIt is injected into every new conversation as context.\nAppend new entries at the bottom.\n\n")
        }
        // Create empty scratchpad if missing
        val scratch = bridge.readFile(SCRATCHPAD_PATH)
        if (scratch.isFailure || scratch.getOrNull()?.content.isNullOrEmpty()) {
            bridge.writeFile(SCRATCHPAD_PATH, "# PocketAgent Scratchpad\n\nRunning notes for the current agent session.\nUpdate this whenever you learn something important:\n- file locations\n- decisions made\n- things tried that didn't work\n- next steps\n\n")
        }
        // Create empty todos if missing
        val todos = bridge.readFile(TODOS_PATH)
        if (todos.isFailure || todos.getOrNull()?.content.isNullOrEmpty()) {
            bridge.writeFile(TODOS_PATH, "[]")
        }
        Result.success(Unit)
    }

    /** Read the current scratchpad (for system prompt injection). */
    suspend fun getScratchpad(): String = withContext(Dispatchers.IO) {
        bridge.readFile(SCRATCHPAD_PATH).getOrNull()?.content ?: ""
    }

    /** Overwrite the scratchpad with new content. */
    suspend fun setScratchpad(content: String): Result<Unit> = withContext(Dispatchers.IO) {
        bridge.writeFile(SCRATCHPAD_PATH, content).map { }
    }

    /** Read the worklog (for system prompt injection — last 4KB only). */
    suspend fun getWorklogTail(maxBytes: Int = 4096): String = withContext(Dispatchers.IO) {
        val full = bridge.readFile(WORKLOG_PATH).getOrNull()?.content ?: ""
        if (full.length <= maxBytes) full else full.takeLast(maxBytes)
    }

    /** Append a new entry to the worklog. */
    suspend fun appendWorklog(entry: WorklogEntry): Result<Unit> = withContext(Dispatchers.IO) {
        val block = buildString {
            append("\n---\n")
            append("## ${entry.title}\n")
            append("Date: ${entry.timestamp}\n")
            if (entry.taskId.isNotEmpty()) append("Task ID: ${entry.taskId}\n")
            if (entry.agent.isNotEmpty()) append("Agent: ${entry.agent}\n")
            append("\n")
            append(entry.body)
            append("\n")
        }
        // Append by reading + writing (daemon doesn't have an append endpoint)
        val current = bridge.readFile(WORKLOG_PATH).getOrNull()?.content ?: ""
        bridge.writeFile(WORKLOG_PATH, current + block).map { }
    }

    /** Get the full state snapshot for system prompt injection. */
    suspend fun getStateSnapshot(): String = withContext(Dispatchers.IO) {
        val scratch = getScratchpad()
        val worklogTail = getWorklogTail()
        buildString {
            if (scratch.isNotBlank()) {
                append("## Scratchpad (running notes)\n```\n")
                append(scratch.take(2000))
                append("\n```\n\n")
            }
            if (worklogTail.isNotBlank()) {
                append("## Recent Worklog (last tasks)\n```\n")
                append(worklogTail)
                append("\n```\n")
            }
        }
    }

    data class WorklogEntry(
        val title: String,
        val body: String,
        val timestamp: String = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()),
        val taskId: String = "",
        val agent: String = "main"
    )
}
