package io.github.zeuspizza.yoriwake.agent.engines;

import io.github.zeuspizza.yoriwake.agent.capture.JvmSession;
import org.testng.IExecutionListener;
import org.testng.ITestListener;
import org.testng.ITestResult;

/**
 * Per-test capture for TestNG, discovered by {@code ServiceLoader} from the agent jar's
 * {@code META-INF/services}, so no instrumentation is needed.
 *
 * <p>TestNG is {@code compileOnly}: only TestNG ever loads this class, so it is always present then,
 * and the agent ships nothing a host could collide with. Boundaries feed {@link JvmSession}.
 */
public final class TestNgEvents implements ITestListener, IExecutionListener {

    /**
     * TestNG's own switch for a run that reports every test without running one. The JUnit
     * Platform's TestNG engine sets it while it discovers, before the Platform's plan starts:
     * taken for real starts, that dry run would hand this listener the JVM's capture, and the
     * Platform's listener, which sees every engine, would record nothing.
     */
    private static final String DRY_RUN_PROPERTY = "testng.mode.dryrun";

    /** Declared rather than inherited: an older TestNG gives this method no default. */
    @Override
    public void onExecutionStart() {}

    /** After every suite, and never on a JVM that exits first: the moment capture is complete. */
    @Override
    public void onExecutionFinish() {
        if (dryRun()) {
            return;
        }
        JvmSession.EVENTS.runFinished();
    }

    @Override
    public void onTestStart(ITestResult result) {
        if (dryRun()) {
            return;
        }
        String id = idOf(result);
        if (id != null) {
            JvmSession.EVENTS.started(id);
        }
    }

    @Override
    public void onTestSuccess(ITestResult result) {
        close(result, false);
    }

    @Override
    public void onTestFailure(ITestResult result) {
        close(result, true);
    }

    /**
     * A skipped test closes its window like any other.
     *
     * <p>{@code onTestStart} has already fired, so leaving it open would credit its coverage to the
     * next test.
     */
    @Override
    public void onTestSkipped(ITestResult result) {
        close(result, true);
    }

    @Override
    public void onTestFailedButWithinSuccessPercentage(ITestResult result) {
        close(result, true);
    }

    /**
     * Closes a test's window, recording failures as failures.
     *
     * <p>Recorded because a test not known to have passed is always re-run.
     */
    private void close(ITestResult result, boolean failed) {
        if (dryRun()) {
            return;
        }
        String id = idOf(result);
        if (id == null) {
            return;
        }
        if (failed) {
            JvmSession.EVENTS.failed(id);
        }
        JvmSession.EVENTS.finished(id, null);
    }

    private static boolean dryRun() {
        try {
            return Boolean.parseBoolean(System.getProperty(DRY_RUN_PROPERTY));
        } catch (Throwable ignored) {
            // Unreadable, it is treated as a real run: TestNG's own default.
            return false;
        }
    }

    /**
     * A stable id, shaped like the JUnit Platform's so one map format serves every runner.
     *
     * <p>Parameterised invocations share an id; the decoder keeps each record, so no parameter
     * set's coverage is dropped.
     */
    private static String idOf(ITestResult result) {
        try {
            String className = result.getTestClass().getName();
            String methodName = result.getMethod().getMethodName();
            if (className == null || methodName == null) {
                return null;
            }
            return "[engine:testng]/[class:" + className + "]/[method:" + methodName + "]";
        } catch (Throwable ignored) {
            // Costs a record rather than the run.
            return null;
        }
    }
}
