package io.github.zeuspizza.yoriwake.gradle.report

import io.github.zeuspizza.yoriwake.gradle.capture.writeAtomically
import java.io.File
import java.util.Locale

/** Break-even: test time selection saves against what the plugin costs at configuration. */
internal object Payback {

    /**
     * The recorded per-test time in a map. [workers] is null when not recorded rather than assumed
     * to be one: the figure is a sum across workers.
     */
    data class RecordedTime(
        val nanos: Long,
        val records: Int,
        /** Records that ran and did not say how long -- not records that took no time. */
        val withoutDuration: Int,
        val workers: Int?,
        /** Test ids timed more than once (e.g. by a retry plugin), counted once. */
        val duplicated: Int = 0,
    )

    /**
     * What this plugin cost this build's configuration, across every project. Kept in nanoseconds:
     * whole-millisecond division truncates a sub-millisecond per-task cost to 0, so every suite
     * would appear to pay. Never cached into the map: it belongs to this machine at this moment.
     */
    data class ConfigureCost(val nanos: Long, val testTasks: Int) {
        /** Never zero: the only production caller counts a task before it configures one. */
        val perTestTaskNanos: Long get() = nanos / testTasks.coerceAtLeast(1)
    }

    /** Break-even on both axes, or a refusal naming the one axis that is missing. */
    fun paybackLines(
        time: RecordedTime?,
        distribution: Audit.Distribution,
        toll: Audit.Toll,
        cost: ConfigureCost?,
    ): List<String> {
        val prefix = "BREAK-EVEN:"
        if (time == null) {
            return listOf(
                "PAYBACK NOT ANSWERED: this map records no per-test durations, so what selection " +
                    "would save cannot be stated in seconds. The toll above is one axis of two.",
            )
        }
        val workers = time.workers ?: return listOf(
            "PAYBACK NOT ANSWERED: the map does not record how many workers its durations were " +
                "summed across, so ${seconds(time.nanos)} of recorded test time cannot be turned " +
                "into wall clock. Capture again with this version of the plugin, which records it.",
        )
        if (cost == null) {
            return listOf(
                "PAYBACK NOT ANSWERED: no configure cost has been measured, so what the plugin " +
                    "costs when it selects nothing is unknown. It is counted during configuration, " +
                    "so a build that has never configured this project -- or one whose plugin " +
                    "loads through more than one classloader -- has nothing to report.",
            )
        }
        // Recorded time above a real clock over the same suite means the durations span more JVMs
        // than the worker count says, or more than one run; dividing it would be confidently wrong.
        val instrumented = toll.instrumented
        val wallClockNanos = (time.nanos / workers)
        if (instrumented != null && wallClockNanos > instrumented * 1_000_000_000L) {
            return listOf(
                "PAYBACK NOT ANSWERED: the map holds more recorded test time " +
                    "(${seconds(time.nanos)} across $workers worker(s) = " +
                    "${seconds(wallClockNanos)}) than the instrumented run took " +
                    "(${seconds((instrumented * 1_000_000_000L).toLong())}). That is the signature " +
                    "of durations summed across more workers than the map records, or of records " +
                    "from more than one run. Dividing it down would be arithmetic on a figure " +
                    "already known to be wrong.",
                "This check has slack, and the slack is one-sided: the run it compares against is " +
                    "a whole build -- compile, configuration and JVM startup as well as the " +
                    "suite -- so recorded time BELOW it is not proof the sum is sound on a " +
                    "compile-heavy build. Nothing here measures the suite's own portion of that " +
                    "run, and a fraction nobody measured is not a bound.",
            )
        }
        // `meanShare` counts tests, not seconds: this assumes selected tests are of average length.
        val savedNanos = (wallClockNanos * (1.0 - distribution.meanShare)).toLong()
        val costPerBuild = cost.perTestTaskNanos
        // `meanShare` is a share of every record, but the seconds sum only those with a duration.
        val sample = if (time.withoutDuration > 0) {
            " ${time.records} of ${time.records + time.withoutDuration} test records carried a " +
                "duration, so the saving is a share of those."
        } else {
            ""
        }
        return listOf(
            "$prefix a change selecting ${percent(distribution.meanShare)} of this suite skips " +
                "about ${seconds(savedNanos)} of test time (${seconds(time.nanos)} recorded " +
                "across $workers worker(s)), against ${seconds(costPerBuild)} of configure cost " +
                "per `Test` task when this build last configured (${seconds(cost.nanos)} over " +
                "${cost.testTasks} task(s); a build that reuses its configuration-cache entry " +
                "replays this figure rather than measuring a new one).$sample",
            "Both figures are optimistic in the same direction: the saving is measured INSIDE the " +
                "instrumented JVM so it includes the capture toll, and it counts tests rather " +
                "than seconds -- so it assumes the tests a change selects are of average length, " +
                "which a suite with a few long integration tests breaks. The configure cost is a " +
                "lower bound: it times this plugin's own work, not Gradle's cost of carrying the " +
                "tasks it registers, and it is per build -- an included build's configuration is " +
                "its own and never reaches this figure. Neither number is a measurement of what " +
                "this plugin costs a build." +
                if (time.duplicated > 0) {
                    " ${time.duplicated} test id(s) carried more than one timed record, counted " +
                        "once each; something re-ran tests during the capture."
                } else {
                    ""
                },
            if (savedNanos > costPerBuild) {
                "On these numbers the saving is the larger of the two, so selection can pay here " +
                    "-- but only across builds that actually skip tests. A build that selects " +
                    "everything pays the configure cost and saves nothing."
            } else {
                "On these numbers the configure cost is the larger of the two, so selection " +
                    "cannot pay on this suite as it stands."
            },
        )
    }

    /** One `Test` task's contribution to a build-wide answer: both figures, or what was missing. */
    data class TaskPayback(
        val taskPath: String,
        /** Wall-clock test time this task's map records, already divided by its worker count. */
        val savedNanos: Long? = null,
        /** What this plugin cost that task's project to configure, per `Test` task. */
        val costNanos: Long? = null,
        /** The missing input; null exactly when both figures are present. */
        val missing: String? = null,
    ) {
        val answered: Boolean get() = savedNanos != null && costNanos != null
    }

    /**
     * Whether selection pays for itself across this build's `Test` tasks, or a refusal. Without
     * the forced-run share the net figure is refused, and per-task refusals are always listed.
     *
     * @param forcedShare the fraction of runs that execute everything, as typed. A string, so
     *   "unparseable" and "never supplied" stay distinct.
     * @param configured how many `Test` tasks this build configured, the composition's denominator.
     */
    fun aggregateLines(
        tasks: List<TaskPayback>,
        forcedShare: String?,
        configured: Int? = null,
    ): List<String> {
        val share = forcedShare?.let {
            it.toDoubleOrNull() ?: return listOf(
                "BUILD PAYBACK NOT ANSWERED: a forced-run share of \"$it\" is not a number, so " +
                    "nothing is weighted by it.",
            )
        }
        if (tasks.isEmpty()) {
            return listOf(
                "BUILD PAYBACK NOT ANSWERED: no `Test` task in this build has left a payback " +
                    "record, so there is nothing to aggregate.",
                CACHED,
            )
        }
        val answered = tasks.filter(TaskPayback::answered)
        val refused = tasks.filterNot(TaskPayback::answered)
        val composition = "${answered.size} of ${tasks.size} `Test` task(s) with a record " +
            "answered" + (configured?.let { " (this build configured $it)" } ?: "") +
            if (refused.isEmpty()) "." else
                "; " + refused.joinToString("; ") {
                    "${it.taskPath} could not (${it.missing ?: "no reason recorded"})"
                }
        if (answered.isEmpty()) {
            return listOf(
                "BUILD PAYBACK NOT ANSWERED: no `Test` task in this build produced both axes.",
                composition,
                CACHED,
            )
        }
        val saved = answered.sumOf { it.savedNanos ?: 0L }
        val cost = answered.sumOf { it.costNanos ?: 0L }
        if (share == null) {
            return listOf(
                "BUILD PAYBACK NOT ANSWERED: ${seconds(saved)} of test time could be skipped " +
                    "against ${seconds(cost)} of configure cost, but how often this build runs " +
                    "everything is UNKNOWN -- and the saving is only collected on the builds that " +
                    "narrow, and this version does not measure that share.",
                composition,
                CACHED,
                NOT_A_SUITE_WALL_CLOCK,
            )
        }
        if (share !in 0.0..1.0) {
            return listOf(
                "BUILD PAYBACK NOT ANSWERED: a forced-run share of $share is not a fraction " +
                    "between 0 and 1, so nothing is weighted by it.",
                composition,
            )
        }
        // The cost is paid on every build; the saving only on the ones that narrow.
        val expected = (saved * (1.0 - share)).toLong()
        return listOf(
            "BUILD PAYBACK: across ${answered.size} `Test` task(s), a build that narrows skips " +
                "about ${seconds(saved)} of test time -- but ${percent(share)} of builds run " +
                "everything, so the EXPECTED saving is ${seconds(expected)} a build, against " +
                "${seconds(cost)} of configure cost paid on every build including the forced ones.",
            breakEven(saved, cost, share, expected),
            composition,
            CACHED,
            NOT_A_SUITE_WALL_CLOCK,
        )
    }

    /** Each task's contribution, beside its map; a sibling's may date from another commit. */
    private const val TASK_FILE = "payback.tsv"

    /** Records this task's contribution where a later audit of any task in the build can read it. */
    fun writeTask(mapDir: File, task: TaskPayback) {
        runCatching {
            mapDir.mkdirs()
            // Atomic: a torn write leaves a shorter file that still parses, so an interrupted task
            // would read as one that refused.
            writeAtomically(
                File(mapDir, TASK_FILE),
                buildString {
                    append("task\t").append(task.taskPath).append('\n')
                    task.savedNanos?.let { append("saved\t").append(it).append('\n') }
                    task.costNanos?.let { append("cost\t").append(it).append('\n') }
                    task.missing?.let { append("missing\t").append(it.replace('\n', ' ')).append('\n') }
                }
            )
        }
    }

    /** Every recorded contribution; a task whose audit never ran is absent, not a refusal. */
    fun readTasks(mapRoot: File): List<TaskPayback> {
        val dirs = mapRoot.listFiles()?.filter(File::isDirectory).orEmpty().sortedBy { it.name }
        return dirs.mapNotNull { dir ->
            val file = File(dir, TASK_FILE).takeIf(File::isFile) ?: return@mapNotNull null
            val fields = runCatching {
                file.readLines().mapNotNull { line ->
                    line.split('\t', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
                }.toMap()
            }.getOrNull()
            // A file that exists but cannot be read is a refusal, not a task that never ran.
                ?: return@mapNotNull TaskPayback(
                    dir.name, missing = "its record at $file could not be read",
                )
            val path = fields["task"]
                ?: return@mapNotNull TaskPayback(
                    dir.name, missing = "its record names no task, so it was written mid-run",
                )
            TaskPayback(
                taskPath = path,
                savedNanos = fields["saved"]?.toLongOrNull(),
                costNanos = fields["cost"]?.toLongOrNull(),
                missing = fields["missing"],
            )
        }
    }

    /** The verdict and the forced-run share it turns on, or that no share turns it. */
    private fun breakEven(saved: Long, cost: Long, forcedShare: Double, expected: Long): String {
        if (cost >= saved) {
            return "On these numbers selection cannot pay at ANY forced-run share: the configure " +
                "cost (${seconds(cost)}) is at least the whole saving a build that narrows " +
                "everything would collect (${seconds(saved)})."
        }
        val share = 1.0 - cost.toDouble() / saved
        return if (expected > cost) {
            "On these numbers selection pays for itself at a forced-run share of " +
                "${percent(forcedShare)}. It stops paying above ${percent(share)}."
        } else {
            "On these numbers selection does NOT pay for itself at a forced-run share of " +
                "${percent(forcedShare)}. It begins to pay below ${percent(share)}."
        }
    }

    /** Emitted with the aggregate so the reader of the number, not just the maintainer, sees it. */
    private const val CACHED =
        "Each task's contribution was recorded when that task's audit last ran, so this is as " +
            "current as its stalest member, and a task whose audit has never run is absent rather " +
            "than counted."

    private const val NOT_A_SUITE_WALL_CLOCK =
        "NOT a suite wall clock, exactly as the per-task figure is not: both sides are measured " +
            "inside the instrumented JVM, they count tests rather than seconds, and they exclude " +
            "compile, JVM startup and discovery. This is the time selection could act on."

    /**
     * Null rather than zero when no record carries a duration: an untimed map is not a suite that
     * runs instantly, and zero would make the verdict say the plugin never pays.
     */
    fun recordedTime(
        timedRecords: Int,
        durationNanos: Long,
        withoutDuration: Int,
        duplicated: Int,
        workers: Int?,
    ): RecordedTime? {
        if (timedRecords == 0) return null
        return RecordedTime(
            nanos = durationNanos,
            records = timedRecords,
            withoutDuration = withoutDuration,
            workers = workers,
            duplicated = duplicated,
        )
    }

    /** The recorded time with its caveats alongside, so it cannot be quoted as a wall clock. */
    fun recordedTimeLines(time: RecordedTime?, testRecords: Int): List<String> {
        if (time == null) {
            // Distinct from "this suite is fast". The reader is told which of the two they have.
            return if (testRecords == 0) emptyList() else listOf(
                "The map records no per-test durations, so how long these tests take is UNKNOWN " +
                    "here -- which is not the same as fast. A map captured before the agent timed " +
                    "tests, or a run whose timer failed, both look like this.",
            )
        }
        val workers = time.workers
            ?.let { if (it == 1) "1 worker" else "$it workers" }
            // Stated rather than defaulted: see RecordedTime.workers.
            ?: "an unknown number of workers"
        return buildList {
            add(
                "${seconds(time.nanos)} of recorded test time across ${time.records} tests, " +
                    "summed across $workers.",
            )
            add(
                "NOT a suite duration: it is measured inside the instrumented JVM, so it carries " +
                    "the capture toll, and it excludes compile, configuration, JVM startup and " +
                    "discovery. It is the time selection could act on, not the time a build takes.",
            )
            if (time.withoutDuration > 0) {
                add(
                    "${time.withoutDuration} test record(s) carry no duration and are not in that " +
                        "sum -- a test that did not say how long is not a test that took no time.",
                )
            }
        }
    }

    /**
     * A duration a reader cannot mistake for zero. `Locale.ROOT` because the figure also goes into
     * `audit.json`, where a decimal comma would not parse.
     */
    fun seconds(nanos: Long): String = when {
        nanos >= 1_000_000_000L -> String.format(Locale.ROOT, "%.1fs", nanos / 1_000_000_000.0)
        // `%dms` would truncate anything under a millisecond to "0ms".
        nanos >= 1_000_000L -> String.format(Locale.ROOT, "%dms", nanos / 1_000_000L)
        else -> String.format(Locale.ROOT, "%.2fms", nanos / 1_000_000.0)
    }

    fun json(t: RecordedTime) = buildString {
        append("{\n")
        append("""    "nanos": ${t.nanos},""").append('\n')
        append("""    "records": ${t.records},""").append('\n')
        append("""    "recordsWithoutDuration": ${t.withoutDuration},""").append('\n')
        // Null when unrecorded, so a consumer does not guess the divisor.
        append("""    "workers": ${t.workers ?: "null"},""").append('\n')
        // In the payload as well as the prose, because a JSON consumer never reads the prose.
        append("""    "duplicatedIds": ${t.duplicated},""").append('\n')
        append("""    "isSuiteDuration": false,""").append('\n')
        append("""    "instrumented": true""").append('\n')
        append("  }")
    }
}

/**
 * A share as a percentage in `Locale.ROOT`: it is copied into issues and grepped by scripts,
 * none of which survive a decimal comma.
 */
internal fun percent(share: Double) = String.format(Locale.ROOT, "%.1f%%", share * 100)
