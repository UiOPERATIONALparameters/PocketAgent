# PocketAgent

A BYOK (Bring Your Own Key) AI agent app for Android. The agent runs in a sandboxed Linux workspace on your phone with **total freedom** — bash, file I/O, web, package install, even proot-distro Debian/Ubuntu containers — driven by any OpenAI-compatible LLM provider.

Inspired by z.ai's ZCode agent mode and Kimi's Computer — your phone becomes the AI's computer.

## Status

**v2.0.0** — Major rewrite. 40+ bug fixes from v1.9.0 audit, proot-distro integration, install_apk tool, workspace state injection, dynamic system prompt, foreground service for Android 14+.

## What's new in v2.0.0

### Critical fixes
- **Multi-ABI bootstrap** — works on aarch64, armv7, x86_64, x86 (was hardcoded aarch64)
- **Dynamic apt architecture** — detected from device ABI (was hardcoded arm64)
- **proot-distro integration** — one-tap install of full Debian/Ubuntu for `apt install` access (ffmpeg, ImageMagick, LaTeX, anything)
- **install_apk tool** — agent can build APKs and the user installs them in one tap
- **POST_NOTIFICATIONS + FOREGROUND_SERVICE_DATA_SYNC** — works on Android 13/14/15+
- **REQUEST_INSTALL_PACKAGES** — so built APKs can actually be installed
- **Foreground service type** — agent survives backgrounding on Android 14+
- **Fixed GlobTool regex** — `**/*.kt` patterns now work correctly
- **Fixed FileListTool truncation** — was always reporting `truncated=false`
- **Fixed BashTool display** — exit code now visible in UI chip color
- **Fixed GrepTool** — can now grep dotfiles (.github/, .env); streams large files
- **Fixed WebFetchTool** — binary content handled safely
- **Fixed WebSearchTool** — "no results" no longer treated as error
- **Fixed UTF-8 truncation** — FileReadTool no longer splits multi-byte chars
- **Fixed forceReload race** — SettingsRepository
- **Fixed isStreaming flag** — auto-reset on app launch after crashes
- **Fixed smart-scroll off-by-one** — ChatScreen
- **Workspace quota from settings** — actually uses user's configured quota

### Architecture improvements
- **Dynamic system prompt** — tool list generated from `toolRouter.specs()`, no more hardcoded "9 tools"
- **Workspace state injection** — every prompt includes `pwd`, `ls ~/`, `git status` (ZCode-style)
- **maxIterations 30 → 50** — supports longer tasks
- **Token save mode redesigned** — no longer disables tools; just truncates results
- **Preserve assistant content** — text before tool calls now shown in UI
- **Temperature configurable** — per-conversation override
- **Stream retry logic** — 1 retry on transient network failures
- **Better stream error handling** — distinguishes user-cancel from network error

### UI/UX
- **Body font 14sp → 16sp** — accessibility win (WCAG)
- **Save to Downloads** — uses MediaStore, not share sheet
- **WELCOME step button works** — was a no-op since v0.x
- **Foreground service text from intent** — shows actual task status
- **Notification permission flow** — requests on first agent task on Android 13+

### Repo hygiene
- Removed `scripts/apply_v15_changes.sh`, `apply_v16.py`, `apply_v17.py` (one-shot patch scripts)
- Moved JSON research files to `docs/research/`
- Removed leaked `/home/z/my-project/...` paths from source comments
- Real DB migration for schema v2 (no more `fallbackToDestructiveMigration` data loss)

## Features

- **BYOK**: Connect any OpenAI-compatible gateway (OpenAI, Anthropic-via-proxy, OpenRouter, z.ai, DeepSeek, Groq, Together, Ollama, vLLM, LM Studio, etc.)
- **Agent loop**: Model can call 10 tools autonomously (bash, file_read, file_write, file_list, str_replace, grep, glob, web_fetch, web_search, install_apk)
- **Sandboxed Linux workspace**: All file operations confined to `/data/data/com.pocketagent/files/workspace/`
- **proot-distro**: Optional one-tap install of full Debian/Ubuntu for unlimited apt packages
- **Streaming chat**: Token-by-token streaming with reasoning content support (GLM, DeepSeek-R1, etc.)
- **Tool call cards**: Collapsible UI cards showing every tool call the agent makes
- **Workspace state injection**: Every prompt includes cwd, file tree summary, git status
- **Encrypted storage**: API keys stored via Android Keystore (AES-256-GCM, hardware-backed)
- **iOS-clean UI**: Design tokens extracted directly from Kimi's live DOM
- **Dark mode**: System-follow with hand-tuned dark palette
- **Foreground service**: Long agent tasks survive screen-off on Android 14+
- **No root required**: Uses Termux bootstrap + proot for full Linux user-land

## Install

1. Download the latest APK from [Releases](../../releases)
2. On your phone, open the APK file
3. If prompted, allow "Install unknown apps" for your file manager
4. Tap **Install**

Minimum: Android 8.0 (API 26) or higher.

## Setup

1. Open the app — you'll see the onboarding screen
2. Pick a provider preset (OpenAI, OpenRouter, z.ai, DeepSeek, Groq, Together, Ollama, Custom) or enter any OpenAI-compatible URL
3. Enter your API key (stored encrypted, never leaves your device except to your chosen gateway)
4. Tap **Connect** — the app fetches your gateway's model list
5. Pick a model
6. Optional: tap "Install Linux Environment" in Settings to enable proot-distro (Debian/Ubuntu) for full apt access
7. Start chatting — ask the agent to do anything

## Architecture

```
┌──────────────────────────────────────────────────────┐
│                   UI (Jetpack Compose)                │
│  chat / onboarding / settings / files                │
└────────────────────────┬─────────────────────────────┘
                         │
┌────────────────────────▼─────────────────────────────┐
│              Agent Host (AgentLoop.kt)                │
│  - prompt → LLM → tool_call → execute → feed back     │
│  - workspace state injection (pwd, ls, git)           │
│  - dynamic system prompt with tool catalog            │
│  - maxIterations=50, cancellable, resumable           │
└────────────────────────┬─────────────────────────────┘
                         │
         ┌───────────────┼───────────────┐
         ▼               ▼               ▼
┌──────────────┐ ┌──────────────┐ ┌──────────────┐
│   LLM (BYOK) │ │  10 Tools    │ │  Storage     │
│  OpenAI-compat│ │ bash, file_* │ │ Room + Encr  │
│  streaming    │ │ grep, glob   │ │   prefs      │
│  tool calling │ │ web_*, apk   │ │              │
└──────────────┘ └──────┬───────┘ └──────────────┘
                         │
┌────────────────────────▼─────────────────────────────┐
│            Sandbox (Termux bootstrap + proot)         │
│  /data/data/com.pocketagent/files/                   │
│    ├── workspace/   (agent's $HOME, sandboxed)       │
│    │   ├── projects/                                  │
│    │   ├── tmp/                                       │
│    │   ├── downloads/                                 │
│    │   └── .state/                                    │
│    ├── usr/         (Termux bootstrap: bash, python,  │
│    │                  node, git, curl, apt, etc.)     │
│    └── debian/      (optional proot-distro rootfs)   │
└──────────────────────────────────────────────────────┘
```

### Layers

- **UI** (`ui/`): Compose screens — chat, onboarding, settings, files
- **Design** (`design/`): Color/Type/Shape/Theme tokens (Kimi-inspired)
- **Agent** (`agent/`): AgentLoop (with workspace state injection), ToolRouter, 10 tool implementations
- **LLM** (`llm/`): Unified `LlmProvider` interface, OpenAI-compatible adapter with SSE streaming + retry logic
- **Sandbox** (`sandbox/`): Workspace, PathGuard, ShellExecutor, BootstrapInstaller (multi-ABI), ProotDistroManager
- **Storage** (`storage/`): Room database (with real migrations) + EncryptedSharedPreferences (API keys)

## Build

The APK is built automatically via GitHub Actions on every push to `v2.0` and every tag push.

To build locally:
```bash
./gradlew assembleDebug       # debug APK
./gradlew assembleRelease     # release APK
```

Requirements: JDK 17, Android SDK 35.

## Security

- API keys are stored using `EncryptedSharedPreferences` (AES-256-GCM)
- Master key is hardware-backed via Android Keystore where available
- App never logs or transmits API keys except to your chosen gateway
- `android:allowBackup="false"` — no app data included in cloud backups
- Workspace is in app-private storage — invisible to other apps
- PathGuard enforces that all file operations stay inside `~/`
- proot-distro containers live in app-private storage — fully isolated

## Tech Stack

- Kotlin 2.1
- Jetpack Compose (Material 3 + custom design system)
- Hilt for DI
- Room for chat history (with proper migrations)
- OkHttp + kotlinx.serialization for LLM API calls
- EncryptedSharedPreferences for API key storage
- Foreground Service + WakeLock for background execution
- Termux bootstrap (multi-ABI) + proot-distro for Linux user-land
- Min SDK 26, Target SDK 28 (W^X exemption for executing binaries from app-private storage; Termux uses the same approach)

## License

MIT — see [LICENSE](LICENSE).

## Acknowledgments

- UI design tokens extracted from [Kimi](https://kimi.com) (Moonshot AI) via live DOM inspection
- Agent loop pattern inspired by Anthropic's Computer Use and Claude Code
- Bash tool schema mirrors Anthropic's spec for zero-shot compatibility
- Workspace state injection pattern from z.ai's ZCode
- proot-distro from [Termux](https://github.com/termux/proot-distro)
