package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.gradle.capture.CoverageDecoder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A full run asked for on a selecting run: `-Pyoriwake.fullRun`, or a `yoriwake: full` line in a
 * commit message since the base. Either makes the run a recording run that says why.
 */
class FullRunRequestFunctionalTest : FunctionalTestSupport() {

    private val bothTests = setOf("dev.sample.AlphaTest", "dev.sample.BetaTest")

    @Test
    fun `a full run asked for on the command line runs everything and records the map`(@TempDir dir: File) {
        captured(dir)
        changeBeta(dir)
        commit(dir, "change beta")

        val output = select(dir, "-Pyoriwake.fullRun").output

        assertEquals(bothTests, ranTests(dir), output)
        assertEquals("full-run-requested", decisionNotes(dir)["refusal-kind"], output)
        assertEquals("full-run-requested", decisionNotes(dir)["declines"], output)
        assertContains(output, "-Pyoriwake.fullRun")
        assertEquals(head(dir), stamp(dir), "the declined run did not date the map")
    }

    @Test
    fun `a commit asking for a full run runs everything, records the map and names the commit`(@TempDir dir: File) {
        captured(dir)
        changeBeta(dir)
        commit(dir, "change beta")
        File(dir, "notes.txt").writeText("risky")
        commit(dir, "refactor the world\n\nYoriwake: FULL")

        val output = select(dir).output

        assertEquals(bothTests, ranTests(dir), output)
        assertEquals("full-run-commit", decisionNotes(dir)["refusal-kind"], output)
        assertContains(output, head(dir).take(12))
        assertContains(output, "refactor the world")
        assertEquals(head(dir), stamp(dir), "the declined run did not date the map")
    }

    @Test
    fun `no marker and no flag narrows as before`(@TempDir dir: File) {
        captured(dir)
        changeBeta(dir)
        commit(dir, "change beta\n\nsee yoriwake: full run docs")

        val output = select(dir).output

        assertEquals(setOf("dev.sample.BetaTest"), ranTests(dir), output)
        assertFalse("refusal-kind" in decisionNotes(dir), output)
    }

    @Test
    fun `a yoriwake line that is not the marker asks for nothing and says so`(@TempDir dir: File) {
        captured(dir)
        changeBeta(dir)
        commit(dir, "change beta\n\nyoriwake: full-run")

        val output = select(dir).output

        assertEquals(setOf("dev.sample.BetaTest"), ranTests(dir), output)
        assertContains(output, "yoriwake: full")
        assertContains(output, head(dir).take(12))
    }

    @Test
    fun `a marker on an older commit since the map's capture declines with the base at HEAD`(@TempDir dir: File) {
        captured(dir)
        File(dir, "a.txt").writeText("a")
        commit(dir, "first\n\nyoriwake: full")
        File(dir, "b.txt").writeText("b")
        commit(dir, "second")
        changeBeta(dir)
        commit(dir, "third")

        val output = select(dir).output

        assertEquals(bothTests, ranTests(dir), output)
        assertEquals("full-run-commit", decisionNotes(dir)["refusal-kind"], output)
    }

    @Test
    fun `a marker on HEAD declines when no branch base is found`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest, secondClass, secondTest, classOrderByName)
        ignoreBuildOutputs(dir)
        git(dir, "init", "-b", "work")
        commit(dir, "base")
        runner(dir, "test").build()
        changeBeta(dir)
        commit(dir, "change beta\n\nyoriwake: full")

        val output = runner(dir, "test", "-Pyoriwake.select").build().output

        assertEquals(bothTests, ranTests(dir), output)
        assertEquals("full-run-commit", decisionNotes(dir)["refusal-kind"], output)
    }

    @Test
    fun `git that cannot list the messages runs everything as undetermined, not as git unavailable`(@TempDir dir: File) {
        captured(dir)
        changeBeta(dir)
        commit(dir, "change beta")

        val output = select(dir, environment = failingLog(dir)).output

        assertEquals(bothTests, ranTests(dir), output)
        assertEquals("decline-undetermined", decisionNotes(dir)["refusal-kind"], output)
        assertContains(output, "git")
        assertFalse("git-unavailable" in output, output)
    }

    @Test
    fun `a run declined because git cannot list the messages still refuses in-JVM parallelism`(@TempDir dir: File) {
        captured(dir)
        val coverage = File(mapDir(dir), "coverage.tsv").readBytes()
        File(dir, "build.gradle.kts").appendText(
            "\ntasks.test { systemProperty(\"junit.jupiter.execution.parallel.enabled\", \"true\") }\n"
        )
        commit(dir, "parallel")

        val output = select(dir, environment = failingLog(dir)).output

        assertEquals(bothTests, ranTests(dir), output)
        assertEquals("in-jvm-parallelism", decisionNotes(dir)["refusal-kind"], output)
        assertTrue(coverage.contentEquals(File(mapDir(dir), "coverage.tsv").readBytes()), output)
    }

    @Test
    fun `a declined run with isolated capture records with a JVM per test class`(@TempDir dir: File) {
        captured(dir)
        changeBeta(dir)
        commit(dir, "change beta")

        val output = select(dir, "-Pyoriwake.fullRun", "-Pyoriwake.isolatedCapture").output

        assertEquals(bothTests, ranTests(dir), output)
        assertContains(output, "fresh test JVM per test class")
        assertEquals(head(dir), stamp(dir), output)
    }

    @Test
    fun `a declined run leaves a map recorded in isolation alone without isolated capture`(@TempDir dir: File) {
        captured(dir, "-Pyoriwake.isolatedCapture")
        val coverage = File(mapDir(dir), "coverage.tsv").readBytes()
        val before = stamp(dir)
        changeBeta(dir)
        commit(dir, "change beta")

        val output = select(dir, "-Pyoriwake.fullRun").output

        assertEquals(bothTests, ranTests(dir), output)
        assertTrue(coverage.contentEquals(File(mapDir(dir), "coverage.tsv").readBytes()), output)
        assertEquals(before, stamp(dir), output)
        assertFalse("recorded no coverage" in output, output)
    }

    @Test
    fun `the flag and a marked commit are both listed, the flag first`(@TempDir dir: File) {
        captured(dir)
        changeBeta(dir)
        commit(dir, "change beta\n\nyoriwake: full")

        val output = select(dir, "-Pyoriwake.fullRun").output

        assertEquals("full-run-requested", decisionNotes(dir)["refusal-kind"], output)
        assertEquals("full-run-requested,full-run-commit", decisionNotes(dir)["declines"], output)
    }

    @Test
    fun `tests named on a declined run run as named and are listed with the decline`(@TempDir dir: File) {
        captured(dir)
        changeBeta(dir)
        commit(dir, "change beta")

        val output = select(dir, "-Pyoriwake.fullRun", "--tests", "dev.sample.AlphaTest").output

        assertEquals(setOf("dev.sample.AlphaTest"), ranTests(dir), output)
        assertEquals("tests-named", decisionNotes(dir)["refusal-kind"], output)
        assertEquals("full-run-requested,tests-named", decisionNotes(dir)["declines"], output)
    }

    @Test
    fun `a marker on an older commit of a pinned base declines every run`(@TempDir dir: File) {
        captured(dir)
        val base = head(dir)
        changeBeta(dir)
        commit(dir, "change beta\n\nyoriwake: full")
        File(dir, "later.txt").writeText("later")
        commit(dir, "later")

        repeat(2) { round ->
            val output = runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=$base").build().output
            assertEquals("full-run-commit", decisionNotes(dir)["refusal-kind"], "run ${round + 1}: $output")
        }
    }

    @Test
    fun `explain reports a marked commit without -Pyoriwake_select`(@TempDir dir: File) {
        captured(dir)
        changeBeta(dir)
        commit(dir, "change beta\n\nyoriwake: full")

        val output = runner(dir, "yoriwakeExplainTest", "-Pyoriwake.base=HEAD").build().output

        assertContains(output, "would run everything")
        assertContains(File(mapDir(dir), YoriwakePlugin.EXPLANATION_FILE).readText(), "\"full-run-commit\"")
    }

    @Test
    fun `a commit made after a cached configuration is still read`(@TempDir dir: File) {
        captured(dir)
        changeBeta(dir)
        commit(dir, "change beta")
        assertEquals(setOf("dev.sample.BetaTest"), ranTests(dir.also { select(it) }))

        File(dir, "notes.txt").writeText("risky")
        commit(dir, "risky\n\nyoriwake: full")
        val output = select(dir).output

        assertEquals("full-run-commit", decisionNotes(dir)["refusal-kind"], output)
        assertEquals(bothTests, ranTests(dir), output)
    }

    @Test
    fun `a recording run reads no commit message and declines nothing`(@TempDir dir: File) {
        captured(dir)
        changeBeta(dir)
        commit(dir, "change beta\n\nyoriwake: full")

        val output = runner(dir, "test", "-Pyoriwake.base=HEAD").build().output

        assertFalse("full-run-commit" in output, output)
        assertFalse("refusal-kind" in decisionNotes(dir), output)
    }

    /** A committed two-class sample whose map was captured at HEAD. */
    private fun captured(dir: File, vararg flags: String) {
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest, secondClass, secondTest, classOrderByName)
        committed(dir)
        runner(dir, "test", *flags).build()
    }

    private fun select(dir: File, vararg flags: String, environment: Map<String, String>? = null) =
        runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD", *flags)
            .let { r -> environment?.let { r.withEnvironment(it) } ?: r }
            .build()

    private fun mapDir(dir: File) = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)

    private fun stamp(dir: File) = File(mapDir(dir), CoverageDecoder.CAPTURE_COMMIT_FILE).readText().trim()

    private fun head(dir: File): String =
        ProcessBuilder("git", "rev-parse", "HEAD").directory(dir).start().inputStream.bufferedReader().readText().trim()

    /** A `git` first on PATH whose `log` of commit messages fails, and that runs anything else as git. */
    private fun failingLog(dir: File): Map<String, String> {
        val bin = File(dir.parentFile, "${dir.name}-bin").apply { mkdirs() }
        val real = System.getenv("PATH").split(File.pathSeparator).map { File(it, "git") }.first { it.canExecute() }
        File(bin, "git").apply {
            writeText(
                "#!/bin/sh\ncase \"\$*\" in *%x1e*) exit 128;; esac\nexec ${real.absolutePath} \"\$@\"\n"
            )
            setExecutable(true)
        }
        return mapOf("PATH" to bin.absolutePath + File.pathSeparator + System.getenv("PATH"))
    }
}
