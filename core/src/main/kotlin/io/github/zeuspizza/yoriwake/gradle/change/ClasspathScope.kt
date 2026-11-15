package io.github.zeuspizza.yoriwake.gradle.change

/**
 * Drops changed files of modules that are not in this task's JVM, unless a test can reach them.
 *
 * A module not on the classpath puts no class in the JVM, but a test can still read its files by
 * path, so a Java or Kotlin source under an absent module's `src` drops only when no compiled class
 * of this task holds a path to it of two or more segments (`TaskArtifacts.Naming.CLASS_SOURCE`).
 *
 * On Gradle a module is absent only when, besides its output not being on the classpath, the task
 * neither hands its test JVM a path into it nor depends on a task of it
 * (`ClasspathView.reachedModules`).
 *
 * The module's build file and its other files under `src` drop only with more said of them
 * ([Others]): a test or the build can read a resource without naming its path, found by a pattern,
 * by walking a directory, or handed over by path, and a build script can configure another project.
 * So each drops only when no class or resource of the task names it, the task neither hands its
 * module's directory to the test JVM nor depends on a task of it, and no build script reaches
 * across ([BuildFileReach]). Every other file of the module keeps forcing.
 */
internal object ClasspathScope {

    /** What the restriction did, so the run can report it rather than silently shrinking a diff. */
    data class Restriction(
        val kept: List<String>,
        val dropped: List<String>,
        /** The part of [dropped] that is not a class source: build files and other `src` files. */
        val droppedFiles: List<String> = emptyList(),
    )

    /**
     * What a module's build file or a non-source file under its `src` must clear before it drops.
     * Each answer that cannot be had keeps every such file.
     */
    class Others(
        /** Module path to its build file, repository-relative. */
        val buildFiles: Map<String, String>,
        /**
         * The modules the task reaches outside its classpath; null when unknown. Asked only for a
         * candidate. Gradle counts them present too, so its class sources stay; Maven does not.
         */
        val reached: () -> Set<String>?,
        /** Which of these files a compiled class or a resource of the task names; null when it cannot be asked. */
        val named: (Collection<String>) -> Set<String>?,
        /** Why this file of that module (its build file when the flag is set) keeps forcing; null lets it drop. */
        val refusal: (path: String, module: String, buildFile: Boolean) -> String?,
        /** Whether a repository-relative directory holds a build of its own: a settings script. */
        val ownBuild: (String) -> Boolean,
    )

    /**
     * Keeps every path except class sources of modules absent from the classpath that no class
     * names, and, with [others], those modules' build files and `src` files that clear it.
     *
     * @param paths repository-relative, forward-slashed
     * @param moduleDirs repository-relative directory of each module, mapped to its module path
     *   (its Gradle path, or its Maven project id).
     *   The root project appears as `""` and never owns a path by itself.
     * @param present module paths of the modules whose output is on this task's classpath. Empty
     *   means the classpath could not be established, and nothing is dropped.
     * @param namedInClasses which of the candidate paths this task's compiled classes name. Null or
     *   a throw means it could not be asked, and nothing is dropped.
     * @param others what lets a build file or another `src` file drop; null keeps all of them.
     */
    fun restrict(
        paths: Collection<String>,
        moduleDirs: Map<String, String>,
        present: Set<String>,
        namedInClasses: (Collection<String>) -> Set<String>?,
        others: Others? = null,
    ): Restriction {
        if (present.isEmpty()) {
            return Restriction(paths.toList(), emptyList())
        }
        val candidates = paths.filterTo(mutableSetOf()) { path ->
            val owner = ownerOf(path, moduleDirs)
            owner != null && owner.second !in present && isClassSource(path, owner.first)
        }
        val dropped = mutableSetOf<String>()
        if (candidates.isNotEmpty()) {
            val named = runCatching { namedInClasses(candidates) }.getOrNull()
            if (named != null) dropped += candidates - named
        }
        val files = if (others == null) emptySet() else droppedOthers(paths, moduleDirs, present, others)
        val (gone, kept) = paths.partition { it in dropped || it in files }
        return Restriction(kept, gone, gone.filter { it in files })
    }

    /** The build files and other `src` files of absent modules that clear [others]. */
    private fun droppedOthers(
        paths: Collection<String>,
        moduleDirs: Map<String, String>,
        present: Set<String>,
        others: Others,
    ): Set<String> {
        val candidates = paths.mapNotNull { path ->
            val (dir, module) = ownerOf(path, moduleDirs) ?: return@mapNotNull null
            if (module in present || isClassSource(path, dir)) return@mapNotNull null
            val buildFile = path == others.buildFiles[module]
            if (!buildFile && !path.startsWith("$dir/src/")) return@mapNotNull null
            if (inOwnBuild(path, dir, others)) return@mapNotNull null
            Triple(path, module, buildFile)
        }
        if (candidates.isEmpty()) return emptySet()
        val reached = runCatching { others.reached() }.getOrNull() ?: return emptySet()
        val unreached = candidates.filter { (_, module, _) -> module !in reached }
        if (unreached.isEmpty()) return emptySet()
        val named = runCatching { others.named(unreached.map { it.first }) }.getOrNull() ?: return emptySet()
        return unreached.filter { (path, module, buildFile) ->
            path !in named && runCatching { others.refusal(path, module, buildFile) }.getOrDefault("it could not be read") == null
        }.mapTo(mutableSetOf()) { it.first }
    }

    /** Whether a directory from the module's own down to the file's holds a build of its own. */
    private fun inOwnBuild(path: String, moduleDir: String, others: Others): Boolean {
        var dir = path.substringBeforeLast('/', "")
        while (dir.length >= moduleDir.length) {
            if (runCatching { others.ownBuild(dir) }.getOrDefault(true)) return true
            dir = dir.substringBeforeLast('/', "")
            if (dir.isEmpty()) break
        }
        return false
    }

    /**
     * The module whose directory is the longest prefix of this path, so a present parent module
     * never vouches for an absent nested one, or the reverse.
     */
    internal fun ownerOf(path: String, moduleDirs: Map<String, String>): Pair<String, String>? =
        moduleDirs.entries
            .filter { (dir, _) -> dir.isNotEmpty() && (path == dir || path.startsWith("$dir/")) }
            .maxByOrNull { (dir, _) -> dir.length }
            ?.let { (dir, gradlePath) -> dir to gradlePath }

    /**
     * Whether the path is a Java or Kotlin source of that module, which compiles to classes. A
     * module with another layout has its files kept, which is the safe direction.
     */
    private fun isClassSource(path: String, moduleDir: String): Boolean =
        CLASS_SOURCE.matches(path.removePrefix("$moduleDir/"))

    private val CLASS_SOURCE = Regex("""^src/[^/]+/(?:java|kotlin)/.+\.(?:java|kt)$""")
}
