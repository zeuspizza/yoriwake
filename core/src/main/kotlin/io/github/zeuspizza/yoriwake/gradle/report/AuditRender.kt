package io.github.zeuspizza.yoriwake.gradle.report

import io.github.zeuspizza.yoriwake.gradle.capture.writeAtomically
import io.github.zeuspizza.yoriwake.gradle.report.Audit.AUDIT_FILE
import io.github.zeuspizza.yoriwake.gradle.report.Audit.Blocker
import io.github.zeuspizza.yoriwake.gradle.report.Audit.Counts
import io.github.zeuspizza.yoriwake.gradle.report.Audit.Distribution
import io.github.zeuspizza.yoriwake.gradle.report.Audit.PAYLOAD_VERSION
import io.github.zeuspizza.yoriwake.gradle.report.Audit.Provenance
import io.github.zeuspizza.yoriwake.gradle.report.Audit.Result
import java.io.File
import java.util.Locale

// The audit as text and as audit.json, apart from how the verdict is reached.

internal fun narrowingLines(
    d: Distribution,
    counts: Counts,
): List<String> = buildList {
    add(
        "${d.tests} tests and ${d.classes} classes with recorded coverage, from " +
            "${counts.lines} lines."
    )
    if (counts.malformed > 0) {
        add("${counts.malformed} malformed lines were skipped and are not in any figure above.")
    }
    // Printed before the percentiles, because it decides how much they are worth.
    if (d.unattributableTests > 0) {
        add(
            "${d.unattributableTests} tests (${percent(d.unattributableShare)}) carry no " +
                "classes and RUN ON EVERY BUILD whatever you change. They belong to no class, " +
                "so they appear nowhere in the shares below -- those are fractions of the " +
                "whole suite, and narrowing here can never do better than the " +
                "${percent(1.0 - d.unattributableShare)} that is left."
        )
    }
    add(
        "${d.hubClassesOverHalf} classes are reached by more than half the suite; a change to " +
            "one of those selects the whole suite in all but name."
    )
    add(
        "${d.startupClasses} classes are in startup coverage, which no test owns: touching " +
            "one forces a full run."
    )
    if (d.hubs.isNotEmpty()) {
        add("Most-reached classes:")
        d.hubs.forEach { add("    ${percent(it.share).padStart(6)}  ${it.tests}  ${it.className}") }
    }
    add(
        "A class with no entry here has no executed-probe record: JaCoCo reports what ran, so " +
            "a class that was loaded and initialised but whose methods never ran looks " +
            "identical to one nothing ever touched."
    )
    add(
        "Reachability, not savings: this counts tests, not seconds. A suite whose slowest tests " +
            "are the ones every change reaches will read as narrow here and save nothing."
    )
}

/** The narrowing half only, worded so it cannot imply anything about the toll. */
internal fun unmeasuredToll(captureTask: String) = listOf(
    "Whether narrowing repays what instrumenting costs is UNMEASURED here, and is not " +
        "derivable from a map: an instrumented full run arrives before any uninstrumented one. " +
        "One project's overhead does not predict another's: it varies widely between suites. " +
        "To measure it on this suite, run scripts/measure-toll.sh, which times `$captureTask` " +
        "twice and calls this task back with both timings."
)

/**
 * The blocker list as prose, never as a verdict: the terminal state says whether any of it is
 * worth doing.
 */
internal fun blockerLines(blockers: List<Blocker>): List<String> = buildList {
    if (blockers.isEmpty()) {
        // Said explicitly, because silence reads like an audit that never looked.
        add(
            "Nothing was found stopping this task from narrowing. That is not a prediction " +
                "that it will: what a change selects is the distribution above, and whether " +
                "narrowing repays instrumenting is a separate measurement."
        )
        return@buildList
    }
    add("${blockers.size} thing(s) stop this task narrowing:")
    blockers.forEach { blocker ->
        add("  [${blocker.token}] ${blocker.detail}")
        add("      ${blocker.remedy ?: "No remedy is known. Reported so the shape is visible."}")
    }
}

internal fun seconds(value: Double?) =
    value?.let { String.format(Locale.ROOT, "%.1fs", it) } ?: "no time"

internal fun provenanceLines(provenance: Provenance): List<String> = buildList {
    if (provenance.captureCommit == null) {
        add(
            "This map carries no capture-commit stamp, so the age of what it describes is " +
                "unknown."
        )
    } else {
        add(
            "Captured against host commit ${provenance.captureCommit}" +
                (provenance.ageDays?.let { "; the records were last written $it days ago" } ?: "")
                + "."
        )
    }
    // Said explicitly, so no reader assumes the map records its writer's version.
    add(
        "The plugin version that wrote this map is not recorded in it and cannot be " +
            "established: capture-commit is the host project's commit, and the schema version " +
            "deliberately does not change for every capture-affecting fix. A map written " +
            "before such a fix repairs itself, but only once the task has run again."
    )
}

/**
 * Written before anything is logged, so a missing file never has to be told apart from a log
 * line in a shape the caller does not parse.
 */
internal fun Audit.write(mapDir: File, result: Result) {
    val json = buildString {
        append("{\n")
        append("""  "version": $PAYLOAD_VERSION,""").append('\n')
        append("""  "state": ${Json.string(result.state.token)},""").append('\n')
        append("""  "headline": ${Json.string(result.headline)},""").append('\n')
        append("""  "counts": {""").append('\n')
        append("""    "lines": ${result.counts.lines},""").append('\n')
        append("""    "malformed": ${result.counts.malformed},""").append('\n')
        append("""    "testRecords": ${result.counts.testRecords},""").append('\n')
        append("""    "nonTestRecords": ${result.counts.nonTestRecords}""").append('\n')
        append("  },\n")
        append("""  "provenance": {""").append('\n')
        append("""    "captureCommit": """)
            .append(Json.string(result.provenance.captureCommit))
            .append(",\n")
        append("""    "ageDays": ${result.provenance.ageDays ?: "null"},""").append('\n')
        // Not recordable from the map; stated as data so a consumer need not parse the prose.
        append("""    "writingPluginVersion": null""").append('\n')
        append("  },\n")
        append("""  "distribution": """)
        append(result.distribution?.let { json(it) } ?: "null")
        append(",\n")
        // Null, not zero: a map with no durations has not said the suite is instant.
        append("""  "recordedTime": """)
        append(result.recordedTime?.let { Payback.json(it) } ?: "null")
        append(",\n")
        append("""  "blockers": [""").append('\n')
        append(
            result.blockers.joinToString(",\n") { blocker ->
                """    { "token": ${Json.string(blocker.token)}, """ +
                    """"detail": ${Json.string(blocker.detail)}, """ +
                    """"remedy": ${Json.string(blocker.remedy)} }"""
            }
        )
        append('\n').append("  ],\n")
        append("""  "lines": [""").append('\n')
        append(result.lines.joinToString(",\n") { "    ${Json.string(it)}" })
        append('\n').append("  ]\n").append("}\n")
    }
    // Atomic: scripts parse this, and a truncated one could read as a shorter blocker list.
    runCatching {
        File(mapDir, AUDIT_FILE).parentFile?.mkdirs()
        writeAtomically(File(mapDir, AUDIT_FILE), json)
    }
}

private fun json(d: Distribution) = buildString {
    append("{\n")
    append("""    "tests": ${d.tests},""").append('\n')
    append("""    "classes": ${d.classes},""").append('\n')
    append("""    "unattributableTests": ${d.unattributableTests},""").append('\n')
    append("""    "unattributableShare": ${d.unattributableShare},""").append('\n')
    append("""    "meanShare": ${d.meanShare},""").append('\n')
    append("""    "medianTestsPerClass": ${d.medianTestsPerClass},""").append('\n')
    append("""    "medianShare": ${d.medianShare},""").append('\n')
    append("""    "p90Share": ${d.p90Share},""").append('\n')
    append("""    "p99Share": ${d.p99Share},""").append('\n')
    append("""    "maxShare": ${d.maxShare},""").append('\n')
    append("""    "hubClassesOverHalf": ${d.hubClassesOverHalf},""").append('\n')
    append("""    "startupClasses": ${d.startupClasses},""").append('\n')
    append("""    "hubs": [""").append('\n')
    append(
        d.hubs.joinToString(",\n") {
            """      { "class": ${Json.string(it.className)}, "tests": ${it.tests}, "share": ${it.share} }"""
        }
    )
    append('\n').append("    ]\n").append("  }")
}
