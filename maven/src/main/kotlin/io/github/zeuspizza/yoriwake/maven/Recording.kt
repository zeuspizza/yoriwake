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
import io.github.zeuspizza.yoriwake.gradle.RunPlan
import io.github.zeuspizza.yoriwake.gradle.SettingNames
import io.github.zeuspizza.yoriwake.gradle.capture.EnvironmentFingerprint
import io.github.zeuspizza.yoriwake.gradle.change.ClasspathFiles
import io.github.zeuspizza.yoriwake.gradle.change.RefusalKind
import io.github.zeuspizza.yoriwake.gradle.wiring.ParallelismDetector
import io.github.zeuspizza.yoriwake.gradle.wiring.pendingClasspathFiles
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
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

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
    private val compiled = ConcurrentHashMap<String, MutableSet<String>>()

    /** What each started execution's decode reads back; `-T` starts and ends them on several threads. */
    private val running = ConcurrentHashMap<String, Run>()

    private class Run(
        val mapDir: File,
        val undatedReason: String?,
        val classpath: List<File>,
        val reports: File,
        val isolated: Boolean,
        val kind: RunPlan.Kind,
    )

    private val selection = Selection(session, root, log).also { it.validate() }

    init {
        if (start != null && wired.isNotEmpty() && reachesTests()) startTree()
    }

    /**
     * Whether this invocation can run a test execution at all: a lifecycle phase from `test` on, or a
     * Surefire or Failsafe goal. Anything else (`mvn compile`, `mvn clean`) leaves no file here.
     */
    private fun reachesTests(): Boolean = session.goals.any { goal ->
        goal in TEST_PHASES || goal.contains("surefire") || goal.contains("failsafe")
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
        // Failsafe's `verify` shares the execution, and its configuration has no argLine to wire.
        if (outcome is ScopeOutcome.NotApplied || !isTestGoal(execution)) return
        runCatching { wire(event.project, execution, key) }.onFailure {
            log.warn("[yoriwake] ${nameOf(event.project, execution)}: could not wire the run ($it); nothing is captured and the map is left as it was")
        }
    }

    fun ended(event: ExecutionEvent, succeeded: Boolean) {
        val execution = event.mojoExecution ?: return
        val phase = execution.lifecyclePhase
        if (phase == "compile" || phase == "test-compile") {
            // A compile that failed built nothing to vouch for, even when `-fn` goes on.
            if (succeeded && !skipped(event.project, execution)) {
                compiled.computeIfAbsent(event.project.id) { ConcurrentHashMap.newKeySet() } += phase
            }
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
        // Before the configuration is touched: a wiring that fails part-way must leave no earlier run's
        // file for the test JVM to read, which then refuses and runs every test.
        File(mapDir, AgentContract.RUN_FILE).delete()
        File(mapDir, AgentContract.CHANGE_SET_FILE).delete()
        val configuration = execution.configuration ?: Xpp3Dom("configuration")
        // Nothing runs, so nothing of the last run's is cleared and the map is not touched.
        if (skipsTests(project, execution, configuration)) return
        // A module of this reactor that this invocation did not build comes from a repository, as
        // whatever was installed last: its bytes are not the checkout's (KTD7).
        val reactor = session.allProjects.associateBy { "${it.groupId}:${it.artifactId}" }
        val built = session.projects.map { it.id }.toSet()
        val outside = project.artifacts.mapNotNull { reactor["${it.groupId}:${it.artifactId}"] }.filter { it.id !in built }
        if (outside.isNotEmpty()) {
            log.warn(
                "[yoriwake] $name declined, ${RefusalKind.REACTOR_MODULE_FROM_REPOSITORY.token}: " +
                    "${outside.joinToString { it.artifactId }} come(s) from a repository, not this build (add -am); " +
                    "every test runs, nothing is captured and the map is left as it was"
            )
            return
        }
        val classpath = runCatching { project.testClasspathElements.map(::File) }.getOrDefault(emptyList())
        // Tests interleaved in one JVM share its one JaCoCo agent, and their coverage would blend.
        parallelism(project, configuration, classpath)?.let { where ->
            log.warn(
                "[yoriwake] $name declined, ${RefusalKind.IN_JVM_PARALLELISM.token}: its tests may run concurrently in one JVM " +
                    "($where); every test runs and nothing is captured"
            )
            return
        }
        // What reaches the JVM is the authority: an argLine that drops the JaCoCo agent's property
        // records no coverage, whatever the agent's configuration says.
        if (!reachesJacoco(project, configuration)) {
            log.warn(
                "[yoriwake] $name declined, ${ScopeOutcome.NotApplied.Kind.NO_JACOCO_PLUGIN.token}: the JaCoCo agent does not reach " +
                    "the test JVM's argLine; every test runs and nothing is captured"
            )
            return
        }
        configuration.getChild("parallel")?.value?.takeIf { it.isNotBlank() && !it.startsWith("\${") }?.let {
            log.info("[yoriwake] $name: Surefire's parallel=$it applies to its JUnit 4.7 and TestNG providers only, and is ignored on the JUnit Platform")
        }
        ensureHome()
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

        val agent = agentJar()
        val ours = "-javaagent:${quoted(agent.path)} -D${AgentContract.HOST_PROPERTY}=${AgentContract.HOST_MAVEN} " +
            quoted("-D${AgentContract.RUN_FILE_PROPERTY}=${runFile.path}")
        // The host's JaCoCo hands its agent through the `argLine` property; an expression naming a
        // property nobody set would reach the JVM as its literal text.
        val defined = Declines.property(project, session, "argLine") != null
        val host = (configuration.getChild("argLine")?.value?.trim() ?: if (defined) "@{argLine}" else "")
            .let { if (defined) it else it.replace("\${argLine}", "").replace("@{argLine}", "").trim() }
        child(configuration, "argLine").value = listOf(host, ours).filter(String::isNotEmpty).joinToString(" ")
        // A JVM per test class, as Gradle's forkEvery=1: a static a class built is never seen by the next.
        val isolated = Declines.property(project, session, SettingNames.ISOLATED_CAPTURE)
            ?.let { it.isEmpty() || it.equals("true", ignoreCase = true) } == true
        if (isolated) child(configuration, "reuseForks").value = "false"
        child(configuration, "additionalClasspathElements").addChild(Xpp3Dom("additionalClasspathElement").apply { value = agent.path })

        val reports = File(
            configuration.getChild("reportsDirectory")?.value?.takeUnless { it.contains("\${") }
                ?: File(project.build.directory, if (execution.artifactId == FAILSAFE) "failsafe-reports" else "surefire-reports").path
        )
        val environment = fingerprints(project, mapDir, configuration, classpath, arguments(host.let { resolved(project, it).orEmpty() }))
        val undated = undatedBy(project, execution, configuration)
        val hostArguments = arguments(resolved(project, host).orEmpty())
        val kind = selection.apply(
            name, mapDir, project.basedir.relativeTo(root).invariantSeparatorsPath, view(project, classpath),
            environment, undated, isolated,
            stamp = { stampOf(project, key, configuration, classpath, hostArguments) },
            stopsAfterFailure = parameter(
                project, configuration, "skipAfterFailureCount",
                if (execution.artifactId == FAILSAFE) "failsafe.skipAfterFailureCount" else "surefire.skipAfterFailureCount",
            )
                ?.toIntOrNull()?.let { it > 0 } == true,
            properties = properties,
        )
        // A selecting or complement execution may leave out every test, which Surefire would otherwise fail on.
        if (kind == RunPlan.Kind.SELECT || kind == RunPlan.Kind.COMPLEMENT) child(configuration, "failIfNoTests").value = "false"
        execution.configuration = configuration
        writeAtomically(runFile, buildString {
            properties.stringPropertyNames().sorted().forEach { append(escape(it)).append('=').append(escape(properties.getProperty(it))).append('\n') }
            append(AgentContract.RUN_FILE_END).append('\n')
        })
        running[key] = Run(mapDir, undated, classpath, reports, isolated, kind)
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
            selecting = run.kind == RunPlan.Kind.SELECT,
            observing = run.kind == RunPlan.Kind.OBSERVE,
            asksSelection = selection.asksSelection,
            complementing = run.kind == RunPlan.Kind.COMPLEMENT,
            wholeTask = false,
            loadedScope = reactorPackages,
            datesTheMap = run.undatedReason == null,
            undatedReason = run.undatedReason.orEmpty(),
            effectiveScope = null,
            isolated = run.isolated,
            scheduleSet = selection.schedules,
            rootDir = root,
            mapDir = run.mapDir,
            facts = view(project, classpath),
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

    private fun view(project: MavenProject, classpath: List<File>): ClasspathView = classpathView(session, root, project, classpath)

    /** A Surefire or Failsafe parameter as this execution resolves it: its POM value, else its user property. */
    private fun parameter(project: MavenProject, configuration: Xpp3Dom, name: String, property: String): String? =
        configuration.getChild(name)?.value?.trim()?.takeUnless { it.isEmpty() || it.startsWith("\${") }
            ?: Declines.property(project, session, property)

    /** This execution's selection-record stamp; see [MavenStamp]. */
    private fun stampOf(
        project: MavenProject,
        key: String,
        configuration: Xpp3Dom,
        classpath: List<File>,
        hostArguments: List<String>,
    ) = MavenStamp.observe(
        root, key, project.id, classpath,
        File(session.localRepository?.basedir ?: File(System.getProperty("user.home"), ".m2/repository").path),
        // Surefire promotes the invocation's user properties to the test JVM's system properties.
        session.userProperties.stringPropertyNames().associateWith { session.userProperties.getProperty(it) } +
            configuration.getChild("systemPropertyVariables")?.children.orEmpty()
                .associate { it.name to resolved(project, it.value).orEmpty() },
        hostArguments,
        // Surefire's own classpath changes too: a profile can add or exclude entries Maven's model does not show.
        listOf("includes", "excludes", "additionalClasspathElements", "classpathDependencyExcludes").flatMap { group ->
            configuration.getChild(group)?.children.orEmpty().map { it.value.orEmpty().trim() }
                // yoriwake's own agent, which wiring adds by an absolute path another machine does not share.
                .filterNot { File(it).parentFile == File(home, "agent") }
                .map { "$group $it" }
        } + listOf("groups", "excludedGroups", "test", "classpathDependencyScopeExclude").mapNotNull { name ->
            parameter(project, configuration, name, name)?.let { "$name $it" }
        },
    )

    /**
     * Why this execution cannot date the map, or null when it can: tests named on the command line
     * or in a file narrow the run, and stopping after a failure leaves later tests unobserved. The
     * JUnit Platform's own filters (`groups`, engines) still date it, as Gradle's do.
     */
    private fun undatedBy(project: MavenProject, execution: MojoExecution, configuration: Xpp3Dom): String? {
        fun parameter(name: String, property: String): String? = parameter(project, configuration, name, property)
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

    /**
     * Where the JUnit Platform was told to run tests in parallel inside one JVM, or null: Surefire's
     * `configurationParameters`, its `properties` and `systemPropertyVariables`, the `argLine`, and a
     * `junit-platform.properties` on the test classpath.
     */
    private fun parallelism(project: MavenProject, configuration: Xpp3Dom, classpath: List<File>): String? {
        // Surefire hands the fork the command line's -D user properties as system properties.
        val parameters = session.userProperties.entries.associateTo(mutableMapOf<String, String?>()) { (k, v) -> k.toString() to v?.toString() }
        fun read(text: String?) = text?.let { runCatching { Properties().apply { load(java.io.StringReader(it)) } }.getOrNull() }
            ?.forEach { (k, v) -> parameters[k.toString()] = v.toString() }
        read(configuration.getChild("configurationParameters")?.value)
        configuration.getChild("properties")?.children?.forEach { child ->
            when {
                child.name == "configurationParameters" -> read(child.value)
                // The `<property><name/><value/></property>` form.
                child.name == "property" && child.getChild("name") != null -> parameters[child.getChild("name").value] = child.getChild("value")?.value
                else -> parameters[child.name] = child.value
            }
        }
        configuration.getChild("systemPropertyVariables")?.children?.forEach { parameters[it.name] = it.value }
        resolved(project, configuration.getChild("systemPropertiesFile")?.value)?.let { path ->
            runCatching { read(File(path).readText()) }
        }
        return ParallelismDetector.detect(parameters, arguments(resolvedArgLine(project, configuration)), classpath)
    }

    /**
     * What the test JVM will see, recorded for the decode to keep beside the map: the build's own files
     * on its classpath, and its JVM and environment. A selecting run compares them later; one that
     * cannot be recorded makes that run run everything.
     */
    /** Records what the decode compares the next run against; returns this run's environment, null when unreadable. */
    private fun fingerprints(
        project: MavenProject,
        mapDir: File,
        configuration: Xpp3Dom,
        classpath: List<File>,
        hostArguments: List<String>,
    ): Pair<EnvironmentFingerprint.View, EnvironmentFingerprint.Rules>? {
        val classpathPending = pendingClasspathFiles(mapDir)
        classpathPending.delete()
        // A local repository inside the checkout holds libraries, not this build's output.
        val repository = session.localRepository?.basedir?.let { File(it).absoluteFile }
        val built = classpath.filterNot { entry -> repository != null && entry.absoluteFile.startsWith(repository) }
        when (val walk = runCatching { ClasspathFiles.walk(built, session.allProjects.map { it.build.directory }, root) }
            .getOrElse { ClasspathFiles.Walk.Refused(it.toString()) }) {
            is ClasspathFiles.Walk.Found -> runCatching { CoverageDecoder.writeClasspathFilesTo(classpathPending, walk.digests) }
            is ClasspathFiles.Walk.Refused -> log.debug("[yoriwake] ${project.artifactId}: no classpath file digests recorded (${walk.reason})")
        }
        File(mapDir, EnvironmentFingerprint.PENDING_FILE).delete()
        return runCatching {
            val jvm = resolved(project, configuration.getChild("jvm")?.value)
            // Another JDK forks the tests, whose version nobody read here.
            val otherJdk = jvm != null || configuration.getChild("jdkToolchain")?.childCount?.let { it > 0 } == true ||
                project.getPlugin("org.apache.maven.plugins:maven-toolchains-plugin") != null
            val environment = System.getenv() + configuration.getChild("environmentVariables")?.children.orEmpty()
                .associate { it.name to it.value.orEmpty() }
            val view = EnvironmentFingerprint.View(
                // Maven's own JVM forks the tests unless the POM names another, whose version nobody read.
                if (otherJdk) null else System.getProperty("java.runtime.version"),
                if (otherJdk) null else System.getProperty("java.vendor"),
                jvm, System.getProperty("os.name"), System.getProperty("os.arch"),
                // As Gradle's JVM arguments carry its system properties.
                hostArguments + configuration.getChild("systemPropertyVariables")?.children.orEmpty()
                    .map { "-D${it.name}=${resolved(project, it.value).orEmpty()}" },
                environment,
            )
            val rules = EnvironmentFingerprint.Rules(
                emptyList(), emptyList(), root.path,
                session.localRepository?.basedir ?: File(System.getProperty("user.home"), ".m2/repository").path,
                System.getProperty("user.home"),
            )
            EnvironmentFingerprint.writePending(mapDir, view, rules)
            view to rules
        }.onFailure { log.debug("[yoriwake] ${project.artifactId}: no environment fingerprint recorded ($it)") }.getOrNull()
    }

    /**
     * Whether JaCoCo's agent reaches the fork: in the argLine it will get, its expressions resolved,
     * or in the `JAVA_TOOL_OPTIONS` every JVM reads. JaCoCo quotes an agent path holding a space.
     */
    private fun reachesJacoco(project: MavenProject, configuration: Xpp3Dom): Boolean {
        val agent = Regex("""-javaagent:("[^"]*|\S*)(jacocoagent|org\.jacoco\.agent)""")
        val toolOptions = configuration.getChild("environmentVariables")?.getChild("JAVA_TOOL_OPTIONS")?.value ?: System.getenv("JAVA_TOOL_OPTIONS")
        return listOfNotNull(resolvedArgLine(project, configuration), toolOptions).any(agent::containsMatchIn)
    }

    /** The argLine the fork will get, its `${x}` and `@{x}` resolved as Maven and Surefire resolve them. */
    private fun resolvedArgLine(project: MavenProject, configuration: Xpp3Dom): String =
        resolved(project, configuration.getChild("argLine")?.value ?: "\${argLine}").orEmpty()

    private fun resolved(project: MavenProject, value: String?): String? =
        value?.let { Regex("""[$@]\{([^}]+)}""").replace(it) { m -> Declines.property(project, session, m.groupValues[1]).orEmpty() } }
            ?.takeIf(String::isNotBlank)

    /** A command line's arguments, a double-quoted one kept whole, as Surefire splits an argLine. */
    private fun arguments(line: String): List<String> =
        Regex(""""([^"]*)"|(\S+)""").findAll(line).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList()

    /** `.yoriwake/`, ignored by git before anything is written into it. */
    private fun ensureHome() {
        home.mkdirs()
        File(home, ".gitignore").takeUnless(File::isFile)?.writeText("*\n")
    }

    /** Whether Surefire or Failsafe will run no test at all this time. */
    private fun skipsTests(project: MavenProject, execution: MojoExecution, configuration: Xpp3Dom): Boolean {
        fun flag(name: String, property: String) =
            (configuration.getChild(name)?.value?.trim()?.takeUnless { it.startsWith("\${") } ?: Declines.property(project, session, property))
                ?.let { it.isEmpty() || it.equals("true", ignoreCase = true) } == true
        return flag("skip", "maven.test.skip") || flag("skipTests", "skipTests") || flag("skipExec", "maven.test.skip.exec") ||
            (execution.artifactId == FAILSAFE && flag("skipITs", "skipITs"))
    }

    /** The tree where every capture of this session starts, read once, before anything compiled. */
    private fun startTree() = runCatching {
        ensureHome()
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
            // One file per writer, moved into place whole: another module's thread or another build of
            // this checkout may be writing the same jar.
            val partial = Files.createTempFile(jar.parentFile.toPath(), jar.name, ".part")
            Files.write(partial, bytes)
            Files.move(partial, jar.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
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
    private fun skipped(project: MavenProject, execution: MojoExecution): Boolean {
        if (execution.artifactId != "maven-compiler-plugin") return false
        val name = if (execution.goal == "testCompile") "skip" else "skipMain"
        val property = if (execution.goal == "testCompile") "maven.test.skip" else "maven.main.skip"
        val value = execution.configuration?.getChild(name)?.value?.trim()?.takeUnless { it.startsWith("\${") }
            ?: Declines.property(project, session, property)
        return value.equals("true", ignoreCase = true) || value == ""
    }

    private fun child(parent: Xpp3Dom, name: String): Xpp3Dom = parent.getChild(name) ?: Xpp3Dom(name).also(parent::addChild)

    private fun quoted(value: String) = if (value.any { it.isWhitespace() || it == '\'' }) "\"$value\"" else value

    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\n", "\\n").replace("=", "\\=").replace(":", "\\:")

    companion object {
        /** Under the reactor root, beside `.mvn`; it writes its own `.gitignore`, so it is never committed. */
        const val MAPS_HOME = ".yoriwake"
        const val FAILSAFE = "maven-failsafe-plugin"
        const val INTERNAL_LOADED = "yoriwake.internal.loaded"
        /** The lifecycle phases that run Surefire or Failsafe, or come after them. */
        private val TEST_PHASES = setOf(
            "test", "prepare-package", "package", "pre-integration-test", "integration-test", "post-integration-test",
            "verify", "install", "deploy",
        )
        private const val AGENT_RESOURCE = "yoriwake-agent.jar"
        private val log = LoggerFactory.getLogger(Recording::class.java)

        /** One map per test execution of a module. */
        fun keyOf(project: MavenProject, execution: MojoExecution) =
            "${project.groupId}:${project.artifactId}:${execution.artifactId}:${execution.executionId}"

        fun nameOf(project: MavenProject, execution: MojoExecution) =
            "${project.artifactId} ${execution.artifactId.removePrefix("maven-").removeSuffix("-plugin")}:${execution.goal} (${execution.executionId})"
    }
}
