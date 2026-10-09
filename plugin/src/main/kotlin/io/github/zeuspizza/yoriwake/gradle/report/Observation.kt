package io.github.zeuspizza.yoriwake.gradle.report

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.agent.contract.Tsv
import io.github.zeuspizza.yoriwake.gradle.capture.writeAtomically
import java.io.File
import java.util.Locale

/**
 * What an observing run shows: each failing test a selecting run of the same build would have left
 * out, and the recorded test time of the tests it would have skipped. The verdicts come from every
 * writer's decision record, the outcomes from this run's raw records; the report claims nothing
 * either of them does not hold.
 */
internal object Observation {

    /** What the decode knows of the run beside the records. */
    data class Run(
        val taskPath: String,
        /** The commit the tests ran at. */
        val commit: String?,
        /** The capture commit of the map the selection was computed against. */
        val mapCaptureCommit: String?,
        /** Why this run's outcomes are not read, or null when they are. */
        val note: String? = null,
    )

    class Report(
        val run: Run,
        val outcomesRecorded: Boolean,
        val observedOutcome: String?,
        val fullRunKind: String?,
        val refusalKind: String?,
        /** False when a writer's record is missing or cut short, so some tests have no verdict. */
        val complete: Boolean,
        val testsWithoutVerdict: Int,
        val failures: Int,
        val failuresKept: Int,
        /** Each would-be miss as its test id and outcome. */
        val misses: List<Pair<String, String>>,
        val wouldBeSkipped: Int,
        val skippedNanos: Long,
    )

    private val FAILING = setOf("FAILED", "ABORTED")

    private val CAVEATS = listOf(
        "A would-be miss is a test that failed in this run and that selection would have left out. A test " +
            "that fails only when the whole suite runs in this order, or that is flaky, is listed as one too.",
        "Selection can make a test fail by changing what runs before it. A run that does not select cannot " +
            "observe that.",
        "This run refreshes the map, so the selection it observes was computed against a fresher map than a " +
            "run that only selects would read. The skipped time is an upper bound for such a run.",
        "No would-be miss on a run where no test failed, or where selection would have run everything, says " +
            "nothing about how often selection misses a failure.",
        "recordedInstrumentedTestNanos is the skipped tests' recorded durations under instrumentation, summed " +
            "over forks: not the wall-clock time a selecting run would save.",
    )

    /** Joins this run's verdicts and outcomes in [mapDir], writes the report there and returns it. */
    fun write(mapDir: File, run: Run): Report {
        val report = compute(mapDir, run)
        writeAtomically(File(mapDir, AgentContract.OBSERVATION_FILE), json(report))
        return report
    }

    private class Verdicts(
        val excludedById: Map<String, Boolean>,
        val observed: List<String>,
        val whole: Boolean,
    )

    /**
     * Every writer's observation lines, by test id. A test any writer would have kept counts as kept,
     * so a would-be miss is never claimed on one writer's word against another's.
     */
    private fun verdicts(mapDir: File): Verdicts {
        val excluded = HashMap<String, Boolean>()
        var observed: List<String> = emptyList()
        var whole = true
        val parts = mapDir.listFiles { file ->
            file.name.startsWith("${AgentContract.DECISIONS_FILE}.") &&
                file.name.endsWith(AgentContract.DECISIONS_PART_SUFFIX)
        }.orEmpty().sortedBy(File::getName)
        for (part in parts) {
            val lines = runCatching { part.readLines() }.getOrNull()
            if (lines == null) {
                whole = false
                continue
            }
            var declared: Int? = null
            var rows = 0
            for (line in lines) {
                when {
                    line.startsWith(AgentContract.OBSERVATION_LINE_PREFIX) -> {
                        val fields = Tsv.split(line.removePrefix(AgentContract.OBSERVATION_LINE_PREFIX))
                        if (fields.size < 2) continue
                        val out = fields[1] == "excluded"
                        excluded[fields[0]] = (excluded[fields[0]] ?: true) && out
                    }
                    line.startsWith(AgentContract.NOTE_PREFIX) -> {
                        val fields = Tsv.split(line.removePrefix(AgentContract.NOTE_PREFIX))
                        when (fields[0]) {
                            AgentContract.ROWS_NOTE -> declared = fields.getOrNull(1)?.toIntOrNull()
                            AgentContract.OBSERVED_OUTCOME_NOTE -> {
                                val value = fields.drop(1)
                                // A writer that narrowed is the one a miss could come from.
                                if (observed.isEmpty() || value.firstOrNull() == AgentContract.RUN_NARROWED) observed = value
                            }
                        }
                    }
                    line.isNotBlank() && !line.startsWith("#") -> rows++
                }
            }
            if (declared != rows) whole = false
        }
        return Verdicts(excluded, observed, whole)
    }

    private class Outcome(var failing: String?, var nanos: Long)

    /** This run's outcome for each test id, with every record of it: failing if any record failed. */
    private fun outcomes(mapDir: File): Map<String, Outcome>? {
        val workers = File(mapDir, AgentContract.RAW_DIR)
            .listFiles { file -> file.isDirectory && file.name.startsWith(AgentContract.WORKER_DIR_PREFIX) }
            .orEmpty().sortedBy(File::getName)
            .map { File(it, AgentContract.INDEX_FILE) }.filter(File::isFile)
        if (workers.isEmpty()) return null
        val byId = LinkedHashMap<String, Outcome>()
        for (index in workers) {
            for (line in runCatching { index.readLines() }.getOrDefault(emptyList())) {
                // sequence, durationNanos, byteCount, outcome, testId
                val fields = Tsv.split(line)
                if (fields.size != 5) continue
                val id = fields[4]
                if (id == AgentContract.UNATTRIBUTED_RECORD_ID || id.startsWith(AgentContract.CLASS_SCOPED_RECORD_PREFIX)) {
                    continue
                }
                val outcome = byId.getOrPut(id) { Outcome(null, 0L) }
                outcome.nanos += fields[1].toLongOrNull() ?: 0L
                if (fields[3] in FAILING && outcome.failing != "FAILED") outcome.failing = fields[3]
            }
        }
        return byId
    }

    /** The verdict for [id], or for its nearest recorded ancestor (a prefix ending at `/`), as rules match subtrees. */
    private fun excludedFor(id: String, excluded: Map<String, Boolean>): Boolean? {
        var at = id
        while (true) {
            excluded[at]?.let { return it }
            val slash = at.lastIndexOf('/')
            if (slash < 0) return null
            at = at.substring(0, slash)
        }
    }

    private fun compute(mapDir: File, run: Run): Report {
        val verdicts = verdicts(mapDir)
        val outcomes = if (run.note == null) outcomes(mapDir) else null
        // No writer at all: the filter never ran, as off the JUnit Platform.
        val observed = verdicts.observed.ifEmpty { listOf(AgentContract.RUN_NOT_DECIDED) }
        var failures = 0
        var kept = 0
        var unjudged = 0
        var skipped = 0
        var nanos = 0L
        val misses = mutableListOf<Pair<String, String>>()
        outcomes.orEmpty().forEach { (id, outcome) ->
            val excluded = excludedFor(id, verdicts.excludedById)
            if (outcome.failing != null) failures++
            when {
                excluded == null -> unjudged++
                excluded -> {
                    skipped++
                    nanos += outcome.nanos
                    outcome.failing?.let { misses += id to it }
                }
                outcome.failing != null -> kept++
            }
        }
        return Report(
            run, outcomes != null, observed.getOrNull(0), observed.getOrNull(1)?.ifEmpty { null },
            observed.getOrNull(2)?.ifEmpty { null },
            complete = outcomes != null && verdicts.whole && unjudged == 0, testsWithoutVerdict = unjudged,
            failures = failures, failuresKept = kept, misses = misses, wouldBeSkipped = skipped, skippedNanos = nanos,
        )
    }

    private fun json(report: Report): String = buildString {
        val run = report.run
        append("{\n")
        append("""  "version": 1,""").append('\n')
        append("""  "task": ${Json.string(run.taskPath)},""").append('\n')
        append("""  "commit": ${Json.string(run.commit)},""").append('\n')
        append("""  "mapCaptureCommit": ${Json.string(run.mapCaptureCommit)},""").append('\n')
        append("""  "note": ${Json.string(run.note)},""").append('\n')
        append("""  "outcomesRecorded": ${report.outcomesRecorded},""").append('\n')
        append("""  "observedOutcome": ${Json.string(report.observedOutcome)},""").append('\n')
        append("""  "fullRunKind": ${Json.string(report.fullRunKind)},""").append('\n')
        append("""  "refusalKind": ${Json.string(report.refusalKind)},""").append('\n')
        append("""  "complete": ${report.complete},""").append('\n')
        append("""  "testsWithoutVerdict": ${report.testsWithoutVerdict},""").append('\n')
        append("""  "failures": ${report.failures},""").append('\n')
        append("""  "failuresKept": ${report.failuresKept},""").append('\n')
        append("""  "wouldBeMisses": """)
        if (report.misses.isEmpty()) {
            append("[]")
        } else {
            append(
                report.misses.joinToString(",\n", "[\n", "\n  ]") { (test, outcome) ->
                    """    { "test": ${Json.string(test)}, "outcome": ${Json.string(outcome)} }"""
                }
            )
        }
        append(",\n")
        append("""  "wouldBeSkipped": ${report.wouldBeSkipped},""").append('\n')
        append("""  "recordedInstrumentedTestNanos": ${report.skippedNanos},""").append('\n')
        append("""  "caveats": """)
        append(CAVEATS.joinToString(",\n", "[\n", "\n  ]") { "    ${Json.string(it)}" })
        append("\n}\n")
    }

    /** The one console line that summarises [report]; never a rate. */
    fun line(report: Report): String {
        val path = report.run.taskPath
        report.run.note?.let { return "[yoriwake] $path: observed nothing, because $it." }
        if (!report.outcomesRecorded) {
            return "[yoriwake] $path: observed nothing, because this run recorded no test outcome."
        }
        val selection = when (report.observedOutcome) {
            AgentContract.RUN_NARROWED -> "selection would have narrowed"
            AgentContract.RUN_FULL ->
                "selection would have run everything (" + listOfNotNull(report.fullRunKind, report.refusalKind)
                    .joinToString(", ").ifEmpty { AgentContract.RUN_FULL } + ")"
            else -> "selection decided nothing (${report.observedOutcome})"
        }
        val failed = if (report.failures == 0) {
            "no test failed"
        } else {
            "${report.failures} tests failed, ${report.failuresKept} of them kept"
        }
        val misses = if (report.misses.size == 1) "1 would-be miss" else "${report.misses.size} would-be misses"
        val seconds = String.format(Locale.ROOT, "%.1f", report.skippedNanos / 1e9)
        val partial = if (report.complete) "" else {
            " Partial: ${report.testsWithoutVerdict} tests ran with no recorded verdict and are not counted as skipped."
        }
        return "[yoriwake] $path observed: $selection; $failed; $misses; ${report.wouldBeSkipped} tests would have " +
            "been skipped, $seconds s of recorded test time (instrumented, summed over forks; not a wall-clock " +
            "saving). ${AgentContract.OBSERVATION_FILE} lists them.$partial"
    }
}
