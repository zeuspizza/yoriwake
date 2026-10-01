package io.github.zeuspizza.yoriwake.gradle.report

import io.github.zeuspizza.yoriwake.gradle.capture.writeAtomically
import io.github.zeuspizza.yoriwake.gradle.change.RefusalKind
import io.github.zeuspizza.yoriwake.gradle.wiring.ScopeOutcome
import java.io.File

/**
 * Answers "will selection help me?" from the map alone: inverting it gives, per class, how many
 * tests execute that class, and that distribution is the selectability of the suite.
 *
 * Reachability, not savings: it counts tests, not seconds, and says the toll is unknown without two
 * timed runs. Most terminal states are refusals, because shares over a map that does not describe
 * the suite read healthier than the suite is.
 */
internal object Audit {

    const val AUDIT_FILE = "audit.json"

    /** Bumped when the payload's shape changes, so a consumer can tell which one it is reading. */
    const val PAYLOAD_VERSION = 1

    /** How many most-reached classes to list. */
    const val HUB_TOP = 10

    /**
     * Largest share of tests with no classes before the shares stop describing the suite: such
     * tests drop out of the inversion yet always run.
     */
    const val DEFAULT_UNATTRIBUTABLE_CEILING = 0.25

    /** Relative gap two timings need before the difference is evidence rather than noise. */
    const val DEFAULT_NOISE_FLOOR = 0.18

    enum class State(val token: String) {
        NO_CONCLUSION("NO CONCLUSION"),
        NARROWING_ONLY("NARROWING ONLY"),
        WORTH_IT("WORTH IT"),
        DO_NOT_ENABLE("DO NOT ENABLE"),
    }

    /** A class and how much of the suite reaches it. */
    data class Hub(val className: String, val tests: Int, val share: Double)

    /**
     * Something that stops this task narrowing, and its remedy or null. Reported beside the
     * terminal state, never instead of it: clearing blockers does not imply payback.
     */
    data class Blocker(val token: String, val detail: String, val remedy: String?) {
        constructor(kind: BlockerKind, detail: String, remedy: String?) : this(kind.token, detail, remedy)

        /** One line each: both are printed as one indented line under the token. */
        init {
            require(!detail.contains('\n') && remedy?.contains('\n') != true) {
                "a blocker's text is one line: $token"
            }
        }
    }

    /**
     * Every kind of [Blocker], as the token `audit.json` and the console print. [declined] marks the
     * ones meaning the plugin never configured the task, so capturing again cannot produce a map.
     */
    enum class BlockerKind(val token: String, val declined: Boolean = false) {
        GIT_UNAVAILABLE("git-unavailable"),
        SCOPE_NOT_APPLIED("scope-not-applied", declined = true),
        NO_HOST_PLUGIN(ScopeOutcome.NotApplied.Kind.NO_HOST_PLUGIN.token, declined = true),
        ISOLATED_PROJECTS(ScopeOutcome.NotApplied.Kind.ISOLATED_PROJECTS.token, declined = true),
        GRADLE_TOO_OLD(ScopeOutcome.NotApplied.Kind.GRADLE_TOO_OLD.token, declined = true),
        JACOCO_DISABLED(ScopeOutcome.NotApplied.Kind.JACOCO_DISABLED.token, declined = true),
        KOTLIN_MULTIPLATFORM(ScopeOutcome.NotApplied.Kind.KOTLIN_MULTIPLATFORM.token, declined = true),
        FRAMEWORK_UNKNOWN("framework-unknown"),
        NOT_ON_JUNIT_PLATFORM("not-on-junit-platform"),
        RECORDED_NO_COVERAGE("recorded-no-coverage"),
        DECODE_REFUSED("decode-refused"),
        IN_JVM_PARALLELISM(RefusalKind.IN_JVM_PARALLELISM.token),
        SMAP_ABSENT(RefusalKind.SMAP_ABSENT.token),
        CLASSPATH_UNRESOLVED_HERE("classpath-unresolved-here"),
        INLINE_SCAN_INCOMPLETE("inline-scan-incomplete"),
        CLASSES_ABSENT_FROM_MAP("classes-absent-from-map"),
    }

    /**
     * What the last run of the task recorded about itself. Read from a file beside the map because
     * Gradle often settles the test framework after this plugin is applied.
     */
    data class TaskFacts(
        val framework: String?,
        val parallelism: String?,
        /**
         * Whether the last capture run recorded any coverage, or null when none has run. A task
         * the listener never reaches captures nothing on every run, so "capture one" cannot help.
         */
        val recordedCoverage: Boolean? = null,
        /** Worker JVMs the last capture decoded; the map's durations are summed across them. */
        val workers: Int? = null,
        /**
         * Why the last decode refused to merge a run's records, or null. The map then keeps
         * answering from older records; distinct from "recorded no coverage".
         */
        val decodeRefused: String? = null,
    ) {
        companion object {
            const val FILE = "task-facts"

            /** Names the JUnit Platform, which is the only framework this tool can deselect within. */
            const val PLATFORM = "junit-platform"

            fun read(mapDir: File): TaskFacts {
                val file = File(mapDir, FILE)
                if (!file.isFile) return TaskFacts(null, null)
                val fields = runCatching {
                    file.readLines()
                        .mapNotNull { line ->
                            line.split("=", limit = 2).takeIf { it.size == 2 }
                                ?.let { it[0].trim() to it[1].trim() }
                        }
                        .toMap()
                }.getOrElse { return TaskFacts(null, null) }
                return TaskFacts(
                    framework = fields["framework"]?.takeIf { it.isNotEmpty() },
                    // Written as the empty string when there is none.
                    parallelism = fields["parallelism"]?.takeIf { it.isNotEmpty() },
                    recordedCoverage = fields["recordedCoverage"]?.toBooleanStrictOrNull(),
                    workers = fields["workers"]?.toIntOrNull()?.takeIf { it > 0 },
                    decodeRefused = fields["decodeRefused"]?.takeIf { it.isNotEmpty() },
                )
            }

            fun write(mapDir: File, framework: String, parallelism: String?) {
                update(mapDir) { previous ->
                    // Carried forward: written in `doFirst`, before this run records anything.
                    previous.copy(framework = framework, parallelism = parallelism)
                }
            }

            /** Recorded by the capture path once it knows, which is after the run. */
            fun recordCoverage(mapDir: File, recorded: Boolean) {
                update(mapDir) { it.copy(recordedCoverage = recorded) }
            }

            /** Recorded by the decode finalizer, which is the only place that counts the workers. */
            fun recordWorkers(mapDir: File, workers: Int) {
                update(mapDir) { it.copy(workers = workers) }
            }

            /**
             * Records why a decode refused to merge, or clears the fact when one succeeds. A run
             * that decodes nothing does not clear it, since it is no evidence the merge works.
             */
            fun recordDecodeRefusal(mapDir: File, reason: String?) {
                update(mapDir) { it.copy(decodeRefused = reason?.lines()?.firstOrNull()?.trim()) }
            }

            /** Read, change one fact, write the whole file back, so no writer drops another's. */
            private fun update(mapDir: File, change: (TaskFacts) -> TaskFacts) {
                runCatching {
                    val next = change(read(mapDir))
                    mapDir.mkdirs()
                    // Atomic: several writers rewrite this file during one run, and a torn write
                    // loses facts silently.
                    writeAtomically(
                        File(mapDir, FILE),
                        "framework=${next.framework.orEmpty()}\n" +
                            "parallelism=${next.parallelism.orEmpty()}\n" +
                            "recordedCoverage=${next.recordedCoverage?.toString().orEmpty()}\n" +
                            "workers=${next.workers?.toString().orEmpty()}\n" +
                            // One line: the file is `key=value` per line.
                            "decodeRefused=${next.decodeRefused.orEmpty().replace('\n', ' ')}\n",
                    )
                }
            }
        }
    }

    /** What was read, on every terminal state, so a refusal shows how much it looked at. */
    data class Counts(
        val lines: Int,
        val malformed: Int,
        val testRecords: Int,
        val nonTestRecords: Int,
    )

    data class Distribution(
        val tests: Int,
        val classes: Int,
        val unattributableTests: Int,
        val unattributableShare: Double,
        val meanShare: Double,
        val medianTestsPerClass: Int,
        val medianShare: Double,
        val p90Share: Double,
        val p99Share: Double,
        val maxShare: Double,
        val hubClassesOverHalf: Int,
        val startupClasses: Int,
        val hubs: List<Hub>,
    )

    /**
     * Where this map came from. `capture-commit` is the host project's commit; the map records no
     * plugin version, so a map from an older plugin cannot be told apart from a current one.
     */
    data class Provenance(val captureCommit: String?, val ageDays: Long?)

    /** Two timed full runs, or why there are not two. Never persisted: a toll is per machine. */
    class Toll private constructor(
        val instrumented: Double?,
        val uninstrumented: Double?,
        val floor: Double,
        val failedRun: String?,
        val failureDetail: String?,
    ) {
        /** The overhead as a share of an uninstrumented run, or null when there is no pair. */
        val share: Double? =
            if (instrumented != null && uninstrumented != null && uninstrumented > 0.0) {
                (instrumented - uninstrumented) / uninstrumented
            } else {
                null
            }

        companion object {
            fun measured(
                instrumented: Double,
                uninstrumented: Double,
                floor: Double = DEFAULT_NOISE_FLOOR,
            ) = Toll(instrumented, uninstrumented, floor, null, null)

            /** A failed run is never a time. */
            fun failed(run: String, detail: String) =
                Toll(null, null, DEFAULT_NOISE_FLOOR, run, detail)
        }
    }

    data class Result(
        val state: State,
        val headline: String,
        val lines: List<String>,
        val counts: Counts,
        val distribution: Distribution?,
        val provenance: Provenance,
        /** What stops this task narrowing. Reported on every terminal state, refusals included. */
        val blockers: List<Blocker> = emptyList(),
        /** See [Payback.RecordedTime]. Null when the map holds no usable duration. */
        val recordedTime: Payback.RecordedTime? = null,
    )

    /**
     * How little of a module's compiled classes the map may cover before that is a finding. The
     * selector cannot tell "nothing covers this" from "never recorded", so such changes force.
     * Loose on purpose: interfaces carry no probes and loaded-but-unexecuted classes are invisible
     * to JaCoCo.
     */
    const val MAP_COVERAGE_FLOOR = 0.5
}
