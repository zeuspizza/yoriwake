package io.github.zeuspizza.yoriwake.gradle.breaking

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Attempts on edits to test classes. An edited test class selects its own known tests whatever its
 * coverage says; a test the map has never seen runs; and a test class nothing else names is exempt
 * from dating every later test in its JVM, so only what reaches it from outside its own tests,
 * after discovery, dates it.
 */
class TestClassEditsAttempts : BreakAttempt() {

    private fun source(path: String, body: String) = path to body.trimIndent()

    private val other = source(
        "src/main/java/dev/sample/Other.java",
        "package dev.sample;\npublic class Other { public int one() { return 1; } }",
    )

    private val first = source(
        "src/test/java/dev/sample/A0UnrelatedTest.java",
        """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class A0UnrelatedTest { @Test void runsFirst() { assertEquals(1, new Other().one()); } }
        """,
    )

    /** Each test class in its own JVM, so nothing but the rule under attack can select the target. */
    private val forkPerClass = minimalBuild.replace(
        "tasks.test { useJUnitPlatform() }",
        "tasks.test { useJUnitPlatform(); forkEvery = 1 }",
    )

    // Track: code reading. Boundary: the own-class rule matches a test's class against the changed
    // prefixes, its own name or a nested class's beneath it, not only the class that declares the
    // file's tests. The target is a static nested class Jupiter discovers on its own, so its tests'
    // ids name `Outer$Inner`, and only its body is edited; the outer class declares no test.
    @Test
    fun `an edited static nested test class selects its own test`(@TempDir dir: File) {
        val outer = source(
            "src/test/java/dev/sample/A1Outer.java",
            """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A1Outer {
                static class Checks { @Test void addsOne() { assertEquals(2, new Other().one() + 1); } }
            }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A1Outer\$Checks", Aim.Row("own-class-changed"),
            listOf(other, first, outer, classOrderByName), buildScript = forkPerClass,
        ) { dir ->
            val file = File(dir, outer.first)
            file.writeText(file.readText().replace("assertEquals(2,", "assertEquals(3,"))
        }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: a test is known to the map by its exact id, or as a template
    // through recorded children; any other id is not in the map and runs. The target's class is not
    // edited: the test method it inherits gains a parameter, so its id changes under it.
    @Test
    fun `an inherited test whose id changes with its base class's signature runs as not in the map`(@TempDir dir: File) {
        val base = source(
            "src/test/java/dev/sample/BaseChecks.java",
            """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            abstract class BaseChecks { @Test void addsOne() { assertEquals(2, new Other().one() + 1); } }
            """,
        )
        val target = source(
            "src/test/java/dev/sample/A1TargetTest.java",
            "package dev.sample;\nclass A1TargetTest extends BaseChecks {}",
        )
        val result = attempt(
            dir, "dev.sample.A1TargetTest", Aim.Row("not-in-map"),
            listOf(other, first, base, target, classOrderByName), buildScript = forkPerClass,
        ) { dir ->
            File(dir, base.first).writeText(
                """
                package dev.sample;
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.TestInfo;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                abstract class BaseChecks { @Test void addsOne(TestInfo info) { assertEquals(3, new Other().one() + 1); } }
                """.trimIndent(),
            )
        }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: an exempt test class is dated only by lookups, reads,
    // definitions and executions after discovery, because during discovery only the engine is
    // assumed to touch test classes. A class orderer is user code the engine runs during discovery,
    // handed every test class without naming one: it reflects on the edited class there, and a
    // later test in the same JVM depends on what it saw. Such code dates the edited class from the
    // start of its JVM, so an unrelated test is given a JVM of its own, where discovery never
    // loads the edited class: a second source set's classes are scanned after the test set's, and
    // three classes to a JVM end the first JVM before them.
    @Test
    fun `a later test that depends on what a class orderer read from an edited test class during discovery runs`(
        @TempDir dir: File,
    ) {
        val orderer = source(
            "src/test/java/dev/sample/NameOrderer.java",
            """
            package dev.sample;
            import java.lang.reflect.Method;
            import java.util.Comparator;
            import org.junit.jupiter.api.ClassDescriptor;
            import org.junit.jupiter.api.ClassOrderer;
            import org.junit.jupiter.api.ClassOrdererContext;
            public class NameOrderer implements ClassOrderer {
                /** How many methods the test classes declare, as discovery found them. */
                public static int declared;
                @Override public void orderClasses(ClassOrdererContext context) {
                    context.getClassDescriptors().sort(Comparator.comparing(d -> d.getTestClass().getName()));
                    int count = 0;
                    for (ClassDescriptor descriptor : context.getClassDescriptors()) {
                        for (Method method : descriptor.getTestClass().getDeclaredMethods()) {
                            if (!method.isSynthetic()) count++;
                        }
                    }
                    declared = count;
                }
            }
            """,
        )
        val order = "src/test/resources/junit-platform.properties" to
            "junit.jupiter.testclass.order.default=dev.sample.NameOrderer\n"
        val edited = source(
            "src/test/java/dev/sample/A1EditedTest.java",
            """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A1EditedTest { @Test void addsOne() { assertEquals(2, new Other().one() + 1); } }
            """,
        )
        val target = source(
            "src/test/java/dev/sample/A2TargetTest.java",
            """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A2TargetTest { @Test void twoMethods() { assertEquals(2, NameOrderer.declared); } }
            """,
        )
        val apart = source(
            "src/apart/java/dev/sample/A3ApartTest.java",
            """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A3ApartTest { @Test void runsApart() { assertEquals(1, new Other().one()); } }
            """,
        )
        val apartBuild = minimalBuild.replace(
            "tasks.test { useJUnitPlatform() }",
            """
            val apart = sourceSets.create("apart") {
                compileClasspath += sourceSets.main.get().output
                runtimeClasspath += sourceSets.main.get().output
            }
            configurations["apartImplementation"].extendsFrom(configurations.testImplementation.get())
            configurations["apartRuntimeOnly"].extendsFrom(configurations.testRuntimeOnly.get())
            tasks.test {
                useJUnitPlatform()
                testClassesDirs += apart.output.classesDirs
                classpath += apart.output
                forkEvery = 3
            }
            """.trimIndent(),
        )
        val result = attempt(
            dir, "dev.sample.A2TargetTest", Aim.Row("shares-jvm-changed-class"),
            listOf(other, orderer, order, edited, target, apart),
            buildScript = apartBuild,
        ) { dir ->
            val file = File(dir, edited.first)
            file.writeText(file.readText().replace("} }", "}\n    private int unused() { return 0; } }"))
        }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }
}
