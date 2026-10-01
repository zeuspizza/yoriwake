package io.github.zeuspizza.yoriwake.agent.capture

import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
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
}
