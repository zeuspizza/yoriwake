package io.github.zeuspizza.yoriwake.gradle.change

import java.io.File
import java.util.Locale

/**
 * Where each path that forces a run came from, as far as the tree and the last capture can say.
 * It only explains a forced run: nothing here reaches the change set, so a path that forces
 * keeps forcing whatever it is called.
 */
internal object ForcingPaths {

    enum class Origin(val token: String) {
        /** Untracked or ignored, inside a source directory: on a classpath, so tests can read it. */
        GENERATED_IN_SOURCES("generated-in-sources"),

        /** Untracked or ignored, and moved while the map's last dating capture build ran. */
        WRITTEN_DURING_CAPTURE("written-during-capture"),

        /** Untracked or ignored, and neither of the above. */
        UNTRACKED("untracked"),
    }

    /**
     * [origins] for the forcing paths that got one, and [names] what each is called on the console;
     * a path HEAD or the base tracks gets none and is only counted in [unnamed]. When git could not
     * say which paths are tracked, every path is counted there and [untold] is set.
     */
    class Classified(
        val origins: Map<String, Origin>,
        val moves: Map<String, WorkingTree.Move>,
        val sourceDirOf: Map<String, String>,
        val unnamed: Int,
        val names: Map<String, String> = emptyMap(),
        val untold: Boolean = false,
        /** The names that hold sources, for which no glob is offered. */
        val holdingSources: Set<String> = emptySet(),
        /** The names under a source directory, which pathIgnore never leaves out: no glob is offered. */
        val underSources: Set<String> = emptySet(),
    ) {
        companion object {
            fun unnamed(count: Int) = Classified(emptyMap(), emptyMap(), emptyMap(), count, untold = true)

            val NONE = unnamed(0)
        }
    }

    /**
     * Classifies [forcing] against [sourceDirs] (root-relative, every source set's). [tracked] and
     * [captured] are asked only when there is a path to classify, and [captured] only when a path
     * is neither tracked nor in a source directory. [holdsTracked] says whether git tracks a file
     * at or under a directory; it names each classified path.
     */
    fun classify(
        forcing: Collection<String>,
        sourceDirs: Collection<String>,
        tracked: (Collection<String>) -> Set<String>?,
        captured: () -> Map<String, WorkingTree.Move>?,
        holdsTracked: (String) -> Boolean,
        holdsSourceRoot: (String) -> Boolean = { false },
    ): Classified {
        if (forcing.isEmpty()) return Classified.NONE
        val headTracks = tracked(forcing) ?: return Classified.unnamed(forcing.size)
        val moves by lazy { captured().orEmpty() }
        val origins = sortedMapOf<String, Origin>()
        val sourceDirOf = mutableMapOf<String, String>()
        forcing.filterNot { it in headTracks }.forEach { path ->
            val sourceDir = sourceDirs.firstOrNull { path.startsWith("$it/") }
            // Checked first: a file on a classpath is never offered a remedy that would move it.
            origins[path] = when {
                sourceDir != null -> Origin.GENERATED_IN_SOURCES.also { sourceDirOf[path] = sourceDir }
                path in moves -> Origin.WRITTEN_DURING_CAPTURE
                else -> Origin.UNTRACKED
            }
        }
        val names = origins.keys.associateWith { name(it, holdsTracked) }
        val holdingSources = names.entries.groupBy({ it.value }, { it.key })
            .filter { (name, paths) -> paths.any(::isSource) || (name.endsWith("/**") && holdsSourceRoot(name.removeSuffix("/**"))) }
            .keys
        // pathIgnore keeps whatever lies in a source set's directory or under any `src` directory.
        val underSources = names.entries.groupBy({ it.value }, { it.key })
            .filter { (_, paths) -> paths.any { origins[it] == Origin.GENERATED_IN_SOURCES || "src" in it.split('/').dropLast(1) } }
            .keys
        return Classified(
            origins,
            if (Origin.WRITTEN_DURING_CAPTURE in origins.values) {
                moves.filterKeys { origins[it] == Origin.WRITTEN_DURING_CAPTURE }
            } else {
                emptyMap()
            },
            sourceDirOf,
            forcing.size - origins.size,
            names,
            holdingSources = holdingSources,
            underSources = underSources,
        )
    }

    // What a build lists to stop the path forcing: the outermost directory above it that git
    // tracks nothing in, as a pathIgnore glob, so a regenerated tree of thousands of files is
    // one name; the path itself when its own directory holds a tracked file.
    private fun name(path: String, holdsTracked: (String) -> Boolean): String {
        var slash = path.indexOf('/')
        while (slash >= 0) {
            val dir = path.substring(0, slash)
            if (!holdsTracked(dir)) return "$dir/**"
            slash = path.indexOf('/', slash + 1)
        }
        return path
    }

    /**
     * [classify] against the tree at [rootDir] and the record beside [mapDir]'s current snapshot;
     * a path the index or [base] (the commit the change set was diffed against) tracks is tracked.
     * [Classified.NONE] if anything fails, since a failing explanation must never fail the run.
     */
    fun classify(
        rootDir: File,
        mapDir: File,
        forcing: Collection<String>,
        sourceDirs: Collection<String>,
        base: String?,
        git: (List<String>) -> String? = { ChangeDetection.rawGit(rootDir, it) },
    ): Classified = runCatching { classifyTree(rootDir, mapDir, forcing, sourceDirs, base, git) }.getOrDefault(Classified.NONE)

    private fun classifyTree(
        rootDir: File,
        mapDir: File,
        forcing: Collection<String>,
        sourceDirs: Collection<String>,
        base: String?,
        git: (List<String>) -> String?,
    ): Classified {
        val tree by lazy { TrackedTree.list(base, git) }
        return classify(
            forcing, sourceDirs,
            tracked = { paths -> tree?.let { listed -> paths.filterTo(mutableSetOf(), listed::tracks) } },
            captured = {
                val snapshot = File(mapDir, WorkingTree.SNAPSHOT_FILE).takeIf(File::isFile)
                    ?.let { runCatching { it.readText() }.getOrNull() }
                    ?.let(WorkingTree::parse)
                WorkingTree.capturedMoves(mapDir, snapshot?.takenMillis)
            },
            // Unknown reads as held, which names the path itself rather than a wider glob.
            holdsTracked = { dir -> tree?.holds(dir) ?: true },
            holdsSourceRoot = { dir -> holdsSourceRoot(File(rootDir, dir)) },
        )
    }

    // Each path is looked up both under the build's prefix and from the repository's top: the
    // change set keeps a path outside a build in a subdirectory repo-relative. A path deleted since
    // the base is only in its tree. Either match withholds an origin, which is the safe direction.
    private class TrackedTree(private val prefix: String, private val files: Set<String>) {

        // Each tracked file and every directory above it; a submodule is listed as one entry.
        private val held: Set<String> by lazy {
            files.flatMapTo(HashSet()) { file ->
                generateSequence(file) { it.substringBeforeLast('/', "").ifEmpty { null } }.asIterable()
            }
        }

        fun tracks(path: String) = "$prefix$path" in files || path in files

        fun holds(dir: String) = "$prefix$dir" in held || dir in held

        companion object {
            fun list(base: String?, git: (List<String>) -> String?): TrackedTree? {
                val prefix = git(listOf("rev-parse", "--show-prefix"))?.trimEnd('\n') ?: return null
                // The whole index, once, whatever the number of forcing paths: one pathspec per
                // path overran the command line on a large untracked tree, and git said nothing.
                val listed = git(
                    listOfNotNull("ls-files", "-z", "--full-name", base?.let { "--with-tree=$it" }, "--", ":/")
                )?.split('\u0000')?.filterTo(HashSet(), String::isNotEmpty) ?: return null
                return TrackedTree(prefix, listed)
            }
        }
    }

    /** Every source set's directories of the build, relative to [rootDir]; those outside it are left out. */
    fun sourceDirs(rootDir: File, dirs: Collection<File>): List<String> =
        dirs.mapNotNull { dir ->
            dir.relativeToOrNull(rootDir)?.invariantSeparatorsPath?.trim('/')
                ?.takeIf { it.isNotEmpty() && !it.startsWith("..") }
        }.distinct()

    /** Where the console lines point for what to do about a file in the source tree. */
    const val REFERENCE = "docs/reference.md#files-written-into-the-source-tree"

    private const val NAMED = 5

    /**
     * One line per origin, naming at most [NAMED] directories or files each, those holding the most
     * forcing paths first, then how many other paths force too. When git could not say which paths
     * are tracked, one line counts them instead.
     */
    fun lines(task: String, classified: Classified): List<String> {
        if (classified.origins.isEmpty()) {
            val count = classified.unnamed
            if (!classified.untold || count == 0) return emptyList()
            return listOf(
                "$count changed ${if (count == 1) "path forces" else "paths force"} this run and could not be " +
                    "told apart from tracked ones; ${explainTask(task)} lists a sample in explain.json."
            )
        }
        val byOrigin = classified.origins.entries.groupBy({ it.value }, { it.key })
        return buildList {
            byOrigin[Origin.WRITTEN_DURING_CAPTURE]?.let { paths ->
                val groups = grouped(paths, classified)
                val named = named(groups) { name, files ->
                    if (files == listOf(name)) "$name (${moved(classified.moves[name])})" else counted(name, files)
                }
                add(
                    "$named changed during $task's last capture build (by its tasks or its tests), so a " +
                        "selecting run sees ${them(paths)} changed and runs everything. If a test writes " +
                        "such a file and no test, the writer included, ever reads it back, have it write " +
                        "the file into the project's build directory (Derby: the derby.stream.error.file " +
                        "system property). If a test reads it, or a build task writes it, moving it hides " +
                        "its changes from selection; see $REFERENCE. ${listing(paths, groups, classified)}"
                )
            }
            byOrigin[Origin.GENERATED_IN_SOURCES]?.let { paths ->
                val groups = grouped(paths, classified)
                val dirs = groups.take(NAMED).flatMap { it.second }.mapNotNull { classified.sourceDirOf[it] }.distinct()
                add(
                    "${named(groups, ::counted)} ${be(paths)} untracked and in source " +
                        "${if (dirs.size == 1) "directory" else "directories"} ${dirs.joinToString(", ")}, " +
                        "so on the classpath (generated by the build, or not yet added to git). " +
                        listing(paths, groups, classified)
                )
            }
            byOrigin[Origin.UNTRACKED]?.let { paths ->
                val groups = grouped(paths, classified)
                add(
                    "${named(groups, ::counted)} ${be(paths)} untracked or ignored and " +
                        "${if (paths.size == 1) "counts" else "count"} by content; see $REFERENCE. " +
                        listing(paths, groups, classified)
                )
            }
            if (classified.unnamed > 0) {
                add(
                    "${classified.unnamed} other changed ${if (classified.unnamed == 1) "path also forces" else "paths also force"} " +
                        "this run, so the files named above are not all that keeps it from narrowing."
                )
            }
        }
    }

    /** [paths] by the name they are listed under, the names holding the most paths first. */
    private fun grouped(paths: List<String>, classified: Classified): List<Pair<String, List<String>>> =
        paths.groupBy { classified.names[it] ?: it }.toList()
            .sortedWith(compareByDescending<Pair<String, List<String>>> { it.second.size }.thenBy { it.first })

    private fun named(groups: List<Pair<String, List<String>>>, label: (String, List<String>) -> String): String =
        groups.take(NAMED).joinToString(", ") { (name, files) -> label(name, files) } +
            if (groups.size > NAMED) " and ${groups.size - NAMED} more" else ""

    private fun counted(name: String, files: List<String>) =
        if (files.size == 1) name else "$name (${"%,d".format(Locale.ROOT, files.size)} files)"

    // Offered under the condition that makes it safe: a test still sees what the build compiles
    // or copies from these files onto its classpath, which the selecting run compares by content.
    // Never for a name holding sources: listed, a changed source there would stop counting as one,
    // and the tests that use what it compiles to could be skipped.
    private fun listing(paths: List<String>, groups: List<Pair<String, List<String>>>, classified: Classified): String {
        val (sources, rest) = groups.take(NAMED).partition { (name, _) -> name in classified.holdingSources }
        val (kept, offered) = rest.partition { (name, _) -> name in classified.underSources }
        val holding = if (sources.isEmpty()) "" else
            "${sources.joinToString(", ") { it.first }} ${if (sources.size == 1) "holds" else "hold"} source " +
                "files, so no glob is offered for ${if (sources.size == 1) "it" else "them"}: listed, a change " +
                "to them would no longer count as a change to sources."
        val under = if (kept.isEmpty()) "" else
            "${kept.joinToString(", ") { it.first }} ${if (kept.size == 1) "is" else "are"} under a source " +
                "directory, which pathIgnore never leaves out: generating ${if (kept.size == 1) "it" else "them"} " +
                "under the build directory instead stops the full runs."
        val withheld = listOf(holding, under).filter(String::isNotEmpty).joinToString(" ")
        if (offered.isEmpty()) return withheld
        val offeredPaths = offered.flatMap { it.second }
        return "If neither a test nor a build step reads ${if (offeredPaths.size == 1) "this file" else "these files"} " +
            "other than to compile or copy ${them(offeredPaths)} onto the test classpath (whose changed classes run " +
            "the tests that executed them, and whose changed files the tests that read them), list " +
            "${them(offeredPaths)} in pathIgnore: " +
            offered.joinToString(", ") { "\"${it.first}\"" } +
            (if (groups.size > NAMED) ", and the ${groups.size - NAMED} more alike" else "") + "." +
            (if (withheld.isEmpty()) "" else " $withheld")
    }

    private val SOURCE_SUFFIXES = listOf(".java", ".kt", ".kts", ".groovy", ".scala")

    private fun isSource(path: String) = SOURCE_SUFFIXES.any(path::endsWith)

    private val SOURCE_ROOTS = listOf("java", "kotlin", "groovy", "scala")

    // A source tree laid out as a source set's (`main/java`), at the directory or one level below
    // it: the generated-sources directories builds write, which a later change could put a
    // source file in.
    private fun holdsSourceRoot(dir: File): Boolean = runCatching {
        val candidates = listOf(dir) + dir.listFiles().orEmpty().filter(File::isDirectory)
        candidates.any { base ->
            base.listFiles().orEmpty().any { set -> set.isDirectory && SOURCE_ROOTS.any { File(set, it).isDirectory } }
        }
    }.getOrDefault(true)

    private fun explainTask(task: String) =
        task.substringBeforeLast(':') + ":yoriwakeExplain" + task.substringAfterLast(':').replaceFirstChar(Char::uppercase)

    private fun moved(move: WorkingTree.Move?) = when (move) {
        WorkingTree.Move.CREATED -> "created"
        WorkingTree.Move.DELETED -> "deleted"
        else -> "rewritten"
    }

    private fun them(paths: List<String>) = if (paths.size == 1) "it" else "them"

    private fun be(paths: List<String>) = if (paths.size == 1) "is" else "are"
}
