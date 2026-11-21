package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.gradle.capture.CoverageDecoder
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * An annotation change selects like any other change to its class: a test that only reads a
 * class's annotations, as a framework's scan does, never executes it, but it loads the class or
 * opens its class file, and the map holds both. A class the map never saw can be found by listing
 * a directory, which the map holds nowhere, so a new class that carries an annotation runs
 * everything.
 */
class AnnotationFunctionalTest : FunctionalTestSupport() {

    private val profile = "src/main/java/dev/sample/Profile.java" to """
        package dev.sample;
        import java.lang.annotation.*;
        @Retention(RetentionPolicy.RUNTIME)
        public @interface Profile { String value(); }
    """.trimIndent()

    private val marker = "src/main/java/dev/sample/Marker.java" to """
        package dev.sample;
        import java.lang.annotation.*;
        @Retention(RetentionPolicy.RUNTIME)
        public @interface Marker { }
    """.trimIndent()

    private val service = "src/main/java/dev/sample/Service.java" to """
        package dev.sample;
        @Profile("a")
        public class Service { public int twice(int n) { return n * 2; } }
    """.trimIndent()

    private val serviceTest = "src/test/java/dev/sample/ServiceTest.java" to """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class ServiceTest {
            @Test void doubles() { assertEquals(2, new Service().twice(1)); }
        }
    """.trimIndent()

    /** Stands in for a framework scan: reads Service's annotations and never runs a line of it. */
    private val scanTest = "src/test/java/dev/sample/ScanTest.java" to """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertTrue;
        class ScanTest {
            @Test void reads() throws Exception {
                assertTrue(Service.class.getAnnotations().length
                    + Service.class.getMethod("twice", int.class).getAnnotations().length >= 0);
            }
        }
    """.trimIndent()

    private val other = "src/main/java/dev/sample/Other.java" to """
        package dev.sample;
        public class Other { public int one() { return 1; } }
    """.trimIndent()

    /** Never names Service, in a JVM of its own: the test a narrowed run leaves out. */
    private val otherTest = "src/test/java/dev/sample/OtherTest.java" to """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class OtherTest {
            @Test void one() { assertEquals(1, new Other().one()); }
        }
    """.trimIndent()

    /** Each test class in its own JVM, so a class one test loads is not loaded for another. */
    private val forkingBuild = minimalBuild.replace(
        "tasks.test { useJUnitPlatform() }",
        "tasks.test { useJUnitPlatform(); forkEvery = 1 }",
    )

    private val both = setOf("dev.sample.ServiceTest", "dev.sample.ScanTest")
    private val all = both + "dev.sample.OtherTest"

    /** Captures with [extra] sources, applies [edit] to [edited], and runs a selecting build. */
    private fun selectAfter(
        dir: File,
        beforeSelecting: (File) -> Unit = {},
        afterEdit: () -> Unit = {},
        edited: String = service.first,
        extra: List<Pair<String, String>> = emptyList(),
        captured: Set<String> = all,
        run: GradleRunner.() -> BuildResult = GradleRunner::build,
        edit: (String) -> String?,
    ): String {
        build(
            dir, "build.gradle.kts" to forkingBuild, profile, marker, service, serviceTest, scanTest,
            other, otherTest, *extra.toTypedArray(),
        )
        committed(dir)
        runner(dir, "test").build()
        assertEquals(captured, ranTests(dir))
        beforeSelecting(mapDir(dir))

        // A null edit deletes the file.
        val source = File(dir, edited)
        val text = edit(source.readText())
        if (text == null) source.delete() else source.writeText(text)
        afterEdit()
        File(dir, "build/test-results").deleteRecursively()
        // Called as a function: `runner(...).run()` would be GradleRunner's own run(), which
        // expects no outcome.
        return run(runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD")).output
    }

    private fun mapDir(dir: File) = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)

    private fun assertNarrowedTo(dir: File, expected: Set<String>, output: String) {
        assertEquals("narrowed", decisionNotes(dir)["outcome"], output)
        assertEquals(null, decisionNotes(dir)["refusal-kind"], output)
        assertEquals(expected, ranTests(dir), output)
    }

    @Test
    fun `changing an annotation value runs the tests that loaded the class and leaves the rest out`(
        @TempDir dir: File,
    ) {
        var explained = ""
        val output = selectAfter(
            dir,
            // Compiled first: the explanation reads whatever bytes the build directory holds.
            afterEdit = {
                runner(dir, "classes", "yoriwakeExplainTest", "-Pyoriwake.base=HEAD").build()
                explained = File(mapDir(dir), "explain.json").readText()
            },
        ) { it.replace("@Profile(\"a\")", "@Profile(\"b\")") }

        assertFalse("annotations-changed" in explained, explained)
        // ScanTest only reads Service's annotations, which loads it in ScanTest's JVM.
        assertNarrowedTo(dir, both, output)
    }

    @Test
    fun `adding a class-level annotation runs the tests that loaded the class`(@TempDir dir: File) {
        val output = selectAfter(dir) { it.replace("@Profile(\"a\")", "@Profile(\"a\") @Marker") }

        assertNarrowedTo(dir, both, output)
    }

    @Test
    fun `adding a method-level annotation runs the tests that loaded the class`(@TempDir dir: File) {
        val output = selectAfter(dir) { it.replace("public int twice", "@Marker public int twice") }

        assertNarrowedTo(dir, both, output)
    }

    @Test
    fun `a test that reads the class file without loading the class runs when its annotation changes`(
        @TempDir dir: File,
    ) {
        // Stands in for a scan that parses class files, as Spring's and ClassGraph's do.
        val bytesTest = "src/test/java/dev/sample/BytesTest.java" to """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertTrue;
            class BytesTest {
                @Test void reads() throws Exception {
                    try (java.io.InputStream in =
                            getClass().getClassLoader().getResourceAsStream("dev/sample/Service.class")) {
                        assertTrue(in.readAllBytes().length > 0);
                    }
                }
            }
        """.trimIndent()
        val output = selectAfter(dir, extra = listOf(bytesTest), captured = all + "dev.sample.BytesTest") {
            it.replace("@Profile(\"a\")", "@Profile(\"b\")")
        }

        assertNarrowedTo(dir, both + "dev.sample.BytesTest", output)
    }

    @Test
    fun `deleting an annotated class runs the test that read its annotations`(@TempDir dir: File) {
        // Both tests find Extra by name, so the build still compiles once it is gone. ExecTest
        // gives the map coverage of it; LookupTest only reads its annotations.
        val extra = "src/main/java/dev/sample/Extra.java" to """
            package dev.sample;
            @Profile("a")
            public class Extra { public int one() { return 1; } }
        """.trimIndent()
        val execTest = "src/test/java/dev/sample/ExecTest.java" to """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class ExecTest {
                @Test void runs() throws Exception {
                    Class<?> extra = Class.forName("dev.sample.Extra");
                    assertEquals(1, extra.getMethod("one").invoke(extra.getDeclaredConstructor().newInstance()));
                }
            }
        """.trimIndent()
        val lookupTest = "src/test/java/dev/sample/LookupTest.java" to """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertTrue;
            class LookupTest {
                @Test void reads() throws Exception {
                    assertTrue(Class.forName("dev.sample.Extra").getAnnotations().length >= 0);
                }
            }
        """.trimIndent()
        val readers = setOf("dev.sample.ExecTest", "dev.sample.LookupTest")
        val output = selectAfter(
            dir, edited = extra.first, extra = listOf(extra, execTest, lookupTest), captured = all + readers,
            run = GradleRunner::buildAndFail,
        ) { null }

        assertNarrowedTo(dir, readers, output)
    }

    @Test
    fun `a new annotated class runs every test`(@TempDir dir: File) {
        // A scan finds a new class by listing its package's directory, and the map holds no record
        // of a listing: nothing says which test would have picked the class up.
        val output = selectAfter(dir) {
            it.replace("public class Service {", "public class Service { @Profile(\"a\") public static class Added { }")
        }

        assertEquals(all, ranTests(dir), output)
        assertEquals("annotations-unrecorded", decisionNotes(dir)["refusal-kind"], output)
    }

    @Test
    fun `a body-only change with identical annotations still narrows`(@TempDir dir: File) {
        val output = selectAfter(dir) { it.replace("n * 2", "n + n") }

        // ScanTest runs too: it reads Service reflectively, which loads Service in its JVM.
        assertNarrowedTo(dir, both, output)
    }

    @Test
    fun `a new test method in a test class this task runs still narrows`(@TempDir dir: File) {
        // The class's own tests are selected anyway, so its annotations cannot hide one of them.
        val output = selectAfter(dir, edited = serviceTest.first) {
            it.replace(
                "@Test void doubles()",
                "@Test void quadruples() { assertEquals(4, new Service().twice(2)); }\n" +
                    "    @Test void doubles()",
            )
        }

        assertContains(output, "not-in-map=1")
        assertEquals("narrowed", decisionNotes(dir)["outcome"], output)
        assertEquals(null, decisionNotes(dir)["refusal-kind"], output)
    }

    @Test
    fun `an annotation change on a test helper nothing executes runs only the tests after its JVM loaded it`(
        @TempDir dir: File,
    ) {
        // In the test output, but no test of its own and no test that executes it: the map has no
        // coverage of it. Each class of the test output goes to a JVM of its own, so the helper is
        // loaded only in one that runs no test; that load dates it, and nothing runs.
        val helper = "src/test/java/dev/sample/Fixtures.java" to """
            package dev.sample;
            @Profile("a")
            class Fixtures { static int one() { return 1; } }
        """.trimIndent()
        val output = selectAfter(dir, edited = helper.first, extra = listOf(helper)) {
            it.replace("@Profile(\"a\")", "@Profile(\"b\")")
        }

        assertEquals(emptySet(), ranTests(dir), output)
        assertEquals(null, decisionNotes(dir)["refusal-kind"], output)
        assertEquals(null, decisionNotes(dir)["full-run-kind"], output)
    }

    @Test
    fun `a map without an annotation table forces a full run`(@TempDir dir: File) {
        // A map captured before the table existed: its annotations were never recorded.
        val output = selectAfter(
            dir,
            beforeSelecting = { File(it, CoverageDecoder.ANNOTATION_DIGESTS_FILE).delete() },
        ) { it.replace("n * 2", "n + n") }

        assertEquals(all, ranTests(dir), output)
        assertEquals("annotations-unrecorded", decisionNotes(dir)["refusal-kind"], output)
    }
}
