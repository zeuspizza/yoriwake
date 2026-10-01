package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.agent.capture.CaptureSession
import io.github.zeuspizza.yoriwake.agent.capture.JacocoAgent
import io.github.zeuspizza.yoriwake.agent.capture.JvmSession
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.agent.engines.JUnit4Events
import io.github.zeuspizza.yoriwake.agent.engines.TestNgEvents
import io.github.zeuspizza.yoriwake.agent.select.ChangeSet
import io.github.zeuspizza.yoriwake.agent.select.MapReader
import io.github.zeuspizza.yoriwake.agent.select.Selector
import io.github.zeuspizza.yoriwake.gradle.capture.CoverageDecoder
import org.jacoco.core.data.ExecutionData
import org.jacoco.core.data.ExecutionDataWriter
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.testng.IClass
import org.testng.ITestNGMethod
import org.testng.ITestResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * JUnit 4 and TestNG capture, decoded and handed to the selector, the way a real run takes it.
 *
 * Setup-only code is recorded in a window between tests, useful only if the selector can map the
 * window's id back to the class's tests. Producer and selector live in different modules, so this
 * drives the real ones rather than asserting on the id string.
 */
class CaptureToSelectorTest {

    @TempDir
    lateinit var mapDir: File

    /** The JVM-wide session JUnit 4 and TestNG feed, installed and ended through its test seams. */
    private val jvmSession = JvmSession::class.java

    private var session: CaptureSession? = null

    /** Hands out one blob per take, in the order the boundaries ask for them. */
    private class ScriptedAgent(val blobs: ArrayDeque<ByteArray>) : JacocoAgent {
        override fun version() = "test"
        override fun takeExecutionData(): ByteArray = blobs.removeFirstOrNull() ?: ByteArray(0)
    }

    /** A JVM whose loads and reads were all observed and touched no class, so only coverage selects. */
    private object NothingTouched : CaptureSession.Touches {
        override fun drain(): List<Array<String>> = emptyList()
        override fun incomplete(): String? = null
    }

    /** Stands in for org.junit.runner.Description, which JUnit4Events reads reflectively. */
    class Description(val className: String, val methodName: String)

    @BeforeEach
    fun install() {
        reset()
    }

    @AfterEach
    fun release() {
        reset()
    }

    private fun covering(vararg classes: String): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = ExecutionDataWriter(out)
        classes.forEachIndexed { i, name ->
            writer.visitClassExecution(ExecutionData(i + 1L, name.replace('.', '/'), booleanArrayOf(true)))
        }
        return out.toByteArray()
    }

    private fun seam(name: String, vararg types: Class<*>) =
        jvmSession.getDeclaredMethod(name, *types).apply { isAccessible = true }

    private fun reset() {
        session?.endAtShutdown(false)
        session = null
        seam("reset").invoke(null)
    }

    /** A real writer into the directory the decoder reads, and an agent that returns [windows]. */
    private fun start(vararg windows: ByteArray) {
        val opened = CaptureSession.open(
            ScriptedAgent(ArrayDeque(windows.toList())), CoverageDecoder.recordsDir(mapDir), "1", true,
            NothingTouched,
        )
        session = opened
        seam("install", CaptureSession::class.java).invoke(null, opened)
    }

    /** The shutdown hook's flush, then the decode and the decision a selecting run would take. */
    private fun decide(changed: String): Selector.Decision {
        seam("shutdown").invoke(null)
        assertEquals(0, session!!.failures(), "capture failed")
        CoverageDecoder.decode(mapDir, listOf("com.acme"))
        return Selector.decide(
            MapReader.read(mapDir),
            ChangeSet.of(listOf(changed), emptyList()),
            emptyList(),
        )
    }

    private fun junit4(className: String, method: String) = Description(className, method)

    private fun junit4Id(className: String, method: String) =
        "[engine:junit4]/[class:$className]/[method:$method]"

    private fun runJUnit4(className: String, method: String) {
        JUnit4Events.started(junit4(className, method))
        JUnit4Events.finished(junit4(className, method))
    }

    private fun testNgResult(className: String, method: String): ITestResult {
        val loader = javaClass.classLoader
        val testClass = Proxy.newProxyInstance(loader, arrayOf(IClass::class.java)) { _, m, _ ->
            if (m.name == "getName") className else null
        }
        val testMethod = Proxy.newProxyInstance(loader, arrayOf(ITestNGMethod::class.java)) { _, m, _ ->
            if (m.name == "getMethodName") method else null
        }
        return Proxy.newProxyInstance(loader, arrayOf(ITestResult::class.java)) { _, m, _ ->
            when (m.name) {
                "getTestClass" -> testClass
                "getMethod" -> testMethod
                else -> null
            }
        } as ITestResult
    }

    private fun runTestNg(listener: TestNgEvents, className: String, method: String) {
        val result = testNgResult(className, method)
        listener.onTestStart(result)
        listener.onTestSuccess(result)
    }

    private fun assertSelectsOnly(decision: Selector.Decision, expected: Set<String>, all: Set<String>) {
        assertFalse(decision.isFullRun, "forced instead of narrowing: ${decision.fullRunReason()}")
        for (id in all) {
            assertEquals(
                id in expected, decision.includes(id),
                "selection of $id from\n" + File(mapDir, AgentContract.COVERAGE_FILE).readText(),
            )
        }
    }

    @Test
    fun `JUnit 4 - code reached only from @BeforeClass selects every test of that class`() {
        // Windows in take order: startup, first, A's @BeforeClass (between First and A), a1,
        // between a1 and a2, a2, between A and Other, other, tail.
        start(
            covering("com.acme.Boot"),
            covering("com.acme.FirstSubject"),
            covering("com.acme.Setup"),
            covering("com.acme.ASubject"),
            ByteArray(0),
            covering("com.acme.ASubject"),
            ByteArray(0),
            covering("com.acme.OtherSubject"),
            ByteArray(0),
        )
        runJUnit4("com.acme.FirstTest", "first")
        runJUnit4("com.acme.ATest", "a1")
        runJUnit4("com.acme.ATest", "a2")
        runJUnit4("com.acme.OtherTest", "other")

        val first = junit4Id("com.acme.FirstTest", "first")
        val a1 = junit4Id("com.acme.ATest", "a1")
        val a2 = junit4Id("com.acme.ATest", "a2")
        val other = junit4Id("com.acme.OtherTest", "other")

        // The window straddles FirstTest and ATest, so both classes run. OtherTest runs too, only
        // because it ran after Setup first ran in its JVM; FirstTest ran before and needs the window.
        assertSelectsOnly(decide("com.acme.Setup"), setOf(first, a1, a2, other), setOf(first, a1, a2, other))
    }

    @Test
    fun `TestNG - code reached only from @BeforeClass selects every test of that class`() {
        val listener = TestNgEvents()
        start(
            covering("com.acme.Boot"),
            covering("com.acme.FirstSubject"),
            covering("com.acme.Setup"),
            covering("com.acme.ASubject"),
            ByteArray(0),
            covering("com.acme.ASubject"),
            ByteArray(0),
            covering("com.acme.OtherSubject"),
            ByteArray(0),
        )
        runTestNg(listener, "com.acme.FirstTest", "first")
        runTestNg(listener, "com.acme.ATest", "a1")
        runTestNg(listener, "com.acme.ATest", "a2")
        runTestNg(listener, "com.acme.OtherTest", "other")

        val id = { c: String, m: String -> "[engine:testng]/[class:$c]/[method:$m]" }
        val first = id("com.acme.FirstTest", "first")
        val a1 = id("com.acme.ATest", "a1")
        val a2 = id("com.acme.ATest", "a2")
        val other = id("com.acme.OtherTest", "other")

        // OtherTest ran after Setup first ran in its JVM; FirstTest ran before and needs the window.
        assertSelectsOnly(decide("com.acme.Setup"), setOf(first, a1, a2, other), setOf(first, a1, a2, other))
    }

    @Test
    fun `TestNG - a configuration window inside one class selects that class and nothing that ran before it`() {
        // Between a1 and a2 both sides are ATest, as a @BeforeMethod or @AfterMethod window is.
        val listener = TestNgEvents()
        start(
            covering("com.acme.Boot"),
            covering("com.acme.FirstSubject"),
            ByteArray(0),
            covering("com.acme.ASubject"),
            covering("com.acme.Setup"),
            covering("com.acme.ASubject"),
            ByteArray(0),
            covering("com.acme.OtherSubject"),
            ByteArray(0),
        )
        runTestNg(listener, "com.acme.FirstTest", "first")
        runTestNg(listener, "com.acme.ATest", "a1")
        runTestNg(listener, "com.acme.ATest", "a2")
        runTestNg(listener, "com.acme.OtherTest", "other")

        val id = { c: String, m: String -> "[engine:testng]/[class:$c]/[method:$m]" }
        val all = setOf(
            id("com.acme.FirstTest", "first"), id("com.acme.ATest", "a1"),
            id("com.acme.ATest", "a2"), id("com.acme.OtherTest", "other"),
        )
        // a1 ran before Setup and needs the window; a2 and OtherTest ran after Setup first ran.
        assertSelectsOnly(
            decide("com.acme.Setup"),
            setOf(id("com.acme.ATest", "a1"), id("com.acme.ATest", "a2"), id("com.acme.OtherTest", "other")),
            all,
        )
    }

    @Test
    fun `a window with a side whose class cannot be read is attributed to nobody and forces`() {
        start(
            covering("com.acme.Boot"),
            covering("com.acme.FirstSubject"),
            covering("com.acme.Setup"),
            covering("com.acme.Other"),
            ByteArray(0),
        )
        runJUnit4("com.acme.FirstTest", "first")
        // Not a shape either producer mints, so its class is unknown.
        JvmSession.EVENTS.started("[engine:junit4]/[suite:odd]")
        JvmSession.EVENTS.finished("[engine:junit4]/[suite:odd]", null)

        val decision = decide("com.acme.Setup")

        assertTrue(decision.isFullRun, "a window no class can own narrowed the run")
    }
}
