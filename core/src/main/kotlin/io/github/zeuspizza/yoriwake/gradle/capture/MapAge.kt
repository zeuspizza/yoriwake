package io.github.zeuspizza.yoriwake.gradle.capture

import io.github.zeuspizza.yoriwake.gradle.change.ChangeDetection
import io.github.zeuspizza.yoriwake.gradle.change.RefusalKind
import java.io.File

// How old a map's records are, from its capture stamp: shared by the test task and
// `yoriwakeExplain` so both widen the base the same way.

/** A valid `capture-commit` stamp, or null. A non-hash (say, from a restored cache) is none. */
internal fun validCaptureStamp(raw: String?): String? =
    raw?.trim()?.takeIf { it.matches(Regex("[0-9a-fA-F]{7,40}")) }

/** How far back a change set must reach for a map's records to be current, or why nobody can say. */
internal sealed interface MapAge {
    /** [stamp] is the map's capture commit, null when there is no map. */
    data class Known(val base: ChangeDetection.Base, val stamp: String? = null) : MapAge
    data class Unknown(val kind: RefusalKind, val reason: String) : MapAge
}

/**
 * The base widened to the map's age: the common ancestor of the base and the capture stamp.
 *
 * The git call is passed in so the test task and `yoriwakeExplain` share this decision. A map with
 * no usable stamp has an unknown age and refuses, since the unwidened base would miss every change
 * between the capture and the base. With no map at all the base stands: that run runs everything.
 */
internal fun widenToMapAge(
    base: ChangeDetection.Base,
    stamp: String?,
    mapPresent: Boolean,
    commonAncestor: (String, String) -> String?,
): MapAge {
    if (stamp.isNullOrEmpty()) {
        return if (!mapPresent) MapAge.Known(base) else MapAge.Unknown(
            RefusalKind.STAMP_ABSENT,
            "the map carries no capture stamp, so how much it knows cannot be established",
        )
    }
    // git could not relate the stamp to the base (rebase, force-push, gc, foreign cache): the
    // map's age is unknown, not current.
    val ancestor = commonAncestor(base.ref, stamp) ?: return MapAge.Unknown(
        RefusalKind.STAMP_UNRELATABLE,
        "the map records that it was captured at a commit git cannot relate to ${base.ref}, " +
            "so how much it knows cannot be established",
    )
    if (ancestor == base.ref) {
        return MapAge.Known(base, stamp)
    }
    return MapAge.Known(
        ChangeDetection.Base(ancestor, "${base.origin}, widened to cover a map captured at ${stamp.take(12)}"),
        stamp,
    )
}

/** The same read, for callers that have a map directory rather than a provider's text. */
internal fun readCaptureStamp(mapDir: File): String? = validCaptureStamp(
    File(mapDir, CoverageDecoder.CAPTURE_COMMIT_FILE)
        .takeIf(File::isFile)
        ?.let { runCatching { it.readText() }.getOrNull() }
)
