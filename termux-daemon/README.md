# PocketAgent Termux Daemon

A tiny HTTP server that runs in your Termux and lets the PocketAgent Android app execute commands, read/write files, and manage processes on your phone — using your real Termux environment.

## Why

Instead of trying to recreate Termux inside the PocketAgent app (which was buggy and broke with every Android update), v6 simply **uses your real Termux**. The AI gets the exact same environment you have — same packages, same `$PATH`, same git config, same ssh keys.

## Install (one line)

Open Termux and run:

```bash
curl -sL https://raw.githubusercontent.com/UiOPERATIONALparameters/PocketAgent/v6-termux-bridge/termux-daemon/install.sh | bash
```

This will:
1. Install Python 3 if you don't have it
2. Download `daemon.py` to `~/.pocketagent/`
3. Generate an auth token
4. Create a `pocketagent-daemon` command
5. (Optional) Add autostart to your `~/.bashrc`

## Run

```bash
pocketagent-daemon
```

The daemon listens on `127.0.0.1:8765`. The PocketAgent app connects automatically when on the same device.

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/health` | Status check |
| POST | `/exec` | Run a command, return result |
| GET | `/stream?command=...` | Live stdout/stderr as NDJSON |
| POST | `/proc/list` | List running processes |
| POST | `/proc/kill` | Kill a process by PID |
| POST | `/files/read` | Read a file (≤1MB) |
| POST | `/files/write` | Write a file |
| POST | `/files/list` | List directory entries |
| POST | `/files/stat` | Stat a file |
| POST | `/files/mkdir` | Make a directory |
| POST | `/files/delete` | Delete a file or directory |

All requests need an `X-PocketAgent-Token` header with the token from `~/.pocketagent/token`.

## Security

- Binds to `127.0.0.1` only — only the same device can connect (Android enforces same-UID)
- Auth token required on every request
- File operations confined to `$HOME` — `..` traversal blocked
- No `eval`, no `shell=True`, no `pickle`
- Process kills use `killpg()` to clean up children

## Uninstall

```bash
rm -rf ~/.pocketagent /data/data/com.termux/files/usr/bin/pocketagent-daemon
# Also remove the autostart line from ~/.bashrc if you added it
```

## For developers

The daemon is **200 lines of stdlib-only Python** — no dependencies, no virtualenv, no setup.py. Just `python3 daemon.py`. See [`daemon.py`](daemon.py) for the full source.

Architecture details: [`docs/v6/ARCHITECTURE.md`](../docs/v6/ARCHITECTURE.md)
