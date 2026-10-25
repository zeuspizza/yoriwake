package io.github.zeuspizza.yoriwake.gradle.tasks

import io.github.zeuspizza.yoriwake.gradle.Settings
import io.github.zeuspizza.yoriwake.gradle.bytecode.TaskArtifacts
import io.github.zeuspizza.yoriwake.gradle.capture.DecodeRun
import io.github.zeuspizza.yoriwake.gradle.facts.ClasspathFacts
import io.github.zeuspizza.yoriwake.gradle.facts.classpathFacts
import io.github.zeuspizza.yoriwake.gradle.wiring.AfterTest
import io.github.zeuspizza.yoriwake.gradle.wiring.ScopeOutcome
import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.api.tasks.testing.Test

/**
 * Decodes the run's records into the map the selector will read. A run that captured nothing
 * leaves the previous map untouched: an empty map would select nothing.
 *
 * A finalizer of its `Test` task, not a doLast: Gradle skips a failed task's own actions, and a
 * test task fails whenever a test does. Finalizers run either way.
 */
@UntrackedTask(because = "it merges this run's records into the map in place")
internal abstract class DecodeTask : DefaultTask() {

    @get:Input
    abstract val taskPath: Property<String>

    /** The applied or adopted scope, as package prefixes. */
    @get:Input
    abstract val patterns: ListProperty<String>

    @get:Input
    abstract val selecting: Property<Boolean>

    @get:Input
    abstract val observing: Property<Boolean>

    /** Whether the run was asked to select, and so leaves or removes a selection record. */
    @get:Input
    abstract val asksSelection: Property<Boolean>

    /** Whether the run leaves out what a selecting run's record lists as ran. */
    @get:Input
    abstract val complementing: Property<Boolean>

    @get:Input
    abstract val wholeTask: Property<Boolean>

    @get:Input
    abstract val loadedScope: ListProperty<String>

    @get:Input
    abstract val datesTheMap: Property<Boolean>

    @get:Input
    abstract val undatedReason: Property<String>

    @get:Input
    @get:Optional
    abstract val effectiveScope: Property<String>

    @get:Input
    abstract val isolated: Property<Boolean>

    /** Whether `fullRunEveryBuilds` or `fullRunEveryDays` is set, so the decode keeps their count. */
    @get:Input
    abstract val scheduleSet: Property<Boolean>

    @get:Internal("where git runs, not a set of files this task reads")
    lateinit var rootDir: File

    @get:Internal("the map directory, read, merged and rewritten in place")
    lateinit var mapDir: File

    @get:Internal("the classpath the digests read without depending on the tasks that build it")
    lateinit var facts: ClasspathFacts

    @get:Internal("work its test task's own doLast did not reach when a test failed")
    lateinit var afterTest: AfterTest

    @get:Internal("Gradle's JUnit XML for the run, read for failures a stopped test JVM never recorded")
    var junitXml: File? = null

    @TaskAction
    fun decode() {
        val taskPath = taskPath.get()
        // First, so a report that runs after this task reads the restored file.
        afterTest.runIfPending(taskPath, logger)
        val facts = facts
        DecodeRun(
            taskPath = taskPath,
            patterns = patterns.get(),
            selecting = selecting.get(),
            observing = observing.getOrElse(false),
            asksSelection = asksSelection.getOrElse(false),
            complementing = complementing.getOrElse(false),
            wholeTask = wholeTask.getOrElse(false),
            loadedScope = loadedScope.getOrElse(emptyList()),
            datesTheMap = datesTheMap.getOrElse(false),
            undatedReason = undatedReason.getOrElse(""),
            effectiveScope = effectiveScope.orNull,
            isolated = isolated.getOrElse(false),
            scheduleSet = scheduleSet.getOrElse(false),
            rootDir = rootDir,
            mapDir = mapDir,
            facts = facts,
            taskArtifacts = { TaskArtifacts(facts.classpath.files, facts.buildDirs.values, facts.testOutputs.files) },
            junitXml = junitXml,
            loadedFlag = "-P${Settings.LOADED}",
            logger = object : DecodeRun.Log {
                override fun lifecycle(message: String) = logger.lifecycle(message)
                override fun warn(message: String) = logger.warn(message)
                override fun info(message: String) = logger.info(message)
            },
        ).run()
    }

    /** What the `Test` task's wiring decided that the decode reads back. */
    internal class Inputs(
        val mapDir: File,
        val scope: ScopeOutcome,
        val selecting: Boolean,
        /** Whether this run observes selection, and so writes `observation.json`. */
        val observing: Boolean,
        /** Whether this run was asked to select, declined or not. */
        val asksSelection: Boolean,
        /** Whether this run complements a selecting run. */
        val complementing: Boolean,
        val wholeTask: Provider<Boolean>,
        val loadedScope: Provider<List<String>>,
        val datesTheMap: Provider<Boolean>,
        /** Why a run that cannot date the map does not; empty when it can. */
        val undatedReason: Provider<String>,
        val effectiveScope: Provider<String>,
        /** Whether this run forked a JVM per test class while it captured. */
        val isolated: Boolean,
        val afterTest: AfterTest,
        /** Whether a scheduled full run is configured, so the decode keeps its count. */
        val scheduleSet: Boolean = false,
    )

    internal companion object {
        fun nameFor(testName: String) = "yoriwakeDecode${testName.replaceFirstChar(Char::uppercase)}"

        /**
         * Registers the finalizer beside [testName]; the wiring attaches it. [inputs] answers null
         * for a `Test` task the plugin declined, which leaves this task nothing to do.
         */
        fun register(project: Project, testName: String, inputs: (Test) -> Inputs?) {
            project.tasks.register(nameFor(testName), DecodeTask::class.java) { task ->
                val test = project.tasks.named(testName, Test::class.java).get()
                val wired = inputs(test)
                task.taskPath.set(test.path)
                if (wired == null) {
                    // Attached to nothing, so this runs only if asked for by name.
                    task.enabled = false
                    return@register
                }
                task.mapDir = wired.mapDir
                task.patterns.set(
                    when (val scope = wired.scope) {
                        is ScopeOutcome.Applied -> scope.patterns
                        is ScopeOutcome.AdoptedFromHost -> scope.patterns
                        is ScopeOutcome.NotApplied -> emptyList()
                    }.map { it.removeSuffix("*").removeSuffix(".") }.filter(String::isNotEmpty)
                )
                task.selecting.set(wired.selecting)
                task.observing.set(wired.observing)
                task.asksSelection.set(wired.asksSelection)
                task.complementing.set(wired.complementing)
                task.wholeTask.set(wired.wholeTask)
                task.loadedScope.set(wired.loadedScope)
                task.datesTheMap.set(wired.datesTheMap)
                task.undatedReason.set(wired.undatedReason)
                task.effectiveScope.set(wired.effectiveScope)
                task.isolated.set(wired.isolated)
                task.scheduleSet.set(wired.scheduleSet)
                task.afterTest = wired.afterTest
                // A File, not the property: wired as a property it carries the test task as its
                // producer, which the configuration cache cannot store on this task. None when the
                // report is off, since a directory left from an earlier run would then never change.
                task.junitXml = test.reports.junitXml.outputLocation.asFile.orNull
                    ?.takeIf { test.reports.junitXml.required.getOrElse(true) }
                task.rootDir = project.rootDir
                // Empty, stated rather than defaulted: these facts never reach
                // `modulesOnClasspath`, so resolving `declared` would buy nothing.
                task.facts = classpathFacts(project, test, changedPaths = emptyList())
            }
        }
    }
}
