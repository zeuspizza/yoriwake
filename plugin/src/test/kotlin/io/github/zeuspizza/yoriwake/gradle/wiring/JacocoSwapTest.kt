package io.github.zeuspizza.yoriwake.gradle.wiring

import io.github.zeuspizza.yoriwake.gradle.bytecode.ClassFileSupport
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.testing.jacoco.plugins.JacocoPlugin
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** When the plugin's bundled JaCoCo agent stands in for Gradle's default. */
class JacocoSwapTest {

    @Test
    fun `the bundled agent is one the ceiling table lists`() {
        val bundled = assertNotNull(JacocoSwap.bundledVersion, "the plugin build bundles a JaCoCo agent")

        assertNull(ClassFileSupport.jacocoCeiling(bundled).note, "jacoco-java-support.tsv has no row for $bundled")
    }

    @Test
    fun `a test JVM above Gradle's default and within the bundled agent's reach gets the bundled agent`() {
        assertEquals("0.8.15", JacocoSwap.replacing("0.8.14", "0.8.14", 26, bundled = "0.8.15"))
    }

    @Test
    fun `a version the build chose is never replaced`() {
        assertNull(JacocoSwap.replacing("0.8.13", "0.8.14", 26, bundled = "0.8.15"))
        assertNull(JacocoSwap.replacing("0.8.16", "0.8.14", 26, bundled = "0.8.15"))
    }

    @Test
    fun `a test JVM the default covers, one above the bundled agent, or one unknown keeps the default`() {
        assertNull(JacocoSwap.replacing("0.8.14", "0.8.14", 25, bundled = "0.8.15"))
        assertNull(JacocoSwap.replacing("0.8.14", "0.8.14", 27, bundled = "0.8.15"))
        assertNull(JacocoSwap.replacing("0.8.14", "0.8.14", null, bundled = "0.8.15"))
    }

    @Test
    fun `a default the table does not know is not replaced`() {
        assertNull(JacocoSwap.replacing("0.9.0", "0.9.0", 26, bundled = "0.8.15"))
        assertNull(JacocoSwap.replacing("", "", 26, bundled = "0.8.15"))
    }

    @Test
    fun `with no stand-in, Gradle's default is read from the running Gradle's JaCoCo plugin`() {
        val project = ProjectBuilder.builder().build()

        assertEquals(JacocoPlugin.DEFAULT_JACOCO_VERSION, JacocoSwap.gradleDefault(project))
    }
}
