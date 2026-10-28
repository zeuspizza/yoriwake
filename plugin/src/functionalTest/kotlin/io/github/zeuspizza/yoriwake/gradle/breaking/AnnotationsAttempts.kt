package io.github.zeuspizza.yoriwake.gradle.breaking

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Attempts on annotations. A framework's scan, condition or qualifier reads a class's annotations
 * without executing it, so coverage holds no edge: a changed annotation forces, a body-only change
 * narrows, and a test class this task runs that declares a test is exempt because its own tests run
 * anyway.
 */
class AnnotationsAttempts : BreakAttempt() {

    private fun source(path: String, body: String) = path to body.trimIndent()

    private fun testClass(name: String, body: String, annotations: String = "") = source(
        "src/test/java/dev/sample/$name.java",
        """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.*;
        $annotations
        class $name { $body }
        """,
    )

    private val level = source(
        "src/main/java/dev/sample/Level.java",
        """
        package dev.sample;
        import java.lang.annotation.*;
        @Retention(RetentionPolicy.RUNTIME)
        @Target({ElementType.PACKAGE, ElementType.TYPE})
        public @interface Level { int value(); }
        """,
    )

    private val third = source("src/main/java/dev/sample/Third.java", "package dev.sample;\npublic class Third { public int three() { return 3; } }")
    private val thirdTest = testClass("A3ThirdTest", "@Test void three() { assertEquals(3, new Third().three()); }")

    /** Each test class in its own JVM, so a window one opens reaches no other. */
    private val forkingBuild = minimalBuild.replace(
        "tasks.test { useJUnitPlatform() }",
        "tasks.test { useJUnitPlatform(); forkEvery = 1 }",
    )

    // Track: code reading. Boundary: the classes judged for a changed file are the ones its name
    // compiles to. A `package-info.java` declares no type, yet compiles to `package-info.class`,
    // and a framework reads a package's annotations without executing anything.
    @Test
    fun `a changed package annotation forces`(@TempDir dir: File) {
        val packageInfo = source("src/main/java/dev/sample/package-info.java", "@Level(3)\npackage dev.sample;")
        val target = testClass(
            "A1PackageTest",
            "@Test void level() { assertEquals(3, Alpha.class.getPackage().getAnnotation(Level.class).value()); }",
        )
        val result = attempt(
            dir, "dev.sample.A1PackageTest", Aim.Forced("annotations-changed"),
            listOf(level, packageInfo, oneClass, oneTest, target, third, thirdTest, classOrderByName),
        ) { File(it, packageInfo.first).writeText("@Level(4)\npackage dev.sample;") }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: a class the annotation table lacks is new, and forces only if
    // it carries an annotation. The new class here carries one only as a type use on a field, the
    // kind a nullness checker or a binding library reads.
    @Test
    fun `a new nested class whose only annotation is a type use forces`(@TempDir dir: File) {
        val nullable = source(
            "src/main/java/dev/sample/Nullable.java",
            """
            package dev.sample;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.TYPE_USE)
            public @interface Nullable { }
            """,
        )
        val service = source("src/main/java/dev/sample/Service.java", "package dev.sample;\npublic class Service { public int twice(int n) { return n * 2; } }")
        val target = testClass("A1ShapeTest", "@Test void noNestedTypes() { assertEquals(0, Service.class.getDeclaredClasses().length); }")
        val result = attempt(
            dir, "dev.sample.A1ShapeTest", Aim.Forced("annotations-unrecorded"),
            listOf(nullable, service, target, third, thirdTest, classOrderByName),
        ) {
            File(it, service.first).writeText(
                "package dev.sample;\npublic class Service { public int twice(int n) { return n * 2; } " +
                    "static class Extra { @Nullable String name; } }",
            )
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: a changed class only in the test output is spared the force
    // for a class the map never recorded, because discovery runs it; that holds only for a class
    // the absence rule could vouch for. An annotated helper that declares no test, which nothing
    // executes, is not one, and its body-only change keeps its annotations identical.
    @Test
    fun `a body change to an annotated test helper nothing executes forces`(@TempDir dir: File) {
        val helper = source(
            "src/test/java/dev/sample/TestConfig.java",
            """
            package dev.sample;
            @Level(1)
            class TestConfig { String greeting() { return "hello"; } }
            """,
        )
        val target = testClass(
            "A1ConfigTest",
            """
            @Test void greetsHello() throws Exception {
                try (java.io.InputStream in = getClass().getClassLoader().getResourceAsStream("dev/sample/TestConfig.class")) {
                    assertTrue(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1).contains("hello"));
                }
            }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A1ConfigTest", Aim.Forced("no-coverage-for-changed"),
            listOf(level, helper, target, third, thirdTest, classOrderByName),
        ) { File(it, helper.first).writeText(helper.second.replace("\"hello\"", "\"howdy\"")) }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: annotations inside method bodies are left out of the annotation
    // digest, since no reflective read reaches them. A change that adds a type annotation on a cast
    // and edits the body must still narrow to the test that executes the body.
    @Test
    fun `a type annotation added inside a method body narrows to the test that executes it`(@TempDir dir: File) {
        val tag = source(
            "src/main/java/dev/sample/Tag.java",
            """
            package dev.sample;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.TYPE_USE)
            public @interface Tag { }
            """,
        )
        val service = source(
            "src/main/java/dev/sample/Service.java",
            "package dev.sample;\n@Level(1)\npublic class Service { public int twice(int n) { Object o = n; return ((Integer) o) * 2; } }",
        )
        val target = testClass("A1ServiceTest", "@Test void doubles() { assertEquals(2, new Service().twice(1)); }")
        val result = attempt(
            dir, "dev.sample.A1ServiceTest", Aim.Row("reaches-change"),
            listOf(level, tag, service, target, third, thirdTest),
            buildScript = forkingBuild,
        ) {
            File(it, service.first).writeText(service.second.replace("((Integer) o) * 2", "((@Tag Integer) o) * 2 + 1"))
        }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: a test class this task runs that declares a test is exempt
    // from the annotation refusal, on the ground that its own tests run anyway; when nothing else
    // names it, the shared-JVM rule dates it by a lookup, a copy, a file read or an execution from
    // another test class, and by its own first test. Another test that reads its annotations through
    // a Class object the class left in shared state does none of the others, and depends on the
    // annotation alone. Discovery loads the edited class before a JVM's first test, so an unrelated
    // test is given a JVM of its own, where it is left out: a second source set's classes are scanned
    // after the test set's, and three classes to a JVM end the first JVM before them.
    @Test
    fun `a test that reads another test class's annotation through shared state is held by the change`(
        @TempDir dir: File,
    ) {
        val seen = source(
            "src/test/java/dev/sample/Seen.java",
            "package dev.sample;\nfinal class Seen { static final java.util.List<Class<?>> CLASSES = new java.util.ArrayList<>(); }",
        )
        val levelled = testClass(
            "A1LevelledTest",
            "@Test void registers() { Seen.CLASSES.add(getClass()); assertEquals(2, new Alpha().twice(1)); }",
            annotations = "@Level(3)",
        )
        val auditor = testClass(
            "A2AuditTest",
            """
            @Test void everyRegisteredClassIsLevelThree() {
                for (Class<?> c : Seen.CLASSES) assertEquals(3, c.getAnnotation(Level.class).value());
            }
            """,
        )
        val apartTest = thirdTest.first.replace("src/test/", "src/apart/") to thirdTest.second
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
            dir, "dev.sample.A2AuditTest", Aim.Row("shares-jvm-changed-class"),
            listOf(level, oneClass, seen, levelled, auditor, third, apartTest, classOrderByName),
            buildScript = apartBuild,
        ) { File(it, levelled.first).writeText(levelled.second.replace("@Level(3)", "@Level(4)")) }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }
}
