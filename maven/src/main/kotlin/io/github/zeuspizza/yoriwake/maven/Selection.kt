package io.github.zeuspizza.yoriwake.maven

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.gradle.RunPlan
import io.github.zeuspizza.yoriwake.gradle.SettingNames
import io.github.zeuspizza.yoriwake.gradle.capture.CoverageDecoder
import io.github.zeuspizza.yoriwake.gradle.capture.EnvironmentFingerprint
import io.github.zeuspizza.yoriwake.gradle.capture.MapAge
import io.github.zeuspizza.yoriwake.gradle.capture.readCaptureStamp
import io.github.zeuspizza.yoriwake.gradle.capture.widenToMapAge
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

/**
 * A selecting run on Maven (`-Dyoriwake.select`): the run plan, the base widened to the map's age, the
 * change set, and the steps a Gradle test task takes before its tests start, all settled once when
 * the execution starts. The answers reach the test JVM through its run file, as every other contract
 * property does.
 */
internal class Selection(private val session: MavenSession, private val root: File, private val log: Logger) {

    private val git = ChangeDetection.directRunner(root)

    /** Asked once per invocation: whether it selects, and the declines that hold, are the same for every execution. */
    private val asked: RunPlan.Asked by lazy {
        RunPlan.Asked(
            select = flag(SettingNames.SELECT),
            observe = flag(SettingNames.OBSERVE),
            complement = null,
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
        properties: Properties,
    ): RunPlan.Kind {
        val host = Host(name, properties)
        val plan = try {
            RunPlan.resolve(
                asked,
                { "-D$it" },
                { widening(mapDir) },
                { since -> ChangeDetection.scanCommitMessages({ ChangeDetection.rawGit(root, it) }, since) },
            )
        } catch (rejected: RunPlan.Rejected) {
            throw MavenExecutionException(rejected.message, null as Throwable?)
        }
        plan.notes.forEach { host.lifecycle("[yoriwake] $name: $it") }
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

        val settled = SelectionSetup.settle(
            plan.widening!!,
            { ChangeDetection.changedPaths(git, root, it) },
            LargeChange.DEFAULT_THRESHOLD,
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
        // Written by the selecting step; a file it could not write reads as a refusal in the test JVM.
        host.property(AgentContract.CHANGE_SET_FILE_PROPERTY, File(mapDir, AgentContract.CHANGE_SET_FILE).absolutePath)
        host.property(AgentContract.SELECT_PROPERTY, "true")
        // A test's declared-dependency tag is relative to its module; the agent resolves it against this.
        host.property(AgentContract.PROJECT_DIR_PROPERTY, projectDir)
        // As on Gradle, the environment is compared before the change set is looked at.
        if (environment == null) {
            host.refuse(RefusalKind.ENVIRONMENT_UNRECORDED, "the test JVM's environment could not be read")
            SelectingStep.runEverything(
                host, mapDir, observing = observing, "the test JVM's environment could not be read",
                "A JVM nobody could describe cannot be compared with the one the map was recorded in", clears = true, undated,
            )
            return kind
        }
        SelectingStep.refuseAChangedEnvironment(
            host, mapDir, environment.first, environment.second, File(mapDir, AgentContract.CHANGE_SET_FILE), observing = observing,
        )
        if (host.refused) return kind
        val sourceDirs = ForcingPaths.sourceDirs(
            root,
            session.allProjects.flatMap { module -> (module.compileSourceRoots + module.testCompileSourceRoots).map(::File) },
        )
        SelectingStep.run(
            SelectingStep.Inputs(
                mapDir, root, paths, widened.ref, widened.origin, sourceDirs, view,
                notReadByTests = emptyList(), ignore = null, declared = emptyList(), observing = observing,
            ),
            host,
        )
        return kind
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
