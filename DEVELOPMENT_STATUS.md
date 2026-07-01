# PocketAgent Development Status — v5.1.0

## Current Architecture (v5.1.0)

### Core Approach
- **applicationId = "com.termux"** — makes app data directory `/data/data/com.termux/files/` which matches Termux binary hardcoded paths exactly
- **Native Termux bootstrap** — downloads Termux bootstrap zip (~40MB), extracts natively (no proot, no container)
- **targetSdk = 28** — exempts app from Android 10+ W^X (Write XOR Execute) restriction (same as Termux)

### Linux Environment Setup (NativeEnvironmentManager.kt)
1. Download bootstrap zip from GitHub releases
2. Extract using ZipFile (random access, not ZipInputStream — prevents ELF corruption)
3. Patch hardcoded Termux paths in scripts (patchTermuxPaths + patchAllScripts)
4. Create .so symlinks (createSoSymlinks)
5. Create sh → bash symlink (createShSymlink)
6. Create /usr/bin/env wrapper (createUsrBinSymlinks)
7. Create HTTPS apt method symlink (createHttpsAptMethod)
8. Setup SSL certificates from Android system CA store (setupSslCerts)
9. Copy GPG signing keys (setupGpgKeys)
10. Configure apt.conf with correct architecture (aarch64, NOT arm64)
11. Create wrapper scripts for apt/dpkg/pkg (createCommandWrappers)
12. Create .profile and .bashrc with all environment variables
13. Run apt-get update to download package index

### Key Design Decisions

#### Why applicationId = "com.termux"
Termux binaries have `/data/data/com.termux/files/usr/` compiled into the ELF binary.
There is no way to override this without root. By using `applicationId = "com.termux"`,
the app's data directory IS `/data/data/com.termux/files/`, so all hardcoded paths match.
Trade-off: Can't coexist with real Termux app (same package name).

#### Why targetSdk = 28
Android 10+ (API 29+) enforces W^X (Write XOR Execute) which blocks executing binaries
from writable directories like `/data/data/<pkg>/files/`. targetSdk 28 exempts the app.
Termux uses the same approach. Since we sideload (not Play Store), this is safe.

#### Why no proot
proot uses ptrace(2) to intercept syscalls. Android 14+ blocks ptrace via seccomp
filter (signal 31 / SIGSYS). This is a kernel-level restriction — no fix possible
without root. Native Termux binaries use Android's Bionic libc with /system/bin/linker64
and run natively without proot.

#### Seccomp (Signal 31) Fix
dpkg/apt wrappers use `env -u LD_PRELOAD` to completely strip LD_PRELOAD from the
environment before calling the real binary. This prevents termux-exec from being
loaded in maintainer scripts (postinst/preinst), which triggers seccomp.

#### Path Doubling Fix
Removed `--instdir` and `root` from dpkg config. Termux .deb packages contain files
at absolute paths (e.g., `data/data/com.termux/files/usr/bin/python`). When dpkg
extracts them without `--instdir`, they go to the correct absolute path. Setting
`--instdir` caused doubled paths: `/data/data/com.termux/files/usr/data/data/com.termux/files/usr/bin/python`.

### Tools (15 total)
bash, file_read, file_write, file_list, str_replace, grep, glob,
web_fetch, web_search, web_reader, load_skill, install_apk,
todo, serve_http, spawn_subagent

### Skills (11 total)
build-website, build-apk, research-topic, write-script, make-chart,
debug-code, summarize-document, convert-file, data-analysis,
file-management, install-java

### UI Features
- Tool call grouping (⚡ collapsed summary cards, expandable)
- Terminal-style output (black bg, green monospace text)
- TodoListCard (green checkmarks, count badge, collapsible)
- Advanced Settings section (skill toggles, collapsed by default)
- SmoothTextField (no input lag — local state + focus-loss sync)
- Smart auto-scroll (only on new messages, not streaming)

### Known Issues (v5.1.0)
1. Some packages' postinst scripts may still trigger seccomp if they use
   blocked syscalls directly (not via LD_PRELOAD). Workaround: install-java
   skill shows how to manually extract with dpkg-deb --extract.
2. /tmp is not writable — use ~/tmp or $TMPDIR instead.
3. Can't coexist with real Termux app (same packageId).

### Build
GitHub Actions workflow builds APK on every push to main and every tag.
Tag pushes create GitHub Releases with signed APK + SHA256 checksum.
Debug builds for branch pushes, release builds for tags.

### Version History
- v1.x-v2.x: Termux bootstrap + proot (broken on Android 14+)
- v3.x: Native Termux (no proot), ELF corruption fixes, LD_PRELOAD
- v4.x: applicationId=com.termux, seccomp fix, tool grouping, todo UI
- v5.0: Removed broken pre-install, just apt-get update
- v5.1: Fixed path doubling (--instdir removed), env -u LD_PRELOAD
