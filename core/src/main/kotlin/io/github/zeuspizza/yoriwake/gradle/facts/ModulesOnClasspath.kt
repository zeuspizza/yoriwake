package io.github.zeuspizza.yoriwake.gradle.facts

import java.io.File

/**
 * The modules whose output is reachable from this task, by Gradle path: dependency resolution and
 * file matching unioned, over runtime, compile and annotation-processor classpaths. Empty when
 * nothing could be established, which the caller reads as "drop nothing".
 */
internal fun modulesOnClasspath(facts: ClasspathView): Set<String> {
    val reachable = runCatching {
        facts.classpathFiles() + facts.compileTimeFiles()
    }.getOrElse { return emptySet() }
    if (reachable.isEmpty()) {
        return emptySet()
    }
    // Konsist reads every module's `src/` from the filesystem, so an off-classpath sibling is still
    // in its view. Drop nothing.
    if (reachable.any { it.name.startsWith("konsist") }) {
        return emptySet()
    }
    // "Could not be asked" answers the empty set, which ClasspathScope.restrict reads as "drop
    // nothing".
    val declared = facts.declaredModules() ?: return emptySet()
    val present = mutableSetOf<String>()
    present += declared
    facts.buildDirs.forEach { (path, buildDir) ->
        if (reachable.any { it.absolutePath.startsWith(buildDir + File.separator) }) {
            present += path
        }
    }
    present += facts.ownPath
    return present
}
