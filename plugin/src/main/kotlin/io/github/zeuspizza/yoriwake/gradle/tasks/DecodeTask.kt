package io.github.zeuspizza.yoriwake.gradle.tasks

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.gradle.Settings
import io.github.zeuspizza.yoriwake.gradle.bytecode.DigestScan
import io.github.zeuspizza.yoriwake.gradle.bytecode.Recordability
import io.github.zeuspizza.yoriwake.gradle.bytecode.TaskArtifacts
import io.github.zeuspizza.yoriwake.gradle.capture.CoverageDecoder
import io.github.zeuspizza.yoriwake.gradle.capture.MapProvenance
import io.github.zeuspizza.yoriwake.gradle.change.CaptureStart
import io.github.zeuspizza.yoriwake.gradle.change.WorkingTree
import io.github.zeuspizza.yoriwake.gradle.facts.ClasspathFacts
import io.github.zeuspizza.yoriwake.gradle.facts.classpathFacts
import io.github.zeuspizza.yoriwake.gradle.report.Audit
import io.github.zeuspizza.yoriwake.gradle.wiring.ScopeOutcome
import io.github.zeuspizza.yoriwake.gradle.wiring.develocityRefusalMarker
import io.github.zeuspizza.yoriwake.gradle.wiring.fullRunMarker
import io.github.zeuspizza.yoriwake.gradle.wiring.parallelRefusalMarker
import io.github.zeuspizza.yoriwake.gradle.wiring.pendingHead
import io.github.zeuspizza.yoriwake.gradle.wiring.pendingStats
import io.github.zeuspizza.yoriwake.gradle.wiring.pendingSnapshot
import io.github.zeuspizza.yoriwake.gradle.wiring.ranMarker
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.api.tasks.testing.Test
import java.io.File

/**
 * Decodes the run's records into the map the selector will read. A run that captured nothing
 * leaves the previous map untouched: an empty map would select nothing.
 *
 * A finalizer of its `Test` task, not a doLast: Gradle skips a failed task's own actions, and a
 * test task fails whenever a test does. Finalizers run either way.
 */
@UntrackedTask(because = "it merges this run's records into the map in place")
internal abstract class DecodeTask : DefaultTask() {

    @get:Input
    abstract val taskPath: Property<String>

    /** The applied or adopted scope, as package prefixes. */
    @get:Input
    abstract val patterns: ListProperty<String>

    @get:Input
    abstract val selecting: Property<Boolean>

    @get:Input
    abstract val wholeTask: Property<Boolean>

    @get:Input
    abstract val loadedScope: ListProperty<String>

    @get:Input
    abstract val datesTheMap: Property<Boolean>

    @get:Input
    abstract val undatedReason: Property<String>

    @get:Input
    @get:Optional
    abstract val effectiveScope: Property<String>

    @get:Input
    abstract val isolated: Property<Boolean>

    @get:Internal("where git runs, not a set of files this task reads")
    lateinit var rootDir: File

    @get:Internal("the map directory, read, merged and rewritten in place")
    lateinit var mapDir: File

    @get:Internal("the classpath the digests read without depending on the tasks that build it")
    lateinit var facts: ClasspathFacts

    @TaskAction
    fun decode() {
        val taskPath = taskPath.get()
        val patterns = patterns.get()
        val selecting = selecting.get()
        val marker = ranMarker(CoverageDecoder.recordsDir(mapDir))
        val fullRunMarker = fullRunMarker(CoverageDecoder.recordsDir(mapDir))
        val parallelRefused = parallelRefusalMarker(CoverageDecoder.recordsDir(mapDir))
        val develocityDeclined = develocityRefusalMarker(CoverageDecoder.recordsDir(mapDir))
        val datedSnapshot = pendingSnapshot(mapDir)
        val startStats = pendingStats(mapDir)
        // First: Develocity ran or chose this run's tests, and nothing of ours acted on it.
        if (develocityDeclined.delete()) {
            marker.delete()
            parallelRefused.delete()
            fullRunMarker.delete()
            logger.lifecycle(
                "[yoriwake] $taskPath: Develocity declined this run, so the map is left exactly as it was."
            )
            return
        }
        // Before the ran-marker check: this run did work but declined to capture, and a
        // decode would merge older records into a map it must not touch.
        if (parallelRefused.delete()) {
            marker.delete()
            logger.lifecycle(
                "[yoriwake] $taskPath: declined over in-JVM parallelism, so the map is left " +
                    "exactly as it was."
            )
            return
        }
        // The marker exists only when the test task actually executed; see ranMarker.
        if (!marker.delete()) {
            logger.info("[yoriwake] $taskPath did no work; the map is left as it is")
            return
        }
        // Read here, not through a provider, which resolves before any task action runs.
        // A run that executed the whole suite dates the map even under `-Pyoriwake.select`,
        // or the map's age would never reset.
        val executedEverything = fullRunMarker.delete()
        // The HEAD the tests saw, read before the build compiled anything; null when it could not be
        // read, which removes the stamp so the next run refuses. HEAD even on a dirty tree: the
        // working-tree snapshot beside the stamp covers what HEAD does not pin.
        val pending = CaptureStart.Pending.decode(
            pendingHead(mapDir).takeIf(File::isFile)?.let { runCatching { it.readText() }.getOrNull() }
        )
        val start = pending?.reading
        val now = CaptureStart.read(rootDir)
        val captureCommit = start?.head
        // Records span two commits once either moved, and no single diff covers both. A HEAD that
        // cannot be read now differs from the one read at the start. Without a start reading there
        // is nothing to compare, and the missing stamp is what keeps the next run safe.
        val startHead = if (start == null) now.head else start.head
        val headNow = now.head
        val headMoved = startHead != headNow
        val startReflogs = start?.reflogs ?: now.reflogs
        val reflogsNow = now.reflogs
        val reflogMoved = startReflogs != reflogsNow
        // Dated only if every test JVM finished: one that died mid-plan leaves older
        // records for tests it never reached.
        val unfinished = CoverageDecoder.unfinishedWorkers(mapDir)
        val observedEverything = datesTheMap.getOrElse(false) && (!selecting || executedEverything)
        if (observedEverything && unfinished.isNotEmpty()) {
            logger.warn(
                "[yoriwake] $taskPath: ${unfinished.joinToString()} did not finish its run (a test " +
                    "that exited the JVM, or a worker that was killed or crashed), so the map " +
                    "is not dated: the tests it never reached keep records from an older " +
                    "capture, and selection widens across every commit since the last " +
                    "complete one."
            )
        }
        val datesMap = observedEverything && unfinished.isEmpty() && !headMoved && !reflogMoved
        val hasWorkerRecords = CoverageDecoder.recordsDir(mapDir).listFiles().orEmpty()
            .any { it.isDirectory && it.name.startsWith(AgentContract.WORKER_DIR_PREFIX) }
        // Merged undated, these records would describe code the stamp's commit does not hold, and a
        // later change that undoes it would be in no diff. Kept, the map stays at one commit.
        if (!datesMap && hasWorkerRecords) {
            val reason = when {
                // A narrowed selecting run is not a capture; it never meant to write the map.
                selecting && !executedEverything -> null
                !datesTheMap.getOrElse(false) -> undatedReason.getOrElse("").ifEmpty { "the run was filtered" }
                unfinished.isNotEmpty() -> "${unfinished.joinToString()} did not finish"
                headMoved -> "HEAD moved from ${startHead ?: "nothing"} to ${headNow ?: "something unreadable"} during the run"
                startReflogs.first != reflogsNow.first -> "HEAD's reflog changed during the run"
                startReflogs.second != reflogsNow.second -> "the stash's reflog changed during the run"
                else -> "the run could not be dated"
            }
            val failures = runCatching { CoverageDecoder.carryFailures(mapDir) }.getOrElse { problem ->
                logger.warn("[yoriwake] $taskPath could not mark this run's failures in the map ($problem)")
                0
            }
            if (failures > 0) {
                writeDigest(taskPath)
            }
            val kept = if (failures == 0) "" else ", except that the $failures test(s) it saw fail or skip keep that outcome"
            if (reason == null) {
                logger.info("[yoriwake] $taskPath selected part of the suite, so its records were not kept$kept")
            } else {
                logger.lifecycle(
                    "[yoriwake] $taskPath: $reason, so this run's coverage was not kept and the map is " +
                        "as it was$kept."
                )
            }
            return
        }
        val artifacts = if (datesMap) {
            runCatching {
                TaskArtifacts(
                    facts.classpath.files, facts.buildDirs.values, facts.testOutputs.files,
                )
            }.getOrNull()
        } else {
            null
        }
        val outcome = runCatching {
            CoverageDecoder.decode(
                mapDir,
                patterns,
                selecting,
                wholeTask.getOrElse(false),
                loadedScope.getOrElse(emptyList()),
                captureCommit,
                datesMap,
                effectiveScope.orNull,
                // So a later run can ask whether a constant changed. Null records nothing:
                // a missing digest would read as a class declaring no constant.
                runCatching {
                    artifacts?.constantDigests()
                }.onFailure {
                    logger.warn(
                        "[yoriwake] $taskPath: could not record constants ($it); every change to " +
                            "a constant holder forces until the next capture succeeds"
                    )
                }.getOrNull(),
                // A digest of every class, so a later run can see changes `git diff`
                // cannot. Recorded on every capture so turning the flag on works at once.
                runCatching {
                    when (val scan = artifacts?.classDigests()) {
                        null -> null
                        is DigestScan.Found -> scan.digests
                        is DigestScan.Refused -> {
                            logger.info(
                                "[yoriwake] $taskPath: no class digests recorded " +
                                    "(${scan.kind}: ${scan.reason})"
                            )
                            null
                        }
                    }
                }.getOrNull(),
                // Every class's annotations, so a later run forces when one changes: a test that
                // only scans a class never executes it.
                annotationDigests = runCatching {
                    when (val scan = artifacts?.classDigests(Recordability::annotationDigest)) {
                        null -> null
                        is DigestScan.Found -> scan.digests
                        is DigestScan.Refused -> {
                            logger.warn(
                                "[yoriwake] $taskPath: no annotation digests recorded " +
                                    "(${scan.kind}: ${scan.reason}); every change forces a full " +
                                    "run until a capture records them"
                            )
                            null
                        }
                    }
                }.getOrNull(),
                // Asked only once records were merged, which only a dating capture does. Every path
                // touched since the start reading is unknown. A start file that is missing, or that
                // another build has rewritten since, removes the snapshot, and the next run refuses.
                worktreeSnapshot = {
                    WorkingTree.reobserve(
                        rootDir,
                        WorkingTree.readStart(datedSnapshot, pending?.snapshotId),
                        WorkingTree.readStart(startStats, pending?.statsId),
                    )
                },
                isolated = isolated.getOrElse(false),
            )
        }
        // A map we cannot write leaves the previous one alone; it never fails someone
        // else's tests from inside a finalizer.
        outcome.onFailure {
            // Recorded for `yoriwakeAudit`, not only warned: the kept map looks healthy
            // while it ages.
            Audit.TaskFacts.recordDecodeRefusal(mapDir, it.toString())
            // It may have written part of the map, so its digest no longer describes it.
            runCatching { File(mapDir, AgentContract.MAP_DIGEST_FILE).delete() }
            logger.warn("[yoriwake] $taskPath could not update the map ($it)")
            return
        }
        when (val decoded = outcome.getOrNull()) {
            // A capture run that recorded nothing usually means the Platform listener never
            // loaded (TestNG, say), and every later run would silently run everything.
            // Not for selecting runs, which may legitimately deselect everything.
            null -> if (selecting) {
                logger.info(
                    "[yoriwake] $taskPath selected no tests, so there was nothing to record"
                )
            } else {
                // Recorded for `yoriwakeAudit`, which would otherwise advise capturing a
                // map that can never gain coverage.
                Audit.TaskFacts.recordCoverage(mapDir, false)
                logger.warn(
                    "[yoriwake] $taskPath ran but recorded no coverage, so no map was built and " +
                        "selection will never narrow anything here. The JUnit Platform, " +
                        "plain JUnit 4 and TestNG are all captured, so a run that records " +
                        "nothing usually means JaCoCo is not attached to this task, or its " +
                        "engine is one this tool has not been taught. The previous map at " +
                        "$mapDir is unchanged."
                )
            }
            else -> {
                writeDigest(taskPath)
                Audit.TaskFacts.recordCoverage(mapDir, true)
                // A merge that worked is the only thing that clears the refusal.
                Audit.TaskFacts.recordDecodeRefusal(mapDir, null)
                // Only the decode knows how many worker JVMs contributed to the summed
                // durations. Only a dating run records it, as the durations are the merged
                // map's.
                if (datesMap) {
                    Audit.TaskFacts.recordWorkers(mapDir, decoded.workers)
                }
                logger.lifecycle(
                    "[yoriwake] $taskPath map updated: ${decoded.captured} records captured, " +
                        "${decoded.total} known" +
                        if (decoded.unattributable > 0) {
                            ", ${decoded.unattributable} of them unattributable"
                        } else {
                            ""
                        }
                )
                // Warn when the map is mostly unusable, usually a framework like
                // Robolectric running tests in its own classloader. Over the merged map.
                if (decoded.mapTests > 0 &&
                    decoded.mapUnattributable * 2 > decoded.mapTests
                ) {
                    val percent = decoded.mapUnattributable * 100 / decoded.mapTests
                    logger.warn(
                        "[yoriwake] $taskPath: $percent% of the ${decoded.mapTests} tests in " +
                            "this map carry no class, so those tests will run on " +
                            "every build and selection will narrow little. A framework " +
                            "that runs tests in its own classloader (Robolectric is the " +
                            "common one) is the usual cause."
                    )
                }
                // A full run that did not record drops the loaded-class union until the
                // next recording capture. Said out loud: a capability disappears.
                if (decoded.unionDiscarded) {
                    logger.lifecycle(
                        "[yoriwake] $taskPath ran without -P${Settings.LOADED}, so the " +
                            "loaded-class union it could not vouch for was discarded. " +
                            "Changes to code no test executes will force a full run " +
                            "until the next capture with -P${Settings.LOADED}."
                    )
                }
            }
        }
    }

    /**
     * Last after any write to the map, so a caller can list exactly what this decode left. One that
     * cannot be written is removed rather than left describing an older map.
     */
    private fun writeDigest(taskPath: String) {
        runCatching { MapProvenance.writeDigest(mapDir) }.onFailure {
            runCatching { File(mapDir, AgentContract.MAP_DIGEST_FILE).delete() }
            logger.warn("[yoriwake] $taskPath could not write the map's digest ($it); a run given a trusted-map list will not narrow from it")
        }
    }

    /** What the `Test` task's wiring decided that the decode reads back. */
    internal class Inputs(
        val mapDir: File,
        val scope: ScopeOutcome,
        val selecting: Boolean,
        val wholeTask: Provider<Boolean>,
        val loadedScope: Provider<List<String>>,
        val datesTheMap: Provider<Boolean>,
        /** Why a run that cannot date the map does not; empty when it can. */
        val undatedReason: Provider<String>,
        val effectiveScope: Provider<String>,
        /** Whether this run forked a JVM per test class while it captured. */
        val isolated: Boolean,
    )

    internal companion object {
        fun nameFor(testName: String) = "yoriwakeDecode${testName.replaceFirstChar(Char::uppercase)}"

        /**
         * Registers the finalizer beside [testName]; the wiring attaches it. [inputs] answers null
         * for a `Test` task the plugin declined, which leaves this task nothing to do.
         */
        fun register(project: Project, testName: String, inputs: (Test) -> Inputs?) {
            project.tasks.register(nameFor(testName), DecodeTask::class.java) { task ->
                val test = project.tasks.named(testName, Test::class.java).get()
                val wired = inputs(test)
                task.taskPath.set(test.path)
                if (wired == null) {
                    // Attached to nothing, so this runs only if asked for by name.
                    task.enabled = false
                    return@register
                }
                task.mapDir = wired.mapDir
                task.patterns.set(
                    when (val scope = wired.scope) {
                        is ScopeOutcome.Applied -> scope.patterns
                        is ScopeOutcome.AdoptedFromHost -> scope.patterns
                        is ScopeOutcome.NotApplied -> emptyList()
                    }.map { it.removeSuffix("*").removeSuffix(".") }.filter(String::isNotEmpty)
                )
                task.selecting.set(wired.selecting)
                task.wholeTask.set(wired.wholeTask)
                task.loadedScope.set(wired.loadedScope)
                task.datesTheMap.set(wired.datesTheMap)
                task.undatedReason.set(wired.undatedReason)
                task.effectiveScope.set(wired.effectiveScope)
                task.isolated.set(wired.isolated)
                task.rootDir = project.rootDir
                // Empty, stated rather than defaulted: these facts never reach
                // `modulesOnClasspath`, so resolving `declared` would buy nothing.
                task.facts = classpathFacts(project, test, changedPaths = emptyList())
            }
        }
    }
}
