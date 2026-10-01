package io.github.zeuspizza.yoriwake.capturescript;

import static org.jacoco.agent.rt.ScriptedAgent.ran;

import io.github.zeuspizza.yoriwake.agent.engines.TestNgEvents;
import org.testng.ITestResult;

/**
 * Replays one TestNG event sequence into the listener TestNG loads, in a JVM of its own with no
 * JUnit Platform on the classpath, flushed by a real shutdown.
 */
public final class TestNgScript {

    private TestNgScript() {}

    public static void main(String[] args) {
        NoPlatform.check();
        TestNgEvents listener = new TestNgEvents();
        listener.onExecutionStart();
        switch (args[0]) {
            case "sequence":
                sequence(listener);
                break;
            case "overlap-threads":
                overlapThreads(listener);
                break;
            default:
                throw new IllegalArgumentException(args[0]);
        }
        listener.onExecutionFinish();
    }

    /** Tests across classes and every way TestNG closes a test. */
    private static void sequence(TestNgEvents listener) {
        ran("jvm-startup,A-beforeClass");
        ITestResult a1 = TestNgResults.of("com.acme.ATest", "a1");
        listener.onTestStart(a1);
        ran("a1");
        listener.onTestSuccess(a1);
        ran("A-afterMethod");
        ITestResult a2 = TestNgResults.of("com.acme.ATest", "a2");
        listener.onTestStart(a2);
        ran("a2");
        listener.onTestFailure(a2);
        ITestResult a3 = TestNgResults.of("com.acme.ATest", "a3");
        listener.onTestStart(a3);
        ran("a3");
        listener.onTestSkipped(a3);
        ITestResult a4 = TestNgResults.of("com.acme.ATest", "a4");
        listener.onTestStart(a4);
        ran("a4");
        listener.onTestFailedButWithinSuccessPercentage(a4);
        ran("A-afterClass,B-beforeClass");
        ITestResult b1 = TestNgResults.of("com.acme.BTest", "b1");
        listener.onTestStart(b1);
        ran("b1");
        listener.onTestSuccess(b1);
        ran("tail");
    }

    /** A test starting on a second thread while the first is still open. */
    private static void overlapThreads(TestNgEvents listener) {
        ran("startup");
        ITestResult a1 = TestNgResults.of("com.acme.ATest", "a1");
        ITestResult b1 = TestNgResults.of("com.acme.BTest", "b1");
        listener.onTestStart(a1);
        ran("a1-early");
        Threads other = new Threads(
                () -> {
                    listener.onTestStart(b1);
                    ran("b1");
                },
                () -> listener.onTestSuccess(b1));
        other.start();
        ran("a1-late");
        listener.onTestSuccess(a1);
        other.finish();
        ran("tail");
    }
}
