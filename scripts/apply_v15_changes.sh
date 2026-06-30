#!/usr/bin/env bash
set -e
cd /home/z/my-project/PocketAgent

# 1. Fix createSoSymlinks — create ALL intermediate symlinks
python3 << 'PYEOF'
with open('app/src/main/java/com/pocketagent/sandbox/BootstrapInstaller.kt', 'r') as f:
    content = f.read()
old = '''    private fun createSoSymlinks(libDir: File) {
        if (!libDir.exists()) return

        libDir.listFiles { f -> f.name.endsWith(".so") || f.name.contains(".so.") }?.forEach { file ->
            val name = file.name
            if (java.nio.file.Files.isSymbolicLink(file.toPath())) return@forEach
            val soIdx = name.indexOf(".so")
            if (soIdx < 0) return@forEach
            val baseName = name.substring(0, soIdx + 3)
            val versionPart = name.substring(soIdx + 3)
            if (versionPart.isNotEmpty()) {
                val parts = versionPart.split(".").filter { it.isNotEmpty() }
                if (parts.isNotEmpty()) {
                    val soname = "$baseName.${parts[0]}"
                    val sonameFile = File(libDir, soname)
                    if (!sonameFile.exists()) {
                        try { java.nio.file.Files.createSymbolicLink(sonameFile.toPath(), file.toPath()) }
                        catch (_: Exception) { try { sonameFile.copyFrom(file) } catch (_: Exception) {} }
                    }
                }
            }
            val devLink = File(libDir, baseName)
            if (!devLink.exists()) {
                try { java.nio.file.Files.createSymbolicLink(devLink.toPath(), file.toPath()) }
                catch (_: Exception) { try { devLink.copyFrom(file) } catch (_: Exception) {} }
            }
        }
    }'''
new = '''    private fun createSoSymlinks(libDir: File) {
        if (!libDir.exists()) return
        val realFiles = libDir.listFiles { f -> f.isFile && !java.nio.file.Files.isSymbolicLink(f.toPath()) && (f.name.endsWith(".so") || f.name.contains(".so.")) } ?: return
        realFiles.forEach { file ->
            val name = file.name
            val soIdx = name.indexOf(".so")
            if (soIdx < 0) return@forEach
            val baseName = name.substring(0, soIdx + 3)
            val versionPart = name.substring(soIdx + 3)
            if (versionPart.isNotEmpty()) {
                val parts = versionPart.split(".").filter { it.isNotEmpty() }
                for (i in parts.indices) {
                    val symlinkName = baseName + "." + parts.subList(0, i + 1).joinToString(".")
                    val symlinkFile = File(libDir, symlinkName)
                    if (symlinkFile.name != name && !symlinkFile.exists()) {
                        try { java.nio.file.Files.createSymbolicLink(symlinkFile.toPath(), file.toPath()) }
                        catch (_: Exception) { try { symlinkFile.copyFrom(file) } catch (_: Exception) {} }
                    }
                }
            }
            val devLink = File(libDir, baseName)
            if (!devLink.exists()) {
                try { java.nio.file.Files.createSymbolicLink(devLink.toPath(), file.toPath()) }
                catch (_: Exception) { try { devLink.copyFrom(file) } catch (_: Exception) {} }
            }
        }
    }'''
content = content.replace(old, new)
with open('app/src/main/java/com/pocketagent/sandbox/BootstrapInstaller.kt', 'w') as f:
    f.write(content)
print("OK: createSoSymlinks fixed")
PYEOF

# 2. Fix lib/ permissions
python3 << 'PYEOF'
with open('app/src/main/java/com/pocketagent/sandbox/BootstrapInstaller.kt', 'r') as f:
    content = f.read()
old = '''            // Also set +x on .so files (some are loaded as executables)
            if (libDir.exists()) {
                libDir.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        file.setExecutable(true, true)
                        file.setReadable(true, true)
                    }
                }
            }'''
new = '''            // CRITICAL: Set executable on ALL files in lib/ AND subdirectories
            if (libDir.exists()) {
                libDir.walkTopDown().forEach { file ->
                    if (file.isFile) {
                        file.setExecutable(true, true)
                        file.setReadable(true, true)
                    }
                }
            }'''
content = content.replace(old, new)
with open('app/src/main/java/com/pocketagent/sandbox/BootstrapInstaller.kt', 'w') as f:
    f.write(content)
print("OK: lib/ permissions fixed")
PYEOF

# 3. Fix ShellExecutor
python3 << 'PYEOF'
with open('app/src/main/java/com/pocketagent/sandbox/ShellExecutor.kt', 'r') as f:
    content = f.read()
content = content.replace(
    'val termuxExec = File(libDir, "libtermux-exec.so")',
    'val termuxExec = findTermuxExec(libDir)'
)
content = content.replace(
    'if (termuxExec.exists()) {',
    'if (termuxExec != null && termuxExec.exists()) {'
)
content = content.replace(
    '    private fun readStream(',
    '    private fun findTermuxExec(libDir: File): File? {\n        if (!libDir.exists()) return null\n        val candidates = libDir.listFiles { f -> f.isFile && f.name.startsWith("libtermux-exec") } ?: return null\n        return candidates.minByOrNull { it.name.length }\n    }\n\n    private fun readStream('
)
# Fix bash invocation
content = content.replace(
    "listOf(bootstrapPath, \"--login\", \"-c\", command)",
    "listOf(bootstrapPath, \"--noprofile\", \"--norc\", \"-c\", \"source \\\"\" + workspace.homeDir.absolutePath + \"/.profile\\\" 2>/dev/null\\n\" + command)"
)
# Add SSL/APT env vars
content = content.replace(
    'env["COLORTERM"] = "truecolor"\n        } else {',
    'env["COLORTERM"] = "truecolor"\n            val certDir = File(bootstrapInstaller.usrDir, "etc/ssl/certs")\n            val caBundle = File(certDir, "ca-certificates.crt")\n            if (caBundle.exists()) {\n                env["CURL_CA_BUNDLE"] = caBundle.absolutePath\n                env["SSL_CERT_FILE"] = caBundle.absolutePath\n                env["REQUESTS_CA_BUNDLE"] = caBundle.absolutePath\n                env["GIT_SSL_CAINFO"] = caBundle.absolutePath\n            }\n            val aptConf = File(bootstrapInstaller.usrDir, "etc/apt/apt.conf")\n            if (aptConf.exists()) env["APT_CONFIG"] = aptConf.absolutePath\n            val dpkgDir = File(bootstrapInstaller.usrDir, "var/lib/dpkg")\n            if (dpkgDir.exists()) env["DPKG_ADMINDIR"] = dpkgDir.absolutePath\n        } else {'
)
with open('app/src/main/java/com/pocketagent/sandbox/ShellExecutor.kt', 'w') as f:
    f.write(content)
print("OK: ShellExecutor fixed")
PYEOF

# 4. Fix bash timeout
sed -i 's/coerceIn(1, 120)/coerceIn(1, 600)/g; s/coerceIn(1, userMaxTimeout.coerceAtLeast(120))/coerceIn(1, 600)/g' app/src/main/java/com/pocketagent/agent/tools/BashTool.kt
echo "OK: bash timeout fixed"

echo "Done with code fixes"
