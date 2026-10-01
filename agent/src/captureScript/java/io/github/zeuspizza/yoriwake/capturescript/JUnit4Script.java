package io.github.zeuspizza.yoriwake.capturescript;

import static org.jacoco.agent.rt.ScriptedAgent.ran;

import io.github.zeuspizza.yoriwake.agent.engines.JUnit4Events;
import org.junit.runner.Description;
import org.junit.runner.notification.Failure;

/**
 * Replays one JUnit 4 event sequence into the calls the rewritten {@code RunNotifier} makes, in a
 * JVM of its own with no JUnit Platform on the classpath, flushed by a real shutdown.
 */
public final class JUnit4Script {

    /** What Gradle's rewritten JUnit 4 processor sets when its {@code stop()} runs. */
    private static final String RUN_FINISHED_PROPERTY = "yoriwake.junit4.runFinished";

    private JUnit4Script() {}

    public static void main(String[] args) {
        NoPlatform.check();
        switch (args[0]) {
            case "sequence":
                sequence();
                break;
            case "nested-run":
                nestedRun();
                break;
            case "overlap-threads":
                overlapThreads();
                break;
            case "one-test":
                run(test("com.acme.ATest", "a1"), "a1");
                break;
            case "unfinished":
                run(test("com.acme.ATest", "a1"), "a1");
                return;
            default:
                throw new IllegalArgumentException(args[0]);
        }
        System.setProperty(RUN_FINISHED_PROPERTY, "true");
    }

    /** Tests across classes, a failure, an assumption failure, and a class name with brackets. */
    private static void sequence() {
        ran("jvm-startup,A-beforeClass");
        JUnit4Events.started(Description.createSuiteDescription("com.acme.ATest"));
        run(test("com.acme.ATest", "a1"), "a1");
        ran("A-after");
        Description a2 = test("com.acme.ATest", "a2");
        JUnit4Events.started(a2);
        ran("a2");
        JUnit4Events.failed(new Failure(a2, new AssertionError("a2")));
        JUnit4Events.finished(a2);
        ran("A-afterClass,B-beforeClass");
        Description b1 = test("com.acme.BTest", "b1");
        JUnit4Events.started(b1);
        ran("b1");
        // What fireTestAssumptionFailed calls.
        JUnit4Events.failed(new Failure(b1, new IllegalStateException("assumption")));
        JUnit4Events.finished(b1);
        ran("B-afterClass,W-beforeClass");
        run(test("com.acme.Weird[Name]Test", "w1"), "w1");
        ran("W-after");
        run(test("com.acme.Weird[Name]Test", "w2"), "w2");
        ran("W-afterClass,C-beforeClass");
        run(test("com.acme.CTest", "c1"), "c1");
        ran("tail");
    }

    /** A test that hands classes to JUnitCore, whose notifications reach the same notifier hook. */
    private static void nestedRun() {
        ran("startup");
        Description outer = test("com.acme.ATest", "outer");
        JUnit4Events.started(outer);
        ran("outer-before");
        run(test("com.acme.InnerTest", "n1"), "n1");
        ran("between-inner");
        run(test("com.acme.InnerTest", "n2"), "n2");
        ran("outer-after");
        JUnit4Events.finished(outer);
        ran("between");
        run(test("com.acme.BTest", "b1"), "b1");
        ran("tail");
    }

    /** A test starting on a second thread while the first is still open. */
    private static void overlapThreads() {
        ran("startup");
        Description a1 = test("com.acme.ATest", "a1");
        Description b1 = test("com.acme.BTest", "b1");
        JUnit4Events.started(a1);
        ran("a1-early");
        Threads other = new Threads(
                () -> {
                    JUnit4Events.started(b1);
                    ran("b1");
                },
                () -> JUnit4Events.finished(b1));
        other.start();
        ran("a1-late");
        JUnit4Events.finished(a1);
        other.finish();
        ran("between");
        run(test("com.acme.CTest", "c1"), "c1");
        ran("tail");
    }

    private static void run(Description test, String work) {
        JUnit4Events.started(test);
        ran(work);
        JUnit4Events.finished(test);
    }

    private static Description test(String className, String method) {
        return Description.createTestDescription(className, method);
    }
}
