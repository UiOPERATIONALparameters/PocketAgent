# PocketAgent v6.0 — Architecture

> **The shift:** v1–v5 tried to BE Termux (1,185-line `NativeEnvironmentManager`, hijacked `applicationId`, `LD_PRELOAD` juggling, seccomp death-spiral). v6 USES Termux — a 150-line HTTP daemon runs in the user's real Termux, the app talks to it over localhost.

## Why v6

v5.1 hit a structural dead-end:
- `applicationId = "com.termux"` → can't coexist with real Termux
- 1,185-line installer patching hardcoded ELF paths → every fix creates the next bug
- `LD_PRELOAD` juggling → causes seccomp (signal 31) in dpkg maintainer scripts
- `env -u LD_PRELOAD` wrapper to undo the previous fix → cascading complexity
- 98 commits, ~80% are "Fix ..."

The user's own observation unlocks the right architecture: *"I can write scripts or code on Termux and that's easy, then why can't the AI have the same capabilities?"*

It can. The AI just needs to talk to Termux.

## Architecture

```
┌──────────────────────────────────────────────────────┐
│  PocketAgent App (com.pocketagent, ~6MB)             │
│                                                       │
│  ┌────────────────────────────────────────────────┐ │
│  │  UI Layer (Jetpack Compose, Material 3)         │ │
│  │   • Chat pane (streaming, auto-collapsed tools) │ │
│  │   • Workspace pane (file tree, diffs)           │ │
│  │   • Task pane (todos, subagent tree)            │ │
│  │   • Theme: monochrome/eink minimalist           │ │
│  └──────────────────┬─────────────────────────────┘ │
│                     │                                 │
│  ┌──────────────────▼─────────────────────────────┐ │
│  │  Agent Host                                     │ │
│  │   • AgentLoop (ReAct, stream, cancellable)      │ │
│  │   • ContextManager (token count, auto-compact)  │ │
│  │   • SubagentManager (fork context, isolate)     │ │
│  │   • StateStore (worklog.md, scratchpad, todos)  │ │
│  │   • ToolRouter (registry, schema, retries)      │ │
│  └──────────────────┬─────────────────────────────┘ │
│                     │                                 │
│  ┌──────────────────▼─────────────────────────────┐ │
│  │  Bridge Layer                                   │ │
│  │   • TermuxBridge (HTTP client → localhost:8765) │ │
│  │   • Connection state machine                    │ │
│  │   • Auto-reconnect via RUN_COMMAND intent       │ │
│  └──────────────────┬─────────────────────────────┘ │
└─────────────────────┼────────────────────────────────┘
                      │ HTTP/WS to 127.0.0.1:8765
                      │ (Android enforces same-UID)
┌─────────────────────▼────────────────────────────────┐
│  User's Termux (F-Droid install)                     │
│                                                       │
│  ┌────────────────────────────────────────────────┐ │
│  │  pocketagent-daemon (~200 lines Python)         │ │
│  │   • POST /exec      → run command               │ │
│  │   • WS   /stream    → live stdout/stderr tail   │ │
│  │   • POST /proc/list → running processes         │ │
│  │   • POST /proc/kill → cancel by pid             │ │
│  │   • GET  /health    → status check              │ │
│  │   • POST /files/read, /files/write              │ │
│  │   • POST /files/list, /files/stat               │ │
│  └────────────────────────────────────────────────┘ │
│                                                       │
│  Real Termux environment:                             │
│   • bash, python, node, gcc, git, gradle, java, …    │
│   • apt/pkg for installs                              │
│   • User's $PATH, git config, ssh keys                │
│   • User's installed packages                         │
│   • ~/.pocketagent/ (worklog, scratchpad, todos)     │
└──────────────────────────────────────────────────────┘
```

## Design Principles

### 1. Use Termux, don't be Termux
The app is a chat UI + agent loop. Execution lives in Termux. No sandbox, no bootstrap, no rootfs, no path patching.

### 2. Structured tool errors
Every tool returns JSON. Errors look like:
```json
{
  "error": "package 'foo' not found",
  "exit_code": 1,
  "stderr": "E: Unable to locate package foo",
  "suggestion": "Run `apt update` first, or check the package name with `apt search foo`."
}
```
Not raw stderr. This is what lets the AI recover instead of loop.

### 3. Context management is non-optional
- Token counting on every message (heuristic: chars/4, refined by usage data)
- Auto-compact at 70% of model's `max_input_tokens`
- Compact = LLM summarizes prior turns → `~/.pocketagent/scratchpad.md` → old messages marked "compacted" (kept in DB, not sent to LLM)
- Manual `compact(reason)` tool the AI can call when it notices itself losing track

### 4. Subagents are real
`task(description, prompt)` tool:
- Creates a new conversation in DB with `parent_id` link
- Subagent runs in its own coroutine with its own context window
- Subagent has read-only workspace access by default (configurable)
- Subagent's final summary is written to parent conversation as one message
- Parent's context is not polluted by subagent's tool calls

### 5. Persistent state across sessions
`~/.pocketagent/`:
- `worklog.md` — append-only log of every task the agent has done (the z.ai pattern)
- `scratchpad.md` — running notes the agent is encouraged to update
- `todos.json` — current task list, survives app restarts
- `processes.json` — registry of long-running processes
- `subagents/` — subagent conversation summaries

These are injected into every system prompt as "Current State" — the agent boots up aware.

### 6. Recovery > perfection
- Tool failures return structured errors with suggestions
- AgentLoop catches exceptions per-tool, doesn't kill the loop
- Max-iterations hit → emit "Type 'continue' to resume" and stop cleanly
- Stream network errors → 1 retry with backoff, then user-visible error
- Daemon disconnect → pause loop, show "Reconnect to Termux" button, resume on reconnect

### 7. UI: monochrome/eink minimalist
- Single-color palette (black/white/grays) with one accent
- Material 3 Expressive structure, desaturated tokens
- High contrast text, generous whitespace, no decoration
- Three-pane on tablets, single-pane with bottom-sheet on phones
- Tool calls auto-collapse 2s after completion
- Long-running bash (>2s) expands into live terminal view
- File diffs inline in chat
- Low-contrast "focus" mode (the eink feel)

## File structure (v6)

```
PocketAgent/
├── app/
│   ├── build.gradle.kts              (updated: applicationId=com.pocketagent, v6.0.0)
│   ├── src/main/
│   │   ├── AndroidManifest.xml       (updated: drop Termux perms, keep foreground svc)
│   │   ├── java/com/pocketagent/
│   │   │   ├── PocketAgentApp.kt     (unchanged)
│   │   │   ├── MainActivity.kt       (unchanged)
│   │   │   ├── bridge/
│   │   │   │   ├── TermuxBridge.kt   (NEW: HTTP client)
│   │   │   │   ├── BridgeState.kt    (NEW: connection state machine)
│   │   │   │   └── BridgeModels.kt   (NEW: request/response DTOs)
│   │   │   ├── agent/
│   │   │   │   ├── AgentLoop.kt      (REWRITTEN: context mgr, subagent mgr)
│   │   │   │   ├── ContextManager.kt (NEW)
│   │   │   │   ├── SubagentManager.kt (NEW)
│   │   │   │   ├── StateStore.kt     (NEW: worklog/scratchpad/todos)
│   │   │   │   └── tools/
│   │   │   │       ├── AgentTool.kt       (interface, structured errors)
│   │   │   │       ├── ToolRouter.kt      (updated)
│   │   │   │       ├── BashTool.kt        (uses TermuxBridge)
│   │   │   │       ├── FileTools.kt       (uses TermuxBridge)
│   │   │   │       ├── GrepTool.kt        (uses TermuxBridge)
│   │   │   │       ├── GlobTool.kt
│   │   │   │       ├── StrReplaceTool.kt
│   │   │   │       ├── WebFetchTool.kt
│   │   │   │       ├── WebSearchTool.kt
│   │   │   │       ├── WebReaderTool.kt
│   │   │   │       ├── TodoTool.kt
│   │   │   │       ├── CompactTool.kt    (NEW)
│   │   │   │       ├── TaskTool.kt       (NEW: real subagent)
│   │   │   │       ├── StateTool.kt      (NEW: save/load scratchpad)
│   │   │   │       ├── ServeHttpTool.kt
│   │   │   │       ├── InstallApkTool.kt
│   │   │   │       └── LoadSkillTool.kt
│   │   │   ├── llm/                       (unchanged from v5.1)
│   │   │   ├── design/
│   │   │   │   ├── Color.kt              (REWRITTEN: monochrome palette)
│   │   │   │   ├── Type.kt               (REWRITTEN: tighter type scale)
│   │   │   │   ├── Theme.kt              (REWRITTEN: eink aesthetic)
│   │   │   │   ├── Shape.kt
│   │   │   │   └── SoftShadow.kt
│   │   │   ├── storage/                   (unchanged DB schema, minor additions)
│   │   │   ├── service/
│   │   │   │   └── AgentForegroundService.kt  (updated status text)
│   │   │   └── ui/
│   │   │       ├── PocketApp.kt          (updated nav)
│   │   │       ├── RootViewModel.kt
│   │   │       ├── chat/
│   │   │       │   ├── ChatScreen.kt     (REWRITTEN: split into pieces)
│   │   │       │   ├── ChatViewModel.kt  (updated: subagent mgr, context mgr)
│   │   │       │   ├── MessageBubble.kt  (NEW)
│   │   │       │   ├── ToolCallCard.kt   (NEW: auto-collapse, status chips)
│   │   │       │   ├── StreamingTerminal.kt (NEW: live bash tail)
│   │   │       │   ├── TodoListCard.kt
│   │   │       │   ├── MarkdownText.kt
│   │   │       │   └── Composer.kt       (NEW: input + attachments)
│   │   │       ├── workspace/            (NEW)
│   │   │       │   ├── WorkspaceScreen.kt
│   │   │       │   ├── FileTree.kt
│   │   │       │   └── DiffView.kt
│   │   │       ├── tasks/                (NEW)
│   │   │       │   └── TaskTreeScreen.kt
│   │   │       ├── onboarding/           (updated: Termux setup flow)
│   │   │       │   ├── OnboardingScreen.kt
│   │   │       │   └── TermuxSetupScreen.kt  (NEW)
│   │   │       ├── settings/
│   │   │       │   ├── SettingsScreen.kt (REWRITTEN: split into pieces)
│   │   │       │   └── SettingsViewModel.kt
│   │   │       └── SmoothTextField.kt
│   │   └── res/
│   │       ├── values/
│   │       │   ├── themes.xml
│   │       │   ├── colors.xml           (updated: monochrome)
│   │       │   └── strings.xml
│   │       └── values-night/themes.xml
│   └── proguard-rules.pro
├── termux-daemon/                        (NEW)
│   ├── daemon.py                         (~200 lines, stdlib only)
│   ├── install.sh                        (one-line installer)
│   └── README.md
├── docs/
│   ├── v6/ARCHITECTURE.md                (this file)
│   ├── v6/SETUP.md                       (user setup guide)
│   └── v6/MIGRATION.md                   (v5 → v6 migration)
├── .github/workflows/
│   └── release.yml                       (updated: build APK on tag)
├── README.md                             (rewritten for v6)
└── LICENSE
```

## What gets deleted

| File | LOC | Why |
|---|---|---|
| `sandbox/NativeEnvironmentManager.kt` | 1,185 | Death-spiral. Replaced by TermuxBridge. |
| `sandbox/ShellExecutor.kt` | 193 | Replaced by TermuxBridge. |
| `sandbox/PathGuard.kt` | 62 | Path safety now lives in daemon. |
| `sandbox/Workspace.kt` | 72 | Workspace is now `~` in Termux. |
| **Total deleted** | **1,512** | |

## What gets added

| File | LOC est | Why |
|---|---|---|
| `bridge/TermuxBridge.kt` | ~200 | HTTP client to daemon |
| `bridge/BridgeState.kt` | ~80 | Connection state machine |
| `bridge/BridgeModels.kt` | ~60 | DTOs |
| `agent/ContextManager.kt` | ~180 | Token count + auto-compact |
| `agent/SubagentManager.kt` | ~150 | Fork conversations |
| `agent/StateStore.kt` | ~120 | worklog/scratchpad/todos |
| `agent/tools/CompactTool.kt` | ~80 | Manual compaction |
| `agent/tools/TaskTool.kt` | ~120 | Real subagent fork |
| `agent/tools/StateTool.kt` | ~80 | Save/load scratchpad |
| `ui/chat/MessageBubble.kt` | ~120 | Split ChatScreen |
| `ui/chat/ToolCallCard.kt` | ~150 | Auto-collapse, status chips |
| `ui/chat/StreamingTerminal.kt` | ~100 | Live bash tail |
| `ui/chat/Composer.kt` | ~80 | Input + attachments |
| `ui/workspace/WorkspaceScreen.kt` | ~200 | File tree + diffs |
| `ui/tasks/TaskTreeScreen.kt` | ~100 | Subagent tree |
| `ui/onboarding/TermuxSetupScreen.kt` | ~120 | Termux setup flow |
| `termux-daemon/daemon.py` | ~250 | HTTP daemon |
| `termux-daemon/install.sh` | ~50 | One-liner installer |
| **Total added** | **~2,200** | |

Net change: +~700 LOC, but complexity drops massively because the new code is straightforward HTTP/JSON, not Android-OS-fighting.

## Verification checklist (before tagging v6.0.0)

- [ ] `daemon.py` runs in Termux, listens on 127.0.0.1:8765
- [ ] `install.sh` one-liner works in fresh Termux
- [ ] App's `TermuxBridge` connects to daemon
- [ ] `bash` tool routes through daemon, returns structured JSON
- [ ] `pkg install python3` works end-to-end via agent
- [ ] `python3 -c "print('hi')"` works end-to-end via agent
- [ ] ContextManager auto-compacts at 70% of model context window
- [ ] `task` tool creates subagent conversation in DB
- [ ] `~/.pocketagent/worklog.md` is appended to after each task
- [ ] UI renders in monochrome/eink theme
- [ ] Tool calls auto-collapse 2s after completion
- [ ] Long bash commands stream output to UI
- [ ] Foreground service survives backgrounding
- [ ] `applicationId = "com.pocketagent"` — coexists with real Termux
- [ ] APK builds clean (debug + release)
- [ ] GitHub Actions builds APK on tag push
- [ ] README + SETUP docs accurate

## Release plan

1. All v6 work on `v6-termux-bridge` branch
2. Tag `v6.0.0` when verification checklist passes
3. GitHub Actions builds release APK, creates GitHub Release
4. Old `main` branch preserved as v5.1 frozen
5. After community validation, `v6-termux-bridge` becomes `main`
