package dev.forgeai.agent

import android.content.Context
import android.net.Uri
import dev.forgeai.python.PythonHost
import dev.forgeai.shell.ShellPolicy
import dev.forgeai.shell.ShellRunner
import dev.forgeai.workspace.WorkspaceAccess
import dev.forgeai.workspace.WorkspaceMirror
import java.io.File

/**
 * Android adapters: the pure engine talks to real SAF storage, the working copy, the
 * whitelisted shell runner, the bundled Python runtime and the loaded local model only
 * through these thin bridges.
 */

/** SAF-backed filesystem. Maps the engine's opaque rootId to the persisted tree Uri. */
class SafFileSystem(private val access: WorkspaceAccess) : FileSystemPort {

    private fun uri(rootId: String): Uri = Uri.parse(rootId)

    override fun grants(): List<FolderGrant> = access.grants().map {
        FolderGrant(it.root.toString(), it.name, it.writable)
    }

    override fun listTree(rootId: String): List<FileRef> = access.listTree(uri(rootId)).map { it.toRef() }

    override fun entry(rootId: String, path: String): FileRef {
        val root = uri(rootId)
        val e = try {
            access.entry(root, path)
        } catch (e: dev.forgeai.workspace.WriteException) {
            throw e.toFs()
        }
        return e.toRef()
    }

    override fun readBytes(ref: FileRef, maxBytes: Int): ByteArray {
        val entry = access.entry(uri(ref.rootId), ref.path)
        return try {
            access.readBytes(entry, maxBytes)
        } catch (e: dev.forgeai.workspace.WriteException) {
            throw e.toFs()
        }
    }

    override fun readText(ref: FileRef, maxBytes: Int): String {
        val entry = access.entry(uri(ref.rootId), ref.path)
        return try {
            access.readText(entry, maxBytes)
        } catch (e: dev.forgeai.workspace.WriteException) {
            throw e.toFs()
        } catch (e: Exception) {
            throw FsException("'${ref.path}' could not be read as UTF-8 text.", FsException.Kind.PROVIDER)
        }
    }

    override fun createFile(rootId: String, dirPath: String, name: String, content: ByteArray,
                            mime: String): FileRef {
        val root = uri(rootId)
        return try {
            access.createFile(root, dirPath, name, content, mime).toRef()
        } catch (e: dev.forgeai.workspace.WriteException) {
            throw e.toFs()
        }
    }

    override fun replaceFile(ref: FileRef, newContent: ByteArray, expected: ByteArray?): ByteArray {
        val entry = access.entry(uri(ref.rootId), ref.path)
        return try {
            access.replaceFile(entry, newContent, expected)
        } catch (e: dev.forgeai.workspace.WriteException) {
            throw e.toFs()
        }
    }

    private fun dev.forgeai.workspace.WorkspaceAccess.Entry.toRef() =
        FileRef(root.toString(), path, name, folder, size, lastModified)

    private fun dev.forgeai.workspace.WriteException.toFs() = FsException(
        message ?: "storage error",
        when (kind) {
            dev.forgeai.workspace.WriteException.Kind.NO_WRITE_GRANT -> FsException.Kind.NO_WRITE_GRANT
            dev.forgeai.workspace.WriteException.Kind.CONFLICT -> FsException.Kind.CONFLICT
            dev.forgeai.workspace.WriteException.Kind.VERIFY_FAILED -> FsException.Kind.VERIFY_FAILED
            dev.forgeai.workspace.WriteException.Kind.TOO_LARGE -> FsException.Kind.TOO_LARGE
            dev.forgeai.workspace.WriteException.Kind.INVALID_NAME -> FsException.Kind.INVALID_NAME
            dev.forgeai.workspace.WriteException.Kind.PROVIDER -> FsException.Kind.PROVIDER
        })
}

/** Real working copy on app-private storage. */
class RealMirror(context: Context, private val mirror: WorkspaceMirror) : MirrorPort {
    private var dir: java.io.File? = null

    override fun open(taskKey: String) {
        val id = taskKey.toLongOrNull() ?: (taskKey.hashCode().toLong() and 0x7fff_ffff_ffff_ffffL)
        dir = mirror.open(id)
    }

    override fun syncIn(files: List<FileRef>, read: (FileRef) -> ByteArray) {
        val cwd = dir ?: return
        var total = 0L
        for (ref in files) {
            if (total > TOTAL_SYNC_CAP) break
            try {
                val bytes = read(ref)
                val target = mirror.resolve(cwd, ref.path)
                target.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
                if (target.canonicalPath.startsWith(cwd.canonicalPath)) {
                    target.writeBytes(bytes)
                    total += bytes.size
                }
            } catch (_: Exception) { /* unreadable entries are simply absent from the copy */ }
        }
    }

    override fun put(path: String, bytes: ByteArray) {
        val cwd = dir ?: return
        val target = mirror.resolve(cwd, path)
        target.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
        if (target.canonicalPath.startsWith(cwd.canonicalPath)) target.writeBytes(bytes)
    }

    override fun contains(path: String): Boolean {
        val cwd = dir ?: return false
        return mirror.resolve(cwd, path).isFile
    }

    override fun runnerDir(): String? = dir?.takeIf { it.isDirectory }?.absolutePath

    private companion object { const val TOTAL_SYNC_CAP = 32L * 1024 * 1024 }
}

/** Whitelisted shell runner. */
class WhitelistedChecks : CheckRunnerPort {
    private val runner = ShellRunner()

    override fun run(cwd: String?, command: String, args: List<String>, timeoutMs: Long): CheckRunnerPort.Outcome {
        val r = runner.run(ShellPolicy.Policy.AGENT, File(cwd ?: return notStarted("No working copy directory.")),
            command, args, timeoutMs)
        return CheckRunnerPort.Outcome(r.started, r.failure, r.exitCode, r.timedOut, r.cancelled,
            r.stdout, r.stderr, r.durationMs)
    }

    override fun cancel() = runner.cancel()

    private fun notStarted(why: String) = CheckRunnerPort.Outcome(false, why, null, false, false, "", "", 0)
}

/** Bundled Chaquopy runtime. */
class BundledPython(private val context: Context) : PythonPort {
    override val available: Boolean get() = PythonHost.available

    override fun runPredefined(tool: String, input: String): PythonPort.PyResult {
        val t = runCatching { PythonHost.Predefined.valueOf(tool.uppercase()) }
            .getOrNull() ?: return PythonPort.PyResult(false, "", "", "unknown tool '$tool'", 0, null)
        return PythonHost.runPredefined(context, t, input).let { it.toPort() }
    }

    override fun runScript(code: String, input: String, deadlineSeconds: Int): PythonPort.PyResult =
        PythonHost.run(context, code, input, deadlineSeconds).let { it.toPort() }

    private fun PythonHost.Result.toPort() = PythonPort.PyResult(ok, stdout, stderr, error, durationMs, note)
}

/** The loaded local Qwen model via the chat engine's agent hook. */
class LoadedModel(private val hook: (String, Int) -> String?) : ModelPort {
    override fun generate(prompt: String, maxTokens: Int): String? = hook(prompt, maxTokens)
}
