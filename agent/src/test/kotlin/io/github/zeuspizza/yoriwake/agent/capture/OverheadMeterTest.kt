package io.github.zeuspizza.yoriwake.agent.capture

import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OverheadMeterTest {

    @Test
    fun `sums agent and write time into the total`() {
        val meter = OverheadMeter()
        val now = System.nanoTime()

        meter.recordAgentCall(now - 5_000_000)
        meter.recordAgentCall(now - 3_000_000)
        meter.recordWrite(now - 2_000_000)

        assertTrue(meter.agentNanos() >= 8_000_000, "agent time is the sum of both calls")
        assertTrue(meter.writeNanos() >= 2_000_000)
        assertEquals(meter.agentNanos() + meter.writeNanos(), meter.totalOverheadNanos())
        assertEquals(2, meter.agentCalls())
    }

    @Test
    fun `fraction is overhead over the test plan's own wall time`() {
        val meter = OverheadMeter()
        meter.planStarted()
        meter.recordAgentCall(System.nanoTime() - 10_000_000)
        Thread.sleep(20)
        meter.planFinished()

        // The exact ratio depends on scheduling; it must be a real fraction, not unbounded or zero.
        assertTrue(meter.fractionOfPlan() > 0.0)
        assertTrue(meter.fractionOfPlan() < 1.0)
    }

    @Test
    fun `fraction is zero rather than infinite when the plan was never timed`() {
        val meter = OverheadMeter()
        meter.recordAgentCall(System.nanoTime() - 1_000_000)

        assertEquals(0.0, meter.fractionOfPlan())
    }

    @Test
    fun `per-test cost divides by real tests only`() {
        val meter = OverheadMeter()
        meter.recordAgentCall(System.nanoTime() - 10_000_000)

        val perTest = meter.nanosPerTest(10)

        assertTrue(perTest in 900_000..2_000_000, "expected roughly a tenth of 10ms, got $perTest")
    }

    @Test
    fun `render excludes unattributed records from the test count`() {
        // Dividing by total records would understate per-test cost by however many setup windows
        // the run happened to contain.
        val rendered = OverheadMeter().render(10, 4, 0)

        assertContains(rendered, "tests=6")
    }

    @Test
    fun `render surfaces the failure count`() {
        // A capture with failures produced an incomplete map; it must not read as a clean run.
        assertContains(OverheadMeter().render(5, 0, 3), "failures=3")
    }

    @Test
    fun `per-test cost is zero rather than a division error when nothing was captured`() {
        assertEquals(0L, OverheadMeter().nanosPerTest(0))
    }

    @Test
    fun `renders every field the report needs`() {
        val meter = OverheadMeter()
        meter.planStarted()
        meter.recordAgentCall(System.nanoTime() - 1_000_000)
        meter.planFinished()

        val rendered = meter.render(2, 1, 0)

        listOf(
            "agentMs=", "writeMs=", "planMs=", "calls=",
            // records/tests/unattributed make the scope explicit: the line is one worker's, not the
            // suite's.
            "records=2", "tests=1", "unattributed=1", "failures=0", "perTestUs=", "fraction=",
        ).forEach { assertContains(rendered, it) }
    }
}
