package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.gradle.wiring.AgentJar
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Configuration-cache reuse and configuration-time work. */
class ConfigurationCacheFunctionalTest : FunctionalTestSupport() {

    @Test
    fun `the second build after an install reuses its configuration-cache entry`(@TempDir dir: File) {
        // The agent is unpacked at execution time. Unpacking it during configuration makes the
        // stored entry record the jar as absent, and the next build discards the entry.
        // Asserted from Gradle's own line rather than a clock, which would only measure the
        // machine.
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest)

        val first = runner(dir, "help").build().output
        val second = runner(dir, "help").build().output

        assertContains(first, "Configuration cache entry stored")
        assertTrue(
            second.contains("Configuration cache entry reused"),
            "the second build reconfigured; the entry the first stored was discarded. Gradle said: " +
                second.lines().first { it.contains("Configuration cache entry") },
        )

        // `help` never realises the `Test` task, so it never reaches the code that places the
        // agent. A build that runs the task does, and stores its own entry.
        val firstTest = runner(dir, "test").build().output
        val secondTest = runner(dir, "test").build().output

        assertContains(firstTest, "Configuration cache entry stored")
        assertTrue(
            secondTest.contains("Configuration cache entry reused"),
            "the second test build reconfigured; the entry the first stored was discarded. Gradle " +
                "said: " + secondTest.lines().first { it.contains("Configuration cache entry") },
        )
    }

    @Test
    fun `the agent is attached on a reusing build, and restored if it is deleted`(@TempDir dir: File) {
        // The jar must still be there when the test JVM starts, including on a build that reused
        // its entry and never configured, and on one whose cache directory was cleaned between
        // runs.
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest)
        runner(dir, "test").build()
        val mapDir = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)
        assertTrue(File(mapDir, "coverage.tsv").readText().isNotEmpty(), "the first run captured nothing")

        val agent = File(dir, ".gradle/${AgentJar.DIRECTORY}/${AgentJar.RESOURCE}")
        assertTrue(agent.isFile, "the agent was never unpacked")
        assertTrue(agent.delete(), "could not delete the unpacked agent")
        File(mapDir, "coverage.tsv").writeText("")

        val second = runner(dir, "test", "--rerun-tasks").build()

        assertContains(second.output, "Configuration cache entry reused")
        assertTrue(agent.isFile, "the agent was not restored on a build that did not configure")
        assertTrue(
            File(mapDir, "coverage.tsv").readText().isNotEmpty(),
            "the reusing build captured nothing, so the agent was not attached",
        )
    }

    /**
     * Applying the plugin costs a build that never runs its tests no `Test` task configuration:
     * each is wired when something first asks for it, and `help` never does.
     */
    @Test
    fun `help realises no Test task, however many the build declares`(@TempDir dir: File) {
        val many = minimalBuild + """

            (1..20).forEach { tasks.register<Test>("extra${'$'}it") { useJUnitPlatform() } }
            tasks.withType<Test>().configureEach { println("[probe] realised " + path) }
        """.trimIndent()
        build(dir, "build.gradle.kts" to many, oneClass, oneTest)

        val help = runner(dir, "help").build().output

        assertFalse(help.contains("[probe] realised"), help)
        // The probe counts: a build that does ask for a task realises it, and the plugin's tasks
        // exist by name for every one of them without realising any.
        assertContains(runner(dir, "test", "--dry-run").build().output, "[probe] realised :test")
        assertContains(runner(dir, "tasks", "--all").build().output, "yoriwakeExplainExtra20")
    }

    @Test
    fun `a build with flags set still reuses its configuration-cache entry`(@TempDir dir: File) {
        capturedWithAlphaChanged(dir, minimalBuild, oneTest, secondTest)
        val flags = arrayOf(
            "-Pyoriwake.select", "-Pyoriwake.base=HEAD", "-Pyoriwake.internal.loaded",
        )

        runner(dir, "help", *flags).build()
        val second = runner(dir, "help", *flags).build().output

        assertContains(second, "Configuration cache entry reused")
    }
}
