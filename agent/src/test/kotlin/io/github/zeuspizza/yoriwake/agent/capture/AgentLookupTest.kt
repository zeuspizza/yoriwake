package io.github.zeuspizza.yoriwake.agent.capture

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.AfterEach
import java.util.function.Supplier
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentLookupTest {

    @Test
    fun `reports NotOnClasspath when RT is absent from the given classloader`() {
        val isolated = object : ClassLoader(null) {}

        val result = AgentLookup.find(isolated)

        assertFalse(result.isFound)
        assertContains(result.reason().diagnostic(), "-javaagent")
    }

    @Test
    fun `diagnostic names the class it looked for`() {
        val result = AgentLookup.find(object : ClassLoader(null) {})

        assertContains(result.reason().diagnostic(), "org.jacoco.agent.rt.RT")
    }

    @Test
    fun `reports absence rather than throwing when the agent cannot be seen`() {
        // Absence is constructed, not assumed: this module's own coverage gate attaches JaCoCo.
        val nowhere = object : ClassLoader(null) {}

        assertFalse(AgentLookup.find(nowhere).isFound)
    }

    @Test
    fun `accessing the agent of a missing result fails loudly rather than returning null`() {
        val result = AgentLookup.find(object : ClassLoader(null) {})

        val error = runCatching { result.agent() }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
    }

    // Classes shaped like JaCoCo's, never the test JVM's own agent: a take through it would reset
    // the coverage this module's own gate reads.
    private val started = org.jacoco.agent.rt.internal_0a1.Agent::class.java
    private val neverStarted = org.jacoco.agent.rt.internal_0b2.Agent::class.java
    private val startsLater = org.jacoco.agent.rt.internal_0c3.Agent::class.java
    private val rtNotStarted = AgentLookup.Unavailable.notAttached(IllegalStateException("JaCoCo agent not started."))

    @AfterEach
    fun stopLateAgent() {
        org.jacoco.agent.rt.internal_0c3.Agent.started = false
    }

    private fun among(vararg loaded: Class<*>) =
        AgentLookup.startedAmong(Supplier { arrayOf(String::class.java, *loaded) }, rtNotStarted)

    @Test
    fun `with RT's runtime never started, the one started agent among the loaded classes is used`() {
        val result = among(neverStarted, started)

        assertTrue(result.isFound)
        assertEquals("0a1", result.agent().version())
        assertContentEquals(byteArrayOf(1), result.agent().takeExecutionData())
    }

    @Test
    fun `two started agents are refused, since coverage would split between them`() {
        org.jacoco.agent.rt.internal_0c3.Agent.started = true

        val result = among(started, startsLater)

        assertFalse(result.isFound)
        assertContains(result.reason().diagnostic(), "2 JaCoCo agents have started")
    }

    @Test
    fun `no started agent among the loaded classes keeps RT's answer and says none was found`() {
        val result = among(neverStarted, startsLater)

        assertFalse(result.isFound)
        assertContains(result.reason().diagnostic(), "JaCoCo agent not started")
        assertContains(result.reason().diagnostic(), "no other started JaCoCo agent")
    }

    @Test
    fun `an agent found this way names another that starts after it`() {
        val agent = among(started, startsLater).agent()
        assertNull(agent.anotherStarted())

        org.jacoco.agent.rt.internal_0c3.Agent.started = true

        val another = agent.anotherStarted()
        assertNotNull(another)
        assertContains(another, "0c3")
    }
}
