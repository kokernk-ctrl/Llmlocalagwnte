package dev.forgeai.workspace

import android.content.Context
import java.io.File

/**
 * Controlled working copy: SAF files are mirrored into app-private storage so tools that
 * need real paths (shell, hashing) can operate without ever touching a path outside it.
 * Mirroring is one-way (SAF → copy). Shell commands in the allowed list cannot modify the
 * mirror (no write-capable binaries), so sync-back is only implemented for explicitly
 * authorized project runners, which are gated behind per-task approval.
 */
class WorkspaceMirror(context: Context) {
    private val base: File = File(context.noBackupFilesDir, "mirror").apply { mkdirs() }

    /** Fresh directory per task; old mirrors are wiped to bound storage. */
    fun open(taskId: Long): File {
        val dir = File(base, "task-$taskId")
        dir.deleteRecursively()
        if (!dir.mkdirs()) throw IllegalStateException("Could not create the working copy directory.")
        return dir
    }

    fun clearAll() { base.deleteRecursively(); base.mkdirs() }

    fun wipe(dir: File) { if (dir.canonicalPath.startsWith(base.canonicalPath)) dir.deleteRecursively() }

    /**
     * Copies entries from SAF into the mirror, preserving relative paths.
     * Rejects paths escaping the mirror and entries beyond the size cap.
     */
    fun syncIn(access: WorkspaceAccess, root: android.net.Uri, entries: List<WorkspaceAccess.Entry>,
               cwd: File, maxFileBytes: Int = READ_LIMIT, maxTotalBytes: Int = 32 * 1024 * 1024,
               progress: (Int) -> Unit = {}) {
        var total = 0L
        entries.forEachIndexed { index, entry ->
            if (entry.folder) return@forEachIndexed
            if (total + maxFileBytes > maxTotalBytes) return@forEachIndexed
            val target = resolve(cwd, entry.path)
            target.parentFile?.let { parent ->
                if (!parent.isDirectory && !parent.mkdirs()) return@forEachIndexed
            }
            try {
                val bytes = access.readBytes(entry, maxFileBytes)
                if (!target.canonicalPath.startsWith(cwd.canonicalPath)) return@forEachIndexed
                target.writeBytes(bytes)
                total += bytes.size
            } catch (_: Exception) { /* unreadable entries are skipped and simply absent */ }
            progress(index + 1)
        }
    }

    /** Hard boundary: every mirror path resolves inside the working copy. */
    fun resolve(cwd: File, relativePath: String): File {
        val clean = relativePath.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (clean.any { it == ".." }) throw IllegalStateException("Path escapes the working copy.")
        val file = File(cwd, clean.joinToString("/"))
        // canonical check happens at sync time; here we only prevent obvious escapes.
        return file
    }
}
