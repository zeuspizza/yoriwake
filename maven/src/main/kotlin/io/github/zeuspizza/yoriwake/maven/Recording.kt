package io.github.zeuspizza.yoriwake.maven

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.gradle.bytecode.EffectiveScope
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
import io.github.zeuspizza.yoriwake.gradle.change.ClasspathRecord
import io.github.zeuspizza.yoriwake.gradle.change.DependencyRule
import io.github.zeuspizza.yoriwake.gradle.change.RefusalKind
import io.github.zeuspizza.yoriwake.gradle.change.TrackedText
import io.github.zeuspizza.yoriwake.gradle.report.Audit
import io.github.zeuspizza.yoriwake.gradle.wiring.ParallelismDetector
import io.github.zeuspizza.yoriwake.gradle.wiring.parallelRefusalMarker
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
    /** Every execution [YoriwakeLifecycle] left wired, by its key, with the scope the agent records under. */
    private val wired: Map<String, ScopeOutcome>,
    /** The packages of the whole reactor, which the loaded-class union may speak for. */
    private val reactorPackages: List<String>,
) {

    /** The top of the reactor, where git runs and the maps live. */
    private val root: File = (session.request.multiModuleProjectDirectory ?: session.topLevelProject.basedir).absoluteFile
    private val home = File(root, MAPS_HOME)

    /** HEAD and its reflogs before anything compiled; null when git could not be read. */
    private val start: String? = runCatching { CaptureStart.read(root).encode() }.getOrNull()

    /** Per module, the phases this session ran: an execution needs `compile` and `test-compile`. */
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
        /** The JaCoCo agent's scope as the fork is handed it, serialized; null when unreadable. */
        val effectiveScope: String?,
        /** Whether this run's loaded-class lists may stand for the whole execution; see [wholeTaskOf]. */
        val wholeTask: Boolean,
    )

    private val selection = Selection(session, root, log).also { it.validate() }

    /** Each module's pinned tests, read before anything runs so that a pattern it cannot use fails the build. */
    private val pins = session.projects.associate { it.id to Settings.alwaysRunOf(it, session) }

    /** Each module's environment lists and whether it records one test at a time, read now for the same reason. */
    private val environments = session.projects.associate { it.id to Settings.environmentOf(it, session) }
    private val sequential = session.projects.associate { it.id to Settings.recordSequentiallyOf(it, session) }
    private val captureForks = session.projects.associate { it.id to Settings.isolatedCaptureForksOf(it, session) }

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
        // An earlier run's, killed before its decode, would make this run's decode leave the map alone.
        val parallelMarker = parallelRefusalMarker(CoverageDecoder.recordsDir(mapDir))
        parallelMarker.delete()
        val configuration = execution.configuration ?: Xpp3Dom("configuration")
        // Nothing runs, so nothing of the last run's is cleared and the map is not touched.
        if (skipsTests(project, execution, configuration)) return
        val classpath = runCatching { project.testClasspathElements.map(::File) }.getOrDefault(emptyList())
        val inJvm = parallelism(project, execution, configuration, classpath)
        val agentMissing = !reachesJacoco(project, configuration)
        // For the audit, before the declines below, so a declined execution still says what it runs
        // on and why it was declined. A module from a repository is this invocation's, not the build's.
        val framework = frameworkOf(project)
        Audit.TaskFacts.write(mapDir, framework, parallelism = inJvm, agentNotOnArgLine = agentMissing.takeIf { it })
        // A module of this reactor that this invocation did not build comes from a repository, as
        // whatever was installed last: its bytes are not the checkout's.
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
        // Tests interleaved in one JVM share its one JaCoCo agent, and their coverage would blend.
        // Under recordSequentially on the JUnit Platform, a run asked only to record turns the engines'
        // parallel switches off, found or not, and any other run keeps them and captures nothing.
        val covered = sequential[project.id] == true && framework == Audit.TaskFacts.PLATFORM
        val recordsSequentially = covered && selection.asksRecording
        val keepsParallelism = inJvm.takeIf { covered && !selection.asksRecording }
        if (inJvm != null && !covered) {
            log.warn(
                "[yoriwake] $name declined, ${RefusalKind.IN_JVM_PARALLELISM.token}: its tests may run concurrently in one JVM " +
                    "($inJvm); every test runs and nothing is captured" +
                    if (sequential[project.id] == true) {
                        ". -D${SettingNames.RECORD_SEQUENTIALLY} covers JUnit Jupiter and Vintage on the JUnit Platform only"
                    } else ""
            )
            return
        }
        // What reaches the JVM is the authority: an argLine that drops the JaCoCo agent's property
        // records no coverage, whatever the agent's configuration says.
        if (agentMissing) {
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
        // Every run, flagged or not, so an earlier run's lists never pass for this one's.
        val loadedDir = CoverageDecoder.loadedDir(mapDir)
        loadedDir.deleteRecursively()
        startReading(mapDir)

        val runFile = File(mapDir, AgentContract.RUN_FILE)
        val properties = Properties().apply {
            setProperty(AgentContract.MAP_DIR_PROPERTY, mapDir.path)
            setProperty(AgentContract.RECORDS_DIR_PROPERTY, recordsDir.path)
            setProperty(AgentContract.CAPTURE_ROOT_PROPERTY, root.path)
            if (recordsLoaded(project)) setProperty(AgentContract.LOADED_DIR_PROPERTY, loadedDir.path)
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
        // One index listing for this execution's digests, never kept past it: a later execution reads afresh.
        val text = TrackedText(root)
        // On the configuration as the POM set it, before the parallel switches below, so a flagged
        // selecting run, which keeps them on, compares equal to the capture.
        val environment = fingerprints(project, execution, mapDir, configuration, classpath, arguments(host.let { resolved(project, it).orEmpty() }), text)
        val undated = undatedBy(project, execution, configuration)
        val hostArguments = arguments(resolved(project, host).orEmpty())
        val identities = moduleIdentities(project)
        recordClasspath(project, mapDir, classpath, identities, properties)
        val dependencies = if (!Settings.narrowDependenciesOf(project, session)) null else DependencyRule.Context(
            git = selection.git,
            raw = { ChangeDetection.rawGit(root, it) },
            current = { ClasspathRecord.of(classpath, identities, session.allProjects.map { it.build.directory }, root, dependencyHomes(session)) },
            jvmArguments = { forkSystemProperties(project, execution, configuration).map { valueOf(project, it) } + hostArguments },
            configurations = { null },
            ownBuildFile = { path -> path in poms() },
            maven = DependencyRule.Maven(
                poms = { poms() },
                outsideParent = {
                    session.allProjects.any { module ->
                        val parent = module.parent ?: return@any false
                        parent.file?.absoluteFile?.startsWith(root.absoluteFile) != true
                    }
                },
            ),
        )
        val kind = selection.apply(
            name, mapDir, project.basedir.relativeTo(root).invariantSeparatorsPath, view(project, execution, classpath, configuration),
            environment, undated, isolated,
            stamp = { stampOf(project, execution, key, configuration, classpath, hostArguments, text) },
            stopsAfterFailure = parameter(
                project, configuration, "skipAfterFailureCount",
                if (execution.artifactId == FAILSAFE) "failsafe.skipAfterFailureCount" else "surefire.skipAfterFailureCount",
            )
                ?.toIntOrNull()?.let { it > 0 } == true,
            properties = properties,
            alwaysRun = pins[project.id].orEmpty(),
            namedTests = parameter(project, configuration, "test", if (execution.artifactId == FAILSAFE) "it.test" else "test")
                ?.let { "tests were named with -D${if (execution.artifactId == FAILSAFE) "it.test" else "test"}=$it" },
            text = text,
            dependencies = dependencies,
            narrowLineOnlyChanges = Settings.narrowLineOnlyChangesOf(project, session),
        )
        // A selecting or complement execution may leave out every test, which Surefire would otherwise fail on.
        if (kind == RunPlan.Kind.SELECT || kind == RunPlan.Kind.COMPLEMENT) child(configuration, "failIfNoTests").value = "false"
        // Only once the kind is known: a run that stays selecting or complementing keeps the build's forks,
        // and so does one that keeps its in-JVM parallelism, which captures nothing.
        if (isolated && keepsParallelism == null && (kind == RunPlan.Kind.RECORD || kind == RunPlan.Kind.OBSERVE)) {
            captureForks[project.id]?.let { raiseForks(name, project, configuration, it.resolve()) }
        }
        if (recordsSequentially) recordOneAtATime(name, mapDir, configuration, properties, inJvm)
        keepsParallelism?.let { keepParallelism(name, mapDir, parallelMarker, properties, it) }
        execution.configuration = configuration
        writeAtomically(runFile, buildString {
            properties.stringPropertyNames().sorted().forEach { append(escape(it)).append('=').append(escape(properties.getProperty(it))).append('\n') }
            append(AgentContract.RUN_FILE_END).append('\n')
        })
        val scope = EffectiveScope.fromAgentArguments(hostArguments + arguments(resolved(project, toolOptionsOf(configuration)).orEmpty()))
        running[key] = Run(mapDir, undated, classpath, reports, isolated, kind, scope?.serialize(), wholeTaskOf(project, execution, configuration, undated))
    }

    /** Every model file read this session that lies in the checkout: each module's POM, and its parents'. */
    private fun poms(): List<String> =
        session.allProjects.flatMap { module -> generateSequence(module) { it.parent }.mapNotNull { it.file } }
            .mapNotNull { pom -> pom.absoluteFile.relativeToOrNull(root.absoluteFile)?.invariantSeparatorsPath }
            .filterNot { it.startsWith("..") }.distinct()

    /**
     * Each library module file of [project]'s resolved test classpath, with its identity, as the Gradle
     * plugin names them: `groupId:artifactId`, then `:classifier` and `@extension` when not a plain jar.
     */
    private fun moduleIdentities(project: MavenProject): Map<File, String> =
        project.artifacts.orEmpty().mapNotNull { artifact ->
            val file = artifact.file ?: return@mapNotNull null
            val extension = artifact.artifactHandler?.extension ?: file.extension
            val classifier = artifact.classifier.orEmpty()
            file.absoluteFile to "${artifact.groupId}:${artifact.artifactId}" +
                (if (classifier.isEmpty()) "" else ":$classifier") + (if (extension == "jar") "" else "@$extension")
        }.toMap()

    /**
     * The resolved test classpath, for the decode to keep beside the map, and the library jars on it,
     * for the agent to tell their touches apart; see [ClasspathRecord]. None recorded leaves a later
     * bump forcing until a capture records one.
     */
    private fun recordClasspath(project: MavenProject, mapDir: File, classpath: List<File>, identities: Map<File, String>, properties: Properties) {
        val modules = File(mapDir, ClasspathRecord.MODULES_FILE)
        ClasspathRecord.pending(mapDir).delete()
        ClasspathRecord.pendingDirs(mapDir).delete()
        modules.delete()
        properties.setProperty(AgentContract.JAR_MODULES_PROPERTY, modules.path)
        runCatching {
            val record = ClasspathRecord.of(classpath, identities, session.allProjects.map { it.build.directory }, root, dependencyHomes(session))
            ClasspathRecord.write(ClasspathRecord.pending(mapDir), ClasspathRecord.pendingDirs(mapDir), record)
            ClasspathRecord.writeModules(modules, record)
        }.onFailure { log.debug("[yoriwake] ${project.artifactId}: no resolved classpath recorded ($it)") }
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
            wholeTask = run.wholeTask,
            loadedScope = reactorPackages,
            datesTheMap = run.undatedReason == null,
            undatedReason = run.undatedReason.orEmpty(),
            effectiveScope = run.effectiveScope,
            isolated = run.isolated,
            scheduleSet = selection.schedules,
            rootDir = root,
            mapDir = run.mapDir,
            facts = view(project, execution, classpath, execution.configuration),
            taskArtifacts = { TaskArtifacts(classpath, buildDirs, listOf(File(project.build.testOutputDirectory))) },
            junitXml = run.reports,
            loadedFlag = "-D${INTERNAL_LOADED}",
            buildState = { isBuildState(it) },
            logger = object : DecodeRun.Log {
                override fun lifecycle(message: String) = log.info(message)
                override fun warn(message: String) = log.warn(message)
                override fun info(message: String) = log.debug(message)
            },
            optimistic = run.kind == RunPlan.Kind.SELECT && selection.optimistic,
            fullRunPolicy = selection.fullRunPolicy,
        ).run()
    }

    private fun view(project: MavenProject, execution: MojoExecution, classpath: List<File>, configuration: Xpp3Dom?): ClasspathView =
        classpathView(session, root, project, classpath, configuration, execution.artifactId == FAILSAFE)

    /**
     * The framework Surefire runs [project]'s tests on, as it picks a provider from the resolved test
     * classpath: the JUnit Platform when any of it is there, else TestNG, else JUnit 4.
     */
    private fun frameworkOf(project: MavenProject): String {
        val artifacts = project.artifacts.map { "${it.groupId}:${it.artifactId}" }
        return when {
            artifacts.any { it.startsWith("org.junit.platform:") } -> Audit.TaskFacts.PLATFORM
            "org.testng:testng" in artifacts -> "TestNG"
            "junit:junit" in artifacts -> Audit.TaskFacts.JUNIT4
            else -> "JUnit 3 or plain test classes"
        }
    }

    /** `forkCount` up to [forks], never below what the POM or `-DforkCount` already asks for. */
    private fun raiseForks(name: String, project: MavenProject, configuration: Xpp3Dom, forks: Int) {
        val own = parameter(project, configuration, "forkCount", "forkCount")?.let(Declines::forks) ?: 1
        val raised = maxOf(own, forks)
        child(configuration, "forkCount").value = raised.toString()
        log.info(
            "[yoriwake] $name: recording with a fresh test JVM per test class (-D${SettingNames.ISOLATED_CAPTURE}), " +
                "up to $raised at a time"
        )
    }

    /** A Surefire or Failsafe parameter as this execution resolves it: its POM value, else its user property. */
    private fun parameter(project: MavenProject, configuration: Xpp3Dom, name: String, property: String): String? =
        configuration.getChild(name)?.value?.trim()?.takeUnless { it.isEmpty() || it.startsWith("\${") }
            ?: Declines.property(project, session, property)

    /**
     * Turns Jupiter's and Vintage's parallel switches off for this run as launcher configuration
     * parameters, which outrank system properties, the user properties Surefire promotes and a
     * `junit-platform.properties` file. Surefire hands the launcher only its
     * `<properties><configurationParameters>`. The test JVM confirms it before recording.
     */
    private fun recordOneAtATime(name: String, mapDir: File, configuration: Xpp3Dom, properties: Properties, source: String?) {
        val switches = ParallelismDetector.SWITCHES.joinToString("\n") { "$it=false" }
        val holder = child(configuration, "properties")
        // The `<property><name/><value/></property>` form, when the POM used it for these parameters.
        val parameters = holder.children.firstOrNull { it.name == "property" && it.getChild("name")?.value?.trim() == "configurationParameters" }
            ?.let { child(it, "value") }
            ?: child(holder, "configurationParameters")
        // The last of a name given twice wins.
        parameters.value = listOfNotNull(parameters.value?.takeIf(String::isNotBlank), switches).joinToString("\n")
        properties.setProperty(AgentContract.PARALLELISM_OVERRIDE_PROPERTY, "true")
        properties.setProperty(AgentContract.PARALLELISM_PROPERTY, AgentContract.PARALLELISM_RECORDED_SEQUENTIALLY)
        Audit.TaskFacts.recordParallelismOverride(mapDir, AgentContract.PARALLELISM_RECORDED_SEQUENTIALLY)
        source?.let {
            log.info(
                "[yoriwake] $name: recording one test at a time (-D${SettingNames.RECORD_SEQUENTIALLY}): parallel execution " +
                    "via $it is turned off for this run, and each test JVM confirms it before recording"
            )
        }
    }

    /**
     * Runs a flagged suite's tests with its own parallel scheduling and captures nothing: no records
     * directory, and the marker on which the decode leaves the map exactly as it was. The build's
     * JaCoCo agent stays, as the build set it.
     */
    private fun keepParallelism(name: String, mapDir: File, marker: File, properties: Properties, source: String) {
        runCatching { writeAtomically(marker, "$source\n") }.onFailure {
            log.warn("[yoriwake] $name: could not record that this run captures nothing ($it); nothing is captured either way")
        }
        properties.setProperty(AgentContract.RECORDS_DIR_PROPERTY, "")
        properties.setProperty(AgentContract.PARALLELISM_PROPERTY, AgentContract.PARALLELISM_KEPT_NOT_CAPTURED)
        Audit.TaskFacts.recordParallelismOverride(mapDir, AgentContract.PARALLELISM_KEPT_NOT_CAPTURED)
        log.info(
            "[yoriwake] $name: tests run in parallel as configured (via $source), so this run captures nothing; " +
                "only a recording run records the map, one test at a time"
        )
    }

    /** This execution's selection-record stamp; see [MavenStamp]. */
    private fun stampOf(
        project: MavenProject,
        execution: MojoExecution,
        key: String,
        configuration: Xpp3Dom,
        classpath: List<File>,
        hostArguments: List<String>,
        text: TrackedText,
    ) = MavenStamp.observe(
        selection.git, root, key, project.id, classpath,
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
        } +
            // Beside the map, which keeps one value per name: the other sources Surefire reads its system properties from.
            forkSystemProperties(project, execution, configuration).filter { it.source != ForkSystemProperty.SYSTEM_PROPERTY_VARIABLES }
                .map { "${it.source} ${it.name}=${valueOf(project, it)}" },
        text,
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

    /** Whether `-Dyoriwake.internal.loaded` asks this run's test JVMs to list the classes they load. */
    private fun recordsLoaded(project: MavenProject): Boolean =
        Declines.property(project, session, INTERNAL_LOADED)?.let { it.isEmpty() || it.equals("true", ignoreCase = true) } == true

    /**
     * Whether this run's loaded-class union may speak for the whole execution, as Gradle's
     * unfiltered task: flagged, dating the map, and not narrowed by groups, tags, JUnit 5 engines or
     * TestNG suite files. POM `includes` and `excludes` say which classes are tests, as a Gradle
     * task's own do, and filter nothing. Failsafe never: its tests commonly drive an application
     * another JVM runs, whose loaded classes no union sees.
     */
    private fun wholeTaskOf(project: MavenProject, execution: MojoExecution, configuration: Xpp3Dom, undated: String?): Boolean {
        if (!recordsLoaded(project) || undated != null || execution.artifactId == FAILSAFE) return false
        // A value given through an expression counts unless it resolves to nothing.
        fun given(value: String?) = resolved(project, value?.trim()) != null
        fun listed(name: String) = configuration.getChild(name).let { list -> given(list?.value) || list?.children.orEmpty().any { given(it.value) } }
        // The JUnit Platform provider still reads tag filters from `<properties>`, in either form.
        val properties = configuration.getChild("properties")?.children.orEmpty()
            .associate { if (it.name == "property") it.getChild("name")?.value?.trim() to it.getChild("value")?.value else it.name to it.value }
        val byGroups = listOf("groups", "excludedGroups").any { given(configuration.getChild(it)?.value) || given(Declines.property(project, session, it)) } ||
            listOf("includeTags", "excludeTags").any { given(properties[it]) }
        val byEngines = listOf("includeJUnit5Engines", "excludeJUnit5Engines").any { listed(it) || given(Declines.property(project, session, "surefire.$it")) }
        val bySuites = listed("suiteXmlFiles") || given(Declines.property(project, session, "surefire.suiteXmlFiles"))
        return !byGroups && !byEngines && !bySuites
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
     * `configurationParameters` and its other `properties`, the system properties it hands the fork
     * (its `systemProperties`, the file `systemPropertiesFile` names, `systemPropertyVariables`, then
     * the command line's), the `argLine`, and a `junit-platform.properties` on the test classpath.
     */
    private fun parallelism(project: MavenProject, execution: MojoExecution, configuration: Xpp3Dom, classpath: List<File>): String? {
        val parameters = mutableMapOf<String, String?>()
        fun read(text: String?) = text?.let { runCatching { Properties().apply { load(java.io.StringReader(it)) } }.getOrNull() }
            ?.forEach { (k, v) -> parameters[k.toString()] = v.toString() }
        val launcher = mutableListOf(configuration.getChild("configurationParameters")?.value)
        configuration.getChild("properties")?.children?.forEach { child ->
            // The `<property><name/><value/></property>` form, when the POM used it.
            val named = child.takeIf { it.name == "property" }?.getChild("name")
            val name = if (named != null) named.value?.trim() else child.name
            val value = if (named != null) child.getChild("value")?.value else child.value
            if (name == "configurationParameters") launcher += value else if (name != null) parameters[name] = value
        }
        // The fork's system properties in the order Surefire applies them, a later one winning: its
        // configuration, then the command line's -D user properties.
        forkSystemProperties(project, execution, configuration).forEach { parameters[it.name] = valueOf(project, it) }
        session.userProperties.forEach { (k, v) -> parameters[k.toString()] = v?.toString() }
        // The launcher's configuration parameters outrank the JVM's system properties.
        launcher.forEach(::read)
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
        execution: MojoExecution,
        mapDir: File,
        configuration: Xpp3Dom,
        classpath: List<File>,
        hostArguments: List<String>,
        text: TrackedText,
    ): Pair<EnvironmentFingerprint.View, EnvironmentFingerprint.Rules>? {
        val classpathPending = pendingClasspathFiles(mapDir)
        classpathPending.delete()
        // The local repository counts as a dependency home even inside the checkout.
        val buildDirs = session.allProjects.map { it.build.directory }
        when (val walk = runCatching { ClasspathFiles.table(classpath, buildDirs, root, dependencyHomes(session), text = text) }
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
            // Surefire promotes the invocation's user properties to the test JVM's system properties,
            // after the POM's; the core compares a name given twice by every value.
            val userProperties = if (promotesUserProperties(project, execution, configuration)) {
                session.userProperties.stringPropertyNames().sorted().map { "-D$it=${session.userProperties.getProperty(it)}" }
            } else emptyList()
            val view = EnvironmentFingerprint.View(
                // Maven's own JVM forks the tests unless the POM names another, whose version nobody read.
                if (otherJdk) null else System.getProperty("java.runtime.version"),
                if (otherJdk) null else System.getProperty("java.vendor"),
                jvm, System.getProperty("os.name"), System.getProperty("os.arch"),
                // As Gradle's JVM arguments carry its system properties.
                hostArguments + forkSystemProperties(project, execution, configuration)
                    .map { "-D${it.name}=${valueOf(project, it)}" } + userProperties,
                environment,
                // A map recorded before user properties were compared stored none of them, and would
                // otherwise match a run under a property it never saw.
                plain = mapOf(USER_PROPERTIES_COMPONENT to "compared"),
            )
            val lists = environments[project.id] ?: Settings.Environment(emptyList(), emptyList())
            val rules = EnvironmentFingerprint.Rules(
                lists.systemPropertyIgnore, lists.environmentInclude, root.path,
                session.localRepository?.basedir ?: File(System.getProperty("user.home"), ".m2/repository").path,
                System.getProperty("user.home"),
            )
            EnvironmentFingerprint.writePending(mapDir, view, rules)
            view to rules
        }.onFailure { log.debug("[yoriwake] ${project.artifactId}: no environment fingerprint recorded ($it)") }.getOrNull()
    }

    /**
     * Whether Surefire or Failsafe hands the fork the invocation's user properties: always before
     * 3.4.0, and from then unless `promoteUserPropertiesToSystemProperties` is false.
     */
    private fun promotesUserProperties(project: MavenProject, execution: MojoExecution, configuration: Xpp3Dom): Boolean {
        val value = resolved(project, configuration.getChild("promoteUserPropertiesToSystemProperties")?.value)?.trim()
        if (!value.equals("false", ignoreCase = true)) return true
        val version = execution.version.orEmpty().split('.', '-').map { it.toIntOrNull() ?: 0 }
        val major = version.getOrElse(0) { 0 }
        return major < 3 || (major == 3 && version.getOrElse(1) { 0 } < 4)
    }

    /**
     * Whether JaCoCo's agent reaches the fork: in the argLine it will get, its expressions resolved,
     * or in the `JAVA_TOOL_OPTIONS` every JVM reads. JaCoCo quotes an agent path holding a space.
     */
    private fun reachesJacoco(project: MavenProject, configuration: Xpp3Dom): Boolean {
        val agent = Regex("""-javaagent:("[^"]*|\S*)(jacocoagent|org\.jacoco\.agent)""")
        return listOfNotNull(resolvedArgLine(project, configuration), toolOptionsOf(configuration)).any(agent::containsMatchIn)
    }

    /** The argLine the fork will get, its `${x}` and `@{x}` resolved as Maven and Surefire resolve them. */
    private fun resolvedArgLine(project: MavenProject, configuration: Xpp3Dom): String =
        resolved(project, configuration.getChild("argLine")?.value ?: "\${argLine}").orEmpty()

    /** What Surefire hands the fork from its configuration as system properties; see [forkSystemProperties]. */
    private fun forkSystemProperties(project: MavenProject, execution: MojoExecution, configuration: Xpp3Dom) =
        forkSystemProperties(project, session, configuration, execution.artifactId == FAILSAFE)

    /** [property]'s value as the fork gets it: the POM's resolved, a file's as written. */
    private fun valueOf(project: MavenProject, property: ForkSystemProperty): String =
        if (property.fromPom) resolved(project, property.value).orEmpty() else property.value

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
        val excluded = WorkingTree.excluded(root, session.allProjects.map { it.build.directory }) + BUILD_STATE
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

        /** The id Develocity's Maven extension writes for build scans; no build step or test reads it. */
        const val DEVELOCITY_WORKSPACE_ID = ".mvn/.develocity/develocity-workspace-id"

        /** What yoriwake and the tools beside it write into the project, untracked, on every build. */
        val BUILD_STATE = listOf(MAPS_HOME, DEVELOCITY_WORKSPACE_ID)

        /**
         * Whether an untracked path git names is the workspace id, relative to the build's root or,
         * as `git status` names it, to a repository the build lies below.
         */
        fun isBuildState(path: String) = path == DEVELOCITY_WORKSPACE_ID || path.endsWith("/$DEVELOCITY_WORKSPACE_ID")
        const val FAILSAFE = "maven-failsafe-plugin"
        const val INTERNAL_LOADED = "yoriwake.internal.loaded"

        /** The fingerprint component saying the invocation's user properties are compared. */
        private const val USER_PROPERTIES_COMPONENT = "maven.user-properties"
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
