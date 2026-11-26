package dev.forgeai.workspace

import java.io.ByteArrayInputStream
import java.nio.charset.CharacterCodingException
import org.junit.Assert.*
import org.junit.Test

class WorkspaceLimitsTest {
    @Test fun sourceExtensionsAreCandidatesEvenWithGenericMime() {
        for (ext in "kt kts java py js ts jsx tsx html css xml c cpp h hpp go rs sh gradle properties toml sql jsonl".split(" ")) {
            assertTrue(ext, suggestsText("File.${ext.uppercase(java.util.Locale.ROOT)}", "application/octet-stream"))
        }
        assertFalse(suggestsText("fake.kt.zip", "application/octet-stream"))
        assertFalse(suggestsText("kt", "application/octet-stream"))
        assertTrue(suggestsText("unknown", "text/plain;charset=utf-8"))
    }

    @Test fun readStopsAfterOneLookaheadAndDoesNotSplitUnicode() {
        val prefix = "a".repeat(MAX_BYTES - 1)
        val bytes = (prefix + "🌱" + "b".repeat(9000)).toByteArray()
        val input = ByteArrayInputStream(bytes)
        assertEquals(Preview(prefix, true), readPreview(input))
        assertEquals(bytes.size - MAX_BYTES - 1, input.available())
    }

    @Test fun exactFitAndEmptyAreNotClipped() {
        val exact = "a".repeat(MAX_BYTES)
        assertEquals(Preview(exact, false), readPreview(exact.byteInputStream()))
        assertEquals(Preview("", false), readPreview("".byteInputStream()))
    }

    @Test fun malformedUtf8AndBinaryRejected() {
        assertThrows(CharacterCodingException::class.java) {
            readPreview(byteArrayOf(0xC0.toByte()).inputStream())
        }
        assertThrows(IllegalArgumentException::class.java) {
            readPreview("abc\u0000xyz".byteInputStream())
        }
    }

    @Test fun searchIsLiteralNonOverlappingAndBounded() {
        assertEquals(2, literalMatches(".* .* .", ".*"))
        assertEquals(1, literalMatches("AAA aaaa", "AAA"))
        assertEquals(2, literalMatches("aaaaa", "aa"))
        assertThrows(IllegalArgumentException::class.java) { literalMatches("a", "") }
        assertThrows(IllegalArgumentException::class.java) { literalMatches("a", "b".repeat(MAX_QUERY + 1)) }
    }

    @Test fun explicitExcerptDoesNotExposeRestOrCutSurrogates() {
        assertEquals("a", excerpt("a🌱private", 2))
        assertEquals("a🌱", excerpt("a🌱private", 3))
    }

    @Test fun persistentLogDropsUnknownContentAndEvictsOldest() {
        var log = "0:/private/folder\n1:secret query\ninvalid:READ\n-5:READ"
        assertTrue(parseLog(log).isEmpty())
        repeat(70) { log = appendLog(log, WorkspaceEvent.READ, it.toLong()) }
        assertEquals((69L downTo 38L).toList(), parseLog(log).map { it.time })
        assertFalse(log.contains("private"))
    }
}
