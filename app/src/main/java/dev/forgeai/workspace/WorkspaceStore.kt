package dev.forgeai.workspace

import android.content.ContentResolver
import android.content.Context
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.IOException

internal class WorkspaceStore(context: Context) {
    private val resolver = context.contentResolver
    private val prefs = context.getSharedPreferences("workspace_activity_v1", Context.MODE_PRIVATE)

    // Restrict ownership to pure tree grants; never release a model's individual document grant.
    private fun isRoot(uri: Uri) = uri.scheme == "content" && !uri.authority.isNullOrEmpty() &&
        uri.pathSegments.size == 2 && uri.pathSegments.first() == "tree" &&
        uri.query == null && uri.fragment == null

    fun roots(): List<Uri> = resolver.persistedUriPermissions
        .filter { it.isReadPermission && isRoot(it.uri) }.map { it.uri }

    fun grant(uri: Uri) = synchronized(lock) {
        require(isRoot(uri) && roots().isEmpty())
        resolver.takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)
        record(WorkspaceEvent.GRANT)
    }

    fun revoke(root: Uri) {
        require(isRoot(root))
        resolver.releasePersistableUriPermission(root, FLAG_GRANT_READ_URI_PERMISSION)
        record(WorkspaceEvent.REVOKE)
    }

    fun folderName(root: Uri): String = safeName(DocumentsContract.getTreeDocumentId(root)
        .substringAfterLast('/').substringAfterLast(':')).ifBlank { "Selected folder" }

    private fun safeName(name: String) = name.take(120).filterNot {
        it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt()
    }

    class Entry internal constructor(internal val root: Uri, internal val id: String, val name: String,
                                     val folder: Boolean, val readable: Boolean)
    data class Listing(val entries: List<Entry>, val more: Boolean)

    fun list(root: Uri): Listing {
        check(root in roots())
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(root, DocumentsContract.getTreeDocumentId(root))
        val cols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        val args = Bundle().apply { putInt(ContentResolver.QUERY_ARG_LIMIT, MAX_ROWS + 1) }
        val entries = mutableListOf<Entry>()
        val more = resolver.query(children, cols, args, null)?.use { c ->
            var visited = 0
            while (visited < MAX_ROWS && c.moveToNext()) {
                visited++
                val id = c.getString(0)?.takeIf { it.length in 1..4096 } ?: continue
                val name = safeName(c.getString(1).orEmpty()).ifBlank { "Unnamed document" }
                val mime = c.getString(2).orEmpty()
                val folder = mime == Document.MIME_TYPE_DIR
                entries += Entry(root, id, name, folder, !folder && suggestsText(name, mime))
            }
            visited == MAX_ROWS && c.moveToNext()
        } ?: throw IOException("No cursor")
        record(WorkspaceEvent.LIST)
        return Listing(entries, more)
    }

    fun preview(entry: Entry): Preview {
        check(entry.readable && entry.root in roots())
        val document = DocumentsContract.buildDocumentUriUsingTree(entry.root, entry.id)
        val value = resolver.openInputStream(document)?.use(::readPreview) ?: throw IOException("No stream")
        record(WorkspaceEvent.READ)
        return value
    }

    fun record(event: WorkspaceEvent) = synchronized(lock) {
        val value = appendLog(prefs.getString("events", "").orEmpty(), event, System.currentTimeMillis())
        prefs.edit().putString("events", value).apply()
    }

    fun history() = parseLog(prefs.getString("events", "").orEmpty())
    fun clearHistory() = synchronized(lock) { prefs.edit().remove("events").apply() }

    private companion object { val lock = Any() }
}
