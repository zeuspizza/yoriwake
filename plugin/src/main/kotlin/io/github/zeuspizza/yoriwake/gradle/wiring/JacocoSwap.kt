package io.github.zeuspizza.yoriwake.gradle.wiring

import io.github.zeuspizza.yoriwake.gradle.bytecode.ClassFileSupport
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.DependencySet
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.FileCollectionDependency
import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.plugins.JacocoPlugin
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import java.io.File

/**
 * Attaches the plugin's bundled JaCoCo agent in place of Gradle's default when a test JVM runs a
 * Java newer than that default officially instruments, so the classes it runs can be trusted and
 * selection narrows. Only while the build leaves `jacoco.toolVersion` at Gradle's default and
 * declares no agent of its own: a version the build chose is never replaced, and the refusal names
 * it instead. The host's report tasks keep `jacoco.toolVersion`.
 */
internal object JacocoSwap {

    private const val AGENT_CONFIGURATION = JacocoPlugin.AGENT_CONFIGURATION_NAME
    private const val DIRECTORY = "yoriwake-jacoco"
    private const val TASKS_KEY = "yoriwake.jacocoSwap.tasks"
    private const val DECIDED_KEY = "yoriwake.jacocoSwap.decided"
    private const val DEFAULT_KEY = "yoriwake.jacocoSwap.default"

    /** The bundled agent's JaCoCo version; null in a plugin build that bundles none. */
    val bundledVersion: String? by lazy {
        javaClass.classLoader.getResourceAsStream("$DIRECTORY/version")
            ?.use { it.readBytes().toString(Charsets.UTF_8).trim() }
            ?.takeIf { it.isNotEmpty() && javaClass.classLoader.getResource(resource(it)) != null }
    }

    private fun resource(version: String) = "$DIRECTORY/org.jacoco.agent-$version.jar"

    /** The task that puts the bundled agent in place, ahead of every test task that resolves it. */
    const val EXTRACT_TASK = "yoriwakeBundledJacocoAgent"

    /**
     * Where the bundled agent is put, named for Gradle, which reads the agent's version from it. In
     * the project's build directory, so each project's task owns its own copy.
     */
    fun location(project: Project, version: String): File =
        project.layout.buildDirectory.file("yoriwake/org.jacoco.agent-$version.jar").get().asFile

    /**
     * Gradle's default JaCoCo version, read from the running Gradle: the compiled constant would be
     * the default of the Gradle API the plugin was built against.
     */
    fun gradleDefault(project: Project): String? =
        project.extensions.extraProperties.let { if (it.has(DEFAULT_KEY)) it.get(DEFAULT_KEY) as String else null }
            ?: runCatching { JacocoPlugin::class.java.getField("DEFAULT_JACOCO_VERSION").get(null) as String }.getOrNull()

    /**
     * The JaCoCo version that replaces [toolVersion] for a test JVM of Java [java], or null: the
     * bundled one, when [toolVersion] is Gradle's [default], [java] is above what that officially
     * instruments, and the bundled one officially instruments [java].
     */
    fun replacing(toolVersion: String?, default: String?, java: Int?, bundled: String? = bundledVersion): String? {
        if (toolVersion == null || toolVersion != default || java == null || bundled == null) return null
        val defaultCeiling = ClassFileSupport.jacocoCeiling(default)
        val bundledCeiling = ClassFileSupport.jacocoCeiling(bundled)
        // An unlisted default is assumed to reach the newest row, which leaves no room above it.
        if (defaultCeiling.note != null || bundledCeiling.note != null) return null
        return bundled.takeIf { java > defaultCeiling.java && java <= bundledCeiling.java }
    }

    /**
     * Registers [test], which the plugin instruments, as one whose test JVM decides the project's
     * agent; the first one wires the project's agent configuration. Every `Test` task of a project
     * shares that configuration, so the others get the bundled agent too, which covers their JVMs.
     */
    fun register(project: Project, test: Test, defaultOverride: String?) {
        if (bundledVersion == null) return
        // `Settings.jacocoDefault`: a stand-in for Gradle's default, for tests on the JDKs at hand.
        defaultOverride?.let { project.extensions.extraProperties.set(DEFAULT_KEY, it) }
        val configuration = project.configurations.findByName(AGENT_CONFIGURATION) ?: return
        val extra = project.extensions.extraProperties
        @Suppress("UNCHECKED_CAST")
        val known = (if (extra.has(TASKS_KEY)) extra.get(TASKS_KEY) as MutableList<Test> else null)
        if (known != null) {
            known += test
            return
        }
        extra.set(TASKS_KEY, mutableListOf(test))
        // At resolution, so a `toolVersion` or toolchain the build sets after the plugin still counts.
        configuration.withDependencies { dependencies ->
            val version = versionFor(project)
            // What the run's facts read from now on, so they name the agent that was resolved.
            extra.set(DECIDED_KEY, version.orEmpty())
            if (version == null) return@withDependencies
            dependencies.clear()
            dependencies.add(project.dependencies.create(project.files(location(project, version)).builtBy(EXTRACT_TASK)))
        }
    }

    /**
     * Registers the task that puts the bundled agent in place: a task, not a `doFirst`, because
     * Gradle reads the agent jar before a test task's own actions run, and every task resolving the
     * swapped configuration, a declined one too, then depends on it. Registered when the project
     * applies `jacoco`, where registering is allowed.
     */
    fun registerTask(project: Project) {
        val version = bundledVersion ?: return
        project.tasks.register(EXTRACT_TASK, BundledJacocoAgent::class.java) { task ->
            task.description = "Puts yoriwake's bundled JaCoCo agent where the test tasks read it"
            task.agent.set(location(project, version))
        }
    }

    /** The bundled version when the project's agent is replaced by it, else null. */
    fun versionFor(project: Project): String? {
        val bundled = bundledVersion ?: return null
        project.extensions.extraProperties.let { if (it.has(DECIDED_KEY)) return (it.get(DECIDED_KEY) as String).ifEmpty { null } }
        val toolVersion = project.extensions.findByType(JacocoPluginExtension::class.java)?.toolVersion
        val configuration = project.configurations.findByName(AGENT_CONFIGURATION) ?: return null
        // Resolved before the swap was wired, so Gradle's default agent is the one that runs.
        if (configuration.state != org.gradle.api.artifacts.Configuration.State.UNRESOLVED) return null
        if (!leftToGradle(configuration.dependencies, toolVersion, project)) return null
        val default = gradleDefault(project)
        @Suppress("UNCHECKED_CAST")
        val tests = project.extensions.extraProperties.let { if (it.has(TASKS_KEY)) it.get(TASKS_KEY) as List<Test> else emptyList() }
        return bundled.takeIf { tests.any { replacing(toolVersion, default, testJvm(it), bundled) != null } }
    }

    /**
     * The line a run of [test] prints when its agent is replaced, or null; read where the run's
     * facts are, so it names the same versions.
     */
    fun line(project: Project, test: Test): String? {
        val version = versionFor(project) ?: return null
        val default = gradleDefault(project) ?: return null
        val java = testJvm(test)?.let { " runs Java $it and" }.orEmpty()
        return "this task's test JVM$java Gradle's default JaCoCo $default (`jacoco.toolVersion`) officially " +
            "instruments up to Java ${ClassFileSupport.jacocoJava(default)}, so yoriwake attaches its " +
            "bundled JaCoCo $version agent in its place. Set `jacoco.toolVersion` to choose a JaCoCo yourself."
    }

    /**
     * Whether the agent configuration still holds only what Gradle puts there for [toolVersion] (or
     * the bundled agent, once replaced): a build that declares its own agent keeps it.
     */
    private fun leftToGradle(dependencies: DependencySet, toolVersion: String?, project: Project): Boolean =
        dependencies.toList().all { it.isGradleDefault(toolVersion) || it.isBundled(project) }

    private fun Dependency.isGradleDefault(toolVersion: String?) =
        this is ExternalModuleDependency && group == "org.jacoco" && name == "org.jacoco.agent" && version == toolVersion

    private fun Dependency.isBundled(project: Project): Boolean {
        val version = bundledVersion ?: return false
        return this is FileCollectionDependency && files.files == setOf(location(project, version))
    }

    /** The test JVM's feature version as its launcher reports it, null when it cannot be told. */
    private fun testJvm(test: Test): Int? =
        runCatching { test.javaLauncher.get().metadata.languageVersion.asInt() }.getOrNull()

    /** Writes the bundled agent to [target]. */
    internal fun extract(target: File) {
        val version = bundledVersion ?: return
        val bytes = javaClass.classLoader.getResourceAsStream(resource(version))?.use { it.readBytes() } ?: return
        target.parentFile.mkdirs()
        target.writeBytes(bytes)
    }
}

/** Puts the plugin's bundled JaCoCo agent at [agent]; see [JacocoSwap]. */
internal abstract class BundledJacocoAgent : org.gradle.api.DefaultTask() {

    @get:org.gradle.api.tasks.OutputFile
    abstract val agent: org.gradle.api.file.RegularFileProperty

    @org.gradle.api.tasks.TaskAction
    fun put() = JacocoSwap.extract(agent.get().asFile)
}
