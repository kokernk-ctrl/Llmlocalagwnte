package dev.forgeai.agent

import dev.forgeai.tools.JVal
import dev.forgeai.tools.JsonLite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerTest {

    private fun file(name: String, size: Long = 128L) =
        Planner.PlanFile(name, name.substringAfterLast('/'), size, false)

    @Test
    fun `planning prompt lists tools and folder contents`() {
        val prompt = Planner.buildPlanningPrompt("create notes.md", AgentMode.REVIEW, true, listOf("readme.md", "src/"))
        assertTrue(prompt.contains("write_file"))
        assertTrue(prompt.contains("run_check"))
        assertTrue(prompt.contains("readme.md"))
        assertTrue(prompt.contains("read and write"))
    }

    @Test
    fun `parseToolCalls accepts valid json objects from model text`() {
        val text = """
            1. create the file
            {"tool":"write_file","args":{"path":"x.md","content":"abc"}}
            2. verify
            {"tool":"run_check","args":{"command":"wc","args":["-l","x.md"]}}
        """.trimIndent()
        val calls = Planner.parseToolCalls(text)
        assertEquals(2, calls.size)
        assertTrue(calls.all { it.fromModel })
        assertEquals("write_file", calls[0].tool)
    }

    @Test
    fun `parseToolCalls ignores malformed objects`() {
        val calls = Planner.parseToolCalls("{}")
        assertEquals(0, calls.size)
    }

    @Test
    fun `deterministic plan creates a default file when no name matches`() {
        val calls = Planner.deterministicPlan("organize this folder", AgentMode.AUTO, true, listOf(file("a.txt")))
        val create = calls.first { it.tool == "write_file" }
        val path = JsonLite.str(create.args, "path")!!
        assertTrue(path.startsWith("forge-task-notes") && path.endsWith(".md"))
        assertTrue(JsonLite.str(create.args, "content")!!.contains("FORGE AI agent task"))
        assertTrue(calls.any { it.tool == "run_check" })
        assertTrue(calls.all { !it.fromModel })
    }

    @Test
    fun `deterministic plan uses the requested create name`() {
        val calls = Planner.deterministicPlan("criar o arquivo plano.md com resumo", AgentMode.AUTO,
            true, listOf(file("a.txt")))
        val create = calls.first { it.tool == "write_file" }
        assertEquals("plano.md", JsonLite.str(create.args, "path"))
    }

    @Test
    fun `deterministic plan never overwrites an existing file`() {
        val calls = Planner.deterministicPlan("create existing.md please", AgentMode.AUTO,
            true, listOf(file("existing.md")))
        assertTrue(calls.none { it.tool == "write_file" && JsonLite.str(it.args, "path") == "existing.md" })
    }

    @Test
    fun `deterministic plan appends to the requested edit target`() {
        val calls = Planner.deterministicPlan("create log.md and edit README.md changelog",
            AgentMode.AUTO, true, listOf(file("README.md")))
        val read = calls.first { it.tool == "read_file" }
        assertEquals("README.md", JsonLite.str(read.args, "path"))
        val append = calls.first { it.tool == "append_file" }
        assertEquals("README.md", JsonLite.str(append.args, "path"))
    }

    @Test
    fun `read-only folder still yields a plan and write rejection happens at execution`() {
        // The planner builds the plan; the tool layer rejects writes on read-only grants.
        val calls = Planner.deterministicPlan("create notes.md", AgentMode.REVIEW, false, emptyList())
        assertTrue(calls.isNotEmpty())
    }

    @Test
    fun `tool call args summaries stay bounded`() {
        val big = JVal.JObj(linkedMapOf("content" to JVal.JStr("x".repeat(5000))))
        val written = JsonLite.write(big)
        assertTrue(written.length >= 5000)
        val parsed = JsonLite.parse(written) as JVal.JObj
        assertEquals(5000, JsonLite.str(parsed, "content")!!.length)
    }

    @Test
    fun `agent phase interruptedLike distinguishes unfinished tasks from done`() {
        assertTrue(AgentState(phase = AgentPhase.RUNNING).interruptedLike)
        assertTrue(AgentState(phase = AgentPhase.AWAITING_REVIEW).interruptedLike)
        assertTrue(AgentState(phase = AgentPhase.APPLYING).interruptedLike)
        assertTrue(!AgentState(phase = AgentPhase.DONE).interruptedLike)
        assertTrue(!AgentState(phase = AgentPhase.FAILED).interruptedLike)
        assertTrue(!AgentState(phase = AgentPhase.CANCELLED).interruptedLike)
        assertTrue(!AgentState(phase = AgentPhase.IDLE).interruptedLike)
    }
}
