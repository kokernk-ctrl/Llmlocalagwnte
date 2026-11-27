package dev.forgeai.agent

/**
 * Pure-Kotlin engine vocabulary. The agent engine never imports Android framework classes:
 * every real-world side effect (SAF writes, shell, Python, model inference, persistence)
 * crosses one of these ports, so the whole conversation flow is unit-testable on the JVM
 * with fakes that reproduce provider quirks (renamed documents, write failures, …).
 */

/** A document inside an authorized folder. `rootId` is the opaque folder key (SAF tree URI on device). */
data class FileRef(
    val rootId: String,
    val path: String,          // relative path, "" impossible for files; "/"-separated
    val name: String,
    val folder: Boolean,
    val size: Long,
    val lastModified: Long,
)

data class FolderGrant(val rootId: String, val displayName: String, val writable: Boolean)

/** Failure taxonomy shared by every port implementation. */
class FsException(message: String, val kind: Kind) : Exception(message) {
    enum class Kind { NOT_FOUND, NO_WRITE_GRANT, CONFLICT, TOO_LARGE, INVALID_NAME, VERIFY_FAILED, PROVIDER }

    /** User-facing explanation with the concrete next action — never a bare stack class. */
    fun userAction(): String = when (kind) {
        Kind.NO_WRITE_GRANT -> "Grant write access to the folder (Files → grant read+write), then retry."
        Kind.CONFLICT -> "The file changed outside the app. Re-read it and re-apply your change."
        Kind.NOT_FOUND -> "The file was not found. It may have been renamed, moved or deleted; re-check the folder."
        Kind.VERIFY_FAILED -> "The write did not verify. The previous version was kept as a backup when applicable."
        Kind.TOO_LARGE -> "The file exceeds the 1 MiB tool limit."
        Kind.INVALID_NAME -> "The file name is not valid."
        Kind.PROVIDER -> "The storage provider refused the operation. Retry, or pick a different folder."
    }
}

/** Filesystem port backed by SAF on the device. */
interface FileSystemPort {
    fun grants(): List<FolderGrant>
    fun listTree(rootId: String): List<FileRef>
    fun entry(rootId: String, path: String): FileRef
    fun readBytes(ref: FileRef, maxBytes: Int): ByteArray
    fun readText(ref: FileRef, maxBytes: Int): String

    /**
     * Creates a document and returns the REAL stored reference. Providers may store a
     * different display name than requested (e.g. appending an extension for the MIME
     * type); the caller must trust [FileRef.path] of the result, never the planned name.
     */
    fun createFile(rootId: String, dirPath: String, name: String, content: ByteArray, mime: String): FileRef

    /** Replaces content with optimistic-concurrency check; returns the backup bytes (may be empty). */
    fun replaceFile(ref: FileRef, newContent: ByteArray, expected: ByteArray?): ByteArray
}

/** Working-copy port: real paths for runners, updated after each verified write. */
interface MirrorPort {
    fun open(taskKey: String)
    fun syncIn(files: List<FileRef>, read: (FileRef) -> ByteArray)
    fun put(path: String, bytes: ByteArray)
    fun contains(path: String): Boolean
    /** Runner working directory, or null when no real filesystem is available (unit tests). */
    fun runnerDir(): String?
}

/** Local check runner (whitelisted binaries only). */
interface CheckRunnerPort {
    data class Outcome(
        val started: Boolean,
        val failure: String?,
        val exitCode: Int?,
        val timedOut: Boolean,
        val cancelled: Boolean,
        val stdout: String,
        val stderr: String,
        val durationMs: Long,
    )
    fun run(cwd: String?, command: String, args: List<String>, timeoutMs: Long): Outcome
    fun cancel()
}

/** Bundled Python runtime port. */
interface PythonPort {
    data class PyResult(val ok: Boolean, val stdout: String, val stderr: String,
                         val error: String, val durationMs: Long, val note: String?)
    val available: Boolean
    fun runPredefined(tool: String, input: String): PyResult
    fun runScript(code: String, input: String, deadlineSeconds: Int): PyResult
}

/** Local model port (the loaded Qwen engine). Returns null when no model is available. */
interface ModelPort {
    fun generate(prompt: String, maxTokens: Int): String?
}

/** Checkpoint persistence port. */
interface CheckpointPort {
    fun save(task: SavedTask)
    fun list(): List<SavedTask>
    fun delete(id: Long)
}

/** Extension-aware MIME selection: passing "text/plain" for a ".md" name makes some
 * providers append ".txt" to the stored document. The extension decides the MIME instead. */
object FileKinds {
    fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "md", "markdown" -> "text/markdown"
            "txt", "log", "text" -> "text/plain"
            "json" -> "application/json"
            "jsonl", "ndjson" -> "application/x-ndjson"
            "csv", "tsv" -> "text/csv"
            "html", "htm" -> "text/html"
            "xml" -> "application/xml"
            "yaml", "yml" -> "application/yaml"
            "py" -> "text/x-python"
            "js" -> "text/javascript"
            "css" -> "text/css"
            else -> "application/octet-stream"
        }
    }

    fun isTextName(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf("txt", "md", "markdown", "json", "jsonl", "ndjson", "csv", "tsv",
            "log", "text", "html", "htm", "xml", "yaml", "yml", "py", "js", "ts", "css",
            "kt", "kts", "java", "c", "cpp", "h", "hpp", "go", "rs", "sh", "sql", "toml", "properties", "gradle")
    }
}
