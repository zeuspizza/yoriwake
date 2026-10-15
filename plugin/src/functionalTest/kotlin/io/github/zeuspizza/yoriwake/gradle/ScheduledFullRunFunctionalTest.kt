package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.gradle.capture.Schedule
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `fullRunEveryBuilds` and `fullRunEveryDays`: past either, a selecting run runs every test, says
 * why and records the map where it may, and running every test starts the count again.
 */
class ScheduledFullRunFunctionalTest : FunctionalTestSupport() {

    private val bothTests = setOf("dev.sample.AlphaTest", "dev.sample.BetaTest")

    private fun captured(dir: File, limits: String, vararg flags: String) {
        build(
            dir, "build.gradle.kts" to "$minimalBuild\nyoriwake { $limits }",
            oneClass, oneTest, secondClass, secondTest, classOrderByName,
        )
        committed(dir)
        runner(dir, "test", *flags).build()
    }

    private var edits = 0

    /** Changes Beta to another equivalent body each time, so every selecting run has work to do. */
    private fun selectAfterAChange(dir: File, vararg flags: String): String {
        edits++
        File(dir, "src/main/java/dev/sample/Beta.java").writeText(
            """
            package dev.sample;
            public class Beta { public int thrice(int n) { return n * 3 + ${edits} - ${edits}; } }
            """.trimIndent()
        )
        File(dir, "build/test-results").deleteRecursively()
        return runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD", *flags).build().output
    }

    private fun mapDir(dir: File) = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)

    private fun count(dir: File) = File(mapDir(dir), Schedule.COUNT_FILE).readText().trim()

    @Test
    fun `the Nth build after a full run runs every test and starts the count again`(@TempDir dir: File) {
        captured(dir, "fullRunEveryBuilds = 3")
        assertEquals("0", count(dir))

        selectAfterAChange(dir)
        assertEquals(setOf("dev.sample.BetaTest"), ranTests(dir))
        selectAfterAChange(dir)
        assertEquals(setOf("dev.sample.BetaTest"), ranTests(dir))
        assertEquals("2", count(dir))

        val output = selectAfterAChange(dir)
        assertEquals(bothTests, ranTests(dir), output)
        assertEquals("full-run-scheduled", decisionNotes(dir)["refusal-kind"], output)
        assertContains(output, "3 builds since the last full run")
        assertEquals("0", count(dir))

        selectAfterAChange(dir)
        assertEquals(setOf("dev.sample.BetaTest"), ranTests(dir))
    }

    @Test
    fun `D days after the last full run a selecting run runs every test`(@TempDir dir: File) {
        captured(dir, "fullRunEveryDays = 7")
        val time = File(mapDir(dir), Schedule.TIME_FILE)

        time.writeText(Instant.now().minus(Duration.ofDays(6)).toString())
        selectAfterAChange(dir)
        assertEquals(setOf("dev.sample.BetaTest"), ranTests(dir))

        time.writeText(Instant.now().minus(Duration.ofDays(8)).toString())
        val output = selectAfterAChange(dir)
        assertEquals(bothTests, ranTests(dir), output)
        assertContains(output, "8 days since the last full run")
    }

    @Test
    fun `a count that cannot be read runs every test and says so`(@TempDir dir: File) {
        captured(dir, "fullRunEveryBuilds = 20")
        File(mapDir(dir), Schedule.COUNT_FILE).writeText("x")

        val output = selectAfterAChange(dir)

        assertEquals(bothTests, ranTests(dir), output)
        assertContains(output, Schedule.COUNT_FILE)
        assertEquals("0", count(dir), "the full run rewrites the count")
    }

    @Test
    fun `with no limit set nothing is read or written`(@TempDir dir: File) {
        captured(dir, "")
        selectAfterAChange(dir)

        assertEquals(setOf("dev.sample.BetaTest"), ranTests(dir))
        assertFalse(File(mapDir(dir), Schedule.COUNT_FILE).exists())
        assertFalse(File(mapDir(dir), Schedule.TIME_FILE).exists())
    }

    @Test
    fun `a limit below one fails the build naming it`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to "$minimalBuild\nyoriwake { fullRunEveryBuilds = 0 }", oneClass, oneTest)

        val output = runner(dir, "test").buildAndFail().output

        assertContains(output, "fullRunEveryBuilds is 0")
    }

    @Test
    fun `a reused configuration still notices the schedule falling due`(@TempDir dir: File) {
        captured(dir, "fullRunEveryBuilds = 2")
        selectAfterAChange(dir)
        assertEquals("1", count(dir))

        // The same invocation, which would reuse its entry if the verdict were not re-obtained.
        File(dir, "build/test-results").deleteRecursively()
        val output = runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD", "--rerun").build().output

        assertEquals(bothTests, ranTests(dir), output)
        assertEquals("full-run-scheduled", decisionNotes(dir)["refusal-kind"], output)
    }

    @Test
    fun `a run between two builds that are not due reuses the configuration`(@TempDir dir: File) {
        captured(dir, "fullRunEveryBuilds = 5")
        selectAfterAChange(dir)
        File(dir, "build/test-results").deleteRecursively()
        runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD", "--rerun").build()
        File(dir, "build/test-results").deleteRecursively()

        val output = runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD", "--rerun").build().output

        assertContains(output, "Reusing configuration cache")
    }

    @Test
    fun `explain says how far away the next full run is, and reports it once due`(@TempDir dir: File) {
        captured(dir, "fullRunEveryBuilds = 20")
        File(mapDir(dir), Schedule.COUNT_FILE).writeText("16")

        assertContains(runner(dir, "yoriwakeExplainTest").build().output, "full run due in 3 builds")

        File(mapDir(dir), Schedule.COUNT_FILE).writeText("19")
        val output = runner(dir, "yoriwakeExplainTest").build().output
        assertContains(output, "20 builds since the last full run")
        assertContains(File(mapDir(dir), "explain.json").readText(), "\"refusalKind\": \"full-run-scheduled\"")
    }

    @Test
    fun `a due run on a map recorded in isolation leaves the map alone and starts the count again`(@TempDir dir: File) {
        captured(dir, "fullRunEveryBuilds = 2", "-Pyoriwake.isolatedCapture")
        selectAfterAChange(dir)
        assertEquals("1", count(dir))

        val output = selectAfterAChange(dir)

        assertEquals(bothTests, ranTests(dir), output)
        assertFalse("recorded no coverage" in output, output)
        assertEquals("0", count(dir))
        assertTrue("isolatedCapture" in output, output)
    }
}
