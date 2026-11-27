package dev.forgeai.agent

import dev.forgeai.tools.JVal
import dev.forgeai.tools.JsonLite

/**
 * Tool catalog for the conversation prompt, tolerant parsing of model output, and the
 * deterministic fallback plan used when no model is loaded (or its output is unusable).
 *
 * v0.4.0 fixes relative to v0.3.0:
 *  - The fallback planner no longer picks a leftover provider-renamed artifact
 *    (e.g. "forge-task-notes.md.txt") as its edit target when the task wants to create
 *    "forge-task-notes.md": files whose stem collides with the planned creation are
 *    excluded, and default names avoid any stem collision in the folder.
 *  - Edit targets can be named without extension in the objective ("update the README"
 *    matches README, README.md, README.txt).
 */
object Planner {

    /** The tool registry the model sees; the engine validates every call against it. */
    val CATALOG = listOf(
        Triple("list_files", "{\"path\":\"\" (optional subfolder)}",
            "List files under the authorized folder (recursive, capped)."),
        Triple("read_file", "{\"path\":\"README.md\"}",
            "Read a UTF-8 text file (max 1 MiB)."),
        Triple("search_text", "{\"query\":\"text\", \"path\":\"sub\" (optional)}",
            "Count literal matches of the query across text files."),
        Triple("write_file", "{\"path\":\"notes.md\", \"content\":\"...\"}",
            "Create a new file or replace an existing one with the full content. Real write; verified by re-read."),
        Triple("append_file", "{\"path\":\"notes.md\", \"content\":\"...\"}",
            "Append a text block to the end of an existing file (reads it first)."),
        Triple("count_lines", "{\"path\":\"file.txt\"}", "Count lines of a text file."),
        Triple("hash_file", "{\"path\":\"file.txt\", \"algo\":\"sha256\"}", "Hash a file (sha256 or md5)."),
        Triple("run_check", "{\"command\":\"wc\", \"args\":[\"-l\",\"file.txt\"]}",
            "Run an allowed read-only local command on the working copy (after files are saved)."),
        Triple("python_tool", "{\"tool\":\"csv_summary\", \"path\":\"data.csv\"}",
            "Analyze a file with the bundled Python runtime (json_summary, csv_summary, text_stats, jsonl_count)."),
        Triple("python_script", "{\"code\":\"print(1)\", \"input\":\"\" (optional)}",
            "Run custom Python in isolation; ALWAYS requires your explicit approval."),
    )

    fun toolCatalog(): String = CATALOG.joinToString("\n") { (name, args, desc) ->
        "- $name $args — $desc"
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

    private fun stem(name: String) = name.substringBeforeLast('.', name).lowercase()

    /** Deterministic plan used when no model is loaded or its output cannot be parsed.
     *  Actions are gated by the user's verbs: nothing is created or edited unless asked. */
    fun deterministicPlan(objective: String, mode: AgentMode, writable: Boolean,
                          files: List<PlanFile>): List<ToolCall> {
        val calls = mutableListOf<ToolCall>()
        fun jobj(vararg pairs: Pair<String, JVal>) =
            JVal.JObj(LinkedHashMap<String, JVal>().apply { pairs.forEach { put(it.first, it.second) } })
        fun jstr(s: String) = JVal.JStr(s)

        val createPattern = Regex(
            "(?:cri(?:ar|e|a)|create|novo|new|make|fazer|faça)[^\\n\"]{0,40}?([\\w.\\-]+\\.(?:md|txt|py|kt|json|csv|html))",
            RegexOption.IGNORE_CASE)
        val editPattern = Regex(
            "(?:edite?|editar|modifi(?:car|que|ca)?|atualiz(?:ar|e|a)?|update|append|acrescent)[^\\n\"]{0,40}?([\\w.\\-]+(?:\\.(?:md|txt|py|kt|json|csv|html))?)",
            RegexOption.IGNORE_CASE)
        val checkPattern = Regex(
            "(?:check|verify|confir(?:a|e|me)?|verifi(?:que|car)?|conferir)[^\\n\"]{0,40}?([\\w.\\-]+(?:\\.(?:md|txt|py|kt|json|csv|html))?)",
            RegexOption.IGNORE_CASE)
        val createVerb = Regex("\\b(criar|crie|cria|create|novo|new|make|fazer|faça)\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(objective)
        val editVerb = Regex("\\b(edite|editar|edit|modifique|modificar|modifica|update|atualize|atualizar|atualiza|append|acrescente)\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(objective)
        val checkVerb = Regex("\\b(check|verify|confira|confirme|verifique|verificar|conferir)\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(objective)

        val byName = files.filter { !it.isFolder }.associateBy { it.name.lowercase() }
        val byStem = files.filter { !it.isFolder }.groupBy { stem(it.name) }

        val requestedCreate = createPattern.find(objective)?.groupValues?.get(1)?.lowercase()
        // 1. Creation target. The default name must not collide with ANY existing stem:
        //    a leftover "forge-task-notes.md.txt" must never make us plan another
        //    "forge-task-notes.md" (the provider would rename it again).
        val createPath: String? = when {
            requestedCreate != null && byName.containsKey(requestedCreate) -> null // exists: never clobber
            requestedCreate != null -> requestedCreate
            createVerb -> {
                var candidate = "forge-task-notes.md"
                var n = 2
                while (byStem.keys.any { it == stem(candidate) || it.startsWith(stem(candidate)) }) {
                    candidate = "forge-task-notes-$n.md"; n++
                }
                candidate
            }
            else -> null
        }
        val createStem = createPath?.let { stem(it) }

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

        // 2. Edit an existing file: explicit name (with or without extension) or a
        //    README-like fallback — never a stale artifact of the create stem.
        val stopwords = setOf("the", "and", "with", "com", "this", "that", "esse", "essa",
            "arquivo", "file", "a", "o", "as", "os", "um", "uma", "de", "da", "do", "das",
            "dos", "e", "no", "na", "em", "in", "of", "to", "para", "por")
        val editMention = editPattern.find(objective)?.groupValues?.get(1)?.lowercase()
            ?.takeIf { it.length > 2 && it !in stopwords }
        fun editCandidates(): List<PlanFile> = files.filter { f ->
            !f.isFolder && f.size in 1L..256L * 1024 &&
                (createStem == null || (stem(f.name) != createStem && !stem(f.name).startsWith("$createStem-"))) &&
                !stem(f.name).startsWith("forge-task-notes")
        }
        val mentionTarget: PlanFile? = when {
            editMention == null -> null
            byName.containsKey(editMention) -> byName[editMention]
            byStem[editMention]?.firstOrNull() != null -> byStem[editMention]!!.firstOrNull()
            else -> files.filter { !it.isFolder }.firstOrNull { stem(it.name).contains(editMention) }
        }
        val editTarget: PlanFile? = mentionTarget
            ?: (if (editVerb) editCandidates().firstOrNull { stem(it.name) == "readme" }
                ?: editCandidates().firstOrNull() else null)
        if (editTarget != null) {
            calls.add(ToolCall("read_file", jobj("path" to jstr(editTarget.path)), fromModel = false))
            calls.add(ToolCall("append_file", jobj("path" to jstr(editTarget.path),
                "content" to jstr("\n<!-- edited by FORGE AI agent: ${objective.take(120)} -->\n")),
                fromModel = false))
        }

        // 3. Local check: the file the user asked to verify, or the created one —
        //    the agent always verifies its own writes. The engine remaps to the real
        //    stored name and only runs the check once the write is actually saved.
        val checkMention = checkPattern.find(objective)?.groupValues?.get(1)?.lowercase()
            ?.takeIf { it.length > 2 && it !in stopwords }
        val checkTarget: PlanFile? = when {
            checkMention == null -> null
            byName.containsKey(checkMention) -> byName[checkMention]
            byStem[checkMention]?.firstOrNull() != null -> byStem[checkMention]!!.firstOrNull()
            else -> files.filter { !it.isFolder }.firstOrNull { stem(it.name).contains(checkMention) }
        }
        val checkPath = when {
            checkTarget != null -> checkTarget.path
            createPath != null -> createPath
            checkMention != null -> checkMention  // unresolved name: the check reports honestly if missing
            else -> null
        }
        if (checkPath != null) {
            calls.add(ToolCall("run_check", jobj("command" to jstr("wc"),
                "args" to JVal.JArr(listOf(jstr("-l"), jstr(checkPath)))), fromModel = false))
        }
        // "check that both files are correct": when the objective asks for verification
        // and an existing file was also edited, verify that one too.
        if (checkVerb && editTarget != null && editTarget.path != checkPath) {
            calls.add(ToolCall("run_check", jobj("command" to jstr("wc"),
                "args" to JVal.JArr(listOf(jstr("-l"), jstr(editTarget.path)))), fromModel = false))
        }
        return calls
    }
}
