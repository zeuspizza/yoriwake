package io.github.zeuspizza.yoriwake.gradle

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Kover 0.9.8 applied beside the plugin, in each of its two modes.
 *
 * Its default agent leaves capture, the host's JaCoCo report and Kover's own report as they are
 * without the other, and a narrowed run leaves Kover with the coverage of the selected tests only.
 * Its JaCoCo mode (`useJacoco()`) and Gradle's JaCoCo agent cannot start one test JVM, plugin or
 * not; without Gradle's agent the plugin captures nothing and runs every test.
 *
 * Kover instruments only the test tasks of a Kotlin module, so the fixture applies `kotlin("jvm")`
 * over Java sources.
 */
class KoverFunctionalTest : FunctionalTestSupport() {

    private val sources = arrayOf(
        oneClass, secondClass,
        "src/main/java/dev/sample/Gamma.java" to """
            package dev.sample;
            public class Gamma { public int square(int n) { return n * n; } }
        """.trimIndent(),
        testClass("AlphaTest", "assertEquals(2, new Alpha().twice(1));", "assertEquals(4, new Alpha().twice(2));"),
        testClass("BetaTest", "assertEquals(3, new Beta().thrice(1));", "assertEquals(6, new Beta().thrice(2));"),
        testClass("GammaTest", "assertEquals(1, new Gamma().square(1));", "assertEquals(4, new Gamma().square(2));"),
        classOrderByName,
    )

    private val mainClasses = setOf("dev/sample/Alpha", "dev/sample/Beta", "dev/sample/Gamma")
    private val everyTest = setOf("dev.sample.AlphaTest", "dev.sample.BetaTest", "dev.sample.GammaTest")

    @Test
    fun `beside Kover's agent each test captures what it captures without Kover`(@TempDir root: File) {
        val plain = fixture(File(root, "plain"), kover = null)
        val kover = fixture(File(root, "kover"), kover = "")
        runner(plain, "test").build()

        runner(kover, "test").build()

        assertEquals(6, perTest(plain).size, "${perTest(plain)}")
        assertEquals(perTest(plain), perTest(kover))
        assertEquals(mainClasses, classesHitIn(mainClasses, hostFile(kover)))
    }

    @Test
    fun `with Kover applied before jacoco each test still captures what it captures without Kover`(@TempDir root: File) {
        val plain = fixture(File(root, "plain"), kover = null)
        val kover = fixture(File(root, "kover"), kover = "", koverFirst = true)
        runner(plain, "test").build()

        runner(kover, "test").build()

        assertEquals(perTest(plain), perTest(kover))
        assertEquals(mainClasses, classesHitIn(mainClasses, hostFile(kover)))
    }

    @Test
    fun `beside Kover's agent both reports equal those of a build with only one of the two`(@TempDir root: File) {
        val plain = fixture(File(root, "plain"), kover = null)
        val kover = fixture(File(root, "kover"), kover = "")
        runner(plain, "test", "jacocoTestReport").build()

        runner(kover, "test", "jacocoTestReport", "koverXmlReport").build()
        val jacoco = jacocoReport(kover)
        val captured = koverReport(kover)
        File(kover, "build").deleteRecursively()
        runner(kover, "test", "koverXmlReport", "-Pyoriwake.disabled").build()

        assertEquals(jacocoReport(plain), jacoco)
        assertEquals(koverReport(kover), captured)
        assertEquals(setOf("Alpha", "Beta", "Gamma"), covered(captured))
    }

    @Test
    fun `a narrowed run leaves Kover's report with the selected tests only, so koverVerify fails`(@TempDir root: File) {
        // A bound the whole suite meets (3 of 3 lines) and GammaTest alone does not (1 of 3).
        val dir = fixture(root, kover = "", extra = "kover { reports { verify { rule { minBound(50) } } } }")
        repository(dir)
        runner(dir, "test", "koverVerify").build()
        changeGamma(dir)

        val result = runner(dir, "test", "koverXmlReport", "koverVerify", "-Pyoriwake.select").buildAndFail()

        assertEquals(setOf("dev.sample.GammaTest"), ranTests(dir))
        assertEquals(TaskOutcome.SUCCESS, result.task(":koverXmlReport")?.outcome)
        assertEquals(setOf("Gamma"), covered(koverReport(dir)))
        assertEquals(TaskOutcome.FAILED, result.task(":koverVerify")?.outcome)
        assertContains(result.output, "Rule violated")
    }

    @Test
    fun `beside Kover's agent a complement leaves out the tests the selecting run ran`(@TempDir root: File) {
        val dir = fixture(root, kover = "")
        repository(dir)
        runner(dir, "test").build()
        changeGamma(dir)
        commit(dir, "change gamma")
        runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD").build()
        assertEquals(setOf("dev.sample.GammaTest"), ranTests(dir))

        val output = runner(dir, "test", "-Pyoriwake.complement").build().output

        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir), output)
        assertContains(output, "2 tests left out as already run, 4 run")
    }

    @Test
    fun `the test JVM's arguments are the same whether or not a Kover task is requested`(@TempDir root: File) {
        val dir = fixture(root, kover = "", extra = "kover { reports { verify { rule { minBound(50) } } } }" + PROBE)
        runner(dir, "test").build()
        val alone = File(dir, ARGS).readText()
        File(dir, "build").deleteRecursively()

        runner(dir, "test", "koverVerify").build()

        assertTrue(alone.lines().any { it.startsWith("-javaagent:") && "kover-jvm-agent-0.9.8.jar" in it }, alone)
        assertEquals(alone, File(dir, ARGS).readText())
    }

    @Test
    fun `Kover's JaCoCo mode and Gradle's JaCoCo agent cannot start one test JVM, with the plugin or without`(
        @TempDir root: File,
    ) {
        val withPlugin = fixture(File(root, "plugin"), kover = JACOCO_MODE)
        val withoutPlugin = fixture(File(root, "no-plugin"), kover = JACOCO_MODE)
        File(withoutPlugin, "build.gradle.kts").apply { writeText(readText().replace("id(\"io.github.zeuspizza.yoriwake\")", "")) }

        val results = listOf(
            runner(withPlugin, "test").buildAndFail(),
            runner(withPlugin, "test", "-Pyoriwake.disabled").buildAndFail(),
            runner(withoutPlugin, "test").buildAndFail(),
        )

        for (result in results) {
            assertEquals(TaskOutcome.FAILED, result.task(":test")?.outcome)
            assertContains(result.output, "duplicate class definition for java.lang.\$JaCoCo")
        }
    }

    @Test
    fun `in Kover's JaCoCo mode without Gradle's JaCoCo agent nothing is captured and Kover's report is whole`(
        @TempDir root: File,
    ) {
        val withoutAgent = mapOf(
            "disabled on the task" to { script: String ->
                script + "\ntasks.test { extensions.configure<JacocoTaskExtension> { isEnabled = false } }\n"
            },
            "not applied" to { script: String ->
                script.replace("\n    jacoco\n", "\n").replace(JACOCO_XML, "")
            },
        )
        for ((name, edit) in withoutAgent) {
            val dir = fixture(File(root, name.replace(' ', '-')), kover = JACOCO_MODE)
            File(dir, "build.gradle.kts").apply { writeText(edit(readText())) }

            val output = runner(dir, "test", "koverXmlReport").build().output
            val ran = ranTests(dir)
            val captured = koverReport(dir)
            File(dir, "build").deleteRecursively()
            runner(dir, "test", "koverXmlReport", "-Pyoriwake.disabled").build()

            assertEquals(everyTest, ran, "$name: $output")
            assertContains(output, "Nothing is captured or selected for this task", message = name)
            assertFalse(File(dir, ".gradle/yoriwake").walk().any { it.name == "coverage.tsv" }, name)
            assertEquals(setOf("Alpha", "Beta", "Gamma"), covered(captured), name)
            assertEquals(koverReport(dir), captured, name)
        }
    }

    /**
     * The sample, with Kover applied after the plugin (or first) and [kover] as its configuration,
     * or without Kover when [kover] is null.
     */
    private fun fixture(dir: File, kover: String?, koverFirst: Boolean = false, extra: String = ""): File {
        val plugin = "id(\"org.jetbrains.kotlinx.kover\") version \"0.9.8\""
        val plugins = listOfNotNull(
            plugin.takeIf { kover != null && koverFirst },
            "kotlin(\"jvm\") version \"2.1.20\"",
            "jacoco",
            "id(\"io.github.zeuspizza.yoriwake\")",
            plugin.takeIf { kover != null && !koverFirst },
        )
        val script = kotlinBuild.replace(
            Regex("plugins \\{.*?\\n}", RegexOption.DOT_MATCHES_ALL),
            plugins.joinToString("\n    ", "plugins {\n    ", "\n}"),
        ) + "\n$JACOCO_XML\n${kover.orEmpty()}\n$extra\n"
        dir.mkdirs()
        build(dir, "build.gradle.kts" to script, *sources)
        File(dir, "settings.gradle.kts").writeText(
            "pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }\n" +
                """rootProject.name = "sample""""
        )
        return dir
    }

    /** [committed], with the directory where Kotlin's compiler keeps its session state ignored. */
    private fun repository(dir: File) {
        ignoreBuildOutputs(dir)
        File(dir, ".gitignore").appendText(System.lineSeparator() + ".kotlin/")
        git(dir, "init")
        commit(dir, "base")
    }

    private fun testClass(name: String, vararg bodies: String) = "src/test/java/dev/sample/$name.java" to """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class $name {
        ${bodies.mapIndexed { i, body -> "    @Test void case$i() { $body }" }.joinToString("\n")}
        }
    """.trimIndent()

    /** Gamma's tests run last by name, so a change to it reaches no other test. */
    private fun changeGamma(dir: File) {
        File(dir, "src/main/java/dev/sample/Gamma.java").writeText(
            """
            package dev.sample;
            public class Gamma { public int square(int n) { return n * n + 0; } }
            """.trimIndent()
        )
    }

    /** Each test's captured classes, as the map holds them. */
    private fun perTest(dir: File): Map<String, String> {
        val mapDir = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)
        return File(mapDir, "coverage.tsv").readLines()
            .filter { "[method:" in it && "[yoriwake:" !in it }
            .map { it.split('\t') }
            .associate { columns -> columns.first { "[method:" in it } to columns[2] }
    }

    /** An XML report without its session list, which names each JVM and when it ran. */
    private fun jacocoReport(dir: File) =
        File(dir, "build/reports/jacoco/test/jacocoTestReport.xml").readText().replace(Regex("<sessioninfo[^>]*/>"), "")

    private fun koverReport(dir: File) =
        File(dir, "build/reports/kover/report.xml").readText().replace(Regex("<sessioninfo[^>]*/>"), "")

    /** The simple names of the classes an XML report counts any covered line for. */
    private fun covered(report: String): Set<String> =
        Regex("<class name=\"dev/sample/(\\w+)\"[^>]*>.*?</class>", RegexOption.DOT_MATCHES_ALL).findAll(report)
            .filter { Regex("<counter type=\"LINE\" missed=\"\\d+\" covered=\"[1-9]").containsMatchIn(it.value) }
            .map { it.groupValues[1] }.toSet()

    private companion object {
        const val JACOCO_MODE = "kover { useJacoco() }"
        const val JACOCO_XML = "tasks.jacocoTestReport { reports { xml.required.set(true) } }"
        const val ARGS = "build/probe/jvm-args.txt"

        // Inserted just before the tests run, after every action of the plugin's.
        val PROBE = """

            gradle.taskGraph.whenReady {
                val test = tasks.named<Test>("test").get()
                val out = layout.buildDirectory.file("probe/jvm-args.txt").get().asFile
                val probe = Action<Task> {
                    out.parentFile.mkdirs()
                    out.writeText((this as Test).allJvmArgs.joinToString("\n"))
                }
                val actions = test.actions.toMutableList()
                val tests = actions.indexOfFirst { (it as? org.gradle.api.Describable)?.displayName?.contains("executeTests") == true }
                actions.add(tests, probe)
                test.actions = actions
            }
        """.trimIndent()
    }
}
