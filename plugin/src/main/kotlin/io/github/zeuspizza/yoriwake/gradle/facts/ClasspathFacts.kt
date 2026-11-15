package io.github.zeuspizza.yoriwake.gradle.facts

import io.github.zeuspizza.yoriwake.gradle.YoriwakePlugin
import io.github.zeuspizza.yoriwake.gradle.change.ClasspathFiles
import io.github.zeuspizza.yoriwake.gradle.change.ClasspathScope
import io.github.zeuspizza.yoriwake.gradle.wiring.cacheDir
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
    /**
     * Modules Gradle's resolution names; null means "could not be asked", not "none". The modules
     * the task reaches by [handed] and [closure] are added to it as [declaredModules] answers.
     */
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
    /** Plain directories taken at configuration, so nothing resolves or reads a jar then. */
    override val dependencyHomes: Map<String, File> = emptyMap(),
    /** Gradle path to the project's build file, repository-relative. */
    override val buildFiles: Map<String, String> = emptyMap(),
    /** See [ClasspathView.handedPaths]; absent, or null here, when it cannot be told. */
    val handed: org.gradle.api.provider.Provider<Set<File>>? = null,
    /** See [ClasspathView.closureModules]; absent, or null here, when it cannot be told. */
    val closure: org.gradle.api.provider.Provider<Set<String>>? = null,
) : ClasspathView {
    override fun classpathFiles(): Set<File> = classpath.files

    override fun testOutputFiles(): Set<File> = testOutputs.files

    override fun compileTimeFiles(): Set<File> = compileTime.files

    /**
     * [declared] with the modules the task reaches by a path it hands its test JVM or a task it
     * depends on, as Maven counts the modules its fork values name; null when any part is unknown.
     */
    override fun declaredModules(): Set<String>? {
        val named = declared.getOrNull() ?: return null
        return named + (reachedModules() ?: return null)
    }

    override fun jacocoVersion(): String? = jacocoVersion?.let { it.readOrNull() ?: "" }

    override fun jacocoSetting(): String = "`jacoco.toolVersion`"

    override fun testJvm(): Int? = testJvm.readOrNull()

    override fun agentCeiling(): String? = agentCeiling.readOrNull()

    override fun handedPaths(): Set<File>? = handed.readOrNull()

    override fun closureModules(): Set<String>? = closure.readOrNull()
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
    // What `declared` asks, for the facts read beside it.
    val reaches = changedPaths == null || reachesOutsideModule(
        project.projectDir.relativeTo(project.rootDir).invariantSeparatorsPath.trim('/'), ownPath, changedPaths, facts.moduleDirs,
    )
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
            withSourceRootModules(project, facts.moduleDirs, resolvedProjectDependencies(project))
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
    dependencyHomes = dependencyHomes(project),
    buildFiles = facts.buildFiles,
    // Read when the change set reaches outside this module, as `declared` is.
    handed = project.provider { if (reaches) handedPaths(test) else emptySet() },
    closure = if (reaches) dependencyClosure(project, test, facts.moduleDirs) else project.provider { emptySet<String>() },
    )
}

/** What [test] hands its test JVM by path; see [ClasspathView.handedPaths]. */
internal fun handedPaths(test: Test): Set<File> {
    val workingDir = test.workingDir
    val values = io.github.zeuspizza.yoriwake.gradle.capture.JvmArgFiles.expand(test.allJvmArgs, workingDir) +
        test.environment.values.map { it?.toString().orEmpty() }
    return handedPathTokens(values, workingDir) + workingDir + test.inputs.files.files
}

/**
 * The modules owning a task [test] depends on, directly or not, and those holding a file such a
 * task reads (a copy from a sibling's `src`), as the task graph has them once it is ready: before
 * the configuration cache stores its entry, and never from a task action. A task not in the graph,
 * as the test task is when only `yoriwakeExplain` runs, is asked for its own dependencies then.
 * Unset, so unknown, when the graph never becomes ready for it or a walk fails.
 */
internal fun dependencyClosure(
    project: Project,
    test: Test,
    moduleDirs: Map<String, String>,
): org.gradle.api.provider.Provider<Set<String>> {
    val closure = project.objects.setProperty(String::class.java)
    closure.set(null as Iterable<String>?)
    val build = project.rootProject
    val rootDir = project.rootDir
    project.gradle.taskGraph.whenReady { graph ->
        runCatching {
            val read = mutableSetOf<File>()
            modulesDependedOn(test, build) { task ->
                if (task != test) read += task.inputs.files.files
                if (graph.hasTask(task)) graph.getDependencies(task) else task.taskDependencies.getDependencies(task)
            } + modulesHolding(read, rootDir, moduleDirs)
        }.getOrNull()?.let { closure.set(it) }
    }
    return closure
}

/** The modules of [build] owning a task [test] depends on, through [dependencies], directly or not. */
internal fun modulesDependedOn(
    test: org.gradle.api.Task,
    build: Project,
    dependencies: (org.gradle.api.Task) -> Set<org.gradle.api.Task>,
): Set<String> {
    val seen = mutableSetOf<org.gradle.api.Task>()
    val pending = ArrayDeque(listOf(test))
    while (pending.isNotEmpty()) {
        dependencies(pending.removeFirst()).forEach { if (seen.add(it)) pending += it }
    }
    // A task of another build has a project of that build, whose paths are not this build's modules.
    return seen.mapNotNullTo(mutableSetOf()) { task ->
        runCatching { task.project.takeIf { it.rootProject == build }?.path }.getOrNull()
    }
}

/** Where a Gradle test classpath takes dependencies from; see [ClasspathView.dependencyHomes]. */
internal fun dependencyHomes(project: Project): Map<String, File> = mapOf(
    ClasspathFiles.GRADLE_USER_HOME to project.gradle.gradleUserHomeDir,
    ClasspathFiles.PROJECT_CACHE_DIR to project.cacheDir(),
    ClasspathFiles.USER_HOME to File(System.getProperty("user.home").orEmpty()),
)

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
 * [declared] with every module whose directory holds, or lies in, a source directory of [project]
 * or of a module already named, until no new one is named: a sibling's sources compiled in that
 * way end up in classes whose source file names no path to them. Null when either side is unknown.
 */
internal fun withSourceRootModules(project: Project, moduleDirs: Map<String, String>, declared: Set<String>?): Set<String>? {
    val named = (declared ?: return null).toMutableSet()
    val dirs = moduleDirs.map { (dir, path) -> project.rootDir.resolve(dir).toPath().normalize() to path }
    val unread = ArrayDeque(listOf(project.path))
    val read = mutableSetOf<String>()
    while (unread.isNotEmpty()) {
        val path = unread.removeFirst()
        if (!read.add(path)) continue
        // A module of another build is not in [moduleDirs], so none of its paths can drop.
        val module = project.rootProject.findProject(path) ?: continue
        for (source in sourceDirectories(module) ?: return null) {
            val root = source.toPath().normalize()
            dirs.filter { (dir, _) -> root.startsWith(dir) || dir.startsWith(root) }.forEach { (_, holder) -> named += holder }
        }
        unread += named - read
    }
    return named
}

/**
 * Every source and resource directory of [project]'s source sets: Java's, and those the Kotlin and
 * Android plugins keep, read by name since neither is on this plugin's classpath. Null when one
 * cannot be read, or when either plugin's sets yield no directory, so a renamed property never
 * reads as "no directories".
 */
internal fun sourceDirectories(project: Project): List<File>? = runCatching {
    val found = mutableListOf<File>()
    project.extensions.findByType(org.gradle.api.tasks.SourceSetContainer::class.java)?.forEach { found += it.allSource.srcDirs }
    listOf("kotlin", "android").mapNotNull { project.extensions.findByName(it) }.forEach { extension ->
        val sets = getter(extension, "getSourceSets") as? Iterable<*> ?: return null
        val dirs = sets.flatMap { set ->
            listOf("getKotlin", "getJava", "getResources").flatMap { kind ->
                getter(set!!, kind)?.let { getter(it, "getSrcDirs") as Set<*> }.orEmpty().map { it as File }
            }
        }
        if (dirs.isEmpty()) return null
        found += dirs
    }
    found
}.getOrNull()

/** What [target]'s public getter [name] returns, or null when it has none. */
private fun getter(target: Any, name: String): Any? {
    val method = runCatching { target.javaClass.getMethod(name) }.getOrNull() ?: return null
    method.trySetAccessible()
    return method.invoke(target)
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
