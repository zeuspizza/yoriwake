package io.github.zeuspizza.yoriwake.gradle.change

import java.io.File

/**
 * Names each changed source file by the classes the build compiled from it, once that output
 * exists. [ChangeDetection.split] reads names from the text, where a nested or local type comes
 * out flattened, a typealias or a word in a comment as a class that does not exist, and a renamed
 * or multifile facade not at all; the map has no coverage under a name no class carries, so each
 * such name would run every test.
 */
internal object CompiledNames {

    /** What [resolve] changed, for the step's log. */
    data class Resolved(val change: ChangeDetection.Change, val replaced: Int, val dropped: Int)

    /**
     * [change] with each listed path's names replaced by its compiled classes' ([namesFor]). [listed]
     * answers a path's classes, or null when they cannot be listed, which keeps the path as
     * [ChangeDetection.split] left it.
     */
    fun resolve(
        change: ChangeDetection.Change,
        projectDir: File,
        listed: (path: String, packageName: String, fileName: String) -> List<String>?,
    ): Resolved {
        fun classesOf(path: String): List<String>? {
            val packageName = ChangeDetection.packageOf(projectDir, path) ?: return null
            return listed(path, packageName, path.substringAfterLast('/'))?.takeIf { it.isNotEmpty() }
        }
        var replaced = 0
        var dropped = 0
        val inlineSources = change.inlineSources.toMutableSet()
        val byPath = change.prefixesByPath.mapValuesTo(linkedMapOf()) { (path, names) ->
            val classes = classesOf(path) ?: return@mapValuesTo names
            val resolved = namesFor(path, names, classes)
            if (resolved != names) replaced++
            dropped += (names - resolved).size
            ChangeDetection.pathName(path)?.takeIf { path.endsWith(".kt") && it !in resolved }?.let(inlineSources::add)
            resolved
        }
        // An inline-only file stays one only under a name a class carries; otherwise its class is judged.
        val prefixes = byPath.values.flatten().toSet()
        return Resolved(
            change.copy(
                classPrefixes = prefixes,
                inlineOnly = change.inlineOnly.intersect(prefixes),
                prefixesByPath = byPath,
                inlineSources = inlineSources,
            ),
            replaced, dropped,
        )
    }

    /**
     * The names of one file whose compiled [classes] are known: every class carrying a name it
     * declares (`pkg.Name`, or a nested or local class whose own name is it), its path name when its
     * class or file class was compiled, and the outermost name of any class none of these covers (a
     * `@file:JvmName` facade, a multifile part, the capitalised file class of a lowercase file).
     */
    fun namesFor(path: String, names: Set<String>, classes: List<String>): Set<String> {
        val pathName = ChangeDetection.pathName(path)
        val result = sortedSetOf<String>()
        for (name in names) {
            if (name == pathName) {
                if (name in classes || name + "Kt" in classes) result += name
                continue
            }
            val simple = name.substringAfterLast('.')
            result += classes.filter { carries(it, simple) }
        }
        for (cls in classes) {
            if (result.none { covers(cls, it) }) {
                result += cls.substringBeforeLast('.', "").let { if (it.isEmpty()) "" else "$it." } +
                    cls.substringAfterLast('.').substringBefore('$')
            }
        }
        return result
    }

    /** `pkg.Name`, `pkg.Outer$Name` or the local `pkg.Outer$1Name`. */
    private fun carries(binary: String, simple: String): Boolean {
        val own = binary.substringAfterLast('.')
        if (own == simple) return true
        if ('$' !in own) return false
        return own.substringAfterLast('$').trimStart { it.isDigit() } == simple
    }

    /** As the selector's `matchesSourceFile`: the class itself, its file class, or one nested in either. */
    private fun covers(binary: String, prefix: String): Boolean =
        binary == prefix || binary == prefix + "Kt" || binary.startsWith("$prefix$") || binary.startsWith("${prefix}Kt$")
}
