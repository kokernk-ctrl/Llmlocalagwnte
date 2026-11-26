package dev.forgeai.workspace

/**
 * Line-based unified diff over UTF-8 text. Pure Kotlin so the JVM suite can verify it.
 * The LCS table is bounded: files beyond the line cap fall back to a whole-file
 * replacement hunk instead of silently truncating the diff.
 */
internal object DiffTool {

    enum class Op { EQUAL, INSERT, DELETE }

    data class Line(val op: Op, val text: String)

    const val MAX_DIFF_LINES = 1500

    /** Splits on \n. A trailing empty segment means the text ended with a newline. */
    fun splitLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val raw = text.split("\n")
        return if (raw.last().isEmpty()) raw.dropLast(1) else raw
    }

    private fun lcsOps(a: List<String>, b: List<String>): ArrayList<Line> {
        val n = a.size
        val m = b.size
        val table = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            val row = table[i]
            val below = table[i + 1]
            for (j in m - 1 downTo 0) {
                row[j] = if (a[i] == b[j]) below[j + 1] + 1 else maxOf(below[j], row[j + 1])
            }
        }
        val ops = ArrayList<Line>(maxOf(n, m))
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { ops.add(Line(Op.EQUAL, a[i])); i++; j++ }
                table[i + 1][j] >= table[i][j + 1] -> { ops.add(Line(Op.DELETE, a[i])); i++ }
                else -> { ops.add(Line(Op.INSERT, b[j])); j++ }
            }
        }
        while (i < n) { ops.add(Line(Op.DELETE, a[i])); i++ }
        while (j < m) { ops.add(Line(Op.INSERT, b[j])); j++ }
        return ops
    }

    fun diff(original: List<String>, modified: List<String>): List<Line> {
        if (original.size > MAX_DIFF_LINES || modified.size > MAX_DIFF_LINES) {
            // Bounded fallback: present the change as a whole-file replacement.
            val ops = ArrayList<Line>()
            if (original.isNotEmpty() && original.size <= MAX_DIFF_LINES) {
                ops.addAll(original.map { Line(Op.DELETE, it) })
            } else ops.add(Line(Op.DELETE, "… (${original.size} lines, too large for inline diff)"))
            if (modified.isNotEmpty() && modified.size <= MAX_DIFF_LINES) {
                ops.addAll(modified.map { Line(Op.INSERT, it) })
            } else ops.add(Line(Op.INSERT, "… (${modified.size} lines, too large for inline diff)"))
            return ops
        }
        return lcsOps(original, modified)
    }

    /** Standard unified hunks: `@@ -a,n +b,m @@` with one leading space for equal lines. */
    fun unified(original: List<String>, modified: List<String>, context: Int = 3): String {
        require(context in 0..16)
        if (original == modified) return ""
        val ops = diff(original, modified)
        // 1-based line numbers on both sides; null for the side a line does not occupy.
        data class Marked(val op: Op, val text: String, val a: Int?, val b: Int?)
        val marked = ArrayList<Marked>(ops.size)
        var ai = 0
        var bi = 0
        for (line in ops) {
            val an = if (line.op != Op.INSERT) ++ai else null
            val bn = if (line.op != Op.DELETE) ++bi else null
            marked.add(Marked(line.op, line.text, an, bn))
        }
        val changed = IntArray(marked.size) { if (marked[it].op != Op.EQUAL) it else -1 }
            .filter { it >= 0 }
        if (changed.isEmpty()) return ""
        val out = StringBuilder()
        var g = 0
        while (g < changed.size) {
            val first = changed[g]
            var last = first
            var next = g + 1
            while (next < changed.size && changed[next] - last <= 2 * context + 1) {
                last = changed[next]; next++
            }
            val start = maxOf(0, first - context)
            val end = minOf(marked.size - 1, last + context)
            // Git convention: origin is the first line present on each side inside the hunk;
            // pure insertion/deletion edges fall back to the previous line number (or 0 at
            // the file start) so "@@ -0,0" appears only for truly empty sides.
            val aFrom = marked.slice(start..end).firstNotNullOfOrNull { it.a }
                ?: (if (start > 0) marked[start - 1].a else 0) ?: 0
            val bFrom = marked.slice(start..end).firstNotNullOfOrNull { it.b }
                ?: (if (start > 0) marked[start - 1].b else 0) ?: 0
            var aCount = 0
            var bCount = 0
            for (x in start..end) {
                if (marked[x].op != Op.INSERT) aCount++
                if (marked[x].op != Op.DELETE) bCount++
            }
            out.append("@@ -").append(aFrom).append(',').append(aCount)
            out.append(" +").append(bFrom).append(',').append(bCount).append(" @@\n")
            for (x in start..end) {
                val line = marked[x]
                val sign = when (line.op) { Op.EQUAL -> ' '; Op.DELETE -> '-'; Op.INSERT -> '+' }
                out.append(sign).append(line.text).append('\n')
            }
            g = next
        }
        return out.toString()
    }

    fun unifiedText(original: String, modified: String, context: Int = 3): String =
        unified(splitLines(original), splitLines(modified), context)
}
