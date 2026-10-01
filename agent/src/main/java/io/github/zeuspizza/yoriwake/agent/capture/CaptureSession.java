package io.github.zeuspizza.yoriwake.agent.capture;

import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.CLASS_SCOPED_RECORD_PREFIX;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.OUTCOME_NOT_A_TEST;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.OUTCOME_SKIPPED;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.OUTCOME_SUCCESSFUL;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.OUTCOME_UNKNOWN;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.UNATTRIBUTED_RECORD_ID;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Which coverage window belongs to which test, for every engine: the one capture algorithm.
 *
 * <p>{@code IAgent.getExecutionData(reset = true)} takes and clears in one call, so a take when a
 * test starts and another when it finishes leaves exactly that test's coverage. Every window is
 * recorded under some id, because coverage that reaches no record can never select a test:
 *
 * <ul>
 *   <li>startup, up to the first test or to the Platform's plan start, belongs to no test, and a
 *       change touching it forces a full run;
 *   <li>the window between two tests (setup, teardown, construction) belongs to the classes on
 *       either side: discarding it loses recall, and marking it global would force a full run for
 *       almost any edit;
 *   <li>tests whose windows overlap, open at once on two threads or interleaved on one, cannot be
 *       told apart and are recorded as {@code UNKNOWN}, which always runs them.
 * </ul>
 *
 * <p>A test the engine reports inside an open test of its own, as a Spock iteration inside its
 * feature, gets a window of its own. A nested run's tests, reported through the same events from
 * inside an open test, belong to that test's window.
 *
 * <p>Every entry point is synchronized and swallows its failures, counting them: an engine callback
 * that throws fails the host's test, and an uncounted failure leaves a quietly incomplete map.
 */
public final class CaptureSession implements TestEvents {

    /** The Platform's name for a failed test, which every engine records. */
    private static final String OUTCOME_FAILED = "FAILED";

    /** What the JVM loaded or read for the first time, drained at every take. */
    public interface Touches {
        /** Every first touch since the last drain, as {kind, value} pairs. */
        List<String[]> drain();

        /** Null while every load and read is observed; otherwise why not. */
        String incomplete();

        /**
         * Null while every lookup of a class by name is observed, and nothing reached classes in a
         * way no lookup shows (a native agent, the whole loaded set); otherwise why not.
         */
        default String lookupsIncomplete() {
            return "lookups are not observed";
        }

        /** Discovery is over: reads and lookups from here on are kept even if discovery saw them. */
        default void planStarted() {}
    }

    private final JacocoAgent agent;
    private final ExecRecordWriter writer;
    private final boolean nestedRuns;
    private final Touches touches;
    private boolean touchesIncomplete;
    private boolean lookupsIncomplete;
    private final OverheadMeter meter = new OverheadMeter();

    /** Tests in progress, in the order they started. */
    private final List<Open> open = new ArrayList<>();
    private final Set<String> unattributable = new HashSet<>();
    private final Set<String> failed = new HashSet<>();

    /** The class scope of the test that last opened a window, so a between-tests window names both sides. */
    private String previousScope;
    private boolean startupRecorded;
    private boolean runFinished;
    private boolean ended;
    private int windows;
    private int failures;

    private CaptureSession(JacocoAgent agent, ExecRecordWriter writer, boolean nestedRuns, Touches touches) {
        this.agent = agent;
        this.writer = writer;
        this.nestedRuns = nestedRuns;
        this.touches = touches;
        // Started before the first take, so the plan window contains every metered call.
        meter.planStarted();
    }

    /**
     * Opens a session writing one worker's records under {@code recordsDir}. Nothing is taken yet.
     *
     * @param nestedRuns whether a nested run's tests reach these same events, as they do through
     *     JUnit 4's notifier hook and TestNG's listener. A test then starting on the open test's
     *     thread is its nested run. A nested Launcher has a listener of its own, so on the Platform
     *     such a start is an overlap.
     * @throws IOException when the directory cannot be opened
     */
    public static CaptureSession open(JacocoAgent agent, File recordsDir, String workerId, boolean nestedRuns)
            throws IOException {
        return open(agent, recordsDir, workerId, nestedRuns, TouchRecorder.JVM);
    }

    /** As {@link #open(JacocoAgent, File, String, boolean)}, observing loads and reads through {@code touches}. */
    public static CaptureSession open(JacocoAgent agent, File recordsDir, String workerId, boolean nestedRuns,
            Touches touches) throws IOException {
        return new CaptureSession(agent, new ExecRecordWriter(recordsDir, workerId), nestedRuns, touches);
    }

    /**
     * Ends the startup window here rather than at the first test: the Platform's plan start, after
     * which the first class's setup is that class's.
     */
    public synchronized void startupEnded() {
        try {
            recordStartup();
        } catch (Throwable t) {
            recordFailure("end of startup: " + t);
        }
    }

    @Override
    public synchronized void started(String id) {
        // A run that goes on after it said it was over has not finished, whatever it said.
        runFinished = false;
        try {
            if (!ended) {
                open(id);
            }
        } catch (Throwable t) {
            recordFailure("start of " + id + ": " + t);
        }
    }

    private void open(String id) {
        Thread thread = Thread.currentThread();
        if (!open.isEmpty() && !insideEveryOpenTest(id)) {
            if (nestedRuns && thread == open.get(0).thread) {
                open.add(new Open(id, thread, 0L, true));
                return;
            }
            // Two tests sharing one window, and a take is JVM-global: neither owns what it holds.
            for (Open test : open) {
                unattributable.add(test.id);
            }
            unattributable.add(id);
            previousScope = classScopeOf(id);
            open.add(new Open(id, thread, System.nanoTime(), false));
            return;
        }
        String scope = classScopeOf(id);
        if (startupRecorded) {
            captureBetweenTests(previousScope, scope, false);
        } else {
            recordStartup();
        }
        previousScope = scope;
        open.add(new Open(id, thread, System.nanoTime(), false));
    }

    @Override
    public synchronized void failed(String id) {
        try {
            failed.add(id);
        } catch (Throwable t) {
            // A lost failure makes the test look passed, and so skippable.
            recordFailure("outcome of " + id + ": " + t);
        }
    }

    @Override
    public synchronized void finished(String id, String outcome) {
        try {
            if (!ended) {
                close(id, outcome);
            }
        } catch (Throwable t) {
            recordFailure("finish of " + id + ": " + t);
        }
    }

    private void close(String id, String outcome) {
        Open test = innermostOpen(id);
        if (test != null && test.nested) {
            open.remove(test);
            return;
        }
        long duration;
        if (test == null) {
            // Matched by identity: after an unbalanced notification a nested run's bookkeeping is
            // abandoned, or it would swallow every later test into one record.
            if (nestedRuns) {
                open.clear();
            }
            duration = -1L;
        } else {
            open.remove(test);
            if (open.stream().allMatch(remaining -> remaining.nested)) {
                open.clear();
            }
            duration = System.nanoTime() - test.startedAt;
        }
        String recorded = outcome != null ? outcome : failed.contains(id) ? OUTCOME_FAILED : OUTCOME_SUCCESSFUL;
        failed.remove(id);
        if (unattributable.remove(id)) {
            recorded = OUTCOME_UNKNOWN;
        }
        byte[] data = take();
        if (data != null) {
            write(id, duration, recorded, data);
        }
    }

    /** Recorded with no coverage and a non-successful outcome, so a re-enabled test stays selectable. */
    @Override
    public synchronized void skipped(String id) {
        try {
            if (!ended) {
                write(id, 0L, OUTCOME_SKIPPED, new byte[0]);
            }
        } catch (Throwable t) {
            recordFailure("skip of " + id + ": " + t);
        }
    }

    /**
     * Records each of {@code tests} FAILED with no coverage, because {@code container} around them
     * failed. One that cannot be listed or written counts as a capture failure, so the worker is
     * not marked complete.
     */
    public synchronized void failedByContainer(String container, Supplier<? extends Iterable<String>> tests) {
        try {
            if (!ended) {
                for (String id : tests.get()) {
                    write(id, 0L, OUTCOME_FAILED, new byte[0]);
                }
            }
        } catch (Throwable t) {
            recordFailure("the tests beneath " + container + ": " + t);
        }
    }

    @Override
    public synchronized void runFinished() {
        if (open.isEmpty()) {
            runFinished = true;
        }
    }

    /**
     * Ends capture at the runner's own end of run. The tail holds the last class's teardown, so it
     * belongs to that class.
     */
    public synchronized void endRun() {
        runFinished();
        end(true);
    }

    /**
     * Ends capture at JVM shutdown, for runners that never call back after their last test. The
     * tail holds whatever else ran before exit, which no test owns.
     *
     * @param hostSaysFinished whether the host said the run reached its end
     */
    public synchronized void endAtShutdown(boolean hostSaysFinished) {
        if (hostSaysFinished) {
            runFinished();
        }
        end(false);
    }

    /**
     * Records the tail, writes everything, and marks the worker complete only for a run that said
     * it finished with no test open. The shutdown hook runs on {@code System.exit} mid-test as
     * readily as at the end, and a run that died part-way leaves records that decode cleanly.
     */
    private void end(boolean atRunEnd) {
        if (ended) {
            return;
        }
        ended = true;
        try {
            if (atRunEnd) {
                captureBetweenTests(previousScope, null, true);
            } else if (startupRecorded) {
                capture(UNATTRIBUTED_RECORD_ID);
            }
        } catch (Throwable t) {
            recordFailure("the tail window: " + t);
        }
        try {
            drainTouches();
            writer.completeTouches();
        } catch (Throwable t) {
            // The touches then lack their end marker, which the decoder reads as "all touched".
            recordFailure("the last touches: " + t);
        }
        // Flushed inside the metered window, since the flush is where the record I/O happens.
        long flushStartedAt = System.nanoTime();
        try {
            writer.flush();
        } catch (IOException e) {
            recordFailure("Could not flush records: " + e);
        }
        meter.recordWrite(flushStartedAt);
        meter.planFinished();
        try {
            writer.writeOverhead(overhead());
        } catch (IOException e) {
            recordFailure("Could not write the overhead summary: " + e);
        }
        try {
            // Closed in its own block: a failure writing the summary must not leak the index handle.
            writer.close();
        } catch (IOException e) {
            recordFailure("Could not close the record index: " + e);
        }
        boolean complete = runFinished && open.isEmpty();
        // After the close, and only for a clean capture: after a failure a missing record would let
        // the older one it supersedes survive the merge looking current.
        if (complete && failures == 0) {
            try {
                writer.markPlanComplete();
            } catch (IOException e) {
                recordFailure("Could not mark the plan complete: " + e);
            }
        }
        if (complete) {
            LoadedClassRecorder.markComplete();
        }
    }

    /** What capture cost this worker, as one line. */
    public synchronized String overhead() {
        return meter.render(writer.recordCount(), windows, failures);
    }

    public synchronized int recordCount() {
        return writer.recordCount();
    }

    public synchronized int failures() {
        return failures;
    }

    public File directory() {
        return writer.directory();
    }

    private void recordStartup() {
        if (!startupRecorded) {
            startupRecorded = true;
            capture(UNATTRIBUTED_RECORD_ID);
            // Everything drained so far happened during discovery, when the engine itself loads
            // and inspects its test classes; the marker is how the decoder tells the two apart.
            try {
                touches.planStarted();
                writer.touched(java.util.Collections.singletonList(
                        new String[] {io.github.zeuspizza.yoriwake.agent.contract.AgentContract.TOUCH_PLAN_STARTED, ""}));
            } catch (Throwable t) {
                recordFailure("marking the plan's start: " + t);
            }
        }
    }

    /** Records a window that belongs to no test but to the classes on either side of it. */
    private void captureBetweenTests(String endingClass, String startingClass, boolean isTail) {
        // If the starting class is unknown (the vintage engine emits [runner:...]), blaming the
        // previous class alone would never select the test that actually ran the code.
        boolean nextScopeUnknown = !isTail && startingClass == null;
        if ((endingClass == null && startingClass == null) || nextScopeUnknown) {
            capture(UNATTRIBUTED_RECORD_ID);
            return;
        }
        StringBuilder id = new StringBuilder(CLASS_SCOPED_RECORD_PREFIX);
        if (endingClass != null) {
            id.append(endingClass);
        }
        if (startingClass != null && !startingClass.equals(endingClass)) {
            if (endingClass != null) {
                id.append('|');
            }
            id.append(startingClass);
        }
        capture(id.toString());
    }

    /** Takes the accumulated coverage and records it under the given id, skipping empty windows. */
    private void capture(String id) {
        byte[] data = take();
        if (data != null && data.length > 0) {
            write(id, 0L, OUTCOME_NOT_A_TEST, data);
            windows++;
        }
    }

    /**
     * The class-level prefix of a test id, up to its {@code [class:...]} segment: a prefix, because
     * the selector expands a class-scoped record to the test ids starting with it. Null when there
     * is none, and the window is then treated as global.
     */
    static String classScopeOf(String id) {
        if (id == null) {
            return null;
        }
        // The outermost class: its @BeforeAll runs once before the first nested class, so scoping
        // to the innermost class would skip every sibling that shares that setup.
        int end = id.indexOf("[class:");
        if (end < 0) {
            return null;
        }
        // Tracks depth because JUnit does not escape ']' inside a segment value.
        int depth = 0;
        for (int i = end; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return id.substring(0, i + 1);
                }
            }
        }
        return null;
    }

    private boolean insideEveryOpenTest(String id) {
        for (Open test : open) {
            if (!id.startsWith(test.id + "/")) {
                return false;
            }
        }
        return true;
    }

    private Open innermostOpen(String id) {
        for (int i = open.size() - 1; i >= 0; i--) {
            if (open.get(i).id.equals(id)) {
                return open.get(i);
            }
        }
        return null;
    }

    private byte[] take() {
        long startedAt = System.nanoTime();
        byte[] data;
        try {
            data = agent.takeExecutionData();
            meter.recordAgentCall(startedAt);
        } catch (Throwable t) {
            recordFailure("getExecutionData failed: " + t.getClass().getName() + ": " + t.getMessage());
            data = null;
        }
        try {
            drainTouches();
        } catch (Throwable t) {
            recordFailure("recording touches: " + t);
        }
        return data;
    }

    /**
     * Files what was touched since the last take under the record this take is about to write, so
     * a test's own loads count as its own. Once observation is incomplete, every class counts as
     * touched from this record on.
     */
    private void drainTouches() {
        writer.touched(touches.drain());
        String incomplete = touches.incomplete();
        if (incomplete != null && !touchesIncomplete) {
            touchesIncomplete = true;
            writer.touched(java.util.Collections.singletonList(
                    new String[] {io.github.zeuspizza.yoriwake.agent.contract.AgentContract.TOUCH_ALL, incomplete}));
        }
        // A lookup nobody saw can reach any class by name, so it counts as a lookup of all of them.
        if (touches.lookupsIncomplete() != null && !lookupsIncomplete) {
            lookupsIncomplete = true;
            writer.touched(java.util.Collections.singletonList(new String[] {
                io.github.zeuspizza.yoriwake.agent.contract.AgentContract.TOUCH_LOOKUP,
                io.github.zeuspizza.yoriwake.agent.contract.AgentContract.FIRST_TOUCH_ANY}));
        }
    }

    private void write(String id, long durationNanos, String outcome, byte[] data) {
        long startedAt = System.nanoTime();
        try {
            writer.write(id, durationNanos, outcome, data);
        } catch (IOException e) {
            recordFailure("Could not write record for " + id + ": " + e);
        }
        meter.recordWrite(startedAt);
    }

    /** Counted, and only the first printed; any failure means the map is incomplete. */
    private void recordFailure(String message) {
        if (failures == 0) {
            System.out.println("[yoriwake] capture failure (further ones counted, not printed): " + message);
        }
        failures++;
    }

    /** Ids the session could not attribute, for tests. */
    synchronized Set<String> unattributable() {
        return new HashSet<>(unattributable);
    }

    /** The ids in progress, outermost first, for tests. */
    synchronized List<String> inProgress() {
        List<String> ids = new ArrayList<>();
        for (Open test : open) {
            ids.add(test.id);
        }
        return ids;
    }

    /**
     * A test in progress. A plain class rather than a {@code record}: this jar's bytecode floor is
     * the oldest JVM a host tests on.
     */
    private static final class Open {
        final String id;
        final Thread thread;
        final long startedAt;
        /** Part of an enclosing test's nested run, owning no window of its own. */
        final boolean nested;

        Open(String id, Thread thread, long startedAt, boolean nested) {
            this.id = id;
            this.thread = thread;
            this.startedAt = startedAt;
            this.nested = nested;
        }
    }
}
