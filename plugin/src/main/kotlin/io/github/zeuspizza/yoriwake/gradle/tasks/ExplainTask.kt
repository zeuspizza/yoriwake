package io.github.zeuspizza.yoriwake.gradle.tasks

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.agent.select.FilterRule
import io.github.zeuspizza.yoriwake.agent.select.Strictness
import io.github.zeuspizza.yoriwake.gradle.RunPlan
import io.github.zeuspizza.yoriwake.gradle.Settings
import io.github.zeuspizza.yoriwake.gradle.report.Audit
import io.github.zeuspizza.yoriwake.gradle.YoriwakePlugin
import io.github.zeuspizza.yoriwake.gradle.YoriwakePlugin.Companion.noBaseFound
import io.github.zeuspizza.yoriwake.gradle.capture.CoverageDecoder
import io.github.zeuspizza.yoriwake.gradle.capture.IsolatedPick
import io.github.zeuspizza.yoriwake.gradle.capture.EnvironmentFingerprint
import io.github.zeuspizza.yoriwake.gradle.capture.MapAge
import io.github.zeuspizza.yoriwake.gradle.capture.MapProvenance
import io.github.zeuspizza.yoriwake.gradle.capture.Schedule
import io.github.zeuspizza.yoriwake.gradle.capture.readCaptureStamp
import io.github.zeuspizza.yoriwake.gradle.capture.widenToMapAge
import io.github.zeuspizza.yoriwake.gradle.change.ChangeDetection
import io.github.zeuspizza.yoriwake.gradle.change.Declarations
import io.github.zeuspizza.yoriwake.gradle.change.SelectingStep
import io.github.zeuspizza.yoriwake.gradle.change.ForcingPaths
import io.github.zeuspizza.yoriwake.gradle.change.INLINE_REFUSAL_KEY
import io.github.zeuspizza.yoriwake.gradle.change.LargeChange
import io.github.zeuspizza.yoriwake.gradle.change.PathIgnore
import io.github.zeuspizza.yoriwake.gradle.change.RefusalKind
import io.github.zeuspizza.yoriwake.gradle.change.WorkingTree
import io.github.zeuspizza.yoriwake.gradle.change.changedPaths
import io.github.zeuspizza.yoriwake.gradle.change.defaultBase
import io.github.zeuspizza.yoriwake.gradle.change.outsideRoot
import io.github.zeuspizza.yoriwake.gradle.change.ClasspathRecord
import io.github.zeuspizza.yoriwake.gradle.change.DependencyRule
import io.github.zeuspizza.yoriwake.gradle.facts.classpathConfigurations
import io.github.zeuspizza.yoriwake.gradle.facts.moduleIdentities
import io.github.zeuspizza.yoriwake.gradle.facts.moduleScript
import io.github.zeuspizza.yoriwake.gradle.facts.BuildMemo
import io.github.zeuspizza.yoriwake.gradle.facts.ClasspathFacts
import io.github.zeuspizza.yoriwake.gradle.facts.classpathFacts
import io.github.zeuspizza.yoriwake.gradle.facts.projectFacts
import io.github.zeuspizza.yoriwake.gradle.report.PathIgnoreReport
import io.github.zeuspizza.yoriwake.gradle.report.loosenedTests
import io.github.zeuspizza.yoriwake.gradle.report.selectionShare
import io.github.zeuspizza.yoriwake.gradle.report.writeExplanation
import io.github.zeuspizza.yoriwake.gradle.report.writeUnanswered
import io.github.zeuspizza.yoriwake.gradle.wiring.DevelocityDetection
import io.github.zeuspizza.yoriwake.gradle.wiring.ScopeOutcome
import io.github.zeuspizza.yoriwake.gradle.wiring.TestPatterns
import io.github.zeuspizza.yoriwake.gradle.wiring.buildLogicDirs
import io.github.zeuspizza.yoriwake.gradle.wiring.checkEnvironment
import io.github.zeuspizza.yoriwake.gradle.wiring.environmentRefusal
import io.github.zeuspizza.yoriwake.gradle.wiring.environmentView
import io.github.zeuspizza.yoriwake.gradle.wiring.extraTaskInputs
import io.github.zeuspizza.yoriwake.gradle.wiring.readFilterVerdict
import io.github.zeuspizza.yoriwake.gradle.wiring.unionFilterOf
import io.github.zeuspizza.yoriwake.gradle.wiring.trustedDigest
import java.io.File
import java.nio.file.Files
import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.api.tasks.testing.Test

/**
 * `yoriwakeExplain<Task>`: what selection would do here, without running a single test,
 * through the same `Selector.decide` the test JVM applies.
 */
@UntrackedTask(because = "its answer depends on git and on the map, which Gradle cannot fingerprint")
internal abstract class ExplainTask : DefaultTask() {

    @get:Input
    abstract val taskPath: Property<String>

    @get:Input
    @get:Optional
    abstract val explicitBase: Property<String>

    /** The `-Pyoriwake.mode` this explanation decides under; unset is the safe rule. */
    @get:Input
    @get:Optional
    abstract val mode: Property<String>

    /** What the run would decline under Develocity, encoded; empty when it would not. */
    @get:Input
    @get:Optional
    abstract val develocity: Property<String>

    /** The token of the decline the run takes over `--tests`; unset when it takes none. */
    @get:Input
    @get:Optional
    abstract val namedTestsKind: Property<String>

    /** That decline's reason, as the run prints it. */
    @get:Input
    @get:Optional
    abstract val namedTestsReason: Property<String>

    /** The digest of the build-script filter the run runs under; unset when it runs unfiltered. */
    @get:Input
    @get:Optional
    abstract val unionFilter: Property<String>

    /** Whether `-Pyoriwake.fullRun` was passed, which declines selection. */
    @get:Input
    @get:Optional
    abstract val fullRunRequested: Property<Boolean>

    /** The build's `fullRunBranches`; a listed branch checked out declines selection. */
    @get:Input
    @get:Optional
    abstract val fullRunBranches: ListProperty<String>

    /** The build's `notReadByTests`: changed files that match one are no change. */
    @get:Input
    @get:Optional
    abstract val notReadByTests: ListProperty<String>

    /** The build's `pathIgnore`: changed paths that match one leave the change set, unless protected. */
    @get:Input
    @get:Optional
    abstract val pathIgnore: ListProperty<String>

    /** The build's `pathIgnoreDefaults`: whether the default entries are matched after [pathIgnore]. */
    @get:Input
    @get:Optional
    abstract val pathIgnoreDefaults: Property<Boolean>

    /**
     * The test task's input files, and its classpath and test classes beside them: what the run's
     * task-input rule for the default entries compares.
     */
    @get:Internal("read only when a default entry matched; the task is untracked")
    var testInputs: org.gradle.api.file.FileCollection? = null

    @get:Internal("read only when a default entry matched; the task is untracked")
    var testOwned: org.gradle.api.file.FileCollection? = null

    @get:Internal("only names where build logic lies; the task is untracked")
    var buildLogicDirs: List<String> = emptyList()

    /** The build's `fullRunEveryBuilds`; unset when it has none. */
    @get:Input
    @get:Optional
    abstract val fullRunEveryBuilds: Property<Int>

    /** The build's `fullRunEveryDays`; unset when it has none. */
    @get:Input
    @get:Optional
    abstract val fullRunEveryDays: Property<Int>

    /** Whether `-Pyoriwake.trustedMaps` was passed, which makes the run check the map's provenance. */
    @get:Input
    @get:Optional
    abstract val checksProvenance: Property<Boolean>

    /** The build's `largeChangeThreshold`. */
    @get:Input
    abstract val largeChangeThreshold: Property<Int>

    /** The digest that list names for this map; unset when it names none. */
    @get:Input
    @get:Optional
    abstract val listedDigest: Property<String>

    /** The build's own output directories, which the working-tree drift leaves out. */
    @get:Input
    @get:Optional
    abstract val excluded: ListProperty<String>

    /** Whether the test task is plain JUnit 4 with `narrowPlainRunners` on, so whole classes may be left out. */
    @get:Input
    abstract val narrowsPlainJUnit4: Property<Boolean>

    /** The build's `narrowLineOnlyChanges`, as the run resolves it. */
    @get:Input
    abstract val narrowLineOnlyChanges: Property<Boolean>

    @get:Internal("where git runs, not a set of files this task reads")
    lateinit var rootDir: File

    /** What the test task says of its test JVM, read as the configuration cache stores its entry. */
    @get:Internal("compared with the map's fingerprint, which Gradle cannot fingerprint")
    abstract val environmentView: Property<EnvironmentFingerprint.View>

    @get:Internal("the build's environmentInclude and systemPropertyIgnore, and the paths they normalize")
    var environment: EnvironmentFingerprint.Rules? = null

    @get:Internal("read through the map's own reader, which knows which of its files matter")
    lateinit var mapDir: File

    @get:Internal("only names where a forcing path lies; the task is untracked")
    var sourceDirs: List<String> = emptyList()

    /** Absent for a declined task, which writes nothing. */
    @get:OutputFile
    @get:Optional
    abstract val explanation: RegularFileProperty

    // Internal, not an input: the task is untracked. It depends on what builds the classpath, see
    // register.
    @get:Internal("the classpath this task reads, built by the tasks it depends on")
    var facts: ClasspathFacts? = null

    /** What a dependency bump is judged against, as the run judges it; null when `narrowDependencies` is off. */
    @get:Internal("read from the test task and the project's classpaths; the task is untracked")
    var dependencies: DependencyFacts? = null

    /** The parts of [DependencyRule.Context] the run takes from its build, held as configuration-cache-safe values. */
    class DependencyFacts(
        val identities: org.gradle.api.provider.Provider<Map<File, String>>,
        val configurations: org.gradle.api.provider.Provider<Set<String>>,
        val jvmArguments: org.gradle.api.provider.Provider<List<String>>,
    )

    /** Each glob the build declares every test of this task depends on: resolved, and as written. */
    @get:Internal("matched against the change set in the action; the task is untracked")
    var declared: List<Pair<String, String>> = emptyList()

    /** Set when the plugin declined the `Test` task, which leaves nothing to explain. */
    @get:Internal("the decline's sentence, printed as is")
    var declined: String? = null

    /** Why the scheduled full run is due, as the run decides it; null with no limit set. */
    private fun scheduled(): String? {
        val builds = fullRunEveryBuilds.orNull
        val days = fullRunEveryDays.orNull
        if (builds == null && days == null) return null
        return Schedule.dueIn(mapDir, builds, days)
    }

    @TaskAction
    fun explain() {
        val taskPath = taskPath.get()
        declined?.let { detail ->
            logger.lifecycle(
                "[yoriwake] $taskPath: $detail. Nothing is captured or selected for this task, so " +
                    "there is no selection to explain."
            )
            return
        }
        // Before anything else, as the run declines before anything else.
        DevelocityDetection.decode(develocity.orNull)?.let { (kind, reason) ->
            logger.lifecycle("[yoriwake] $taskPath would run everything: $reason")
            writeUnanswered(
                mapDir, taskPath, explicitBase.orNull ?: "",
                io.github.zeuspizza.yoriwake.agent.select.Selector.Decision.FullRunKind.DAEMON_REFUSED,
                reason,
                refusalKind = kind.token,
            )
            return
        }
        // Declined next, as the run declines before any selection action.
        namedTestsKind.orNull?.let { kind ->
            val reason = namedTestsReason.get()
            logger.lifecycle("[yoriwake] $taskPath would run every test the filter matches: $reason")
            writeUnanswered(
                mapDir, taskPath, explicitBase.orNull ?: "",
                io.github.zeuspizza.yoriwake.agent.select.Selector.Decision.FullRunKind.DAEMON_REFUSED,
                reason,
                refusalKind = kind,
            )
            return
        }
        val facts = facts!!
        val explicitBase = explicitBase.orNull
        val excluded = excluded.get()
        // Asked in the action, so builds that never run this task pay no git calls.
        val git = ChangeDetection.directRunner(rootDir)
        // Through the same fallback the real run uses.
        val resolvedBase = explicitBase?.let { ChangeDetection.Base(it, Settings.BASE) }
            ?: ChangeDetection.defaultBase(git)
            ?: noBaseFound()
        // Widened to the map's age through the rule the run applies, or this would report
        // a narrower change set than the run executes.
        val age = widenToMapAge(
            resolvedBase, readCaptureStamp(mapDir),
            mapPresent(mapDir),
        ) { a, b ->
            if (a.startsWith("-") || b.startsWith("-")) null
            else git.run(listOf("merge-base", "--end-of-options", a, b))
                ?.firstOrNull()?.takeIf(String::isNotBlank)
        }
        val drift = if (age is MapAge.Known) WorkingTree.drift(rootDir, mapDir, excluded) else null
        val unknown = (drift as? WorkingTree.Drift.Unknown)?.let { MapAge.Unknown(it.kind, it.reason) }
            ?: age as? MapAge.Unknown
        // As the run checks them, before the map's age refuses: a full run asked for, a listed
        // branch, then a commit asking for one, read over the range the run would select over.
        val (declines, notes, branch) = RunPlan.declines(
            fullRunRequested.getOrElse(false),
            fullRunBranches.getOrElse(emptyList()),
            { ChangeDetection.checkedOut { ChangeDetection.rawGit(rootDir, it) } },
            unknown ?: age,
            { since -> ChangeDetection.scanCommitMessages({ ChangeDetection.rawGit(rootDir, it) }, since) },
            { scheduled() },
            fullRunFlag = "-P${Settings.FULL_RUN}",
        )
        notes.forEach { logger.lifecycle("[yoriwake] $taskPath: $it") }
        if (declines.isEmpty() && (fullRunEveryBuilds.isPresent || fullRunEveryDays.isPresent)) {
            Schedule.untilDue(
                Schedule.read(File(mapDir, Schedule.COUNT_FILE)), Schedule.read(File(mapDir, Schedule.TIME_FILE)),
                fullRunEveryBuilds.orNull, fullRunEveryDays.orNull,
            )?.let { logger.lifecycle("[yoriwake] $taskPath: $it") }
        }
        declines.firstOrNull()?.let { decline ->
            logger.lifecycle("[yoriwake] $taskPath would run everything: ${decline.reason}")
            writeUnanswered(
                mapDir, taskPath, resolvedBase.ref,
                io.github.zeuspizza.yoriwake.agent.select.Selector.Decision.FullRunKind.DAEMON_REFUSED,
                decline.reason,
                refusalKind = decline.kind.token,
                branch = branch,
            )
            return
        }
        if (unknown != null) {
            logger.lifecycle("[yoriwake] $taskPath would run everything: ${unknown.reason}")
            writeUnanswered(
                mapDir, taskPath, resolvedBase.ref,
                io.github.zeuspizza.yoriwake.agent.select.Selector.Decision.FullRunKind.DAEMON_REFUSED,
                unknown.reason,
                refusalKind = unknown.kind.token,
                branch = branch,
            )
            return
        }
        val widenedBase = (age as MapAge.Known).base
        val base = widenedBase.ref
        val baseOrigin = widenedBase.origin
        // With the capture commit's diff, as the run takes it.
        val tracked = listOfNotNull(base, age.stamp)
            .map { ChangeDetection.changedPaths(git, rootDir, it) }
            .takeUnless { null in it }?.flatMap { it.orEmpty() }
        val paths = tracked?.let { (drift as? WorkingTree.Drift.Moved)?.let { moved -> (tracked + moved.paths).distinct() } }
        // As the run takes them: the paths only the drift since the capture names.
        val driftOnly = (drift as? WorkingTree.Drift.Moved)?.paths.orEmpty() - tracked.orEmpty().toSet()
        // Printed first, because when it fires it explains every line below it.
        logger.lifecycle("[yoriwake] $taskPath: base $base ($baseOrigin)")
        if (paths == null) {
            logger.lifecycle("[yoriwake] $taskPath: git could not report changes against $base")
            // Recorded, not just printed, so a reader collating these files sees it.
            writeUnanswered(
                mapDir, taskPath, base,
                // The same finer token the run channel carries for this cause.
                io.github.zeuspizza.yoriwake.agent.select.Selector.Decision.FullRunKind.DAEMON_REFUSED,
                "git could not report changes against $base, so there is no change set",
                refusalKind = RefusalKind.NO_CHANGE_SET.token,
                branch = branch,
            )
            return
        }
        // As the run checks it, against the base it was asked about, before any analysis.
        ChangeDetection.changedPaths(git, rootDir, resolvedBase.ref)?.let { askedPaths ->
            val changed = (askedPaths + ((drift as? WorkingTree.Drift.Moved)?.paths ?: emptyList())).distinct()
            val ignore = ignoreDeclaration(changed) {
                ChangeDetection.outsideRoot(git, rootDir, resolvedBase.ref)?.toSet() ?: changed.toSet()
            }
            val count = LargeChange.counted(changed, ignore, notReadByTests.getOrElse(emptyList()))
            LargeChange.refusal(count, largeChangeThreshold.get(), resolvedBase.ref)?.let { reason ->
                logger.lifecycle("[yoriwake] $taskPath would run everything: $reason")
                writeUnanswered(
                    mapDir, taskPath, base,
                    io.github.zeuspizza.yoriwake.agent.select.Selector.Decision.FullRunKind.DAEMON_REFUSED,
                    reason,
                    refusalKind = RefusalKind.LARGE_CHANGE.token,
                    branch = branch,
                )
                return
            }
        }
        // Checked by the run before anything narrows, so it explains every line below it.
        if (checksProvenance.getOrElse(false)) {
            MapProvenance.verify(mapDir, listedDigest.orNull).refusal?.let { (kind, reason) ->
                logger.lifecycle("[yoriwake] $taskPath would run everything: $reason")
                writeUnanswered(
                    mapDir, taskPath, base,
                    io.github.zeuspizza.yoriwake.agent.select.Selector.Decision.FullRunKind.DAEMON_REFUSED,
                    reason,
                    refusalKind = kind.token,
                    branch = branch,
                )
                return
            }
        }
        // As the run checks it, before it selects.
        environment?.let { rules ->
            val (kind, reason) = environmentRefusal(checkEnvironment(mapDir, environmentView.get(), rules).verdict) ?: run {
                if (mapPresent(mapDir)) {
                    EnvironmentFingerprint.uncomparedReads(mapDir, rules).forEach { logger.lifecycle("[yoriwake] $taskPath: $it") }
                }
                return@let
            }
            logger.lifecycle("[yoriwake] $taskPath would run everything: $reason")
            writeUnanswered(
                mapDir, taskPath, base,
                io.github.zeuspizza.yoriwake.agent.select.Selector.Decision.FullRunKind.DAEMON_REFUSED,
                reason,
                refusalKind = kind.token,
                branch = branch,
            )
            return
        }
        val map = io.github.zeuspizza.yoriwake.agent.select.MapReader.read(mapDir, unionFilter.orNull)
        if (!map.isUsable) {
            logger.lifecycle("[yoriwake] $taskPath would run everything: ${map.unusableReason()}")
            writeUnanswered(
                mapDir, taskPath, base,
                io.github.zeuspizza.yoriwake.agent.select.Selector.Decision.FullRunKind.MAP_UNUSABLE,
                map.unusableReason(),
                branch = branch,
            )
            return
        }
        val mode = CoverageDecoder.captureMode(mapDir)
        val recorded = when (mode) {
            AgentContract.MODE_ISOLATED -> "with a fresh test JVM per test class"
            AgentContract.MODE_SHARED -> "in shared test JVMs"
            else -> "partly with a fresh test JVM per test class and partly in shared test JVMs"
        }
        logger.lifecycle("[yoriwake] $taskPath: the map was recorded $recorded ($mode)")
        if (mode != AgentContract.MODE_SHARED) {
            IsolatedPick.Stamp.read(File(mapDir, IsolatedPick.STAMP_FILE))?.takeIf { it.classes.isNotEmpty() }?.let {
                logger.lifecycle("[yoriwake] $taskPath: ${IsolatedPick.explain(it)}")
            }
        }
        // As the run applies it, with the same diffs to place paths outside the root.
        val ignore = ignoreDeclaration(paths) {
            val outside = listOfNotNull(base, age.stamp).map { ChangeDetection.outsideRoot(git, rootDir, it) }
            if (null in outside) paths.toSet() else outside.flatMap { it.orEmpty() }.toSet()
        }
        val strictness = Strictness.of(this.mode.orNull)
        // The selecting run's own step, on a copy: it writes the change set and the capture markers
        // beside the map it reads, and this task must leave the map as it was.
        val copy = Files.createTempDirectory("yoriwake-explain").toFile()
        try {
            mapDir.copyRecursively(copy, overwrite = true)
            val properties = Properties().apply {
                setProperty(AgentContract.CHANGE_SET_FILE_PROPERTY, File(copy, AgentContract.CHANGE_SET_FILE).absolutePath)
                setProperty(AgentContract.MODE_PROPERTY, strictness.token())
            }
            val dependencyContext = dependencies?.let { held ->
                DependencyRule.Context(
                    git = git,
                    raw = { ChangeDetection.rawGit(rootDir, it) },
                    current = {
                        ClasspathRecord.of(facts.classpath.files, held.identities.get(), facts.buildDirs.values, rootDir, facts.dependencyHomes)
                    },
                    jvmArguments = { held.jvmArguments.get() },
                    configurations = { held.configurations.get() },
                    ownBuildFile = moduleScript(facts.moduleDirs.keys),
                )
            }
            val step = SelectingStep.run(
                SelectingStep.Inputs(
                    copy, rootDir, paths, base, baseOrigin, sourceDirs, facts,
                    notReadByTests.getOrElse(emptyList()), ignore, declared, observing = false,
                    dependencies = dependencyContext,
                    narrowLineOnlyChanges = narrowLineOnlyChanges.get(),
                    taskInputs = { extraTaskInputs(rootDir, testInputs?.files.orEmpty(), testOwned?.files.orEmpty()) },
                    stamp = age.stamp,
                    moved = driftOnly.toSet(),
                    unionFilter = unionFilter.orNull,
                ),
                ExplainingHost(taskPath, properties) { logger.lifecycle(it) },
            )
            val scoped = step.scoped
            val widening = step.widening
            // A refusal of the inline widening keeps the full explanation, with what the scan found;
            // any other refusal of the run is its reason and kind alone.
            val refused = properties.getProperty(AgentContract.REFUSED_PROPERTY)
            if (refused != null && !widening.forces) {
                logger.lifecycle("[yoriwake] $taskPath would run everything: $refused")
                writeUnanswered(
                    mapDir, taskPath, base,
                    io.github.zeuspizza.yoriwake.agent.select.Selector.Decision.FullRunKind.DAEMON_REFUSED,
                    refused, refusalKind = properties.getProperty(AgentContract.REFUSED_KIND_PROPERTY), branch = branch,
                )
                return
            }
            // Named in the refusals so it prints and serialises beside every other reason.
            val established = if (widening.forces) {
                step.established.copy(refusals = step.established.refusals + (INLINE_REFUSAL_KEY to widening.refusal.orEmpty()))
            } else {
                step.established
            }
            val provable = established.provable + established.testClasses
            // The test JVM's own decision, from what the step handed it. Not its Java version: this
            // JVM is the build's, not the test task's.
            val decision = FilterRule.decisionFrom(FilterRule.resolveInputs { properties.getProperty(it) }, copy, null)
            val safe = decision.safe()
            val loosened = if (safe == null) emptyList() else loosenedTests(map, safe, decision, decision.rules())
            logger.lifecycle(
                "[yoriwake] $taskPath vs $base: ${scoped.change.classPrefixes.size} changed classes, " +
                    "${scoped.change.unmappablePaths.size} paths coverage cannot see, " +
                    "${scoped.dropped} off this task's classpath, " +
                    "${scoped.ideState} an IDE's state, ${scoped.notReadByTests} notReadByTests lists, " +
                    "${provable.size} the daemon could vouch for as untested " +
                    "(${established.testClasses.size} of them this task's own test classes, " +
                    "from ${facts.testOutputs.files.count { it.isDirectory }} test output dirs)"
            )
            // As the run applies it: optimistic mode never captures.
            val capture = if (strictness == Strictness.SAFE) step.capture else step.capture.copy(
                capture = false,
                reason = "optimistic mode never captures, so nothing is instrumented and the map is left exactly as it was.",
            )
            // The last run's, since only a test JVM can see it: the next recording run meets it again.
            val unconfirmed = Audit.TaskFacts.read(mapDir).parallelismUnconfirmed?.also {
                logger.lifecycle("[yoriwake] $taskPath: the last run captured nothing: its test JVM found that $it")
            }
            writeExplanation(
                mapDir, taskPath, base, scoped, established, decision, widening, capture, step.forcing, branch,
                PathIgnoreReport.evidence(scoped.ignore, mapDir),
                unconfirmed,
                mode = strictness.token(),
                loosened = loosened,
            )
            if (decision.isFullRun) {
                logger.lifecycle(
                    "[yoriwake] $taskPath would run everything: ${decision.fullRunReason()}"
                )
                // Naming why each class could not be vouched for is the point of this task.
                established.refusals.entries.sortedBy { it.key }.take(REPORTED_REFUSALS)
                    .forEach { (prefix, reason) ->
                        logger.lifecycle("[yoriwake]     $prefix: $reason")
                    }
            } else {
                val known = decision.knownTests()
                val selected = decision.selectedCount()
                logger.lifecycle(
                    "[yoriwake] $taskPath would run $selected of $known known tests " +
                        selectionShare(selected, known)
                )
                if (strictness != Strictness.SAFE) {
                    logger.lifecycle(
                        "[yoriwake] $taskPath in optimistic mode leaves out ${loosened.size} tests the safe rule runs " +
                            "(pins, declared dependencies and whole-class runners are judged when the tests run)" +
                            loosened.take(REPORTED_REFUSALS).joinToString("") { (test, rules) -> "\n[yoriwake]     $test ($rules)" } +
                            (if (loosened.size > REPORTED_REFUSALS) "\n[yoriwake]     and ${loosened.size - REPORTED_REFUSALS} more" else "")
                    )
                }
                if (narrowsPlainJUnit4.getOrElse(false)) {
                    val (leftOut, classes) = plainClassesLeftOut(map, decision)
                    val named = leftOut.sorted()
                    logger.lifecycle(
                        "[yoriwake] $taskPath runs plain JUnit 4, which leaves out whole classes: " +
                            "${leftOut.size} of $classes known classes" +
                            named.take(REPORTED_REFUSALS).joinToString("") { "\n[yoriwake]     $it" } +
                            (if (named.size > REPORTED_REFUSALS) "\n[yoriwake]     and ${named.size - REPORTED_REFUSALS} more" else "") +
                            "\n[yoriwake]   Every test of a class kept runs, so the run runs more than the count above; " +
                            "runners, new tests and pins are judged when the tests run, so it may keep more classes too."
                    )
                }
            }
        } finally {
            copy.deleteRecursively()
        }
    }

    /**
     * The declaration as the run builds it (`pathIgnoreDeclaration`): null when no path in [paths]
     * matches an entry or a default; [outsideRoot] only asked otherwise.
     */
    private fun ignoreDeclaration(paths: List<String>, outsideRoot: () -> Set<String>): PathIgnore.Declaration? {
        val entries = pathIgnore.getOrElse(emptyList())
        val defaults = if (pathIgnoreDefaults.getOrElse(true)) PathIgnore.DEFAULTS else emptyList()
        if (paths.none { path -> (entries + defaults).any { PathIgnore.matches(it, path) } }) return null
        return PathIgnore.Declaration(entries, rootDir, sourceDirs, buildLogicDirs, outsideRoot(), defaults)
    }

    internal companion object {
        fun nameFor(testName: String) = "yoriwakeExplain${testName.replaceFirstChar(Char::uppercase)}"

        /**
         * Registers the task beside [testName]. Configured only when something asks for it, which
         * realises the `Test` task and so runs its wiring first; [outcome] reads what that decided.
         */
        fun register(
            project: Project,
            testName: String,
            settings: Settings,
            fullRunBranches: List<String>,
            schedule: Schedule.Limits,
            notReadByTests: List<String>,
            pathIgnore: List<String>,
            pathIgnoreDefaults: Boolean,
            environment: EnvironmentFingerprint.Rules,
            largeChange: Int,
            narrowPlainRunners: Boolean,
            narrowDependencies: Boolean,
            narrowLineOnlyChanges: Boolean,
            declarations: List<Declarations.Declaration>,
            outcome: (Test) -> Triple<File, ScopeOutcome, TestPatterns?>,
        ) {
            project.tasks.register(nameFor(testName), ExplainTask::class.java) { task ->
                task.group = "verification"
                task.description = "Reports what predictive test selection would do, without running tests"
                val test = project.tasks.named(testName, Test::class.java).get()
                val (mapDir, scope, fromBuildScript) = outcome(test)
                task.taskPath.set(test.path)
                task.rootDir = project.rootDir
                task.mapDir = mapDir
                // Before the declined return: a required input left unset fails the task's validation.
                task.largeChangeThreshold.set(largeChange)
                task.narrowLineOnlyChanges.set(narrowLineOnlyChanges)
                // Read when the entry is stored, after the build script chose the framework.
                task.narrowsPlainJUnit4.set(project.provider {
                    narrowPlainRunners && test.options is org.gradle.api.tasks.testing.junit.JUnitOptions
                })
                if (scope is ScopeOutcome.NotApplied) {
                    task.declined = scope.detail
                    return@register
                }
                task.explicitBase.set(settings.base)
                task.mode.set(settings.mode.token())
                task.fullRunRequested.set(settings.fullRun)
                task.fullRunBranches.set(fullRunBranches)
                task.notReadByTests.set(notReadByTests)
                task.pathIgnore.set(pathIgnore)
                task.pathIgnoreDefaults.set(pathIgnoreDefaults)
                task.declared = declarations.filter { it.task == testName }.map { it.resolved to it.glob }
                task.testInputs = test.inputs.files
                task.testOwned = test.classpath + test.testClassesDirs
                task.buildLogicDirs = buildLogicDirs(project)
                schedule.builds?.let(task.fullRunEveryBuilds::set)
                schedule.days?.let(task.fullRunEveryDays::set)
                task.environment = environment
                task.environmentView.set(project.provider { environmentView(test) })
                task.develocity.set(DevelocityDetection.provider(project, test))
                // Through a provider: Gradle applies `--tests`, and an init script's `whenReady` adds
                // patterns, after this runs. Both are seen when the configuration cache stores its
                // entry; a test launcher's patterns arrive later, but it never asks for this task.
                val buildScriptPatterns = fromBuildScript ?: TestPatterns.of(test.filter)
                val namedTestsDecline = project.provider<Pair<RefusalKind, String>> {
                    readFilterVerdict(test, buildScriptPatterns).namedTestsDecline()
                }
                task.namedTestsKind.set(namedTestsDecline.map { it.first.token })
                task.unionFilter.set(project.provider<String> { unionFilterOf(test) })
                task.namedTestsReason.set(namedTestsDecline.map { it.second })
                val trusted = trustedDigest(project, settings, mapDir)
                task.checksProvenance.set(trusted != null)
                trusted?.digest?.let(task.listedDigest::set)
                task.explanation.set(File(mapDir, YoriwakePlugin.EXPLANATION_FILE))
                // Unknown, not empty: the change set is computed when this task runs, and an empty
                // one could drop a changed sibling's sources. So this task always pays the full
                // resolution.
                val facts = classpathFacts(project, test, changedPaths = null)
                task.facts = facts
                if (narrowDependencies) {
                    task.dependencies = DependencyFacts(
                        moduleIdentities(project),
                        classpathConfigurations(project),
                        project.provider { test.allJvmArgs + test.systemProperties.values.map { it.toString() } },
                    )
                }
                // What the test task compiles, so a changed source is read as the run will read
                // it: stale class files would hide a changed constant and predict a narrower run.
                task.dependsOn(facts.classpath, facts.testOutputs)
                task.excluded.set(WorkingTree.excluded(project.rootDir, facts.buildDirs.values))
                task.sourceDirs = ForcingPaths.sourceDirs(
                    project.rootDir, projectFacts(project, BuildMemo.of(project)).allSourceDirs,
                )
            }
        }
    }
}

/**
 * The selecting step's answers for an explanation: kept in [properties] for the decision the test JVM
 * would make from them, and its console lines on the task's own.
 */
private class ExplainingHost(
    override val name: String,
    private val properties: Properties,
    private val log: (String) -> Unit,
) : SelectingStep.Host {
    override fun lifecycle(line: String) = log(line)

    override fun warn(line: String) = log(line)

    // As Gradle's own refusal does: the last one stands.
    override fun refuse(kind: RefusalKind, reason: String) {
        properties.setProperty(AgentContract.SELECT_PROPERTY, "false")
        properties.setProperty(AgentContract.REFUSED_PROPERTY, reason)
        properties.setProperty(AgentContract.REFUSED_KIND_PROPERTY, kind.token)
    }

    override fun property(name: String, value: String) {
        properties.setProperty(name, value)
    }

    // No action of the build's runs after an explanation, so there is nothing to look at last.
    override fun handOver(compared: Map<String, String>): Boolean = true

    override fun captureNothing() {
        properties.setProperty(AgentContract.RECORDS_DIR_PROPERTY, "")
    }

    override fun flag(name: String) = "-P$name"

    override val explain: String get() = ForcingPaths.explainTask(name)

    override val optimistic: Boolean
        get() = properties.getProperty(AgentContract.MODE_PROPERTY) == Strictness.OPTIMISTIC.token()
}

/** Whether a map is there to have recorded the variables its tests read. */
private fun mapPresent(mapDir: File) = File(mapDir, AgentContract.MAP_SCHEMA_VERSION_FILE).isFile

/**
 * The plain JUnit 4 classes a selecting run would leave out, by name, and how many the map knows:
 * those whose every recorded test is skipped. Their runners are not known here.
 */
internal fun plainClassesLeftOut(
    map: io.github.zeuspizza.yoriwake.agent.select.MapReader.Result,
    decision: io.github.zeuspizza.yoriwake.agent.select.Selector.Decision,
): Pair<List<String>, Int> {
    val prefix = "[engine:junit4]/[class:"
    val byClass = map.entries().map { it.testId() }.filter { it.startsWith(prefix) }
        .groupBy { it.substring(prefix.length, it.indexOf(']', prefix.length)) }
    val skipped = io.github.zeuspizza.yoriwake.agent.select.Selector.Decision.Reason.SKIPPED
    val leftOut = byClass.filterValues { ids -> ids.all { decision.reasonForTest(it) == skipped } }.keys
    return leftOut.toList() to byClass.size
}

/** Enough to see the pattern; a 300-class refactor should not print 300 lines. */
private const val REPORTED_REFUSALS = 12
