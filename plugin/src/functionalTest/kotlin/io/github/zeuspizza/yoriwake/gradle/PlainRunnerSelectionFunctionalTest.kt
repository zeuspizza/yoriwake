package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * Plain JUnit 4, off the JUnit Platform: a selecting run that names `junit4` to the agent leaves out
 * a whole class only when its runner runs exactly its own methods and every one is skipped. A class
 * left out takes Gradle's own way out for a class its filters emptied, so it leaves no report entry.
 */
class PlainRunnerSelectionFunctionalTest : FunctionalTestSupport() {

    /** The agent is told to judge plain JUnit 4 only when the run passes `-Pnarrow`. */
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
        val narrow = providers.gradleProperty("narrow").isPresent
        tasks.test {
            useJUnit()
            if (narrow) systemProperty("${AgentContract.NARROW_PLAIN_RUNNERS_PROPERTY}", "${AgentContract.NARROW_PLAIN_JUNIT4}")
        }
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

    private val everyClass = setOf(
        "dev.sample.AlphaTest", "dev.sample.BetaTest", "dev.sample.AlphaMockitoTest", "dev.sample.AlphaLegacyTest",
        "dev.sample.AlphaLegacyIgnoredTest", "dev.sample.ParameterizedAlphaTest", "dev.sample.AllIgnoredTest",
    )

    private fun captured(dir: File) {
        build(dir, "build.gradle.kts" to build, *sources)
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

        val output = runner(dir, "test", "-Pyoriwake.select", "-Pnarrow").build().output

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
    fun `without the agent told to judge plain JUnit 4, every class runs`(@TempDir dir: File) {
        captured(dir)

        runner(dir, "test", "-Pyoriwake.select").build()

        assertEquals(everyClass, ranTests(dir))
    }

    @Test
    fun `an observing run records what it would leave out and leaves out nothing`(@TempDir dir: File) {
        captured(dir)

        runner(dir, "test", "-Pyoriwake.observe", "-Pnarrow").build()

        assertEquals(everyClass, ranTests(dir))
    }

    @Test
    fun `a refused selecting run leaves no class out`(@TempDir dir: File) {
        captured(dir)

        runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.fullRun", "-Pnarrow").build()

        assertEquals(everyClass, ranTests(dir))
        val rows = decisions(dir).filter { !it.startsWith("#") }.map { it.split("\t")[2] }.toSet()
        assertEquals(setOf("DAEMON_REFUSED"), rows)
    }
}
