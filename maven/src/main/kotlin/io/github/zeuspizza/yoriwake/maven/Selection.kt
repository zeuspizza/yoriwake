package io.github.zeuspizza.yoriwake.maven

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.gradle.RunPlan
import io.github.zeuspizza.yoriwake.gradle.SettingNames
import io.github.zeuspizza.yoriwake.gradle.capture.CaptureDecision
import io.github.zeuspizza.yoriwake.gradle.capture.CoverageDecoder
import io.github.zeuspizza.yoriwake.gradle.capture.EnvironmentFingerprint
import io.github.zeuspizza.yoriwake.gradle.capture.MapAge
import io.github.zeuspizza.yoriwake.gradle.capture.MapProvenance
import io.github.zeuspizza.yoriwake.gradle.capture.decideCapture
import io.github.zeuspizza.yoriwake.gradle.capture.Schedule
import io.github.zeuspizza.yoriwake.gradle.capture.SelectionRecord
import io.github.zeuspizza.yoriwake.gradle.capture.readCaptureStamp
import io.github.zeuspizza.yoriwake.gradle.capture.widenToMapAge
import io.github.zeuspizza.yoriwake.gradle.capture.writeAtomically
import io.github.zeuspizza.yoriwake.gradle.change.ChangeDetection
import io.github.zeuspizza.yoriwake.gradle.change.ForcingPaths
import io.github.zeuspizza.yoriwake.gradle.change.LargeChange
import io.github.zeuspizza.yoriwake.gradle.change.RefusalKind
import io.github.zeuspizza.yoriwake.gradle.change.SelectingStep
import io.github.zeuspizza.yoriwake.gradle.change.SelectionSetup
import io.github.zeuspizza.yoriwake.gradle.change.WorkingTree
import io.github.zeuspizza.yoriwake.gradle.facts.ClasspathView
import io.github.zeuspizza.yoriwake.gradle.wiring.declinedLeftAloneMarker
import org.apache.maven.MavenExecutionException
import org.apache.maven.execution.MavenSession
import org.slf4j.Logger
import java.io.File
import java.util.Properties
import java.util.UUID

/**
 * A selecting run on Maven (`-Dyoriwake.select`): the run plan, the base widened to the map's age, the
 * change set, and the steps a Gradle test task takes before its tests start, all settled once when
 * the execution starts. The answers reach the test JVM through its run file, as every other contract
 * property does.
 */
internal class Selection(private val session: MavenSession, private val root: File, private val log: Logger) {

    private val git = ChangeDetection.directRunner(root)

    private val settings by lazy { Settings.of(session) }

    /**
     * `-Dyoriwake.trustedMaps=<file>`'s digests by map directory name, or null when it is not set:
     * with a list, a map narrows only when the list names its digest. A list that cannot be read or
     * holds a malformed line fails the build, as on Gradle.
     */
    private val trusted: Map<String, String>? by lazy {
        val path = session.userProperties.getProperty(SettingNames.TRUSTED_MAPS) ?: return@lazy null
        val flag = "-D${SettingNames.TRUSTED_MAPS}=$path"
        val file = File(path).let { if (it.isAbsolute) it else File(root, path) }
        val text = runCatching { file.readText() }.getOrElse {
            throw MavenExecutionException("[yoriwake] $flag: $file cannot be read.", null as Throwable?)
        }
        try {
            MapProvenance.parseTrustedList(text, flag)
        } catch (malformed: IllegalArgumentException) {
            throw MavenExecutionException(malformed.message, null as Throwable?)
        }
    }

    /** Reads what can fail the build, so it fails when the build starts rather than at a test goal. */
    fun validate() {
        trusted
    }

    /** Whether the POM schedules full runs, so the decode counts the builds since the last one. */
    val schedules: Boolean get() = settings.schedule.set

    /** Asked once per invocation: whether it selects, and the declines that hold, are the same for every execution. */
    private val asked: RunPlan.Asked by lazy {
        RunPlan.Asked(
            select = flag(SettingNames.SELECT),
            observe = flag(SettingNames.OBSERVE),
            complement = session.userProperties.getProperty(SettingNames.COMPLEMENT)?.let {
                when {
                    it.isEmpty() || it.equals("true", ignoreCase = true) -> ""
                    it.equals("false", ignoreCase = true) -> null
                    else -> it
                }
            },
            fullRun = flag(SettingNames.FULL_RUN),
        )
    }

    /** Whether this invocation asked to select; a recording run asks git nothing more. */
    val asksSelection: Boolean get() = asked.select

    /**
     * Settles one execution's run and writes what its test JVM needs into [properties]. Returns what
     * the run is: [RunPlan.Kind.SELECT] keeps no records unless it ran everything, and an
     * [RunPlan.Kind.OBSERVE] run records as a recording run does while its test JVM decides as a
     * selecting run would. [undated] is why this execution cannot date the map, or null.
     */
    fun apply(
        name: String,
        mapDir: File,
        projectDir: String,
        view: ClasspathView,
        environment: Pair<EnvironmentFingerprint.View, EnvironmentFingerprint.Rules>?,
        undated: String?,
        isolatedCapture: Boolean,
        /** This execution's selection-record stamp, read where it starts. */
        stamp: () -> SelectionRecord.Stamp,
        /** Whether Surefire stops after a failure, so a record could list a test whose outcome nobody saw. */
        stopsAfterFailure: Boolean,
        properties: Properties,
        /**
         * Explaining, not running: asks to select whatever the invocation asked, leaves no selection
         * record, compares no environment it was not given, and hands what the selecting step worked
         * out, with the base it compared against, to [worked].
         */
        explaining: Boolean = false,
        worked: (SelectingStep.Worked, String) -> Unit = { _, _ -> },
    ): RunPlan.Kind {
        val host = Host(name, properties)
        val plan = try {
            RunPlan.resolve(
                if (explaining) RunPlan.Asked(select = true, observe = false, complement = null, fullRun = asked.fullRun) else asked,
                { "-D$it" },
                { widening(mapDir) },
                { since -> ChangeDetection.scanCommitMessages({ ChangeDetection.rawGit(root, it) }, since) },
                settings.fullRunBranches,
                // Not a build-wide git failure: a branch git cannot read declines this run alone.
                { ChangeDetection.checkedOut { ChangeDetection.rawGit(root, it) } },
                scheduled = {
                    settings.schedule.takeIf { it.set }?.let { Schedule.dueIn(mapDir, it.builds, it.days) }
                },
            )
        } catch (rejected: RunPlan.Rejected) {
            throw MavenExecutionException(rejected.message, null as Throwable?)
        }
        plan.notes.forEach { host.lifecycle("[yoriwake] $name: $it") }
        // Never one of an earlier run's: a selecting run writes its own, a complement its own copy.
        File(mapDir, SelectionRecord.START_FILE).delete()
        File(mapDir, AgentContract.COMPLEMENT_RECORD_FILE).delete()
        if (plan.complementing) return complement(host, mapDir, plan.complementFrom.orEmpty(), stamp)
        val observing = plan.observing
        // Beside the selecting properties below, or a decline's refusal.
        if (observing) host.property(AgentContract.OBSERVE_PROPERTY, "true")
        if (plan.declines.isNotEmpty() && observing) {
            val first = plan.declines.first()
            host.refuse(first.kind, first.reason)
            host.property(AgentContract.DECLINES_PROPERTY, plan.declines.joinToString(",") { it.kind.token })
            declinedLeftAloneMarker(CoverageDecoder.recordsDir(mapDir)).delete()
            host.lifecycle(
                "[yoriwake] $name: ${plan.declines.joinToString("; ") { it.reason }}, so a selecting run would run " +
                    "every test, and that is what this run observes."
            )
            return RunPlan.Kind.OBSERVE
        }
        if (plan.declines.isNotEmpty()) {
            val first = plan.declines.first()
            host.refuse(first.kind, first.reason)
            host.property(AgentContract.DECLINES_PROPERTY, plan.declines.joinToString(",") { it.kind.token })
            declinedLeftAloneMarker(CoverageDecoder.recordsDir(mapDir)).delete()
            SelectingStep.declined(host, mapDir, plan.declines.map { it.reason }, SettingNames.SELECT, isolatedCapture)
            return RunPlan.Kind.RECORD
        }
        if (!plan.selecting && !observing) return RunPlan.Kind.RECORD
        val kind = plan.kind
        if (plan.selecting && !explaining) startRecord(host, mapDir, stamp, stopsAfterFailure)

        val settled = SelectionSetup.settle(
            plan.widening!!,
            { ChangeDetection.changedPaths(git, root, it) },
            settings.largeChangeThreshold,
        ) { _, changed -> LargeChange.counted(changed, null) }
        val (widened, paths) = when (settled) {
            is SelectionSetup.Outcome.RunEverything -> {
                host.refuse(settled.kind, settled.reason)
                SelectingStep.runEverything(
                    host, mapDir, observing = observing, settled.reason, settled.cause, settled.clears, undated,
                )
                return kind
            }
            is SelectionSetup.Outcome.NoChangeSet -> {
                host.refuse(RefusalKind.NO_CHANGE_SET, settled.reason)
                SelectingStep.withoutChangeSet(host, mapDir, observing = observing, settled.against)
                return kind
            }
            is SelectionSetup.Outcome.Select -> settled.widened to settled.paths
        }
        // First of the checks a selecting run makes once it has a change set, as on Gradle.
        trusted?.let { listed ->
            MapProvenance.verify(mapDir, listed[mapDir.name]).refusal?.let { (refusal, reason) ->
                host.lifecycle(
                    "[yoriwake] ${host.name}: $reason, so the whole suite runs and records a new map. A map " +
                        "narrows here only when -D${SettingNames.TRUSTED_MAPS} names its digest, as a run on the " +
                        "default branch recorded it."
                )
                host.refuse(refusal, reason)
                if (!explaining) {
                    MapProvenance.clear(mapDir)
                    SelectingStep.applyCapture(
                        host, mapDir, observing,
                        decideCapture(mapDir, mapUsable = false, fullRun = true, learnable = emptySet()),
                    )
                }
                return kind
            }
        }
        // Written by the selecting step; a file it could not write reads as a refusal in the test JVM.
        host.property(AgentContract.CHANGE_SET_FILE_PROPERTY, File(mapDir, AgentContract.CHANGE_SET_FILE).absolutePath)
        host.property(AgentContract.SELECT_PROPERTY, "true")
        // A test's declared-dependency tag is relative to its module; the agent resolves it against this.
        host.property(AgentContract.PROJECT_DIR_PROPERTY, projectDir)
        // As on Gradle, the environment is compared before the change set is looked at.
        if (environment == null && !explaining) {
            host.refuse(RefusalKind.ENVIRONMENT_UNRECORDED, "the test JVM's environment could not be read")
            SelectingStep.runEverything(
                host, mapDir, observing = observing, "the test JVM's environment could not be read",
                "A JVM nobody could describe cannot be compared with the one the map was recorded in", clears = true, undated,
            )
            return kind
        }
        if (environment != null) {
            SelectingStep.refuseAChangedEnvironment(
                host, mapDir, environment.first, environment.second, File(mapDir, AgentContract.CHANGE_SET_FILE), observing = observing,
            )
        }
        if (host.refused) return kind
        val sourceDirs = ForcingPaths.sourceDirs(
            root,
            session.allProjects.flatMap { module -> (module.compileSourceRoots + module.testCompileSourceRoots).map(::File) },
        )
        val done = SelectingStep.run(
            SelectingStep.Inputs(
                mapDir, root, paths, widened.ref, widened.origin, sourceDirs, view,
                notReadByTests = emptyList(), ignore = null, declared = emptyList(), observing = observing,
            ),
            host,
        )
        worked(done, widened.ref)
        return kind
    }

    /**
     * Begins the selection record a complement run reads: the stamp and a token the test JVM writes
     * into its decision records. The decode completes it, or removes it when it cannot vouch for it.
     */
    private fun startRecord(host: Host, mapDir: File, stamp: () -> SelectionRecord.Stamp, stopsAfterFailure: Boolean) {
        if (stopsAfterFailure) {
            log.debug("[yoriwake] ${host.name}: Surefire stops after a failure, so this run leaves no selection record")
            return
        }
        val token = UUID.randomUUID().toString()
        host.property(AgentContract.RUN_TOKEN_PROPERTY, token)
        runCatching { SelectionRecord.writeStart(mapDir, SelectionRecord.Start(token, stamp())) }
            .onFailure { log.debug("[yoriwake] ${host.name}: the selection record's start could not be written ($it)") }
    }

    /**
     * A complement run (`-Dyoriwake.complement`, or `=<dir>` holding a copy of `.yoriwake/`): leaves out
     * every test the selecting run's record lists as ran, once the record's stamp equals this run's.
     * A record that is missing or does not match makes it a recording run, named; one that matches
     * captures nothing and leaves the map and the record as they are.
     */
    private fun complement(host: Host, mapDir: File, from: String, stamp: () -> SelectionRecord.Stamp): RunPlan.Kind {
        val record = if (from.isEmpty()) {
            File(mapDir, AgentContract.SELECTION_FILE)
        } else {
            File(File(from).let { if (it.isAbsolute) it else File(root, from) }, "${mapDir.name}/${AgentContract.SELECTION_FILE}")
        }
        val where = if (from.isEmpty()) "this build's map directory" else from
        val text = record.takeIf(File::isFile)?.let { runCatching { it.readText() }.getOrNull() }
        val read = SelectionRecord.read(text)
        val unusable = when {
            text == null && record.exists() -> RefusalKind.COMPLEMENT_NO_RECORD to
                "the ${AgentContract.SELECTION_FILE} for ${host.name} in $where cannot be read as a file"
            text == null -> RefusalKind.COMPLEMENT_NO_RECORD to
                "no ${AgentContract.SELECTION_FILE} for ${host.name} in $where: no selecting run that narrowed at a clean tree left one"
            read.failure() != null -> RefusalKind.COMPLEMENT_RECORD_MISMATCH to
                "the ${AgentContract.SELECTION_FILE} for ${host.name} in $where is unusable: ${read.failure()}"
            else -> null
        }
        val recorded = SelectionRecord.stampOf(read)
        val copy = File(mapDir, AgentContract.COMPLEMENT_RECORD_FILE)
        val (kind, reason) = unusable
            ?: (SelectionRecord.mismatch(recorded, stamp())
                ?: runCatching { copy.parentFile.mkdirs(); writeAtomically(copy, text!!) }
                    .exceptionOrNull()?.let { "its copy could not be written ($it)" })
                ?.let {
                    RefusalKind.COMPLEMENT_RECORD_MISMATCH to
                        "the ${AgentContract.SELECTION_FILE} for ${host.name} in $where differs from this run in $it"
                }
            ?: (null to null)
        if (kind != null) {
            host.refuse(kind, reason!!)
            host.lifecycle("[yoriwake] ${host.name}: $reason, so every test runs and the map is recorded.")
            return RunPlan.Kind.RECORD
        }
        host.property(AgentContract.COMPLEMENT_RECORD_PROPERTY, copy.absolutePath)
        SelectingStep.applyCapture(
            host, mapDir, observing = false,
            CaptureDecision(
                capture = false, fullRun = false, mapCurrent = false,
                reason = "leaving out the ${read.ran().size} tests the selecting run at ${recorded.commit?.take(12)} ran; " +
                    "nothing is instrumented, and the map and ${AgentContract.SELECTION_FILE} are left as they are.",
            ),
        )
        return RunPlan.Kind.COMPLEMENT
    }

    /** The base, from `-Dyoriwake.base` or git, widened to the map's age, and the tree's drift since the capture. */
    private fun widening(mapDir: File): RunPlan.Widening {
        val base = session.userProperties.getProperty(SettingNames.BASE)?.let { ChangeDetection.Base(it, "-D${SettingNames.BASE}") }
            ?: ChangeDetection.defaultBase(git)
            ?: ChangeDetection.Base(
                "HEAD",
                "no branch base found, so the working tree only -- every run will be a full run until you pass " +
                    "-D${SettingNames.BASE}=<ref> (in CI, the pull request base or the commit you are comparing against)",
            )
        val stampAge = widenToMapAge(
            base, readCaptureStamp(mapDir), File(mapDir, AgentContract.MAP_SCHEMA_VERSION_FILE).isFile,
        ) { a, b ->
            if (a.startsWith("-") || b.startsWith("-")) null
            else git.run(listOf("merge-base", "--end-of-options", a, b))?.firstOrNull()?.takeIf(String::isNotBlank)
        }
        // Only for a map whose stamp stands; the drift is the part of the map's age a commit cannot state.
        val drift = if (stampAge is MapAge.Known) {
            // As the capture's start snapshot excluded them: build output and the maps themselves.
            val excluded = WorkingTree.excluded(root, session.allProjects.map { it.build.directory }) + Recording.MAPS_HOME
            WorkingTree.drift(root, mapDir, excluded)
        } else {
            null
        }
        val age = (drift as? WorkingTree.Drift.Unknown)?.let { MapAge.Unknown(it.kind, it.reason) } ?: stampAge
        return RunPlan.Widening(age, drift, base)
    }

    private fun flag(name: String): Boolean =
        session.userProperties.getProperty(name)?.let { Settings.parseFlag(name, it) } ?: false

    /** The test JVM's answers go into its run file; Maven runs no action of the build's after this. */
    private inner class Host(override val name: String, private val properties: Properties) : SelectingStep.Host {
        var refused = false
            private set

        override fun lifecycle(line: String) = log.info(line)

        override fun warn(line: String) = log.warn(line)

        override fun refuse(kind: RefusalKind, reason: String) {
            refused = true
            properties.setProperty(AgentContract.SELECT_PROPERTY, "false")
            properties.setProperty(AgentContract.REFUSED_PROPERTY, reason)
            properties.setProperty(AgentContract.REFUSED_KIND_PROPERTY, kind.token)
        }

        override fun property(name: String, value: String) {
            properties.setProperty(name, value)
        }

        override fun handOver(compared: Map<String, String>): Boolean = true

        override fun captureNothing() {
            properties.setProperty(AgentContract.RECORDS_DIR_PROPERTY, "")
        }
    }
}
