package io.github.zeuspizza.yoriwake.agent.select;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Whether a selecting run may leave a whole test class out, for a framework that can only run or
 * skip a class: from the tests the class holds now, a verdict for each and one answer for the class.
 *
 * <p>The class is left out only when it holds at least one test and every one is {@link
 * Verdict#SKIPPED}. Otherwise it runs whole, and a test that would have been skipped on its own is
 * recorded {@link Verdict#CLASS_GRANULARITY}. A class with no test runs, so whatever its runner
 * does with it is not hidden.
 */
public final class ClassVerdicts {

    private final boolean leaveOut;
    private final Map<String, Verdict> verdicts;

    private ClassVerdicts(boolean leaveOut, Map<String, Verdict> verdicts) {
        this.leaveOut = leaveOut;
        this.verdicts = Collections.unmodifiableMap(verdicts);
    }

    /** Whether the class is left out. */
    public boolean leaveOut() {
        return leaveOut;
    }

    /** Each test's verdict, in the order the ids were given. */
    public Map<String, Verdict> verdicts() {
        return verdicts;
    }

    /**
     * Judges the class whose tests are {@code ids}. {@code pinned} says whether a test is always
     * run; {@code keepReason}, when not null, is why the caller already knows the class runs whole,
     * and its tests are then recorded {@link Verdict#CLASS_RUNS_WHOLE}.
     */
    public static ClassVerdicts of(UnaryOperator<String> inputs, Selector.Decision decision,
            Predicate<String> pinned, List<String> ids, String keepReason) {
        Map<String, Verdict> verdicts = new LinkedHashMap<>();
        boolean allSkipped = !ids.isEmpty();
        for (String id : ids) {
            Verdict verdict = verdictFor(inputs, decision, pinned, id, keepReason);
            verdicts.put(id, verdict);
            allSkipped &= verdict == Verdict.SKIPPED;
        }
        if (allSkipped) {
            return new ClassVerdicts(true, verdicts);
        }
        // Rounding up to the class only ever includes, so it cannot lose a failure.
        for (Map.Entry<String, Verdict> entry : verdicts.entrySet()) {
            if (entry.getValue() == Verdict.SKIPPED) {
                entry.setValue(Verdict.CLASS_GRANULARITY);
            }
        }
        return new ClassVerdicts(false, verdicts);
    }

    /** In the Platform filter's order: the whole run's state, then pins, then the selector. */
    private static Verdict verdictFor(UnaryOperator<String> inputs, Selector.Decision decision,
            Predicate<String> pinned, String id, String keepReason) {
        Verdict verdict = FilterRule.verdictFor(inputs, decision, id, true);
        if (verdict == Verdict.DAEMON_REFUSED || verdict == Verdict.SELECTION_NOT_REQUESTED) {
            return verdict;
        }
        if (pinned.test(id)) {
            return Verdict.ALWAYS_RUN;
        }
        if (verdict == Verdict.FULL_RUN) {
            return verdict;
        }
        return keepReason != null ? Verdict.CLASS_RUNS_WHOLE : verdict;
    }
}
