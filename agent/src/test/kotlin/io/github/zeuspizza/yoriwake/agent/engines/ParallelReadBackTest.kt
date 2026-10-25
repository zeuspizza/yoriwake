package io.github.zeuspizza.yoriwake.agent.engines

import org.junit.jupiter.api.Test
import java.util.Optional
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ParallelReadBackTest {

    private fun reading(vararg parameters: Pair<String, String>): String? {
        val map = parameters.toMap()
        return ParallelReadBack.unconfirmed { key: String -> Optional.ofNullable(map[key]) }
    }


    @Test
    fun `switches that are absent or off confirm one test at a time`() {
        assertNull(reading())
        assertNull(reading(ParallelReadBack.JUPITER to "false", ParallelReadBack.VINTAGE to "false", ParallelReadBack.TESTNG to "none"))
        assertNull(reading(ParallelReadBack.JUPITER to "yes"))
    }

    @Test
    fun `Jupiter's switch on is unconfirmed, whatever its case or spacing`() {
        assertContains(reading(ParallelReadBack.JUPITER to " TRUE ")!!, "JUnit Jupiter")
    }

    @Test
    fun `the vintage engine's switch on is unconfirmed, naming it`() {
        assertContains(reading(ParallelReadBack.VINTAGE to "true")!!, "Vintage")
    }

    @Test
    fun `any TestNG parallel mode but none is unconfirmed`() {
        assertContains(reading(ParallelReadBack.TESTNG to "methods")!!, "TestNG's parallel mode is methods")
        assertNotNull(reading(ParallelReadBack.TESTNG to "false"))
    }

    @Test
    fun `reads the plan's own configuration`() {
        assertNull(ParallelReadBack.unconfirmed(TestIdentifiers.emptyPlan(), true))
        assertContains(ParallelReadBack.unconfirmed(TestIdentifiers.emptyPlan(ParallelReadBack.JUPITER to "true"), false)!!, "Jupiter")
    }

    @Test
    fun `a configuration that cannot be read is unconfirmed only when the plugin asked`() {
        val tooOld = { throw NoSuchMethodError("getConfigurationParameters") }

        assertContains(ParallelReadBack.unconfirmed(tooOld, true)!!, "1.8 or later")
        assertNull(ParallelReadBack.unconfirmed(tooOld, false))

        val unreadable = { throw IllegalStateException("no configuration") }
        assertContains(ParallelReadBack.unconfirmed(unreadable, true)!!, "could not be read")
        assertNull(ParallelReadBack.unconfirmed(unreadable, false))
    }
}
