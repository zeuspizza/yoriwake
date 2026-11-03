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
        assumeTrue(FunctionalGradle.atLeast(9, 1), "Gradle before 9.1 does not support Java 25")
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
        capturesAndSelects(dir, jdk!!, tolerant = true) { jacoco ->
            expectedOn(
                jdk,
                agentCeiling = AgentClassFileSupport.asmCeiling(),
                pluginCeiling = ClassFileSupport.asmCeiling(),
                jacocoJava = ClassFileSupport.jacocoCeiling(jacoco).java,
            )
        }
    }

    /** [expected] reads the build's JaCoCo version and says what selection must do; null narrows. */
    /**
     * Runs first and asks for a native library no file provides, as a library trying its platform's
     * names does. On every JDK that is no load, so the run still narrows: judged where the JDK has
     * opened a library, a hook this JDK's own classes must take.
     */
    private val absentLibraryAttempt = "src/test/java/dev/sample/AaNativeTest.java" to """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        class AaNativeTest {
            @Test void tries() {
                try { System.loadLibrary("yoriwakeabsentcodec"); } catch (UnsatisfiedLinkError absent) { }
            }
        }
    """.trimIndent()

    private val fixture = listOf(oneClass, secondClass, oneTest, secondTest, classOrderByName, absentLibraryAttempt)

    /**
     * [tolerant] lets tests beyond the prediction pass, reported on one line for the job to warn
     * on: on an early-access JDK a wider run is the safe direction, on a supported one a regression.
     */
    private fun capturesAndSelects(
        dir: File,
        jdk: Int,
        tolerant: Boolean = false,
        expected: ((jacoco: String) -> String)? = null,
    ) {
        val asked = System.getProperty("yoriwake.functional.testJvms")
        assumeTrue(asked == null || jdk.toString() in asked.split(","), "not in yoriwake.functional.testJvms")
        val versionTask = "tasks.register(\"jacocoVersion\") { val v = jacoco.toolVersion; doLast { println(\"JACOCO-VERSION \$v\") } }"
        build(
            dir,
            "build.gradle.kts" to "$minimalBuild\njava { toolchain { languageVersion = JavaLanguageVersion.of($jdk) } }" +
                (if (expected == null) "" else "\n$versionTask"),
            *fixture.toTypedArray(),
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

        val notes = decisionNotes(dir)
        val ran = ranTests(dir)
        // A refusal runs the whole fixture; narrowing must at least run the changed class's test.
        val mustRun = if (outcome == NARROWED) setOf("dev.sample.BetaTest") else fixtureTests(fixture.map { it.first })
        val (failure, extra) = verdict(outcome, notes["refusal-kind"] ?: notes["outcome"], mustRun, ran, tolerant)
        assertTrue(failure == null, "JDK $jdk: $failure; predicted $outcome, ran $ran, notes $notes")
        if (extra.isNotEmpty()) println(widerMarker(extra, decisionReasons(dir)))
    }

    @Test
    fun `the early-access expectation follows the ceilings in the order a selecting run checks them`() {
        // Ceilings as class-file majors for the two ASMs, a Java version for JaCoCo.
        assertEquals(NARROWED, expectedOn(26, agentCeiling = 71, pluginCeiling = 71, jacocoJava = 26))
        assertEquals("class-file-unsupported", expectedOn(27, agentCeiling = 71, pluginCeiling = 71, jacocoJava = 26))
        assertEquals("class-file-unsupported", expectedOn(27, agentCeiling = 71, pluginCeiling = 70, jacocoJava = 27))
        assertEquals("test-jvm-unsupported", expectedOn(28, agentCeiling = 71, pluginCeiling = 72, jacocoJava = 28))
    }

    @Test
    fun `a selecting run fails on a skipped test or a wrong outcome, and only the early-access case tolerates a wider run`() {
        val all = setOf("dev.sample.AaNativeTest", "dev.sample.AlphaTest", "dev.sample.BetaTest")
        val beta = setOf("dev.sample.BetaTest")
        val refused = "test-jvm-unsupported"
        assertEquals(Verdict(null, emptySet()), verdict(refused, refused, all, all, tolerant = true))
        assertTrue(verdict(refused, refused, all, all - "dev.sample.AaNativeTest", tolerant = true).failure!!.contains("AaNativeTest"))
        assertTrue(verdict(refused, "class-file-unsupported", all, all, tolerant = true).failure != null)
        assertTrue(verdict(refused, NARROWED, all, all, tolerant = true).failure != null)
        assertTrue(verdict(NARROWED, refused, beta, all, tolerant = true).failure != null)
        val wider = beta + "dev.sample.AaNativeTest"
        assertEquals(Verdict(null, setOf("dev.sample.AaNativeTest")), verdict(NARROWED, NARROWED, beta, wider, tolerant = true))
        assertTrue(verdict(NARROWED, NARROWED, beta, wider, tolerant = false).failure!!.contains("AaNativeTest"))
        assertTrue(verdict(NARROWED, NARROWED, beta, setOf("dev.sample.AlphaTest"), tolerant = true).failure!!.contains("BetaTest"))
        assertEquals(all, fixtureTests(fixture.map { it.first }))
    }

    @Test
    fun `the wider-run line names each extra test with its decision reasons`() {
        val reasons = mapOf(
            "[engine:junit-jupiter]/[class:dev.sample.AaNativeTest]/[method:tries()]" to "NATIVE_LOAD",
            "[engine:junit-jupiter]/[class:dev.sample.BetaTest]/[method:passes()]" to "CHANGED",
        )
        assertEquals(
            "yoriwake-early-access-wider dev.sample.AaNativeTest=NATIVE_LOAD dev.sample.Zed=none",
            widerMarker(setOf("dev.sample.Zed", "dev.sample.AaNativeTest"), reasons),
        )
    }

    private companion object {
        const val NARROWED = "narrowed"

        /** The line `matrix_check.py early-access` turns into a warning. */
        const val WIDER_MARKER = "yoriwake-early-access-wider"

        data class Verdict(val failure: String?, val extra: Set<String>)

        /** The test classes a fixture's source paths declare. */
        fun fixtureTests(paths: List<String>): Set<String> = paths
            .mapNotNull { Regex("""src/test/java/(.+Test)\.java""").matchEntire(it)?.groupValues?.get(1) }
            .map { it.replace('/', '.') }
            .toSet()

        /**
         * Why a selecting run that ended in [actual] and ran [ran] is wrong when [predicted] and
         * [mustRun] were expected; with no failure, the tests it ran beyond [mustRun].
         */
        fun verdict(predicted: String, actual: String?, mustRun: Set<String>, ran: Set<String>, tolerant: Boolean): Verdict {
            val missing = mustRun - ran
            val extra = ran - mustRun
            val failure = when {
                actual != predicted -> "expected $predicted, the run ended in $actual"
                missing.isNotEmpty() -> "did not run ${missing.sorted()}"
                extra.isNotEmpty() && !tolerant -> "ran ${extra.sorted()} beyond the prediction"
                else -> null
            }
            return Verdict(failure, if (failure == null) extra else emptySet())
        }

        /** [reasons] is keyed by JUnit unique id, so a class's reasons are the ids naming it. */
        fun widerMarker(extra: Set<String>, reasons: Map<String, String>): String =
            WIDER_MARKER + extra.sorted().joinToString("") { test ->
                val found = reasons.filterKeys { "[class:$test]" in it }.values.toSortedSet()
                " $test=" + (found.joinToString(",").ifEmpty { "none" })
            }

        /** What a JDK whose classes and test JVM are both at [jdk] must do under these ceilings. */
        fun expectedOn(jdk: Int, agentCeiling: Int, pluginCeiling: Int, jacocoJava: Int): String = when {
            jdk + 44 > agentCeiling -> "test-jvm-unsupported"
            jdk > minOf(pluginCeiling - 44, jacocoJava) -> "class-file-unsupported"
            else -> NARROWED
        }
    }
}
