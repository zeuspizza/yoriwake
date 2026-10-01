package io.github.zeuspizza.yoriwake.capturescript;

import static org.jacoco.agent.rt.ScriptedAgent.ran;

import io.github.zeuspizza.yoriwake.agent.engines.PlatformEvents;
import io.github.zeuspizza.yoriwake.agent.engines.TestNgEvents;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor;
import org.junit.platform.launcher.TestIdentifier;
import org.testng.ITestResult;

/**
 * Replays one JUnit Platform event sequence into the listener a host's Launcher loads, in a JVM of
 * its own so capture ends the way a host's does.
 */
public final class PlatformScript {

    private PlatformScript() {}

    public static void main(String[] args) {
        switch (args[0]) {
            case "sequence":
                sequence();
                break;
            case "overlap-threads":
                overlapThreads();
                break;
            case "overlap-one-thread":
                overlapOneThread();
                break;
            case "children":
                children();
                break;
            case "nested-launcher":
                nestedLauncher();
                break;
            case "one-test":
                oneTest();
                break;
            case "testng-on-platform":
                testNgOnPlatform();
                break;
            default:
                throw new IllegalArgumentException(args[0]);
        }
    }

    /** Tests across classes, a nested class, every outcome, and an id with no class segment. */
    private static void sequence() {
        ran("jvm-startup");
        PlatformEvents listener = new PlatformEvents();
        listener.testPlanExecutionStarted(null);
        ran("A-beforeAll");
        TestIdentifier classA = container(jupiter("com.acme.ATest"));
        listener.executionStarted(classA);
        run(listener, test(jupiter("com.acme.ATest").append("method", "a1()")), "a1",
                TestExecutionResult.successful());
        ran("A-afterEach");
        run(listener, test(jupiter("com.acme.ATest").append("nested-class", "Inner").append("method", "i1()")),
                "i1", TestExecutionResult.failed(new AssertionError("i1")));
        ran("A-afterAll");
        listener.executionFinished(classA, TestExecutionResult.successful());
        ran("B-beforeAll");
        run(listener, test(jupiter("com.acme.BTest").append("method", "b1()")), "b1",
                TestExecutionResult.aborted(null));
        listener.executionSkipped(test(jupiter("com.acme.BTest").append("method", "b2()")), "disabled");
        ran("B-afterAll");
        run(listener, test(UniqueId.forEngine("junit-vintage").append("runner", "com.acme.OldTest")
                .append("test", "t(com.acme.OldTest)")), "old", TestExecutionResult.successful());
        ran("after-old");
        run(listener, test(jupiter("com.acme.CTest").append("method", "c1()")), "c1", null);
        ran("C-afterAll");
        listener.testPlanExecutionFinished(null);
    }

    /** A test starting on a second thread while the first is still open. */
    private static void overlapThreads() {
        PlatformEvents listener = new PlatformEvents();
        listener.testPlanExecutionStarted(null);
        ran("A-beforeAll");
        TestIdentifier a1 = test(jupiter("com.acme.ATest").append("method", "a1()"));
        TestIdentifier b1 = test(jupiter("com.acme.BTest").append("method", "b1()"));
        listener.executionStarted(a1);
        ran("a1-early");
        Threads other = new Threads(
                () -> {
                    listener.executionStarted(b1);
                    ran("b1");
                },
                () -> listener.executionFinished(b1, TestExecutionResult.successful()));
        other.start();
        ran("a1-late");
        listener.executionFinished(a1, TestExecutionResult.successful());
        other.finish();
        ran("tail");
        listener.testPlanExecutionFinished(null);
    }

    /** A test starting on the same thread inside another that is not its parent. */
    private static void overlapOneThread() {
        PlatformEvents listener = new PlatformEvents();
        listener.testPlanExecutionStarted(null);
        TestIdentifier a1 = test(jupiter("com.acme.ATest").append("method", "a1()"));
        TestIdentifier b1 = test(jupiter("com.acme.BTest").append("method", "b1()"));
        listener.executionStarted(a1);
        ran("a1-early");
        listener.executionStarted(b1);
        ran("b1");
        listener.executionFinished(b1, TestExecutionResult.successful());
        ran("a1-late");
        listener.executionFinished(a1, TestExecutionResult.successful());
        ran("tail");
        listener.testPlanExecutionFinished(null);
    }

    /** A test that reports tests of its own, as a Spock data-driven feature does. */
    private static void children() {
        PlatformEvents listener = new PlatformEvents();
        listener.testPlanExecutionStarted(null);
        UniqueId featureId = UniqueId.forEngine("spock").append("spec", "com.acme.HandlerSpec")
                .append("feature", "$spock_feature_0_0");
        TestIdentifier feature = identifier(featureId, TestDescriptor.Type.CONTAINER_AND_TEST);
        listener.executionStarted(feature);
        ran("where-block");
        run(listener, test(featureId.append("iteration", "0")), "it0", TestExecutionResult.successful());
        ran("between-iterations");
        run(listener, test(featureId.append("iteration", "1")), "it1", TestExecutionResult.successful());
        ran("feature-cleanup");
        listener.executionFinished(feature, TestExecutionResult.successful());
        UniqueId nestedFeature = jupiter("com.acme.DTest").append("method", "d1()");
        TestIdentifier d1 = identifier(nestedFeature, TestDescriptor.Type.CONTAINER_AND_TEST);
        listener.executionStarted(d1);
        ran("d1-setup");
        run(listener, test(nestedFeature.append("dynamic-test", "#1")), "d1-1", TestExecutionResult.successful());
        ran("d1-cleanup");
        listener.executionFinished(d1, TestExecutionResult.successful());
        ran("tail");
        listener.testPlanExecutionFinished(null);
    }

    /** A test that runs a Launcher of its own, which loads a second listener. */
    private static void nestedLauncher() {
        PlatformEvents outer = new PlatformEvents();
        outer.testPlanExecutionStarted(null);
        TestIdentifier a1 = test(jupiter("com.acme.ATest").append("method", "a1()"));
        outer.executionStarted(a1);
        ran("a1-before-nested");
        PlatformEvents inner = new PlatformEvents();
        inner.testPlanExecutionStarted(null);
        run(inner, test(jupiter("com.acme.InnerTest").append("method", "x1()")), "x1",
                TestExecutionResult.successful());
        inner.testPlanExecutionFinished(null);
        ran("a1-after-nested");
        outer.executionFinished(a1, TestExecutionResult.successful());
        ran("tail");
        outer.testPlanExecutionFinished(null);
    }

    private static void oneTest() {
        PlatformEvents listener = new PlatformEvents();
        listener.testPlanExecutionStarted(null);
        run(listener, test(jupiter("com.acme.ATest").append("method", "a1()")), "a1",
                TestExecutionResult.successful());
        listener.testPlanExecutionFinished(null);
    }

    /**
     * TestNG run by the Platform's TestNG engine: the Platform listener and TestNG's own listener
     * both see every test.
     */
    private static void testNgOnPlatform() {
        PlatformEvents platform = new PlatformEvents();
        TestNgEvents testng = new TestNgEvents();
        platform.testPlanExecutionStarted(null);
        testng.onExecutionStart();
        ran("setup");
        for (String method : new String[] {"m1", "m2"}) {
            TestIdentifier id = test(UniqueId.forEngine("testng").append("class", "com.acme.NgTest")
                    .append("method", method + "()"));
            ITestResult result = TestNgResults.of("com.acme.NgTest", method);
            platform.executionStarted(id);
            testng.onTestStart(result);
            ran(method);
            testng.onTestSuccess(result);
            platform.executionFinished(id, TestExecutionResult.successful());
            ran("after-" + method);
        }
        testng.onExecutionFinish();
        platform.testPlanExecutionFinished(null);
    }

    private static void run(PlatformEvents listener, TestIdentifier id, String work,
            TestExecutionResult result) {
        listener.executionStarted(id);
        ran(work);
        listener.executionFinished(id, result);
    }

    private static UniqueId jupiter(String className) {
        return UniqueId.forEngine("junit-jupiter").append("class", className);
    }

    private static TestIdentifier test(UniqueId id) {
        return identifier(id, TestDescriptor.Type.TEST);
    }

    private static TestIdentifier container(UniqueId id) {
        return identifier(id, TestDescriptor.Type.CONTAINER);
    }

    private static TestIdentifier identifier(UniqueId id, TestDescriptor.Type type) {
        return TestIdentifier.from(new AbstractTestDescriptor(id, id.getLastSegment().getValue()) {
            @Override
            public Type getType() {
                return type;
            }
        });
    }
}
