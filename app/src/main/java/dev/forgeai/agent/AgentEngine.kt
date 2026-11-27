package dev.forgeai.agent

import dev.forgeai.tools.JVal
import dev.forgeai.tools.JsonLite
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single-conversation agent engine.
 *
 * The user chats; the model (or, without one, a labeled deterministic planner) drives real
 * tool calls inside the same conversation. The runtime validates every call against scope
 * and budgets, executes it for real, and feeds the actual result back to the model.
 *
 * Flow guarantees fixed relative to v0.3.0:
 *  - A check that depends on a write not yet applied is reported as AWAITING_WRITE and
 *    never runs against a file that does not exist.
 *  - A write is applied, re-read and verified; the provider's REAL document path is the
 *    source of truth (providers may rename documents), and the working copy is updated
 *    from the verified bytes before any dependent check executes.
 *  - A failed save blocks its dependent checks (SKIPPED with the concrete reason) and the
 *    task is never summarized as "verified" when verification failed.
 *  - Refusing a write marks it REFUSED and skips only the steps that depended on it.
 */
class AgentEngine(
    private val fs: FileSystemPort,
    private val mirror: MirrorPort,
    private val checks: CheckRunnerPort,
    private val py: PythonPort,
    private val model: ModelPort,
    private val store: CheckpointPort,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "forge-agent-engine") }
    private val current = MutableStateFlow(EngineState())
    val state = current.asStateFlow()
    private val cancelFlag = AtomicBoolean(false)
    private var itemId = 0L
    private var writeSeq = 0
    private var stepSeq = 0

    fun cancel() {
        cancelFlag.set(true)
        checks.cancel()
        // When parked awaiting approval no worker runnable is active: settle the
        // cancellation immediately so the state reflects what was and wasn't applied.
        if (current.value.phase == RunPhase.AWAITING_APPROVAL) {
            worker.execute { checkCancelled() }
        }
    }
    fun isBusy() = current.value.active

    // ---- public entry points ---------------------------------------------------

    fun start(config: EngineConfig) {
        if (isBusy()) return
        cancelFlag.set(false)
        readCount = 0; stepSeq = 0; modelTurns = 0
        val previous = current.value.task
        itemId = ((previous?.let { current.value.items.maxOfOrNull { i -> i.id } }) ?: now()) + 1
        val grant = fs.grants().firstOrNull { it.rootId == config.rootId }
        val task = TaskInfo(id = now(), objective = config.objective, mode = config.mode,
            rootId = config.rootId, folderName = grant?.displayName ?: "(no folder)",
            writable = grant?.writable ?: false, startedAt = now())
        // The conversation continues across tasks: settled writes stay as dependency
        // context (e.g. a later check on a file created in an earlier task).
        val divider = if (previous != null) listOf<Tl>(Tl.Info(nextItem(), "— new task —")) else emptyList()
        current.value = current.value.copy(phase = RunPhase.RUNNING, task = task,
            planSource = "", error = null,
            items = current.value.items + divider,
            writes = current.value.writes.filter { it.settled() })
        persist()
        worker.execute { runTask(config, grant) }
    }

    /** Approves exactly one pending write (or a pending script approval). */
    fun approve(writeId: Int) {
        val s = current.value
        if (s.phase != RunPhase.AWAITING_APPROVAL) return
        worker.execute { resolveApproval(writeId, approved = true) }
    }

    fun refuse(writeId: Int) {
        val s = current.value
        if (s.phase != RunPhase.AWAITING_APPROVAL) return
        worker.execute { resolveApproval(writeId, approved = false) }
    }

    fun deleteTask(id: Long) = store.delete(id)
    fun savedTasks(): List<SavedTask> = store.list()

    /** Loads a checkpoint into the timeline for inspection (never auto-resumes execution). */
    fun showCheckpoint(id: Long) {
        if (isBusy()) return
        val saved = store.list().firstOrNull { it.id == id } ?: return
        itemId = (saved.state.items.maxOfOrNull { it.id } ?: 0) + 1
        current.value = saved.state
    }

    fun clearConversation() {
        if (isBusy()) return
        current.value = EngineState()
    }

    fun shutdown() { worker.shutdown() }

    // ---- state helpers ---------------------------------------------------------

    private fun update(f: (EngineState) -> EngineState) {
        current.value = f(current.value)
        persist()
    }

    private fun persist() {
        val s = current.value
        val t = s.task ?: return
        runCatching { store.save(SavedTask(t.id, s, t.rootId)) }
    }

    private fun nextItem(): Long = ++itemId

    private fun addItem(item: Tl) = update { it.copy(items = it.items + item) }

    private fun replaceItem(id: Long, item: Tl) = update { st ->
        st.copy(items = st.items.map { if (it.id == id) item else it })
    }

    private fun updateWrite(writeId: Int, f: (TrackedWrite) -> TrackedWrite) = update { st ->
        st.copy(writes = st.writes.map { if (it.writeId == writeId) f(it) else it })
    }

    private fun checkCancelled(): Boolean {
        if (!cancelFlag.get()) return false
        val report = Tl.FinalReport(nextItem(), finalReportText(cancelled = true))
        update { st -> st.copy(phase = RunPhase.CANCELLED,
            task = st.task?.copy(finishedAt = now()),
            writes = st.writes.map { if (it.pending()) it.copy(outcome = WriteOutcome.CANCELLED) else it },
            items = st.items.map { i ->
                if (i is Tl.Check && i.outcome == CheckOutcome.AWAITING_WRITE)
                    i.copy(outcome = CheckOutcome.CANCELLED, reason = "Cancelled.") else i
            } + report) }
        return true
    }

    // ---- task runner -----------------------------------------------------------

    private fun runTask(config: EngineConfig, grant: FolderGrant?) {
        try {
            if (grant == null && config.rootId.isNotEmpty()) {
                addItem(Tl.Problem(nextItem(), "Folder access is no longer authorized. Re-authorize the folder in Files, then send the task again."))
                update { it.copy(phase = RunPhase.FAILED, task = it.task?.copy(finishedAt = now())) }
                return
            }
            if (config.mode == AgentMode.AUTO && grant != null && !grant.writable) {
                addItem(Tl.Problem(nextItem(), "The folder '${grant.displayName}' is read-only. Auto-apply needs write access: grant read+write in Files, or switch to review mode."))
                update { it.copy(phase = RunPhase.FAILED, task = it.task?.copy(finishedAt = now())) }
                return
            }

            addItem(Tl.Objective(nextItem(), config.objective))

            if (grant == null) {
                // Conversation mode: no folder authorized — file tools stay honestly disabled.
                addItem(Tl.Info(nextItem(), "No folder is selected, so file, shell and folder tools are disabled for this conversation. Pick a folder in the header to let the agent work on real files."))
                if (config.useModel) {
                    runModelLoop(config, null)
                } else {
                    addItem(Tl.Info(nextItem(), "Load a model (header) or select a folder to give the agent real work."))
                    update { it.copy(phase = RunPhase.DONE, task = it.task?.copy(finishedAt = now())) }
                }
                return
            }

            // Working copy: real files only, versioned by the provider timestamps we saw.
            val entries = fs.listTree(config.rootId)
            val syncable = entries.filter { !it.folder && FileKinds.isTextName(it.name) && it.size in 0..READ_CAP }
            runCatching {
                mirror.open(current.value.task?.id?.toString() ?: config.rootId)
                mirror.syncIn(syncable.take(MAX_SYNC_FILES)) { ref -> fs.readBytes(ref, READ_CAP) }
            }
            addItem(Tl.Info(nextItem(), "Working copy ready: ${syncable.size.coerceAtMost(MAX_SYNC_FILES)} file(s) mirrored from '${grant.displayName}' " +
                "(${if (grant.writable) "read+write" else "read-only"}). Folder has ${entries.size} entr${if (entries.size == 1) "y" else "ies"}."))

            if (config.useModel) {
                runModelLoop(config, grant)
            } else {
                runDeterministicPlan(config, grant, entries)
            }
        } catch (e: Exception) {
            addItem(Tl.Problem(nextItem(), "Task stopped: ${e.message ?: e.javaClass.simpleName}"))
            update { it.copy(phase = RunPhase.FAILED, task = it.task?.copy(finishedAt = now())) }
        }
    }

    // ---- deterministic plan path ------------------------------------------------

    private fun runDeterministicPlan(config: EngineConfig, grant: FolderGrant, entries: List<FileRef>) {
        val files = entries.map { Planner.PlanFile(it.path, it.name, it.size, it.folder) }
        val calls = Planner.deterministicPlan(config.objective, config.mode, grant.writable, files)
        update { it.copy(planSource = "deterministic plan (no model drive)") }
        addItem(Tl.PlanNote(nextItem(),
            calls.joinToString("\n") { c -> "• ${c.tool} ${shortArgs(c)}" }.ifEmpty { "(no actions needed)" },
            "deterministic plan (no model drive)"))
        for (call in calls) {
            if (checkCancelled()) return
            if (budgetExceeded(config)) return
            executeTool(call, config, grant, fromModel = false)
        }
        settleOrFinish(config, grant)
    }

    /** After the plan ran: if writes are still proposed, wait for approval; else finish. */
    private fun settleOrFinish(config: EngineConfig, grant: FolderGrant?) {
        if (checkCancelled()) return
        val pending = current.value.writes.filter { it.pending() }
        if (pending.isNotEmpty()) {
            update { it.copy(phase = RunPhase.AWAITING_APPROVAL) }
            addItem(Tl.Info(nextItem(), "Review the proposed change(s) above. Nothing has been written yet; checks that depend on them are waiting."))
            return
        }
        finishTask(config, grant)
    }

    // ---- model-driven loop -------------------------------------------------------

    private fun runModelLoop(config: EngineConfig, grant: FolderGrant?) {
        update { it.copy(planSource = "local model") }
        var firstTurn = true
        while (true) {
            if (checkCancelled()) return
            if (budgetExceeded(config)) return
            if (modelTurns >= config.maxModelTurns) {
                addItem(Tl.Info(nextItem(), "Model turn limit reached (${config.maxModelTurns}). Stopping with an honest summary."))
                finishTask(config, grant)
                return
            }
            modelTurns++
            val prompt = buildModelPrompt(config, grant)
            val reply = model.generate(prompt, config.replyTokens)
            if (reply == null) {
                if (firstTurn && grant != null) {
                    addItem(Tl.Info(nextItem(), "The model is not available right now (not loaded or busy). Falling back to the labeled deterministic plan."))
                    update { it.copy(planSource = "deterministic plan (model unavailable)") }
                    runDeterministicPlan(config, grant, fs.listTree(config.rootId))
                } else {
                    addItem(Tl.Info(nextItem(), "The model became unavailable (not loaded or busy). Actions already taken are kept; nothing else was invented."))
                    if (current.value.writes.any { it.pending() }) {
                        update { it.copy(phase = RunPhase.AWAITING_APPROVAL) }
                    } else finishTask(config, grant)
                }
                return
            }
            firstTurn = false
            val calls = Planner.parseToolCalls(reply, maxCalls = 1)
            if (calls.isEmpty()) {
                val text = reply.trim()
                if (text.isNotEmpty()) addItem(Tl.AgentText(nextItem(), text.take(4000)))
                else addItem(Tl.Info(nextItem(), "The model returned no text and no tool call."))
                settleOrFinish(config, grant)
                return
            }
            executeTool(calls.first(), config, grant, fromModel = true)
            // A queued write parks the model loop: the next model turn depends on the
            // real outcome of this one.
            if (current.value.writes.any { it.pending() }) {
                update { it.copy(phase = RunPhase.AWAITING_APPROVAL) }
                return
            }
        }
    }

    /** Rebuilds the model context from the timeline and continues the loop after a decision. */
    private var modelTurns = 0

    // ---- approval resolution ------------------------------------------------------

    private fun resolveApproval(writeId: Int, approved: Boolean) {
        val s0 = current.value
        val config = configOf(s0) ?: return
        val grant = fs.grants().firstOrNull { it.rootId == config.rootId }
        if (grant == null) {
            addItem(Tl.Problem(nextItem(), "Folder access was revoked. ${FsException("", FsException.Kind.NO_WRITE_GRANT).userAction()}"))
            refuseAllPending()
            update { it.copy(phase = RunPhase.FAILED, task = it.task?.copy(finishedAt = now())) }
            return
        }
        val target = s0.writes.firstOrNull { it.writeId == writeId }
        if (target != null && target.pending()) {
            if (approved) {
                if (target.path == SCRIPT_MARKER) runApprovedScript(target)
                else applyWrite(target, config, grant)
            } else {
                updateWrite(writeId) { it.copy(outcome = WriteOutcome.REFUSED) }
                replaceWriteTimeline(writeId) { it.copy(outcome = WriteOutcome.REFUSED) }
                addItem(Tl.Info(nextItem(), "${approvalLabel(target)} refused by you. Steps that depended on it will be skipped."))
            }
        }
        val stillPending = current.value.writes.any { it.pending() }
        if (stillPending) {
            update { it.copy(phase = RunPhase.AWAITING_APPROVAL) }
            return
        }
        // All writes settled: run the checks that were waiting, then finish or continue.
        update { it.copy(phase = RunPhase.RUNNING) }
        runDeferredChecks(config, grant)
        if (checkCancelled()) return
        if (config.useModel) {
            runModelLoop(config, grant)
        } else {
            finishTask(config, grant)
        }
    }

    private fun approvalLabel(t: TrackedWrite): String =
        if (t.path == SCRIPT_MARKER) "Python script" else "Change to '${t.path}'"

    /** Runs an approved python script in the isolated runner; result joins the timeline. */
    private fun runApprovedScript(target: TrackedWrite) {
        val r = py.runScript(target.newText, "", deadlineSeconds = 30)
        val outcome = if (r.ok) WriteOutcome.RUN_OK else WriteOutcome.RUN_FAILED
        val errorText = if (r.ok) null else (r.error.ifBlank { r.stderr }.ifBlank { "script failed" }).take(300)
        updateWrite(target.writeId) { it.copy(outcome = outcome, error = errorText) }
        replaceWriteTimeline(target.writeId) { it.copy(outcome = outcome, error = errorText) }
        addItem(Tl.Action(nextItem(), ++stepSeq, "python_script", "(approved script)",
            if (r.ok) ActionOutcome.OK else ActionOutcome.FAILED,
            if (r.ok) "Script ran in ${r.durationMs} ms (isolated, no network)." else "Script failed.",
            (r.stdout.take(4000) + (if (r.stderr.isNotBlank()) "\n[stderr] ${r.stderr.take(1000)}" else "")), 0))
    }

    private fun refuseAllPending() = update { st ->
        st.copy(writes = st.writes.map { if (it.pending()) it.copy(outcome = WriteOutcome.REFUSED) else it })
    }

    private fun configOf(s: EngineState): EngineConfig? {
        val t = s.task ?: return null
        return EngineConfig(objective = t.objective, mode = t.mode, rootId = t.rootId,
            useModel = s.planSource.startsWith("local model"))
    }

    // ---- tool execution --------------------------------------------------------

    private fun budgetExceeded(config: EngineConfig): Boolean {
        val s = current.value
        val t = s.task ?: return true
        if (now() - t.startedAt > config.timeBudgetMs) {
            addItem(Tl.Info(nextItem(), "Time budget reached (${config.timeBudgetMs / 1000}s). Remaining steps were not run."))
            settleOrFinish(config, fs.grants().firstOrNull { it.rootId == config.rootId } ?: return true)
            return true
        }
        if (stepSeq >= config.maxSteps) {
            addItem(Tl.Info(nextItem(), "Step limit reached (${config.maxSteps} tool actions). Remaining steps were not run."))
            settleOrFinish(config, fs.grants().firstOrNull { it.rootId == config.rootId } ?: return true)
            return true
        }
        return false
    }

    private fun executeTool(call: ToolCall, config: EngineConfig, grant: FolderGrant?, fromModel: Boolean) {
        val t0 = now()
        val argsSummary = shortArgs(call)
        val index = ++stepSeq
        val needsFolder = call.tool in FOLDER_TOOLS
        if (needsFolder && grant == null) {
            addItem(Tl.Action(nextItem(), index, call.tool, argsSummary, ActionOutcome.FAILED,
                "No folder is authorized in this conversation; pick one in the chat header to enable file tools.", "", 0))
            return
        }
        try {
            when (call.tool) {
                "list_files" -> doList(call, index, argsSummary, t0)
                "read_file" -> doRead(call, index, argsSummary, t0, config)
                "search_text" -> doSearch(call, index, argsSummary, t0, config)
                "count_lines" -> doCountLines(call, index, argsSummary, t0, config)
                "hash_file" -> doHash(call, index, argsSummary, t0, config)
                "write_file" -> doWrite(call, config, grant, index, argsSummary, t0)
                "append_file" -> doAppend(call, config, grant, index, argsSummary, t0)
                "run_check" -> doCheck(call, config, index, argsSummary, t0)
                "python_tool" -> doPythonTool(call, index, argsSummary, t0, config)
                "python_script" -> doPythonScript(call, config, grant, index, argsSummary, t0)
                else -> addItem(Tl.Action(nextItem(), index, call.tool, argsSummary, ActionOutcome.FAILED,
                    "Unknown tool '${call.tool}'.", "", now() - t0))
            }
        } catch (e: FsException) {
            addItem(Tl.Action(nextItem(), index, call.tool, argsSummary, ActionOutcome.FAILED,
                "${e.message}", e.userAction(), now() - t0))
        } catch (e: Rejected) {
            addItem(Tl.Action(nextItem(), index, call.tool, argsSummary, ActionOutcome.FAILED,
                "Rejected: ${e.message}", "", now() - t0))
        } catch (e: Exception) {
            addItem(Tl.Action(nextItem(), index, call.tool, argsSummary, ActionOutcome.FAILED,
                "Tool error: ${e.message ?: e.javaClass.simpleName}", "", now() - t0))
        }
    }

    private class Rejected(message: String) : Exception(message)

    // ---- read-only tools ---------------------------------------------------------

    private fun doList(call: ToolCall, index: Int, argsSummary: String, t0: Long) {
        val sub = JsonLite.str(call.args, "path").orEmpty()
        if (sub.isNotEmpty()) validateRelPath(sub)
        val entries = fs.listTree(current.value.task!!.rootId)
        val scoped = if (sub.isEmpty()) entries else entries.filter { it.path == sub || it.path.startsWith("$sub/") }
        val names = scoped.take(120).joinToString("\n") { (if (it.folder) "${it.path}/" else it.path) }
        addItem(Tl.Action(nextItem(), index, "list_files", argsSummary, ActionOutcome.OK,
            "${scoped.size} entries${if (sub.isNotEmpty()) " under '$sub'" else ""}.", names.take(6000), now() - t0))
    }

    private fun doRead(call: ToolCall, index: Int, argsSummary: String, t0: Long, config: EngineConfig) {
        countRead(config)
        val path = validateRelPath(JsonLite.str(call.args, "path")
            ?: throw Rejected("read_file needs \"path\"."))
        val pendingCreate = current.value.writes.firstOrNull { it.pending() && it.kind == TrackedWrite.Kind.CREATE && it.path == path }
        if (pendingCreate != null) {
            addItem(Tl.Action(nextItem(), index, "read_file", argsSummary, ActionOutcome.BLOCKED,
                "'$path' is planned but not written yet (creation awaiting your approval).", "", now() - t0))
            return
        }
        val entry = fs.entry(current.value.task!!.rootId, path)
        if (entry.folder) throw Rejected("'$path' is a folder.")
        val text = fs.readText(entry, READ_CAP)
        val pendingReplace = current.value.writes.firstOrNull { it.pending() && it.kind == TrackedWrite.Kind.REPLACE && it.path == path }
        val note = if (pendingReplace != null) "\n[note: a proposed edit of this file is still awaiting approval; this is the CURRENT on-disk content]" else ""
        addItem(Tl.Action(nextItem(), index, "read_file", argsSummary, ActionOutcome.OK,
            "Read '$path' (${text.length} chars).", (text.take(12000) + note), now() - t0))
    }

    private fun doSearch(call: ToolCall, index: Int, argsSummary: String, t0: Long, config: EngineConfig) {
        countRead(config)
        val query = JsonLite.str(call.args, "query") ?: throw Rejected("search_text needs \"query\".")
        if (query.isEmpty() || query.length > 128) throw Rejected("Query must be 1–128 chars.")
        val sub = JsonLite.str(call.args, "path").orEmpty()
        if (sub.isNotEmpty()) validateRelPath(sub)
        val rootId = current.value.task!!.rootId
        val entries = fs.listTree(rootId).filter { !it.folder && FileKinds.isTextName(it.name) }
            .filter { sub.isEmpty() || it.path == sub || it.path.startsWith("$sub/") }
        val hits = mutableListOf<String>()
        var searched = 0
        for (entry in entries.take(200)) {
            if (cancelFlag.get()) break
            val text = runCatching { fs.readText(entry, 256 * 1024) }.getOrNull() ?: continue
            searched++
            var count = 0
            var start = 0
            while (count <= 999) {
                val at = text.indexOf(query, start)
                if (at < 0) break
                count++; start = at + query.length
            }
            if (count > 0) hits.add("${entry.path}: $count")
        }
        addItem(Tl.Action(nextItem(), index, "search_text", argsSummary, ActionOutcome.OK,
            "Searched $searched files; ${hits.size} with matches.", hits.joinToString("\n").take(4000), now() - t0))
    }

    private fun doCountLines(call: ToolCall, index: Int, argsSummary: String, t0: Long, config: EngineConfig) {
        countRead(config)
        val path = validateRelPath(JsonLite.str(call.args, "path") ?: throw Rejected("count_lines needs \"path\"."))
        val pendingCreate = current.value.writes.firstOrNull { it.pending() && it.path == path }
        if (pendingCreate != null) {
            addItem(Tl.Action(nextItem(), index, "count_lines", argsSummary, ActionOutcome.BLOCKED,
                "'$path' is planned but not written yet (awaiting approval).", "", now() - t0))
            return
        }
        val entry = fs.entry(current.value.task!!.rootId, path)
        val text = fs.readText(entry, 256 * 1024)
        addItem(Tl.Action(nextItem(), index, "count_lines", argsSummary, ActionOutcome.OK,
            "'$path' has ${dev.forgeai.workspace.DiffTool.splitLines(text).size} lines.", "", now() - t0))
    }

    private fun doHash(call: ToolCall, index: Int, argsSummary: String, t0: Long, config: EngineConfig) {
        countRead(config)
        val path = validateRelPath(JsonLite.str(call.args, "path") ?: throw Rejected("hash_file needs \"path\"."))
        val algo = (JsonLite.str(call.args, "algo") ?: "sha256").lowercase()
        if (algo !in setOf("sha256", "md5")) throw Rejected("algo must be sha256 or md5.")
        val entry = fs.entry(current.value.task!!.rootId, path)
        val bytes = fs.readBytes(entry, READ_CAP)
        val digest = java.security.MessageDigest.getInstance(if (algo == "sha256") "SHA-256" else "MD5").digest(bytes)
        val hex = digest.joinToString("") { "%02x".format(it) }
        addItem(Tl.Action(nextItem(), index, "hash_file", argsSummary, ActionOutcome.OK,
            "$algo('$path') = $hex (${bytes.size} bytes).", hex, now() - t0))
    }

    private var readCount = 0
    private fun countRead(config: EngineConfig) {
        if (++readCount > config.maxReads) throw Rejected("Read budget exhausted (max ${config.maxReads}).")
    }

    // ---- write tools ---------------------------------------------------------------

    private fun doWrite(call: ToolCall, config: EngineConfig, grant: FolderGrant?,
                        index: Int, argsSummary: String, t0: Long) {
        val path = validateRelPath(JsonLite.str(call.args, "path") ?: throw Rejected("write_file needs \"path\"."))
        val content = JsonLite.str(call.args, "content") ?: throw Rejected("write_file needs \"content\".")
        if (content.length > WRITE_CAP) throw Rejected("Content exceeds the 1 MiB write limit.")
        if (current.value.writes.count { it.taskId == current.value.task?.id } >= config.maxWrites) throw Rejected("Write budget exhausted (max ${config.maxWrites}).")
        if (grant != null && !grant.writable) {
            throw FsException("The folder '${grant.displayName}' is read-only.", FsException.Kind.NO_WRITE_GRANT)
        }
        if (grant == null) throw Rejected("No folder is authorized in this conversation; pick one in the chat header.")
        val rootId = current.value.task!!.rootId
        val existing = runCatching { fs.entry(rootId, path) }.getOrNull()
        val tracked = if (existing != null && !existing.folder) {
            val original = fs.readText(existing, READ_CAP)
            TrackedWrite(++writeSeq, path, TrackedWrite.Kind.REPLACE, content, original, WriteOutcome.PROPOSED,
                taskId = current.value.task!!.id,
                diff = dev.forgeai.workspace.DiffTool.unifiedText(original, content))
        } else {
            val dir = path.substringBeforeLast('/', "")
            if (dir.isNotEmpty()) fs.entry(rootId, dir).let { if (!it.folder) throw Rejected("'$dir' is not a folder.") }
            TrackedWrite(++writeSeq, path, TrackedWrite.Kind.CREATE, content, null, WriteOutcome.PROPOSED,
                taskId = current.value.task!!.id,
                diff = dev.forgeai.workspace.DiffTool.unified(emptyList(),
                    dev.forgeai.workspace.DiffTool.splitLines(content)))
        }
        update { it.copy(writes = it.writes + tracked) }
        addItem(Tl.Write(nextItem(), tracked.writeId, tracked.path, null, tracked.kind, WriteOutcome.PROPOSED, tracked.diff, null))
        if (config.mode == AgentMode.AUTO) {
            applyWrite(tracked, config, grant)
            val fresh = current.value.writeById(tracked.writeId) ?: tracked
            addItem(Tl.Action(nextItem(), index, "write_file", argsSummary,
                if (fresh.savedOrVerified()) ActionOutcome.OK else ActionOutcome.FAILED,
                autoWriteSummary(fresh), "", now() - t0))
        } else {
            addItem(Tl.Action(nextItem(), index, "write_file", argsSummary, ActionOutcome.BLOCKED,
                "Proposed change to '${tracked.path}' — waiting for your approval below.", "", now() - t0))
            addItem(Tl.Approval(nextItem(), tracked.writeId, tracked.path, tracked.kind, tracked.diff, null))
            // NOTE: the phase is NOT switched here. The caller parks the loop once the
            // current plan/turn finishes, so "awaiting approval" always means the worker
            // is idle and the timeline is complete for this turn.
        }
    }

    private fun TrackedWrite.savedOrVerified() =
        outcome == WriteOutcome.SAVED || outcome == WriteOutcome.VERIFIED

    private fun autoWriteSummary(t: TrackedWrite): String = when (t.outcome) {
        WriteOutcome.VERIFIED -> "Saved as '${t.realPath()}' and verified by re-read."
        WriteOutcome.SAVED -> "Saved as '${t.realPath()}'."
        else -> "Write failed: ${t.error ?: "unknown error"}"
    }

    private fun doAppend(call: ToolCall, config: EngineConfig, grant: FolderGrant?,
                         index: Int, argsSummary: String, t0: Long) {
        val path = validateRelPath(JsonLite.str(call.args, "path") ?: throw Rejected("append_file needs \"path\"."))
        val block = JsonLite.str(call.args, "content") ?: throw Rejected("append_file needs \"content\".")
        val rootId = current.value.task!!.rootId
        val pendingCreate = current.value.writes.firstOrNull { it.pending() && it.path == path }
        if (pendingCreate != null) {
            addItem(Tl.Action(nextItem(), index, "append_file", argsSummary, ActionOutcome.BLOCKED,
                "'$path' is planned but not written yet (awaiting approval); appending now would lose your review.", "", now() - t0))
            return
        }
        val entry = fs.entry(rootId, path)
        if (entry.folder) throw Rejected("'$path' is a folder.")
        val original = fs.readText(entry, READ_CAP)
        val merged = if (original.isEmpty() || original.endsWith("\n")) original + block else "$original\n$block"
        val patched = JVal.JObj(linkedMapOf("path" to JVal.JStr(path), "content" to JVal.JStr(merged)))
        doWrite(ToolCall("write_file", patched, call.fromModel), config, grant, index, argsSummary, t0)
    }

    /**
     * Applies one tracked write for real. The provider's stored document is the source of
     * truth: its real path is recorded, verified by re-read, and pushed into the working
     * copy BEFORE any dependent check is allowed to run.
     */
    private fun applyWrite(tracked: TrackedWrite, config: EngineConfig, grant: FolderGrant?) {
        val rootId = current.value.task!!.rootId
        val bytes = tracked.newText.toByteArray(Charsets.UTF_8)
        if (grant == null || !grant.writable) {
            updateWrite(tracked.writeId) { it.copy(outcome = WriteOutcome.SAVE_FAILED,
                error = "The folder is read-only. ${FsException("", FsException.Kind.NO_WRITE_GRANT).userAction()}") }
            replaceWriteTimeline(tracked.writeId) { it.copy(outcome = WriteOutcome.SAVE_FAILED,
                error = "The folder is read-only. Grant write access, then retry.") }
            return
        }
        try {
            val real: FileRef = if (tracked.kind == TrackedWrite.Kind.CREATE) {
                val dir = tracked.path.substringBeforeLast('/', "")
                val name = tracked.path.substringAfterLast('/')
                fs.createFile(rootId, dir, name, bytes, FileKinds.mimeFor(name))
            } else {
                val entry = fs.entry(rootId, tracked.path)
                fs.replaceFile(entry, bytes, tracked.originalText?.toByteArray(Charsets.UTF_8))
                entry
            }
            val realPath = real.path.ifEmpty { real.name }
            val renamed = realPath != tracked.path && tracked.kind == TrackedWrite.Kind.CREATE
            updateWrite(tracked.writeId) { it.copy(outcome = WriteOutcome.SAVED, providerPath = realPath) }
            replaceWriteTimeline(tracked.writeId) { it.copy(outcome = WriteOutcome.SAVED, providerPath = realPath) }
            if (renamed) {
                addItem(Tl.Info(nextItem(),
                    "The storage provider saved the document as '$realPath' instead of '${tracked.path}'. " +
                        "All later steps use the real name."))
            }
            // Verification: re-read the REAL document and compare.
            val verifyRef = fs.entry(rootId, realPath)
            val back = fs.readBytes(verifyRef, WRITE_CAP)
            if (back.contentEquals(bytes)) {
                updateWrite(tracked.writeId) { it.copy(outcome = WriteOutcome.VERIFIED) }
                replaceWriteTimeline(tracked.writeId) { it.copy(outcome = WriteOutcome.VERIFIED) }
            } else {
                updateWrite(tracked.writeId) { it.copy(outcome = WriteOutcome.VERIFY_FAILED,
                    error = "Re-read does not match what was written (${back.size} bytes back vs ${bytes.size} written).") }
                replaceWriteTimeline(tracked.writeId) { it.copy(outcome = WriteOutcome.VERIFY_FAILED,
                    error = "Re-read does not match what was written.") }
                addItem(Tl.Problem(nextItem(),
                    "Verification of '$realPath' failed: the stored content differs from what was written. " +
                        "Dependent checks will be skipped. ${FsException("", FsException.Kind.VERIFY_FAILED).userAction()}"))
            }
            // Working copy updated with the VERIFIED bytes (or the best-known bytes) under the REAL name.
            runCatching { mirror.put(realPath, back) }
        } catch (e: FsException) {
            updateWrite(tracked.writeId) { it.copy(outcome = WriteOutcome.SAVE_FAILED, error = "${e.message}") }
            replaceWriteTimeline(tracked.writeId) { it.copy(outcome = WriteOutcome.SAVE_FAILED, error = "${e.message}") }
            addItem(Tl.Problem(nextItem(), "Could not save '${tracked.path}': ${e.message} ${e.userAction()} " +
                "Steps that depend on this write will be skipped."))
        } catch (e: Exception) {
            updateWrite(tracked.writeId) { it.copy(outcome = WriteOutcome.SAVE_FAILED,
                error = e.message ?: e.javaClass.simpleName) }
            replaceWriteTimeline(tracked.writeId) { it.copy(outcome = WriteOutcome.SAVE_FAILED,
                error = e.message ?: e.javaClass.simpleName) }
            addItem(Tl.Problem(nextItem(), "Could not save '${tracked.path}': ${e.message ?: e.javaClass.simpleName}. " +
                "Steps that depend on this write will be skipped."))
        }
    }

    private fun replaceWriteTimeline(writeId: Int, f: (Tl.Write) -> Tl.Write) = update { st ->
        st.copy(items = st.items.map { if (it is Tl.Write && it.writeId == writeId) f(it) else it })
    }

    // ---- checks ---------------------------------------------------------------------

    private fun doCheck(call: ToolCall, config: EngineConfig, index: Int, argsSummary: String, t0: Long) {
        val command = JsonLite.str(call.args, "command") ?: throw Rejected("run_check needs \"command\".")
        val argv = (JsonLite.arr(call.args, "args") ?: emptyList()).mapNotNull { (it as? JVal.JStr)?.value }
        val taskStart = current.value.items.indexOfLast { it is Tl.Objective }.coerceAtLeast(0)
        val checkIndex = current.value.items.drop(taskStart).count { it is Tl.Check } + 1
        if (checkIndex > config.maxChecks) throw Rejected("Check budget exhausted (max ${config.maxChecks}).")

        // Dependency: any positional argument referencing a write that is not yet on disk.
        val positionals = argv.filter { !it.startsWith("-") }
        val dependsOn = current.value.writes.filter { w ->
            positionals.any { it == w.path || it == w.realPath() }
        }
        val blocking = dependsOn.filter { it.outcome == WriteOutcome.PROPOSED }
        val failedDeps = dependsOn.filter { it.outcome == WriteOutcome.SAVE_FAILED || it.outcome == WriteOutcome.VERIFY_FAILED || it.outcome == WriteOutcome.REFUSED }

        if (blocking.isNotEmpty()) {
            val names = blocking.joinToString(", ") { "'${it.path}'" }
            addItem(Tl.Check(nextItem(), command, argv, CheckOutcome.AWAITING_WRITE, null,
                "Waiting for your approval of $names — the file is not on disk yet, so the check was not run.",
                "", "", 0))
            return
        }
        if (failedDeps.isNotEmpty()) {
            val why = failedDeps.joinToString(", ") { "'${it.path}' (${it.outcome.label})" }
            addItem(Tl.Check(nextItem(), command, argv, CheckOutcome.SKIPPED, null,
                "Skipped: depends on $why, which was not saved.",
                "", "", 0))
            return
        }

        // Remap planned paths to the real stored names before running.
        val remapped = argv.map { arg ->
            if (arg.startsWith("-")) arg
            else {
                val w = dependsOn.firstOrNull { it.path == arg }
                if (w != null && w.settled()) w.realPath() else arg
            }
        }
        val cwd = mirror.runnerDir()
        if (cwd == null) {
            addItem(Tl.Check(nextItem(), command, remapped, CheckOutcome.NOT_EXECUTED, null,
                "Not executed: no real working copy is available in this environment.",
                "", "", 0))
            return
        }
        val r = checks.run(cwd, command, remapped, config.checkTimeoutMs)
        val outcome = when {
            !r.started -> CheckOutcome.NOT_EXECUTED
            r.timedOut -> CheckOutcome.FAILED
            r.cancelled -> CheckOutcome.CANCELLED
            r.exitCode == 0 -> CheckOutcome.PASSED
            else -> CheckOutcome.FAILED
        }
        val reason = when {
            !r.started -> "Not executed: ${r.failure}"
            r.timedOut -> "Timed out after ${config.checkTimeoutMs / 1000}s."
            r.cancelled -> "Cancelled."
            else -> ""
        }
        addItem(Tl.Check(nextItem(), command, remapped, outcome, r.exitCode, reason,
            r.stdout.take(2000), r.stderr.take(1000), r.durationMs))
    }

    /** Runs the checks that were deferred while writes awaited approval. */
    private fun runDeferredChecks(config: EngineConfig, grant: FolderGrant?) {
        val waiting = current.value.items.filterIsInstance<Tl.Check>()
            .filter { it.outcome == CheckOutcome.AWAITING_WRITE }
        if (waiting.isEmpty()) return
        // Post-restart approval: re-open and re-sync the working copy first.
        if (mirror.runnerDir() == null) {
            runCatching {
                mirror.open(current.value.task?.id?.toString() ?: "restored")
                val entries = fs.listTree(current.value.task!!.rootId)
                    .filter { !it.folder && FileKinds.isTextName(it.name) && it.size in 0..READ_CAP }
                mirror.syncIn(entries.take(MAX_SYNC_FILES)) { ref -> fs.readBytes(ref, READ_CAP) }
            }
        }
        // Make sure the working copy reflects every settled write before re-running.
        for (w in current.value.writes.filter { it.settled() && it.outcome != WriteOutcome.REFUSED }) {
            if (!mirror.contains(w.realPath())) {
                runCatching {
                    val ref = fs.entry(current.value.task!!.rootId, w.realPath())
                    val bytes = fs.readBytes(ref, WRITE_CAP)
                    mirror.put(w.realPath(), bytes)
                }
            }
        }
        for (item in waiting) {
            if (checkCancelled()) return
            val deps = current.value.writes.filter { w -> item.args.any { it == w.path || it == w.realPath() } }
            val bad = deps.filter { it.outcome == WriteOutcome.REFUSED || it.outcome == WriteOutcome.SAVE_FAILED || it.outcome == WriteOutcome.VERIFY_FAILED || it.outcome == WriteOutcome.CANCELLED }
            if (bad.isNotEmpty()) {
                replaceItem(item.id, item.copy(outcome = CheckOutcome.SKIPPED,
                    reason = "Skipped: depends on ${bad.joinToString(", ") { "'${it.path}' (${it.outcome.label})" }}, which was not saved."))
                continue
            }
            val remapped = item.args.map { arg ->
                if (arg.startsWith("-")) arg
                else deps.firstOrNull { it.path == arg }?.realPath() ?: arg
            }
            val cwd = mirror.runnerDir()
            if (cwd == null) {
                replaceItem(item.id, item.copy(outcome = CheckOutcome.NOT_EXECUTED,
                    reason = "Not executed: no real working copy available."))
                continue
            }
            val r = checks.run(cwd, item.command, remapped, config.checkTimeoutMs)
            val outcome = when {
                !r.started -> CheckOutcome.NOT_EXECUTED
                r.timedOut -> CheckOutcome.FAILED
                r.cancelled -> CheckOutcome.CANCELLED
                r.exitCode == 0 -> CheckOutcome.PASSED
                else -> CheckOutcome.FAILED
            }
            val reason = when {
                !r.started -> "Not executed: ${r.failure}"
                r.timedOut -> "Timed out after ${config.checkTimeoutMs / 1000}s."
                r.cancelled -> "Cancelled."
                else -> ""
            }
            replaceItem(item.id, item.copy(args = remapped, outcome = outcome, exitCode = r.exitCode,
                reason = reason, stdout = r.stdout.take(2000), stderr = r.stderr.take(1000), durationMs = r.durationMs))
        }
    }

    // ---- python tools ------------------------------------------------------------------

    private fun doPythonTool(call: ToolCall, index: Int, argsSummary: String, t0: Long, config: EngineConfig) {
        val tool = JsonLite.str(call.args, "tool") ?: throw Rejected("python_tool needs \"tool\".")
        val allowed = setOf("json_summary", "csv_summary", "text_stats", "jsonl_count")
        if (tool !in allowed) throw Rejected("python_tool must be one of: ${allowed.joinToString()}.")
        if (!py.available) {
            addItem(Tl.Action(nextItem(), index, "python_tool", argsSummary, ActionOutcome.NOT_RUN,
                "Not executed: the Python runtime is not available in this build.", "", now() - t0))
            return
        }
        countRead(config)
        val path = validateRelPath(JsonLite.str(call.args, "path") ?: throw Rejected("python_tool needs \"path\"."))
        val pendingCreate = current.value.writes.firstOrNull { it.pending() && it.path == path }
        if (pendingCreate != null) {
            addItem(Tl.Action(nextItem(), index, "python_tool", argsSummary, ActionOutcome.BLOCKED,
                "'$path' is planned but not written yet (awaiting approval).", "", now() - t0))
            return
        }
        val entry = fs.entry(current.value.task!!.rootId, path)
        val input = fs.readText(entry, 512 * 1024)
        val r = py.runPredefined(tool, input)
        addItem(Tl.Action(nextItem(), index, "python_tool($tool)", argsSummary,
            if (r.ok) ActionOutcome.OK else ActionOutcome.FAILED,
            if (r.ok) "Python $tool on '$path' finished in ${r.durationMs} ms." else "Python $tool failed.",
            (r.stdout.take(4000) + (if (r.stderr.isNotBlank()) "\n[stderr] ${r.stderr.take(1000)}" else "")), now() - t0))
    }

    private fun doPythonScript(call: ToolCall, config: EngineConfig, grant: FolderGrant?,
                               index: Int, argsSummary: String, t0: Long) {
        val code = JsonLite.str(call.args, "code") ?: throw Rejected("python_script needs \"code\".")
        val input = JsonLite.str(call.args, "input").orEmpty()
        if (code.length > 64 * 1024) throw Rejected("Script exceeds the 64 KiB limit.")
        if (!py.available) {
            addItem(Tl.Action(nextItem(), index, "python_script", argsSummary, ActionOutcome.NOT_RUN,
                "Not executed: the Python runtime is not available in this build.", "", now() - t0))
            return
        }
        // Free-form scripts ALWAYS require explicit approval, even in auto mode.
        val tracked = TrackedWrite(++writeSeq, SCRIPT_MARKER, TrackedWrite.Kind.CREATE, code, null,
            WriteOutcome.PROPOSED, diff = "", error = null, taskId = current.value.task?.id ?: 0)
        update { it.copy(writes = it.writes + tracked) }
        addItem(Tl.Action(nextItem(), index, "python_script", argsSummary, ActionOutcome.BLOCKED,
            "Script proposed — it will only run after your explicit approval below (isolated, time-limited, no network).", "", now() - t0))
        addItem(Tl.Approval(nextItem(), tracked.writeId, "(python script)", tracked.kind, "", script = code))
        // Phase parking happens at loop level (pending write), not here.
    }

    // ---- finish -------------------------------------------------------------------------

    private fun finishTask(config: EngineConfig, grant: FolderGrant?) {
        if (checkCancelled()) return
        runDeferredChecks(config, grant ?: return)
        val s = current.value
        val text = finalReportText(cancelled = false)
        update { it.copy(phase = RunPhase.DONE, task = it.task?.copy(finishedAt = now()),
            items = it.items + Tl.FinalReport(nextItem(), text)) }
    }

    private fun finalReportText(cancelled: Boolean): String {
        val s = current.value
        val taskStart = s.items.indexOfLast { it is Tl.Objective }.coerceAtLeast(0)
        val taskItems = s.items.drop(taskStart)
        val w = s.writes.filter { it.taskId == s.task?.id }
        val verified = w.filter { it.outcome == WriteOutcome.VERIFIED }
        val savedOnly = w.filter { it.outcome == WriteOutcome.SAVED }
        val saveFailed = w.filter { it.outcome == WriteOutcome.SAVE_FAILED }
        val verifyFailed = w.filter { it.outcome == WriteOutcome.VERIFY_FAILED }
        val refused = w.filter { it.outcome == WriteOutcome.REFUSED }
        val cancelledW = w.filter { it.outcome == WriteOutcome.CANCELLED }
        val scripts = w.filter { it.path == SCRIPT_MARKER }
        val fileWrites = w.filter { it.path != SCRIPT_MARKER }
        val checks = taskItems.filterIsInstance<Tl.Check>()
        val passed = checks.filter { it.outcome == CheckOutcome.PASSED }
        val failed = checks.filter { it.outcome == CheckOutcome.FAILED }
        val skipped = checks.filter { it.outcome == CheckOutcome.SKIPPED }
        val notRun = checks.filter { it.outcome == CheckOutcome.NOT_EXECUTED || it.outcome == CheckOutcome.AWAITING_WRITE || it.outcome == CheckOutcome.CANCELLED }

        val lines = mutableListOf<String>()
        lines.add(if (cancelled) "Task cancelled." else "Task finished.")
        if (fileWrites.isNotEmpty()) {
            val appliedNames = (verified + savedOnly).map { it.realPath() }
            lines.add("Files: ${fileWrites.size} change(s) proposed — " +
                "${verified.size + savedOnly.size} saved${if (verified.isNotEmpty()) " and verified by re-read" else ""}" +
                (if (appliedNames.isNotEmpty()) " (${appliedNames.joinToString()})" else "") + ".")
        }
        if (saveFailed.isNotEmpty()) lines.add("Failed to save: ${saveFailed.joinToString { "'${it.path}' (${it.error ?: "?"})" }}.")
        if (verifyFailed.isNotEmpty()) lines.add("Verification failed for: ${verifyFailed.joinToString { "'${it.realPath()}'" }} — these files are NOT confirmed.")
        if (refused.isNotEmpty()) lines.add("Refused by you: ${refused.joinToString { "'${it.path}'" }} — dependent steps were skipped.")
        if (cancelledW.isNotEmpty()) lines.add("Cancelled before saving: ${cancelledW.joinToString { "'${it.path}'" }}.")
        if (scripts.isNotEmpty()) lines.add("Python scripts: ${scripts.size} proposed, ${scripts.count { it.outcome == WriteOutcome.RUN_OK }} approved and run, ${scripts.count { it.outcome == WriteOutcome.RUN_FAILED }} failed, ${scripts.count { it.outcome == WriteOutcome.REFUSED }} refused.")
        if (checks.isNotEmpty()) {
            lines.add("Checks: ${passed.size} passed, ${failed.size} failed, ${skipped.size} skipped, ${notRun.size} not executed.")
            failed.forEach { c -> lines.add("  • FAILED: ${c.command} ${c.args.joinToString(" ")} — exit ${c.exitCode ?: "?"} ${c.stderr.take(120)}") }
        }
        val problems = saveFailed.isNotEmpty() || verifyFailed.isNotEmpty() || failed.isNotEmpty()
        if (problems && !cancelled) lines.add("The task ended with problems; see the timeline above for the concrete errors.")
        return lines.joinToString("\n")
    }

    // ---- model prompt --------------------------------------------------------------------

    private fun buildModelPrompt(config: EngineConfig, grant: FolderGrant?): String {
        val s = current.value
        val folderBlock = if (grant == null) {
            "No folder is authorized in this conversation. File, shell and folder tools are disabled: if the user asks for file work, tell them to pick a folder in the chat header. Answer conversationally otherwise."
        } else {
            val listing = fs.listTree(config.rootId).take(80)
                .joinToString("\n") { (if (it.folder) "${it.path}/" else it.path) }.ifEmpty { "(empty folder)" }
            """Folder '${grant.displayName}' (${if (grant.writable) "read+write" else "read-only"}) contents:
$listing"""
        }
        val digest = StringBuilder()
        for (item in s.items) {
            when (item) {
                is Tl.Action -> digest.append("${item.index}. ${item.tool} ${item.argsSummary} -> ${item.outcome.label}: ${item.summary}\n")
                is Tl.Check -> digest.append("check ${item.command} ${item.args.joinToString(" ")} -> ${item.outcome.label}${if (item.exitCode != null) " (exit ${item.exitCode})" else ""}\n")
                is Tl.Info -> digest.append("note: ${item.text}\n")
                is Tl.Problem -> digest.append("problem: ${item.text}\n")
                else -> {}
            }
        }
        val digestText = digest.toString().takeLast(2400)
        val modeLine = when (config.mode) {
            AgentMode.REVIEW -> "review mode: every write_file/append_file stops for user approval; after the decision you receive the real outcome."
            AgentMode.AUTO -> "auto mode: writes inside the authorized folder are applied immediately; the user sees the diffs afterwards."
        }
        val sys = """
You are the FORGE AI local coding agent on an offline Android device. You work ONLY with the tools below.
$modeLine
$folderBlock

Rules:
1. Answer with exactly ONE tool call per turn as a single JSON object: {"tool":"...","args":{...}} and nothing else, OR, when the objective is fully done, reply with the final answer as plain text without JSON.
2. Use exact file names from the folder contents. To modify a file, read it first, then write_file with the complete new content.
3. Verify your work with run_check (e.g. wc -l file) after files are saved; check results are real command outputs.
4. python_tool can analyze a file (json_summary, csv_summary, text_stats, jsonl_count). python_script runs custom code but always needs user approval.
5. No network, no arbitrary shell, nothing outside the authorized folder.
""".trim() + "\nTools:\n${Planner.toolCatalog()}\n"
        val user = "Objective: ${config.objective.take(1200)}\n\nProgress so far:\n${digestText.ifBlank { "(nothing yet)" }}\n\nContinue with the next single tool call as JSON, or reply with the final answer text."
        return chatml(sys, user)
    }

    private fun chatml(system: String, user: String): String {
        fun safe(t: String) = t.replace("<|", "< |").replace('\u0000', ' ')
        return "<|im_start|>system\n${safe(system)}<|im_end|>\n" +
            "<|im_start|>user\n${safe(user)}<|im_end|>\n" +
            "<|im_start|>assistant\n"
    }

    // ---- helpers ---------------------------------------------------------------------------

    private fun validateRelPath(path: String): String {
        if (path.isEmpty() || path.length > 512) throw Rejected("Invalid path length.")
        if (path.contains('\\') || path.startsWith("/")) throw Rejected("Use relative paths inside the authorized folder.")
        path.split('/').forEach { part ->
            if (part.isEmpty() || part == "." || part == "..") throw Rejected("Paths must stay inside the folder.")
        }
        return path
    }

    private fun shortArgs(call: ToolCall): String {
        val parts = mutableListOf<String>()
        JsonLite.str(call.args, "path")?.let { parts.add(it) }
        JsonLite.str(call.args, "query")?.let { parts.add("\"${it.take(40)}\"") }
        JsonLite.str(call.args, "command")?.let { c ->
            parts.add(c)
            JsonLite.arr(call.args, "args")?.forEach { a -> (a as? JVal.JStr)?.value?.let { parts.add(it.take(60)) } }
        }
        JsonLite.str(call.args, "tool")?.let { parts.add(it) }
        if (call.tool in setOf("write_file", "append_file")) parts.add("(${(JsonLite.str(call.args, "content") ?: "").length} chars)")
        if (call.tool == "python_script") parts.add("(${(JsonLite.str(call.args, "code") ?: "").length} chars)")
        return parts.joinToString(" ").take(200)
    }

    companion object {
        const val READ_CAP = 1024 * 1024
        const val WRITE_CAP = 1024 * 1024
        const val MAX_SYNC_FILES = 400
        const val SCRIPT_MARKER = "python_script"
        val FOLDER_TOOLS = setOf("list_files", "read_file", "search_text", "count_lines",
            "hash_file", "write_file", "append_file", "run_check", "python_tool")
    }
}
