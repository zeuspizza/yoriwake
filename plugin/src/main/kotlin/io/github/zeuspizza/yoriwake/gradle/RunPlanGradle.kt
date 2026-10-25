package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.gradle.change.ChangeDetection.CheckedOut
import io.github.zeuspizza.yoriwake.gradle.change.ChangeDetection.CommitScan
import org.gradle.api.InvalidUserDataException

/** [RunPlan.resolve] for this invocation's [settings], each flag named as it is passed, `-P<name>`. */
internal fun runPlan(
    settings: Settings,
    widening: () -> RunPlan.Widening,
    scan: (String) -> CommitScan,
    fullRunBranches: List<String> = emptyList(),
    checkedOut: () -> CheckedOut = { error("the branch was asked for with no branch listed") },
    scheduled: () -> String? = { null },
): RunPlan = invalidUserData {
    RunPlan.resolve(
        RunPlan.Asked(settings.select, settings.observe, settings.complement, settings.fullRun),
        { "-P$it" }, widening, scan, fullRunBranches, checkedOut, scheduled,
    )
}

/** [RunPlan.fullRunBranches] for the extension's `fullRunBranches`. */
internal fun fullRunBranches(declared: List<String>, owner: String): List<String> = invalidUserData {
    RunPlan.fullRunBranches(declared, owner, "fullRunBranches.add(\"main\")")
}

private fun <T> invalidUserData(block: () -> T): T =
    try {
        block()
    } catch (rejected: RunPlan.Rejected) {
        throw InvalidUserDataException(rejected.message.orEmpty())
    }
