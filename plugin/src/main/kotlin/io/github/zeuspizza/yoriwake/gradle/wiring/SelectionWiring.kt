package io.github.zeuspizza.yoriwake.gradle.wiring

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.ABSENCE_PROVABLE_PROPERTY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.ACCOUNTED_PROPERTY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.CHANGED_BYTES_PROPERTY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.CHANGED_CLASSES_PROPERTY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.CHANGE_SET_FILE_PROPERTY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.CLASS_GRANULARITY_PROPERTY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.EXEMPT_TEST_CLASSES_PROPERTY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.OWN_TEST_CLASSES_PROPERTY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.RECORDS_DIR_PROPERTY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.REFUSED_KIND_PROPERTY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.SELECT_PROPERTY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.UNMAPPABLE_PATHS_PROPERTY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.UNREADABLE_PATHS_PROPERTY
import io.github.zeuspizza.yoriwake.gradle.Settings
import io.github.zeuspizza.yoriwake.gradle.YoriwakePlugin.Companion.noBaseFound
import io.github.zeuspizza.yoriwake.gradle.capture.CaptureDecision
import io.github.zeuspizza.yoriwake.gradle.capture.CoverageDecoder
import io.github.zeuspizza.yoriwake.gradle.capture.MapAge
import io.github.zeuspizza.yoriwake.gradle.capture.MapProvenance
import io.github.zeuspizza.yoriwake.gradle.capture.decideCapture
import io.github.zeuspizza.yoriwake.gradle.capture.validCaptureStamp
import io.github.zeuspizza.yoriwake.gradle.capture.widenToMapAge
import io.github.zeuspizza.yoriwake.gradle.change.ChangeDetection
import io.github.zeuspizza.yoriwake.gradle.change.RefusalKind
import io.github.zeuspizza.yoriwake.gradle.change.changeSet
import io.github.zeuspizza.yoriwake.gradle.change.WorkingTree
import io.github.zeuspizza.yoriwake.gradle.change.accountedFor
import io.github.zeuspizza.yoriwake.gradle.change.digestRule
import io.github.zeuspizza.yoriwake.gradle.change.establish
import io.github.zeuspizza.yoriwake.gradle.change.reportDigest
import io.github.zeuspizza.yoriwake.gradle.change.scopedChange
import io.github.zeuspizza.yoriwake.gradle.change.widenForInlining
import io.github.zeuspizza.yoriwake.gradle.change.wouldRunEverything
import io.github.zeuspizza.yoriwake.gradle.facts.BuildMemo
import io.github.zeuspizza.yoriwake.gradle.facts.ClasspathFacts
import io.github.zeuspizza.yoriwake.gradle.facts.classpathFacts
import io.github.zeuspizza.yoriwake.gradle.facts.projectFacts
import org.gradle.api.InvalidUserDataException
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import java.io.File

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
 */
internal fun TestTaskWiring.configureSelection(
    project: Project,
    test: Test,
    mapDir: File,
    buildMemo: BuildMemo?,
) {
    if (!settings.select) {
        return
    }
    // Read before any refusal below, so a list that cannot be read fails every selecting run alike.
    val trusted = trustedDigest(project, settings, mapDir)

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
    if (age is MapAge.Unknown) {
        refuse(test, age.kind, age.reason)
        val refusalJacoco = test.extensions.findByName("jacoco") as? JacocoTaskExtension
        val cause = if (age.kind == RefusalKind.SNAPSHOT_ABSENT) {
            "A map captured before snapshots were recorded looks like this"
        } else {
            "A rebase, a force-push, or a map cached from a different history all look like this"
        }
        test.doFirst { task ->
            if (declinedUnderDevelocity(test)) return@doFirst
            // A map recorded in isolation is left as it is, as by every other fallback.
            val leftAlone = decideCapture(
                mapDir,
                mapUsable = runCatching {
                    io.github.zeuspizza.yoriwake.agent.select.MapReader.read(mapDir).isUsable
                }.getOrDefault(false),
                fullRun = true,
                learnable = emptySet(),
                ageKnown = false,
            ).takeUnless { it.capture }
            task.logger.lifecycle(
                "[yoriwake] ${task.path}: ${age.reason}, and the whole suite runs. $cause; " +
                    if (leftAlone == null) "this run's capture clears it." else "the next recording run clears it."
            )
            // Running everything, so it captures and dates the map; otherwise a build that
            // always selects would refuse forever. An isolated map waits for its recording run.
            applyCaptureDecision(
                test, mapDir, refusalJacoco,
                leftAlone ?: CaptureDecision(
                    capture = true, fullRun = true, mapCurrent = false,
                    reason = "running everything, so this run also captures and dates the map.",
                ),
            )
        }
        return
    }
    val widened = (age as MapAge.Known).base
    val against = widened.ref
    val stamp = age.stamp
    // Union only: the snapshot adds what `git diff` from the stamp cannot see (a reverted
    // dirty edit, an ignored fixture). A tree git could not list is no change set at all.
    // The diff from the capture commit too: a map captured off this history (another branch, a
    // reset) recorded content the widened base may share with the tree.
    val paths = listOfNotNull(against, stamp)
        .map { ChangeDetection.changedPaths(project.providers, project.rootDir, it, buildMemo) }
        .takeUnless { null in it }?.flatMap { it.orEmpty() }
        ?.let { tracked -> (drift as? WorkingTree.Drift.Moved)?.let { (tracked + it.paths).distinct() } }
    if (paths == null) {
        refuse(test, RefusalKind.NO_CHANGE_SET,
            "git could not report changes against $against, so there is no change set to " +
                "select from")
        val refusalJacoco = test.extensions.findByName("jacoco") as? JacocoTaskExtension
        test.doFirst {
            if (declinedUnderDevelocity(test)) return@doFirst
            test.logger.lifecycle(
                "[yoriwake] ${test.path}: git could not report changes against $against, so the " +
                    "whole suite runs. Selection needs a change set it can trust."
            )
            // Captures and dates a shared map like any full run; a map recorded in isolation is
            // left as it is, as by every other fallback.
            applyCaptureDecision(
                test, mapDir, refusalJacoco,
                decideCapture(
                    mapDir,
                    mapUsable = runCatching {
                        io.github.zeuspizza.yoriwake.agent.select.MapReader.read(mapDir).isUsable
                    }.getOrDefault(false),
                    fullRun = true,
                    learnable = emptySet(),
                    ageKnown = false,
                ),
            )
        }
        return
    }

    // Resolved to plain values and providers here: a task action may not touch Project under
    // the configuration cache.
    val classpathFacts = classpathFacts(project, test, paths)
    // Held from configuration time: a task action may not reach Task.extensions under the
    // configuration cache.
    val jacoco = test.extensions.findByName("jacoco") as? JacocoTaskExtension
    // Written by the action below; a file it could not write reads as a refusal in the test JVM.
    val changeSetFile = File(mapDir, AgentContract.CHANGE_SET_FILE)
    test.systemProperty(CHANGE_SET_FILE_PROPERTY, changeSetFile.absolutePath)
    // The whole change set, as an input: the file is not one, and a different change set must
    // re-run the task, or a change that forces a full run finds it up to date and runs nothing.
    // Only configuration-time state is an input; what the actions below set is not.
    test.inputs.property(
        "yoriwake.changeSet", changeSetDigest(ChangeDetection.split(paths, project.rootDir)))
    test.systemProperty(SELECT_PROPERTY, "true")
    // Forwarded only when asked for; the agent defaults it to false.
    test.systemProperty(
        CLASS_GRANULARITY_PROPERTY, settings.classGranularity.toString())
    allowAnEmptyRun(test)

    // Re-derived at execution, when the classpath is resolvable. Every doFirst runs before the
    // test JVM launches, so these properties are ones the JVM starts with.
    test.doFirst {
        // Decided by a check below, which runs before this action and captures.
        if (refusedAtExecution(test)) {
            return@doFirst
        }
        val scoped = scopedChange(paths, classpathFacts)
        val established = establish(
            { message -> test.logger.lifecycle("[yoriwake] ${test.path}: $message") },
            mapDir, scoped.change, classpathFacts,
        )

        // An inline function's body is compiled into its call sites, so coverage cannot link
        // them; Kotlin's SMAP records the edge, and the consumers count as changed.
        val widening = widenForInlining(
            scoped.change.classPrefixes, classpathFacts, CoverageDecoder.readConstants(mapDir),
            scoped.change.inlinableSourceChanged,
            digestRule(mapDir, bytesAreFresh = true),
            recordedAnnotations = CoverageDecoder.readAnnotationDigests(mapDir),
            ownTestClasses = established.testClasses,
        )
        reportDigest(widening) { message -> test.logger.lifecycle("[yoriwake] ${test.path}: $message") }

        val changed = if (widening.forces) {
            // Unknown, not "nothing": selection is switched off and everything executes, as
            // SelectionFilter does for a missing select flag.
            test.logger.lifecycle("[yoriwake] ${test.path}: ${widening.refusal}")
            refuse(test, widening.refusalKind ?: RefusalKind.UNNAMED, widening.refusal.orEmpty())
            scoped.change.classPrefixes
        } else {
            if (widening.inliners.isNotEmpty()) {
                test.logger.lifecycle(
                    "[yoriwake] ${test.path}: ${widening.inliners.size} class(es) inlined the changed " +
                        "sources and are selected with them"
                )
            }
            widening.prefixes
        }

        // Says the empty change set was arrived at (every path off-classpath or unreadable)
        // rather than reported by git, which would force a full run.
        test.systemProperty(
            ACCOUNTED_PROPERTY,
            accountedFor(paths, scoped, established).toString(),
        )
        writeChangeSet(
            test, changeSetFile,
            mapOf(
                CHANGED_CLASSES_PROPERTY to changed,
                // Its own property: the change set is also the selector's anchor set for nested
                // classes.
                CHANGED_BYTES_PROPERTY to if (widening.forces) emptySet() else widening.changedBytes,
                UNMAPPABLE_PATHS_PROPERTY to scoped.change.unmappablePaths,
                ABSENCE_PROVABLE_PROPERTY to established.provable,
                // Separate property: a test class that has ever run is in the loaded-class union by
                // definition, so the absence rule would never fire for it.
                OWN_TEST_CLASSES_PROPERTY to established.testClasses,
                EXEMPT_TEST_CLASSES_PROPERTY to established.exemptTestClasses,
                UNREADABLE_PATHS_PROPERTY to established.unreadablePaths,
            ),
        )
        if (established.unreadablePaths.isNotEmpty() || established.testClasses.isNotEmpty()) {
            test.logger.lifecycle(
                "[yoriwake] ${test.path}: ${established.unreadablePaths.size} changed paths the build " +
                    "never reads, ${established.testClasses.size} changed test classes discovery " +
                    "already runs"
            )
        }
        test.logger.lifecycle(
            "[yoriwake] ${test.path} selecting against ${widened.ref} (${widened.origin}): " +
                "${scoped.change.classPrefixes.size} changed classes, " +
                "${scoped.change.unmappablePaths.size} paths coverage cannot see" +
                if (scoped.dropped == 0) "" else
                    ", ${scoped.dropped} in modules that are not on this task's classpath"
        )

        val outlook = wouldRunEverything(mapDir, changed, scoped, established, widening, paths)
        // The un-widened change set on purpose: an unchanged inline consumer teaches the map
        // nothing, and widening would re-capture the suite on every inline change.
        applyCaptureDecision(
            test, mapDir, jacoco,
            decideCapture(
                mapDir,
                mapUsable = outlook.mapUsable,
                fullRun = outlook.fullRun,
                learnable = scoped.change.classPrefixes,
            ),
        )
    }

    refuseAStaleChangeSet(project, test, mapDir, against, stamp, paths, jacoco, changeSetFile, buildMemo)
    trusted?.let { listed ->
        refuseAnUnverifiedMap(project, test, mapDir, listed.digest, jacoco, changeSetFile)
    }
}

/** The kinds an action of the run itself decides; a later action leaves the run as they set it. */
private val EXECUTION_REFUSALS =
    setOf(
        RefusalKind.CHANGE_SET_STALE, RefusalKind.MAP_UNVERIFIED, RefusalKind.MAP_UNTRUSTED,
        RefusalKind.DEVELOCITY_TEST_DISTRIBUTION, RefusalKind.DEVELOCITY_TEST_SELECTION,
        RefusalKind.DEVELOCITY_UNDETERMINED,
    ).map { it.token }

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
        throw InvalidUserDataException(malformed.message)
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
) {
    // Fingerprinted at execution, so a result cached under another verdict is never reused.
    val provenance = project.providers.of(MapProvenance.Source::class.java) {
        it.parameters.mapDir.set(mapDir.absolutePath)
        listed?.let { digest -> it.parameters.listed.set(digest) }
    }
    test.inputs.property("yoriwake.mapProvenance", provenance)
    test.doFirst {
        // A declined run leaves the map exactly as it was: no check, no clear.
        if (declinedUnderDevelocity(test)) return@doFirst
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
            test, mapDir, jacoco,
            decideCapture(mapDir, mapUsable = false, fullRun = true, learnable = emptySet()),
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
) {
    // Plain values: a task action may not touch Project or its providers under the configuration
    // cache, so git is asked directly.
    val rootDir = project.rootDir
    val excluded = WorkingTree.excluded(rootDir, projectFacts(project, buildMemo).buildDirs.values)
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
        val isNoise = { path: String -> path !in tracked && buildState(path) }
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
        applyCaptureDecision(
            test, mapDir, jacoco,
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

/** The configuration-time change set as one short value, standing in for it as a task input. */
private fun changeSetDigest(change: ChangeDetection.Change): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    (change.classPrefixes.sorted() + "\u0000" + change.unmappablePaths.sorted())
        .forEach { digest.update(it.toByteArray(Charsets.UTF_8)); digest.update(0) }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Hands the list-valued change-set properties to the test JVM through [file] (see
 * [CHANGE_SET_FILE_PROPERTY]): as system properties, a large change set is a command line the OS
 * refuses to start. A file that cannot be written refuses the run instead.
 */
private fun TestTaskWiring.writeChangeSet(test: Test, file: File, lists: Map<String, Collection<String>>) {
    val properties = java.util.Properties()
    lists.forEach { (name, values) -> properties.setProperty(name, values.sorted().joinToString(",")) }
    val text = java.io.StringWriter().also { properties.store(it, null) }.toString() +
        AgentContract.CHANGE_SET_END + "\n"
    runCatching {
        file.parentFile.mkdirs()
        io.github.zeuspizza.yoriwake.gradle.capture.writeAtomically(file, text)
    }.onFailure { failure ->
        // A stale file must not stand in for this run's; the refusal wins over it either way.
        runCatching { file.delete() }
        val reason = "the change set could not be written to $file ($failure)"
        test.logger.lifecycle("[yoriwake] ${test.path}: $reason, so the whole suite runs.")
        refuse(test, RefusalKind.CHANGE_SET_UNREADABLE, reason)
    }
}

/**
 * Applies [decideCapture] to this run: instrument, or make instrumenting impossible by clearing
 * the records directory. Turning JaCoCo off with the listener on would record every test as
 * covering nothing.
 */
private fun applyCaptureDecision(
    test: Test,
    mapDir: File,
    jacoco: JacocoTaskExtension?,
    decision: CaptureDecision,
) {
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
        test.logger.warn(
            "[yoriwake] ${test.path}: could not record that this run executed the whole suite " +
                "($it), so the map keeps the age it had and the next run selects against a " +
                "wider base than it needs to."
        )
    }

    if (decision.capture) {
        test.logger.lifecycle("[yoriwake] ${test.path}: ${decision.reason}")
        return
    }

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
    test.logger.lifecycle("[yoriwake] ${test.path}: ${decision.reason}")
}

/** [WorkingTree.drift] through a value source; one that could not be obtained is unlisted. */
private fun worktreeDrift(project: Project, mapDir: File, buildMemo: BuildMemo?): WorkingTree.Drift {
    val excluded = WorkingTree.excluded(project.rootDir, projectFacts(project, buildMemo).buildDirs.values)
    val listed = WorkingTree.configuredListing(project.rootDir, excluded, buildMemo) {
        ChangeDetection.cachedRawGit(project.providers, project.rootDir, buildMemo, it)
    }
    return WorkingTree.decode(
        runCatching {
            project.providers.of(WorkingTree.DriftSource::class.java) {
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
