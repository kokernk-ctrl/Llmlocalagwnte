package dev.forgeai.agent

import dev.forgeai.tools.JVal

/** Agent task configuration. Budgets are concrete counters enforced by the runtime. */
data class AgentConfig(
    val objective: String,
    val mode: AgentMode,
    val maxSteps: Int = 12,
    val maxReads: Int = 24,
    val maxWrites: Int = 6,
    val maxChecks: Int = 4,
    val timeBudgetMs: Long = 180_000L,
    val planFromModel: Boolean = false,
)

enum class AgentMode(val label: String, val detail: String) {
    REVIEW("Review before apply",
        "The agent shows the diff of every proposed change and waits for explicit approval before writing."),
    AUTO("Auto-apply within scope",
        "You pre-authorize the objective, the folder and file writes. The agent creates and edits files " +
            "inside that scope without per-write confirmation and shows the diffs afterwards."),
}

enum class AgentPhase(val label: String, val terminal: Boolean) {
    IDLE("Idle", true),
    PLANNING("Planning", false),
    RUNNING("Running tools", false),
    AWAITING_REVIEW("Waiting for your review", false),
    APPLYING("Applying changes", false),
    VERIFYING("Verifying writes", false),
    TESTING("Running local checks", false),
    DONE("Finished", true),
    FAILED("Failed", true),
    CANCELLED("Cancelled", true),
}

data class ToolCall(val tool: String, val args: JVal.JObj, val fromModel: Boolean)

data class StepRecord(
    val index: Int,
    val tool: String,
    val argsSummary: String,
    val ok: Boolean,
    val summary: String,
    val detail: String = "",
    val durationMs: Long = 0,
)

data class PendingWrite(
    val path: String,
    val kind: Kind,
    val newText: String,
    val originalText: String? = null,   // null for creates
    val applied: Boolean = false,
    val diff: String = "",
    val error: String? = null,
) {
    enum class Kind { CREATE, REPLACE }

    /** Creates have no diff against an original; show the whole file as additions. */
    fun displayDiff(): String {
        if (diff.isNotEmpty()) return diff
        return if (kind == Kind.CREATE) {
            dev.forgeai.workspace.DiffTool.unified(emptyList(),
                dev.forgeai.workspace.DiffTool.splitLines(newText), 2)
        } else ""
    }
}

data class CheckResult(
    val command: String,
    val args: List<String>,
    val summary: String,
    val exitCode: Int?,
    val executed: Boolean,
    val output: String = "",
)

data class AgentState(
    val phase: AgentPhase = AgentPhase.IDLE,
    val objective: String = "",
    val mode: AgentMode = AgentMode.REVIEW,
    val steps: List<StepRecord> = emptyList(),
    val pending: List<PendingWrite> = emptyList(),
    val appliedFiles: List<String> = emptyList(),
    val checks: List<CheckResult> = emptyList(),
    val notice: String? = null,
    val error: String? = null,
    val planSource: String = "",     // "local model" or "deterministic fallback" for honesty
    val startedAt: Long = 0,
    val finishedAt: Long = 0,
    val writesAttempted: Int = 0,
    val writesSucceeded: Int = 0,
) {
    /** A task resumed after process death: non-terminal states cannot be trusted as done. */
    val interruptedLike: Boolean get() = !phase.terminal && phase != AgentPhase.IDLE
}
