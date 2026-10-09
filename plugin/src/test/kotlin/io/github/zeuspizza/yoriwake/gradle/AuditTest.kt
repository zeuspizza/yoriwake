package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.agent.select.MapReader
import io.github.zeuspizza.yoriwake.gradle.bytecode.InlineScan
import io.github.zeuspizza.yoriwake.gradle.capture.CoverageDecoder
import io.github.zeuspizza.yoriwake.gradle.change.establish
import io.github.zeuspizza.yoriwake.gradle.report.Audit
import io.github.zeuspizza.yoriwake.gradle.report.Payback
import io.github.zeuspizza.yoriwake.gradle.report.audit
import io.github.zeuspizza.yoriwake.gradle.report.blockers
import io.github.zeuspizza.yoriwake.gradle.report.ownClassCoverage
import io.github.zeuspizza.yoriwake.gradle.report.percent
import io.github.zeuspizza.yoriwake.gradle.report.taskPayback
import io.github.zeuspizza.yoriwake.gradle.report.write
import io.github.zeuspizza.yoriwake.gradle.wiring.ScopeOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Refusals first, arithmetic second: every assertion that clears a path is paired with one that
 * blocks it, because a guard is not a guard until it has been seen refusing something.
 */
class AuditTest {

    /** A record line as the decoder writes it: outcome, duration, classes, test id. */
    private fun record(id: String, vararg classes: String, outcome: String = "SUCCESSFUL") =
        "$outcome\t1000\t${classes.joinToString(",")}\t$id"

    private fun map(
        dir: File,
        vararg records: String,
        version: String? = "${AgentContract.MAP_SCHEMA_VERSION}",
        captureCommit: String? = "abcdef1",
    ): File {
        dir.mkdirs()
        File(dir, AgentContract.COVERAGE_FILE).writeText(records.joinToString("\n", postfix = "\n"))
        version?.let { File(dir, AgentContract.MAP_SCHEMA_VERSION_FILE).writeText("$it\n") }
        captureCommit?.let { File(dir, CoverageDecoder.CAPTURE_COMMIT_FILE).writeText("$it\n") }
        return dir
    }

    private fun audit(dir: File, ceiling: Double = Audit.DEFAULT_UNATTRIBUTABLE_CEILING) =
        Audit.audit(dir, taskPath = ":app:test", captureTask = "test", unattributableCeiling = ceiling)

    private fun Audit.Result.text() = (listOf(headline) + lines).joinToString("\n")

    private fun createTempDir(): File =
        java.nio.file.Files.createTempDirectory("yoriwake-facts").toFile()

    /** A record with a duration of its own, in nanoseconds. */
    private fun timed(id: String, nanos: Long, vararg classes: String, outcome: String = "SUCCESSFUL") =
        "$outcome\t$nanos\t${classes.joinToString(",")}\t$id"

    // --- recorded time ------------------------------------------------------------------------

    @Test
    fun `recorded time is summed, counted, and never called a suite duration`(@TempDir dir: File) {
        map(
            dir,
            timed("t1", 1_500_000_000, "com.acme.A"),
            timed("t2", 500_000_000, "com.acme.B"),
        )
        Audit.TaskFacts.write(dir, Audit.TaskFacts.PLATFORM, null)
        Audit.TaskFacts.recordWorkers(dir, 4)

        val result = audit(dir)
        val text = result.text()

        assertEquals(2_000_000_000L, assertNotNull(result.recordedTime).nanos)
        assertEquals(2, result.recordedTime!!.records)
        assertEquals(4, result.recordedTime!!.workers)
        // Summed across workers inside an instrumented JVM, so it is not a suite duration.
        assertContains(text, "2.0s of recorded test time")
        assertContains(text, "4 workers")
        assertFalse(text.contains("suite takes"), "the audit called a summed figure a wall clock")
    }

    @Test
    fun `a map whose records carry no duration says so, rather than reporting zero seconds`(
        @TempDir dir: File,
    ) {
        // Empty is not unknown: a map with no durations must not read as a suite that runs
        // instantly.
        map(dir, timed("t1", 0, "com.acme.A"), timed("t2", 0, "com.acme.B"))
        Audit.TaskFacts.write(dir, Audit.TaskFacts.PLATFORM, null)
        Audit.TaskFacts.recordWorkers(dir, 1)

        val result = audit(dir)

        assertNull(result.recordedTime, "zero durations were reported as a figure")
        assertContains(result.text(), "no per-test durations")
    }

    @Test
    fun `an unknown worker count is reported as unknown, never assumed to be one`(
        @TempDir dir: File,
    ) {
        // A summed figure cannot be compared with a wall clock without the worker count, and
        // assuming one worker would overstate a four-fork suite's time fourfold.
        map(dir, timed("t1", 1_000_000_000, "com.acme.A"))
        Audit.TaskFacts.write(dir, Audit.TaskFacts.PLATFORM, null)

        val result = audit(dir)

        assertEquals(1_000_000_000L, assertNotNull(result.recordedTime).nanos)
        assertNull(result.recordedTime!!.workers)
        assertContains(result.text(), "an unknown number of workers")
    }

    @Test
    fun `malformed lines are excluded from the recorded time, as they are from every other figure`(
        @TempDir dir: File,
    ) {
        map(
            dir,
            timed("t1", 1_000_000_000, "com.acme.A"),
            "SUCCESSFUL\tnot-a-number\tcom.acme.B\tt2",
            "two fields only",
        )
        Audit.TaskFacts.write(dir, Audit.TaskFacts.PLATFORM, null)
        Audit.TaskFacts.recordWorkers(dir, 2)

        val result = audit(dir)

        assertEquals(1_000_000_000L, assertNotNull(result.recordedTime).nanos)
        assertEquals(1, result.recordedTime!!.records)
        // Counted visibly rather than as zero: a record without a duration did not take no time.
        assertEquals(1, result.recordedTime!!.withoutDuration)
    }

    @Test
    fun `startup coverage and class windows carry no test time and are not counted as tests`(
        @TempDir dir: File,
    ) {
        // Their duration field is 0 because no test ran in them. Counted as records without a
        // duration they would report a healthy map as one that recorded nothing.
        map(
            dir,
            timed("t1", 1_000_000_000, "com.acme.A"),
            timed("[yoriwake:unattributed]", 0, "com.acme.Startup", outcome = "NONE"),
            timed("[yoriwake:class]/dev.acme.A", 0, "com.acme.A", outcome = "NONE"),
        )
        Audit.TaskFacts.write(dir, Audit.TaskFacts.PLATFORM, null)
        Audit.TaskFacts.recordWorkers(dir, 1)

        val result = audit(dir)

        assertEquals(1, assertNotNull(result.recordedTime).records)
        assertEquals(0, result.recordedTime!!.withoutDuration)
    }

    @Test
    fun `the recorded worker count survives a later run that records only coverage`(
        @TempDir dir: File,
    ) {
        // task-facts is rewritten by several writers at different moments in a run. A count dropped
        // by the next writer would make the audit refuse a payback question it could answer.
        Audit.TaskFacts.write(dir, Audit.TaskFacts.PLATFORM, null)
        Audit.TaskFacts.recordWorkers(dir, 3)
        Audit.TaskFacts.recordCoverage(dir, true)

        assertEquals(3, Audit.TaskFacts.read(dir).workers)
        assertEquals(Audit.TaskFacts.PLATFORM, Audit.TaskFacts.read(dir).framework)
    }

    @Test
    fun `a real duration under a second is never printed as zero seconds`(@TempDir dir: File) {
        // A real measurement rounded to "0.0s" looks like an answer while saying nothing.
        map(dir, timed("t1", 12_000_000, "com.acme.A"))
        Audit.TaskFacts.write(dir, Audit.TaskFacts.PLATFORM, null)
        Audit.TaskFacts.recordWorkers(dir, 1)

        val text = audit(dir).text()

        assertContains(text, "12ms of recorded test time")
        assertFalse(text.contains("0.0s"), "a real measurement was rounded into zero")
        assertContains(text, "summed across 1 worker.")
    }

    @Test
    fun `audit json carries the recorded time as data, with its caveats and a null for unknown`(
        @TempDir dir: File,
    ) {
        // Consumers read this file, not the prose, so the caveats must be in the payload: it is not
        // a suite duration, and the worker count it was summed across may be unknown.
        map(dir, timed("t1", 2_500_000_000, "com.acme.A"))
        Audit.TaskFacts.write(dir, Audit.TaskFacts.PLATFORM, null)

        Audit.write(dir, audit(dir))
        val json = File(dir, Audit.AUDIT_FILE).readText()

        assertContains(json, """"nanos": 2500000000""")
        assertContains(json, """"workers": null""")
        assertContains(json, """"isSuiteDuration": false""")
        // Single-line values, which Blocker's own invariant requires of this file as a whole.
        assertFalse(
            json.lines().any { it.count { c -> c == '"' } % 2 != 0 },
            "a value in audit.json spans lines",
        )
    }

    @Test
    fun `a map with no durations writes null rather than a zero a consumer would divide by`(
        @TempDir dir: File,
    ) {
        map(dir, timed("t1", 0, "com.acme.A"))
        Audit.TaskFacts.write(dir, Audit.TaskFacts.PLATFORM, null)

        Audit.write(dir, audit(dir))

        assertContains(File(dir, Audit.AUDIT_FILE).readText(), """"recordedTime": null""")
    }

    /** A map of `tests` tests, each reaching one class of its own, so the mean share is 1/tests. */
    private fun paybackMap(dir: File, tests: Int, nanosEach: Long, workers: Int?) {
        map(dir, *(1..tests).map { timed("t$it", nanosEach, "com.acme.C$it") }.toTypedArray())
        Audit.TaskFacts.write(dir, Audit.TaskFacts.PLATFORM, null)
        workers?.let { Audit.TaskFacts.recordWorkers(dir, it) }
    }

    private fun payback(
        dir: File,
        // Above the 18% noise floor on purpose: below it the audit refuses before payback is
        // reached, which is the existing refusal working and not the one under test here.
        toll: Audit.Toll? = Audit.Toll.measured(instrumented = 13.0, uninstrumented = 10.0),
        cost: Payback.ConfigureCost? = Payback.ConfigureCost(nanos = 400_000_000, testTasks = 1),
    ) = Audit.audit(
        dir, taskPath = ":app:test", captureTask = "test", toll = toll, configureCost = cost,
    )

    @Test
    fun `both axes present names both, on the adopter's own numbers`(@TempDir dir: File) {
        paybackMap(dir, tests = 10, nanosEach = 1_000_000_000, workers = 2)

        val text = payback(dir).text()

        // The saving axis: 10s of recorded test time across 2 workers is 5s of wall clock, of which
        // a change that selects a tenth skips nine tenths.
        assertContains(text, "4.5s")
        // The cost axis, per Test task, from this build.
        assertContains(text, "400ms")
        // The toll line above it says "instrumented" too, so this asserts the payback line's own
        // wording -- an assertion satisfied by a neighbouring line constrains nothing.
        assertContains(text, "the saving is measured INSIDE the instrumented JVM")
        assertContains(text, "BREAK-EVEN")
        // And the axis that is optimistic is labelled where the number is.
        assertContains(text, "instrumented")
    }

    @Test
    fun `a test id recorded twice contributes one duration, and the retry is reported`(
        @TempDir dir: File,
    ) {
        // A retry plugin writes several records under one id; summing every line would count the
        // test more than once and overstate the saving.
        // Three records, not two: at two, "extra records" and "distinct ids" agree, so a wrong
        // counter still passes.
        map(
            dir,
            timed("t1", 1_000_000_000, "com.acme.A"),
            timed("t1", 3_000_000_000, "com.acme.A", outcome = "FAILED"),
            timed("t1", 2_000_000_000, "com.acme.A", outcome = "FAILED"),
            timed("t2", 1_000_000_000, "com.acme.B"),
        )
        Audit.TaskFacts.write(dir, Audit.TaskFacts.PLATFORM, null)
        Audit.TaskFacts.recordWorkers(dir, 1)

        val result = payback(dir)
        val text = result.text()

        // 3s (t1's longest record) plus 1s, not every line summed.
        assertContains(text, "4.0s of recorded test time across 2 tests")
        assertContains(text, "1 test id(s) carried more than one timed record")
        assertFalse(text.contains("2 test id(s)"), "the count reported extra records, not ids")
    }

    @Test
    fun `a record whose outcome is not an outcome is malformed, not a test with a duration`(
        @TempDir dir: File,
    ) {
        map(
            dir,
            timed("t1", 1_000_000_000, "com.acme.A"),
            "SUCCESSF	9000000000	com.acme.B	t2",
        )
        Audit.TaskFacts.write(dir, Audit.TaskFacts.PLATFORM, null)
        Audit.TaskFacts.recordWorkers(dir, 1)

        val text = payback(dir).text()

        assertContains(text, "1.0s of recorded test time across 1 tests")
        assertContains(text, "1 malformed")
    }

    @Test
    fun `a flaky record is a test like any other`(@TempDir dir: File) {
        map(
            dir, record("t1", "com.acme.A"),
            record("t2", "com.acme.B", outcome = "FLAKY"), record("t2", "com.acme.C", outcome = "FLAKY"),
        )

        val result = audit(dir)

        assertEquals(0, result.counts.malformed)
        assertEquals(2, result.counts.testRecords)
    }

    @Test
    fun `the wall-clock check says what its own slack cannot catch`(@TempDir dir: File) {
        // The comparison is against a whole build, so recorded time below it proves nothing on a
        // compile-heavy one; the refusal states that gap rather than implying a bound.
        paybackMap(dir, tests = 10, nanosEach = 10_000_000_000, workers = 1)

        val text = payback(dir).text()

        assertContains(text, "PAYBACK NOT ANSWERED: the map holds more recorded test time")
        assertContains(text, "This check has slack")
        assertContains(text, "compile, configuration and JVM startup")
    }

    @Test
    fun `the assumption about test length sits in the line that makes it`(@TempDir dir: File) {
        // `meanShare` counts tests, not seconds. A suite of one long integration test and fifty
        // fast unit tests breaks that assumption, and the reader has to meet it where the number is.
        paybackMap(dir, tests = 10, nanosEach = 1_000_000_000, workers = 2)

        val optimism = payback(dir).lines.single { it.contains("optimistic in the same direction") }

        assertContains(optimism, "counts tests rather than seconds")
        assertContains(optimism, "average length")
        assertContains(optimism, "an included build's configuration is its own")
    }

    @Test
    fun `no configure cost refuses by naming that input, not by assuming it is free`(
        @TempDir dir: File,
    ) {
        paybackMap(dir, tests = 10, nanosEach = 1_000_000_000, workers = 2)

        val text = payback(dir, cost = null).text()

        assertContains(text, "no configure cost has been measured")
        assertFalse(text.contains("BREAK-EVEN"), "a verdict was reached with one axis missing")
    }

    @Test
    fun `no recorded duration refuses by naming the duration, not the configure cost`(
        @TempDir dir: File,
    ) {
        map(dir, timed("t1", 0, "com.acme.A"))
        Audit.TaskFacts.write(dir, Audit.TaskFacts.PLATFORM, null)
        Audit.TaskFacts.recordWorkers(dir, 1)

        val text = payback(dir).text()

        assertContains(text, "records no per-test duration")
        assertFalse(text.contains("BREAK-EVEN"))
    }

    @Test
    fun `an unknown worker count refuses rather than assuming one worker`(@TempDir dir: File) {
        // Assuming one worker on a four-fork suite overstates the saving fourfold.
        paybackMap(dir, tests = 10, nanosEach = 1_000_000_000, workers = null)

        val text = payback(dir).text()

        assertContains(text, "how many workers")
        assertFalse(text.contains("BREAK-EVEN"))
    }

    @Test
    fun `recorded time above the observed wall clock is refused, never divided down`(
        @TempDir dir: File,
    ) {
        // The signature of worker summing: 40s of recorded test time in a run the clock says took
        // 11s. Dividing it by anything is arithmetic on a figure already known to be wrong.
        paybackMap(dir, tests = 10, nanosEach = 4_000_000_000, workers = 1)

        val text = payback(dir).text()

        assertContains(text, "more recorded test time")
        assertContains(text, "than the instrumented run took")
        assertFalse(text.contains("BREAK-EVEN"))
    }

    @Test
    fun `the payback section is absent entirely when no toll was measured`(@TempDir dir: File) {
        // The default path, and it must stay the default: asking for payback times two full suites,
        // and a task that did that without being asked would be a trap.
        paybackMap(dir, tests = 10, nanosEach = 1_000_000_000, workers = 2)

        val text = Audit.audit(dir, ":app:test", "test", configureCost = Payback.ConfigureCost(400, 1))
            .text()

        assertFalse(text.contains("BREAK-EVEN"))
        assertContains(text, "UNMEASURED")
    }

    private fun answering(path: String, saved: Long, cost: Long) =
        Payback.TaskPayback(path, savedNanos = saved, costNanos = cost)

    @Test
    fun `a project whose forced-run share is unknown reports no net figure, and names the gap`() {
        // A per-task figure assumes every build narrows; an aggregate that inherited that
        // assumption would answer best-case.
        val lines = Payback.aggregateLines(
            listOf(answering(":a:test", 10_000_000_000, 100_000_000)),
            forcedShare = null,
        )

        assertTrue(lines.any { it.startsWith("BUILD PAYBACK NOT ANSWERED") }, lines.toString())
        assertTrue(lines.any { it.contains("does not measure that share") }, lines.toString())
        assertFalse(lines.any { it.contains(Settings.FORCED_SHARE) }, "names an unsupported flag: $lines")
        assertFalse(lines.any { it.startsWith("BUILD PAYBACK:") })
    }

    @Test
    fun `the aggregate moves when the forced-run share moves`() {
        val tasks = listOf(answering(":a:test", 10_000_000_000, 1_000_000_000))
        val rarely = Payback.aggregateLines(tasks, forcedShare = "0.1")
        val mostly = Payback.aggregateLines(tasks, forcedShare = "0.8")

        assertContains(rarely.first(), "9.0s a build")
        assertContains(mostly.first(), "2.0s a build")
        // The cost is 1.0s a build either way, so the verdict turns on the share alone.
        assertContains(rarely[1], "pays for itself")
        assertContains(mostly[1], "pays for itself")
        assertContains(Payback.aggregateLines(tasks, forcedShare = "0.95")[1], "does NOT pay")
        // And the break-even share is stated rather than left to be inferred.
        assertContains(rarely[1], "90.0%")
    }

    @Test
    fun `a project with one refusing task reports the aggregate AND the refusal`() {
        // Never the aggregate alone: a project-wide number that silently omits a task reads as
        // complete, and the task it omits is the one nobody can measure.
        val lines = Payback.aggregateLines(
            listOf(
                answering(":a:test", 10_000_000_000, 100_000_000),
                Payback.TaskPayback(":b:integrationTest", missing = "the map records no durations"),
            ),
            forcedShare = "0.5",
        )

        assertTrue(lines.any { it.startsWith("BUILD PAYBACK:") }, lines.toString())
        val composition = lines.single { it.contains("`Test` task(s) with a record") }
        assertContains(composition, "1 of 2")
        assertContains(composition, ":b:integrationTest")
        assertContains(composition, "the map records no durations")
    }

    @Test
    fun `a project where every task refuses reports no number at all`() {
        val lines = Payback.aggregateLines(
            listOf(Payback.TaskPayback(":a:test", missing = "no configure cost was measured")),
            forcedShare = "0.5",
        )

        assertTrue(lines.first().startsWith("BUILD PAYBACK NOT ANSWERED"), lines.toString())
        // Structural, not a phrase: a guard keyed to the wording of the answered headline goes
        // green the moment that headline is reworded, which is the one way a figure could leak into
        // a refusal unnoticed.
        assertFalse(lines.any { it.startsWith("BUILD PAYBACK:") })
        assertContains(lines[1], "no configure cost was measured")
    }

    @Test
    fun `a project with no tasks at all refuses rather than reporting zero`() {
        val lines = Payback.aggregateLines(emptyList(), forcedShare = "0.5")

        assertTrue(lines.first().startsWith("BUILD PAYBACK NOT ANSWERED"), lines.toString())
    }

    @Test
    fun `a forced-run share that is not a fraction is refused rather than clamped`() {
        // Clamping would answer a question nobody asked: a share of 1.5 is a typo or a percentage
        // handed in as a percentage, and either way the number it would produce is not about this
        // project.
        val tasks = listOf(answering(":a:test", 10_000_000_000, 100_000_000))

        assertTrue(
            Payback.aggregateLines(tasks, forcedShare = "55").first()
                .startsWith("BUILD PAYBACK NOT ANSWERED"),
        )
        assertTrue(
            Payback.aggregateLines(tasks, forcedShare = "-0.2").first()
                .startsWith("BUILD PAYBACK NOT ANSWERED"),
        )
        // A value that is not a number says so, rather than reading as never supplied.
        val garbage = Payback.aggregateLines(tasks, forcedShare = "0,5").first()
        assertContains(garbage, "is not a number")
        assertFalse(garbage.contains("UNKNOWN"))
    }

    @Test
    fun `the aggregate says it is not a suite wall clock, as the per-task figure does`() {
        val lines = Payback.aggregateLines(
            listOf(answering(":a:test", 10_000_000_000, 100_000_000)),
            forcedShare = "0.5",
        )

        assertTrue(lines.any { it.startsWith("NOT a suite wall clock") }, lines.toString())
        // The staleness caveat has to be emitted, not only documented.
        assertTrue(lines.any { it.contains("stalest member") }, lines.toString())
    }

    @Test
    fun `a task record round-trips through the file a sibling audit reads`(@TempDir dir: File) {
        val root = File(dir, "yoriwake").apply { mkdirs() }
        val answering = File(root, "test-aaaa").apply { mkdirs() }
        val refusing = File(root, "integrationTest-bbbb").apply { mkdirs() }
        Payback.writeTask(answering, answering(":a:test", 9_000_000_000, 100_000_000))
        Payback.writeTask(refusing, Payback.TaskPayback(":b:integrationTest", missing = "no durations"))
        // A directory whose audit never ran contributes nothing: never-asked is not
        // could-not-answer, and counting it as a refusal would make every new project look partly
        // unmeasurable.
        File(root, "otherTest-cccc").mkdirs()

        val read = Payback.readTasks(root).sortedBy { it.taskPath }

        assertEquals(2, read.size, "records read: ${read.map { it.taskPath }}")
        assertEquals(":a:test", read[0].taskPath)
        assertEquals(9_000_000_000, read[0].savedNanos)
        assertEquals(100_000_000, read[0].costNanos)
        assertTrue(read[0].answered)
        assertEquals(":b:integrationTest", read[1].taskPath)
        assertEquals("no durations", read[1].missing)
        assertFalse(read[1].answered)
    }

    @Test
    fun `an audit that reached no distribution refuses rather than reporting no saving`(
        @TempDir dir: File,
    ) {
        // "This suite saves nothing" and "this suite's shares could not be computed" are different
        // facts, and the first is the one that would talk somebody out of installing the tool.
        paybackMap(dir, tests = 10, nanosEach = 1_000_000_000, workers = 2)
        val refused = Audit.audit(dir, ":app:test", "test").copy(distribution = null)

        val contribution = Audit.taskPayback(":app:test", refused,
                                             Payback.ConfigureCost(400_000_000, 1))

        assertFalse(contribution.answered)
        assertEquals(null, contribution.savedNanos)
        assertContains(contribution.missing ?: "", "no distribution")
    }

    @Test
    fun `a task with no configure cost names that axis and no other`(@TempDir dir: File) {
        paybackMap(dir, tests = 10, nanosEach = 1_000_000_000, workers = 2)
        val result = Audit.audit(dir, ":app:test", "test")

        val contribution = Audit.taskPayback(":app:test", result, cost = null)

        assertFalse(contribution.answered)
        assertContains(contribution.missing ?: "", "configure cost")
    }

    @Test
    fun `a task whose recorded time exceeds its own run refuses, as the per-task sentence does`(
        @TempDir dir: File,
    ) {
        // More recorded time than the instrumented run took means durations summed across more
        // workers than recorded; the aggregate refuses it, as the per-task sentence does.
        paybackMap(dir, tests = 10, nanosEach = 1_000_000_000, workers = 1)
        val result = Audit.audit(dir, ":app:test", "test")

        val contribution = Audit.taskPayback(
            ":app:test", result, Payback.ConfigureCost(400_000_000, 1),
            toll = Audit.Toll.measured(instrumented = 1.0, uninstrumented = 1.0, floor = 0.0),
        )

        assertFalse(contribution.answered)
        assertContains(contribution.missing ?: "", "more recorded test time")
    }

    @Test
    fun `an unreadable task record is a refusal, not a task that never ran`(@TempDir dir: File) {
        // Dropping it would shrink the denominator silently. Empty is not the same as unknown, and
        // this reader is the one that decides what "N of M answered" means.
        val root = File(dir, "yoriwake").apply { mkdirs() }
        val broken = File(root, "test-aaaa").apply { mkdirs() }
        File(broken, "payback.tsv").writeText("no tabs here at all\n")

        val read = Payback.readTasks(root)

        assertEquals(1, read.size, "an unreadable record was dropped: $read")
        assertFalse(read[0].answered)
        assertContains(read[0].missing ?: "", "names no task")
    }

    @Test
    fun `a composition counts the build's test tasks, not the ones that happen to have a record`() {
        // Otherwise one record in a ten-module build reads "1 of 1 answered", which looks complete.
        val lines = Payback.aggregateLines(
            listOf(answering(":a:test", 10_000_000_000, 100_000_000)),
            forcedShare = "0.5",
            configured = 10,
        )

        val composition = lines.single { it.contains("`Test` task(s) with a record") }
        assertContains(composition, "this build configured 10")
    }

    @Test
    fun `no forced-run share makes selection pay when the cost exceeds the whole saving`() {
        // A clamped break-even would name a share below 0%, which does not exist.
        val lines = Payback.aggregateLines(
            listOf(answering(":a:test", 1_000_000_000, 2_000_000_000)),
            forcedShare = "0.1",
        )

        assertContains(lines[1], "cannot pay at ANY forced-run share")
        assertFalse(lines[1].contains("0.0%"))
    }

    @Test
    fun `a configure cost under a millisecond per task is not rounded away`(@TempDir dir: File) {
        // A small configure cost spread over many Test tasks: whole milliseconds would print "0ms",
        // and every suite with a recorded duration would then pass.
        paybackMap(dir, tests = 10, nanosEach = 1_000_000, workers = 1)

        val text = payback(dir, cost = Payback.ConfigureCost(nanos = 37_000_000, testTasks = 41))
            .text()

        // Asserted as the whole figure: "0.90ms" ends with "0ms", so a substring check for the
        // rounded form matches the correct output too.
        assertContains(text, "0.90ms of configure cost")
        assertFalse(text.contains(" 0ms "), "a real cost was rounded to nothing")
    }

    @Test
    fun `a configure cost larger than the saving says selection cannot pay`(@TempDir dir: File) {
        // The other branch of the verdict: ten 1ms tests against two seconds of configure cost a
        // task.
        paybackMap(dir, tests = 10, nanosEach = 1_000_000, workers = 1)

        val text = payback(dir, cost = Payback.ConfigureCost(nanos = 2_000_000_000, testTasks = 1))
            .text()

        assertContains(text, "cannot pay on this suite as it stands")
        assertFalse(text.contains("so selection can pay here"))
    }

    @Test
    fun `the saving names how many records carried a duration when some did not`(
        @TempDir dir: File,
    ) {
        // The share counts every record and the seconds only the timed ones, so the denominator is
        // stated.
        map(
            dir,
            timed("t1", 1_000_000_000, "com.acme.A"),
            timed("t2", 0, "com.acme.B"),
        )
        Audit.TaskFacts.write(dir, Audit.TaskFacts.PLATFORM, null)
        Audit.TaskFacts.recordWorkers(dir, 1)

        assertContains(payback(dir).text(), "1 of 2 test records carried a duration")
    }

    @Test
    fun `audit json carries the recorded time on the payback path too`(@TempDir dir: File) {
        // The payload must carry the figure the prose on the same run quotes.
        paybackMap(dir, tests = 4, nanosEach = 1_000_000_000, workers = 2)

        Audit.write(dir, payback(dir))
        val json = File(dir, Audit.AUDIT_FILE).readText()

        assertContains(json, """"nanos": 4000000000""")
        assertContains(json, """"workers": 2""")
    }

    private val platform = Audit.TaskFacts(Audit.TaskFacts.PLATFORM, null)

    @Test
    fun `a build whose git could not answer is a blocker, before any map-shaped one`() {
        // The build forced everything and the map is irrelevant to why, so git is reported first.
        val blockers = Audit.blockers(
            Audit.TaskFacts(Audit.TaskFacts.PLATFORM, null),
            gitUnavailable = true,
        )

        val blocker = blockers.first()
        assertEquals("git-unavailable", blocker.token, "the git outage must come before the map's")
        assertContains(blocker.detail, "every task ran in full")
        assertContains(assertNotNull(blocker.remedy), "git")

        assertTrue(
            Audit.blockers(Audit.TaskFacts(Audit.TaskFacts.PLATFORM, null))
                .none { it.token == "git-unavailable" },
            "a build whose git answered must not carry the blocker",
        )
    }

    @Test
    fun `a task off the JUnit Platform is named, with the script that fixes it`() {
        // Off the Platform the PostDiscoveryFilter is never consulted, so every test runs.
        val blockers = Audit.blockers(Audit.TaskFacts("JUnit 4, off the Platform", null))

        val blocker = blockers.single { it.token == "not-on-junit-platform" }
        assertContains(blocker.detail, "JUnit 4")
        assertContains(assertNotNull(blocker.remedy), "junit-vintage.init.gradle.kts")
        // The honesty condition travels with the remedy or the remedy is a trap: a vintage run is
        // evidence about the suite it actually runs, which is not the one that was failing.
        assertContains(blocker.remedy, "only honest evidence about the suite it")
    }

    @Test
    fun `a build with no SMAP is named as that, and not as a spent budget`() {
        val blockers = Audit.blockers(
            platform,
            inlineScan = InlineScan.Refused(
                "925 classes scanned, 13 of them Kotlin, and none anywhere carries a " +
                    "SourceDebugExtension",
                InlineScan.Kind.SMAP_ABSENT,
            ),
        )

        val blocker = blockers.single { it.token == "smap-absent" }
        assertContains(blocker.detail, "13 of them Kotlin")
        assertContains(assertNotNull(blocker.remedy), "-Xno-source-debug-extension")
        // The other cause: Kotlin that calls no inline function.
        assertContains(blocker.remedy!!, "Kotlin classes call no `inline` function")
    }

    @Test
    fun `an unfinished scan is a different blocker from a build with no signal`() {
        val blockers = Audit.blockers(
            platform,
            inlineScan = InlineScan.Refused("/tmp/x.jar could not be scanned", InlineScan.Kind.INCOMPLETE),
        )

        assertEquals(listOf("inline-scan-incomplete"), blockers.map { it.token })
    }

    @Test
    fun `a classpath this instrument cannot resolve is not reported as the selector's blocker`() {
        // The audit runs no tasks, so an Android unit-test classpath produced by a transform is not
        // resolvable when it asks; a selecting build resolves it later. That is the audit's limit,
        // not a blocker for the selector.
        val blockers = Audit.blockers(
            platform,
            inlineScan = InlineScan.Refused(
                "the classpath could not be resolved here: " +
                    "org.gradle.api.InvalidUserCodeException: Querying the mapped value of task " +
                    "':app:transformStandardDebugUnitTestClassesWithAsm' property 'jarsOutputDir' " +
                    "before task ':app:transformStandardDebugUnitTestClassesWithAsm' has completed",
                InlineScan.Kind.CLASSPATH_UNRESOLVED,
            ),
        )

        assertEquals(listOf("classpath-unresolved-here"), blockers.map { it.token })
        val blocker = blockers.single()
        assertContains(blocker.detail, "jarsOutputDir")
        assertContains(assertNotNull(blocker.remedy), "runs no tasks")
    }

    @Test
    fun `a scan that finished is not a blocker at all`() {
        assertEquals(emptyList(), Audit.blockers(platform, inlineScan = InlineScan.Found(emptySet())))
    }

    @Test
    fun `the plugin declining to configure the task is the first thing reported`() {
        // Nothing else was even attempted on this task: no agent, no map directory, no selection.
        val blockers = Audit.blockers(
            Audit.TaskFacts(null, null),
            scopeRefusal = ScopeOutcome.NotApplied(
                "no packages were found in this build's sources, so no scope could be derived",
                ScopeOutcome.NotApplied.Kind.NO_PACKAGES_DERIVED,
            ),
        )

        assertEquals("scope-not-applied", blockers.first().token)
        // Groovy and Scala; Kotlin Multiplatform has a kind of its own.
        assertContains(assertNotNull(blockers.first().remedy), "Groovy")
    }

    @Test
    fun `a multiplatform module is declined under its own token, with the layout it needs`() {
        val blockers = Audit.blockers(
            Audit.TaskFacts(null, null),
            scopeRefusal = ScopeOutcome.NotApplied(
                "this is a Kotlin Multiplatform module",
                ScopeOutcome.NotApplied.Kind.KOTLIN_MULTIPLATFORM,
            ),
        )

        // Its own token: `scope-not-applied` names no limit and `no-packages-derived` the wrong
        // one.
        assertEquals("kotlin-multiplatform", blockers.first().token)
        val remedy = assertNotNull(blockers.first().remedy)
        // What is supported, and what the user can do.
        assertContains(remedy, "src/jvmMain")
        assertContains(remedy, "accept full runs")
    }

    @Test
    fun `a build with tests and no host plugin is told which plugin to apply`() {
        val blockers = Audit.blockers(
            Audit.TaskFacts(null, null),
            scopeRefusal = ScopeOutcome.NotApplied(
                "this project has test tasks but applies none of them",
                ScopeOutcome.NotApplied.Kind.NO_HOST_PLUGIN,
            ),
        )

        assertEquals("no-host-plugin", blockers.first().token)
        assertContains(assertNotNull(blockers.first().remedy), "apply the `java` plugin")
    }

    @Test
    fun `Isolated Projects is named with its own token and the reason there is no remedy`() {
        val blockers = Audit.blockers(
            Audit.TaskFacts(null, null),
            scopeRefusal = ScopeOutcome.NotApplied(
                "Isolated Projects is enabled",
                ScopeOutcome.NotApplied.Kind.ISOLATED_PROJECTS,
            ),
        )

        assertEquals("isolated-projects", blockers.first().token)
        val remedy = assertNotNull(blockers.first().remedy)
        assertContains(remedy, "no remedy")
        // The choice the owner actually has, rather than a limitation stated at them.
        assertContains(remedy, "Turn the feature off")
    }

    @Test
    fun `a disabled jacoco extension points at how a build is likely to gate it`() {
        val blockers = Audit.blockers(
            Audit.TaskFacts(null, null),
            scopeRefusal = ScopeOutcome.NotApplied(
                ":core:test has a jacoco extension and it is DISABLED",
                ScopeOutcome.NotApplied.Kind.JACOCO_DISABLED,
            ),
        )

        assertEquals("jacoco-disabled", blockers.first().token)
        // Where to look is the difference between a diagnosis and a symptom: a convention plugin
        // can switch coverage off unless a property says otherwise.
        assertContains(assertNotNull(blockers.first().remedy), "project property")
    }

    @Test
    fun `every decline kind carries a token of its own, so the audit can recognise one`() {
        // The audit decides whether to say "run `test` once to capture a map" by looking the
        // blocker's token up in a set derived from these kinds. A kind whose token duplicated
        // another, or an empty one, would put a task in the wrong branch silently.
        val tokens = ScopeOutcome.NotApplied.Kind.entries.map { it.token }

        assertEquals(tokens.size, tokens.toSet().size, "two kinds share a token")
        assertTrue(tokens.none { it.isBlank() }, "a kind has no token")
    }

    @Test
    fun `in-JVM parallelism is named with where it was switched on`() {
        val blockers = Audit.blockers(Audit.TaskFacts(Audit.TaskFacts.PLATFORM, "a JVM argument (-Dx=true)"))

        val blocker = blockers.single { it.token == "in-jvm-parallelism" }
        assertContains(blocker.detail, "-Dx=true")
        // The remedy says what is not affected, or a reader turns off parallel forks too.
        assertContains(assertNotNull(blocker.remedy), "maxParallelForks")
    }

    @Test
    fun `a capture that recorded nothing is a blocker, not a suggestion to capture again`() {
        // Advising another capture would never terminate: capturing again records nothing again.
        val blocker = Audit.blockers(Audit.TaskFacts(Audit.TaskFacts.PLATFORM, null, recordedCoverage = false))
            .single { it.token == "recorded-no-coverage" }

        assertContains(blocker.detail, "no coverage")
        assertContains(assertNotNull(blocker.remedy), "ServiceLoader")
    }

    @Test
    fun `a truncated task-facts file loses facts rather than looking damaged, which is why it is written atomically`(
        @TempDir dir: File,
    ) {
        // Invisible otherwise: the format is `key=value` per line, so a half-written file parses
        // with fewer facts, each reading as "not recorded", and the audit refuses what it could
        // answer.
        Audit.TaskFacts.write(dir, Audit.TaskFacts.PLATFORM, null)
        Audit.TaskFacts.recordWorkers(dir, 4)
        val whole = File(dir, Audit.TaskFacts.FILE).readText()
        assertEquals(4, Audit.TaskFacts.read(dir).workers)

        File(dir, Audit.TaskFacts.FILE).writeText(whole.substringBefore("workers="))

        assertNull(
            Audit.TaskFacts.read(dir).workers,
            "a truncated file reported a worker count it does not carry",
        )
        assertEquals(
            Audit.TaskFacts.PLATFORM,
            Audit.TaskFacts.read(dir).framework,
            "the truncation was not detectable at all -- it parses, which is the point",
        )

        // And the writer leaves no half-file behind: the content goes to a temporary and is renamed
        // over the target, so a reader sees the whole of one version or the whole of the other.
        Audit.TaskFacts.recordWorkers(dir, 2)
        assertEquals(
            emptyList(),
            dir.listFiles().orEmpty().map(File::getName).filter { it.endsWith(".tmp") },
            "a temporary file survived the write",
        )
        assertEquals(whole.replace("workers=4", "workers=2"), File(dir, Audit.TaskFacts.FILE).readText())
    }

    @Test
    fun `a refused merge is its own blocker, with a remedy, and is not a capture that recorded nothing`() {
        // The two facts have different causes and opposite remedies. A refused merge means the run
        // recorded fine and the map was left alone -- so it keeps answering from before that run,
        // which looks exactly like a healthy map. Telling that owner their engine is untaught sends
        // them to fix something that is not broken.
        val blockers = Audit.blockers(
            Audit.TaskFacts(Audit.TaskFacts.PLATFORM, null, decodeRefused = "NoSuchFileException: x")
        )

        val blocker = blockers.single { it.token == "decode-refused" }
        assertContains(blocker.detail, "NoSuchFileException: x")
        assertContains(assertNotNull(blocker.remedy), AgentContract.RAW_DIR)
        assertTrue(
            blockers.none { it.token == "recorded-no-coverage" },
            "a refused merge was reported as a capture that recorded nothing",
        )
    }

    @Test
    fun `a decode that succeeded afterwards leaves no refusal behind`(@TempDir dir: File) {
        // A fact that outlives its cause is the stale-fact defect. Recorded, then cleared by the
        // success, and read back from the file rather than from the object that wrote it.
        Audit.TaskFacts.recordDecodeRefusal(dir, "NoSuchFileException: x")
        assertEquals("NoSuchFileException: x", Audit.TaskFacts.read(dir).decodeRefused)

        Audit.TaskFacts.recordDecodeRefusal(dir, null)

        assertEquals(null, Audit.TaskFacts.read(dir).decodeRefused)
        assertTrue(
            Audit.blockers(Audit.TaskFacts.read(dir)).none { it.token == "decode-refused" },
            "the refusal survived the decode that cleared it",
        )
    }

    @Test
    fun `a capture that has never run is not reported as one that recorded nothing`() {
        // Null is "no capture has run", false is "one ran and recorded nothing". Collapsing them
        // would report every project as recording nothing on its first day.
        assertEquals(
            emptyList(),
            Audit.blockers(Audit.TaskFacts(Audit.TaskFacts.PLATFORM, null, recordedCoverage = null)),
        )
        assertEquals(
            emptyList(),
            Audit.blockers(Audit.TaskFacts(Audit.TaskFacts.PLATFORM, null, recordedCoverage = true)),
        )
    }

    @Test
    fun `recording coverage keeps the framework the run already established`() {
        // Two writers, one file: `write` runs in doFirst and `recordCoverage` in the finalizer, and
        // neither may erase what the other recorded.
        val dir = createTempDir()
        try {
            Audit.TaskFacts.write(dir, Audit.TaskFacts.PLATFORM, "a JVM argument (-Dx=true)")
            Audit.TaskFacts.recordCoverage(dir, true)

            val facts = Audit.TaskFacts.read(dir)
            assertEquals(Audit.TaskFacts.PLATFORM, facts.framework)
            assertEquals("a JVM argument (-Dx=true)", facts.parallelism)
            assertEquals(true, facts.recordedCoverage)

            // And the other direction: a second run's doFirst must not erase what the first
            // recorded, because the fact it carries is about the last capture and not this one.
            Audit.TaskFacts.write(dir, Audit.TaskFacts.PLATFORM, null)
            assertEquals(true, Audit.TaskFacts.read(dir).recordedCoverage)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a build with no SMAP does not claim to stop a Java-only change`() {
        // A Java-only change set still narrows; only a changed Kotlin source forces.
        val blocker = Audit.blockers(
            platform,
            inlineScan = InlineScan.Refused("1063 classes scanned", InlineScan.Kind.SMAP_ABSENT),
        ).single { it.token == "smap-absent" }

        assertContains(blocker.detail, "Java sources alone is unaffected")
    }

    @Test
    fun `the coverage ratio compares the module's own classes, not the whole map`(@TempDir dir: File) {
        // The instrumentation scope spans every project, so the whole map against one module's
        // output would give a ratio above any floor.
        val mapDir = map(
            dir,
            record("[engine:junit-jupiter]/[class:A]/[method:t()]", "com.acme.Seen", "other.Lots"),
            record("[engine:junit-jupiter]/[class:B]/[method:t()]", "other.More", "other.Still"),
        )
        val own = setOf("com.acme.Seen", "com.acme.Unseen", "com.acme.AlsoUnseen")

        val (seen, total) = assertNotNull(Audit.ownClassCoverage(mapDir, own))

        // One of three. Counting every class in the map would see four, mostly from other modules.
        assertEquals(1, seen, "only one of the module's own classes is in the map")
        assertEquals(3, total)
    }

    @Test
    fun `a coverage ratio nobody could establish is not a finding`(@TempDir dir: File) {
        // Empty is not unknown. A module whose layout was not recognised yields no names, and a
        // ratio against a number nobody counted must produce no blocker rather than a certain one.
        assertNull(Audit.ownClassCoverage(map(dir, record("[engine:x]/[class:A]", "com.acme.A")), emptySet()))
        assertNull(Audit.ownClassCoverage(File(dir, "absent"), setOf("com.acme.A")))
    }

    @Test
    fun `a blocker's text is one line, so audit-json stays parseable`() {
        // Details can interpolate a throwable's message, which may carry a newline; the console
        // report prints one blocker per line.
        val blocker = Audit.blockers(
            platform,
            inlineScan = InlineScan.Refused("could not read\n  at Foo.bar(Foo.java:1)", InlineScan.Kind.INCOMPLETE),
        ).single()

        assertFalse(blocker.detail.contains('\n'), blocker.detail)
        assertContains(blocker.detail, "at Foo.bar")
    }

    @Test
    fun `a map that knows almost nothing about the module is reported without a remedy`() {
        // There is no fix, and the audit must not invent one.
        val blocker = Audit.blockers(platform, compiledClasses = 1000, mapClasses = 100)
            .single { it.token == "classes-absent-from-map" }

        assertContains(blocker.detail, "100 of the 1000 classes")
        assertNull(blocker.remedy, "there is no remedy for this and inventing one would be worse")
    }

    @Test
    fun `a map that covers most of the module is not reported at all`() {
        assertEquals(emptyList(), Audit.blockers(platform, compiledClasses = 1000, mapClasses = 900))
    }

    @Test
    fun `a count nobody established is never a finding`() {
        // "Could not look" is not "zero classes": a null on either side produces no blocker.
        assertEquals(emptyList(), Audit.blockers(platform, compiledClasses = null, mapClasses = 0))
        assertEquals(emptyList(), Audit.blockers(platform, compiledClasses = 1000, mapClasses = null))
    }

    @Test
    fun `a project with no blockers says so rather than saying nothing`(@TempDir dir: File) {
        val result = Audit.audit(
            map(dir, record("[engine:junit-jupiter]/[class:A]/[method:t()]", "com.acme.A")),
            taskPath = ":app:test", captureTask = "test",
        )

        assertContains(result.text(), "Nothing was found stopping this task from narrowing")
        // A blocker list is a fact about the build; it is not permission to imply payback.
        assertFalse(
            result.text().contains("you should"),
            "the audit must not turn a blocker list into a recommendation: ${result.text()}",
        )
    }

    @Test
    fun `blockers are reported even when the audit refuses for another reason`(@TempDir dir: File) {
        // The order this actually happens in on a new project: no map yet, so the arithmetic
        // refuses -- and the platform blocker is the thing they most need to be told, because it is
        // why the map they are about to capture will never narrow anything.
        val result = Audit.audit(
            File(dir, "absent"), ":app:test", "test",
            blockers = Audit.blockers(Audit.TaskFacts("TestNG", null)),
        )

        assertEquals(Audit.State.NO_CONCLUSION, result.state)
        assertContains(result.text(), "not-on-junit-platform")
    }

    @Test
    fun `the blockers reach audit-json, where a reader that is not a person can find them`(
        @TempDir dir: File,
    ) {
        val mapDir = map(dir, record("[engine:junit-jupiter]/[class:A]/[method:t()]", "com.acme.A"))
        val result = Audit.audit(
            mapDir, ":app:test", "test",
            blockers = Audit.blockers(Audit.TaskFacts("TestNG", null), compiledClasses = 10, mapClasses = 1),
        )
        Audit.write(mapDir, result)

        val json = File(mapDir, Audit.AUDIT_FILE).readText()
        assertContains(json, """"token": "not-on-junit-platform"""")
        assertContains(json, """"token": "classes-absent-from-map"""")
        assertContains(json, """"remedy": null""")
    }

    @Test
    fun `task facts survive a round trip, and an absent file is unknown rather than clean`(
        @TempDir dir: File,
    ) {
        assertEquals(Audit.TaskFacts(null, null), Audit.TaskFacts.read(dir))
        // A task that has never run must not read as "on the Platform, no parallelism" -- that is
        // the empty-is-not-unknown defect, and it would clear two blockers by not looking.
        assertEquals("framework-unknown", Audit.blockers(Audit.TaskFacts.read(dir)).single().token)

        Audit.TaskFacts.write(dir, Audit.TaskFacts.PLATFORM, null)
        assertEquals(Audit.TaskFacts(Audit.TaskFacts.PLATFORM, null), Audit.TaskFacts.read(dir))
        assertEquals(emptyList(), Audit.blockers(Audit.TaskFacts.read(dir)))

        Audit.TaskFacts.write(dir, "TestNG", "TestNG's parallel mode ('methods')")
        assertEquals(
            Audit.TaskFacts("TestNG", "TestNG's parallel mode ('methods')"),
            Audit.TaskFacts.read(dir),
        )
    }

    @Test
    fun `no map directory refuses and names the task that would capture one`(@TempDir dir: File) {
        val result = audit(File(dir, "absent"))

        assertEquals(Audit.State.NO_CONCLUSION, result.state)
        assertNull(result.distribution)
        // The remedy, not just the diagnosis. An adopter running this before their first build is
        // the expected case, not an error, and the next thing they should type is in the message.
        assertTrue(result.text().contains("test"), result.text())
        assertTrue(result.text().contains("no map"), result.text())
    }

    @Test
    fun `a schema mismatch refuses naming both versions and never reads the records`(@TempDir dir: File) {
        // The records below are perfectly good. They must not be described: a map written by a
        // schema this build does not read is a map whose fields may not mean what they appear to.
        map(dir, record("t1", "com.acme.A"), version = "${AgentContract.MAP_SCHEMA_VERSION + 1}")

        val result = audit(dir)

        assertEquals(Audit.State.NO_CONCLUSION, result.state)
        assertNull(result.distribution)
        assertTrue(result.text().contains("${AgentContract.MAP_SCHEMA_VERSION + 1}"), result.text())
        assertTrue(result.text().contains("${AgentContract.MAP_SCHEMA_VERSION}"), result.text())
        // Not fallen through to the arithmetic on the way past.
        assertEquals(0, result.counts.testRecords)
    }

    @Test
    fun `records with no version marker are a capture that died, not an old format`(@TempDir dir: File) {
        // As in MapReader: a missing version marker means the capture died, not that the format is
        // old.
        map(dir, record("t1", "com.acme.A"), version = null)

        val result = audit(dir)

        assertEquals(Audit.State.NO_CONCLUSION, result.state)
        assertTrue(result.text().contains("never finished"), result.text())
    }

    @Test
    fun `an empty coverage file reports zero usable records, never zero percent selected`(@TempDir dir: File) {
        map(dir)

        val result = audit(dir)

        assertEquals(Audit.State.NO_CONCLUSION, result.state)
        assertNull(result.distribution)
        assertEquals(0, result.counts.testRecords)
        // Empty is not unknown: a map holding nothing must never read as a suite nothing selects,
        // since a selector acting on that runs no tests.
        assertFalse(result.text().contains("0.0%"), result.text())
        assertFalse(result.text().contains("would be selected"), result.text())
    }

    @Test
    fun `all lines malformed is a distinct refusal from an empty map`(@TempDir dir: File) {
        map(dir, "this is not a record", "neither\tis\tthis")

        val result = audit(dir)

        assertEquals(Audit.State.NO_CONCLUSION, result.state)
        assertEquals(2, result.counts.malformed)
        assertEquals(2, result.counts.lines)
        assertEquals(0, result.counts.testRecords)
        // Distinct from the empty case: "0 of 2 lines were usable" sends a reader to the decoder,
        // "the map is empty" sends them to the capture. They are different bugs.
        assertTrue(result.text().contains("2"), result.text())
        assertTrue(result.text().contains("malformed"), result.text())
    }

    @Test
    fun `one malformed line among good ones is counted, not fatal`(@TempDir dir: File) {
        // Deliberately unlike MapReader, which refuses the entire map on one bad line. That is
        // right for a selector -- it cannot know what the line would have said -- and wrong for a
        // reporting instrument, whose job is to describe the map and say how much of it it could
        // not read. Both are conservative; they are conservative about different things.
        map(dir, record("t1", "com.acme.A"), "garbage", record("t2", "com.acme.B"))

        val result = audit(dir)

        assertEquals(Audit.State.NARROWING_ONLY, result.state)
        assertEquals(1, result.counts.malformed)
        assertEquals(2, result.counts.testRecords)
        assertTrue(result.text().contains("malformed"), result.text())
    }

    @Test
    fun `an unattributable share above the ceiling refuses -- the glide shape`(@TempDir dir: File) {
        // Robolectric's sandboxed classloader keeps JaCoCo's probes from reaching the agent, so
        // most records carry no classes; percentiles over the rest would describe a suite that runs
        // in full.
        val records = (1..80).map { record("empty$it") } +
            (1..20).map { record("real$it", "com.acme.A") }
        map(dir, *records.toTypedArray())

        val result = audit(dir)

        assertEquals(Audit.State.NO_CONCLUSION, result.state)
        assertNull(result.distribution)
        assertTrue(result.text().contains("80"), result.text())
        assertTrue(result.text().contains("100"), result.text())
        assertTrue(result.text().contains("RUN ON EVERY BUILD"), result.text())
    }

    @Test
    fun `an unattributable share just below the ceiling proceeds and still reports the share`(@TempDir dir: File) {
        val records = (1..10).map { record("empty$it") } +
            (1..90).map { record("real$it", "com.acme.A$it") }
        map(dir, *records.toTypedArray())

        val result = audit(dir, ceiling = 0.25)

        assertEquals(Audit.State.NARROWING_ONLY, result.state)
        val distribution = assertNotNull(result.distribution)
        assertEquals(10, distribution.unattributableTests)
        assertEquals(0.10, distribution.unattributableShare, 1e-9)
        // Proceeding is not the same as forgetting. The share below the ceiling still governs how
        // much every percentile beneath it is worth, so it stays in the output.
        assertTrue(result.text().contains("10"), result.text())
    }

    @Test
    fun `the ceiling is the boundary it says it is`(@TempDir dir: File) {
        // Refuses at the boundary: at exactly the limit the arithmetic is as untrustworthy as it is
        // one record later.
        val records = (1..25).map { record("empty$it") } +
            (1..75).map { record("real$it", "com.acme.A$it") }
        map(dir, *records.toTypedArray())

        assertEquals(Audit.State.NO_CONCLUSION, audit(dir, ceiling = 0.25).state)
        assertEquals(Audit.State.NARROWING_ONLY, audit(dir, ceiling = 0.26).state)
    }

    @Test
    fun `a healthy map reports the hand-computed distribution`(@TempDir dir: File) {
        // Four tests, four classes, by hand:
        //   com.acme.Hub  -> t1 t2 t3 t4   4 tests, share 100%
        //   com.acme.A    -> t1            1 test,  share 25%
        //   com.acme.B    -> t2            1 test,  share 25%
        //   com.acme.C    -> t3            1 test,  share 25%
        // counts sorted = [1, 1, 1, 4]; nearest-rank median at index round(0.5*3)=2 -> 1 test.
        // mean = (4+1+1+1)/4 classes = 1.75 tests, / 4 tests = 43.75%.
        map(
            dir,
            record("t1", "com.acme.Hub", "com.acme.A"),
            record("t2", "com.acme.Hub", "com.acme.B"),
            record("t3", "com.acme.Hub", "com.acme.C"),
            record("t4", "com.acme.Hub"),
        )

        val d = assertNotNull(audit(dir).distribution)

        assertEquals(4, d.tests)
        assertEquals(4, d.classes)
        assertEquals(1, d.medianTestsPerClass)
        assertEquals(0.25, d.medianShare, 1e-9)
        assertEquals(0.4375, d.meanShare, 1e-9)
        assertEquals(1.0, d.maxShare, 1e-9)
        assertEquals(1, d.hubClassesOverHalf)
    }

    @Test
    fun `a hub class appears in the hub list with its share`(@TempDir dir: File) {
        map(
            dir,
            record("t1", "com.acme.Hub", "com.acme.A"),
            record("t2", "com.acme.Hub", "com.acme.B"),
            record("t3", "com.acme.Hub"),
        )

        val d = assertNotNull(audit(dir).distribution)

        val hub = d.hubs.first()
        assertEquals("com.acme.Hub", hub.className)
        assertEquals(3, hub.tests)
        assertEquals(1.0, hub.share, 1e-9)
    }

    @Test
    fun `startup coverage is counted separately and never as a test`(@TempDir dir: File) {
        // Outcome NONE is startup coverage and class-scoped windows: real coverage the selector
        // uses to force, owned by no test. Counting it as a test would understate concentration.
        map(
            dir,
            record("startup", "com.acme.Boot", outcome = "NONE"),
            record("t1", "com.acme.A"),
            record("t2", "com.acme.B"),
        )

        val d = assertNotNull(audit(dir).distribution)

        assertEquals(2, d.tests)
        assertEquals(1, d.startupClasses)
        assertEquals(2, d.classes)
    }

    @Test
    fun `a class-scoped id appearing twice unions its windows rather than replacing them`(@TempDir dir: File) {
        map(
            dir,
            record("scoped", "com.acme.A", outcome = "NONE"),
            record("scoped", "com.acme.B", outcome = "NONE"),
            record("t1", "com.acme.X"),
        )

        val d = assertNotNull(audit(dir).distribution)

        // Two windows, two classes. Replacing would silently discard the earlier window's coverage.
        assertEquals(2, d.startupClasses)
    }

    @Test
    fun `the map-only verdict never makes a payback claim`(@TempDir dir: File) {
        map(dir, record("t1", "com.acme.A"), record("t2", "com.acme.B"))

        val text = audit(dir).text().lowercase()

        // The toll is not derivable from a map, so the map-only verdict must be unable to imply
        // payback.
        assertFalse(text.contains("worth it"), text)
        assertFalse(text.contains("do not enable"), text)
        assertTrue(text.contains("unmeasured"), text)
    }

    @Test
    fun `the verdict says no test covers a class only as an absent probe record`(@TempDir dir: File) {
        map(dir, record("t1", "com.acme.A"))

        val text = audit(dir).text().lowercase()

        // A class loaded, initialised and read but whose methods never ran has no entry at all,
        // indistinguishable from never loaded. "Untested" is a claim the map cannot support.
        assertFalse(text.contains("untested"), text)
    }

    @Test
    fun `shares are formatted with a decimal point in every locale`(@TempDir dir: File) {
        // A decimal-comma locale would print "27,3%", which breaks anything that parses or compares
        // a share.
        val default = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            map(dir, record("t1", "com.acme.A", "com.acme.B", "com.acme.C"), record("t2", "com.acme.A"))

            val text = audit(dir).text()

            assertTrue(text.contains("66.7%") || text.contains("50.0%"), text)
            assertFalse(Regex("""\d,\d%""").containsMatchIn(text), text)
        } finally {
            java.util.Locale.setDefault(default)
        }
    }

    // Two timings always differ, and arithmetic on them always produces a word; a pair is not
    // evidence until the difference clears the noise floor.
    private fun toll(instrumented: Double, uninstrumented: Double, floor: Double = Audit.DEFAULT_NOISE_FLOOR) =
        Audit.Toll.measured(instrumented, uninstrumented, floor)

    @Test
    fun `runs within the noise floor refuse, and print both timings`(@TempDir dir: File) {
        // 214s vs 231s is under 8% apart. The floor is 18%. This is exactly where a verdict matters
        // most and where a single pair is worth least.
        map(dir, record("t1", "com.acme.A"), record("t2", "com.acme.B"))

        val result = Audit.audit(dir, ":app:test", "test", toll = toll(231.0, 214.0))

        assertEquals(Audit.State.NO_CONCLUSION, result.state)
        val text = result.text()
        assertTrue(text.contains("231"), text)
        assertTrue(text.contains("214"), text)
        assertFalse(text.lowercase().contains("worth it"), text)
        assertFalse(text.lowercase().contains("do not enable"), text)
    }

    @Test
    fun `the floor is a threshold the difference must reach -- the documented boundary`(
        @TempDir dir: File,
    ) {
        // `share < floor` refuses, so a toll of exactly the floor concludes. The unattributable
        // ceiling refuses at its boundary because it compares integer counts; this is a ratio of
        // measured durations, which never lands on the floor exactly.
        map(dir, record("t1", "com.acme.A"), record("t2", "com.acme.B"))

        assertEquals(
            Audit.State.NO_CONCLUSION,
            Audit.audit(dir, ":app:test", "test", toll = toll(117.0, 100.0)).state,
            "just under the floor must refuse",
        )
        assertTrue(
            Audit.audit(dir, ":app:test", "test", toll = toll(118.0, 100.0)).state
                != Audit.State.NO_CONCLUSION,
            "at the floor, the difference has reached the threshold",
        )
    }

    @Test
    fun `an instrumented run that ran faster is never a negative toll`(@TempDir dir: File) {
        map(dir, record("t1", "com.acme.A"), record("t2", "com.acme.B"))

        val result = Audit.audit(dir, ":app:test", "test", toll = toll(180.0, 214.0))

        assertEquals(Audit.State.NO_CONCLUSION, result.state)
        // A negative toll would conclude worth it more confidently the more wrong the measurement
        // was. Matched as a negative number: ordinary hyphens appear in the prose.
        assertFalse(Regex("-[0-9]").containsMatchIn(result.text()), result.text())
        assertTrue(result.text().lowercase().contains("faster"), result.text())
    }

    @Test
    fun `a run that failed to build refuses and names which one`(@TempDir dir: File) {
        map(dir, record("t1", "com.acme.A"), record("t2", "com.acme.B"))

        val instrumented = Audit.audit(
            dir, ":app:test", "test", toll = Audit.Toll.failed("instrumented", "exit 1"))
        assertEquals(Audit.State.NO_CONCLUSION, instrumented.state)
        assertTrue(instrumented.text().contains("instrumented"), instrumented.text())

        val uninstrumented = Audit.audit(
            dir, ":app:test", "test", toll = Audit.Toll.failed("uninstrumented", "exit 1"))
        assertEquals(Audit.State.NO_CONCLUSION, uninstrumented.state)
        assertTrue(uninstrumented.text().contains("uninstrumented"), uninstrumented.text())
        // A failed run is never a time.
        assertFalse(uninstrumented.text().contains("0.0s"), uninstrumented.text())
    }

    @Test
    fun `a low toll against a narrow suite concludes it is worth it, with the arithmetic shown`(
        @TempDir dir: File,
    ) {
        // 100 classes each reached by one of 100 tests: a mean share of 1%. Toll 25%.
        val records = (1..100).map { record("t$it", "com.acme.A$it") }
        map(dir, *records.toTypedArray())

        val result = Audit.audit(dir, ":app:test", "test", toll = toll(125.0, 100.0))

        assertEquals(Audit.State.WORTH_IT, result.state)
        // The arithmetic, not just the word. A verdict a reader cannot check is an assertion.
        assertTrue(result.text().contains("1.0%"), result.text())
        assertTrue(result.text().contains("25.0%"), result.text())
    }

    @Test
    fun `a high toll against a wide suite concludes do not enable`(@TempDir dir: File) {
        // Every test reaches the same class: a mean share of 100%. Nothing can repay a 160% toll.
        val records = (1..20).map { record("t$it", "com.acme.Hub") }
        map(dir, *records.toTypedArray())

        val result = Audit.audit(dir, ":app:test", "test", toll = toll(260.0, 100.0))

        assertEquals(Audit.State.DO_NOT_ENABLE, result.state)
        assertTrue(result.text().contains("160.0%"), result.text())
    }

    @Test
    fun `an overridden floor is honoured and echoed`(@TempDir dir: File) {
        map(dir, record("t1", "com.acme.A"), record("t2", "com.acme.B"))

        // A 10% toll: refused at the default floor, allowed at a floor the caller lowered.
        assertEquals(
            Audit.State.NO_CONCLUSION,
            Audit.audit(dir, ":app:test", "test", toll = toll(110.0, 100.0)).state,
        )
        val lowered = Audit.audit(dir, ":app:test", "test", toll = toll(110.0, 100.0, floor = 0.05))
        assertTrue(lowered.state != Audit.State.NO_CONCLUSION, lowered.text())
        // Echoed, because it was measured on one machine and a reader has to know which floor the
        // verdict rests on.
        assertTrue(lowered.text().contains("5.0%"), lowered.text())
    }

    @Test
    fun `a refusal earlier than the toll wins, and the suite is never described`(@TempDir dir: File) {
        // A map that cannot be described cannot be given a payback verdict either, however good the
        // timings are. The order of refusals is part of the contract.
        map(dir, record("t1", "com.acme.A"), version = "${AgentContract.MAP_SCHEMA_VERSION + 1}")

        val result = Audit.audit(dir, ":app:test", "test", toll = toll(250.0, 100.0))

        assertEquals(Audit.State.NO_CONCLUSION, result.state)
        assertTrue(result.text().contains("schema version"), result.text())
    }

    @Test
    fun `the measured toll is never written into the map`(@TempDir dir: File) {
        map(dir, record("t1", "com.acme.A"), record("t2", "com.acme.B"))

        val result = Audit.audit(dir, ":app:test", "test", toll = toll(125.0, 100.0))
        Audit.write(dir, result)

        // audit.json may report the toll; the map may not. A toll belongs to a machine and a
        // moment, and caching it would let one build's toll answer for another.
        assertFalse(File(dir, "toll").exists())
        assertEquals(
            setOf(AgentContract.COVERAGE_FILE, AgentContract.MAP_SCHEMA_VERSION_FILE,
                  CoverageDecoder.CAPTURE_COMMIT_FILE, Audit.AUDIT_FILE),
            dir.listFiles()!!.map { it.name }.toSet(),
        )
    }

    @Test
    fun `the capture commit is reported and the writing plugin version is stated as unknown`(@TempDir dir: File) {
        map(dir, record("t1", "com.acme.A"), captureCommit = "deadbee")

        val result = audit(dir)

        assertEquals("deadbee", result.provenance.captureCommit)
        assertTrue(result.text().contains("deadbee"), result.text())
        // SCHEMA_VERSION stays 2 across capture-affecting changes and capture-commit is the host
        // project's commit, so nothing identifies the writing plugin; the audit says so.
        assertTrue(result.text().contains("plugin version"), result.text())
    }

    @Test
    fun `an absent capture commit makes the age unknown, never zero`(@TempDir dir: File) {
        map(dir, record("t1", "com.acme.A"), captureCommit = null)

        val result = audit(dir)

        assertNull(result.provenance.captureCommit)
        assertNull(result.provenance.ageDays)
        assertTrue(result.text().contains("unknown"), result.text())
        assertFalse(result.text().contains("0 days"), result.text())
    }

    @Test
    fun `audit json is written on a refusal and carries the same counts as the log`(@TempDir dir: File) {
        map(dir, "garbage", "more garbage")

        val result = audit(dir)
        Audit.write(dir, result)

        val json = File(dir, Audit.AUDIT_FILE).readText()
        assertTrue(json.contains("\"state\": \"NO CONCLUSION\""), json)
        assertTrue(json.contains("\"malformed\": 2"), json)
        assertTrue(json.contains("\"testRecords\": 0"), json)
        assertTrue(json.contains("\"version\": ${Audit.PAYLOAD_VERSION}"), json)
        // Null, not absent and not zero: a refusal has no distribution, and a reader must be able
        // to tell that from a distribution of zeroes.
        assertTrue(json.contains("\"distribution\": null"), json)
    }

    @Test
    fun `audit json carries the distribution on a conclusion`(@TempDir dir: File) {
        map(dir, record("t1", "com.acme.A"), record("t2", "com.acme.B"))

        val result = audit(dir)
        Audit.write(dir, result)

        val json = File(dir, Audit.AUDIT_FILE).readText()
        assertTrue(json.contains("\"state\": \"NARROWING ONLY\""), json)
        assertTrue(json.contains("\"medianShare\""), json)
        assertTrue(json.contains("\"captureCommit\": \"abcdef1\""), json)
    }

    @Test
    fun `a quote in a class name does not break the json`(@TempDir dir: File) {
        map(dir, record("t1", "com.acme.\"Odd"))

        Audit.write(dir, audit(dir))

        val json = File(dir, Audit.AUDIT_FILE).readText()
        assertTrue(json.contains("""com.acme.\"Odd"""), json)
    }
}
