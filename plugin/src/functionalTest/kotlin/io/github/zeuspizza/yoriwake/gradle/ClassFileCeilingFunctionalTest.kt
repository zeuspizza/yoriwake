package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    /** Java 21 classes under a JaCoCo whose official support ends at Java 20. */
    private val jacocoBehindTheClasses = "$minimalBuild\njacoco { toolVersion = \"0.8.10\" }"

    private fun captured(dir: File, script: String, vararg args: String): String {
        build(dir, "build.gradle.kts" to script, oneClass, secondClass, unused, oneTest, secondTest, classOrderByName)
        toolchains(dir)
        committed(dir)
        return runner(dir, "test", *args).build().output
    }

    private fun select(dir: File, vararg args: String): String {
        File(dir, "build/test-results").deleteRecursively()
        return runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD", *args).build().output
    }

    /** Gradle's default JaCoCo taken to be 0.8.10, which the build's `toolVersion` then equals. */
    private val defaultBehindTheClasses = "-Pyoriwake.internal.jacocoDefault=0.8.10"

    private val bundledLine = "yoriwake attaches its bundled JaCoCo 0.8.15 agent in its place"

    private fun mapJacoco(dir: File): String? =
        File(dir, ".gradle/yoriwake").walkTopDown().singleOrNull { it.name == "jacoco-version" }?.readText()?.trim()

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
    fun `classes newer than the host's JaCoCo officially instruments run every test`(@TempDir dir: File) {
        captured(dir, jacocoBehindTheClasses)

        changeBeta(dir)
        val output = select(dir)

        val notes = decisionNotes(dir)
        assertEquals("full-run", notes["outcome"], notes.toString())
        assertEquals("class-file-unsupported", notes["refusal-kind"], notes.toString())
        assertContains(output, "JaCoCo 0.8.10 (`jacoco.toolVersion`) officially instruments up to Java 20")
        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir))
    }

    @Test
    fun `a change to a class no test loaded still runs every test when JaCoCo does not officially support the build's Java`(@TempDir dir: File) {
        captured(dir, jacocoBehindTheClasses)

        File(dir, "src/main/java/dev/sample/Unused.java").writeText(unused.second.replace("return n;", "return n + 0;"))
        select(dir)

        assertEquals("class-file-unsupported", decisionNotes(dir)["refusal-kind"])
        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir))
    }

    @Test
    fun `classes newer than Gradle's default JaCoCo instruments are captured with the bundled JaCoCo and narrow`(@TempDir dir: File) {
        val capture = captured(dir, jacocoBehindTheClasses, defaultBehindTheClasses)
        assertContains(capture, bundledLine)
        assertFalse("class-file-unsupported" in capture, capture)
        assertEquals("0.8.15", mapJacoco(dir))

        changeBeta(dir)
        val output = select(dir, defaultBehindTheClasses)

        assertContains(output, bundledLine)
        val notes = decisionNotes(dir)
        assertEquals(null, notes["refusal-kind"], notes.toString())
        assertEquals(setOf("dev.sample.BetaTest"), ranTests(dir))
    }

    @Test
    fun `a map the build's own JaCoCo recorded runs every test under the bundled one until a recording run`(@TempDir dir: File) {
        captured(dir, jacocoBehindTheClasses)
        assertEquals("0.8.10", mapJacoco(dir))

        changeBeta(dir)
        val refused = select(dir, defaultBehindTheClasses)

        assertEquals("class-file-unsupported", decisionNotes(dir)["refusal-kind"])
        assertContains(refused, "the map was recorded by JaCoCo 0.8.10")
        assertContains(refused, "A recording run with this run's JaCoCo 0.8.15 lifts this.")
        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir))

        commit(dir, "beta")
        runner(dir, "test", defaultBehindTheClasses).build()
        assertEquals("0.8.15", mapJacoco(dir))
        File(dir, "src/main/java/dev/sample/Beta.java").writeText(
            "package dev.sample;\npublic class Beta { public int thrice(int n) { return 3 * n; } }\n"
        )
        select(dir, defaultBehindTheClasses)

        assertEquals(null, decisionNotes(dir)["refusal-kind"])
        assertEquals(setOf("dev.sample.BetaTest"), ranTests(dir))
    }

    @Test
    fun `a disabled run keeps the build's own JaCoCo agent`(@TempDir dir: File) {
        val printsAgent = """
            tasks.register("agentJar") {
                val agent: FileCollection = configurations.getByName("jacocoAgent")
                doLast { println("agent: " + agent.files.map { it.name }) }
            }
        """.trimIndent()
        captured(dir, "$jacocoBehindTheClasses\n$printsAgent", defaultBehindTheClasses)

        val swapped = runner(dir, "test", "agentJar", defaultBehindTheClasses).build().output
        val disabled = runner(dir, "test", "agentJar", defaultBehindTheClasses, "-Pyoriwake.disabled").build().output

        assertContains(swapped, "agent: [org.jacoco.agent-0.8.15.jar]")
        assertContains(disabled, "agent: [org.jacoco.agent-0.8.10.jar]")
        assertFalse(bundledLine in disabled, disabled)
    }

    /** A test JVM on this JDK judged against an agent ceiling below it, set the way [how] says. */
    private fun belowTheAgentCeiling(how: String) = minimalBuild.replace(
        "tasks.test { useJUnitPlatform() }",
        "tasks.test { useJUnitPlatform(); $how }",
    )

    @Test
    fun `a test JVM newer than the agent reads runs every test`(@TempDir dir: File) {
        // A system property of the task: the daemon reads it and refuses before the JVM starts.
        val capture = captured(dir, belowTheAgentCeiling("systemProperty(\"${AgentContract.AGENT_ASM_CEILING_PROPERTY}\", \"60\")"))
        assertContains(capture, "selecting runs of this task will run every test (${AgentContract.TEST_JVM_UNSUPPORTED_KIND})")

        changeBeta(dir)
        val output = select(dir)

        val notes = decisionNotes(dir)
        assertEquals(AgentContract.TEST_JVM_UNSUPPORTED_KIND, notes["refusal-kind"], notes.toString())
        assertContains(output, "the yoriwake agent's ASM reads up to Java 16")
        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir))
    }

    @Test
    fun `a test JVM the daemon could not judge judges itself`(@TempDir dir: File) {
        // Through the JVM's own environment, which the daemon never reads: only the test JVM knows.
        captured(dir, belowTheAgentCeiling("environment(\"JAVA_TOOL_OPTIONS\", \"-D${AgentContract.AGENT_ASM_CEILING_PROPERTY}=60\")"))
        val touches = File(dir, ".gradle/yoriwake").walkTopDown()
            .filter { it.name == AgentContract.TOUCHES_FILE }.flatMap { it.readLines() }.toList()
        assertTrue(
            touches.any { it.contains("\t${AgentContract.TOUCH_ALL}\t${AgentContract.TEST_JVM_UNSUPPORTED_KIND}: ") },
            "touches: $touches",
        )

        changeBeta(dir)
        select(dir)

        val notes = decisionNotes(dir)
        assertEquals(null, notes["refusal-kind"], notes.toString())
        assertEquals(AgentContract.TEST_JVM_UNSUPPORTED_KIND, notes["full-run-kind"], notes.toString())
        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir))
    }

}
