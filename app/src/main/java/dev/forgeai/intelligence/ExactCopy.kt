package dev.forgeai.intelligence

import java.io.InputStream
import java.io.OutputStream

internal const val MAX_MODEL_BYTES = 1536L * 1024 * 1024

/** The advertised size is a hard ceiling, not a hint. Never write an oversized chunk. */
internal fun copyExact(source: InputStream, out: OutputStream, expected: Long,
    checkActive: () -> Unit, progress: (Int) -> Unit) {
    require(expected in 1..MAX_MODEL_BYTES)
    val buf = ByteArray(256 * 1024)
    var total = 0L
    var percent = -1
    while (true) {
        checkActive()
        val n = source.read(buf)
        if (n == -1) break
        if (n !in 1..buf.size || n > expected - total)
            throw ChatProblem("The document size did not match its registration.")
        checkActive()
        out.write(buf, 0, n)
        total += n
        val next = (100 * total / expected).toInt()
        if (next != percent) { percent = next; progress(percent) }
    }
    if (total != expected) throw ChatProblem("Incomplete model copy. Check the source document.")
}
