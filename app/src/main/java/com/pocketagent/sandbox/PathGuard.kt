package com.pocketagent.sandbox

import java.io.File
import java.io.IOException

/**
 * Enforces that all file operations stay inside the agent's home directory.
 * Prevents path traversal attacks (../../etc/passwd) and absolute path escapes.
 */
object PathGuard {
    /**
     * Resolve [relativePath] inside [home], throwing if the result escapes.
     */
    fun resolveSafe(home: File, relativePath: String): File {
        // Strip leading slashes — agent paths are always relative to home
        val cleaned = relativePath.trim().removePrefix("/")
        val resolved = File(home, cleaned).canonicalFile
        val canonicalHome = home.canonicalFile
        if (!resolved.path.startsWith(canonicalHome.path)) {
            throw SecurityException("Path escapes workspace: $relativePath")
        }
        return resolved
    }

    fun isInside(home: File, target: File): Boolean {
        val canonicalHome = home.canonicalFile
        val canonicalTarget = target.canonicalFile
        return canonicalTarget.path.startsWith(canonicalHome.path)
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
