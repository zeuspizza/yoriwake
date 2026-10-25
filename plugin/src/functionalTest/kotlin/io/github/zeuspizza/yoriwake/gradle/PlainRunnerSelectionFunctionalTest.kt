package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Plain JUnit 4, off the JUnit Platform: with `narrowPlainRunners`, a selecting run leaves out a whole
 * class only when its runner runs exactly its own methods and every one is skipped. A class left out
 * takes Gradle's own way out for a class its filters emptied, so it leaves no report entry.
 */
class PlainRunnerSelectionFunctionalTest : FunctionalTestSupport() {

    private val build = """
        plugins {
            java
            jacoco
            id("io.github.zeuspizza.yoriwake")
        }
        repositories { mavenCentral() }
        dependencies {
            testImplementation("junit:junit:4.13.2")
            testImplementation("org.mockito:mockito-core:5.14.2")
        }
        tasks.test { useJUnit() }
    """.trimIndent()

    private fun test(name: String, body: String, header: String = "") =
        "src/test/java/dev/sample/$name.java" to """
            package dev.sample;
            import org.junit.Test;
            import static org.junit.Assert.assertEquals;
            $header
            public class $name {
                $body
            }
        """.trimIndent()

    /** Every class but BetaTest covers Alpha only; BetaTest covers Beta. */
    private val sources = arrayOf(
        "src/main/java/dev/sample/Alpha.java" to "package dev.sample;\npublic class Alpha { public int twice(int n) { return n * 2; } }",
        "src/main/java/dev/sample/Beta.java" to "package dev.sample;\npublic class Beta { public int thrice(int n) { return n * 3; } }",
        test("AlphaTest", "@Test public void a() { assertEquals(2, new Alpha().twice(1)); }\n" +
            "@Test @org.junit.Ignore public void ignored() { }"),
        test("BetaTest", "@Test public void b() { assertEquals(3, new Beta().thrice(1)); }"),
        test("AlphaMockitoTest", "@Test public void m() { assertEquals(4, new Alpha().twice(2)); }",
            "@org.junit.runner.RunWith(org.mockito.junit.MockitoJUnitRunner.StrictStubs.class)"),
        "src/test/java/dev/sample/AlphaLegacyTest.java" to """
            package dev.sample;
            public class AlphaLegacyTest extends junit.framework.TestCase {
                public void testTwice() { assertEquals(6, new Alpha().twice(3)); }
            }
        """.trimIndent(),
        // JUnit 3 runs a method whatever @Ignore it carries, so this one reaches Beta's change.
        "src/test/java/dev/sample/AlphaLegacyIgnoredTest.java" to """
            package dev.sample;
            public class AlphaLegacyIgnoredTest extends junit.framework.TestCase {
                public void testTwice() { assertEquals(6, new Alpha().twice(3)); }
                @org.junit.Ignore public void testThrice() { assertEquals(3, new Beta().thrice(1)); }
            }
        """.trimIndent(),
        test("ParameterizedAlphaTest",
            """
            @org.junit.runners.Parameterized.Parameters public static java.util.List<Object[]> data() {
                return java.util.Arrays.asList(new Object[][] {{1}, {2}});
            }
            private final int n;
            public ParameterizedAlphaTest(int n) { this.n = n; }
            @Test public void p() { assertEquals(n * 2, new Alpha().twice(n)); }
            """.trimIndent(),
            "@org.junit.runner.RunWith(org.junit.runners.Parameterized.class)"),
        test("AllIgnoredTest", "@Test @org.junit.Ignore public void never() { }"),
        // Mockito's defaults attach an agent to the JVM, which rightly widens all of it.
        "src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker" to "mock-maker-subclass",
        "src/test/resources/mockito-extensions/org.mockito.plugins.MemberAccessor" to "member-accessor-reflection",
    )

    private fun decisions(dir: File) =
        File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)
            .resolve(AgentContract.DECISIONS_FILE).readLines()

    /** The classes whose runner is judged and whose tests cannot reach Beta. */
    private val leftOut = setOf("dev.sample.AlphaTest", "dev.sample.AlphaMockitoTest", "dev.sample.AlphaLegacyTest")

    private val everyClass = setOf(
        "dev.sample.AlphaTest", "dev.sample.BetaTest", "dev.sample.AlphaMockitoTest", "dev.sample.AlphaLegacyTest",
        "dev.sample.AlphaLegacyIgnoredTest", "dev.sample.ParameterizedAlphaTest", "dev.sample.AllIgnoredTest",
    )

    private fun mapDir(dir: File) = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)

    private fun captured(dir: File, script: String = build) {
        build(dir, "build.gradle.kts" to script, *sources)
        committed(dir)
        // Each class recorded in a JVM of its own, so no class shares a JVM with Beta's first load.
        runner(dir, "test", "-Pyoriwake.isolatedCapture").build()
        File(dir, "build/test-results").deleteRecursively()
        changeBeta(dir)
    }

    @Test
    fun `a class whose tests a change cannot reach is left out when its runner is judged, and runs otherwise`(
        @TempDir dir: File,
    ) {
        captured(dir)

        val output = runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.narrowPlainRunners").build().output

        // AlphaTest, AlphaMockitoTest and AlphaLegacyTest are left out: no report entry, as for a
        // class Gradle's own filter empties. A runner not on the list, and a class with nothing to
        // judge, run.
        val lines = decisions(dir)
        assertEquals(
            setOf(
                "dev.sample.BetaTest", "dev.sample.AlphaLegacyIgnoredTest", "dev.sample.ParameterizedAlphaTest",
                "dev.sample.AllIgnoredTest",
            ),
            ranTests(dir),
            lines.joinToString("\n"),
        )
        assertContains(lines, "#!${AgentContract.PLAIN_FILTER_NOTE}\t${AgentContract.PLAIN_FILTER_HOOKED}")
        assertContains(lines, "#!${AgentContract.CLASS_RUNS_WHOLE_NOTE}\t${AgentContract.CLASS_RUNS_WHOLE_JUNIT4_RUNNER}=1")
        val rows = lines.filter { !it.startsWith("#") }.associate { it.split("\t").let { cells -> cells[0] to cells[2] } }
        assertEquals("SKIPPED", rows["[engine:junit4]/[class:dev.sample.AlphaTest]/[method:a]"])
        assertEquals("SKIPPED", rows["[engine:junit4]/[class:dev.sample.AlphaMockitoTest]/[method:m]"])
        assertEquals("SKIPPED", rows["[engine:junit4]/[class:dev.sample.AlphaLegacyTest]/[method:testTwice]"])
        assertEquals("REACHES_CHANGE", rows["[engine:junit4]/[class:dev.sample.BetaTest]/[method:b]"])
        assertEquals("CLASS_RUNS_WHOLE", rows["[engine:junit4]/[class:dev.sample.ParameterizedAlphaTest]/[method:p[0]]"])
        // An ignored test is never judged: it never runs, and the map never records it.
        assertEquals(null, rows["[engine:junit4]/[class:dev.sample.AlphaTest]/[method:ignored]"])
        assertEquals("REACHES_CHANGE", rows["[engine:junit4]/[class:dev.sample.AlphaLegacyIgnoredTest]/[method:testThrice]"])
        assertContains(output, "plain JUnit 4 selects whole classes: 3 left out")
    }

    @Test
    fun `without narrowPlainRunners, every class runs`(@TempDir dir: File) {
        captured(dir)

        runner(dir, "test", "-Pyoriwake.select").build()

        assertEquals(everyClass, ranTests(dir))
    }

    @Test
    fun `an observing run records what it would leave out and leaves out nothing`(@TempDir dir: File) {
        captured(dir)

        runner(dir, "test", "-Pyoriwake.observe", "-Pyoriwake.narrowPlainRunners").build()

        assertEquals(everyClass, ranTests(dir))
        // The agent judged every class, as a selecting run would have.
        assertContains(decisions(dir), "#!${AgentContract.PLAIN_FILTER_NOTE}\t${AgentContract.PLAIN_FILTER_HOOKED}")
    }

    @Test
    fun `a selecting run that names its tests refuses, and the class it names runs whole`(@TempDir dir: File) {
        captured(dir)

        runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.narrowPlainRunners", "--tests", "dev.sample.AlphaTest")
            .build()

        // Gradle's own filter keeps the rest out before the class is ever judged.
        assertEquals(setOf("dev.sample.AlphaTest"), ranTests(dir))
        val lines = decisions(dir)
        assertEquals(setOf("DAEMON_REFUSED"), lines.filter { !it.startsWith("#") }.map { it.split("\t")[2] }.toSet())
        assertContains(lines, "#!${AgentContract.REFUSAL_KIND_NOTE}\ttests-named")
    }

    @Test
    fun `the build's narrowPlainRunners alone narrows, and the command line turns it off`(@TempDir dir: File) {
        captured(dir, build + "\nyoriwake { narrowPlainRunners = true }\n")

        runner(dir, "test", "-Pyoriwake.select").build()
        assertEquals(everyClass - leftOut, ranTests(dir))

        File(dir, "build/test-results").deleteRecursively()
        runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.narrowPlainRunners=false").build()
        assertEquals(everyClass, ranTests(dir))
    }

    @Test
    fun `a recording run with the flag hands the agent nothing to judge`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to build, *sources)
        committed(dir)

        runner(dir, "test", "-Pyoriwake.narrowPlainRunners").build()

        assertEquals(everyClass, ranTests(dir))
        assertFalse(File(mapDir(dir), AgentContract.DECISIONS_FILE).exists())
    }

    @Test
    fun `a flag value that is not a boolean fails the build naming it`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to build, *sources)

        val output = runner(dir, "test", "-Pyoriwake.narrowPlainRunners=yes").buildAndFail().output

        assertContains(output, "yoriwake.narrowPlainRunners")
    }

    @Test
    fun `the audit stops asking for the JUnit Platform once a selecting run narrowed plain JUnit 4`(@TempDir dir: File) {
        captured(dir)

        runner(dir, "test", "-Pyoriwake.select").build()
        runner(dir, "yoriwakeAuditTest").build()
        val unflagged = File(mapDir(dir), "audit.json").readText()
        runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.narrowPlainRunners").build()
        runner(dir, "yoriwakeAuditTest").build()
        val flagged = File(mapDir(dir), "audit.json").readText()

        assertContains(unflagged, "not-on-junit-platform")
        assertContains(unflagged, "narrowPlainRunners")
        assertFalse("not-on-junit-platform" in flagged, File(mapDir(dir), "task-facts").readText() + flagged)
    }

    @Test
    fun `explain names the classes it predicts left out and says their runners are judged later`(@TempDir dir: File) {
        captured(dir)

        val output = runner(dir, "yoriwakeExplainTest", "-Pyoriwake.narrowPlainRunners").build().output

        assertContains(output, "leaves out whole classes: 4 of 6 known classes")
        assertContains(output, "Every test of a class kept runs")
        leftOut.forEach { assertContains(output, it) }
        assertContains(output, "may keep more")
    }

    @Test
    fun `a TestNG task with the flag runs every test and says TestNG is not covered`(@TempDir dir: File) {
        build(
            dir,
            "build.gradle.kts" to build.replace("useJUnit()", "useTestNG()")
                .replace("testImplementation(\"junit:junit:4.13.2\")", "testImplementation(\"org.testng:testng:7.10.2\")"),
            sources[0], sources[1],
            "src/test/java/dev/sample/AlphaNgTest.java" to """
                package dev.sample;
                public class AlphaNgTest {
                    @org.testng.annotations.Test public void a() { org.testng.Assert.assertEquals(new Alpha().twice(1), 2); }
                }
            """.trimIndent(),
            "src/test/java/dev/sample/BetaNgTest.java" to """
                package dev.sample;
                public class BetaNgTest {
                    @org.testng.annotations.Test public void b() { org.testng.Assert.assertEquals(new Beta().thrice(1), 3); }
                }
            """.trimIndent(),
        )
        committed(dir)
        runner(dir, "test").build()
        File(dir, "build/test-results").deleteRecursively()
        changeBeta(dir)

        val output = runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.narrowPlainRunners").build().output

        assertEquals(setOf("dev.sample.AlphaNgTest", "dev.sample.BetaNgTest"), ranTests(dir))
        assertContains(output, "runs TestNG, which narrowPlainRunners does not cover")
    }
}
