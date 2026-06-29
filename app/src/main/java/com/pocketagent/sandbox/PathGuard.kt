package com.pocketagent.sandbox

import java.io.File
import java.io.IOException

/**
 * Enforces that all file operations stay inside the agent's home directory.
 * Prevents path traversal attacks (../../etc/passwd) and absolute path escapes.
 *
 * CRITICAL: Uses canonical paths because Android's /data/data/ is a symlink
 * to /data/user/0/. Non-canonical comparison would fail.
 */
object PathGuard {
    /**
     * Resolve [relativePath] inside [home], throwing if the result escapes.
     *
     * [home] MUST be canonical (call .canonicalFile before passing).
     */
    fun resolveSafe(home: File, relativePath: String): File {
        // Strip leading slashes — agent paths are always relative to home
        val cleaned = relativePath.trim().removePrefix("/").removePrefix("./")

        // Block path traversal
        if (cleaned.contains("..")) {
            throw SecurityException("Path traversal not allowed: $relativePath")
        }

        val resolved = File(home, cleaned).canonicalFile
        val canonicalHome = home.canonicalFile

        val homePath = canonicalHome.absolutePath
        val resolvedPath = resolved.absolutePath

        // Check that resolved is either home itself or a DIRECT CHILD
        // (using separator to prevent /workspace_evil matching /workspace)
        val isInside = resolvedPath == homePath ||
            resolvedPath.startsWith(homePath + File.separator)

        if (!isInside) {
            throw SecurityException("Path escapes workspace: $relativePath")
        }
        return resolved
    }

    fun isInside(home: File, target: File): Boolean {
        val canonicalHome = home.canonicalFile
        val canonicalTarget = target.canonicalFile
        val homePath = canonicalHome.absolutePath
        val targetPath = canonicalTarget.absolutePath
        return targetPath == homePath ||
            targetPath.startsWith(homePath + File.separator)
    }

    fun ensureParentExists(file: File) {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            if (!parent.mkdirs()) {
                throw IOException("Failed to create parent directory: ${parent.absolutePath}")
            }
        }
    }
}
