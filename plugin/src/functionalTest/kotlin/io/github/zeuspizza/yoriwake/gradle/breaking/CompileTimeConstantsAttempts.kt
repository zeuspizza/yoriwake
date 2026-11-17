package io.github.zeuspizza.yoriwake.gradle.breaking

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Attempts on compile-time constants. javac copies a constant's value into every class that reads
 * it, so a test that baked in the old value names the declaring class nowhere and coverage records
 * no edge to it. A changed constant that can leave its class narrows to the classes compiled with
 * the new value when the compiled classes were compared with the map's, and forces when they were
 * not or the map never recorded the constant; an exempt one narrows to the tests that execute it.
 */
class CompileTimeConstantsAttempts : BreakAttempt() {

    private fun source(path: String, body: String) = path to body.trimIndent()

    private fun testClass(name: String, body: String) = source(
        "src/test/java/dev/sample/$name.java",
        """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.*;
        class $name { $body }
        """,
    )

    private val third = source("src/main/java/dev/sample/Third.java", "package dev.sample;\npublic class Third { public int three() { return 3; } }")
    private val thirdTest = testClass("A3ThirdTest", "@Test void three() { assertEquals(3, new Third().three()); }")

    /** As [thirdTest], but first by name: it runs before any other test class loads a changed one. */
    private val firstTest = testClass("A0ThirdTest", "@Test void three() { assertEquals(3, new Third().three()); }")

    /** Each test class in its own JVM, so a window one opens reaches no other. */
    private val forkingBuild = minimalBuild.replace(
        "tasks.test { useJUnitPlatform() }",
        "tasks.test { useJUnitPlatform(); forkEvery = 1 }",
    )

    // Track: code reading. Boundary: an interface constant beside a default method: the file maps,
    // and the field is public by the language, so the constant can leave it. The test that copied
    // it was recompiled with the new value, and its own changed class file is what selects it.
    @Test
    fun `a constant on an interface with a default method that a test baked in selects that test`(@TempDir dir: File) {
        val limits = source(
            "src/main/java/dev/sample/Limits.java",
            """
            package dev.sample;
            public interface Limits {
                int MAX = 10;
                default int max() { return MAX; }
            }
            """,
        )
        val baked = testClass("A1BakedTest", "@Test void max() { assertEquals(10, Limits.MAX); }")
        val executes = testClass("A2LimitsTest", "@Test void max() { assertEquals(10, new Limits() { }.max()); }")
        val result = attempt(
            dir, "dev.sample.A1BakedTest", Aim.Row("changed-bytes"),
            listOf(limits, baked, executes, third, firstTest, classOrderByName),
        ) { File(it, limits.first).writeText(limits.second.replace("MAX = 10", "MAX = 11")) }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: a file that declares no method is judged by the class it
    // compiled to, as any changed class is. Its one static initialiser ran under a test, so the
    // holder is covered; the test that copied the constant was recompiled with the new value.
    @Test
    fun `a constant on an interface of constants alone that a test baked in selects that test`(@TempDir dir: File) {
        val limits = source(
            "src/main/java/dev/sample/Limits.java",
            """
            package dev.sample;
            public interface Limits {
                int MAX = 10;
                String LABEL = String.valueOf(System.nanoTime());
            }
            """,
        )
        val baked = testClass("A1BakedTest", "@Test void max() { assertEquals(10, Limits.MAX); }")
        val initialises = testClass("A2LimitsTest", "@Test void label() { assertNotNull(Limits.LABEL); }")
        val result = attempt(
            dir, "dev.sample.A1BakedTest", Aim.Row("changed-bytes"),
            listOf(limits, baked, initialises, third, firstTest, classOrderByName),
        ) { File(it, limits.first).writeText(limits.second.replace("MAX = 10", "MAX = 11")) }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: as above, but nothing initialises the holder, so no test covers
    // it: a changed class the map holds no coverage of runs everything.
    @Test
    fun `a constant on an interface of constants alone that no test covers forces`(@TempDir dir: File) {
        val limits = source(
            "src/main/java/dev/sample/Limits.java",
            """
            package dev.sample;
            public interface Limits {
                int MAX = 10;
            }
            """,
        )
        val baked = testClass("A1BakedTest", "@Test void max() { assertEquals(10, Limits.MAX); }")
        val result = attempt(
            dir, "dev.sample.A1BakedTest", Aim.Forced("no-coverage-for-changed"),
            listOf(limits, baked, third, thirdTest, classOrderByName),
        ) { File(it, limits.first).writeText(limits.second.replace("MAX = 10", "MAX = 11")) }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: the classes compared with the map's are every class the build
    // itself compiles onto the test classpath, another module's among them. The constant lives in
    // a sibling module; the class that copied it is this module's, its source untouched, and the
    // target executes only that class.
    @Test
    fun `a constant in another module that a production class copied selects the test of that class`(@TempDir dir: File) {
        val lib = source("lib/build.gradle.kts", "plugins { `java-library` }")
        val limits = source(
            "lib/src/main/java/dev/lib/Limits.java",
            """
            package dev.lib;
            public class Limits {
                public static final int MAX = 10;
                public int max() { return MAX; }
            }
            """,
        )
        val gate = source(
            "src/main/java/dev/sample/Gate.java",
            """
            package dev.sample;
            public class Gate {
                public String classify(int n) { return n == dev.lib.Limits.MAX ? "max" : "other"; }
            }
            """,
        )
        val target = testClass("A1GateTest", "@Test void atTheLimit() { assertEquals(\"max\", new Gate().classify(10)); }")
        val executes = testClass("A2LimitsTest", "@Test void max() { assertTrue(new dev.lib.Limits().max() > 0); }")
        val result = attempt(
            dir, "dev.sample.A1GateTest", Aim.Row("changed-bytes"),
            listOf(
                "settings.gradle.kts" to "rootProject.name = \"sample\"\ninclude(\"lib\")\n",
                lib, limits, gate, target, executes, third, firstTest, classOrderByName,
            ),
            buildScript = minimalBuild.replace(
                "testRuntimeOnly(\"org.junit.platform:junit-platform-launcher\")",
                "testRuntimeOnly(\"org.junit.platform:junit-platform-launcher\")\n    implementation(project(\":lib\"))",
            ),
        ) { File(it, limits.first).writeText(limits.second.replace("MAX = 10", "MAX = 11")) }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: only an exact match against a recorded constant digest licenses
    // a skip. The changed file gains a nested class that holds a constant, so the holder is a class
    // the capture never saw, not a recorded one whose value moved.
    @Test
    fun `a constant moved into a nested class the capture never saw forces`(@TempDir dir: File) {
        val limits = source(
            "src/main/java/dev/sample/Limits.java",
            """
            package dev.sample;
            public class Limits {
                public int max() { return 10; }
            }
            """,
        )
        val target = testClass("A1LimitsTest", "@Test void max() { assertEquals(10, new Limits().max()); }")
        val result = attempt(
            dir, "dev.sample.A1LimitsTest", Aim.Forced("constant-unrecorded"),
            listOf(limits, target, third, thirdTest, classOrderByName),
        ) {
            File(it, limits.first).writeText(
                """
                package dev.sample;
                public class Limits {
                    public static class Values { public static final int MAX = 11; }
                    public int max() { return Values.MAX; }
                }
                """.trimIndent(),
            )
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: a private constant that is never written into a field is
    // exempt from the holder rule, which is blind to a value folded into arithmetic and returned.
    // Standing on that exemption, the change must still select the test that executes the method.
    @Test
    fun `a private constant folded into arithmetic narrows to the test that executes it`(@TempDir dir: File) {
        val pricing = source(
            "src/main/java/dev/sample/Pricing.java",
            """
            package dev.sample;
            public class Pricing {
                private static final int BASE = 10;
                public int price(int n) { return n * BASE + 1; }
            }
            """,
        )
        val target = testClass("A1PricingTest", "@Test void price() { assertEquals(21, new Pricing().price(2)); }")
        val result = attempt(
            dir, "dev.sample.A1PricingTest", Aim.Row("reaches-change"),
            listOf(pricing, target, third, thirdTest),
            buildScript = forkingBuild,
        ) { File(it, pricing.first).writeText(pricing.second.replace("BASE = 10", "BASE = 11")) }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }
}
