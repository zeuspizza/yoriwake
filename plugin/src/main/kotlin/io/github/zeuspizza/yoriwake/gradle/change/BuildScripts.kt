package io.github.zeuspizza.yoriwake.gradle.change

import java.io.File

// Build-script detection, apart from selection: a changed path a script names is read by the
// build even when no compiled class names it.

/** Directories no build script lives in, skipped rather than walked for them. */
private val NOT_BUILD_LOGIC = setOf("build", ".gradle", ".git", "node_modules", ".idea")

/**
 * Which changed paths a build script names.
 *
 * A file no compiled class names may still be a code-generation input (`openapi.yaml`, `.proto`).
 * A script that cannot be read counts as naming every path.
 */
internal fun namedInBuildScripts(rootDir: File, paths: Collection<String>): Set<String> {
    if (paths.isEmpty()) return emptySet()
    // No depth limit, and build logic's own sources count: convention plugins under buildSrc or
    // an included build are Kotlin sources. Only non-build-logic `src` trees and build output are
    // skipped.
    val scripts = rootDir.walkTopDown()
        .onEnter { dir ->
            dir == rootDir || (dir.name !in NOT_BUILD_LOGIC &&
                (dir.name != "src" || isBuildLogic(rootDir, dir)))
        }
        .filter { file ->
            file.isFile && (
                file.name.endsWith(".gradle") || file.name.endsWith(".gradle.kts") ||
                    file.name.endsWith(".versions.toml") || file.name == "gradle.properties" ||
                    ((file.name.endsWith(".kt") || file.name.endsWith(".java") ||
                        file.name.endsWith(".groovy")) && isBuildLogic(rootDir, file))
                )
        }
        .toList()
    // Each path and every directory above it, since a script names `testdata`, not the file
    // inside it. Mirrors `TaskArtifacts.pathsNamedInClasses`: change both or neither.
    val needlesByPath = paths.associateWith { path ->
        val forms = mutableSetOf(path, path.substringAfterLast('/'))
        var parent = path.substringBeforeLast('/', "")
        while (parent.isNotEmpty()) {
            forms += parent
            forms += parent.substringAfterLast('/')
            parent = parent.substringBeforeLast('/', "")
        }
        forms.filter { it.isNotEmpty() }
    }
    val found = mutableSetOf<String>()
    scripts.forEach { script ->
        val text = runCatching { script.readText() }.getOrNull()
        if (text == null) {
            return paths.toSet()
        }
        needlesByPath.forEach { (path, needles) ->
            if (path !in found && needles.any { text.contains(it) }) {
                found += path
            }
        }
    }
    return found
}

/**
 * Whether a file sits in build logic: `buildSrc`, or an included build -- a directory below the
 * root carrying its own settings file.
 */
private fun isBuildLogic(rootDir: File, file: File): Boolean {
    var dir: File? = file.parentFile
    while (dir != null && dir != rootDir) {
        if (dir.name == "buildSrc" ||
            File(dir, "settings.gradle.kts").isFile || File(dir, "settings.gradle").isFile
        ) {
            return true
        }
        dir = dir.parentFile
    }
    return false
}
