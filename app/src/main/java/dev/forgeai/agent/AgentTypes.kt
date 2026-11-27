package dev.forgeai.agent

import dev.forgeai.tools.JVal

/**
 * Agent task configuration values shared by the engine and the UI. The conversation
 * timeline, statuses and tracked writes live in [Timeline.kt]; engine ports in [Ports.kt].
 */

enum class AgentMode(val label: String, val detail: String) {
    REVIEW("Review before apply",
        "The agent shows the diff of every proposed change in the conversation and waits for your approval. " +
            "Steps that depend on a pending write are held back — they run only after the file is really saved."),
    AUTO("Auto-apply within scope",
        "You pre-authorize writes in the selected folder for the task. The agent creates and edits files " +
            "without per-file confirmation and shows the diffs and verification results in the conversation. " +
            "Free-form Python scripts always require approval anyway."),
}

/** One structured tool call parsed from model output or built by the deterministic planner. */
data class ToolCall(val tool: String, val args: JVal.JObj, val fromModel: Boolean)
