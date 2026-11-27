package dev.forgeai.agent

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.forgeai.workspace.WorkspaceAccess
import dev.forgeai.workspace.WorkspaceMirror
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class ShellLogEntry(val command: String, val args: List<String>,
                                  val result: dev.forgeai.shell.ShellRunner.Result)

/** Shared singletons for workspace-backed features (chat agent, shell, python tools). */
class Workspaces(app: Application) {
    val access = WorkspaceAccess(app)
    val mirror = WorkspaceMirror(app)
    val checkpoints = CheckpointStore(app)

    companion object {
        @Volatile private var instance: Workspaces? = null
        fun get(app: Application): Workspaces = instance ?: synchronized(this) {
            instance ?: Workspaces(app).also { instance = it }
        }
    }
}

/**
 * Owns the conversation agent engine and the session settings shown in the chat header:
 * active model readiness, active folder and edit mode. The engine runs on its own
 * worker; every state change is checkpointed by the engine itself.
 */
class ChatAgentViewModel(app: Application) : AndroidViewModel(app) {
    private val ws = Workspaces.get(app)

    @Volatile private var modelHook: ((String, Int) -> String?)? = null
    @Volatile private var modelReady: () -> Boolean = { false }

    val engine = AgentEngine(
        fs = SafFileSystem(ws.access),
        mirror = RealMirror(app, ws.mirror),
        checks = WhitelistedChecks(),
        py = BundledPython(app),
        model = LoadedModel { prompt, tokens -> modelHook?.invoke(prompt, tokens) },
        store = ws.checkpoints,
    )

    val state = engine.state
    private val grants = MutableStateFlow(emptyList<WorkspaceAccess.Grant>())
    val folderGrants = grants.asStateFlow()

    private val prefs = getApplication<Application>().getSharedPreferences("chat_agent", android.content.Context.MODE_PRIVATE)
    private val modeFlow = MutableStateFlow(
        runCatching { AgentMode.valueOf(prefs.getString("mode", AgentMode.REVIEW.name)!!) }
            .getOrDefault(AgentMode.REVIEW))
    val mode = modeFlow.asStateFlow()
    private val folderFlow = MutableStateFlow<String?>(prefs.getString("folder", null))
    val selectedFolder = folderFlow.asStateFlow()

    private val draftFlow = MutableStateFlow("")
    val draft = draftFlow.asStateFlow()

    init { refresh() }

    /** Wired by the app shell: the loaded local model becomes the conversation driver. */
    fun bindModel(hook: (String, Int) -> String?, ready: () -> Boolean) {
        modelHook = hook; modelReady = ready
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val live = ws.access.grants()
            grants.value = live
            val keep = folderFlow.value
            if (keep != null && live.none { it.root.toString() == keep }) {
                folderFlow.value = null
                prefs.edit().remove("folder").apply()
            }
        }
    }

    fun editDraft(text: String) { if (text.length <= 4000) draftFlow.value = text }
    fun setDraft(text: String) { draftFlow.value = text }

    fun setMode(m: AgentMode) {
        if (engine.isBusy()) return
        modeFlow.value = m
        prefs.edit().putString("mode", m.name).apply()
    }

    fun selectFolder(rootId: String) {
        if (engine.isBusy()) return
        folderFlow.value = rootId
        prefs.edit().putString("folder", rootId).apply()
    }

    fun grantPicked(uri: Uri, write: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                if (write) ws.access.grantReadWrite(uri) else ws.access.grantRead(uri)
            }
            val live = ws.access.grants()
            grants.value = live
            if (write || live.any { it.root.toString() == uri.toString() }) {
                folderFlow.value = uri.toString()
                prefs.edit().putString("folder", uri.toString()).apply()
            }
        }
    }

    fun revokeFolder(rootId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { ws.access.revoke(Uri.parse(rootId)) }
            refresh()
        }
    }

    /** Sends one message from the composer: starts (or continues) the agent conversation. */
    fun send(text: String) {
        val clean = text.trim()
        if (clean.isEmpty() || engine.isBusy()) return
        draftFlow.value = ""
        val useModel = modelReady()
        val folder = folderFlow.value ?: ""
        engine.start(EngineConfig(objective = clean, mode = modeFlow.value, rootId = folder,
            useModel = useModel))
    }

    fun approve(writeId: Int) = engine.approve(writeId)
    fun refuse(writeId: Int) = engine.refuse(writeId)
    fun cancel() = engine.cancel()
    fun savedTasks(): List<SavedTask> = engine.savedTasks()
    fun showCheckpoint(id: Long) = engine.showCheckpoint(id)
    fun deleteTask(id: Long) = engine.deleteTask(id)
    fun clearConversation() = engine.clearConversation()

    override fun onCleared() {
        engine.shutdown()
        super.onCleared()
    }
}

class ShellViewModel(app: Application) : AndroidViewModel(app) {
    private val ws = Workspaces.get(app)
    private val runner = dev.forgeai.shell.ShellRunner()
    internal val logs = MutableStateFlow(emptyList<ShellLogEntry>())
    internal val running = MutableStateFlow(false)
    internal val folderGrants = MutableStateFlow(emptyList<WorkspaceAccess.Grant>())
    private var mirrorDir: java.io.File? = null

    init { viewModelScope.launch(Dispatchers.IO) { folderGrants.value = ws.access.grants() } }

    fun refreshGrants() {
        viewModelScope.launch(Dispatchers.IO) { folderGrants.value = ws.access.grants() }
    }

    /** User-initiated commands run on a fresh mirror of the selected (authorized) folder. */
    fun runUserCommand(command: String, args: List<String>, root: Uri?, timeoutSec: Int) {
        if (running.value) return
        running.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = if (root == null) {
                    dev.forgeai.shell.ShellRunner.Result(command, args, "", "", false, false, null, false, false, 0, false,
                        "Select an authorized folder first; commands run on its working copy.")
                } else {
                    val access = ws.access
                    val dir = ws.mirror.open(System.currentTimeMillis() / 60000)
                    mirrorDir = dir
                    val entries = runCatching { access.listTree(root) }.getOrDefault(emptyList())
                    ws.mirror.syncIn(access, root, entries.filter { !it.folder }, dir)
                    access.record(dev.forgeai.workspace.WorkspaceEvent.SHELL)
                    runner.run(dev.forgeai.shell.ShellPolicy.Policy.USER, dir, command, args,
                        timeoutMs = timeoutSec.coerceIn(1, 120).toLong() * 1000)
                }
                logs.value = logs.value + ShellLogEntry(command, args, result)
            } finally {
                running.value = false
            }
        }
    }

    fun cancel() = runner.cancel()

    fun allowedCommands(): List<String> = dev.forgeai.shell.ShellPolicy.commandsFor[dev.forgeai.shell.ShellPolicy.Policy.USER]!!.keys.toList()
        .sorted()
}
