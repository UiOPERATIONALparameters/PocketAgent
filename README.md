# PocketAgent

A BYOK (Bring Your Own Key) AI agent app for Android. The agent runs in a sandboxed workspace on your phone and can execute bash commands, read/write files, and fetch the web — driven by any OpenAI-compatible LLM provider.

## Status

**v0.2.0** — Bug fixes + image attachments, file browser, editable system prompt, configurable timeouts.

## What's new in v0.2.0

### Bug fixes
- **Fixed**: API key disappearing from Settings after onboarding (race condition with `apply()` vs `load()` — switched to `commit()`)
- **Fixed**: "Expected URL scheme 'http' or 'https'" error when fetching models (Settings now loads persisted state correctly)
- **Fixed**: New chat not working (no longer navigates to fake URL — clears state in place)
- **Fixed**: Delete chat not working visually (click event bubbling fixed; clears state if deleting current conversation)
- **Fixed**: Tool call results not persisting (now properly query tool_runs table when displaying messages)
- **Fixed**: Onboarding not pre-filling existing provider info on re-open

### New features
- **Image attachments**: Tap the paperclip in the composer to attach images. Sent as base64 to vision-capable models (GPT-4o, Claude, Gemini, Kimi K2.6, Gemma 4, Qwen-VL, etc.)
- **File browser**: Tap the folder icon in the top bar to inspect the agent's workspace. Navigate directories, preview text files, delete files, see storage usage.
- **Editable system prompt**: Customize the agent's identity and behavior in Settings.
- **Configurable bash timeout**: Default 30s, adjustable 5-300s in Settings.
- **Configurable workspace quota**: Default 2GB, adjustable 100MB-10GB in Settings.
- **Improved streaming tool calls**: Tool calls now show "running…" state and update in-place when results arrive.

## Features

- **BYOK**: Connect any OpenAI-compatible gateway (OpenAI, Z.ai, OpenRouter, DeepSeek, Groq, Together, Ollama, vLLM, LM Studio, etc.)
- **Agent loop**: Model can call `bash`, `file_read`, `file_write`, `file_list`, `web_fetch` tools autonomously
- **Sandboxed workspace**: All file operations confined to `/data/data/com.pocketagent/files/workspace/`
- **Streaming chat**: Token-by-token streaming with reasoning content support (GLM, DeepSeek-R1, etc.)
- **Tool call cards**: Collapsible UI cards showing every tool call the agent makes
- **Encrypted storage**: API keys stored via Android Keystore (AES-256-GCM, hardware-backed)
- **iOS-clean UI**: Design tokens extracted directly from Kimi's live DOM — system fonts, 24dp rounded composer, soft two-layer shadows
- **Dark mode**: System-follow with hand-tuned dark palette
- **Foreground service**: Long agent tasks survive screen-off
- **No root required**: Uses Android's built-in `/system/bin/sh` (mksh)

## Install

1. Download the latest APK from [Releases](../../releases)
2. On your phone, open the APK file
3. If prompted, allow "Install unknown apps" for your file manager
4. Tap **Install**

Minimum: Android 8.0 (API 26) or higher.

## Setup

1. Open the app — you'll see the onboarding screen
2. Enter your gateway URL (e.g. `https://api.gateway.orgn.com/v1`) and API key
3. Tap **Connect** — the app fetches your gateway's model list
4. Pick a model (search the list, or just pick a default)
5. Start chatting — ask the agent to do anything

## Architecture

```
┌─────────────────────┐
│   Compose UI        │  ← iOS-clean, Kimi-style
│   (chat/onboard/    │
│    settings/files)  │
└──────────┬──────────┘
           │
┌──────────▼──────────┐
│   Agent Loop        │  ← LLM ↔ Tool calls
│   (AgentLoop.kt)    │
└──────────┬──────────┘
           │
   ┌───────┼───────┐
   ▼       ▼       ▼
┌──────┐ ┌─────┐ ┌──────┐
│ LLM  │ │Tools│ │Storage│
│ (BYOK)│ │     │ │(Room) │
└──┬───┘ └─┬───┘ └──────┘
   │       │
   ▼       ▼
   Your   Sandboxed
   gateway  workspace
           (~/)
```

### Layers

- **UI** (`ui/`): Compose screens — chat, onboarding, settings, files
- **Design** (`design/`): Color/Type/Shape/Theme tokens (extracted from Kimi's live DOM)
- **Agent** (`agent/`): AgentLoop, ToolRouter, individual tool implementations
- **LLM** (`llm/`): Unified `LlmProvider` interface, OpenAI-compatible adapter with SSE streaming + multimodal support
- **Sandbox** (`sandbox/`): Workspace, PathGuard, ShellExecutor
- **Storage** (`storage/`): Room database (chat history) + EncryptedSharedPreferences (API keys)

## Build

The APK is built automatically via GitHub Actions on every push and every tag push.

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

## Roadmap

- [ ] v0.3: Vendor Termux bootstrap for full Linux userland (apt install, python, node, ffmpeg)
- [ ] v0.3: Multiple conversations with proper sidebar management
- [ ] v0.3: Tool enable/disable toggles
- [ ] v0.4: Multi-agent orchestration (Kimi-style swarm)

## Tech Stack

- Kotlin 2.1
- Jetpack Compose (Material 3 + custom design system)
- Hilt for DI
- Room for chat history
- OkHttp + kotlinx.serialization for LLM API calls
- EncryptedSharedPreferences for API key storage
- Foreground Service + WakeLock for background execution
- Min SDK 26, Target SDK 35

## License

MIT — see [LICENSE](LICENSE).

## Acknowledgments

- UI design tokens extracted from [Kimi](https://kimi.com) (Moonshot AI) via live DOM inspection
- Agent loop pattern inspired by Anthropic's Computer Use and Claude Code
- Bash tool schema mirrors Anthropic's spec for zero-shot compatibility
