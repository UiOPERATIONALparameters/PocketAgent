# PocketAgent v6 Setup Guide

## Prerequisites

- Android phone running Android 8.0 (API 26) or higher
- Termux installed from F-Droid (NOT Play Store)
- An OpenAI-compatible LLM API key (OpenAI, OpenRouter, z.ai, DeepSeek, Groq, etc.)

## Step 1: Install Termux

1. Open [F-Droid](https://f-droid.org/en/packages/com.termux/) on your phone
2. Tap "Install" for Termux
3. Open Termux once to initialize it

> **Why F-Droid?** The Play Store version of Termux is deprecated and won't work. F-Droid has the latest version.

## Step 2: Install the PocketAgent daemon

In Termux, run:

```bash
curl -sL https://raw.githubusercontent.com/UiOPERATIONALparameters/PocketAgent/v6-termux-bridge/termux-daemon/install.sh | bash
```

This will:
1. Install Python 3 if you don't have it
2. Download `daemon.py` to `~/.pocketagent/`
3. Generate an auth token at `~/.pocketagent/token`
4. Create a `pocketagent-daemon` command
5. (Optional) Add autostart to your `~/.bashrc`

When prompted for autostart, type `Y` (recommended).

The installer prints a token at the end — **copy it**. You'll paste it into the PocketAgent app.

## Step 3: Start the daemon

If you said yes to autostart, the daemon is already running. Otherwise:

```bash
pocketagent-daemon
```

You should see:

```
╔══════════════════════════════════════════════╗
║  PocketAgent Daemon v6.0.0                  ║
║  Listening on http://127.0.0.1:8765         ║
╚══════════════════════════════════════════════╝
```

Leave Termux running (you can press Home — the daemon keeps running in the background).

## Step 4: Install PocketAgent APK

1. Download the latest APK from [Releases](https://github.com/UiOPERATIONALparameters/PocketAgent/releases)
2. Open the APK file on your phone
3. If prompted, allow "Install unknown apps" for your file manager
4. Tap **Install**

## Step 5: Onboarding

1. Open PocketAgent
2. Tap **Get Started**
3. Pick a provider preset (OpenAI, OpenRouter, z.ai, DeepSeek, Groq, Ollama, Custom)
4. Enter your API key
5. Tap **Connect** — the app fetches your gateway's model list
6. Pick a model
7. Tap **Finish**

## Step 6: Connect to Termux

1. Open Settings (gear icon in top-right)
2. Scroll to the **Termux** section
3. Paste the token from Step 2
4. Tap **Save**
5. Tap **Reconnect**
6. You should see "Connected to Termux" with the daemon version and your username

## Step 7: Start chatting

Go back to the chat screen and ask the agent anything. Try:

- `pkg install python && python3 -c "print('hello from PocketAgent')"`
- `Build a simple HTML website and serve it on port 8080`
- `What files are in my home directory?`

The agent has **total freedom** — it can install packages, write files, run scripts, build APKs, anything you can do in Termux.

## Troubleshooting

### "Termux not connected"

1. Open Termux
2. Check the daemon is running: `pgrep -f daemon.py` should return a PID
3. If not, start it: `pocketagent-daemon`
4. In PocketAgent, tap Reconnect

### "Unauthorized" error

The token doesn't match. Re-copy it from Termux:

```bash
cat ~/.pocketagent/token
```

Then paste into PocketAgent Settings → Termux → Token.

### "Connection refused"

The daemon isn't running. Start it:

```bash
pocketagent-daemon
```

### Daemon dies when Termux is backgrounded

Termux has a setting "Acquire wake lock" — tap it in the Termux notification. This prevents Android from killing Termux in the background.

Also enable autostart:

```bash
# Check if autostart is in your .bashrc
grep pocketagent ~/.bashrc
```

If not, re-run the installer:

```bash
curl -sL https://raw.githubusercontent.com/UiOPERATIONALparameters/PocketAgent/v6-termux-bridge/termux-daemon/install.sh | bash
```

### Agent can't find a command

The AI uses your real Termux. If a command isn't installed, tell it to install it:

> `pkg install python nodejs git gcc ffmpeg`

### Want to stop the daemon

```bash
pkill -f daemon.py
```

### Want to uninstall the daemon

```bash
rm -rf ~/.pocketagent /data/data/com.termux/files/usr/bin/pocketagent-daemon
# Also remove the autostart line from ~/.bashrc if you added it
```

## Tips

- **Install common packages up front**: `pkg install python nodejs git gcc make curl wget ripgrep`
- **Use ripgrep**: `pkg install ripgrep` — the grep tool uses it if available (much faster)
- **Set up ssh keys**: The AI can use your existing ssh keys for git operations
- **Customize your .bashrc**: The AI inherits your aliases and PATH

## Security

- The daemon binds to `127.0.0.1` only — only the same device can connect (Android enforces same-UID access)
- Auth token required on every request
- File operations confined to `$HOME` — `..` traversal blocked
- No `eval`, no `shell=True`, no `pickle`
- API key stored via Android Keystore (AES-256-GCM, hardware-backed where available)
- App never logs or transmits API keys except to your chosen LLM gateway
- `android:allowBackup="false"` — no app data in cloud backups
