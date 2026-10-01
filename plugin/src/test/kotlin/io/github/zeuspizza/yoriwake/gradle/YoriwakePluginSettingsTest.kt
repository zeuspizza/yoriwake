package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.gradle.wiring.JacocoScoping
import org.gradle.api.tasks.testing.Test as TestTask
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// `includeNoLocationClasses` makes a Robolectric suite attributable, but without excluding the
// JDK's reflection-accessor loaders it makes a suite that deserializes anything report zero tests.
// A real JaCoCo extension never refuses the exclusion, so the failing direction needs one that
// does; the functional test covers the real one.
class YoriwakePluginSettingsTest {

    private fun testTask(): TestTask =
        ProjectBuilder.builder().build().tasks.register("test", TestTask::class.java).get()

    /** JaCoCo's own extension, recording what it was told, optionally refusing the exclusion. */
    class RecordingExtension(task: TestTask, private val refuseExclusion: Boolean = false) :
        JacocoTaskExtension(task.project.objects, null, task) {
        var noLocation: Boolean? = null

        override fun setIncludeNoLocationClasses(includeNoLocationClasses: Boolean) {
            noLocation = includeNoLocationClasses
            super.setIncludeNoLocationClasses(includeNoLocationClasses)
        }

        override fun setExcludeClassLoaders(excludeClassLoaders: List<String>?) {
            check(!refuseExclusion) { "this JaCoCo cannot exclude class loaders" }
            super.setExcludeClassLoaders(excludeClassLoaders)
        }
    }

    @Test
    fun `the setting is applied when its containment holds`() {
        val test = testTask()
        val jacoco = RecordingExtension(test)

        JacocoScoping.applyNoLocationInstrumentation(test, jacoco)

        assertEquals(true, jacoco.noLocation)
        assertEquals(
            listOf(
                "sun.reflect.DelegatingClassLoader",
                "jdk.internal.reflect.DelegatingClassLoader",
            ),
            jacoco.excludeClassLoaders,
        )
    }

    @Test
    fun `the setting is NOT applied when its containment fails`() {
        // Applying it here would instrument the JDK's generated serialization accessors and lose
        // the whole suite's results; an unattributable Robolectric suite is the safe fallback.
        val test = testTask()
        val jacoco = RecordingExtension(test, refuseExclusion = true)

        JacocoScoping.applyNoLocationInstrumentation(test, jacoco)

        assertEquals(null, jacoco.noLocation, "the setting was applied with no containment in place")
    }

    @Test
    fun `a failed containment reports failure rather than throwing`() {
        val test = testTask()
        assertFalse(
            JacocoScoping.excludeReflectionAccessorClassLoaders(test, RecordingExtension(test, refuseExclusion = true))
        )
    }

    @Test
    fun `a host's own excluded loaders are kept and not duplicated`() {
        // Replacing the list would silently drop a loader the host chose to exclude.
        val test = testTask()
        val jacoco = RecordingExtension(test)
        jacoco.excludeClassLoaders = listOf("dev.host.OwnLoader", "sun.reflect.DelegatingClassLoader")

        assertTrue(JacocoScoping.excludeReflectionAccessorClassLoaders(test, jacoco))

        assertEquals(
            listOf(
                "dev.host.OwnLoader",
                "sun.reflect.DelegatingClassLoader",
                "jdk.internal.reflect.DelegatingClassLoader",
            ),
            jacoco.excludeClassLoaders,
        )
    }
}
