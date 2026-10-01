package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.gradle.capture.CoverageDecoder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * An annotation change forces a full run: a test that only reads a class's annotations, as a
 * framework's scan does, never executes it and holds no coverage edge to it.
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

    private val both = setOf("dev.sample.ServiceTest", "dev.sample.ScanTest")

    /** Captures with [extra] sources, applies [edit] to [edited], and runs a selecting build. */
    private fun selectAfter(
        dir: File,
        beforeSelecting: (File) -> Unit = {},
        afterEdit: () -> Unit = {},
        edited: String = service.first,
        extra: List<Pair<String, String>> = emptyList(),
        edit: (String) -> String,
    ): String {
        build(
            dir, "build.gradle.kts" to minimalBuild, profile, marker, service, serviceTest, scanTest,
            *extra.toTypedArray(),
        )
        committed(dir)
        runner(dir, "test").build()
        assertEquals(both, ranTests(dir))
        beforeSelecting(mapDir(dir))

        val source = File(dir, edited)
        source.writeText(edit(source.readText()))
        afterEdit()
        File(dir, "build/test-results").deleteRecursively()
        return runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD").build().output
    }

    private fun mapDir(dir: File) = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)

    @Test
    fun `changing an annotation value forces a full run`(@TempDir dir: File) {
        var explained = ""
        val output = selectAfter(
            dir,
            // Before the selecting run, whose full run recaptures the map. Compiled first: the
            // explanation reads whatever bytes the build directory holds.
            afterEdit = {
                runner(dir, "classes", "yoriwakeExplainTest", "-Pyoriwake.base=HEAD").build()
                explained = File(mapDir(dir), "explain.json").readText()
            },
        ) { it.replace("@Profile(\"a\")", "@Profile(\"b\")") }

        assertContains(explained, "\"refusalKind\": \"annotations-changed\"")
        assertEquals(both, ranTests(dir), output)
        assertEquals("annotations-changed", decisionNotes(dir)["refusal-kind"], output)
    }

    @Test
    fun `adding a class-level annotation forces a full run`(@TempDir dir: File) {
        val output = selectAfter(dir) { it.replace("@Profile(\"a\")", "@Profile(\"a\") @Marker") }

        assertEquals(both, ranTests(dir), output)
    }

    @Test
    fun `adding a method-level annotation forces a full run`(@TempDir dir: File) {
        val output = selectAfter(dir) { it.replace("public int twice", "@Marker public int twice") }

        assertEquals(both, ranTests(dir), output)
    }

    @Test
    fun `a body-only change with identical annotations still narrows`(@TempDir dir: File) {
        val output = selectAfter(dir) { it.replace("n * 2", "n + n") }

        // ScanTest runs too: it reads Service reflectively, so Service is loaded in its JVM by the
        // time either test finishes, whichever runs first. The outcome is what tells this apart
        // from the full run an annotation change forces.
        assertEquals(both, ranTests(dir), output)
        assertEquals("narrowed", decisionNotes(dir)["outcome"], output)
        assertEquals(null, decisionNotes(dir)["refusal-kind"], output)
    }

    @Test
    fun `a new test method in a test class this task runs still narrows`(@TempDir dir: File) {
        // The class's own tests are selected anyway, so its annotations cannot hide one of them.
        // ScanTest runs too, through the setup record it shares with ServiceTest, so the outcome
        // is what tells this apart from a full run.
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
    fun `an annotation change on a test helper that runs no tests forces a full run`(@TempDir dir: File) {
        // In the test output, but no test of its own: nothing selects it, and a scan may read it.
        val helper = "src/test/java/dev/sample/Fixtures.java" to """
            package dev.sample;
            @Profile("a")
            class Fixtures { static int one() { return 1; } }
        """.trimIndent()
        val output = selectAfter(dir, edited = helper.first, extra = listOf(helper)) {
            it.replace("@Profile(\"a\")", "@Profile(\"b\")")
        }

        assertEquals(both, ranTests(dir), output)
        assertEquals("annotations-changed", decisionNotes(dir)["refusal-kind"], output)
    }

    @Test
    fun `a map without an annotation table forces a full run`(@TempDir dir: File) {
        // A map captured before the table existed: its annotations were never recorded.
        val output = selectAfter(
            dir,
            beforeSelecting = { File(it, CoverageDecoder.ANNOTATION_DIGESTS_FILE).delete() },
        ) { it.replace("n * 2", "n + n") }

        assertEquals(both, ranTests(dir), output)
        assertEquals("annotations-unrecorded", decisionNotes(dir)["refusal-kind"], output)
    }
}
