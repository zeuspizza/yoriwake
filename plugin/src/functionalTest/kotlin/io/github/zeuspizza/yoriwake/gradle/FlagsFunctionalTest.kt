package io.github.zeuspizza.yoriwake.gradle

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The public flags and how a build sets them. */
class FlagsFunctionalTest : FunctionalTestSupport() {

    /**
     * `-Pyoriwake.disabled` is `enabled = false` from a command line.
     *
     * The command line is what a user can reach while finding out what this plugin costs them. It
     * is also the uninstrumented run of the toll measurement, so it has to mean "did nothing at
     * all", not "decided not to capture this time".
     */
    @Test
    fun `yoriwake disabled beats a build script that enables it explicitly`(@TempDir dir: File) {
        // A convention would lose here, and quietly: the uninstrumented timing would still be
        // instrumented, the two would come out nearly equal, and the audit would refuse for the
        // wrong reason. A command-line switch outranks a build script's stated default.
        build(
            dir,
            "build.gradle.kts" to (
                minimalBuild + System.lineSeparator() +
                    "yoriwake { enabled.set(true) }"
                ),
            oneClass, oneTest,
        )
        ignoreBuildOutputs(dir)

        val result = runner(dir, "test", "-Pyoriwake.disabled").build()

        assertContains(result.output, "disabled; not configured")
        assertEquals(TaskOutcome.SUCCESS, result.task(":test")!!.outcome)
        // No map directory: nothing was scoped, injected or captured. This is also the line
        // scripts/measure-toll.sh greps for before it trusts its uninstrumented run.
        assertFalse(File(dir, ".gradle/yoriwake").isDirectory, "a disabled build still wrote a map")
    }

    @Test
    fun `yoriwake disabled from a build script's own afterEvaluate is honoured`(@TempDir dir: File) {
        // The latest a build script can say it: the plugin reads `enabled` once every script has run.
        build(
            dir,
            "build.gradle.kts" to (
                minimalBuild + System.lineSeparator() + "afterEvaluate { yoriwake { enabled.set(false) } }"
                ),
            oneClass, oneTest,
        )
        ignoreBuildOutputs(dir)

        val output = runner(dir, "test").build().output

        assertContains(output, "disabled; not configured")
        assertFalse(File(dir, ".gradle/yoriwake").isDirectory, "a disabled build still wrote a map")
    }

    @Test
    fun `yoriwake select honours its value, true selects and false runs everything`(
        @TempDir dir: File,
    ) {
        capturedWithAlphaChanged(dir, minimalBuild, oneTest, secondTest, classOrderByName)

        // BetaTest ran after Alpha was first loaded, so both run either way; the decision record
        // is what tells selecting from not selecting.
        File(dir, "build/test-results").deleteRecursively()
        runner(dir, "test", "-Pyoriwake.select=true").build()
        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir))
        assertEquals("narrowed", decisionNotes(dir)["outcome"])

        File(dir, "build/test-results").deleteRecursively()
        runner(dir, "test", "-Pyoriwake.select=false").build()
        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir))
        assertEquals("selection-not-requested", decisionNotes(dir)["outcome"])
    }

    @Test
    fun `a flag value that is not a boolean fails the build and names the flag`(
        @TempDir dir: File,
    ) {
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest)

        val output = runner(dir, "test", "-Pyoriwake.select=yes").buildAndFail().output

        assertContains(output, "-Pyoriwake.select=yes is not a boolean")
    }

    @Test
    fun `yoriwake disabled set to false in gradle properties leaves the plugin active`(
        @TempDir dir: File,
    ) {
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest)
        File(dir, "gradle.properties").writeText("yoriwake.disabled=false\n")

        val output = runner(dir, "test").build().output

        assertFalse(output.contains("disabled; not configured"), "=false disabled the plugin")
        assertContains(output, "scope=derived(dev.sample.*)")
    }

    @Test
    fun `a flag under its old public name no longer has any effect`(@TempDir dir: File) {
        capturedWithAlphaChanged(dir, junit4Build, *junit4Tests)

        File(dir, "build/test-results").deleteRecursively()
        runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.classSelection").build()

        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir))
    }
}
