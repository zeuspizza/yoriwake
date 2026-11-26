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
    data class Resolved(val change: ChangeDetection.Change, val replaced: Int, val dropped: Int, val routed: Int)

    /**
     * [change] with each listed path's names replaced by its compiled classes' ([namesFor]). [listed]
     * answers a path's classes, or null when they cannot be listed, which keeps the path as
     * [ChangeDetection.split] left it: its names, or unmappable for a code-less file. A path name
     * no class carries stays when [readAsSource] answers that a test JVM read the file by that name:
     * such a read is a touch the selector matches by the path name alone.
     *
     * [movedFrom] pairs a moved Kotlin file's path with the path it moved from. When both are in
     * [change], only the new file's file class carries its path name, and [pairable] answers that no
     * class anywhere carries it and the map never held that file class, the name goes to
     * [ChangeDetection.Change.movedNames]: the move created it, and the tests of the old file's
     * classes are the ones that ran this code.
     */
    fun resolve(
        change: ChangeDetection.Change,
        projectDir: File,
        listed: (path: String, packageName: String, fileName: String) -> List<String>?,
        readAsSource: (pathNames: Collection<String>) -> Set<String>,
        movedFrom: Map<String, String> = emptyMap(),
        pairable: (pathName: String) -> Boolean = { false },
    ): Resolved {
        fun classesOf(path: String): List<String>? {
            val packageName = ChangeDetection.packageOf(projectDir, path) ?: return null
            return listed(path, packageName, path.substringAfterLast('/'))?.takeIf { it.isNotEmpty() }
        }
        var replaced = 0
        var dropped = 0
        val inlineSources = change.inlineSources.toMutableSet()
        val droppedPathNames = linkedMapOf<String, String>()
        val movedNames = linkedMapOf<String, String>()
        fun keepTrack(path: String, resolved: Set<String>) {
            val pathName = ChangeDetection.pathName(path)?.takeIf { it !in resolved } ?: return
            droppedPathNames[path] = pathName
            if (path.endsWith(".kt")) inlineSources += pathName
        }
        val byPath = change.prefixesByPath.mapValuesTo(linkedMapOf()) { (path, names) ->
            val classes = classesOf(path) ?: return@mapValuesTo names
            val resolved = namesFor(path, names, classes)
            val pathName = ChangeDetection.pathName(path)
            val old = movedFrom[path]?.takeIf { it in change.prefixesByPath }?.let(ChangeDetection::pathName)
            if (old != null && pathName != null && pathName in resolved && pathName !in classes && pairable(pathName)) movedNames[pathName] = old
            if (resolved != names) replaced++
            dropped += (names - resolved).size
            keepTrack(path, resolved)
            resolved
        }
        val unmappable = change.unmappablePaths.toMutableList()
        var inlinable = change.inlinableSourceChanged
        var routed = 0
        for ((path, names) in change.codeLess) {
            val classes = classesOf(path) ?: continue
            val resolved = namesFor(path, names, classes)
            unmappable.remove(path)
            byPath[path] = resolved
            inlinable = inlinable || ChangeDetection.inlinable(path)
            keepTrack(path, resolved)
            routed++
        }
        if (droppedPathNames.isNotEmpty()) {
            val read = readAsSource(droppedPathNames.values)
            for ((path, pathName) in droppedPathNames) {
                if (pathName in read) byPath[path] = byPath.getValue(path) + pathName
            }
        }
        // An inline-only file stays one only under a name a class carries; otherwise its class is judged.
        val prefixes = byPath.values.flatten().toSet()
        return Resolved(
            change.copy(
                classPrefixes = prefixes,
                unmappablePaths = unmappable,
                inlinableSourceChanged = inlinable,
                inlineOnly = change.inlineOnly.intersect(prefixes),
                prefixesByPath = byPath,
                codeLess = change.codeLess.filterKeys { it in unmappable },
                inlineSources = inlineSources,
                movedNames = movedNames,
            ),
            replaced, dropped, routed,
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
