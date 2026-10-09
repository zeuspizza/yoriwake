package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.gradle.capture.MapAge
import io.github.zeuspizza.yoriwake.gradle.change.ChangeDetection.CommitScan
import io.github.zeuspizza.yoriwake.gradle.change.RefusalKind
import io.github.zeuspizza.yoriwake.gradle.change.WorkingTree

/**
 * What kind of run one test task makes: the single answer every wiring site reads, so no two of
 * them can disagree about whether a run selects.
 *
 * A run asked to select can be declined: asked on the command line or in a commit message to run
 * everything, it records instead, and says why.
 */
internal class RunPlan(
    val kind: Kind,
    /** What the invocation asked for, before any decline. */
    val asked: Kind,
    /** Where a run asked to select compares from; null on a run asked to record. */
    val widening: Widening?,
    /** Every decline that held, in the order they are checked; the first is the run's refusal. */
    val declines: List<Decline> = emptyList(),
    /** Lines the run prints about commits that look like a request and are not one. */
    val notes: List<String> = emptyList(),
) {

    enum class Kind {
        /** Runs every test, captures and dates the map. The default. */
        RECORD,

        /** Runs what selection keeps; captures only when it falls back to running everything. */
        SELECT,
    }

    /** A reason a run asked to select runs everything instead. */
    data class Decline(val kind: RefusalKind, val reason: String)

    /**
     * The base widened to the map's age, or why that age is unknown, and how the working tree moved
     * since the capture: worked out once, for the declines and then for the change set.
     */
    class Widening(val age: MapAge, val drift: WorkingTree.Drift?)

    val selecting: Boolean get() = kind == Kind.SELECT

    companion object {
        /**
         * [widening] and [scan] are asked only for a run asked to select, so a recording run asks
         * git nothing more than it did.
         */
        fun resolve(settings: Settings, widening: () -> Widening, scan: (String) -> CommitScan): RunPlan {
            if (!settings.select) {
                return RunPlan(Kind.RECORD, Kind.RECORD, widening = null)
            }
            val widened = widening()
            val (declines, notes) = declines(settings.fullRun, widened.age, scan)
            return RunPlan(if (declines.isEmpty()) Kind.SELECT else Kind.RECORD, Kind.SELECT, widened, declines, notes)
        }

        /**
         * The declines that hold for a run asked to select, and the lines it prints. The commit
         * messages are read only over a range known to reach back to the map's capture: with an
         * unknown age the run already runs everything, under its own refusal, and with no map at all
         * it runs everything and records.
         */
        fun declines(fullRun: Boolean, age: MapAge, scan: (String) -> CommitScan): Pair<List<Decline>, List<String>> {
            val declines = mutableListOf<Decline>()
            val notes = mutableListOf<String>()
            if (fullRun) {
                declines += Decline(RefusalKind.FULL_RUN_REQUESTED, "a full run was requested with -P${Settings.FULL_RUN}")
            }
            if (age is MapAge.Known && age.stamp != null) {
                when (val read = scan(age.base.ref)) {
                    is CommitScan.Read -> {
                        read.marked?.let {
                            declines += Decline(
                                RefusalKind.FULL_RUN_COMMIT,
                                "commit ${it.sha.take(12)} (${it.subject}) asks for a full run with a `yoriwake: full` line",
                            )
                        }
                        read.hints.forEach {
                            notes += "commit ${it.sha.take(12)} (${it.subject}) has a `yoriwake:` line that is not " +
                                "`yoriwake: full`, so it asks for nothing"
                        }
                    }
                    is CommitScan.Failed -> declines += Decline(
                        RefusalKind.DECLINE_UNDETERMINED,
                        "${read.reason}, so whether a commit asks for a full run is unknown",
                    )
                }
            }
            return declines to notes
        }
    }
}
