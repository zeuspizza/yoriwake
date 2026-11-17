package io.github.zeuspizza.yoriwake.maven

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertContains
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
        assertNull(UnionTrust.failsafeDoubt(false, "app exec-maven-plugin:exec", listOf(File("testcontainers-1.20.4.jar"))))
    }

    @Test
    fun `a Failsafe execution after only listed mojos, with no container launcher, is trusted`() {
        assertNull(UnionTrust.failsafeDoubt(true, null, listOf(File("junit-jupiter-5.11.4.jar"))))
    }

    @Test
    fun `a Failsafe execution after a mojo that may start a process is not trusted`() {
        assertContains(
            assertNotNull(UnionTrust.failsafeDoubt(true, "app exec-maven-plugin:exec", emptyList())),
            "app exec-maven-plugin:exec ran before it",
        )
    }

    @Test
    fun `a Failsafe execution with a container launcher on its classpath is not trusted`() {
        assertContains(
            assertNotNull(UnionTrust.failsafeDoubt(true, null, listOf(File("/repo/testcontainers-1.20.4.jar")))),
            "testcontainers-1.20.4.jar is on its classpath",
        )
    }

    @Test
    fun `JaCoCo's agent setup and the packaging plugins start no process`() {
        listOf("org.jacoco:jacoco-maven-plugin", "org.apache.maven.plugins:maven-jar-plugin", Settings.PLUGIN_KEY)
            .forEach { assertContains(UnionTrust.STARTS_NO_PROCESS, it) }
        listOf("org.codehaus.mojo:exec-maven-plugin", "org.apache.maven.plugins:maven-antrun-plugin")
            .forEach { assertEquals(false, it in UnionTrust.STARTS_NO_PROCESS, it) }
    }
}
