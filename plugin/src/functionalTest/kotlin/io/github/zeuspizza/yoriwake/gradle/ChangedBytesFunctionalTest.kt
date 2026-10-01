package io.github.zeuspizza.yoriwake.gradle

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * A class whose compiled bytes change while its source does not -- a post-compile weaver, a
 * bytecode post-processor -- selects the tests that recorded it. `git diff` reports nothing for
 * such a class, so only the bytes can say it changed.
 */
class ChangedBytesFunctionalTest : FunctionalTestSupport() {

    /**
     * Stands in for a weaver: with `-Pweave=<Class>`, compileJava rewrites that class's string
     * constant "hello" after compiling it, leaving its source as it is.
     */
    private val weavingBuild = minimalBuild + "\n" + """
        tasks.compileJava {
            // Local, not a script val: the configuration cache cannot serialise script references.
            val weave = providers.gradleProperty("weave")
            inputs.property("weave", weave.orElse(""))
            val out = destinationDirectory
            doLast {
                val target = weave.orNull ?: return@doLast
                val file = out.get().file("dev/sample/" + target + ".class").asFile
                val bytes = file.readBytes()
                val from = "hello".toByteArray()
                val to = "HELLO".toByteArray()
                var i = 0
                while (i <= bytes.size - from.size) {
                    if ((from.indices).all { bytes[i + it] == from[it] }) to.copyInto(bytes, i)
                    i++
                }
                file.writeBytes(bytes)
            }
        }
    """.trimIndent()

    private fun source(name: String, body: String) =
        "src/main/java/dev/sample/$name.java" to "package dev.sample;\npublic class $name { $body }"

    private fun test(name: String, assertion: String) =
        "src/test/java/dev/sample/${name}Test.java" to """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class ${name}Test {
                @Test void works() { $assertion }
            }
        """.trimIndent()

    private val greeter = source("Greeter", """public String greet() { return "hello"; }""")
    private val other = source("Other", "public int two() { return 2; }")
    private val third = source("Third", "public int three() { return 3; }")
    /** Executed by no test, so no test recorded it. */
    private val unused = source("Unused", """public String word() { return "hello"; }""")

    private val all = setOf("dev.sample.GreeterTest", "dev.sample.OtherTest", "dev.sample.ThirdTest")

    /** Captures, edits Other's source, and leaves the selecting run to the caller. */
    private fun capturedWithOtherChanged(dir: File) {
        build(
            dir, "build.gradle.kts" to weavingBuild, greeter, other, third, unused, classOrderByName,
            test("Greeter", """assertEquals("hello", new Greeter().greet());"""),
            test("Other", "assertEquals(2, new Other().two());"),
            test("Third", "assertEquals(3, new Third().three());"),
        )
        committed(dir)
        runner(dir, "test").build()
        assertEquals(all, ranTests(dir))

        val edited = File(dir, other.first)
        edited.writeText(edited.readText().replace("return 2;", "return 1 + 1;"))
        File(dir, "build/test-results").deleteRecursively()
    }

    private fun mapDir(dir: File) = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)

    @Test
    fun `a class rewritten after compilation selects the tests that recorded it`(@TempDir dir: File) {
        capturedWithOtherChanged(dir)

        // GreeterTest fails on the rewritten bytes, so the build failing is the test having run.
        val output = runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD", "-Pweave=Greeter")
            .buildAndFail().output

        // Greeter's test runs first, so every later test ran after Greeter was first loaded and
        // runs too. The bytes rule is what the reason column credits for GreeterTest.
        assertEquals(all, ranTests(dir), output)
        assertEquals("narrowed", decisionNotes(dir)["outcome"], output)
        assertEquals(
            "REACHES_CHANGE",
            decisionReasons(dir)["[engine:junit-jupiter]/[class:dev.sample.GreeterTest]/[method:works()]"],
            output,
        )
    }

    @Test
    fun `a rewritten class no test recorded forces a full run`(@TempDir dir: File) {
        capturedWithOtherChanged(dir)

        // The explanation reads whatever bytes the build directory holds, so compiled first.
        runner(dir, "classes", "yoriwakeExplainTest", "-Pyoriwake.base=HEAD", "-Pweave=Unused").build()
        val explained = File(mapDir(dir), "explain.json").readText()
        val output = runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD", "-Pweave=Unused")
            .build().output

        assertContains(explained, "\"fullRunKind\": \"no-coverage-for-changed-bytes\"")
        assertEquals(all, ranTests(dir), output)
        assertEquals("no-coverage-for-changed-bytes", decisionNotes(dir)["full-run-kind"], output)
    }

    @Test
    fun `bytes that match the capture add nothing`(@TempDir dir: File) {
        capturedWithOtherChanged(dir)

        val output = runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD").build().output

        // ThirdTest ran after Other was first loaded; GreeterTest ran before and stays out.
        assertEquals(setOf("dev.sample.OtherTest", "dev.sample.ThirdTest"), ranTests(dir), output)
        assertEquals("narrowed", decisionNotes(dir)["outcome"], output)
    }
}
