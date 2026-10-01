package io.github.zeuspizza.yoriwake.gradle.capture

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.MODE_SHARED
import io.github.zeuspizza.yoriwake.gradle.Settings
import java.io.File

// Whether a run instruments. External tooling reads its reason strings, so keep them stable.

/** Whether this run instruments, and why; one decision shared by the run and `yoriwakeExplain`. */
internal data class CaptureDecision(
    val capture: Boolean,
    val fullRun: Boolean,
    /** Whether the map already accounts for everything this change could teach it. */
    val mapCurrent: Boolean,
    val reason: String,
)

/**
 * Instrument when the run is full anyway and the map would learn something. An unreadable map
 * with a leftover stamp is never current, so it gets rebuilt.
 *
 * A usable map any JVM of which was recorded in isolation is left alone: a selecting run keeps the
 * host's fork settings, so what it captured would supersede those records with shared-JVM ones.
 * Only a recording run updates such a map.
 */
internal fun decideCapture(
    mapDir: File,
    mapUsable: Boolean,
    fullRun: Boolean,
    learnable: Set<String>,
    /** False when the map's age could not be established, which no stamp makes current. */
    ageKnown: Boolean = true,
): CaptureDecision {
    val stamp = readCaptureStamp(mapDir)
    // "Current" is about what the map knows, not which commit it was stamped at: the change set
    // is already against a base widened to the map's age, so no learnable classes means nothing
    // coverage could record has changed. Without a valid stamp the age is unknown and the run
    // captures.
    val current = ageKnown && mapUsable && !stamp.isNullOrEmpty() && learnable.isEmpty()
    val mode = if (fullRun && !current && mapUsable) CoverageDecoder.captureMode(mapDir) else MODE_SHARED
    val keepsIsolation = mode != MODE_SHARED
    val capture = fullRun && !current && !keepsIsolation
    val reason = when {
        capture ->
            "running everything, so this run also captures -- coverage costs the toll once here " +
                "and buys a complete map."
        keepsIsolation ->
            "running everything, but the map holds records made with a fresh test JVM per test " +
                "class ($mode), so this selecting run leaves it alone and nothing is instrumented. " +
                "Only a run without -P${Settings.SELECT} updates it; pass " +
                "-P${Settings.ISOLATED_CAPTURE} there to keep it isolated."
        fullRun ->
            "running everything, but the map already covers this commit, so nothing is " +
                "instrumented and the run costs what it always did."
        else ->
            "narrowing, so nothing is instrumented and the map is left alone. A partial run " +
                "cannot produce a map worth keeping."
    }
    return CaptureDecision(capture, fullRun, current, reason)
}
