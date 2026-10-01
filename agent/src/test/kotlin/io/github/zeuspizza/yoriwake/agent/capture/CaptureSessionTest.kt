package io.github.zeuspizza.yoriwake.agent.capture

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.agent.engines.TestNgEvents
import io.github.zeuspizza.yoriwake.agent.host.GradleHost
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The bookkeeping that decides which test owns which coverage window. A mistake here does not
 * throw: it writes a record under the wrong id, and a later run skips a test it should have run.
 */
class CaptureSessionTest {

    private val alpha = "[engine:junit-jupiter]/[class:com.acme.AlphaTest]/[method:one()]"
    private val beta = "[engine:junit-jupiter]/[class:com.acme.BetaTest]/[method:two()]"

    @TempDir
    lateinit var records: File

    private val sessions = mutableListOf<CaptureSession>()

    private val stubAgent = object : JacocoAgent {
        override fun version() = "stub"
        override fun takeExecutionData() = ByteArray(1)
    }

    private fun session(nestedRuns: Boolean = true): CaptureSession =
        CaptureSession.open(stubAgent, records, "0", nestedRuns).also { sessions += it }

    /** Ends every session: Windows will not delete a file a handle still holds. */
    @AfterEach
    fun release() {
        sessions.forEach { it.endAtShutdown(false) }
        JvmSession.reset()
    }

    private fun recorded(): List<Pair<String, String>> =
        File(records, "worker-0/${AgentContract.INDEX_FILE}").readLines().map {
            val columns = it.split('\t')
            columns[3] to columns[4]
        }

    @Test
    fun `a test that runs another test inside itself keeps its own window`() {
        // A test that hands a class to JUnitCore fires nested notifications; reading them flatly
        // would close the outer test's window at the inner test's start and lose its coverage.
        val session = session()
        session.started(alpha)
        session.started(beta)

        assertEquals(listOf(alpha, beta), session.inProgress())
        assertTrue(
            session.unattributable().isEmpty(),
            "same-thread nesting is ordinary, not a reason to give up on attribution",
        )

        session.finished(beta, null)
        session.finished(alpha, null)

        assertTrue(session.inProgress().isEmpty())
    }

    @Test
    fun `two tests open at once on different threads are both marked unattributable`() {
        // Not nesting: two tests sharing one JaCoCo window. Neither can be attributed, so both are
        // forced.
        val session = session()
        session.started(alpha)

        val onAnotherThread = CountDownLatch(1)
        Thread {
            runCatching { session.started(beta) }
            onAnotherThread.countDown()
        }.start()
        assertTrue(onAnotherThread.await(5, TimeUnit.SECONDS), "the second start never ran")

        val unattributable = session.unattributable()
        assertTrue(alpha in unattributable, "the enclosing test was left attributable: $unattributable")
        assertTrue(beta in unattributable, "the concurrent test was left attributable: $unattributable")
    }

    @Test
    fun `two overlapping tests in one thread record as unknown where no nested run can reach the events`() {
        // A Launcher's nested run has a listener of its own, so a Platform test starting inside
        // another that is not its parent shares its window: parallel execution, not nesting.
        val session = session(nestedRuns = false)
        session.started(alpha)
        session.started(beta)
        session.finished(beta, "SUCCESSFUL")
        session.finished(alpha, "SUCCESSFUL")
        session.endRun()

        val outcomes = recorded().filter { it.second == alpha || it.second == beta }
        assertEquals(listOf("UNKNOWN" to beta, "UNKNOWN" to alpha), outcomes)
    }

    @Test
    fun `a test the engine reports inside its parent keeps a window of its own`() {
        // A Spock iteration inside its feature: the selector reads the iterations' records.
        val feature = "[engine:spock]/[spec:com.acme.HandlerSpec]/[feature:f]"
        val session = session(nestedRuns = false)
        session.started(feature)
        session.started("$feature/[iteration:0]")
        session.finished("$feature/[iteration:0]", "SUCCESSFUL")
        session.finished(feature, "SUCCESSFUL")
        session.endRun()

        val tests = recorded().filter { it.first != AgentContract.OUTCOME_NOT_A_TEST }
        assertEquals(listOf("SUCCESSFUL" to "$feature/[iteration:0]", "SUCCESSFUL" to feature), tests)
    }

    @Test
    fun `a finish for a test that never started does not corrupt the stack`() {
        // Runners do surprising things, and a stray notification must not leave the bookkeeping in
        // a state where the NEXT test's coverage lands under a dead id.
        val session = session()
        session.finished(beta, null)

        assertTrue(session.inProgress().isEmpty())

        session.started(alpha)
        assertEquals(listOf(alpha), session.inProgress())
    }

    @Test
    fun `a failure recorded for an open test does not close it`() {
        val session = session()
        session.started(alpha)
        session.failed(alpha)

        assertEquals(listOf(alpha), session.inProgress())
    }

    @Test
    fun `the class scope of a test id is read to the closing bracket, and unknown shapes answer null`() {
        assertEquals("[engine:junit-jupiter]/[class:com.acme.AlphaTest]", CaptureSession.classScopeOf(alpha))
        assertEquals(null, CaptureSession.classScopeOf("no-class-segment-here"))
        assertEquals(null, CaptureSession.classScopeOf(null))
    }

    @Test
    fun `an id whose class segment is never closed answers null rather than running off the end`() {
        assertEquals(null, CaptureSession.classScopeOf("[engine:x]/[class:com.acme.Unterminated"))
    }

    @Test
    fun `nesting three deep unwinds in order`() {
        val gamma = "[engine:junit-jupiter]/[class:com.acme.GammaTest]/[method:three()]"
        val session = session()
        session.started(alpha)
        session.started(beta)
        session.started(gamma)

        assertEquals(listOf(alpha, beta, gamma), session.inProgress())

        session.finished(gamma, null)
        assertEquals(listOf(alpha, beta), session.inProgress())
        session.finished(beta, null)
        session.finished(alpha, null)
        assertTrue(session.inProgress().isEmpty())
    }

    private val completionMarker get() = File(records, "worker-0/${AgentContract.PLAN_COMPLETE_FILE}")

    /** The JVM-wide session's shutdown hook, run in place. */
    private fun shutDown(session: CaptureSession) {
        JvmSession.install(session)
        JvmSession.shutdown()
    }

    @Test
    fun `a JVM that shuts down without the runner saying it finished is not marked complete`() {
        // The shutdown hook runs on System.exit mid-run as readily as at the end, so it must not
        // write the marker unconditionally.
        val session = session()
        session.started(alpha)
        session.finished(alpha, null)
        shutDown(session)

        assertFalse(completionMarker.exists(), "a run nobody said finished was marked complete")
    }

    @Test
    fun `TestNG's end of execution marks the run complete`() {
        val session = session()
        JvmSession.install(session)
        session.started(alpha)
        session.finished(alpha, null)
        TestNgEvents().onExecutionFinish()
        shutDown(session)

        assertTrue(completionMarker.isFile, "a finished TestNG run wrote no completion marker")
    }

    @Test
    fun `a TestNG dry run reaches no capture`() {
        // The Platform's TestNG engine discovers by a dry run, before the plan starts. Taken as a
        // real run, it hands TestNG's listener the JVM's capture, and the Platform's listener, the
        // one that sees every engine, records nothing.
        val session = session()
        JvmSession.install(session)
        val events = TestNgEvents()
        val gamma = testNgResult("com.acme.GammaTest", "three")
        System.setProperty("testng.mode.dryrun", "true")
        try {
            events.onTestStart(gamma)
            events.onTestSuccess(gamma)
            events.onExecutionFinish()
        } finally {
            System.clearProperty("testng.mode.dryrun")
        }
        shutDown(session)

        assertTrue(recorded().none { it.second.contains("GammaTest") }, "a dry run recorded a test: ${recorded()}")
        assertFalse(completionMarker.exists(), "a dry run's end marked the run complete")
    }

    /** A TestNG result for one method, without running TestNG. */
    private fun testNgResult(className: String, methodName: String): org.testng.ITestResult {
        fun <T> proxy(type: Class<T>, answer: (String) -> Any?): T = type.cast(
            java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
                answer(method.name)
            }
        )
        val testClass = proxy(org.testng.ITestClass::class.java) { if (it == "getName") className else null }
        val method = proxy(org.testng.ITestNGMethod::class.java) { if (it == "getMethodName") methodName else null }
        return proxy(org.testng.ITestResult::class.java) {
            when (it) {
                "getTestClass" -> testClass
                "getMethod" -> method
                else -> null
            }
        }
    }

    @Test
    fun `Gradle's JUnit 4 processor stopping marks the run complete`() {
        val session = session()
        session.started(alpha)
        session.finished(alpha, null)
        System.setProperty(GradleHost.RUN_FINISHED_PROPERTY, "true")
        try {
            shutDown(session)
        } finally {
            System.clearProperty(GradleHost.RUN_FINISHED_PROPERTY)
        }

        assertTrue(completionMarker.isFile, "a finished JUnit 4 run wrote no completion marker")
    }

    @Test
    fun `an end of run inside an open test is a nested run's, not this one's`() {
        val session = session()
        session.started(alpha)
        session.runFinished()
        shutDown(session)

        assertFalse(completionMarker.exists(), "a nested run's end marked the outer run complete")
    }

    @Test
    fun `a test that starts after the run said it finished makes it unfinished again`() {
        val session = session()
        session.started(alpha)
        session.finished(alpha, null)
        session.runFinished()
        session.started(beta)
        session.finished(beta, null)
        shutDown(session)

        assertFalse(completionMarker.exists(), "a run that went on after finishing was marked complete")
    }

    @Test
    fun `the JVM-wide session does not capture while another capture holds the claim`() {
        // TestNG on the Platform reports every test to the Platform's listener and to TestNG's
        // own; two captures resetting one JVM's coverage would each record windows that are wrong.
        System.setProperty(AgentContract.RECORDS_DIR_PROPERTY, records.path)
        val held = CaptureClaim.take(CaptureSessionTest::class.java)!!
        try {
            JvmSession.EVENTS.started(alpha)
            JvmSession.EVENTS.finished(alpha, null)
        } finally {
            held.release()
            System.clearProperty(AgentContract.RECORDS_DIR_PROPERTY)
        }

        assertTrue(records.listFiles().orEmpty().isEmpty(), "a second capture wrote ${records.list()?.toList()}")
    }

    /** Touches handed out one batch per drain, observation complete unless [incomplete] says not. */
    private class ScriptedTouches(
        vararg batches: List<Array<String>>,
        val incomplete: String? = null,
        val lookups: String? = null,
    ) : CaptureSession.Touches {
        private val pending = ArrayDeque(batches.toList())
        var started = false
        override fun drain(): List<Array<String>> = pending.removeFirstOrNull() ?: emptyList()
        override fun incomplete(): String? = incomplete
        override fun lookupsIncomplete(): String? = lookups
        override fun planStarted() { started = true }
    }

    private fun touchLines(): List<String> =
        File(records, "worker-0/${AgentContract.TOUCHES_FILE}").readLines()

    @Test
    fun `a class first loaded inside a test is filed under that test's record`() {
        val session = CaptureSession.open(
            stubAgent, records, "0", true,
            ScriptedTouches(
                emptyList(),
                listOf(arrayOf(AgentContract.TOUCH_LOADED, "com.acme.Rates")),
            ),
        ).also { sessions += it }
        session.started(alpha)
        session.finished(alpha, null)
        session.endRun()

        val alphaSequence = File(records, "worker-0/${AgentContract.INDEX_FILE}").readLines()
            .map { it.split('\t') }.single { it[4] == alpha }[0]
        assertEquals(
            listOf(
                "2\t${AgentContract.TOUCH_PLAN_STARTED}\t",
                "$alphaSequence\t${AgentContract.TOUCH_LOADED}\tcom.acme.Rates",
                AgentContract.TOUCHES_COMPLETE,
            ),
            touchLines(),
        )
    }

    @Test
    fun `an observation that is not complete says so once, at the first record`() {
        val session = CaptureSession.open(
            stubAgent, records, "0", true, ScriptedTouches(incomplete = "no hooks"),
        ).also { sessions += it }
        session.started(alpha)
        session.finished(alpha, null)
        session.endRun()

        assertEquals(
            listOf("1\t${AgentContract.TOUCH_ALL}\tno hooks", "2\t${AgentContract.TOUCH_PLAN_STARTED}\t", AgentContract.TOUCHES_COMPLETE),
            touchLines(),
        )
    }

    @Test
    fun `touches from a run that never ended are not marked complete`() {
        val session = CaptureSession.open(stubAgent, records, "0", true, ScriptedTouches())
        session.started(alpha)
        session.finished(alpha, null)
        // No end: the JVM died, so nothing may vouch for what it did not report.
        sessions.remove(session)

        assertFalse(File(records, "worker-0/${AgentContract.TOUCHES_FILE}").readLines().contains(AgentContract.TOUCHES_COMPLETE))
    }

    @Test
    fun `the plan's start is marked once, after what discovery touched`() {
        val touches = ScriptedTouches(listOf(arrayOf(AgentContract.TOUCH_LOOKUP, "com.acme.AlphaTest")))
        val session = CaptureSession.open(stubAgent, records, "0", true, touches).also { sessions += it }
        session.started(alpha)
        session.finished(alpha, null)
        session.endRun()

        assertTrue(touches.started)
        assertEquals(
            listOf("1\t${AgentContract.TOUCH_LOOKUP}\tcom.acme.AlphaTest", "2\t${AgentContract.TOUCH_PLAN_STARTED}\t"),
            touchLines().take(2),
        )
    }

    @Test
    fun `lookups the agent could not observe are filed as a lookup of every class`() {
        val session = CaptureSession.open(
            stubAgent, records, "0", true, ScriptedTouches(lookups = "no lookup hooks"),
        ).also { sessions += it }
        session.started(alpha)
        session.finished(alpha, null)
        session.endRun()

        assertTrue(touchLines().contains("1\t${AgentContract.TOUCH_LOOKUP}\t${AgentContract.FIRST_TOUCH_ANY}"), "${touchLines()}")
    }
}
