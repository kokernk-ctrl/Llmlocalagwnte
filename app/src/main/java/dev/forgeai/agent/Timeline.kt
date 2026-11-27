package dev.forgeai.agent

import dev.forgeai.tools.JVal
import dev.forgeai.tools.JsonLite

/**
 * Conversation timeline and the explicit state machine that fixes the v0.3.0 reporting
 * bugs. Every step carries a distinct outcome; "ok" is never inferred from prose, and a
 * failed verification can never be reported as a pass.
 */

enum class RunPhase(val label: String, val terminal: Boolean) {
    IDLE("Idle", true),
    RUNNING("Working", false),
    AWAITING_APPROVAL("Waiting for your approval", false),
    DONE("Finished", true),
    FAILED("Failed", true),
    CANCELLED("Cancelled", true),
}

/** Outcome of a generic tool action. */
enum class ActionOutcome(val label: String) {
    OK("ok"), FAILED("failed"), BLOCKED("waiting"), NOT_RUN("not executed"),
}

/** Lifecycle of a proposed/real file change. */
enum class WriteOutcome(val label: String) {
    PROPOSED("pending approval"),
    SAVED("saved"),
    SAVE_FAILED("failed to save"),
    VERIFIED("verified"),
    VERIFY_FAILED("verification failed"),
    REFUSED("refused"),
    CANCELLED("cancelled"),
    RUN_OK("ran"),          // python script approvals: executed successfully
    RUN_FAILED("failed to run"), // python script approvals: execution failed
}

/** Outcome of a local check command. */
enum class CheckOutcome(val label: String) {
    PASSED("passed"),
    FAILED("failed"),
    AWAITING_WRITE("awaiting approval"),
    SKIPPED("skipped"),
    NOT_EXECUTED("not executed"),
    CANCELLED("cancelled"),
}

/** One item rendered in the conversation timeline. */
sealed class Tl {
    abstract val id: Long

    data class Objective(override val id: Long, val text: String) : Tl()
    data class AgentText(override val id: Long, val text: String) : Tl()
    data class PlanNote(override val id: Long, val text: String, val source: String) : Tl()
    data class Action(override val id: Long, val index: Int, val tool: String, val argsSummary: String,
                      val outcome: ActionOutcome, val summary: String, val detail: String,
                      val durationMs: Long) : Tl()
    data class Write(override val id: Long, val writeId: Int, val path: String, val providerPath: String?,
                     val kind: TrackedWrite.Kind, val outcome: WriteOutcome, val diff: String,
                     val error: String?) : Tl()
    /** Active approval request rendered with inline buttons. */
    data class Approval(override val id: Long, val writeId: Int, val path: String,
                        val kind: TrackedWrite.Kind, val diff: String, val script: String?) : Tl()
    data class Check(override val id: Long, val command: String, val args: List<String>,
                     val outcome: CheckOutcome, val exitCode: Int?, val reason: String,
                     val stdout: String, val stderr: String, val durationMs: Long) : Tl()
    data class Info(override val id: Long, val text: String) : Tl()
    data class Problem(override val id: Long, val text: String) : Tl()
    data class FinalReport(override val id: Long, val text: String) : Tl()
}

/** A tracked write: planned path, real provider path, explicit outcome. */
data class TrackedWrite(
    val writeId: Int,
    val path: String,               // planned relative path
    val kind: Kind,
    val newText: String,
    val originalText: String?,      // null for creates
    val outcome: WriteOutcome,
    val providerPath: String? = null, // real stored path when it differs from plan
    val error: String? = null,
    val diff: String = "",
    val taskId: Long = 0,           // which conversation task proposed it
) {
    enum class Kind { CREATE, REPLACE }
    /** The path that actually exists on storage once saved. */
    fun realPath(): String = providerPath ?: path
    fun pending(): Boolean = outcome == WriteOutcome.PROPOSED
    fun settled(): Boolean = outcome != WriteOutcome.PROPOSED
}

/** Task configuration with concrete budgets enforced by the engine. */
data class EngineConfig(
    val objective: String,
    val mode: AgentMode,
    val rootId: String,
    val useModel: Boolean,
    val maxSteps: Int = 14,
    val maxWrites: Int = 6,
    val maxReads: Int = 24,
    val maxChecks: Int = 6,
    val maxModelTurns: Int = 12,
    val timeBudgetMs: Long = 240_000L,
    val checkTimeoutMs: Long = 30_000L,
    val replyTokens: Int = 320,
)

data class TaskInfo(
    val id: Long,
    val objective: String,
    val mode: AgentMode,
    val rootId: String,
    val folderName: String,
    val writable: Boolean,
    val startedAt: Long,
    val finishedAt: Long = 0,
)

/** Full engine state; checkpointed after every mutation. */
data class EngineState(
    val phase: RunPhase = RunPhase.IDLE,
    val task: TaskInfo? = null,
    val items: List<Tl> = emptyList(),
    val writes: List<TrackedWrite> = emptyList(),
    val planSource: String = "",
    val error: String? = null,
) {
    val active: Boolean get() = !phase.terminal && phase != RunPhase.IDLE
    val awaitingApproval: Boolean get() = phase == RunPhase.AWAITING_APPROVAL
    /** Interrupted-like: non-terminal phase persisted across process death. */
    val interruptedLike: Boolean get() = !phase.terminal && phase != RunPhase.IDLE

    fun writeById(id: Int): TrackedWrite? = writes.firstOrNull { it.writeId == id }
}

/** Serialized task persisted by [CheckpointPort]. */
data class SavedTask(val id: Long, val state: EngineState, val rootId: String?)

// ---- serialization (manual, JsonLite-based, versioned) ----------------------

internal object EngineSerde {
    const val VERSION = 2L

    fun write(t: SavedTask): String {
        val s = t.state
        val task = s.task
        fun optStr(v: String?): JVal = v?.let { JVal.JStr(it) } ?: JVal.JNull
        fun num(v: Long): JVal = JVal.JNum(v.toDouble())
        fun numI(v: Int): JVal = JVal.JNum(v.toDouble())
        val root = JVal.JObj(linkedMapOf(
            "v" to num(VERSION),
            "id" to num(t.id),
            "rootId" to optStr(t.rootId),
            "phase" to JVal.JStr(s.phase.name),
            "planSource" to JVal.JStr(s.planSource),
            "error" to optStr(s.error),
            "task" to (task?.let { tk ->
                JVal.JObj(linkedMapOf(
                    "id" to num(tk.id), "objective" to JVal.JStr(tk.objective),
                    "mode" to JVal.JStr(tk.mode.name), "rootId" to JVal.JStr(tk.rootId),
                    "folderName" to JVal.JStr(tk.folderName), "writable" to JVal.JBool(tk.writable),
                    "startedAt" to num(tk.startedAt), "finishedAt" to num(tk.finishedAt),
                ))
            } ?: JVal.JNull),
            "items" to JVal.JArr(s.items.map { item(it) }),
            "writes" to JVal.JArr(s.writes.map { w ->
                JVal.JObj(linkedMapOf(
                    "writeId" to numI(w.writeId), "path" to JVal.JStr(w.path),
                    "kind" to JVal.JStr(w.kind.name), "newText" to JVal.JStr(w.newText),
                    "originalText" to optStr(w.originalText), "outcome" to JVal.JStr(w.outcome.name),
                    "providerPath" to optStr(w.providerPath), "error" to optStr(w.error),
                    "diff" to JVal.JStr(w.diff.take(20_000)),
                    "taskId" to num(w.taskId),
                ))
            }),
        ))
        return JsonLite.write(root)
    }

    private fun item(i: Tl): JVal {
        val f = LinkedHashMap<String, JVal>()
        fun put(k: String, v: JVal) { f[k] = v }
        fun putS(k: String, v: String) { put(k, JVal.JStr(v)) }
        fun putL(k: String, v: Long) { put(k, JVal.JNum(v.toDouble())) }
        fun putI(k: String, v: Int) { put(k, JVal.JNum(v.toDouble())) }
        fun putO(k: String, v: String?) { put(k, v?.let { JVal.JStr(it) } ?: JVal.JNull) }
        when (i) {
            is Tl.Objective -> { putS("t", "obj"); putL("id", i.id); putS("text", i.text) }
            is Tl.AgentText -> { putS("t", "agent"); putL("id", i.id); putS("text", i.text) }
            is Tl.PlanNote -> { putS("t", "plan"); putL("id", i.id); putS("text", i.text); putS("source", i.source) }
            is Tl.Action -> { putS("t", "act"); putL("id", i.id); putI("index", i.index); putS("tool", i.tool);
                putS("args", i.argsSummary); putS("outcome", i.outcome.name); putS("summary", i.summary);
                putS("detail", i.detail.take(6000)); putL("dur", i.durationMs) }
            is Tl.Write -> { putS("t", "write"); putL("id", i.id); putI("wid", i.writeId); putS("path", i.path);
                putO("providerPath", i.providerPath); putS("kind", i.kind.name); putS("outcome", i.outcome.name);
                putS("diff", i.diff.take(20_000)); putO("error", i.error) }
            is Tl.Approval -> { putS("t", "appr"); putL("id", i.id); putI("wid", i.writeId); putS("path", i.path);
                putS("kind", i.kind.name); putS("diff", i.diff.take(20_000)); putO("script", i.script) }
            is Tl.Check -> { putS("t", "check"); putL("id", i.id); putS("cmd", i.command);
                put("args", JVal.JArr(i.args.map { JVal.JStr(it) })); putS("outcome", i.outcome.name);
                put("exit", i.exitCode?.let { JVal.JNum(it.toDouble()) } ?: JVal.JNull);
                putS("reason", i.reason); putS("out", i.stdout.take(3000)); putS("err", i.stderr.take(1500));
                putL("dur", i.durationMs) }
            is Tl.Info -> { putS("t", "info"); putL("id", i.id); putS("text", i.text) }
            is Tl.Problem -> { putS("t", "prob"); putL("id", i.id); putS("text", i.text) }
            is Tl.FinalReport -> { putS("t", "final"); putL("id", i.id); putS("text", i.text) }
        }
        return JVal.JObj(f)
    }

    fun read(text: String): SavedTask? {
        val v = runCatching { JsonLite.parse(text) }.getOrNull() as? JVal.JObj ?: return null
        if ((v.fields["v"] as? JVal.JNum)?.value?.toLong() != VERSION) return null
        val id = (v.fields["id"] as? JVal.JNum)?.value?.toLong() ?: return null
        val phase = runCatching { RunPhase.valueOf(JsonLite.str(v, "phase") ?: return null) }.getOrNull() ?: return null
        val taskObj = v.fields["task"] as? JVal.JObj
        val task = taskObj?.let { o ->
            TaskInfo(
                id = (o.fields["id"] as? JVal.JNum)?.value?.toLong() ?: return null,
                objective = JsonLite.str(o, "objective") ?: "",
                mode = runCatching { AgentMode.valueOf(JsonLite.str(o, "mode") ?: "REVIEW") }.getOrDefault(AgentMode.REVIEW),
                rootId = JsonLite.str(o, "rootId") ?: "",
                folderName = JsonLite.str(o, "folderName") ?: "",
                writable = JsonLite.bool(o, "writable") ?: false,
                startedAt = (o.fields["startedAt"] as? JVal.JNum)?.value?.toLong() ?: 0,
                finishedAt = (o.fields["finishedAt"] as? JVal.JNum)?.value?.toLong() ?: 0,
            )
        }
        val items = (v.fields["items"] as? JVal.JArr)?.items?.mapNotNull { raw ->
            val o = raw as? JVal.JObj ?: return@mapNotNull null
            val tid = (o.fields["id"] as? JVal.JNum)?.value?.toLong() ?: return@mapNotNull null
            when (JsonLite.str(o, "t")) {
                "obj" -> Tl.Objective(tid, JsonLite.str(o, "text") ?: "")
                "agent" -> Tl.AgentText(tid, JsonLite.str(o, "text") ?: "")
                "plan" -> Tl.PlanNote(tid, JsonLite.str(o, "text") ?: "", JsonLite.str(o, "source") ?: "")
                "act" -> Tl.Action(tid, (o.fields["index"] as? JVal.JNum)?.value?.toInt() ?: 0,
                    JsonLite.str(o, "tool") ?: "?", JsonLite.str(o, "args") ?: "",
                    runCatching { ActionOutcome.valueOf(JsonLite.str(o, "outcome") ?: "FAILED") }.getOrDefault(ActionOutcome.FAILED),
                    JsonLite.str(o, "summary") ?: "", JsonLite.str(o, "detail") ?: "",
                    (o.fields["dur"] as? JVal.JNum)?.value?.toLong() ?: 0)
                "write" -> Tl.Write(tid, (o.fields["wid"] as? JVal.JNum)?.value?.toInt() ?: 0,
                    JsonLite.str(o, "path") ?: "", JsonLite.str(o, "providerPath"),
                    runCatching { TrackedWrite.Kind.valueOf(JsonLite.str(o, "kind") ?: "CREATE") }.getOrNull() ?: TrackedWrite.Kind.CREATE,
                    runCatching { WriteOutcome.valueOf(JsonLite.str(o, "outcome") ?: "PROPOSED") }.getOrNull() ?: WriteOutcome.PROPOSED,
                    JsonLite.str(o, "diff") ?: "", JsonLite.str(o, "error"))
                "appr" -> Tl.Approval(tid, (o.fields["wid"] as? JVal.JNum)?.value?.toInt() ?: 0,
                    JsonLite.str(o, "path") ?: "",
                    runCatching { TrackedWrite.Kind.valueOf(JsonLite.str(o, "kind") ?: "CREATE") }.getOrNull() ?: TrackedWrite.Kind.CREATE,
                    JsonLite.str(o, "diff") ?: "", JsonLite.str(o, "script"))
                "check" -> Tl.Check(tid, JsonLite.str(o, "cmd") ?: "",
                    (o.fields["args"] as? JVal.JArr)?.items?.mapNotNull { (it as? JVal.JStr)?.value } ?: emptyList(),
                    runCatching { CheckOutcome.valueOf(JsonLite.str(o, "outcome") ?: "NOT_EXECUTED") }.getOrNull() ?: CheckOutcome.NOT_EXECUTED,
                    (o.fields["exit"] as? JVal.JNum)?.value?.toInt(), JsonLite.str(o, "reason") ?: "",
                    JsonLite.str(o, "out") ?: "", JsonLite.str(o, "err") ?: "",
                    (o.fields["dur"] as? JVal.JNum)?.value?.toLong() ?: 0)
                "info" -> Tl.Info(tid, JsonLite.str(o, "text") ?: "")
                "prob" -> Tl.Problem(tid, JsonLite.str(o, "text") ?: "")
                "final" -> Tl.FinalReport(tid, JsonLite.str(o, "text") ?: "")
                else -> null
            }
        } ?: emptyList()
        val writes = (v.fields["writes"] as? JVal.JArr)?.items?.mapNotNull { raw ->
            val o = raw as? JVal.JObj ?: return@mapNotNull null
            TrackedWrite(
                writeId = (o.fields["writeId"] as? JVal.JNum)?.value?.toInt() ?: return@mapNotNull null,
                path = JsonLite.str(o, "path") ?: "",
                kind = runCatching { TrackedWrite.Kind.valueOf(JsonLite.str(o, "kind") ?: "CREATE") }.getOrNull() ?: TrackedWrite.Kind.CREATE,
                newText = JsonLite.str(o, "newText") ?: "",
                originalText = JsonLite.str(o, "originalText"),
                outcome = runCatching { WriteOutcome.valueOf(JsonLite.str(o, "outcome") ?: "PROPOSED") }.getOrNull() ?: WriteOutcome.PROPOSED,
                providerPath = JsonLite.str(o, "providerPath"),
                error = JsonLite.str(o, "error"),
                diff = JsonLite.str(o, "diff") ?: "",
                taskId = (o.fields["taskId"] as? JVal.JNum)?.value?.toLong() ?: 0,
            )
        } ?: emptyList()
        return SavedTask(id, EngineState(phase = phase, task = task, items = items, writes = writes,
            planSource = JsonLite.str(v, "planSource") ?: "", error = JsonLite.str(v, "error")),
            JsonLite.str(v, "rootId"))
    }
}
