package dev.forgeai.agent

import dev.forgeai.tools.JVal
import dev.forgeai.tools.JsonLite

/**
 * Builds the planning prompt for the local model, parses its structured tool calls, and
 * provides a deterministic fallback plan when the model output is unusable. The runtime
 * never trusts the model: every call is re-validated against scope and budgets.
 */
object Planner {

    fun buildPlanningPrompt(objective: String, mode: AgentMode, writable: Boolean,
                            files: List<String>): String {
        val catalog = AgentTools.promptCatalog()
        val listing = files.take(60).joinToString("\n").ifBlank { "(empty folder)" }
        return """
You are the FORGE AI local coding agent. Work only with the tools below.
Folder contents:
$listing

User objective: ${objective.take(1200)}
Access mode: ${if (writable) "read and write (real writes are applied)" else "read-only (no writes possible)"}.

Rules:
1. Reply with a short plan (max 5 lines), then ONE tool call per line as a single JSON object: {"tool":"...","args":{...}}.
2. Only use the listed tools. Do not invent files: use exact names from the folder contents.
3. To create a file use write_file with full content. To modify a file, read it first, then write_file with the complete new content.
4. Finish with run_check on the files you touched (for example wc -l) when possible.
5. No network, no shell besides run_check, no files outside the folder.
""".trimIndent() + "\nAvailable tools:\n$catalog\n"
    }

    /** Extracts tool calls from model output. Tolerant of prose; strict about arguments. */
    fun parseToolCalls(text: String, maxCalls: Int = 10): List<ToolCall> {
        require(maxCalls in 1..24)
        val calls = mutableListOf<ToolCall>()
        for (candidate in JsonLite.extractObjects(text, maxObjects = maxCalls * 2)) {
            val tool = JsonLite.str(candidate, "tool") ?: continue
            val args = JsonLite.obj(candidate, "args") ?: continue
            calls.add(ToolCall(tool.trim(), args, fromModel = true))
            if (calls.size == maxCalls) break
        }
        return calls
    }

    data class PlanFile(val path: String, val name: String, val size: Long, val isFolder: Boolean)

    /** Deterministic plan used when no model is loaded or its output cannot be parsed. */
    fun deterministicPlan(objective: String, mode: AgentMode, writable: Boolean,
                          files: List<PlanFile>): List<ToolCall> {
        val calls = mutableListOf<ToolCall>()
        fun jobj(vararg pairs: Pair<String, JVal>) =
            JVal.JObj(LinkedHashMap<String, JVal>().apply { pairs.forEach { put(it.first, it.second) } })
        fun jstr(s: String) = JVal.JStr(s)

        val createPattern = Regex(
            "(?:cri(?:ar|e|a)|create|novo|new)[^\\n\"]{0,40}?([\\w.\\-]+\\.(?:md|txt|py|kt|json|csv|html))",
            RegexOption.IGNORE_CASE)
        val editPattern = Regex(
            "(?:edite?|editar|modifi(?:car|que|ca)?|atualiz(?:ar|e|a)?|update|append|acrescent)[^\\n\"]{0,40}?([\\w.\\-]+\\.(?:md|txt|py|kt|json|csv|html))",
            RegexOption.IGNORE_CASE)
        val byName = files.filter { !it.isFolder }.associateBy { it.name.lowercase() }

        val createName = createPattern.find(objective)?.groupValues?.get(1)?.lowercase()
        val editName = editPattern.find(objective)?.groupValues?.get(1)?.lowercase()
            ?.takeUnless { it == createName && byName[it] == null }

        // 1. Create. Never overwrite an existing file merely because a regex matched it:
        // when the requested name already exists the creation step is skipped and reported.
        val requested = createName
        val createPath = when {
            requested == null -> {
                var candidate = "forge-task-notes.md"
                var n = 2
                while (byName.containsKey(candidate)) { candidate = "forge-task-notes-$n.md"; n++ }
                candidate
            }
            byName.containsKey(requested) -> null // exists: deterministic plan must not clobber it
            else -> requested
        }
        if (createPath != null) {
            val createContent = buildString {
                appendLine("# FORGE AI agent task")
                appendLine()
                appendLine("Objective: ${objective.take(300)}")
                appendLine("Mode: ${mode.label}")
                appendLine("Created: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date())}")
                appendLine()
                appendLine("This file was written by the FORGE AI agent inside the folder you authorized.")
            }
            calls.add(ToolCall("write_file", jobj("path" to jstr(createPath), "content" to jstr(createContent)),
                fromModel = false))
        }

        // 2. Edit an existing file: append a clearly-marked section after reading it.
        val editTarget = editName?.let { byName[it] } ?: files.firstOrNull {
            !it.isFolder && it.name.lowercase() != createPath && it.size in 1L..256L * 1024
        }
        if (editTarget != null) {
            calls.add(ToolCall("read_file", jobj("path" to jstr(editTarget.path)), fromModel = false))
            // The executor resolves the append marker against the freshly read content.
            calls.add(ToolCall("append_file", jobj("path" to jstr(editTarget.path),
                "content" to jstr("\n<!-- edited by FORGE AI agent: ${objective.take(120)} -->\n")),
                fromModel = false))
        }

        // 3. Local check with the read-only whitelist (only for the file actually created).
        if (createPath != null) {
            calls.add(ToolCall("run_check", jobj("command" to jstr("wc"),
                "args" to JVal.JArr(listOf(jstr("-l"), jstr(createPath)))), fromModel = false))
        }
        return calls
    }
}
