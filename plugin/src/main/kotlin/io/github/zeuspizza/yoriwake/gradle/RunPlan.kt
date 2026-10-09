package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.agent.select.Glob
import io.github.zeuspizza.yoriwake.gradle.capture.MapAge
import io.github.zeuspizza.yoriwake.gradle.change.ChangeDetection.CheckedOut
import io.github.zeuspizza.yoriwake.gradle.change.ChangeDetection.CommitScan
import io.github.zeuspizza.yoriwake.gradle.change.RefusalKind
import io.github.zeuspizza.yoriwake.gradle.change.WorkingTree
import org.gradle.api.InvalidUserDataException

/**
 * What kind of run one test task makes: the single answer every wiring site reads, so no two of
 * them can disagree about whether a run selects.
 *
 * A run asked to select can be declined: asked on the command line, by a branch the build lists, or
 * in a commit message to run everything, it records instead, and says why.
 */
internal class RunPlan(
    val kind: Kind,
    /** What the invocation asked for, before any decline. */
    val asked: Kind,
    /** Where a run asked to select compares from; null on a run asked to record. */
    val widening: Widening?,
    /** Every decline that held, in the order they are checked; the first is the run's refusal. */
    val declines: List<Decline> = emptyList(),
    /**
     * Lines the run prints: about commits that look like a request and are not one, and what was
     * checked out when a branch list is set and the run still selects.
     */
    val notes: List<String> = emptyList(),
    /** What was checked out, when a branch list is set and git could say; null otherwise. */
    val branch: String? = null,
) {

    enum class Kind {
        /** Runs every test, captures and dates the map. The default. */
        RECORD,

        /** Runs what selection keeps; captures only when it falls back to running everything. */
        SELECT,
    }

    /** A reason a run asked to select runs everything instead. */
    data class Decline(val kind: RefusalKind, val reason: String)

    /** The declines that held, the lines the run prints, and the branch detected, as [RunPlan] holds them. */
    data class Declines(val declines: List<Decline>, val notes: List<String>, val branch: String?)

    /**
     * The base widened to the map's age, or why that age is unknown, and how the working tree moved
     * since the capture: worked out once, for the declines and then for the change set.
     */
    class Widening(val age: MapAge, val drift: WorkingTree.Drift?)

    val selecting: Boolean get() = kind == Kind.SELECT

    companion object {
        /**
         * [widening], [scan] and [checkedOut] are asked only for a run asked to select, and
         * [checkedOut] only when [fullRunBranches] lists a branch, so a recording run asks git
         * nothing more than it did.
         */
        fun resolve(
            settings: Settings,
            widening: () -> Widening,
            scan: (String) -> CommitScan,
            fullRunBranches: List<String> = emptyList(),
            checkedOut: () -> CheckedOut = { error("the branch was asked for with no branch listed") },
        ): RunPlan {
            if (!settings.select) {
                return RunPlan(Kind.RECORD, Kind.RECORD, widening = null)
            }
            val widened = widening()
            val (declines, notes, branch) = declines(settings.fullRun, fullRunBranches, checkedOut, widened.age, scan)
            return RunPlan(
                if (declines.isEmpty()) Kind.SELECT else Kind.RECORD, Kind.SELECT, widened, declines, notes, branch,
            )
        }

        /**
         * The build's `fullRunBranches`, trimmed. An empty entry names no branch, and a star alone
         * would switch selection off on every branch while CI still asks for it: both fail the build.
         */
        fun fullRunBranches(declared: List<String>, owner: String): List<String> =
            declared.map(String::trim).onEach { entry ->
                if (entry.isEmpty() || entry.all { it == '*' }) {
                    throw InvalidUserDataException(
                        "[yoriwake] $owner: fullRunBranches holds '$entry', which " +
                            (if (entry.isEmpty()) "names no branch" else "matches every branch, so no run would ever select") +
                            ". List the branch names that should always run in full, such as fullRunBranches.add(\"main\")."
                    )
                }
            }

        /** The entry of [fullRunBranches] that [name] matches whole, or null. */
        fun listedAs(fullRunBranches: List<String>, name: String): String? =
            fullRunBranches.firstOrNull { Glob.matches(it, name) }

        /**
         * The declines that hold for a run asked to select, and the lines it prints. The commit
         * messages are read only over a range known to reach back to the map's capture: with an
         * unknown age the run already runs everything, under its own refusal, and with no map at all
         * it runs everything and records.
         */
        fun declines(
            fullRun: Boolean,
            fullRunBranches: List<String>,
            checkedOut: () -> CheckedOut,
            age: MapAge,
            scan: (String) -> CommitScan,
        ): Declines {
            val declines = mutableListOf<Decline>()
            val notes = mutableListOf<String>()
            if (fullRun) {
                declines += Decline(RefusalKind.FULL_RUN_REQUESTED, "a full run was requested with -P${Settings.FULL_RUN}")
            }
            var branch: String? = null
            if (fullRunBranches.isNotEmpty()) {
                when (val head = checkedOut()) {
                    is CheckedOut.Failed -> declines += Decline(
                        RefusalKind.DECLINE_UNDETERMINED,
                        "${head.reason}, so whether a branch fullRunBranches lists is checked out is unknown",
                    )
                    is CheckedOut.Branch -> {
                        branch = "on branch ${head.name}"
                        listedAs(fullRunBranches, head.name)?.let { entry ->
                            declines += Decline(RefusalKind.FULL_RUN_BRANCH, "$branch, and fullRunBranches lists `$entry`")
                        }
                    }
                    is CheckedOut.Detached -> {
                        val match = head.containing.firstNotNullOfOrNull { ref ->
                            listedAs(fullRunBranches, ref.name)?.let { ref.shown to it }
                        }
                        branch = match?.let { "detached; ${it.first} contains HEAD" }
                            ?: "detached; no listed branch contains HEAD"
                        match?.let { (_, entry) ->
                            declines += Decline(RefusalKind.FULL_RUN_BRANCH, "$branch, and fullRunBranches lists `$entry`")
                        }
                    }
                }
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
            if (declines.isEmpty() && branch != null) {
                notes += "not a full-run branch ($branch), so selection goes ahead"
            }
            return Declines(declines, notes, branch)
        }
    }
}
