package io.github.zeuspizza.yoriwake.gradle.bytecode

import java.io.File

/**
 * The set of packages a build owns, used as JaCoCo's instrumentation scope.
 *
 * Left open, JaCoCo instruments the whole classpath and capture gets expensive; too narrow, and a
 * class outside the scope is invisible to the map and forces a full run.
 *
 * Packages come from the sources' `package` declarations, not directory shape: a source set rooted
 * at `src` would otherwise yield `main.java.com.acme`, which matches nothing.
 */
internal object InstrumentationScope {

    /** Bounds the symlink-cycle case; no real source tree is anywhere near this deep. */
    private const val MAX_DEPTH = 64

    /**
     * The JVM source languages whose `package` declaration reads like Java's. A module in any other
     * language derives no packages, so its task declines to capture.
     */
    private val SOURCE_EXTENSIONS = setOf("kt", "java", "groovy", "scala")

    /**
     * Source files that declare no type. Without this, `module-info.java` (no `package` line) would
     * read as a default-package class and widen every modular build's scope to `*`.
     */
    private val DECLARES_NO_TYPE = setOf("module-info.java")

    private val PACKAGE_DECLARATION =
        Regex("""^\s*package\s+([A-Za-z_][A-Za-z0-9_]*(?:\s*\.\s*[A-Za-z_][A-Za-z0-9_]*)*)""")

    private val WHITESPACE = Regex("""\s+""")

    /**
     * Reads the distinct package roots declared beneath these source directories.
     *
     * A package is dropped only when another declared package is its ancestor. Siblings are not
     * merged into a common parent, which would widen the scope to packages the build never declared.
     */
    fun derive(sourceDirs: Collection<File>, onFilesOpened: (Int) -> Unit = {}): Set<String> {
        var opened = 0
        val packages = sourceDirs
            .filter(File::isDirectory)
            .flatMap { declaredPackages(it) { opened++ } }
            .toSet()
        // Reported to the caller, which cannot otherwise see how many files configuration opened.
        onFilesOpened(opened)
        return collapseToRoots(packages)
    }

    private fun declaredPackages(root: File, onOpen: () -> Unit): List<String> {
        val found = mutableListOf<String>()
        walk(root, depth = 0) { file ->
            onOpen()
            when (val scan = packageOf(file)) {
                // Code with no package declaration lives in the default package; only `*` covers it.
                is PackageScan.DefaultPackage -> found += DEFAULT_PACKAGE
                is PackageScan.Declared -> found += scan.name
                // An empty or wholly commented-out file compiles to no class, so it must not widen
                // the scope to `*`. An unreadable file is not this case: it may declare a class.
                is PackageScan.DeclaresNothing -> Unit
            }
        }
        return found
    }

    /** What one source file said about its package, and whether it said anything at all. */
    private sealed interface PackageScan {
        data class Declared(val name: String) : PackageScan

        /** Code, but no `package` line: the default package. */
        object DefaultPackage : PackageScan

        /** No package line and no code: an empty or wholly commented-out file. */
        object DeclaresNothing : PackageScan
    }

    /** Depth-bounded so a directory symlink cycle cannot spin the configuration phase forever. */
    private fun walk(dir: File, depth: Int, onSource: (File) -> Unit) {
        if (depth > MAX_DEPTH) return
        val entries = dir.listFiles() ?: return
        entries.forEach { entry ->
            when {
                entry.isDirectory -> walk(entry, depth + 1, onSource)
                entry.name in DECLARES_NO_TYPE -> Unit
                entry.extension in SOURCE_EXTENSIONS -> onSource(entry)
            }
        }
    }

    /**
     * What a source file says about its package: a declared name, the default package, or nothing.
     */
    private fun packageOf(file: File): PackageScan =
        runCatching { file.useLines { lines -> declaredPackage(lines) } }
            // An unreadable file may declare a class, so it widens rather than narrows the scope.
            .getOrDefault(PackageScan.DefaultPackage)

    /**
     * The first package declaration in a file, reading past comments; [PackageScan.DefaultPackage]
     * when it holds code and no declaration, [PackageScan.DeclaresNothing] when it holds no code.
     *
     * No line bound: a licence header can push the declaration past line 200, and one default
     * package collapses the whole build's scope to `*`. Comments are stripped, not skipped by shape,
     * so prose like `package private helpers` in a header cannot match. Multi-line file annotations
     * mean no earlier line proves a declaration will not follow.
     */
    private fun declaredPackage(lines: Sequence<String>): PackageScan {
        var inBlockComment = false
        var sawCode = false
        for (line in lines) {
            val code = StringBuilder()
            var i = 0
            while (i < line.length) {
                when {
                    inBlockComment && line.startsWith("*/", i) -> { inBlockComment = false; i += 2 }
                    inBlockComment -> i++
                    line.startsWith("/*", i) -> { inBlockComment = true; i += 2 }
                    line.startsWith("//", i) -> i = line.length
                    else -> code.append(line[i++])
                }
            }
            if (code.isNotBlank()) sawCode = true
            PACKAGE_DECLARATION.find(code)?.let {
                return PackageScan.Declared(it.groupValues[1].replace(WHITESPACE, ""))
            }
        }
        return if (sawCode) PackageScan.DefaultPackage else PackageScan.DeclaresNothing
    }

    /** Reduces a package set to the shortest prefixes covering it. */
    private fun collapseToRoots(packages: Set<String>): Set<String> {
        if (DEFAULT_PACKAGE in packages) return setOf(DEFAULT_PACKAGE)
        val sorted = packages.sorted()
        val roots = mutableListOf<String>()
        sorted.forEach { candidate ->
            if (roots.none { candidate == it || candidate.startsWith("$it.") }) {
                roots += candidate
            }
        }
        return roots.toSet()
    }

    /**
     * Turns package roots into JaCoCo include patterns.
     *
     * Returns null when nothing was found, rather than throwing: a module with tests but no main
     * sources is legitimate. The caller decides what an absent scope means.
     */
    fun toIncludePatterns(packageRoots: Set<String>): List<String>? = when {
        packageRoots.isEmpty() -> null
        DEFAULT_PACKAGE in packageRoots -> listOf("*")
        else -> packageRoots.sorted().map { "$it.*" }
    }

    /** Marker for "sources live in the default package", where no narrowing is possible. */
    const val DEFAULT_PACKAGE = "<default>"
}
