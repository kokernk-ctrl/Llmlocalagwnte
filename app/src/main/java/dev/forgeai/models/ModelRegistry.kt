package dev.forgeai.models

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException

internal class ModelRegistry(context: Context) : AutoCloseable {
    private val resolver = context.contentResolver
    private val store = RegistryDb(context)

    fun list(): List<ModelEntry> {
        val grants = readGrants()
        return store.readableDatabase.rawQuery("""
            SELECT id, uri, name, bytes, gguf_version, architecture, quantization, tensors, preferred
            FROM models ORDER BY preferred DESC, id DESC
        """.trimIndent(), null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val uri = Uri.parse(cursor.getString(1))
                    val bytes = cursor.getLong(3)
                    add(ModelEntry(cursor.getLong(0), cursor.getString(2), bytes,
                        GgufMetadata(cursor.getInt(4), cursor.getString(5), cursor.getString(6), cursor.getLong(7)),
                        cursor.getInt(8) != 0, availability(uri, bytes, grants)))
                }
            }
        }
    }

    fun register(uri: Uri): String {
        if (uri.scheme != "content") throw ModelImportException("Choose a document in the Android file picker.")
        val db = store.writableDatabase
        val registered = db.rawQuery("SELECT id FROM models WHERE uri = ?", arrayOf(uri.toString()))
            .use { it.moveToFirst() }
        val hadGrant = uri in readGrants()
        val display = document(uri)
        if (!display.first.endsWith(".gguf", ignoreCase = true)) {
            throw ModelImportException("Choose a file with the .gguf extension.")
        }
        val bytes = display.second
        if (bytes < 32) throw ModelImportException("File is empty, too small, or its provider did not report a size.")
        val metadata = try {
            (resolver.openInputStream(uri) ?: throw IOException("The selected file could not be opened."))
                .buffered().use { GgufReader.read(it, bytes) }
        } catch (error: IOException) {
            if (error is java.io.FileNotFoundException) throw ModelImportException("The selected file could not be opened.")
            throw ModelImportException(error.message ?: "The GGUF metadata could not be read.")
        }
        if (!hadGrant) {
            try {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                throw ModelImportException("This provider did not grant persistent read access. Try a local file.")
            }
        }
        try {
            val values = ContentValues().apply {
                put("uri", uri.toString())
                put("name", display.first.take(240).filterNot { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() })
                put("bytes", bytes)
                put("gguf_version", metadata.version)
                put("architecture", metadata.architecture)
                put("quantization", metadata.quantization)
                put("tensors", metadata.tensorCount)
            }
            if (registered) db.update("models", values, "uri = ?", arrayOf(uri.toString()))
            else db.insertOrThrow("models", null, values)
        } catch (error: RuntimeException) {
            if (!hadGrant && !registered) release(uri)
            throw error
        }
        return if (registered) "Registration updated." else "Model registered. No weights were copied."
    }

    fun prefer(id: Long) {
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE models SET preferred = 0 WHERE preferred = 1")
            val count = db.update("models", ContentValues().apply { put("preferred", 1) }, "id = ?", arrayOf(id.toString()))
            check(count == 1)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun remove(id: Long): String {
        val db = store.writableDatabase
        val args = arrayOf(id.toString())
        val uri = db.rawQuery("SELECT uri FROM models WHERE id = ?", args).use {
            if (it.moveToFirst()) Uri.parse(it.getString(0)) else return "This entry was already removed."
        }
        db.delete("models", "id = ?", args)
        return if (release(uri)) "Registration removed. The original file was not deleted."
        else "Registration removed. The original file was not deleted. Android could not release its read grant."
    }

    private fun readGrants() = resolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri }.toSet()

    private fun release(uri: Uri): Boolean = try {
        if (uri in readGrants()) resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    } catch (_: RuntimeException) { false }

    private fun availability(uri: Uri, bytes: Long, grants: Set<Uri>): ModelAvailability {
        if (uri !in grants) return ModelAvailability.NO_ACCESS
        return try {
            val reported = document(uri).second
            if (reported >= 0 && reported != bytes) return ModelAvailability.CHANGED
            resolver.openAssetFileDescriptor(uri, "r")?.use { ModelAvailability.AVAILABLE }
                ?: ModelAvailability.UNAVAILABLE
        } catch (_: SecurityException) { ModelAvailability.NO_ACCESS }
        catch (_: IOException) { ModelAvailability.UNAVAILABLE }
        catch (_: RuntimeException) { ModelAvailability.UNAVAILABLE }
    }

    private fun document(uri: Uri): Pair<String, Long> = resolver.query(uri,
        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) throw ModelImportException("The selected file is unavailable.")
        val name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val size = cursor.getColumnIndex(OpenableColumns.SIZE)
        val display = if (name < 0) null else cursor.getString(name)
        (display ?: "") to (if (size < 0 || cursor.isNull(size)) -1 else cursor.getLong(size))
    } ?: throw ModelImportException("The document provider did not return file information.")

    override fun close() = store.close()

    private class RegistryDb(context: Context) : SQLiteOpenHelper(context, "model_registry.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE models (
                    id INTEGER PRIMARY KEY, uri TEXT NOT NULL UNIQUE, name TEXT NOT NULL,
                    bytes INTEGER NOT NULL CHECK(bytes > 0), gguf_version INTEGER NOT NULL,
                    architecture TEXT NOT NULL, quantization TEXT NOT NULL, tensors INTEGER NOT NULL,
                    preferred INTEGER NOT NULL DEFAULT 0 CHECK(preferred IN (0, 1))
                )
            """.trimIndent())
            db.execSQL("CREATE UNIQUE INDEX one_preferred ON models(preferred) WHERE preferred = 1")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
            error("Model registry migration missing: $oldVersion -> $newVersion")
    }
}

internal class ModelImportException(message: String) : IOException(message)
