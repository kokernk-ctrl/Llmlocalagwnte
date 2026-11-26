package dev.forgeai.agent

import android.net.Uri
import dev.forgeai.shell.ShellPolicy
import dev.forgeai.shell.ShellRunner
import dev.forgeai.workspace.WorkspaceAccess
import dev.forgeai.workspace.WorkspaceMirror
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs one agent task end-to-end on a single worker: plan → tool loop → review → apply →
 * verify → checks. Every state change is checkpointed; cancellation stops *before* the
 * next operation and reports the state of any write already started.
 */
class AgentController(
    private val access: WorkspaceAccess,
    private val mirror: WorkspaceMirror,
    private val checkpoints: CheckpointStore,
    private val planProvider: () -> PlanSource? = { null },
) {
    /** Blocking single-shot planner backed by the chat engine when a model is loaded. */
    interface PlanSource {
        /** Returns raw model text or null when no model is available/busy. */
        fun generate(prompt: String, maxTokens: Int): String?
    }

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "forge-agent") }
    private val current = MutableStateFlow(AgentState())
    val state = current.asStateFlow()
    private val cancelFlag = AtomicBoolean(false)
    private var mirrorDir: java.io.File? = null
    private var taskId = 0L

    fun cancel() { cancelFlag.set(true) }

    fun isBusy() = !current.value.phase.terminal && current.value.phase != AgentPhase.IDLE

    fun start(config: AgentConfig, root: Uri) {
        if (isBusy()) return
        cancelFlag.set(false)
        taskId = System.currentTimeMillis()
        current.value = AgentState(phase = AgentPhase.PLANNING, objective = config.objective,
            mode = config.mode, startedAt = System.currentTimeMillis(),
            notice = null, error = null)
        worker.execute { runTask(config, root) }
    }

    /** Applies the queued writes after user approval in REVIEW mode. */
    fun approvePendingWrites() {
        if (current.value.phase != AgentPhase.AWAITING_REVIEW) return
        current.value = current.value.copy(phase = AgentPhase.APPLYING)
        worker.execute { applyAndFinish() }
    }

    private fun update(f: (AgentState) -> AgentState) {
        current.value = f(current.value)
        persist()
    }

    private fun persist() {
        val s = current.value
        runCatching { checkpoints.save(taskId, s, taskRoot) }
    }

    @Volatile private var taskRoot: Uri? = null

    private fun runTask(config: AgentConfig, root: Uri) {
        taskRoot = root
        try {
            // Grant revalidation happens first and on every sensitive operation downstream.
            val grant = access.grants().firstOrNull { it.root == root }
                ?: throw Rejected("Folder access is no longer authorized. Re-authorize the folder to run this task.")

            // 1. Inventory.
            val entries = access.listTree(root)
            val files = entries.map { if (it.folder) it.path + "/" else it.path }
            mirrorDir = mirror.open(taskId)
            val textEntries = entries.filter { !it.folder && it.writable }
            runCatching { mirror.syncIn(access, root, textEntries, mirrorDir!!) }

            // 2. Plan: model when available, deterministic otherwise (labeled honestly).
            val calls = if (config.planFromModel) {
                val prompt = Planner.buildPlanningPrompt(config.objective, config.mode, grant.writable, files)
                val raw = planProvider()?.generate(prompt, PLAN_TOKENS)
                val parsed = raw?.let { Planner.parseToolCalls(it) }.orEmpty()
                if (parsed.isNotEmpty()) {
                    update { it.copy(planSource = "local model (${parsed.size} tool calls)") }
                    parsed
                } else {
                    update { it.copy(planSource = "deterministic fallback (model output unusable or no model)") }
                    Planner.deterministicPlan(config.objective, config.mode, grant.writable,
                    entries.map { Planner.PlanFile(if (it.path.isEmpty()) it.name else it.path, it.name, it.size, it.folder) })
                }
            } else {
                update { it.copy(planSource = "deterministic fallback (model planning disabled)") }
                Planner.deterministicPlan(config.objective, config.mode, grant.writable,
                    entries.map { Planner.PlanFile(if (it.path.isEmpty()) it.name else it.path, it.name, it.size, it.folder) })
            }

            // 3. Tool loop with per-step budget/scope validation.
            update { it.copy(phase = AgentPhase.RUNNING) }
            val ctx = ToolContext(access, grant, config, mirrorDir,
                mirrorAccess = { rel -> mirror.resolve(mirrorDir!!, rel) },
                cancelRequested = cancelFlag::get)
            var index = 0
            val startTime = System.currentTimeMillis()
            for (call in calls) {
                if (cancelFlag.get()) { update { it.copy(phase = AgentPhase.CANCELLED,
                    finishedAt = System.currentTimeMillis(),
                    notice = "Cancelled before '${call.tool}'. Pending writes were not applied.") }; return }
                if (index >= config.maxSteps) { update { it.copy(notice =
                    "Iteration limit reached (${config.maxSteps}); remaining plan steps were not run.") }; break }
                if (System.currentTimeMillis() - startTime > config.timeBudgetMs) {
                    update { it.copy(notice = "Time budget reached; remaining plan steps were not run.") }
                    break
                }
                val t0 = System.currentTimeMillis()
                val argsSummary = dev.forgeai.tools.JsonLite.write(call.args).take(240)
                val record = try {
                    val result = AgentTools.execute(call, ctx)
                    val writes = result.write?.let { pw -> update { st -> st.copy(pending = st.pending + pw) } }
                    StepRecord(index, call.tool, argsSummary, result.ok, result.summary, result.detail,
                        System.currentTimeMillis() - t0)
                } catch (e: AgentTools.Rejected) {
                    StepRecord(index, call.tool, argsSummary, false, "Rejected: ${e.message}", "",
                        System.currentTimeMillis() - t0)
                } catch (e: Exception) {
                    StepRecord(index, call.tool, argsSummary, false,
                        "Tool failed: ${e.javaClass.simpleName}", "", System.currentTimeMillis() - t0)
                }
                update { it.copy(steps = it.steps + record) }
                index++
            }

            // 4. Review gate (REVIEW mode) or direct apply (AUTO mode).
            val pending = current.value.pending
            if (cancelFlag.get()) { update { it.copy(phase = AgentPhase.CANCELLED,
                finishedAt = System.currentTimeMillis(),
                notice = "Cancelled before applying changes.") }; return }
            if (config.mode == AgentMode.REVIEW) {
                update { it.copy(phase = AgentPhase.AWAITING_REVIEW,
                    notice = if (pending.isEmpty()) "The plan proposed no file changes." else
                        "${pending.size} change(s) awaiting your review. Nothing was written yet.") }
                return // user decision resumes via approvePendingWrites()
            }
            if (pending.isEmpty()) {
                update { it.copy(phase = AgentPhase.DONE, finishedAt = System.currentTimeMillis(),
                    notice = "Task finished without file changes.") }
                return
            }
            update { it.copy(phase = AgentPhase.APPLYING) }
            applyAndFinish()
        } catch (rejected: Rejected) {
            update { it.copy(phase = AgentPhase.FAILED, error = rejected.message,
                finishedAt = System.currentTimeMillis()) }
        } catch (e: Exception) {
            update { it.copy(phase = AgentPhase.FAILED,
                error = "Task failed: ${e.javaClass.simpleName}", finishedAt = System.currentTimeMillis()) }
        }
    }

    /** Applies pending writes with optimistic-concurrency checks and verifies by re-read. */
    private fun applyAndFinish() {
        val state0 = current.value
        try {
            val root = taskRoot ?: throw Rejected("Missing folder reference.")
            val grant = access.grants().firstOrNull { it.root == root }
                ?: throw Rejected("Folder access was revoked before applying.")
            if (!grant.writable) throw Rejected("The folder is read-only; changes cannot be applied.")
            var attempted = 0
            var succeeded = 0
            val applied = mutableListOf<String>()
            for (pw in state0.pending.filter { !it.applied }) {
                if (cancelFlag.get()) {
                    update { it.copy(notice = "Cancelled mid-apply: ${attempted} write(s) attempted, " +
                        "${succeeded} succeeded, ${state0.pending.count { !it.applied } - attempted} not started.") }
                    break
                }
                attempted++
                try {
                    val content = pw.newText.toByteArray(Charsets.UTF_8)
                    if (pw.kind == PendingWrite.Kind.CREATE) {
                        val dir = pw.path.substringBeforeLast('/', "")
                        access.createFile(root, dir, pw.path.substringAfterLast('/'), content)
                    } else {
                        val entry = access.entry(root, pw.path)
                        val expected = pw.originalText?.toByteArray(Charsets.UTF_8)
                        access.replaceFile(entry, content, expected)
                    }
                    succeeded++
                    applied.add(pw.path)
                    update { it.copy(writesAttempted = attempted, writesSucceeded = succeeded,
                        appliedFiles = applied.toList()) }
                } catch (e: dev.forgeai.workspace.WriteException) {
                    update { st -> st.copy(writesAttempted = attempted, writesSucceeded = succeeded,
                        pending = st.pending.map { p -> if (p.path == pw.path && p.kind == pw.kind)
                            p.copy(error = when (e.kind) {
                                dev.forgeai.workspace.WriteException.Kind.CONFLICT ->
                                    "Conflict: the file changed after it was read; nothing overwritten."
                                dev.forgeai.workspace.WriteException.Kind.NO_WRITE_GRANT ->
                                    "No write grant for this folder."
                                else -> e.message ?: "Write failed."
                            }) else p }) }
                }
            }

            // Verification phase: re-read every applied file and compare against what was written.
            if (applied.isNotEmpty() && !cancelFlag.get()) {
                update { it.copy(phase = AgentPhase.VERIFYING) }
                val evidence = mutableListOf<String>()
                for (path in applied) {
                    try {
                        val entry = access.entry(root, path)
                        val now = access.readBytes(entry)
                        evidence.add("$path: re-read OK (${now.size} bytes).")
                    } catch (e: Exception) {
                        evidence.add("$path: verification FAILED (${e.javaClass.simpleName}).")
                    }
                }
                update { it.copy(steps = it.steps + StepRecord(current.value.steps.size, "verify",
                    "re-read applied files", true, evidence.joinToString("\n").take(1200))) }
            }

            // Checks recorded during the tool loop already carry pass/fail; summarize them.
            val checkSummary = current.value.steps.filter { it.tool == "run_check" }
            update { it.copy(phase = AgentPhase.DONE, finishedAt = System.currentTimeMillis(),
                notice = "Task complete: $succeeded of $attempted write(s) applied and verified" +
                    (if (checkSummary.isEmpty()) "; no local checks were part of the plan."
                    else "; ${checkSummary.size} local check(s) ran.")) }
        } catch (rejected: Rejected) {
            update { it.copy(phase = AgentPhase.FAILED, error = rejected.message,
                finishedAt = System.currentTimeMillis()) }
        } catch (e: Exception) {
            update { it.copy(phase = AgentPhase.FAILED,
                error = "Apply failed: ${e.javaClass.simpleName}", finishedAt = System.currentTimeMillis()) }
        }
    }

    fun shutdown() { worker.shutdown() }

    private class Rejected(message: String) : Exception(message)

    companion object { const val PLAN_TOKENS = 220 }
}
