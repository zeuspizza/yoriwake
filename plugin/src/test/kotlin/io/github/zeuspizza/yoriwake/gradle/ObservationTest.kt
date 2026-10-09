package io.github.zeuspizza.yoriwake.gradle

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.agent.contract.Tsv
import io.github.zeuspizza.yoriwake.gradle.report.Observation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `observation.json`: what an observing run's verdicts and this run's outcomes say together. Read by
 * scripts and by people deciding whether to turn selection on, so its layout is pinned and it must
 * never claim more than the records show.
 */
class ObservationTest {

    private val alpha = "[engine:junit-jupiter]/[class:dev.sample.AlphaTest]/[method:passes()]"
    private val bar = "[engine:junit-jupiter]/[class:dev.sample.BarTest]/[method:passes()]"
    private val qux = "[engine:junit-jupiter]/[class:dev.early.QuxTest]/[method:passes()]"
    private val run = Observation.Run(":test", "a".repeat(40), "b".repeat(40))

    private fun golden(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/golden/$name")) { "no golden file $name" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    /** One writer's decision record: every row ran, and each `#?` line holds the selecting verdict. */
    private fun part(
        mapDir: File,
        name: String,
        observed: List<String>,
        vararg verdicts: Pair<String, String>,
        declaredRows: Int = verdicts.size,
    ) {
        mapDir.mkdirs()
        val text = buildString {
            append("# test\tverdict\treason\n")
            append("#!record-version\t${AgentContract.DECISIONS_VERSION}\n")
            append("#!rows\t$declaredRows\n")
            append("#!writer\t1 of 1\n")
            append("#!outcome\tfull-run\n")
            append("#!" + Tsv.join(AgentContract.OBSERVED_OUTCOME_NOTE, *observed.toTypedArray()) + "\n")
            verdicts.forEach { (id, _) -> append(Tsv.join(id, "included", "OBSERVING") + "\n") }
            verdicts.forEach { (id, reason) ->
                val inclusion = if (reason == "SKIPPED") "excluded" else "included"
                append(AgentContract.OBSERVATION_LINE_PREFIX + Tsv.join(id, inclusion, reason) + "\n")
            }
        }
        File(mapDir, "${AgentContract.DECISIONS_FILE}.$name${AgentContract.DECISIONS_PART_SUFFIX}").writeText(text)
    }

    /** One worker's raw index: `outcome to nanos to id` per record, in order. */
    private fun worker(mapDir: File, name: String, vararg records: Triple<String, Long, String>) {
        val dir = File(mapDir, "${AgentContract.RAW_DIR}/${AgentContract.WORKER_DIR_PREFIX}$name").apply { mkdirs() }
        File(dir, AgentContract.INDEX_FILE).writeText(
            records.mapIndexed { sequence, (outcome, nanos, id) ->
                Tsv.join("${sequence + 1}", "$nanos", "0", outcome, id)
            }.joinToString("\n", postfix = "\n")
        )
    }

    private fun windows() = arrayOf(
        Triple("NONE", 5L, AgentContract.UNATTRIBUTED_RECORD_ID),
        Triple("NONE", 7L, AgentContract.CLASS_SCOPED_RECORD_PREFIX + "[engine:junit-jupiter]/[class:dev.sample.AlphaTest]"),
    )

    private fun written(mapDir: File) = File(mapDir, AgentContract.OBSERVATION_FILE).readText()

    @Test
    fun `a failure selection would have kept is no would-be miss, and the unreached tests are would-be skipped`(
        @TempDir mapDir: File,
    ) {
        part(mapDir, "1-1", listOf("narrowed"), alpha to "SKIPPED", bar to "REACHES_CHANGE")
        worker(mapDir, "1", *windows(), Triple("SUCCESSFUL", 1_000_000L, alpha), Triple("FAILED", 2_000_000L, bar))

        Observation.write(mapDir, run)

        assertEquals(golden("observation-kept.json"), written(mapDir))
    }

    @Test
    fun `a failing test selection would have left out is a would-be miss`(@TempDir mapDir: File) {
        part(mapDir, "1-1", listOf("narrowed"), qux to "SKIPPED", alpha to "SKIPPED", bar to "REACHES_CHANGE")
        worker(
            mapDir, "1", *windows(),
            Triple("FAILED", 3_000_000L, qux), Triple("SUCCESSFUL", 1_000_000L, alpha), Triple("FAILED", 2_000_000L, bar),
        )

        Observation.write(mapDir, run)

        assertEquals(golden("observation-miss.json"), written(mapDir))
    }

    @Test
    fun `a failing invocation under a would-be-excluded template is a would-be miss, and the report stays complete`(
        @TempDir mapDir: File,
    ) {
        val template = "[engine:junit-jupiter]/[class:dev.sample.ParamTest]/[test-template:each(int)]"
        part(mapDir, "1-1", listOf("narrowed"), template to "SKIPPED", bar to "REACHES_CHANGE")
        worker(
            mapDir, "1",
            Triple("SUCCESSFUL", 10L, "$template/[test-template-invocation:#1]"),
            Triple("FAILED", 20L, "$template/[test-template-invocation:#2]"),
            Triple("SUCCESSFUL", 30L, bar),
        )

        val report = Observation.write(mapDir, run)

        assertEquals(listOf("$template/[test-template-invocation:#2]" to "FAILED"), report.misses)
        assertEquals(2, report.wouldBeSkipped)
        assertEquals(30L, report.skippedNanos)
        assertTrue(report.complete)
    }

    @Test
    fun `a test with a failing record after a passing one is failing, and its records' durations add up`(
        @TempDir mapDir: File,
    ) {
        part(mapDir, "1-1", listOf("narrowed"), alpha to "SKIPPED", bar to "REACHES_CHANGE")
        worker(mapDir, "1", Triple("SUCCESSFUL", 100L, alpha), Triple("FAILED", 0L, alpha), Triple("SUCCESSFUL", 5L, bar))

        val report = Observation.write(mapDir, run)

        assertEquals(listOf(alpha to "FAILED"), report.misses)
        assertEquals(1, report.failures)
        assertEquals(100L, report.skippedNanos)
    }

    @Test
    fun `an aborted test counts as failing`(@TempDir mapDir: File) {
        part(mapDir, "1-1", listOf("narrowed"), alpha to "SKIPPED")
        worker(mapDir, "1", Triple("ABORTED", 1L, alpha))

        assertEquals(listOf(alpha to "ABORTED"), Observation.write(mapDir, run).misses)
    }

    @Test
    fun `two forks' parts and workers are all read, and the totals are their sums`(@TempDir mapDir: File) {
        part(mapDir, "11-1", listOf("narrowed"), alpha to "SKIPPED")
        part(mapDir, "12-1", listOf("narrowed"), bar to "SKIPPED", qux to "REACHES_CHANGE")
        worker(mapDir, "1", Triple("SUCCESSFUL", 100L, alpha))
        worker(mapDir, "2", Triple("FAILED", 20L, bar), Triple("FAILED", 3L, qux))

        val report = Observation.write(mapDir, run)

        assertEquals(2, report.wouldBeSkipped)
        assertEquals(120L, report.skippedNanos)
        assertEquals(2, report.failures)
        assertEquals(1, report.failuresKept)
        assertEquals(listOf(bar to "FAILED"), report.misses)
        assertTrue(report.complete)
    }

    @Test
    fun `a fork whose part is missing makes the report partial, and its tests are never counted as skipped`(
        @TempDir mapDir: File,
    ) {
        part(mapDir, "11-1", listOf("narrowed"), alpha to "SKIPPED")
        worker(mapDir, "1", Triple("SUCCESSFUL", 100L, alpha))
        worker(mapDir, "2", Triple("FAILED", 20L, bar), Triple("SUCCESSFUL", 3L, qux))

        val report = Observation.write(mapDir, run)

        assertFalse(report.complete)
        assertEquals(2, report.testsWithoutVerdict)
        assertEquals(1, report.wouldBeSkipped)
        assertEquals(100L, report.skippedNanos)
        assertEquals(emptyList(), report.misses)
        assertContains(written(mapDir), "\"complete\": false")
    }

    @Test
    fun `a part cut short makes the report partial`(@TempDir mapDir: File) {
        part(mapDir, "1-1", listOf("narrowed"), alpha to "SKIPPED", declaredRows = 2)
        worker(mapDir, "1", Triple("SUCCESSFUL", 100L, alpha))

        assertFalse(Observation.write(mapDir, run).complete)
    }

    @Test
    fun `a selection that would have run everything names why, and skips nothing`(@TempDir mapDir: File) {
        part(
            mapDir, "1-1", listOf("full-run", "daemon-refused", "full-run-requested"),
            alpha to "DAEMON_REFUSED", bar to "DAEMON_REFUSED",
        )
        worker(mapDir, "1", Triple("SUCCESSFUL", 1L, alpha), Triple("FAILED", 2L, bar))

        val report = Observation.write(mapDir, run)

        assertEquals("full-run", report.observedOutcome)
        assertEquals("daemon-refused", report.fullRunKind)
        assertEquals("full-run-requested", report.refusalKind)
        assertEquals(0, report.wouldBeSkipped)
        assertEquals(1, report.failuresKept)
        assertContains(Observation.line(report), "full-run-requested")
    }

    @Test
    fun `a fork that discovered no test does not hide the full run the deciding fork would have run`(
        @TempDir mapDir: File,
    ) {
        part(mapDir, "11-1", listOf("not-decided"))
        part(mapDir, "12-1", listOf("full-run", "unmappable-paths"), alpha to "UNMAPPABLE_PATHS")
        worker(mapDir, "2", Triple("SUCCESSFUL", 1L, alpha))

        val report = Observation.write(mapDir, run)

        assertEquals("full-run", report.observedOutcome)
        assertEquals("unmappable-paths", report.fullRunKind)
        assertTrue(report.complete)
    }

    @Test
    fun `a narrowing fork speaks for the run whichever fork sorts first`(@TempDir mapDir: File) {
        part(mapDir, "11-1", listOf("full-run", "unmappable-paths"), bar to "UNMAPPABLE_PATHS")
        part(mapDir, "12-1", listOf("narrowed"), alpha to "SKIPPED")
        part(mapDir, "13-1", listOf("not-decided"))
        worker(mapDir, "1", Triple("SUCCESSFUL", 1L, alpha), Triple("SUCCESSFUL", 2L, bar))

        assertEquals("narrowed", Observation.write(mapDir, run).observedOutcome)
    }

    @Test
    fun `a run with no decision record decided nothing, and none of its tests counts as skipped`(
        @TempDir mapDir: File,
    ) {
        worker(mapDir, "1", Triple("SUCCESSFUL", 1L, alpha), Triple("FAILED", 2L, bar))

        val report = Observation.write(mapDir, run)

        assertEquals(AgentContract.RUN_NOT_DECIDED, report.observedOutcome)
        assertFalse(report.complete)
        assertEquals(2, report.testsWithoutVerdict)
        assertEquals(0, report.wouldBeSkipped)
        assertEquals(emptyList(), report.misses)
        assertContains(Observation.line(report), "selection decided nothing (not-decided)")
    }

    @Test
    fun `a run that recorded no outcome says why, and counts nothing`(@TempDir mapDir: File) {
        part(mapDir, "1-1", listOf("narrowed"), alpha to "SKIPPED")
        worker(mapDir, "1", Triple("FAILED", 1L, alpha))

        val report = Observation.write(mapDir, run.copy(note = "the test task did no work"))

        assertFalse(report.outcomesRecorded)
        assertEquals(0, report.failures)
        assertEquals(0, report.wouldBeSkipped)
        assertContains(written(mapDir), "the test task did no work")
        assertContains(Observation.line(report), "the test task did no work")
    }

    @Test
    fun `the report is strict JSON whatever a test id holds`(@TempDir mapDir: File) {
        val odd = "[engine:junit-jupiter]/[class:dev.Odd]/[method:quote\"back\\slash\ttab()]"
        part(mapDir, "1-1", listOf("narrowed"), odd to "SKIPPED")
        worker(mapDir, "1", Triple("FAILED", 1L, odd))

        Observation.write(mapDir, run)

        val tests = mutableListOf<String>()
        JsonFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).createParser(written(mapDir)).use { parser ->
            while (parser.nextToken() != null) {
                if (parser.currentToken == JsonToken.FIELD_NAME && parser.currentName() == "test") {
                    parser.nextToken()
                    tests += parser.text
                }
            }
        }
        assertEquals(listOf(odd), tests)
    }

    @Test
    fun `the console line labels the time as recorded test time and never prints a rate`(@TempDir mapDir: File) {
        part(mapDir, "1-1", listOf("narrowed"), qux to "SKIPPED", bar to "REACHES_CHANGE")
        worker(mapDir, "1", Triple("FAILED", 1_500_000_000L, qux), Triple("FAILED", 2L, bar))

        val line = Observation.line(Observation.write(mapDir, run))

        assertContains(line, "recorded test time")
        assertContains(line, "not a wall-clock saving")
        assertContains(line, "1 would-be miss")
        assertFalse("%" in line, line)
    }

    @Test
    fun `a green run's line says no test failed`(@TempDir mapDir: File) {
        part(mapDir, "1-1", listOf("narrowed"), alpha to "SKIPPED", bar to "REACHES_CHANGE")
        worker(mapDir, "1", Triple("SUCCESSFUL", 1L, alpha), Triple("SUCCESSFUL", 2L, bar))

        val report = Observation.write(mapDir, run)

        assertEquals(0, report.failures)
        assertContains(Observation.line(report), "no test failed")
    }
}
