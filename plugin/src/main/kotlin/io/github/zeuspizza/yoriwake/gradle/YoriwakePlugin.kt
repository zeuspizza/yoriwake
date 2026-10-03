package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.gradle.capture.MapLocation
import io.github.zeuspizza.yoriwake.gradle.change.ChangeDetection
import io.github.zeuspizza.yoriwake.gradle.facts.BuildMemo
import io.github.zeuspizza.yoriwake.gradle.tasks.AuditTask
import io.github.zeuspizza.yoriwake.gradle.wiring.ScopeOutcome
import io.github.zeuspizza.yoriwake.gradle.wiring.TestTaskWiring
import io.github.zeuspizza.yoriwake.gradle.wiring.cacheDir
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import java.io.File

/**
 * Applies per-test coverage selection to a project's test tasks.
 *
 * Two halves ship together: this plugin, which runs in the Gradle daemon and configures the build,
 * and an agent jar that rides inside each test JVM. They share only a map directory and a few
 * system properties; the agent carries nothing unshaded onto a foreign test classpath.
 *
 * Configuration happens after evaluation, so the host build's own conventions cannot overwrite
 * these settings afterwards.
 *
 * The plugin is a guest in someone else's build: it observes and, when asked, deselects. A build
 * that already scopes its own JaCoCo coverage keeps that scope.
 */
public abstract class YoriwakePlugin : Plugin<Project> {

    /**
     * Gradle's own answer to "is Isolated Projects on", injected rather than looked up
     * reflectively, which answered false on a build that had the feature enabled.
     */
    @get:javax.inject.Inject
    protected abstract val buildFeatures: org.gradle.api.configuration.BuildFeatures

    private lateinit var settings: Settings
    private lateinit var wiring: TestTaskWiring

    override fun apply(project: Project) {
        settings = Settings.of(project)
        wiring = TestTaskWiring(settings)
        val extension = project.extensions.create("yoriwake", YoriwakeExtension::class.java)


        // Before any host-plugin hook: the decline must come before the first cross-project read.
        if (declineOldGradle(project, extension)) {
            return
        }
        if (declineIsolatedProjects(project, extension)) {
            return
        }
        val configured = java.util.concurrent.atomic.AtomicBoolean(false)
        HOST_PLUGINS.forEach { pluginId ->
            project.plugins.withId(pluginId) {
                // Once, however many of these a module applies.
                if (configured.compareAndSet(false, true)) {
                    configureWhenEvaluated(project, extension)
                }
            }
        }
        attachWhenMultiplatform(project, extension, configured)
        declineWhenNoHostPlugin(project, configured, extension)
    }

    /**
     * Attaches to a Kotlin Multiplatform module that has a JVM target, and only then.
     *
     * Not in [HOST_PLUGINS], which attach unconditionally. Attaching is safe only because
     * [multiplatformSourceDirs] puts the module's own sources in the scope; without it the scope
     * would name only other modules' packages and the map would speak for classes it never saw.
     */
    private fun attachWhenMultiplatform(
        project: Project,
        extension: YoriwakeExtension,
        configured: java.util.concurrent.atomic.AtomicBoolean,
    ) {
        project.plugins.withId(KOTLIN_MULTIPLATFORM_PLUGIN) {
            if (canAttachMultiplatform(project) && configured.compareAndSet(false, true)) {
                configureWhenEvaluated(project, extension)
            }
        }
    }

    /**
     * Declines the whole plugin, by name, on a Gradle older than [MINIMUM_GRADLE].
     *
     * Below it, API the plugin calls is missing, and the failure is swallowed into an empty answer
     * where it is read: an empty set of declared modules drops a sibling module's changes, which
     * skips tests that should run. So it refuses before anything else, and still registers
     * `yoriwakeAudit` so the owner's diagnostic command answers. The check itself calls only API
     * every Gradle 8 has.
     */
    private fun declineOldGradle(project: Project, extension: YoriwakeExtension): Boolean {
        val running = org.gradle.util.GradleVersion.current()
        // The base version, so a release candidate of 8.14 is not below the floor.
        if (running.baseVersion >= org.gradle.util.GradleVersion.version(MINIMUM_GRADLE)) {
            return false
        }
        project.afterEvaluate {
            // A disabled plugin is a no-op here as on every other path.
            if (isDisabled(extension)) {
                return@afterEvaluate
            }
            declareOldGradleDecline(project, running.version)
        }
        return true
    }

    private fun declareOldGradleDecline(project: Project, running: String) {
        project.logger.warn(
            "[yoriwake] ${project.path}: Gradle $running is older than $MINIMUM_GRADLE, the oldest " +
                "Gradle this plugin supports, and predictive test selection cannot run on it. " +
                "Nothing is captured or selected, every run is a full run, and your build is " +
                "otherwise untouched. `yoriwakeAudit<Task>` reports this as `gradle-too-old`."
        )
        val refusal = ScopeOutcome.NotApplied(
            "Gradle $running is older than $MINIMUM_GRADLE, the oldest this plugin supports",
            ScopeOutcome.NotApplied.Kind.GRADLE_TOO_OLD,
        )
        project.tasks.withType(Test::class.java).names.toList().forEach { name ->
            AuditTask.register(project, name, settings, { test ->
                Triple(MapLocation.forTask(project.cacheDir(), test.path), refusal, null)
            })
        }
    }

    /**
     * Declines the whole plugin, by name, when the build has Isolated Projects enabled.
     *
     * The scope and module maps need cross-project reads (`projectsEvaluated`,
     * `rootProject.allprojects`), which Isolated Projects forbids. So this refuses before any such
     * read, letting the build configure instead of failing, and still registers `yoriwakeAudit` so
     * the owner's diagnostic command answers rather than reporting "task not found".
     */
    private fun declineIsolatedProjects(project: Project, extension: YoriwakeExtension): Boolean {
        if (!isolatedProjectsActive(project)) {
            return false
        }
        project.afterEvaluate {
            // A no-op is a no-op: a disabled plugin neither warns nor registers tasks. Asked here
            // because the extension is not final until the build script has run.
            if (isDisabled(extension)) {
                return@afterEvaluate
            }
            declareIsolatedProjectsDecline(project)
        }
        return true
    }

    private fun declareIsolatedProjectsDecline(project: Project) {
        // Warn: a plugin declining over a build feature should be visible by default.
        project.logger.warn(
            "[yoriwake] ${project.path}: Isolated Projects is enabled, and predictive test selection " +
                "cannot run under it. Every way this plugin has of seeing the whole build -- the " +
                "instrumentation scope, the module directory maps -- is a cross-project read that " +
                "Isolated Projects forbids, and reading them per project instead gives a scope " +
                "that depends on evaluation order. Nothing is captured or selected, every run is a " +
                "full run, and your build is otherwise untouched. `yoriwakeAudit<Task>` reports this as " +
                "`isolated-projects`."
        )
        val refusal = ScopeOutcome.NotApplied(
            "Isolated Projects is enabled and this plugin's scope derivation is a " +
                "cross-project read, which it forbids",
            ScopeOutcome.NotApplied.Kind.ISOLATED_PROJECTS,
        )
        project.tasks.withType(Test::class.java).names.toList().forEach { name ->
            AuditTask.register(project, name, settings, { test ->
                Triple(MapLocation.forTask(project.cacheDir(), test.path), refusal, null)
            })
        }
    }

    private fun isDisabled(extension: YoriwakeExtension): Boolean =
        settings.disabled || !extension.enabled.getOrElse(true)

    /**
     * Whether Isolated Projects is on, asked of the injected [buildFeatures] rather than of a
     * property whose spelling changed between Gradle versions. A provider that cannot be resolved
     * answers false.
     */
    private fun isolatedProjectsActive(project: Project): Boolean = runCatching {
        buildFeatures.isolatedProjects.active.get()
    }.getOrElse {
        project.logger.info("[yoriwake] could not ask whether Isolated Projects is active: $it")
        false
    }

    /**
     * Says so when this project has tests and the plugin will do nothing about them, so a build
     * that applies the plugin directly does not see it succeed and then do nothing. Also registers
     * `yoriwakeAudit` for each `Test` task, so the diagnostic command answers.
     */
    private fun declineWhenNoHostPlugin(
        project: Project,
        configured: java.util.concurrent.atomic.AtomicBoolean,
        extension: YoriwakeExtension,
    ) {
        // `projectsEvaluated`, not `afterEvaluate`: a host applying `java` in its own
        // `afterEvaluate` may run after ours. By then `configured` is final.
        project.gradle.projectsEvaluated { declareNoHostPluginDecline(project, configured, extension) }
    }

    private fun declareNoHostPluginDecline(
        project: Project,
        configured: java.util.concurrent.atomic.AtomicBoolean,
        extension: YoriwakeExtension,
    ) {
        if (configured.get() || isDisabled(extension)) {
            return
        }
        val tests = project.tasks.withType(Test::class.java).names.toList()
        if (tests.isEmpty()) {
            // No tests to select from; a warning would be noise on every non-test module.
            return
        }
        val refusal = hostPluginRefusal(project)
        project.logger.warn("[yoriwake] ${project.path}: ${refusal.detail}")
        tests.forEach { name ->
            AuditTask.register(project, name, settings, { test ->
                Triple(MapLocation.forTask(project.cacheDir(), test.path), refusal, null)
            })
        }
    }

    /** Why this project's tests are not selectable, named for the cause rather than the symptom. */
    private fun hostPluginRefusal(project: Project): ScopeOutcome.NotApplied =
        if (project.plugins.hasPlugin(KOTLIN_MULTIPLATFORM_PLUGIN)) {
            ScopeOutcome.NotApplied(
                "this is a Kotlin Multiplatform module with no JVM target this plugin can see. It " +
                    "looks for $MULTIPLATFORM_JVM_MARKERS beside at least one `<target>Main` " +
                    "source directory. A module it cannot both see tests in AND scope is declined " +
                    "here rather than scoped from directories that are not its own. Nothing is " +
                    "captured or selected here and every run is a full run",
                ScopeOutcome.NotApplied.Kind.KOTLIN_MULTIPLATFORM,
            )
        } else {
            ScopeOutcome.NotApplied(
                "this project has test tasks but applies none of $HOST_PLUGINS, so predictive " +
                    "test selection is not configured here and nothing is captured or selected",
                ScopeOutcome.NotApplied.Kind.NO_HOST_PLUGIN,
            )
        }

    /**
     * Wires every `Test` task once the whole build has been evaluated: the scope spans every
     * project, and from a project's own `afterEvaluate` it would depend on evaluation order.
     */
    private fun configureWhenEvaluated(project: Project, extension: YoriwakeExtension) {
        project.gradle.projectsEvaluated {
            // Once per project, not per `Test` task. It cannot be deduplicated per build: the
            // state would live in the service that just failed to register.
            if (BuildMemo.of(project) == null) {
                project.logger.lifecycle(
                    "[yoriwake] ${project.path}: this build loads the plugin through more than one " +
                        "classloader, so git answers cannot be shared between projects and every " +
                        "configured task asks again. Selection is unaffected; configuration is slower."
                )
            }
            // Timed as a whole, so an adopter sees what the plugin costs their configuration. The
            // per-mechanism counters nest inside and would double-count if summed.
            wiring.timedConfigure(project) { wiring.register(project, extension) }
        }
    }

    // Public only where scripts/yoriwake.init.gradle.kts reads it: that script is compiled apart
    // from this module and cannot see `internal`.
    public companion object {
        /** Key prefixes for [BuildMemo.value]. Distinct, because one map holds both. */
        internal const val SCOPE_KEY = "scope:"
        internal const val FACTS_KEY = "projectFacts:"
        internal const val WORKTREE_KEY = "worktreeListing:"

        internal const val TEST_TASKS_COUNTER = "testTasksConfigured"

        /** The pass at `projectsEvaluated` plus each `Test` task's wiring. It nests around every other timer. */
        internal const val CONFIGURE_COUNTER = "configure"
        internal const val DERIVE_SCOPE_COUNTER = "deriveScope"
        internal const val WALK_COUNTER = "allprojectsWalk"
        internal const val SOURCE_FILES_OPENED_COUNTER = "sourceFilesOpened"
        internal const val CLASSPATH_FACTS_COUNTER = "classpathFacts"
        /** The working tree listed and filtered at configuration: once per build, however many tasks ask. */
        internal const val WORKTREE_LISTING_COUNTER = "worktreeListing"

        /** Where `yoriwakeExplain` writes the decision for a machine to read. */
        internal const val EXPLANATION_FILE = "explain.json"

        /** The oldest Gradle the plugin runs on; below it the plugin declines. */
        internal const val MINIMUM_GRADLE = "8.14"


        /**
         * Plugins whose presence means this project has JVM tests worth selecting. Must match the
         * list in scripts/yoriwake.init.gradle.kts, the other entry point.
         */
        public val HOST_PLUGINS: List<String> = listOf(
            "java",
            "com.android.application",
            "com.android.library",
            "com.android.test",
            "com.android.dynamic-feature",
        )

        /** Attached through [attachWhenMultiplatform], not [HOST_PLUGINS]: it needs a condition. */
        public const val KOTLIN_MULTIPLATFORM_PLUGIN: String = "org.jetbrains.kotlin.multiplatform"

        /**
         * A multiplatform module's own main source directories, found by layout rather than
         * through KGP's extension. Every `<target>Main`, since a JVM half may live in an
         * intermediate source set such as `androidAndJvmMain`; non-JVM ones are harmless.
         */
        internal fun multiplatformSourceDirs(projectDir: File): List<File> =
            File(projectDir, "src").listFiles()
                ?.filter { it.isDirectory && it.name.endsWith("Main") }
                ?.flatMap { listOf(File(it, "kotlin"), File(it, "java")) }
                ?.filter { it.isDirectory }
                .orEmpty()

        /** The layout an ordinary JVM or Android module uses. */
        internal val CONVENTIONAL_SOURCE_DIRS = listOf("src/main/java", "src/main/kotlin")

        /**
         * The directories whose presence says this module has a JVM target. `jvmTest` counts too,
         * since JVM main sources may sit in an intermediate source set.
         */
        internal val MULTIPLATFORM_JVM_MARKERS = listOf("src/jvmMain", "src/jvmTest")

        /** Whether this multiplatform module has a JVM target, asked without KGP's extension. */
        internal fun hasJvmTarget(project: Project): Boolean =
            MULTIPLATFORM_JVM_MARKERS
                .any { File(project.projectDir, it).isDirectory }

        /**
         * Whether attaching to this multiplatform module can also scope it, so a module never
         * attaches without contributing its own packages. Used by scripts/yoriwake.init.gradle.kts.
         */
        public fun canAttachMultiplatform(project: Project): Boolean =
            hasJvmTarget(project) && multiplatformSourceDirs(project.projectDir).isNotEmpty()

        /** Whether `-Pyoriwake.disabled` is on for [project], as the plugin reads it. */
        public fun isDisabled(project: Project): Boolean = Settings.of(project).disabled

        /** The last resort when no branch base resolves: the working tree alone, a full run. */
        internal const val DEFAULT_BASE = "HEAD"

        /** The reason attached to [DEFAULT_BASE], in one place so every caller shows the remedy. */
        internal fun noBaseFound(): ChangeDetection.Base = ChangeDetection.Base(
            DEFAULT_BASE,
            // Safe and useless: nothing narrows. The usual cause is a CI checkout with a detached
            // HEAD, no upstream and no refs/remotes/origin/HEAD, on a branch not named main/master.
            "no branch base found, so the working tree only -- every run will be a full run until " +
                "you pass -P${Settings.BASE}=<ref> (in CI, the pull request base or the commit " +
                "you are comparing against)",
        )
    }
}

/** Configuration surface. Deliberately small: the plugin should be usable by applying it alone. */
public abstract class YoriwakeExtension {
    /** Set false to leave the build entirely alone — no scoping, no refusals, no selection. */
    public abstract val enabled: org.gradle.api.provider.Property<Boolean>

    /**
     * Tests selection must never skip, as globs: `alwaysRun.add("com.acme.FlakyTest")`.
     *
     * A name matches the class and everything in it; `com.acme.FlakyTest.oneMethod` pins just that
     * method; `*` inside a pattern matches anything. The equivalent for a single run is
     * `-Pyoriwake.alwaysRun=<glob>`, and in a test's own source `@Tag("yoriwake-always-run")`,
     * which needs nothing on the compile classpath. It can only cause more tests to run.
     */
    public abstract val alwaysRun: org.gradle.api.provider.ListProperty<String>

    init {
        @Suppress("LeakingThis")
        enabled.convention(true)
    }
}
