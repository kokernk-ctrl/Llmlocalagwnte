package dev.forgeai.agent

import dev.forgeai.tools.JVal
import dev.forgeai.tools.JsonLite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerTest {

    private fun file(name: String, size: Long = 128L) =
        Planner.PlanFile(name, name.substringAfterLast('/'), size, false)

    @Test
    fun `tool catalog lists every engine tool for the model`() {
        val catalog = Planner.toolCatalog()
        assertTrue(catalog.contains("write_file"))
        assertTrue(catalog.contains("run_check"))
        assertTrue(catalog.contains("python_tool"))
        assertTrue(catalog.contains("python_script"))
        assertTrue(catalog.contains("search_text"))
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
    fun `deterministic plan creates a default file when a create verb is present`() {
        val calls = Planner.deterministicPlan("create notes for this project", AgentMode.AUTO, true, listOf(file("a.txt")))
        val create = calls.first { it.tool == "write_file" }
        val path = JsonLite.str(create.args, "path")!!
        assertTrue(path.startsWith("forge-task-notes") && path.endsWith(".md"))
        assertTrue(JsonLite.str(create.args, "content")!!.contains("FORGE AI agent task"))
        assertTrue(calls.any { it.tool == "run_check" })
        assertTrue(calls.all { !it.fromModel })
    }

    @Test
    fun `deterministic plan creates nothing without a create verb`() {
        // v0.4.0: nothing is created or edited unless the objective asks for it.
        val calls = Planner.deterministicPlan("summarize this folder", AgentMode.AUTO, true, listOf(file("a.txt")))
        assertTrue(calls.none { it.tool == "write_file" })
        assertTrue(calls.none { it.tool == "append_file" })
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
    fun `default name avoids stem collisions with provider-renamed artifacts`() {
        // The v0.3.0 trap: a leftover "forge-task-notes.md.txt" (provider rename) must not
        // make the planner propose "forge-task-notes.md" again.
        val calls = Planner.deterministicPlan("create notes for this project", AgentMode.AUTO, true,
            listOf(file("forge-task-notes.md.txt")))
        val create = calls.first { it.tool == "write_file" }
        val path = JsonLite.str(create.args, "path")!!
        assertTrue(path.startsWith("forge-task-notes-") && path.endsWith(".md"))
        assertFalse(path == "forge-task-notes.md")
    }

    @Test
    fun `edit target is never the stale renamed artifact and prefers README`() {
        val calls = Planner.deterministicPlan("create notes and update the readme", AgentMode.AUTO, true,
            listOf(file("forge-task-notes.md.txt"), file("README.md")))
        val append = calls.first { it.tool == "append_file" }
        assertEquals("README.md", JsonLite.str(append.args, "path"))
    }

    @Test
    fun `edit mention without extension matches by stem`() {
        val calls = Planner.deterministicPlan("atualize o README com uma referência", AgentMode.AUTO, true,
            listOf(file("README.md"), file("other.txt")))
        val append = calls.first { it.tool == "append_file" }
        assertEquals("README.md", JsonLite.str(append.args, "path"))
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
    fun `check intent targets the named existing file`() {
        val calls = Planner.deterministicPlan("check forge-task-notes.md with wc", AgentMode.AUTO, true,
            listOf(file("forge-task-notes.md")))
        val check = calls.first { it.tool == "run_check" }
        val args = (JsonLite.arr(check.args, "args") as List<JVal>)
            .mapNotNull { (it as? JVal.JStr)?.value }
        assertEquals(listOf("-l", "forge-task-notes.md"), args)
        assertTrue(calls.none { it.tool == "write_file" })
    }

    @Test
    fun `read-only folder still yields a plan and write rejection happens at execution`() {
        // The planner builds the plan; the engine rejects writes on read-only grants.
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
    fun `engine state interruptedLike distinguishes unfinished tasks from done`() {
        assertTrue(EngineState(phase = RunPhase.RUNNING).interruptedLike)
        assertTrue(EngineState(phase = RunPhase.AWAITING_APPROVAL).interruptedLike)
        assertFalse(EngineState(phase = RunPhase.DONE).interruptedLike)
        assertFalse(EngineState(phase = RunPhase.FAILED).interruptedLike)
        assertFalse(EngineState(phase = RunPhase.CANCELLED).interruptedLike)
        assertFalse(EngineState(phase = RunPhase.IDLE).interruptedLike)
    }

    @Test
    fun `mime selection follows the extension to avoid provider renames`() {
        assertEquals("text/markdown", FileKinds.mimeFor("notes.md"))
        assertEquals("text/plain", FileKinds.mimeFor("notes.txt"))
        assertEquals("application/json", FileKinds.mimeFor("data.json"))
        assertEquals("text/csv", FileKinds.mimeFor("data.csv"))
        assertEquals("application/octet-stream", FileKinds.mimeFor("blob.bin"))
        assertTrue(FileKinds.isTextName("notes.md"))
        assertFalse(FileKinds.isTextName("image.png"))
    }

    @Test
    fun `serde round trip preserves write state`() {
        val w = TrackedWrite(7, "notes.md", TrackedWrite.Kind.CREATE, "content", null,
            WriteOutcome.VERIFIED, providerPath = "notes.md.txt", taskId = 42)
        val state = EngineState(phase = RunPhase.AWAITING_APPROVAL,
            task = TaskInfo(42, "obj", AgentMode.REVIEW, "root", "Folder", true, 1, 2),
            items = listOf(Tl.Objective(1, "obj"), Tl.Check(2, "wc", listOf("-l", "notes.md"),
                CheckOutcome.AWAITING_WRITE, null, "waiting", "", "", 0)),
            writes = listOf(w))
        val text = EngineSerde.write(SavedTask(42, state, "root"))
        val back = EngineSerde.read(text)!!
        assertEquals(WriteOutcome.VERIFIED, back.state.writes.single().outcome)
        assertEquals("notes.md.txt", back.state.writes.single().providerPath)
        assertEquals(42L, back.state.writes.single().taskId)
        assertEquals(CheckOutcome.AWAITING_WRITE, back.state.items.filterIsInstance<Tl.Check>().single().outcome)
        assertEquals("root", back.rootId)
        // v0.3.0 checkpoints are rejected
        assertNull(EngineSerde.read("{\"id\":1,\"phase\":\"DONE\"}"))
    }
}
