package io.github.zeuspizza.yoriwake.gradle.breaking

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Attempts on compile-time constants. javac copies a constant's value into every class that reads
 * it, so a test that baked in the old value names the declaring class nowhere and coverage records
 * no edge to it. The holder rule forces on a changed constant that can leave its class, and lets an
 * exempt one narrow to the tests that execute it.
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

    /** Each test class in its own JVM, so a window one opens reaches no other. */
    private val forkingBuild = minimalBuild.replace(
        "tasks.test { useJUnitPlatform() }",
        "tasks.test { useJUnitPlatform(); forkEvery = 1 }",
    )

    // Track: code reading. Boundary: a file of constants alone is no class prefix at all (it is an
    // unmappable path), so the holder rule only sees a constant whose file also declares code. An
    // interface constant beside a default method stands just inside the rule: the file maps, and
    // the field is public by the language, so the constant can leave it.
    @Test
    fun `a constant on an interface with a default method that a test baked in forces`(@TempDir dir: File) {
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
            dir, "dev.sample.A1BakedTest", Aim.Forced("constant-changed"),
            listOf(limits, baked, executes, third, thirdTest, classOrderByName),
        ) { File(it, limits.first).writeText(limits.second.replace("MAX = 10", "MAX = 11")) }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
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
