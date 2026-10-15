package io.github.zeuspizza.yoriwake.gradle

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

    private fun capturesAndSelects(dir: File, jdk: Int) {
        val asked = System.getProperty("yoriwake.functional.testJvms")
        assumeTrue(asked == null || jdk.toString() in asked.split(","), "not in yoriwake.functional.testJvms")
        build(
            dir,
            "build.gradle.kts" to "$minimalBuild\njava { toolchain { languageVersion = JavaLanguageVersion.of($jdk) } }",
            oneClass, secondClass, oneTest, secondTest, classOrderByName,
        )
        toolchains(dir)
        ignoreBuildOutputs(dir)
        git(dir, "init")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "--allow-empty", "-m", "base")
        git(dir, "add", ".")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-m", "sample")

        runner(dir, "test").build()

        val mapDir = File(dir, ".gradle/yoriwake").listFiles()?.singleOrNull(File::isDirectory)
        assertTrue(
            mapDir != null && File(mapDir, "coverage.tsv").readText().contains("dev.sample.Beta"),
            "no map was captured on JDK $jdk",
        )

        changeBeta(dir)
        File(dir, "build/test-results").deleteRecursively()
        runner(dir, "test", "-Pyoriwake.select").build()

        assertEquals("narrowed", decisionNotes(dir)["outcome"], "no test was judged from the map on JDK $jdk")
        assertEquals(setOf("dev.sample.BetaTest"), ranTests(dir))
    }
}
