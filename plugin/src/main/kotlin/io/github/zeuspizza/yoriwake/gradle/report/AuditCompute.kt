package io.github.zeuspizza.yoriwake.gradle.report

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.agent.select.MapReader
import io.github.zeuspizza.yoriwake.gradle.bytecode.InlineScan
import io.github.zeuspizza.yoriwake.gradle.capture.CoverageDecoder
import io.github.zeuspizza.yoriwake.gradle.report.Audit.Blocker
import io.github.zeuspizza.yoriwake.gradle.report.Audit.Counts
import io.github.zeuspizza.yoriwake.gradle.report.Audit.DEFAULT_UNATTRIBUTABLE_CEILING
import io.github.zeuspizza.yoriwake.gradle.report.Audit.Distribution
import io.github.zeuspizza.yoriwake.gradle.report.Audit.HUB_TOP
import io.github.zeuspizza.yoriwake.gradle.report.Audit.Hub
import io.github.zeuspizza.yoriwake.gradle.report.Audit.MAP_COVERAGE_FLOOR
import io.github.zeuspizza.yoriwake.gradle.report.Audit.Provenance
import io.github.zeuspizza.yoriwake.gradle.report.Audit.Result
import io.github.zeuspizza.yoriwake.gradle.report.Audit.State
import io.github.zeuspizza.yoriwake.gradle.report.Audit.TaskFacts
import io.github.zeuspizza.yoriwake.gradle.report.Audit.Toll
import io.github.zeuspizza.yoriwake.gradle.wiring.ScopeOutcome
import java.io.File
import java.util.concurrent.TimeUnit

// The audit's verdict and blockers, computed from the map; [Audit] holds the types, and
// AuditRender.kt the prose and the JSON.

/** Outcome of records that are not tests: startup coverage and class-scoped windows. */
private val NON_TEST_OUTCOMES = setOf("NONE")

/**
 * Test outcomes as an allowlist, so a truncated or unknown outcome field counts as malformed.
 * `UNKNOWN` is what the decoder writes when it could not attribute a window.
 */
private val TEST_OUTCOMES = setOf("SUCCESSFUL", "FAILED", "ABORTED", "UNKNOWN")

/** outcome, durationNanos, comma-separated classes, testId. */
private const val OUTCOME = 0

/**
 * `System.nanoTime()` around one test, inside the instrumented JVM. Not a suite wall clock: it
 * includes the capture toll, sums across parallel workers, and excludes compile, configuration,
 * JVM startup and discovery.
 */
private const val DURATION = 1
private const val CLASSES = 2
private const val TEST_ID = 3
private const val FIELDS = 4

/** Every token meaning "the plugin declined this task", derived so new kinds join. */
private val DECLINED_TOKENS: Set<String> =
    Audit.BlockerKind.entries.filter { it.declined }.mapTo(mutableSetOf()) { it.token }

/** Collapses whitespace so a throwable's message can be interpolated into a one-line blocker. */
private fun oneLine(value: String) = value.replace(Regex("\\s+"), " ").trim()

private val NO_COUNTS = Counts(lines = 0, malformed = 0, testRecords = 0, nonTestRecords = 0)

/**
 * The whole verdict, as data. Lives outside `YoriwakePlugin.kt`, which the JaCoCo line floor
 * excludes.
 */
internal fun Audit.audit(
    mapDir: File,
    taskPath: String,
    captureTask: String,
    unattributableCeiling: Double = DEFAULT_UNATTRIBUTABLE_CEILING,
    toll: Toll? = null,
    /** What stops this task narrowing, reported on every terminal state, refusals included. */
    blockers: List<Blocker> = emptyList(),
    /** See [Payback.ConfigureCost]. Null when the build could not count it. */
    configureCost: Payback.ConfigureCost? = null,
): Result {
    val provenance = provenance(mapDir)
    fun refuse(headline: String, counts: Counts = NO_COUNTS, vararg detail: String) = Result(
        State.NO_CONCLUSION,
        headline,
        detail.toList() + blockerLines(blockers) + provenanceLines(provenance),
        counts,
        distribution = null,
        provenance = provenance,
        blockers = blockers,
    )

    val coverage = File(mapDir, AgentContract.COVERAGE_FILE)
    if (!coverage.isFile) {
        // On a task the plugin declined, capturing again never produces a map, so do not
        // advise it.
        val declined = blockers.any { it.token in DECLINED_TOKENS }
        return refuse(
            "$taskPath: no map at $mapDir, so there is nothing to audit.",
            detail = arrayOf(
                if (declined) {
                    "No map will appear for this task, because the plugin declined to " +
                        "configure it at all -- see the blocker below. Running " +
                        "`$captureTask` again changes nothing."
                } else {
                    "Run `$captureTask` once to capture one, then run this again. A first " +
                        "run with no map is the expected state, not a fault."
                },
            ),
        )
    }

    // Checks the map's metadata as MapReader does, but not through MapReader.read: that
    // refuses the whole map on one malformed line, while a report should describe the rest
    // and count what it could not read.
    val version = File(mapDir, AgentContract.MAP_SCHEMA_VERSION_FILE)
    if (!version.isFile) {
        return refuse(
            "$taskPath: the map at $mapDir was never finished.",
            detail = arrayOf(
                "It holds records but no ${AgentContract.MAP_SCHEMA_VERSION_FILE}, which only a completed " +
                    "capture writes, so a capture failed partway. Look for an earlier " +
                    "'[yoriwake] could not update the map' warning for the cause.",
            ),
        )
    }
    val found = runCatching { version.readText().trim().toInt() }.getOrNull()
    if (found != AgentContract.MAP_SCHEMA_VERSION) {
        return refuse(
            "$taskPath: the map at $mapDir is schema version " +
                "${found ?: version.readText().trim()} but this plugin reads " +
                "${AgentContract.MAP_SCHEMA_VERSION}.",
            detail = arrayOf(
                "Its records are not read at all here: fields written under a schema this " +
                    "build does not know may not mean what they appear to. Rebuild the map " +
                    "by running the full suite.",
            ),
        )
    }

    val records = read(coverage)
    val counts = records.counts
    if (counts.lines == 0) {
        return refuse(
            "$taskPath: the map at $mapDir holds no records.",
            counts,
            "An empty map is not a suite that nothing selects -- it is a map that has not " +
                "learned anything yet. Run `$captureTask` to capture.",
        )
    }
    if (counts.testRecords == 0) {
        return refuse(
            "$taskPath: 0 of ${counts.lines} lines in the map were usable test records " +
                "(${counts.malformed} malformed, ${counts.nonTestRecords} startup coverage).",
            counts,
            "This is not a suite of which 0% would be selected. It is a map whose contents " +
                "could not be read as tests, which is a decoder question, not a selection one.",
        )
    }

    val byClass = invert(records.tests)
    val unattributable = records.tests.count { it.value.isEmpty() }
    val unattributableShare = unattributable.toDouble() / counts.testRecords
    // Inclusive: at exactly the ceiling the shares are as untrustworthy as one record later.
    if (unattributableShare >= unattributableCeiling || byClass.isEmpty()) {
        return refuse(
            "$taskPath: $unattributable of ${counts.testRecords} tests " +
                "(${percent(unattributableShare)}) could not be attributed to any class and " +
                "RUN ON EVERY BUILD.",
            counts,
            "Those tests belong to no class, so they would appear in none of the shares " +
                "this audit could report -- which would therefore describe a minority of this " +
                "suite and read far healthier than the suite is. " +
                "A framework that runs tests in its own classloader -- Robolectric is the " +
                "common one -- is the usual cause. Ceiling: " +
                "${percent(unattributableCeiling)}.",
        )
    }

    // Reported beside the reachability figures, not folded in: one counts tests, the other
    // nanoseconds.
    val recordedTime = Payback.recordedTime(
        records.timedRecords, records.durationNanos, records.recordsWithoutDuration,
        records.duplicateTimedRecords, TaskFacts.read(mapDir).workers,
    )
    val distribution = distribution(records, byClass, unattributable, unattributableShare)
    val shape = "$taskPath: a change to one class reaches a median " +
        "${percent(distribution.medianShare)} of this suite " +
        "(mean ${percent(distribution.meanShare)}, p90 ${percent(distribution.p90Share)}, " +
        "max ${percent(distribution.maxShare)})."
    val described = narrowingLines(distribution, counts) +
        Payback.recordedTimeLines(recordedTime, records.counts.testRecords) + blockerLines(blockers) +
        provenanceLines(provenance)

    // No toll given: report narrowing only, and state that the toll is unmeasured.
    if (toll == null) {
        return Result(
            State.NARROWING_ONLY, shape, described + unmeasuredToll(captureTask),
            counts, distribution, provenance, blockers, recordedTime,
        )
    }

    // Refusals come first: two timings always differ, so a verdict that always concludes is
    // the failure this ordering prevents.
    if (toll.failedRun != null) {
        return refuse(
            "$taskPath: the ${toll.failedRun} run did not complete, so there is no pair to " +
                "compare.",
            counts,
            "It reported: ${toll.failureDetail}. A failed run is never a time, and one run is " +
                "not a measurement of overhead.",
        )
    }
    val share = toll.share
    if (share == null) {
        return refuse(
            "$taskPath: the two runs did not produce a usable pair of timings.", counts,
            "An uninstrumented run of zero seconds cannot be divided by.",
        )
    }
    if (share < 0.0) {
        return refuse(
            "$taskPath: the instrumented run ran FASTER than the uninstrumented one " +
                "(${seconds(toll.instrumented)} against ${seconds(toll.uninstrumented)}).",
            counts,
            "That is a measurement of the machine, not of the toll -- a warm cache, a busy " +
                "core, or another build sharing the box. Re-run both on a quiet machine. " +
                "This is never reported as an overhead below zero, because arithmetic on one " +
                "would grow more confident the more wrong the measurement was.",
        )
    }
    if (share < toll.floor) {
        return refuse(
            "$taskPath: the two runs are within the noise floor " +
                "(${seconds(toll.instrumented)} instrumented against " +
                "${seconds(toll.uninstrumented)} uninstrumented, ${percent(share)} apart, " +
                "floor ${percent(toll.floor)}).",
            counts,
            "Baselines on one machine have been observed moving 187s to 241s across identical " +
                "runs, so a difference this small is weather. Both timings are printed above " +
                "rather than resolved into a verdict, because a single pair cannot settle it. " +
                "Re-run on a quiet machine.",
        )
    }

    // A floor on "could this ever help", not a savings estimate: a run costs `share` extra to
    // instrument and then runs `meanShare` of the suite, so selection pays only below one.
    val predicted = distribution.meanShare + share
    val arithmetic = listOf(
        "Measured toll: ${percent(share)} " +
            "(${seconds(toll.instrumented)} instrumented against " +
            "${seconds(toll.uninstrumented)} uninstrumented, floor ${percent(toll.floor)}).",
        "A random change would then cost ${percent(distribution.meanShare)} of the suite in " +
            "tests plus ${percent(share)} to instrument = ${percent(predicted)} of a full run.",
        "That model assumes every test costs the same, which is false -- it counts tests, not " +
            "seconds. It is a floor on whether selection could ever help here, not an estimate " +
            "of what it would save.",
        "This toll was NOT written into the map. It is a property of this machine at this " +
            "moment, and a cached one becomes another project's borrowed number.",
    ) + Payback.paybackLines(recordedTime, distribution, toll, configureCost)
    // `recordedTime` on both, so `audit.json` agrees with the prose beside it.
    return if (predicted < 1.0) {
        Result(
            State.WORTH_IT, shape, described + arithmetic, counts, distribution, provenance,
            blockers, recordedTime,
        )
    } else {
        Result(
            State.DO_NOT_ENABLE, shape,
            described + arithmetic +
                "Selection would be SLOWER than running everything on this suite.",
            counts, distribution, provenance, blockers, recordedTime,
        )
    }
}

/**
 * Everything that stops this task narrowing, in the order a reader should act on it.
 *
 * Pure, and must never run a test task or spawn a nested build: a nested Gradle build from a
 * task action deadlocks on the outer build's own file locks.
 */
internal fun Audit.blockers(
    facts: TaskFacts,
    scopeRefusal: ScopeOutcome.NotApplied? = null,
    inlineScan: InlineScan? = null,
    compiledClasses: Int? = null,
    mapClasses: Int? = null,
    gitUnavailable: Boolean = false,
): List<Blocker> = buildList {
    // Not about the map: without a change set every task runs in full.
    if (gitUnavailable) {
        add(
            Blocker(
                Audit.BlockerKind.GIT_UNAVAILABLE,
                "a git call in this build could not answer, so no change set could be " +
                    "computed and every task ran in full",
                "check that git is on PATH and that this is a git checkout with history. A " +
                    "shallow clone, a missing `.git`, or a git that took longer than 30 " +
                    "seconds all land here; the build log of the run names which call it was.",
            )
        )
    }
    // Before the map-shaped blockers: nothing else was even attempted.
    scopeRefusal?.let { refusal ->
        add(
            Blocker(
                // Named for the cause where it has its own remedy; the non-attachment kinds
                // are not scope declines.
                when (refusal.kind) {
                    ScopeOutcome.NotApplied.Kind.NO_HOST_PLUGIN -> Audit.BlockerKind.NO_HOST_PLUGIN
                    ScopeOutcome.NotApplied.Kind.ISOLATED_PROJECTS -> Audit.BlockerKind.ISOLATED_PROJECTS
                    ScopeOutcome.NotApplied.Kind.GRADLE_TOO_OLD -> Audit.BlockerKind.GRADLE_TOO_OLD
                    ScopeOutcome.NotApplied.Kind.JACOCO_DISABLED -> Audit.BlockerKind.JACOCO_DISABLED
                    ScopeOutcome.NotApplied.Kind.KOTLIN_MULTIPLATFORM -> Audit.BlockerKind.KOTLIN_MULTIPLATFORM
                    else -> Audit.BlockerKind.SCOPE_NOT_APPLIED
                },
                oneLine("the plugin declined to configure this task: ${refusal.detail}"),
                when (refusal.kind) {
                    ScopeOutcome.NotApplied.Kind.NO_JACOCO_PLUGIN ->
                        "apply the `jacoco` plugin to this project. Coverage is what this " +
                            "tool selects from; without it there is nothing to select on."
                    ScopeOutcome.NotApplied.Kind.NO_PACKAGES_DERIVED ->
                        "no remedy: this build's sources are in a language the scope " +
                            "derivation does not read -- Groovy and Scala modules among " +
                            "them. A documented limit, and the plugin declines rather than " +
                            "capturing an unknown extent."
                    ScopeOutcome.NotApplied.Kind.NO_HOST_PLUGIN ->
                        "apply the `java` plugin, or one of the Android plugins, to the " +
                            "project whose tests you want selected. This tool attaches to " +
                            "those; a build with test tasks and none of them is one it cannot " +
                            "see the sources of."
                    ScopeOutcome.NotApplied.Kind.KOTLIN_MULTIPLATFORM ->
                        "a Kotlin Multiplatform module is supported when it has a JVM target " +
                            "laid out as `src/jvmMain` or `src/jvmTest` beside at least one " +
                            "`<target>Main` source directory; its scope comes from those " +
                            "directories. This module has none the plugin can see, so it is " +
                            "declined rather than scoped from other modules' packages. Give its " +
                            "JVM target that layout, or accept full runs for this module."
                    ScopeOutcome.NotApplied.Kind.ISOLATED_PROJECTS ->
                        "no remedy: this tool derives its instrumentation scope from the " +
                            "packages every project in the build declares, and Isolated " +
                            "Projects forbids reading them. Deriving per project instead makes " +
                            "the scope depend on evaluation order, which is the defect that " +
                            "derivation was moved to `projectsEvaluated` to fix. Turn the " +
                            "feature off for a build that should select, or accept full runs."
                    ScopeOutcome.NotApplied.Kind.GRADLE_TOO_OLD ->
                        "upgrade Gradle to 8.14 or newer. Below it, API this plugin reads is " +
                            "missing, and reading around the gap could skip a test that should " +
                            "run, so the plugin declines instead and every run is a full run."
                    ScopeOutcome.NotApplied.Kind.JACOCO_DISABLED ->
                        "your build switches the jacoco extension off on this task, so no " +
                            "coverage agent reaches the test JVM. Some builds disable it " +
                            "through a convention plugin, for example behind a project " +
                            "property, so check yours and enable it for the runs you want " +
                            "selected. Nothing is captured until you do."
                    ScopeOutcome.NotApplied.Kind.NO_JACOCO_EXTENSION,
                    ScopeOutcome.NotApplied.Kind.INCLUDES_REJECTED -> null
                },
            )
        )
    }
    when (facts.framework) {
        null -> add(
            Blocker(
                Audit.BlockerKind.FRAMEWORK_UNKNOWN,
                "no run of this task has recorded which test framework it uses",
                "run the task once. Gradle settles the framework after this plugin is " +
                    "applied, so it is recorded by the run rather than read from the build.",
            )
        )
        TaskFacts.PLATFORM -> Unit
        else -> add(
            Blocker(
                Audit.BlockerKind.NOT_ON_JUNIT_PLATFORM,
                "this task runs on ${facts.framework}, so the JUnit Platform's " +
                    "PostDiscoveryFilter is never consulted and no test can be deselected " +
                    "through it",
                "run JUnit 4 through the vintage engine -- " +
                    "`scripts/junit-vintage.init.gradle.kts` does this without editing the " +
                    "build. A vintage run is only honest evidence about the suite it " +
                    "actually runs: the test runtime gained an engine, so say so wherever " +
                    "its numbers are quoted. It does not help a Robolectric suite, whose " +
                    "classes fail Platform discovery. See " +
                    "docs/reference.md#junit-4-and-the-vintage-engine.",
            )
        )
    }
    if (facts.recordedCoverage == false) {
        add(
            Blocker(
                Audit.BlockerKind.RECORDED_NO_COVERAGE,
                "the last capture run of this task executed tests and recorded no coverage at " +
                    "all, so no map was built and nothing can ever be selected here",
                "the recording listener is found by ServiceLoader from the JUnit Platform. A " +
                    "task that records nothing usually has no JaCoCo agent attached, or runs " +
                    "on an engine this tool has not been taught. Capturing again changes " +
                    "nothing until one of those does.",
            )
        )
    }
    facts.decodeRefused?.let { reason ->
        add(
            Blocker(
                Audit.BlockerKind.DECODE_REFUSED,
                "the last capture ran and its records could not be merged into the map " +
                    "($reason), so the map still answers from before that run",
                "the records that could not be merged are in the map's `" +
                    AgentContract.RAW_DIR + "` directory. Delete that directory and " +
                    "run the suite once to rebuild the map; if it refuses again, the reason " +
                    "above is the one to report.",
            )
        )
    }
    facts.parallelism?.let { source ->
        add(
            Blocker(
                Audit.BlockerKind.IN_JVM_PARALLELISM,
                "in-JVM parallel execution is enabled via $source, and the plugin refuses to " +
                    "capture under it",
                "no remedy: tests interleaved inside one JVM share a single coverage agent, " +
                    "so per-test attribution would silently blend them. Parallel FORKS " +
                    "(maxParallelForks) are unaffected and remain safe.",
            )
        )
    }
    (inlineScan as? InlineScan.Refused)?.let { refused ->
        add(
            when (refused.kind) {
                // Not a flat blocker: a Java-only change set still narrows, because a Java
                // source cannot be an inline body copied into a caller. Kotlin changes force.
                InlineScan.Kind.SMAP_ABSENT -> Blocker(
                    Audit.BlockerKind.SMAP_ABSENT,
                    oneLine(
                        "the inline scan cannot answer for a change that touches a Kotlin " +
                            "source: ${refused.reason}. A change set of Java sources alone is " +
                            "unaffected and still narrows."
                    ),
                    "if your build passes -Xno-source-debug-extension, removing it restores " +
                        "the signal. If it does not, the build's Kotlin classes call no " +
                        "`inline` function, so the compiler wrote a SourceDebugExtension " +
                        "into none of them -- typically a mostly-Java build with a few " +
                        "Kotlin classes -- and there is nothing to fix in your build: a " +
                        "Kotlin `inline` body is copied into its call sites and coverage " +
                        "records no edge to the declaring file, so a Kotlin change genuinely " +
                        "cannot be reasoned about here.",
                )

                // Not about the selector: this audit runs no tasks and so cannot resolve the
                // classpath; a selecting build resolves it later and never sees this.
                InlineScan.Kind.CLASSPATH_UNRESOLVED -> Blocker(
                    Audit.BlockerKind.CLASSPATH_UNRESOLVED_HERE,
                    oneLine(
                        "this audit could not resolve the task's classpath, so the inline scan " +
                            "was never asked: ${refused.reason}"
                    ),
                    "yoriwakeAudit deliberately runs no tasks, and an Android unit-test classpath " +
                        "carries entries mapped from a transform task's output that Gradle " +
                        "will not resolve before that task has completed. A selecting build " +
                        "resolves it after the build and is unaffected. To ask this question " +
                        "here, run the module's test compile first, then the audit.",
                )

                else -> Blocker(
                    Audit.BlockerKind.INLINE_SCAN_INCOMPLETE,
                    oneLine("the inline scan did not finish: ${refused.reason}"),
                    "the message names what stopped it. An unreadable class or jar is usually " +
                        "a class-file version this plugin's ASM is older than.",
                )
            }
        )
    }
    if (compiledClasses != null && mapClasses != null && compiledClasses > 0 &&
        mapClasses.toDouble() / compiledClasses < MAP_COVERAGE_FLOOR
    ) {
        add(
            Blocker(
                Audit.BlockerKind.CLASSES_ABSENT_FROM_MAP,
                "the map has coverage for $mapClasses of the $compiledClasses classes this " +
                    "module compiles, so a change is likely to land on one it has never seen",
                null,
            )
        )
    }
}

private class Records(
    val tests: Map<String, Set<String>>,
    val nonTests: Map<String, Set<String>>,
    val counts: Counts,
    /** Summed over TEST lines only; see [DURATION]. */
    val durationNanos: Long,
    val timedRecords: Int,
    val recordsWithoutDuration: Int,
    /** Test ids that carried a timed record more than once; see [read]. */
    val duplicateTimedRecords: Int,
)

/**
 * Every (testId, classes) the map holds, plus the ids that are not tests. A malformed line is
 * counted and skipped, never guessed at.
 */
private fun read(coverage: File): Records {
    val tests = mutableMapOf<String, MutableSet<String>>()
    val nonTests = mutableMapOf<String, MutableSet<String>>()
    var lines = 0
    var malformed = 0
    // One duration per test id: a retried test writes a second record under the same id, and
    // summing both counts it twice. The larger is kept; affected ids are reported separately.
    val timedById = mutableMapOf<String, Long>()
    // Ids, not occurrences: one id with three records counts once.
    val duplicatedIds = mutableSetOf<String>()
    var recordsWithoutDuration = 0
    coverage.forEachLine { raw ->
        if (raw.isBlank()) return@forEachLine
        lines++
        val parts = raw.trimEnd('\n', '\r').split("\t", limit = FIELDS)
        if (parts.size < FIELDS) {
            malformed++
            return@forEachLine
        }
        val classes = parts[CLASSES].split(",").filter(String::isNotEmpty)
        // An outcome that is neither a test outcome nor the non-test marker is malformed.
        if (parts[OUTCOME] !in TEST_OUTCOMES && parts[OUTCOME] !in NON_TEST_OUTCOMES) {
            malformed++
            return@forEachLine
        }
        val isTest = parts[OUTCOME] !in NON_TEST_OUTCOMES
        if (isTest) {
            // Only tests are timed: non-test records carry 0 because no test ran in them.
            when (val nanos = parts[DURATION].toLongOrNull()) {
                // Ran but did not say how long: unknown, not zero.
                null -> recordsWithoutDuration++
                else -> if (nanos > 0) {
                    val id = parts[TEST_ID]
                    if (timedById.containsKey(id)) duplicatedIds += id
                    timedById[id] = maxOf(nanos, timedById[id] ?: 0L)
                } else {
                    recordsWithoutDuration++
                }
            }
        }
        val target = if (isTest) tests else nonTests
        // Class-scoped ids appear once per coverage window, so union rather than replace.
        target.getOrPut(parts[TEST_ID]) { mutableSetOf() }.addAll(classes)
    }
    return Records(
        tests,
        nonTests,
        Counts(lines, malformed, testRecords = tests.size, nonTestRecords = nonTests.size),
        durationNanos = timedById.values.sum(),
        timedRecords = timedById.size,
        recordsWithoutDuration = recordsWithoutDuration,
        duplicateTimedRecords = duplicatedIds.size,
    )
}

/**
 * How many of this module's own classes the map covers, out of how many it compiled, or null.
 * Own classes only, since the map spans the whole build.
 */
internal fun Audit.ownClassCoverage(mapDir: File, ownClasses: Set<String>): Pair<Int, Int>? {
    if (ownClasses.isEmpty()) return null
    val coverage = File(mapDir, AgentContract.COVERAGE_FILE)
    if (!coverage.isFile) return null
    val records = runCatching { read(coverage) }.getOrNull() ?: return null
    val seen = records.tests.values.flatten().toSet()
    return ownClasses.count { it in seen } to ownClasses.size
}

/** class -> the tests that execute it. */
private fun invert(tests: Map<String, Set<String>>): Map<String, Int> {
    val byClass = mutableMapOf<String, MutableSet<String>>()
    tests.forEach { (id, classes) ->
        classes.forEach { byClass.getOrPut(it) { mutableSetOf() }.add(id) }
    }
    return byClass.mapValues { it.value.size }
}

private fun distribution(
    records: Records,
    byClass: Map<String, Int>,
    unattributable: Int,
    unattributableShare: Double,
): Distribution {
    val tests = records.counts.testRecords
    val counts = byClass.values.toList()
    // Denominator is every test record, unattributable ones included, so a share reads "this
    // fraction of the whole suite". Unattributable tests drop out of the numerator only, which
    // is why the ceiling refuses rather than the arithmetic changing.
    fun share(count: Int) = count.toDouble() / tests
    val median = percentile(counts, 0.5)
    return Distribution(
        tests = tests,
        classes = byClass.size,
        unattributableTests = unattributable,
        unattributableShare = unattributableShare,
        meanShare = counts.sum().toDouble() / counts.size / tests,
        medianTestsPerClass = median,
        medianShare = share(median),
        p90Share = share(percentile(counts, 0.90)),
        p99Share = share(percentile(counts, 0.99)),
        maxShare = share(counts.max()),
        hubClassesOverHalf = counts.count { share(it) > 0.5 },
        startupClasses = records.nonTests.values.flatten().toSet().size,
        hubs = byClass.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }
            .thenBy { it.key })
            .take(HUB_TOP)
            .map { Hub(it.key, it.value, share(it.value)) },
    )
}

/** Nearest-rank percentile. */
private fun percentile(values: List<Int>, fraction: Double): Int {
    val ordered = values.sorted()
    val index = minOf(ordered.size - 1, Math.round(fraction * (ordered.size - 1)).toInt())
    return ordered[index]
}

private fun provenance(mapDir: File): Provenance {
    val stamp = File(mapDir, CoverageDecoder.CAPTURE_COMMIT_FILE)
        .takeIf { it.isFile }
        ?.let { runCatching { it.readText().trim() }.getOrNull() }
        ?.takeIf { it.isNotEmpty() }
    // Age only beside the stamp, never from mtime alone: a CI cache restore rewrites mtimes.
    val age = stamp?.let {
        val written = File(mapDir, AgentContract.COVERAGE_FILE).lastModified()
        if (written <= 0L) null
        else TimeUnit.MILLISECONDS.toDays((System.currentTimeMillis() - written).coerceAtLeast(0))
    }
    return Provenance(stamp, age)
}

/**
 * This task's contribution to the build-wide payback, or the axis it could not supply, with the
 * same refusals as [Payback.paybackLines] so the two cannot disagree.
 */
internal fun Audit.taskPayback(
    taskPath: String,
    result: Result,
    cost: Payback.ConfigureCost?,
    toll: Toll? = null,
): Payback.TaskPayback {
    val time = result.recordedTime
        ?: return Payback.TaskPayback(taskPath, missing = "this map records no per-test durations")
    val workers = time.workers?.takeIf { it > 0 }
        ?: return Payback.TaskPayback(
            taskPath,
            missing = "the map does not record how many workers its durations were summed across",
        )
    val distribution = result.distribution
        ?: return Payback.TaskPayback(
            taskPath,
            missing = "this task's audit reached no distribution, so no share could be applied",
        )
    if (cost == null) {
        return Payback.TaskPayback(taskPath, missing = "no configure cost was measured here")
    }
    val wallClock = time.nanos / workers
    val instrumented = toll?.instrumented
    if (instrumented != null && wallClock > instrumented * 1_000_000_000L) {
        return Payback.TaskPayback(
            taskPath,
            missing = "the map holds more recorded test time than the instrumented run took, " +
                "the signature of durations summed across more workers than it records",
        )
    }
    return Payback.TaskPayback(
        taskPath,
        savedNanos = (wallClock * (1.0 - distribution.meanShare)).toLong(),
        costNanos = cost.perTestTaskNanos,
    )
}
