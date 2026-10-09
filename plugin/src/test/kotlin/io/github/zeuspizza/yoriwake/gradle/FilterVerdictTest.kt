package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.gradle.wiring.FilterVerdict
import io.github.zeuspizza.yoriwake.gradle.wiring.TestPatterns
import io.github.zeuspizza.yoriwake.gradle.wiring.decideFilterVerdict
import io.github.zeuspizza.yoriwake.gradle.wiring.readFilterVerdict
import org.gradle.api.tasks.testing.Test as TestTask
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which filters let a run date the map. A filter given for one run (`--tests`, patterns added after
 * the build script ran) never does; one the build script set on a JUnit Platform task does, as a
 * tag or engine filter does.
 */
class FilterVerdictTest {

    private val none = TestPatterns(emptySet(), emptySet())
    private val scriptExcludesAlpha = TestPatterns(emptySet(), setOf("*AlphaTest"))

    private fun verdict(
        unreadable: String? = null,
        commandLine: Set<String> = emptySet(),
        fromBuildScript: TestPatterns = none,
        live: TestPatterns = fromBuildScript,
        framework: String? = null,
        onPlatform: Boolean = true,
    ) = decideFilterVerdict(unreadable, commandLine, fromBuildScript, live, framework, onPlatform, "(whole task)")

    private fun assertUndated(verdict: FilterVerdict) {
        assertFalse(verdict.unfiltered, verdict.toString())
        assertFalse(verdict.datesTheMap(failFast = false), verdict.toString())
    }

    @Test
    fun `a filter that cannot be read leaves the map undated`() {
        val verdict = verdict(unreadable = "a.Filter", fromBuildScript = scriptExcludesAlpha)

        assertUndated(verdict)
        assertFalse(verdict.filterReadable)
        assertContains(verdict.detail, "a.Filter")
    }

    @Test
    fun `--tests leaves the map undated, whatever the build script also set`() {
        val verdict = verdict(commandLine = setOf("*BetaTest"), fromBuildScript = scriptExcludesAlpha)

        assertUndated(verdict)
        assertEquals("--tests [*BetaTest]", verdict.detail)
        assertEquals(setOf("*BetaTest"), verdict.commandLinePatterns)
    }

    @Test
    fun `a pattern added after the build script ran leaves the map undated`() {
        val verdict = verdict(live = TestPatterns(setOf("dev.sample.BetaTest"), emptySet()))

        assertUndated(verdict)
        assertEquals("filter.includePatterns=[dev.sample.BetaTest] added after the build script ran", verdict.detail)
    }

    @Test
    fun `a pattern added beside the build script's own counts as added`() {
        val verdict = verdict(
            fromBuildScript = scriptExcludesAlpha,
            live = TestPatterns(setOf("dev.sample.BetaTest"), setOf("*AlphaTest")),
        )

        assertUndated(verdict)
        assertContains(verdict.detail, "added after the build script ran")
    }

    @Test
    fun `a filter the build script set on a JUnit Platform task dates the map`() {
        val verdict = verdict(fromBuildScript = scriptExcludesAlpha)

        assertFalse(verdict.unfiltered)
        assertTrue(verdict.datesTheMap(failFast = false))
        assertEquals("filter.excludePatterns=[*AlphaTest] from the build script", verdict.detail)
    }

    @Test
    fun `a filter the build script set does not date the map off the JUnit Platform`() {
        assertUndated(verdict(fromBuildScript = scriptExcludesAlpha, onPlatform = false))
    }

    @Test
    fun `a build-script and a framework filter together date the map, and both are named`() {
        val verdict = verdict(fromBuildScript = scriptExcludesAlpha, framework = "excludeTags=[slow]")

        assertTrue(verdict.datesTheMap(failFast = false))
        assertEquals("filter.excludePatterns=[*AlphaTest] from the build script excludeTags=[slow]", verdict.detail)
    }

    @Test
    fun `a pattern removed after the build script ran leaves the rest the build script's`() {
        val verdict = verdict(
            fromBuildScript = TestPatterns(emptySet(), setOf("*AlphaTest", "*GammaTest")),
            live = scriptExcludesAlpha,
        )

        assertTrue(verdict.datesTheMap(failFast = false))
        assertContains(verdict.detail, "from the build script")
    }

    @Test
    fun `a framework filter dates the map`() {
        val verdict = verdict(framework = "includeTags=[fast]")

        assertTrue(verdict.byFramework)
        assertTrue(verdict.datesTheMap(failFast = false))
        assertEquals("includeTags=[fast]", verdict.detail)
    }

    @Test
    fun `no filter at all dates the map`() {
        val verdict = verdict()

        assertTrue(verdict.unfiltered)
        assertTrue(verdict.datesTheMap(failFast = false))
    }

    @Test
    fun `a fail-fast run never dates the map, whatever the filter`() {
        listOf(verdict(), verdict(framework = "includeTags=[fast]"), verdict(fromBuildScript = scriptExcludesAlpha))
            .forEach { assertFalse(it.datesTheMap(failFast = true), it.toString()) }
    }

    /** A real `Test` task on [framework] whose build script excludes AlphaTest by pattern. */
    private fun scriptFilteredTask(framework: TestTask.() -> Unit): TestTask {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("java")
        val test = project.tasks.named("test", TestTask::class.java).get()
        test.framework()
        test.filter.excludeTestsMatching("*AlphaTest")
        return test
    }

    private fun readAsConfigured(test: TestTask) =
        readFilterVerdict(test, TestPatterns(test.filter.includePatterns.toSet(), test.filter.excludePatterns.toSet()))

    @Test
    fun `a JUnit 4 task with a build-script filter leaves the map undated`() {
        assertUndated(readAsConfigured(scriptFilteredTask { useJUnit() }))
    }

    @Test
    fun `a TestNG task with a build-script filter leaves the map undated`() {
        assertUndated(readAsConfigured(scriptFilteredTask { useTestNG() }))
    }

    @Test
    fun `a JUnit Platform task with a build-script filter dates the map`() {
        assertTrue(readAsConfigured(scriptFilteredTask { useJUnitPlatform() }).datesTheMap(failFast = false))
    }
}
