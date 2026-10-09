package io.github.zeuspizza.yoriwake.agent.platform;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The tests whose execution finished {@code SUCCESSFUL} or {@code FAILED} in this JVM's outermost
 * test plan, by unique id: the tests whose body ran, whether or not the run captures. A test that
 * aborted, was skipped, or never finished is not here, so a later run never takes it as having run.
 *
 * <p>Written by the attached copy of {@code PlatformEvents}, read by each decision record at exit.
 */
public final class RanTests {

    private RanTests() {}

    private static final Map<String, String> OUTCOMES = new ConcurrentHashMap<>();

    /** Records [testId] as having run when [status] is {@code SUCCESSFUL} or {@code FAILED}. */
    public static void record(String testId, String status) {
        if (testId != null && ("SUCCESSFUL".equals(status) || "FAILED".equals(status))) {
            OUTCOMES.put(testId, status);
        }
    }

    /** {@code SUCCESSFUL} or {@code FAILED} for a test that ran, null for any other. */
    public static String outcome(String testId) {
        return testId == null ? null : OUTCOMES.get(testId);
    }
}
