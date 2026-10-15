package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.agent.capture.ClassFileSupport as AgentClassFileSupport
import io.github.zeuspizza.yoriwake.gradle.bytecode.ClassFileSupport
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The test-JVM JDKs the reference names as supported, each captured and selected on.
 *
 * The agent is compiled for Java 11 and the daemon needs 21, so a test JVM below the daemon's is
 * reached only through a toolchain, and nothing else in the suite runs one. A JDK the toolchain
 * cannot find fails the build rather than falling back to another, so a green case ran on its JDK.
 */
class TestJvmRangeFunctionalTest : FunctionalTestSupport() {

    @Test
    fun `test JVM on JDK 11`(@TempDir dir: File) = capturesAndSelects(dir, 11)

    @Test
    fun `test JVM on JDK 17`(@TempDir dir: File) = capturesAndSelects(dir, 17)

    @Test
    fun `test JVM on JDK 21`(@TempDir dir: File) = capturesAndSelects(dir, 21)

    @Test
    fun `test JVM on JDK 25`(@TempDir dir: File) {
        assumeTrue(FunctionalGradle.version.startsWith("9."), "Gradle before 9.1 does not support Java 25")
        capturesAndSelects(dir, 25)
    }

    /**
     * The newest early-access JDK, named by the workflow that installs it. What it must do is
     * computed from the ceilings this build carries, so it changes with an ASM or JaCoCo upgrade
     * and never needs writing down: narrow within every ceiling, else refuse by name.
     */
    @Test
    fun `test JVM on the newest early-access JDK`(@TempDir dir: File) {
        val jdk = System.getProperty("yoriwake.functional.earlyAccessJdk")?.toIntOrNull()
        assumeTrue(jdk != null, "no early-access JDK named in yoriwake.functional.earlyAccessJdk")
        capturesAndSelects(dir, jdk!!) { jacoco ->
            expectedOn(
                jdk,
                agentCeiling = AgentClassFileSupport.asmCeiling(),
                pluginCeiling = ClassFileSupport.asmCeiling(),
                jacocoJava = ClassFileSupport.jacocoCeiling(jacoco).java,
            )
        }
    }

    /** [expected] reads the build's JaCoCo version and says what selection must do; null narrows. */
    private fun capturesAndSelects(dir: File, jdk: Int, expected: ((jacoco: String) -> String)? = null) {
        val asked = System.getProperty("yoriwake.functional.testJvms")
        assumeTrue(asked == null || jdk.toString() in asked.split(","), "not in yoriwake.functional.testJvms")
        val versionTask = "tasks.register(\"jacocoVersion\") { val v = jacoco.toolVersion; doLast { println(\"JACOCO-VERSION \$v\") } }"
        build(
            dir,
            "build.gradle.kts" to "$minimalBuild\njava { toolchain { languageVersion = JavaLanguageVersion.of($jdk) } }" +
                (if (expected == null) "" else "\n$versionTask"),
            oneClass, secondClass, oneTest, secondTest, classOrderByName,
        )
        toolchains(dir)
        ignoreBuildOutputs(dir)
        git(dir, "init")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "--allow-empty", "-m", "base")
        git(dir, "add", ".")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-m", "sample")
        val outcome = expected?.let { it(Regex("""JACOCO-VERSION (\S+)""").find(runner(dir, "jacocoVersion").build().output)!!.groupValues[1]) }
            ?: NARROWED

        runner(dir, "test").build()

        val mapDir = File(dir, ".gradle/yoriwake").listFiles()?.singleOrNull(File::isDirectory)
        // A JDK above a reader still records a map, but JaCoCo may have instrumented nothing.
        assertTrue(
            mapDir != null && (outcome != NARROWED || File(mapDir, "coverage.tsv").readText().contains("dev.sample.Beta")),
            "no map was captured on JDK $jdk",
        )

        changeBeta(dir)
        File(dir, "build/test-results").deleteRecursively()
        runner(dir, "test", "-Pyoriwake.select").build()

        if (outcome == NARROWED) {
            assertEquals(NARROWED, decisionNotes(dir)["outcome"], "no test was judged from the map on JDK $jdk")
            assertEquals(setOf("dev.sample.BetaTest"), ranTests(dir))
        } else {
            assertEquals(outcome, decisionNotes(dir)["refusal-kind"], "JDK $jdk: ${decisionNotes(dir)}")
            assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir))
        }
    }

    @Test
    fun `the early-access expectation follows the ceilings in the order a selecting run checks them`() {
        // Ceilings as class-file majors for the two ASMs, a Java version for JaCoCo.
        assertEquals(NARROWED, expectedOn(26, agentCeiling = 71, pluginCeiling = 71, jacocoJava = 26))
        assertEquals("class-file-unsupported", expectedOn(27, agentCeiling = 71, pluginCeiling = 71, jacocoJava = 26))
        assertEquals("class-file-unsupported", expectedOn(27, agentCeiling = 71, pluginCeiling = 70, jacocoJava = 27))
        assertEquals("test-jvm-unsupported", expectedOn(28, agentCeiling = 71, pluginCeiling = 72, jacocoJava = 28))
    }

    private companion object {
        const val NARROWED = "narrowed"

        /** What a JDK whose classes and test JVM are both at [jdk] must do under these ceilings. */
        fun expectedOn(jdk: Int, agentCeiling: Int, pluginCeiling: Int, jacocoJava: Int): String = when {
            jdk + 44 > agentCeiling -> "test-jvm-unsupported"
            jdk > minOf(pluginCeiling - 44, jacocoJava) -> "class-file-unsupported"
            else -> NARROWED
        }
    }
}
