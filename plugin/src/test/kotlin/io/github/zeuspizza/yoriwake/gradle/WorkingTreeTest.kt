package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.gradle.change.RefusalKind
import io.github.zeuspizza.yoriwake.gradle.change.WorkingTree
import io.github.zeuspizza.yoriwake.gradle.report.percent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The snapshot of what HEAD does not pin: changes `git diff` from the capture stamp cannot see.
class WorkingTreeTest {

    private fun git(dir: File, vararg args: String) {
        val exit = ProcessBuilder("git", *args).directory(dir).redirectErrorStream(true)
            .start().waitFor()
        assertEquals(0, exit, "git ${args.joinToString(" ")} failed")
    }

    /** A repository at [repo] whose Gradle root is [root], with one committed source. */
    private fun repo(repo: File, root: File = repo): File {
        git(repo, "init", "--initial-branch=main")
        File(root, "src/main/java/A.java").also { it.parentFile.mkdirs() }.writeText("class A {}")
        File(root, ".gitignore").writeText("build/\n.gradle/\n*.local\n")
        git(repo, "add", ".")
        git(repo, "-c", "user.email=t@e.com", "-c", "user.name=t", "-c", "commit.gpgsign=false",
            "commit", "-m", "base")
        return root
    }

    /** A map directory holding a map, so [WorkingTree.drift] has something to vouch for. */
    private fun mapDir(root: File, snapshot: String?): File {
        val dir = File(root, ".gradle/yoriwake/test").also { it.mkdirs() }
        File(dir, AgentContract.MAP_SCHEMA_VERSION_FILE).writeText("1\n")
        snapshot?.let { File(dir, WorkingTree.SNAPSHOT_FILE).writeText(it) }
        return dir
    }

    private fun captured(root: File, excluded: List<String> = emptyList(), previous: String? = null) =
        assertNotNull(WorkingTree.capture(root, excluded, previous))

    private fun moved(root: File, snapshot: String, excluded: List<String> = emptyList()): Set<String> =
        assertIs<WorkingTree.Drift.Moved>(WorkingTree.drift(root, mapDir(root, snapshot), excluded)).paths

    @Test
    fun `a dirty edit captured and then reverted is reported`(@TempDir dir: File) {
        // The hole the stamp alone leaves: HEAD is the stamp, the revert makes the file equal HEAD
        // again, and `git diff` from the stamp is empty -- while the records describe the edit.
        val root = repo(dir)
        val source = File(root, "src/main/java/A.java")
        source.writeText("class A { int edited; }")
        val snapshot = captured(root).dated
        source.writeText("class A {}")

        assertEquals(setOf("src/main/java/A.java"), moved(root, snapshot))
    }

    @Test
    fun `an ignored file edited, added or removed is reported, and one left alone is not`(
        @TempDir dir: File,
    ) {
        val root = repo(dir)
        File(root, "edited.local").writeText("one")
        File(root, "removed.local").writeText("gone soon")
        File(root, "untouched.local").writeText("same")
        val snapshot = captured(root).dated

        File(root, "edited.local").writeText("two")
        File(root, "removed.local").delete()
        File(root, "added.local").writeText("new")
        // Rewritten with the same bytes: the stat moves, the content does not, and it must not
        // report -- or every regenerated ignored file forces every run.
        File(root, "untouched.local").writeText("same")

        assertEquals(setOf("added.local", "edited.local", "removed.local"), moved(root, snapshot))
    }

    @Test
    fun `build output and gradle state are not snapshotted`(@TempDir dir: File) {
        val root = repo(dir)
        File(root, "build/classes/A.class").also { it.parentFile.mkdirs() }.writeText("x")
        File(root, "sub/out/B.class").also { it.parentFile.mkdirs() }.writeText("x")
        File(root, ".gradle/8/lock").also { it.parentFile.mkdirs() }.writeText("x")
        File(root, "sub/.gradle/lock").also { it.parentFile.mkdirs() }.writeText("x")
        // The Kotlin Gradle plugin's own state: a session marker per live compiler daemon.
        File(root, ".kotlin/sessions/kotlin-compiler-1.salive").also { it.parentFile.mkdirs() }.writeText("")
        File(root, "sub/.kotlin/errors/errors-1.log").also { it.parentFile.mkdirs() }.writeText("x")
        File(root, "kept.local").writeText("x")
        File(root, ".gitignore").appendText("sub/\n.kotlin/\n")

        val listed = assertNotNull(WorkingTree.listing(root, listOf("build", "sub/out")))

        // The .gitignore edit is tracked-dirty; the rest is the one ignored file that is not output.
        assertEquals(setOf(".gitignore", "kept.local"), listed.toSet())
    }

    @Test
    fun `build directories are named relative to the root, and never as the whole tree`(
        @TempDir dir: File,
    ) {
        assertEquals(
            listOf("app/build", "build"),
            WorkingTree.excluded(
                dir,
                listOf(File(dir, "build").path, File(dir, "app/build").path, dir.path,
                    File(dir.parentFile, "elsewhere").path),
            ),
        )
    }

    @Test
    fun `a stat that vouches for a file is trusted, and a racy one is not`(@TempDir dir: File) {
        val root = repo(dir)
        val file = File(root, "fixture.local").also { it.writeText("content") }
        val real = assertIs<WorkingTree.Present>(WorkingTree.observe(root, "fixture.local", null))
        val planted = real.copy(sha = "0".repeat(64))

        // Recorded well before the snapshot, stat unchanged: reused without hashing.
        val settled = WorkingTree.Snapshot(real.mtimeMillis + 60_000, mapOf("fixture.local" to planted))
        assertEquals(planted, WorkingTree.observe(root, "fixture.local", settled))

        // Recorded in the same instant it was last written: an edit in that tick of the same size
        // leaves the stat as it was, so the stat proves nothing and the file is read again.
        val racy = WorkingTree.Snapshot(real.mtimeMillis, mapOf("fixture.local" to planted))
        assertEquals(real.sha, assertIs<WorkingTree.Present>(WorkingTree.observe(root, "fixture.local", racy)).sha)
        assertTrue(file.isFile)
    }

    @Test
    fun `a recapture reuses what the stat vouches for`(@TempDir dir: File) {
        val root = repo(dir)
        File(root, "fixture.local").writeText("content")
        val stat = assertIs<WorkingTree.Present>(WorkingTree.observe(root, "fixture.local", null))
        val previous = WorkingTree.render(
            WorkingTree.Snapshot(
                stat.mtimeMillis + 60_000,
                mapOf("fixture.local" to stat.copy(sha = "0".repeat(64))),
            )
        )

        val recaptured = assertNotNull(WorkingTree.parse(captured(root, previous = previous).dated))

        assertEquals("0".repeat(64), (recaptured.entries["fixture.local"] as WorkingTree.Present).sha)
    }

    @Test
    fun `a build in a subdirectory lists paths relative to itself and nothing outside it`(
        @TempDir dir: File,
    ) {
        // `git diff` prints repository-relative paths and `ls-files` cwd-relative ones; the change
        // set is relative to the Gradle root, so both listings must arrive that way.
        val root = File(dir, "backend").also { it.mkdirs() }
        repo(dir, root)
        File(dir, "outside.local").writeText("not this build's")
        val source = File(root, "src/main/java/A.java")
        source.writeText("class A { int edited; }")
        File(root, "src/test/resources/fixture.local").also { it.parentFile.mkdirs() }.writeText("one")
        val snapshot = captured(root).dated
        assertEquals(
            setOf("src/main/java/A.java", "src/test/resources/fixture.local"),
            assertNotNull(WorkingTree.parse(snapshot)).entries.keys,
        )

        source.writeText("class A {}")
        File(root, "src/test/resources/fixture.local").writeText("two")
        File(dir, "outside.local").writeText("still not")

        assertEquals(
            setOf("src/main/java/A.java", "src/test/resources/fixture.local"),
            moved(root, snapshot),
        )
    }

    @Test
    fun `a map with no snapshot, or a damaged one, refuses, and no map at all does not`(
        @TempDir dir: File,
    ) {
        val root = repo(dir)
        val absent = WorkingTree.drift(root, mapDir(root, snapshot = null), emptyList())
        assertEquals(RefusalKind.SNAPSHOT_ABSENT, assertIs<WorkingTree.Drift.Unknown>(absent).kind)

        val whole = captured(root).dated
        // Cut short: one line fewer still parses line by line, which is why the footer counts.
        val truncated = whole.lines().filterNot { it.startsWith("end") }.joinToString("\n")
        val damaged = WorkingTree.drift(root, mapDir(root, truncated), emptyList())
        assertEquals(RefusalKind.SNAPSHOT_ABSENT, assertIs<WorkingTree.Drift.Unknown>(damaged).kind)

        val noMap = File(dir, "nomap").also { it.mkdirs() }
        assertEquals(WorkingTree.Drift.Moved(emptySet()), WorkingTree.drift(root, noMap, emptyList()))
    }

    @Test
    fun `a git that cannot list the tree is no answer, not an empty one`(@TempDir dir: File) {
        val root = repo(dir)
        val snapshot = captured(root).dated

        assertEquals(
            WorkingTree.Drift.Unlisted,
            WorkingTree.drift(root, mapDir(root, snapshot), emptyList()) { null },
        )
        assertNull(WorkingTree.capture(root, emptyList(), null) { null })
    }

    @Test
    fun `a partial capture marks what it saw differently as unknown until a full one`(
        @TempDir dir: File,
    ) {
        // Its merged records saw THIS tree and the rest saw the previous one, so a path on which the
        // two differ cannot be vouched for in either state.
        val root = repo(dir)
        File(root, "fixture.local").writeText("one")
        File(root, "steady.local").writeText("same")
        val first = captured(root).dated

        File(root, "fixture.local").writeText("two")
        File(root, "new.local").writeText("new")
        val partial = assertNotNull(captured(root, previous = first).undated)
        val entries = assertNotNull(WorkingTree.parse(partial)).entries

        assertEquals(WorkingTree.Unknown, entries["fixture.local"])
        assertEquals(WorkingTree.Unknown, entries["new.local"])
        assertIs<WorkingTree.Present>(entries["steady.local"])
        // And an unknown path reports even once it returns to the state the older records saw.
        File(root, "fixture.local").writeText("one")
        assertContains(moved(root, partial), "fixture.local")
        assertFalse("steady.local" in moved(root, partial))
        // With no previous snapshot to amend, a partial capture has nothing to write.
        assertNull(captured(root).undated)
    }

    @Test
    fun `a path with a tab, a newline or a percent sign survives the file`(@TempDir dir: File) {
        val paths = listOf("a\tb.local", "c\nd.local", "e%09f.local", "g\rh.local")
        val snapshot = WorkingTree.Snapshot(1L, paths.associateWith { WorkingTree.Absent })

        assertEquals(paths.toSet(), assertNotNull(WorkingTree.parse(WorkingTree.render(snapshot))).entries.keys)
    }

    @Test
    fun `a drift crosses the value source intact`() {
        val drifts = listOf(
            WorkingTree.Drift.Moved(setOf("a.local", "b/c.local")),
            WorkingTree.Drift.Moved(emptySet()),
            WorkingTree.Drift.Unlisted,
            WorkingTree.Drift.Unknown(RefusalKind.SNAPSHOT_ABSENT, "why"),
        )
        drifts.forEach { assertEquals(it, WorkingTree.decode(WorkingTree.encode(it))) }
        assertEquals(WorkingTree.Drift.Unlisted, WorkingTree.decode(null))
        assertEquals(WorkingTree.Drift.Unlisted, WorkingTree.decode("garbage"))
    }
}
