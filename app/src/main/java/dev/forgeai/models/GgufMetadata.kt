package dev.forgeai.models

import java.io.IOException
import java.io.InputStream

data class GgufMetadata(
    val version: Int,
    val architecture: String,
    val quantization: String,
    val tensorCount: Long,
)

/**
 * Bounded registry inspection, not a model loader. Walks metadata and the tensor directory
 * sequentially and checks that data starts. Does NOT validate tensor types, extents, model
 * completeness, or inference compatibility. Caller owns the stream; byteSize is the file size.
 */
object GgufReader {
    private const val PREFIX_LIMIT = 64L * 1024 * 1024
    private const val MAX_ENTRIES = 4096
    private const val MAX_TENSORS = 100_000
    private const val MAX_ARRAY = 1_000_000
    private const val STRING = 8
    private const val ARRAY = 9
    private const val UINT32 = 4

    @Throws(IOException::class)
    fun read(input: InputStream, byteSize: Long): GgufMetadata {
        if (byteSize < 24) fail("File is too small for a GGUF header.")
        val p = Prefix(input, minOf(byteSize, PREFIX_LIMIT))
        if (p.word(4) != 0x46554747L) fail("Not a GGUF file.")
        val version = p.word(4)
        if (version !in 2L..3L) fail("Only little-endian GGUF v2 and v3 are supported.")
        val tensors = p.word(8)
        if (tensors !in 1L..MAX_TENSORS.toLong()) fail("Unsupported GGUF tensor count.")
        val entries = p.count(MAX_ENTRIES)
        val keys = HashSet<String>()
        var arch: String? = null
        var quant = "Not declared"
        var alignment = 32L
        var shardMetadata = false
        var singleShard = false
        repeat(entries) {
            val key = p.text(512)
            if (key.isEmpty() || !keys.add(key)) fail("Empty or duplicate GGUF metadata key.")
            val type = p.word(4)
            when (key) {
                "general.architecture" -> {
                    expect(type, STRING)
                    arch = p.text(64).also {
                        if (it != "qwen2" && it != "qwen3" && it != "llama")
                            fail("Model architecture is not supported by this registry.")
                    }
                }
                "general.file_type" -> {
                    expect(type, UINT32)
                    quant = fileTypes[p.word(4)] ?: "Unrecognized file type"
                }
                "general.alignment" -> {
                    expect(type, UINT32)
                    alignment = p.word(4)
                    if (alignment !in 1L..65536L || (alignment and (alignment - 1)) != 0L)
                        fail("Unsupported GGUF alignment.")
                }
                "split.count", "split.no", "split.tensors.count" -> {
                    shardMetadata = true
                    val total = key == "split.tensors.count"
                    expect(type, if (total) 5 else 2)
                    val n = p.word(if (total) 4 else 2)
                    when (key) {
                        "split.count" -> singleShard = n == 1L
                        "split.no" -> if (n != 0L) fail("Split GGUF files are not supported.")
                        else -> if (n != tensors) fail("Split GGUF files are not supported.")
                    }
                }
                else -> p.discardValue(type)
            }
        }
        val architecture = arch ?: fail("Missing GGUF architecture.")
        if (shardMetadata && !singleShard) fail("Split GGUF files are not supported.")

        var largestOffset = 0L
        repeat(tensors.toInt()) {
            val nameLength = p.count(64)
            if (nameLength == 0) fail("Empty GGUF tensor name.")
            p.discard(nameLength.toLong())
            val dimensions = p.word(4)
            if (dimensions !in 1L..4L) fail("Unsupported GGUF tensor dimensions.")
            repeat(dimensions.toInt()) {
                if (p.word(8) <= 0) fail("Invalid GGUF tensor shape.")
            }
            p.discard(4) // ggml_type: not decoded by this registry.
            val offset = p.word(8)
            if (offset < 0 || offset % alignment != 0L) fail("Invalid GGUF tensor offset.")
            largestOffset = maxOf(offset, largestOffset)
        }
        p.discard((alignment - p.position % alignment) % alignment)
        if (largestOffset >= byteSize - p.position) fail("Missing GGUF tensor data.")
        p.discard(1)
        return GgufMetadata(version.toInt(), architecture, quant, tensors)
    }

    // Common general.file_type (llama_ftype) values, NOT per-tensor ggml_type values.
    private val fileTypes = listOf(
        "F32", "F16", "Q4_0", "Q4_1", null, null, null, "Q8_0", "Q5_0", "Q5_1",
        "Q2_K", "Q3_K_S", "Q3_K_M", "Q3_K_L", "Q4_K_S", "Q4_K_M", "Q5_K_S", "Q5_K_M",
        "Q6_K", "IQ2_XXS", "IQ2_XS", "Q2_K_S", "IQ3_XS", "IQ3_XXS", "IQ1_S", "IQ4_NL",
        "IQ3_S", "IQ3_M", "IQ2_S", "IQ2_M", "IQ4_XS", "IQ1_M", "BF16",
    ).mapIndexed { index, name -> index.toLong() to name }.toMap()

    private fun expect(actual: Long, expected: Int) {
        if (actual != expected.toLong()) fail("Invalid type for GGUF metadata field.")
    }

    private fun fail(message: String): Nothing = throw IOException(message)

    /** One small buffer independent of model size. Never trusts skip(), available(), or a length. */
    private class Prefix(private val source: InputStream, private val limit: Long) {
        private val buffer = ByteArray(8192)
        private var i = 0
        private var end = 0
        var position = 0L
            private set

        private fun refill() {
            if (i < end) return
            val room = minOf(buffer.size.toLong(), limit - position).toInt()
            if (room <= 0) fail("GGUF header is truncated or exceeds the scan limit.")
            i = 0
            end = try {
                source.read(buffer, 0, room)
            } catch (_: IOException) {
                fail("Could not read GGUF file.")
            }
            if (end !in 1..room) fail("Truncated or unreadable GGUF file.")
        }

        fun word(width: Int): Long {
            var result = 0L
            repeat(width) { k ->
                refill()
                result = result or ((buffer[i++].toLong() and 255) shl (8 * k))
                position++
            }
            return result
        }

        fun count(max: Int): Int {
            val n = word(8)
            if (n !in 0L..max.toLong()) fail("Unsupported GGUF length or count.")
            return n.toInt()
        }

        fun text(max: Int): String {
            val n = count(max)
            return ByteArray(n) { word(1).toByte() }.toString(Charsets.UTF_8)
        }

        fun discard(n: Long) {
            if (n < 0 || n > limit - position) fail("GGUF field exceeds file or scan limit.")
            var left = n
            while (left > 0) {
                refill()
                val take = minOf(left, (end - i).toLong()).toInt()
                i += take
                position += take
                left -= take
            }
        }

        fun discardValue(type: Long) {
            if (type == ARRAY.toLong()) {
                val element = word(4)
                val count = count(MAX_ARRAY)
                if (element == STRING.toLong()) repeat(count) { discard(word(8)) }
                else discard(width(element) * count)
            } else if (type == STRING.toLong()) discard(word(8))
            else discard(width(type))
        }

        private fun width(type: Long) = when (type) {
            0L, 1L, 7L -> 1L
            2L, 3L -> 2L
            4L, 5L, 6L -> 4L
            10L, 11L, 12L -> 8L
            else -> fail("Unsupported GGUF metadata value type.")
        }
    }
}
