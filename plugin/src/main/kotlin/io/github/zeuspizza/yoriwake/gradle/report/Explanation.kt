package io.github.zeuspizza.yoriwake.gradle.report

import io.github.zeuspizza.yoriwake.gradle.YoriwakePlugin
import io.github.zeuspizza.yoriwake.gradle.capture.CaptureDecision
import io.github.zeuspizza.yoriwake.gradle.change.Established
import io.github.zeuspizza.yoriwake.gradle.change.InlineWidening
import io.github.zeuspizza.yoriwake.gradle.change.ScopedChange
import java.io.File

// The explain.json writer, apart from the decision it records.

/**
 * The decision `yoriwakeExplain` prints, as a file an agent can read instead of grepping the log.
 * Hand-rolled JSON: the plugin ships inside a foreign build and adds no dependency for it.
 */
internal fun writeExplanation(
    mapDir: File,
    taskPath: String,
    base: String,
    scoped: ScopedChange,
    established: Established,
    decision: io.github.zeuspizza.yoriwake.agent.select.Selector.Decision,
    /** The inline widening's decision states, as data rather than log prose. */
    widening: InlineWidening = InlineWidening.widened(emptyList(), emptySet()),
    capture: CaptureDecision? = null,
) {
    val known = decision.knownTests()
    val selected = if (decision.isFullRun) known else decision.selectedCount()
    val json = buildString {
        append("{\n")
        append("""  "version": 1,""").append('\n')
        append("""  "task": ${Json.string(taskPath)},""").append('\n')
        append("""  "base": ${Json.string(base)},""").append('\n')
        append("""  "fullRun": ${decision.isFullRun},""").append('\n')
        append("""  "reason": ${Json.string(decision.fullRunReason())},""")
            .append('\n')
        // The same fact as `reason`, as a token.
        append("""  "fullRunKind": """)
            .append(Json.string(decision.fullRunKind()?.token()))
            .append(',').append('\n')
        append("""  "known": $known,""").append('\n')
        append("""  "selected": $selected,""").append('\n')
        append("""  "changedClasses": ${scoped.change.classPrefixes.size},""").append('\n')
        append("""  "unmappablePaths": ${scoped.change.unmappablePaths.size},""").append('\n')
        // The paths, not only how many: a build script and a README force for the same reason
        // but are not the same risk. Bounded, because a change set can be thousands long.
        append("""  "unmappablePathsSample": """)
        append(Json.strings(scoped.change.unmappablePaths.sorted().take(UNMAPPABLE_SAMPLE)))
        append(",").append('\n')
        append("""  "offClasspath": ${scoped.dropped},""").append('\n')
        append("""  "provablyUntested": ${established.provable.size},""").append('\n')
        append("""  "ownTestClasses": ${established.testClasses.size},""").append('\n')
        append("""  "unreadablePaths": ${established.unreadablePaths.size},""").append('\n')
        append("""  "inlineScanExhausted": ${widening.scanExhausted},""").append('\n')
        append("""  "refusalKind": """)
            .append(Json.string(widening.refusalKind?.token))
            .append(',').append('\n')
        append("""  "refusalDetail": """)
            .append(Json.string(widening.refusalDetail))
            .append(',').append('\n')
        append("""  "capture": ${capture?.capture ?: false},""").append('\n')
        append("""  "mapCurrent": ${capture?.mapCurrent ?: false},""").append('\n')
        append("""  "captureReason": ${Json.string(capture?.reason)},""")
            .append('\n')
        append("""  "inliners": """).append(Json.strings(widening.inliners.sorted())).append(",\n")
        // The bytes comparison. `digestOn` false means the rest describe a comparison that did not
        // run; `digestAdded` beside the sample shows when the sample was cut short.
        append("""  "digestOn": ${widening.digest != null},""").append('\n')
        append("""  "digestSilence": """)
            .append(Json.string(widening.digest?.silence))
            .append(',').append('\n')
        append("""  "digestSilenceDetail": """)
            .append(Json.string(widening.digest?.detail))
            .append(',').append('\n')
        append("""  "digestAdded": ${widening.digest?.added?.size ?: 0},""").append('\n')
        append("""  "digestConsidered": ${widening.digest?.considered ?: 0},""").append('\n')
        append("""  "digestDigested": ${widening.digest?.digested ?: 0},""").append('\n')
        append("""  "digestUnreadable": ${widening.digest?.unreadable ?: 0},""").append('\n')
        append("""  "digestRecorded": ${widening.digest?.recorded ?: 0},""").append('\n')
        // True when the digest read bytes no compile task of this invocation produced.
        append("""  "digestBytesStale": ${widening.digest?.bytesAreFresh == false},""").append('\n')
        append("""  "digestAddedSample": """)
        append(Json.strings(widening.digest?.added.orEmpty().sorted().take(UNMAPPABLE_SAMPLE)))
        append(",").append('\n')
        append("""  "refusals": [""").append('\n')
        append(
            established.refusals.entries.sortedBy { it.key }.joinToString(",\n") { (prefix, reason) ->
                """    { "class": ${Json.string(prefix)}, "reason": ${Json.string(reason)} }"""
            }
        )
        append('\n').append("  ]\n").append("}\n")
    }
    runCatching { File(mapDir, YoriwakePlugin.EXPLANATION_FILE).also { it.parentFile.mkdirs() }.writeText(json) }
}

/** How many unmappable paths an explanation lists before it stops. A sample, and it says so. */
internal const val UNMAPPABLE_SAMPLE = 40

internal fun writeUnanswered(
    mapDir: File,
    taskPath: String,
    base: String,
    kind: io.github.zeuspizza.yoriwake.agent.select.Selector.Decision.FullRunKind,
    reason: String,
    /** The daemon's own finer token, in the same vocabulary the run channel carries. */
    refusalKind: String? = null,
) {
    val json = buildString {
        append("{\n")
        append("""  "version": 1,""").append('\n')
        append("""  "task": ${Json.string(taskPath)},""").append('\n')
        append("""  "base": ${Json.string(base)},""").append('\n')
        append("""  "fullRun": true,""").append('\n')
        append("""  "reason": ${Json.string(reason)},""").append('\n')
        append("""  "fullRunKind": ${Json.string(kind.token())},""").append('\n')
        append("""  "known": 0,""").append('\n')
        append("""  "selected": 0,""").append('\n')
        append("""  "changedClasses": 0,""").append('\n')
        append("""  "unmappablePaths": 0,""").append('\n')
        append("""  "unmappablePathsSample": [],""").append('\n')
        append("""  "offClasspath": 0,""").append('\n')
        append("""  "provablyUntested": 0,""").append('\n')
        append("""  "ownTestClasses": 0,""").append('\n')
        append("""  "unreadablePaths": 0,""").append('\n')
        append("""  "inlineScanExhausted": false,""").append('\n')
        append("""  "refusalKind": """)
            .append(Json.string(refusalKind))
            .append(',').append('\n')
        append("""  "refusalDetail": null,""").append('\n')
        append("""  "capture": false,""").append('\n')
        append("""  "mapCurrent": false,""").append('\n')
        append("""  "captureReason": null,""").append('\n')
        append("""  "inliners": [],""").append('\n')
        // Nothing was computed, so the digest rule did not run, which differs from it finding
        // nothing.
        append("""  "digestOn": false,""").append('\n')
        append("""  "digestSilence": null,""").append('\n')
        append("""  "digestSilenceDetail": null,""").append('\n')
        append("""  "digestAdded": 0,""").append('\n')
        append("""  "digestConsidered": 0,""").append('\n')
        append("""  "digestDigested": 0,""").append('\n')
        append("""  "digestUnreadable": 0,""").append('\n')
        append("""  "digestRecorded": 0,""").append('\n')
        append("""  "digestBytesStale": false,""").append('\n')
        append("""  "digestAddedSample": [],""").append('\n')
        append("""  "refusals": [""").append('\n')
        append("  ]\n").append("}\n")
    }
    runCatching { File(mapDir, YoriwakePlugin.EXPLANATION_FILE).also { it.parentFile.mkdirs() }.writeText(json) }
}

/**
 * `selected of known` as a percentage, in `Locale.ROOT`: the share is pasted into issues and
 * grepped for, and a decimal comma breaks both.
 */
internal fun selectionShare(selected: Int, known: Int): String =
    String.format(java.util.Locale.ROOT, "(%.1f%%)", if (known == 0) 0.0 else 100.0 * selected / known)
