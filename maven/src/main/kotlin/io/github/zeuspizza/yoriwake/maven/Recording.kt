package io.github.zeuspizza.yoriwake.maven

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.gradle.bytecode.TaskArtifacts
import io.github.zeuspizza.yoriwake.gradle.capture.CoverageDecoder
import io.github.zeuspizza.yoriwake.gradle.capture.DecodeRun
import io.github.zeuspizza.yoriwake.gradle.capture.MapLocation
import io.github.zeuspizza.yoriwake.gradle.capture.writeAtomically
import io.github.zeuspizza.yoriwake.gradle.change.CaptureStart
import io.github.zeuspizza.yoriwake.gradle.change.ChangeDetection
import io.github.zeuspizza.yoriwake.gradle.change.WorkingTree
import io.github.zeuspizza.yoriwake.gradle.facts.ClasspathView
import io.github.zeuspizza.yoriwake.gradle.wiring.ScopeOutcome
import io.github.zeuspizza.yoriwake.gradle.wiring.pendingHead
import io.github.zeuspizza.yoriwake.gradle.wiring.ranMarker
import io.github.zeuspizza.yoriwake.gradle.wiring.undatedRunMarker
import org.apache.maven.execution.ExecutionEvent
import org.apache.maven.execution.MavenSession
import org.apache.maven.plugin.MojoExecution
import org.apache.maven.project.MavenProject
import org.codehaus.plexus.util.xml.Xpp3Dom
import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest
import java.util.Properties

/**
 * A recording run on Maven: the wiring Gradle does at configuration and in its test task's `doFirst`,
 * done when Surefire's or Failsafe's mojo starts, and the decode Gradle's finalizer runs, done when it
 * ends, whether a test failed or not. Each execution has its own map, under the reactor root's
 * `.yoriwake/`, which survives `mvn clean`.
 */
internal class Recording(
    private val session: MavenSession,
    /** Every execution U6 left wired, by its key, with the scope the agent records under. */
    private val wired: Map<String, ScopeOutcome>,
    /** The packages of the whole reactor, which the loaded-class union may speak for. */
    private val reactorPackages: List<String>,
) {

    /** The top of the reactor, where git runs and the maps live. */
    private val root: File = (session.request.multiModuleProjectDirectory ?: session.topLevelProject.basedir).absoluteFile
    private val home = File(root, MAPS_HOME)

    /** HEAD and its reflogs before anything compiled; null when git could not be read. */
    private val start: String? = runCatching { CaptureStart.read(root).encode() }.getOrNull()

    /** Per module, the phases this session ran: an execution needs `compile` and `test-compile` (KTD8). */
    private val compiled = mutableMapOf<String, MutableSet<String>>()

    /** What each started execution's decode reads back. */
    private val running = mutableMapOf<String, Run>()

    private class Run(val mapDir: File, val undatedReason: String?, val classpath: List<File>, val reports: File)

    init {
        if (start != null && wired.isNotEmpty()) startTree()
    }

    fun started(event: ExecutionEvent) {
        val execution = event.mojoExecution ?: return
        val key = keyOf(event.project, execution)
        val outcome = wired[key]
        if (outcome == null) {
            // Typed on the command line (`mvn surefire:test`): an execution the reactor did not declare.
            if (isTestGoal(execution)) {
                val name = nameOf(event.project, execution)
                log.warn(
                    if (!compiledHere(event.project)) "[yoriwake] $name: ${notCompiled(event.project)}"
                    else "[yoriwake] $name: an execution the POM does not declare captures nothing, and the map is left as it was"
                )
            }
            return
        }
        if (outcome is ScopeOutcome.NotApplied) return
        runCatching { wire(event.project, execution, key) }.onFailure {
            log.warn("[yoriwake] ${nameOf(event.project, execution)}: could not wire the run ($it); nothing is captured and the map is left as it was")
        }
    }

    fun ended(event: ExecutionEvent) {
        val execution = event.mojoExecution ?: return
        val phase = execution.lifecyclePhase
        if (phase == "compile" || phase == "test-compile") {
            if (!skipped(execution)) compiled.getOrPut(event.project.id) { mutableSetOf() } += phase
            return
        }
        val key = keyOf(event.project, execution)
        val run = running.remove(key) ?: return
        decode(event.project, execution, key, run)
    }

    private fun wire(project: MavenProject, execution: MojoExecution, key: String) {
        val name = nameOf(project, execution)
        val mapDir = MapLocation.forTask(home, key)
        if (!compiledHere(project)) {
            log.warn("[yoriwake] $name: ${notCompiled(project)}")
            return
        }
        home.mkdirs()
        File(home, ".gitignore").takeUnless(File::isFile)?.writeText("*\n")
        val recordsDir = CoverageDecoder.recordsDir(mapDir)
        discardPrevious(mapDir, recordsDir, name)
        undatedRunMarker(recordsDir).delete()
        startReading(mapDir)

        val runFile = File(mapDir, AgentContract.RUN_FILE)
        val properties = Properties().apply {
            setProperty(AgentContract.MAP_DIR_PROPERTY, mapDir.path)
            setProperty(AgentContract.RECORDS_DIR_PROPERTY, recordsDir.path)
            setProperty(AgentContract.CAPTURE_ROOT_PROPERTY, root.path)
        }
        writeAtomically(runFile, buildString {
            properties.stringPropertyNames().sorted().forEach { append(escape(it)).append('=').append(escape(properties.getProperty(it))).append('\n') }
            append(AgentContract.RUN_FILE_END).append('\n')
        })

        val agent = agentJar()
        val configuration = execution.configuration ?: Xpp3Dom("configuration")
        val ours = "-javaagent:${quoted(agent.path)} -D${AgentContract.HOST_PROPERTY}=${AgentContract.HOST_MAVEN} " +
            quoted("-D${AgentContract.RUN_FILE_PROPERTY}=${runFile.path}")
        val argLine = configuration.getChild("argLine")
        when {
            argLine?.value != null -> argLine.value = argLine.value.trim() + " " + ours
            // The host's JaCoCo hands its agent through the `argLine` property when the POM names no argLine.
            project.properties.getProperty("argLine") != null -> child(configuration, "argLine").value = "@{argLine} $ours"
            else -> child(configuration, "argLine").value = ours
        }
        child(configuration, "additionalClasspathElements").addChild(Xpp3Dom("additionalClasspathElement").apply { value = agent.path })
        execution.configuration = configuration

        val reports = File(
            configuration.getChild("reportsDirectory")?.value?.takeUnless { it.contains("\${") }
                ?: File(project.build.directory, if (execution.artifactId == FAILSAFE) "failsafe-reports" else "surefire-reports").path
        )
        val classpath = runCatching { project.testClasspathElements.map(::File) }.getOrDefault(emptyList())
        running[key] = Run(mapDir, undatedBy(project, execution, configuration), classpath, reports)
    }

    private fun decode(project: MavenProject, execution: MojoExecution, key: String, run: Run) {
        val scope = wired[key]
        val patterns = when (scope) {
            is ScopeOutcome.Applied -> scope.patterns
            is ScopeOutcome.AdoptedFromHost -> scope.patterns
            else -> emptyList()
        }.map { it.removeSuffix("*").removeSuffix(".") }.filter(String::isNotEmpty)
        val buildDirs = session.allProjects.map { it.build.directory }
        val classpath = run.classpath
        DecodeRun(
            taskPath = nameOf(project, execution),
            patterns = patterns,
            selecting = false,
            observing = false,
            asksSelection = false,
            complementing = false,
            wholeTask = false,
            loadedScope = reactorPackages,
            datesTheMap = run.undatedReason == null,
            undatedReason = run.undatedReason.orEmpty(),
            effectiveScope = null,
            isolated = false,
            scheduleSet = false,
            rootDir = root,
            mapDir = run.mapDir,
            facts = object : ClasspathView {
                override val rootDir = root
                override val moduleDirs = session.allProjects.associate { module ->
                    module.basedir.relativeTo(root).invariantSeparatorsPath to module.id
                }
                override val buildDirs = session.allProjects.associate { it.id to it.build.directory }
                override val ownPath: String = project.id
                override fun classpathFiles(): Set<File> = classpath.toSet()
                override fun testOutputFiles(): Set<File> = setOf(File(project.build.testOutputDirectory))
                override fun compileTimeFiles(): Set<File> =
                    runCatching { project.compileClasspathElements.map(::File).toSet() }.getOrDefault(emptySet())
                override fun declaredModules(): Set<String>? = null
                override fun jacocoVersion(): String? = project.getPlugin(JacocoAdoption.JACOCO)?.version.orEmpty()
                override fun testJvm(): Int? = null
                override fun agentCeiling(): String? = null
            },
            taskArtifacts = { TaskArtifacts(classpath, buildDirs, listOf(File(project.build.testOutputDirectory))) },
            junitXml = run.reports,
            loadedFlag = "-D${INTERNAL_LOADED}",
            logger = object : DecodeRun.Log {
                override fun lifecycle(message: String) = log.info(message)
                override fun warn(message: String) = log.warn(message)
                override fun info(message: String) = log.debug(message)
            },
        ).run()
    }

    /**
     * Why this execution cannot date the map, or null when it can: tests named on the command line
     * or in a file narrow the run, and stopping after a failure leaves later tests unobserved. The
     * JUnit Platform's own filters (`groups`, engines) still date it, as Gradle's do.
     */
    private fun undatedBy(project: MavenProject, execution: MojoExecution, configuration: Xpp3Dom): String? {
        fun parameter(name: String, property: String): String? =
            configuration.getChild(name)?.value?.trim()?.takeUnless { it.isEmpty() || it.startsWith("\${") }
                ?: Declines.property(project, session, property)
        val failsafe = execution.artifactId == FAILSAFE
        parameter("test", if (failsafe) "it.test" else "test")?.let { return "the run was filtered to $it" }
        parameter("includesFile", if (failsafe) "failsafe.includesFile" else "surefire.includesFile")?.let { return "the run was filtered by $it" }
        parameter("excludesFile", if (failsafe) "failsafe.excludesFile" else "surefire.excludesFile")?.let { return "the run was filtered by $it" }
        val stopAfter = parameter("skipAfterFailureCount", if (failsafe) "failsafe.skipAfterFailureCount" else "surefire.skipAfterFailureCount")
        if ((stopAfter?.toIntOrNull() ?: 0) > 0) return "the run stops after $stopAfter failure(s)"
        return null
    }

    /** The previous run's records, decisions and observation go before this one writes any. */
    private fun discardPrevious(mapDir: File, recordsDir: File, name: String) {
        File(mapDir, AgentContract.OBSERVATION_FILE).delete()
        File(mapDir, AgentContract.DECISIONS_FILE).delete()
        mapDir.listFiles { file ->
            file.name.startsWith("${AgentContract.DECISIONS_FILE}.") && file.name.endsWith(AgentContract.DECISIONS_PART_SUFFIX)
        }?.forEach(File::delete)
        if (!recordsDir.deleteRecursively() && recordsDir.exists()) {
            log.warn("[yoriwake] $name: could not clear $recordsDir, so this run may capture nothing and may merge records left by an earlier one")
        }
        mapDir.mkdirs()
        ranMarker(recordsDir).writeText("ran")
    }

    /** The tree where every capture of this session starts, read once, before anything compiled. */
    private fun startTree() = runCatching {
        val excluded = WorkingTree.excluded(root, session.allProjects.map { it.build.directory }) + MAPS_HOME
        val listed = WorkingTree.listing(root, excluded)
        val snapshot = File(home, WorkingTree.SNAPSHOT_PENDING_FILE)
        val previous = WorkingTree.takePreviousStart(snapshot)
        if (listed != null) WorkingTree.writeStart(snapshot, WorkingTree.startSnapshot(root, listed, previous))
        val tracked = ChangeDetection.rawGit(root, listOf("ls-files", "-z"))?.split('\u0000')?.filter(String::isNotEmpty)
        val stats = File(home, WorkingTree.STATS_PENDING_FILE)
        WorkingTree.takePreviousStart(stats)
        if (tracked != null) WorkingTree.writeStart(stats, WorkingTree.startStats(root, tracked, excluded))
    }.onFailure { log.warn("[yoriwake] could not read the working tree where this build's captures start ($it); their maps will not be dated") }

    /** This session's start reading, for the decode of [mapDir] to compare against. */
    private fun startReading(mapDir: File) {
        val head = pendingHead(mapDir)
        head.delete()
        val reading = start?.let(CaptureStart::decode) ?: return
        val pending = CaptureStart.Pending(
            reading,
            WorkingTree.startId(File(home, WorkingTree.SNAPSHOT_PENDING_FILE)),
            WorkingTree.startId(File(home, WorkingTree.STATS_PENDING_FILE)),
        )
        runCatching { writeAtomically(head, pending.encode()) }
    }

    /**
     * The agent jar this plugin bundles, copied once beside the maps under a name its bytes decide,
     * so two plugin versions never share one.
     */
    private fun agentJar(): File {
        val bytes = requireNotNull(javaClass.classLoader.getResourceAsStream(AGENT_RESOURCE)) { "this plugin bundles no agent" }.use { it.readBytes() }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).take(8).joinToString("") { "%02x".format(it) }
        val jar = File(File(home, "agent"), "yoriwake-agent-$digest.jar")
        if (!jar.isFile || jar.length() != bytes.size.toLong()) {
            jar.parentFile.mkdirs()
            val partial = File(jar.parentFile, jar.name + ".part")
            partial.writeBytes(bytes)
            check(partial.renameTo(jar) || jar.isFile) { "could not write $jar" }
        }
        return jar
    }

    private fun compiledHere(project: MavenProject) = compiled[project.id].orEmpty() == setOf("compile", "test-compile")

    private fun notCompiled(project: MavenProject) =
        "this session did not compile ${project.artifactId}'s classes and tests, so nothing is captured and the map is left as it was"

    private fun isTestGoal(execution: MojoExecution) =
        (execution.artifactId == "maven-surefire-plugin" && execution.goal == "test") ||
            (execution.artifactId == FAILSAFE && execution.goal == "integration-test")

    /** A compiler execution that skipped its work did not build the classes (`maven.main.skip`, `maven.test.skip`). */
    private fun skipped(execution: MojoExecution): Boolean {
        if (execution.artifactId != "maven-compiler-plugin") return false
        val name = if (execution.goal == "testCompile") "skip" else "skipMain"
        val property = if (execution.goal == "testCompile") "maven.test.skip" else "maven.main.skip"
        val value = execution.configuration?.getChild(name)?.value?.trim()?.takeUnless { it.startsWith("\${") }
            ?: session.userProperties.getProperty(property) ?: session.systemProperties.getProperty(property)
        return value.equals("true", ignoreCase = true) || value == ""
    }

    private fun child(parent: Xpp3Dom, name: String): Xpp3Dom = parent.getChild(name) ?: Xpp3Dom(name).also(parent::addChild)

    private fun quoted(value: String) = if (value.any(Char::isWhitespace)) "\"$value\"" else value

    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\n", "\\n").replace("=", "\\=").replace(":", "\\:")

    companion object {
        /** Under the reactor root, beside `.mvn`; it writes its own `.gitignore`, so it is never committed. */
        const val MAPS_HOME = ".yoriwake"
        const val FAILSAFE = "maven-failsafe-plugin"
        const val INTERNAL_LOADED = "yoriwake.internal.loaded"
        private const val AGENT_RESOURCE = "yoriwake-agent.jar"
        private val log = LoggerFactory.getLogger(Recording::class.java)

        /** One map per test execution of a module. */
        fun keyOf(project: MavenProject, execution: MojoExecution) =
            "${project.groupId}:${project.artifactId}:${execution.artifactId}:${execution.executionId}"

        fun nameOf(project: MavenProject, execution: MojoExecution) =
            "${project.artifactId} ${execution.artifactId.removePrefix("maven-").removeSuffix("-plugin")}:${execution.goal} (${execution.executionId})"
    }
}
