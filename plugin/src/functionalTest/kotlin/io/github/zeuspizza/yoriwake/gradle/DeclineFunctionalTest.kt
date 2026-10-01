package io.github.zeuspizza.yoriwake.gradle

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Declines: builds the plugin will not wire, named rather than failed. */
class DeclineFunctionalTest : FunctionalTestSupport() {

    @Test
    fun `a project with no test tasks applies cleanly`(@TempDir dir: File) {
        build(
            dir,
            "build.gradle.kts" to """
                plugins { java; id("io.github.zeuspizza.yoriwake") }
            """.trimIndent(),
            oneClass,
        )

        val result = runner(dir, "help").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":help")?.outcome)
    }

    @Test
    fun `in-JVM parallel execution is declined, and the build still runs its tests`(
        @TempDir dir: File,
    ) {
        // Declined rather than failed: a suite this plugin cannot help must still run.
        build(
            dir,
            "build.gradle.kts" to minimalBuild + """

                tasks.test {
                    systemProperty("junit.jupiter.execution.parallel.enabled", "true")
                }
            """.trimIndent(),
            oneClass,
            oneTest,
        )

        val result = runner(dir, "test").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":test")?.outcome)
        assertContains(result.output, "junit.jupiter.execution.parallel.enabled")
        assertContains(result.output, "share a single coverage agent")
        assertContains(result.output, "the map is left exactly as it was")

        // The audit must still name the cause afterwards. `doFirst` prepends, so the fact
        // recorder's place in the action order decides whether any fact is written before the
        // decline.
        val audit = runner(dir, "yoriwakeAuditTest").build().output

        assertContains(audit, "in-jvm-parallelism")
        assertContains(audit, "junit.jupiter.execution.parallel.enabled")
        // The remedy says what is not affected, or a reader turns off parallel forks too.
        assertContains(audit, "maxParallelForks")
    }

    @Test
    fun `a task whose jacoco extension is switched off is declined before the run, not after it`(
        @TempDir dir: File,
    ) {
        // The extension exists, so it is found, scoped and configured; it is disabled, so Gradle
        // adds no agent and the run records nothing. The decline has to come before the suite runs.
        val disabled = minimalBuild + """

            tasks.test { extensions.getByType<JacocoTaskExtension>().isEnabled = false }
        """.trimIndent()
        build(dir, "build.gradle.kts" to disabled, oneClass, oneTest)

        val output = runner(dir, "test").build().output

        assertContains(output, "jacoco extension and it is DISABLED")
        // And it declined rather than capturing: no scope line, so nothing was instrumented.
        assertFalse(
            output.lines().any { it.contains("[yoriwake] :test ") && it.contains("scope=") },
            "the plugin configured capture against a disabled coverage agent",
        )
    }

    @Test
    fun `a build with Isolated Projects gets a named decline and an audit, not a failure`(
        @TempDir dir: File,
    ) {
        // Two modules, because in a single project no cross-project read is possible and the guard
        // cannot fail. The decline path itself must not read the root project's files or look up a
        // parent's property, both of which Gradle refuses under Isolated Projects.
        File(dir, "settings.gradle.kts").writeText(
            """
            rootProject.name = "multi"
            include("alpha", "beta")
            """.trimIndent()
        )
        listOf("alpha", "beta").forEach { module ->
            File(dir, "$module/build.gradle.kts").apply { parentFile.mkdirs() }
                .writeText(minimalBuild)
            File(dir, "$module/${oneClass.first}").apply { parentFile.mkdirs() }
                .writeText(oneClass.second)
            File(dir, "$module/${oneTest.first}").apply { parentFile.mkdirs() }
                .writeText(oneTest.second)
        }
        // Both spellings: Gradle 8.14 reads `org.gradle.unsafe.isolated-projects`, later versions
        // the name without `unsafe`. Setting only the one this runner does not read would never
        // enable the feature.
        File(dir, "gradle.properties").writeText(
            "org.gradle.unsafe.isolated-projects=true\norg.gradle.isolated-projects=true\n"
        )

        val output = runner(dir, ":alpha:yoriwakeAuditTest", ":beta:yoriwakeAuditTest").build().output

        // It configures. That is the whole point: the decline must not be delivered by dying.
        assertContains(output, "Isolated Projects is enabled")

        // The token in the payload, not only the sentence in the log: `audit.json` is what scripts
        // read and what survives the build, while the prose is one rewording away from asserting
        // nothing.
        val declined = File(dir, ".gradle/yoriwake").listFiles().orEmpty()
            .filter(File::isDirectory)
            .map { File(it, "audit.json") }
            .filter(File::isFile)
            .map(File::readText)
        assertTrue(declined.isNotEmpty(), "the declined tasks wrote no audit payload at all")
        assertTrue(
            declined.all { it.contains(""""token": "isolated-projects"""") },
            "a declined task's payload does not name the decline: $declined",
        )
        // A declined task has no map and never will, so "run `test` once to capture one" would be
        // advice that cannot terminate. The token above is what selects this sentence.
        assertContains(output, "Running `test` again changes nothing")
        assertFalse(
            output.contains("Run `test` once to capture one"),
            "the audit told the owner to capture a map that can never be captured",
        )
        // And the plugin did not configure selection: no scope line, because it returned before any
        // cross-project read rather than after one.
        assertFalse(
            output.lines().any { it.contains("scope=derived") || it.contains("scope=adopted") },
            "the plugin configured a task under Isolated Projects",
        )
    }

    @Test
    fun `a Gradle older than 8_14 gets a named decline, runs every test, and captures nothing`(
        @TempDir dir: File,
    ) {
        // One version below the floor, driven by this test itself; the floor suite is enough to
        // run it, and a second run would only fetch the same old distribution again.
        assumeTrue(FunctionalGradle.version == "8.14", "runs in the 8.14 suite only")
        val old = "8.13"
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, secondClass, oneTest, secondTest)
        fun runner(vararg args: String) = runner(dir, *args)
            .withGradleVersion(old)
            .withTestKitDir(FunctionalGradle.testKitDir(old))

        val result = runner("test", "-Pyoriwake.select").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":test")?.outcome)
        assertContains(result.output, "Gradle $old is older than 8.14")
        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir))
        val written = File(dir, ".gradle/yoriwake").walkTopDown().filter(File::isFile).toList()
        assertTrue(
            written.none { it.name == "coverage.tsv" || it.name == "index.tsv" },
            "the declined build captured a map: $written",
        )

        runner("yoriwakeAuditTest").build()

        val audit = File(dir, ".gradle/yoriwake").listFiles().orEmpty()
            .filter(File::isDirectory)
            .map { File(it, "audit.json") }
            .single(File::isFile)
            .readText()
        assertContains(audit, """"token": "gradle-too-old"""")
    }

    @Test
    fun `a disabled plugin declines nothing and registers nothing, even under Isolated Projects`(
        @TempDir dir: File,
    ) {
        // `-Pyoriwake.disabled` is the uninstrumented run of `scripts/measure-toll.sh`, and an
        // escape hatch that still warns and adds tasks is not one. The decline paths run before the
        // check every other path makes, so they must honour it too.
        File(dir, "settings.gradle.kts").writeText(
            """
            rootProject.name = "multi"
            include("alpha")
            """.trimIndent()
        )
        File(dir, "alpha/build.gradle.kts").apply { parentFile.mkdirs() }.writeText(minimalBuild)
        File(dir, "alpha/${oneClass.first}").apply { parentFile.mkdirs() }.writeText(oneClass.second)
        File(dir, "alpha/${oneTest.first}").apply { parentFile.mkdirs() }.writeText(oneTest.second)
        File(dir, "gradle.properties").writeText(
            "org.gradle.unsafe.isolated-projects=true\norg.gradle.isolated-projects=true\n"
        )

        val output = runner(dir, "tasks", "--all", "-Pyoriwake.disabled").build().output

        assertFalse(
            output.contains("Isolated Projects is enabled"),
            "a plugin that was switched off still declined out loud",
        )
        assertFalse(
            output.contains("yoriwakeAudit"),
            "a plugin that was switched off still registered its tasks",
        )
    }

    @Test
    fun `a build that applies java from its own afterEvaluate is configured, not declined forever`(
        @TempDir dir: File,
    ) {
        // Gradle runs afterEvaluate callbacks in registration order, so a host applying `java` in
        // one registered after this plugin's must not lose the race to the decline and bake
        // `no-host-plugin` into the audit task.
        // The fixture needs a `Test` task before the host plugin arrives, or the decline returns
        // early on "no tests to select from" and the race never happens.
        build(
            dir,
            "build.gradle.kts" to """
                plugins {
                    id("io.github.zeuspizza.yoriwake")
                }
                tasks.register<Test>("customTest")
                afterEvaluate {
                    apply(plugin = "java")
                    apply(plugin = "jacoco")
                }
            """.trimIndent(),
            oneClass,
            oneTest,
        )

        val output = runner(dir, "yoriwakeAuditCustomTest").build().output

        assertFalse(
            output.contains("no-host-plugin"),
            "a project the plugin configured reported that it has no host plugin",
        )
        val payloads = File(dir, ".gradle/yoriwake").listFiles().orEmpty()
            .filter(File::isDirectory)
            .map { File(it, "audit.json") }
            .filter(File::isFile)
            .map(File::readText)
        assertTrue(payloads.isNotEmpty(), "the audit wrote no payload at all")
        assertTrue(
            payloads.none { it.contains(""""token": "no-host-plugin"""") },
            "the stale decline survived into the payload: $payloads",
        )
    }

    @Test
    fun `a project with tests and no host plugin is declined by name, with an audit to ask`(
        @TempDir dir: File,
    ) {
        // Applied directly rather than through the init script, as the README says to adopt it. A
        // project matching no host plugin must say so, and `yoriwakeAudit` must exist for the owner
        // to ask.
        val noHostPlugin = """
            plugins {
                base
                id("io.github.zeuspizza.yoriwake")
            }
            val check = tasks.register<Test>("test") {
                testClassesDirs = files()
                classpath = files()
            }
        """.trimIndent()
        build(dir, "build.gradle.kts" to noHostPlugin)

        val output = runner(dir, "yoriwakeAuditTest").build().output

        assertContains(output, "applies none of")
        assertContains(output, "no-host-plugin")
    }
}
