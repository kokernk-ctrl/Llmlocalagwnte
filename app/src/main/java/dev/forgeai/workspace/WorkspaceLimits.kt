package dev.forgeai.workspace

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.util.Locale

internal const val MAX_ROWS = 50
internal const val MAX_BYTES = 8192
internal const val MAX_LOG = 64
internal const val MAX_QUERY = 128

// Agent tool budget constants.
internal const val AGENT_MAX_FILES = 2000
internal const val AGENT_MAX_PATH = 512
internal const val READ_LIMIT = 1024 * 1024
internal const val WRITE_LIMIT = 1024 * 1024
internal const val MAX_BACKUP_BYTES = 1024 * 1024

internal data class Preview(val text: String, val clipped: Boolean)

/** Cleans provider-supplied display names before they reach the UI or the audit log. */
internal fun safeName(name: String) = name.take(120).filterNot {
    it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt()
}

private val textExtensions = setOf("txt", "md", "csv", "log", "json", "yaml", "yml",
    "kt", "kts", "java", "py", "js", "ts", "jsx", "tsx", "html", "css", "xml", "c", "cpp",
    "h", "hpp", "go", "rs", "sh", "gradle", "properties", "toml", "sql", "jsonl")

/** Metadata suggests candidates; readPreview independently validates bytes. Never execute content. */
internal fun suggestsText(name: String, mime: String): Boolean {
    val type = mime.substringBefore(';').trim().lowercase(Locale.ROOT)
    return type.startsWith("text/") || type in setOf("application/json", "application/xml",
        "application/javascript", "application/sql") || type.endsWith("+json") || type.endsWith("+xml") ||
        name.substringAfterLast('.', "").lowercase(Locale.ROOT) in textExtensions
}

internal fun excerpt(text: String, limit: Int) =
    text.take(limit).dropLastWhile { it.isHighSurrogate() }

/** One lookahead byte distinguishes an exact fit from a clipped UTF-8 preview. */
internal fun readPreview(input: InputStream): Preview {
    val buffer = ByteArray(MAX_BYTES + 1)
    var length = 0
    while (length < buffer.size) {
        val n = input.read(buffer, length, buffer.size - length)
        if (n < 0) break
        if (n == 0) throw IOException("Empty read")
        length += n
    }
    val clipped = length > MAX_BYTES
    val chars = CharBuffer.allocate(MAX_BYTES)
    val decoder = Charsets.UTF_8.newDecoder()
    decoder.decode(ByteBuffer.wrap(buffer, 0, length.coerceAtMost(MAX_BYTES)), chars, !clipped)
        .throwIfError()
    chars.flip()
    val text = chars.toString().removePrefix("\uFEFF")
    require(text.none { it.isISOControl() && it !in "\t\n\r" }) { "Not plain text" }
    return Preview(text, clipped)
}

private fun java.nio.charset.CoderResult.throwIfError() {
    if (isError) throwException()
}

internal fun literalMatches(text: String, query: String): Int {
    require(text.length <= MAX_BYTES && query.length in 1..MAX_QUERY)
    var matches = 0
    var start = 0
    while (start < text.length) {
        val hit = text.indexOf(query, startIndex = start)
        if (hit < 0) break
        matches++
        start = hit + query.length
    }
    return matches
}

internal enum class WorkspaceEvent(val label: String) {
    GRANT("Read access granted"), GRANT_WRITE("Read+write access granted"),
    LIST("Listed folder entries"), READ("Opened a file"),
    SEARCH("Searched file content"), COPY("Copied an excerpt"), DRAFT("Shared an excerpt to a chat draft"),
    REVOKE("Access revoked"),
    WRITE_CREATE("Created a file"), WRITE_REPLACE("Modified a file"), CONFLICT("Concurrent change detected"),
    EXPORT("Exported a copy"), SHELL("Ran a local command"), CHECK("Ran a local check"),
    FAILED("Operation unsuccessful")
}

internal data class LogEntry(val time: Long, val event: WorkspaceEvent)

/** Accept only timestamps and known event codes; no provider/user strings enter the log. */
internal fun parseLog(raw: String): List<LogEntry> = raw.take(8192).lineSequence()
    .mapNotNull { line ->
        val time = line.substringBefore(':').toLongOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null
        val event = WorkspaceEvent.entries.firstOrNull { it.name == line.substringAfter(':') }
            ?: return@mapNotNull null
        LogEntry(time, event)
    }.take(MAX_LOG).toList()

internal fun appendLog(raw: String, event: WorkspaceEvent, now: Long): String =
    (listOf(LogEntry(now, event)) + parseLog(raw)).take(MAX_LOG)
        .joinToString("\n") { "${it.time}:${it.event.name}" }
