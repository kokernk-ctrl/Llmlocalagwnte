package dev.forgeai.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffToolTest {

    @Test
    fun `identical text produces empty diff`() {
        val text = "a\nb\nc"
        assertEquals("", DiffTool.unifiedText(text, text))
    }

    @Test
    fun `single line modification hunk matches git output`() {
        val diff = DiffTool.unifiedText("alpha\nbeta\ngamma\ndelta", "alpha\nbeta\nGAMMA\ndelta")
        // Reference: git diff --no-index --unified=3 on the same inputs.
        assertTrue(diff.contains("@@ -1,4 +1,4 @@"))
        assertTrue(diff.contains(" alpha"))
        assertTrue(diff.contains("-gamma"))
        assertTrue(diff.contains("+GAMMA"))
        assertTrue(diff.contains(" delta"))
    }

    @Test
    fun `insert at start matches git output`() {
        val diff = DiffTool.unifiedText("b\nc", "a\nb\nc")
        assertTrue(diff.contains("@@ -1,2 +1,3 @@"))
        assertTrue(diff.contains("+a"))
    }

    @Test
    fun `delete at end`() {
        val diff = DiffTool.unifiedText("a\nb\nc", "a\nb")
        assertTrue(diff.contains("-c"))
        assertTrue(diff.contains("@@ -1,3 +1,2 @@") || diff.contains("@@ -2,2 +2,1 @@") ||
            diff.contains("@@ -3,1 +2,0 @@"))
    }

    @Test
    fun `splitLines drops trailing newline segment`() {
        assertEquals(listOf("a", "b"), DiffTool.splitLines("a\nb\n"))
        assertEquals(listOf("a", "b", ""), DiffTool.splitLines("a\nb\n\n"))
        assertEquals(emptyList<String>(), DiffTool.splitLines(""))
    }

    @Test
    fun `oversized files fall back to whole-file replacement hunk`() {
        val big = List(2000) { "line $it" }
        val diff = DiffTool.unified(big, big.mapIndexed { i, s -> if (i == 1999) "changed" else s })
        assertTrue(diff.contains("@@ -1,"))
        assertTrue(diff.contains("too large for inline diff"))
    }

    @Test
    fun `append produces insertion at end`() {
        val diff = DiffTool.unifiedText("hello", "hello\n\n<!-- edited by FORGE AI agent -->")
        assertTrue(diff.contains("+"))
        assertTrue(diff.contains("<!-- edited by FORGE AI agent -->"))
        assertTrue(diff.contains(" hello"))
    }
}
