package io.github.zeuspizza.yoriwake.gradle.wiring

import io.github.zeuspizza.yoriwake.agent.contract.Tsv
import io.github.zeuspizza.yoriwake.gradle.IsolatedCaptureForks
import io.github.zeuspizza.yoriwake.gradle.Settings
import io.github.zeuspizza.yoriwake.gradle.capture.CoverageDecoder
import io.github.zeuspizza.yoriwake.gradle.capture.IsolatedPick
import io.github.zeuspizza.yoriwake.gradle.capture.writeAtomically
import io.github.zeuspizza.yoriwake.gradle.tasks.DecodeTask
import java.io.File
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult
import org.gradle.api.tasks.testing.junit.JUnitOptions
import org.gradle.api.tasks.testing.junitplatform.JUnitPlatformOptions
import org.gradle.api.tasks.testing.testng.TestNGOptions
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension

/**
 * `<task>YoriwakeIsolated`: on a recording run under `-Pyoriwake.isolatedCaptureBudget`, runs the
 * test classes the budget picked, which the test task left out, each in a JVM of its own, with the
 * test task's classpath, framework, JVM arguments and environment, into the same records. Runs only
 * when the test task recorded and picked something; the decode runs after it, so one decode writes
 * one map. When it does not run, the picked classes' tests are missing from the map, and a test not
 * in the map runs.
 */
internal object IsolatedCompanion {

    fun nameFor(testName: String) = "${testName}YoriwakeIsolated"

    /** Registers the companion of [testName]; [mapDir] answers null for a task the plugin declined. */
    fun register(project: Project, testName: String, forks: IsolatedCaptureForks?, mapDir: (Test) -> File?) {
        val name = nameFor(testName)
        project.tasks.register(name, Test::class.java) { task ->
            task.description = "Runs the test classes -P${Settings.ISOLATED_CAPTURE_BUDGET} picked from $testName, each in its own JVM."
            val source = project.tasks.named(testName, Test::class.java).get()
            val map = mapDir(source)
            if (map == null) {
                task.enabled = false
                return@register
            }
            task.testClassesDirs = source.testClassesDirs
            task.classpath = source.classpath
            when (val options = source.options) {
                is JUnitPlatformOptions -> task.useJUnitPlatform { it.copyFrom(options) }
                is JUnitOptions -> task.useJUnit { it.copyFrom(options) }
                is TestNGOptions -> task.useTestNG { it.copyFrom(options) }
            }
            task.filter.setIncludePatterns(*source.filter.includePatterns.toTypedArray())
            task.filter.setExcludePatterns(*source.filter.excludePatterns.toTypedArray())
            task.filter.isFailOnNoMatchingTests = false
            task.setExcludes(source.excludes)
            task.ignoreFailures = source.ignoreFailures
            task.failFast = source.failFast
            task.timeout.set(source.timeout)
            task.workingDir = source.workingDir
            task.environment = source.environment
            task.javaLauncher.set(source.javaLauncher)
            // The test task's own arguments carry its assertion switch.
            task.enableAssertions = false
            task.forkEvery = 1
            allowAnEmptyRun(task)
            // Its inputs do not name what it runs, which the test task decides as it executes.
            task.outputs.upToDateWhen { false }
            task.outputs.doNotCacheIf("it runs what the test task picked as it executed") { true }

            val pending = File(map, IsolatedPick.PENDING_FILE)
            val handOff = isolatedHandOff(map)
            val ran = ranMarker(CoverageDecoder.recordsDir(map))
            task.onlyIf("the test task recorded and the budget picked test classes") {
                pending.isFile && handOff.isFile && ran.isFile
            }
            task.doFirst {
                val pick = IsolatedPick.Stamp.read(pending) ?: throw IllegalStateException("$pending cannot be read")
                pick.classes.keys.forEach { task.include(IsolatedPick.scanPatterns(it)) }
                // The test task's arguments as its JVMs started: its system properties, the agent and
                // JaCoCo's; a debugger agent was left out of them.
                task.jvmArgs(handOff.readLines().filter(String::isNotEmpty).map(Tsv::unescape))
                handOff.delete()
                task.maxParallelForks = minOf(forks?.resolve() ?: IsolatedPick.DEFAULT_FORKS, Runtime.getRuntime().availableProcessors())
                val parallel = task.maxParallelForks
                task.addTestListener(object : TestListener {
                    override fun beforeSuite(suite: TestDescriptor) {}
                    override fun afterSuite(suite: TestDescriptor, result: TestResult) {
                        if (suite.parent != null) return
                        // What a JVM start costs here, for the next pick to plan with.
                        val wall = (result.endTime - result.startTime) / 1000.0
                        runCatching { writeAtomically(pending, IsolatedPick.measured(pick, wall, parallel).encode()) }
                    }
                    override fun beforeTest(testDescriptor: TestDescriptor) {}
                    override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {}
                })
                task.logger.lifecycle(
                    "[yoriwake] ${task.path}: recording ${pick.classes.size} test classes, each in its own JVM, " +
                        "up to $parallel at a time"
                )
            }
        }
        // After Gradle's JaCoCo plugin has given it an agent of its own: the test task's arguments
        // already carry one.
        project.tasks.withType(Test::class.java).configureEach { task ->
            if (task.name == name) (task.extensions.findByName("jacoco") as? JacocoTaskExtension)?.isEnabled = false
        }
        project.tasks.named(testName).configure { it.finalizedBy(name) }
        project.tasks.named(DecodeTask.nameFor(testName)).configure { it.mustRunAfter(name) }
    }
}
