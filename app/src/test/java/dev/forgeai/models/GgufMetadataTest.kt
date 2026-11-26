package dev.forgeai.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** Generated registry fixtures only. No weights, tokenizer, or inference engine are tested. */
class GgufMetadataTest {
    private class Bytes : ByteArrayOutputStream() {
        fun word(value: Long, width: Int) = repeat(width) { write((value ushr (8 * it)).toInt()) }
        fun str(value: String) {
            val b = value.toByteArray()
            word(b.size.toLong(), 8)
            write(b)
        }
    }

    private fun field(name: String, type: Long, payload: Bytes.() -> Unit) =
        Bytes().apply { str(name); word(type, 4); payload() }.toByteArray()

    private fun arch(value: String = "qwen2") = field("general.architecture", 8) { str(value) }
    private fun quant(value: Long) = field("general.file_type", 4) { word(value, 4) }

    private fun fixture(
        vararg fields: ByteArray, version: Long = 3, tensors: Long = 1, offset: Long = 0,
        alignment: Int = 32, entries: Long = fields.size.toLong(),
    ): ByteArray = Bytes().apply {
        write("GGUF".toByteArray())
        word(version, 4); word(tensors, 8); word(entries, 8)
        fields.forEach { write(it) }
        str("test.weight"); word(1, 4); word(1, 8); word(0, 4); word(offset, 8)
        while (size() % alignment != 0) write(0)
        word(0, 4)
    }.toByteArray()

    private fun read(b: ByteArray, size: Long = b.size.toLong()) =
        GgufReader.read(ByteArrayInputStream(b), size)

    private fun reject(b: ByteArray, size: Long = b.size.toLong()): IOException =
        assertThrows(IOException::class.java) { read(b, size) }

    @Test fun registersDeclaredMetadataWithoutLoadingModel() {
        for (v in 2..3) for (a in listOf("qwen2", "qwen3", "llama")) {
            assertEquals(GgufMetadata(v, a, "Q4_K_M", 1), read(fixture(arch(a), quant(15), version = v.toLong())))
        }
        assertEquals("F32", read(fixture(arch(), quant(0))).quantization)
        assertEquals("Not declared", read(fixture(arch())).quantization)
        assertEquals("Unrecognized file type", read(fixture(arch(), quant(999))).quantization)
    }

    @Test fun streamsTokenizerSizedArraysBeforeArchitecture() {
        val n = 152_000
        val tokens = field("tokenizer.ggml.tokens", 9) {
            word(8, 4); word(n.toLong(), 8)
            repeat(n) { word(1, 8); write(97) }
        }
        val types = field("tokenizer.ggml.token_type", 9) {
            word(5, 4); word(n.toLong(), 8); write(ByteArray(4 * n))
        }
        val b = fixture(tokens, types, arch())
        // Simulates a provider that returns short reads and does not support skip().
        val stream = object : ByteArrayInputStream(b) {
            override fun read(buf: ByteArray, off: Int, len: Int) = super.read(buf, off, minOf(257, len))
            override fun skip(n: Long): Long = error("skip() must not be required")
        }
        assertEquals("qwen2", GgufReader.read(stream, b.size.toLong()).architecture)
    }

    @Test fun rejectsBadHeaderAndUnreasonableCounts() {
        reject(fixture(arch()).apply { this[0] = 0 })
        reject(fixture(arch(), version = 1))
        reject(fixture(arch(), version = 0x03000000))
        for (n in listOf(0L, -1L, Long.MAX_VALUE)) reject(fixture(arch(), tensors = n))
        reject(fixture(arch(), entries = 4097))
    }

    @Test fun rejectsBadArchitectureAndMetadataTypesWithoutEchoingValues() {
        reject(fixture())
        val ex = reject(fixture(arch("private-project-path")))
        assertFalse(ex.message.orEmpty().contains("private-project-path"))
        reject(fixture(arch(), arch()))
        reject(fixture(field("general.architecture", 4) { word(123, 4) }))
        reject(fixture(arch(), field("unknown", 1000) {}))
        reject(fixture(arch(), field("bad", 9) { word(9, 4); word(0, 8) }))
    }

    @Test fun rejectsUnsignedOverflowAndExcessiveLengthsBeforeSkipping() {
        for (length in listOf(-1L, Long.MAX_VALUE, 64L * 1024 * 1024)) {
            reject(fixture(arch(), field("oversize", 8) { word(length, 8) }), Long.MAX_VALUE)
        }
        reject(fixture(arch(), field("oversize", 9) { word(8, 4); word(1_000_001, 8) }))
    }

    @Test fun rejectsHeaderOnlyFilesEvenIfClaimedSizeIsLarger() {
        val b = fixture(arch())
        for (n in 0..b.size - 4) {
            reject(b.copyOf(n), b.size.toLong())
            reject(b, n.toLong())
        }
        reject(fixture(arch(), offset = 32))
        reject(fixture(arch(), offset = -1))
    }

    @Test fun respectsAlignment() {
        fun align(n: Long) = field("general.alignment", 4) { word(n, 4) }
        reject(fixture(arch(), align(0)))
        reject(fixture(arch(), align(24)))
        assertEquals("qwen2", read(fixture(align(128), arch(), alignment = 128)).architecture)
    }

    @Test fun rejectsMultiPartAndIncompleteShardMetadata() {
        fun shardCount(n: Long) = field("split.count", 2) { word(n, 2) }
        fun shardIndex(n: Long) = field("split.no", 2) { word(n, 2) }
        fun total(n: Long) = field("split.tensors.count", 5) { word(n, 4) }
        reject(fixture(arch(), shardCount(2)))
        reject(fixture(arch(), shardIndex(0)))
        reject(fixture(arch(), shardCount(1), shardIndex(1)))
        reject(fixture(arch(), shardCount(1), total(2)))
        assertEquals(1L, read(fixture(arch(), shardCount(1), shardIndex(0), total(1))).tensorCount)
    }

    @Test fun sanitizesProviderReadErrors() {
        val ex = assertThrows(IOException::class.java) {
            GgufReader.read(object : InputStream() {
                override fun read(): Int = throw IOException("private-document-uri")
            }, 1024)
        }
        assertFalse(ex.message.orEmpty().contains("private-document-uri"))
    }
}
