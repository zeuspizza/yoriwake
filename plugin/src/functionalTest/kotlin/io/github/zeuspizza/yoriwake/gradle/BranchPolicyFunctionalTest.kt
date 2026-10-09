package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.gradle.capture.CoverageDecoder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * `fullRunBranches`: on a listed branch a selecting run runs everything and records the map, so a
 * CI command can pass `-Pyoriwake.select` on every branch. Elsewhere nothing changes.
 */
class BranchPolicyFunctionalTest : FunctionalTestSupport() {

    private val bothTests = setOf("dev.sample.AlphaTest", "dev.sample.BetaTest")

    @Test
    fun `on a listed branch a selecting run runs everything and dates the map`(@TempDir dir: File) {
        captured(dir)
        changeBeta(dir)
        commit(dir, "change beta")

        val output = select(dir).output

        assertEquals(bothTests, ranTests(dir), output)
        assertEquals("full-run-branch", decisionNotes(dir)["refusal-kind"], output)
        assertEquals("full-run-branch", decisionNotes(dir)["declines"], output)
        assertContains(output, "on branch main")
        assertEquals(head(dir), stamp(dir), "the declined run did not date the map")
    }

    @Test
    fun `on a branch not listed the run narrows and names the branch`(@TempDir dir: File) {
        captured(dir)
        git(dir, "checkout", "-q", "-b", "feature/x")
        changeBeta(dir)
        commit(dir, "change beta")

        val output = select(dir).output

        assertEquals(setOf("dev.sample.BetaTest"), ranTests(dir), output)
        assertFalse("refusal-kind" in decisionNotes(dir), output)
        assertContains(output, "on branch feature/x")
    }

    @Test
    fun `a detached HEAD a listed remote branch contains declines, also once that branch moved on`(@TempDir dir: File) {
        captured(dir)
        changeBeta(dir)
        commit(dir, "change beta")
        git(dir, "remote", "add", "origin", "../nowhere")
        git(dir, "update-ref", "refs/remotes/origin/main", "HEAD")
        git(dir, "checkout", "-q", "--detach")
        git(dir, "branch", "-D", "main")

        val atTip = select(dir).output

        assertEquals(bothTests, ranTests(dir), atTip)
        assertEquals("full-run-branch", decisionNotes(dir)["refusal-kind"], atTip)
        assertContains(atTip, "origin/main")

        File(dir, "later.txt").writeText("later")
        commit(dir, "later")
        git(dir, "update-ref", "refs/remotes/origin/main", "HEAD")
        git(dir, "checkout", "-q", "--detach", "HEAD~1")

        val behind = select(dir).output

        assertEquals("full-run-branch", decisionNotes(dir)["refusal-kind"], behind)
    }

    @Test
    fun `a detached pull request merge commit selects and says no listed branch contains it`(@TempDir dir: File) {
        captured(dir)
        git(dir, "remote", "add", "origin", "../nowhere")
        git(dir, "update-ref", "refs/remotes/origin/main", "HEAD")
        git(dir, "checkout", "-q", "--detach")
        changeBeta(dir)
        commit(dir, "merge feature into main")
        git(dir, "update-ref", "refs/remotes/pull/1/merge", "HEAD")

        val output = select(dir).output

        assertEquals(setOf("dev.sample.BetaTest"), ranTests(dir), output)
        assertFalse("refusal-kind" in decisionNotes(dir), output)
        assertContains(output, "detached; no listed branch contains HEAD")
        assertFalse("git-unavailable" in output, output)
    }

    @Test
    fun `a star in an entry matches any characters`(@TempDir dir: File) {
        captured(dir, branches = "\"release/*\"")
        git(dir, "checkout", "-q", "-b", "release/1.2")
        changeBeta(dir)
        commit(dir, "change beta")

        val output = select(dir).output

        assertEquals(bothTests, ranTests(dir), output)
        assertEquals("full-run-branch", decisionNotes(dir)["refusal-kind"], output)
    }

    @Test
    fun `a branch git cannot read runs everything as undetermined, not as git unavailable`(@TempDir dir: File) {
        captured(dir)
        changeBeta(dir)
        commit(dir, "change beta")

        val output = select(dir, environment = failingBranch(dir)).output

        assertEquals(bothTests, ranTests(dir), output)
        assertEquals("decline-undetermined", decisionNotes(dir)["refusal-kind"], output)
        assertContains(output, "git rev-parse --symbolic-full-name HEAD")
        assertFalse("git-unavailable" in output, output)
    }

    @Test
    fun `an empty entry or a star alone fails the build naming the property`(@TempDir dir: File) {
        listOf("\"  \"", "\"*\"").forEachIndexed { i, entry ->
            val project = File(dir, "p$i").apply { mkdirs() }
            build(project, "build.gradle.kts" to buildWith(entry), oneClass, oneTest)

            val output = runner(project, "test").buildAndFail().output

            assertContains(output, "fullRunBranches")
        }
    }

    @Test
    fun `a marked commit on a listed branch is listed after the branch`(@TempDir dir: File) {
        captured(dir)
        changeBeta(dir)
        commit(dir, "change beta\n\nyoriwake: full")

        val output = select(dir).output

        assertEquals("full-run-branch", decisionNotes(dir)["refusal-kind"], output)
        assertEquals("full-run-branch,full-run-commit", decisionNotes(dir)["declines"], output)
    }

    @Test
    fun `on a listed branch isolated capture records with a JVM per test class`(@TempDir dir: File) {
        captured(dir)
        changeBeta(dir)
        commit(dir, "change beta")

        val output = select(dir, "-Pyoriwake.isolatedCapture").output

        assertEquals(bothTests, ranTests(dir), output)
        assertContains(output, "fresh test JVM per test class")
        assertEquals(head(dir), stamp(dir), output)
    }

    @Test
    fun `without -Pyoriwake_select a listed branch is an ordinary recording run`(@TempDir dir: File) {
        captured(dir)
        changeBeta(dir)
        commit(dir, "change beta")

        val output = runner(dir, "test").build().output

        assertFalse("full-run-branch" in output, output)
        assertFalse("on branch main" in output, output)
        assertEquals(head(dir), stamp(dir), output)
    }

    @Test
    fun `checking out a listed branch after a cached configuration declines`(@TempDir dir: File) {
        captured(dir)
        git(dir, "checkout", "-q", "-b", "feature/x")
        changeBeta(dir)
        commit(dir, "change beta")
        assertEquals(setOf("dev.sample.BetaTest"), ranTests(dir.also { select(it) }))

        git(dir, "checkout", "-q", "main")
        val output = select(dir).output

        assertEquals("full-run-branch", decisionNotes(dir)["refusal-kind"], output)
    }

    @Test
    fun `explain names a listed branch, and the branch it detected where it would select`(@TempDir dir: File) {
        captured(dir)
        changeBeta(dir)
        commit(dir, "change beta")

        val onMain = runner(dir, "yoriwakeExplainTest", "-Pyoriwake.base=HEAD").build().output

        assertContains(onMain, "would run everything")
        assertContains(File(mapDir(dir), YoriwakePlugin.EXPLANATION_FILE).readText(), "\"full-run-branch\"")

        git(dir, "checkout", "-q", "-b", "feature/x")
        val onFeature = runner(dir, "yoriwakeExplainTest", "-Pyoriwake.base=HEAD~1").build().output

        assertContains(onFeature, "on branch feature/x")
        assertContains(
            File(mapDir(dir), YoriwakePlugin.EXPLANATION_FILE).readText(),
            "\"branch\": \"on branch feature/x\"",
        )
    }

    private fun buildWith(branches: String) =
        minimalBuild + System.lineSeparator() + "yoriwake { fullRunBranches.add($branches) }"

    /** A committed two-class sample on `main`, listing [branches], whose map was captured at HEAD. */
    private fun captured(dir: File, branches: String = "\"main\"") {
        build(
            dir, "build.gradle.kts" to buildWith(branches),
            oneClass, oneTest, secondClass, secondTest, classOrderByName,
        )
        ignoreBuildOutputs(dir)
        git(dir, "init", "-q", "-b", "main")
        commit(dir, "base")
        runner(dir, "test").build()
    }

    private fun select(dir: File, vararg flags: String, environment: Map<String, String>? = null) =
        runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD", *flags)
            .let { r -> environment?.let { r.withEnvironment(it) } ?: r }
            .build()

    private fun mapDir(dir: File) = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)

    private fun stamp(dir: File) = File(mapDir(dir), CoverageDecoder.CAPTURE_COMMIT_FILE).readText().trim()

    private fun head(dir: File): String =
        ProcessBuilder("git", "rev-parse", "HEAD").directory(dir).start().inputStream.bufferedReader().readText().trim()

    /** A `git` first on PATH that cannot name the checked-out branch, and runs anything else as git. */
    private fun failingBranch(dir: File): Map<String, String> {
        val bin = File(dir.parentFile, "${dir.name}-bin").apply { mkdirs() }
        val real = System.getenv("PATH").split(File.pathSeparator).map { File(it, "git") }.first { it.canExecute() }
        File(bin, "git").apply {
            writeText(
                "#!/bin/sh\ncase \"\$*\" in *--symbolic-full-name*) exit 128;; esac\nexec ${real.absolutePath} \"\$@\"\n"
            )
            setExecutable(true)
        }
        return mapOf("PATH" to bin.absolutePath + File.pathSeparator + System.getenv("PATH"))
    }
}
