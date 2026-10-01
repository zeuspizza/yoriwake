package io.github.zeuspizza.yoriwake.agent.capture;

/**
 * Measures what per-test attribution costs, from inside the JVM that pays for it.
 *
 * <p>Timing the calls directly, rather than differencing two whole-suite wall clocks, keeps the
 * ratio stable on a busy machine: contention slows numerator and denominator together.
 *
 * <p>Counts time inside {@code getExecutionData} and writing records, not GC pressure or cache
 * effects, so the figure is a lower bound on the true cost.
 */
final class OverheadMeter {

    private long agentNanos;
    private long writeNanos;
    private long planStartedAtNanos;
    private long planNanos;
    private int agentCalls;

    void planStarted() {
        planStartedAtNanos = System.nanoTime();
    }

    void planFinished() {
        if (planStartedAtNanos != 0L) {
            planNanos = System.nanoTime() - planStartedAtNanos;
        }
    }

    long recordAgentCall(long startedAtNanos) {
        long elapsed = System.nanoTime() - startedAtNanos;
        agentNanos += elapsed;
        agentCalls++;
        return elapsed;
    }

    void recordWrite(long startedAtNanos) {
        writeNanos += System.nanoTime() - startedAtNanos;
    }

    long totalOverheadNanos() {
        return agentNanos + writeNanos;
    }

    long agentNanos() {
        return agentNanos;
    }

    long writeNanos() {
        return writeNanos;
    }

    long planNanos() {
        return planNanos;
    }

    int agentCalls() {
        return agentCalls;
    }

    /**
     * Overhead as a fraction of the test plan's own wall time.
     *
     * <p>The denominator is the plan, not the whole build, whose configuration time this tool does
     * not cause and would only dilute the figure.
     */
    double fractionOfPlan() {
        return planNanos == 0L ? 0.0 : (double) totalOverheadNanos() / planNanos;
    }

    /**
     * Mean cost per real test.
     *
     * <p>Unattributed records (setup windows) are excluded from the divisor.
     */
    long nanosPerTest(int tests) {
        return tests <= 0 ? 0L : totalOverheadNanos() / tests;
    }

    /**
     * One line summarising what capture cost this worker.
     *
     * <p>Every count is per worker, since each forked test JVM runs its own listener;
     * {@code records} and {@code unattributed} make that scope explicit.
     */
    String render(int records, int unattributed, int failures) {
        int tests = Math.max(0, records - unattributed);
        return String.format(
                "captureOverhead worker agentMs=%d writeMs=%d planMs=%d calls=%d records=%d "
                        + "tests=%d unattributed=%d failures=%d perTestUs=%d fraction=%.4f",
                agentNanos / 1_000_000,
                writeNanos / 1_000_000,
                planNanos / 1_000_000,
                agentCalls,
                records,
                tests,
                unattributed,
                failures,
                nanosPerTest(tests) / 1_000,
                fractionOfPlan());
    }
}
