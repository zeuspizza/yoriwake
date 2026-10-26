package io.github.zeuspizza.yoriwake.gradle.wiring

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.ABSENCE_PROVABLE_PROPERTY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.ACCOUNTED_PROPERTY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.CHANGED_BYTES_PROPERTY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.CHANGED_CLASSES_PROPERTY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.CHANGED_PATHS_PROPERTY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.CHANGE_SET_FILE_PROPERTY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.CLASS_GRANULARITY_PROPERTY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.DECLINES_PROPERTY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.EXEMPT_TEST_CLASSES_PROPERTY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.IGNORED_PATHS_PROPERTY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.MOVED_FILES_PROPERTY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.OWN_TEST_CLASSES_PROPERTY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.PROJECT_DIR_PROPERTY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.RECORDS_DIR_PROPERTY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.REFUSED_KIND_PROPERTY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.SELECT_PROPERTY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.UNMAPPABLE_PATHS_PROPERTY
import io.github.zeuspizza.yoriwake.agent.select.PathGlob
import io.github.zeuspizza.yoriwake.gradle.RunPlan
import io.github.zeuspizza.yoriwake.gradle.Settings
import io.github.zeuspizza.yoriwake.gradle.YoriwakePlugin.Companion.noBaseFound
import io.github.zeuspizza.yoriwake.gradle.capture.CaptureDecision
import io.github.zeuspizza.yoriwake.gradle.capture.CoverageDecoder
import io.github.zeuspizza.yoriwake.gradle.capture.EnvironmentFingerprint
import io.github.zeuspizza.yoriwake.gradle.capture.MapAge
import io.github.zeuspizza.yoriwake.gradle.capture.MapProvenance
import io.github.zeuspizza.yoriwake.gradle.capture.MapProvenanceSource
import io.github.zeuspizza.yoriwake.gradle.capture.SelectionRecord
import io.github.zeuspizza.yoriwake.gradle.capture.decideCapture
import io.github.zeuspizza.yoriwake.gradle.capture.validCaptureStamp
import io.github.zeuspizza.yoriwake.gradle.capture.widenToMapAge
import io.github.zeuspizza.yoriwake.gradle.capture.writeAtomically
import io.github.zeuspizza.yoriwake.gradle.change.ChangeDetection
import io.github.zeuspizza.yoriwake.gradle.change.ClasspathFiles
import io.github.zeuspizza.yoriwake.gradle.change.ClasspathFilesVerdict
import io.github.zeuspizza.yoriwake.gradle.change.Declarations
import io.github.zeuspizza.yoriwake.gradle.change.ForcingPaths
import io.github.zeuspizza.yoriwake.gradle.change.LargeChange
import io.github.zeuspizza.yoriwake.gradle.change.NotRead
import io.github.zeuspizza.yoriwake.gradle.change.PathIgnore
import io.github.zeuspizza.yoriwake.gradle.change.RefusalKind
import io.github.zeuspizza.yoriwake.gradle.change.SelectingStep
import io.github.zeuspizza.yoriwake.gradle.change.SelectionSetup
import io.github.zeuspizza.yoriwake.gradle.change.WorkingTree
import io.github.zeuspizza.yoriwake.gradle.change.WorkingTreeDriftSource
import io.github.zeuspizza.yoriwake.gradle.change.accountedFor
import io.github.zeuspizza.yoriwake.gradle.change.cachedRawGit
import io.github.zeuspizza.yoriwake.gradle.change.changeSet
import io.github.zeuspizza.yoriwake.gradle.change.changedPaths
import io.github.zeuspizza.yoriwake.gradle.change.classpathFilesRule
import io.github.zeuspizza.yoriwake.gradle.change.commonAncestor
import io.github.zeuspizza.yoriwake.gradle.change.configuredListing
import io.github.zeuspizza.yoriwake.gradle.change.defaultBase
import io.github.zeuspizza.yoriwake.gradle.change.digestRule
import io.github.zeuspizza.yoriwake.gradle.change.establish
import io.github.zeuspizza.yoriwake.gradle.change.jacocoCeilingNote
import io.github.zeuspizza.yoriwake.gradle.change.outsideRoot
import io.github.zeuspizza.yoriwake.gradle.change.reportDigest
import io.github.zeuspizza.yoriwake.gradle.change.scopedChange
import io.github.zeuspizza.yoriwake.gradle.change.trackedPaths
import io.github.zeuspizza.yoriwake.gradle.change.widenForInlining
import io.github.zeuspizza.yoriwake.gradle.change.withClasspathFiles
import io.github.zeuspizza.yoriwake.gradle.change.wouldRunEverything
import io.github.zeuspizza.yoriwake.gradle.facts.BuildMemo
import io.github.zeuspizza.yoriwake.gradle.facts.ClasspathFacts
import io.github.zeuspizza.yoriwake.gradle.facts.classpathFacts
import io.github.zeuspizza.yoriwake.gradle.facts.projectFacts
import io.github.zeuspizza.yoriwake.gradle.report.PathIgnoreReport
import java.io.File
import org.gradle.api.InvalidUserDataException
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension

// The selecting half of the Test-task wiring: the change set, its widening and the capture
// decision. Called from the same point of TestTaskWiring.configure, so doFirst order is kept.

/**
 * Lets a selecting run discover no tests without failing the build (Gradle 9 fails an empty
 * test task). Reflective: the property does not exist before Gradle 9.
 */
private fun allowAnEmptyRun(test: Test) {
    val setter = test.javaClass.methods.firstOrNull {
        it.name == "setFailOnNoDiscoveredTests" && it.parameterCount == 1
    }
    val getter = test.javaClass.methods.firstOrNull {
        it.name == "getFailOnNoDiscoveredTests" && it.parameterCount == 0
    }
    // Absent on Gradle 8 and earlier, where there is nothing to relax.
    if (setter == null && getter == null) {
        return
    }

    val outcome = runCatching {
        if (setter != null) {
            setter.invoke(test, false)
        } else {
            val property = getter!!.invoke(test)
            property.javaClass.methods
                .first {
                    it.name == "set" && it.parameterCount == 1 &&
                        it.parameterTypes[0] != org.gradle.api.provider.Provider::class.java
                }
                .invoke(property, false)
        }
    }
    // Said out loud: if this fails, a module whose tests are all deselected fails the build.
    outcome.onFailure {
        test.logger.warn(
            "[yoriwake] ${test.path}: could not relax failOnNoDiscoveredTests ($it). A module whose " +
                "tests are all deselected may fail this build."
        )
    }
}

/**
 * Hands the change set to the in-JVM filter, then turns selection on: last, and only when git
 * answered, so any failure degrades to a full run. Opt-in per invocation (`-Pyoriwake.select`).
 *
 * An observing run (`-Pyoriwake.observe`) computes the same change set and hands it over the same
 * way, so the test JVM decides exactly as a selecting run would; it captures as a recording run does.
 */
internal fun TestTaskWiring.configureSelection(
    project: Project,
    test: Test,
    mapDir: File,
    buildMemo: BuildMemo?,
    runPlan: RunPlan,
    fromBuildScript: TestPatterns,
    notReadByTests: List<String>,
    pathIgnore: List<String>,
    environment: EnvironmentFingerprint.Rules,
    projectDir: String,
    declarations: List<Declarations.Declaration>,
    largeChange: Int,
) {
    if (!runPlan.selecting && !runPlan.observing) {
        return
    }
    val observing = runPlan.observing
    // Read before any refusal below, so a list that cannot be read fails every selecting run alike.
    val trusted = trustedDigest(project, settings, mapDir)

    // Not read by a run that refuses over the map's age.
    val sourceDirs by lazy { ForcingPaths.sourceDirs(project.rootDir, projectFacts(project, buildMemo).allSourceDirs) }
    val settled = SelectionSetup.settle(
        runPlan.widening!!,
        { ChangeDetection.changedPaths(project.providers, project.rootDir, it, buildMemo) },
        largeChange,
    ) { base, changed ->
        LargeChange.counted(
            changed, pathIgnoreDeclaration(project, pathIgnore, sourceDirs, listOf(base), changed, buildMemo),
        )
    }
    val (widened, stamp, paths) = when (settled) {
        is SelectionSetup.Outcome.RunEverything -> {
            refuseAndRecord(
                test, mapDir, observing, fromBuildScript, settled.kind, settled.reason, settled.cause, settled.clears,
            )
            return
        }
        is SelectionSetup.Outcome.NoChangeSet -> {
            refuse(test, RefusalKind.NO_CHANGE_SET, settled.reason)
            val refusalJacoco = test.extensions.findByName("jacoco") as? JacocoTaskExtension
            val against = settled.against
            test.doFirst {
                if (refusedAtExecution(test)) return@doFirst
                SelectingStep.withoutChangeSet(GradleSelectingHost(test, refusalJacoco, mapDir), mapDir, observing, against)
            }
            return
        }
        is SelectionSetup.Outcome.Select -> Triple(settled.widened, settled.stamp, settled.paths)
    }
    val against = widened.ref

    // Resolved to plain values and providers here: a task action may not touch Project under
    // the configuration cache.
    val classpathFacts = classpathFacts(project, test, paths)
    val rootDir = project.rootDir
    val ignore = pathIgnoreDeclaration(project, pathIgnore, sourceDirs, listOfNotNull(against, stamp), paths, buildMemo)
    // Held from configuration time: a task action may not reach Task.extensions under the
    // configuration cache.
    val jacoco = test.extensions.findByName("jacoco") as? JacocoTaskExtension
    // Written by the action below; a file it could not write reads as a refusal in the test JVM.
    val changeSetFile = File(mapDir, AgentContract.CHANGE_SET_FILE)
    test.systemProperty(CHANGE_SET_FILE_PROPERTY, changeSetFile.absolutePath)
    // The whole change set, as an input: the file is not one, and a different change set must
    // re-run the task, or a change that forces a full run finds it up to date and runs nothing.
    // Only configuration-time state is an input; what the actions below set is not.
    // With a declaration, each path it can take out is in by its content as well: the name alone
    // would leave the task up to date after a second edit to the same file, unlogged.
    test.inputs.property(
        "yoriwake.changeSet",
        changeSetDigest(ChangeDetection.split(paths, project.rootDir)) +
            (ignore?.let { ":" + ignoredContentDigest(project, it, paths) } ?: ""),
    )
    test.systemProperty(SELECT_PROPERTY, "true")
    // A test's declared-dependency tag is relative to its project; the agent resolves it against this.
    test.systemProperty(PROJECT_DIR_PROPERTY, projectDir)
    // Forwarded only when asked for; the agent defaults it to false.
    test.systemProperty(
        CLASS_GRANULARITY_PROPERTY, settings.classGranularity.toString())
    // An observing run leaves out nothing, so an empty task fails as it would without yoriwake.
    if (!observing) allowAnEmptyRun(test)

    // Re-derived at execution, when the classpath is resolvable. Every doFirst runs before the
    // test JVM launches, so these properties are ones the JVM starts with.
    val declared = declarations.map { it.resolved to it.glob }
    test.doFirst {
        // Decided by a check below, which runs before this action and captures.
        if (refusedAtExecution(test)) {
            return@doFirst
        }
        runCatching { LastLook.handOver(mapDir).delete() }
        SelectingStep.run(
            SelectingStep.Inputs(
                mapDir, rootDir, paths, against, widened.origin, sourceDirs, classpathFacts,
                notReadByTests, ignore, declared, observing,
            ),
            GradleSelectingHost(test, jacoco, mapDir, rootDir, classpathFacts.buildDirs.values),
        )
    }

    refuseAChangedEnvironment(test, mapDir, environment, jacoco, changeSetFile, observing)
    refuseAStaleChangeSet(project, test, mapDir, against, stamp, paths, jacoco, changeSetFile, buildMemo, observing, notReadByTests)
    trusted?.let { listed ->
        refuseAnUnverifiedMap(project, test, mapDir, listed.digest, jacoco, changeSetFile, observing)
    }
}

/**
 * Refuses to select, so the whole suite runs, and has that run capture and date the map unless the
 * map was recorded in isolation. The console line is [reason], then [cause]; when [clears], it also
 * says whether this run's capture clears the refusal.
 */
private fun refuseAndRecord(
    test: Test,
    mapDir: File,
    observing: Boolean,
    fromBuildScript: TestPatterns,
    kind: RefusalKind,
    reason: String,
    cause: String,
    clears: Boolean,
) {
    refuse(test, kind, reason)
    val refusalJacoco = test.extensions.findByName("jacoco") as? JacocoTaskExtension
    test.doFirst {
        if (refusedAtExecution(test)) return@doFirst
        // Read here, where `--fail-fast` and the filter are final: a run that cannot date the
        // map must not promise that its capture clears the refusal.
        val undated = readFilterVerdict(test, fromBuildScript).undatedBy(test.failFast)
        SelectingStep.runEverything(GradleSelectingHost(test, refusalJacoco, mapDir), mapDir, observing, reason, cause, clears, undated)
    }
}

/**
 * The base a selecting run compares from, widened to the map's age, and the working tree's drift
 * since the capture. The run plan asks it once, before any decline is checked.
 */
internal fun selectionWidening(
    project: Project,
    settings: Settings,
    mapDir: File,
    buildMemo: BuildMemo?,
): RunPlan.Widening {
    val explicit = settings.base
    val base = when {
        explicit != null -> ChangeDetection.Base(explicit, Settings.BASE)
        else -> ChangeDetection.defaultBase(project.providers, project.rootDir, buildMemo)
            ?: noBaseFound()
    }
    val stampAge = widenBaseToMapAge(project, mapDir, base, buildMemo)
    // Only for a map whose stamp stands; the drift is the part of the map's age a commit
    // cannot state.
    val drift = if (stampAge is MapAge.Known) worktreeDrift(project, mapDir, buildMemo) else null
    val age = (drift as? WorkingTree.Drift.Unknown)?.let { MapAge.Unknown(it.kind, it.reason) }
        ?: stampAge
    return RunPlan.Widening(age, drift, base)
}

/**
 * Turns a run asked to select into a recording run, for each decline that held: it runs every
 * test, captures and dates the map. A map recorded with a JVM per test class is left as it is
 * unless this invocation passes `-Pyoriwake.isolatedCapture`, as on every other fallback. Registered
 * where the selection actions would be, so the named-tests and Develocity declines still run first.
 *
 * An observing run already records; only the selection it observes is declined.
 */
internal fun TestTaskWiring.declineSelection(test: Test, mapDir: File, runPlan: RunPlan) {
    val first = runPlan.declines.first()
    refuse(test, first.kind, first.reason)
    test.systemProperty(DECLINES_PROPERTY, runPlan.declines.joinToString(",") { it.kind.token })
    if (runPlan.observing) {
        val reasons = runPlan.declines.joinToString("; ") { it.reason }
        val leftAlone = declinedLeftAloneMarker(CoverageDecoder.recordsDir(mapDir))
        test.doFirst {
            // One a declined run left when its decode never ran must not discard this capture.
            leftAlone.delete()
            if (declinedUnderDevelocity(test) || declinedForNamedTests(test, first.kind)) return@doFirst
            test.logger.lifecycle(
                "[yoriwake] ${test.path}: $reasons, so a selecting run would run every test, and that is " +
                    "what this run observes."
            )
        }
        return
    }
    // Held from configuration time: a task action may not reach Task.extensions under the
    // configuration cache.
    val jacoco = test.extensions.findByName("jacoco") as? JacocoTaskExtension
    val reasons = runPlan.declines.map { it.reason }
    val isolatedCapture = settings.isolatedCapture
    val leftAlone = declinedLeftAloneMarker(CoverageDecoder.recordsDir(mapDir))
    test.doFirst {
        leftAlone.delete()
        if (declinedUnderDevelocity(test)) return@doFirst
        if (declinedForNamedTests(test, first.kind)) {
            // The decode reads it: this run is not selecting, yet captured nothing on purpose.
            runCatching {
                leftAlone.parentFile.mkdirs()
                leftAlone.writeText(NAMED_TESTS_LEFT_ALONE + "\n")
            }
            return@doFirst
        }
        val flag = if (runPlan.asked == RunPlan.Kind.COMPLEMENT) Settings.COMPLEMENT else Settings.SELECT
        SelectingStep.declined(GradleSelectingHost(test, jacoco, mapDir), mapDir, reasons, flag, isolatedCapture)
    }
}

/**
 * Leaves out of a complement run the tests the selecting run's record lists as ran, once the record's
 * stamp equals this run's: the commit, a clean tree, the task, the build, the classpath and the task's
 * configuration. The test JVM checks its own identity. A record that is missing or does not match
 * makes the run a recording full run, named; one that matches captures nothing and leaves the map,
 * and the record, as they are. With a trusted-map list, only a record from `<dir>` is used.
 *
 * The record is read at configuration, so its digest is a task input and a new record is never
 * `UP-TO-DATE`; its stamp is compared where the task starts, when the classpath and the task's
 * options are settled, as the selecting run read its own.
 */
internal fun TestTaskWiring.configureComplement(
    project: Project,
    test: Test,
    mapDir: File,
    runPlan: RunPlan,
    agent: File?,
    buildMemo: BuildMemo?,
) {
    val from = runPlan.complementFrom ?: return
    val record = if (from.isEmpty()) {
        File(mapDir, AgentContract.SELECTION_FILE)
    } else {
        File(File(from).let { if (it.isAbsolute) it else File(project.rootDir, from) }, "${mapDir.name}/${AgentContract.SELECTION_FILE}")
    }
    val where = if (from.isEmpty()) "this build's map directory" else from
    val copy = File(mapDir, AgentContract.COMPLEMENT_RECORD_FILE)
    // A record that cannot be read is no record: it costs a full run, never the build. Asked before
    // Gradle reads it, since a read that fails is a configuration cache problem however it is caught;
    // a missing record is still Gradle's to read, so a record written later is not missed.
    val text = if (!record.isFile || java.nio.file.Files.isReadable(record.toPath())) {
        project.providers.fileContents(project.objects.fileProperty().fileValue(record)).asText.orNull
    } else {
        null
    }
    val read = SelectionRecord.read(text)
    val unusable = when {
        // A trusted-map list makes a restored map directory unreviewed input, and the record in it is
        // outside the map's digest: only a record handed over by the run that wrote it is used.
        from.isEmpty() && settings.trustedMaps != null -> RefusalKind.COMPLEMENT_RECORD_MISMATCH to
            "the ${AgentContract.SELECTION_FILE} for ${test.path} in $where is not one the trusted-map list " +
            "vouches for, since a restored cache could have written it; with -P${Settings.TRUSTED_MAPS}, pass " +
            "the selecting run's own record as -P${Settings.COMPLEMENT}=<dir>"
        text == null && record.exists() -> RefusalKind.COMPLEMENT_NO_RECORD to
            "the ${AgentContract.SELECTION_FILE} for ${test.path} in $where cannot be read as a file"
        text == null -> RefusalKind.COMPLEMENT_NO_RECORD to
            "no ${AgentContract.SELECTION_FILE} for ${test.path} in $where: no selecting run that narrowed at a clean tree left one"
        read.failure() != null -> RefusalKind.COMPLEMENT_RECORD_MISMATCH to
            "the ${AgentContract.SELECTION_FILE} for ${test.path} in $where is unusable: ${read.failure()}"
        else -> null
    }
    // The record as an input: a new one must run the task again, and never find it up to date.
    test.inputs.property("yoriwake.complement", text?.let(::complementDigest) ?: "none")
    val leftAlone = declinedLeftAloneMarker(CoverageDecoder.recordsDir(mapDir))
    if (unusable != null) {
        val (kind, reason) = unusable
        refuse(test, kind, reason)
        test.doFirst {
            copy.delete()
            if (declinedUnderDevelocity(test) || namedTestsLeftAlone(test, leftAlone)) return@doFirst
            test.logger.lifecycle("[yoriwake] ${test.path}: $reason, so every test runs and the map is recorded.")
        }
        return
    }
    // Everything the selecting run ran may be left out.
    allowAnEmptyRun(test)
    val recorded = SelectionRecord.stampOf(read)
    val identity = StampIdentity.of(project, test, agent)
    val listed = read.ran().size
    // Held from configuration time: a task action may not reach Task.extensions under the
    // configuration cache.
    val jacoco = test.extensions.findByName("jacoco") as? JacocoTaskExtension
    val validated: String = text!!
    val rootDir = project.rootDir
    // A build directory outside the root holds build output the classpath stamp reads too, so the
    // last look compares it as well.
    val buildDirs = projectFacts(project, buildMemo).buildDirs.values.toList()
    val lastLook = LastLook.handOver(mapDir)
    test.doFirst {
        copy.delete()
        runCatching { lastLook.delete() }
        if (declinedUnderDevelocity(test) || namedTestsLeftAlone(test, leftAlone) || refusedAtExecution(test)) return@doFirst
        val mismatch = SelectionRecord.mismatch(recorded, identity.observe(test))
            ?: runCatching { copy.parentFile.mkdirs(); writeAtomically(copy, validated) }
                .exceptionOrNull()?.let { "its copy could not be written ($it)" }
            // The build's own doFirst actions run after this: the last look compares the build's
            // output on the classpath again, as the classpath stamp read it.
            ?: when (val walk = runCatching { ClasspathFiles.walk(test.classpath.files, buildDirs, rootDir, classes = true) }
                .getOrElse { ClasspathFiles.Walk.Refused(it.toString()) }) {
                is ClasspathFiles.Walk.Refused -> "the build output on its classpath, which could not be digested (${walk.reason})"
                is ClasspathFiles.Walk.Found ->
                    runCatching { LastLook.write(mapDir, rootDir, buildDirs, classes = true, walk.digests) }
                        .exceptionOrNull()?.let { "the build output on its classpath, which could not be written to ${lastLook.name} ($it)" }
            }
        if (mismatch != null) {
            val reason = "the ${AgentContract.SELECTION_FILE} for ${test.path} in $where differs from this run in $mismatch"
            refuse(test, RefusalKind.COMPLEMENT_RECORD_MISMATCH, reason)
            test.logger.lifecycle("[yoriwake] ${test.path}: $reason, so every test runs and the map is recorded.")
            return@doFirst
        }
        test.systemProperty(AgentContract.COMPLEMENT_RECORD_PROPERTY, copy.absolutePath)
        applyCaptureDecision(
            test, mapDir, jacoco, observing = false,
            CaptureDecision(
                capture = false, fullRun = false, mapCurrent = false,
                reason = "leaving out the $listed tests the selecting run at ${recorded.commit?.take(12)} ran; nothing " +
                    "is instrumented, and the map and ${AgentContract.SELECTION_FILE} are left as they are.",
            ),
        )
    }
}

/**
 * Whether tests were named on this run, which then run whole and are captured by nothing; the decode
 * reads [leftAlone] and leaves the map as it is.
 */
private fun namedTestsLeftAlone(test: Test, leftAlone: File): Boolean {
    if (!declinedForNamedTests(test)) return false
    runCatching {
        leftAlone.parentFile.mkdirs()
        leftAlone.writeText(NAMED_TESTS_LEFT_ALONE + "\n")
    }
    return true
}

/** The selection record as one short value, standing in for it as a task input. */
private fun complementDigest(text: String): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

private const val NAMED_TESTS_LEFT_ALONE =
    "only the named tests run, so nothing is instrumented and the map is left alone."

private val NAMED_TESTS_REFUSALS = setOf(RefusalKind.TESTS_NAMED, RefusalKind.DECLINE_UNDETERMINED).map { it.token }

/** The kinds an action of the run itself decides; a later action leaves the run as they set it. */
private val EXECUTION_REFUSALS =
    setOf(
        RefusalKind.CHANGE_SET_STALE, RefusalKind.MAP_UNVERIFIED, RefusalKind.MAP_UNTRUSTED,
        RefusalKind.ENVIRONMENT_CHANGED, RefusalKind.ENVIRONMENT_UNRECORDED,
    )
        .map { it.token } + DEVELOCITY_REFUSALS + NAMED_TESTS_REFUSALS

/**
 * Whether this run declined selection because tests were named, or could not tell. [planned] is
 * the decline the run plan made at configuration: an undetermined one of its own is not about the
 * test filter.
 */
internal fun declinedForNamedTests(test: Test, planned: RefusalKind? = null): Boolean {
    val kind = test.systemProperties[REFUSED_KIND_PROPERTY]?.toString()
    return kind in NAMED_TESTS_REFUSALS && kind != planned?.token
}

/**
 * Declines selection on a task run with `--tests`: a developer who names tests expects every one of
 * them to run, so the filter Gradle applies decides alone. So does a run whose filter gained include
 * patterns after the build script ran, as an IDE's test launcher adds the tests it was asked for.
 * Decided at execution, from the filter itself: Gradle applies `--tests` after configuration, and a
 * test launcher's patterns after the configuration cache stored its entry. Registered after every
 * other selection action, so it runs before them, and each of them returns on it. A filter whose
 * patterns cannot be read declines too. [fromBuildScript] is the filter as the build script left it.
 */
internal fun TestTaskWiring.declineNamedTests(
    test: Test,
    mapDir: File,
    fromBuildScript: TestPatterns,
    observing: Boolean,
) {
    // Held from configuration time: a task action may not reach Task.extensions under the
    // configuration cache.
    val jacoco = test.extensions.findByName("jacoco") as? JacocoTaskExtension
    test.doFirst {
        if (declinedUnderDevelocity(test)) return@doFirst
        val (kind, reason) = readFilterVerdict(test, fromBuildScript).namedTestsDecline() ?: return@doFirst
        test.logger.lifecycle(
            "[yoriwake] ${test.path}: $reason, so selection is declined and every test the filter " +
                "matches runs."
        )
        refuse(test, kind, reason)
        // Listed beside any decline the run plan already made.
        val declined = test.systemProperties[DECLINES_PROPERTY]?.toString().orEmpty()
        test.systemProperty(DECLINES_PROPERTY, listOf(declined, kind.token).filter(String::isNotEmpty).joinToString(","))
        // Only part of the suite runs, so there is nothing to capture.
        applyCaptureDecision(
            test, mapDir, jacoco, observing,
            CaptureDecision(
                capture = false, fullRun = false, mapCurrent = false,
                reason = NAMED_TESTS_LEFT_ALONE,
            ),
        )
    }
}

private fun refusedAtExecution(test: Test) =
    test.systemProperties[REFUSED_KIND_PROPERTY]?.toString() in EXECUTION_REFUSALS

/** What `-Pyoriwake.trustedMaps` says of one map: [digest] is null when the list does not name it. */
internal class TrustedDigest(val digest: String?)

/**
 * The trusted-map list's entry for [mapDir], or null when no list was passed. Read through a
 * provider, so a reused configuration-cache entry sees a changed list. An unreadable list or a
 * malformed line fails the build: it is the caller's input.
 */
internal fun trustedDigest(project: Project, settings: Settings, mapDir: File): TrustedDigest? {
    val path = settings.trustedMaps ?: return null
    val flag = "-P${Settings.TRUSTED_MAPS}=$path"
    val file = File(path).let { if (it.isAbsolute) it else File(project.rootDir, path) }
    val text = project.providers.fileContents(project.objects.fileProperty().fileValue(file)).asText.orNull
        ?: throw InvalidUserDataException("[yoriwake] $flag: $file cannot be read.")
    val listed = try {
        MapProvenance.parseTrustedList(text, flag)
    } catch (malformed: IllegalArgumentException) {
        throw InvalidUserDataException(malformed.message.orEmpty())
    }
    return TrustedDigest(listed[mapDir.name])
}

/**
 * Runs the whole suite when the map is not one the trusted-map list vouches for: restored from a
 * cache the pull request could have written, its content is unreviewed. Registered after every
 * other selection action, so it runs first; registered only when a list was passed.
 */
private fun TestTaskWiring.refuseAnUnverifiedMap(
    project: Project,
    test: Test,
    mapDir: File,
    listed: String?,
    jacoco: JacocoTaskExtension?,
    changeSetFile: File,
    observing: Boolean,
) {
    // Fingerprinted at execution, so a result cached under another verdict is never reused.
    val provenance = project.providers.of(MapProvenanceSource::class.java) {
        it.parameters.mapDir.set(mapDir.absolutePath)
        listed?.let { digest -> it.parameters.listed.set(digest) }
    }
    test.inputs.property("yoriwake.mapProvenance", provenance)
    test.doFirst {
        // A declined run leaves the map exactly as it was: no check, no clear.
        if (refusedAtExecution(test)) return@doFirst
        val (kind, reason) = MapProvenance.verify(mapDir, listed).refusal ?: return@doFirst
        test.logger.lifecycle(
            "[yoriwake] ${test.path}: $reason, so the whole suite runs and records a new " +
                "map. A map narrows here only when -P${Settings.TRUSTED_MAPS} names its digest, as a " +
                "run on the default branch recorded it."
        )
        refuse(test, kind, reason)
        MapProvenance.clear(mapDir)
        runCatching { changeSetFile.delete() }
        applyCaptureDecision(
            test, mapDir, jacoco, observing,
            decideCapture(mapDir, mapUsable = false, fullRun = true, learnable = emptySet()),
        )
    }
}

/**
 * Runs the whole suite when the test JVM's environment is not the one the map was recorded in, or
 * the map records none; see [EnvironmentFingerprint]. The capture it falls back to records the
 * environment as it is now, so the next run compares against it. Registered after the selecting
 * action, so it runs before it; the stale-change-set and provenance checks run earlier still.
 */
private fun TestTaskWiring.refuseAChangedEnvironment(
    test: Test,
    mapDir: File,
    rules: EnvironmentFingerprint.Rules,
    jacoco: JacocoTaskExtension?,
    changeSetFile: File,
    observing: Boolean,
) {
    test.doFirst {
        if (refusedAtExecution(test)) return@doFirst
        SelectingStep.refuseAChangedEnvironment(
            GradleSelectingHost(test, jacoco, mapDir), mapDir, environmentView(test), rules, changeSetFile, observing,
        )
    }
}

/**
 * Runs the whole suite when the tree the tests will see is not the one the change set was computed
 * from: a task earlier in this build, or a continuous build's later cycle, may have edited it after
 * configuration. Registered after the other selection actions, so it runs before them; only the
 * provenance check, registered after it, runs earlier.
 */
private fun TestTaskWiring.refuseAStaleChangeSet(
    project: Project,
    test: Test,
    mapDir: File,
    against: String,
    stamp: String?,
    paths: List<String>,
    jacoco: JacocoTaskExtension?,
    changeSetFile: File,
    buildMemo: BuildMemo?,
    observing: Boolean,
    notReadByTests: List<String>,
) {
    // Plain values: a task action may not touch Project or its providers under the configuration
    // cache, so git is asked directly.
    val rootDir = project.rootDir
    val facts = projectFacts(project, buildMemo)
    val excluded = WorkingTree.excluded(rootDir, facts.buildDirs.values)
    val sourceDirs = ForcingPaths.sourceDirs(rootDir, facts.allSourceDirs)
    // The same git call as the change set's own, answered from the build's memo.
    val configuredTracked = listOfNotNull(against, stamp)
        .map { ChangeDetection.trackedPaths(project.providers, rootDir, it, buildMemo) }
        .takeUnless { null in it }?.flatMap { it.orEmpty() }
    test.doFirst {
        // A provenance refusal already runs everything over a cleared map, which has no drift to compare.
        if (refusedAtExecution(test)) {
            return@doFirst
        }
        val git = ChangeDetection.directRunner(rootDir)
        // The same union as at configuration: the diffs from the widened base and the capture
        // commit, and the drift.
        val listed = runCatching {
            val tracked = listOfNotNull(against, stamp)
                .map { ChangeDetection.trackedPaths(git, rootDir, it) ?: return@runCatching null }
                .flatten()
            val untracked = ChangeDetection.untrackedPaths(git) ?: return@runCatching null
            val drift = WorkingTree.drift(rootDir, mapDir, excluded) as? WorkingTree.Drift.Moved
                ?: return@runCatching null
            val trackedThen = configuredTracked ?: return@runCatching null
            // Tracked at either time: a revert after configuration leaves it only in the first.
            Pair(tracked + trackedThen, tracked + untracked + drift.paths)
        }.getOrNull()
        // Build output and Gradle's state are written by the build itself between configuration and
        // now, such as a Kotlin session marker in an unignored `.kotlin/`; they are not a change.
        // A tracked path is never such noise, whatever directory it sits in.
        val buildState = WorkingTree.buildState(rootDir, excluded)
        val tracked = listed?.first.orEmpty().toSet()
        // What is no change to selection is no change here either, tracked or not.
        val declared = NotRead.declared(notReadByTests)
        val isNoise = { path: String ->
            (path !in tracked && buildState(path)) || NotRead.isIdeState(path) || declared(path)
        }
        val configured = paths.filterNot(isNoise).toSet()
        val now = listed?.second?.filterNot(isNoise)?.toSet()
        if (now == configured) {
            return@doFirst
        }
        val reason = if (now == null) {
            "the change set could not be computed again when the task ran"
        } else {
            "the working tree changed after this build was configured " +
                "(${(now - configured).size + (configured - now).size} paths)"
        }
        test.logger.lifecycle("[yoriwake] ${test.path}: $reason, so the whole suite runs.")
        refuse(test, RefusalKind.CHANGE_SET_STALE, reason)
        // Never read under a refusal; deleted so a previous run's file is not mistaken for this one's.
        runCatching { changeSetFile.delete() }
        if (now != null) {
            ForcingPaths.lines(test.path, ForcingPaths.classify(rootDir, mapDir, now - configured, sourceDirs, against))
                .forEach { test.logger.lifecycle("[yoriwake] ${test.path}: $it") }
        }
        applyCaptureDecision(
            test, mapDir, jacoco, observing,
            decideCapture(
                mapDir,
                mapUsable = runCatching {
                    io.github.zeuspizza.yoriwake.agent.select.MapReader.read(mapDir).isUsable
                }.getOrDefault(false),
                fullRun = true,
                learnable = ChangeDetection.split(now ?: configured, rootDir).classPrefixes,
            ),
        )
    }
}

/**
 * The declaration a selecting run applies, or null when [entries] is empty. Plain values, read at
 * configuration: a task action may not touch Project. A path outside the root that git could not
 * place is treated as outside, so it is kept.
 */
internal fun pathIgnoreDeclaration(
    project: Project,
    entries: List<String>,
    sourceDirs: List<String>,
    against: List<String>,
    paths: List<String>,
    buildMemo: BuildMemo?,
): PathIgnore.Declaration? {
    if (entries.isEmpty()) return null
    val outside = against.map { ChangeDetection.outsideRoot(project.providers, project.rootDir, it, buildMemo) }
    return PathIgnore.Declaration(
        entries,
        project.rootDir,
        sourceDirs,
        buildLogicDirs(project),
        if (null in outside) paths.toSet() else outside.flatMap { it.orEmpty() }.toSet(),
    )
}

/** The root-relative directories of the builds this one includes, which hold build logic. */
internal fun buildLogicDirs(project: Project): List<String> =
    runCatching { project.gradle.includedBuilds.map { it.projectDir } }.getOrDefault(emptyList())
        .let { ForcingPaths.sourceDirs(project.rootDir, it) }

/**
 * Each changed path an entry matches, by name and content, read through a provider so a reused
 * configuration-cache entry sees the file change.
 */
private fun ignoredContentDigest(project: Project, declaration: PathIgnore.Declaration, paths: List<String>): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    (declaration.entries + "\u0000").forEach { digest.update(it.toByteArray(Charsets.UTF_8)); digest.update(0) }
    paths.filter { path -> declaration.entries.any { PathIgnore.matches(it, path) } }.sorted().forEach { path ->
        val file = File(project.rootDir, path)
        val content = if (file.isFile) {
            project.providers.fileContents(project.objects.fileProperty().fileValue(file)).asBytes.orNull
        } else {
            null
        }
        digest.update(path.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(content?.let { java.security.MessageDigest.getInstance("SHA-256").digest(it) } ?: ByteArray(0))
        digest.update(0)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** The configuration-time change set as one short value, standing in for it as a task input. */
private fun changeSetDigest(change: ChangeDetection.Change): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    (change.classPrefixes.sorted() + "\u0000" + change.unmappablePaths.sorted())
        .forEach { digest.update(it.toByteArray(Charsets.UTF_8)); digest.update(0) }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Applies [decideCapture] to this run: instrument, or make instrumenting impossible by clearing
 * the records directory. Turning JaCoCo off with the listener on would record every test as
 * covering nothing.
 *
 * An [observing] run runs every test and captures as a recording run does, whatever the decision
 * says a selecting run would do.
 */
private fun applyCaptureDecision(
    test: Test,
    mapDir: File,
    jacoco: JacocoTaskExtension?,
    observing: Boolean,
    decided: CaptureDecision,
) = SelectingStep.applyCapture(GradleSelectingHost(test, jacoco, mapDir), mapDir, observing, decided)

/**
 * A selecting run's answers on a Gradle `Test` task: its console, its system properties, and its own
 * JaCoCo extension, held from configuration time as the configuration cache requires.
 */
private class GradleSelectingHost(
    private val test: Test,
    private val jacoco: JacocoTaskExtension?,
    private val mapDir: File,
    private val rootDir: File? = null,
    private val buildDirs: Collection<String> = emptyList(),
) : SelectingStep.Host {
    override val name: String get() = test.path

    override fun lifecycle(line: String) = test.logger.lifecycle(line)

    override fun warn(line: String) = test.logger.warn(line)

    override fun refuse(kind: RefusalKind, reason: String) = refuse(test, kind, reason)

    override fun property(name: String, value: String) {
        test.systemProperty(name, value)
    }

    // The build's own doFirst actions run after this one: the last look compares again.
    override fun handOver(compared: Map<String, String>): Boolean =
        runCatching { LastLook.write(mapDir, requireNotNull(rootDir), buildDirs, classes = false, compared) }.isSuccess

    override fun captureNothing() {
        test.systemProperty(RECORDS_DIR_PROPERTY, "")
        runCatching {
            jacoco?.isEnabled = false
        }.onFailure {
            // Costs speed, not correctness. `warn`, because Gradle hides `info`.
            test.logger.warn(
                "[yoriwake] ${test.path}: could not switch instrumentation off ($it), so this run still " +
                    "pays the instrumentation toll while capturing nothing."
            )
        }
    }

    override fun flag(name: String) = "-P$name"

    override val explain: String get() = ForcingPaths.explainTask(test.path)
}

/** [WorkingTree.drift] through a value source; one that could not be obtained is unlisted. */
private fun worktreeDrift(project: Project, mapDir: File, buildMemo: BuildMemo?): WorkingTree.Drift {
    val excluded = WorkingTree.excluded(project.rootDir, projectFacts(project, buildMemo).buildDirs.values)
    val listed = WorkingTree.configuredListing(project.rootDir, excluded, buildMemo) {
        ChangeDetection.cachedRawGit(project.providers, project.rootDir, buildMemo, it)
    }
    return WorkingTree.decode(
        runCatching {
            project.providers.of(WorkingTreeDriftSource::class.java) {
                it.parameters.rootDir.set(project.rootDir.absolutePath)
                it.parameters.mapDir.set(mapDir.absolutePath)
                // Left unset for an unlisted tree, which the source reads as exactly that.
                listed?.let { joined -> it.parameters.listed.set(joined) }
            }.get()
        }.getOrNull()
    )
}

private fun widenBaseToMapAge(
    project: Project,
    mapDir: File,
    base: ChangeDetection.Base,
    buildMemo: BuildMemo?,
): MapAge {
    val stampFile = project.objects.fileProperty()
        .fileValue(File(mapDir, CoverageDecoder.CAPTURE_COMMIT_FILE))
    // Validated as a sha before it reaches git, through the same helper as the capture
    // decision; see [validCaptureStamp].
    val stamp = validCaptureStamp(project.providers.fileContents(stampFile).asText.orNull)
    // Whether a map exists, through a provider so a configuration-cache entry is invalidated
    // when one appears.
    val versionFile = project.objects.fileProperty()
        .fileValue(File(mapDir, AgentContract.MAP_SCHEMA_VERSION_FILE))
    val mapPresent = project.providers.fileContents(versionFile).asText.isPresent
    return widenToMapAge(base, stamp, mapPresent) { a, b ->
        ChangeDetection.commonAncestor(project.providers, project.rootDir, a, b, buildMemo)
    }
}
