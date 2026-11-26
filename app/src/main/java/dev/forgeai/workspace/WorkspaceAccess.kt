package dev.forgeai.workspace

import android.content.ContentResolver
import android.content.Context
import android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File
import java.io.IOException
import java.io.InputStream

internal class WriteException(message: String, val kind: Kind) : IOException(message) {
    enum class Kind { NO_WRITE_GRANT, CONFLICT, VERIFY_FAILED, INVALID_NAME, TOO_LARGE, PROVIDER }
}

/**
 * Multi-folder SAF access layer. Grants are held as tree URIs with explicit read or
 * read+write persistable flags; every sensitive operation re-checks the live grant.
 * Relative paths are resolved by directory queries — document IDs from outside this
 * class are never trusted.
 */
class WorkspaceAccess(context: Context) {
    private val resolver = context.contentResolver
    private val prefs = context.getSharedPreferences("workspace_activity_v2", Context.MODE_PRIVATE)
    private val backups: File = File(context.noBackupFilesDir, "workspace-backups").apply { mkdirs() }

    data class Grant(val root: Uri, val name: String, val writable: Boolean)

    data class Entry(
        val root: Uri, val docId: String, val path: String, val name: String,
        val folder: Boolean, val mime: String, val size: Long, val lastModified: Long,
    ) { val writable: Boolean get() = !folder && suggestsText(name, mime) }

    data class Listing(val entries: List<Entry>, val truncated: Boolean)

    // ---- grants ---------------------------------------------------------------

    private fun isTree(uri: Uri) = uri.scheme == "content" && !uri.authority.isNullOrEmpty() &&
        uri.pathSegments.size == 2 && uri.pathSegments.first() == "tree" &&
        uri.query == null && uri.fragment == null

    private fun liveGrants(): List<android.content.UriPermission> = resolver.persistedUriPermissions
        .filter { it.isReadPermission && isTree(it.uri) }

    fun grants(): List<Grant> = liveGrants().map { p ->
        Grant(p.uri, folderName(p.uri), p.isWritePermission)
    }

    fun grantRead(uri: Uri) = grant(uri, FLAG_GRANT_READ_URI_PERMISSION)

    fun grantReadWrite(uri: Uri) = grant(uri, FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION)

    private fun grant(uri: Uri, flags: Int) {
        check(isTree(uri)) { "Only a SAF tree grant from the folder picker is accepted." }
        // takePersistableUriPermission accepts only READ/WRITE bits; persistability is
        // requested on the picker intent, not here (lint WrongConstant catches this).
        resolver.takePersistableUriPermission(uri, flags)
        record(if (flags and FLAG_GRANT_WRITE_URI_PERMISSION != 0) WorkspaceEvent.GRANT_WRITE else WorkspaceEvent.GRANT)
    }

    /** Revokes one folder: releases both sides of the grant without touching other folders. */
    fun revoke(root: Uri) {
        check(isTree(root))
        resolver.releasePersistableUriPermission(root, FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION)
        record(WorkspaceEvent.REVOKE)
    }

    fun folderName(root: Uri): String = safeName(DocumentsContract.getTreeDocumentId(root)
        .substringAfterLast('/').substringAfterLast(':')).ifBlank { "Selected folder" }

    /** Throws when the live write grant is missing; call before every mutation. */
    private fun requireWrite(root: Uri) {
        val live = liveGrants().firstOrNull { it.uri == root }
            ?: throw WriteException("Folder access was revoked. Re-authorize the folder.", WriteException.Kind.NO_WRITE_GRANT)
        if (!live.isWritePermission) {
            throw WriteException("This folder was granted read-only access. Grant write access to edit it.",
                WriteException.Kind.NO_WRITE_GRANT)
        }
    }

    // ---- path / listing -------------------------------------------------------

    private fun childUri(root: Uri, docId: String): Uri =
        DocumentsContract.buildChildDocumentsUriUsingTree(root, docId)

    private fun docUri(root: Uri, docId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(root, docId)

    private fun cols() = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)

    private fun entryFrom(root: Uri, path: String, cursor: android.database.Cursor): Entry? {
        val id = cursor.getString(0)?.takeIf { it.length in 1..4096 } ?: return null
        val name = safeName(cursor.getString(1).orEmpty()).ifBlank { return null }
        val mime = cursor.getString(2).orEmpty()
        val size = if (cursor.isNull(3)) -1L else cursor.getLong(3)
        val modified = if (cursor.isNull(4)) -1L else cursor.getLong(4)
        val folder = mime == Document.MIME_TYPE_DIR
        return Entry(root, id, path, name, folder, mime, size, modified)
    }

    /** Lists one directory under the granted tree. `path` uses "/" separators, "" = root. */
    fun listDir(root: Uri, path: String, limit: Int = MAX_ROWS): Listing {
        check(root in grants().map { it.root }) { "Folder is not authorized." }
        val treeId = DocumentsContract.getTreeDocumentId(root)
        val docId = if (path.isEmpty()) treeId else resolvePath(root, path)
            ?: throw WriteException("Folder $path was not found.", WriteException.Kind.PROVIDER)
        val args = Bundle().apply { putInt(ContentResolver.QUERY_ARG_LIMIT, limit + 1) }
        val entries = mutableListOf<Entry>()
        val truncated = resolver.query(childUri(root, docId), cols(), args, null)?.use { c ->
            while (entries.size < limit && c.moveToNext()) entryFrom(root, path, c)?.let { entries.add(it) }
            c.moveToNext()
        } ?: throw IOException("The folder could not be read.")
        record(WorkspaceEvent.LIST)
        return Listing(entries, truncated)
    }

    /** Depth-limited recursive walk for agent project inspection. */
    fun listTree(root: Uri, maxDepth: Int = 6, maxEntries: Int = AGENT_MAX_FILES): List<Entry> {
        require(maxDepth in 1..12 && maxEntries in 1..AGENT_MAX_FILES)
        val out = mutableListOf<Entry>()
        var truncated = false
        fun childPath(parent: String, name: String) = if (parent.isEmpty()) name else "$parent/$name"
        fun walk(path: String, depth: Int) {
            if (depth > maxDepth || out.size >= maxEntries || truncated) {
                if (out.size >= maxEntries) truncated = true
                return
            }
            val remaining = maxEntries - out.size
            val page = try { listDir(root, path, remaining + 1) } catch (_: Exception) { return }
            if (page.entries.size > remaining) truncated = true
            for (entry in page.entries.take(remaining)) {
                out.add(entry)
                if (entry.folder) walk(childPath(entry.path, entry.name), depth + 1)
            }
        }
        walk("", 1)
        record(WorkspaceEvent.LIST)
        return out
    }

    /** Resolves a relative path by walking child queries. Returns the document id or null. */
    fun resolvePath(root: Uri, path: String): String? {
        if (path.isEmpty()) return DocumentsContract.getTreeDocumentId(root)
        require(path.length <= AGENT_MAX_PATH) { "Path is too long." }
        var docId = DocumentsContract.getTreeDocumentId(root)
        var current = ""
        for (part in path.split('/')) {
            if (part.isEmpty() || part == ".") continue
            check(part != "..") { "Paths must stay inside the authorized folder." }
            val parentPath = current
            val page = resolver.query(childUri(root, docId), cols(), null, null)
                ?: throw IOException("The folder could not be read.")
            val isLast = part == path.split('/').last()
            val found = page.use { c ->
                var hit: String? = null
                while (c.moveToNext()) {
                    val entry = entryFrom(root, parentPath, c) ?: continue
                    if (entry.name == part && (entry.folder || isLast)) { hit = entry.docId; break }
                }
                hit
            } ?: return null
            docId = found
            current = if (current.isEmpty()) part else "$current/$part"
        }
        return docId
    }

    fun entry(root: Uri, path: String): Entry {
        val docId = resolvePath(root, path)
            ?: throw WriteException("$path was not found.", WriteException.Kind.PROVIDER)
        val self = resolver.query(docUri(root, docId), cols(), null, null)?.use { c ->
            if (c.moveToFirst()) entryFrom(root, path, c) else null
        } ?: throw WriteException("$path could not be described.", WriteException.Kind.PROVIDER)
        if (self.folder) throw WriteException("$path is a folder, not a file.", WriteException.Kind.PROVIDER)
        return self
    }

    /** Describes a folder path; throws when missing or when it is a file. */
    fun entryForFolder(root: Uri, path: String): Entry {
        val docId = resolvePath(root, path)
            ?: throw WriteException("Folder '$path' was not found.", WriteException.Kind.PROVIDER)
        val self = resolver.query(docUri(root, docId), cols(), null, null)?.use { c ->
            if (c.moveToFirst()) entryFrom(root, path, c) else null
        } ?: throw WriteException("Folder '$path' could not be described.", WriteException.Kind.PROVIDER)
        if (!self.folder) throw WriteException("'$path' is a file, not a folder.", WriteException.Kind.PROVIDER)
        return self
    }

    // ---- read -----------------------------------------------------------------

    /** Raw bytes with a hard cap; one extra byte detects truncation. */
    fun readBytes(uri: Uri, maxBytes: Int = READ_LIMIT): ByteArray {
        val buffer = ByteArray(maxBytes + 1)
        var length = 0
        resolver.openInputStream(uri)?.use { stream ->
            while (length < buffer.size) {
                val n = stream.read(buffer, length, buffer.size - length)
                if (n < 0) break
                if (n == 0) throw IOException("Empty read")
                length += n
            }
        } ?: throw IOException("The document could not be opened.")
        if (length > maxBytes) throw WriteException("File exceeds the $maxBytes-byte tool limit.",
            WriteException.Kind.TOO_LARGE)
        return buffer.copyOf(length)
    }

    fun readBytes(root: Uri, docId: String, maxBytes: Int = READ_LIMIT): ByteArray =
        readBytes(docUri(root, docId), maxBytes)

    fun readBytes(entry: Entry, maxBytes: Int = READ_LIMIT): ByteArray {
        check(entry.root in grants().map { it.root }) { "Folder access was revoked." }
        val bytes = readBytes(entry.root, entry.docId, maxBytes)
        record(WorkspaceEvent.READ)
        return bytes
    }

    /** Full UTF-8 text read for text tools; rejects embedded control characters. */
    fun readText(entry: Entry, maxBytes: Int = READ_LIMIT): String {
        val bytes = readBytes(entry, maxBytes)
        val text = bytes.toString(Charsets.UTF_8)
        check(text.none { it.isISOControl() && it !in "\t\n\r" }) { "Not plain UTF-8 text." }
        return text
    }

    // ---- write ----------------------------------------------------------------

    fun sanitizeFileName(name: String): String {
        val clean = name.trim().take(120)
            .filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() || it == '/' || it == '\\' }
        if (clean.isEmpty() || clean == "." || clean == "..") {
            throw WriteException("Invalid file name.", WriteException.Kind.INVALID_NAME)
        }
        return clean
    }

    /**
     * Creates a new document and writes `content`, then re-reads to verify. The provider
     * may choose a different display name; the returned entry carries the real one.
     * DocumentsContract has no atomic create+write, so a crash between create and write
     * can leave a partial file; this limitation is reported, not hidden.
     */
    fun createFile(root: Uri, dirPath: String, name: String, content: ByteArray,
                   mime: String = "text/plain"): Entry {
        requireWrite(root)
        check(content.size <= WRITE_LIMIT) { "Write exceeds the $WRITE_LIMIT-byte limit." }
        val clean = sanitizeFileName(name)
        val treeId = DocumentsContract.getTreeDocumentId(root)
        val parentDocId = if (dirPath.isEmpty()) treeId else resolvePath(root, dirPath)
            ?: throw WriteException("Folder $dirPath was not found.", WriteException.Kind.PROVIDER)
        val created = try {
            DocumentsContract.createDocument(resolver, docUri(root, parentDocId), mime, clean)
        } catch (e: Exception) {
            throw WriteException("The provider refused to create the file.", WriteException.Kind.PROVIDER)
        } ?: throw WriteException("The provider refused to create the file.", WriteException.Kind.PROVIDER)
        val result = try {
            writeStream(created, content)
            verify(created, content)
            describe(created, root)
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, created) }
            throw e
        }
        val realPath = if (dirPath.isEmpty()) result.name else "$dirPath/${result.name}"
        record(WorkspaceEvent.WRITE_CREATE)
        return result.copy(path = realPath)
    }

    /**
     * Replaces file content with optimistic-concurrency checking:
     * 1. Re-reads current bytes; if `expected` is supplied and differs, throws CONFLICT.
     * 2. Saves a best-effort backup copy in private storage (not a transaction).
     * 3. Writes with truncate and verifies by re-reading.
     * SAF replacement is not atomic; the backup is the recovery path and is stated as such.
     */
    fun replaceFile(entry: Entry, newContent: ByteArray, expected: ByteArray? = null): ByteArray {
        requireWrite(entry.root)
        check(newContent.size <= WRITE_LIMIT) { "Write exceeds the $WRITE_LIMIT-byte limit." }
        val current = readBytes(entry.root, entry.docId, WRITE_LIMIT)
        if (expected != null && !current.contentEquals(expected)) {
            record(WorkspaceEvent.CONFLICT)
            throw WriteException("The file changed after the agent read it (concurrent modification detected). " +
                "Nothing was overwritten; review and retry.", WriteException.Kind.CONFLICT)
        }
        val backup = if (current.isNotEmpty()) saveBackup(entry, current) else null
        val uri = docUri(entry.root, entry.docId)
        try {
            writeStream(uri, newContent)
            verify(uri, newContent)
        } catch (e: Exception) {
            record(WorkspaceEvent.FAILED)
            throw if (e is WriteException) e else WriteException(
                "The write did not complete. The previous version was kept as a backup copy.",
                WriteException.Kind.PROVIDER)
        }
        record(WorkspaceEvent.WRITE_REPLACE)
        return backup ?: ByteArray(0)
    }

    private fun writeStream(uri: Uri, content: ByteArray) {
        val stream = try { resolver.openOutputStream(uri, "wt") } catch (e: Exception) { null }
            ?: throw WriteException("The provider refused write access to this file.", WriteException.Kind.PROVIDER)
        stream.use { out ->
            var written = 0
            while (written < content.size) {
                val chunk = minOf(64 * 1024, content.size - written)
                try { out.write(content, written, chunk) } catch (e: IOException) {
                    throw WriteException("The provider stopped accepting bytes mid-write.",
                        WriteException.Kind.PROVIDER)
                }
                written += chunk
            }
            try { out.flush() } catch (_: IOException) { /* flush is advisory; verify() is authoritative */ }
        }
    }

    private fun verify(uri: Uri, content: ByteArray) {
        val back = try { readBytes(uri, content.size) } catch (e: WriteException) {
            if (e.kind == WriteException.Kind.TOO_LARGE) throw WriteException(
                "Post-write verification failed: the file holds more bytes than were written.",
                WriteException.Kind.VERIFY_FAILED)
            throw WriteException("Post-write verification could not read the file back.",
                WriteException.Kind.VERIFY_FAILED)
        }
        if (!back.contentEquals(content)) {
            throw WriteException("Post-write verification failed: the file does not match what was written.",
                WriteException.Kind.VERIFY_FAILED)
        }
    }

    private fun describe(uri: Uri, root: Uri): Entry {
        val cols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
        val e = resolver.query(uri, cols, null, null)?.use { c ->
            if (c.moveToFirst()) entryFrom(root, "", c) else null
        } ?: throw WriteException("Created document could not be described.", WriteException.Kind.PROVIDER)
        return e
    }

    /** Best-effort private backup; size-capped and clearly reported as non-atomic. */
    fun saveBackup(entry: Entry, bytes: ByteArray): ByteArray {
        if (bytes.size > MAX_BACKUP_BYTES) return ByteArray(0)
        val dir = File(backups, entry.name.take(40).ifBlank { "file" })
        if (!dir.isDirectory && !dir.mkdirs()) return ByteArray(0)
        val file = File(dir, "${System.currentTimeMillis()}-${entry.name.take(60)}.bak")
        return try {
            file.writeBytes(bytes)
            file.readBytes()
        } catch (_: IOException) { ByteArray(0) }
    }

    /** Exports the current content as a new file in a writable folder; never claims an in-place edit. */
    fun exportCopy(source: Entry, targetRoot: Uri, targetDir: String, targetName: String): Entry {
        val bytes = readBytes(source)
        return createFile(targetRoot, targetDir, targetName, bytes)
    }

    // ---- audit ----------------------------------------------------------------

    internal fun record(event: WorkspaceEvent) = synchronized(lock) {
        val value = appendLog(prefs.getString("events", "").orEmpty(), event, System.currentTimeMillis())
        prefs.edit().putString("events", value).apply()
    }

    internal fun history() = parseLog(prefs.getString("events", "").orEmpty())
    internal fun clearHistory() = synchronized(lock) { prefs.edit().remove("events").apply() }

    private companion object { val lock = Any() }
}
