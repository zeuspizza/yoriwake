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

    /** The test JVM's feature version, null when nobody can tell. */
    fun testJvm(): Int?

    /** A lower agent ceiling the task hands its test JVM, for tests on installed JDKs; else null. */
    fun agentCeiling(): String?
}
