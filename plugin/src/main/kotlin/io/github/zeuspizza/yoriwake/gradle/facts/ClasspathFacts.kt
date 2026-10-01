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
    val rootDir: File,
    /** Repository-relative module directory to Gradle path, for every module in the build. */
    val moduleDirs: Map<String, String>,
    /** Gradle path to build directory, so a classpath entry can be traced back to its module. */
    val buildDirs: Map<String, String>,
    /** Modules Gradle's resolution names; null means "could not be asked", not "none". */
    val declared: org.gradle.api.provider.Provider<Set<String>?>,
    /** Compile and annotation-processor classpaths, which the test runtime classpath omits. */
    val compileTime: org.gradle.api.file.FileCollection,
    /** The module under test, which is present whether or not it has produced output yet. */
    val ownPath: String,
    /** This task's own compiled test classes, for the rule about changed test classes. */
    val testOutputs: org.gradle.api.file.FileCollection,
    /** The runtime classpath, held here because a Task reference cannot be configuration-cached. */
    val classpath: org.gradle.api.file.FileCollection,
)

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
    )
}

/**
 * The modules whose output is reachable from this task, by Gradle path: dependency resolution and
 * file matching unioned, over runtime, compile and annotation-processor classpaths. Empty when
 * nothing could be established, which the caller reads as "drop nothing".
 */
internal fun modulesOnClasspath(facts: ClasspathFacts): Set<String> {
    val reachable = runCatching {
        facts.classpath.files + facts.compileTime.files
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
    val declared = facts.declared.getOrElse(null) ?: return emptySet()
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
