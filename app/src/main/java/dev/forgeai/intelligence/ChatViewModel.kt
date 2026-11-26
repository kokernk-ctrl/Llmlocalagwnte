package dev.forgeai.intelligence

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import androidx.lifecycle.AndroidViewModel
import dev.forgeai.models.ModelEntry
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class Phase(val title: String) {
    EMPTY("Not loaded"), COPYING("Copying model"), LOADING("Loading model"), READY("Ready"),
    RUNNING("Generating"), STOPPING("Stopping"), UNLOADING("Unloading")
}

internal data class ChatState(
    val phase: Phase = Phase.EMPTY, val percent: Int = 0, val context: Int = 1024,
    val turns: List<Turn> = emptyList(), val notice: String? = null
)

/** All JNI operations except the thread-safe cancellation signal run on a single worker.
 * An epoch invalidates callbacks immediately; free is always queued *after* generate returns. */
class ChatViewModel(application: Application) : AndroidViewModel(application), ComponentCallbacks2 {
    private val app = application
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "forge-chat") }
    private val current = MutableStateFlow(ChatState())
    internal val state = current.asStateFlow()
    private val composer = MutableStateFlow("")
    internal val draft = composer.asStateFlow()

    fun setWorkspaceDraft(excerpt: String) { composer.value = ChatProtocol.workspaceDraft(excerpt) }
    internal fun editDraft(text: String) {
        if (text.length <= ChatProtocol.MAX_INPUT) composer.value = text
    }
    private var epoch = 0L
    private var ticket: CopyTicket? = null
    @Volatile private var handle = 0L
    // Only the worker touches these.
    private var ownsSlot = false
    private var weights: File? = null

    init {
        app.registerComponentCallbacks(this)
        worker.execute {
            if (PrivateWeights.slot.tryAcquire()) {
                try { File(app.noBackupFilesDir, "chat-session.gguf").delete() }
                finally { PrivateWeights.slot.release() }
            }
        }
    }

    @Synchronized internal fun setContext(n: Int) {
        if (current.value.phase == Phase.EMPTY && n in ChatProtocol.contexts)
            current.value = current.value.copy(context = n)
    }

    @Synchronized internal fun load(model: ModelEntry) {
        if (current.value.phase != Phase.EMPTY) return
        val id = ++epoch
        val work = CopyTicket().also { ticket = it }
        val context = current.value.context
        current.value = ChatState(Phase.COPYING, context = context)
        worker.execute {
            try {
                work.check()
                if (!PrivateWeights.slot.tryAcquire()) throw ChatProblem("Another chat is using the engine.")
                ownsSlot = true
                val memory = ActivityManager.MemoryInfo()
                app.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
                if (memory.lowMemory) throw ChatProblem("Device memory is low. Close other apps, then retry.")
                weights = PrivateWeights.stage(app, model, work) { n ->
                    change(id) { it.copy(percent = n) }
                }
                work.check()
                change(id) { it.copy(phase = Phase.LOADING) }
                handle = NativeBridge.load(weights!!.absolutePath, context)
                if (handle == 0L) throw ChatProblem("The inference engine could not load this GGUF.")
                work.check()
                change(id) { it.copy(phase = Phase.READY) }
            } catch (e: Throwable) {
                release()
                change(id) { it.copy(phase = Phase.EMPTY, notice = problem(e, loading = true)) }
            }
        }
    }

    @Synchronized internal fun send(text: String): Boolean {
        val old = current.value
        if (old.phase != Phase.READY || handle == 0L) return false
        val user = text.trim()
        val prompt = try { ChatProtocol.prompt(user, old.turns, old.context) } catch (e: ChatProblem) {
            current.value = old.copy(notice = e.message)
            return false
        }
        val prior = old.turns.takeLast(ChatProtocol.MAX_TURNS - 1)
        val id = ++epoch
        current.value = old.copy(phase = Phase.RUNNING, turns = prior + Turn(user, ""), notice = null)
        composer.value = ""
        worker.execute {
            val decoder = Utf8Pieces()
            val reply = StringBuilder()
            try {
                if (running(id)) {
                    NativeBridge.generate(handle, prompt, ChatProtocol.REPLY_TOKENS, TokenSink { bytes ->
                        if (running(id)) {
                            reply.append(decoder.decode(bytes))
                            change(id) { it.copy(turns = prior + Turn(user, reply.toString())) }
                        } else NativeBridge.cancel(handle) // covers stop just before native generate enters
                    })
                }
                if (running(id)) reply.append(decoder.decode(byteArrayOf(), end = true))
                change(id) {
                    val stopped = it.phase == Phase.STOPPING
                    it.copy(phase = Phase.READY, turns = prior + Turn(user, reply.toString(), !stopped),
                        notice = when {
                            stopped -> "Stopped. This exchange won’t be used as context."
                            reply.isBlank() -> "The model ended its reply without returning text."
                            else -> null
                        })
                }
            } catch (e: Throwable) {
                change(id) { it.copy(phase = Phase.READY,
                    notice = if (it.phase == Phase.STOPPING) "Stopped. This exchange won’t be used as context."
                    else problem(e, loading = false)) }
            }
        }
        return true
    }

    @Synchronized internal fun stop() {
        if (current.value.phase != Phase.RUNNING) return
        current.value = current.value.copy(phase = Phase.STOPPING)
        if (handle != 0L) NativeBridge.cancel(handle)
    }

    @Synchronized internal fun unload(notice: String? = null) {
        val phase = current.value.phase
        if (phase == Phase.UNLOADING || phase == Phase.EMPTY) return
        val id = ++epoch
        ticket?.cancel()
        if (phase == Phase.RUNNING || phase == Phase.STOPPING) NativeBridge.cancel(handle)
        current.value = ChatState(Phase.UNLOADING, context = current.value.context, notice = notice)
        worker.execute {
            release()
            change(id) { it.copy(phase = Phase.EMPTY) }
        }
    }

    private fun release() {
        try { if (handle != 0L) NativeBridge.free(handle) } finally {
            handle = 0
            weights?.delete()
            weights = null
            if (ownsSlot) { ownsSlot = false; PrivateWeights.slot.release() }
        }
    }

    @Synchronized private fun running(id: Long) = epoch == id && current.value.phase == Phase.RUNNING
    @Synchronized private fun change(id: Long, f: (ChatState) -> ChatState) {
        if (id == epoch) current.value = f(current.value)
    }

    // Never pass provider/native exception messages to UI: they can contain paths or URIs.
    private fun problem(e: Throwable, loading: Boolean) = when (e) {
        is ChatProblem -> e.message ?: "Chat operation failed."
        is LinkageError -> "The native inference library is unavailable for this device."
        is SecurityException -> "Model access was revoked. Re-import the document in Models."
        is OutOfMemoryError -> "Not enough memory. Unload and try a smaller model."
        else -> if (loading) "Could not open this model. Check document access, storage, and that it is a complete, compatible Qwen2 GGUF."
            else "Generation failed. Try a shorter message or unload and reload the model."
    }

    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) unload("Unloaded in response to Android memory or background notification.")
    }
    override fun onLowMemory() { unload("Unloaded because device memory is low.") }
    override fun onConfigurationChanged(newConfig: Configuration) = Unit
    override fun onCleared() {
        app.unregisterComponentCallbacks(this)
        unload()
        worker.shutdown()
        super.onCleared()
    }
}
