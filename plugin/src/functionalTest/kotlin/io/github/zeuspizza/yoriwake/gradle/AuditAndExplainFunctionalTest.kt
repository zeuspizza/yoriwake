package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.gradle.capture.CoverageDecoder
import io.github.zeuspizza.yoriwake.gradle.report.Audit
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `yoriwakeAudit`, `yoriwakeExplain` and the decision record a run leaves. */
class AuditAndExplainFunctionalTest : FunctionalTestSupport() {

    @Test
    fun `a build whose git cannot answer says so in its audit`(@TempDir dir: File) {
        // The audit is where somebody looks afterwards to ask why nothing narrowed. Asked in the
        // same invocation as the selecting run, because the record of the failure lives for one
        // build.
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest)

        val output = runner(dir, "yoriwakeAuditTest", "-Pyoriwake.select").build().output

        assertContains(output, "git-unavailable")
        val mapDir = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)
        assertContains(File(mapDir, "audit.json").readText(), "git-unavailable")
    }

    @Test
    fun `a run nobody asked to select records that, and names no refusal`(@TempDir dir: File) {
        // The other side of the same distinction. A capture-only run is not a refusal, and a reader
        // that cannot tell them apart cannot count either.
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest)
        ignoreBuildOutputs(dir)
        git(dir, "init")
        git(dir, "add", ".")
        git(dir, "-c", "user.email=t@e.com", "-c", "user.name=t", "commit", "-m", "base")

        runner(dir, "test", "-Pyoriwake.internal.loaded").build()

        val notes = decisionNotes(dir)
        assertEquals("selection-not-requested", notes["outcome"])
        assertFalse("refusal-kind" in notes, "a run nobody asked about refused nothing")
    }

    /**
     * `yoriwakeExplain` answers, and answers again from the configuration cache.
     *
     * Capturing the `Test` task in its own action would print a correct answer and then fail the
     * build under the cache. A cache problem surfaces when the entry is stored, and the reuse
     * proves the stored entry was serialisable.
     */
    @Test
    fun `yoriwakeExplain reports a decision, twice, without failing the build`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest, secondClass, secondTest)
        ignoreBuildOutputs(dir)
        git(dir, "init")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit",
            "--allow-empty", "-m", "base")
        git(dir, "add", ".")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-m", "sample")

        runner(dir, "test", "-Pyoriwake.internal.loaded").build()

        File(dir, "src/main/java/dev/sample/Alpha.java").writeText(
            """
            package dev.sample;
            public class Alpha { public int twice(int n) { return n + n; } }
            """.trimIndent()
        )

        val first = runner(dir, "yoriwakeExplainTest").build().output
        assertContains(first, "[yoriwake] :test vs")
        assertContains(first, "would run")

        // And as something other than prose: the console line is for a person, this file is the
        // contract an agent or a script reads.
        val mapDir = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)
        val explanation = File(mapDir, CoverageDecoder.let { "explain.json" })
        assertTrue(explanation.isFile, "yoriwakeExplain wrote no machine-readable decision")
        val json = explanation.readText()
        assertContains(json, "\"fullRun\": false")
        assertContains(json, "\"selected\":")
        assertContains(json, "\"known\":")

        // Again, from the stored entry, which is where a configuration-cache defect surfaces.
        val second = runner(dir, "yoriwakeExplainTest").build().output
        assertContains(second, "would run")
        assertContains(second, "Configuration cache entry reused")
    }

    /**
     * `yoriwakeAudit` answers, and answers again from the configuration cache.
     *
     * Same reason `yoriwakeExplain` earns this test: the cache refuses a task action that reaches
     * back into the project, and the failure surfaces when the entry is stored, so it is the second
     * invocation that proves the first one was serialisable.
     */
    @Test
    fun `yoriwakeAudit reports a distribution, twice, without failing the build`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest, secondClass, secondTest)
        ignoreBuildOutputs(dir)
        git(dir, "init")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit",
            "--allow-empty", "-m", "base")
        git(dir, "add", ".")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-m", "sample")

        runner(dir, "test", "-Pyoriwake.internal.loaded").build()

        val first = runner(dir, "yoriwakeAuditTest").build().output
        assertContains(first, "NARROWING ONLY")
        assertContains(first, "reaches a median")
        // The half it must never imply. The toll is not derivable from a map.
        assertContains(first, "UNMEASURED")

        val mapDir = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)
        val json = File(mapDir, "audit.json")
        assertTrue(json.isFile, "yoriwakeAudit wrote no machine-readable verdict")
        assertContains(json.readText(), "\"medianShare\"")

        val second = runner(dir, "yoriwakeAuditTest").build().output
        assertContains(second, "NARROWING ONLY")
        assertContains(second, "Configuration cache entry reused")
    }

    /**
     * The decision record: why each test ran, as a file rather than as console prose.
     *
     * `SelectionFilter` computes the reason and JUnit surfaces it nowhere a script can read, so
     * `.github/assert_selection.py` could see what ran but never why anything did not.
     *
     * Only a real run can prove this: the file is written by a shutdown hook, in the host's test
     * JVM, by an agent found through ServiceLoader.
     */
    @Test
    fun `a selecting run records why every test was included or excluded`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest, secondClass, secondTest, classOrderByName)
        ignoreBuildOutputs(dir)
        git(dir, "init")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit",
            "--allow-empty", "-m", "base")
        git(dir, "add", ".")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-m", "sample")

        runner(dir, "test").build()
        File(dir, "build/test-results").deleteRecursively()
        // Beta, whose test runs last: AlphaTest ran before Beta was loaded, so it is excluded.
        changeBeta(dir)

        runner(dir, "test", "-Pyoriwake.select").build()

        val mapDir = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)
        val decisions = File(mapDir, AgentContract.DECISIONS_FILE)
        assertTrue(decisions.isFile, "no decision record was written")

        val rows = decisions.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split("\t") }
        assertTrue(rows.isNotEmpty(), decisions.readText())
        assertTrue(rows.all { it.size == 3 }, "a row was not three fields: $rows")

        // The question the result XML cannot answer: AlphaTest did not run, and here is why.
        val alpha = rows.single { it[0].contains("AlphaTest") }
        assertEquals("excluded", alpha[1], alpha.toString())
        assertEquals("SKIPPED", alpha[2], alpha.toString())

        // And the one that did run carries a reason token, not prose.
        val beta = rows.single { it[0].contains("BetaTest") }
        assertEquals("included", beta[1], beta.toString())
        assertTrue(beta[2].all { it.isUpperCase() || it == '_' }, "not a token: ${beta[2]}")
    }

    /**
     * A run that was never asked to select still says so, per test.
     *
     * Without this the file exists only for selecting runs, and an adopter comparing "before I
     * turned it on" against "after" has nothing to compare against -- which is exactly when someone
     * most wants to see what the tool would have done.
     */
    @Test
    fun `a capture-only run records that selection was not requested`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest)
        ignoreBuildOutputs(dir)

        runner(dir, "test").build()

        val mapDir = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)
        val rows = File(mapDir, AgentContract.DECISIONS_FILE).readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue(rows.isNotEmpty(), "a capture-only run recorded nothing")
        assertTrue(
            rows.all { it.endsWith("\tincluded\tSELECTION_NOT_REQUESTED") },
            "a capture-only run must include everything, and say why: $rows",
        )
    }

    /**
     * The default never runs the suite, and this is asserted rather than assumed.
     *
     * `yoriwakeAudit` reads a map; `-Pyoriwake.audit.measureToll` times two full runs of the host's
     * suite, which would be a serious surprise in somebody else's build. The flag is read at
     * configuration time, so without it the timed runs are unreachable, and this test holds that.
     */
    @Test
    fun `yoriwakeAudit does not run the suite unless the toll is asked for`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest)
        ignoreBuildOutputs(dir)
        git(dir, "init")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit",
            "--allow-empty", "-m", "base")
        git(dir, "add", ".")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-m", "sample")

        runner(dir, "test", "-Pyoriwake.internal.loaded").build()
        val result = runner(dir, "yoriwakeAuditTest").build()

        assertFalse(result.output.contains("timing the"), result.output)
        // The test task is not even in the graph. A task that merely finished up-to-date would
        // still mean the audit had asked for it.
        assertEquals(null, result.task(":test"))
        assertContains(result.output, "UNMEASURED")
    }

    /**
     * Asking for the toll without supplying it refuses, and names the timing it is missing.
     *
     * The task cannot time the runs itself: a nested build on this project would wait for file
     * locks this build is holding. So the timings arrive from `scripts/measure-toll.sh`, and asking
     * for a verdict without them fails loudly rather than giving a map-only answer a payback label.
     */
    @Test
    fun `the toll refuses when no timings were supplied`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest)
        ignoreBuildOutputs(dir)
        git(dir, "init")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit",
            "--allow-empty", "-m", "base")
        git(dir, "add", ".")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-m", "sample")
        runner(dir, "test", "-Pyoriwake.internal.loaded").build()

        val output = runner(dir, "yoriwakeAuditTest", "-Pyoriwake.audit.measureToll").build().output

        assertContains(output, "NO CONCLUSION")
        assertContains(output, "instrumented")
        assertContains(output, "measure-toll.sh")
    }

    /**
     * Supplied timings well clear of the floor produce a verdict, with the arithmetic shown.
     */
    @Test
    fun `the toll concludes from supplied timings`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest, secondClass, secondTest)
        ignoreBuildOutputs(dir)
        git(dir, "init")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit",
            "--allow-empty", "-m", "base")
        git(dir, "add", ".")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-m", "sample")
        runner(dir, "test", "-Pyoriwake.internal.loaded").build()

        val output = runner(
            dir, "yoriwakeAuditTest", "-Pyoriwake.audit.measureToll",
            "-Pyoriwake.audit.instrumentedSeconds=260", "-Pyoriwake.audit.uninstrumentedSeconds=100",
        ).build().output

        // A 160% toll cannot be repaid by any suite.
        assertContains(output, "160.0%")
        assertContains(output, "DO NOT ENABLE")
        // And the suite was still not run by the audit itself.
        assertFalse(output.contains("timing the"), output)
    }

    @Test
    fun `the payback verdict names both axes from one real build`(@TempDir dir: File) {
        // The whole chain on a real build: recorded durations, the decoded worker count and this
        // build's measured configure cost, none of which a unit test can produce. It also executes
        // the code behind the flag, not only the flag-absent path.
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest, secondClass, secondTest)
        ignoreBuildOutputs(dir)
        runner(dir, "test").build()

        val output = runner(
            dir, "yoriwakeAuditTest", "-Pyoriwake.audit.measureToll",
            // A 30% toll, above the noise floor and small enough that a suite could repay it.
            "-Pyoriwake.audit.instrumentedSeconds=13", "-Pyoriwake.audit.uninstrumentedSeconds=10",
        ).build().output

        assertContains(output, "BREAK-EVEN:")
        assertContains(output, "of configure cost per `Test` task when this build last configured")
        // The optimism is stated where the number is, not in a footnote somebody scrolls past.
        assertContains(output, "Both figures are optimistic in the same direction")
    }

    @Test
    fun `a narrowing run captures nothing, so it cannot restamp the worker count`(
        @TempDir dir: File,
    ) {
        // The divisor cannot drift: `captureDecision` refuses to instrument a narrowing run, so a
        // run that decodes anything ran everything. Otherwise a two-fork selecting run could stamp
        // its count beside an eight-fork capture's durations. If that ever changes, this fails.
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest, secondClass, secondTest)
        ignoreBuildOutputs(dir)
        git(dir, "init")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit",
            "--allow-empty", "-m", "base")
        git(dir, "add", ".")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-m", "sample")
        // `-Pyoriwake.internal.loaded` so the capture records the loaded-class union: without it a
        // class the map has never seen forces a full run, and the "selecting" run below would
        // execute everything -- which dates the map legitimately and proves nothing about the
        // guard.
        runner(dir, "test", "-Pyoriwake.internal.loaded").build()
        val facts = dir.walkTopDown().first { it.name == Audit.TaskFacts.FILE }
        // A capture that ran wider than the selecting run below ever will.
        facts.writeText(
            facts.readLines().map { if (it.startsWith("workers=")) "workers=8" else it }
                .joinToString("\n", postfix = "\n")
        )

        // A change that reaches one of the two tests, so the selecting run executes a strict
        // subset. Running everything would legitimately restamp the count, and the guard would pass
        // for an unrelated reason.
        File(dir, "src/main/java/dev/sample/Unrelated.java").writeText(
            """
            package dev.sample;
            public class Unrelated { public int n() { return 1; } }
            """.trimIndent()
        )

        val narrowed = runner(dir, "test", "-Pyoriwake.select").build().output

        assertTrue("skipped=" in narrowed, "the selecting run executed everything")
        assertFalse(
            narrowed.contains("map updated"),
            "a narrowing run captured, so a partial map could restate the worker count",
        )
        assertTrue(
            "workers=8" in facts.readText(),
            "a narrower run restamped the divisor beside a wider capture's durations",
        )
    }

    @Test
    fun `the payback verdict refuses by name when the map predates the worker count`(
        @TempDir dir: File,
    ) {
        // An adopter upgrading the plugin has a cached map with durations and no worker count, and
        // a summed figure cannot be turned into wall clock without one. It must say which input is
        // missing, or "cannot say" is not actionable.
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest, secondClass, secondTest)
        ignoreBuildOutputs(dir)
        runner(dir, "test").build()
        // Exactly what an older map looks like: the fact recorded by a plugin that did not have it.
        val facts = dir.walkTopDown().first { it.name == Audit.TaskFacts.FILE }
        facts.writeText(facts.readLines().filterNot { it.startsWith("workers=") }.joinToString("\n"))

        val output = runner(
            dir, "yoriwakeAuditTest", "-Pyoriwake.audit.measureToll",
            "-Pyoriwake.audit.instrumentedSeconds=13", "-Pyoriwake.audit.uninstrumentedSeconds=10",
        ).build().output

        assertContains(output, "PAYBACK NOT ANSWERED")
        assertContains(output, "how many workers")
        assertFalse(output.contains("BREAK-EVEN:"), "a verdict was reached without the divisor")
    }

    /**
     * Before any capture, the honest answer is a refusal that names the next thing to type.
     *
     * This is the state every adopter is in on their first run, so it is the one most likely to be
     * seen and the one least likely to be tested.
     */
    @Test
    fun `yoriwakeAudit with no map refuses and names the task that would build one`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest)
        ignoreBuildOutputs(dir)

        val output = runner(dir, "yoriwakeAuditTest").build().output

        assertContains(output, "NO CONCLUSION")
        assertContains(output, "no map")
        assertContains(output, "Run `test` once")
        // Never a share. An absent map is not a suite of which nothing narrows.
        assertFalse(output.contains("reaches a median"), output)
    }

    /**
     * A task the plugin declined is a task with no map, no agent and no selection.
     *
     * `yoriwakeAudit` is registered there and `yoriwakeExplain` is not. `yoriwakeExplain` answers
     * "what would selection do about this change", which needs a map that will never exist here.
     * `yoriwakeAudit` answers "what is stopping you", and the decline is the answer.
     *
     * The audit must still not describe a map that will never exist, nor say to capture one.
     */
    @Test
    fun `yoriwakeAudit on a task the plugin declined names the decline and promises nothing`(
        @TempDir dir: File,
    ) {
        build(
            dir,
            "build.gradle.kts" to minimalBuild,
            // No sources at all, so no packages are derived and the scope is never applied.
            "src/test/resources/keep.txt" to "",
        )
        ignoreBuildOutputs(dir)

        assertFalse(runner(dir, "tasks", "--all").build().output.contains("yoriwakeExplainTest"))

        val output = runner(dir, "yoriwakeAuditTest").build().output

        assertContains(output, "NO CONCLUSION")
        assertContains(output, "scope-not-applied")
        assertContains(output, "no packages were found")
        // The advice that would send a reader round a loop that cannot terminate.
        assertFalse(output.contains("Run `test` once"), output)
        // Never a share: there is no map to describe.
        assertFalse(output.contains("reaches a median"), output)
    }

    /**
     * The task explains a full run by naming the class that caused it, not just that one happened.
     *
     * "Why did it run everything?" is the first question a host build asks, and the answer has to
     * survive being read by someone who was not here when it was written.
     */
    @Test
    fun `yoriwakeExplain names the reason when it would run everything`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest)
        ignoreBuildOutputs(dir)
        git(dir, "init")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit",
            "--allow-empty", "-m", "base")
        git(dir, "add", ".")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-m", "sample")

        runner(dir, "test", "-Pyoriwake.internal.loaded").build()

        // A build script is a build input: it changes what compiles, so it must keep forcing.
        File(dir, "build.gradle.kts").appendText(System.lineSeparator() + "// touched")

        val output = runner(dir, "yoriwakeExplainTest").build().output

        assertContains(output, "would run everything")
        assertContains(output, "build.gradle.kts")

        val mapDir = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)
        val json = File(mapDir, "explain.json").readText()
        assertContains(json, "\"fullRun\": true")
        assertContains(json, "build.gradle.kts")
    }
}
