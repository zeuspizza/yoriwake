package io.github.zeuspizza.yoriwake.agent.engines

import io.github.zeuspizza.yoriwake.agent.capture.AgentLookup
import io.github.zeuspizza.yoriwake.agent.capture.CaptureAccess
import io.github.zeuspizza.yoriwake.agent.capture.JacocoAgent
import io.github.zeuspizza.yoriwake.agent.capture.ProbeReporter
import io.github.zeuspizza.yoriwake.agent.capture.ProbeResult
import org.junit.jupiter.api.Test
import java.io.File
import java.util.ServiceLoader
import java.util.function.Supplier
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlatformEventsTest {

    private class RecordingReporter : ProbeReporter {
        val results = mutableListOf<ProbeResult>()
        override fun report(result: ProbeResult) {
            results += result
        }
    }

    private class FakeAgent(
        private val version: String = "0.8.12",
        private val data: ByteArray = ByteArray(64),
        private val failure: Throwable? = null,
    ) : JacocoAgent {
        override fun version(): String = failure?.let { throw it } ?: version
        override fun takeExecutionData(): ByteArray = data
    }

    private fun runProbe(lookup: Supplier<AgentLookup.Result>): RecordingReporter {
        val reporter = RecordingReporter()
        PlatformEvents(lookup, reporter).probeAndReport()
        return reporter
    }

    @Test
    fun `reports the agent version when the agent is reachable`() {
        val reporter = runProbe { AgentLookup.Result.found(FakeAgent(version = "0.8.12")) }

        val result = reporter.results.single()
        assertTrue(result.isReachable)
        assertEquals("0.8.12", result.version())
        // Taking execution data would reset away classes initialised before the probe, so the
        // startup window would begin at the probe instead of at JVM start.
        assertEquals(0, result.executionDataBytes())
    }

    @Test
    fun `degrades to a diagnostic when the agent classes are absent`() {
        val reason = CaptureAccess.notOnClasspath(
            ClassNotFoundException("org.jacoco.agent.rt.RT")
        )
        val reporter = runProbe { AgentLookup.Result.missing(reason) }

        val result = reporter.results.single()
        assertFalse(result.isReachable)
        assertContains(result.diagnostic(), "not visible to this classloader")
    }

    @Test
    fun `degrades to a diagnostic when the classes load but no agent is running`() {
        val reason = CaptureAccess.notAttached(IllegalStateException("agent not started"))
        val reporter = runProbe { AgentLookup.Result.missing(reason) }

        assertContains(reporter.results.single().diagnostic(), "no agent is running")
    }

    @Test
    fun `never propagates a lookup failure into the build under measurement`() {
        val reporter = RecordingReporter()

        // Must be swallowed: the listener must never break the suite it runs in.
        PlatformEvents(
            { throw NoClassDefFoundError("org/jacoco/agent/rt/RT") },
            reporter,
        ).probeAndReport()

        assertContains(reporter.results.single().diagnostic(), "NoClassDefFoundError")
    }

    @Test
    fun `never propagates a failure from the agent itself`() {
        val reporter = runProbe {
            AgentLookup.Result.found(FakeAgent(failure = UnsupportedOperationException("boom")))
        }

        assertContains(reporter.results.single().diagnostic(), "Agent found but unusable")
    }

    @Test
    fun `is discoverable through the JUnit Platform service loader`() {
        val listeners = ServiceLoader.load(
            org.junit.platform.launcher.TestExecutionListener::class.java,
            PlatformEventsTest::class.java.classLoader,
        ).toList()

        assertTrue(
            listeners.any { it is PlatformEvents },
            "not registered via META-INF/services; found ${listeners.map { it::class.java.name }}",
        )
    }

    @Test
    fun `exposes a public no-arg constructor for ServiceLoader`() {
        // Kotlin defaulted constructor parameters make ServiceLoader fail on a host classpath
        // without kotlin-stdlib.
        val ctor = PlatformEvents::class.java.getConstructor()
        assertTrue(java.lang.reflect.Modifier.isPublic(ctor.modifiers))
    }

    @Test
    fun `no-arg construction does not require any dependency beyond the JUnit Platform`() {
        // Constructing through reflection the way ServiceLoader does, with no Kotlin types involved.
        val instance = PlatformEvents::class.java.getConstructor().newInstance()
        assertTrue(instance is org.junit.platform.launcher.TestExecutionListener)
    }
}

class ProbeReporterTest {

    @Test
    fun `appends a readable line per result`() {
        val target = File.createTempFile("yoriwake-probe", ".txt").also { it.delete() }
        try {
            ProbeReporter.ToFile(target).report(ProbeResult.reachable("0.8.12", 128))
            ProbeReporter.ToFile(target).report(ProbeResult.failed("nope"))

            val lines = target.readLines()
            assertEquals(2, lines.size)
            assertContains(lines[0], "REACHABLE jacoco=0.8.12 executionDataBytes=128")
            assertContains(lines[1], "FAILED nope")
        } finally {
            target.delete()
        }
    }

    @Test
    fun `no probe file is written unless the property names one, capture off or on`() {
        // The listener runs in every test JVM of a host build, so it must not write here.
        val formerDefault = File("build/yoriwake-agent-probe.txt").also { it.delete() }
        assertEquals(null, System.getProperty(CaptureAccess.PROBE_FILE_PROPERTY))
        val records = File.createTempFile("yoriwake-records", "").let { it.delete(); it.mkdirs(); it }
        try {
            val found = Supplier { AgentLookup.Result.found(object : JacocoAgent {
                override fun version() = "0.8.12"
                override fun takeExecutionData() = ByteArray(0)
            }) }
            PlatformEvents(found, ProbeReporter.ToFile(), null).probeAndReport()
            PlatformEvents(found, ProbeReporter.ToFile(), records).probeAndReport()

            assertFalse(formerDefault.exists(), "a probe file was written with no property set")
        } finally {
            records.deleteRecursively()
        }
    }

    @Test
    fun `an unwritable target does not surface to the caller`() {
        val dir = File.createTempFile("yoriwake-probe-dir", "").let { it.delete(); it.mkdirs(); it }
        try {
            ProbeReporter.ToFile(dir).report(ProbeResult.failed("x"))
        } finally {
            dir.delete()
        }
    }
}
