package dev.forgeai.intelligence

import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ExactCopyTest {
    @Test fun copiesExactlyAndSignalsCompletion() {
        val data = ByteArray(300_000) { it.toByte() }
        val out = ByteArrayOutputStream()
        val percentages = mutableListOf<Int>()
        copyExact(data.inputStream(), out, data.size.toLong(), {}, percentages::add)
        assertArrayEquals(data, out.toByteArray())
        assertEquals(100, percentages.last())
        assertTrue(percentages.size > 1)
    }
    @Test fun rejectsTruncationAndExcessData() {
        for (length in listOf(9L, 11L)) {
            val out = ByteArrayOutputStream()
            assertThrows(ChatProblem::class.java) { copyExact(ByteArray(10).inputStream(), out, length, {}, {}) }
            assertTrue(out.size().toLong() <= length)
        }
    }
    @Test fun cancelAfterReadDoesNotWrite() {
        val out = ByteArrayOutputStream()
        var checked = false
        assertThrows(CancellationException::class.java) {
            copyExact(byteArrayOf(1).inputStream(), out, 1L, {
                if (checked) throw CancellationException()
                checked = true
            }, {})
        }
        assertEquals(0, out.size())
    }
}
