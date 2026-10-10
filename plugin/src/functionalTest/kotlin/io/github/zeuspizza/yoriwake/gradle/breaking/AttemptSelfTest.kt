package io.github.zeuspizza.yoriwake.gradle.breaking

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * One attempt of each outcome, so the helper is shown to tell them apart. A helper that skipped a
 * check would call an attempt that could not fail held, and every attempt kept on it would read as
 * protection it is not.
 */
class AttemptSelfTest : BreakAttempt() {

    private val classOrder = classOrderByName

    /** Beta's behaviour changes, so BetaTest fails; AlphaTest runs before anything loads Beta. */
    private fun breakBeta(dir: File) {
        File(dir, "src/main/java/dev/sample/Beta.java").writeText(
            "package dev.sample;\npublic class Beta { public int thrice(int n) { return n * 3 + 1; } }",
        )
    }

    @Test
    fun `an attempt whose change does not fail the target under a full run is vacuous`(@TempDir dir: File) {
        // AlphaTest never reaches Beta: skipped, and rightly, since the change cannot fail it.
        val result = attempt(
            dir, "dev.sample.AlphaTest", Aim.Row("reaches-change"),
            listOf(oneClass, oneTest, secondClass, secondTest, classOrder),
        ) { breakBeta(it) }

        assertEquals(BreakAttempt.Outcome.VACUOUS, result.outcome, result.why)
        assertTrue("does not fail the target under a full run" in result.why, result.why)
    }

    @Test
    fun `an attempt that leaves no test out is vacuous`(@TempDir dir: File) {
        val result = attempt(dir, "dev.sample.AlphaTest", Aim.Row("reaches-change"), listOf(oneClass, oneTest)) {
            File(it, "src/main/java/dev/sample/Alpha.java").writeText(
                "package dev.sample;\npublic class Alpha { public int twice(int n) { return n * 2 + 1; } }",
            )
        }

        assertEquals(BreakAttempt.Outcome.VACUOUS, result.outcome, result.why)
        assertTrue("nothing left out" in result.why, result.why)
    }

    @Test
    fun `an attempt the build script forces is held by force, naming the kind`(@TempDir dir: File) {
        val factorBuild = minimalBuild.replace(
            "tasks.test { useJUnitPlatform() }",
            "tasks.test { useJUnitPlatform(); systemProperty(\"factor\", \"2\") }",
        )
        val readsFactor = "src/test/java/dev/sample/AlphaTest.java" to """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class AlphaTest {
                @Test void passes() { assertEquals(Integer.getInteger("factor").intValue(), new Alpha().twice(1)); }
            }
        """.trimIndent()

        val result = attempt(
            dir, "dev.sample.AlphaTest", Aim.Forced("unmappable-paths"),
            listOf(oneClass, readsFactor, secondClass, secondTest), buildScript = factorBuild,
        ) {
            File(it, "build.gradle.kts").writeText(factorBuild.replace("\"factor\", \"2\"", "\"factor\", \"3\""))
        }

        assertEquals(BreakAttempt.Outcome.HELD_BY_FORCE, result.outcome, result.why)
        assertTrue("unmappable-paths" in result.why, result.why)
    }

    @Test
    fun `an attempt that reaches the rule it aims at is held by rule`(@TempDir dir: File) {
        val result = attempt(
            dir, "dev.sample.BetaTest", Aim.Row("reaches-change"),
            listOf(oneClass, oneTest, secondClass, secondTest, classOrder),
        ) { breakBeta(it) }

        assertEquals(BreakAttempt.Outcome.HELD_BY_RULE, result.outcome, result.why)
    }

    @Test
    fun `an attempt whose target ran by another rule than the one it aims at is off target`(@TempDir dir: File) {
        // BetaTest executes Beta itself, so ordinary coverage selects it before any JVM-wide rule.
        val result = attempt(
            dir, "dev.sample.BetaTest", Aim.Row("shares-jvm-changed-class"),
            listOf(oneClass, oneTest, secondClass, secondTest, classOrder),
        ) { breakBeta(it) }

        assertEquals(BreakAttempt.Outcome.OFF_TARGET, result.outcome, result.why)
    }

    @Test
    fun `an attempt through a documented limit that skips its failing target is a gap`(@TempDir dir: File) {
        // A file under the build directory, off the test classpath, read by path, is build state:
        // never a change. A build step copies Alpha's source there, so the change to Alpha reaches
        // ReaderTest through it, and ReaderTest, alone in its JVM, never touches Alpha.
        val copyingBuild = minimalBuild.replace(
            "tasks.test { useJUnitPlatform() }",
            """
            val copyAlpha = tasks.register<Copy>("copyAlpha") {
                from("src/main/java/dev/sample/Alpha.java")
                rename { "alpha.txt" }
                into(layout.buildDirectory.dir("copied"))
            }
            tasks.test {
                useJUnitPlatform()
                forkEvery = 1
                dependsOn(copyAlpha)
                systemProperty("alpha.copy", layout.buildDirectory.file("copied/alpha.txt").get().asFile.absolutePath)
            }
            """.trimIndent(),
        )
        val reader = "src/test/java/dev/sample/ReaderTest.java" to """
            package dev.sample;
            import java.nio.file.Files;
            import java.nio.file.Paths;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertTrue;
            class ReaderTest {
                @Test void doubles() throws Exception {
                    assertTrue(Files.readString(Paths.get(System.getProperty("alpha.copy"))).contains("n * 2"));
                }
            }
        """.trimIndent()

        val result = attempt(
            dir, "dev.sample.ReaderTest", Aim.Row("reaches-change"),
            listOf(oneClass, oneTest, reader), buildScript = copyingBuild,
        ) {
            File(it, "src/main/java/dev/sample/Alpha.java").writeText(
                "package dev.sample;\npublic class Alpha { public int twice(int n) { return n + n; } }",
            )
        }

        assertEquals(BreakAttempt.Outcome.GAP, result.outcome, result.why)
    }

    @Test
    fun `a kept attempt that no longer reaches its rule fails asking to re-aim it`() {
        val forced = BreakAttempt.Result(BreakAttempt.Outcome.HELD_BY_FORCE, "recorded [unmappable-paths]")

        val failure = assertFailsWith<AssertionError> { assertOutcome(BreakAttempt.Outcome.HELD_BY_RULE, forced) }

        assertTrue("re-aim it" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `a kept attempt that became a gap fails as a skipped test`() {
        val gap = BreakAttempt.Result(BreakAttempt.Outcome.GAP, "the target was skipped")

        val failure = assertFailsWith<AssertionError> { assertOutcome(BreakAttempt.Outcome.HELD_BY_RULE, gap) }

        assertTrue("should have run was skipped" in failure.message.orEmpty(), failure.message)
    }
}
