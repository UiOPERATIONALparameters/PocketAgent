# PocketAgent v6

> **Your AI has its own real Linux computer on your phone — using your real Termux.**

PocketAgent is a BYOK (Bring Your Own Key) AI agent for Android. It gives the AI **total freedom**: bash, file I/O, web access, package install, build APKs — driven by any OpenAI-compatible LLM.

Inspired by z.ai agentic mode, Claude Code, and Kimi Computer — your phone becomes the AI's computer.

## What's new in v6

**Major architectural overhaul.** v6 abandons the broken "be Termux" approach (1,185 lines patching hardcoded ELF paths, fighting Android's seccomp and W^X) and instead **uses your real Termux** via a tiny HTTP daemon.

### The shift in one sentence
v1–v5 tried to BE Termux. v6 USES Termux.

| Aspect | v5 (broken) | v6 (clean) |
|---|---|---|
| Linux environment | Bundled bootstrap, hijacked `com.termux` package ID | Uses your real Termux via HTTP daemon |
| Code complexity | 1,185-line `NativeEnvironmentManager` | ~250-line `TermuxBridge` |
| Bug surface | 98 commits of fixes | No path patching, no LD_PRELOAD, no seccomp workarounds |
| Coexists with Termux | ❌ | ✅ |
| AI's environment | Fresh rootfs | Your actual Termux — same packages, $PATH, ssh keys |
| Updates | Re-ship bootstrap on every fix | `pkg upgrade` in Termux |

## Features

### Agent capabilities
- **17 tools**: bash, file_read, file_write, file_list, str_replace, grep, glob, web_fetch, web_search, web_reader, todo, serve_http, install_apk, load_skill, **compact** (NEW), **task** (NEW — real subagents), **state** (NEW — persistent scratchpad)
- **Real Termux execution** — AI gets your exact environment (python, node, gcc, git, gradle, java, ffmpeg, anything)
- **Subagent manager** — `task` tool spawns real subagents with their own context window (z.ai Task tool pattern)
- **Context compaction** — auto-compacts at 70% of context window, with manual `compact` tool
- **Persistent state** — `~/.pocketagent/scratchpad.md` + `worklog.md` survive across sessions
- **Structured tool errors** — every error returns a `suggestion` field with actionable advice
- **BYOK** — any OpenAI-compatible gateway (OpenAI, OpenRouter, z.ai, DeepSeek, Groq, Ollama, vLLM, custom)

### UI
- **Monochrome / e-ink aesthetic** — calm, focused, paper-like, high-contrast text
- **Auto-collapsed tool calls** — clean chat surface, expand on tap
- **Streaming bash logs** — live terminal view for long-running commands
- **Three-pane layout** on tablets (chat | workspace | task tree)
- **Dark mode** with hand-tuned palette

## Install

### 1. Install Termux (one-time, 2 minutes)

Install Termux from [F-Droid](https://f-droid.org/en/packages/com.termux/) (NOT Play Store — that version is deprecated).

### 2. Install the PocketAgent daemon (one-time, 30 seconds)

Open Termux and run:

```bash
curl -sL https://raw.githubusercontent.com/UiOPERATIONALparameters/PocketAgent/v6-termux-bridge/termux-daemon/install.sh | bash
```

This:
- Installs Python 3 if you don't have it
- Downloads `daemon.py` to `~/.pocketagent/`
- Generates an auth token
- Creates a `pocketagent-daemon` command
- (Optional) Adds autostart to your `~/.bashrc`

The installer prints a token — copy it.

### 3. Install PocketAgent APK

Download the latest APK from [Releases](../../releases), install it, and:

1. Open PocketAgent
2. Go through onboarding (pick provider, enter API key, pick model)
3. Open Settings → Termux → paste the token
4. Tap "Reconnect"
5. Start chatting with your AI

## Architecture

```
┌──────────────────────────────────────────────────────┐
│  PocketAgent App (com.pocketagent, ~6MB)             │
│   • UI (Compose, monochrome/eink theme)              │
│   • AgentLoop (ReAct, streaming, cancellable)        │
│   • ContextManager (auto-compact at 70%)             │
│   • SubagentManager (fork context, isolate)          │
│   • StateStore (worklog.md, scratchpad.md)           │
│   • 17 Tools (bash, file_*, web_*, task, compact, …) │
│   • TermuxBridge (HTTP client → localhost:8765)      │
└──────────────────┬───────────────────────────────────┘
                   │ HTTP to 127.0.0.1:8765
┌──────────────────▼───────────────────────────────────┐
│  User's Termux (F-Droid install)                     │
│   • pocketagent-daemon (~250 lines Python, stdlib)   │
│   • Real bash, python, node, git, gradle, java, …    │
│   • ~/.pocketagent/ (worklog, scratchpad, todos)     │
│   • User's $PATH, ssh keys, git config               │
└──────────────────────────────────────────────────────┘
```

### Why this architecture

1. **Use Termux, don't be Termux** — Termux has 9 years of accumulated Android workarounds. Don't reinvent them.
2. **Real environment** — AI gets your actual Termux packages, not a fresh rootfs
3. **No OS fighting** — no path patching, no LD_PRELOAD, no seccomp workarounds
4. **Coexists with real Termux** — your existing Termux setup is untouched
5. **Updates flow through `pkg upgrade`** — never re-ship a rootfs

### Design principles

1. **Use Termux, don't be Termux** — execution lives in Termux, app is a chat UI + agent loop
2. **Structured tool errors** — every error returns a `suggestion` field (Anthropic's "Writing Effective Tools" principle)
3. **Context management is non-optional** — auto-compact at 70%, manual `compact` tool, persistent scratchpad
4. **Subagents are real** — `task` tool forks a real conversation in the DB, returns only the summary
5. **Persistent state across sessions** — worklog.md + scratchpad.md injected into every system prompt
6. **Recovery > perfection** — structured errors let the AI recover instead of dying
7. **Monochrome/eink UI** — calm, focused, paper-like

## Build

```bash
./gradlew assembleDebug       # debug APK
./gradlew assembleRelease     # release APK
```

Requirements: JDK 17, Android SDK 35.

The APK is built automatically via GitHub Actions on every push and every tag. Tag pushes create GitHub Releases.

## Tech stack

- Kotlin 2.1
- Jetpack Compose (Material 3 + custom monochrome design system)
- Hilt for DI
- Room for chat history (with migrations)
- OkHttp + kotlinx.serialization
- EncryptedSharedPreferences for API keys + Termux token
- Foreground Service for background execution
- Python 3 stdlib for the Termux daemon (no dependencies)

## Migration from v5

If you had v5 installed:
- The v5 Linux environment at `/data/data/com.termux/files/usr/` is unused by v6
- Uninstall v5 first (or install v6 side-by-side — different `applicationId` now)
- Conversations are migrated automatically (DB schema v2 → v3)

## Documentation

- [Architecture (v6)](docs/v6/ARCHITECTURE.md)
- [Setup guide](docs/v6/SETUP.md)
- [Migration from v5](docs/v6/MIGRATION.md)
- [Termux daemon README](termux-daemon/README.md)

## License

MIT — see [LICENSE](LICENSE).

## Acknowledgments

- Agent loop pattern inspired by Anthropic's Claude Code and Computer Use
- Workspace state injection pattern from z.ai's ZCode agentic mode
- Subagent forking pattern from Claude Code's Task tool
- Termux for 9 years of Android Linux workarounds
- "Writing Effective Tools for Agents" — Anthropic engineering blog
