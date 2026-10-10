package io.github.zeuspizza.yoriwake.gradle.wiring

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.ALWAYS_RUN_PROPERTY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.CHANGE_SET_FILE_PROPERTY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.JUNIT4_HOOK_PROPERTY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.LOADED_DIR_PROPERTY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.MAP_DIR_PROPERTY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.OBSERVE_PROPERTY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.RECORDS_DIR_PROPERTY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.REFUSED_KIND_PROPERTY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.REFUSED_PROPERTY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.RUN_TOKEN_PROPERTY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.SELECT_PROPERTY
import io.github.zeuspizza.yoriwake.gradle.RunPlan
import io.github.zeuspizza.yoriwake.gradle.Settings
import io.github.zeuspizza.yoriwake.gradle.YoriwakeExtension
import io.github.zeuspizza.yoriwake.gradle.YoriwakePlugin
import io.github.zeuspizza.yoriwake.gradle.YoriwakePlugin.Companion.CONFIGURE_COUNTER
import io.github.zeuspizza.yoriwake.gradle.YoriwakePlugin.Companion.TEST_TASKS_COUNTER
import io.github.zeuspizza.yoriwake.gradle.bytecode.EffectiveScope
import io.github.zeuspizza.yoriwake.gradle.capture.CoverageDecoder
import io.github.zeuspizza.yoriwake.gradle.capture.MapLocation
import io.github.zeuspizza.yoriwake.gradle.capture.SelectionRecord
import io.github.zeuspizza.yoriwake.gradle.capture.writeAtomically
import io.github.zeuspizza.yoriwake.gradle.change.CaptureStart
import io.github.zeuspizza.yoriwake.gradle.change.ChangeDetection
import io.github.zeuspizza.yoriwake.gradle.change.ClasspathFiles
import io.github.zeuspizza.yoriwake.gradle.change.RefusalKind
import io.github.zeuspizza.yoriwake.gradle.change.WorkingTree
import io.github.zeuspizza.yoriwake.gradle.facts.BuildMemo
import io.github.zeuspizza.yoriwake.gradle.facts.projectFacts
import io.github.zeuspizza.yoriwake.gradle.report.Audit
import io.github.zeuspizza.yoriwake.gradle.tasks.AuditTask
import io.github.zeuspizza.yoriwake.gradle.tasks.DecodeTask
import io.github.zeuspizza.yoriwake.gradle.tasks.ExplainTask
import io.github.zeuspizza.yoriwake.gradle.wiring.JacocoScoping.allProjectPackages
import io.github.zeuspizza.yoriwake.gradle.wiring.JacocoScoping.applyJacocoIncludes
import io.github.zeuspizza.yoriwake.gradle.wiring.JacocoScoping.deriveScope
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoReportBase
import java.io.File

/**
 * Everything the plugin does to one `Test` task: the agent and its system properties, capture,
 * selection, the task-facts recorder and the in-JVM parallelism refusal.
 *
 * `doFirst` prepends, so the order these register their actions in is behaviour; the plugin's
 * characterization test pins it.
 */
internal class TestTaskWiring(internal val settings: Settings) {

    /** What [configure] decided for each `Test` task, by name, for the tasks configured after it. */
    private val configured = mutableMapOf<String, Configured>()

    private class Configured(
        val mapDir: File,
        val scope: List<String>?,
        val outcome: ScopeOutcome,
        /** Null for a declined task: nothing is captured, so there is nothing to decode. */
        val decode: DecodeTask.Inputs?,
        /** The filter's patterns as the build script left them; null for a declined task. */
        val fromBuildScript: TestPatterns? = null,
    )

    /**
     * Wires every `Test` task of [project], once every build script has run, without realising
     * any: each is configured when something first asks for it, as `help` never does.
     */
    internal fun register(project: Project, extension: YoriwakeExtension) {
        val names = project.tasks.withType(Test::class.java).names.toList()
        // Read here rather than in `apply`: a build script runs after it and could set `enabled`
        // back, and `disallowChanges()` would make it throw. Read here rather than when a task is
        // realised, so a change after every build script has run is ignored, as it always was.
        val disabledOnCommandLine = settings.disabled

        // Everything below is a no-op when disabled: an escape hatch must not rewrite the host's
        // configuration.
        if (disabledOnCommandLine || !extension.enabled.getOrElse(true)) {
            names.forEach { name ->
                project.tasks.named(name, Test::class.java).configure { test ->
                    test.doFirst { test.logger.lifecycle("[yoriwake] ${test.path} disabled; not configured") }
                }
            }
            return
        }
        // Read with `enabled`, for the same reason.
        val alwaysRun = extension.alwaysRun.getOrElse(emptyList())
        val fullRunBranches = RunPlan.fullRunBranches(extension.fullRunBranches.getOrElse(emptyList()), project.path)

        // One instance for the whole build, so git questions are asked once rather than per `Test`
        // task. Null when the plugin loads through more than one classloader: slow, not wrong.
        val buildMemo = BuildMemo.of(project)
        val scope = deriveScope(project, buildMemo)
        // Whether a task is wired, and so has `yoriwakeExplain` and a decode, follows from the
        // project alone, except with no derived scope: then the host's own includes on the task
        // decide, and that task is realised here so a declined one still has no explain task.
        val jacoco = project.plugins.hasPlugin("jacoco")
        val decidedByTask = jacoco && scope == null

        // Only when a run asks for them by name, and written once every task in the graph is
        // configured. The property is build-scoped fingerprint state, so runs with and without it
        // are different configuration-cache entries.
        settings.counters?.let { path ->
            project.gradle.taskGraph.whenReady { buildMemo?.writeCountsTo(File(path)) }
        }

        names.forEach { name ->
            // The denominator every per-task figure needs: `Test` tasks, not projects.
            buildMemo?.count(TEST_TASKS_COUNTER)
            val test = project.tasks.named(name, Test::class.java)
            test.configure { timedConfigure(project) { configure(project, it, scope, alwaysRun, fullRunBranches, buildMemo) } }
            val wired = if (decidedByTask) configured.getValue(test.get().name).decode != null else jacoco

            // Registered by name, configured only when asked for: configuring one realises its
            // `Test` task, whose wiring above has then decided what these read.
            AuditTask.register(
                project, name, settings,
                outcome = { t ->
                    configured.getValue(t.name).let { Triple(it.mapDir, it.outcome as? ScopeOutcome.NotApplied, it.scope) }
                },
                // Resolved when the entry stores, after every git call this build makes.
                gitFailed = project.provider { buildMemo?.failed() == true },
                // Resolved when the cache entry stores, so it is the build's whole configure cost; a
                // reused entry replays the last configuring build's figure, and the verdict says so.
                configureCost = project.provider { BuildMemo.of(project)?.configureCost() },
            )
            if (wired) {
                ExplainTask.register(project, name, settings, fullRunBranches) { t ->
                    configured.getValue(t.name).let { Triple(it.mapDir, it.outcome, it.fromBuildScript) }
                }
                DecodeTask.register(project, name) { t -> configured.getValue(t.name).decode }
                // A failed run restores the host's JaCoCo file in the decode; a report reads it after.
                project.tasks.withType(JacocoReportBase::class.java).configureEach {
                    it.mustRunAfter(DecodeTask.nameFor(name))
                }
            }
        }
    }

    /**
     * Times [body] as the plugin's configure cost. Once however the calls nest: a `Test` task
     * realised inside the pass at `projectsEvaluated` must not be counted twice.
     */
    internal fun <T> timedConfigure(project: Project, body: () -> T): T {
        val memo = BuildMemo.of(project)
        if (memo == null || TIMING.get()) {
            return body()
        }
        TIMING.set(true)
        try {
            return memo.time(CONFIGURE_COUNTER, body)
        } finally {
            TIMING.set(false)
        }
    }

    private fun configure(
        project: Project,
        test: Test,
        scope: List<String>?,
        alwaysRunDeclared: List<String>,
        fullRunBranches: List<String>,
        buildMemo: BuildMemo?,
    ) {
        val mapDir = MapLocation.forTask(project.cacheDir(), test.path)
        val scopeOutcome = applyJacocoIncludes(project, test, scope)

        // Decline, do not throw, and do not capture: a map without an applied scope has unknown
        // extent, and selection on it could skip a failing test.
        if (scopeOutcome is ScopeOutcome.NotApplied) {
            // Only the audit reads it, so the owner's `yoriwakeAudit<Task>` reports the refusal
            // instead of "task not found". It runs nothing and writes nothing.
            configured[test.name] = Configured(mapDir, scope, scopeOutcome, decode = null)
            test.doFirst {
                test.logger.lifecycle(
                    "[yoriwake] ${test.path}: ${scopeOutcome.detail}. Nothing is captured or selected " +
                        "for this task and every run of it is a full run. This is a limitation of " +
                        "the plugin, not a problem with your build. The only trace it leaves is " +
                        "on your jacoco settings: classes with no code-source location are " +
                        "instrumented, and the JDK's reflection classloaders are not."
                )
            }
            return
        }

        val agent = injectAgent(project, test)

        // Validated at configuration time so a typo fails the build now rather than protecting
        // nothing. The agent re-parses the same string in the test JVM.
        val alwaysRun = (
            alwaysRunDeclared + settings.alwaysRun
            ).map(String::trim).filter(String::isNotEmpty)
        if (alwaysRun.isNotEmpty()) {
            val joined = alwaysRun.joinToString(",")
            runCatching { io.github.zeuspizza.yoriwake.agent.select.AlwaysRun.from(joined) }.onFailure { refused ->
                throw org.gradle.api.InvalidUserDataException(
                    "[yoriwake] ${test.path}: ${refused.message}", refused,
                )
            }
            test.systemProperty(ALWAYS_RUN_PROPERTY, joined)
        }

        test.systemProperty(MAP_DIR_PROPERTY, mapDir.absolutePath)
        // Raw records stay beneath the map, inspectable after a run; the decoder writes the map.
        val runPlan = RunPlan.resolve(
            settings,
            { selectionWidening(project, settings, mapDir, buildMemo) },
            { since ->
                // Not a build-wide git failure: a scan git cannot answer declines this task alone.
                ChangeDetection.scanCommitMessages(
                    { ChangeDetection.cachedRawGit(project.providers, project.rootDir, buildMemo, it, recordFailures = false) },
                    since,
                )
            },
            fullRunBranches,
            {
                // Not a build-wide git failure either: a branch git cannot read declines this run alone.
                ChangeDetection.checkedOut {
                    ChangeDetection.cachedRawGit(project.providers, project.rootDir, buildMemo, it, recordFailures = false)
                }
            },
        )
        val selecting = runPlan.selecting
        if (runPlan.observing) {
            // Beside the selecting properties configureSelection sets, or a decline's refusal.
            test.systemProperty(OBSERVE_PROPERTY, "true")
        }
        val recording = settings.loaded
        // Why it refused, not just that it did. A build's own `test.includes`/`excludes` define
        // which classes are tests and do not refuse; `--tests` and per-run filters narrow the run
        // and do. The patterns set by now are the build script's: this runs once every build script
        // and `afterEvaluate` has, and before an IDE's test launcher adds its own. Copied as plain
        // sets, so the provider keeps no task reference beyond the one it already reads. A provider
        // resolves when the configuration cache stores its entry, before a test launcher's patterns
        // arrive, so the actions that must see them read the filter themselves.
        val fromBuildScript = TestPatterns.of(test.filter)
        val recordsDir = CoverageDecoder.recordsDir(mapDir)
        test.systemProperty(RECORDS_DIR_PROPERTY, recordsDir.absolutePath)
        // First, so its action runs last: after the selecting action has decided whether this run
        // captures, and with nothing between it and the test JVM.
        recordClasspathFiles(project, test, mapDir, selecting, buildMemo)
        observeWorkingTree(project, test, mapDir, selecting, buildMemo, startReading(project, buildMemo))
        discardPreviousRecords(test, recordsDir)
        recordWhetherTheRunDates(test, recordsDir, fromBuildScript)
        val afterTest = AfterTest(
            afterTestPending(recordsDir),
            listOf(
                RestoreHostCoverage(
                    recordsDir,
                    project.provider { (test.extensions.findByName("jacoco") as? JacocoTaskExtension)?.destinationFile },
                ),
            ),
        )
        afterTest.attachTo(test)

        // Opt-in (-Pyoriwake.internal.loaded): a second -javaagent learns which classes the test
        // JVM loaded. Cleared every run, so old files never pass for this run's output.
        val loadedDir = CoverageDecoder.loadedDir(mapDir)
        test.doFirst { loadedDir.deleteRecursively() }

        if (recording) {
            agent?.let { test.jvmArgs("-javaagent:" + it.absolutePath) }
            test.systemProperty(LOADED_DIR_PROPERTY, loadedDir.absolutePath)
        }
        configureNonPlatformRunner(test, agent, recording, selecting)

        // Carried as serialized text: a plain String round-trips through the configuration cache
        // unchanged. Typed explicitly because Kotlin 2.0 infers `Provider<String?>` here.
        val effectiveScope = project.provider<String> {
            EffectiveScope.readFrom(test.extensions.findByName("jacoco"))?.serialize()
        }

        val filterVerdict = project.provider { readFilterVerdict(test, fromBuildScript) }
        val unfiltered = project.provider { filterVerdict.get().unfiltered }

        // Registered in this order because `doFirst` prepends: the recorder must run before the
        // parallelism check can throw, so `yoriwakeAudit` can name the cause; the isolation after
        // that check, which may switch capture off.
        isolateWhileCapturing(test, runPlan)
        refuseInJvmParallelism(test, mapDir, runPlan.declines.firstOrNull()?.kind, runPlan.observing)
        recordTaskFacts(test, mapDir)
        if (runPlan.declines.isEmpty()) {
            // Registered before the marker's deletion, so it runs after it and may write one.
            if (runPlan.complementing) configureComplement(project, test, mapDir, runPlan, agent)
            // One a declined run left when its decode never ran must not discard this capture.
            val leftAlone = declinedLeftAloneMarker(recordsDir)
            test.doFirst { leftAlone.delete() }
            configureSelection(project, test, mapDir, buildMemo, runPlan, fromBuildScript)
        } else {
            declineSelection(test, mapDir, runPlan)
        }
        if (runPlan.notes.isNotEmpty()) {
            val notes = runPlan.notes
            test.doFirst { notes.forEach { test.logger.lifecycle("[yoriwake] ${test.path}: $it") } }
        }
        // After every selection action, so it runs before them; each returns on it. A declined run
        // was asked to select, so tests named on it still run as named.
        if (runPlan.asked != RunPlan.Kind.RECORD) declineNamedTests(test, mapDir, fromBuildScript, runPlan.observing)
        reportResolvedConfiguration(project, test, mapDir, scopeOutcome, fromBuildScript)
        if (selecting) startSelectionRecord(project, test, mapDir, agent)
        // Last, so its action runs first; every other action returns on it. See declinedUnderDevelocity.
        declineUnderDevelocity(project, test, mapDir, agent)
        // Decided at execution time, since Gradle applies --tests after afterEvaluate. `wholeTask`
        // also requires recording: only a recording run produces a loaded-class union.
        val wholeTask = project.provider { unfiltered.get() && recording }
        // Only a run that executed the whole suite may date the map, since unobserved records keep
        // their age; a fail-fast run may not. For selecting runs that is known only at execution
        // time and carried in a marker file. See FilterVerdict.datesTheMap for the filters that
        // still date it. These resolve when the configuration cache stores its entry; a filter
        // that gained patterns later overrides them through recordWhetherTheRunDates's marker.
        val datesTheMap = project.provider { filterVerdict.get().datesTheMap(test.failFast) }
        // Why a run that cannot date the map left it as it was; empty when it can.
        val undatedReason = project.provider { filterVerdict.get().undatedBy(test.failFast).orEmpty() }

        // The packages the union may speak for, resolved lazily once every project is evaluated.
        val loadedScope = project.provider { allProjectPackages(project) }

        configured[test.name] = Configured(
            mapDir, scope, scopeOutcome,
            DecodeTask.Inputs(
                mapDir, scopeOutcome, selecting, runPlan.observing, runPlan.asked == RunPlan.Kind.SELECT, runPlan.complementing,
                wholeTask, loadedScope, datesTheMap, undatedReason,
                // What JaCoCo actually instruments, read back off the task so the host's excludes
                // are in it. See EffectiveScope.
                effectiveScope,
                // What this run asked for; the decoder still checks that each JVM ran one class.
                isolated = settings.isolatedCapture && !selecting,
                afterTest = afterTest,
            ),
            fromBuildScript,
        )
        test.finalizedBy(DecodeTask.nameFor(test.name))

        // Once per build, not per project: one git failure makes every project force.
        buildMemo?.failureToReport()?.let { failure ->
            project.logger.lifecycle(
                "[yoriwake] $failure, so no change set could be computed and every task in this build " +
                    "runs in full. `yoriwakeAudit<Task>` reports this as `git-unavailable`."
            )
        }
    }

    /**
     * Tells the test JVM that the daemon refused. Selection stays off, so an agent that misses or
     * does not know the refusal property still runs everything.
     */
    internal fun refuse(test: Test, kind: RefusalKind, reason: String) {
        test.systemProperty(SELECT_PROPERTY, "false")
        test.systemProperty(REFUSED_PROPERTY, reason)
        test.systemProperty(REFUSED_KIND_PROPERTY, kind.token)
    }


    /**
     * Removes the previous run's raw records before this one writes any: the decoder folds in
     * whatever the directory holds.
     */
    private fun discardPreviousRecords(test: Test, recordsDir: File) {
        val marker = ranMarker(recordsDir)
        // The decisions file beside the map goes too: the agent writes it at JVM exit, so a run
        // whose agent never loaded would leave the previous run's decisions next to a fresh map.
        val decisions = File(recordsDir.parentFile, AgentContract.DECISIONS_FILE)
        // As does an observation, which describes the run that wrote it.
        val observation = File(recordsDir.parentFile, AgentContract.OBSERVATION_FILE)
        test.doFirst { task ->
            observation.delete()
            if (decisions.isFile && !decisions.delete()) {
                task.logger.warn(
                    "[yoriwake] ${task.path}: could not delete $decisions; if it survives this run it " +
                        "describes the previous one."
                )
            }
            decisions.parentFile.listFiles { file ->
                file.name.startsWith("${AgentContract.DECISIONS_FILE}.") &&
                    file.name.endsWith(AgentContract.DECISIONS_PART_SUFFIX)
            }?.forEach { part ->
                if (!part.delete()) {
                    task.logger.warn(
                        "[yoriwake] ${task.path}: could not delete $part; if it survives this run it " +
                            "merges the previous run's decisions into this one."
                    )
                }
            }
            // A worker directory that cannot be deleted (a file held open on Windows) makes
            // ExecRecordWriter refuse it, so that worker captures nothing. Said out loud.
            if (!recordsDir.deleteRecursively() && recordsDir.exists()) {
                task.logger.warn(
                    "[yoriwake] ${task.path}: could not clear $recordsDir, so this run may capture " +
                        "nothing and may merge records left by an earlier one. Delete it by hand " +
                        "if selection stops narrowing."
                )
            }
            marker.parentFile.mkdirs()
            marker.writeText("ran")
        }
    }

    /**
     * Records, as the task executes, why this run cannot date the map, for the decode to read:
     * the decode's own inputs were fixed when the configuration cache stored its entry, before an
     * IDE's test launcher added the tests it was asked to run. Deleted first, so an earlier run's
     * reason never outlives it; when it cannot be written, those inputs decide.
     */
    private fun recordWhetherTheRunDates(test: Test, recordsDir: File, fromBuildScript: TestPatterns) {
        val marker = undatedRunMarker(recordsDir)
        test.doFirst {
            marker.delete()
            if (declinedUnderDevelocity(test)) return@doFirst
            val reason = readFilterVerdict(test, fromBuildScript).undatedBy(test.failFast) ?: return@doFirst
            runCatching {
                marker.parentFile.mkdirs()
                marker.writeText(reason)
            }
        }
    }

    /**
     * Digests the build-produced files on the test classpath as the test JVM is about to see them,
     * for the decode to write beside the map; see [ClasspathFiles]. Before the JVM starts, so a file
     * a test writes into a classpath directory is never recorded. A selecting run that narrows
     * captures nothing, so it walks nothing.
     */
    private fun recordClasspathFiles(
        project: Project,
        test: Test,
        mapDir: File,
        selecting: Boolean,
        buildMemo: BuildMemo?,
    ) {
        val pending = pendingClasspathFiles(mapDir)
        val fullRunMarker = fullRunMarker(CoverageDecoder.recordsDir(mapDir))
        val classpath = test.classpath
        val buildDirs = projectFacts(project, buildMemo).buildDirs.values.toList()
        val rootDir = project.rootDir
        test.doFirst {
            pending.delete()
            if (declinedUnderDevelocity(test)) return@doFirst
            if (selecting && !fullRunMarker.isFile) return@doFirst
            val walk = runCatching { ClasspathFiles.walk(classpath.files, buildDirs, rootDir) }
                .getOrElse { ClasspathFiles.Walk.Refused(it.toString()) }
            when (walk) {
                is ClasspathFiles.Walk.Found ->
                    runCatching { CoverageDecoder.writeClasspathFilesTo(pending, walk.digests) }
                // No table: the next selecting run forces as unrecorded and captures again.
                is ClasspathFiles.Walk.Refused -> test.logger.info(
                    "[yoriwake] ${test.path}: no classpath file digests recorded (${walk.reason})"
                )
            }
        }
    }

    /**
     * Observes the working tree at the start of the run, as the tests will see it, for the decode
     * to write beside the map. See [WorkingTree]. Registered before the doFirst actions it depends on,
     * because `doFirst` prepends.
     */
    private fun observeWorkingTree(
        project: Project,
        test: Test,
        mapDir: File,
        selecting: Boolean,
        buildMemo: BuildMemo?,
        startReading: String?,
    ) {
        val dated = pendingSnapshot(mapDir)
        val stats = pendingStats(mapDir)
        val head = pendingHead(mapDir)
        // The tree, like HEAD, is read before compilation, at configuration: an edit between the two
        // would otherwise be in the snapshot while the compiled classes predate it.
        if (startReading != null) {
            startTree(project, mapDir, buildMemo)
        }
        test.doFirst {
            // One left by an earlier build describes its tree; the undated one only 0.1 wrote.
            head.delete()
            File(mapDir, WorkingTree.SNAPSHOT_FILE + ".undated").delete()
            if (declinedUnderDevelocity(test)) return@doFirst
            val reading = startReading?.let(CaptureStart::decode)
            if (reading == null) {
                // No start reading this build: the decode removes the stamp and the snapshot, and the
                // next run refuses and captures.
                dated.delete()
                return@doFirst
            }
            val pending = CaptureStart.Pending(reading, WorkingTree.startId(dated), WorkingTree.startId(stats))
            runCatching { writeAtomically(head, pending.encode()) }
        }
    }

    private fun startTree(project: Project, mapDir: File, memo: BuildMemo?) {
        val rootDir = project.rootDir
        val excluded = WorkingTree.excluded(rootDir, projectFacts(project, memo).buildDirs.values)
        val git = { arguments: List<String> -> ChangeDetection.cachedRawGit(project.providers, rootDir, memo, arguments) }
        val listed = WorkingTree.configuredListing(rootDir, excluded, memo, git)
        // Asked outside the memo's compute below: git memoises through the same map.
        val tracked = git(listOf("ls-files", "-z"))
        val stats = {
            project.providers.of(WorkingTree.StatsSource::class.java) {
                it.parameters.rootDir.set(rootDir.absolutePath)
                it.parameters.file.set(pendingStats(mapDir).absolutePath)
                // Unset when git could not answer, which the source reads as exactly that.
                tracked?.let { said -> it.parameters.tracked.set(said) }
                it.parameters.excluded.set(excluded.joinToString("\u0000"))
            }
        }
        // One provider per build, so its stat of every tracked file runs once, not once per task.
        runCatching { (memo?.provider(YoriwakePlugin.CAPTURE_STATS_KEY + rootDir.path, stats) ?: stats()).get() }
        runCatching {
            project.providers.of(WorkingTree.StartSource::class.java) {
                it.parameters.rootDir.set(rootDir.absolutePath)
                it.parameters.mapDir.set(mapDir.absolutePath)
                it.parameters.file.set(pendingSnapshot(mapDir).absolutePath)
                listed?.let { joined -> it.parameters.listed.set(joined) }
            }.get()
        }
    }

    private fun startReading(project: Project, memo: BuildMemo?): String? {
        val arguments = project.gradle.startParameter.taskRequests.flatMap { it.args }
        if ("--tests" in arguments || "--fail-fast" in arguments) {
            return null
        }
        val read = {
            runCatching {
                project.providers.of(CaptureStart.Source::class.java) {
                    it.parameters.rootDir.set(project.rootDir.absolutePath)
                }.get()
            }.getOrNull()
        }
        return memo?.value(YoriwakePlugin.CAPTURE_START_KEY + project.rootDir.path, read) ?: read()
    }

    /**
     * Reports what is actually in effect, read back from the task at execution time rather than
     * restating what this plugin intended, so it can disagree with intent.
     */
    private fun reportResolvedConfiguration(
        project: Project,
        test: Test,
        mapDir: File,
        scope: ScopeOutcome,
        fromBuildScript: TestPatterns,
    ) {
        // Through a provider: Task.extensions may not be touched at execution time under the
        // configuration cache.
        val jacoco = { test.extensions.findByName("jacoco") as? JacocoTaskExtension }
        val liveIncludes = project.provider { jacoco()?.includes?.toString() }
        // Whether a class is instrumented at all is orthogonal to the included packages, so it is
        // read back too. A failure would show only as Robolectric tests never attributing.
        val liveNoLocation = project.provider { jacoco()?.isIncludeNoLocationClasses?.toString() }
        // The containment for the setting above: without it a deserializing suite may not start.
        val liveExcludedLoaders = project.provider { jacoco()?.excludeClassLoaders?.toString() }
        // Only plain values are captured by the task action, for the configuration cache.
        val scopeDescription = scope.toString()
        val scopeDetail = scope.detail

        test.doFirst {
            // A declined run took the map directory away on purpose, and runs nothing of ours.
            if (declinedUnderDevelocity(test)) return@doFirst
            val liveMapDir = test.systemProperties[MAP_DIR_PROPERTY]

            val agentOnClasspath = test.classpath.files.any { it.name == AgentJar.RESOURCE }

            test.logger.lifecycle(
                "[yoriwake] ${test.path} map=$liveMapDir scope=$scopeDescription " +
                    "includes=${liveIncludes.orNull} " +
                    "nolocation=${liveNoLocation.orNull ?: "unknown"} " +
                    "exclloaders=${liveExcludedLoaders.orNull ?: "unknown"} " +
                    "agent=$agentOnClasspath forks=${test.maxParallelForks} " +
                    "unfiltered=${readFilterVerdict(test, fromBuildScript)}"
            )

            // No agent means no records, no map, and every later run silently forcing. A task whose
            // scope could not be applied never reaches this code; configure() declines it earlier.
            val broken = when {
                !agentOnClasspath -> "the agent is not on the test runtime classpath, so nothing would be captured"
                mapDir.absolutePath != liveMapDir ->
                    "the map directory was overwritten after configuration (expected $mapDir, task " +
                        "reports $liveMapDir)"
                else -> null
            } ?: return@doFirst
            // Registered last, so it runs first: nothing after it turns selection or capture back on.
            test.systemProperty(SELECT_PROPERTY, "false")
            test.systemProperty(RECORDS_DIR_PROPERTY, "")
            test.logger.warn(
                "[yoriwake] ${test.path}: $broken. Nothing is selected or captured, so every test runs."
            )
        }
    }

    /**
     * With `-Pyoriwake.isolatedCapture`, gives each test class a JVM of its own on a run that records
     * the map, so its coverage and first touches are its own work. The host's `maxParallelForks`
     * stands. A selecting run never gets it, even one that ends up capturing, and neither does a
     * run whose capture is off, so both run the host's tests as the host configured them.
     *
     * At execution time because the in-JVM parallelism refusal, which switches capture off, is only
     * decided then; registered before [refuseInJvmParallelism] so it runs after it.
     */
    private fun isolateWhileCapturing(test: Test, runPlan: RunPlan) {
        if (!settings.isolatedCapture || runPlan.selecting) {
            return
        }
        test.doFirst {
            // Emptied by a refusal: nothing is recorded, so the host's forks stand.
            if (test.systemProperties[RECORDS_DIR_PROPERTY]?.toString().isNullOrEmpty()) {
                return@doFirst
            }
            test.forkEvery = 1
            test.logger.lifecycle(
                "[yoriwake] ${test.path}: recording with a fresh test JVM per test class " +
                    "(-P${Settings.ISOLATED_CAPTURE}), up to ${test.maxParallelForks} at a time"
            )
        }
    }

    /**
     * Writes the test framework and parallelism beside the map for `yoriwakeAudit`; both settle too
     * late for configuration time. Registered after [refuseInJvmParallelism] so it runs first.
     */
    private fun recordTaskFacts(test: Test, mapDir: java.io.File) {
        test.doFirst { task ->
            task as Test
            val framework = when (task.options) {
                is org.gradle.api.tasks.testing.junitplatform.JUnitPlatformOptions ->
                    Audit.TaskFacts.PLATFORM
                is org.gradle.api.tasks.testing.junit.JUnitOptions -> "JUnit 4, off the Platform"
                is org.gradle.api.tasks.testing.testng.TestNGOptions -> "TestNG"
                else -> task.options.javaClass.simpleName
            }
            val testng = task.options as? org.gradle.api.tasks.testing.testng.TestNGOptions
            Audit.TaskFacts.write(
                mapDir,
                framework,
                ParallelismDetector.detect(
                    systemProperties = task.systemProperties.mapValues { it.value?.toString() },
                    jvmArgs = task.allJvmArgs,
                    testRuntimeFiles = task.classpath.files,
                    testngParallel = testng?.parallel,
                    testngThreadCount = testng?.threadCount,
                ),
                // Set by the decline, which runs before this action.
                develocity = task.systemProperties[REFUSED_KIND_PROPERTY]?.toString()
                    ?.takeIf { declinedUnderDevelocity(task) },
            )
        }
    }

    /**
     * Refuses in-JVM parallel test execution while capturing: it interleaves tests under one agent
     * and blends their attributions. Parallel forks are fine. An [observing] run captures with tests
     * named too, so it is refused then as well.
     */
    private fun refuseInJvmParallelism(test: Test, mapDir: File, planned: RefusalKind?, observing: Boolean) {
        // Resolved here, not in the action: `Task.extensions` at execution time violates the
        // configuration cache.
        val jacoco = test.extensions.findByName("jacoco") as? JacocoTaskExtension
        test.doFirst {
            // Already declined, with the host's JaCoCo left as the host set it, or with tests named,
            // which select nothing and, unless the run observes, capture nothing.
            if (declinedUnderDevelocity(test) || (!observing && declinedForNamedTests(test, planned))) return@doFirst
            val testng = test.options as? org.gradle.api.tasks.testing.testng.TestNGOptions
            val source = ParallelismDetector.detect(
                systemProperties = test.systemProperties.mapValues { it.value?.toString() },
                jvmArgs = test.allJvmArgs,
                testRuntimeFiles = test.classpath.files,
                testngParallel = testng?.parallel,
                testngThreadCount = testng?.threadCount,
            ) ?: return@doFirst

            // Declines rather than throwing, so the suite still runs, and suppresses capture too:
            // a blended map would be believed by every later run.
            val marker = parallelRefusalMarker(CoverageDecoder.recordsDir(mapDir))
            runCatching {
                marker.parentFile.mkdirs()
                marker.writeText("$source\n")
            }.onFailure {
                // The decode finalizer reads this marker and leaves the map alone on purpose.
                test.logger.warn(
                    "[yoriwake] ${test.path}: could not record the parallelism refusal ($it). Nothing " +
                        "is captured either way; the map is untouched because no records exist."
                )
            }
            refuse(
                test,
                RefusalKind.IN_JVM_PARALLELISM,
                "in-JVM parallel execution is enabled via $source, so nothing is captured or selected here",
            )
            test.systemProperty(RECORDS_DIR_PROPERTY, "")
            runCatching {
                jacoco?.isEnabled = false
            }.onFailure {
                // No records directory means no capture anyway; this costs speed, not correctness.
                test.logger.warn(
                    "[yoriwake] ${test.path}: could not switch instrumentation off ($it), so this run " +
                        "pays the toll while capturing nothing."
                )
            }
            test.logger.warn(
                "[yoriwake] ${test.path}: in-JVM parallel test execution is enabled (via $source), so " +
                    "predictive test selection is declined here. Every test runs, nothing is " +
                    "captured, and the map is left exactly as it was. Tests interleaved inside one " +
                    "JVM share a single coverage agent, so per-test attribution would blend them. " +
                    "Parallel FORKS (maxParallelForks) are unaffected and remain safe. " +
                    "`yoriwakeAudit${test.name.replaceFirstChar(Char::uppercase)}` reports this as " +
                    "`in-jvm-parallelism`."
            )
        }
    }

    /**
     * Declines a task Develocity runs or chooses tests for: it may run them on other machines, or
     * leave out tests yoriwake would keep, so yoriwake neither selects nor records there. The task
     * then runs as without yoriwake, but for the agent jar on its classpath, inert with selection
     * off. Registered last, so its action runs first.
     */
    private fun declineUnderDevelocity(project: Project, test: Test, mapDir: File, agent: File?) {
        val develocity = DevelocityDetection.provider(project, test)
        val agentArgument = agent?.let { "-javaagent:" + it.absolutePath }
        val marker = develocityRefusalMarker(CoverageDecoder.recordsDir(mapDir))
        test.doFirst {
            val (kind, reason) = DevelocityDetection.decode(develocity.orNull) ?: run {
                // One a declined run left when its decode never ran must not discard this capture.
                marker.delete()
                return@doFirst
            }
            // The decode returns on it, so a run that captured nothing on purpose is not reported
            // as one whose listener never loaded.
            runCatching {
                marker.parentFile.mkdirs()
                marker.writeText(kind.token + "\n")
            }.onFailure {
                test.logger.warn(
                    "[yoriwake] ${test.path}: could not record the Develocity decline ($it). Nothing is " +
                        "captured either way; its decode may report that nothing was recorded."
                )
            }
            refuse(test, kind, reason)
            test.systemProperty(RECORDS_DIR_PROPERTY, "")
            test.systemProperty(LOADED_DIR_PROPERTY, "")
            // Absolute local paths: under Test Distribution they would travel to another machine.
            test.setSystemProperties(
                test.systemProperties.filterKeys { it != MAP_DIR_PROPERTY && it != CHANGE_SET_FILE_PROPERTY }
            )
            // Added at configuration on a run that records loaded classes.
            agentArgument?.let { argument -> test.setJvmArgs(test.jvmArgs.orEmpty().filterNot { it == argument }) }
            test.logger.lifecycle(
                "[yoriwake] ${test.path}: $reason. The map is left exactly as it was. " +
                    "`yoriwakeAudit${test.name.replaceFirstChar(Char::uppercase)}` reports this as `${kind.token}`."
            )
        }
    }

    /**
     * Gives a selecting run its token and reads the stamp its selection record will carry, before
     * any other action of ours but the Develocity decline: the commit, whether the tree is clean,
     * the task, the build, the classpath and the task's configuration. See [SelectionRecord].
     */
    private fun startSelectionRecord(project: Project, test: Test, mapDir: File, agent: File?) {
        val identity = StampIdentity.of(project, test, agent)
        test.doFirst {
            File(mapDir, SelectionRecord.START_FILE).delete()
            if (declinedUnderDevelocity(test)) return@doFirst
            // A test JVM keeps running after the first failure, and Gradle drops what it reports then:
            // a test could be listed as ran whose outcome no run showed.
            if (test.failFast) {
                test.logger.info("[yoriwake] ${test.path}: --fail-fast drops the results of tests that finish after the first failure, so this run leaves no selection record")
                return@doFirst
            }
            val token = java.util.UUID.randomUUID().toString()
            test.systemProperty(RUN_TOKEN_PROPERTY, token)
            runCatching { SelectionRecord.writeStart(mapDir, SelectionRecord.Start(token, identity.observe(test))) }
                .onFailure { test.logger.info("[yoriwake] ${test.path}: the selection record's start could not be written ($it)") }
        }
    }

    /** Puts the agent on the test runtime classpath. */
    private fun injectAgent(project: Project, test: Test): File? {
        // Unpacked at execution time, named at configuration time: writing the jar during
        // configuration invalidated the first build's own cache entry.
        if (!AgentJar.isBundled()) {
            test.logger.warn(
                "[yoriwake] the agent jar is not bundled in this plugin build; ${test.path} will record " +
                    "nothing and selection will have no map to read."
            )
            return null
        }
        val agent = AgentJar.locationIn(File(project.cacheDir(), AgentJar.DIRECTORY))
        test.classpath += project.files(agent)
        // Re-checked every run, so a cleaned cache directory gets its agent back.
        test.doFirst { AgentJar.extractTo(agent.parentFile) }
        return agent
    }

    /**
     * Attaches the agent to every test task, and on one that does not run on the JUnit Platform
     * turns on the agent's `premain` bytecode hook, its only capture path there. Decided in
     * `doFirst`, since the test framework is often set after the plugin is applied.
     */
    private fun configureNonPlatformRunner(
        test: Test,
        agent: java.io.File?,
        alreadyAttached: Boolean,
        selecting: Boolean,
    ) {
        val path = agent?.absolutePath
        test.doFirst { task ->
            if (declinedUnderDevelocity(task as Test)) return@doFirst
            // Every framework: only the attached agent sees what a test JVM loads and reads, which
            // is what dates the code a JVM runs once. Attached here rather than at configuration,
            // so the jar's absolute path stays out of the task's cache key.
            if (path != null && !alreadyAttached) {
                (task as Test).jvmArgs("-javaagent:$path")
            }
            if ((task as Test).options is org.gradle.api.tasks.testing.junitplatform.JUnitPlatformOptions) {
                return@doFirst
            }
            if (task.options is org.gradle.api.tasks.testing.junit.JUnitOptions) {
                // Tells the agent that rewriting RunNotifier is the capture path. On a
                // vintage-engine JVM both paths would steal each other's coverage windows.
                task.systemProperty(JUNIT4_HOOK_PROPERTY, "true")
            }
            // Only the Platform's in-JVM filter deselects, so a task off it runs everything.
            if (selecting) {
                task.logger.lifecycle(
                    "[yoriwake] ${task.path} does not run on the JUnit Platform, so selection runs every test."
                )
            }
        }
    }


    private companion object {
        /** Whether this thread is inside [timedConfigure] already. */
        val TIMING: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }
    }
}

/**
 * What [test]'s own filter leaves to run; see [FilterVerdict]. Read once Gradle has applied
 * `--tests`. [fromBuildScript] is the filter's patterns as they stood when yoriwake configured the
 * task.
 */
// Top level, so the providers reading it capture no wiring instance.
internal fun readFilterVerdict(test: Test, fromBuildScript: TestPatterns): FilterVerdict {
    val filter = test.filter
    // A Gradle without this internal type or its method links to nothing here, which must refuse
    // as an unreadable filter does rather than fail the task.
    val commandLine = runCatching {
        (filter as? org.gradle.api.internal.tasks.testing.filter.DefaultTestFilter)?.commandLineIncludePatterns?.toSet()
    }.getOrNull()
    return decideFilterVerdict(
        unreadable = if (commandLine == null) filter.javaClass.name else null,
        commandLine = commandLine.orEmpty(),
        fromBuildScript = fromBuildScript,
        live = TestPatterns.of(filter),
        framework = frameworkFilter(test),
        onPlatform = test.options is org.gradle.api.tasks.testing.junitplatform.JUnitPlatformOptions,
        unfilteredDetail = "(task includes=${test.includes} excludes=${test.excludes})",
    )
}

/** A test filter's name patterns: `filter.includePatterns` and `filter.excludePatterns`. */
internal data class TestPatterns(val includes: Set<String>, val excludes: Set<String>) {
    fun isEmpty() = includes.isEmpty() && excludes.isEmpty()

    /** As `filter.<set>=[...]`, each set that is not empty. */
    fun describe(): String = listOf("includePatterns" to includes, "excludePatterns" to excludes)
        .filter { it.second.isNotEmpty() }
        .joinToString(" ") { (name, patterns) -> "filter.$name=$patterns" }

    companion object {
        fun of(filter: org.gradle.api.tasks.testing.TestFilter) =
            TestPatterns(filter.includePatterns.toSet(), filter.excludePatterns.toSet())
    }
}

/**
 * The verdict for a filter, first match wins: one that cannot be read; `--tests`; patterns added
 * after the build script ran; the build script's own patterns; the framework's filter; none.
 *
 * Only the build script's patterns, on the JUnit Platform, join the framework filters in dating
 * the map. Off the Platform a dated map feeds class-granular selection, which would exclude a class
 * whose left-out method is not in the map. Taking a pattern's source wrongly costs speed, not
 * safety: a dating capture keeps no record it did not re-observe, and a test not in the map runs.
 */
internal fun decideFilterVerdict(
    unreadable: String?,
    commandLine: Set<String>,
    fromBuildScript: TestPatterns,
    live: TestPatterns,
    framework: String?,
    onPlatform: Boolean,
    unfilteredDetail: String,
): FilterVerdict {
    val added = TestPatterns(live.includes - fromBuildScript.includes, live.excludes - fromBuildScript.excludes)
    return when {
        // Fail closed: a gate that depends on a Gradle internal refuses when it cannot see.
        unreadable != null -> FilterVerdict(
            false,
            "the test filter is a $unreadable, not a DefaultTestFilter, " +
                "so this build's filtering cannot be read",
            filterReadable = false,
        )
        commandLine.isNotEmpty() -> FilterVerdict(false, "--tests $commandLine")
        !added.isEmpty() -> FilterVerdict(false, "${added.describe()} added after the build script ran")
        !live.isEmpty() -> FilterVerdict(
            false,
            listOfNotNull("${live.describe()} from the build script", framework).joinToString(" "),
            byBuildScript = onPlatform,
        )
        // Tags, engines, categories and groups leave tests out as surely as a pattern does.
        framework != null -> FilterVerdict(false, framework, byFramework = true)
        else -> FilterVerdict(true, unfilteredDetail)
    }.copy(commandLinePatterns = commandLine, addedIncludePatterns = added.includes)
}

/**
 * What a selection record's stamp needs from configuration, as plain values, and how it reads the
 * rest when the task runs: a selecting run reads it where it starts, a complement run before it
 * leaves anything out, so both read the same fields at the same point of the task.
 */
internal class StampIdentity(
    private val rootDir: File,
    private val task: String,
    private val buildPath: String,
    private val gradleUserHome: File,
    /** The plugin's own `-javaagent` argument, left out of the configuration digest. */
    private val agentArgument: String?,
) : java.io.Serializable {

    fun observe(test: Test): SelectionRecord.Stamp {
        val git = ChangeDetection.directRunner(rootDir)
        val top = git.run(listOf("rev-parse", "--show-toplevel"))?.firstOrNull()
        val buildRoot = top?.let { runCatching { rootDir.canonicalFile.relativeTo(File(it).canonicalFile).invariantSeparatorsPath }.getOrNull() }
        return SelectionRecord.Stamp(
            commit = ChangeDetection.head(git),
            clean = ChangeDetection.status(git)?.isEmpty() == true,
            task = task,
            // Unknown reads as a root no build has, so it never matches.
            buildRoot = buildRoot ?: "?${rootDir.absolutePath}",
            buildPath = buildPath,
            classpath = SelectionRecord.classpathDigest(test.classpath.files, rootDir, gradleUserHome),
            configuration = SelectionRecord.configurationDigest(
                test.systemProperties,
                // Every argument the test JVM starts with, those of argument providers included, but
                // the agents and properties the plugin and JaCoCo set by run kind.
                test.allJvmArgs.filter { argument ->
                    argument != agentArgument && !argument.startsWith("-Dyoriwake.") &&
                        !(argument.startsWith("-javaagent:") && argument.substringBefore('=').endsWith("jacocoagent.jar"))
                },
                test.includes.sorted().map { "include $it" } + test.excludes.sorted().map { "exclude $it" } +
                    test.filter.includePatterns.sorted().map { "filter.include $it" } +
                    test.filter.excludePatterns.sorted().map { "filter.exclude $it" },
                frameworkFilter(test),
            ),
        )
    }

    companion object {
        fun of(project: Project, test: Test, agent: File?) = StampIdentity(
            project.rootDir, test.path, project.buildTreePath, project.gradle.gradleUserHomeDir,
            agent?.let { "-javaagent:" + it.absolutePath },
        )
    }
}

/** The test framework's own filters that are set, as `name=[values]`, or null when none is. */
// Top level, so the provider reading it captures no wiring instance.
private fun frameworkFilter(test: Test): String? {
    val filters = when (val options = test.options) {
        is org.gradle.api.tasks.testing.junitplatform.JUnitPlatformOptions -> listOf(
            "includeTags" to options.includeTags, "excludeTags" to options.excludeTags,
            "includeEngines" to options.includeEngines, "excludeEngines" to options.excludeEngines,
        )
        is org.gradle.api.tasks.testing.junit.JUnitOptions -> listOf(
            "includeCategories" to options.includeCategories, "excludeCategories" to options.excludeCategories,
        )
        is org.gradle.api.tasks.testing.testng.TestNGOptions -> listOf(
            "includeGroups" to options.includeGroups, "excludeGroups" to options.excludeGroups,
        )
        else -> emptyList()
    }
    return filters.filter { it.second.isNotEmpty() }.takeIf { it.isNotEmpty() }
        ?.joinToString(" ") { (name, values) -> "$name=$values" }
}

/** The start reading of HEAD and its reflogs; see [CaptureStart]. */
internal fun pendingHead(mapDir: File) = File(mapDir, CaptureStart.PENDING_FILE)

/**
 * The stat tokens of the tracked paths where the capture started, shared by the build's maps; see
 * [WorkingTree.startStats].
 */
internal fun pendingStats(mapDir: File) = File(mapDir.parentFile.parentFile, WorkingTree.STATS_PENDING_FILE)

/** The classpath files where the capture started; see [ClasspathFiles]. */
internal fun pendingClasspathFiles(mapDir: File) = File(mapDir, CoverageDecoder.RESOURCE_DIGESTS_FILE + ".pending")

/** The tree where the capture started; see [WorkingTree.startSnapshot]. */
internal fun pendingSnapshot(mapDir: File) = File(mapDir, WorkingTree.SNAPSHOT_FILE + ".dated")

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

internal val DEVELOCITY_REFUSALS = setOf(
    RefusalKind.DEVELOCITY_TEST_DISTRIBUTION,
    RefusalKind.DEVELOCITY_TEST_SELECTION,
    RefusalKind.DEVELOCITY_UNDETERMINED,
).map { it.token }

/**
 * Whether this run declined under Develocity. Every yoriwake action of the task that would write a
 * file, a marker, a JVM argument, a system property or the host's JaCoCo returns first on it: the
 * decline runs before them, and nothing of ours may act on a run Develocity shapes.
 */
internal fun declinedUnderDevelocity(test: Test) =
    test.systemProperties[REFUSED_KIND_PROPERTY]?.toString() in DEVELOCITY_REFUSALS

/**
 * Honours `--project-cache-dir`. `rootDir`, not `rootProject.file(...)`, which is a
 * cross-project read Isolated Projects forbids.
 */
internal fun Project.cacheDir(): File =
    gradle.startParameter.projectCacheDir ?: File(rootDir, ".gradle")

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

/**
 * Whether the run's own test filter leaves the whole task to run, and what was read to decide. Its
 * text is what the resolved-configuration line prints after `unfiltered=`. [byFramework] when only
 * the test framework's own filter (tags, engines, categories, groups) leaves tests out;
 * [byBuildScript] when the build script's own patterns do, on a JUnit Platform task.
 * [commandLinePatterns] are the `--tests` patterns, whatever else is set; [addedIncludePatterns] the
 * include patterns added after the build script ran, as an IDE's test launcher adds the tests it was
 * asked to run; [filterReadable] is false when the filter is not one whose `--tests` patterns can be
 * read.
 */
internal data class FilterVerdict(
    val unfiltered: Boolean,
    val detail: String,
    val byFramework: Boolean = false,
    val commandLinePatterns: Set<String> = emptySet(),
    val addedIncludePatterns: Set<String> = emptySet(),
    val filterReadable: Boolean = true,
    val byBuildScript: Boolean = false,
) {
    override fun toString(): String = if (unfiltered) "$UNFILTERED $detail" else "$FILTERED: $detail"

    /**
     * Why a run under this filter cannot date the map, or null when it can. A framework or
     * build-script filter still dates it: the dating capture drops the records of the tests it left
     * out, and a test not in the map runs.
     */
    fun undatedBy(failFast: Boolean): String? = when {
        failFast -> "--fail-fast"
        !(unfiltered || byFramework || byBuildScript) -> "filtered by $detail"
        else -> null
    }

    fun datesTheMap(failFast: Boolean): Boolean = undatedBy(failFast) == null

    /**
     * The decline a selecting run takes for this filter, as its kind and reason: tests named with
     * `--tests` or added to the filter for this run, or a filter whose `--tests` patterns cannot be
     * read. Null when none holds.
     */
    fun namedTestsDecline(): Pair<RefusalKind, String>? = when {
        !filterReadable -> RefusalKind.DECLINE_UNDETERMINED to "$detail, so whether tests were named with --tests is unknown"
        commandLinePatterns.isNotEmpty() -> RefusalKind.TESTS_NAMED to
            "tests were named with --tests ${commandLinePatterns.sorted().joinToString(" ")}"
        addedIncludePatterns.isNotEmpty() -> RefusalKind.TESTS_NAMED to
            "tests were named for this run (filter.includePatterns=${addedIncludePatterns.sorted()})"
        else -> null
    }

    companion object {
        const val UNFILTERED = "yes"
        const val FILTERED = "no"
    }
}
