package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.gradle.capture.CoverageDecoder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `-Pyoriwake.mode=optimistic`: a selecting run leaves out the tests only the windows a change opens
 * in a shared JVM select, says how many on every run, and never touches the map.
 *
 * The fixture is a context built once: B1 builds it from AppConfig, B2 uses what it built. A change
 * to AppConfig reaches B1 by coverage and B2 only through the window, so the safe rule runs both and
 * optimistic mode B1 alone.
 */
class OptimisticModeFunctionalTest : FunctionalTestSupport() {

    private val orderedBuild = minimalBuild.replace(
        "tasks.test { useJUnitPlatform() }",
        """
        tasks.test {
            useJUnitPlatform()
            systemProperty("junit.jupiter.testclass.order.default", "org.junit.jupiter.api.ClassOrderer\${'$'}ClassName")
        }
        """.trimIndent(),
    )

    private fun main(name: String, body: String) =
        "src/main/java/dev/sample/$name.java" to "package dev.sample;\n$body"

    private fun test(name: String, body: String) =
        "src/test/java/dev/sample/$name.java" to """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.*;
            class $name {
                $body
            }
        """.trimIndent()

    private val contextSources = arrayOf(
        main("Other", "public class Other { public int one() { return 1; } }"),
        main(
            "Greeter",
            """
            public class Greeter {
                private final String word;
                public Greeter(String word) { this.word = word; }
                public String greet(String name) { return word + " " + name; }
            }
            """.trimIndent(),
        ),
        main("AppConfig", "public class AppConfig { public Greeter greeter() { return new Greeter(\"hello\"); } }"),
        main(
            "AppContext",
            """
            public final class AppContext {
                private static AppContext cached;
                private final Greeter greeter;
                private AppContext(AppConfig config) { this.greeter = config.greeter(); }
                public static synchronized AppContext get() {
                    if (cached == null) cached = new AppContext(new AppConfig());
                    return cached;
                }
                public Greeter greeter() { return greeter; }
            }
            """.trimIndent(),
        ),
        test("B0UnrelatedTest", "@Test void runsFirst() { assertEquals(1, new Other().one()); }"),
        test("B1ContextFirstTest", "@Test void loads() { assertNotNull(AppContext.get().greeter()); }"),
        test("B2ContextLaterTest", "@Test void greets() { assertNotNull(AppContext.get().greeter().greet(\"ann\")); }"),
    )

    private fun names(vararg simple: String) = simple.map { "dev.sample.$it" }.toSet()

    private fun mapDir(dir: File) = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)

    /** A captured, committed fixture with AppConfig changed in the working tree. */
    private fun capturedWithConfigChanged(dir: File, buildScript: String = orderedBuild, vararg extra: Pair<String, String>) {
        build(dir, "build.gradle.kts" to buildScript, *contextSources, *extra)
        committed(dir)
        runner(dir, "test").build()
        val config = File(dir, "src/main/java/dev/sample/AppConfig.java")
        config.writeText(config.readText().replace("\"hello\"", "\"hi\""))
        File(dir, "build/test-results").deleteRecursively()
    }

    /**
     * Every file of the map a run reads to select, by content digest. Left out are the records each run
     * writes of itself: its decisions, change set, task facts, raw records, selection record and
     * explanation, and the handover files a run deletes as it reads them.
     */
    private fun mapDigest(dir: File): Map<String, String> {
        val root = mapDir(dir)
        val perRun = listOf(
            AgentContract.DECISIONS_FILE, AgentContract.CHANGE_SET_FILE, AgentContract.SELECTION_FILE,
            "task-facts", "explain.json", "audit.json", "classpath-files.compared",
        )
        return root.walkTopDown().filter(File::isFile)
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .filterNot { path ->
                path.startsWith("raw/") || path.endsWith(".pending") || perRun.any { path.startsWith(it) }
            }
            .associateWith { path ->
                MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                    .joinToString("") { "%02x".format(it) }
            }
            .toSortedMap()
            .also { assertTrue(AgentContract.COVERAGE_FILE in it, "the map holds no coverage: $it") }
    }

    private fun warnings(output: String) = output.lines().filter { "optimistic mode:" in it }

    @Test
    fun `optimistic mode runs only what a kept rule selects, records the rest as loosened and leaves the map`(
        @TempDir dir: File,
    ) {
        capturedWithConfigChanged(dir)
        val before = mapDigest(dir)

        val output = runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD", "-Pyoriwake.mode=optimistic")
            .build().output

        assertEquals(names("B1ContextFirstTest"), ranTests(dir), output)
        val reasons = decisionReasons(dir)
        assertEquals("LOOSENED", reasons.entries.single { "B2ContextLaterTest" in it.key }.value)
        assertEquals("SKIPPED", reasons.entries.single { "B0UnrelatedTest" in it.key }.value)
        val rules = File(mapDir(dir), AgentContract.DECISIONS_FILE).readLines()
            .single { it.startsWith(AgentContract.RULES_LINE_PREFIX) && "B2ContextLaterTest" in it }
        assertTrue(rules.endsWith("\t" + AgentContract.RULE_SHARES_JVM_CHANGED_CLASS), rules)
        assertEquals("optimistic", decisionNotes(dir)[AgentContract.MODE_NOTE])
        assertEquals(1, warnings(output).size, output)
        assertContains(warnings(output).single(), "optimistic mode: 1 tests the safe rule runs were left out")
        assertContains(warnings(output).single(), "sets no full-run policy")
        assertEquals(before, mapDigest(dir))

        File(dir, "build/test-results").deleteRecursively()
        runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD").build()
        assertEquals(names("B1ContextFirstTest", "B2ContextLaterTest"), ranTests(dir), "the safe rule runs both")
        assertFalse(AgentContract.MODE_NOTE in decisionNotes(dir), "a safe run carries no mode note")
    }

    @Test
    fun `optimistic mode still runs a test the map never saw and one not known to pass`(@TempDir dir: File) {
        capturedWithConfigChanged(dir)
        File(dir, "src/test/java/dev/sample/B3NewTest.java").writeText(
            test("B3NewTest", "@Test void fresh() { assertEquals(1, new Other().one()); }").second,
        )
        // B0 recorded as failing: a test the map does not record as passing runs.
        val coverage = File(mapDir(dir), AgentContract.COVERAGE_FILE)
        coverage.writeText(
            coverage.readLines().joinToString("\n", postfix = "\n") { line ->
                if ("B0UnrelatedTest" in line) line.replaceBefore('\t', "FAILED") else line
            },
        )

        runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD", "-Pyoriwake.mode=optimistic").build()

        assertEquals(names("B0UnrelatedTest", "B1ContextFirstTest", "B3NewTest"), ranTests(dir))
    }

    @Test
    fun `an unknown mode fails the build naming the flag`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to orderedBuild, *contextSources)
        committed(dir)

        val output = runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.mode=fast").buildAndFail().output

        assertContains(output, "-Pyoriwake.mode=fast is not a mode")
    }

    @Test
    fun `a mode set in gradle properties fails the build naming the flag`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to orderedBuild, "gradle.properties" to "yoriwake.mode=optimistic\n", *contextSources)
        committed(dir)

        val output = runner(dir, "test", "-Pyoriwake.select").buildAndFail().output

        assertContains(output, "yoriwake.mode=optimistic is set outside the command line")
    }

    @Test
    fun `without select the mode has no effect, and the run records`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to orderedBuild, *contextSources)
        committed(dir)

        val output = runner(dir, "test", "-Pyoriwake.mode=optimistic").build().output

        assertEquals(names("B0UnrelatedTest", "B1ContextFirstTest", "B2ContextLaterTest"), ranTests(dir))
        assertContains(output, "-Pyoriwake.mode=optimistic has no effect without -Pyoriwake.select")
        assertTrue(File(mapDir(dir), CoverageDecoder.CAPTURE_COMMIT_FILE).isFile, "the recording run captured")
        assertTrue(warnings(output).isEmpty(), output)
    }

    @Test
    fun `a requested full run records and captures, and says the mode was ignored`(@TempDir dir: File) {
        capturedWithConfigChanged(dir)
        val before = mapDigest(dir)

        val output = runner(
            dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD", "-Pyoriwake.mode=optimistic", "-Pyoriwake.fullRun",
        ).build().output

        assertEquals(names("B0UnrelatedTest", "B1ContextFirstTest", "B2ContextLaterTest"), ranTests(dir))
        assertContains(output, "-Pyoriwake.mode=optimistic is ignored: selection was declined")
        assertTrue(before != mapDigest(dir), "the recording run captured AppConfig's change")
    }

    @Test
    fun `a map of unknown age runs every test in optimistic mode and captures nothing`(@TempDir dir: File) {
        capturedWithConfigChanged(dir)
        File(mapDir(dir), CoverageDecoder.CAPTURE_COMMIT_FILE).delete()
        val before = mapDigest(dir)

        val output = runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD", "-Pyoriwake.mode=optimistic")
            .build().output

        assertEquals(names("B0UnrelatedTest", "B1ContextFirstTest", "B2ContextLaterTest"), ranTests(dir))
        assertContains(output, "in optimistic mode, which never captures")
        assertEquals(before, mapDigest(dir))
        assertContains(warnings(output).single(), "optimistic mode: 0 tests")
    }

    @Test
    fun `a base git cannot diff against runs every test in optimistic mode and captures nothing`(@TempDir dir: File) {
        capturedWithConfigChanged(dir)
        val before = mapDigest(dir)
        val failingDiff = gitShim(File(dir.parentFile, "${dir.name}-bin"), failCommands = listOf("diff"))

        val output = runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD", "-Pyoriwake.mode=optimistic")
            .withEnvironment(withFirstOnPath(failingDiff))
            .build().output

        assertEquals(names("B0UnrelatedTest", "B1ContextFirstTest", "B2ContextLaterTest"), ranTests(dir))
        assertContains(output, "git could not report changes")
        assertContains(output, "in optimistic mode, which never captures")
        assertEquals(before, mapDigest(dir))
    }

    @Test
    fun `the explanation in optimistic mode lists what it leaves out with its rule`(@TempDir dir: File) {
        capturedWithConfigChanged(dir)

        val output = runner(dir, "yoriwakeExplainTest", "-Pyoriwake.base=HEAD", "-Pyoriwake.mode=optimistic").build().output

        assertContains(output, "in optimistic mode leaves out 1 tests the safe rule runs")
        assertContains(output, "B2ContextLaterTest]/[method:greets()] (shares-jvm-changed-class)")
        val explanation = File(mapDir(dir), "explain.json").readText()
        assertContains(explanation, "\"mode\": \"optimistic\"")
        assertContains(explanation, "\"rules\": \"shares-jvm-changed-class\"")
    }

    @Test
    fun `two forks print one warning, counting both`(@TempDir dir: File) {
        capturedWithConfigChanged(dir, orderedBuild.replace("useJUnitPlatform()", "useJUnitPlatform()\n    maxParallelForks = 2"))

        val output = runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD", "-Pyoriwake.mode=optimistic")
            .build().output

        val parts = mapDir(dir).listFiles { file -> file.name.endsWith(AgentContract.DECISIONS_PART_SUFFIX) }.orEmpty()
        val loosened = parts.flatMap { part ->
            part.readLines().filter { it.endsWith("\tLOOSENED") }.map { it.substringBefore('\t') }
        }.toSet()
        assertEquals(1, warnings(output).size, output)
        assertContains(warnings(output).single(), "optimistic mode: ${loosened.size} tests")
    }

    @Test
    fun `under the configuration cache a safe run after an optimistic one selects the loosened test again`(
        @TempDir dir: File,
    ) {
        capturedWithConfigChanged(dir)
        runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD").build()
        File(dir, "build/test-results").deleteRecursively()
        runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD", "-Pyoriwake.mode=optimistic").build()
        assertEquals(names("B1ContextFirstTest"), ranTests(dir))
        File(dir, "build/test-results").deleteRecursively()

        val output = runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD").build().output

        assertEquals(names("B1ContextFirstTest", "B2ContextLaterTest"), ranTests(dir), output)
    }
}
