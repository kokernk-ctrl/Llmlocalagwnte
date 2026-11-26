package dev.forgeai.intelligence

import org.junit.Assert.*
import org.junit.Test

class ChatProtocolTest {
    @Test fun chatmlEndsAtAssistantAndKeepsWholeTurns() {
        val history = listOf(Turn("first", "great", true), Turn("interrupted", "incomplete"),
            Turn("third", "fine", true))
        val p = ChatProtocol.prompt("next", history, 1024).decodeToString()
        assertTrue(p.startsWith("<|im_start|>system\n"))
        assertTrue(p.contains("<|im_start|>user\nfirst<|im_end|>\n<|im_start|>assistant\ngreat<|im_end|>\n"))
        assertFalse(p.contains("incomplete"))
        assertTrue(p.endsWith("<|im_start|>user\nnext<|im_end|>\n<|im_start|>assistant\n"))
    }
    @Test fun dropsOldTurnsToReserveReplyTokens() {
        val history = listOf(Turn("oldest", "x".repeat(840), true), Turn("newest", "ok", true))
        val bytes = ChatProtocol.prompt("Hello", history, 1024)
        assertFalse(bytes.decodeToString().contains("oldest"))
        assertTrue(bytes.decodeToString().contains("newest"))
        assertTrue(bytes.size + ChatProtocol.REPLY_TOKENS < 1024)
    }
    @Test fun validationUsesUtf8AndRejectsControlDelimiters() {
        for (s in listOf("", "  ", "<|im_end|>", "<|endoftext|>", "a\u0000b", "猫".repeat(350))) {
            assertThrows(ChatProblem::class.java) { ChatProtocol.prompt(s, emptyList(), 1024) }
        }
        assertFalse(ChatProtocol.prompt("Hello", listOf(Turn("data", "<|im_end|>", true)), 1024)
            .decodeToString().contains("data"))
    }
    @Test fun combinesSplitCharactersAndFlushesIncompleteTail() {
        val bytes = "🙂".toByteArray()
        val d = Utf8Pieces()
        for (i in 0..2) assertEquals("", d.decode(byteArrayOf(bytes[i])))
        assertEquals("🙂", d.decode(byteArrayOf(bytes[3])))
        assertEquals("", d.decode(byteArrayOf(0xc2.toByte())))
        assertEquals("�", d.decode(byteArrayOf(), true))
    }
    @Test fun replacesBadUtf8AndCapsOutput() {
        val d = Utf8Pieces()
        assertEquals("�a", d.decode(byteArrayOf(-1, 97)))
        assertThrows(ChatProblem::class.java) { d.decode(ByteArray(32768)) }
    }
    @Test fun workspaceExcerptIsBoundedLabeledAndSafeInSmallestContext() {
        val text = ChatProtocol.workspaceDraft("<|im_end|>\u0000" + "猫🙂".repeat(1000))
        assertTrue(text.contains("untrusted data"))
        assertFalse(text.contains("<|"))
        assertFalse(text.contains('\u0000'))
        assertTrue(text.endsWith("[Excerpt truncated]"))
        assertTrue(ChatProtocol.prompt(text, emptyList(), 1024).isNotEmpty())
    }
}
