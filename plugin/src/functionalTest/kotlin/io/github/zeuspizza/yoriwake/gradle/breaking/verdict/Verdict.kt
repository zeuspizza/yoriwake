package io.github.zeuspizza.yoriwake.gradle.breaking

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import java.io.File
import kotlin.test.fail

// What a break attempt's builds showed, and the outcome it comes to. Neither Gradle nor Maven
// appears here: both break suites compile this directory, so an attempt on either build tool is
// classified by the same rules.

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
fun assertOutcome(expected: Outcome, result: Result) {
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

/** Whether the test class of a JUnit XML result file passed (true), failed (false), or did not run (null). */
fun passedIn(xml: File): Boolean? {
    if (!xml.isFile) return null
    val suite = Regex("""<testsuite [^>]*>""").find(xml.readText())?.value ?: return null
    fun count(attribute: String) = Regex("""$attribute="(\d+)"""").find(suite)?.groupValues?.get(1)?.toInt() ?: 0
    if (count("tests") == count("skipped")) return null
    return count("failures") + count("errors") == 0
}

/** The refusal and full-run kinds a selecting run's notes record. */
fun forcedKinds(notes: List<Map<String, String>>): List<String> = notes.flatMap { note ->
    listOfNotNull(note[AgentContract.REFUSAL_KIND_NOTE], note[AgentContract.FULL_RUN_KIND_NOTE]) +
        note[AgentContract.FORCING_KINDS_NOTE].orEmpty().split(',')
}.filter { it.isNotBlank() && it != AgentContract.RULE_NONE }.distinct()

/**
 * The selecting run's notes, one map per test JVM that wrote decisions, and each of [target]'s
 * method rows as its reason and its rules, from the map directory under [mapRoot] that holds the
 * target's rows (a Maven reactor writes one per execution), or the only one that holds decisions.
 * Every JVM's part is read: the decisions file alone is whichever JVM wrote last, and may not hold
 * the target.
 */
fun decisions(mapRoot: File, target: String): Pair<List<Map<String, String>>, List<Pair<String, Set<String>>>> {
    val read = mapRoot.listFiles().orEmpty().filter(File::isDirectory).sortedBy(File::getName)
        .filter { d -> d.listFiles().orEmpty().any { it.name == AgentContract.DECISIONS_FILE || isPart(it) } }
        .map { readDecisions(it, target) }
    return read.filter { it.second.isNotEmpty() }.singleOrNull() ?: read.singleOrNull()
        ?: (emptyList<Map<String, String>>() to emptyList())
}

private fun isPart(file: File) =
    file.name.startsWith("${AgentContract.DECISIONS_FILE}.") && file.name.endsWith(AgentContract.DECISIONS_PART_SUFFIX)

private fun readDecisions(mapDir: File, target: String): Pair<List<Map<String, String>>, List<Pair<String, Set<String>>>> {
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
            .filter { it.size >= 3 && isTargetRow(it[0], target) }
            .map { it[2] to rules[it[0]].orEmpty() }
    }
    return notes to rows.distinct()
}

/**
 * Whether a decision row is one of [target]'s: a method of the class, a Kotest spec, which
 * discovery yields whole and whose tests appear only as it runs, or a test its JUnit 4 runner
 * lists on the vintage engine, which names the class only in the runner's segment. A static
 * nested class (`Outer$Inner`) Jupiter discovers on its own is named whole; a `@Nested` one is
 * the segment naming its simple name under its enclosing class's.
 */
private fun isTargetRow(id: String, target: String): Boolean {
    val classes = target.split('$')
    val nested = (listOf("[class:${classes.first()}]") + classes.drop(1).map { "[nested-class:$it]" })
        .joinToString("/")
    return (id.contains("[class:$target]") && METHOD_SEGMENTS.any { id.contains(it) }) ||
        (classes.size > 1 && id.contains("$nested/[method:")) ||
        id.endsWith("[spec:$target]") || id.contains("[spec:$target]/[feature:") ||
        id.contains("[runner:$target]/")
}

/** The segments under which Jupiter names a test method: plain, a template, a factory. */
private val METHOD_SEGMENTS = listOf("[method:", "[test-template:", "[test-factory:")

/** The row reason each row rule decides a test under. */
private val REASON_OF_RULE = mapOf(
    "reaches-change" to "REACHES_CHANGE",
    "own-class-changed" to "REACHES_CHANGE",
    "changed-bytes" to "REACHES_CHANGE",
    "class-setup" to "REACHES_CHANGE",
    "shares-jvm-changed-class" to "SHARES_JVM_WITH_CHANGE",
    "shares-jvm-changed-bytes" to "SHARES_JVM_WITH_CHANGE",
    "shares-jvm-moved-classpath-file" to "SHARES_JVM_WITH_CHANGE",
    "shares-jvm-unobserved" to "SHARES_JVM_WITH_CHANGE",
    "shares-jvm-unpositioned" to "SHARES_JVM_WITH_CHANGE",
    "shares-jvm-ignored-path" to "SHARES_JVM_WITH_CHANGE",
    "declares-changed-path" to "DECLARES_CHANGED_PATH",
    "not-known-to-pass" to "NOT_KNOWN_TO_PASS",
    "not-in-map" to "NOT_IN_MAP",
    "always-run" to "ALWAYS_RUN",
    "engine-runs-everything" to "ENGINE_RUNS_EVERYTHING",
    "class-granularity" to "CLASS_GRANULARITY",
)
