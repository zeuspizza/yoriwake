package io.github.zeuspizza.yoriwake.gradle.wiring

import io.github.zeuspizza.yoriwake.gradle.capture.CoverageDecoder
import io.github.zeuspizza.yoriwake.gradle.change.CaptureStart
import io.github.zeuspizza.yoriwake.gradle.change.WorkingTree
import java.io.File

// The files a test run leaves its decode, beside the map: what the run saw where it started, and
// what it did. Written by the host's wiring before and while the tests run, read by the decode.

/** The start reading of HEAD and its reflogs; see [CaptureStart]. */
internal fun pendingHead(mapDir: File) = File(mapDir, CaptureStart.PENDING_FILE)

/**
 * The stat tokens of the tracked paths where the capture started, shared by the build's maps; see
 * [WorkingTree.startStats].
 */
internal fun pendingStats(mapDir: File) = File(mapDir.parentFile.parentFile, WorkingTree.STATS_PENDING_FILE)

/** The classpath files where the capture started; see [ClasspathFiles]. */
internal fun pendingClasspathFiles(mapDir: File) = File(mapDir, CoverageDecoder.RESOURCE_DIGESTS_FILE + ".pending")

/** The tree where the capture started, shared by the build's maps; see [WorkingTree.startSnapshot]. */
internal fun pendingSnapshot(mapDir: File) = File(mapDir.parentFile.parentFile, WorkingTree.SNAPSHOT_PENDING_FILE)

/**
 * Written when the test task actually executes. A finalizer also runs for UP-TO-DATE,
 * FROM-CACHE or SKIPPED tasks, and `test.didWork` cannot be configuration-cached.
 */
// Beside the records, not inside: the record writer refuses a non-empty directory.
internal fun ranMarker(recordsDir: File) = File(recordsDir.parentFile, "ran.marker")

/**
 * Written by a run declined over in-JVM parallelism, so the decode finalizer can tell "captured
 * nothing on purpose" from "the listener never loaded".
 */
internal fun parallelRefusalMarker(recordsDir: File) = File(recordsDir.parentFile, "parallel-refused.marker")

/**
 * Written by a run declined under Develocity, so the decode finalizer can tell "captured nothing on
 * purpose" from "the listener never loaded".
 */
internal fun develocityRefusalMarker(recordsDir: File) = File(recordsDir.parentFile, "develocity-declined.marker")

/**
 * Written by a declined run that left a map recorded in isolation alone, so the decode finalizer can
 * tell "captured nothing on purpose" from "the listener never loaded". Holds the reason.
 */
internal fun declinedLeftAloneMarker(recordsDir: File) = File(recordsDir.parentFile, "declined-left-alone.marker")

/**
 * Written by a run that executed the whole suite and captured it, read by the decode finalizer:
 * the map's age may only advance when its records describe every test.
 */
internal fun fullRunMarker(recordsDir: File) = File(recordsDir.parentFile, "full-run.marker")

/**
 * Written by a run whose filter, read as the task executed, keeps it from dating the map; holds the
 * reason. Read by the decode finalizer over its own inputs, which may predate the filter.
 */
internal fun undatedRunMarker(recordsDir: File) = File(recordsDir.parentFile, "undated-run.marker")
