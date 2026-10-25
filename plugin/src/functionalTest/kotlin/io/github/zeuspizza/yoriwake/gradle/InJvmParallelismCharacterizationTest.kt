package io.github.zeuspizza.yoriwake.gradle

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * In-JVM parallel execution as the plugin declines it today, by every route it detects, on a run
 * that would record and on one that would select: every test runs, nothing is instrumented or
 * recorded, and the map stays byte for byte what it was. Pinned before the opt-in that records
 * such suites one test at a time changes the recording runs.
 */
class InJvmParallelismCharacterizationTest : FunctionalTestSupport() {

    private val switch = "junit.jupiter.execution.parallel.enabled"

    /** Each route, added to the build only under `-Pparallel`, so one map is recorded without it first. */
    private val routes = mapOf(
        "the task's system properties" to """tasks.test { systemProperty("$switch", "true") }""",
        "a JVM argument" to """tasks.test { jvmArgs("-D$switch=true") }""",
        "a properties file in a directory" to """sourceSets.test { resources.srcDir("parallel-config") }""",
        "a properties file in a jar" to """dependencies { testRuntimeOnly(files("libs/parallel-config.jar")) }""",
    )

    /**
     * Each file of the map, by its path in the map directory, to a digest of its bytes. A run's own
     * files are left out: its raw records, its decision record, the change set it compared and the
     * markers it leaves pending.
     */
    private fun mapDigest(dir: File): Map<String, String> {
        val mapDir = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)
        val perRun = Regex("""raw/.*|decisions\.tsv.*|task-facts|change-set|classpath-files\.compared|.*\.(pending|part|marker)""")
        return mapDir.walk().filter(File::isFile).filterNot { perRun.matches(it.relativeTo(mapDir).invariantSeparatorsPath) }.associate { file ->
            file.relativeTo(mapDir).invariantSeparatorsPath to
                MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        }.toSortedMap()
    }

    private fun declined(dir: File, route: String, vararg args: String) {
        val before = mapDigest(dir)
        File(dir, "build/jacoco").deleteRecursively()
        File(dir, "build/test-results").deleteRecursively()

        val output = runner(dir, "test", "-Pparallel", *args).build().output

        assertContains(output, "predictive test selection is declined here", message = route)
        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir), "$route: $output")
        assertFalse(File(dir, "build/jacoco/test.exec").exists(), "$route: JaCoCo instrumented the run")
        val after = mapDigest(dir)
        val changed = (before.keys + after.keys).filter { before[it] != after[it] }
        assertEquals(emptyList(), changed, "$route: these map files changed")
    }

    @Test
    fun `every route is declined on a recording run and on a selecting run, and the map is left as it was`(
        @TempDir root: File,
    ) {
        for ((index, entry) in routes.entries.withIndex()) {
            val (route, script) = entry
            val dir = File(root, "route$index").apply { mkdirs() }
            build(
                dir, "build.gradle.kts" to minimalBuild + "\nif (providers.gradleProperty(\"parallel\").isPresent) {\n$script\n}\n",
                oneClass, oneTest, secondClass, secondTest,
                "parallel-config/junit-platform.properties" to "$switch=true\n",
            )
            File(dir, "libs").mkdirs()
            ZipOutputStream(File(dir, "libs/parallel-config.jar").outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("junit-platform.properties"))
                zip.write("$switch=true\n".toByteArray())
            }
            committed(dir)
            runner(dir, "test").build()

            declined(dir, "$route, recording")
            File(dir, "src/main/java/dev/sample/Alpha.java").appendText("\n")
            declined(dir, "$route, selecting", "-Pyoriwake.select")
        }
    }

    /**
     * What the test JVM sees when a JVM argument and a system property disagree on the switch, and
     * whether a host's own doFirst runs after the plugin's actions. Printed for the plan; asserted
     * only as far as today's decline goes.
     */
    @Test
    fun `which setting of the switch the test JVM sees, and when a host's doFirst runs`(@TempDir root: File) {
        val probe = "src/test/java/dev/sample/ProbeTest.java" to """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            class ProbeTest {
                @Test void probe() throws Exception {
                    java.nio.file.Files.writeString(java.nio.file.Path.of("probe.txt"),
                        String.valueOf(System.getProperty("$switch")));
                }
            }
        """.trimIndent()
        val conflicting = File(root, "conflicting").apply { mkdirs() }
        build(conflicting, "build.gradle.kts" to minimalBuild + "\n" + """
            tasks.test { jvmArgs("-D$switch=true"); systemProperty("$switch", "false") }
        """.trimIndent(), oneClass, probe)
        committed(conflicting)
        val output = runner(conflicting, "test").build().output
        println("PROBE jvmArgs true + systemProperty false on Gradle ${FunctionalGradle.version}: test JVM sees " +
            File(conflicting, "probe.txt").readText() + "; declined: " + ("declined here" in output))

        val late = File(root, "late").apply { mkdirs() }
        build(late, "build.gradle.kts" to minimalBuild + "\n" + """
            tasks.test { doFirst { systemProperty("$switch", "true") } }
        """.trimIndent(), oneClass, probe)
        committed(late)
        val lateOutput = runner(late, "test").build().output
        println("PROBE a host doFirst setting the switch on Gradle ${FunctionalGradle.version}: test JVM sees " +
            File(late, "probe.txt").readText() + "; declined: " + ("declined here" in lateOutput))
    }
}
