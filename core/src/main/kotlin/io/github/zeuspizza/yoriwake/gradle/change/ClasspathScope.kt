package io.github.zeuspizza.yoriwake.gradle.change

/**
 * Drops changed class sources belonging to modules that are not in this task's JVM, unless a test
 * can name them.
 *
 * A module not on the classpath puts no class in the JVM, but a test can still read its files by
 * path, so a source drops only when no compiled class of this task names it. Only a Java or Kotlin
 * source under an absent module's `src` may drop. Its other files keep forcing, like build scripts
 * and everything else: a test or the build can read a resource without naming it, found by a
 * pattern, by walking a directory, or handed over by path.
 */
internal object ClasspathScope {

    /** What the restriction did, so the run can report it rather than silently shrinking a diff. */
    data class Restriction(val kept: List<String>, val dropped: List<String>)

    /**
     * Keeps every path except class sources of modules absent from the classpath that no class names.
     *
     * @param paths repository-relative, forward-slashed
     * @param moduleDirs repository-relative directory of each module, mapped to its module path
     *   (its Gradle path, or its Maven project id).
     *   The root project appears as `""` and never owns a path by itself.
     * @param present module paths of the modules whose output is on this task's classpath. Empty
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
            owner != null && owner.second !in present && isClassSource(path, owner.first)
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
