package io.github.zeuspizza.yoriwake.gradle.breaking

import io.github.zeuspizza.yoriwake.gradle.FunctionalTestSupport
import java.io.File

/**
 * An attempt to make selection skip a test that should have run, and what became of it.
 *
 * Each attempt records a map of its fixture, applies one change that breaks its target test, runs
 * a selecting build, and classifies the run. An attempt that could not have failed proves nothing,
 * so [attempt] proves the attempt before it classifies: the change must fail the target when every
 * test runs, the selecting run must have left something out or recorded why it could not, and a
 * run that held must have held on the verdict the attempt aimed at, not by another route.
 */
abstract class BreakAttempt : FunctionalTestSupport() {

    /**
     * Builds [sources] with [buildScript], commits them, records the map and checks that [target]
     * (a test class holding one test method) passes; applies [change] to the working tree, runs a
     * selecting build, then a build with yoriwake disabled to learn whether the change fails the
     * target at all, and classifies what the selecting build did.
     */
    protected fun attempt(
        dir: File,
        target: String,
        aim: Aim,
        sources: List<Pair<String, String>>,
        buildScript: String = minimalBuild,
        change: (File) -> Unit,
    ): Result {
        build(dir, "build.gradle.kts" to buildScript, *sources.toTypedArray())
        // The Kotlin plugin keeps its session state in `.kotlin/` beside the build, as Gradle does in
        // `.gradle/`; left untracked, it is a changed file of its own that forces the run.
        ignoreBuildOutputs(dir)
        File(dir, ".gitignore").appendText(System.lineSeparator() + ".kotlin/")
        git(dir, "init")
        commit(dir, "base")
        runner(dir, "test").build()
        val captured = ranTests(dir)
        check(passed(dir, target) == true) { "$target did not pass when the map was recorded; ran $captured" }
        // Selection keeps or skips each method, but a result file speaks for the whole class: a
        // skipped method would hide behind a sibling that ran and passed.
        val targetCases = Regex("<testcase ").findAll(File(dir, "build/test-results/test/TEST-$target.xml").readText())
            .count()
        check(targetCases == 1) {
            "$target ran $targetCases test cases; a target holds one test method, so a skipped one cannot hide behind a sibling that ran"
        }

        File(dir, "build/test-results").deleteRecursively()
        change(dir)
        check(gitStatus(dir).isNotBlank()) { "the change left the working tree as it was" }

        runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD").run()
        val selected = ranTests(dir)
        val targetResult = passed(dir, target)
        val (notes, rows) = decisions(File(dir, ".gradle/yoriwake"), target)

        File(dir, "build/test-results").deleteRecursively()
        runner(dir, "test", "--rerun", "-Pyoriwake.disabled").run()
        val failsUnderFullRun = passed(dir, target) == false

        return classify(
            Observed(
                targetFailsUnderFullRun = failsUnderFullRun,
                targetRan = targetResult != null,
                targetFailed = targetResult == false,
                leftOut = captured - selected,
                forcedKinds = forcedKinds(notes),
                targetRows = rows,
            ),
            aim,
        )
    }

    /** Whether [testClass] passed (true), failed (false), or did not run (null), from its result file. */
    private fun passed(dir: File, testClass: String): Boolean? = passedIn(File(dir, "build/test-results/test/TEST-$testClass.xml"))

    private fun gitStatus(dir: File): String =
        ProcessBuilder("git", "status", "--porcelain").directory(dir).redirectErrorStream(true).start()
            .let { process -> process.inputStream.bufferedReader().readText().also { process.waitFor() } }
}
