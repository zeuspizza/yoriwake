package io.github.zeuspizza.yoriwake.gradle

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Tests named on the command line with `--tests` run, whatever the map says: a developer who names
 * a test expects exactly that test to execute.
 *
 * Every case captures a map, then changes Beta, which AlphaTest never reaches: on its own the
 * change selects BetaTest alone, so a named AlphaTest that runs was not left to selection.
 */
class NamedTestsFunctionalTest : FunctionalTestSupport() {

    private val alphaTests = "src/test/java/dev/sample/AlphaTest.java" to """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class AlphaTest {
            @Test void adds() { assertEquals(2, new Alpha().twice(1)); }
            @Test void alsoAdds() { assertEquals(4, new Alpha().twice(2)); }
            @Test void doubles() { assertEquals(6, new Alpha().twice(3)); }
        }
    """.trimIndent()

    private val allAlpha = setOf("adds", "alsoAdds", "doubles")

    /**
     * A build-script filter switched on by `-Pnarrow`: on during capture it would leave the map
     * undated, and a change to the script itself would force a full run.
     */
    private val narrowedBuild = minimalBuild +
        "\ntasks.test { if (providers.gradleProperty(\"narrow\").isPresent) filter.includeTestsMatching(\"*Test\") }"

    /** A committed sample with a captured map and an uncommitted change to Beta. */
    private fun capturedWithBetaChanged(dir: File, buildScript: String = minimalBuild, vararg extra: Pair<String, String>) {
        build(dir, "build.gradle.kts" to buildScript, oneClass, alphaTests, secondClass, secondTest, classOrderByName, *extra)
        committed(dir)
        runner(dir, "test").build()
        File(dir, "build/test-results").deleteRecursively()
        changeBeta(dir)
    }

    /** The methods each test class ran, by class name, read from the JUnit XML reports. */
    private fun ranMethods(dir: File): Map<String, Set<String>> =
        File(dir, "build/test-results/test")
            .listFiles { f: File -> f.name.endsWith(".xml") }
            .orEmpty()
            .associate { report ->
                report.name.removePrefix("TEST-").removeSuffix(".xml") to
                    Regex("""<testcase name="([^"(]+)""").findAll(report.readText()).map { it.groupValues[1] }.toSet()
            }
            .filterValues { it.isNotEmpty() }

    @Test
    fun `a test named with --tests runs every method under selection`(@TempDir dir: File) {
        capturedWithBetaChanged(dir)

        val output = runner(dir, "test", "--tests", "dev.sample.AlphaTest", "-Pyoriwake.select").build().output

        assertEquals(mapOf("dev.sample.AlphaTest" to allAlpha), ranMethods(dir), output)
        assertContains(output, "tests were named with --tests dev.sample.AlphaTest, so selection is declined")
        assertEquals("tests-named", decisionNotes(dir)["refusal-kind"], output)
    }

    @Test
    fun `a reused configuration still declines for named tests`(@TempDir dir: File) {
        capturedWithBetaChanged(dir)
        runner(dir, "test", "--tests", "dev.sample.AlphaTest", "-Pyoriwake.select").build()
        File(dir, "build/test-results").deleteRecursively()

        val output = runner(dir, "test", "--tests", "dev.sample.AlphaTest", "-Pyoriwake.select", "--rerun-tasks")
            .build().output

        assertContains(output, "Reusing configuration cache")
        assertEquals(mapOf("dev.sample.AlphaTest" to allAlpha), ranMethods(dir), output)
        assertEquals("tests-named", decisionNotes(dir)["refusal-kind"], output)
    }

    @Test
    fun `the explanation names the decline for named tests`(@TempDir dir: File) {
        capturedWithBetaChanged(dir)

        val output = runner(dir, "test", "--tests", "dev.sample.AlphaTest", "yoriwakeExplainTest", "-Pyoriwake.select")
            .build().output

        val mapDir = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)
        assertContains(File(mapDir, "explain.json").readText(), "\"refusalKind\": \"tests-named\"", message = output)
    }

    @Test
    fun `named tests decline even when a change would force a full run`(@TempDir dir: File) {
        val limits = "src/main/java/dev/sample/Limits.java" to """
            package dev.sample;
            public class Limits { public static final int MAX = 10; }
        """.trimIndent()
        val bakedIn = "src/test/java/dev/sample/BetaTest.java" to """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class BetaTest {
                @Test void passes() { assertEquals(10, Limits.MAX); }
            }
        """.trimIndent()
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, alphaTests, limits, bakedIn, classOrderByName)
        committed(dir)
        runner(dir, "test").build()
        File(dir, "build/test-results").deleteRecursively()
        File(dir, limits.first).writeText(limits.second.replace("MAX = 10", "MAX = 11"))

        val output = runner(dir, "test", "--tests", "dev.sample.AlphaTest", "-Pyoriwake.select").build().output

        assertEquals(mapOf("dev.sample.AlphaTest" to allAlpha), ranMethods(dir), output)
        assertEquals("tests-named", decisionNotes(dir)["refusal-kind"], output)
    }

    /** Every AlphaTest method ran, and the run declined for named tests rather than for [other]. */
    private fun assertDeclinedForNamedTests(dir: File, output: String, other: String) {
        assertEquals(mapOf("dev.sample.AlphaTest" to allAlpha), ranMethods(dir), output)
        assertEquals("tests-named", decisionNotes(dir)["refusal-kind"], output)
        assertFalse(other in decisionNotes(dir).values, output)
    }

    private val alphaNamed = arrayOf("test", "--tests", "dev.sample.AlphaTest", "-Pyoriwake.select")

    @Test
    fun `named tests decline on a map whose age is unknown`(@TempDir dir: File) {
        capturedWithBetaChanged(dir)
        val mapDir = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)
        File(mapDir, io.github.zeuspizza.yoriwake.gradle.capture.CoverageDecoder.CAPTURE_COMMIT_FILE)
            .writeText("f".repeat(40))

        val output = runner(dir, *alphaNamed).build().output

        assertDeclinedForNamedTests(dir, output, "stamp-unrelatable")
    }

    @Test
    fun `named tests decline when the tree changes after configuration`(@TempDir dir: File) {
        val editsLater = minimalBuild + """

            val editFixture by tasks.registering {
                val root = layout.projectDirectory.asFile
                doLast { File(root, "src/test/resources/fixture.txt").writeText("two") }
            }
            tasks.processTestResources { mustRunAfter(editFixture) }
            tasks.test { mustRunAfter(editFixture) }
        """.trimIndent()
        capturedWithBetaChanged(dir, editsLater, "src/test/resources/fixture.txt" to "one")

        val output = runner(dir, "editFixture", *alphaNamed).build().output

        assertDeclinedForNamedTests(dir, output, "change-set-stale")
    }

    @Test
    fun `named tests decline under in-JVM parallelism`(@TempDir dir: File) {
        val parallelOnRequest = minimalBuild + "\ntasks.test { if (providers.gradleProperty(\"parallel\").isPresent) " +
            "systemProperty(\"junit.jupiter.execution.parallel.enabled\", \"true\") }"
        capturedWithBetaChanged(dir, parallelOnRequest)

        val output = runner(dir, *alphaNamed, "-Pparallel").build().output

        assertDeclinedForNamedTests(dir, output, "in-jvm-parallelism")
    }

    @Test
    fun `named tests decline on a map the trusted list does not name, and leave it in place`(@TempDir dir: File) {
        capturedWithBetaChanged(dir)
        val mapDir = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)
        val digest = File(mapDir, io.github.zeuspizza.yoriwake.agent.contract.AgentContract.MAP_DIGEST_FILE).readText()
        val list = File(dir, "build/trusted.tsv").also { it.parentFile.mkdirs(); it.writeText("") }

        val output = runner(dir, *alphaNamed, "-Pyoriwake.trustedMaps=${list.absolutePath}").build().output

        assertDeclinedForNamedTests(dir, output, "map-unverified")
        assertEquals(digest, File(mapDir, io.github.zeuspizza.yoriwake.agent.contract.AgentContract.MAP_DIGEST_FILE).readText())
    }

    @Test
    fun `--tests without selection declines nothing`(@TempDir dir: File) {
        capturedWithBetaChanged(dir)

        val output = runner(dir, "test", "--tests", "dev.sample.AlphaTest").build().output

        assertEquals(mapOf("dev.sample.AlphaTest" to allAlpha), ranMethods(dir), output)
        assertFalse("tests were named" in output, output)
    }

    @Test
    fun `a build-script filter without --tests still narrows`(@TempDir dir: File) {
        capturedWithBetaChanged(dir, narrowedBuild)

        val output = runner(dir, "test", "-Pnarrow", "-Pyoriwake.select").build().output

        assertEquals(setOf("dev.sample.BetaTest"), ranTests(dir), output)
    }

    @Test
    fun `--tests beside a build-script filter runs every named test`(@TempDir dir: File) {
        capturedWithBetaChanged(dir, narrowedBuild)

        val output = runner(dir, "test", "-Pnarrow", "--tests", "dev.sample.AlphaTest", "-Pyoriwake.select")
            .build().output

        assertEquals(mapOf("dev.sample.AlphaTest" to allAlpha), ranMethods(dir), output)
    }

    @Test
    fun `a package pattern runs every test under it`(@TempDir dir: File) {
        capturedWithBetaChanged(dir)

        val output = runner(dir, "test", "--tests", "dev.sample.*", "-Pyoriwake.select").build().output

        assertEquals(
            mapOf("dev.sample.AlphaTest" to allAlpha, "dev.sample.BetaTest" to setOf("passes")),
            ranMethods(dir),
            output,
        )
    }

    @Test
    fun `a method pattern runs every method it matches`(@TempDir dir: File) {
        capturedWithBetaChanged(dir)

        val output = runner(dir, "test", "--tests", "dev.sample.AlphaTest.a*", "-Pyoriwake.select").build().output

        assertEquals(mapOf("dev.sample.AlphaTest" to setOf("adds", "alsoAdds")), ranMethods(dir), output)
    }

    @Test
    fun `a JUnit 4 test named with --tests runs under selection`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to junit4Build, oneClass, betaClass, *junit4Tests)
        committed(dir)
        runner(dir, "test").build()
        File(dir, "build/test-results").deleteRecursively()
        changeBeta(dir)

        val output = runner(dir, "test", "--tests", "dev.sample.AlphaTest", "-Pyoriwake.select").build().output

        assertEquals(setOf("dev.sample.AlphaTest"), ranTests(dir), output)
    }
}
