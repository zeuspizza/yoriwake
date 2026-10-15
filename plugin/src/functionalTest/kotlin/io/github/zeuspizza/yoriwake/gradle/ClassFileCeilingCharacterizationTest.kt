package io.github.zeuspizza.yoriwake.gradle

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * What a selecting run does when a class file is newer than a reader on its path can read: the
 * plugin's ASM, or the host's JaCoCo agent. Whatever the outcome is called, the test a change
 * reaches runs.
 */
class ClassFileCeilingCharacterizationTest : FunctionalTestSupport() {

    // The test JVM's stderr reaches the output, so JaCoCo's instrumentation error is visible.
    private val loggedBuild = minimalBuild.replace(
        "tasks.test { useJUnitPlatform() }",
        "tasks.test { useJUnitPlatform(); testLogging { showStandardStreams = true } }",
    )

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

    private fun toolchain(jdk: Int) = "java { toolchain { languageVersion = JavaLanguageVersion.of($jdk) } }"

    private fun captured(dir: File, script: String, vararg sources: Pair<String, String>): String {
        build(dir, "build.gradle.kts" to script, *sources, classOrderByName)
        toolchains(dir)
        committed(dir)
        return runner(dir, "test").build().output
    }

    private fun select(dir: File): String {
        File(dir, "build/test-results").deleteRecursively()
        return runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD").build().output
    }

    @Test
    fun `a class newer than the plugin reads forces every selecting run`(@TempDir dir: File) {
        captured(dir, "$minimalBuild\n$patchUnused", oneClass, secondClass, unused, oneTest, secondTest)

        changeBeta(dir)
        select(dir)

        val notes = decisionNotes(dir)
        assertEquals("full-run", notes["outcome"], notes.toString())
        assertEquals("scan-exhausted", notes["refusal-kind"], notes.toString())
        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir))
    }

    @Test
    fun `a class newer than the host's JaCoCo instruments forces when a test executed it`(@TempDir dir: File) {
        assumeTrue(FunctionalGradle.version.startsWith("9."), "Gradle before 9.1 does not support Java 25")
        val output = captured(
            dir, "$loggedBuild\n${toolchain(25)}\njacoco { toolVersion = \"0.8.12\" }",
            oneClass, secondClass, unused, oneTest, secondTest,
        )
        assertContains(output, "Error while instrumenting dev/sample/Beta")

        changeBeta(dir)
        select(dir)

        val notes = decisionNotes(dir)
        assertEquals("full-run", notes["outcome"], notes.toString())
        assertEquals(null, notes["refusal-kind"], notes.toString())
        assertEquals("no-coverage-for-changed", notes["full-run-kind"], notes.toString())
        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir))
    }

    @Test
    fun `a class newer than the host's JaCoCo instruments forces even when no test loaded it`(@TempDir dir: File) {
        assumeTrue(FunctionalGradle.version.startsWith("9."), "Gradle before 9.1 does not support Java 25")
        captured(
            dir, "$loggedBuild\n${toolchain(25)}\njacoco { toolVersion = \"0.8.12\" }",
            oneClass, secondClass, unused, oneTest, secondTest,
        )

        File(dir, "src/main/java/dev/sample/Unused.java").writeText(
            unused.second.replace("return n;", "return n + 0;")
        )
        select(dir)

        // Nothing was instrumented, so nothing proves Unused was never executed.
        val notes = decisionNotes(dir)
        assertEquals("full-run", notes["outcome"], notes.toString())
        assertEquals("no-coverage-for-changed", notes["full-run-kind"], notes.toString())
        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir))
    }

    private val modules = arrayOf(
        "settings.gradle.kts" to """
            rootProject.name = "sample"
            include("a", "b")
        """.trimIndent(),
        "build.gradle.kts" to "",
        "a/build.gradle.kts" to """
            ${loggedBuild.replace("plugins {", "plugins {\n    `java-library`")}
            ${toolchain(25)}
            jacoco { toolVersion = "0.8.12" }
            tasks.compileJava { options.release = 21 }
            tasks.compileTestJava { options.release = 21 }
            // Read reflectively: classes compiled for Java 21 cannot name Java 25 classes.
            dependencies { testRuntimeOnly(project(":b")) }
            java { disableAutoTargetJvm() }
        """.trimIndent(),
        "b/build.gradle.kts" to """
            plugins { `java-library` }
            ${toolchain(25)}
        """.trimIndent(),
        "b/src/main/java/dev/b/Late.java" to """
            package dev.b;
            public class Late { public static int value() { return 1; } }
        """.trimIndent(),
        "a/src/main/java/dev/a/Early.java" to """
            package dev.a;
            public class Early { public static int value() { return 2; } }
        """.trimIndent(),
        "a/src/test/java/dev/a/CrossTest.java" to """
            package dev.a;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class CrossTest {
                @Test void reachesLate() throws Exception {
                    assertEquals(1, Class.forName("dev.b.Late").getMethod("value").invoke(null));
                }
            }
        """.trimIndent(),
        "a/src/test/java/dev/a/EarlyTest.java" to """
            package dev.a;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class EarlyTest {
                @Test void early() { assertEquals(2, Early.value()); }
            }
        """.trimIndent(),
        "a/src/test/resources/junit-platform.properties" to classOrderByName.second,
    )

    private fun changeLate(dir: File, body: String) {
        File(dir, "b/src/main/java/dev/b/Late.java").writeText(
            """
            package dev.b;
            public class Late { public static int value() { return $body; } }
            """.trimIndent()
        )
    }

    private fun ranIn(dir: File, module: String): Set<String> =
        File(dir, "$module/build/test-results/test")
            .listFiles { f: File -> f.name.endsWith(".xml") }.orEmpty()
            .map { it.name.removePrefix("TEST-").removeSuffix(".xml") }
            .toSet()

    @Test
    fun `a change to a module JaCoCo cannot instrument runs the test in another module that executes it`(@TempDir dir: File) {
        assumeTrue(FunctionalGradle.version.startsWith("9."), "Gradle before 9.1 does not support Java 25")
        build(dir, *modules)
        toolchains(dir)
        committed(dir)
        val capture = runner(dir, ":a:test").build().output
        assertContains(capture, "Error while instrumenting dev/b/Late")

        changeLate(dir, "1 - 0")
        File(dir, "a/build/test-results").deleteRecursively()
        runner(dir, ":a:test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD").build()
        assertContains(ranIn(dir, "a"), "dev.a.CrossTest")

        // Again after a run that merged its records into the map.
        commit(dir, "late")
        changeLate(dir, "2 - 1")
        File(dir, "a/build/test-results").deleteRecursively()
        runner(dir, ":a:test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD").build()
        assertContains(ranIn(dir, "a"), "dev.a.CrossTest")
    }

    @Test
    fun `the yoriwake agent is attached before JaCoCo's, so it sees the bytes before JaCoCo instruments them`(@TempDir dir: File) {
        val argsTest = "src/test/java/dev/sample/ArgsTest.java" to """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            class ArgsTest {
                @Test void prints() {
                    for (String a : java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                        if (a.startsWith("-javaagent:")) { String jar = a.split("=")[0]; System.out.println("AGENT-ORDER " + jar.substring(jar.lastIndexOf('/') + 1)); }
                    }
                }
            }
        """.trimIndent()
        val output = captured(dir, loggedBuild, oneClass, argsTest)

        val order = Regex("""AGENT-ORDER (\S+)""").findAll(output).map { it.groupValues[1] }.toList()
        assertEquals(2, order.size, order.toString())
        assertContains(order[0], "yoriwake")
        assertContains(order[1], "jacocoagent")
    }
}
