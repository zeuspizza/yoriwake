package io.github.zeuspizza.yoriwake.gradle.facts

import java.io.File

/**
 * What a task's classpath says about which modules a change can reach, as the shared rules read it.
 * Each front end backs it by its own build model; every function is asked only when a rule needs
 * its answer, and may cost a resolution then.
 */
internal interface ClasspathView {
    val rootDir: File

    /** Repository-relative module directory to module path, for every module in the build. */
    val moduleDirs: Map<String, String>

    /** Module path to build directory, so a classpath entry can be traced back to its module. */
    val buildDirs: Map<String, String>

    /**
     * Placeholder to directory for where the classpath takes dependencies from (the Gradle user
     * home, the project cache directory, the Maven local repository, the user's home), so the
     * classpath-files table names a dependency alike in another checkout or on another machine.
     */
    val dependencyHomes: Map<String, File>

    /** The module under test, which is present whether or not it has produced output yet. */
    val ownPath: String

    /** The test runtime classpath. */
    fun classpathFiles(): Set<File>

    /** This task's own compiled test classes. */
    fun testOutputFiles(): Set<File>

    /** Compile and annotation-processor classpaths, which the test runtime classpath omits. */
    fun compileTimeFiles(): Set<File>

    /** Modules the build's dependency resolution names; null means "could not be asked", not "none". */
    fun declaredModules(): Set<String>?

    /** The host's JaCoCo version, empty when unreadable; null when these facts leave JaCoCo out. */
    fun jacocoVersion(): String?

    /** Where the build sets [jacocoVersion], as the refusal and its remedy name it. */
    fun jacocoSetting(): String

    /** The test JVM's feature version, null when nobody can tell. */
    fun testJvm(): Int?

    /** A lower agent ceiling the task hands its test JVM, for tests on installed JDKs; else null. */
    fun agentCeiling(): String?

    /**
     * Module path to its build file, repository-relative and forward-slashed (a Gradle module's
     * build script, a Maven module's POM). A module it leaves out has no build file a rule may drop.
     */
    val buildFiles: Map<String, String> get() = emptyMap()

    /**
     * Every path the task hands its test JVM: in a JVM argument, an environment value, a declared
     * input, or as its working directory. Null when it cannot be told, or when these facts do not say.
     */
    fun handedPaths(): Set<File>? = null

    /**
     * The modules that own a task the test task depends on, directly or not, or hold a file such a
     * task reads. Null when it cannot be told, or when these facts do not say.
     */
    fun closureModules(): Set<String>? = null

    /**
     * The modules other than its own this task reaches outside its classpath: those owning a task
     * it depends on, and those a path it hands its test JVM lies in, or lies above strictly below
     * the repository root. The root, and anything above it, reaches nothing: every task hands one,
     * and a test that walks the repository from there is a documented limit. Null when either part
     * is unknown.
     */
    fun reachedModules(): Set<String>? {
        val closure = closureModules() ?: return null
        val handed = handedPaths() ?: return null
        val reached = closure.toMutableSet()
        reached += modulesHolding(handed, rootDir, moduleDirs)
        return reached - ownPath
    }
}

/**
 * The modules [paths] lie in, or lie above strictly below [rootDir]: a directory above a module
 * hands over every module under it, and the root or anything above it hands over none.
 */
internal fun modulesHolding(paths: Collection<File>, rootDir: File, moduleDirs: Map<String, String>): Set<String> {
    val root = rootDir.absoluteFile.toPath().normalize()
    val dirs = moduleDirs.filterKeys { it.isNotEmpty() }
        .map { (dir, module) -> root.resolve(dir).normalize() to module }
    val held = mutableSetOf<String>()
    paths.forEach { file ->
        val path = pathOf(file) ?: return@forEach
        dirs.forEach { (dir, module) ->
            if (path.startsWith(dir) || (dir.startsWith(path) && path.startsWith(root) && path != root)) {
                held += module
            }
        }
    }
    return held
}

/**
 * [file] as a normalised absolute path; a glob or another name the file system refuses is taken up
 * to its first such character, as a glob's directory part is still a path the test reads.
 */
private fun pathOf(file: File): java.nio.file.Path? {
    fun of(f: File) = runCatching { f.absoluteFile.toPath().normalize() }.getOrNull()
    return of(file) ?: of(File(file.path.substringBefore('*').substringBefore('?').takeWhile { it !in "<>|\"" }))
}

/**
 * The paths a JVM argument or an environment value can hand over: after the first `=` or `:` of an
 * option, split at the path separator, a `=` or a `,`, and at whitespace as well. A relative one is
 * resolved against [workingDir]. More pieces than paths only reaches more modules, which keeps more.
 * The plugin's own contract properties (`-Dyoriwake.`) hand over nothing a test reads.
 */
internal fun handedPathTokens(values: Collection<String>, workingDir: File): Set<File> {
    val tokens = mutableSetOf<File>()
    values.forEach { value ->
        if (value.startsWith("-D" + CONTRACT_PREFIX)) return@forEach
        val pieces = mutableSetOf(value)
        if (value.startsWith("-")) {
            if (':' in value) pieces += value.substringAfter(':')
            if ('=' in value) pieces += value.substringAfter('=')
        }
        pieces.flatMap { piece ->
            piece.split(File.pathSeparatorChar, '=', ',') + piece.split(File.pathSeparatorChar, '=', ',', ' ', '\t', '\n')
        }.map { it.trim().trim('"', '\'') }.filter { it.isNotEmpty() }.forEach { token ->
            val file = File(token)
            tokens += if (file.isAbsolute) file else File(workingDir, token)
        }
    }
    return tokens
}

private const val CONTRACT_PREFIX = "yoriwake."
