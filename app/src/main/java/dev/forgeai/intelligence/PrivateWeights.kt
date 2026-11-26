package dev.forgeai.intelligence

import android.content.Context
import android.os.CancellationSignal
import dev.forgeai.models.GgufReader
import dev.forgeai.models.ModelEntry
import dev.forgeai.models.ModelRegistry
import java.io.Closeable
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.Semaphore
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.EmptyCoroutineContext

/** Can interrupt a slow document provider without doing blocking binder calls on the UI thread. */
internal class CopyTicket {
    val signal = CancellationSignal()
    @Volatile var cancelled = false
        private set
    @Volatile var source: Closeable? = null
    fun check() { if (cancelled) throw CancellationException() }
    fun cancel() {
        if (cancelled) return
        cancelled = true
        Dispatchers.IO.dispatch(EmptyCoroutineContext) {
            try { signal.cancel() } finally { runCatching { source?.close() } }
        }
    }
}

internal object PrivateWeights {

    // Process-wide: activity instances cannot stage/load two models or unlink another active mmap.
    val slot = Semaphore(1)

    fun stage(ctx: Context, model: ModelEntry, ticket: CopyTicket, progress: (Int) -> Unit): File {
        if (!model.selected || model.metadata.architecture != "qwen2")
            throw ChatProblem("Select a Qwen2 or Qwen2.5 instruct GGUF in Models.")
        if (model.size !in 32L..MAX_MODEL_BYTES) throw ChatProblem("Use a model no larger than 1.5 GiB.")
        val file = File(ctx.noBackupFilesDir, "chat-session.gguf")
        if (file.exists() && !file.delete()) throw ChatProblem("Could not clear the old private model copy.")
        if (ctx.noBackupFilesDir.usableSpace < model.size + 64L * 1024 * 1024)
            throw ChatProblem("Not enough free storage for a private model copy plus 64 MiB of headroom.")
        val uri = ModelRegistry(ctx).use { it.preferredReadableUri(model.id) }
        ticket.check()
        try {
            val asset = ctx.contentResolver.openAssetFileDescriptor(uri, "r", ticket.signal)
                ?: throw ChatProblem("The model document could not be opened.")
            asset.use { afd ->
                val stat = afd.parcelFileDescriptor.statSize
                if ((afd.length >= 0 && afd.length != model.size) ||
                    (stat >= 0 && stat - afd.startOffset < model.size))
                    throw ChatProblem("The document size changed. Re-import it in Models.")
                afd.createInputStream().use { source ->
                    ticket.source = source
                    file.outputStream().use { out ->
                        copyExact(source, out, model.size, ticket::check, progress)
                    }
                }
            }
            ticket.check()
            if (file.length() != model.size) throw ChatProblem("The private model copy is incomplete.")
            val metadata = file.inputStream().use { GgufReader.read(it, model.size) }
            if (metadata != model.metadata) throw ChatProblem("Model metadata changed. Re-import it in Models.")
            return file
        } catch (t: Throwable) {
            file.delete()
            throw t
        } finally {
            ticket.source = null
        }
    }
}
