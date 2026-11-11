package io.github.zeuspizza.yoriwake.gradle

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * What an edit that changes only comments, blank lines or line breaks selects, without and with
 * `narrowLineOnlyChanges`.
 *
 * Without the flag a comment edit is a changed source file, so it selects like any other change to
 * the classes compiled from it; in Kotlin the same edit can force a full run through line
 * information the compiler stores outside the line-number table. With it, such a file selects only
 * the tests whose JVM could have seen a line number or read the file from then on.
 */
class LineOnlyChangeFunctionalTest : FunctionalTestSupport() {

    private fun mapDir(dir: File) = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)

    private val flag = "-Pyoriwake.narrowLineOnlyChanges"

    /**
     * Captures [sources], applies [edit] to [edited], explains, then runs a selecting build, both
     * with [flags]. [beforeSelecting] is handed the map directory before either.
     */
    private fun selectAfter(
        dir: File,
        buildScript: String,
        sources: List<Pair<String, String>>,
        expectedCapture: Set<String>,
        edited: String,
        flags: List<String> = emptyList(),
        beforeSelecting: (File) -> Unit = {},
        edit: (String) -> String,
    ): Pair<String, String> {
        build(dir, "build.gradle.kts" to buildScript, *sources.toTypedArray())
        committed(dir)
        // The Kotlin plugin's session directory, ignored as a real project ignores it, so it never
        // reads as an untracked path that forces a full run of its own.
        File(dir, ".gitignore").appendText(System.lineSeparator() + ".kotlin/")
        commit(dir, "ignore the Kotlin session directory")
        runner(dir, "test").build()
        assertEquals(expectedCapture, ranTests(dir))

        beforeSelecting(mapDir(dir))
        val source = File(dir, edited)
        val before = source.readText()
        source.writeText(edit(before))
        assertFalse(before == source.readText(), "the edit changed nothing in $edited")
        // Compiled first: the explanation reads whatever bytes the build directory holds.
        runner(dir, "classes", "yoriwakeExplainTest", "-Pyoriwake.base=HEAD", *flags.toTypedArray()).build()
        val explained = File(mapDir(dir), "explain.json").readText()
        File(dir, "build/test-results").deleteRecursively()
        val output = runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD", *flags.toTypedArray()).build().output
        return explained to output
    }

    private val commentAboveThrice: (String) -> String =
        { it.replace("public class Beta {", "public class Beta {\n    // Triples.\n") }

    private val javaSample = listOf(oneClass, oneTest, secondClass, secondTest, classOrderByName)

    private val javaTests = setOf("dev.sample.AlphaTest", "dev.sample.BetaTest")

    @Test
    fun `a comment above a Java method selects the tests that cover its class`(@TempDir dir: File) {
        // Ordered by name, BetaTest runs last, so nothing but its own coverage selects it.
        val (_, output) = selectAfter(
            dir, minimalBuild, javaSample, javaTests, edited = secondClass.first, edit = commentAboveThrice,
        )

        assertEquals(setOf("dev.sample.BetaTest"), ranTests(dir), output)
        assertEquals("narrowed", decisionNotes(dir)["outcome"], output)
        assertEquals(null, decisionNotes(dir)["lines-only"], output)
    }

    @Test
    fun `with the flag, a comment above a Java method runs no test that only covers its class`(@TempDir dir: File) {
        val (explained, output) = selectAfter(
            dir, minimalBuild, javaSample, javaTests, edited = secondClass.first, flags = listOf(flag),
            edit = commentAboveThrice,
        )

        assertContains(explained, "\"linesOnly\": [\"dev.sample.Beta\"]")
        assertContains(explained, "\"linesOnlyPaths\": [\"src/main/java/dev/sample/Beta.java\"]")
        assertEquals(emptySet(), ranTests(dir), output)
        assertEquals("narrowed", decisionNotes(dir)["outcome"], output)
        assertEquals("1", decisionNotes(dir)["lines-only"], output)
        assertContains(output, "changed only in line information")
    }

    @Test
    fun `with the flag, a changed operator still selects as it always did`(@TempDir dir: File) {
        val (explained, output) = selectAfter(
            dir, minimalBuild, javaSample, javaTests, edited = secondClass.first, flags = listOf(flag),
        ) { it.replace("n * 3", "n + n + n") }

        assertContains(explained, "\"reason\": \"class-unmatched\"")
        assertEquals(setOf("dev.sample.BetaTest"), ranTests(dir), output)
        assertEquals("0", decisionNotes(dir)["lines-only"], output)
    }

    @Test
    fun `with the flag, a map without debug touches keeps the file changed and says why`(@TempDir dir: File) {
        val (explained, output) = selectAfter(
            dir, minimalBuild, javaSample, javaTests, edited = secondClass.first, flags = listOf(flag),
            beforeSelecting = { File(it, "debug-touch.tsv").delete() }, edit = commentAboveThrice,
        )

        assertContains(explained, "\"reason\": \"debug-touch-absent\"")
        assertEquals(setOf("dev.sample.BetaTest"), ranTests(dir), output)
    }

    @Test
    fun `with the flag, a map whose line digests another capture stamped keeps the file changed`(@TempDir dir: File) {
        val (explained, output) = selectAfter(
            dir, minimalBuild, javaSample, javaTests, edited = secondClass.first, flags = listOf(flag),
            beforeSelecting = { map ->
                val table = File(map, "line-digests")
                table.writeText(table.readText().replace(Regex("(?m)^#stamp\t.*$"), "#stamp\t" + "0".repeat(40)))
            },
            edit = commentAboveThrice,
        )

        assertContains(explained, "\"reason\": \"line-digests-absent\"")
        assertEquals(setOf("dev.sample.BetaTest"), ranTests(dir), output)
    }

    /** Beta's own frame, in a test that reads where it threw. */
    private val betaThrows = "src/main/java/dev/sample/Beta.java" to """
        package dev.sample;
        public class Beta {
            public int thrice(int n) { return n * 3; }
            public void fail() { throw new IllegalStateException("here"); }
        }
    """.trimIndent()

    private fun javaTest(name: String, body: String) = "src/test/java/dev/sample/$name.java" to """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.*;
        class $name {
            @Test void runs() throws Exception { $body }
        }
    """.trimIndent()

    @Test
    fun `with the flag, a test that reads a line number runs, with every later test of its JVM`(@TempDir dir: File) {
        val (_, output) = selectAfter(
            dir, minimalBuild,
            listOf(
                betaThrows, classOrderByName,
                javaTest("ACoversTest", "assertEquals(3, new Beta().thrice(1));"),
                javaTest(
                    "BLineTest",
                    "try { new Beta().fail(); fail(); } catch (IllegalStateException e) { " +
                        "assertTrue(e.getStackTrace()[0].getLineNumber() > 0); }",
                ),
                javaTest("CCoversTest", "assertEquals(6, new Beta().thrice(2));"),
            ),
            setOf("dev.sample.ACoversTest", "dev.sample.BLineTest", "dev.sample.CCoversTest"),
            edited = betaThrows.first, flags = listOf(flag),
        ) { it.replace("public class Beta {", "public class Beta {\n    // Moves every line.\n") }

        assertEquals(setOf("dev.sample.BLineTest", "dev.sample.CCoversTest"), ranTests(dir), output)
        assertEquals("SHARES_JVM_WITH_CHANGE", decisionReasons(dir).entries.single { "BLineTest" in it.key }.value, output)
    }

    @Test
    fun `with the flag, a test that reads the source file runs`(@TempDir dir: File) {
        val (_, output) = selectAfter(
            dir, minimalBuild,
            listOf(
                secondClass, classOrderByName,
                javaTest("ACoversTest", "assertEquals(3, new Beta().thrice(1));"),
                javaTest(
                    "BSnippetTest",
                    "assertTrue(new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(" +
                        "\"src/main/java/dev/sample/Beta.java\"))).contains(\"thrice\"));",
                ),
            ),
            setOf("dev.sample.ACoversTest", "dev.sample.BSnippetTest"),
            edited = secondClass.first, flags = listOf(flag), edit = commentAboveThrice,
        )

        assertEquals(setOf("dev.sample.BSnippetTest"), ranTests(dir), output)
    }

    @Test
    fun `the flag takes only true or false`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest)
        committed(dir)

        val output = runner(dir, "test", "-Pyoriwake.narrowLineOnlyChanges=yes").buildAndFail().output

        assertContains(output, "yoriwake.narrowLineOnlyChanges")
    }

    private val steps = "src/main/kotlin/dev/demokt/Steps.kt" to """
        package dev.demokt
        import kotlin.coroutines.*
        suspend fun step(n: Int): Int = suspendCoroutine { it.resume(n + 1) }
        suspend fun twoSteps(n: Int): Int {
            val a = step(n)
            return step(a)
        }
        fun runNow(block: suspend () -> Int): Int {
            var result = 0
            block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it.getOrThrow() })
            return result
        }
    """.trimIndent()

    private val stepsTest = "src/test/kotlin/dev/demokt/StepsTest.kt" to """
        package dev.demokt
        import org.junit.jupiter.api.Test
        import kotlin.test.assertEquals
        class StepsTest {
            @Test fun twoSteps() { assertEquals(3, runNow { twoSteps(1) }) }
        }
    """.trimIndent()

    private val alpha = "src/main/kotlin/dev/demokt/Alpha.kt" to """
        package dev.demokt
        class Alpha { fun twice(n: Int): Int = n * 2 }
    """.trimIndent()

    private val alphaTest = "src/test/kotlin/dev/demokt/AlphaTest.kt" to """
        package dev.demokt
        import org.junit.jupiter.api.Test
        import kotlin.test.assertEquals
        class AlphaTest {
            @Test fun twice() { assertEquals(2, Alpha().twice(1)) }
        }
    """.trimIndent()

    private val coroutineTests = setOf("dev.demokt.StepsTest", "dev.demokt.AlphaTest")

    @Test
    fun `a comment above a suspend function forces a full run through its line metadata`(@TempDir dir: File) {
        // The continuation's runtime-visible DebugMetadata annotation carries the suspension
        // points' line numbers, so the comment changes an annotation's digest.
        val (explained, output) = selectAfter(
            dir, kotlinBuild, listOf(steps, stepsTest, alpha, alphaTest), coroutineTests,
            edited = steps.first,
        ) { it.replace("suspend fun twoSteps", "// Two steps in a row.\nsuspend fun twoSteps") }

        assertContains(explained, "\"refusalKind\": \"annotations-changed\"")
        assertEquals(coroutineTests, ranTests(dir), output)
        assertEquals("annotations-changed", decisionNotes(dir)["refusal-kind"], output)
    }

    @Test
    fun `a body change to a suspend function forces the same full run`(@TempDir dir: File) {
        // The refusal is not an artefact of comment edits: a real edit moves the same metadata.
        val (explained, output) = selectAfter(
            dir, kotlinBuild, listOf(steps, stepsTest, alpha, alphaTest), coroutineTests,
            edited = steps.first,
        ) { it.replace("return step(a)", "val b = step(a)\n    return b") }

        assertContains(explained, "\"refusalKind\": \"annotations-changed\"")
        assertEquals(coroutineTests, ranTests(dir), output)
        assertEquals("annotations-changed", decisionNotes(dir)["refusal-kind"], output)
    }

    private val inlined = "src/main/kotlin/dev/demokt/Inlined.kt" to """
        package dev.demokt
        inline fun aboveZero(n: Int): Boolean = n > 0
        fun belowTen(n: Int): Boolean = n < 10
    """.trimIndent()

    private val caller = "src/main/kotlin/dev/demokt/Caller.kt" to """
        package dev.demokt
        class Caller { fun check(n: Int): Boolean = aboveZero(n) }
    """.trimIndent()

    private val callerTest = "src/test/kotlin/dev/demokt/CallerTest.kt" to """
        package dev.demokt
        import org.junit.jupiter.api.Test
        import kotlin.test.assertTrue
        class CallerTest {
            @Test fun positive() { assertTrue(Caller().check(1)) }
        }
    """.trimIndent()

    // `belowTen` is not inline, so RangeTest gives the map real coverage of InlinedKt. Ordered by
    // name it runs after CallerTest, so only the inline scan can select CallerTest.
    private val rangeTest = "src/test/kotlin/dev/demokt/RangeTest.kt" to """
        package dev.demokt
        import org.junit.jupiter.api.Test
        import kotlin.test.assertTrue
        class RangeTest {
            @Test fun below() { assertTrue(belowTen(9)) }
        }
    """.trimIndent()

    @Test
    fun `a comment in a file declaring an inline function adds its inliners to the change set`(
        @TempDir dir: File,
    ) {
        // The comment shifts the inline body's lines, so the SMAP of every class that inlined it
        // changes. The inline scan reads that and adds Caller as a changed class of its own.
        val (explained, output) = selectAfter(
            dir, kotlinBuild, listOf(inlined, caller, callerTest, rangeTest, alpha, alphaTest, classOrderByName),
            expectedCapture = setOf("dev.demokt.AlphaTest", "dev.demokt.CallerTest", "dev.demokt.RangeTest"),
            edited = inlined.first,
        ) { it.replace("inline fun aboveZero", "// Strictly positive.\ninline fun aboveZero") }

        assertContains(explained, "\"inliners\": [\"dev.demokt.Caller\"]")
        assertContains(output, "inlined the changed sources")
        assertEquals(setOf("dev.demokt.CallerTest", "dev.demokt.RangeTest"), ranTests(dir), output)
        assertEquals("narrowed", decisionNotes(dir)["outcome"], output)
    }

    @Test
    fun `with the flag, a comment above a suspend function forces nothing`(@TempDir dir: File) {
        val (explained, output) = selectAfter(
            dir, kotlinBuild, listOf(steps, stepsTest, alpha, alphaTest), coroutineTests,
            edited = steps.first, flags = listOf(flag),
        ) { it.replace("suspend fun twoSteps", "// Two steps in a row.\nsuspend fun twoSteps") }

        assertContains(explained, "\"refusalKind\": null")
        assertEquals("narrowed", decisionNotes(dir)["outcome"], output)
        assertEquals(null, decisionNotes(dir)["refusal-kind"], output)
        assertEquals(emptySet(), ranTests(dir), output)
    }

    @Test
    fun `with the flag, the inliners of a comment edit run only by the line window`(@TempDir dir: File) {
        val (explained, output) = selectAfter(
            dir, kotlinBuild, listOf(inlined, caller, callerTest, rangeTest, alpha, alphaTest, classOrderByName),
            expectedCapture = setOf("dev.demokt.AlphaTest", "dev.demokt.CallerTest", "dev.demokt.RangeTest"),
            edited = inlined.first, flags = listOf(flag),
        ) { it.replace("inline fun aboveZero", "// Strictly positive.\ninline fun aboveZero") }

        assertContains(explained, "\"inliners\": []")
        assertContains(explained, "dev.demokt.Caller")
        assertContains(explained, "\"refusalKind\": null")
        assertEquals("narrowed", decisionNotes(dir)["outcome"], output)
        assertEquals(emptySet(), ranTests(dir), output)
    }
}
