package dev.forgeai.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellPolicyTest {

    private fun validate(policy: ShellPolicy.Policy, cmd: String, args: List<String>) =
        ShellPolicy.validate(policy, cmd, args)

    @Test
    fun `accepts whitelisted read-only command`() {
        val (name, argv) = validate(ShellPolicy.Policy.AGENT, "wc", listOf("-l", "file.txt"))
        assertEquals("wc", name)
        assertEquals(listOf("-l", "file.txt"), argv)
    }

    @Test
    fun `accepts head with numeric parameter`() {
        val (_, argv) = validate(ShellPolicy.Policy.USER, "head", listOf("-n", "20", "a/b.log"))
        assertEquals(listOf("-n", "20", "a/b.log"), argv)
    }

    @Test
    fun `rejects commands outside the whitelist`() {
        for (cmd in listOf("rm", "cp", "mv", "sh", "am", "pm", "settings", "sed", "awk")) {
            try {
                validate(ShellPolicy.Policy.AGENT, cmd, emptyList())
                org.junit.Assert.fail("$cmd should be rejected")
            } catch (_: ShellPolicy.PolicyException) { /* expected */ }
        }
    }

    @Test
    fun `agent policy rejects find and du`() {
        try { validate(ShellPolicy.Policy.AGENT, "find", emptyList()); org.junit.Assert.fail("expected rejection") }
        catch (_: ShellPolicy.PolicyException) {}
        try { validate(ShellPolicy.Policy.AGENT, "du", listOf("-s")); org.junit.Assert.fail("expected rejection") }
        catch (_: ShellPolicy.PolicyException) {}
    }

    @Test
    fun `rejects absolute paths and traversal`() {
        try { validate(ShellPolicy.Policy.USER, "cat", listOf("/etc/passwd")); org.junit.Assert.fail() }
        catch (_: ShellPolicy.PolicyException) {}
        try { validate(ShellPolicy.Policy.USER, "cat", listOf("../../../etc/passwd")); org.junit.Assert.fail() }
        catch (_: ShellPolicy.PolicyException) {}
        try { validate(ShellPolicy.Policy.USER, "cat", listOf("a/../../b")); org.junit.Assert.fail() }
        catch (_: ShellPolicy.PolicyException) {}
    }

    @Test
    fun `rejects unknown and long flags`() {
        try { validate(ShellPolicy.Policy.USER, "wc", listOf("--help")); org.junit.Assert.fail() }
        catch (_: ShellPolicy.PolicyException) {}
        try { validate(ShellPolicy.Policy.USER, "wc", listOf("-x")); org.junit.Assert.fail() }
        catch (_: ShellPolicy.PolicyException) {}
        try { validate(ShellPolicy.Policy.USER, "grep", listOf("-r")); org.junit.Assert.fail() }
        catch (_: ShellPolicy.PolicyException) {}
    }

    @Test
    fun `rejects control characters and oversize args`() {
        try { validate(ShellPolicy.Policy.USER, "echo", listOf("a\u0007b")); org.junit.Assert.fail() }
        catch (_: ShellPolicy.PolicyException) {}
        try { validate(ShellPolicy.Policy.USER, "echo", listOf("x".repeat(5000))); org.junit.Assert.fail() }
        catch (_: ShellPolicy.PolicyException) {}
    }

    @Test
    fun `rejects command names with separators`() {
        try { validate(ShellPolicy.Policy.USER, "wc;rm", emptyList()); org.junit.Assert.fail() }
        catch (_: ShellPolicy.PolicyException) {}
        try { validate(ShellPolicy.Policy.USER, "/system/bin/wc", emptyList()); org.junit.Assert.fail() }
        catch (_: ShellPolicy.PolicyException) {}
        try { validate(ShellPolicy.Policy.USER, "wc -l", emptyList()); org.junit.Assert.fail() }
        catch (_: ShellPolicy.PolicyException) {}
    }

    @Test
    fun `find name pattern stays inside working copy`() {
        val (_, argv) = validate(ShellPolicy.Policy.USER, "find", listOf("-name", "*.md", "-maxdepth", "2"))
        assertTrue(argv.contains("*.md"))
        try { validate(ShellPolicy.Policy.USER, "find", listOf("-name", "a/b")); org.junit.Assert.fail() }
        catch (_: ShellPolicy.PolicyException) {}
    }

    @Test
    fun `stat format whitelist`() {
        validate(ShellPolicy.Policy.USER, "stat", listOf("-c", "%s", "f.txt"))
        try { validate(ShellPolicy.Policy.USER, "stat", listOf("-c", "%A", "f.txt")); org.junit.Assert.fail() }
        catch (_: ShellPolicy.PolicyException) {}
    }
}
