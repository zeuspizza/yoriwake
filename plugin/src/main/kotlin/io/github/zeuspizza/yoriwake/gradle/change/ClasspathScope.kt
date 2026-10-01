package io.github.zeuspizza.yoriwake.gradle.change

import java.io.File

/**
 * Drops changed files belonging to modules that are not in this task's JVM, unless a test can name
 * them.
 *
 * A module not on the classpath is not in the JVM, but a test can still read its files by path, so
 * a path drops only when no compiled class of this task names it. Only files under an absent
 * module's `src` may drop; build scripts and everything else keep forcing.
 */
internal object ClasspathScope {

    /** What the restriction did, so the run can report it rather than silently shrinking a diff. */
    data class Restriction(val kept: List<String>, val dropped: List<String>)

    /**
     * Keeps every path except source files of modules absent from the classpath that no class names.
     *
     * @param paths repository-relative, forward-slashed
     * @param moduleDirs repository-relative directory of each module, mapped to its Gradle path.
     *   The root project appears as `""` and never owns a path by itself.
     * @param present Gradle paths of the modules whose output is on this task's classpath. Empty
     *   means the classpath could not be established, and nothing is dropped.
     * @param namedInClasses which of the candidate paths this task's compiled classes name. Null or
     *   a throw means it could not be asked, and nothing is dropped.
     */
    fun restrict(
        paths: Collection<String>,
        moduleDirs: Map<String, String>,
        present: Set<String>,
        namedInClasses: (Collection<String>) -> Set<String>?,
    ): Restriction {
        if (present.isEmpty()) {
            return Restriction(paths.toList(), emptyList())
        }
        val candidates = paths.filterTo(mutableSetOf()) { path ->
            val owner = ownerOf(path, moduleDirs)
            owner != null && owner.second !in present && isUnderSourceRoot(path, owner.first)
        }
        if (candidates.isEmpty()) {
            return Restriction(paths.toList(), emptyList())
        }
        val named = runCatching { namedInClasses(candidates) }.getOrNull()
            ?: return Restriction(paths.toList(), emptyList())
        val (dropped, kept) = paths.partition { it in candidates && it !in named }
        return Restriction(kept, dropped)
    }

    /**
     * Paths that no part of the build reads, and no compiled class can name (e.g. CI workflow files).
     * A test can only read a file it can name.
     *
     * @param namedInClasses paths some class file mentions
     * @param namedInBuildScripts paths a build script mentions; these may be code-generation inputs
     *   (an `openapi.yaml`, a `.proto`) that no class names, so they keep forcing.
     */
    fun unreadable(
        paths: Collection<String>,
        namedInClasses: Set<String>,
        namedInBuildScripts: Set<String> = emptySet(),
    ): Set<String> =
        paths.filterTo(mutableSetOf()) { path ->
            path !in namedInClasses && path !in namedInBuildScripts && !isBuildInput(path)
        }

    /** Whether the build itself reads this path. Deliberately broad. */
    private fun isBuildInput(path: String): Boolean {
        val name = path.substringAfterLast('/')
        return path.contains("/src/") || path.startsWith("src/") ||
            name.endsWith(".gradle") || name.endsWith(".gradle.kts") ||
            name == "gradle.properties" || name == "settings.gradle" || name == "settings.gradle.kts" ||
            name.endsWith(".versions.toml") ||
            path.startsWith("gradle/") || path.contains("/gradle/") ||
            name == "gradlew" || name == "gradlew.bat"
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
     * Whether the path is a source or resource file of that module. A module with another layout
     * has its files kept, which is the safe direction.
     */
    private fun isUnderSourceRoot(path: String, moduleDir: String): Boolean =
        path.startsWith("$moduleDir/src/")
}
