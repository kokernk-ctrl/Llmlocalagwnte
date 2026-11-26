package dev.forgeai.agent

import android.content.Context
import android.net.Uri
import dev.forgeai.tools.JVal
import dev.forgeai.tools.JsonLite
import java.io.File

/**
 * Checkpoints persist the agent task as JSON in app-private storage. On restart, any
 * non-terminal state is surfaced as interrupted — never reported as completed.
 */
class CheckpointStore(context: Context) {
    private val dir = File(context.noBackupFilesDir, "checkpoints").apply { mkdirs() }

    fun save(id: Long, state: AgentState, root: Uri?) {
        val tmp = File(dir, "task-$id.json.tmp")
        val out = File(dir, "task-$id.json")
        tmp.writeText(serialize(id, state, root))
        if (!tmp.renameTo(out)) {
            out.writeText(serialize(id, state, root))
            tmp.delete()
        }
    }

    fun list(): List<SavedTask> = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        .mapNotNull { f -> runCatching { deserialize(f.readText()) }.getOrNull() }
        .sortedByDescending { it.state.startedAt }

    fun load(id: Long): SavedTask? = File(dir, "task-$id.json").takeIf { it.isFile }
        ?.let { f -> runCatching { deserialize(f.readText()) }.getOrNull() }

    fun delete(id: Long) { File(dir, "task-$id.json").delete() }

    data class SavedTask(val id: Long, val state: AgentState, val root: Uri?)

    // ---- serialization --------------------------------------------------------

    private fun serialize(id: Long, state: AgentState, root: Uri?): String {
        val v = JVal.JObj(linkedMapOf(
            "id" to JVal.JNum(id.toDouble()),
            "root" to (root?.toString()?.let { JVal.JStr(it) } ?: JVal.JNull),
            "phase" to JVal.JStr(state.phase.name),
            "objective" to JVal.JStr(state.objective),
            "mode" to JVal.JStr(state.mode.name),
            "planSource" to JVal.JStr(state.planSource),
            "startedAt" to JVal.JNum(state.startedAt.toDouble()),
            "finishedAt" to JVal.JNum(state.finishedAt.toDouble()),
            "writesAttempted" to JVal.JNum(state.writesAttempted.toDouble()),
            "writesSucceeded" to JVal.JNum(state.writesSucceeded.toDouble()),
            "notice" to (state.notice?.let { JVal.JStr(it) } ?: JVal.JNull),
            "error" to (state.error?.let { JVal.JStr(it) } ?: JVal.JNull),
            "steps" to JVal.JArr(state.steps.map { step ->
                JVal.JObj(linkedMapOf(
                    "index" to JVal.JNum(step.index.toDouble()),
                    "tool" to JVal.JStr(step.tool),
                    "argsSummary" to JVal.JStr(step.argsSummary.take(400)),
                    "ok" to JVal.JBool(step.ok),
                    "summary" to JVal.JStr(step.summary.take(1200)),
                    "detail" to JVal.JStr(step.detail.take(4000)),
                    "durationMs" to JVal.JNum(step.durationMs.toDouble()),
                ))
            }),
            "pending" to JVal.JArr(state.pending.map { pw ->
                JVal.JObj(linkedMapOf(
                    "path" to JVal.JStr(pw.path),
                    "kind" to JVal.JStr(pw.kind.name),
                    "applied" to JVal.JBool(pw.applied),
                    "error" to (pw.error?.let { JVal.JStr(it) } ?: JVal.JNull),
                ))
            }),
            "appliedFiles" to JVal.JArr(state.appliedFiles.map { JVal.JStr(it) }),
            "checks" to JVal.JArr(state.checks.map { cr ->
                JVal.JObj(linkedMapOf(
                    "command" to JVal.JStr(cr.command),
                    "args" to JVal.JArr(cr.args.map { JVal.JStr(it) }),
                    "summary" to JVal.JStr(cr.summary),
                    "exitCode" to (cr.exitCode?.let { JVal.JNum(it.toDouble()) } ?: JVal.JNull),
                    "executed" to JVal.JBool(cr.executed),
                    "output" to JVal.JStr(cr.output.take(2000)),
                ))
            }),
        ))
        return JsonLite.write(v)
    }

    private fun deserialize(text: String): SavedTask? {
        val v = JsonLite.parse(text) as? JVal.JObj ?: return null
        val id = (v.fields["id"] as? JVal.JNum)?.value?.toLong() ?: return null
        val phaseName = JsonLite.str(v, "phase") ?: return null
        val phase = runCatching { AgentPhase.valueOf(phaseName) }.getOrNull() ?: return null
        val mode = runCatching { AgentMode.valueOf(JsonLite.str(v, "mode") ?: "REVIEW") }
            .getOrDefault(AgentMode.REVIEW)
        val objective = JsonLite.str(v, "objective") ?: ""
        val steps = (v.fields["steps"] as? JVal.JArr)?.items?.mapNotNull { s ->
            (s as? JVal.JObj)?.let { o ->
                StepRecord(
                    index = (o.fields["index"] as? JVal.JNum)?.value?.toInt() ?: 0,
                    tool = JsonLite.str(o, "tool") ?: "?",
                    argsSummary = JsonLite.str(o, "argsSummary") ?: "",
                    ok = JsonLite.bool(o, "ok") ?: false,
                    summary = JsonLite.str(o, "summary") ?: "",
                    detail = JsonLite.str(o, "detail") ?: "",
                    durationMs = (o.fields["durationMs"] as? JVal.JNum)?.value?.toLong() ?: 0,
                )
            }
        } ?: emptyList()
        val pending = (v.fields["pending"] as? JVal.JArr)?.items?.mapNotNull { s ->
            (s as? JVal.JObj)?.let { o ->
                PendingWrite(
                    path = JsonLite.str(o, "path") ?: "",
                    kind = runCatching { PendingWrite.Kind.valueOf(JsonLite.str(o, "kind") ?: "CREATE") }
                        .getOrDefault(PendingWrite.Kind.CREATE),
                    newText = "", originalText = null,
                    applied = JsonLite.bool(o, "applied") ?: false,
                    error = JsonLite.str(o, "error"),
                )
            }
        } ?: emptyList()
        val appliedFiles = (v.fields["appliedFiles"] as? JVal.JArr)?.items
            ?.mapNotNull { (it as? JVal.JStr)?.value } ?: emptyList()
        val checks = (v.fields["checks"] as? JVal.JArr)?.items?.mapNotNull { s ->
            (s as? JVal.JObj)?.let { o ->
                CheckResult(
                    command = JsonLite.str(o, "command") ?: "",
                    args = ((o.fields["args"] as? JVal.JArr)?.items?.mapNotNull { (it as? JVal.JStr)?.value }
                        ?: emptyList()),
                    summary = JsonLite.str(o, "summary") ?: "",
                    exitCode = (o.fields["exitCode"] as? JVal.JNum)?.value?.toInt(),
                    executed = JsonLite.bool(o, "executed") ?: false,
                    output = JsonLite.str(o, "output") ?: "",
                )
            }
        } ?: emptyList()
        val root = (v.fields["root"] as? JVal.JStr)?.value?.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) }
        return SavedTask(id, AgentState(
            phase = phase, objective = objective, mode = mode,
            planSource = JsonLite.str(v, "planSource") ?: "",
            startedAt = (v.fields["startedAt"] as? JVal.JNum)?.value?.toLong() ?: 0,
            finishedAt = (v.fields["finishedAt"] as? JVal.JNum)?.value?.toLong() ?: 0,
            writesAttempted = (v.fields["writesAttempted"] as? JVal.JNum)?.value?.toInt() ?: 0,
            writesSucceeded = (v.fields["writesSucceeded"] as? JVal.JNum)?.value?.toInt() ?: 0,
            notice = JsonLite.str(v, "notice"), error = JsonLite.str(v, "error"),
            steps = steps, pending = pending, appliedFiles = appliedFiles, checks = checks,
        ), root)
    }
}
