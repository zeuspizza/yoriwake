package io.github.zeuspizza.yoriwake.maven.breaking

import io.github.zeuspizza.yoriwake.gradle.breaking.Aim
import io.github.zeuspizza.yoriwake.gradle.breaking.Outcome
import io.github.zeuspizza.yoriwake.maven.FunctionalMaven
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One Maven attempt of each outcome, a decline among them, so the Maven helper is shown to tell
 * them apart as the Gradle one does. A helper that misread Surefire's reports or a decline would
 * call an attempt that could not fail held.
 */
class MavenAttemptSelfTest : MavenBreakAttempt() {

    /** Beta's behaviour changes, so BetaTest fails; AlphaTest never loads Beta. */
    private fun breakBeta(project: File) {
        File(project, "src/main/java/dev/sample/Beta.java").writeText(
            "package dev.sample;\npublic class Beta { public int thrice(int n) { return n * 3 + 1; } }",
        )
    }

    private val twoClasses = listOf(oneClass, oneTest, secondClass, secondTest)

    @Test
    fun `an attempt whose change does not fail the target under a full run is vacuous`(@TempDir dir: File) {
        val result = attempt(dir, "dev.sample.AlphaTest", Aim.Row("reaches-change"), twoClasses) { breakBeta(it) }

        assertEquals(Outcome.VACUOUS, result.outcome, result.why)
        assertContains(result.why, "does not fail the target under a full run")
    }

    @Test
    fun `an attempt that leaves no test out is vacuous`(@TempDir dir: File) {
        val result = attempt(dir, "dev.sample.AlphaTest", Aim.Row("reaches-change"), listOf(oneClass, oneTest)) {
            File(it, "src/main/java/dev/sample/Alpha.java").writeText(
                "package dev.sample;\npublic class Alpha { public int twice(int n) { return n * 2 + 1; } }",
            )
        }

        assertEquals(Outcome.VACUOUS, result.outcome, result.why)
        assertContains(result.why, "nothing left out")
    }

    @Test
    fun `an attempt a full run asked for holds by force, naming the kind`(@TempDir dir: File) {
        val result = attempt(
            dir, "dev.sample.BetaTest", Aim.Forced("full-run-requested"), twoClasses,
            select = selecting + "-Dyoriwake.fullRun",
        ) { breakBeta(it) }

        assertEquals(Outcome.HELD_BY_FORCE, result.outcome, result.why)
        assertContains(result.why, "full-run-requested")
    }

    @Test
    fun `a declined execution holds by force, naming the decline`(@TempDir dir: File) {
        val result = attempt(dir, "dev.sample.BetaTest", Aim.Forced("surefire-in-process"), twoClasses) { project ->
            File(project, "pom.xml").writeText(minimalPom.replace(surefireConfiguration, "<forkCount>0</forkCount>"))
            breakBeta(project)
        }

        assertEquals(Outcome.HELD_BY_FORCE, result.outcome, result.why)
        assertContains(result.why, "surefire-in-process")
    }

    @Test
    fun `an attempt that reaches the rule it aims at is held by rule`(@TempDir dir: File) {
        val result = attempt(dir, "dev.sample.BetaTest", Aim.Row("reaches-change"), twoClasses) { breakBeta(it) }

        assertEquals(Outcome.HELD_BY_RULE, result.outcome, result.why)
    }

    @Test
    fun `an attempt whose target ran by another rule than the one it aims at is off target`(@TempDir dir: File) {
        // BetaTest executes Beta itself, so ordinary coverage selects it before any JVM-wide rule.
        val result = attempt(dir, "dev.sample.BetaTest", Aim.Row("shares-jvm-changed-class"), twoClasses) { breakBeta(it) }

        assertEquals(Outcome.OFF_TARGET, result.outcome, result.why)
    }

    @Test
    fun `an attempt through a documented limit that skips its failing target is a gap`(@TempDir dir: File) {
        val result = attempt(dir, "dev.sample.ReaderTest", Aim.Row("reaches-change"), listOf(oneClass, oneTest, reader), copyingPom) {
            File(it, "src/main/java/dev/sample/Alpha.java").writeText(
                "package dev.sample;\npublic class Alpha { public int twice(int n) { return n + n; } }",
            )
        }

        assertEquals(Outcome.GAP, result.outcome, result.why)
    }

    @Test
    fun `a primer runs between the change and the selecting run`(@TempDir dir: File) {
        val order = mutableListOf<String>()

        val result = attempt(
            dir, "dev.sample.BetaTest", Aim.Row("reaches-change"), twoClasses,
            primer = Primer(selecting) { _, output ->
                assertContains(output, "selecting against ")
                order += "primer"
            },
        ) { project ->
            breakBeta(project)
            order += "change"
        }

        assertEquals(listOf("change", "primer"), order)
        assertEquals(Outcome.HELD_BY_RULE, result.outcome, result.why)
    }

    @Test
    fun `a committed change is still in the selecting run's change set`(@TempDir dir: File) {
        val result = attempt(dir, "dev.sample.BetaTest", Aim.Row("reaches-change"), twoClasses, commit = true) { breakBeta(it) }

        assertEquals(Outcome.HELD_BY_RULE, result.outcome, result.why)
        assertTrue("left out [dev.sample.AlphaTest]" in result.why, result.why)
    }

    @Test
    fun `a Maven below the floor holds by force, naming the decline`(@TempDir dir: File) {
        val result = attempt(
            dir, "dev.sample.BetaTest", Aim.Forced("maven-version"), twoClasses, maven = FunctionalMaven.BELOW_FLOOR,
        ) { breakBeta(it) }

        assertEquals(Outcome.HELD_BY_FORCE, result.outcome, result.why)
        assertContains(result.why, "maven-version")
    }

    @Test
    fun `a failing target in one module leaves the later modules running`(@TempDir dir: File) {
        // BetaTest, in the module built after the target's, reaches Alpha too, so selection keeps
        // every test: a later module that stopped with the target's failure would read as left out.
        val module = { name: String, dependency: String ->
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <parent><groupId>dev.sample</groupId><artifactId>sample</artifactId><version>1.0</version></parent>
              <artifactId>$name</artifactId>
              <dependencies>$dependency</dependencies>
            </project>
            """.trimIndent() + "\n"
        }
        val reactor = minimalPom.replace(
            "<version>1.0</version>\n",
            "<version>1.0</version>\n  <packaging>pom</packaging>\n  <modules><module>a</module><module>b</module></modules>\n",
        )
        val usesAlpha = "b/src/test/java/dev/sample/BetaTest.java" to """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertNotNull;
            class BetaTest {
                @Test void passes() { assertNotNull(new Alpha()); }
            }
        """.trimIndent()
        val sources = listOf(
            "a/pom.xml" to module("a", ""),
            "b/pom.xml" to module("b", "<dependency><groupId>dev.sample</groupId><artifactId>a</artifactId><version>1.0</version></dependency>"),
            "a/${oneClass.first}" to oneClass.second, "a/${oneTest.first}" to oneTest.second, usesAlpha,
        )

        val result = attempt(dir, "dev.sample.AlphaTest", Aim.Row("reaches-change"), sources, reactor) {
            File(it, "a/src/main/java/dev/sample/Alpha.java").writeText(
                "package dev.sample;\npublic class Alpha { public int twice(int n) { return n * 2 + 1; } }",
            )
        }

        assertEquals(Outcome.VACUOUS, result.outcome, result.why)
        assertContains(result.why, "nothing left out")
    }
}
