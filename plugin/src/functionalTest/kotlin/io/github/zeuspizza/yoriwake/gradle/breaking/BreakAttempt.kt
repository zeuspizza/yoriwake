package io.github.zeuspizza.yoriwake.gradle.breaking

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.gradle.FunctionalTestSupport
import java.io.File
import kotlin.test.fail

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

    enum class Outcome {
        /** The attempt could not have failed: re-aim it. */
        VACUOUS,

        /** The target did not run, and the change fails it: a test that should have run was skipped. */
        GAP,

        /** Every test ran, and the run recorded the refusal or full-run kind that forced it. */
        HELD_BY_FORCE,

        /** The target ran and failed, and at least one other test was left out. */
        HELD_BY_RULE,

        /** It held, but not on the verdict it aimed at, so it says nothing about that boundary: re-aim it. */
        OFF_TARGET,
    }

    /** The verdict an attempt aims at. */
    sealed class Aim {
        /** The target's row: a row reason (`SHARES_JVM_WITH_CHANGE`) or a row rule (`shares-jvm-changed-class`). */
        data class Row(val token: String) : Aim()

        /** What forces the run: a refusal kind (`constant-changed`) or a full-run kind (`unmappable-paths`). */
        data class Forced(val kind: String) : Aim()
    }

    class Result(val outcome: Outcome, val why: String) {
        override fun toString() = "$outcome: $why"
    }

    /** What the builds of one attempt showed, before anything is classified. */
    class Observed(
        val targetFailsUnderFullRun: Boolean,
        val targetRan: Boolean,
        val targetFailed: Boolean,
        val leftOut: Set<String>,
        /** The run's refusal kind, its full-run kinds, and its full-run kind, as the decision record notes them. */
        val forcedKinds: List<String>,
        /** Each of the target's rows, as its reason and the rules its rule line names. */
        val targetRows: List<Pair<String, Set<String>>>,
    )

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
        committed(dir)
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
        val (notes, rows) = decisions(dir, target)

        File(dir, "build/test-results").deleteRecursively()
        runner(dir, "test", "--rerun", "-Pyoriwake.disabled").run()
        val failsUnderFullRun = passed(dir, target) == false

        val forced = notes.flatMap { note ->
            listOfNotNull(note[AgentContract.REFUSAL_KIND_NOTE], note[AgentContract.FULL_RUN_KIND_NOTE]) +
                note[AgentContract.FORCING_KINDS_NOTE].orEmpty().split(',')
        }.filter { it.isNotBlank() && it != AgentContract.RULE_NONE }.distinct()
        return classify(
            Observed(
                targetFailsUnderFullRun = failsUnderFullRun,
                targetRan = targetResult != null,
                targetFailed = targetResult == false,
                leftOut = captured - selected,
                forcedKinds = forced,
                targetRows = rows,
            ),
            aim,
        )
    }

    /** The outcome of one attempt from what its builds showed. The first check that answers wins. */
    fun classify(observed: Observed, aim: Aim): Result {
        if (!observed.targetFailsUnderFullRun) {
            return Result(Outcome.VACUOUS, "the change does not fail the target under a full run, so no selection could miss it")
        }
        if (observed.leftOut.isEmpty() && observed.forcedKinds.isEmpty()) {
            return Result(Outcome.VACUOUS, "nothing left out; re-aim")
        }
        if (!observed.targetRan) {
            return Result(Outcome.GAP, "the target was skipped and the change fails it; left out ${observed.leftOut}")
        }
        val held = when {
            observed.leftOut.isEmpty() -> Outcome.HELD_BY_FORCE
            observed.targetFailed -> Outcome.HELD_BY_RULE
            else -> return Result(Outcome.VACUOUS, "the target ran and passed under selection; re-aim")
        }
        val recorded = if (held == Outcome.HELD_BY_FORCE) observed.forcedKinds.toString() else observed.targetRows.toString()
        if (!onTarget(observed, aim, held)) {
            return Result(Outcome.OFF_TARGET, "off target; re-aim it: aimed at $aim, recorded $recorded")
        }
        return Result(held, "recorded $recorded; left out ${observed.leftOut}")
    }

    private fun onTarget(observed: Observed, aim: Aim, held: Outcome): Boolean = when (aim) {
        is Aim.Forced -> held == Outcome.HELD_BY_FORCE && aim.kind in observed.forcedKinds
        is Aim.Row -> held == Outcome.HELD_BY_RULE && observed.targetRows.isNotEmpty() &&
            observed.targetRows.all { (reason, rules) ->
                if (aim.token == aim.token.uppercase()) reason == aim.token
                else reason == REASON_OF_RULE[aim.token] && aim.token in rules
            }
    }

    /**
     * Asserts a kept attempt still ends as [expected]. A kept attempt that stopped reaching its rule
     * fails asking to re-aim it; one that became a gap fails as the regression it is.
     */
    protected fun assertOutcome(expected: Outcome, result: Result) {
        if (result.outcome == expected) return
        when {
            result.outcome == Outcome.GAP ->
                fail("a test that should have run was skipped, where this attempt used to be $expected: ${result.why}")
            expected == Outcome.HELD_BY_RULE ->
                fail("the attempt no longer reaches a rule; re-aim it. It is now ${result.outcome}: ${result.why}")
            expected == Outcome.HELD_BY_FORCE ->
                fail("the attempt no longer reaches what forced it; re-aim it. It is now ${result.outcome}: ${result.why}")
            else -> fail("expected $expected, got $result")
        }
    }

    /** Whether [testClass] passed (true), failed (false), or did not run (null), from its result file. */
    private fun passed(dir: File, testClass: String): Boolean? {
        val xml = File(dir, "build/test-results/test/TEST-$testClass.xml")
        if (!xml.isFile) return null
        val suite = Regex("""<testsuite [^>]*>""").find(xml.readText())?.value ?: return null
        fun count(attribute: String) = Regex("""$attribute="(\d+)"""").find(suite)?.groupValues?.get(1)?.toInt() ?: 0
        if (count("tests") == count("skipped")) return null
        return count("failures") + count("errors") == 0
    }

    /**
     * The selecting run's notes, one map per test JVM that wrote decisions, and each of [target]'s
     * method rows as its reason and its rules. Every JVM's part is read: the decisions file alone is
     * whichever JVM wrote last, and may not hold the target.
     */
    private fun decisions(dir: File, target: String): Pair<List<Map<String, String>>, List<Pair<String, Set<String>>>> {
        fun isPart(file: File) =
            file.name.startsWith("${AgentContract.DECISIONS_FILE}.") && file.name.endsWith(AgentContract.DECISIONS_PART_SUFFIX)
        val mapDir = File(dir, ".gradle/yoriwake").listFiles().orEmpty().filter(File::isDirectory)
            .singleOrNull { d -> d.listFiles().orEmpty().any { it.name == AgentContract.DECISIONS_FILE || isPart(it) } }
            ?: return emptyList<Map<String, String>>() to emptyList()
        val files = mapDir.listFiles().orEmpty().filter(::isPart).sortedBy(File::getName)
            .ifEmpty { listOf(File(mapDir, AgentContract.DECISIONS_FILE)).filter(File::isFile) }
        val notes = mutableListOf<Map<String, String>>()
        val rows = mutableListOf<Pair<String, Set<String>>>()
        for (file in files) {
            val lines = file.readLines()
            notes += lines.filter { it.startsWith(AgentContract.NOTE_PREFIX) }.associate { line ->
                val (key, value) = (line.removePrefix(AgentContract.NOTE_PREFIX) + "\t").split("\t", limit = 3)
                key to value
            }
            val rules = lines.filter { it.startsWith(AgentContract.RULES_LINE_PREFIX) }.associate { line ->
                val (id, named) = (line.removePrefix(AgentContract.RULES_LINE_PREFIX) + "\t").split("\t", limit = 3)
                id to named.split(',').filter(String::isNotBlank).toSet()
            }
            rows += lines.filter { it.isNotBlank() && !it.startsWith("#") }
                .map { it.split("\t") }
                .filter { it.size >= 3 && it[0].contains("[class:$target]") && it[0].contains("[method:") }
                .map { it[2] to rules[it[0]].orEmpty() }
        }
        return notes to rows.distinct()
    }

    private fun gitStatus(dir: File): String =
        ProcessBuilder("git", "status", "--porcelain").directory(dir).redirectErrorStream(true).start()
            .let { process -> process.inputStream.bufferedReader().readText().also { process.waitFor() } }

    private companion object {
        /** The row reason each row rule decides a test under. */
        val REASON_OF_RULE = mapOf(
            "reaches-change" to "REACHES_CHANGE",
            "own-class-changed" to "REACHES_CHANGE",
            "changed-bytes" to "REACHES_CHANGE",
            "class-setup" to "REACHES_CHANGE",
            "shares-jvm-changed-class" to "SHARES_JVM_WITH_CHANGE",
            "shares-jvm-changed-bytes" to "SHARES_JVM_WITH_CHANGE",
            "shares-jvm-unobserved" to "SHARES_JVM_WITH_CHANGE",
            "shares-jvm-unpositioned" to "SHARES_JVM_WITH_CHANGE",
            "not-known-to-pass" to "NOT_KNOWN_TO_PASS",
            "not-in-map" to "NOT_IN_MAP",
            "always-run" to "ALWAYS_RUN",
            "engine-runs-everything" to "ENGINE_RUNS_EVERYTHING",
            "class-granularity" to "CLASS_GRANULARITY",
        )
    }
}
