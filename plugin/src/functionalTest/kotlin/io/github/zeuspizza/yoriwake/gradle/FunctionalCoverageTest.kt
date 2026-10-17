package io.github.zeuspizza.yoriwake.gradle

import org.jacoco.core.data.ExecutionData
import org.jacoco.core.data.SessionInfo
import org.jacoco.core.runtime.RemoteControlReader
import org.jacoco.core.runtime.RemoteControlWriter
import org.jacoco.core.tools.ExecFileLoader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetAddress
import java.net.Socket
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The server that collects the TestKit daemons' coverage, driven by stand-ins that speak JaCoCo's
 * own remote-control protocol as its runtime does: they connect, answer a dump request with what
 * they recorded and reset it, and send what is left when their JVM exits.
 */
class FunctionalCoverageTest {

    @TempDir lateinit var dir: File

    private val runtimes = mutableListOf<FakeRuntime>()
    private lateinit var server: CoverageServer

    @AfterEach
    fun stop() {
        runtimes.forEach { it.close() }
        if (this::server.isInitialized) server.close()
    }

    @Test
    fun `one dump holds every connected runtime's data and resets each`() {
        server = start()
        val first = connect().record(1, "a/First", true, false)
        val second = connect().record(2, "a/Second", false, true)

        server.dump("OneTest")
        server.dump("TwoTest")

        val one = load("OneTest.exec")
        assertEquals(setOf("a/First", "a/Second"), one.executionDataStore.contents.map { it.name }.toSet())
        assertEquals(listOf(true, false), one.executionDataStore.get(1).probes.toList())
        assertEquals(2, one.sessionInfoStore.infos.size)
        assertTrue(first.probes(1).none { it } && second.probes(2).none { it }, "a dump resets what it collected")
        assertTrue(load("TwoTest.exec").executionDataStore.contents.none { it.hasHits() })
    }

    @Test
    fun `a runtime that disconnects does not stop later dumps`() {
        server = start()
        connect().record(1, "a/Gone", true).close()
        connect().record(2, "a/Stays", true)

        server.dump("AfterDisconnect")
        server.dump("Later")

        assertTrue(load("AfterDisconnect.exec").executionDataStore.contains("a/Stays"))
        assertTrue(File(dir, "Later.exec").isFile)
    }

    @Test
    fun `a runtime with nothing recorded adds nothing`() {
        server = start()
        connect()

        server.dump("Empty")

        assertTrue(load("Empty.exec").executionDataStore.contents.isEmpty())
    }

    @Test
    fun `data sent unasked is kept in a file of its own`() {
        server = start()
        connect().record(3, "a/Exiting", true).exit()

        waitFor { File(dir, "unsolicited-1.exec").isFile }
        server.dump("Next")

        assertTrue(load("unsolicited-1.exec").executionDataStore.contains("a/Exiting"))
        assertTrue(load("Next.exec").executionDataStore.contents.isEmpty())
    }

    @Test
    fun `the summary names every class that was hit`() {
        server = start()
        connect().record(1, "io/github/zeuspizza/yoriwake/gradle/YoriwakePlugin", true).record(2, "a/Missed", false)

        server.dump("Summarised")

        val summary = File(dir, "summary.txt").readLines()
        assertTrue("hit io/github/zeuspizza/yoriwake/gradle/YoriwakePlugin" in summary, summary.joinToString("\n"))
        assertTrue("hit a/Missed" !in summary)
        assertTrue("dumps 1" in summary)
    }

    @Test
    fun `every home of a run reports to the same port, and a run without coverage sets no arguments`() {
        val first = File(dir, "first").also(FunctionalCoverage::configure)
        val second = File(dir, "second").also(FunctionalCoverage::configure)

        val firstText = File(first, "gradle.properties").readText()
        assertEquals(firstText, File(second, "gradle.properties").readText())
        if (System.getProperty("yoriwake.functional.coverage") == "true") {
            assertTrue("-Djacoco-agent.port=" in firstText && "-Xbootclasspath/a:" in firstText, firstText)
            assertTrue("-javaagent" !in firstText, "a Java agent turns the configuration cache off")
        } else {
            assertTrue("org.gradle.jvmargs" !in firstText, firstText)
        }
    }

    private fun start() = CoverageServer(dir, "test-session", replyTimeoutMillis = 10_000).also { it.start() }

    private fun connect(): FakeRuntime {
        val runtime = FakeRuntime(Socket(InetAddress.getLoopbackAddress(), server.port))
        runtimes += runtime
        // A dump asks only the runtimes the server has accepted so far.
        val expected = runtimes.size
        waitFor { server.accepted >= expected }
        return runtime
    }

    private fun load(name: String) = ExecFileLoader().apply { load(File(dir, name)) }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(20)
        }
    }

    /** Answers dump requests as JaCoCo's runtime does over `output=tcpclient`. */
    private class FakeRuntime(private val socket: Socket) {
        private val data = linkedMapOf<Long, ExecutionData>()
        private val writer = RemoteControlWriter(socket.getOutputStream())
        private val reader = RemoteControlReader(socket.getInputStream())

        init {
            reader.setRemoteCommandVisitor { dump, reset ->
                synchronized(this) {
                    if (dump) send()
                    if (reset) data.values.forEach { it.reset() }
                    writer.sendCmdOk()
                }
            }
            Thread({ runCatching { while (reader.read()) Unit } }, "fake-runtime").apply { isDaemon = true }.start()
        }

        fun record(id: Long, name: String, vararg probes: Boolean) = apply {
            synchronized(this) { data[id] = ExecutionData(id, name, probes.copyOf()) }
        }

        fun probes(id: Long): BooleanArray = synchronized(this) { data.getValue(id).probes.copyOf() }

        /** What the runtime does when its JVM exits: sends its data, then closes. */
        fun exit() {
            synchronized(this) { send() }
            close()
        }

        fun close() {
            runCatching { socket.close() }
        }

        private fun send() {
            writer.visitSessionInfo(SessionInfo("fake", 0, 0))
            data.values.forEach(writer::visitClassExecution)
        }
    }
}
