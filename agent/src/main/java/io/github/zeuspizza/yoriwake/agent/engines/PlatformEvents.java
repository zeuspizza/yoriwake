package io.github.zeuspizza.yoriwake.agent.engines;

import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.OUTCOME_UNKNOWN;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.RECORDS_DIR_PROPERTY;

import io.github.zeuspizza.yoriwake.agent.capture.AgentLookup;
import io.github.zeuspizza.yoriwake.agent.capture.CaptureClaim;
import io.github.zeuspizza.yoriwake.agent.capture.CaptureSession;
import io.github.zeuspizza.yoriwake.agent.capture.JacocoAgent;
import io.github.zeuspizza.yoriwake.agent.capture.ProbeReporter;
import io.github.zeuspizza.yoriwake.agent.capture.ProbeResult;
import io.github.zeuspizza.yoriwake.agent.host.AttachedLoader;
import io.github.zeuspizza.yoriwake.agent.host.HostBuild;
import io.github.zeuspizza.yoriwake.agent.platform.RanTests;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * Per-test capture on the JUnit Platform, discovered by {@code ServiceLoader} from the agent jar's
 * {@code META-INF/services}. Feeds a {@link CaptureSession} for the length of one test plan.
 *
 * <p>Capture is off unless {@code AgentContract.RECORDS_DIR_PROPERTY} is set. No runtime
 * dependencies beyond the JUnit Platform the host already provides.
 */
public class PlatformEvents implements TestExecutionListener {

    // A copy another class loader defined neither probes nor captures. See AttachedLoader.
    private static final boolean ATTACHED = AttachedLoader.attached(PlatformEvents.class);

    // Test plans executing in this JVM right now, whichever launcher runs them.
    private static final AtomicInteger EXECUTING = new AtomicInteger();

    /**
     * Whether a test plan is executing in this JVM: a launcher created now was created by a test,
     * and runs nested inside it.
     */
    public static boolean planExecuting() {
        return EXECUTING.get() > 0;
    }

    private final Supplier<AgentLookup.Result> lookup;
    private final ProbeReporter reporter;
    private final File outputDir;

    private JacocoAgent agent;
    private CaptureClaim claim;
    // Whether this instance serves the outermost plan, whose tests are the task's. A plan a running
    // test started (a nested launcher) runs inside that test, so its tests are never the task's.
    private volatile boolean outermost;
    private volatile CaptureSession session;
    // The plan being captured, for the tests beneath a container that fails.
    private volatile TestPlan plan;

    /** Required by {@link java.util.ServiceLoader}: JUnit instantiates listeners with no arguments. */
    public PlatformEvents() {
        this(AgentLookup::find, new ProbeReporter.ToFile(), configuredOutputDir());
    }

    PlatformEvents(Supplier<AgentLookup.Result> lookup, ProbeReporter reporter) {
        this(lookup, reporter, null);
    }

    PlatformEvents(Supplier<AgentLookup.Result> lookup, ProbeReporter reporter, File outputDir) {
        this.lookup = lookup;
        this.reporter = reporter;
        this.outputDir = outputDir;
    }

    private static File configuredOutputDir() {
        String configured = System.getProperty(RECORDS_DIR_PROPERTY);
        return configured == null || configured.isEmpty() ? null : new File(configured);
    }

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        if (!ATTACHED) {
            return;
        }
        outermost = EXECUTING.incrementAndGet() == 1;
        // A nested in-process Launcher gets its own instance, which loses the claim and does
        // nothing, so the nested run's coverage lands on the outer test.
        if (outputDir != null) {
            claim = CaptureClaim.take(PlatformEvents.class);
            if (claim == null) {
                return;
            }
        }
        probeAndReport();
        if (outputDir == null) {
            return;
        }
        if (agent == null) {
            releaseCapture();
            return;
        }
        try {
            session = CaptureSession.open(agent, outputDir, HostBuild.current().workerId(), false);
        } catch (IOException e) {
            // Every take is a JVM-global reset: with nowhere to record, taking one would only
            // throw coverage away.
            reporter.report(ProbeResult.failed("Could not open the record directory: " + e));
            releaseCapture();
            return;
        }
        plan = testPlan;
        // Everything loaded during JVM and test-plan startup, taken before any other take so
        // nothing is reset away first.
        session.startupEnded();
    }

    @Override
    public void executionStarted(TestIdentifier identifier) {
        CaptureSession capture = session;
        if (capture != null && identifier.isTest()) {
            capture.started(identifier.getUniqueId());
        }
    }

    @Override
    public void executionFinished(TestIdentifier identifier, TestExecutionResult result) {
        // Before the capture check: a run that narrowed captures nothing and still ran these.
        if (outermost && identifier.isTest() && result != null) {
            RanTests.record(identifier.getUniqueId(), result.getStatus().name());
        }
        CaptureSession capture = session;
        if (capture == null) {
            return;
        }
        if (identifier.isTest()) {
            capture.finished(identifier.getUniqueId(), outcomeOf(result));
        } else if (result == null || result.getStatus() != TestExecutionResult.Status.SUCCESSFUL) {
            failedBeneath(capture, identifier);
        }
    }

    /**
     * A container that fails (a throwing {@code @BeforeAll}, a class-level extension) reports none
     * of the tests it did not reach, and one that fails after them ({@code @AfterAll}) leaves them
     * passed. Either way its tests are recorded failed, so none reads as known to pass.
     *
     * <p>A container with nothing beneath it (a test template whose argument source throws, a test
     * factory that throws, or either under a class that failed first) registered no invocation to
     * carry the failure, so it is recorded failed itself; selection judges it by its own id.
     */
    private void failedBeneath(CaptureSession capture, TestIdentifier container) {
        TestPlan current = plan;
        if (current == null) {
            return;
        }
        capture.failedByContainer(container.getUniqueId(), () -> {
            List<String> failed = new ArrayList<>();
            List<TestIdentifier> beneath = new ArrayList<>(current.getDescendants(container));
            beneath.add(container);
            for (TestIdentifier identifier : beneath) {
                if (identifier.isTest() || current.getChildren(identifier).isEmpty()) {
                    failed.add(identifier.getUniqueId());
                }
            }
            return failed;
        });
    }

    /** The test's result, recorded because a test that failed last time must always re-run. */
    private static String outcomeOf(TestExecutionResult result) {
        return result == null ? OUTCOME_UNKNOWN : result.getStatus().name();
    }

    @Override
    public void executionSkipped(TestIdentifier identifier, String reason) {
        CaptureSession capture = session;
        if (capture != null && identifier.isTest()) {
            capture.skipped(identifier.getUniqueId());
        }
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        if (!ATTACHED) {
            return;
        }
        EXECUTING.decrementAndGet();
        outermost = false;
        CaptureSession capture = session;
        if (capture == null) {
            return;
        }
        capture.endRun();
        System.out.println("[yoriwake] " + capture.overhead());
        reporter.report(ProbeResult.captured(
                capture.recordCount(), capture.directory().getAbsolutePath(), capture.failures()));
        session = null;
        plan = null;
        releaseCapture();
    }

    private void releaseCapture() {
        if (claim != null) {
            claim.release();
            claim = null;
        }
    }

    /**
     * The probe itself, separate from the JUnit callback because {@link TestPlan} has no stable
     * public constructor to test with.
     */
    public void probeAndReport() {
        AgentLookup.Result result;
        try {
            result = lookup.get();
        } catch (Throwable t) {
            // A listener that throws takes the host's whole test run down with it.
            reporter.report(ProbeResult.failed(
                    "Agent lookup threw " + t.getClass().getName() + ": " + t.getMessage()));
            return;
        }

        if (!result.isFound()) {
            reporter.report(ProbeResult.failed(result.reason().diagnostic()));
            return;
        }

        this.agent = result.agent();
        reporter.report(probe(agent));
    }

    /** Confirms the agent answers without resetting anything, so startup coverage survives. */
    private ProbeResult probe(JacocoAgent agent) {
        try {
            return ProbeResult.reachable(agent.version(), 0);
        } catch (Throwable t) {
            this.agent = null;
            return ProbeResult.failed(
                    "Agent found but unusable: " + t.getClass().getName() + ": " + t.getMessage());
        }
    }
}
