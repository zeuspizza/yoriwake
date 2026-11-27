package io.github.zeuspizza.yoriwake.maven

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** When a Maven execution's loaded-class union may be trusted, and under which filter. */
class UnionTrustTest {

    private fun filterOf(
        values: Map<String, String> = emptyMap(),
        lists: Map<String, List<String>> = emptyMap(),
        properties: Map<String, String> = emptyMap(),
    ) = UnionTrust.filterOf({ values[it] }, { lists[it].orEmpty() }, { null }, { properties[it] })

    @Test
    fun `an execution with no test filter has no union filter`() {
        assertNull(filterOf(properties = mapOf("it.test" to "AppIT")))
    }

    @Test
    fun `the same groups give the same union filter, and other groups another`() {
        val longRunning = assertNotNull(filterOf(values = mapOf("excludedGroups" to "long-running")))

        assertEquals(longRunning, filterOf(properties = mapOf("excludedGroups" to "long-running")))
        assertNotEquals(longRunning, filterOf(values = mapOf("excludedGroups" to "slow")))
        assertNotEquals(longRunning, filterOf(values = mapOf("groups" to "long-running")))
        assertNotEquals(
            longRunning,
            filterOf(values = mapOf("excludedGroups" to "long-running"), lists = mapOf("excludeJUnit5Engines" to listOf("junit-vintage"))),
        )
    }

    @Test
    fun `a Surefire execution is never in doubt`() {
        assertNull(UnionTrust.failsafeDoubt(false))
    }

    @Test
    fun `a Failsafe execution is always in doubt, since its tests may drive an application another JVM runs`() {
        assertEquals(
            "integration tests commonly drive an application another JVM runs, which no union sees",
            UnionTrust.failsafeDoubt(true),
        )
    }
}
