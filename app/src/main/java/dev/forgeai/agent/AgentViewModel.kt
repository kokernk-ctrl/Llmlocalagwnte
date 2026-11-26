package dev.forgeai.agent

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.forgeai.shell.ShellPolicy
import dev.forgeai.shell.ShellRunner
import dev.forgeai.workspace.WorkspaceAccess
import dev.forgeai.workspace.WorkspaceMirror
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ShellLogEntry(val command: String, val args: List<String>, val result: ShellRunner.Result)

/** Shared singletons for workspace-backed features (agent, shell, editor, python tools). */
class Workspaces(app: Application) {
    val access = WorkspaceAccess(app)
    val mirror = WorkspaceMirror(app)
    val checkpoints = CheckpointStore(app)
    private val runner = ShellRunner()

    companion object {
        @Volatile private var instance: Workspaces? = null
        fun get(app: Application): Workspaces = instance ?: synchronized(this) {
            instance ?: Workspaces(app).also { instance = it }
        }
    }
}

class AgentViewModel(app: Application) : AndroidViewModel(app) {
    private val ws = Workspaces.get(app)
    @Volatile private var planHook: ((String, Int) -> String?)? = null
    private val planProvider: () -> AgentController.PlanSource? = {
        planHook?.let { hook ->
            object : AgentController.PlanSource {
                override fun generate(prompt: String, maxTokens: Int): String? = hook(prompt, maxTokens)
            }
        }
    }
    internal val controller = AgentController(ws.access, ws.mirror, ws.checkpoints, planProvider)
    private val grants = MutableStateFlow(emptyList<WorkspaceAccess.Grant>())
    val state = controller.state
    val folderGrants = grants.asStateFlow()

    /** Wire the chat engine as the planning source when a model is loaded. */
    fun bindChatHook(hook: (String, Int) -> String?) { planHook = hook }

    init { refresh() }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) { grants.value = ws.access.grants() }
    }

    fun start(objective: String, root: Uri, mode: AgentMode, planFromModel: Boolean) {
        val clean = objective.trim()
        if (clean.isEmpty()) return
        controller.start(AgentConfig(clean, mode, planFromModel = planFromModel), root)
        refresh()
    }

    fun approve() = controller.approvePendingWrites()
    fun cancel() = controller.cancel()
    fun interruptedList(): List<CheckpointStore.SavedTask> = ws.checkpoints.list()
        .filter { it.state.interruptedLike || it.state.phase == AgentPhase.DONE }
        .sortedByDescending { it.state.startedAt }
    fun deleteTask(id: Long) = ws.checkpoints.delete(id)
}

class ShellViewModel(app: Application) : AndroidViewModel(app) {
    private val ws = Workspaces.get(app)
    private val runner = ShellRunner()
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
                    ShellRunner.Result(command, args, "", "", false, false, null, false, false, 0, false,
                        "Select an authorized folder first; commands run on its working copy.")
                } else {
                    val access = ws.access
                    val dir = ws.mirror.open(System.currentTimeMillis() / 60000)
                    mirrorDir = dir
                    val entries = runCatching { access.listTree(root) }.getOrDefault(emptyList())
                    ws.mirror.syncIn(access, root, entries.filter { !it.folder }, dir)
                    // Validation happens inside run(); the audit event covers user-initiated runs.
                    access.record(dev.forgeai.workspace.WorkspaceEvent.SHELL)
                    runner.run(ShellPolicy.Policy.USER, dir, command, args,
                        timeoutMs = timeoutSec.coerceIn(1, 120).toLong() * 1000)
                }
                logs.value = logs.value + ShellLogEntry(command, args, result)
            } finally {
                running.value = false
            }
        }
    }

    fun cancel() = runner.cancel()

    fun allowedCommands(): List<String> = ShellPolicy.commandsFor[ShellPolicy.Policy.USER]!!.keys.toList()
        .sorted()
}
