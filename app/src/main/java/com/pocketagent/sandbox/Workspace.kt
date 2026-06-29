package com.pocketagent.sandbox

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The agent's "computer" — a private filesystem inside the app's data dir.
 *
 * Layout:
 *   /data/data/com.pocketagent/files/workspace/   <- agent's $HOME
 *     ├── projects/   (default workspace)
 *     ├── tmp/        (scratch)
 *     ├── downloads/  (output artifacts)
 *     └── .bashrc     (init script)
 *
 * All agent file operations are confined to this directory via PathGuard.
 *
 * CRITICAL: homeDir is canonicalized because Android's /data/data/ is a
 * symlink to /data/user/0/. If we don't canonicalize, PathGuard's
 * startsWith check fails because the paths are on different roots.
 */
@Singleton
class Workspace @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val homeDir: File = File(context.filesDir, "workspace").apply {
        if (!exists()) mkdirs()
    }.canonicalFile

    val projectsDir: File = File(homeDir, "projects").apply { if (!exists()) mkdirs() }
    val tmpDir: File = File(homeDir, "tmp").apply { if (!exists()) mkdirs() }
    val downloadsDir: File = File(homeDir, "downloads").apply { if (!exists()) mkdirs() }

    init {
        // Create initial .bashrc
        val bashrc = File(homeDir, ".bashrc")
        if (!bashrc.exists()) {
            bashrc.writeText(
                """
                # PocketAgent bashrc
                export PS1='pocketagent ❯ '
                export PATH="${'$'}PATH:${'$'}HOME/.local/bin"
                export TERM=xterm-256color
                export HOME="${'$'}HOME"
                alias ll='ls -la'
                alias la='ls -A'
                alias ..='cd ..'
                alias ...='cd ../..'
                cd ~/projects 2>/dev/null || cd ~
                """.trimIndent()
            )
        }
    }

    /** Resolve a path inside the workspace, enforcing it stays inside. */
    fun resolve(relativePath: String): File {
        return PathGuard.resolveSafe(homeDir, relativePath)
    }

    /** Total bytes used in the workspace. */
    fun usedBytes(): Long {
        return homeDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** Quota check — returns true if adding [bytes] would exceed quota. */
    fun wouldExceedQuota(bytes: Long, quotaMb: Int): Boolean {
        return (usedBytes() + bytes) > quotaMb * 1024L * 1024L
    }
}
