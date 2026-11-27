package dev.forgeai.agent

import android.content.Context
import java.io.File

/**
 * Persists the conversation state as versioned JSON in app-private storage. On restart,
 * any non-terminal phase is surfaced as interrupted — never reported as completed.
 * v0.3.0 checkpoints (format v1) fail [EngineSerde.read] and are ignored: their pending
 * writes could not be trusted to resume (that bug is what 0.4.0 fixes).
 */
class CheckpointStore(context: Context) : CheckpointPort {
    private val dir = File(context.noBackupFilesDir, "checkpoints").apply { mkdirs() }

    override fun save(task: SavedTask) {
        val tmp = File(dir, "task-${task.id}.json.tmp")
        val out = File(dir, "task-${task.id}.json")
        tmp.writeText(EngineSerde.write(task))
        if (!tmp.renameTo(out)) {
            out.writeText(EngineSerde.write(task))
            tmp.delete()
        }
    }

    override fun list(): List<SavedTask> =
        dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { f -> runCatching { EngineSerde.read(f.readText()) }.getOrNull() }
            .sortedByDescending { it.state.task?.startedAt ?: it.id }

    override fun delete(id: Long) { File(dir, "task-$id.json").delete() }
}
