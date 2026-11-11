package io.github.zeuspizza.yoriwake.gradle.change

import io.github.zeuspizza.yoriwake.gradle.bytecode.DigestScan
import io.github.zeuspizza.yoriwake.gradle.bytecode.Recordability
import io.github.zeuspizza.yoriwake.gradle.bytecode.TaskArtifacts
import io.github.zeuspizza.yoriwake.gradle.capture.CoverageDecoder
import java.io.File

/**
 * `narrowLineOnlyChanges`: a changed source whose compiled classes moved in line information only
 * leaves the change set, and selects the tests whose JVM could have seen a line number or read the
 * class or source file from then on (`Selector`'s line window). Every condition that cannot be
 * established keeps the path changed, and names why.
 */

/** Why a changed source path stays changed with the flag on, as `explain.json` names it. */
internal enum class LineOnlyKept(val token: String) {
    /** The map records no line digests, or ones stamped by another capture than the map's. */
    LINE_DIGESTS_ABSENT("line-digests-absent"),

    /** The map records no debug touches, or ones that do not speak for its current JVMs. */
    DEBUG_TOUCH_ABSENT("debug-touch-absent"),

    /** The file is gone, and the class it compiled to may linger in the output. */
    PATH_DELETED("path-deleted"),

    /** A class it compiles to differs from the map's in more than line information, or cannot be read. */
    CLASS_UNMATCHED("class-unmatched"),

    /** A class the map recorded for it is no longer compiled. */
    CLASS_VANISHED("class-vanished"),

    /** This build's output could not be walked whole, so a class no name maps could have changed unseen. */
    WALK_INCOMPLETE("walk-incomplete"),

    /** A file the build produced on the classpath changed since the capture, as a processor can from a comment. */
    OUTPUT_FILE_CHANGED("output-file-changed"),

    /** The task runs code in JVMs the agent cannot observe, which may read any line. */
    UNOBSERVED_JVMS("unobserved-jvms"),
}

/** What the line-only rule reads, built by each selecting call site; absent with the flag off. */
internal class LineOnlyRule(
    val rootDir: File,
    /** The map's line digests, when they speak for it; see `CoverageDecoder.readLineDigests`. */
    val recorded: Map<String, String>?,
    /** Whether the map's debug touches speak for it; see `CoverageDecoder.debugTouchesCurrent`. */
    val debugTouches: Boolean,
    /** [ChangeDetection.Change.prefixesByPath] of the change set the prefixes came from. */
    val prefixesByPath: Map<String, Set<String>>,
    /** The build-produced classpath files that moved since the capture, named; null when none did. */
    val movedOutput: String?,
) {
    companion object {
        fun of(mapDir: File, rootDir: File, change: ChangeDetection.Change, classpathFiles: ClasspathFilesVerdict) =
            LineOnlyRule(
                rootDir,
                CoverageDecoder.readLineDigests(mapDir),
                CoverageDecoder.debugTouchesCurrent(mapDir),
                change.prefixesByPath,
                when (classpathFiles) {
                    ClasspathFilesVerdict.Unchanged -> null
                    is ClasspathFilesVerdict.Moved -> classpathFiles.moved.named()
                    is ClasspathFilesVerdict.Handed -> classpathFiles.moved.named()
                    is ClasspathFilesVerdict.DependenciesMoved -> classpathFiles.moved.named()
                    is ClasspathFilesVerdict.Unrecorded -> classpathFiles.why
                },
            )
    }
}

/** A path kept changed, and what about it: a class, a file, a token of the walk. */
internal data class Kept(val kind: LineOnlyKept, val detail: String?)

/** What the line-only rule decided for one change set. */
internal class LineOnly(
    /** The line-only paths. */
    val paths: List<String>,
    /** Their prefixes, which left the change set. */
    val prefixes: Set<String>,
    /** Classes whose bytes moved with no changed source behind them, in line information only. */
    val bytes: Set<String>,
    /** Each changed source path the rule kept changed, and why. */
    val kept: Map<String, Kept>,
) {
    /** What the selector windows by its debug touches: prefixes and class names alike. */
    val classes: Set<String> get() = (prefixes + bytes).toSortedSet()

    companion object {
        val NONE = LineOnly(emptyList(), emptySet(), emptySet(), emptyMap())
    }
}

/**
 * Judges each changed source path behind [prefixes], and each class [digest] found moved with no
 * source change, against the map's line digests. A path is line-only when it exists, every class
 * it compiles to matches its recorded line digest, and none the map recorded for it vanished; a
 * prefix two paths share leaves only when both are. Every path stays when anything the rule rests
 * on is missing: the map's tables, the walk over the whole output, an unchanged classpath, an
 * observed JVM. And every path stays when a class whose line digest moved would be accounted for
 * by nothing that stays changed.
 */
internal fun classifyLineOnly(
    rule: LineOnlyRule,
    prefixes: Collection<String>,
    artifacts: TaskArtifacts,
    digest: DigestWidening?,
): LineOnly {
    val asked = prefixes.toSet()
    val paths = rule.prefixesByPath.filterValues { own -> own.any(asked::contains) }
    if (paths.isEmpty() && digest?.added.isNullOrEmpty()) return LineOnly.NONE
    fun keepAll(kind: LineOnlyKept, detail: String? = null) =
        LineOnly(emptyList(), emptySet(), emptySet(), paths.keys.associateWith { Kept(kind, detail) })

    val recorded = rule.recorded ?: return keepAll(LineOnlyKept.LINE_DIGESTS_ABSENT)
    if (!rule.debugTouches) return keepAll(LineOnlyKept.DEBUG_TOUCH_ABSENT)
    if (runCatching { artifacts.runsCodeInUnobservedJvms() }.getOrDefault(true)) return keepAll(LineOnlyKept.UNOBSERVED_JVMS)
    if (digest == null || digest.silence != null) return keepAll(LineOnlyKept.WALK_INCOMPLETE, digest?.silence)
    rule.movedOutput?.let { return keepAll(LineOnlyKept.OUTPUT_FILE_CHANGED, it) }
    val walk = runCatching { artifacts.classDigests(Recordability::lineDigest) }.getOrNull()
    val current = (walk as? DigestScan.Found)?.digests
        ?: return keepAll(LineOnlyKept.WALK_INCOMPLETE, (walk as? DigestScan.Refused)?.reason)

    val kept = linkedMapOf<String, Kept>()
    for ((path, own) in paths) {
        if (!File(rule.rootDir, path).isFile) {
            kept[path] = Kept(LineOnlyKept.PATH_DELETED, null)
            continue
        }
        judge(own.filter(asked::contains), artifacts, current, recorded)?.let { kept[path] = it }
    }
    val lineOnlyPaths = paths.keys.filter { it !in kept }
    val leaving = lineOnlyPaths.flatMapTo(HashSet()) { paths.getValue(it).filter(asked::contains) } -
        kept.keys.flatMapTo(HashSet()) { paths.getValue(it) }
    val staying = asked - leaving
    // A class whose line digest moved must be one the run still treats as changed: in the bytes
    // the class digest found moved, or compiled from a source that stays. Anything else (a
    // generated class whose local variables alone moved) would be seen by nothing.
    val moved = (current.keys + recorded.keys).filterTo(HashSet()) { current[it] != recorded[it] }
    val unaccounted = io.github.zeuspizza.yoriwake.agent.select.Selector.bytesOnly(moved, staying) - digest.added
    if (leaving.isNotEmpty() && unaccounted.isNotEmpty()) {
        val detail = unaccounted.sorted().first()
        lineOnlyPaths.forEach { kept[it] = Kept(LineOnlyKept.CLASS_UNMATCHED, detail) }
        return LineOnly(emptyList(), emptySet(), lineOnlyBytes(digest, current, recorded), kept)
    }
    return LineOnly(lineOnlyPaths, leaving, lineOnlyBytes(digest, current, recorded), kept)
}

/** Of the classes whose bytes moved with no source change, those that moved in line information only. */
private fun lineOnlyBytes(digest: DigestWidening, current: Map<String, String>, recorded: Map<String, String>): Set<String> =
    digest.added.filterTo(sortedSetOf()) { name -> current[name] != null && current[name] == recorded[name] }

/** Why these prefixes of one path are not line-only, or null when they are. */
private fun judge(
    prefixes: List<String>,
    artifacts: TaskArtifacts,
    current: Map<String, String>,
    recorded: Map<String, String>,
): Kept? {
    for (prefix in prefixes.sorted()) {
        val classes = artifacts.classesFor(prefix)
        if (classes.isNullOrEmpty()) return Kept(LineOnlyKept.CLASS_UNMATCHED, prefix)
        val bytes = classes.toMap()
        // `classesFor` finds a file class's nested classes (`UtilsKt$run$1`) by another name than
        // the map may hold them under, so every class the source compiles to is judged.
        val names = bytes.keys.toSortedSet() + current.keys.filter { compiledFrom(it, prefix) }
        for (name in names) {
            val now = current[name] ?: bytes[name]?.let { Recordability.lineDigest(it).value }
            if (now == null || recorded[name] != now) return Kept(LineOnlyKept.CLASS_UNMATCHED, name)
        }
        recorded.keys.filter { compiledFrom(it, prefix) && it !in current }.minOrNull()?.let {
            return Kept(LineOnlyKept.CLASS_VANISHED, it)
        }
    }
    return null
}
