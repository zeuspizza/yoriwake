package io.github.zeuspizza.yoriwake.gradle.tasks

import io.github.zeuspizza.yoriwake.gradle.Settings
import io.github.zeuspizza.yoriwake.gradle.bytecode.TaskArtifacts
import io.github.zeuspizza.yoriwake.gradle.bytecode.scanOrExplain
import io.github.zeuspizza.yoriwake.gradle.facts.ClasspathFacts
import io.github.zeuspizza.yoriwake.gradle.facts.classpathFacts
import io.github.zeuspizza.yoriwake.gradle.report.Audit
import io.github.zeuspizza.yoriwake.gradle.report.Payback
import io.github.zeuspizza.yoriwake.gradle.report.audit
import io.github.zeuspizza.yoriwake.gradle.report.blockers
import io.github.zeuspizza.yoriwake.gradle.report.ownClassCoverage
import io.github.zeuspizza.yoriwake.gradle.report.taskPayback
import io.github.zeuspizza.yoriwake.gradle.report.write
import io.github.zeuspizza.yoriwake.gradle.wiring.ScopeOutcome
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.api.tasks.testing.Test
import java.io.File

/**
 * `yoriwakeAudit<Task>`: how narrowable this suite is, or a refusal saying what could not be
 * shown. A property of the map alone, unlike `yoriwakeExplain`. The instrumentation toll is not
 * derivable from a map, see [Audit].
 */
@UntrackedTask(because = "it reads the map and every other task's payback file, which change between runs")
internal abstract class AuditTask : DefaultTask() {

    @get:Input
    abstract val taskPath: Property<String>

    @get:Input
    abstract val captureTask: Property<String>

    /** The packages the scope names, for the inline scan. */
    @get:Input
    abstract val prefixes: ListProperty<String>

    // Kept raw, not parsed: a value that does not parse (a decimal comma) must be refused by
    // name, not read as absent.
    @get:Input
    @get:Optional
    abstract val forcedShare: Property<String>

    @get:Internal("read, with its siblings, through the audit's own readers")
    lateinit var mapDir: File

    @get:OutputFile
    abstract val auditFile: RegularFileProperty

    @get:Internal("the module's compiled classes, counted, never depended on")
    lateinit var ownClassesDir: File

    /** Null unless the plugin declined the `Test` task. */
    @get:Internal("the decline itself, reported as a blocker")
    var scopeRefusal: ScopeOutcome.NotApplied? = null

    // Internal for the reason explain's are: an input would depend on the compile tasks.
    @get:Internal("the classpath the inline scan reads without depending on the tasks that build it")
    var facts: ClasspathFacts? = null

    /** Null without the flag, and the audit is map-only: no code path here runs a test. */
    @get:Internal("timings passed in by a script, already parsed")
    var toll: Audit.Toll? = null

    // Internal, so Gradle never resolves it early: it must resolve when the entry stores, after
    // every project's git calls.
    @get:Internal("resolved when the configuration-cache entry stores, never fingerprinted")
    abstract val gitFailed: Property<Boolean>

    @get:Internal("a timing of this build's configuration, which differs on every build")
    abstract val configureCost: Property<Payback.ConfigureCost>

    @TaskAction
    fun audit() {
        val taskPath = taskPath.get()
        val prefixes = prefixes.get()
        val facts = facts
        // Read here: a provider captured by a task action resolves when the entry stores,
        // so it sees the whole build's git failures.
        val gitUnavailable = gitFailed.getOrElse(false)
        val coverage = facts?.let { Audit.ownClassCoverage(mapDir, mainClassNames(ownClassesDir)) }
        val blockers = Audit.blockers(
            Audit.TaskFacts.read(mapDir),
            scopeRefusal,
            // The same scan a selecting build runs; it starts no build.
            facts?.let {
                // A throw is a refusal, not an absent answer. A classpath Gradle refuses
                // to resolve is a fact about this audit, not a blocker for the selector.
                scanOrExplain(
                    resolve = {
                        TaskArtifacts(
                            it.classpath.files, it.buildDirs.values, it.testOutputs.files,
                        )
                    },
                    scan = { artifacts -> artifacts.classesInlining(prefixes) },
                )
            },
            // The module's own classes against how many the map has seen: the same
            // population on both sides of the ratio.
            compiledClasses = coverage?.second,
            mapClasses = coverage?.first,
            gitUnavailable = gitUnavailable,
        )
        val result = Audit.audit(
            mapDir, taskPath, captureTask.get(), Audit.DEFAULT_UNATTRIBUTABLE_CEILING, toll, blockers,
            configureCost = configureCost.orNull,
        )
        // Written before anything is logged, as `yoriwakeExplain` does.
        Audit.write(mapDir, result)
        // This task's contribution to a project-wide answer, written even when it refuses,
        // so the aggregate never reports a partial project as complete.
        Payback.writeTask(
            mapDir,
            Audit.taskPayback(taskPath, result, configureCost.orNull, toll),
        )
        logger.lifecycle("[yoriwake] ${result.state.token} -- ${result.headline}")
        result.lines.forEach { logger.lifecycle("[yoriwake]   $it") }
        // Whether selection pays for itself across this build. Printed last, as the weaker
        // claim built from other runs' files.
        Payback.aggregateLines(
            Payback.readTasks(mapDir.parentFile),
            forcedShare.orNull,
            // The build's own count of configured `Test` tasks, so a first audit does not
            // read "1 of 1 answered".
            configureCost.orNull?.testTasks,
        ).forEach { logger.lifecycle("[yoriwake]   $it") }
    }

    /**
     * The classes this module compiled from its own main sources, by fully qualified name. An
     * unrecognised layout yields an empty set, which reports nothing.
     */
    private fun mainClassNames(classesDir: File): Set<String> = runCatching {
        if (!classesDir.isDirectory) return emptySet()
        val marker = "${File.separator}main${File.separator}"
        classesDir.walkTopDown()
            .filter { it.isFile && it.extension == "class" && it.path.contains(marker) }
            .map { file ->
                // `build/classes/<language>/main/<package>/<Name>.class`.
                file.absolutePath.substringAfter(marker)
                    .removeSuffix(".class")
                    .replace(File.separatorChar, '.')
                    .replace('/', '.')
            }
            .toSet()
    }.getOrElse { emptySet() }

    internal companion object {
        fun nameFor(testName: String) = "yoriwakeAudit${testName.replaceFirstChar(Char::uppercase)}"

        /**
         * Registers the task beside [testName]. Configured only when something asks for it; [outcome]
         * then realises the `Test` task and answers its map directory, the plugin's decline or
         * null, and the derived scope.
         */
        fun register(
            project: Project,
            testName: String,
            settings: Settings,
            outcome: (Test) -> Triple<File, ScopeOutcome.NotApplied?, List<String>?>,
            gitFailed: Provider<Boolean>? = null,
            configureCost: Provider<Payback.ConfigureCost>? = null,
        ) {
            project.tasks.register(nameFor(testName), AuditTask::class.java) { task ->
                task.group = "verification"
                task.description =
                    "Reports how narrowable this suite is, from the map alone, without running tests"
                val test = project.tasks.named(testName, Test::class.java).get()
                val (mapDir, scopeRefusal, scope) = outcome(test)
                task.taskPath.set(test.path)
                task.captureTask.set(test.name)
                task.mapDir = mapDir
                task.auditFile.set(File(mapDir, Audit.AUDIT_FILE))
                task.scopeRefusal = scopeRefusal
                task.toll = tollFrom(settings, Audit.DEFAULT_NOISE_FLOOR)
                // The adopter's own forced-run share, never a default.
                task.forcedShare.set(settings.forcedShare)
                // Null when the plugin declined this task. The audit never reaches
                // `modulesOnClasspath`, hence the empty change set.
                task.facts =
                    if (scopeRefusal == null) classpathFacts(project, test, changedPaths = emptyList()) else null
                task.prefixes.set(scope.orEmpty().map { it.removeSuffix("*").removeSuffix(".") })
                task.ownClassesDir = project.layout.buildDirectory.get().asFile.resolve("classes")
                // A declined task has no capture to charge for and no change set git could fail.
                if (scopeRefusal == null) {
                    gitFailed?.let(task.gitFailed::set)
                    configureCost?.let(task.configureCost::set)
                }
            }
        }

        /**
         * Reads the two timings the payback verdict needs. It cannot run them: a nested `./gradlew`
         * would wait on file locks the outer build holds, forever. They arrive as properties, e.g.
         * from `scripts/measure-toll.sh`.
         */
        private fun tollFrom(settings: Settings, floor: Double): Audit.Toll? {
            if (!settings.measureToll) {
                return null
            }
            val instrumented = settings.instrumentedSeconds
            val uninstrumented = settings.uninstrumentedSeconds
            if (instrumented == null || uninstrumented == null) {
                return Audit.Toll.failed(
                    if (instrumented == null) "instrumented" else "uninstrumented",
                    "no timing was supplied for it. Run scripts/measure-toll.sh, which times both " +
                        "runs and calls this task back with them, or pass " +
                        "-P${Settings.INSTRUMENTED_SECONDS} and -P${Settings.UNINSTRUMENTED_SECONDS} " +
                        "yourself. This task cannot run them itself: a nested build on this " +
                        "project would wait for file locks this build is holding.",
                )
            }
            val instrumentedSeconds = instrumented.toDoubleOrNull()
            val uninstrumentedSeconds = uninstrumented.toDoubleOrNull()
            if (instrumentedSeconds == null || uninstrumentedSeconds == null) {
                return Audit.Toll.failed(
                    if (instrumentedSeconds == null) "instrumented" else "uninstrumented",
                    "its timing was not a number: " +
                        "'${if (instrumentedSeconds == null) instrumented else uninstrumented}'.",
                )
            }
            return Audit.Toll.measured(instrumentedSeconds, uninstrumentedSeconds, floor)
        }
    }
}
