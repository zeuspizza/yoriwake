package io.github.zeuspizza.yoriwake.agent.engines;

import io.github.zeuspizza.yoriwake.agent.capture.JvmSession;
import java.lang.reflect.Method;

/**
 * Where the instrumented {@code RunNotifier} calls back to.
 *
 * <p>{@link JUnit4Hook} writes calls to these methods into JUnit's bytecode, so their names and
 * signatures are a contract no compiler checks. They take {@code Object} so this agent never
 * depends on a JUnit version. Everything is wrapped: a throw would fail the host's tests.
 */
public final class JUnit4Events {

    private JUnit4Events() {}

    /** Called at the head of {@code RunNotifier.fireTestStarted}. */
    public static void started(Object description) {
        try {
            String id = idOf(description);
            if (id != null) {
                JvmSession.EVENTS.started(id);
            }
        } catch (Throwable ignored) {
            // Capture is best-effort; the host's test run is not.
        }
    }

    /** Called at the head of {@code RunNotifier.fireTestFinished}. */
    public static void finished(Object description) {
        try {
            String id = idOf(description);
            if (id != null) {
                JvmSession.EVENTS.finished(id, null);
            }
        } catch (Throwable ignored) {
            // As above.
        }
    }

    /**
     * Called at the head of {@code RunNotifier.fireTestFailure}, which takes a {@code Failure}.
     *
     * <p>Recorded because a test not known to have passed is always re-run.
     */
    public static void failed(Object failure) {
        try {
            Method getDescription = failure.getClass().getMethod("getDescription");
            String id = idOf(getDescription.invoke(failure));
            if (id != null) {
                JvmSession.EVENTS.failed(id);
            }
        } catch (Throwable ignored) {
            // Left as an unknown outcome, which the selector resolves toward running the test.
        }
    }

    /**
     * A stable id for a JUnit 4 test.
     *
     * <p>Shaped like the Platform's unique ids so one map format serves every runner, under its own
     * engine name so a project migrating to the Platform sees new tests and runs them.
     */
    private static String idOf(Object description) throws Exception {
        Class<?> type = description.getClass();
        Object className = type.getMethod("getClassName").invoke(description);
        Object methodName = type.getMethod("getMethodName").invoke(description);
        if (className == null) {
            return null;
        }
        if (methodName == null) {
            // A suite or a class-level notification, which owns no single test.
            return null;
        }
        // Not "junit-vintage": that engine's ids are [runner:X]/[test:m(X)], and a second shape
        // under the same name would be a record that silently matches nothing.
        return "[engine:junit4]/[class:" + className + "]/[method:" + methodName + "]";
    }
}
