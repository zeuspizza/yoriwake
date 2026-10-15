package io.github.zeuspizza.yoriwake.gradle

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * A class of the build's own output newer than the plugin's ASM reads, or than the host's JaCoCo
 * officially instruments: every selecting run runs every test and names the reader, and a
 * recording run says so ahead of time.
 */
class ClassFileCeilingFunctionalTest : FunctionalTestSupport() {

    private val unused = "src/main/java/dev/sample/Unused.java" to """
        package dev.sample;
        public class Unused { public int once(int n) { return n; } }
    """.trimIndent()

    /** Raises `Unused.class` to Java 28's class-file version after compiling; no test loads it. */
    private val patchUnused = """
        tasks.compileJava {
            val out = destinationDirectory
            doLast {
                val file = out.get().file("dev/sample/Unused.class").asFile
                val bytes = file.readBytes()
                bytes[6] = 0
                bytes[7] = 72
                file.writeBytes(bytes)
            }
        }
    """.trimIndent()

    private val java25WithJacoco0812 =
        "$minimalBuild\njava { toolchain { languageVersion = JavaLanguageVersion.of(25) } }\n" +
            "jacoco { toolVersion = \"0.8.12\" }"

    private fun captured(dir: File, script: String): String {
        build(dir, "build.gradle.kts" to script, oneClass, secondClass, unused, oneTest, secondTest, classOrderByName)
        toolchains(dir)
        committed(dir)
        return runner(dir, "test").build().output
    }

    private fun select(dir: File): String {
        File(dir, "build/test-results").deleteRecursively()
        return runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD").build().output
    }

    @Test
    fun `a class compiled for a newer Java than the plugin reads runs every test`(@TempDir dir: File) {
        val capture = captured(dir, "$minimalBuild\n$patchUnused")
        assertContains(capture, "selecting runs of this task will run every test (class-file-unsupported)")

        changeBeta(dir)
        val output = select(dir)

        val notes = decisionNotes(dir)
        assertEquals("full-run", notes["outcome"], notes.toString())
        assertEquals("class-file-unsupported", notes["refusal-kind"], notes.toString())
        assertContains(output, "dev.sample.Unused is compiled for Java 28")
        assertContains(output, "the plugin's ASM reads up to Java 27")
        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir))
    }

    @Test
    fun `classes newer than the host's JaCoCo instruments run every test`(@TempDir dir: File) {
        assumeTrue(FunctionalGradle.version.startsWith("9."), "Gradle before 9.1 does not support Java 25")
        captured(dir, java25WithJacoco0812)

        changeBeta(dir)
        val output = select(dir)

        val notes = decisionNotes(dir)
        assertEquals("full-run", notes["outcome"], notes.toString())
        assertEquals("class-file-unsupported", notes["refusal-kind"], notes.toString())
        assertContains(output, "JaCoCo 0.8.12 (`jacoco.toolVersion`) officially instruments up to Java 22")
        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir))
    }

    @Test
    fun `a change to a class no test loaded still runs every test when JaCoCo cannot instrument the build`(@TempDir dir: File) {
        assumeTrue(FunctionalGradle.version.startsWith("9."), "Gradle before 9.1 does not support Java 25")
        captured(dir, java25WithJacoco0812)

        File(dir, "src/main/java/dev/sample/Unused.java").writeText(unused.second.replace("return n;", "return n + 0;"))
        select(dir)

        assertEquals("class-file-unsupported", decisionNotes(dir)["refusal-kind"])
        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir))
    }
}
