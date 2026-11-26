package dev.forgeai.agent

import dev.forgeai.shell.ShellPolicy
import dev.forgeai.shell.ShellRunner
import dev.forgeai.tools.JVal
import dev.forgeai.tools.JsonLite
import dev.forgeai.workspace.DiffTool
import dev.forgeai.workspace.WorkspaceAccess
import java.io.File
import java.security.MessageDigest

/** Tool execution context: one task, one authorized folder, hard counters. */
class ToolContext(
    val access: WorkspaceAccess,
    val grant: WorkspaceAccess.Grant,
    val config: AgentConfig,
    val mirrorDir: File?,           // null → run_check reports "not executed"
    val mirrorAccess: ((String) -> File)?,
    val counters: Counters = Counters(),
    val cancelRequested: () -> Boolean = { false },
)

class Counters {
    var reads = 0; var writes = 0; var checks = 0; var lists = 0
}

data class ToolResult(val ok: Boolean, val summary: String, val detail: String = "",
                      val write: PendingWrite? = null)

/** The complete tool registry exposed to the local model, with schema text for the prompt. */
object AgentTools {

    val CATALOG = listOf(
        Triple("list_files", "{\"path\":\"\" (optional subfolder)}",
            "List files under the authorized folder (recursive, capped)."),
        Triple("read_file", "{\"path\":\"src/Main.kt\"}",
            "Read a UTF-8 text file (max 1 MiB)."),
        Triple("search_text", "{\"query\":\"text\", \"path\":\"sub\" (optional)}",
            "Count literal matches of the query across text files."),
        Triple("write_file", "{\"path\":\"notes.md\", \"content\":\"...\"}",
            "Create a new file or replace an existing one with the full content. Real write, verified by re-read."),
        Triple("append_file", "{\"path\":\"notes.md\", \"content\":\"...\"}",
            "Append a text block to the end of an existing file after reading it."),
        Triple("count_lines", "{\"path\":\"file.txt\"}", "Count lines of a text file."),
        Triple("hash_file", "{\"path\":\"file.txt\", \"algo\":\"sha256\"}", "Hash a file (sha256 or md5)."),
        Triple("run_check", "{\"command\":\"wc\", \"args\":[\"-l\",\"file.txt\"]}",
            "Run an allowed local check command on the working copy (read-only commands only)."),
    )

    fun promptCatalog(): String = CATALOG.joinToString("\n") { (name, args, desc) ->
        "- $name $args — $desc"
    }

    class Rejected(message: String) : Exception(message)

    fun execute(call: ToolCall, ctx: ToolContext): ToolResult {
        if (ctx.cancelRequested()) throw Rejected("Task was cancelled.")
        val tool = call.tool
        val args = call.args
        return when (tool) {
            "list_files" -> listFiles(args, ctx)
            "read_file" -> readFile(args, ctx)
            "search_text" -> searchText(args, ctx)
            "write_file" -> writeFile(args, ctx)
            "append_file" -> appendFile(args, ctx)
            "count_lines" -> countLines(args, ctx)
            "hash_file" -> hashFile(args, ctx)
            "run_check" -> runCheck(args, ctx)
            else -> ToolResult(false, "Unknown tool '$tool'.")
        }
    }

    // ---- helpers --------------------------------------------------------------

    private fun requirePath(args: JVal.JObj): String {
        val path = JsonLite.str(args, "path") ?: throw Rejected("write/read tools need a \"path\" argument.")
        if (path.isEmpty() || path.length > dev.forgeai.workspace.AGENT_MAX_PATH) throw Rejected("Invalid path length.")
        if (path.contains('\\') || path.startsWith("/")) throw Rejected("Use relative paths inside the authorized folder.")
        path.split('/').forEach { part ->
            if (part.isEmpty() || part == "." || part == "..") throw Rejected("Paths must stay inside the folder.")
        }
        return path
    }

    private fun entryFor(ctx: ToolContext, path: String): WorkspaceAccess.Entry {
        val known = ctx.access.grants().firstOrNull { it.root == ctx.grant.root }
            ?: throw Rejected("Folder access was revoked.")
        return ctx.access.entry(ctx.grant.root, path)
    }

    private fun withTimeoutCheck(ctx: ToolContext, startedAt: Long) {
        if (System.currentTimeMillis() - startedAt > ctx.config.timeBudgetMs) {
            throw Rejected("Time budget exhausted (${ctx.config.timeBudgetMs / 1000}s).")
        }
    }

    // ---- tools ----------------------------------------------------------------

    private fun listFiles(args: JVal.JObj, ctx: ToolContext): ToolResult {
        ctx.counters.lists++
        val path = JsonLite.str(args, "path").orEmpty()
        val entries: List<dev.forgeai.workspace.WorkspaceAccess.Entry> = if (path.isEmpty()) {
            ctx.access.listTree(ctx.grant.root)
        } else {
            val dir = requirePath(args)
            ctx.access.listDir(ctx.grant.root, dir, 200).entries
        }
        val names = entries.map { e ->
            val label = if (e.path.isEmpty()) e.name else e.path
            if (e.folder) "$label/" else label
        }
        return ToolResult(true, "${entries.size} entries under " +
            (if (path.isEmpty()) "the folder root" else "'$path'") + ".",
            names.joinToString("\n").take(6000))
    }

    private fun readFile(args: JVal.JObj, ctx: ToolContext): ToolResult {
        if (ctx.counters.reads >= ctx.config.maxReads) throw Rejected("Read budget exhausted (max ${ctx.config.maxReads}).")
        val path = requirePath(args)
        ctx.counters.reads++
        val entry = entryFor(ctx, path)
        if (entry.folder) throw Rejected("'$path' is a folder.")
        val text = try {
            ctx.access.readText(entry)
        } catch (e: dev.forgeai.workspace.WriteException) {
            if (e.kind == dev.forgeai.workspace.WriteException.Kind.TOO_LARGE)
                return ToolResult(false, "'$path' is larger than the 1 MiB tool limit.", "")
            throw Rejected("'$path' could not be read as UTF-8 text.")
        }
        return ToolResult(true, "Read '$path' (${text.length} chars, ${entry.size} bytes).", text.take(12000))
    }

    private fun searchText(args: JVal.JObj, ctx: ToolContext): ToolResult {
        val query = JsonLite.str(args, "query") ?: throw Rejected("search_text needs a \"query\".")
        if (query.isEmpty() || query.length > dev.forgeai.workspace.MAX_QUERY) throw Rejected("Query must be 1–128 chars.")
        val subdir = JsonLite.str(args, "path").orEmpty()
        if (subdir.isNotEmpty()) {
            requirePath(args)
            ctx.access.entryForFolder(ctx.grant.root, subdir) // throws when missing
        }
        val roots = ctx.access.listTree(ctx.grant.root)
        val scoped = if (subdir.isEmpty()) roots else roots.filter {
            it.path.startsWith(subdir) || it.path == subdir
        }
        var filesSearched = 0
        val hits = mutableListOf<String>()
        for (entry in scoped.filter { !it.folder && it.writable }.take(200)) {
            if (ctx.cancelRequested()) break
            try {
                val text = ctx.access.readText(entry, 256 * 1024)
                filesSearched++
                var count = 0
                var start = 0
                while (true) {
                    val at = text.indexOf(query, start)
                    if (at < 0) break
                    count++
                    start = at + query.length
                    if (count > 999) break
                }
                if (count > 0) hits.add("${entry.path}: $count")
            } catch (_: Exception) { /* binary or unreadable: skipped */ }
        }
        return ToolResult(true, "Searched $filesSearched files; ${hits.size} with matches.",
            hits.joinToString("\n").take(4000))
    }

    /**
     * The only mutating tool. Applies immediately in AUTO mode inside the pre-authorized
     * scope; in REVIEW mode queues a PendingWrite with its diff for user approval.
     */
    private fun writeFile(args: JVal.JObj, ctx: ToolContext): ToolResult {
        if (ctx.counters.writes >= ctx.config.maxWrites) throw Rejected("Write budget exhausted (max ${ctx.config.maxWrites}).")
        val path = requirePath(args)
        val content = JsonLite.str(args, "content") ?: throw Rejected("write_file needs \"content\".")
        if (content.length > dev.forgeai.workspace.WRITE_LIMIT) throw Rejected("Content exceeds the 1 MiB write limit.")
        ctx.counters.writes++
        if (!ctx.grant.writable) {
            throw Rejected("The folder '${ctx.grant.name}' is read-only. Grant write access to apply file changes.")
        }
        val existing = runCatching { entryFor(ctx, path) }.getOrNull()
        val pw = if (existing != null) {
            val original = ctx.access.readText(existing)
            PendingWrite(path = path, kind = PendingWrite.Kind.REPLACE, newText = content,
                originalText = original,
                diff = DiffTool.unifiedText(original, content))
        } else {
            // Creating into a subfolder that does not exist yet is rejected (explicit mkdir is not a tool);
            // the top level and existing subfolders are valid targets.
            val dirPath = path.substringBeforeLast('/', "")
            if (dirPath.isNotEmpty()) {
                ctx.access.entryForFolder(ctx.grant.root, dirPath) // throws when missing
            }
            PendingWrite(path = path, kind = PendingWrite.Kind.CREATE, newText = content,
                diff = DiffTool.unified(emptyList(), DiffTool.splitLines(content)))
        }
        return if (ctx.config.mode == AgentMode.AUTO) {
            ToolResult(true, "Queued (auto-apply): $path (${content.length} chars).", "", write = pw)
        } else {
            ToolResult(true, "Proposed change for review: $path (${content.length} chars).", "", write = pw)
        }
    }

    /** Reads the current file, appends the block, and goes through the same write pipeline. */
    private fun appendFile(args: JVal.JObj, ctx: ToolContext): ToolResult {
        val path = requirePath(args)
        val block = JsonLite.str(args, "content") ?: throw Rejected("append_file needs \"content\".")
        if (block.length > dev.forgeai.workspace.WRITE_LIMIT) throw Rejected("Content exceeds the write limit.")
        val entry = entryFor(ctx, path)
        if (entry.folder) throw Rejected("'$path' is a folder.")
        val original = ctx.access.readText(entry)
        val merged = if (original.endsWith("\n") || original.isEmpty()) original + block else original + "\n" + block
        val patched = JVal.JObj(linkedMapOf("path" to JVal.JStr(path), "content" to JVal.JStr(merged)))
        return writeFile(patched, ctx)
    }

    private fun countLines(args: JVal.JObj, ctx: ToolContext): ToolResult {
        val path = requirePath(args)
        val entry = entryFor(ctx, path)
        val text = ctx.access.readText(entry, 256 * 1024)
        val lines = DiffTool.splitLines(text).size
        return ToolResult(true, "'$path' has $lines lines.", "")
    }

    private fun hashFile(args: JVal.JObj, ctx: ToolContext): ToolResult {
        val path = requirePath(args)
        val algo = JsonLite.str(args, "algo")?.lowercase() ?: "sha256"
        if (algo !in setOf("sha256", "md5")) throw Rejected("algo must be sha256 or md5.")
        val entry = entryFor(ctx, path)
        val bytes = ctx.access.readBytes(entry)
        val digest = MessageDigest.getInstance(if (algo == "sha256") "SHA-256" else "MD5").digest(bytes)
        val hex = digest.joinToString("") { "%02x".format(it) }
        return ToolResult(true, "$algo('$path') = $hex (${bytes.size} bytes).", hex)
    }

    /** Local checks run against the mirror copy with the AGENT whitelist; never on the SAF tree. */
    private fun runCheck(args: JVal.JObj, ctx: ToolContext): ToolResult {
        if (ctx.counters.checks >= ctx.config.maxChecks) throw Rejected("Check budget exhausted (max ${ctx.config.maxChecks}).")
        val command = JsonLite.str(args, "command") ?: throw Rejected("run_check needs \"command\".")
        val argv = (JsonLite.arr(args, "args") ?: emptyList()).mapNotNull { (it as? JVal.JStr)?.value }
        ctx.counters.checks++
        val mirror = ctx.mirrorDir ?: return ToolResult(false, "Not executed: no working copy available for this task.")
        val resolver = ctx.mirrorAccess ?: return ToolResult(false, "Not executed: no working copy mapping.")
        val cwd = resolver("")
        val runner = ShellRunner()
        val result = runner.run(ShellPolicy.Policy.AGENT, cwd, command, argv,
            timeoutMs = ShellRunner.AGENT_TIMEOUT_MS)
        val summary = when {
            !result.started -> "Not executed: ${result.failure}"
            result.timedOut -> "Timed out after ${ShellRunner.AGENT_TIMEOUT_MS / 1000}s."
            result.cancelled -> "Cancelled."
            else -> "Exit code ${result.exitCode} in ${result.durationMs} ms."
        }
        return ToolResult(result.started && !result.timedOut && !result.cancelled && result.exitCode == 0,
            "run_check $command: $summary",
            (result.stdout.take(2000) + (if (result.stderr.isNotBlank()) "\n[stderr] ${result.stderr.take(1000)}" else "")))
    }
}
