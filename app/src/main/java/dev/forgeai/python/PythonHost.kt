package dev.forgeai.python

import android.content.Context
import android.os.SystemClock
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Host for the bundled CPython runtime (Chaquopy). Free-form execution requires explicit
 * user approval per script; limits: time budget via cooperative trace guard, output caps,
 * no network at OS level (the app has no INTERNET permission), and the runner never lets
 * a script exception propagate into the app process.
 */
object PythonHost {

    data class Result(
        val ok: Boolean,
        val stdout: String,
        val stderr: String,
        val error: String,
        val truncated: Boolean,
        val durationMs: Long,
        val note: String? = null,
    )

    @Volatile private var started = false

    /** True when the bundled interpreter is present in this build. */
    val available: Boolean by lazy {
        try {
            Class.forName("com.chaquo.python.Python")
            true
        } catch (_: Throwable) { false }
    }

    fun start(context: Context): Boolean {
        if (!available) return false
        if (!started) {
            synchronized(this) {
                if (!started) {
                    Python.start(AndroidPlatform(context.applicationContext))
                    started = true
                }
            }
        }
        return true
    }

    const val MAX_OUTPUT_CHARS = 64 * 1024
    const val MAX_SCRIPT_CHARS = 64 * 1024
    const val MAX_INPUT_CHARS = 512 * 1024

    fun run(context: Context, code: String, taskInput: String, deadlineSeconds: Int = 30): Result {
        if (code.length > MAX_SCRIPT_CHARS) {
            return Result(false, "", "", "", false, 0, "Script exceeds the ${MAX_SCRIPT_CHARS / 1024} KiB limit.")
        }
        if (taskInput.length > MAX_INPUT_CHARS) {
            return Result(false, "", "", "", false, 0, "Input exceeds the 512 KiB limit.")
        }
        if (!start(context)) {
            return Result(false, "", "", "", false, 0, "Python runtime is not available in this build.")
        }
        val executor = Executors.newSingleThreadExecutor()
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            val future = executor.submit(java.util.concurrent.Callable {
                val py = Python.getInstance()
                val runner = py.getModule("forge_runner")
                val dict = runner.callAttr("run", code, taskInput, deadlineSeconds)
                Result(
                    ok = dict.callAttr("get", "ok").toJava(Boolean::class.java),
                    stdout = dict.callAttr("get", "stdout").toJava(String::class.java),
                    stderr = dict.callAttr("get", "stderr").toJava(String::class.java),
                    error = dict.callAttr("get", "error").toJava(String::class.java),
                    truncated = dict.callAttr("get", "truncated").toJava(Boolean::class.java),
                    durationMs = SystemClock.elapsedRealtime() - startedAt,
                )
            })
            // Hard wall-clock guard on the Kotlin side; the Python-side trace guard is the
            // primary stopper for pure-Python loops (documented limitation for C-level loops).
            future.get(deadlineSeconds + 15L, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            Result(false, "", "", "", false, SystemClock.elapsedRealtime() - startedAt,
                "Wall-clock limit reached; the script could not be interrupted and the runtime was left busy. " +
                    "Reload the Python tab or restart the app to clear it.")
        } catch (e: Exception) {
            Result(false, "", "", e.javaClass.simpleName, false, SystemClock.elapsedRealtime() - startedAt,
                "The runtime call failed: ${e.message?.take(200)}")
        } finally {
            executor.shutdown()
        }
    }

    /** Predefined tools: JSON/CSV/text analysis implemented in Python on the bundled runtime. */
    enum class Predefined(val title: String, val description: String) {
        JSON_SUMMARY("JSON summary", "Parse JSON, report structure, counts and key statistics."),
        CSV_SUMMARY("CSV summary", "Column names, row count, numeric min/max/mean per column."),
        TEXT_STATS("Text stats", "Line, word and character counts plus the 10 most frequent words."),
        JSONL_COUNT("JSONL count", "Line count and validity check for JSON Lines files."),
    }

    fun runPredefined(context: Context, tool: Predefined, input: String): Result {
        val code = when (tool) {
            Predefined.JSON_SUMMARY -> """
import json, sys
data = json.loads(INPUT)
def shape(v, depth=0):
    if isinstance(v, dict): return "object(%d keys)" % len(v)
    if isinstance(v, list): return "array(%d items)" % len(v)
    if isinstance(v, str): return "string(len=%d)" % len(v)
    return type(v).__name__
print("type:", shape(data))
if isinstance(data, dict):
    for k in list(data.keys())[:40]: print(" -", k, ":", shape(data[k]))
elif isinstance(data, list):
    print("first item:", shape(data[0]) if data else "(empty)")
print("valid JSON, %d characters" % len(INPUT))
""".trim()
            Predefined.CSV_SUMMARY -> """
import csv, io
rows = list(csv.reader(io.StringIO(INPUT)))
if not rows:
    print("(empty)")
else:
    header = rows[0]
    print("columns:", len(header), header)
    print("data rows:", len(rows) - 1)
    for i, col in enumerate(header):
        values = [r[i] for r in rows[1:] if i < len(r) and r[i] != ""]
        numeric = []
        for v in values:
            try: numeric.append(float(v))
            except ValueError: pass
        if numeric:
            print(" -", col, "min=%.4g max=%.4g mean=%.4g" % (min(numeric), max(numeric), sum(numeric)/len(numeric)))
        else:
            uniq = len(set(values))
            print(" -", col, "text: %d unique of %d" % (uniq, len(values)))
""".trim()
            Predefined.TEXT_STATS -> """
import collections, re
lines = INPUT.splitlines()
words = re.findall(r"\w+", INPUT.lower())
print("lines:", len(lines))
print("words:", len(words))
print("chars:", len(INPUT))
for w, c in collections.Counter(words).most_common(10):
    print(" -", w, c)
""".trim()
            Predefined.JSONL_COUNT -> """
import json
ok = bad = 0
for i, line in enumerate(INPUT.splitlines()):
    line = line.strip()
    if not line: continue
    try:
        json.loads(line); ok += 1
    except ValueError:
        bad += 1
        if bad <= 5: print("invalid line", i + 1)
print("valid:", ok, "invalid:", bad)
""".trim()
        }
        return run(context, code, input, deadlineSeconds = 30)
    }
}
