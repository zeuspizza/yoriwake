package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Above `largeChangeThreshold` changed paths a selecting run runs everything without analysing the
 * change, and records the map; at or below it, nothing changes.
 */
class LargeChangeFunctionalTest : FunctionalTestSupport() {

    private fun threshold(value: Int, extra: String = "") =
        minimalBuild + System.lineSeparator() + "yoriwake { largeChangeThreshold = $value\n$extra }"

    private fun mapDir(dir: File) = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)

    private fun captureStamp(dir: File) = File(mapDir(dir), AgentContract.CAPTURE_COMMIT_FILE).readText().trim()

    /** A committed two-class sample with a captured map. */
    private fun captured(dir: File, buildScript: String) {
        build(dir, "build.gradle.kts" to buildScript, oneClass, oneTest, secondClass, secondTest)
        committed(dir)
        runner(dir, "test").build()
        File(dir, "build/test-results").deleteRecursively()
    }

    private fun changeAlpha(dir: File) = File(dir, "src/main/java/dev/sample/Alpha.java").writeText(
        "package dev.sample;\npublic class Alpha { public int twice(int n) { return n + n; } }"
    )

    @Test
    fun `more changed paths than the threshold run everything and record the map`(@TempDir dir: File) {
        captured(dir, threshold(3))
        val before = captureStamp(dir)
        changeAlpha(dir)
        (1..3).forEach { File(dir, "notes/$it.txt").apply { parentFile.mkdirs() }.writeText("$it\n") }
        commit(dir, "four paths")

        val output = runner(dir, "test", "-Pyoriwake.select", "-P${Settings.BASE}=HEAD~1").build().output

        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir), output)
        val notes = decisionNotes(dir)
        assertEquals("large-change", notes[AgentContract.REFUSAL_KIND_NOTE], output)
        assertContains(output, "4 paths changed against HEAD~1, above the large-change threshold of 3")
        assertNotEquals(before, captureStamp(dir), "the refused run did not record the map")
    }

    @Test
    fun `as many changed paths as the threshold still select`(@TempDir dir: File) {
        captured(dir, threshold(1))
        changeAlpha(dir)

        val output = runner(dir, "test", "-Pyoriwake.select").build().output

        assertContains(output, ":test: narrowing")
        assertNotEquals("large-change", decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], output)
    }

    @Test
    fun `an old map does not push a small change over the threshold`(@TempDir dir: File) {
        captured(dir, threshold(3, """notReadByTests.add("notes/*")"""))
        (1..4).forEach { File(dir, "notes/$it.txt").apply { parentFile.mkdirs() }.writeText("$it\n") }
        commit(dir, "four paths since the capture")
        changeAlpha(dir)

        // Against HEAD one path changed; widened to the capture, five did.
        val output = runner(dir, "test", "-Pyoriwake.select", "-P${Settings.BASE}=HEAD").build().output

        assertNotEquals("large-change", decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], output)
        assertContains(output, ":test: narrowing")
    }

    @Test
    fun `yoriwakeExplain reports the same refusal`(@TempDir dir: File) {
        captured(dir, threshold(1))
        changeAlpha(dir)
        File(dir, "src/main/java/dev/sample/Beta.java").appendText("\n")

        val output = runner(dir, "yoriwakeExplainTest").build().output

        assertContains(output, "would run everything: 2 paths changed against")
        assertContains(File(mapDir(dir), "explain.json").readText(), "large-change")
    }

    @Test
    fun `a threshold below one fails the build naming it`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to threshold(0), oneClass, oneTest)

        val output = runner(dir, "test").buildAndFail().output

        assertContains(output, "largeChangeThreshold is 0")
    }
}
