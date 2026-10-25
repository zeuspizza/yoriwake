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

    private val testngBuild = junit4Build
        .replace("""testImplementation("junit:junit:4.13.2")""", """testImplementation("org.testng:testng:7.10.2")""")
        .replace("useJUnit()", "useTestNG()")

    private val testngTests = arrayOf(
        "src/test/java/dev/sample/AlphaTest.java" to """
            package dev.sample;
            import org.testng.annotations.Test;
            import static org.testng.Assert.assertEquals;
            public class AlphaTest {
                @Test public void passes() { assertEquals(new Alpha().twice(1), 2); }
            }
        """.trimIndent(),
        "src/test/java/dev/sample/BetaTest.java" to """
            package dev.sample;
            import org.testng.annotations.Test;
            import static org.testng.Assert.assertEquals;
            public class BetaTest {
                @Test public void passes() { assertEquals(new Beta().thrice(1), 3); }
            }
        """.trimIndent(),
    )

    // One JVM per class, so no shared-JVM rule selects BetaTest and only running everything does.
    private fun retiredFlagRunsEverything(dir: File, buildScript: String, vararg tests: Pair<String, String>) {
        capturedWithAlphaChanged(dir, buildScript.replace("tasks.test {", "tasks.test { forkEvery = 1;"), *tests)
        File(dir, "build/test-results").deleteRecursively()

        val result = runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.internal.classSelection").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":test")?.outcome)
        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir))
        assertContains(result.output, ":test does not run on the JUnit Platform, so selection runs every test.")
    }

    @Test
    fun `a retired internal flag no longer narrows a plain JUnit 4 build`(@TempDir dir: File) {
        retiredFlagRunsEverything(dir, junit4Build, *junit4Tests)
    }

    @Test
    fun `a retired internal flag no longer narrows a plain TestNG build`(@TempDir dir: File) {
        retiredFlagRunsEverything(dir, testngBuild, *testngTests)
    }

    // Plain JUnit 4 and TestNG as 0.2 left them, pinned before a flag lets plain JUnit 4 narrow.

    private fun offPlatformSelectingRunsEverything(dir: File, buildScript: String, vararg tests: Pair<String, String>) {
        capturedWithAlphaChanged(dir, buildScript, *tests)
        File(dir, "build/test-results").deleteRecursively()

        val output = runner(dir, "test", "-Pyoriwake.select").build().output

        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir), output)
        assertContains(output, ":test does not run on the JUnit Platform, so selection runs every test.")
        val mapDir = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)
        assertFalse(File(mapDir, "decisions.tsv").exists(), "a plain runner wrote a decision record")
    }

    @Test
    fun `a selecting run on plain JUnit 4 runs every test and records no decisions`(@TempDir dir: File) {
        offPlatformSelectingRunsEverything(dir, junit4Build, *junit4Tests)
    }

    @Test
    fun `a selecting run on plain TestNG runs every test and records no decisions`(@TempDir dir: File) {
        offPlatformSelectingRunsEverything(dir, testngBuild, *testngTests)
    }

    /**
     * What Gradle reports for a class that reaches the JUnit 4 executor and whose methods a
     * method-level pattern empties: the baseline a class left out of a run must look like.
     */
    @Test
    fun `a plain JUnit 4 class a method pattern empties is reported as Gradle reports it`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to junit4Build, oneClass, secondClass, *junit4Tests)

        val result = runner(dir, "test", "--tests", "dev.sample.AlphaTest", "--tests", "dev.sample.BetaTest.noSuchMethod").build()

        assertEquals(setOf("dev.sample.AlphaTest"), ranTests(dir), result.output)
        assertEquals(TaskOutcome.SUCCESS, result.task(":test")?.outcome)
        // On Gradle 8.14 and 9.8 alike: no report entry, no failure, no initializationError.
        assertFalse(File(dir, "build/test-results/test/TEST-dev.sample.BetaTest.xml").exists(), result.output)
    }
}
