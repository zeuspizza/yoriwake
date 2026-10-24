package io.github.zeuspizza.yoriwake.gradle.tasks

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.gradle.RunPlan
import io.github.zeuspizza.yoriwake.gradle.Settings
import io.github.zeuspizza.yoriwake.gradle.YoriwakePlugin
import io.github.zeuspizza.yoriwake.gradle.YoriwakePlugin.Companion.noBaseFound
import io.github.zeuspizza.yoriwake.gradle.capture.CoverageDecoder
import io.github.zeuspizza.yoriwake.gradle.capture.Schedule
import io.github.zeuspizza.yoriwake.gradle.capture.EnvironmentFingerprint
import io.github.zeuspizza.yoriwake.gradle.capture.MapAge
import io.github.zeuspizza.yoriwake.gradle.capture.MapProvenance
import io.github.zeuspizza.yoriwake.gradle.capture.decideCapture
import io.github.zeuspizza.yoriwake.gradle.capture.readCaptureStamp
import io.github.zeuspizza.yoriwake.gradle.capture.widenToMapAge
import io.github.zeuspizza.yoriwake.gradle.change.ChangeDetection
import io.github.zeuspizza.yoriwake.gradle.change.ClasspathFilesVerdict
import io.github.zeuspizza.yoriwake.gradle.change.classpathFilesRule
import io.github.zeuspizza.yoriwake.gradle.change.withClasspathFiles
import io.github.zeuspizza.yoriwake.gradle.change.ForcingPaths
import io.github.zeuspizza.yoriwake.gradle.change.INLINE_REFUSAL_KEY
import io.github.zeuspizza.yoriwake.gradle.change.WorkingTree
import io.github.zeuspizza.yoriwake.gradle.change.LargeChange
import io.github.zeuspizza.yoriwake.gradle.change.RefusalKind
import io.github.zeuspizza.yoriwake.gradle.change.changeSet
import io.github.zeuspizza.yoriwake.gradle.change.digestRule
import io.github.zeuspizza.yoriwake.gradle.change.establish
import io.github.zeuspizza.yoriwake.gradle.change.reportDigest
import io.github.zeuspizza.yoriwake.gradle.change.scopedChange
import io.github.zeuspizza.yoriwake.gradle.change.widenForInlining
import io.github.zeuspizza.yoriwake.gradle.facts.ClasspathFacts
import io.github.zeuspizza.yoriwake.gradle.facts.BuildMemo
import io.github.zeuspizza.yoriwake.gradle.facts.classpathFacts
import io.github.zeuspizza.yoriwake.gradle.facts.projectFacts
import io.github.zeuspizza.yoriwake.gradle.report.selectionShare
import io.github.zeuspizza.yoriwake.gradle.report.writeExplanation
import io.github.zeuspizza.yoriwake.gradle.report.writeUnanswered
import io.github.zeuspizza.yoriwake.gradle.wiring.DevelocityDetection
import io.github.zeuspizza.yoriwake.gradle.wiring.ScopeOutcome
import io.github.zeuspizza.yoriwake.gradle.wiring.TestPatterns
import io.github.zeuspizza.yoriwake.gradle.wiring.checkEnvironment
import io.github.zeuspizza.yoriwake.gradle.wiring.environmentRefusal
import io.github.zeuspizza.yoriwake.gradle.wiring.environmentView
import io.github.zeuspizza.yoriwake.gradle.wiring.readFilterVerdict
import io.github.zeuspizza.yoriwake.gradle.wiring.trustedDigest
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
import java.io.File

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
        val paths = listOfNotNull(base, age.stamp)
            .map { ChangeDetection.changedPaths(git, rootDir, it) }
            .takeUnless { null in it }?.flatMap { it.orEmpty() }
            ?.let { tracked -> (drift as? WorkingTree.Drift.Moved)?.let { (tracked + it.paths).distinct() } }
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
            val count = (askedPaths + ((drift as? WorkingTree.Drift.Moved)?.paths ?: emptyList())).distinct().size
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
        val map = io.github.zeuspizza.yoriwake.agent.select.MapReader.read(mapDir)
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
        val scoped = scopedChange(paths, facts, notReadByTests.getOrElse(emptyList()))
        val found = establish(
            { message -> logger.lifecycle("[yoriwake] $taskPath: $message") },
            mapDir, scoped.change, facts,
        )
        // As the run compares it: this task depends on what builds the classpath.
        val classpathFiles = classpathFilesRule(mapDir, facts)
        // Through the same helper as the run itself, or this would report a narrower
        // selection for any change to an inline function.
        val widening = widenForInlining(
            scoped.change.classPrefixes, facts, CoverageDecoder.readConstants(mapDir),
            scoped.change.inlinableSourceChanged,
            // Fresh: this task depends on the compile tasks the test task does.
            digestRule(mapDir, bytesAreFresh = true),
            recordedAnnotations = CoverageDecoder.readAnnotationDigests(mapDir),
            ownTestClasses = found.testClasses,
        ).withClasspathFiles(
            classpathFiles,
            forcedByPaths = scoped.change.unmappablePaths.isNotEmpty(),
        ) { message -> logger.lifecycle("[yoriwake] $taskPath: $message") }
        reportDigest(widening) { message -> logger.lifecycle("[yoriwake] $taskPath: $message") }
        // Named in the refusals so it prints and serialises beside every other reason.
        val established = if (widening.forces) {
            found.copy(refusals = found.refusals + (INLINE_REFUSAL_KEY to widening.refusal.orEmpty()))
        } else {
            found
        }
        val provable = established.provable + established.testClasses
        val decision = io.github.zeuspizza.yoriwake.agent.select.Selector.decide(
            map,
            changeSet(
                widening.prefixes.ifEmpty { scoped.change.classPrefixes.sorted() },
                paths, scoped, established, widening.changedBytes, widening.movedFiles,
            ).withDaemonRefusal(widening.refusal),
            emptyList(),
        )
        logger.lifecycle(
            "[yoriwake] $taskPath vs $base: ${scoped.change.classPrefixes.size} changed classes, " +
                "${scoped.change.unmappablePaths.size} paths coverage cannot see, " +
                "${scoped.dropped} off this task's classpath, " +
                "${scoped.ideState} an IDE's state, ${scoped.notReadByTests} notReadByTests lists, " +
                "${provable.size} the daemon could vouch for as untested " +
                "(${established.testClasses.size} of them this task's own test classes, " +
                "from ${facts.testOutputs.files.count { it.isDirectory }} test output dirs)"
        )
        val forcing = ForcingPaths.classify(
            rootDir, mapDir, scoped.change.unmappablePaths, sourceDirs, base,
        )
        // Written before anything is logged, through the same function the run uses, so
        // the file never disagrees with the log or the run.
        val capture = decideCapture(
            mapDir,
            mapUsable = map.isUsable,
            fullRun = decision.isFullRun,
            learnable = scoped.change.classPrefixes,
            ageKnown = classpathFiles == ClasspathFilesVerdict.Unchanged,
        )
        writeExplanation(
            mapDir, taskPath, base, scoped, established, decision, widening, capture, forcing, branch,
        )
        ForcingPaths.lines(taskPath, forcing).forEach { logger.lifecycle("[yoriwake] $taskPath: $it") }
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
        }
        logger.lifecycle("[yoriwake] $taskPath: ${capture.reason}")
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
            environment: EnvironmentFingerprint.Rules,
            largeChange: Int,
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
                if (scope is ScopeOutcome.NotApplied) {
                    task.declined = scope.detail
                    return@register
                }
                task.explicitBase.set(settings.base)
                task.fullRunRequested.set(settings.fullRun)
                task.fullRunBranches.set(fullRunBranches)
                task.notReadByTests.set(notReadByTests)
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

/** Whether a map is there to have recorded the variables its tests read. */
private fun mapPresent(mapDir: File) = File(mapDir, AgentContract.MAP_SCHEMA_VERSION_FILE).isFile

/** Enough to see the pattern; a 300-class refactor should not print 300 lines. */
private const val REPORTED_REFUSALS = 12
