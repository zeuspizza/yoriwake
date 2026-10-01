package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.gradle.change.ChangeDetection
import io.github.zeuspizza.yoriwake.gradle.facts.BuildMemo
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Asks git once per build rather than once per `Test` task. These tests check provider identity,
// not answers: git returns the same answer however often it is asked.
class BuildMemoTest {

    private fun memo(): BuildMemo = assertNotNull(BuildMemo.of(ProjectBuilder.builder().build()))

    private fun repo(prefix: String, body: (File, (Array<out String>) -> Unit) -> Unit) {
        val dir = java.nio.file.Files.createTempDirectory(prefix).toFile()
        try {
            body(dir) { args ->
                ProcessBuilder(listOf("git") + args)
                    .directory(dir).redirectErrorStream(true).start().waitFor()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `one key, one provider instance, however many callers ask`() {
        val memo = memo()
        var created = 0
        val create = { created++; ProjectBuilder.builder().build().providers.provider { "answer" } }

        val first = memo.provider("k", create)
        val second = memo.provider("k", create)

        assertSame(first, second, "the same question must reuse the same provider, or git re-runs")
        assertEquals(1, created, "the provider was created twice, so git will run twice")
    }

    @Test
    fun `a different question gets its own provider`() {
        // Collapsing distinct questions onto one answer is the dangerous direction: `rev-parse HEAD`
        // answering a `diff` would be a change set that is not this build's.
        val memo = memo()
        val create = { ProjectBuilder.builder().build().providers.provider { "answer" } }

        assertTrue(memo.provider("a", create) !== memo.provider("b", create))
    }

    @Test
    fun `every project in one build shares one memo, and another build gets its own`() {
        val root = ProjectBuilder.builder().build()
        val child = ProjectBuilder.builder().withParent(root).withName("child").build()

        assertSame(
            BuildMemo.of(root), BuildMemo.of(child),
            "a memo held per project is a memo per project, which is what this replaces",
        )
        // Gradle keeps plugin class objects across builds, statics included. A memo that survived
        // into the next build would answer it with the previous build's git.
        assertTrue(BuildMemo.of(root) !== BuildMemo.of(ProjectBuilder.builder().build()))
    }

    @Test
    fun `a derived value is computed once per build, and a null answer counts as an answer`() {
        val memo = memo()
        var computed = 0

        assertEquals("derived", memo.value("k") { computed++; "derived" })
        assertEquals("derived", memo.value("k") { computed++; "derived" })
        assertEquals(1, computed, "the value was derived twice")

        // `deriveScope` legitimately answers null; a memo that cannot hold null re-walks every
        // source file once per `Test` task.
        var nulls = 0
        assertNull(memo.value<String?>("empty") { nulls++; null })
        assertNull(memo.value<String?>("empty") { nulls++; null })
        assertEquals(1, nulls, "a memoised null was derived again")

        assertEquals("other", memo.value("other") { "other" })
    }

    @Test
    fun `a git failure is reported once and remembered forever`() {
        val memo = memo()
        assertFalse(memo.failed(), "nothing has failed yet")

        memo.recordFailure("git diff could not answer")
        memo.recordFailure("git rev-parse could not answer")

        assertTrue(memo.failed())
        assertEquals(
            "git diff could not answer", memo.failureToReport(),
            "the first failure is the one worth printing; the rest are its consequences",
        )
        // One line per build; `failed` still answers the audit, which asks afterwards.
        assertNull(memo.failureToReport())
        assertTrue(memo.failed())
    }

    @Test
    fun `a memoised question is asked once, and an unmemoised one is asked every time`() {
        // A memoised provider is frozen at its first answer, so a later commit is invisible through
        // it; seeing the new commit would mean git was asked again.
        repo("yoriwake-memo") { dir, git ->
            git(arrayOf("init"))
            File(dir, "a.txt").writeText("one")
            git(arrayOf("add", "."))
            git(arrayOf("-c", "user.email=t@e.com", "-c", "user.name=t", "commit", "-m", "first"))

            val providers = ProjectBuilder.builder().build().providers
            val memo = memo()
            val firstHead = assertNotNull(ChangeDetection.head(providers, dir, memo))

            File(dir, "a.txt").writeText("two")
            git(arrayOf("add", "."))
            git(arrayOf("-c", "user.email=t@e.com", "-c", "user.name=t", "commit", "-m", "second"))

            val fresh = assertNotNull(ChangeDetection.head(providers, dir))
            assertTrue(fresh != firstHead, "the second commit did not happen; this proves nothing")
            assertEquals(
                firstHead, ChangeDetection.head(providers, dir, memo),
                "the memo asked git a second time",
            )
        }
    }

    @Test
    fun `two checkouts asking the same question do not share an answer`() {
        // A composite build asks the same questions from another root; keyed on arguments alone,
        // the included build would get the root build's change set and could skip silently.
        val memo = memo()
        repo("yoriwake-one") { one, gitOne ->
            gitOne(arrayOf("init"))
            File(one, "a.txt").writeText("one")
            gitOne(arrayOf("add", "."))
            gitOne(arrayOf("-c", "user.email=t@e.com", "-c", "user.name=t", "commit", "-m", "one"))

            repo("yoriwake-two") { two, gitTwo ->
                gitTwo(arrayOf("init"))
                File(two, "b.txt").writeText("two")
                gitTwo(arrayOf("add", "."))
                gitTwo(arrayOf("-c", "user.email=t@e.com", "-c", "user.name=t", "commit", "-m", "two"))

                val providers = ProjectBuilder.builder().build().providers
                assertTrue(
                    ChangeDetection.head(providers, one, memo) !=
                        ChangeDetection.head(providers, two, memo),
                    "one checkout answered for the other",
                )
            }
        }
    }

    @Test
    fun `a directory that is not a repository still answers could-not-answer, memo or not`() {
        // A cache must not turn "could not answer" into "answered empty".
        val notARepo = java.nio.file.Files.createTempDirectory("yoriwake-not-a-repo").toFile()
        try {
            val providers = ProjectBuilder.builder().build().providers
            val memo = memo()
            assertNull(ChangeDetection.changedPaths(providers, notARepo, "HEAD", memo))
            assertNull(ChangeDetection.head(providers, notARepo, memo))
            assertTrue(memo.failed(), "a git that could not answer must be reportable")
        } finally {
            notARepo.deleteRecursively()
        }
    }
}
