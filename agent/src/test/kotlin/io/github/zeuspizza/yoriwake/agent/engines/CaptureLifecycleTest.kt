package io.github.zeuspizza.yoriwake.agent.engines

import io.github.zeuspizza.yoriwake.agent.capture.AgentLookup
import io.github.zeuspizza.yoriwake.agent.capture.CaptureAccess
import io.github.zeuspizza.yoriwake.agent.capture.CaptureClaim
import io.github.zeuspizza.yoriwake.agent.capture.JacocoAgent
import io.github.zeuspizza.yoriwake.agent.capture.ProbeReporter
import io.github.zeuspizza.yoriwake.agent.capture.ProbeResult
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.launcher.TestIdentifier
import java.io.File
import java.util.function.Supplier
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Drives the listener's callbacks directly to assert the ordering of reset and dump around each
 * test, which attribution depends on.
 */
class CaptureLifecycleTest {

    // Several cases open a plan and never finish it, which would leave the JVM-wide capture claim
    // held for the next case.
    @AfterEach
    fun releaseTheCaptureClaim() {
        System.clearProperty(CaptureClaim.PROPERTY)
    }

    /** Records every call so the reset/dump ordering can be asserted, not just its results. */
    private class ScriptedAgent(private val blobs: MutableList<ByteArray> = mutableListOf()) : JacocoAgent {
        val takes = mutableListOf<String>()
        var failAfter = Int.MAX_VALUE
        var label = "start"

        override fun version() = "test"

        override fun takeExecutionData(): ByteArray {
            takes += label
            if (takes.size > failAfter) throw IllegalStateException("agent died")
            return if (blobs.isEmpty()) ByteArray(0) else blobs.removeAt(0)
        }
    }

    private class Sink : ProbeReporter {
        val results = mutableListOf<ProbeResult>()
        override fun report(result: ProbeResult) {
            results += result
        }
    }

    private fun identifier(name: String, isTest: Boolean = true): TestIdentifier =
        if (isTest) TestIdentifiers.leaf(name) else TestIdentifiers.container(name)

    private fun listener(agent: JacocoAgent, dir: File?, sink: Sink) =
        PlatformEvents(Supplier { AgentLookup.Result.found(agent) }, sink, dir)

    private fun runTest(l: PlatformEvents, id: TestIdentifier) {
        l.executionStarted(id)
        l.executionFinished(id, TestExecutionResult.successful())
    }

    @Test
    fun `takes execution data once before and once after each test`(@TempDir dir: File) {
        val agent = ScriptedAgent()
        val l = listener(agent, dir, Sink())
        l.testPlanExecutionStarted(null)
        agent.takes.clear() // the plan-start capture of pre-test coverage already happened

        runTest(l, identifier("a"))

        // Two per test: the take that closes the preceding unattributed window, and the one that
        // closes the test's own window.
        assertEquals(2, agent.takes.size)

        l.testPlanExecutionFinished(null)
        // Plus one more closing the window after the last test.
        assertEquals(3, agent.takes.size)
    }

    @Test
    fun `writes one record per test`(@TempDir dir: File) {
        val agent = ScriptedAgent()
        val sink = Sink()
        val l = listener(agent, dir, sink)
        l.testPlanExecutionStarted(null)

        runTest(l, identifier("a"))
        runTest(l, identifier("b"))
        runTest(l, identifier("c"))
        l.testPlanExecutionFinished(null)

        val capture = sink.results.last { it.isCapture }
        // ScriptedAgent returns empty blobs, and empty unattributed windows are not recorded, so
        // only the three real tests produce records.
        assertEquals(3, capture.records())
        assertEquals(0, capture.failures())
    }

    @Test
    fun `ignores containers so only leaf tests are attributed`(@TempDir dir: File) {
        val agent = ScriptedAgent()
        val sink = Sink()
        val l = listener(agent, dir, sink)
        l.testPlanExecutionStarted(null)

        val container = identifier("SomeTest", isTest = false)
        l.executionStarted(container)
        runTest(l, identifier("a"))
        l.executionFinished(container, TestExecutionResult.successful())
        l.testPlanExecutionFinished(null)

        assertEquals(1, sink.results.last { it.isCapture }.records())
    }

    @Test
    fun `a skipped test is still recorded so it stays selectable`(@TempDir dir: File) {
        // A test absent from the map can never be selected, so omitting a disabled test would
        // make it permanently unselectable once re-enabled.
        val agent = ScriptedAgent()
        val sink = Sink()
        val l = listener(agent, dir, sink)
        l.testPlanExecutionStarted(null)

        val skipped = identifier("skipped")
        l.executionSkipped(skipped, "disabled")
        l.testPlanExecutionFinished(null)

        assertEquals(1, sink.results.last { it.isCapture }.records())

        val row = File(dir.listFiles()!!.first(), "index.tsv").readLines().single().split('	')
        assertEquals(AgentContract.OUTCOME_SKIPPED, row[3])
        assertEquals(skipped.uniqueId, row[4])
        assertEquals(0, row[2].toInt(), "a skipped test executed nothing, so its blob is empty")
    }

    @Test
    fun `a class container that fails records every test beneath it as failed`(@TempDir dir: File) {
        // A throwing @BeforeAll reports none of its class's tests, and a throwing @AfterAll comes
        // after they passed. Either way the class fails, and a test left with no record, or a
        // SUCCESSFUL one, keeps whatever the map says about it and can be skipped next time.
        val (plan, container, tests) = TestIdentifiers.plan("FailingSpec", "a", "b")
        val l = listener(ScriptedAgent(), dir, Sink())
        l.testPlanExecutionStarted(plan)

        l.executionStarted(container)
        runTest(l, tests[0])
        l.executionFinished(container, TestExecutionResult.failed(IllegalStateException("setup")))
        l.testPlanExecutionFinished(plan)

        val rows = File(dir.listFiles()!!.single(), "index.tsv").readLines().map { it.split('\t') }
        for (test in tests) {
            assertTrue(
                rows.any { it[4] == test.uniqueId && it[3] == "FAILED" },
                "${test.uniqueId} is not recorded as failed: $rows",
            )
        }
        assertFalse(rows.any { it[4] == container.uniqueId }, "the class itself is not a test: $rows")
    }

    @Test
    fun `a test template that fails before any invocation records itself as failed`(@TempDir dir: File) {
        // A parameterized test whose argument source throws, or a test factory that throws, fails
        // as a container with no test beneath it. Recorded as nothing, its invocations' older
        // SUCCESSFUL records are all the map knows about it, and it can be skipped next time.
        val (plan, container, templates) = TestIdentifiers.templatePlan("TemplateSpec", "cases(int)")
        val l = listener(ScriptedAgent(), dir, Sink())
        l.testPlanExecutionStarted(plan)

        l.executionStarted(container)
        l.executionStarted(templates[0])
        l.executionFinished(templates[0], TestExecutionResult.failed(IllegalStateException("no arguments")))
        l.executionFinished(container, TestExecutionResult.successful())
        l.testPlanExecutionFinished(plan)

        val rows = File(dir.listFiles()!!.single(), "index.tsv").readLines().map { it.split('\t') }
        assertTrue(
            rows.any { it[4] == templates[0].uniqueId && it[3] == "FAILED" },
            "${templates[0].uniqueId} is not recorded as failed: $rows",
        )
    }

    @Test
    fun `a class of test templates whose setup fails records each template as failed`(@TempDir dir: File) {
        // A throwing @BeforeAll reaches no template, so none registers an invocation: the only ids
        // beneath the class that can carry the failure are the templates' own.
        val (plan, container, templates) = TestIdentifiers.templatePlan("TemplateSpec", "a(int)", "b(int)")
        val l = listener(ScriptedAgent(), dir, Sink())
        l.testPlanExecutionStarted(plan)

        l.executionStarted(container)
        l.executionFinished(container, TestExecutionResult.failed(IllegalStateException("setup")))
        l.testPlanExecutionFinished(plan)

        val rows = File(dir.listFiles()!!.single(), "index.tsv").readLines().map { it.split('\t') }
        for (template in templates) {
            assertTrue(
                rows.any { it[4] == template.uniqueId && it[3] == "FAILED" },
                "${template.uniqueId} is not recorded as failed: $rows",
            )
        }
        assertFalse(rows.any { it[4] == container.uniqueId }, "the class has templates to carry it: $rows")
    }

    @Test
    fun `a skipped container is not recorded`(@TempDir dir: File) {
        val agent = ScriptedAgent()
        val sink = Sink()
        val l = listener(agent, dir, sink)
        l.testPlanExecutionStarted(null)

        l.executionSkipped(identifier("SomeSpec", isTest = false), "disabled")
        l.testPlanExecutionFinished(null)

        assertEquals(0, sink.results.last { it.isCapture }.records())
    }

    @Test
    fun `capture stays off when no output directory is configured`(@TempDir dir: File) {
        // Baseline timing runs keep the jar on the classpath but must do no per-test work, so that
        // "listener present" and "listener capturing" can be timed separately.
        val agent = ScriptedAgent()
        val sink = Sink()
        val l = listener(agent, null, sink)
        l.testPlanExecutionStarted(null)
        agent.takes.clear()

        runTest(l, identifier("a"))

        assertTrue(agent.takes.isEmpty(), "no execution data should be taken when capture is off")
        assertFalse(dir.listFiles().orEmpty().any { it.name.startsWith("worker-") })
    }

    @Test
    fun `an agent failure mid-run is counted and does not abort the suite`(@TempDir dir: File) {
        val agent = ScriptedAgent().apply { failAfter = 2 }
        val sink = Sink()
        val l = listener(agent, dir, sink)
        l.testPlanExecutionStarted(null)

        runTest(l, identifier("a"))
        runTest(l, identifier("b"))
        l.testPlanExecutionFinished(null)

        val capture = sink.results.last { it.isCapture }
        assertTrue(capture.failures() > 0, "failures must be surfaced; a partial map cannot be trusted")
    }

    private fun completionMarker(dir: File) =
        File(dir.listFiles()!!.single { it.name.startsWith("worker-") }, AgentContract.PLAN_COMPLETE_FILE)

    @Test
    fun `a plan that runs to its end marks its worker complete`(@TempDir dir: File) {
        val l = listener(ScriptedAgent(), dir, Sink())
        l.testPlanExecutionStarted(null)
        runTest(l, identifier("a"))
        l.testPlanExecutionFinished(null)

        assertTrue(completionMarker(dir).isFile, "a finished plan wrote no completion marker")
    }

    @Test
    fun `a plan abandoned mid-run leaves no completion marker`(@TempDir dir: File) {
        // What a test calling System.exit, an OOM kill or a crash looks like from here: records
        // flushed, and the plan's end never reached. Its map must not be dated.
        val l = listener(ScriptedAgent(), dir, Sink())
        l.testPlanExecutionStarted(null)
        runTest(l, identifier("a"))
        l.executionStarted(identifier("b"))

        assertFalse(completionMarker(dir).exists(), "an abandoned plan was marked complete")
    }

    @Test
    fun `a plan that finished with capture failures is not marked complete`(@TempDir dir: File) {
        // A record it failed to write leaves the older one standing in the merge, looking current.
        val l = listener(ScriptedAgent().apply { failAfter = 2 }, dir, Sink())
        l.testPlanExecutionStarted(null)
        runTest(l, identifier("a"))
        runTest(l, identifier("b"))
        l.testPlanExecutionFinished(null)

        assertFalse(completionMarker(dir).exists(), "a plan with capture failures was marked complete")
    }

    @Test
    fun `capture does not start when the agent was never reachable`(@TempDir dir: File) {
        val sink = Sink()
        val unreachable = Supplier {
            AgentLookup.Result.missing(
                CaptureAccess.notOnClasspath(ClassNotFoundException("RT"))
            )
        }
        val l = PlatformEvents(unreachable, sink, dir)

        l.testPlanExecutionStarted(null)
        runTest(l, identifier("a"))
        l.testPlanExecutionFinished(null)

        assertFalse(sink.results.any { it.isCapture }, "no capture summary without an agent")
        assertFalse(dir.listFiles().orEmpty().any { it.name.startsWith("worker-") })
    }

    @Test
    fun `a nested Launcher's listener leaves the outer test's window and records alone`(@TempDir dir: File) {
        // An in-process Launcher gets a second listener; its takes are JVM-global resets that
        // would wipe the outer test's coverage, and its writer would reopen the same index.
        val agent = ScriptedAgent()
        val outer = listener(agent, dir, Sink())
        outer.testPlanExecutionStarted(null)
        val test = identifier("outer")
        outer.executionStarted(test)
        agent.takes.clear()

        val nestedSink = Sink()
        val nested = listener(agent, dir, nestedSink)
        nested.testPlanExecutionStarted(null)
        runTest(nested, identifier("inner"))
        nested.testPlanExecutionFinished(null)

        assertTrue(agent.takes.isEmpty(), "the nested listener reset the outer test's coverage: ${agent.takes}")
        assertTrue(nestedSink.results.isEmpty(), "the nested listener reported: ${nestedSink.results}")

        outer.executionFinished(test, TestExecutionResult.successful())
        outer.testPlanExecutionFinished(null)
        val ids = File(dir.listFiles()!!.single(), "index.tsv").readLines().map { it.split('\t')[4] }
        assertTrue(test.uniqueId in ids, "the outer test lost its record: $ids")
        assertFalse(ids.any { it.contains("inner") }, "the nested test was recorded: $ids")

        // Released at the end of the owner's plan, so a later plan in the same JVM captures again.
        assertEquals(null, System.getProperty(CaptureClaim.PROPERTY))
    }

    @Test
    fun `a record directory that cannot be opened takes nothing and throws nothing`(@TempDir dir: File) {
        // Every take is a JVM-global reset, so with nowhere to write one it only destroys coverage.
        val worker = File(dir, "worker-" + System.getProperty("org.gradle.test.worker",
            ProcessHandle.current().pid().toString()))
        worker.mkdirs()
        File(worker, "index.tsv").writeText("an earlier capture\n")
        val agent = ScriptedAgent()
        val l = listener(agent, dir, Sink())

        l.testPlanExecutionStarted(null)
        runTest(l, identifier("a"))
        l.testPlanExecutionFinished(null)

        assertTrue(agent.takes.isEmpty(), "a listener with no writer reset coverage: ${agent.takes}")
        assertEquals(null, System.getProperty(CaptureClaim.PROPERTY))
    }

    @Test
    fun `records a duration for each test`(@TempDir dir: File) {
        val agent = ScriptedAgent()
        val l = listener(agent, dir, Sink())
        l.testPlanExecutionStarted(null)

        val id = identifier("a")
        l.executionStarted(id)
        Thread.sleep(5)
        l.executionFinished(id, TestExecutionResult.successful())
        l.testPlanExecutionFinished(null)

        // The first row is the startup record, which has no duration of its own.
        val rows = File(dir.listFiles()!!.first(), "index.tsv").readLines().map { it.split('\t') }
        val testRow = rows.single { it[4] != AgentContract.UNATTRIBUTED_RECORD_ID }
        assertTrue(
            testRow[1].toLong() > 0,
            "duration must be captured; wall-clock conversion depends on it",
        )
    }
}

/**
 * Coverage that accumulates between test windows belongs to no test; discarding it would make a
 * change to setup code select nothing, a silent skip.
 */
class UnattributedAttributionTest {

    private class Sink : ProbeReporter {
        val results = mutableListOf<ProbeResult>()
        override fun report(result: ProbeResult) {
            results += result
        }
    }

    /** Returns a distinct non-empty blob per call so windows are distinguishable. */
    private class CountingAgent : JacocoAgent {
        var call = 0
        override fun version() = "test"
        override fun takeExecutionData(): ByteArray = ByteArray(++call)
    }

    private fun idsOf(dir: java.io.File): List<String> =
        java.io.File(dir.listFiles()!!.first(), "index.tsv").readLines().map { it.split('	')[4] }

    private fun run(tests: Int, dir: java.io.File): List<String> {
        val listener = PlatformEvents({ AgentLookup.Result.found(CountingAgent()) }, Sink(), dir)
        listener.testPlanExecutionStarted(null)
        repeat(tests) { i ->
            val id = TestIdentifiers.leaf("t$i")
            listener.executionStarted(id)
            listener.executionFinished(id, org.junit.platform.engine.TestExecutionResult.successful())
        }
        listener.testPlanExecutionFinished(null)
        return idsOf(dir)
    }

    @org.junit.jupiter.api.Test
    fun `keeps the window before the first test as globally unattributable`(
        @org.junit.jupiter.api.io.TempDir dir: java.io.File,
    ) {
        // JVM and engine startup precede every class, so no class owns it and running any test
        // re-executes it. Global is the only safe scope.
        assertEquals(AgentContract.UNATTRIBUTED_RECORD_ID, run(tests = 1, dir = dir).first())
    }

    @org.junit.jupiter.api.Test
    fun `keeps the window between every pair of tests`(@org.junit.jupiter.api.io.TempDir dir: java.io.File) {
        // Keeping only the pre-first-test window would discard every later class's @BeforeAll and
        // @AfterAll, so a change to them would select nothing.
        val ids = run(tests = 3, dir = dir)

        val u = AgentContract.UNATTRIBUTED_RECORD_ID
        val c = AgentContract.CLASS_SCOPED_RECORD_PREFIX

        // Only the plan-start window is global; every other gap belongs to the class on one or both
        // sides of it, which is what keeps setup coverage from forcing a full suite run.
        assertEquals(1, ids.count { it == u }, "plan start is the only globally unattributable window")
        assertEquals(4, ids.count { it.startsWith(c) }, "before each of three tests, plus the tail")
        assertEquals(3, ids.count { it != u && !it.startsWith(c) }, "three real tests")
    }

    @org.junit.jupiter.api.Test
    fun `keeps the window after the last test, scoped to the class that just ended`(
        @org.junit.jupiter.api.io.TempDir dir: java.io.File,
    ) {
        // The tail holds the last class's @AfterAll, so it belongs to that class rather than to
        // nothing -- attributing it globally would force a full run for any edit to teardown code.
        val last = run(tests = 2, dir = dir).last()

        assertTrue(last.startsWith(AgentContract.CLASS_SCOPED_RECORD_PREFIX), last)
        assertContains(last, "DefaultSpec")
    }

    @org.junit.jupiter.api.Test
    fun `does not record empty unattributed windows`(@org.junit.jupiter.api.io.TempDir dir: java.io.File) {
        // An agent that always returns nothing means nothing new executed between tests; writing a
        // record per gap anyway would double the map for no information.
        val emptyAgent = object : JacocoAgent {
            override fun version() = "test"
            override fun takeExecutionData() = ByteArray(0)
        }
        val listener = PlatformEvents({ AgentLookup.Result.found(emptyAgent) }, Sink(), dir)
        listener.testPlanExecutionStarted(null)
        val id = TestIdentifiers.leaf("only")
        listener.executionStarted(id)
        listener.executionFinished(id, org.junit.platform.engine.TestExecutionResult.successful())
        listener.testPlanExecutionFinished(null)

        assertEquals(listOf(id.uniqueId), idsOf(dir))
    }
}

class UnknownNextScopeTest {

    private class Sink : ProbeReporter {
        val results = mutableListOf<ProbeResult>()
        override fun report(result: ProbeResult) {
            results += result
        }
    }

    private class CountingAgent : JacocoAgent {
        var call = 0
        override fun version() = "test"
        override fun takeExecutionData(): ByteArray = ByteArray(++call)
    }

    @org.junit.jupiter.api.Test
    fun `a window before a test with no class scope is global, not blamed on the previous class`(
        @TempDir dir: File,
    ) {
        // In a mixed jupiter+vintage worker, blaming the preceding jupiter class would mean the
        // vintage test that ran the code is never selected.
        val listener = PlatformEvents({ AgentLookup.Result.found(CountingAgent()) }, Sink(), dir)
        listener.testPlanExecutionStarted(null)

        val jupiter = TestIdentifiers.leaf("t", inClass = "AlphaSpec")
        listener.executionStarted(jupiter)
        listener.executionFinished(jupiter, TestExecutionResult.successful())

        val vintage = TestIdentifiers.vintage("com.example.OldTest", "t")
        listener.executionStarted(vintage)
        listener.executionFinished(vintage, TestExecutionResult.successful())
        listener.testPlanExecutionFinished(null)

        val ids = File(dir.listFiles()!!.first(), "index.tsv").readLines().map { it.split('\t')[4] }
        val windowBeforeVintage = ids[ids.indexOf(jupiter.uniqueId) + 1]

        assertEquals(AgentContract.UNATTRIBUTED_RECORD_ID, windowBeforeVintage)
    }
}
