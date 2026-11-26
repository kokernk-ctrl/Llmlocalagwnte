package dev.forgeai.tools

/**
 * Minimal strict JSON parser for model tool calls. Pure Kotlin so the JVM test suite
 * validates it without Android stubs. Accepts RFC 8259 JSON; rejects trailing data,
 * control characters in strings, and NaN/Infinity.
 */
sealed class JVal {
    object JNull : JVal()
    data class JBool(val value: Boolean) : JVal()
    data class JNum(val value: Double) : JVal()
    data class JStr(val value: String) : JVal()
    data class JArr(val items: List<JVal>) : JVal()
    data class JObj(val fields: Map<String, JVal>) : JVal()
}

class JsonParseException(message: String) : Exception(message)

internal object JsonLite {

    fun parse(text: String): JVal {
        val p = Parser(text)
        val value = p.parseValue()
        p.skipWs()
        if (!p.atEnd()) throw JsonParseException("Trailing data after JSON value.")
        return value
    }

    /** Extracts balanced top-level JSON objects embedded in free text (tool-call scanning). */
    fun extractObjects(text: String, maxObjects: Int = 16): List<JVal.JObj> {
        require(maxObjects in 1..64)
        val found = mutableListOf<JVal.JObj>()
        var i = 0
        while (i < text.length && found.size < maxObjects) {
            if (text[i] != '{') { i++; continue }
            // Attempt a balanced scan from here; on failure advance one char.
            var depth = 0
            var inString = false
            var escaped = false
            var end = -1
            var j = i
            while (j < text.length) {
                val c = text[j]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == '"' -> inString = false
                        c == '\n' || c == '\r' -> break
                    }
                } else {
                    when {
                        c == '"' -> inString = true
                        c == '{' -> depth++
                        c == '}' -> { depth--; if (depth == 0) { end = j; break } }
                    }
                    if (j - i > 8192) break
                }
                j++
            }
            if (end > i) {
                try {
                    val candidate = text.substring(i, end + 1)
                    val parsed = parse(candidate)
                    if (parsed is JVal.JObj) { found.add(parsed); i = end + 1; continue }
                } catch (_: JsonParseException) { /* fall through */ }
            }
            i++
        }
        return found
    }

    private class Parser(val s: String) {
        var pos = 0
        fun atEnd() = pos >= s.length
        fun skipWs() { while (pos < s.length && s[pos] in " \t\r\n") pos++ }
        fun fail(msg: String): Nothing = throw JsonParseException("$msg (at $pos)")

        fun parseValue(): JVal {
            skipWs()
            if (atEnd()) fail("Unexpected end of input")
            return when (s[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JVal.JStr(parseString())
                't' -> literal("true", JVal.JBool(true))
                'f' -> literal("false", JVal.JBool(false))
                'n' -> literal("null", JVal.JNull)
                else -> parseNumber()
            }
        }

        fun literal(word: String, value: JVal): JVal {
            if (!s.startsWith(word, pos)) fail("Invalid literal")
            pos += word.length
            return value
        }

        fun parseObject(): JVal.JObj {
            check(s[pos] == '{'); pos++
            val fields = LinkedHashMap<String, JVal>()
            skipWs()
            if (!atEnd() && s[pos] == '}') { pos++; return JVal.JObj(fields) }
            while (true) {
                skipWs()
                if (atEnd() || s[pos] != '"') fail("Object key expected")
                val key = parseString()
                skipWs()
                if (atEnd() || s[pos] != ':') fail("':' expected")
                pos++
                fields[key] = parseValue()
                skipWs()
                when {
                    atEnd() -> fail("Unterminated object")
                    s[pos] == ',' -> pos++
                    s[pos] == '}' -> { pos++; return JVal.JObj(fields) }
                    else -> fail("',' or '}' expected")
                }
            }
        }

        fun parseArray(): JVal.JArr {
            check(s[pos] == '['); pos++
            val items = ArrayList<JVal>()
            skipWs()
            if (!atEnd() && s[pos] == ']') { pos++; return JVal.JArr(items) }
            while (true) {
                items.add(parseValue())
                skipWs()
                when {
                    atEnd() -> fail("Unterminated array")
                    s[pos] == ',' -> pos++
                    s[pos] == ']' -> { pos++; return JVal.JArr(items) }
                    else -> fail("',' or ']' expected")
                }
            }
        }

        fun parseString(): String {
            check(s[pos] == '"'); pos++
            val out = StringBuilder()
            while (true) {
                if (atEnd()) fail("Unterminated string")
                val c = s[pos]
                when {
                    c == '"' -> { pos++; return out.toString() }
                    c == '\\' -> {
                        pos++
                        if (atEnd()) fail("Unterminated escape")
                        when (val e = s[pos]) {
                            '"' -> out.append('"'); '\\' -> out.append('\\'); '/' -> out.append('/')
                            'b' -> out.append('\b'); 'f' -> out.append('\u000C')
                            'n' -> out.append('\n'); 'r' -> out.append('\r'); 't' -> out.append('\t')
                            'u' -> {
                                if (pos + 4 >= s.length) fail("Truncated unicode escape")
                                val hex = s.substring(pos + 1, pos + 5)
                                val code = hex.toIntOrNull(16) ?: fail("Invalid unicode escape")
                                out.append(code.toChar())
                                pos += 4
                            }
                            else -> fail("Invalid escape \\$e")
                        }
                        pos++
                    }
                    c < ' ' -> fail("Raw control character in string")
                    else -> { out.append(c); pos++ }
                }
            }
        }

        fun parseNumber(): JVal.JNum {
            val start = pos
            if (!atEnd() && s[pos] == '-') pos++
            while (!atEnd() && s[pos] in '0'..'9') pos++
            if (!atEnd() && s[pos] == '.') { pos++; while (!atEnd() && s[pos] in '0'..'9') pos++ }
            if (!atEnd() && s[pos] in "eE") {
                pos++
                if (!atEnd() && s[pos] in "+-") pos++
                while (!atEnd() && s[pos] in '0'..'9') pos++
            }
            val slice = s.substring(start, pos)
            if (slice.isEmpty() || slice == "-") fail("Invalid number")
            val value = slice.toDoubleOrNull() ?: fail("Invalid number $slice")
            if (value.isNaN() || value.isInfinite()) fail("Number out of range")
            return JVal.JNum(value)
        }
    }

    // Convenience accessors with strict typing for tool arguments.
    fun obj(v: JVal?, key: String): JVal.JObj? = (v as? JVal.JObj)?.fields?.get(key) as? JVal.JObj
    fun str(v: JVal.JObj, key: String): String? = (v.fields[key] as? JVal.JStr)?.value
    fun num(v: JVal.JObj, key: String): Double? = (v.fields[key] as? JVal.JNum)?.value
    fun bool(v: JVal.JObj, key: String): Boolean? = (v.fields[key] as? JVal.JBool)?.value
    fun arr(v: JVal.JObj, key: String): List<JVal>? = (v.fields[key] as? JVal.JArr)?.items

    /** Serializes a JVal tree to compact JSON (checkpoints and tool results). */
    fun write(v: JVal): String {
        val out = StringBuilder(256)
        render(v, out)
        return out.toString()
    }

    private fun render(v: JVal, out: StringBuilder) {
        when (v) {
            is JVal.JNull -> out.append("null")
            is JVal.JBool -> out.append(if (v.value) "true" else "false")
            is JVal.JNum -> {
                val d = v.value
                if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) {
                    out.append(d.toLong().toString())
                } else out.append(d.toString())
            }
            is JVal.JStr -> renderString(v.value, out)
            is JVal.JArr -> {
                out.append('[')
                v.items.forEachIndexed { i, item -> if (i > 0) out.append(','); render(item, out) }
                out.append(']')
            }
            is JVal.JObj -> {
                out.append('{')
                var first = true
                for ((k, item) in v.fields) {
                    if (!first) out.append(',')
                    first = false
                    renderString(k, out)
                    out.append(':')
                    render(item, out)
                }
                out.append('}')
            }
        }
    }

    private fun renderString(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c == '\b' -> out.append("\\b")
                c == '\u000C' -> out.append("\\f")
                c < ' ' -> out.append("\\u").append(String.format("%04x", c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
    }
}
