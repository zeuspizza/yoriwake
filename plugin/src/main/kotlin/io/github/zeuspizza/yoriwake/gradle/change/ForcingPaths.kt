package io.github.zeuspizza.yoriwake.gradle.change

import java.io.File

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
     * [origins] for the forcing paths that got one; a path HEAD or the base tracks, or every path when git
     * could not say which are tracked, gets none and is only counted in [unnamed].
     */
    class Classified(
        val origins: Map<String, Origin>,
        val moves: Map<String, WorkingTree.Move>,
        val sourceDirOf: Map<String, String>,
        val unnamed: Int,
    ) {
        companion object {
            fun unnamed(count: Int) = Classified(emptyMap(), emptyMap(), emptyMap(), count)

            val NONE = unnamed(0)
        }
    }

    /**
     * Classifies [forcing] against [sourceDirs] (root-relative, every source set's). [tracked] and
     * [captured] are asked only when there is a path to classify, and [captured] only when a path
     * is neither tracked nor in a source directory.
     */
    fun classify(
        forcing: Collection<String>,
        sourceDirs: Collection<String>,
        tracked: (Collection<String>) -> Set<String>?,
        captured: () -> Map<String, WorkingTree.Move>?,
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
        return Classified(
            origins,
            if (Origin.WRITTEN_DURING_CAPTURE in origins.values) {
                moves.filterKeys { origins[it] == Origin.WRITTEN_DURING_CAPTURE }
            } else {
                emptyMap()
            },
            sourceDirOf,
            forcing.size - origins.size,
        )
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
    ): Classified = runCatching { classifyTree(rootDir, mapDir, forcing, sourceDirs, base) }.getOrDefault(Classified.NONE)

    private fun classifyTree(
        rootDir: File,
        mapDir: File,
        forcing: Collection<String>,
        sourceDirs: Collection<String>,
        base: String?,
    ): Classified = classify(
        forcing, sourceDirs,
        tracked = { paths -> tracked(rootDir, base, paths) },
        captured = {
            val snapshot = File(mapDir, WorkingTree.SNAPSHOT_FILE).takeIf(File::isFile)
                ?.let { runCatching { it.readText() }.getOrNull() }
                ?.let(WorkingTree::parse)
            WorkingTree.capturedMoves(mapDir, snapshot?.takenMillis)
        },
    )

    // Each path is asked both under the build's prefix and from the repository's top: the change
    // set keeps a path outside a build in a subdirectory repo-relative. A path deleted since [base]
    // is only in its tree. Either match withholds an origin, which is the safe direction.
    private fun tracked(rootDir: File, base: String?, paths: Collection<String>): Set<String>? {
        val prefix = ChangeDetection.rawGit(rootDir, listOf("rev-parse", "--show-prefix"))
            ?.trimEnd('\n') ?: return null
        val listed = ChangeDetection.rawGit(
            rootDir,
            listOfNotNull("ls-files", "-z", "--full-name", base?.let { "--with-tree=$it" }, "--") +
                paths.flatMap { listOf(":(top,literal)$prefix$it", ":(top,literal)$it") }.distinct(),
        )?.split('\u0000')?.filter(String::isNotEmpty)?.toSet() ?: return null
        return paths.filterTo(mutableSetOf()) { "$prefix$it" in listed || it in listed }
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

    /** One line per origin, at most [NAMED] paths each, then how many other paths force too. */
    fun lines(task: String, classified: Classified): List<String> {
        if (classified.origins.isEmpty()) return emptyList()
        val byOrigin = classified.origins.entries.groupBy({ it.value }, { it.key })
        return buildList {
            byOrigin[Origin.WRITTEN_DURING_CAPTURE]?.let { paths ->
                val named = named(paths) { "$it (${moved(classified.moves[it])})" }
                add(
                    "$named changed during $task's last capture build (by its tasks or its tests), so a " +
                        "selecting run sees ${them(paths)} changed and runs everything. If a test writes " +
                        "such a file and no test, the writer included, ever reads it back, have it write " +
                        "the file into the project's build directory (Derby: the derby.stream.error.file " +
                        "system property). If a test reads it, or a build task writes it, moving it hides " +
                        "its changes from selection; see $REFERENCE."
                )
            }
            byOrigin[Origin.GENERATED_IN_SOURCES]?.let { paths ->
                val dirs = paths.take(NAMED).mapNotNull { classified.sourceDirOf[it] }.distinct()
                add(
                    "${named(paths) { it }} ${be(paths)} untracked and in source " +
                        "${if (dirs.size == 1) "directory" else "directories"} ${dirs.joinToString(", ")}, " +
                        "so on the classpath (generated by the build, or not yet added to git); tests can " +
                        "read ${them(paths)}, so a change to ${them(paths)} runs everything."
                )
            }
            byOrigin[Origin.UNTRACKED]?.let { paths ->
                add(
                    "${named(paths) { it }} ${be(paths)} untracked or ignored and " +
                        "${if (paths.size == 1) "counts" else "count"} by content; see $REFERENCE."
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

    private fun named(paths: List<String>, name: (String) -> String): String =
        paths.take(NAMED).joinToString(", ", transform = name) +
            if (paths.size > NAMED) " and ${paths.size - NAMED} more" else ""

    private fun moved(move: WorkingTree.Move?) = when (move) {
        WorkingTree.Move.CREATED -> "created"
        WorkingTree.Move.DELETED -> "deleted"
        else -> "rewritten"
    }

    private fun them(paths: List<String>) = if (paths.size == 1) "it" else "them"

    private fun be(paths: List<String>) = if (paths.size == 1) "is" else "are"
}
