package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A test class JaCoCo could not instrument records nothing of itself, so its tests' coverage
 * cannot be what selects them when it is edited. Its known tests still run.
 */
class UninstrumentedTestClassFunctionalTest : FunctionalTestSupport() {

    // The test JVM's stderr reaches the output, so JaCoCo's instrumentation error is visible.
    private val loggedBuild = minimalBuild.replace(
        "tasks.test { useJUnitPlatform() }",
        "tasks.test { useJUnitPlatform(); testLogging { showStandardStreams = true } }",
    )

    private val calc = "src/main/java/dev/sample/Calc.java" to """
        package dev.sample;
        public class Calc { public int add(int a, int b) { return a + b; } }
    """.trimIndent()

    private val other = "src/main/java/dev/sample/Other.java" to """
        package dev.sample;
        public class Other { public int twice(int n) { return n * 2; } }
    """.trimIndent()

    // Runs before ZTest by name, and executes nothing ZTest does.
    private val aTest = "src/test/java/dev/sample/ATest.java" to """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class ATest {
            @Test void doubles() { assertEquals(4, new Other().twice(2)); }
        }
    """.trimIndent()

    /**
     * About 55 KB of bytecode, which javac accepts and JaCoCo's probes push past the JVM's 64 KiB
     * method limit, so JaCoCo loads the class uninstrumented. Never called.
     */
    private val tooLargeToInstrument = buildString {
        appendLine("    static int big(int x) {")
        appendLine("        int y = 0;")
        repeat(5_500) { k -> appendLine("        if (x == $k) y += ${k % 7 + 1};") }
        appendLine("        return y;")
        appendLine("    }")
    }

    private fun zTest(extra: String) = "src/test/java/dev/sample/ZTest.java" to """
        |package dev.sample;
        |import org.junit.jupiter.api.Test;
        |import static org.junit.jupiter.api.Assertions.assertEquals;
        |class ZTest {
        |    @Test void testA() { assertEquals(3, new Calc().add(1, 2)); }
        |    @Test void testB() { assertEquals(0, new Calc().add(0, 0)); }
        |$extra}
    """.trimMargin()

    private fun mapDir(dir: File) = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)

    /** The classes each of ZTest's tests recorded, keyed by method. */
    private fun zTestCoverage(dir: File): Map<String, Set<String>> =
        File(mapDir(dir), "coverage.tsv").readLines()
            .map { it.split("\t") }
            .filter { it.size == 4 && it[3].contains("[class:dev.sample.ZTest]/[method:") }
            .associate { Regex("""\[method:([^(]+)""").find(it[3])!!.groupValues[1] to it[2].split(",").toSet() }

    /** Each ZTest method's decision row (`verdict reason`) and its rule line. */
    private fun zTestDecisions(dir: File): Map<String, Pair<String, String>> {
        val lines = File(mapDir(dir), AgentContract.DECISIONS_FILE).readLines()
            .filter { it.contains("[class:dev.sample.ZTest]/[method:") }
        fun method(id: String) = Regex("""\[method:([^(]+)""").find(id)!!.groupValues[1]
        val rules = lines.filter { it.startsWith(AgentContract.RULES_LINE_PREFIX) }
            .associate { it.removePrefix(AgentContract.RULES_LINE_PREFIX).split("\t").let { (id, r) -> method(id) to r } }
        return lines.filter { !it.startsWith("#") }
            .associate { it.split("\t").let { (id, verdict, reason) -> method(id) to ("$verdict $reason" to rules.getValue(method(id))) } }
    }

    /** `class#method` of every test the last build ran, to whether it failed. */
    private fun ranMethods(dir: File): Map<String, Boolean> =
        File(dir, "build/test-results/test").listFiles { f: File -> f.name.endsWith(".xml") }.orEmpty()
            .flatMap { file ->
                Regex("""<testcase name="([^"(]+)\(\)" classname="([^"]+)"[^>]*?(/>|>([\s\S]*?)</testcase>)""")
                    .findAll(file.readText())
                    .map { "${it.groupValues[2]}#${it.groupValues[1]}" to it.groupValues[4].contains("<failure") }
            }
            .toMap()

    /**
     * Captures, checks [precondition] against the map and the capture's output, edits `testA` so
     * it fails, and runs a selecting build; returns what it ran.
     */
    private fun captureEditSelect(dir: File, extra: String, precondition: (String) -> Unit): Map<String, Boolean> {
        build(dir, "build.gradle.kts" to loggedBuild, calc, other, aTest, zTest(extra), classOrderByName)
        committed(dir)
        val capture = runner(dir, "test").build().output
        assertEquals(
            setOf("dev.sample.ATest#doubles", "dev.sample.ZTest#testA", "dev.sample.ZTest#testB"),
            ranMethods(dir).keys,
        )
        precondition(capture)
        val file = File(dir, "src/test/java/dev/sample/ZTest.java")
        file.writeText(file.readText().replace("assertEquals(3, new Calc()", "assertEquals(4, new Calc()"))
        File(dir, "build/test-results").deleteRecursively()
        // Not buildAndFail: a selection that skips testA passes, and must fail below, not here.
        runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD").run()
        assertEquals("narrowed", decisionNotes(dir)["outcome"])
        return ranMethods(dir)
    }

    @Test
    fun `an edited test class JaCoCo could not instrument runs its known tests`(@TempDir dir: File) {
        val ran = captureEditSelect(dir, tooLargeToInstrument) { capture ->
            // ZTest is uninstrumented, so its tests recorded Calc and nothing of themselves.
            assertContains(capture, "Error while instrumenting dev/sample/ZTest")
            assertEquals(
                mapOf("testA" to setOf("dev.sample.Calc"), "testB" to setOf("dev.sample.Calc")),
                zTestCoverage(dir),
            )
        }

        assertEquals(mapOf("dev.sample.ZTest#testA" to true, "dev.sample.ZTest#testB" to false), ran)

        val byRule = "included REACHES_CHANGE" to "own-class-changed"
        assertEquals(mapOf("testA" to byRule, "testB" to byRule), zTestDecisions(dir))
    }

    @Test
    fun `an edited test class JaCoCo instrumented runs its known tests through their coverage`(@TempDir dir: File) {
        val ran = captureEditSelect(dir, "") {
            assertTrue(zTestCoverage(dir).values.all { "dev.sample.ZTest" in it }, "ZTest was not instrumented")
        }

        // ATest too: its class setup's record shares its JVM's window with ZTest's and holds ZTest.
        assertEquals(
            mapOf("dev.sample.ATest#doubles" to false, "dev.sample.ZTest#testA" to true, "dev.sample.ZTest#testB" to false),
            ran,
        )
        // Their own class's setup record holds ZTest too, so it is a third rule here.
        val byCoverage = "included REACHES_CHANGE" to "reaches-change,own-class-changed,class-setup"
        assertEquals(mapOf("testA" to byCoverage, "testB" to byCoverage), zTestDecisions(dir))
    }
}
