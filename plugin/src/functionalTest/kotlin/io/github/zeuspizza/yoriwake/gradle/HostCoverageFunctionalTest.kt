package io.github.zeuspizza.yoriwake.gradle

import org.gradle.testkit.runner.TaskOutcome
import org.jacoco.core.data.ExecutionDataReader
import org.jacoco.core.data.ExecutionDataStore
import org.jacoco.core.data.SessionInfoStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The host's own JaCoCo execution file and the report built from it, with the plugin applied. */
class HostCoverageFunctionalTest : FunctionalTestSupport() {

    private val build = minimalBuild + "\n" + """
        tasks.jacocoTestReport { reports { xml.required.set(true) } }
    """.trimIndent()

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

    @Test
    fun `a capture run leaves the host file holding what its tests ran`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to build, *sources)

        runner(dir, "test").build()

        assertEquals(mainClasses, mainClassesIn(*rawRecords(dir)))
        assertEquals(mainClasses, mainClassesIn(hostFile(dir)))
    }

    @Test
    fun `the report of a capture run equals the report of a run without the plugin`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to build, *sources)
        runner(dir, "test", "jacocoTestReport").build()
        val captured = report(dir)
        File(dir, "build").deleteRecursively()

        runner(dir, "test", "jacocoTestReport", "-Pyoriwake.disabled").build()

        assertTrue("<class name=\"dev/sample/Gamma\"" in captured, captured)
        assertEquals(report(dir), captured)
    }

    @Test
    fun `two forks leave a complete host file`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to build + "\ntasks.test { maxParallelForks = 2 }", *sources)

        runner(dir, "test").build()

        assertTrue(sessionsIn(hostFile(dir)) >= 2)
        assertEquals(mainClasses, mainClassesIn(hostFile(dir)))
    }

    @Test
    fun `a disabled run leaves the host file complete`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to build, *sources)

        runner(dir, "test", "-Pyoriwake.disabled").build()

        assertEquals(mainClasses, mainClassesIn(hostFile(dir)))
    }

    @Test
    fun `a narrowed run leaves no host file, so the report skips`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to build, *sources)
        committed(dir)
        runner(dir, "test").build()
        assertTrue(hostFile(dir).isFile)
        changeGamma(dir)

        val result = runner(dir, "test", "jacocoTestReport", "-Pyoriwake.select").build()

        assertEquals(setOf("dev.sample.GammaTest"), ranTests(dir))
        assertFalse(hostFile(dir).exists())
        assertEquals(TaskOutcome.SKIPPED, result.task(":jacocoTestReport")?.outcome)
    }

    @Test
    fun `a narrowed run with no task history still leaves no host file`(@TempDir dir: File) {
        // Gradle keeps execution history per Gradle version, so a new version or a cleared cache
        // loses it while the build's output registry stays.
        build(dir, "build.gradle.kts" to build, *sources)
        committed(dir)
        runner(dir, "test").build()
        changeGamma(dir)
        File(dir, ".gradle").listFiles()!!.filter { it.name.first().isDigit() }.forEach { it.deleteRecursively() }

        val result = runner(dir, "test", "jacocoTestReport", "-Pyoriwake.select").build()

        assertEquals(setOf("dev.sample.GammaTest"), ranTests(dir))
        assertFalse(hostFile(dir).exists())
        assertEquals(TaskOutcome.SKIPPED, result.task(":jacocoTestReport")?.outcome)
    }

    @Test
    fun `a run declined over in-JVM parallelism leaves no host file`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to build, *sources)
        runner(dir, "test").build()
        assertTrue(hostFile(dir).isFile)
        File(dir, "build.gradle.kts").writeText(
            build + "\ntasks.test { systemProperty(\"junit.jupiter.execution.parallel.enabled\", \"true\") }"
        )

        val result = runner(dir, "test", "jacocoTestReport").build()

        assertTrue("in-jvm-parallelism" in result.output)
        assertFalse(hostFile(dir).exists())
        assertEquals(TaskOutcome.SKIPPED, result.task(":jacocoTestReport")?.outcome)
    }

    @Test
    fun `a failing test restores the host file before the host's report reads it`(@TempDir dir: File) {
        build(
            dir,
            "build.gradle.kts" to build + "\ntasks.test { finalizedBy(tasks.jacocoTestReport) }",
            *sources,
            testClass("FailingTest", "assertEquals(1, 2);"),
        )

        val result = runner(dir, "test").buildAndFail()

        val order = result.tasks.map { it.path }
        assertTrue(order.indexOf(":yoriwakeDecodeTest") < order.indexOf(":jacocoTestReport"), "$order")
        assertEquals(TaskOutcome.SUCCESS, result.task(":jacocoTestReport")?.outcome)
        assertEquals(mainClasses, mainClassesIn(hostFile(dir)))
        assertEquals(mainClasses.map { it.substringAfterLast('/') }.toSet(), coveredInReport(dir))
    }

    @Test
    fun `a rerun is up to date and a cached run restores the host file`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to build, *sources)
        // The first run unpacks the agent jar onto the classpath, so the second still executes.
        runner(dir, "test", "--build-cache").build()
        runner(dir, "test", "--build-cache").build()
        val stored = hostFile(dir).readBytes()

        assertEquals(TaskOutcome.UP_TO_DATE, runner(dir, "test", "--build-cache").build().task(":test")?.outcome)
        File(dir, "build").deleteRecursively()
        val cached = runner(dir, "test", "--build-cache").build()

        assertEquals(TaskOutcome.FROM_CACHE, cached.task(":test")?.outcome)
        assertTrue(stored.contentEquals(hostFile(dir).readBytes()))
        assertEquals(mainClasses, mainClassesIn(hostFile(dir)))
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

    private fun hostFile(dir: File) = File(dir, "build/jacoco/test.exec")

    /** The XML report without its session list, which names each JVM and when it ran. */
    private fun report(dir: File) =
        File(dir, "build/reports/jacoco/test/jacocoTestReport.xml").readText().replace(Regex("<sessioninfo[^>]*/>"), "")

    /** The simple names of the classes the XML report counts any covered line for. */
    private fun coveredInReport(dir: File): Set<String> =
        Regex("<class name=\"dev/sample/(\\w+)\"[^>]*>.*?</class>").findAll(report(dir))
            .filter { Regex("<counter type=\"LINE\" missed=\"\\d+\" covered=\"[1-9]").containsMatchIn(it.value) }
            .map { it.groupValues[1] }.toSet()

    /** Every per-test record the run wrote. */
    private fun rawRecords(dir: File): Array<File> {
        val mapDir = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)
        return File(mapDir, "raw").walk().filter { it.name.endsWith(".exec") }.sorted().toList().toTypedArray()
    }

    /** The fixture's main classes with any probe hit, over every given file. */
    private fun mainClassesIn(vararg files: File): Set<String> =
        read(*files).first.contents.filter { it.hasHits() }.map { it.name }.filter { it in mainClasses }.toSet()

    private fun sessionsIn(file: File): Int = read(file).second.infos.size

    private fun read(vararg files: File): Pair<ExecutionDataStore, SessionInfoStore> {
        val store = ExecutionDataStore()
        val sessions = SessionInfoStore()
        files.forEach { file ->
            file.inputStream().buffered().use { input ->
                ExecutionDataReader(input).apply {
                    setExecutionDataVisitor(store)
                    setSessionInfoVisitor(sessions)
                    read()
                }
            }
        }
        return store to sessions
    }
}
