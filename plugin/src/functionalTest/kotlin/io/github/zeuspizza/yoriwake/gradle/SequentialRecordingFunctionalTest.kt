package io.github.zeuspizza.yoriwake.gradle

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A test JVM records only once it has confirmed its tests run one at a time: parallel execution
 * the plugin could not see from the build script still leaves the map as it was.
 */
class SequentialRecordingFunctionalTest : FunctionalTestSupport() {

    private val switch = "junit.jupiter.execution.parallel.enabled"

    /** Each map file by its path to a digest of its bytes, leaving out what every run writes afresh. */
    private fun mapDigest(dir: File): Map<String, String> {
        val mapDir = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)
        val perRun = Regex("""raw/.*|decisions\.tsv.*|task-facts|change-set|classpath-files\.compared|.*\.(pending|part|marker)""")
        return mapDir.walk().filter(File::isFile).filterNot { perRun.matches(it.relativeTo(mapDir).invariantSeparatorsPath) }.associate { file ->
            file.relativeTo(mapDir).invariantSeparatorsPath to
                MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        }.toSortedMap()
    }

    @Test
    fun `parallel execution a host's doFirst turns on records nothing`(@TempDir dir: File) {
        build(
            dir,
            "build.gradle.kts" to minimalBuild + "\nif (providers.gradleProperty(\"late\").isPresent) {\n" +
                "tasks.test { doFirst { systemProperty(\"$switch\", \"true\") } }\n}\n",
            oneClass, oneTest, secondClass, secondTest,
        )
        committed(dir)
        runner(dir, "test").build()
        val before = mapDigest(dir)
        File(dir, "src/main/java/dev/sample/Alpha.java").appendText("\n")
        File(dir, "build/test-results").deleteRecursively()

        val output = runner(dir, "test", "-Plate").build().output

        assertContains(output, "JUnit Jupiter's parallel execution is on in the test JVM")
        assertContains(output, "the map is left exactly as it was")
        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir), output)
        val after = mapDigest(dir)
        assertEquals(emptyList(), (before.keys + after.keys).filter { before[it] != after[it] }, output)

        val mapDir = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)
        assertContains(runner(dir, "yoriwakeExplainTest").build().output, "the last run captured nothing")
        assertContains(File(mapDir, "explain.json").readText(), "\"parallelismUnconfirmed\": \"JUnit Jupiter's")
        runner(dir, "yoriwakeAuditTest").build()
        assertContains(File(mapDir, "audit.json").readText(), "in-jvm-parallelism")
    }

    private val parallelSuite = """
        tasks.test {
            systemProperty("$switch", "true")
            systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
        }
    """.trimIndent()

    @Test
    fun `a flagged recording run of a parallel suite records it one test at a time`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to minimalBuild + "\n" + parallelSuite, oneClass, oneTest, secondClass, secondTest)
        committed(dir)

        val output = runner(dir, "test", "-Pyoriwake.recordSequentially").build().output

        assertContains(output, "recording one test at a time")
        assertContains(output, "in the test task's system properties")
        assertEquals("recorded-sequentially", decisionNotes(dir)["parallelism"], output)
        val coverage = File(File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory), "coverage.tsv").readText()
        assertContains(coverage, "AlphaTest")
        assertContains(coverage, "BetaTest")
    }

    @Test
    fun `the extension property alone records sequentially, and the command line turns it off`(@TempDir dir: File) {
        build(
            dir, "build.gradle.kts" to minimalBuild + "\n" + parallelSuite + "\nyoriwake { recordSequentially = true }\n",
            oneClass, oneTest, secondClass, secondTest,
        )
        committed(dir)

        assertContains(runner(dir, "test").build().output, "recording one test at a time")
        File(dir, "build/test-results").deleteRecursively()
        assertContains(
            runner(dir, "test", "-Pyoriwake.recordSequentially=false", "--rerun-tasks").build().output,
            "predictive test selection is declined here",
        )
    }

    @Test
    fun `a flagged run whose host turns parallel execution back on records nothing`(@TempDir dir: File) {
        build(
            dir,
            "build.gradle.kts" to minimalBuild + "\nif (providers.gradleProperty(\"late\").isPresent) {\n" +
                "tasks.test { doFirst { systemProperty(\"$switch\", \"true\") } }\n}\n",
            oneClass, oneTest, secondClass, secondTest,
        )
        committed(dir)
        runner(dir, "test").build()
        val before = mapDigest(dir)
        File(dir, "src/main/java/dev/sample/Alpha.java").appendText("\n")

        val output = runner(dir, "test", "-Plate", "-Pyoriwake.recordSequentially").build().output

        assertContains(output, "the map is left exactly as it was")
        assertEquals("override-unconfirmed", decisionNotes(dir)["parallelism"], output)
        val after = mapDigest(dir)
        assertEquals(emptyList(), (before.keys + after.keys).filter { before[it] != after[it] }, output)
    }

    @Test
    fun `a flagged selecting run narrows with the suite's own scheduling and captures nothing`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to minimalBuild + "\n" + parallelSuite, oneClass, oneTest, secondClass, secondTest)
        committed(dir)
        runner(dir, "test", "-Pyoriwake.recordSequentially").build()
        // Beta, which only the second test loads: the first ran before it, so it is left out.
        File(dir, "src/main/java/dev/sample/Beta.java").writeText(
            "package dev.sample;\npublic class Beta { public int thrice(int n) { return n + n + n; } }"
        )
        val before = mapDigest(dir)
        File(dir, "build/test-results").deleteRecursively()

        val output = runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.recordSequentially").build().output

        assertContains(output, "tests run in parallel as configured")
        assertEquals(setOf("dev.sample.BetaTest"), ranTests(dir), output)
        assertEquals("kept-not-captured", decisionNotes(dir)["parallelism"], output)
        val after = mapDigest(dir)
        assertEquals(emptyList(), (before.keys + after.keys).filter { before[it] != after[it] }, output)

        // Declined into a full run, it still keeps the suite's scheduling and leaves the map alone.
        File(dir, "build/test-results").deleteRecursively()
        val declined = runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.fullRun", "-Pyoriwake.recordSequentially").build().output
        assertContains(declined, "tests run in parallel as configured")
        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir), declined)
        val afterDeclined = mapDigest(dir)
        assertEquals(emptyList(), (before.keys + afterDeclined.keys).filter { before[it] != afterDeclined[it] }, declined)
    }

    @Test
    fun `parallelism a JVM argument provider turns on outlasts the override and records nothing`(@TempDir dir: File) {
        build(
            dir,
            "build.gradle.kts" to minimalBuild + "\n" +
                "tasks.test { jvmArgumentProviders.add(CommandLineArgumentProvider { listOf(\"-D$switch=true\") }) }\n",
            oneClass, oneTest, secondClass, secondTest,
        )
        committed(dir)

        val output = runner(dir, "test", "-Pyoriwake.recordSequentially").build().output

        // A provider's arguments come after the task's system properties, so the last -D is the host's.
        assertEquals("override-unconfirmed", decisionNotes(dir)["parallelism"], output)
        assertContains(output, "the map is left exactly as it was")
    }

    private fun mapDirOf(dir: File) = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)

    @Test
    fun `a flagged selecting run on a map of unknown age keeps the suite's scheduling and records nothing`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to minimalBuild + "\n" + parallelSuite, oneClass, oneTest, secondClass, secondTest)
        committed(dir)
        runner(dir, "test", "-Pyoriwake.recordSequentially").build()
        // With no stamp the map's age is unknown, and a selecting run would refresh it by running everything.
        assertTrue(File(mapDirOf(dir), "capture-commit").delete())
        File(dir, "src/main/java/dev/sample/Beta.java").appendText("\n")
        File(dir, "build/test-results").deleteRecursively()

        val output = runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.recordSequentially").build().output

        assertContains(output, "tests run in parallel as configured")
        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir), output)
        assertEquals("kept-not-captured", decisionNotes(dir)["parallelism"], output)
        assertFalse(File(mapDirOf(dir), "capture-commit").exists(), "the map was refreshed from a parallel run")
    }

    @Test
    fun `flagged selecting runs count toward the scheduled full run`(@TempDir dir: File) {
        build(
            dir, "build.gradle.kts" to minimalBuild + "\n" + parallelSuite + "\nyoriwake { fullRunEveryBuilds = 2 }\n",
            oneClass, oneTest, secondClass, secondTest,
        )
        committed(dir)
        runner(dir, "test", "-Pyoriwake.recordSequentially").build()

        val kinds = (1..3).map { n ->
            File(dir, "src/main/java/dev/sample/Beta.java").writeText(
                "package dev.sample;\npublic class Beta { public int thrice(int n) { int k = $n; return n * 3; } }"
            )
            commit(dir, "change $n")
            val output = runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.recordSequentially", "-P${Settings.BASE}=HEAD~1").build().output
            decisionNotes(dir)["refusal-kind"] to output
        }

        assertTrue(kinds.any { it.first == "full-run-scheduled" }, kinds.joinToString("\n") { it.second })
    }

    @Test
    fun `the flag leaves a parallel TestNG suite declined, and says it covers the JUnit Platform only`(@TempDir dir: File) {
        build(
            dir,
            "build.gradle.kts" to """
                plugins {
                    java
                    jacoco
                    id("io.github.zeuspizza.yoriwake")
                }
                repositories { mavenCentral() }
                dependencies { testImplementation("org.testng:testng:7.10.2") }
                tasks.test { useTestNG { parallel = "methods"; threadCount = 2 } }
            """.trimIndent(),
            oneClass,
            "src/test/java/dev/sample/AlphaTest.java" to """
                package dev.sample;
                import org.testng.annotations.Test;
                import static org.testng.Assert.assertEquals;
                public class AlphaTest {
                    @Test public void passes() { assertEquals(new Alpha().twice(1), 2); }
                }
            """.trimIndent(),
        )
        committed(dir)

        val output = runner(dir, "test", "-Pyoriwake.recordSequentially").build().output

        assertContains(output, "predictive test selection is declined here")
        assertContains(output, "covers JUnit Jupiter and Vintage on the JUnit Platform only")
        assertFalse("recording one test at a time" in output, output)
        assertFalse(File(dir, "build/jacoco/test.exec").exists(), "JaCoCo instrumented the run")
    }
}
