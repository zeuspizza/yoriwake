package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `-Pyoriwake.isolatedCaptureBudget` records the test classes the previous map ranks widest each in
 * a JVM of its own, as many as the budget allows, and the rest shared, into one map.
 *
 * One shared JVM runs, by class name: E0UnrelatedTest, which loads Other and Setup; E1AdderTest,
 * which loads Adder; E2LaterTest, whose three tests use only Other. E2LaterTest ranks first: its
 * tests ran after every class was loaded and cover one. Each class takes about half a second, so a
 * budget of 2.2x on one isolated JVM at a time fits one class and its JVM start, not two.
 */
class IsolatedCaptureBudgetFunctionalTest : FunctionalTestSupport() {

    private val orderedBuild = minimalBuild.replace(
        "tasks.test { useJUnitPlatform() }",
        """
        tasks.test {
            useJUnitPlatform()
            systemProperty("junit.jupiter.testclass.order.default", "org.junit.jupiter.api.ClassOrderer\${'$'}ClassName")
        }
        """.trimIndent(),
    )

    private fun main(name: String, body: String) = "src/main/java/dev/sample/$name.java" to "package dev.sample;\n$body"

    private fun test(name: String, body: String) = "src/test/java/dev/sample/$name.java" to """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.*;
        class $name {
            $body
        }
    """.trimIndent()

    private val budget = listOf("-Pyoriwake.isolatedCaptureBudget=2.2x", "-Pyoriwake.isolatedCaptureForks=1")

    private fun sample(dir: File) {
        build(
            dir, "build.gradle.kts" to orderedBuild,
            main("Other", "public class Other { public int one() { return 1; } }"),
            main("Setup", "public class Setup { public static final int READY = 1; }"),
            main("Adder", "public class Adder { public int add(int a, int b) { return a + b; } }"),
            test(
                "E0UnrelatedTest",
                "@Test void first() throws Exception { Thread.sleep(500); assertEquals(Setup.READY, new Other().one()); }",
            ),
            test("E1AdderTest", "@Test void adds() throws Exception { Thread.sleep(500); assertEquals(3, new Adder().add(1, 2)); }"),
            test(
                "E2LaterTest",
                (1..3).joinToString("\n") {
                    "@Test void later$it() throws Exception { Thread.sleep(170); assertEquals(1, new Other().one()); }"
                },
            ),
        )
        committed(dir)
    }

    /** A shared recording, then a budgeted one picking from its map; returns the second's output. */
    private fun recordedWithinBudget(dir: File, vararg extra: String): String {
        sample(dir)
        runner(dir, "test").build()
        File(dir, "build/test-results").deleteRecursively()
        return runner(dir, "test", *budget.toTypedArray(), *extra).build().output
    }

    private fun mapDir(dir: File) = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)

    private fun rows(dir: File, name: String) =
        File(mapDir(dir), name).readLines().filter(String::isNotBlank).map { it.split("\t") }

    /** The test classes each JVM of the map ran, by its mode. */
    private fun classesByMode(dir: File): Map<String, Set<Set<String>>> {
        val modes = rows(dir, AgentContract.JVM_MODE_FILE).associate { it[0] to it[1] }
        return rows(dir, AgentContract.POSITIONS_FILE)
            .groupBy({ it[0] }, { Regex("""\[class:dev\.sample\.([^\]]+)]""").find(it[2])!!.groupValues[1] })
            .entries.groupBy({ modes[it.key] ?: "shared" }, { it.value.toSet() })
            .mapValues { it.value.toSet() }
    }

    private fun picked(dir: File) =
        File(mapDir(dir), "isolated-pick").readLines().filter { it.startsWith("class\t") }.map { it.substringAfterLast('\t') }

    private fun selecting(dir: File): Set<String> {
        File(dir, "build/test-results").deleteRecursively()
        val output = runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD").build().output
        assertEquals("narrowed", decisionNotes(dir)["outcome"], output)
        return ranTests(dir)
    }

    private fun names(vararg simple: String) = simple.map { "dev.sample.$it" }.toSet()

    @Test
    fun `a budgeted recording runs the picked class in its own JVM`(@TempDir dir: File) {
        val output = recordedWithinBudget(dir)

        assertContains(output, "recording 1 test classes in their own JVM")
        assertTrue(File(dir, "build/test-results/testYoriwakeIsolated/TEST-dev.sample.E2LaterTest.xml").isFile, output)
        assertEquals(names("E0UnrelatedTest", "E1AdderTest"), ranTests(dir))
        val explained = runner(dir, "yoriwakeExplainTest", "-Pyoriwake.base=HEAD").build().output
        assertContains(explained, "1 test classes recorded in their own JVM, picked under budget 2.2x")
    }

    @Test
    fun `a budgeted recording labels the picked class's JVM isolated and the rest shared`(@TempDir dir: File) {
        recordedWithinBudget(dir)

        assertEquals(
            mapOf("shared" to setOf(setOf("E0UnrelatedTest", "E1AdderTest")), "isolated" to setOf(setOf("E2LaterTest"))),
            classesByMode(dir),
        )
    }

    @Test
    fun `a budgeted recording keeps the picked class's tests in the map`(@TempDir dir: File) {
        recordedWithinBudget(dir)

        val e2 = rows(dir, AgentContract.COVERAGE_FILE).filter { "[class:dev.sample.E2LaterTest]" in it[3] && "[method:" in it[3] }
        assertEquals(setOf("SUCCESSFUL"), e2.map { it[0] }.toSet())
        assertEquals(3, e2.map { it[3] }.distinct().size)
    }

    @Test
    fun `a change the shared JVM loaded before the picked class no longer selects it`(@TempDir dir: File) {
        recordedWithinBudget(dir)
        File(dir, "src/main/java/dev/sample/Adder.java").writeText(
            "package dev.sample;\npublic class Adder { public int add(int a, int b) { return b + a; } }",
        )

        assertEquals(names("E1AdderTest"), selecting(dir))
    }

    @Test
    fun `a change the picked class covers still selects it`(@TempDir dir: File) {
        recordedWithinBudget(dir)
        File(dir, "src/main/java/dev/sample/Other.java").writeText(
            "package dev.sample;\npublic class Other { public int one() { return 2 - 1; } }",
        )

        assertContains(selecting(dir), "dev.sample.E2LaterTest")
    }

    @Test
    fun `a budgeted recording with no map records shared`(@TempDir dir: File) {
        sample(dir)

        val output = runner(dir, "test", *budget.toTypedArray()).build().output

        assertContains(output, "has no map to rank test classes from yet")
        assertEquals(setOf("shared"), classesByMode(dir).keys)
        assertFalse(File(dir, "build/test-results/testYoriwakeIsolated").exists())
    }

    @Test
    fun `two budgeted recordings in a row keep the pick`(@TempDir dir: File) {
        // A budget every class but the one the test task keeps fits, so the second pick has no
        // shared JVM left to rank them in.
        sample(dir)
        runner(dir, "test").build()
        runner(dir, "test", "-Pyoriwake.isolatedCaptureBudget=100x").build()
        val first = picked(dir)

        val output = runner(dir, "test", "-Pyoriwake.isolatedCaptureBudget=100x", "--rerun").build().output

        assertContains(first, "dev.sample.E2LaterTest")
        assertEquals(first, picked(dir))
        assertContains(output, "map updated")
        // Recorded again, each in its own JVM, not left out of the map.
        val alone = classesByMode(dir).getValue("isolated")
        first.forEach { assertContains(alone, setOf(it.removePrefix("dev.sample.")), "$it was not recorded alone") }
    }
}
