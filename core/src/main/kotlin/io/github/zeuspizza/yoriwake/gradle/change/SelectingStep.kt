package io.github.zeuspizza.yoriwake.gradle.change

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.ABSENCE_PROVABLE_PROPERTY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.ACCOUNTED_PROPERTY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.BASE_FLAG_PROPERTY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.CHANGED_BYTES_PROPERTY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.CHANGED_CLASSES_PROPERTY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.CHANGED_PATHS_PROPERTY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.EXEMPT_TEST_CLASSES_PROPERTY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.IGNORED_PATHS_PROPERTY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.MOVED_FILES_PROPERTY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.OWN_TEST_CLASSES_PROPERTY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.UNMAPPABLE_PATHS_PROPERTY
import io.github.zeuspizza.yoriwake.agent.select.PathGlob
import io.github.zeuspizza.yoriwake.gradle.SettingNames
import io.github.zeuspizza.yoriwake.gradle.capture.CaptureDecision
import io.github.zeuspizza.yoriwake.gradle.capture.CoverageDecoder
import io.github.zeuspizza.yoriwake.gradle.capture.EnvironmentFingerprint
import io.github.zeuspizza.yoriwake.gradle.capture.decideCapture
import io.github.zeuspizza.yoriwake.gradle.capture.writeAtomically
import io.github.zeuspizza.yoriwake.gradle.facts.ClasspathView
import io.github.zeuspizza.yoriwake.gradle.report.PathIgnoreReport
import io.github.zeuspizza.yoriwake.gradle.wiring.checkEnvironment
import io.github.zeuspizza.yoriwake.gradle.wiring.declinedLeftAloneMarker
import io.github.zeuspizza.yoriwake.gradle.wiring.environmentRefusal
import io.github.zeuspizza.yoriwake.gradle.wiring.fullRunMarker
import java.io.File

/**
 * What a selecting run decides once its change set is known, as it starts its tests: what changed for
 * this run's classpath, what that widens to, whether it must run everything, what the test JVM is
 * handed, and whether the run captures. Every host runs it the same way; the [Host] carries the
 * answers to its own test JVM and console.
 */
internal object SelectingStep {

    /** Where the answers go: the host's console, the test JVM's contract properties, its coverage. */
    interface Host {
        /** The run's name in console lines, as `[yoriwake] <name>: ...`. */
        val name: String

        /** A line on the console, as the build's own lines are. */
        fun lifecycle(line: String)

        /** A line a person must not miss. */
        fun warn(line: String)

        /** Turns selection off for this run, so every test runs, saying [kind] and [reason]. */
        fun refuse(kind: RefusalKind, reason: String)

        /** A contract property the test JVM starts with. */
        fun property(name: String, value: String)

        /**
         * Hands what the classpath-files rule [compared] to a look after the host's own last actions
         * before the test JVM starts; false when it could not. A host with no such actions has
         * nothing to hand over.
         */
        fun handOver(compared: Map<String, String>): Boolean

        /** This run captures nothing: no records directory, and the host's coverage agent off. */
        fun captureNothing()

        /** A setting as this host's command line passes it: `-P<name>` on Gradle, `-D<name>` on Maven. */
        fun flag(name: String): String

        /** What a person runs to explain this run without running it, which writes `explain.json`. */
        val explain: String
    }

    /** One run's change set and what it is judged against. */
    class Inputs(
        val mapDir: File,
        val rootDir: File,
        /** Every changed path, the working tree's drift included. */
        val paths: List<String>,
        /** The base the change set was taken from, and why it is that base. */
        val against: String,
        val origin: String,
        val sourceDirs: List<String>,
        val classpathFacts: ClasspathView,
        val notReadByTests: List<String>,
        val ignore: PathIgnore.Declaration?,
        /** Each glob the build declares every test of this run depends on: resolved, and as written. */
        val declared: List<Pair<String, String>>,
        val observing: Boolean,
    )

    /** What [run] worked out, for an explanation of the run to report without running it again. */
    class Worked(
        val scoped: ScopedChange,
        val established: Established,
        val widening: InlineWidening,
        val forcing: ForcingPaths.Classified,
        val capture: CaptureDecision,
    )

    fun run(inputs: Inputs, host: Host): Worked {
        val paths = inputs.paths
        val mapDir = inputs.mapDir
        val rootDir = inputs.rootDir
        val classpathFacts = inputs.classpathFacts
        val against = inputs.against
        val changeSetFile = File(mapDir, AgentContract.CHANGE_SET_FILE)
        val scoped = scopedChange(paths, classpathFacts, inputs.notReadByTests, inputs.ignore)
        PathIgnoreReport.lines(scoped.ignore, mapDir).forEach { host.lifecycle("[yoriwake] ${host.name}: $it") }
        val established = establish(
            { message -> host.lifecycle("[yoriwake] ${host.name}: $message") },
            mapDir, scoped.change, classpathFacts,
        )

        // A file the build generated onto the classpath from something git does not track moves
        // with no change behind it; read now, after the run's inputs are built, and again by the
        // host's last look, after the build's own last actions.
        var handedOver = true
        val classpathFiles = classpathFilesRule(mapDir, classpathFacts) { compared ->
            handedOver = host.handOver(compared)
        }.let { verdict ->
            // Nothing for the last look to compare against, so nothing vouches for what the build's
            // own last actions write.
            if (handedOver || verdict != ClasspathFilesVerdict.Unchanged) verdict
            else ClasspathFilesVerdict.Unrecorded("the classpath files could not be handed to classpath-files.compared")
        }
        jacocoCeilingNote(classpathFacts)?.let { host.lifecycle("[yoriwake] ${host.name}: $it") }
        // An inline function's body is compiled into its call sites, so coverage cannot link
        // them; Kotlin's SMAP records the edge, and the consumers count as changed.
        val widening = widenForInlining(
            scoped.change.classPrefixes, classpathFacts, CoverageDecoder.readConstants(mapDir),
            scoped.change.inlinableSourceChanged,
            digestRule(mapDir, bytesAreFresh = true),
            recordedAnnotations = CoverageDecoder.readAnnotationDigests(mapDir),
            ownTestClasses = established.testClasses,
        ).withClasspathFiles(
            classpathFiles,
            forcedByPaths = scoped.change.unmappablePaths.isNotEmpty(),
        ) { message -> host.lifecycle("[yoriwake] ${host.name}: $message") }
        reportDigest(widening) { message -> host.lifecycle("[yoriwake] ${host.name}: $message") }

        // The build declared that every test of this run depends on such a path, which coverage cannot see.
        val declaredHit = paths.firstOrNull { path -> inputs.declared.any { PathGlob.matches(it.first, path) } }
        if (declaredHit != null && !widening.forces) {
            val entry = inputs.declared.first { PathGlob.matches(it.first, declaredHit) }
            val why = "$declaredHit changed, which dependsOn declares every test of this task depends on (${entry.second})"
            host.lifecycle("[yoriwake] ${host.name}: $why")
            host.refuse(RefusalKind.DECLARED_PATH_CHANGED, why)
        }
        val changed = if (widening.forces) {
            // Unknown, not "nothing": selection is switched off and everything executes, as
            // SelectionFilter does for a missing select flag.
            host.lifecycle("[yoriwake] ${host.name}: ${widening.refusal}")
            host.refuse(widening.refusalKind ?: RefusalKind.UNNAMED, widening.refusal.orEmpty())
            scoped.change.classPrefixes
        } else {
            if (widening.inliners.isNotEmpty()) {
                host.lifecycle(
                    "[yoriwake] ${host.name}: ${widening.inliners.size} class(es) inlined the changed " +
                        "sources and are selected with them"
                )
            }
            widening.prefixes
        }

        // Says the empty change set was arrived at (every path a class source off the classpath,
        // an IDE's state, or one notReadByTests lists) rather than reported by git, which would
        // force a full run.
        host.property(
            ACCOUNTED_PROPERTY,
            accountedFor(paths, scoped).toString(),
        )
        // From a copy, before the change set is written and printed after it: it only explains
        // the run, and never changes what the test JVM is handed.
        val forcing = ForcingPaths.classify(
            rootDir, mapDir, scoped.change.unmappablePaths, inputs.sourceDirs, against,
        )
        writeChangeSet(
            host, changeSetFile,
            mapOf(
                CHANGED_CLASSES_PROPERTY to changed,
                // Its own property: the change set is also the selector's anchor set for nested
                // classes.
                CHANGED_BYTES_PROPERTY to if (widening.forces) emptySet() else widening.changedBytes,
                MOVED_FILES_PROPERTY to if (widening.forces) emptyList() else widening.movedFiles,
                UNMAPPABLE_PATHS_PROPERTY to scoped.change.unmappablePaths,
                // Every changed path, before any is dropped, for the tests that declare a dependency on one.
                CHANGED_PATHS_PROPERTY to paths,
                ABSENCE_PROVABLE_PROPERTY to established.provable,
                // Separate property: a test class that has ever run is in the loaded-class union by
                // definition, so the absence rule would never fire for it.
                OWN_TEST_CLASSES_PROPERTY to established.testClasses,
                EXEMPT_TEST_CLASSES_PROPERTY to established.exemptTestClasses,
            ) + (
                // Only when there is one, so a build without a declaration hands over what it did.
                if (scoped.ignore.ignored.isEmpty()) emptyMap()
                else mapOf(IGNORED_PATHS_PROPERTY to scoped.ignore.ignored.map(PathIgnore::encode))
            ) + (
                // In the file rather than a property: an explanation decides in the host's own JVM,
                // which reads this file and no property of the test JVM's. Absent, the selector
                // names Gradle's, so a Gradle build hands over what it did.
                if (host.flag(SettingNames.BASE) == "-P${SettingNames.BASE}") emptyMap()
                else mapOf(BASE_FLAG_PROPERTY to listOf(host.flag(SettingNames.BASE)))
            ),
        )
        if (established.testClasses.isNotEmpty()) {
            host.lifecycle(
                "[yoriwake] ${host.name}: ${established.testClasses.size} changed test classes " +
                    "discovery already runs"
            )
        }
        host.lifecycle(
            "[yoriwake] ${host.name} selecting against $against (${inputs.origin}): " +
                "${scoped.change.classPrefixes.size} changed classes, " +
                "${scoped.change.unmappablePaths.size} paths coverage cannot see" +
                (if (scoped.dropped == 0) "" else
                    ", ${scoped.dropped} in modules that are not on this task's classpath") +
                (if (scoped.ideState == 0) "" else ", ${scoped.ideState} an IDE's state") +
                (if (scoped.notReadByTests == 0) "" else ", ${scoped.notReadByTests} notReadByTests lists")
        )
        ForcingPaths.lines(host.name, forcing, host.explain, pathIgnore = host.flag(SettingNames.BASE) == "-P${SettingNames.BASE}").forEach { host.lifecycle("[yoriwake] ${host.name}: $it") }

        val outlook = wouldRunEverything(mapDir, changed, scoped, established, widening, paths)
        // The un-widened change set on purpose: an unchanged inline consumer teaches the map
        // nothing, and widening would re-capture the suite on every inline change.
        val capture = decideCapture(
            mapDir,
            mapUsable = outlook.mapUsable,
            fullRun = outlook.fullRun,
            learnable = scoped.change.classPrefixes,
            // A map whose classpath files moved, or that records none, is not current: the run
            // forces, and its capture records them as they are now.
            ageKnown = classpathFiles == ClasspathFilesVerdict.Unchanged,
            flag = host::flag,
        )
        applyCapture(host, mapDir, inputs.observing, capture)
        return Worked(scoped, established, widening, forcing, capture)
    }

    /**
     * A selecting run that refused for [reason]: the whole suite runs, and it captures and dates the
     * map unless the map was recorded in isolation. The console line is [reason], then [cause]; when
     * [clears], it also says whether this run's capture clears the refusal. [undated] is why this run
     * cannot date the map, or null when it can.
     */
    fun runEverything(host: Host, mapDir: File, observing: Boolean, reason: String, cause: String, clears: Boolean, undated: String?) {
        // A map recorded in isolation is left as it is, as by every other fallback.
        val leftAlone = decideCapture(
            mapDir,
            mapUsable = runCatching {
                io.github.zeuspizza.yoriwake.agent.select.MapReader.read(mapDir).isUsable
            }.getOrDefault(false),
            fullRun = true,
            learnable = emptySet(),
            ageKnown = false,
            flag = host::flag,
        ).takeUnless { it.capture }
        host.lifecycle(
            "[yoriwake] ${host.name}: $reason, and the whole suite runs. $cause" + if (!clears) "." else "; " +
                when {
                    leftAlone != null -> "the next recording run clears it."
                    undated != null -> "this run's capture cannot clear it ($undated); a run without that does."
                    else -> "this run's capture clears it."
                }
        )
        // Running everything, so it captures and dates the map; otherwise a build that
        // always selects would refuse forever. An isolated map waits for its recording run.
        applyCapture(
            host, mapDir, observing,
            leftAlone ?: CaptureDecision(
                capture = true, fullRun = true, mapCurrent = false,
                reason = if (undated == null) {
                    "running everything, so this run also captures and dates the map."
                } else {
                    "running everything."
                },
            ),
        )
    }

    /** A selecting run git could give no change set for: the whole suite runs and captures. */
    fun withoutChangeSet(host: Host, mapDir: File, observing: Boolean, against: String) {
        host.lifecycle(
            "[yoriwake] ${host.name}: git could not report changes against $against, so the " +
                "whole suite runs. Selection needs a change set it can trust."
        )
        // Captures and dates a shared map like any full run; a map recorded in isolation is
        // left as it is, as by every other fallback.
        applyCapture(
            host, mapDir, observing,
            decideCapture(
                mapDir,
                mapUsable = runCatching {
                    io.github.zeuspizza.yoriwake.agent.select.MapReader.read(mapDir).isUsable
                }.getOrDefault(false),
                fullRun = true,
                learnable = emptySet(),
                flag = host::flag,
                ageKnown = false,
            ),
        )
    }

    /**
     * A run asked to select that a decline turned into a recording run: every test runs, and it
     * captures and dates the map, unless the map was recorded in isolation and this run is not
     * ([isolatedCapture]). [flag] is the property the run was asked to select with.
     */
    fun declined(host: Host, mapDir: File, reasons: List<String>, flag: String, isolatedCapture: Boolean) {
        host.lifecycle(
            "[yoriwake] ${host.name}: ${reasons.joinToString("; ")}, so selection is declined and every " +
                "test runs as on a run without ${host.flag(flag)}."
        )
        val decision = decideCapture(
            mapDir,
            mapUsable = runCatching {
                io.github.zeuspizza.yoriwake.agent.select.MapReader.read(mapDir).isUsable
            }.getOrDefault(false),
            fullRun = true,
            learnable = emptySet(),
            ageKnown = false,
            flag = host::flag,
        )
        if (isolatedCapture || decision.capture) return
        applyCapture(host, mapDir, observing = false, decision)
        // The decode reads it: this run captured nothing on purpose.
        val leftAlone = declinedLeftAloneMarker(CoverageDecoder.recordsDir(mapDir))
        runCatching {
            leftAlone.parentFile.mkdirs()
            leftAlone.writeText(decision.reason + "\n")
        }
    }

    /**
     * Runs the whole suite when the test JVM's environment, as [view] sees it, is not the one the map
     * was recorded in, or the map records none; see [EnvironmentFingerprint]. The capture it falls
     * back to records the environment as it is now, so the next run compares against it.
     */
    fun refuseAChangedEnvironment(
        host: Host,
        mapDir: File,
        view: EnvironmentFingerprint.View,
        rules: EnvironmentFingerprint.Rules,
        changeSetFile: File,
        observing: Boolean,
    ) {
        val check = checkEnvironment(mapDir, view, rules)
        if (check.ignored.isNotEmpty()) {
            host.property(AgentContract.FINGERPRINT_IGNORED_PROPERTY, check.ignored.joinToString(","))
        }
        val (kind, reason) = environmentRefusal(check.verdict) ?: return
        host.lifecycle("[yoriwake] ${host.name}: $reason, so the whole suite runs.")
        host.refuse(kind, reason)
        // Never read under a refusal; deleted so a previous run's file is not mistaken for this one's.
        runCatching { changeSetFile.delete() }
        // A map that is not current: with no changed class the decision would otherwise call it
        // current and not capture, and the old fingerprint would refuse every later run.
        applyCapture(
            host, mapDir, observing,
            decideCapture(
                mapDir,
                mapUsable = runCatching {
                    io.github.zeuspizza.yoriwake.agent.select.MapReader.read(mapDir).isUsable
                }.getOrDefault(false),
                fullRun = true,
                learnable = emptySet(),
                flag = host::flag,
                ageKnown = false,
            ),
        )
    }

    /**
     * Hands the list-valued change-set properties to the test JVM through [file] (see
     * [AgentContract.CHANGE_SET_FILE_PROPERTY]): as system properties, a large change set is a
     * command line the OS refuses to start. A file that cannot be written refuses the run instead.
     */
    private fun writeChangeSet(host: Host, file: File, lists: Map<String, Collection<String>>) {
        val properties = java.util.Properties()
        lists.forEach { (name, values) -> properties.setProperty(name, values.sorted().joinToString(",")) }
        val text = java.io.StringWriter().also { properties.store(it, null) }.toString() +
            AgentContract.CHANGE_SET_END + "\n"
        runCatching {
            file.parentFile.mkdirs()
            writeAtomically(file, text)
        }.onFailure { failure ->
            // A stale file must not stand in for this run's; the refusal wins over it either way.
            runCatching { file.delete() }
            val reason = "the change set could not be written to $file ($failure)"
            host.lifecycle("[yoriwake] ${host.name}: $reason, so the whole suite runs.")
            host.refuse(RefusalKind.CHANGE_SET_UNREADABLE, reason)
        }
    }

    /**
     * Applies [decideCapture] to this run: instrument, or make instrumenting impossible. Turning the
     * coverage agent off with the listener on would record every test as covering nothing.
     *
     * An [observing] run runs every test and captures as a recording run does, whatever the decision
     * says a selecting run would do.
     */
    fun applyCapture(host: Host, mapDir: File, observing: Boolean, decided: CaptureDecision) {
        val decision = if (observing) OBSERVING_CAPTURE else decided
        // Written only when this run executes everything and captures, deleted otherwise, so a
        // stale marker never dates a map.
        val fullRunMarker = fullRunMarker(CoverageDecoder.recordsDir(mapDir))
        runCatching {
            if (decision.capture) {
                fullRunMarker.parentFile.mkdirs()
                fullRunMarker.writeText("full")
            } else {
                fullRunMarker.delete()
            }
        }.onFailure {
            // Costs a map refresh, but a map whose age never resets forces forever.
            host.warn(
                "[yoriwake] ${host.name}: could not record that this run executed the whole suite " +
                    "($it), so the map keeps the age it had and the next run selects against a " +
                    "wider base than it needs to."
            )
        }

        if (decision.capture) {
            host.lifecycle("[yoriwake] ${host.name}: ${decision.reason}")
            return
        }

        host.captureNothing()
        host.lifecycle("[yoriwake] ${host.name}: ${decision.reason}")
    }

    private val OBSERVING_CAPTURE = CaptureDecision(
        capture = true, fullRun = true, mapCurrent = false,
        reason = "observing selection, so this run captures the map as a recording run does.",
    )
}
