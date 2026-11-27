package dev.forgeai.agent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * JVM regression suite for the conversation agent engine, including the exact v0.3.0
 * failure the user reported: `wc` running against a file that was still pending
 * approval, `verify` reported ok despite a failed verification, and the
 * forge-task-notes.md vs forge-task-notes.md.txt confusion.
 */

// ---- fakes ---------------------------------------------------------------------

private class FakeFs : FileSystemPort {
    val files = LinkedHashMap<String, ByteArray>()
    var writable = true
    var renameCreatesTo: String? = null       // provider renames created documents
    var failCreate: FsException? = null
    var failReplace: FsException? = null
    var corruptAfterWrite = false             // read-back differs from what was written
    val createdPaths = mutableListOf<String>()

    private fun ref(path: String) = FileRef("root", path, path.substringAfterLast('/'),
        false, files[path]?.size?.toLong() ?: 0, 42)

    override fun grants() = listOf(FolderGrant("root", "TestFolder", writable))
    override fun listTree(rootId: String) = files.keys.map { ref(it) }
    override fun entry(rootId: String, path: String): FileRef {
        if (!files.containsKey(path)) throw FsException("$path was not found.", FsException.Kind.NOT_FOUND)
        return ref(path)
    }
    override fun readBytes(ref: FileRef, maxBytes: Int): ByteArray {
        val bytes = files[ref.path] ?: throw FsException("${ref.path} was not found.", FsException.Kind.NOT_FOUND)
        if (corruptAfterWrite && createdPaths.contains(ref.path)) return "CORRUPTED-BY-PROVIDER".toByteArray()
        return bytes
    }
    override fun readText(ref: FileRef, maxBytes: Int) = String(readBytes(ref, maxBytes), Charsets.UTF_8)
    override fun createFile(rootId: String, dirPath: String, name: String, content: ByteArray,
                            mime: String): FileRef {
        failCreate?.let { throw it }
        val real = renameCreatesTo ?: name
        val path = if (dirPath.isEmpty()) real else "$dirPath/$real"
        files[path] = content
        createdPaths.add(path)
        return ref(path)
    }
    override fun replaceFile(ref: FileRef, newContent: ByteArray, expected: ByteArray?): ByteArray {
        failReplace?.let { throw it }
        val current = files[ref.path] ?: throw FsException("${ref.path} was not found.", FsException.Kind.NOT_FOUND)
        if (expected != null && !current.contentEquals(expected)) {
            throw FsException("concurrent change", FsException.Kind.CONFLICT)
        }
        files[ref.path] = newContent
        return ByteArray(0)
    }
}

private class FakeMirror : MirrorPort {
    val store = LinkedHashMap<String, ByteArray>()
    override fun open(taskKey: String) { /* keep contents; tests assert cumulative state */ }
    override fun syncIn(files: List<FileRef>, read: (FileRef) -> ByteArray) {
        files.forEach { runCatching { store[it.path] = read(it) } }
    }
    override fun put(path: String, bytes: ByteArray) { store[path] = bytes }
    override fun contains(path: String) = store.containsKey(path)
    override fun runnerDir() = "/fake/working-copy"
}

private class FakeRunner : CheckRunnerPort {
    data class Call(val command: String, val args: List<String>)
    val calls = mutableListOf<Call>()
    var nextExitCode = 0
    var nextStdout = "7 notes.md"
    override fun run(cwd: String?, command: String, args: List<String>, timeoutMs: Long): CheckRunnerPort.Outcome {
        calls.add(Call(command, args))
        return CheckRunnerPort.Outcome(true, null, nextExitCode, false, false, nextStdout, "", 4)
    }
    override fun cancel() {}
}

private class FakePython(override val available: Boolean = true) : PythonPort {
    val predefinedCalls = mutableListOf<String>()
    val scripts = mutableListOf<String>()
    var nextStdout = "columns: 2 ['name', 'score']"
    override fun runPredefined(tool: String, input: String): PythonPort.PyResult {
        predefinedCalls.add(tool)
        return PythonPort.PyResult(true, nextStdout, "", "", 3, null)
    }
    override fun runScript(code: String, input: String, deadlineSeconds: Int): PythonPort.PyResult {
        scripts.add(code)
        return PythonPort.PyResult(true, "script-output", "", "", 5, null)
    }
}

private class FakeModel(vararg replies: String?) : ModelPort {
    val prompts = mutableListOf<String>()
    private val queue = ArrayDeque(replies.toList())
    override fun generate(prompt: String, maxTokens: Int): String? {
        prompts.add(prompt)
        return if (queue.isEmpty()) null else queue.removeFirst()
    }
}

private class MemoryStore : CheckpointPort {
    val saved = mutableListOf<SavedTask>()
    override fun save(task: SavedTask) { saved.removeAll { it.id == task.id }; saved.add(task) }
    override fun list() = saved.toList()
    override fun delete(id: Long) { saved.removeAll { it.id == id } }
}

/** Deterministic test clock so ids stay small and unique. */
private class TestClock : () -> Long {
    private var t = 1000L
    override fun invoke(): Long = ++t
}

// ---- engine harness ------------------------------------------------------------

private class Harness(
    val fs: FakeFs = FakeFs(),
    val mirror: FakeMirror = FakeMirror(),
    val runner: FakeRunner = FakeRunner(),
    val py: FakePython = FakePython(),
    val model: FakeModel = FakeModel(),
    val store: MemoryStore = MemoryStore(),
) {
    val clock = TestClock()
    val engine = AgentEngine(fs, mirror, runner, py, model, store, clock)

    fun start(objective: String, mode: AgentMode = AgentMode.REVIEW, useModel: Boolean = false) {
        engine.start(EngineConfig(objective = objective, mode = mode, rootId = "root", useModel = useModel,
            timeBudgetMs = 60_000))
    }

    fun await(vararg phases: RunPhase, timeoutMs: Long = 5000): EngineState {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (engine.state.value.phase in phases) return engine.state.value
            Thread.sleep(10)
        }
        throw AssertionError("timeout waiting for ${phases.joinToString()}; now ${engine.state.value.phase}")
    }

    fun shutdownAndJoin() {
        engine.shutdown()
        // worker threads finish quickly; give them a moment so logs stay quiet
        Thread.sleep(50)
    }
}

// ---- tests ---------------------------------------------------------------------

class AgentEngineTest {

    private lateinit var h: Harness

    @Before fun setUp() { h = Harness() }
    @After fun tearDown() { h.shutdownAndJoin() }

    /**
     * v0.3.0 bug 1: run_check executed while the write was still pending approval,
     * so wc failed with "No such file or directory". Now the check must be reported
     * as AWAITING_WRITE and must NOT run.
     */
    @Test fun reviewModeDefersDependentCheckUntilApproval() {
        h.fs.files["README.md"] = "# readme\n".toByteArray()
        h.start("create notes and update the readme")

        val s = h.await(RunPhase.AWAITING_APPROVAL)
        val check = s.items.filterIsInstance<Tl.Check>().single()
        assertEquals(CheckOutcome.AWAITING_WRITE, check.outcome)
        assertTrue(h.runner.calls.isEmpty())                       // nothing ran against a nonexistent file
        assertTrue(check.reason.contains("not on disk yet"))
        val write = s.writes.single { it.path == "forge-task-notes.md" }
        assertEquals(WriteOutcome.PROPOSED, write.outcome)

        h.engine.approve(write.writeId)
        val edit = h.await(RunPhase.AWAITING_APPROVAL).writes.first { it.kind == TrackedWrite.Kind.REPLACE }
        h.engine.approve(edit.writeId)
        val done = h.await(RunPhase.DONE)
        val checkAfter = done.items.filterIsInstance<Tl.Check>().single()
        assertEquals(CheckOutcome.PASSED, checkAfter.outcome)
        assertEquals(0, checkAfter.exitCode)
        assertEquals(listOf("-l", "forge-task-notes.md"), h.runner.calls.single().args)
        assertTrue(h.fs.files.containsKey("forge-task-notes.md"))  // real file, real content
        assertTrue(h.mirror.store.containsKey("forge-task-notes.md"))
        val final = done.items.filterIsInstance<Tl.FinalReport>().single()
        assertTrue(final.text.contains("verified by re-read"))
    }

    /**
     * v0.3.0 bug 2/3: the provider renamed the created document (".md" + text/plain
     * became "forge-task-notes.md.txt") but the controller kept tracking the planned
     * name, so verification failed with WriteException while the step still said ok.
     * Now the real name is the source of truth everywhere.
     */
    @Test fun providerRenameIsTrackedAndCheckUsesTheRealName() {
        h.fs.files["README.md"] = "# readme\n".toByteArray()
        h.fs.renameCreatesTo = "forge-task-notes.md.txt"           // what the provider did to the user
        h.start("create notes and update the readme")

        val s = h.await(RunPhase.AWAITING_APPROVAL)
        val write = s.writes.single { it.path == "forge-task-notes.md" }
        h.engine.approve(write.writeId)
        val edit = h.await(RunPhase.AWAITING_APPROVAL).writes.first { it.kind == TrackedWrite.Kind.REPLACE }
        h.engine.approve(edit.writeId)

        val done = h.await(RunPhase.DONE)
        val w = done.writes.single { it.path == "forge-task-notes.md" }
        assertEquals(WriteOutcome.VERIFIED, w.outcome)
        assertEquals("forge-task-notes.md.txt", w.providerPath)
        assertTrue(h.fs.files.containsKey("forge-task-notes.md.txt"))
        assertTrue(h.mirror.store.containsKey("forge-task-notes.md.txt"))  // working copy uses the real name
        assertEquals(listOf("-l", "forge-task-notes.md.txt"), h.runner.calls.single().args)
        // the rename is surfaced to the user, not hidden
        assertTrue(done.items.filterIsInstance<Tl.Info>().any { it.text.contains("saved the document as 'forge-task-notes.md.txt'") })
        val final = done.items.filterIsInstance<Tl.FinalReport>().single()
        assertTrue(final.text.contains("forge-task-notes.md.txt"))
    }

    /** v0.3.0 regression: a second task in a folder holding the renamed artifact must not
     *  pick "forge-task-notes.md.txt" as its edit target nor plan another ".md" creation. */
    @Test fun secondTaskDoesNotConfuseRenamedArtifact() {
        h.fs.files["README.md"] = "# readme\n".toByteArray()
        h.fs.files["forge-task-notes.md.txt"] = "old\n".toByteArray()
        h.start("create notes and update the readme")

        val done = h.await(RunPhase.AWAITING_APPROVAL, RunPhase.DONE)
        val create = done.writes.first { it.kind == TrackedWrite.Kind.CREATE }
        assertNotEquals("forge-task-notes.md", create.path)        // stem collision avoided
        assertTrue(create.path.startsWith("forge-task-notes-"))
        // the edit targets README, never the stale artifact
        val edit = done.writes.first { it.kind == TrackedWrite.Kind.REPLACE }
        assertEquals("README.md", edit.path)
    }

    /** Refusal must not write anything and must skip the dependent check with a reason. */
    @Test fun refusalSkipsDependentCheckAndWritesNothing() {
        h.fs.files["README.md"] = "# readme\n".toByteArray()
        h.start("create notes and update the readme")

        val s = h.await(RunPhase.AWAITING_APPROVAL)
        val create = s.writes.single { it.kind == TrackedWrite.Kind.CREATE }
        val edit = s.writes.single { it.kind == TrackedWrite.Kind.REPLACE }
        h.engine.refuse(create.writeId)
        h.engine.refuse(edit.writeId)

        val done = h.await(RunPhase.DONE)
        assertEquals(WriteOutcome.REFUSED, done.writes.first { it.writeId == create.writeId }.outcome)
        assertFalse(h.fs.files.containsKey("forge-task-notes.md"))
        assertFalse(h.fs.createdPaths.isNotEmpty())
        val check = done.items.filterIsInstance<Tl.Check>().single()
        assertEquals(CheckOutcome.SKIPPED, check.outcome)
        assertTrue(check.reason.contains("Skipped"))
        assertTrue(h.runner.calls.isEmpty())
        val final = done.items.filterIsInstance<Tl.FinalReport>().single()
        assertTrue(final.text.contains("Refused by you"))
        assertFalse(final.text.contains("verified by re-read"))
    }

    /** A failed save must block the dependent check; the task may not claim success. */
    @Test fun saveFailureBlocksDependentCheckWithConcreteReason() {
        h.fs.files["README.md"] = "# readme\n".toByteArray()
        h.fs.failCreate = FsException("no write grant", FsException.Kind.NO_WRITE_GRANT)
        h.start("create notes and update the readme", mode = AgentMode.AUTO)   // auto applies immediately

        val done = h.await(RunPhase.DONE)
        val w = done.writes.single { it.path == "forge-task-notes.md" }
        assertEquals(WriteOutcome.SAVE_FAILED, w.outcome)
        assertNotNull(w.error)
        val check = done.items.filterIsInstance<Tl.Check>().single()
        assertEquals(CheckOutcome.SKIPPED, check.outcome)
        assertTrue(check.reason.contains("which was not saved"))
        assertTrue(h.runner.calls.isEmpty())
        val final = done.items.filterIsInstance<Tl.FinalReport>().single()
        assertTrue(final.text.contains("Failed to save"))
        assertTrue(final.text.contains("problems"))
    }

    /** A verification mismatch (re-read differs) is reported as VERIFY_FAILED, never ok. */
    @Test fun verifyFailureIsReportedHonestly() {
        h.fs.files["README.md"] = "# readme\n".toByteArray()
        h.fs.corruptAfterWrite = true
        h.start("create notes and update the readme", mode = AgentMode.AUTO)

        val done = h.await(RunPhase.DONE)
        val w = done.writes.single { it.path == "forge-task-notes.md" }
        assertEquals(WriteOutcome.VERIFY_FAILED, w.outcome)
        val check = done.items.filterIsInstance<Tl.Check>().single()
        assertEquals(CheckOutcome.SKIPPED, check.outcome)
        val final = done.items.filterIsInstance<Tl.FinalReport>().single()
        assertTrue(final.text.contains("Verification failed"))
        assertTrue(final.text.contains("NOT confirmed"))
    }

    /** Auto mode applies writes immediately, so a following check runs against the real file. */
    @Test fun autoModeAppliesImmediatelyAndCheckRunsAfterwards() {
        h.fs.files["README.md"] = "# readme\n".toByteArray()
        h.start("create notes and update the readme", mode = AgentMode.AUTO)

        val done = h.await(RunPhase.DONE)
        assertEquals(WriteOutcome.VERIFIED, done.writes.single { it.path == "forge-task-notes.md" }.outcome)
        val check = done.items.filterIsInstance<Tl.Check>().single()
        assertEquals(CheckOutcome.PASSED, check.outcome)
        assertEquals(1, h.runner.calls.size)
        val edit = done.writes.single { it.kind == TrackedWrite.Kind.REPLACE }
        assertEquals(WriteOutcome.VERIFIED, edit.outcome)
        val readme = String(h.fs.files["README.md"]!!, Charsets.UTF_8)
        assertTrue(readme.contains("<!-- edited by FORGE AI agent"))
    }

    /** The model drives the conversation: real tool calls in, real results back, final text. */
    @Test fun modelDrivenConversationUsesToolsAndAnswers() {
        h.fs.files["README.md"] = "# readme\n".toByteArray()
        val scripted = FakeModel(
            """{"tool":"read_file","args":{"path":"README.md"}}""",
            """{"tool":"write_file","args":{"path":"notes.md","content":"# hi\\nline2\\n"}}""",
            """{"tool":"run_check","args":{"command":"wc","args":["-l","notes.md"]}}""",
            "All done: I created notes.md and verified it with wc.",
        )
        val engine = AgentEngine(h.fs, h.mirror, h.runner, h.py, scripted, h.store, h.clock)
        engine.start(EngineConfig(objective = "read the readme, create notes.md and check it",
            mode = AgentMode.REVIEW, rootId = "root", useModel = true, timeBudgetMs = 60_000))
        val awaiting = awaitOf(engine, RunPhase.AWAITING_APPROVAL)
        assertEquals(WriteOutcome.PROPOSED, awaiting.writes.single { it.path == "notes.md" }.outcome)
        engine.approve(awaiting.writes.single { it.path == "notes.md" }.writeId)
        val done = awaitOf(engine, RunPhase.DONE)
        assertEquals(WriteOutcome.VERIFIED, done.writes.single { it.path == "notes.md" }.outcome)
        val check = done.items.filterIsInstance<Tl.Check>().single()
        assertEquals(CheckOutcome.PASSED, check.outcome)
        assertEquals(listOf("-l", "notes.md"), h.runner.calls.single().args)
        val agentText = done.items.filterIsInstance<Tl.AgentText>().single()
        assertTrue(agentText.text.contains("notes.md"))
        // the model saw the real tool results in its follow-up prompts
        assertTrue(scripted.prompts.size >= 4)
        assertTrue(scripted.prompts[1].contains("read_file"))
        engine.shutdown()
    }

    /** python_tool results reach the model and the timeline (same conversation). */
    @Test fun pythonToolResultReachesModelAndTimeline() {
        h.fs.files["data.csv"] = "name,score\na,1\nb,2\n".toByteArray()
        val scripted = FakeModel(
            """{"tool":"python_tool","args":{"tool":"csv_summary","path":"data.csv"}}""",
            "The CSV has 2 columns and 2 data rows.",
        )
        val engine = AgentEngine(h.fs, h.mirror, h.runner, h.py, scripted, h.store, h.clock)
        engine.start(EngineConfig(objective = "summarize data.csv", mode = AgentMode.REVIEW,
            rootId = "root", useModel = true, timeBudgetMs = 60_000))
        val done = awaitOf(engine, RunPhase.DONE)
        assertEquals(listOf("csv_summary"), h.py.predefinedCalls)
        val action = done.items.filterIsInstance<Tl.Action>().first { it.tool.startsWith("python_tool") }
        assertEquals(ActionOutcome.OK, action.outcome)
        assertTrue(action.detail.contains("columns"))
        // the result was fed back to the model in the next prompt
        assertTrue(scripted.prompts.last().contains("csv_summary") || scripted.prompts.last().contains("columns"))
        val agentText = done.items.filterIsInstance<Tl.AgentText>().single()
        assertTrue(agentText.text.contains("2 columns"))
        engine.shutdown()
    }

    /** python_script always requires approval, even in auto mode. */
    @Test fun pythonScriptRequiresApprovalEvenInAutoMode() {
        h.fs.files["data.csv"] = "a,b\n1,2\n".toByteArray()
        val scripted = FakeModel(
            """{"tool":"python_script","args":{"code":"print(1+1)"}}""",
            "The script printed 2.",
        )
        val engine = AgentEngine(h.fs, h.mirror, h.runner, h.py, scripted, h.store, h.clock)
        engine.start(EngineConfig(objective = "run my computation", mode = AgentMode.AUTO,
            rootId = "root", useModel = true, timeBudgetMs = 60_000))
        val awaiting = awaitOf(engine, RunPhase.AWAITING_APPROVAL)
        val approval = awaiting.items.filterIsInstance<Tl.Approval>().single()
        assertEquals("print(1+1)", approval.script)
        engine.approve(approval.writeId)
        val done = awaitOf(engine, RunPhase.DONE)
        assertEquals(listOf("print(1+1)"), h.py.scripts)
        val action = done.items.filterIsInstance<Tl.Action>().first { it.tool == "python_script" && it.detail.contains("script-output") }
        assertEquals(ActionOutcome.OK, action.outcome)
        engine.shutdown()
    }

    /** Reading a file whose creation is pending approval is honest: blocked, not faked. */
    @Test fun readOfPendingCreateIsBlockedNotFaked() {
        val scripted = FakeModel(
            """{"tool":"write_file","args":{"path":"new.md","content":"hello"}}""",
            """{"tool":"read_file","args":{"path":"new.md"}}""",
            "done",
        )
        val engine = AgentEngine(h.fs, h.mirror, h.runner, h.py, scripted, h.store, h.clock)
        engine.start(EngineConfig(objective = "make new.md", mode = AgentMode.REVIEW,
            rootId = "root", useModel = true, timeBudgetMs = 60_000))
        val awaiting = awaitOf(engine, RunPhase.AWAITING_APPROVAL)
        engine.approve(awaiting.writes.single().writeId)
        val done = awaitOf(engine, RunPhase.DONE)
        // after approval the file exists for real, so the read is real
        val read = done.items.filterIsInstance<Tl.Action>().first { it.tool == "read_file" }
        assertEquals(ActionOutcome.OK, read.outcome)
        assertTrue(read.detail.contains("hello"))
        engine.shutdown()
    }

    /** Checkpoints round-trip the full timeline and write state. */
    @Test fun checkpointRoundTripPreservesState() {
        h.fs.files["README.md"] = "# readme\n".toByteArray()
        h.start("create notes and update the readme")
        val s = h.await(RunPhase.AWAITING_APPROVAL)
        val saved = h.store.saved.last()
        val restored = EngineSerde.read(EngineSerde.write(saved))
        assertNotNull(restored)
        assertEquals(saved.state.phase, restored!!.state.phase)
        assertEquals(saved.state.writes.size, restored.state.writes.size)
        assertEquals(saved.state.writes.map { it.path }, restored.state.writes.map { it.path })
        assertEquals(saved.state.writes.map { it.taskId }, restored.state.writes.map { it.taskId })
        assertEquals(saved.state.items.size, restored.state.items.size)
        assertTrue(restored.state.interruptedLike || restored.state.awaitingApproval)
    }

    /** Cancel marks pending writes CANCELLED and waiting checks CANCELLED — never done. */
    @Test fun cancelMarksPendingAsCancelled() {
        h.fs.files["README.md"] = "# readme\n".toByteArray()
        h.start("create notes and update the readme")
        h.await(RunPhase.AWAITING_APPROVAL)
        h.engine.cancel()
        val done = h.await(RunPhase.CANCELLED)
        assertTrue(done.writes.all { it.outcome == WriteOutcome.CANCELLED || it.outcome == WriteOutcome.REFUSED })
        val check = done.items.filterIsInstance<Tl.Check>().single()
        assertEquals(CheckOutcome.CANCELLED, check.outcome)
        assertFalse(h.fs.createdPaths.isNotEmpty())
    }

    /** A second request in the same chat continues the conversation and keeps context. */
    @Test fun secondTaskInSameChatKeepsConversationAndDependencyContext() {
        h.fs.files["README.md"] = "# readme\n".toByteArray()
        h.start("create notes and update the readme")
        val s = h.await(RunPhase.AWAITING_APPROVAL)
        h.engine.approve(s.writes.first { it.kind == TrackedWrite.Kind.CREATE }.writeId)
        h.await(RunPhase.AWAITING_APPROVAL) // second pending write (README edit)
        val s2 = h.engine.state.value
        h.engine.approve(s2.writes.first { it.kind == TrackedWrite.Kind.REPLACE }.writeId)
        val first = h.await(RunPhase.DONE)
        val firstItemCount = first.items.size

        // second request: verify the file created in the first task — same conversation,
        // no new write, the check reuses the real stored name.
        h.start("check forge-task-notes.md with wc")
        val second = h.await(RunPhase.DONE, RunPhase.AWAITING_APPROVAL)
        assertTrue(second.items.size > firstItemCount)           // conversation continued
        assertTrue(second.items.any { it is Tl.Info && it.text.contains("new task") })
        val check = second.items.filterIsInstance<Tl.Check>().last()
        assertEquals(CheckOutcome.PASSED, check.outcome)
        assertEquals(listOf("-l", "forge-task-notes.md"), check.args)
        // no additional file was created by the second request
        assertEquals(listOf("forge-task-notes.md"), h.fs.createdPaths)
    }

    private fun awaitOf(engine: AgentEngine, vararg phases: RunPhase, timeoutMs: Long = 5000): EngineState {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (engine.state.value.phase in phases) return engine.state.value
            Thread.sleep(10)
        }
        throw AssertionError("timeout waiting for ${phases.joinToString()}; now ${engine.state.value.phase}")
    }
}
