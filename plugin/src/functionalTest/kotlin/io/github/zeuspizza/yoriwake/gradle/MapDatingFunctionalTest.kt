package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.gradle.capture.CoverageDecoder
import io.github.zeuspizza.yoriwake.gradle.change.WorkingTree
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** When a capture dates the map, and the runs that must leave it undated. */
class MapDatingFunctionalTest : FunctionalTestSupport() {

    @Test
    fun `a fail-fast capture leaves the map byte-identical`(@TempDir dir: File) {
        // Byte-identical but for the failure it saw, which only ever runs a test.
        build(
            dir, "build.gradle.kts" to actingBuild(), oneClass, oneTest,
            actingTest("throw new AssertionError(\"fails\");"),
        )
        committed(dir)
        runner(dir, "test").build()
        val before = mapFiles(dir)

        val output = runner(dir, "test", "--fail-fast", "-Pact").buildAndFail().output

        assertContains(output, "--fail-fast, so this run's coverage was not kept and the map is as it was")
        val after = mapFiles(dir)
        assertEquals(before - AgentContract.COVERAGE_FILE, after - AgentContract.COVERAGE_FILE)
        val lines = { files: Map<String, List<Byte>> ->
            String(files.getValue(AgentContract.COVERAGE_FILE).toByteArray()).lines().filter(String::isNotBlank)
        }
        assertEquals(lines(before).filterNot { "ZActTest" in it }, lines(after).filterNot { "ZActTest" in it })
        assertTrue(lines(after).single { "ZActTest" in it && "[method:" in it }.startsWith("FAILED"))
    }

    @Test
    fun `a fail-fast capture does not date the map`(@TempDir dir: File) {
        // It stops at the first failure and keeps the older records of every test it never reached;
        // stamped as current, the next run would never widen across the commits they went stale in.
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest)
        ignoreBuildOutputs(dir)
        git(dir, "init")
        git(dir, "add", ".")
        git(dir, "-c", "user.email=t@e.com", "-c", "user.name=t", "commit", "-m", "base")

        runner(dir, "test", "--fail-fast").build()
        val mapDir = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)
        assertFalse(File(mapDir, CoverageDecoder.CAPTURE_COMMIT_FILE).isFile, "a fail-fast run dated the map")

        runner(dir, "test", "--rerun-tasks").build()
        assertTrue(File(mapDir, CoverageDecoder.CAPTURE_COMMIT_FILE).isFile, "an ordinary capture did not")
    }

    @Test
    fun `a selecting run forced to run everything dates the map, and a narrowed one does not`(
        @TempDir dir: File,
    ) {
        // A selecting run forced to run everything captures a complete map and must date it, or the
        // widened base only grows and every later run forces and instruments.
        // The other side: a run that executed a fraction of the suite must never date the map,
        // because the records it did not re-observe are as old as they were.
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest, secondClass, secondTest, classOrderByName)
        ignoreBuildOutputs(dir)
        git(dir, "init")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit",
            "--allow-empty", "-m", "base")
        commit(dir, "sample")

        runner(dir, "test").build()
        val mapDir = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)
        val capturedAt = File(mapDir, CoverageDecoder.CAPTURE_COMMIT_FILE)
        val first = capturedAt.readText().trim()

        // A class the map has never seen: the run forces, and there is something to learn, so it
        // captures.
        File(dir, "src/main/java/dev/sample/Gamma.java").also { it.parentFile.mkdirs() }.writeText(
            """
            package dev.sample;
            public class Gamma { public int quad(int n) { return n * 4; } }
            """.trimIndent()
        )
        File(dir, "src/test/java/dev/sample/GammaTest.java").also { it.parentFile.mkdirs() }.writeText(
            """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class GammaTest {
                @Test void passes() { assertEquals(4, new Gamma().quad(1)); }
            }
            """.trimIndent()
        )
        commit(dir, "a class the map has never seen")
        File(dir, "build/test-results").deleteRecursively()

        val forced = runner(dir, "test", "-Pyoriwake.select").build().output

        assertContains(forced, "so this run also captures")
        assertEquals(
            setOf("dev.sample.AlphaTest", "dev.sample.BetaTest", "dev.sample.GammaTest"),
            ranTests(dir),
            "the map does not know Gamma, so this run must be a full one",
        )
        val second = capturedAt.readText().trim()
        assertTrue(
            second != first,
            "a selecting run that executed the WHOLE suite and captured it must date the map. " +
                "Left at $first, the widened base only ever grows and every later run forces.",
        )

        // And the property that must survive the fix. Gamma is a class the map now knows, so this
        // run narrows -- and a run that executed a fraction of the suite may not date anything.
        // Gamma rather than Alpha: GammaTest runs last, and a change to Alpha would also select
        // every test that ran after Alpha was first loaded, which here is the whole suite.
        File(dir, "src/main/java/dev/sample/Gamma.java").writeText(
            """
            package dev.sample;
            public class Gamma { public int quad(int n) { return n + n + n + n; } }
            """.trimIndent()
        )
        commit(dir, "a class the map does know")
        File(dir, "build/test-results").deleteRecursively()

        val narrowed = runner(dir, "test", "-Pyoriwake.select").build().output

        assertContains(narrowed, "narrowing, so nothing is instrumented")
        assertFalse(narrowed.contains("was not kept"), "a narrowed selecting run is not a capture: $narrowed")
        assertEquals(setOf("dev.sample.GammaTest"), ranTests(dir))
        assertEquals(
            second,
            capturedAt.readText().trim(),
            "a run that executed a fraction of the suite must never date the map",
        )
    }

    private fun head(dir: File): String =
        ProcessBuilder("git", "rev-parse", "HEAD").directory(dir).start()
            .inputStream.bufferedReader().readText().trim()

    private fun mapDirOf(dir: File): File = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)

    private fun captureStamp(dir: File): String? =
        File(mapDirOf(dir), CoverageDecoder.CAPTURE_COMMIT_FILE).takeIf(File::isFile)?.readText()?.trim()

    @Test
    fun `a capture whose test JVM halts mid-plan does not date the map`(@TempDir dir: File) {
        // It leaves records that merge cleanly, and the older records of every test it never
        // reached survive the merge. Dated at HEAD, those would read as current, and a later run
        // would stop widening across the commits they went stale in.
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest)
        committed(dir)
        runner(dir, "test").build()
        val complete = head(dir)
        assertEquals(complete, captureStamp(dir))

        File(dir, "src/test/java/dev/sample/HaltTest.java").writeText(
            """
            package dev.sample;
            import org.junit.jupiter.api.*;
            @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
            class HaltTest {
                // More records than the writer buffers, so some reach the disk before the JVM dies:
                // without them there is nothing to decode and nothing to date.
                @Order(1) @RepeatedTest(600) void many() { new Alpha().twice(1); }
                @Order(2) @Test void dies() { Runtime.getRuntime().halt(1); }
            }
            """.trimIndent()
        )
        commit(dir, "a test that halts the JVM")
        val before = mapFiles(dir)
        val output = runner(dir, "test").buildAndFail().output

        assertEquals(complete, captureStamp(dir), "a capture whose JVM died mid-plan dated the map")
        assertContains(output, "did not finish its run")
        assertContains(output, "did not finish, so this run's coverage was not kept and the map is as it was")
        assertEquals(before, mapFiles(dir), "a capture whose JVM died mid-plan changed the map")
    }

    @Test
    fun `a plain JUnit 4 capture whose JVM exits mid-run does not date the map`(@TempDir dir: File) {
        // The shutdown hook flushes every record on System.exit, but it cannot tell an exit mid-run
        // from the end of one, so it must not call the run complete.
        build(dir, "build.gradle.kts" to junit4Build, oneClass, betaClass, *junit4Tests)
        committed(dir)
        runner(dir, "test").build()
        val complete = head(dir)
        assertEquals(complete, captureStamp(dir))

        File(dir, "src/test/java/dev/sample/ZExitTest.java").writeText(
            """
            package dev.sample;
            import org.junit.Test;
            public class ZExitTest {
                @Test public void exits() { new Alpha().twice(1); System.exit(1); }
            }
            """.trimIndent()
        )
        commit(dir, "a test that exits the JVM")
        val output = runner(dir, "test").buildAndFail().output

        assertEquals(complete, captureStamp(dir), "a capture whose JVM exited mid-run dated the map")
        assertContains(output, "did not finish its run")
    }

    /** A clean full capture on this build dates the map, and every worker says it finished. */
    private fun assertAnOrdinaryCaptureDates(dir: File) {
        committed(dir)
        runner(dir, "test").build()

        assertEquals(head(dir), captureStamp(dir), "an ordinary capture did not date the map")
        val workers = CoverageDecoder.recordsDir(mapDirOf(dir)).listFiles().orEmpty()
            .filter { it.name.startsWith("worker-") }
        assertTrue(workers.isNotEmpty(), "nothing was captured, so nothing was shown to finish")
        assertEquals(emptyList(), CoverageDecoder.unfinishedWorkers(mapDirOf(dir)))
    }

    @Test
    fun `an ordinary JUnit Platform capture over several forks still dates the map`(@TempDir dir: File) {
        build(
            dir,
            "build.gradle.kts" to minimalBuild.replace(
                "tasks.test { useJUnitPlatform() }",
                "tasks.test { useJUnitPlatform(); maxParallelForks = 2 }",
            ),
            oneClass, oneTest, secondClass, secondTest,
        )
        assertAnOrdinaryCaptureDates(dir)
    }

    @Test
    fun `an ordinary plain JUnit 4 capture, nested runs and all, still dates the map`(@TempDir dir: File) {
        // Gradle's own JUnit 4 runner, and a test that drives JUnitCore inside itself, whose own
        // run-finished must not be mistaken for the worker's.
        build(
            dir, "build.gradle.kts" to junit4Build, oneClass, betaClass, *junit4Tests,
            "src/test/java/dev/sample/InnerCase.java" to """
                package dev.sample;
                import org.junit.Test;
                import static org.junit.Assert.assertEquals;
                public class InnerCase {
                    @Test public void uses_alpha() { assertEquals(2, new Alpha().twice(1)); }
                }
            """.trimIndent(),
            "src/test/java/dev/sample/RunnerTest.java" to """
                package dev.sample;
                import org.junit.Test;
                import org.junit.runner.JUnitCore;
                import static org.junit.Assert.assertTrue;
                public class RunnerTest {
                    @Test public void runs_the_inner_case() {
                        assertTrue(JUnitCore.runClasses(InnerCase.class).wasSuccessful());
                    }
                }
            """.trimIndent(),
        )
        assertAnOrdinaryCaptureDates(dir)
    }

    @Test
    fun `an ordinary vintage capture still dates the map`(@TempDir dir: File) {
        build(
            dir,
            "build.gradle.kts" to """
                plugins {
                    java
                    jacoco
                    id("io.github.zeuspizza.yoriwake")
                }
                repositories { mavenCentral() }
                dependencies {
                    testImplementation("junit:junit:4.13.2")
                    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.11.4")
                    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
                }
                tasks.test { useJUnitPlatform() }
            """.trimIndent(),
            oneClass, betaClass, *junit4Tests,
        )
        assertAnOrdinaryCaptureDates(dir)
    }

    @Test
    fun `an ordinary TestNG capture still dates the map`(@TempDir dir: File) {
        build(
            dir,
            "build.gradle.kts" to """
                plugins {
                    java
                    jacoco
                    id("io.github.zeuspizza.yoriwake")
                }
                repositories { mavenCentral() }
                dependencies { testImplementation("org.testng:testng:7.10.2") }
                tasks.test { useTestNG() }
            """.trimIndent(),
            oneClass,
            "src/test/java/dev/sample/AlphaTest.java" to """
                package dev.sample;
                import org.testng.annotations.Test;
                import static org.testng.Assert.assertEquals;
                public class AlphaTest {
                    @Test public void passes() { assertEquals(new Alpha().twice(1), 2); }
                }
            """.trimIndent(),
        )
        assertAnOrdinaryCaptureDates(dir)
    }

    @Test
    fun `a class whose setup fails at a dated capture still runs on the next unrelated change`(
        @TempDir dir: File,
    ) {
        // A throwing @BeforeAll reports none of its class's tests. Their older SUCCESSFUL records
        // survived the capture that dated the map past the change that broke them, so a later
        // change that does not reach them skipped a class that still fails.
        build(
            dir, "build.gradle.kts" to minimalBuild, oneClass, secondClass, secondTest, classOrderByName,
            "src/test/java/dev/sample/AlphaTest.java" to """
                package dev.sample;
                import org.junit.jupiter.api.*;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class AlphaTest {
                    @BeforeAll static void setUp() {
                        if (new Alpha().twice(1) != 2) throw new IllegalStateException("broken");
                    }
                    @Test void passes() { assertEquals(2, 1 + 1); }
                }
            """.trimIndent(),
        )
        committed(dir)
        runner(dir, "test").build()

        File(dir, "src/main/java/dev/sample/Alpha.java").writeText(
            """
            package dev.sample;
            public class Alpha { public int twice(int n) { return n * 3; } }
            """.trimIndent()
        )
        commit(dir, "breaks AlphaTest's setup")
        runner(dir, "test").buildAndFail()
        assertEquals(head(dir), captureStamp(dir), "the capture that saw the failure dates the map")

        changeBeta(dir)
        commit(dir, "a change AlphaTest does not reach")
        File(dir, "build/test-results").deleteRecursively()
        runner(dir, "test", "-Pyoriwake.select").buildAndFail()

        assertContains(ranTests(dir), "dev.sample.AlphaTest", "a class that still fails was skipped")
    }

    /**
     * A captured sample whose AlphaTest breaks only under `-Pbroken`, run once broken on its own by
     * `--tests` (which does not date the map), and then under selection after a change it does not
     * reach. What it failed with must make it run again.
     */
    private fun failsAloneThenAnUnrelatedChange(dir: File, alphaTest: String) {
        build(
            dir,
            "build.gradle.kts" to minimalBuild.replace(
                "tasks.test { useJUnitPlatform() }",
                "tasks.test { useJUnitPlatform(); " +
                    "systemProperty(\"sample.broken\", project.hasProperty(\"broken\").toString()) }",
            ),
            oneClass, secondClass, secondTest, classOrderByName,
            "src/test/java/dev/sample/AlphaTest.java" to alphaTest,
        )
        committed(dir)
        runner(dir, "test").build()
        runner(dir, "test", "-Pbroken", "--tests", "dev.sample.AlphaTest").buildAndFail()

        changeBeta(dir)
        commit(dir, "a change AlphaTest does not reach")
        File(dir, "build/test-results").deleteRecursively()
        runner(dir, "test", "-Pbroken", "-Pyoriwake.select").buildAndFail()

        assertContains(ranTests(dir), "dev.sample.AlphaTest", "a test that failed last run was skipped")
    }

    @Test
    fun `a parameterized test whose argument source throws still runs on the next unrelated change`(
        @TempDir dir: File,
    ) {
        // Its source throwing leaves the template failed with no invocation beneath it, and the
        // older SUCCESSFUL invocations were all the map knew about it.
        failsAloneThenAnUnrelatedChange(
            dir,
            """
            package dev.sample;
            import java.util.stream.IntStream;
            import org.junit.jupiter.params.ParameterizedTest;
            import org.junit.jupiter.params.provider.MethodSource;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class AlphaTest {
                static IntStream cases() {
                    if (Boolean.getBoolean("sample.broken")) throw new IllegalStateException("broken");
                    return IntStream.of(1, 2);
                }
                @ParameterizedTest @MethodSource("cases") void passes(int n) { assertEquals(n * 2, n + n); }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `a class whose teardown fails after its tests passed still runs on the next unrelated change`(
        @TempDir dir: File,
    ) {
        // Its test is recorded SUCCESSFUL and then FAILED by the class around it, in one capture.
        failsAloneThenAnUnrelatedChange(
            dir,
            """
            package dev.sample;
            import org.junit.jupiter.api.*;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class AlphaTest {
                @AfterAll static void tearDown() {
                    if (Boolean.getBoolean("sample.broken")) throw new IllegalStateException("broken");
                }
                @Test void passes() { assertEquals(2, 1 + 1); }
            }
            """.trimIndent(),
        )
    }

    // A capture that cannot date the map leaves it as it was. ZActTest acts on the repository during
    // the run only under -Pact, so the same sources capture cleanly first.

    /** Every file the decode writes, by name, with its bytes. */
    private fun mapFiles(dir: File): Map<String, List<Byte>> = listOf(
        AgentContract.COVERAGE_FILE, AgentContract.POSITIONS_FILE, AgentContract.FIRST_TOUCH_FILE,
        AgentContract.NAMED_TOUCH_FILE, AgentContract.JVM_MODE_FILE, AgentContract.SCOPE_FILE,
        AgentContract.EFFECTIVE_SCOPE_FILE, AgentContract.MAP_SCHEMA_VERSION_FILE, AgentContract.LOADED_FILE,
        AgentContract.LOADED_PROVENANCE_FILE, AgentContract.LOADED_SCOPE_FILE, CoverageDecoder.CAPTURE_COMMIT_FILE,
        CoverageDecoder.CONSTANTS_FILE, CoverageDecoder.CLASS_DIGESTS_FILE, CoverageDecoder.ANNOTATION_DIGESTS_FILE,
        WorkingTree.SNAPSHOT_FILE,
    ).mapNotNull { name -> File(mapDirOf(dir), name).takeIf(File::isFile)?.let { name to it.readBytes().toList() } }
        .toMap()

    private fun actingBuild(extra: String = "") = minimalBuild.replace(
        "tasks.test { useJUnitPlatform() }",
        "tasks.test { useJUnitPlatform(); systemProperty(\"act\", project.hasProperty(\"act\").toString()) }\n$extra",
    )

    /** A test that runs [action] (Java statements) during the run, under `-Pact` only. */
    private fun actingTest(action: String) = "src/test/java/dev/sample/ZActTest.java" to """
        package dev.sample;
        import java.util.*;
        import org.junit.jupiter.api.Test;
        class ZActTest {
            static void git(String... args) throws Exception {
                List<String> command = new ArrayList<>(List.of("git", "-c", "user.email=t@e.com", "-c", "user.name=t"));
                command.addAll(List.of(args));
                if (new ProcessBuilder(command).inheritIO().start().waitFor() != 0) {
                    throw new IllegalStateException(String.join(" ", args));
                }
            }
            @Test void acts() throws Exception { if (Boolean.getBoolean("act")) { $action } }
        }
    """.trimIndent()

    /** A repository whose HEAD keeps no reflog, so only the HEAD comparison can see a commit. */
    private fun committedWithoutReflogs(dir: File) {
        ignoreBuildOutputs(dir)
        git(dir, "init")
        git(dir, "config", "core.logAllRefUpdates", "false")
        commit(dir, "base")
    }

    @Test
    fun `a commit during a capture in a repository without reflogs leaves the map as it was`(@TempDir dir: File) {
        build(
            dir, "build.gradle.kts" to actingBuild(), oneClass, oneTest,
            actingTest("java.nio.file.Files.writeString(java.nio.file.Path.of(\"touched.txt\"), \"x\"); git(\"add\", \"touched.txt\"); git(\"commit\", \"-m\", \"during the run\");"),
        )
        committedWithoutReflogs(dir)
        runner(dir, "test").build()
        val before = mapFiles(dir)

        val output = runner(dir, "test", "-Pact").build().output

        assertContains(output, "during the run, so this run's coverage was not kept and the map is as it was")
        assertContains(output, "HEAD moved from ")
        assertEquals(before, mapFiles(dir))
    }

    @Test
    fun `a checkout to another branch and back during a capture leaves the map as it was`(@TempDir dir: File) {
        build(
            dir, "build.gradle.kts" to actingBuild(), oneClass, oneTest,
            actingTest("git(\"checkout\", \"-b\", \"other\"); git(\"checkout\", \"-\");"),
        )
        committed(dir)
        runner(dir, "test").build()
        val before = mapFiles(dir)

        val output = runner(dir, "test", "-Pact").build().output

        assertContains(output, "HEAD's reflog changed during the run, so this run's coverage was not kept")
        assertEquals(before, mapFiles(dir))
    }

    @Test
    fun `a dating capture in a repository without reflogs still dates the map`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to actingBuild(), oneClass, oneTest)
        committedWithoutReflogs(dir)
        runner(dir, "test").build()
        changeAlpha(dir)
        commit(dir, "a change")

        runner(dir, "test").build()

        assertEquals(head(dir), captureStamp(dir))
    }

    @Test
    fun `a HEAD git cannot read at the decode leaves the map as it was`(@TempDir dir: File) {
        build(
            dir, "build.gradle.kts" to actingBuild(), oneClass, oneTest,
            actingTest("java.nio.file.Files.writeString(java.nio.file.Path.of(\".git/HEAD\"), \"not a ref\");"),
        )
        committed(dir)
        runner(dir, "test").build()
        val before = mapFiles(dir)

        val output = runner(dir, "test", "-Pact").build().output

        assertContains(output, "to something unreadable during the run")
        assertEquals(before, mapFiles(dir))
    }

    @Test
    fun `a capture whose start reading of HEAD is missing removes the stamp`(@TempDir dir: File) {
        build(
            dir, "build.gradle.kts" to actingBuild(), oneClass, oneTest,
            actingTest(
                "for (java.io.File map : new java.io.File(\".gradle/yoriwake\").listFiles()) " +
                    "new java.io.File(map, \"${io.github.zeuspizza.yoriwake.gradle.change.CaptureStart.PENDING_FILE}\").delete();",
            ),
        )
        committed(dir)
        runner(dir, "test").build()

        runner(dir, "test", "-Pact").build()
        assertEquals(null, captureStamp(dir), "a capture with no start reading kept a stamp")
        changeAlpha(dir)
        runner(dir, "test", "-Pyoriwake.select").build()

        assertEquals("stamp-absent", decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE])
    }

    @Test
    fun `a commit during compilation leaves the map as it was`(@TempDir dir: File) {
        // Read before compilation, HEAD names the commit the compiled classes came from.
        build(
            dir,
            "build.gradle.kts" to actingBuild(
                """
                tasks.compileJava {
                    val act = providers.gradleProperty("act").isPresent
                    val root = rootDir
                    doLast {
                        if (act) {
                            File(root, "touched.txt").writeText("x")
                            ProcessBuilder("git", "add", "touched.txt").directory(root).start().waitFor()
                            ProcessBuilder("git", "-c", "user.email=t@e.com", "-c", "user.name=t", "commit", "-m", "during compilation")
                                .directory(root).start().waitFor()
                        }
                    }
                }
                """.trimIndent(),
            ),
            oneClass, oneTest,
        )
        committed(dir)
        runner(dir, "test").build()
        val before = mapFiles(dir)
        changeAlpha(dir)
        commit(dir, "a change, so compilation runs")

        val output = runner(dir, "test", "-Pact").build().output

        assertContains(output, "HEAD moved from ")
        assertEquals(before, mapFiles(dir))
    }

    @Test
    fun `a filtered run on a task with no map writes no map file`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest, secondClass, secondTest)
        committed(dir)

        runner(dir, "test", "--tests", "dev.sample.AlphaTest").build()

        assertEquals(emptyMap(), mapFiles(dir))
    }

    // A path touched during a dating capture is unknown until the next one. AReaderTest reads a tracked
    // file and runs before anything loads Alpha, so only that file's unknown mark can select it once
    // Alpha changes.

    private val dataFile = "data.txt" to "one"

    private val readerTest = "src/test/java/dev/sample/AReaderTest.java" to """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertFalse;
        class AReaderTest {
            @Test void reads() throws Exception {
                assertFalse(java.nio.file.Files.readString(java.nio.file.Path.of("data.txt")).isEmpty());
            }
        }
    """.trimIndent()

    /** Captured once cleanly, then once under -Pact; returns what the selecting run after an Alpha change ran. */
    private fun readerAfterAnActingCapture(dir: File, action: String, between: () -> Unit = {}): Set<String> {
        build(
            dir, "build.gradle.kts" to actingBuild(), oneClass, oneTest, classOrderByName, dataFile, readerTest,
            actingTest(action),
        )
        committed(dir)
        runner(dir, "test").build()
        runner(dir, "test", "-Pact").build()
        between()
        changeAlpha(dir)
        File(dir, "build/test-results").deleteRecursively()
        runner(dir, "test", "-Pyoriwake.select").build()
        return ranTests(dir)
    }

    private val readData = "java.nio.file.Path.of(\"data.txt\")"

    @Test
    fun `a file edited during a capture stays in the change set until the next capture`(@TempDir dir: File) {
        // Restored before the selecting run, so only the mark the capture left can name it.
        val ran = readerAfterAnActingCapture(
            dir, "java.nio.file.Files.writeString($readData, \"two\");",
            between = { git(dir, "checkout", "--", "data.txt") },
        )

        assertTrue("dev.sample.AReaderTest" in ran, "ran $ran")
    }

    @Test
    fun `a tracked file rewritten and restored during a capture stays in the change set until the next capture`(
        @TempDir dir: File,
    ) {
        val ran = readerAfterAnActingCapture(
            dir,
            "java.nio.file.Files.writeString($readData, \"two\"); Thread.sleep(20); " +
                "java.nio.file.Files.writeString($readData, \"one\");",
        )

        assertTrue("dev.sample.AReaderTest" in ran, "ran $ran")
    }

    @Test
    fun `a stash and pop during a capture keeps the stashed file in the change set`(@TempDir dir: File) {
        build(
            dir, "build.gradle.kts" to actingBuild(), oneClass, oneTest, classOrderByName, dataFile, readerTest,
            actingTest("git(\"stash\"); git(\"stash\", \"pop\");"),
        )
        committed(dir)
        runner(dir, "test").build()
        File(dir, "data.txt").writeText("two")
        runner(dir, "test", "-Pact").build()
        changeAlpha(dir)
        File(dir, "build/test-results").deleteRecursively()
        runner(dir, "test", "-Pyoriwake.select").build()

        assertTrue("dev.sample.AReaderTest" in ranTests(dir), "ran ${ranTests(dir)}")
    }

    @Test
    fun `a resource edited after it was copied for the tests stays in the change set`(@TempDir dir: File) {
        // The tests read the copy processResources made; the edit after it is in the tree, not in what
        // they saw. Read before compilation, the tree's start tells the two apart.
        build(
            dir,
            "build.gradle.kts" to actingBuild(
                """
                tasks.processResources {
                    val act = providers.gradleProperty("act").isPresent
                    val source = file("src/main/resources/message.txt")
                    doLast { if (act) source.writeText("edited after the copy") }
                }
                """.trimIndent(),
            ),
            oneClass, oneTest, classOrderByName,
            "src/main/resources/message.txt" to "hello",
            "src/test/java/dev/sample/AResourceTest.java" to """
                package dev.sample;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertNotNull;
                class AResourceTest {
                    @Test void reads() { assertNotNull(getClass().getResource("/message.txt")); }
                }
            """.trimIndent(),
        )
        committed(dir)
        runner(dir, "test").build()
        File(dir, "src/main/resources/message.txt").writeText("hello again")
        commit(dir, "a resource change, so processResources runs")
        runner(dir, "test", "-Pact").build()
        git(dir, "checkout", "--", "src/main/resources/message.txt")
        changeAlpha(dir)
        File(dir, "build/test-results").deleteRecursively()
        runner(dir, "test", "-Pyoriwake.select").build()

        assertTrue("dev.sample.AResourceTest" in ranTests(dir), "ran ${ranTests(dir)}")
    }

    @Test
    fun `a capture whose start reading of the tree is missing writes no snapshot`(@TempDir dir: File) {
        build(
            dir, "build.gradle.kts" to actingBuild(), oneClass, oneTest,
            actingTest(
                "new java.io.File(\".gradle/${WorkingTree.STATS_PENDING_FILE}\").delete();",
            ),
        )
        committed(dir)
        runner(dir, "test").build()

        runner(dir, "test", "-Pact").build()
        assertFalse(File(mapDirOf(dir), WorkingTree.SNAPSHOT_FILE).isFile, "a capture with no start stats kept a snapshot")
        changeAlpha(dir)
        runner(dir, "test", "-Pyoriwake.select").build()

        assertEquals("snapshot-absent", decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE])
    }

    @Test
    fun `a capture whose start snapshot another build rewrote writes no snapshot`(@TempDir dir: File) {
        // As a build configuring the same project during the capture does: the file is there, but
        // its id is not the one this build wrote.
        build(
            dir, "build.gradle.kts" to actingBuild(), oneClass, oneTest,
            actingTest(
                "for (java.io.File map : new java.io.File(\".gradle/yoriwake\").listFiles()) { " +
                    "java.io.File start = new java.io.File(map, \"${WorkingTree.SNAPSHOT_FILE}.dated\"); " +
                    "String text = java.nio.file.Files.readString(start.toPath()); " +
                    "java.nio.file.Files.writeString(start.toPath(), " +
                    "\"yoriwake-start\\tanother\" + text.substring(text.indexOf('\\n'))); }",
            ),
        )
        committed(dir)
        runner(dir, "test").build()

        runner(dir, "test", "-Pact").build()
        assertFalse(File(mapDirOf(dir), WorkingTree.SNAPSHOT_FILE).isFile, "a capture read another build's start")
        changeAlpha(dir)
        runner(dir, "test", "-Pyoriwake.select").build()

        assertEquals("snapshot-absent", decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE])
    }

    @Test
    fun `a stash kept during a capture leaves the map as it was`(@TempDir dir: File) {
        build(
            dir, "build.gradle.kts" to actingBuild(), oneClass, oneTest, classOrderByName, dataFile, readerTest,
            actingTest("git(\"stash\");"),
        )
        committed(dir)
        runner(dir, "test").build()
        val before = mapFiles(dir)
        File(dir, "data.txt").writeText("two")

        val output = runner(dir, "test", "-Pact").build().output

        assertContains(output, "reflog changed during the run")
        assertEquals(before, mapFiles(dir))
    }

    @Test
    fun `a map from 0_1_0 is rebuilt before selection`(@TempDir dir: File) {
        // A 0.1.0 map may hold records a filtered or interrupted capture merged without dating them,
        // and nothing on disk tells them apart, so it is not selected from. The literal 6, never the
        // constant: the test is that this version is refused.
        // Beta changes and BetaTest runs last, so a map that is read narrows to BetaTest alone.
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest, secondClass, secondTest, classOrderByName)
        committed(dir)
        runner(dir, "test").build()
        File(mapDirOf(dir), AgentContract.MAP_SCHEMA_VERSION_FILE).writeText("6\n")
        changeBeta(dir)
        File(dir, "build/test-results").deleteRecursively()

        runner(dir, "test", "-Pyoriwake.select").build()

        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir))
        assertEquals("${AgentContract.MAP_SCHEMA_VERSION}", File(mapDirOf(dir), AgentContract.MAP_SCHEMA_VERSION_FILE).readText().trim())
    }

    private fun changeAlpha(dir: File) = File(dir, "src/main/java/dev/sample/Alpha.java").writeText(
        """
        package dev.sample;
        public class Alpha { public int twice(int n) { return n + n; } }
        """.trimIndent()
    )

    /** The test id of every record in the map. */
    private fun mapIds(dir: File): List<String> =
        File(mapDirOf(dir), AgentContract.COVERAGE_FILE).readLines().filter(String::isNotBlank)
            .map { it.substringAfterLast('\t') }

    private fun unionWritten(dir: File): Boolean = File(mapDirOf(dir), AgentContract.LOADED_FILE).isFile

    /**
     * Captures [sources] whole, then again under `-Pquick` with Beta changed, where the build's
     * filter leaves AlphaTest out. Returns the filtered run's output.
     */
    private fun capturedThenFiltered(dir: File, buildScript: String, vararg sources: Pair<String, String>): String {
        build(dir, "build.gradle.kts" to buildScript, oneClass, secondClass, *sources)
        committed(dir)
        runner(dir, "test", "-Pyoriwake.internal.loaded").build()
        assertEquals(head(dir), captureStamp(dir))
        assertTrue(mapIds(dir).any { "AlphaTest" in it }, "the whole capture did not record AlphaTest")
        assertTrue(unionWritten(dir), "the whole capture wrote no loaded-class union")

        changeBeta(dir)
        commit(dir, "a change")
        val output = runner(dir, "test", "-Pquick", "-Pyoriwake.internal.loaded").build().output

        // Dated, because it drops the records of what it left out, which then run as not in the
        // map; never the whole task, because a union of part of the suite would skip more.
        assertContains(output, "unfiltered=no")
        assertEquals(head(dir), captureStamp(dir), "a run under this filter did not date the map")
        assertEquals(emptyList(), mapIds(dir).filter { "AlphaTest" in it }, "what it left out kept its records")
        assertFalse(unionWritten(dir), "a filtered run wrote a loaded-class union")
        return output
    }

    @Test
    fun `a capture that leaves tests out by tag dates the map without them`(@TempDir dir: File) {
        val output = capturedThenFiltered(
            dir,
            minimalBuild.replace(
                "tasks.test { useJUnitPlatform() }",
                "tasks.test { useJUnitPlatform { if (project.hasProperty(\"quick\")) excludeTags(\"slow\") } }",
            ),
            secondTest,
            "src/test/java/dev/sample/AlphaTest.java" to """
                package dev.sample;
                import org.junit.jupiter.api.*;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                @Tag("slow")
                class AlphaTest {
                    @Test void passes() { assertEquals(2, new Alpha().twice(1)); }
                }
            """.trimIndent(),
        )
        assertContains(output, "excludeTags=[slow]")

        File(dir, "src/main/java/dev/sample/Beta.java").writeText(
            """
            package dev.sample;
            public class Beta { public int thrice(int n) { return 3 * n; } }
            """.trimIndent()
        )
        commit(dir, "a change AlphaTest does not reach")
        File(dir, "build/test-results").deleteRecursively()
        val selected = runner(dir, "test", "-Pyoriwake.select").build().output

        assertContains(ranTests(dir), "dev.sample.AlphaTest", "a test the filtered run left out was skipped")
        assertContains(selected, "not-in-map=1")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("frameworkFilters")
    fun `every framework filter dates the map but never speaks for the whole task`(
        filter: String,
        buildScript: String,
        alphaTest: String,
        betaTest: String,
        @TempDir dir: File,
    ) {
        val output = capturedThenFiltered(
            dir, buildScript,
            "src/test/java/dev/sample/AlphaTest.java" to alphaTest,
            "src/test/java/dev/sample/BetaTest.java" to betaTest,
            *FilterFixtures.support,
        )
        assertContains(output, "$filter=[")
    }

    /** [minimalBuild] with [filter] set on the task's test filter. */
    private fun scriptFiltered(filter: String) = minimalBuild.replace(
        "tasks.test { useJUnitPlatform() }",
        "tasks.test { useJUnitPlatform(); filter { $filter } }",
    )

    private val alphaLeftOut = scriptFiltered("excludeTestsMatching(\"*AlphaTest\")")

    /**
     * Captures Alpha and Beta with their tests under [buildScript], whose filter leaves AlphaTest
     * out: the map is dated and holds BetaTest alone.
     */
    private fun capturedUnderScriptFilter(dir: File, buildScript: String = alphaLeftOut) {
        build(dir, "build.gradle.kts" to buildScript, oneClass, oneTest, secondClass, secondTest, classOrderByName)
        committed(dir)

        val output = runner(dir, "test").build().output

        assertContains(output, "unfiltered=no")
        assertEquals(head(dir), captureStamp(dir), "a capture under a build-script filter did not date the map")
        assertTrue(mapIds(dir).any { "BetaTest" in it }, "the capture did not record BetaTest")
        assertEquals(emptyList(), mapIds(dir).filter { "AlphaTest" in it }, "what the filter left out is in the map")
    }

    @Test
    fun `a capture under a build-script filter dates the map without what it left out`(@TempDir dir: File) {
        capturedUnderScriptFilter(dir)
        changeBeta(dir)
        commit(dir, "a change only BetaTest reaches")
        File(dir, "build/test-results").deleteRecursively()

        val output = runner(dir, "test", "-Pyoriwake.select").build().output

        assertEquals(setOf("dev.sample.BetaTest"), ranTests(dir), output)
        assertEquals(null, decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], output)
        assertEquals(null, decisionNotes(dir)[AgentContract.FULL_RUN_KIND_NOTE], output)
    }

    @Test
    fun `a test a build-script filter left out runs once the filter is off`(@TempDir dir: File) {
        capturedThenFiltered(
            dir,
            minimalBuild.replace(
                "tasks.test { useJUnitPlatform() }",
                "tasks.test { useJUnitPlatform(); if (project.hasProperty(\"quick\")) filter { excludeTestsMatching(\"*AlphaTest\") } }",
            ),
            oneTest, secondTest,
        )

        File(dir, "src/main/java/dev/sample/Beta.java").writeText(
            """
            package dev.sample;
            public class Beta { public int thrice(int n) { return 3 * n; } }
            """.trimIndent()
        )
        commit(dir, "a change AlphaTest does not reach")
        File(dir, "build/test-results").deleteRecursively()
        val selected = runner(dir, "test", "-Pyoriwake.select").build().output

        assertContains(ranTests(dir), "dev.sample.AlphaTest", "a test the filtered run left out was skipped")
        assertContains(selected, "not-in-map=1")
    }

    @Test
    fun `a build-script filter removed after a filtered capture forces a run whose capture holds what it left out`(
        @TempDir dir: File,
    ) {
        capturedUnderScriptFilter(dir)
        // With a class change beside it, so the forced run captures rather than finding the map current.
        File(dir, "build.gradle.kts").writeText(minimalBuild)
        changeBeta(dir)
        commit(dir, "the filter is gone")
        File(dir, "build/test-results").deleteRecursively()

        val output = runner(dir, "test", "-Pyoriwake.select").build().output

        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir), output)
        assertEquals("unmappable-paths", decisionNotes(dir)[AgentContract.FULL_RUN_KIND_NOTE], output)
        assertEquals(head(dir), captureStamp(dir), output)
        assertTrue(mapIds(dir).any { "AlphaTest" in it }, "the run without the filter did not record AlphaTest")
    }

    @Test
    fun `a build-script filter widened after a filtered capture runs the test it now admits`(@TempDir dir: File) {
        capturedUnderScriptFilter(dir, scriptFiltered("includeTestsMatching(\"*BetaTest\")"))
        File(dir, "build.gradle.kts").writeText(
            scriptFiltered("includeTestsMatching(\"*BetaTest\"); includeTestsMatching(\"*AlphaTest\")"),
        )
        commit(dir, "the filter admits AlphaTest")
        File(dir, "build/test-results").deleteRecursively()

        val output = runner(dir, "test", "-Pyoriwake.select").build().output

        assertContains(ranTests(dir), "dev.sample.AlphaTest", output)
        assertEquals("unmappable-paths", decisionNotes(dir)[AgentContract.FULL_RUN_KIND_NOTE], output)
    }

    @Test
    fun `--tests over a build-script filter leaves the map's stamp as it was`(@TempDir dir: File) {
        capturedUnderScriptFilter(dir)
        val captured = head(dir)
        changeBeta(dir)
        commit(dir, "an unrelated change")

        val output = runner(dir, "test", "--tests", "*BetaTest").build().output

        assertEquals(captured, captureStamp(dir), output)
    }

    @Test
    fun `a pattern added after the build script ran leaves the map undated`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest, secondClass, secondTest, classOrderByName)
        committed(dir)
        runner(dir, "test").build()
        val captured = head(dir)
        changeBeta(dir)
        commit(dir, "an unrelated change")
        // Where an IDE's test launcher adds its pattern: after every build script, as the graph is ready.
        val launcher = File(dir, "build/launcher.init.gradle").apply {
            writeText(
                "gradle.taskGraph.whenReady { graph -> graph.allTasks.findAll { it instanceof Test }" +
                    ".each { it.filter.includeTest('dev.sample.BetaTest', null) } }\n",
            )
        }

        val output = runner(dir, "test", "--init-script", launcher.absolutePath).build().output

        assertEquals(setOf("dev.sample.BetaTest"), ranTests(dir), output)
        assertContains(output, "added after the build script ran")
        assertEquals(captured, captureStamp(dir), output)
    }

    @Test
    fun `an unfiltered capture dates the map over a reason an earlier filtered run left behind`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest, secondClass, secondTest, classOrderByName)
        committed(dir)
        runner(dir, "test").build()
        changeBeta(dir)
        commit(dir, "an unrelated change")
        // As a filtered run leaves it when its decode never runs.
        io.github.zeuspizza.yoriwake.gradle.wiring.undatedRunMarker(CoverageDecoder.recordsDir(mapDirOf(dir)))
            .writeText("filtered by an earlier run")

        val output = runner(dir, "test").build().output

        assertEquals(head(dir), captureStamp(dir), output)
    }

    @Test
    fun `a build-script filter naming one test dates the map with that test alone`(@TempDir dir: File) {
        capturedUnderScriptFilter(dir, scriptFiltered("includeTestsMatching(\"*BetaTest\")"))
        changeBeta(dir)
        commit(dir, "a change BetaTest reaches")
        File(dir, "build/test-results").deleteRecursively()

        val output = runner(dir, "test", "-Pyoriwake.select").build().output

        assertEquals(setOf("dev.sample.BetaTest"), ranTests(dir), output)
        assertEquals(null, decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], output)
    }

    private val alphaFastAndSlow = "src/test/java/dev/sample/AlphaTest.java" to """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class AlphaTest {
            @Test void fast() { assertEquals(2, new Alpha().twice(1)); }
            @Test void slow() { assertEquals(4, new Alpha().twice(2)); }
        }
    """.trimIndent()

    /** The AlphaTest methods the last run executed, read from its JUnit XML report. */
    private fun alphaMethodsRan(dir: File): Set<String> =
        File(dir, "build/test-results/test/TEST-dev.sample.AlphaTest.xml").takeIf(File::isFile)
            ?.let { report -> Regex("""<testcase name="([^"(]+)""").findAll(report.readText()).map { it.groupValues[1] }.toSet() }
            .orEmpty()

    @Test
    fun `a build-script filter on one method dates the map without that method`(@TempDir dir: File) {
        build(
            dir, "build.gradle.kts" to scriptFiltered("excludeTestsMatching(\"*AlphaTest.slow\")"),
            oneClass, alphaFastAndSlow, secondClass, secondTest, classOrderByName,
        )
        committed(dir)

        runner(dir, "test").build()

        assertEquals(head(dir), captureStamp(dir))
        assertTrue(mapIds(dir).any { "AlphaTest" in it && "fast()" in it }, "AlphaTest.fast is not in the map")
        assertEquals(emptyList(), mapIds(dir).filter { "slow()" in it }, "the method the filter left out is in the map")
    }

    @Test
    fun `a method a build-script filter left out runs once the filter is off`(@TempDir dir: File) {
        build(
            dir,
            "build.gradle.kts" to minimalBuild.replace(
                "tasks.test { useJUnitPlatform() }",
                "tasks.test { useJUnitPlatform(); if (project.hasProperty(\"quick\")) filter { excludeTestsMatching(\"*AlphaTest.slow\") } }",
            ),
            oneClass, alphaFastAndSlow, secondClass, secondTest, classOrderByName,
        )
        committed(dir)
        runner(dir, "test").build()
        runner(dir, "test", "-Pquick").build()
        assertEquals(emptyList(), mapIds(dir).filter { "slow()" in it }, "the filtered capture kept AlphaTest.slow")
        changeBeta(dir)
        commit(dir, "a change AlphaTest does not reach")
        File(dir, "build/test-results").deleteRecursively()

        val output = runner(dir, "test", "-Pyoriwake.select").build().output

        assertEquals(setOf("slow"), alphaMethodsRan(dir), output)
        assertContains(output, "not-in-map=1")
    }

    @Test
    fun `a fail-fast run under a build-script filter leaves the map's stamp as it was`(@TempDir dir: File) {
        capturedUnderScriptFilter(
            dir,
            minimalBuild.replace(
                "tasks.test { useJUnitPlatform() }",
                "tasks.test { useJUnitPlatform(); filter { excludeTestsMatching(\"*AlphaTest\") }; " +
                    "if (project.hasProperty(\"ff\")) failFast = true }",
            ),
        )
        val captured = head(dir)
        changeBeta(dir)
        commit(dir, "an unrelated change")

        val output = runner(dir, "test", "-Pff").build().output

        assertEquals(captured, captureStamp(dir), output)
    }

    @Test
    fun `a build-script filter added after an unfiltered capture forces a run whose capture dates the map within it`(
        @TempDir dir: File,
    ) {
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest, secondClass, secondTest, classOrderByName)
        committed(dir)
        runner(dir, "test").build()
        File(dir, "build.gradle.kts").writeText(alphaLeftOut)
        changeBeta(dir)
        commit(dir, "the filter leaves AlphaTest out")
        File(dir, "build/test-results").deleteRecursively()

        val output = runner(dir, "test", "-Pyoriwake.select").build().output

        assertEquals(setOf("dev.sample.BetaTest"), ranTests(dir), output)
        assertEquals("unmappable-paths", decisionNotes(dir)[AgentContract.FULL_RUN_KIND_NOTE], output)
        assertEquals(head(dir), captureStamp(dir), output)
        assertEquals(emptyList(), mapIds(dir).filter { "AlphaTest" in it }, "what the filter left out is in the map")
    }

    companion object {
        @JvmStatic
        fun frameworkFilters(): List<Arguments> = FilterFixtures.cases()
    }
}

/** One task per Gradle filter kind, each leaving AlphaTest out under `-Pquick` and keeping BetaTest. */
private object FilterFixtures {

    private fun buildScript(dependencies: String, framework: String) = """
        plugins {
            java
            jacoco
            id("io.github.zeuspizza.yoriwake")
        }
        repositories { mavenCentral() }
        dependencies {
            $dependencies
        }
        tasks.test { $framework }
    """.trimIndent()

    private val PLATFORM = """testImplementation(platform("org.junit:junit-bom:5.11.4"))
            testImplementation("org.junit.jupiter:junit-jupiter")
            testRuntimeOnly("org.junit.platform:junit-platform-launcher")"""
    private val VINTAGE = """$PLATFORM
            testImplementation("junit:junit:4.13.2")
            testRuntimeOnly("org.junit.vintage:junit-vintage-engine")"""
    private val JUNIT4 = """testImplementation("junit:junit:4.13.2")"""
    private val TESTNG = """testImplementation("org.testng:testng:7.10.2")"""

    private fun quick(framework: String, filter: String) =
        "$framework { if (project.hasProperty(\"quick\")) $filter }"

    private fun jupiter(name: String, uses: String, annotation: String = "") = """
        package dev.sample;
        import org.junit.jupiter.api.*;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        $annotation
        class $name {
            @Test void passes() { $uses }
        }
    """.trimIndent()

    private fun junit4(name: String, uses: String, annotation: String = "") = """
        package dev.sample;
        import org.junit.Test;
        import org.junit.experimental.categories.Category;
        import static org.junit.Assert.assertEquals;
        $annotation
        public class $name {
            @Test public void passes() { $uses }
        }
    """.trimIndent()

    private fun testng(name: String, uses: String, groups: String = "") = """
        package dev.sample;
        import org.testng.annotations.Test;
        import static org.testng.Assert.assertEquals;
        public class $name {
            @Test$groups public void passes() { $uses }
        }
    """.trimIndent()

    private const val ALPHA = "assertEquals(2, new Alpha().twice(1));"
    private const val BETA = "assertEquals(3, new Beta().thrice(1));"

    /** The JUnit 4 categories the category filters name. */
    val support = arrayOf(
        "src/test/java/dev/sample/Fast.java" to "package dev.sample;\npublic interface Fast {}",
        "src/test/java/dev/sample/Slow.java" to "package dev.sample;\npublic interface Slow {}",
    )

    fun cases(): List<Arguments> = listOf(
        Arguments.of(
            "includeTags", buildScript(PLATFORM, quick("useJUnitPlatform", "includeTags(\"fast\")")),
            jupiter("AlphaTest", ALPHA), jupiter("BetaTest", BETA, "@Tag(\"fast\")"),
        ),
        Arguments.of(
            "includeEngines", buildScript(VINTAGE, quick("useJUnitPlatform", "includeEngines(\"junit-jupiter\")")),
            junit4("AlphaTest", ALPHA), jupiter("BetaTest", BETA),
        ),
        Arguments.of(
            "excludeEngines", buildScript(VINTAGE, quick("useJUnitPlatform", "excludeEngines(\"junit-vintage\")")),
            junit4("AlphaTest", ALPHA), jupiter("BetaTest", BETA),
        ),
        Arguments.of(
            "includeCategories", buildScript(JUNIT4, quick("useJUnit", "includeCategories(\"dev.sample.Fast\")")),
            junit4("AlphaTest", ALPHA), junit4("BetaTest", BETA, "@Category(Fast.class)"),
        ),
        Arguments.of(
            "excludeCategories", buildScript(JUNIT4, quick("useJUnit", "excludeCategories(\"dev.sample.Slow\")")),
            junit4("AlphaTest", ALPHA, "@Category(Slow.class)"), junit4("BetaTest", BETA),
        ),
        Arguments.of(
            "includeGroups", buildScript(TESTNG, quick("useTestNG", "includeGroups(\"fast\")")),
            testng("AlphaTest", ALPHA), testng("BetaTest", BETA, "(groups = \"fast\")"),
        ),
        Arguments.of(
            "excludeGroups", buildScript(TESTNG, quick("useTestNG", "excludeGroups(\"slow\")")),
            testng("AlphaTest", ALPHA, "(groups = \"slow\")"), testng("BetaTest", BETA),
        ),
    )
}
