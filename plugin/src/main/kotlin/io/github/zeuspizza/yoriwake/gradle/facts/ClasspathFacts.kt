package io.github.zeuspizza.yoriwake.gradle.facts

import io.github.zeuspizza.yoriwake.gradle.YoriwakePlugin
import io.github.zeuspizza.yoriwake.gradle.change.ClasspathScope
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import java.io.File

// What a task's classpath says about which modules a change can reach, held in
// configuration-cache-safe form.

/**
 * Everything the classpath rule needs, in a form a task action may hold: plain values and
 * providers only, because capturing a Project fails the build under the configuration cache.
 */
internal data class ClasspathFacts(
    override val rootDir: File,
    /** Repository-relative module directory to Gradle path, for every module in the build. */
    override val moduleDirs: Map<String, String>,
    /** Gradle path to build directory, so a classpath entry can be traced back to its module. */
    override val buildDirs: Map<String, String>,
    /** Modules Gradle's resolution names; null means "could not be asked", not "none". */
    val declared: org.gradle.api.provider.Provider<Set<String>?>,
    /** Compile and annotation-processor classpaths, which the test runtime classpath omits. */
    val compileTime: org.gradle.api.file.FileCollection,
    /** The module under test, which is present whether or not it has produced output yet. */
    override val ownPath: String,
    /** This task's own compiled test classes, for the rule about changed test classes. */
    val testOutputs: org.gradle.api.file.FileCollection,
    /** The runtime classpath, held here because a Task reference cannot be configuration-cached. */
    val classpath: org.gradle.api.file.FileCollection,
    /**
     * The project's `jacoco.toolVersion`, absent when it cannot be read. Null only in tests of
     * other rules, which then leave JaCoCo's ceiling out.
     */
    val jacocoVersion: org.gradle.api.provider.Provider<String>? = null,
    /** The test JVM's feature version as its launcher reports it, absent when nobody can tell. */
    val testJvm: org.gradle.api.provider.Provider<Int>? = null,
    /** A lower agent ceiling the task hands its test JVM, for tests on installed JDKs; else absent. */
    val agentCeiling: org.gradle.api.provider.Provider<String>? = null,
) : ClasspathView {
    override fun classpathFiles(): Set<File> = classpath.files

    override fun testOutputFiles(): Set<File> = testOutputs.files

    override fun compileTimeFiles(): Set<File> = compileTime.files

    override fun declaredModules(): Set<String>? = declared.getOrNull()

    override fun jacocoVersion(): String? = jacocoVersion?.let { it.readOrNull() ?: "" }

    override fun jacocoSetting(): String = "`jacoco.toolVersion`"

    override fun testJvm(): Int? = testJvm.readOrNull()

    override fun agentCeiling(): String? = agentCeiling.readOrNull()
}

/** A provider's value, or null when it is absent, unset or throws. */
private fun <T : Any> org.gradle.api.provider.Provider<T>?.readOrNull(): T? =
    this?.let { runCatching { it.orNull }.getOrNull() }

/**
 * Whether the change set touches anything this module does not own.
 *
 * Ownership is the longest matching module directory, as in [ClasspathScope], never a raw path
 * prefix: nested modules (`:a`, `:a:b`) would otherwise drop `:a:b`'s changes as off-classpath.
 * Every uncertainty answers true: a root project at the repository root, or a path no module owns.
 */
internal fun reachesOutsideModule(
    ownDir: String,
    ownPath: String,
    changedPaths: Collection<String>,
    moduleDirs: Map<String, String>,
): Boolean =
    ownDir.isEmpty() || changedPaths.any { raw ->
        ClasspathScope.ownerOf(raw.normalizeSeparators(), moduleDirs)?.second != ownPath
    }

/**
 * `\` to `/` on every host, since a change set may come from another OS. A POSIX name that really
 * contains a backslash is misattributed, but git quotes such names rather than emitting them raw.
 */
internal fun String.normalizeSeparators(): String = replace('\\', '/')

/**
 * @param changedPaths the change set, used only to decide whether the expensive part is needed.
 *   Null means unknown and resolves the complete answer. Empty lets `declared` come back empty,
 *   which is safe only for callers whose facts never reach [modulesOnClasspath]. No default: the
 *   choice belongs at the call site.
 */
internal fun classpathFacts(
    project: Project,
    test: Test,
    changedPaths: Collection<String>?,
): ClasspathFacts {
    val memo = BuildMemo.of(project)
    // Counted, not timed: the walk inside `projectFacts` is timed, and nested timers would
    // overlap.
    memo?.count(YoriwakePlugin.CLASSPATH_FACTS_COUNTER)
    val facts = projectFacts(project, memo)
    val ownPath = project.path
    return ClasspathFacts(
    rootDir = project.rootDir,
    moduleDirs = facts.moduleDirs,
    buildDirs = facts.buildDirs,
    // Resolved only when the change set reaches outside this module: Gradle resolves it while
    // storing the configuration-cache entry, which is the most expensive thing the selecting path
    // does. A smaller `declared` drops more changes, so ownership follows
    // [reachesOutsideModule].
    declared = project.provider {
        val ownDir = project.projectDir.relativeTo(project.rootDir).invariantSeparatorsPath.trim('/')
        // Null is unknown and resolves: `yoriwakeExplain` computes its change set in its own
        // action.
        if (changedPaths == null ||
            reachesOutsideModule(ownDir, ownPath, changedPaths, facts.moduleDirs)
        ) {
            resolvedProjectDependencies(project)
        } else {
            emptySet()
        }
    },
    compileTime = project.files(
        project.provider {
            project.tasks.withType(org.gradle.api.tasks.compile.JavaCompile::class.java).flatMap { compile ->
                listOfNotNull(compile.classpath, compile.options.annotationProcessorPath)
            }
        }
    ),
    ownPath = ownPath,
    testOutputs = test.testClassesDirs,
    classpath = test.classpath,
    jacocoVersion = project.provider {
        project.extensions.findByType(org.gradle.testing.jacoco.plugins.JacocoPluginExtension::class.java)?.toolVersion
    },
    // Absent, never thrown, when the launcher cannot be probed (an `executable` that is a script):
    // the configuration cache resolves this when it stores, and the test JVM then judges itself.
    testJvm = test.javaLauncher.let { launcher ->
        project.provider { runCatching { launcher.get().metadata.languageVersion.asInt() }.getOrNull() }
    },
    agentCeiling = test.systemProperties.let { properties ->
        project.provider { properties[io.github.zeuspizza.yoriwake.agent.contract.AgentContract.AGENT_ASM_CEILING_PROPERTY]?.toString() }
    },
    )
}

/**
 * Modules this project depends on, across every classpath that matters. Null when no
 * configuration could be asked; matched by shape because Android's classpaths are per-variant.
 */
internal fun resolvedProjectDependencies(project: Project): Set<String>? {
    // Declared dependencies first, without resolving: they cover kapt and KSP configurations the
    // classpath shapes below miss, and cannot under-report a dependency the build script states.
    // Resolution is added on top.
    val declared = runCatching {
        project.configurations.flatMap { configuration ->
            configuration.dependencies
                .filterIsInstance<org.gradle.api.artifacts.ProjectDependency>()
                .map { it.path }
        }
    }.getOrDefault(emptyList())

    val candidates = runCatching {
        project.configurations.filter { configuration ->
            configuration.isCanBeResolved && isClasspathLike(configuration.name)
        }
    }.getOrElse { return null }
    if (candidates.isEmpty()) {
        return declared.toSet().ifEmpty { null }
    }
    val paths = declared.toMutableSet()
    // Any failure makes the answer unknown: a module reachable only through the configuration that
    // failed would look absent, and absent licenses a drop.
    var complete = true
    candidates.forEach { configuration ->
        val ok = runCatching {
            configuration.incoming.resolutionResult.allComponents.forEach { component ->
                val id = component.id
                if (id is org.gradle.api.artifacts.component.ProjectComponentIdentifier) {
                    paths += id.projectPath
                }
            }
        }.isSuccess
        if (!ok) complete = false
    }
    return if (complete) paths else null
}

/**
 * Whether a configuration name is one whose resolution names the modules this project depends on.
 * Suffix matching covers every variant; resolving every configuration would be too costly.
 */
internal fun isClasspathLike(name: String): Boolean =
    name.endsWith("RuntimeClasspath", ignoreCase = true) ||
        name.endsWith("CompileClasspath", ignoreCase = true) ||
        name.endsWith("AnnotationProcessor", ignoreCase = true) ||
        name.endsWith("AnnotationProcessorClasspath", ignoreCase = true) ||
        // No common suffix for kapt and KSP configurations, so matched by prefix.
        name.startsWith("kapt", ignoreCase = true) ||
        name.startsWith("ksp", ignoreCase = true)
