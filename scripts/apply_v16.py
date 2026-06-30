#!/usr/bin/env python3
"""Apply all v1.6.0 fixes to PocketAgent source code."""

import os
os.chdir('/home/z/my-project/PocketAgent')

# ============================================================
# 1. Fix createSoSymlinks — create ALL intermediate symlinks
# ============================================================
with open('app/src/main/java/com/pocketagent/sandbox/BootstrapInstaller.kt', 'r') as f:
    content = f.read()

old_symlinks = '''    private fun createSoSymlinks(libDir: File) {
        if (!libDir.exists()) return

        libDir.listFiles { f -> f.name.endsWith(".so") || f.name.contains(".so.") }?.forEach { file ->
            val name = file.name
            // Skip if it's already a symlink
            if (java.nio.file.Files.isSymbolicLink(file.toPath())) return@forEach

            // Parse version from name: libfoo.so.8.0 -> libfoo.so.8, libfoo.so
            val soIdx = name.indexOf(".so")
            if (soIdx < 0) return@forEach

            val baseName = name.substring(0, soIdx + 3)  // libfoo.so
            val versionPart = name.substring(soIdx + 3)  // .8.0 or empty

            if (versionPart.isNotEmpty()) {
                // Create libfoo.so.8 symlink (first version number)
                val parts = versionPart.split(".").filter { it.isNotEmpty() }
                if (parts.isNotEmpty()) {
                    val soname = "$baseName.${parts[0]}"  // libfoo.so.8
                    val sonameFile = File(libDir, soname)
                    if (!sonameFile.exists()) {
                        try {
                            java.nio.file.Files.createSymbolicLink(sonameFile.toPath(), file.toPath())
                        } catch (_: Exception) {
                            // Fallback: copy if symlink fails (some filesystems don't support symlinks)
                            try { sonameFile.copyFrom(file) } catch (_: Exception) {}
                        }
                    }
                }
            }

            // Create libfoo.so symlink (development link) if it doesn't exist
            val devLink = File(libDir, baseName)
            if (!devLink.exists()) {
                try {
                    java.nio.file.Files.createSymbolicLink(devLink.toPath(), file.toPath())
                } catch (_: Exception) {
                    try { devLink.copyFrom(file) } catch (_: Exception) {}
                }
            }
        }
    }'''

new_symlinks = '''    private fun createSoSymlinks(libDir: File) {
        if (!libDir.exists()) return

        // Get real files (not symlinks)
        val realFiles = libDir.listFiles { f ->
            f.isFile && !java.nio.file.Files.isSymbolicLink(f.toPath()) &&
            (f.name.endsWith(".so") || f.name.contains(".so."))
        } ?: return

        realFiles.forEach { file ->
            val name = file.name
            val soIdx = name.indexOf(".so")
            if (soIdx < 0) return@forEach

            val baseName = name.substring(0, soIdx + 3)  // libfoo.so
            val versionPart = name.substring(soIdx + 3)  // .1.0.8 or empty

            if (versionPart.isNotEmpty()) {
                // Parse version components: .1.0.8 -> [1, 0, 8]
                val parts = versionPart.split(".").filter { it.isNotEmpty() }

                // Create ALL intermediate symlinks:
                //   libfoo.so.1.0.8 (real file)
                //   libfoo.so.1.0   -> libfoo.so.1.0.8
                //   libfoo.so.1     -> libfoo.so.1.0.8
                for (i in parts.indices) {
                    val symlinkName = baseName + "." + parts.subList(0, i + 1).joinToString(".")
                    val symlinkFile = File(libDir, symlinkName)
                    if (symlinkFile.name == name) continue  // Don't link to itself
                    if (!symlinkFile.exists()) {
                        try {
                            java.nio.file.Files.createSymbolicLink(symlinkFile.toPath(), file.toPath())
                        } catch (_: Exception) {
                            try { symlinkFile.copyFrom(file) } catch (_: Exception) {}
                        }
                    }
                }
            }

            // Create libfoo.so (development link)
            val devLink = File(libDir, baseName)
            if (!devLink.exists()) {
                try {
                    java.nio.file.Files.createSymbolicLink(devLink.toPath(), file.toPath())
                } catch (_: Exception) {
                    try { devLink.copyFrom(file) } catch (_: Exception) {}
                }
            }
        }
    }'''

if old_symlinks in content:
    content = content.replace(old_symlinks, new_symlinks)
    print("OK: createSoSymlinks fixed — creates ALL intermediate symlinks")
else:
    print("SKIP: createSoSymlinks pattern not found (may already be fixed)")

# ============================================================
# 2. Add createShSymlink function and call it during install
# ============================================================
# Find where patchShebangs() is called and add createShSymlink() after it
if 'createShSymlink()' not in content:
    content = content.replace(
        '            patchShebangs()\n',
        '            patchShebangs()\n\n            // CRITICAL: Create sh symlink (bash -> sh)\n            // Many scripts have #!/path/to/sh shebangs\n            createShSymlink()\n'
    )
    print("OK: Added createShSymlink() call")

# Add the createShSymlink function before createSoSymlinks
if 'private fun createShSymlink' not in content:
    content = content.replace(
        '    private fun createSoSymlinks(',
        '''    /**
     * CRITICAL: Create sh symlink. Many scripts use #!/path/to/sh but
     * only bash exists in the bootstrap. Create sh -> bash symlink.
     */
    private fun createShSymlink() {
        val bashFile = File(binDir, "bash")
        val shFile = File(binDir, "sh")
        if (bashFile.exists() && !shFile.exists()) {
            try {
                java.nio.file.Files.createSymbolicLink(shFile.toPath(), bashFile.toPath())
                shFile.setExecutable(true, true)
            } catch (_: Exception) {
                // Fallback: copy bash to sh
                try { shFile.copyFrom(bashFile) } catch (_: Exception) {}
            }
        }
    }

    private fun createSoSymlinks('''
    )
    print("OK: Added createShSymlink function")

# ============================================================
# 3. Fix lib/ permissions — use walkTopDown for ALL files
# ============================================================
old_perms = '''            // Also set +x on .so files (some are loaded as executables)
            if (libDir.exists()) {
                libDir.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        file.setExecutable(true, true)
                        file.setReadable(true, true)
                    }
                }
            }'''

new_perms = '''            // CRITICAL: Set executable on ALL files in lib/ AND subdirectories
            // This includes lib/apt/methods/http, https, copy, file, gpgv, rsh, store
            // lib/git-core/*, lib/*.so, etc.
            if (libDir.exists()) {
                libDir.walkTopDown().forEach { file ->
                    if (file.isFile) {
                        file.setExecutable(true, true)
                        file.setReadable(true, true)
                    }
                }
            }'''

if old_perms in content:
    content = content.replace(old_perms, new_perms)
    print("OK: lib/ permissions fixed — walkTopDown for ALL files")
else:
    print("SKIP: lib/ permissions pattern not found")

with open('app/src/main/java/com/pocketagent/sandbox/BootstrapInstaller.kt', 'w') as f:
    f.write(content)

# ============================================================
# 4. Fix ShellExecutor — findTermuxExec + --noprofile + SSL/APT
# ============================================================
with open('app/src/main/java/com/pocketagent/sandbox/ShellExecutor.kt', 'r') as f:
    content = f.read()

# Fix termuxExec to use findTermuxExec
if 'val termuxExec = File(libDir, "libtermux-exec.so")' in content:
    content = content.replace(
        'val termuxExec = File(libDir, "libtermux-exec.so")',
        'val termuxExec = findTermuxExec(libDir)'
    )
    print("OK: ShellExecutor uses findTermuxExec")

# Fix the termuxExec.exists() check
if 'if (termuxExec.exists()) {' in content:
    content = content.replace(
        'if (termuxExec.exists()) {',
        'if (termuxExec != null && termuxExec.exists()) {'
    )
    print("OK: ShellExecutor termuxExec null check fixed")

# Fix bash invocation — use --noprofile --norc
if '--login' in content and '--noprofile' not in content:
    content = content.replace(
        "listOf(bootstrapPath, \"--login\", \"-c\", command)",
        "listOf(bootstrapPath, \"--noprofile\", \"--norc\", \"-c\", \"source \\\"\" + workspace.homeDir.absolutePath + \"/.profile\\\" 2>/dev/null\\n\" + command)"
    )
    print("OK: ShellExecutor uses --noprofile --norc")

# Add findTermuxExec function
if 'private fun findTermuxExec' not in content:
    content = content.replace(
        '    private fun readStream(',
        '''    private fun findTermuxExec(libDir: File): File? {
        if (!libDir.exists()) return null
        val candidates = libDir.listFiles { f -> f.isFile && f.name.startsWith("libtermux-exec") } ?: return null
        return candidates.minByOrNull { it.name.length }
    }

    private fun readStream('''
    )
    print("OK: Added findTermuxExec function")

# Add SSL/APT env vars after COLORTERM
if 'CURL_CA_BUNDLE' not in content:
    content = content.replace(
        'env["COLORTERM"] = "truecolor"\n        } else {',
        '''env["COLORTERM"] = "truecolor"

            // SSL certificate paths for curl/wget/git
            val certDir = File(bootstrapInstaller.usrDir, "etc/ssl/certs")
            val caBundle = File(certDir, "ca-certificates.crt")
            if (caBundle.exists()) {
                env["CURL_CA_BUNDLE"] = caBundle.absolutePath
                env["SSL_CERT_FILE"] = caBundle.absolutePath
                env["REQUESTS_CA_BUNDLE"] = caBundle.absolutePath
                env["GIT_SSL_CAINFO"] = caBundle.absolutePath
            }

            // APT config
            val aptConf = File(bootstrapInstaller.usrDir, "etc/apt/apt.conf")
            if (aptConf.exists()) env["APT_CONFIG"] = aptConf.absolutePath
            val dpkgDir = File(bootstrapInstaller.usrDir, "var/lib/dpkg")
            if (dpkgDir.exists()) env["DPKG_ADMINDIR"] = dpkgDir.absolutePath
        } else {'''
    )
    print("OK: Added SSL/APT env vars")

with open('app/src/main/java/com/pocketagent/sandbox/ShellExecutor.kt', 'w') as f:
    f.write(content)

# ============================================================
# 5. Fix bash timeout to 600s
# ============================================================
with open('app/src/main/java/com/pocketagent/agent/tools/BashTool.kt', 'r') as f:
    content = f.read()
if 'coerceIn(1, userMaxTimeout.coerceAtLeast(120))' in content:
    content = content.replace(
        'coerceIn(1, userMaxTimeout.coerceAtLeast(120))',
        'coerceIn(1, 600)'
    )
    print("OK: Bash timeout fixed to 600s")
elif 'coerceIn(1, 120)' in content:
    content = content.replace('coerceIn(1, 120)', 'coerceIn(1, 600)')
    print("OK: Bash timeout fixed to 600s")
else:
    print("SKIP: Bash timeout already fixed or pattern not found")
with open('app/src/main/java/com/pocketagent/agent/tools/BashTool.kt', 'w') as f:
    f.write(content)

# ============================================================
# 6. Bump version to 1.6.0
# ============================================================
with open('app/build.gradle.kts', 'r') as f:
    content = f.read()
content = content.replace('versionCode = 16', 'versionCode = 17')
content = content.replace('versionName = "1.5.0"', 'versionName = "1.6.0"')
with open('app/build.gradle.kts', 'w') as f:
    f.write(content)
print("OK: Version bumped to 1.6.0")

print("\n=== ALL FIXES APPLIED ===")
