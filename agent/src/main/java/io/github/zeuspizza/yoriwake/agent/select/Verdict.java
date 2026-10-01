package io.github.zeuspizza.yoriwake.agent.select;

/**
 * What the test-JVM filter did with one test, and which rule decided it: one row of
 * {@code decisions.tsv}. Only {@link #SKIPPED} excludes; every other verdict runs the test.
 */
public enum Verdict {
    /** The daemon refused this run, so nothing is deselected. */
    DAEMON_REFUSED,
    /** The run did not ask to select. */
    SELECTION_NOT_REQUESTED,
    /** Selection threw, so the test runs. */
    SELECTION_FAILED,
    /** Pinned by pattern or tag. */
    ALWAYS_RUN,
    /** Its engine cannot honour an exclusion; see {@link FilterRule#engineRunsEverything}. */
    ENGINE_RUNS_EVERYTHING,
    /** Excluded on its own, included because another test in its class is selected. */
    CLASS_GRANULARITY,
    /** See {@link Selector.Decision.Reason#FULL_RUN}. */
    FULL_RUN,
    /** See {@link Selector.Decision.Reason#REACHES_CHANGE}. */
    REACHES_CHANGE,
    /** See {@link Selector.Decision.Reason#SHARES_JVM_WITH_CHANGE}. */
    SHARES_JVM_WITH_CHANGE,
    /** See {@link Selector.Decision.Reason#NOT_KNOWN_TO_PASS}. */
    NOT_KNOWN_TO_PASS,
    /** See {@link Selector.Decision.Reason#NOT_IN_MAP}. */
    NOT_IN_MAP,
    /** See {@link Selector.Decision.Reason#SKIPPED}. */
    SKIPPED;

    /** The selector's own reason, as the row it becomes. */
    public static Verdict of(Selector.Decision.Reason reason) {
        switch (reason) {
            case FULL_RUN: return FULL_RUN;
            case REACHES_CHANGE: return REACHES_CHANGE;
            case SHARES_JVM_WITH_CHANGE: return SHARES_JVM_WITH_CHANGE;
            case NOT_KNOWN_TO_PASS: return NOT_KNOWN_TO_PASS;
            case NOT_IN_MAP: return NOT_IN_MAP;
            case SKIPPED: return SKIPPED;
            default: throw new IllegalArgumentException("no verdict for " + reason);
        }
    }

    public boolean included() {
        return this != SKIPPED;
    }

    /** The row's verdict column. */
    public String inclusionToken() {
        return included() ? "included" : "excluded";
    }

    /** The row's reason column. */
    public String reasonToken() {
        return name();
    }
}
