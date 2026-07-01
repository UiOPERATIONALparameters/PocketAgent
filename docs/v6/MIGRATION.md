# Migrating from v5 to v6

## What changed

v6 is a **major architectural overhaul**. The entire `sandbox/` package (1,185 lines of `NativeEnvironmentManager` + 193 lines of `ShellExecutor` + `PathGuard` + `Workspace`) has been deleted. Replaced by a ~250-line `TermuxBridge` that talks to a Python daemon running in your real Termux.

### Breaking changes

1. **`applicationId` is back to `com.pocketagent`** — coexists with real Termux now
2. **The v5 bundled Linux environment is unused** — your AI now uses your real Termux
3. **New setup required** — you must install Termux + the daemon

### What stays

- Your conversations (DB schema migrated v2 → v3 automatically)
- Your provider config and API key
- Your settings (system prompt, max iterations, etc.)

## Migration steps

### Step 1: Uninstall v5 (recommended)

Uninstall the old PocketAgent. Don't worry — your conversations are in the DB, but if you uninstall, they're lost. **If you want to keep them, skip to Step 2.**

### Step 2: Install v6

Download the latest v6 APK from [Releases](https://github.com/UiOPERATIONALparameters/PocketAgent/releases) and install it.

If you didn't uninstall v5 first, v6 installs side-by-side (different `applicationId` now). You can later uninstall v5 from Settings → Apps.

### Step 3: Set up Termux

Follow the [Setup Guide](SETUP.md). Quick version:

1. Install Termux from F-Droid
2. In Termux: `curl -sL https://raw.githubusercontent.com/UiOPERATIONALparameters/PocketAgent/v6-termux-bridge/termux-daemon/install.sh | bash`
3. Copy the token
4. In PocketAgent → Settings → Termux → paste the token → Save → Reconnect

### Step 4: Clean up v5's mess (optional)

If you had v5 installed, the v5 Linux environment is at `/data/data/com.termux/files/usr/`. This is unused by v6.

To clean it up:

1. Uninstall v5 from Settings → Apps
2. The `/data/data/com.termux/` directory is now owned by your real Termux (or gone if you didn't have Termux installed before)

If you have Termux installed and want to reset it:

```bash
# In Termux
rm -rf /data/data/com.termux/files/usr/*
# Then reinstall Termux packages
pkg update
pkg install python nodejs git
```

### Step 5: Verify

1. Open PocketAgent v6
2. Check Settings → Termux shows "Connected to Termux"
3. In chat, ask the agent: `pkg list-installed | head -20`
4. You should see your installed Termux packages

## What if I have issues?

1. Check the [Setup Guide troubleshooting section](SETUP.md#troubleshooting)
2. Check the daemon is running in Termux: `pgrep -f daemon.py`
3. Check the token matches: `cat ~/.pocketagent/token`
4. File an issue at [GitHub Issues](https://github.com/UiOPERATIONALparameters/PocketAgent/issues)

## Why was v5 broken?

v5 tried to BE Termux instead of using Termux. It:

- Hijacked `applicationId = "com.termux"` (couldn't coexist with real Termux)
- Bundled a 40MB Termux bootstrap in the APK
- Had a 1,185-line `NativeEnvironmentManager` that:
  - Downloaded and extracted the bootstrap zip
  - Patched every hardcoded `/data/data/com.termux/files/usr/` path
  - Created `.so` symlinks
  - Created `/usr/bin/env` wrapper
  - Created `apt`/`dpkg`/`pkg` wrapper scripts
  - Juggled `LD_PRELOAD` with `libtermux-exec.so`
  - Then had to `env -u LD_PRELOAD` to undo it for dpkg maintainer scripts (seccomp fix)
  - Faked dpkg status entries (which broke dependency resolution)
  - Removed in v5.0 because it was so broken
- 98 commits, ~80% are "Fix ..."
- Three commits were literally `Fix missing closing quote`, `Fix unescaped quotes`, `Remove extra closing brace`

v6 deletes all of that and replaces it with ~250 lines of HTTP/JSON that talks to your real Termux.

## Will my conversations survive?

Yes, if you install v6 over v5 (don't uninstall first). The DB schema is migrated from v2 to v3 automatically. The migration adds four new columns to the `conversations` table (for subagent support): `parentId`, `isSubagent`, `status`, `summary`.

If you uninstall v5 first, the DB is deleted and your conversations are lost.

## Will my Termux setup be affected?

No. v6 doesn't touch your Termux. The only thing it adds is a daemon process (when running) and files in `~/.pocketagent/`. Your existing Termux packages, $PATH, git config, ssh keys are all untouched.
