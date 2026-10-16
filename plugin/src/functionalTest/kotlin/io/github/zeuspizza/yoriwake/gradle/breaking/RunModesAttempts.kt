package io.github.zeuspizza.yoriwake.gradle.breaking

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Attempts on the declines of a run asked to select: tests named for the run, a full run asked for
 * with a flag, by a listed branch or in a commit message, and a decline whose condition git cannot
 * read. Each makes the run run everything, so each attempt is held only by that decline's kind.
 */
class RunModesAttempts : BreakAttempt() {

    private fun source(path: String, body: String) = path to body.trimIndent()

    private val codec = source(
        "src/main/java/dev/sample/Codec.java",
        """
        package dev.sample;
        public class Codec { public String encode(String s) { return new StringBuilder(s).reverse().toString(); } }
        """,
    )

    private val other = source(
        "src/main/java/dev/sample/Other.java",
        "package dev.sample;\npublic class Other { public int one() { return 1; } }",
    )

    private val first = source(
        "src/test/java/dev/sample/A0UnrelatedTest.java",
        """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class A0UnrelatedTest { @Test void runsFirst() { assertEquals(1, new Other().one()); } }
        """,
    )

    private val codecTest = source(
        "src/test/java/dev/sample/A9CodecTest.java",
        """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class A9CodecTest { @Test void reverses() { assertEquals("cba", new Codec().encode("abc")); } }
        """,
    )

    private val sample = listOf(codec, other, first, codecTest, classOrderByName)

    private fun changeCodec(dir: File) {
        val file = File(dir, codec.first)
        file.writeText(file.readText().replace("reverse()", "reverse().append(\"!\")"))
    }

    private fun git(dir: File, vararg args: String, input: String? = null) {
        val process = ProcessBuilder("git", "-c", "user.email=t@example.com", "-c", "user.name=t", *args)
            .directory(dir).redirectErrorStream(true).start()
        input?.let { text -> process.outputStream.use { it.write(text.toByteArray()) } }
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
    }

    private fun branchesBuild(entry: String) = minimalBuild + "\nyoriwake { fullRunBranches.add(\"$entry\") }\n"

    // Track: code reading. Boundary: the flag is read through Gradle's properties, where a bare
    // value turns a flag on. Here it is set in the project's gradle.properties, empty, rather than
    // passed on the command line.
    @Test
    fun `a full run asked for by an empty flag in gradle properties runs everything`(@TempDir dir: File) {
        val result = attempt(dir, "dev.sample.A9CodecTest", Aim.Forced("full-run-requested"), sample) { dir ->
            changeCodec(dir)
            File(dir, "gradle.properties").writeText("yoriwake.fullRun=\n")
        }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: on a detached HEAD the listed entries are matched against every
    // branch containing HEAD, and a star matches any characters. Here HEAD is detached behind the
    // tip of a branch two levels below the listed prefix, which moved on since.
    @Test
    fun `a detached HEAD behind a nested branch a starred entry lists runs everything`(@TempDir dir: File) {
        val result = attempt(
            dir, "dev.sample.A9CodecTest", Aim.Forced("full-run-branch"), sample, buildScript = branchesBuild("release/*"),
        ) { dir ->
            changeCodec(dir)
            git(dir, "checkout", "-q", "-b", "release/2.0/rc")
            git(dir, "add", ".")
            git(dir, "commit", "-q", "-m", "change codec")
            File(dir, "later.txt").writeText("later")
            git(dir, "add", ".")
            git(dir, "commit", "-q", "-m", "later")
            git(dir, "checkout", "-q", "--detach", "HEAD~1")
            File(dir, "notes.txt").writeText("not committed")
        }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: every commit since the base widened to the map's age is read,
    // merges' second parents included, and the marker is a whole line in any case and spacing. Here
    // it is the last trailer of a commit with CRLF line endings that only a merge brings in.
    @Test
    fun `a marker in a trailer of a commit a merge brought in runs everything`(@TempDir dir: File) {
        val result = attempt(dir, "dev.sample.A9CodecTest", Aim.Forced("full-run-commit"), sample) { dir ->
            git(dir, "checkout", "-q", "-b", "side")
            changeCodec(dir)
            git(dir, "add", ".")
            git(dir, "commit", "-q", "-F", "-", input = "change codec\r\n\r\nSigned-off-by: t <t@example.com>\r\n  Yoriwake :  FULL  \r\n")
            git(dir, "checkout", "-q", "-")
            File(dir, "notes.txt").writeText("notes")
            git(dir, "add", ".")
            git(dir, "commit", "-q", "-m", "notes")
            git(dir, "merge", "-q", "--no-ff", "-m", "merge side", "side")
            File(dir, "notes.txt").writeText("notes, edited")
        }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: the branch check reads HEAD's symbolic name and takes only
    // refs/heads as a branch and HEAD alone as detached; any other answer is git failing to say.
    // Here HEAD points at a remote-tracking ref of the listed branch, so whether a listed branch is
    // checked out cannot be read.
    @Test
    fun `HEAD pointing at a remote-tracking ref while a branch is listed runs everything as undetermined`(@TempDir dir: File) {
        val result = attempt(
            dir, "dev.sample.A9CodecTest", Aim.Forced("decline-undetermined"), sample, buildScript = branchesBuild("main"),
        ) { dir ->
            git(dir, "update-ref", "refs/remotes/origin/main", "HEAD")
            git(dir, "symbolic-ref", "HEAD", "refs/remotes/origin/main")
            changeCodec(dir)
        }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: patterns count as named for the run when they were added to the
    // filter after the plugin read it at the end of the project's evaluation. Here the build script
    // itself adds one once the task graph is ready, matching every test.
    @Test
    fun `a pattern the build script adds once the task graph is ready runs every test it matches`(@TempDir dir: File) {
        val result = attempt(dir, "dev.sample.A9CodecTest", Aim.Forced("tests-named"), sample) { dir ->
            changeCodec(dir)
            File(dir, "build.gradle.kts").appendText(
                "\ngradle.taskGraph.whenReady { allTasks.filterIsInstance<Test>().forEach { it.filter.includeTestsMatching(\"dev.sample.*\") } }\n",
            )
        }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_FORCE, result)
    }
}
