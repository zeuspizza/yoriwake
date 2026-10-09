package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.gradle.capture.CoverageDecoder
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `-Pyoriwake.observe`: every test runs and the map is captured as on a recording run, while the
 * test JVM records what a selecting run of the same build would have left out.
 */
class ObservationFunctionalTest : FunctionalTestSupport() {

    private val bothTests = setOf("dev.sample.AlphaTest", "dev.sample.BetaTest")
    private val alpha = "[engine:junit-jupiter]/[class:dev.sample.AlphaTest]/[method:passes()]"
    private val beta = "[engine:junit-jupiter]/[class:dev.sample.BetaTest]/[method:passes()]"

    @Test
    fun `an observing run executes every test and records what selection would have left out`(@TempDir dir: File) {
        captured(dir)
        changeBeta(dir)
        commit(dir, "change beta")
        val before = stamp(dir)

        val output = observe(dir).output

        assertEquals(bothTests, ranTests(dir), output)
        assertEquals(setOf("OBSERVING"), decisionReasons(dir).values.toSet(), output)
        assertTrue(decisionRows(dir).all { it[1] == "included" }, output)
        assertEquals(
            mapOf(alpha to listOf("excluded", "SKIPPED"), beta to listOf("included", "REACHES_CHANGE")),
            observations(dir),
            output,
        )
        assertEquals(AgentContract.RUN_FULL, decisionNotes(dir)[AgentContract.OUTCOME_NOTE], output)
        assertEquals(AgentContract.RUN_NARROWED, decisionNotes(dir)[AgentContract.OBSERVED_OUTCOME_NOTE], output)
        assertFalse("tests discovered" in output, output)
        assertNotEquals(before, stamp(dir), "the observing run did not date the map")
        assertEquals(head(dir), stamp(dir))
    }

    @Test
    fun `an observing run decides as a selecting run of the same state does, from the same change set`(@TempDir dir: File) {
        captured(dir)
        changeBeta(dir)
        commit(dir, "change beta")
        val map = mapDir(dir)
        val snapshot = File(dir.parentFile, "${dir.name}-map")
        map.copyRecursively(snapshot)

        runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD").build()
        val selected = decisionRows(dir).associate { it[0] to listOf(it[1], it[2]) }
        val selectedInputs = inputNotes(dir)
        val selectedChangeSet = changeSet(dir)
        map.deleteRecursively()
        snapshot.copyRecursively(map)
        val output = observe(dir).output

        assertEquals(selected, observations(dir), output)
        assertTrue(selected.values.any { it[0] == "excluded" }, "the selecting run left nothing out: $selected")
        assertEquals(selectedInputs, inputNotes(dir), output)
        assertEquals(selectedChangeSet, changeSet(dir), output)
    }

    @Test
    fun `an observing run writes the map a recording run writes on the same state`(@TempDir dir: File) {
        captured(dir, "-Pyoriwake.internal.loaded")
        changeBeta(dir)
        commit(dir, "change beta")
        val map = mapDir(dir)
        val snapshot = File(dir.parentFile, "${dir.name}-map")
        map.copyRecursively(snapshot)

        runner(dir, "test", "-Pyoriwake.internal.loaded").build()
        val recorded = mapFiles(dir)
        map.deleteRecursively()
        snapshot.copyRecursively(map)
        val output = observe(dir, "-Pyoriwake.internal.loaded").output

        assertTrue(AgentContract.LOADED_FILE in recorded, recorded.keys.toString())
        assertEquals(recorded, mapFiles(dir), output)
    }

    @Test
    fun `a pinned test observes as always-run`(@TempDir dir: File) {
        captured(dir)
        changeBeta(dir)
        commit(dir, "change beta")

        val output = observe(dir, "-Pyoriwake.alwaysRun=dev.sample.AlphaTest").output

        assertEquals(listOf("included", "ALWAYS_RUN"), observations(dir)[alpha], output)
        assertEquals(bothTests, ranTests(dir), output)
    }

    @Test
    fun `a run the plugin refuses observes a full run, naming the refusal`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest, secondClass, secondTest, classOrderByName)
        committed(dir)

        val output = observe(dir).output

        val notes = decisionNotes(dir)
        val refusal = checkNotNull(notes[AgentContract.REFUSAL_KIND_NOTE]) { output }
        assertEquals("full-run\tdaemon-refused\t$refusal", notes[AgentContract.OBSERVED_OUTCOME_NOTE], output)
        assertEquals(setOf("DAEMON_REFUSED"), observations(dir).values.map { it[1] }.toSet(), output)
        assertEquals(bothTests, ranTests(dir), output)
        assertTrue(File(mapDir(dir), AgentContract.COVERAGE_FILE).isFile, "the refused observing run captured nothing")
    }

    @Test
    fun `a full run asked for is the selection an observing run observes, and it still records`(@TempDir dir: File) {
        captured(dir)
        changeBeta(dir)
        commit(dir, "change beta")

        val output = observe(dir, "-Pyoriwake.fullRun").output

        assertEquals(
            "full-run\tdaemon-refused\tfull-run-requested",
            decisionNotes(dir)[AgentContract.OBSERVED_OUTCOME_NOTE],
            output,
        )
        assertEquals(setOf("included"), observations(dir).values.map { it[0] }.toSet(), output)
        assertEquals(head(dir), stamp(dir), "the observing run did not date the map")
    }

    @Test
    fun `a left-alone marker no decode consumed does not discard a declined observing run's capture`(@TempDir dir: File) {
        captured(dir)
        // As a declined run leaves it when its decode never runs: cancelled, or excluded with -x.
        File(mapDir(dir), "declined-left-alone.marker").writeText("left alone by an earlier run\n")
        changeBeta(dir)
        commit(dir, "change beta")

        val output = observe(dir, "-Pyoriwake.fullRun").output

        assertEquals(head(dir), stamp(dir), output)
        assertFalse("left alone by an earlier run" in output, output)
    }

    @Test
    fun `an observing run whose test task did no work reports that, never the previous run`(@TempDir dir: File) {
        captured(dir)
        changeBeta(dir)
        commit(dir, "change beta")
        observe(dir)
        // The first dates the map, which changes the test task's inputs; the second leaves them as they are.
        observe(dir)
        assertContains(report(dir), "\"outcomesRecorded\": true")

        val result = observe(dir)

        assertEquals(TaskOutcome.UP_TO_DATE, result.task(":test")?.outcome, result.output)
        assertContains(report(dir), "\"outcomesRecorded\": false")
        assertContains(report(dir), "the test task did no work")
    }

    @Test
    fun `tests named on an observing run run as named, observe a full run, and leave the map undated`(@TempDir dir: File) {
        captured(dir)
        changeBeta(dir)
        commit(dir, "change beta")
        val before = stamp(dir)

        val output = observe(dir, "--tests", "dev.sample.AlphaTest").output

        assertEquals(setOf("dev.sample.AlphaTest"), ranTests(dir), output)
        assertEquals(
            "full-run\tdaemon-refused\ttests-named",
            decisionNotes(dir)[AgentContract.OBSERVED_OUTCOME_NOTE],
            output,
        )
        assertEquals(before, stamp(dir), "a run of named tests dated the map")
    }

    @Test
    fun `an observing run with isolated capture records with a JVM per test class`(@TempDir dir: File) {
        captured(dir)
        changeBeta(dir)
        commit(dir, "change beta")

        val output = observe(dir, "-Pyoriwake.isolatedCapture").output

        assertContains(output, "fresh test JVM per test class")
        assertContains(File(mapDir(dir), AgentContract.JVM_MODE_FILE).readText(), AgentContract.MODE_ISOLATED)
    }

    @Test
    fun `observing and selecting together fail, naming both`(@TempDir dir: File) {
        captured(dir)

        val output = runner(dir, "test", "-Pyoriwake.observe", "-Pyoriwake.select").buildAndFail().output

        assertContains(output, "-Pyoriwake.select")
        assertContains(output, "-Pyoriwake.observe")
    }

    @Test
    fun `a failure selection would have kept fails the build as usual and is no would-be miss`(@TempDir dir: File) {
        captured(dir)
        breakBeta(dir)
        commit(dir, "break beta")
        val before = stamp(dir)

        val output = runner(dir, "test", "-Pyoriwake.observe", "-Pyoriwake.base=HEAD").buildAndFail().output

        assertEquals(bothTests, ranTests(dir), output)
        val report = report(dir)
        assertContains(report, "\"observedOutcome\": \"narrowed\"")
        assertContains(report, "\"failures\": 1,")
        assertContains(report, "\"failuresKept\": 1,")
        assertContains(report, "\"wouldBeMisses\": [],")
        assertContains(report, "\"wouldBeSkipped\": 1,")
        assertContains(report, "\"commit\": \"${head(dir)}\"")
        assertContains(report, "\"mapCaptureCommit\": \"$before\"")
        assertContains(output, "recorded test time")
        assertEquals(head(dir), stamp(dir), "the observing run did not date the map")
    }

    @Test
    fun `a failing test selection would have left out is a would-be miss`(@TempDir dir: File) {
        capturedWithEarlyTests(dir)
        breakBeta(dir)
        commit(dir, "break beta")

        val output = runner(dir, "test", "-Pyoriwake.observe", "-Pyoriwake.base=HEAD", "-PquxFail=true")
            .buildAndFail().output

        assertEquals(listOf("FAILED"), misses(dir).filterKeys { "QuxTest" in it }.values.toList(), output)
        assertTrue(misses(dir).keys.none { "BetaTest" in it }, output)
    }

    @Test
    fun `failures in a class's setup, its teardown and one invocation are would-be misses`(@TempDir dir: File) {
        capturedWithEarlyTests(dir)
        changeBeta(dir)
        commit(dir, "change beta")

        val output = runner(dir, "test", "-Pyoriwake.observe", "-Pyoriwake.base=HEAD", "-PquxFail=true")
            .buildAndFail().output

        val misses = misses(dir).keys
        assertTrue(misses.any { "SetupTest" in it && "[method:first()]" in it }, "$misses\n$output")
        assertTrue(misses.any { "SetupTest" in it && "[method:second()]" in it }, "$misses\n$output")
        assertTrue(misses.any { "TeardownTest" in it && "[method:passes()]" in it }, "$misses\n$output")
        assertEquals(
            listOf("[test-template-invocation:#2]"),
            misses.filter { "ParamTest" in it }.map { it.substringAfterLast('/') },
            "$misses\n$output",
        )
        assertContains(report(dir), "\"complete\": true")
    }

    @Test
    fun `a green observing run reports no failure`(@TempDir dir: File) {
        captured(dir)
        changeBeta(dir)
        commit(dir, "change beta")

        val output = observe(dir).output

        assertContains(report(dir), "\"failures\": 0,")
        assertContains(output, "no test failed")
    }

    @Test
    fun `an observing run declined for in-JVM parallelism reports this run, never the previous one`(@TempDir dir: File) {
        captured(dir)
        changeBeta(dir)
        commit(dir, "change beta")
        observe(dir)
        assertContains(report(dir), "\"outcomesRecorded\": true")
        File(dir, "build.gradle.kts").appendText(
            "\ntasks.test { systemProperty(\"junit.jupiter.execution.parallel.enabled\", \"true\") }\n"
        )
        commit(dir, "parallel")

        val output = observe(dir).output

        assertContains(report(dir), "\"outcomesRecorded\": false")
        assertContains(report(dir), "in-JVM parallel")
        assertContains(output, "observed nothing")
    }

    @Test
    fun `every fork's record is read`(@TempDir dir: File) {
        build(
            dir, "build.gradle.kts" to minimalBuild + "\ntasks.test { forkEvery = 1 }\n",
            oneClass, oneTest, secondClass, secondTest, classOrderByName,
        )
        committed(dir)
        runner(dir, "test").build()
        changeBeta(dir)
        commit(dir, "change beta")

        val output = observe(dir).output

        val parts = mapDir(dir).listFiles().orEmpty().count { it.name.endsWith(AgentContract.DECISIONS_PART_SUFFIX) }
        assertTrue(parts >= 2, "$parts parts\n$output")
        assertContains(report(dir), "\"complete\": true")
        assertContains(report(dir), "\"wouldBeSkipped\": 1,")
    }

    /** A committed two-class sample whose map was captured at HEAD. */
    private fun captured(dir: File, vararg flags: String) {
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest, secondClass, secondTest, classOrderByName)
        committed(dir)
        runner(dir, "test", *flags).build()
    }

    /**
     * The two-class sample with classes that run before them by name: `QuxTest`, and a class whose
     * setup, one whose teardown and one parameterised invocation fail when the test JVM sees
     * `qux.fail`, which `-PquxFail` sets for one run, out of sight of any change set. Each reaches
     * Alpha, so the map knows it passed, and none reaches Beta.
     */
    private fun capturedWithEarlyTests(dir: File) {
        val early = "src/test/java/dev/early"
        val imports = "package dev.early;\nimport org.junit.jupiter.api.*;\n"
        val fails = "System.getProperty(\"qux.fail\") != null"
        val reach = "new dev.sample.Alpha().twice(1);"
        build(
            dir,
            "build.gradle.kts" to minimalBuild +
                "\ntasks.test { providers.gradleProperty(\"quxFail\").orNull?.let { systemProperty(\"qux.fail\", it) } }\n",
            oneClass, oneTest, secondClass, secondTest, classOrderByName,
            "$early/QuxTest.java" to "${imports}class QuxTest { @Test void passes() { $reach Assertions.assertFalse($fails); } }",
            "$early/SetupTest.java" to "${imports}class SetupTest {\n" +
                "  @BeforeAll static void setUp() { Assertions.assertFalse($fails); }\n" +
                "  @Test void first() { $reach }\n  @Test void second() { $reach }\n}",
            "$early/TeardownTest.java" to "${imports}class TeardownTest {\n" +
                "  @AfterAll static void tearDown() { Assertions.assertFalse($fails); }\n" +
                "  @Test void passes() { $reach }\n}",
            "$early/ParamTest.java" to "${imports}import org.junit.jupiter.params.ParameterizedTest;\n" +
                "import org.junit.jupiter.params.provider.ValueSource;\nclass ParamTest {\n" +
                "  @ParameterizedTest @ValueSource(ints = {1, 2}) void each(int n) { $reach Assertions.assertFalse(n == 2 && $fails); }\n}",
        )
        committed(dir)
        runner(dir, "test").build()
    }

    /** A Beta whose test fails: only BetaTest reaches it. */
    private fun breakBeta(dir: File) {
        File(dir, "src/main/java/dev/sample/Beta.java").writeText(
            "package dev.sample;\npublic class Beta { public int thrice(int n) { return n * 4; } }\n"
        )
    }

    private fun report(dir: File) = File(mapDir(dir), AgentContract.OBSERVATION_FILE).readText()

    /** Each would-be miss's outcome, keyed by test id, as the report lists them. */
    private fun misses(dir: File): Map<String, String> =
        Regex("""\{ "test": "((?:[^"\\]|\\.)*)", "outcome": "([A-Z]+)" \}""").findAll(report(dir))
            .associate { it.groupValues[1].replace("\\\"", "\"") to it.groupValues[2] }

    private fun observe(dir: File, vararg flags: String) =
        runner(dir, "test", "-Pyoriwake.observe", "-Pyoriwake.base=HEAD", *flags).build()

    private fun mapDir(dir: File) = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)

    private fun stamp(dir: File) = File(mapDir(dir), CoverageDecoder.CAPTURE_COMMIT_FILE).readText().trim()

    private fun head(dir: File): String =
        ProcessBuilder("git", "rev-parse", "HEAD").directory(dir).start().inputStream.bufferedReader().readText().trim()

    private fun decisionLines(dir: File) = File(mapDir(dir), AgentContract.DECISIONS_FILE).readLines()

    private fun decisionRows(dir: File) =
        decisionLines(dir).filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split("\t") }

    /** Each observation line's verdict and reason, keyed by test id. */
    private fun observations(dir: File) = decisionLines(dir)
        .filter { it.startsWith(AgentContract.OBSERVATION_LINE_PREFIX) }
        .map { it.removePrefix(AgentContract.OBSERVATION_LINE_PREFIX).split("\t") }
        .associate { it[0] to listOf(it[1], it[2]) }

    private fun inputNotes(dir: File) =
        decisionNotes(dir).filterKeys { it.startsWith(AgentContract.INPUT_NOTE_PREFIX) }

    /** The change set handed to the test JVM, without the timestamp `Properties` writes first. */
    private fun changeSet(dir: File) =
        File(mapDir(dir), AgentContract.CHANGE_SET_FILE).readLines().filterNot { it.startsWith("#") && it != AgentContract.CHANGE_SET_END }

    /**
     * The map files a capture writes, as text, with each record's duration dropped and each JVM id
     * named alike: two runs of the same tests never take the same time, the id is fresh per capture,
     * and the digest of the map follows from both.
     */
    private fun mapFiles(dir: File): Map<String, String> = mapDir(dir).listFiles().orEmpty()
        .filter { it.isFile && it.name in MAP_FILES }
        .associate { file ->
            file.name to if (file.name == AgentContract.COVERAGE_FILE) {
                file.readLines().joinToString("\n") { line -> line.split("\t").let { (it.take(1) + it.drop(2)).joinToString("\t") } }
            } else {
                file.readText().replace(Regex("""(?m)^[0-9a-f]+/worker-\d+\t"""), "jvm\t")
            }
        }

    private companion object {
        val MAP_FILES = setOf(
            AgentContract.COVERAGE_FILE, AgentContract.MAP_SCHEMA_VERSION_FILE, AgentContract.SCOPE_FILE,
            AgentContract.EFFECTIVE_SCOPE_FILE, AgentContract.LOADED_FILE, AgentContract.LOADED_PROVENANCE_FILE,
            AgentContract.LOADED_SCOPE_FILE, AgentContract.POSITIONS_FILE, AgentContract.FIRST_TOUCH_FILE,
            AgentContract.NAMED_TOUCH_FILE, AgentContract.JVM_MODE_FILE, CoverageDecoder.CAPTURE_COMMIT_FILE,
        )
    }
}
