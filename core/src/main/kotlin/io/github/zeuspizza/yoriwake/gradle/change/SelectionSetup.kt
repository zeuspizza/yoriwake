package io.github.zeuspizza.yoriwake.gradle.change

import io.github.zeuspizza.yoriwake.gradle.RunPlan
import io.github.zeuspizza.yoriwake.gradle.capture.MapAge

/**
 * What a selecting run settles before its tests start, from the base widened to the map's age:
 * that it runs everything, that git gave it no change set, or the change set it selects from.
 * Every host settles it the same way; [SelectingStep] then carries out the answer.
 */
internal object SelectionSetup {

    sealed interface Outcome {
        /** Refuses with [kind] and [reason]; see [SelectingStep.runEverything] for [cause] and [clears]. */
        class RunEverything(val kind: RefusalKind, val reason: String, val cause: String, val clears: Boolean) : Outcome

        /** git could not report changes against [against]. */
        class NoChangeSet(val against: String) : Outcome {
            val reason get() = "git could not report changes against $against, so there is no change set to select from"
        }

        /** Selects from [paths], every changed path against [widened] and the map's [stamp]. */
        class Select(val widened: ChangeDetection.Base, val stamp: String?, val paths: List<String>) : Outcome
    }

    /**
     * [changedPaths] is git's answer for one base, null when it has none; [counted] is how many of
     * the paths changed against a base count toward [largeChange], after the build's ignores.
     */
    fun settle(
        widening: RunPlan.Widening,
        changedPaths: (String) -> List<String>?,
        largeChange: Int,
        counted: (base: String, changed: List<String>) -> Int,
    ): Outcome {
        val age = widening.age
        val drift = widening.drift
        if (age is MapAge.Unknown) {
            val cause = if (age.kind == RefusalKind.SNAPSHOT_ABSENT) {
                "A map captured before snapshots were recorded looks like this"
            } else {
                "A rebase, a force-push, a map cached from a different history, or one 0.1.0 captured " +
                    "under a test filter in its build script all look like this"
            }
            return Outcome.RunEverything(age.kind, age.reason, cause, clears = true)
        }
        val widened = (age as MapAge.Known).base
        val against = widened.ref
        val stamp = age.stamp
        val moved = (drift as? WorkingTree.Drift.Moved)?.paths
        // Union only: the snapshot adds what `git diff` from the stamp cannot see (a reverted
        // dirty edit, an ignored fixture). A tree git could not list is no change set at all.
        // The diff from the capture commit too: a map captured off this history (another branch, a
        // reset) recorded content the widened base may share with the tree.
        val paths = listOfNotNull(against, stamp)
            .map { changedPaths(it) }
            .takeUnless { null in it }?.flatMap { it.orEmpty() }
            ?.let { tracked -> moved?.let { (tracked + it).distinct() } }
            ?: return Outcome.NoChangeSet(against)

        // Before any analysis: a change this size would end in a full run, so analysing it only costs.
        // Counted against the base the run was asked about, which the map's age cannot grow.
        val asked = widening.requested ?: widened
        changedPaths(asked.ref)?.let { askedPaths ->
            val changed = (askedPaths + moved.orEmpty()).distinct()
            LargeChange.refusal(counted(asked.ref, changed), largeChange, asked.ref)?.let { reason ->
                return Outcome.RunEverything(
                    RefusalKind.LARGE_CHANGE, reason, "Analysing a change this size costs more than it saves", clears = false,
                )
            }
        }
        return Outcome.Select(widened, stamp, paths)
    }
}
