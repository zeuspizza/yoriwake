package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.gradle.change.ChangeDetection
import io.github.zeuspizza.yoriwake.gradle.change.ChangeDetection.CommitScan
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommitScanTest {

    @Test
    fun `only a line that is exactly the marker asks for a full run, in any case and spacing`() {
        listOf("yoriwake: full", "Yoriwake: FULL", "yoriwake:full", "yoriwake :  FULL", "  yoriwake: full  ")
            .forEach { assertTrue(ChangeDetection.isFullRunMarker(it), it) }
        listOf("see yoriwake: full run docs", "yoriwake: full-run", "yoriwake: fully", "yoriwake full", "")
            .forEach { assertFalse(ChangeDetection.isFullRunMarker(it), it) }
    }

    @Test
    fun `a marker anywhere in the body of a commit since the base is found`(@TempDir dir: File) {
        val base = repo(dir)
        commit(dir, "first")
        commit(dir, "second\n\nSome context.\nYoriwake: FULL\nMore context.")
        commit(dir, "third")

        val scan = assertIs<CommitScan.Read>(ChangeDetection.scanCommitMessages(raw(dir), base))

        assertEquals("second", scan.marked?.subject)
        assertEquals(sha(dir, "HEAD~1"), scan.marked?.sha)
    }

    @Test
    fun `a commit at the base itself is not in the range`(@TempDir dir: File) {
        repo(dir, message = "base\n\nyoriwake: full")
        commit(dir, "after")

        val scan = assertIs<CommitScan.Read>(ChangeDetection.scanCommitMessages(raw(dir), sha(dir, "HEAD~1")))

        assertNull(scan.marked)
    }

    @Test
    fun `with no commit since the base, HEAD's own message is read`(@TempDir dir: File) {
        repo(dir, message = "base\n\nyoriwake: full")

        val scan = assertIs<CommitScan.Read>(ChangeDetection.scanCommitMessages(raw(dir), "HEAD"))

        assertEquals("base", scan.marked?.subject)
    }

    @Test
    fun `a merge commit in the range is read`(@TempDir dir: File) {
        val base = repo(dir)
        git(dir, "checkout", "-q", "-b", "side")
        commit(dir, "side work")
        git(dir, "checkout", "-q", "main")
        commit(dir, "main work")
        git(dir, "-c", "user.email=t@e.com", "-c", "user.name=t", "merge", "-q", "--no-ff", "side",
            "-m", "merge side\n\nyoriwake: full")

        val scan = assertIs<CommitScan.Read>(ChangeDetection.scanCommitMessages(raw(dir), base))

        assertEquals("merge side", scan.marked?.subject)
    }

    @Test
    fun `a yoriwake line that is not the marker is named as a hint, and asks for nothing`(@TempDir dir: File) {
        val base = repo(dir)
        commit(dir, "tweak\n\nyoriwake: full-run")

        val scan = assertIs<CommitScan.Read>(ChangeDetection.scanCommitMessages(raw(dir), base))

        assertNull(scan.marked)
        assertEquals(listOf("tweak"), scan.hints.map { it.subject })
    }

    @Test
    fun `git that cannot list the messages is a failed scan`() {
        val scan = ChangeDetection.scanCommitMessages({ null }, "abc1234")

        assertIs<CommitScan.Failed>(scan)
    }

    @Test
    fun `a base that git would read as an option is refused`() {
        assertThrows<IllegalArgumentException> { ChangeDetection.scanCommitMessages({ "" }, "--output=x") }
    }

    private fun raw(dir: File): (List<String>) -> String? = { ChangeDetection.rawGit(dir, it) }

    /** A repository on `main` with one commit; returns that commit. */
    private fun repo(dir: File, message: String = "base"): String {
        git(dir, "init", "-q", "-b", "main")
        commit(dir, message)
        return sha(dir, "HEAD")
    }

    private fun commit(dir: File, message: String) =
        git(dir, "-c", "user.email=t@e.com", "-c", "user.name=t", "commit", "-q", "--allow-empty", "-m", message)

    private fun sha(dir: File, ref: String) = ChangeDetection.rawGit(dir, listOf("rev-parse", ref))!!.trim()

    private fun git(dir: File, vararg args: String) {
        val exit = ProcessBuilder("git", *args).directory(dir).redirectErrorStream(true).start().waitFor()
        assertEquals(0, exit, "git ${args.joinToString(" ")}")
    }
}
