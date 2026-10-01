package io.github.zeuspizza.yoriwake.gradle.change

import io.github.zeuspizza.yoriwake.gradle.bytecode.DigestScan
import io.github.zeuspizza.yoriwake.gradle.bytecode.EffectiveScope
import io.github.zeuspizza.yoriwake.gradle.bytecode.InlineScan
import io.github.zeuspizza.yoriwake.gradle.bytecode.Recordability
import io.github.zeuspizza.yoriwake.gradle.bytecode.TaskArtifacts
import io.github.zeuspizza.yoriwake.gradle.capture.CoverageDecoder
import io.github.zeuspizza.yoriwake.gradle.facts.ClasspathFacts
import io.github.zeuspizza.yoriwake.gradle.facts.modulesOnClasspath
import java.io.File

/**
 * What the daemon can establish about a change set, using the classpath and the compiled output.
 *
 * Nothing here may hold a reference to a `Task`: [ClasspathFacts] carries plain values, providers
 * and file collections so the configuration cache can serialise it.
 */

/** What the daemon could establish about a change set, for the rules that need bytecode. */
internal data class Established(
    /** Changed classes whose absence from the map means untested, as far as this side can tell. */
    val provable: Set<String>,
    /** Changed classes that are this task's own test classes, which discovery already runs. */
    val testClasses: Set<String>,
    /** Changed paths the build never reads and no compiled class can name. */
    val unreadablePaths: Set<String>,
    /** Why each class the daemon could not vouch for was refused, for `yoriwakeExplain` to report. */
    val refusals: Map<String, String> = emptyMap(),
    /**
     * Of [testClasses], those that declare a test and that no other compiled class or resource
     * names, so their discovery-time loading opens no JVM-shared window.
     */
    val exemptTestClasses: Set<String> = emptySet(),
)

internal fun establish(
    report: (String) -> Unit,
    mapDir: File,
    change: ChangeDetection.Change,
    facts: ClasspathFacts,
): Established {
    val artifacts = runCatching {
        TaskArtifacts(facts.classpath.files, facts.buildDirs.values, facts.testOutputs.files)
    }.getOrNull() ?: return Established(emptySet(), emptySet(), emptySet())

    // Independent of the map: a path nothing can name is unreadable whatever the map knows, and a
    // changed test class is one discovery already covers.
    val unreadable = ClasspathScope.unreadable(
        change.unmappablePaths,
        artifacts.pathsNamedInClasses(change.unmappablePaths),
        namedInBuildScripts(facts.rootDir, change.unmappablePaths),
    )
    // A task that runs builds in child JVMs is one the union cannot speak for; the reason belongs
    // to the task, not to any one class.
    val verdicts = if (artifacts.runsCodeInUnobservedJvms()) {
        report(
            "this task runs code in child JVMs the agent cannot observe (gradle-testkit is on " +
                "its classpath), so a class absent from the loaded-class union proves nothing " +
                "and every changed class the map does not mention still forces a full run"
        )
        change.classPrefixes.associateWith {
            AbsenceEvidence.Verdict(false, "this task runs code in JVMs the agent cannot see")
        }
    } else {
        AbsenceEvidence.unannotated(
            absenceVerdicts(report, mapDir, change, artifacts),
            artifacts::classesFor,
        ) { prefix -> artifacts.onlyInTestOutput(artifacts.classesFor(prefix)) }
    }
    // Living in the test output is not enough: a constants-only fixture is discovered by nothing,
    // inlined into every test that reads it and never covered. So a test class must clear the same
    // recordability and resource gates as a main class; only the loaded-class union is waived.
    val testClasses = verdicts
        .filterKeys { prefix -> artifacts.onlyInTestOutput(artifacts.classesFor(prefix)) }
        .filterValues { it.provable }
        .keys
    // A test class JUnit alone instantiates can reach another test only through a lookup, a read
    // or an execution the agent sees. One that anything else names can be reached without any.
    val exemptTestClasses = testClasses.filterTo(mutableSetOf()) { prefix ->
        val own = artifacts.classesFor(prefix)
        own?.firstOrNull { (name, _) -> name == prefix }?.let { (_, bytes) -> Recordability.declaresTestMethod(bytes) } == true &&
            !artifacts.namedOutsideItself(prefix)
    }
    return Established(
        provable = verdicts.filterValues { it.provable }.keys,
        testClasses = testClasses,
        unreadablePaths = unreadable,
        exemptTestClasses = exemptTestClasses,
        refusals = verdicts
            .filterValues { !it.provable }
            .mapValues { (_, verdict) -> verdict.reason.orEmpty() },
    )
}

/**
 * The change set widened by every class that inlined it, or a refusal to narrow this run at all.
 *
 * One helper for every call site, so selection and `yoriwakeExplain` never answer different
 * questions.
 */
internal class InlineWidening private constructor(
    /** The changed classes, unioned with the classes that inlined them. Empty when [forces]. */
    val prefixes: List<String>,
    val inliners: Set<String>,
    /** Why nothing may be deselected this run, or null. */
    val refusal: String?,
    /** Whether the refusal was the inline scan failing to finish, as opposed to a constant holder. */
    val scanExhausted: Boolean,
    /** Which refusal this is, as a token rather than as English. See [RefusalKind]. */
    val refusalKind: RefusalKind? = null,
    /** The scan's own account of the branch it stopped on, kept apart from the prose. */
    val refusalDetail: String? = null,
    /** What comparing the compiled bytes against the map found, or null when nothing compared. */
    val digest: DigestWidening? = null,
) {
    val forces: Boolean get() = refusal != null

    /**
     * Classes whose bytes changed with no changed source behind them, for the selector's separate
     * set. Never part of [prefixes]: those are also the anchor set for `Selector.isNestedUnder`,
     * where an added name can certify a prefix nothing covers.
     */
    val changedBytes: Set<String>
        get() = digest?.added
            ?.let { io.github.zeuspizza.yoriwake.agent.select.Selector.bytesOnly(it, prefixes) }
            .orEmpty()

    fun withDigest(digest: DigestWidening?): InlineWidening =
        if (digest == null) this
        else InlineWidening(prefixes, inliners, refusal, scanExhausted, refusalKind, refusalDetail, digest)

    companion object {
        fun widened(
            prefixes: List<String>,
            inliners: Set<String>,
            digest: DigestWidening? = null,
        ) = InlineWidening(prefixes, inliners, null, false, digest = digest)

        fun refuse(
            reason: String,
            scanExhausted: Boolean,
            kind: RefusalKind? = null,
            detail: String? = null,
            digest: DigestWidening? = null,
        ) = InlineWidening(emptyList(), emptySet(), reason, scanExhausted, kind, detail, digest)
    }
}

/** What the bytes comparison compares against on this call. */
internal class DigestRule(
    /** The map's recorded table, from `CoverageDecoder.readClassDigests`. Null when it has none. */
    val recorded: Map<String, String>?,
    /** Whether the bytes to walk come from this run's compile tasks. */
    val bytesAreFresh: Boolean,
)

/**
 * What comparing this build's compiled bytes against the map's recorded digests produced.
 * [added] names classes whose bytecode moved where `git diff` cannot see it (a constant, an inline
 * body); [silence] tells "nothing compared" apart from "everything matched".
 */
internal data class DigestWidening(
    /** Classes whose current digest differs from the recorded one, or that one side does not hold. */
    val added: Set<String> = emptySet(),
    /** Why nothing was compared, as a [DigestSilence] token. Null when the comparison actually ran. */
    val silence: String? = null,
    /** What the silence happened to: a file, a directory, a throwable. */
    val detail: String? = null,
    /** Class files the walk reached, digests it produced, and files it could not read. */
    val considered: Int = 0,
    val digested: Int = 0,
    val unreadable: Int = 0,
    val recorded: Int = 0,
    /** See [DigestRule.bytesAreFresh]. */
    val bytesAreFresh: Boolean = true,
)

/** The comparison every selecting call site runs. Top-level: a task action may not reach Project. */
internal fun digestRule(mapDir: File, bytesAreFresh: Boolean): DigestRule =
    DigestRule(CoverageDecoder.readClassDigests(mapDir), bytesAreFresh)

/** Says what the bytes comparison found; a comparison that could not run forces and says so. */
internal fun reportDigest(widening: InlineWidening, log: (String) -> Unit) {
    val digest = widening.digest ?: return
    if (digest.silence != null || widening.forces) return
    val stale =
        if (digest.bytesAreFresh) ""
        else " -- read from whatever bytes the build directory held, which no compile task in this " +
            "invocation produced"
    val changed = widening.changedBytes
    log(
        "compared ${digest.digested} compiled classes against ${digest.recorded} the map recorded: " +
            "${changed.size} changed with no source change behind them" +
            (if (changed.isEmpty()) "" else " (" +
                changed.sorted().take(REPORTED_BYTES).joinToString(", ") +
                (if (changed.size > REPORTED_BYTES) ", ..." else "") + "). A test that recorded " +
                "one runs, and one no test recorded runs everything") +
            stale
    )
}

private const val REPORTED_BYTES = 5

/**
 * The refusal for a comparison that could not run: a class rewritten after compilation, with no
 * source change, cannot then be ruled out.
 */
private fun bytesRefusal(digest: DigestWidening?): InlineWidening {
    val silence = digest?.silence
    val why = when (silence) {
        DigestSilence.TABLE_ABSENT.token -> "the map records no digest of this build's compiled classes"
        null -> "this build's compiled classes could not be established"
        else -> "this build's compiled classes could not be compared against the map ($silence" +
            (digest.detail?.let { ": $it" } ?: "") + ")"
    }
    return InlineWidening.refuse(
        "$why, so a class whose bytes changed with no source change -- a weaver's or a " +
            "post-processor's output -- cannot be ruled out. The whole suite runs.",
        scanExhausted = false,
        kind = when (silence) {
            DigestSilence.TABLE_ABSENT.token -> RefusalKind.BYTES_UNRECORDED
            DigestSilence.WALK_NOTHING_TO_SCAN.token, null -> RefusalKind.SCAN_REFUSED
            else -> RefusalKind.SCAN_EXHAUSTED
        },
        detail = digest?.detail ?: silence,
        digest = digest,
    )
}

/** Why the digest rule compared nothing, as the token `explain.json` reports in `digestSilence`. */
internal enum class DigestSilence(val token: String) {
    /** No table: the map predates the digest walk, or its last capture could not finish one. */
    TABLE_ABSENT("digest-table-absent"),

    /** The walk over this build's output did not finish; one value per [DigestScan.Kind]. */
    WALK_NOTHING_TO_SCAN("digest-walk-nothing-to-scan"),
    WALK_UNACCOUNTED_OUTPUT("digest-walk-unaccounted-output"),
    WALK_UNREADABLE("digest-walk-unreadable"),
    WALK_BUDGET_SPENT("digest-walk-budget-spent"),
    ;

    companion object {
        fun walk(kind: DigestScan.Kind): DigestSilence = when (kind) {
            DigestScan.Kind.NOTHING_TO_SCAN -> WALK_NOTHING_TO_SCAN
            DigestScan.Kind.UNACCOUNTED_OUTPUT -> WALK_UNACCOUNTED_OUTPUT
            DigestScan.Kind.UNREADABLE -> WALK_UNREADABLE
            DigestScan.Kind.BUDGET_SPENT -> WALK_BUDGET_SPENT
        }
    }
}

/** Parenthesised because every other key in [Established.refusals] is a class name. */
internal const val INLINE_REFUSAL_KEY: String = "(inline widening)"

internal const val INLINE_SCAN_REFUSAL: String =
    "the classes that inline the changed sources could not be read, so the whole suite runs. " +
        "An inline body is copied into its call sites, and coverage cannot see that edge."

/**
 * Widens a change set to the classes that inlined it, and refuses when it cannot.
 *
 * - The scan did not finish: "nothing inlines this" from an unfinished scan would be a silent skip.
 * - A changed class declares a compile-time constant: the value is copied into every consumer, so
 *   a test that baked in the old value records no coverage edge to the holder.
 */
internal fun widenForInlining(
    prefixes: Collection<String>,
    facts: ClasspathFacts,
    /** The map's recorded constant digests; empty forces on every holder. */
    recordedConstants: Map<String, String> = emptyMap(),
    /** False only for a Java-only change set, which skips the missing-SMAP refusal. */
    inlinableSourceChanged: Boolean = true,
    /**
     * The bytes comparison, which every selecting call site passes; null compares nothing and is
     * for tests of the other rules only.
     */
    digestRule: DigestRule? = null,
    /** The map's annotation digests, from `CoverageDecoder.readAnnotationDigests`; null forces. */
    recordedAnnotations: Map<String, String>? = null,
    /** [Established.testClasses], whose test classes an annotation change does not force on. */
    ownTestClasses: Set<String> = emptySet(),
): InlineWidening {
    val artifacts = runCatching {
        TaskArtifacts(facts.classpath.files, facts.buildDirs.values, facts.testOutputs.files)
    }.getOrNull()
    if (artifacts == null) {
        if (prefixes.isEmpty() && digestRule != null) return bytesRefusal(null)
        // An empty change set widens to nothing.
        return if (prefixes.isEmpty()) InlineWidening.widened(emptyList(), emptySet())
        else InlineWidening.refuse(
            INLINE_SCAN_REFUSAL,
            scanExhausted = true,
            kind = RefusalKind.SCAN_REFUSED,
        )
    }

    // Computed before the refusals so a forced run still records what the comparison found.
    val digest = digestRule?.let { rule -> compareDigests(rule, artifacts) }
    val bytesUnknown = digest != null && digest.silence != null

    if (prefixes.isEmpty()) {
        // No digest-derived prefixes: an empty change set nobody accounted for forces on its own
        // account, and one that was accounted for selects on the changed bytes alone.
        return if (bytesUnknown) bytesRefusal(digest)
        else InlineWidening.widened(emptyList(), emptySet(), digest)
    }

    constantHolderRefusal(prefixes, recordedConstants, artifacts::classesFor)
        ?.let { return it.withDigest(digest) }

    // The throwable is kept as the only account of why the scan failed.
    var threw: Throwable? = null
    val scan = runCatching { artifacts.classesInlining(prefixes, inlinableSourceChanged) }
        .getOrElse { problem ->
            threw = problem
            InlineScan.Refused(
                "the scan threw ${problem::class.java.name}" +
                    (problem.message?.let { ": $it" } ?: " with no message"),
                InlineScan.Kind.INCOMPLETE,
            )
        }
    val inliners = when (scan) {
        is InlineScan.Found -> scan.classes
        // The scan's own kind: missing SMAP, an unowned classpath directory and a spent budget
        // need telling apart, and only the last is fixed by raising a number.
        is InlineScan.Refused -> return InlineWidening.refuse(
            "$INLINE_SCAN_REFUSAL Refused because: ${scan.reason}",
            scanExhausted = true,
            kind = if (threw != null) RefusalKind.SCAN_THREW else kindOf(scan.kind),
            detail = scan.reason,
            digest = digest,
        )
    }
    // Last, so every refusal above keeps its own token when both apply.
    annotationRefusal(prefixes, recordedAnnotations, ownTestClasses, artifacts::classesFor)
        ?.let { return it.withDigest(digest) }
    if (bytesUnknown) return bytesRefusal(digest)
    // Digest-derived names stay out of the prefixes, which are also the anchor set for
    // `Selector.isNestedUnder`; they reach the selector as [InlineWidening.changedBytes].
    return InlineWidening.widened(
        (prefixes + inliners).distinct().sorted(),
        inliners,
        digest,
    )
}

/**
 * Which classes of this build's output no longer digest the way the map recorded them. Never
 * refuses: [DigestWidening.silence] tells the ways of knowing nothing apart from agreement.
 */
internal fun compareDigests(rule: DigestRule, artifacts: TaskArtifacts): DigestWidening {
    val recorded = rule.recorded ?: return DigestWidening(
        silence = DigestSilence.TABLE_ABSENT.token,
        bytesAreFresh = rule.bytesAreFresh,
    )
    val scan = runCatching { artifacts.classDigests() }.getOrElse { problem ->
        DigestScan.Refused(
            "the walk threw ${problem::class.java.name}" +
                (problem.message?.let { ": $it" } ?: " with no message"),
            DigestScan.Kind.UNREADABLE,
            DigestScan.Counts(),
        )
    }
    return when (scan) {
        is DigestScan.Refused -> DigestWidening(
            silence = DigestSilence.walk(scan.kind).token,
            detail = scan.reason,
            considered = scan.counts.considered,
            digested = scan.counts.digested,
            unreadable = scan.counts.unreadable,
            recorded = recorded.size,
            bytesAreFresh = rule.bytesAreFresh,
        )
        is DigestScan.Found -> {
            val added = sortedSetOf<String>()
            for ((name, value) in scan.digests) {
                // A class the map never recorded is new, and new is not agreement.
                if (recorded[name] != value) added += name
            }
            // A recorded class this output no longer holds is not evidence of anything unchanged,
            // so it is added. Refusing on a deletion is left to the rules that already do.
            for (name in recorded.keys) {
                if (name !in scan.digests) added += name
            }
            DigestWidening(
                added = added,
                considered = scan.counts.considered,
                digested = scan.counts.digested,
                unreadable = scan.counts.unreadable,
                recorded = recorded.size,
                bytesAreFresh = rule.bytesAreFresh,
            )
        }
    }
}

private fun kindOf(kind: InlineScan.Kind): RefusalKind = when (kind) {
    InlineScan.Kind.NOTHING_TO_SCAN -> RefusalKind.SCAN_REFUSED
    // Grouped with `NOTHING_TO_SCAN`: the scan never began, and raising a budget fixes neither.
    InlineScan.Kind.CLASSPATH_UNRESOLVED -> RefusalKind.SCAN_REFUSED
    InlineScan.Kind.SMAP_ABSENT -> RefusalKind.SMAP_ABSENT
    InlineScan.Kind.UNACCOUNTED_OUTPUT, InlineScan.Kind.INCOMPLETE ->
        RefusalKind.SCAN_EXHAUSTED
}

/**
 * The constant-holder refusal, or null when no changed class declares one. Classes that cannot
 * be established, or a throw, refuse: empty is not unknown.
 */
internal fun constantHolderRefusal(
    prefixes: Collection<String>,
    /** Recorded constant digests; empty forces on every holder. [classesOf] stays last. */
    recordedConstants: Map<String, String> = emptyMap(),
    classesOf: (String) -> List<Pair<String, ByteArray>>?,
): InlineWidening? = runCatching {
    for (prefix in prefixes.sorted()) {
        val classes = classesOf(prefix)
            ?: return@runCatching InlineWidening.refuse(
                INLINE_SCAN_REFUSAL,
                scanExhausted = true,
                kind = RefusalKind.SCAN_REFUSED,
                detail = "no compiled class could be established for $prefix",
            )
        for ((name, bytes) in classes) {
            val digest = Recordability.constantDigest(bytes)
            if (digest == null && !Recordability.declaresConstant(bytes)) {
                continue
            }
            // A constant that cannot leave the class cannot have been copied anywhere; see
            // `declaresEscapableConstant` for what escapes. Asked before the digest comparison so
            // it suppresses `constant-unrecorded` too.
            if (!Recordability.declaresEscapableConstant(bytes)) {
                continue
            }
            // The question is whether a constant changed, not whether one exists. A class the map
            // never recorded, or one whose digest cannot be computed now, still forces; only an
            // exact match licenses skipping.
            val recorded = recordedConstants[name]
            if (digest != null && recorded == digest) {
                continue
            }
            return@runCatching InlineWidening.refuse(
                if (recorded == null) {
                    "$prefix declares a compile-time constant the map did not record, so whether it " +
                        "changed cannot be established. The compiler copies such a value into every " +
                        "consumer, and a test that baked in the old one names the holder nowhere. " +
                        "The whole suite runs."
                } else {
                    "$prefix declares a compile-time constant whose value differs from the one the " +
                        "map was captured under. The compiler copies it into every consumer, so a " +
                        "test that baked in the old value names the holder nowhere and coverage " +
                        "records no edge to it. The whole suite runs."
                },
                scanExhausted = false,
                // Capturing fixes the first; nothing fixes the second.
                kind = if (recorded == null) RefusalKind.CONSTANT_UNRECORDED
                else RefusalKind.CONSTANT_CHANGED,
                detail = name,
            )
        }
    }
    null
}.getOrElse {
    InlineWidening.refuse(
        INLINE_SCAN_REFUSAL,
        scanExhausted = true,
        kind = RefusalKind.SCAN_REFUSED,
        detail = it.toString(),
    )
}

internal fun absenceVerdicts(
    report: (String) -> Unit,
    mapDir: File,
    change: ChangeDetection.Change,
    artifacts: TaskArtifacts,
): Map<String, AbsenceEvidence.Verdict> {
    if (change.classPrefixes.isEmpty()) {
        return emptyMap()
    }
    val provenance = io.github.zeuspizza.yoriwake.agent.select.MapReader.scopeProvenanceOf(mapDir)
    if (provenance != io.github.zeuspizza.yoriwake.agent.select.MapReader.ScopeProvenance.RECORDED) {
        // Lifecycle, not info: a rule that switched itself off must say so, or the run looks
        // like a tool with nothing to narrow.
        report(
            "the map does not record the scope it was captured under, so a changed class it " +
                "does not mention still forces a full run. A full capture records it; a map " +
                "built before this version of the plugin does not have it."
        )
        return change.classPrefixes.associateWith {
            AbsenceEvidence.Verdict(false, "the map's instrumentation scope is $provenance")
        }
    }
    // The scopes the map was captured under, not this build's current configuration.
    val recorded = io.github.zeuspizza.yoriwake.agent.select.MapReader.recordedScopeOf(mapDir)
        ?: return change.classPrefixes.associateWith {
            AbsenceEvidence.Verdict(false, "the map records no instrumentation scope")
        }
    val scopes = EffectiveScope.parseAll(recorded)
    return AbsenceEvidence
        .assess(change.classPrefixes, scopes, artifacts::classesFor, artifacts::namedInResources)
}

/**
 * Whether the change set emptied because every changed path was accounted for (off this task's
 * classpath, or unreadable), rather than git reporting nothing, which forces.
 */
internal fun accountedFor(
    paths: List<String>,
    scoped: ScopedChange,
    established: Established,
): Boolean = paths.isNotEmpty() &&
    scoped.change.classPrefixes.isEmpty() &&
    (scoped.change.unmappablePaths - established.unreadablePaths).isEmpty()

/**
 * The change set the three daemon-side `Selector.decide` sites hand it, built one way so they cannot
 * disagree. [changed] is the caller's: widened by inlining at some sites and not at others.
 */
internal fun changeSet(
    changed: List<String>,
    paths: List<String>,
    scoped: ScopedChange,
    established: Established,
    /** [InlineWidening.changedBytes]: kept apart from [changed], which the selector anchors on. */
    changedBytes: Collection<String>,
): io.github.zeuspizza.yoriwake.agent.select.ChangeSet =
    io.github.zeuspizza.yoriwake.agent.select.ChangeSet.of(changed, scoped.change.unmappablePaths.sorted())
        .withAccountedFor(accountedFor(paths, scoped, established))
        .withAbsenceProvable(established.provable)
        .withUnreadablePaths(established.unreadablePaths)
        .withOwnTestClasses(established.testClasses)
        .withExemptTestClasses(established.exemptTestClasses)
        .withChangedBytes(changedBytes.sorted())

/** What the daemon can say about the run the test JVM is about to perform. */
internal data class FullRunOutlook(
    val fullRun: Boolean,
    /** Whether `MapReader` could read a map at all. An unusable map is never current. */
    val mapUsable: Boolean,
)

/**
 * The decision the test JVM is about to reach, reached here instead, through the same
 * `Selector.decide` and inputs `yoriwakeExplain` uses. Anything that cannot be established is a
 * full run, which also makes capture happen, so an error here costs time and never coverage.
 */
internal fun wouldRunEverything(
    mapDir: File,
    changed: Collection<String>,
    scoped: ScopedChange,
    established: Established,
    widening: InlineWidening,
    paths: List<String>,
): FullRunOutlook = runCatching {
    val map = io.github.zeuspizza.yoriwake.agent.select.MapReader.read(mapDir)
    if (!map.isUsable) return FullRunOutlook(fullRun = true, mapUsable = false)
    FullRunOutlook(
        fullRun = io.github.zeuspizza.yoriwake.agent.select.Selector.decide(
            map,
            changeSet(changed.sorted(), paths, scoped, established, widening.changedBytes)
                .withDaemonRefusal(widening.refusal),
            emptyList(),
        ).isFullRun,
        mapUsable = true,
    )
}.getOrDefault(FullRunOutlook(fullRun = true, mapUsable = false))

/** A change set narrowed to this task's classpath, and how much was dropped. */
internal data class ScopedChange(val change: ChangeDetection.Change, val dropped: Int) {
    /** Whether every path was dropped as off-classpath, as opposed to an empty change set. */
    val everythingWasOffClasspath: Boolean
        get() = dropped > 0 &&
            change.classPrefixes.isEmpty() &&
            change.unmappablePaths.isEmpty()
}

/**
 * The change set with other modules' sources removed, computed at execution time. Falls back to
 * the whole change set whenever the classpath or its classes cannot be established.
 */
internal fun scopedChange(
    paths: List<String>,
    facts: ClasspathFacts,
): ScopedChange {
    // The same artifacts `establish` builds, so a sibling path this task's classes name is kept.
    // Lazy: most change sets have no off-classpath path to ask about.
    val present = modulesOnClasspath(facts)
    val restriction = ClasspathScope.restrict(paths, facts.moduleDirs, present) { candidates ->
        TaskArtifacts(facts.classpath.files, facts.buildDirs.values, facts.testOutputs.files)
            .pathsNamedInClasses(candidates)
    }
    return ScopedChange(
        ChangeDetection.split(restriction.kept, facts.rootDir),
        restriction.dropped.size,
    )
}

/**
 * The annotation refusal, or null when no changed class's annotations moved since the capture. A
 * test that depends on a class through a framework's scan (`@Profile`, `@Component`,
 * `@ConditionalOn...`) reads its annotations without executing it, so coverage holds no edge.
 *
 * [recorded] holds every own class at capture; null (an older map, or a walk that did not finish)
 * forces. A class it lacks is new, and forces only if it carries annotations. An annotated class
 * that is gone forces too: removing a bean is an annotation change to whatever scanned it.
 *
 * A test class this task runs is exempt, because its own tests are selected anyway: the ones the
 * map holds through their coverage of it, new ones as tests the map has no record of. That is a
 * class from [ownTestClasses] that itself declares a test method; a helper, a base class or a
 * nested configuration beside it still forces. Not covered: a test that reads another test
 * class's annotations.
 */
internal fun annotationRefusal(
    prefixes: Collection<String>,
    recorded: Map<String, String>?,
    /** [Established.testClasses]: the changed sources in this task's own test output. */
    ownTestClasses: Set<String> = emptySet(),
    classesOf: (String) -> List<Pair<String, ByteArray>>?,
): InlineWidening? = runCatching {
    fun refuse(kind: RefusalKind, name: String, why: String) = InlineWidening.refuse(
        "$name $why. A test that reads annotations, as a framework's scan does, never executes " +
            "the class and coverage records no edge to it. The whole suite runs.",
        scanExhausted = false,
        kind = kind,
        detail = name,
    )
    for (prefix in prefixes.sorted()) {
        if (recorded == null) {
            return@runCatching refuse(
                RefusalKind.ANNOTATIONS_UNRECORDED, prefix,
                "changed and the map records no annotations, so whether they changed cannot be established",
            )
        }
        val classes = classesOf(prefix)
            ?: return@runCatching InlineWidening.refuse(
                INLINE_SCAN_REFUSAL,
                scanExhausted = true,
                kind = RefusalKind.SCAN_REFUSED,
                detail = "no compiled class could be established for $prefix",
            )
        for ((name, bytes) in classes) {
            val current = Recordability.annotationDigest(bytes).value
                ?: return@runCatching InlineWidening.refuse(
                    INLINE_SCAN_REFUSAL,
                    scanExhausted = true,
                    kind = RefusalKind.SCAN_REFUSED,
                    detail = "the annotations of $name could not be read",
                )
            val before = recorded[name]
            if (before == current) continue
            if (prefix in ownTestClasses && Recordability.declaresTestMethod(bytes)) continue
            if (before == null) {
                if (current == Recordability.NO_ANNOTATIONS) continue
                return@runCatching refuse(
                    RefusalKind.ANNOTATIONS_UNRECORDED, name,
                    "carries annotations the map did not record",
                )
            }
            return@runCatching refuse(
                RefusalKind.ANNOTATIONS_CHANGED, name,
                "carries annotations that differ from the ones the map was captured under",
            )
        }
        val present = classes.mapTo(HashSet()) { it.first }
        for ((name, before) in recorded) {
            if (compiledFrom(name, prefix) && name !in present && before != Recordability.NO_ANNOTATIONS) {
                return@runCatching refuse(
                    RefusalKind.ANNOTATIONS_CHANGED, name,
                    "carried annotations when the map was captured and is no longer compiled",
                )
            }
        }
    }
    null
}.getOrElse {
    InlineWidening.refuse(
        INLINE_SCAN_REFUSAL,
        scanExhausted = true,
        kind = RefusalKind.SCAN_REFUSED,
        detail = it.toString(),
    )
}

/** Whether [name] is a class the source behind [prefix] compiles to; mirrors `classesFor`, wider. */
private fun compiledFrom(name: String, prefix: String): Boolean {
    if (name.substringBeforeLast('.', "") != prefix.substringBeforeLast('.', "")) return false
    val simple = prefix.substringAfterLast('.')
    val own = name.substringAfterLast('.')
    return own == simple || own == simple + "Kt" || own.startsWith("$simple$") ||
        own.startsWith("${simple}Kt$") || own.endsWith("$$simple")
}
