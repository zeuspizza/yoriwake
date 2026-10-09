package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.agent.contract.Tsv
import io.github.zeuspizza.yoriwake.gradle.capture.CoverageDecoder
import org.gradle.testkit.runner.BuildResult
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A host that retries failed tests with `org.gradle.test-retry`, or with Develocity's own retry:
 * the retry rounds run inside the same `Test` task, in new test JVMs. A retried test keeps one
 * honest outcome in the map, its coverage stays its own, and a selecting run's retry round runs
 * every test its first round ran and saw fail.
 */
class TestRetryFunctionalTest : FunctionalTestSupport() {

    /** The current test-retry release, pinned so a change in its internals shows up here first. */
    private val testRetryVersion = "1.6.6"

    /** The outcome of a test whose records in one capture hold both a pass and a failure. */
    private val flaky = "FLAKY"

    private fun retryBuild(retry: String = "maxRetries.set(1)") = minimalBuild
        .replace(
            "id(\"io.github.zeuspizza.yoriwake\")",
            "id(\"org.gradle.test-retry\") version \"$testRetryVersion\"\n    id(\"io.github.zeuspizza.yoriwake\")",
        )
        .replace(
            "tasks.test { useJUnitPlatform() }",
            """
            tasks.test {
                useJUnitPlatform()
                systemProperty("script.dir", layout.buildDirectory.dir("script").get().asFile.absolutePath)
                retry { $retry }
            }
            """.trimIndent(),
        )

    /** Develocity's built-in retry in place of the test-retry plugin. */
    private val develocityRetryBuild = minimalBuild.replace(
        "tasks.test { useJUnitPlatform() }",
        """
        tasks.test {
            useJUnitPlatform()
            systemProperty("script.dir", layout.buildDirectory.dir("script").get().asFile.absolutePath)
            develocity { testRetry { maxRetries.set(1) } }
        }
        """.trimIndent(),
    )

    /**
     * Fails a test on the executions it is armed for: `build/script/<name>` holds the number of the
     * execution that fails, counted across the task's JVMs, or `every`.
     */
    private val script = "src/test/java/dev/sample/Script.java" to """
        package dev.sample;
        import java.nio.file.Files;
        import java.nio.file.Path;
        final class Script {
            private Script() {}
            static boolean failsNow(String name) throws Exception {
                Path dir = Path.of(System.getProperty("script.dir"));
                Path armed = dir.resolve(name);
                if (!Files.isRegularFile(armed)) return false;
                Path count = dir.resolve(name + ".count");
                int n = Files.isRegularFile(count) ? Integer.parseInt(Files.readString(count).trim()) + 1 : 1;
                Files.writeString(count, Integer.toString(n));
                String plan = Files.readString(armed).trim();
                return plan.equals("every") || plan.equals(Integer.toString(n));
            }
        }
    """.trimIndent()

    private val zetaClass = "src/main/java/dev/sample/Zeta.java" to """
        package dev.sample;
        public class Zeta { public int square(int n) { return n * n; } }
    """.trimIndent()

    /** First by name, and reaches only Beta, which an Alpha change leaves behind. */
    private val ableTest = "src/test/java/dev/sample/AbleTest.java" to """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class AbleTest {
            @Test void passes() { assertEquals(3, new Beta().thrice(1)); }
        }
    """.trimIndent()

    /**
     * `flips` runs before `zSibling`, so a Zeta change reaches the sibling and not `flips`. When it
     * fails, `flips` executes Beta first, so its failing round covers a class its passing one does not.
     */
    private val flipTest = "src/test/java/dev/sample/FlipTest.java" to """
        package dev.sample;
        import org.junit.jupiter.api.MethodOrderer;
        import org.junit.jupiter.api.Test;
        import org.junit.jupiter.api.TestMethodOrder;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        import static org.junit.jupiter.api.Assertions.fail;
        @TestMethodOrder(MethodOrderer.MethodName.class)
        class FlipTest {
            @Test void flips() throws Exception {
                assertEquals(2, new Alpha().twice(1));
                if (Script.failsNow("flips")) {
                    new Beta().thrice(1);
                    fail("armed to fail this execution");
                }
            }
            @Test void zSibling() { assertEquals(4, new Zeta().square(2)); }
        }
    """.trimIndent()

    private val zetaTest = "src/test/java/dev/sample/ZetaTest.java" to """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class ZetaTest {
            @Test void passes() { assertEquals(9, new Zeta().square(3)); }
        }
    """.trimIndent()

    /** Retried whole under `classRetry`: `bPassThenFail` passes in the first round, fails in the second. */
    private val passThenFailTest = "src/test/java/dev/sample/PassThenFailTest.java" to """
        package dev.sample;
        import org.junit.jupiter.api.MethodOrderer;
        import org.junit.jupiter.api.Test;
        import org.junit.jupiter.api.TestMethodOrder;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        import static org.junit.jupiter.api.Assertions.fail;
        @TestMethodOrder(MethodOrderer.MethodName.class)
        class PassThenFailTest {
            @Test void aFlips() throws Exception {
                assertEquals(2, new Alpha().twice(1));
                if (Script.failsNow("aFlips")) fail("armed to fail this execution");
            }
            @Test void bPassThenFail() throws Exception {
                assertEquals(3, new Beta().thrice(1));
                if (Script.failsNow("bPassThenFail")) fail("armed to fail this execution");
            }
        }
    """.trimIndent()

    private val alwaysFailTest = "src/test/java/dev/sample/AlwaysFailTest.java" to """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        import static org.junit.jupiter.api.Assertions.fail;
        class AlwaysFailTest {
            @Test void fails() throws Exception {
                assertEquals(2, new Alpha().twice(1));
                if (Script.failsNow("always")) fail("armed to fail every execution");
            }
        }
    """.trimIndent()

    private fun sample(dir: File, buildScript: String = retryBuild(), vararg extra: Pair<String, String>) {
        build(
            dir, "build.gradle.kts" to buildScript, oneClass, secondClass, zetaClass, script,
            ableTest, oneTest, flipTest, zetaTest, classOrderByName, *extra,
        )
        committed(dir)
    }

    private fun sampleRetriedBy(retrying: String, dir: File) {
        if (retrying == TEST_RETRY) return sample(dir)
        sample(dir, develocityRetryBuild)
        File(dir, "settings.gradle.kts").writeText(
            "plugins { id(\"com.gradle.develocity\") version \"4.6.0\" }\nrootProject.name = \"sample\"\n"
        )
        commit(dir, "develocity")
    }

    /** Arms [name] to fail on its [execution]th execution from now, counted across JVMs. */
    private fun arm(dir: File, name: String, execution: String = "1") {
        val scripts = File(dir, "build/script").apply { mkdirs() }
        File(scripts, "$name.count").delete()
        File(scripts, name).writeText(execution)
    }

    private fun run(dir: File, vararg args: String): BuildResult {
        File(dir, "build/test-results").deleteRecursively()
        return runner(dir, "test", *args).build()
    }

    private fun changeZeta(dir: File) = File(dir, "src/main/java/dev/sample/Zeta.java").writeText(
        """
        package dev.sample;
        public class Zeta { public int square(int n) { return n * n + 0; } }
        """.trimIndent()
    )

    private fun changeAlpha(dir: File) = File(dir, "src/main/java/dev/sample/Alpha.java").writeText(
        """
        package dev.sample;
        public class Alpha { public int twice(int n) { return n + n; } }
        """.trimIndent()
    )

    private fun mapDir(dir: File) = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)

    private fun id(testClass: String, method: String) =
        "[engine:junit-jupiter]/[class:dev.sample.$testClass]/[method:$method()]"

    private val productionClasses = setOf("dev.sample.Alpha", "dev.sample.Beta", "dev.sample.Zeta")

    /** The map's lines for one test, as `outcome` and the production classes it covered. */
    private fun lines(dir: File, testClass: String, method: String): List<Pair<String, Set<String>>> =
        File(mapDir(dir), AgentContract.COVERAGE_FILE).readLines()
            .map(Tsv::split)
            .filter { it.size == 4 && it[3] == id(testClass, method) }
            .map { it[0] to it[2].split(',').filter { name -> name in productionClasses }.toSet() }

    private fun outcomes(dir: File, testClass: String, method: String) =
        lines(dir, testClass, method).map { it.first }

    /** How often each test case of a class executed in the last run, by its report. */
    private fun executions(dir: File, testClass: String, method: String): Int {
        val report = File(dir, "build/test-results/test/TEST-dev.sample.$testClass.xml")
        if (!report.isFile) return 0
        return Regex("""<testcase name="${Regex.escape("$method()")}"""").findAll(report.readText()).count()
    }

    private fun modes(dir: File) =
        File(mapDir(dir), AgentContract.JVM_MODE_FILE).readLines().filter(String::isNotBlank)
            .map { it.substringAfter('\t') }.toSet()

    @Test
    fun `a test that fails and passes on retry is recorded flaky, with both rounds' coverage`(@TempDir dir: File) {
        sample(dir)
        arm(dir, "flips")

        val output = run(dir).output

        assertEquals(2, executions(dir, "FlipTest", "flips"), output)
        val flips = lines(dir, "FlipTest", "flips")
        assertEquals(listOf(flaky, flaky), flips.map { it.first })
        assertEquals(setOf("dev.sample.Alpha", "dev.sample.Beta"), flips.flatMap { it.second }.toSet())
        assertTrue(flips.any { it.second == setOf("dev.sample.Alpha") }, "the passing round's line is gone: $flips")
        // The tests around it are recorded as if nothing had been retried.
        assertEquals(listOf(AgentContract.OUTCOME_SUCCESSFUL to setOf("dev.sample.Alpha")), lines(dir, "AlphaTest", "passes"))
        assertEquals(listOf(AgentContract.OUTCOME_SUCCESSFUL), outcomes(dir, "FlipTest", "zSibling"))
    }

    @Test
    fun `a test that flipped during a capture runs on a change it does not reach`(@TempDir dir: File) {
        sample(dir)
        arm(dir, "flips")
        run(dir)
        changeZeta(dir)

        val output = run(dir, "-Pyoriwake.select").output

        assertEquals("narrowed", decisionNotes(dir)["outcome"], output)
        assertEquals(1, executions(dir, "FlipTest", "flips"), output)
        assertEquals(0, executions(dir, "AlphaTest", "passes"), output)
    }

    @Test
    fun `a capture with a retried test still dates the map`(@TempDir dir: File) {
        sample(dir)
        arm(dir, "flips")

        val output = run(dir).output

        assertContains(output, "map updated")
        assertTrue(File(mapDir(dir), CoverageDecoder.CAPTURE_COMMIT_FILE).isFile, output)
        assertEquals(setOf(AgentContract.MODE_SHARED), modes(dir))
        // The retry round ran in a JVM of its own, and both JVMs hold a position.
        val jvms = File(mapDir(dir), AgentContract.POSITIONS_FILE).readLines().filter(String::isNotBlank)
            .filter { Tsv.split(it).last() == id("FlipTest", "flips") }.map { Tsv.split(it).first() }
        assertEquals(2, jvms.toSet().size, "positions: $jvms")
    }

    @Test
    fun `an isolated capture with a retried test is recorded isolated and flaky`(@TempDir dir: File) {
        sample(dir)
        arm(dir, "flips")

        val output = run(dir, "-Pyoriwake.isolatedCapture").output

        assertTrue(File(mapDir(dir), CoverageDecoder.CAPTURE_COMMIT_FILE).isFile, output)
        assertEquals(setOf(AgentContract.MODE_ISOLATED), modes(dir))
        assertEquals(listOf(flaky, flaky), outcomes(dir, "FlipTest", "flips"))
    }

    @Test
    fun `a test that passed and then failed when its class was retried is flaky, not successful`(@TempDir dir: File) {
        sample(
            dir,
            retryBuild("maxRetries.set(1)\n classRetry { includeClasses.add(\"dev.sample.PassThenFailTest\") }"),
            passThenFailTest, alwaysFailTest,
        )
        arm(dir, "aFlips", "1")
        arm(dir, "bPassThenFail", "2")
        arm(dir, "always", "every")

        val output = runner(dir, "test").buildAndFail().output

        assertEquals(2, executions(dir, "PassThenFailTest", "bPassThenFail"), output)
        assertEquals(listOf(flaky, flaky), outcomes(dir, "PassThenFailTest", "bPassThenFail"))
        assertEquals(listOf(flaky, flaky), outcomes(dir, "PassThenFailTest", "aFlips"))
        assertEquals(listOf("FAILED", "FAILED"), outcomes(dir, "AlwaysFailTest", "fails"))
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = [TEST_RETRY, DEVELOCITY])
    fun `a selecting run whose selected test flips records every discovered test once, as its first round decided`(
        retrying: String,
        @TempDir dir: File,
    ) {
        sampleRetriedBy(retrying, dir)
        run(dir)
        changeAlpha(dir)
        arm(dir, "flips")

        val output = run(dir, "-Pyoriwake.select").output

        assertEquals(2, executions(dir, "FlipTest", "flips"), output)
        val rows = File(mapDir(dir), AgentContract.DECISIONS_FILE).readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map(Tsv::split)
        val discovered = listOf(
            id("AbleTest", "passes"), id("AlphaTest", "passes"), id("FlipTest", "flips"),
            id("FlipTest", "zSibling"), id("ZetaTest", "passes"),
        )
        assertEquals(discovered.sorted(), rows.map { it[0] }.sorted(), output)
        assertEquals("excluded", rows.single { it[0] == id("AbleTest", "passes") }[1])
        assertEquals(setOf("included"), rows.filter { it[0] != id("AbleTest", "passes") }.map { it[1] }.toSet())
        assertEquals("narrowed", decisionNotes(dir)["outcome"])
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = [TEST_RETRY, DEVELOCITY])
    fun `a test included only because its class is selected runs again when it flips`(
        retrying: String,
        @TempDir dir: File,
    ) {
        sampleRetriedBy(retrying, dir)
        run(dir)
        changeZeta(dir)
        arm(dir, "flips")

        val output = run(dir, "-Pyoriwake.select", "-P${Settings.CLASS_GRANULARITY}=true").output

        assertEquals("CLASS_GRANULARITY", decisionReasons(dir)[id("FlipTest", "flips")], output)
        assertEquals(2, executions(dir, "FlipTest", "flips"), output)
    }

    @Test
    fun `a test Develocity retries is recorded flaky`(@TempDir dir: File) {
        sampleRetriedBy(DEVELOCITY, dir)
        arm(dir, "flips")

        val output = run(dir).output

        assertEquals(2, executions(dir, "FlipTest", "flips"), output)
        assertTrue(File(mapDir(dir), CoverageDecoder.CAPTURE_COMMIT_FILE).isFile, output)
        assertEquals(listOf(flaky, flaky), outcomes(dir, "FlipTest", "flips"))
    }

    private companion object {
        const val TEST_RETRY = "test-retry"
        const val DEVELOCITY = "develocity"
    }
}
