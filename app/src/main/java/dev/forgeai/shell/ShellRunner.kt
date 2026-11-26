package dev.forgeai.shell

import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Executes whitelisted binaries with hard limits. One process per run; stdout/stderr are
 * captured with byte caps; a watchdog enforces the timeout; cancel destroys the process.
 */
internal class ShellRunner {

    data class Result(
        val command: String,
        val args: List<String>,
        val stdout: String,
        val stderr: String,
        val stdoutTruncated: Boolean,
        val stderrTruncated: Boolean,
        val exitCode: Int?,          // null on timeout or cancel
        val timedOut: Boolean,
        val cancelled: Boolean,
        val durationMs: Long,
        val started: Boolean,
        val failure: String? = null, // spawn failures and policy errors land here
    )

    private val process = java.util.concurrent.atomic.AtomicReference<Process?>(null)
    private val cancelled = AtomicBoolean(false)

    companion object {
        const val MAX_OUTPUT_BYTES = 256 * 1024
        const val USER_TIMEOUT_MS = 120_000L
        const val AGENT_TIMEOUT_MS = 30_000L
    }

    fun cancel() {
        cancelled.set(true)
        process.get()?.destroyForcibly()
    }

    /**
     * Runs a command after policy validation. `cwd` must be the controlled working copy.
     * The binary is resolved from /system/bin or /system/xbin — never from project files.
     */
    fun run(
        policy: ShellPolicy.Policy,
        cwd: File,
        command: String,
        args: List<String>,
        timeoutMs: Long = if (policy == ShellPolicy.Policy.USER) USER_TIMEOUT_MS else AGENT_TIMEOUT_MS,
        maxOutputBytes: Int = MAX_OUTPUT_BYTES,
    ): Result {
        val startedAt = System.currentTimeMillis()
        val (name, argv) = try {
            ShellPolicy.validate(policy, command, args)
        } catch (e: ShellPolicy.PolicyException) {
            return Result(command, args, "", "", false, false, null, false, false,
                0, false, e.message)
        }
        if (cancelled.get()) {
            return Result(name, argv, "", "", false, false, null, false, true, 0, false, "Cancelled before start.")
        }
        val binary = ShellPolicy.binaryPath(name)
            ?: return Result(name, argv, "", "", false, false, null, false, false, 0, false,
                "'$name' is not available on this device.")
        require(cwd.isDirectory) { "Working copy does not exist." }
        if (timeoutMs !in 1000..USER_TIMEOUT_MS) throw IOException("Timeout must be 1–120 seconds.")
        if (maxOutputBytes !in 1024..MAX_OUTPUT_BYTES) throw IOException("Output cap out of range.")

        val pb = ProcessBuilder(listOf(binary) + argv)
        pb.directory(cwd)
        pb.redirectErrorStream(false)
        pb.environment()["LC_ALL"] = "C"
        var proc: Process? = null
        try {
            proc = pb.start()
        } catch (e: Exception) {
            return Result(name, argv, "", "", false, false, null, false, false, 0, false,
                "Could not start '$name': ${e.javaClass.simpleName}")
        }
        process.set(proc)
        val stdout = ByteCap(maxOutputBytes)
        val stderr = ByteCap(maxOutputBytes)
        val outThread = Thread { stdout.drain(proc.inputStream) }.also { it.start() }
        val errThread = Thread { stderr.drain(proc.errorStream) }.also { it.start() }
        var timedOut = false
        try {
            if (!proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                timedOut = true
                proc.destroyForcibly()
                proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            proc.destroyForcibly()
        } finally {
            outThread.join(2000)
            errThread.join(2000)
            process.set(null)
        }
        val duration = System.currentTimeMillis() - startedAt
        return if (cancelled.get()) {
            Result(name, argv, stdout.text(), stderr.text(), stdout.truncated, stderr.truncated,
                null, false, true, duration, true)
        } else {
            Result(name, argv, stdout.text(), stderr.text(), stdout.truncated, stderr.truncated,
                if (timedOut) null else proc.exitValue(), timedOut, false, duration, true)
        }
    }

    private class ByteCap(val cap: Int) {
        private val buffer = java.io.ByteArrayOutputStream()
        @Volatile var truncated = false
            private set

        fun drain(stream: java.io.InputStream) {
            val chunk = ByteArray(16 * 1024)
            try {
                var total = 0
                while (true) {
                    val room = cap - total
                    if (room <= 0) { truncated = true; break }
                    val n = stream.read(chunk, 0, minOf(chunk.size, room))
                    if (n < 0) break
                    if (n > 0) { buffer.write(chunk, 0, n); total += n }
                }
            } catch (_: IOException) { /* process died or stream closed; keep what we have */ }
        }

        fun text(): String = buffer.toString(java.nio.charset.StandardCharsets.UTF_8.name())
    }
}
