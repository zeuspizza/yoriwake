package io.github.zeuspizza.yoriwake.agent.select;

import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.CLASS_GRANULARITY_PROPERTY;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.COMPLEMENT_NOTE;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.COMPLEMENT_RECORD_MISMATCH_KIND;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.COMPLEMENT_RECORD_PROPERTY;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.DECLINES_NOTE;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.DECLINES_PROPERTY;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.FINGERPRINT_IGNORED_NOTE;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.FINGERPRINT_IGNORED_PROPERTY;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.FULL_RUN_KIND_NOTE;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.FULL_RUN_REASON_NOTE;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.MAP_DIR_PROPERTY;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.OBSERVED_OUTCOME_NOTE;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.OBSERVE_PROPERTY;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.OUTCOME_NOTE;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.PARALLELISM_NOTE;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.PARALLELISM_OVERRIDE_UNCONFIRMED;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.PARALLELISM_PROPERTY;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.REFUSAL_KIND_NOTE;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.REFUSED_KIND_PROPERTY;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.RUN_COMPLEMENTED;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.RUN_FULL;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.RUN_NARROWED;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.RUN_NOT_DECIDED;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.RUN_NOT_REQUESTED;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.SELECT_PROPERTY;

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract;
import io.github.zeuspizza.yoriwake.agent.host.HostBuild;
import io.github.zeuspizza.yoriwake.agent.platform.Concurrency;
import io.github.zeuspizza.yoriwake.agent.platform.DecisionRecord;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

/**
 * One test JVM's selection, apart from the framework that asks for it: the inputs, the decision made
 * once, the tally, the pins, the whole-run notes and the decision record written at exit.
 *
 * <p>Loads no JUnit Platform class, so a filter for a framework that runs outside the Platform can
 * use it as the Platform's filter does.
 */
public final class SelectionRun {

    // Written by each judgement, read as a whole by the shutdown hook.
    private final Map<Selector.Decision.Reason, Integer> tally =
            Collections.synchronizedMap(new EnumMap<>(Selector.Decision.Reason.class));
    private volatile Selector.Decision decision;
    private volatile UnaryOperator<String> inputs;
    private volatile boolean reported;

    // Hooked separately from the tally: a run never asked to select never calls decide(), but
    // still needs its record written.
    private final DecisionRecord decisions = new DecisionRecord();

    private volatile boolean recordHookInstalled;

    // A framework's own notes, added just before the record is written.
    private final Runnable beforeWrite;

    // Pins are counted apart from the tally: a pin is selection told not to apply, not an outcome.
    private final AlwaysRun alwaysRun = AlwaysRun.from(orEmpty(HostBuild.current().property(AgentContract.ALWAYS_RUN_PROPERTY)));

    private final AtomicInteger pinned = new AtomicInteger();

    // Apart from the tally, which counts the selector's reasons: the selector never judged these.
    private final AtomicInteger engineRunsEverything = new AtomicInteger();

    // A complement run's record, read once; and what it left out and ran, for its note.
    private volatile Complement complement;
    private final AtomicInteger leftOut = new AtomicInteger();
    private final AtomicInteger complementRan = new AtomicInteger();

    /** The tests a complement run may leave out, or why it leaves out none. */
    public static final class Complement {
        public final Map<String, String> ran;
        public final String mismatch;

        Complement(Map<String, String> ran, String mismatch) {
            this.ran = ran;
            this.mismatch = mismatch;
        }
    }

    /** A run whose record is written at JVM exit. */
    public SelectionRun() {
        this(() -> { });
    }

    /** A run whose record is written at JVM exit, after {@code beforeWrite} has added its notes. */
    public SelectionRun(Runnable beforeWrite) {
        this.beforeWrite = beforeWrite;
        // Installed at construction: a run that judges no tests must still record why.
        installRecordHook();
    }

    /** At JVM exit because there is no end-of-discovery callback. Safe to call again. */
    public void installRecordHook() {
        if (recordHookInstalled) {
            return;
        }
        synchronized (this) {
            if (recordHookInstalled) {
                return;
            }
            recordHookInstalled = true;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    beforeWrite.run();
                } catch (Throwable ignored) {
                    // A framework's note that cannot be written must not cost the record.
                }
                noteOutcome();
                Selector.Decision current = decision;
                decisions.writeTo(mapDir(), current == null ? null : current.rules());
            }));
        }
    }

    /** The record this run writes, for the notes a framework adds of its own. */
    public DecisionRecord decisions() {
        return decisions;
    }

    /**
     * Records why this run selected what it did, as a fact about the run: a refusal, an unrequested
     * selection, a full run and its reason, or a narrowed run. Written only, never read back.
     */
    private void noteOutcome() {
        try {
            String ignored = orEmpty(inputs().apply(FINGERPRINT_IGNORED_PROPERTY));
            if (!ignored.isEmpty()) {
                // Whatever the outcome: a build reading why a run narrowed sees what was not compared.
                decisions.note(FINGERPRINT_IGNORED_NOTE, ignored);
            }
            // Whatever the outcome too: what the test JVM found outranks what the plugin asked for.
            String parallelism = Concurrency.unconfirmed() != null
                    ? PARALLELISM_OVERRIDE_UNCONFIRMED
                    : orEmpty(inputs().apply(PARALLELISM_PROPERTY));
            if (!parallelism.isEmpty()) {
                decisions.note(PARALLELISM_NOTE, parallelism);
            }
            // Whatever the outcome too: a wrong declaration is found by reading what it took out.
            int pathsIgnored = FilterRule.ignoredPaths(orEmpty(inputs().apply(AgentContract.IGNORED_PATHS_PROPERTY))).size();
            if (pathsIgnored > 0) {
                decisions.note(AgentContract.PATHS_IGNORED_NOTE, String.valueOf(pathsIgnored));
            }
            // From the properties, not the decision, so a refused run with no tests still records why.
            String refusal = daemonRefusal();
            if (refusal != null) {
                decisions.note(OUTCOME_NOTE, RUN_FULL);
                decisions.note(FULL_RUN_KIND_NOTE, Selector.Decision.FullRunKind.DAEMON_REFUSED.token());
                String kind = orEmpty(inputs().apply(REFUSED_KIND_PROPERTY));
                if (observing()) {
                    decisions.noteFields(OBSERVED_OUTCOME_NOTE,
                            RUN_FULL, Selector.Decision.FullRunKind.DAEMON_REFUSED.token(), kind);
                }
                if (!kind.isEmpty()) {
                    // DAEMON_REFUSED says which side refused; this says which refusal it was.
                    decisions.note(REFUSAL_KIND_NOTE, kind);
                }
                String declines = orEmpty(inputs().apply(DECLINES_PROPERTY));
                if (!declines.isEmpty()) {
                    // The refusal names one; this names every decline that held.
                    decisions.note(DECLINES_NOTE, declines);
                }
                decisions.note(FULL_RUN_REASON_NOTE, refusal);
                return;
            }
            if (complementing()) {
                Complement read = complement;
                if (read != null && read.mismatch != null) {
                    // The daemon's refusal channel was set before this JVM started, so the agent's
                    // own refusal is a note.
                    decisions.note(OUTCOME_NOTE, RUN_FULL);
                    decisions.note(REFUSAL_KIND_NOTE, COMPLEMENT_RECORD_MISMATCH_KIND);
                    decisions.note(FULL_RUN_REASON_NOTE, read.mismatch);
                    return;
                }
                decisions.note(OUTCOME_NOTE, RUN_COMPLEMENTED);
                decisions.noteFields(COMPLEMENT_NOTE, String.valueOf(leftOut.get()), String.valueOf(complementRan.get()));
                return;
            }
            if (observing()) {
                decisions.note(OUTCOME_NOTE, RUN_FULL);
                decisions.note(FULL_RUN_REASON_NOTE, "the run observes selection, so every test runs");
                decisions.noteFields(OBSERVED_OUTCOME_NOTE, selectedOutcome());
                return;
            }
            if (!selectionRequested()) {
                decisions.note(OUTCOME_NOTE, RUN_NOT_REQUESTED);
                return;
            }
            Selector.Decision current = decision;
            if (current == null) {
                // Discovery never asked, so there is no decision to describe.
                decisions.note(OUTCOME_NOTE, RUN_NOT_DECIDED);
                return;
            }
            if (!current.isFullRun()) {
                decisions.note(OUTCOME_NOTE, RUN_NARROWED);
                return;
            }
            decisions.note(OUTCOME_NOTE, RUN_FULL);
            Selector.Decision.FullRunKind kind = current.fullRunKind();
            if (kind != null) {
                decisions.note(FULL_RUN_KIND_NOTE, kind.token());
            }
            decisions.note(FULL_RUN_REASON_NOTE, current.fullRunReason());
        } catch (Throwable ignored) {
            // A diagnostic that cannot be written must cost information, never the host's build.
        }
    }

    /**
     * The outcome a selecting run of these inputs notes, as an observation records it: the outcome,
     * then the full-run kind when it ran everything. The daemon's refusal is noted before this.
     */
    private String[] selectedOutcome() {
        if (!selectionRequested()) {
            return new String[] {RUN_NOT_REQUESTED};
        }
        Selector.Decision current = decision;
        if (current == null) {
            return new String[] {RUN_NOT_DECIDED};
        }
        if (!current.isFullRun()) {
            return new String[] {RUN_NARROWED};
        }
        Selector.Decision.FullRunKind kind = current.fullRunKind();
        return new String[] {RUN_FULL, kind == null ? "" : kind.token()};
    }

    /**
     * Never throws: a row that cannot be recorded must not change what runs. On an observing run the
     * verdict is what a selecting run would have done, recorded beside the row of what ran.
     */
    public void record(String id, Verdict verdict) {
        try {
            if (observing()) {
                decisions.observe(id, verdict);
                return;
            }
            decisions.add(id, verdict);
        } catch (Throwable ignored) {
            // A row that cannot be recorded is not worth failing a build over.
        }
    }

    /** Counts one test under the selector's reason, for the console tally. */
    public void count(Selector.Decision.Reason reason) {
        tally.merge(reason, 1, Integer::sum);
    }

    /** Counts one test its engine forces to run. */
    public void countEngineRunsEverything() {
        engineRunsEverything.incrementAndGet();
    }

    /** The configured always-run patterns, each remembering whether it matched. */
    public AlwaysRun alwaysRun() {
        return alwaysRun;
    }

    /** Counts one test kept by a pin. */
    public void countPinned() {
        pinned.incrementAndGet();
    }

    /** Counts one test a complement run left out. */
    public void countLeftOut() {
        leftOut.incrementAndGet();
    }

    /** Counts one test a complement run ran. */
    public void countComplementRan() {
        complementRan.incrementAndGet();
    }

    /** The complement record, read once; any doubt about it leaves out nothing. */
    public synchronized Complement complementRecord() {
        if (complement == null) {
            complement = readComplement(HostBuild.current().property(COMPLEMENT_RECORD_PROPERTY));
        }
        return complement;
    }

    private static Complement readComplement(String path) {
        String text;
        try {
            text = new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return new Complement(Collections.<String, String>emptyMap(), "the selection record could not be read (" + e + ")");
        }
        SelectionRecordFile.Result record = SelectionRecordFile.read(text);
        if (record.failure() != null) {
            return new Complement(Collections.<String, String>emptyMap(), "the selection record is unusable: " + record.failure());
        }
        for (String property : AgentContract.JVM_IDENTITY_PROPERTIES.split(",")) {
            String recorded = record.notes().get(AgentContract.JVM_NOTE_PREFIX + property);
            String here = System.getProperty(property, "");
            if (!here.equals(recorded)) {
                return new Complement(Collections.<String, String>emptyMap(),
                        "the selection record was written on another test JVM: " + property + " was " + recorded
                                + " there and is " + here + " here");
            }
        }
        return new Complement(record.ran(), null);
    }

    // At JVM exit because there is no end-of-discovery callback and the count is only complete then.
    private void reportTallyOnExit() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            int engineForced = engineRunsEverything.get();
            int total = engineForced;
            for (int count : tally.values()) {
                total += count;
            }
            if (total == 0) {
                return;
            }
            // An observing run's own line comes from the decode, which joins verdicts with outcomes.
            if (observing()) {
                reportPins();
                return;
            }
            StringBuilder line = new StringBuilder("[yoriwake] of " + total + " tests discovered:");
            for (Selector.Decision.Reason reason : Selector.Decision.Reason.values()) {
                Integer count = tally.get(reason);
                if (count != null) {
                    line.append(' ').append(reason.token())
                            .append('=').append(count);
                }
            }
            if (engineForced > 0) {
                line.append(' ').append(AgentContract.RULE_ENGINE_RUNS_EVERYTHING)
                        .append('=').append(engineForced);
            }
            System.out.println(line);
            reportPins();
        }));
    }

    // A pattern with a typo matches nothing and stays green, so unmatched patterns are named.
    private void reportPins() {
        int count = pinned.get();
        if (count > 0) {
            System.out.println("[yoriwake] " + count + " tests always-run by configuration, not by "
                    + "selection (" + alwaysRun.size() + " patterns plus @Tag(\""
                    + AlwaysRun.TAG + "\"))");
        }
        java.util.Set<String> missed = alwaysRun.unmatched();
        if (!missed.isEmpty()) {
            System.out.println("[yoriwake] WARNING: these always-run patterns matched NO test, so they "
                    + "are protecting nothing: " + missed + ". A pattern that matches nothing looks "
                    + "exactly like a suite where nothing needed pinning.");
        }
    }

    /** The decision, made once: the map must not be parsed for each test. */
    public synchronized Selector.Decision decide() {
        if (decision == null) {
            reportTallyOnExit();
            for (String input : FilterRule.INPUTS) {
                decisions.note(AgentContract.INPUT_NOTE_PREFIX + input, orEmpty(inputs().apply(input)));
            }
            decision = FilterRule.decisionFrom(inputs(), mapDir());
        }
        return decision;
    }

    /** Whether the run asked to select. A capture-only run must not, or the map would only describe tests it already knew. */
    public boolean selectionRequested() {
        return Boolean.parseBoolean(HostBuild.current().property(SELECT_PROPERTY));
    }

    /** Whether this run leaves out what a selecting run's record lists as ran. */
    public boolean complementing() {
        String path = HostBuild.current().property(COMPLEMENT_RECORD_PROPERTY);
        return path != null && !path.isEmpty();
    }

    /** Whether this run includes everything and records what selection would have done. */
    public boolean observing() {
        return Boolean.parseBoolean(HostBuild.current().property(OBSERVE_PROPERTY));
    }

    /** Whether a selection is rounded up to whole classes. See {@link AgentContract#CLASS_GRANULARITY_PROPERTY}. */
    public boolean classGranularity() {
        return Boolean.parseBoolean(HostBuild.current().property(CLASS_GRANULARITY_PROPERTY));
    }

    /** The daemon's own reason for refusing this run, or null when it did not refuse. */
    public String daemonRefusal() {
        return FilterRule.refusalFrom(inputs());
    }

    /** The decision's inputs, read once: the change-set file is the same for the whole run, and large. */
    public UnaryOperator<String> inputs() {
        UnaryOperator<String> resolved = inputs;
        if (resolved == null) {
            synchronized (this) {
                if (inputs == null) {
                    inputs = FilterRule.resolveInputs(HostBuild.current()::property);
                }
                resolved = inputs;
            }
        }
        return resolved;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static File mapDir() {
        String configured = HostBuild.current().property(MAP_DIR_PROPERTY);
        return configured == null || configured.isEmpty() ? null : new File(configured);
    }

    /**
     * Says once, on stdout, what the selector decided.
     *
     * <p>Deselected tests are invisible in Gradle's report — they are simply absent rather than
     * shown as skipped — so without this a developer cannot tell a selected run from a broken one.
     */
    public void reportOnce(String message) {
        if (!reported) {
            reported = true;
            System.out.println("[yoriwake] " + message);
        }
    }
}
