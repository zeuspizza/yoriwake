package io.github.zeuspizza.yoriwake.agent.platform;

import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.CLASS_GRANULARITY_PROPERTY;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.COMPLEMENT_NOTE;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.COMPLEMENT_RECORD_MISMATCH_KIND;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.COMPLEMENT_RECORD_PROPERTY;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.DECLINES_NOTE;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.DECLINES_PROPERTY;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.FULL_RUN_KIND_NOTE;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.FULL_RUN_REASON_NOTE;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.MAP_DIR_PROPERTY;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.OBSERVED_OUTCOME_NOTE;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.OBSERVE_PROPERTY;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.OUTCOME_NOTE;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.REFUSAL_KIND_NOTE;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.REFUSED_KIND_PROPERTY;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.RUN_COMPLEMENTED;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.RUN_FULL;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.RUN_NARROWED;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.RUN_NOT_DECIDED;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.RUN_NOT_REQUESTED;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.SELECT_PROPERTY;

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract;
import io.github.zeuspizza.yoriwake.agent.engines.PlatformEvents;
import io.github.zeuspizza.yoriwake.agent.host.AttachedLoader;
import io.github.zeuspizza.yoriwake.agent.select.AlwaysRun;
import io.github.zeuspizza.yoriwake.agent.select.FilterRule;
import io.github.zeuspizza.yoriwake.agent.select.SelectionRecordFile;
import io.github.zeuspizza.yoriwake.agent.select.Selector;
import io.github.zeuspizza.yoriwake.agent.select.Verdict;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestTag;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.launcher.PostDiscoveryFilter;

/**
 * Deselects tests a change cannot reach, at discovery time.
 *
 * <p>A {@code PostDiscoveryFilter} rather than a Gradle test filter: Gradle matches name patterns,
 * not test ids, and this is the only place that sees the tests that exist right now, which "always
 * run a test the map has never seen" requires. Registered by {@code ServiceLoader}.
 *
 * <p>Opt-in per run: with no selection requested, everything is included and the run only builds
 * or refreshes the map. An observing run decides as a selecting one does, includes everything, and
 * records each verdict beside the row of what ran. A complement run leaves out only the tests a
 * selecting run's record lists as ran.
 */
public class SelectionFilter implements PostDiscoveryFilter {

    // A copy another class loader defined serves a nested launcher the map never described: it
    // includes everything and records nothing. See AttachedLoader.
    private static final boolean ATTACHED = AttachedLoader.attached(SelectionFilter.class);

    // Created while a test runs, so it serves a launcher that test started. What that launcher runs
    // is part of the running test, which the map describes whole: it includes everything and
    // records nothing, like a copy.
    private final boolean nested;

    // Written by discovery, read as a whole by the shutdown hook.
    private final Map<Selector.Decision.Reason, Integer> tally =
            Collections.synchronizedMap(new EnumMap<>(Selector.Decision.Reason.class));
    private volatile Selector.Decision decision;
    private volatile java.util.function.UnaryOperator<String> inputs;
    private volatile boolean reported;

    // Hooked separately from the tally: a run never asked to select never calls decide(), but
    // still needs its record written.
    private final DecisionRecord decisions;

    private volatile boolean recordHookInstalled;

    // Pins are counted apart from the tally: a pin is selection told not to apply, not an outcome.
    private final AlwaysRun alwaysRun =
            AlwaysRun.from(System.getProperty(AgentContract.ALWAYS_RUN_PROPERTY, ""));
    /** Memo for {@link #anySiblingSelects}: outermost class id -> does anything in it select. */
    private final Map<String, Boolean> classSelects = new java.util.concurrent.ConcurrentHashMap<>();

    private final java.util.concurrent.atomic.AtomicInteger pinned =
            new java.util.concurrent.atomic.AtomicInteger();

    // Apart from the tally, which counts the selector's reasons: the selector never judged these.
    private final java.util.concurrent.atomic.AtomicInteger engineRunsEverything =
            new java.util.concurrent.atomic.AtomicInteger();

    // A complement run's record, read once; and what it left out and ran, for its note.
    private volatile Complement complement;
    private final java.util.concurrent.atomic.AtomicInteger leftOut = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger complementRan =
            new java.util.concurrent.atomic.AtomicInteger();

    /** The tests a complement run may leave out, or why it leaves out none. */
    private static final class Complement {
        final java.util.Map<String, String> ran;
        final String mismatch;

        Complement(java.util.Map<String, String> ran, String mismatch) {
            this.ran = ran;
            this.mismatch = mismatch;
        }
    }

    /** Required by {@link java.util.ServiceLoader}: JUnit instantiates filters with no arguments. */
    public SelectionFilter() {
        this(PlatformEvents.planExecuting());
    }

    SelectionFilter(boolean nested) {
        this.nested = nested;
        // Not counted as a writer: the writer note says how many records describe this task.
        this.decisions = nested ? null : new DecisionRecord();
        // Installed at construction: a run that discovers no tests never calls apply(), and a
        // refused run that discovered nothing must still record why.
        installRecordHook();
    }

    @Override
    public FilterResult apply(TestDescriptor descriptor) {
        // A filter that throws aborts discovery for the whole run. A failure here must cost
        // selectivity, never the ability to run tests.
        try {
            if (!ATTACHED) {
                return FilterResult.included("a copy of the agent in another class loader does not select");
            }
            if (nested) {
                return FilterResult.included("a launcher started by a running test does not select");
            }
            // The constructor already installed it, but the Platform swallows exceptions there.
            installRecordHook();
            return decideFor(descriptor);
        } catch (Throwable failure) {
            reportOnce("selection failed, so everything runs: " + failure);
            record(descriptor, Verdict.SELECTION_FAILED);
            return FilterResult.included("selection failed");
        }
    }

    // At JVM exit because there is no end-of-discovery callback.
    private void installRecordHook() {
        if (recordHookInstalled || nested) {
            return;
        }
        synchronized (this) {
            if (recordHookInstalled) {
                return;
            }
            recordHookInstalled = true;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                noteOutcome();
                Selector.Decision current = decision;
                decisions.writeTo(mapDir(), current == null ? null : current.rules());
            }));
        }
    }

    /**
     * Records why this run selected what it did, as a fact about the run: a refusal, an unrequested
     * selection, a full run and its reason, or a narrowed run. Written only, never read back.
     */
    private void noteOutcome() {
        try {
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

    // By @Tag or by pattern, so pinning needs nothing on the host's compile classpath.
    private boolean isPinned(TestDescriptor descriptor) {
        try {
            for (TestTag tag : descriptor.getTags()) {
                if (AlwaysRun.TAG.equals(tag.getName())) {
                    return true;
                }
            }
            return alwaysRun.matches(nameOf(descriptor));
        } catch (Throwable ignored) {
            // A pin that cannot be evaluated must not decide anything, and must not break the run.
            return false;
        }
    }

    /**
     * {@code com.acme.FlakyTest} or {@code com.acme.FlakyTest.method}, from the unique id: the
     * source is absent for dynamic tests and the display name is not something to match against.
     */
    private static String nameOf(TestDescriptor descriptor) {
        String type = null;
        String method = null;
        for (UniqueId.Segment segment : descriptor.getUniqueId().getSegments()) {
            String kind = segment.getType();
            if ("class".equals(kind) || "nested-class".equals(kind)) {
                type = segment.getValue();
            } else if ("method".equals(kind) || "test-template".equals(kind)) {
                // `foo(java.lang.String)` -- the parameter list is not something a user would write.
                String value = segment.getValue();
                int paren = value.indexOf('(');
                method = paren < 0 ? value : value.substring(0, paren);
            }
        }
        if (type == null) {
            return null;
        }
        return method == null ? type : type + "." + method;
    }

    /**
     * Never throws: a row that cannot be recorded must not change what runs. On an observing run the
     * verdict is what a selecting run would have done, recorded beside the row of what ran.
     */
    private void record(TestDescriptor descriptor, Verdict verdict) {
        try {
            if (observing()) {
                decisions.observe(descriptor.getUniqueId().toString(), verdict);
                return;
            }
            decisions.add(descriptor.getUniqueId().toString(), verdict);
        } catch (Throwable ignored) {
            // An id that cannot even be turned into a string is not worth failing a build over.
        }
    }

    private FilterResult decideFor(TestDescriptor descriptor) {
        FilterResult selected = select(descriptor);
        // Decided and recorded as a selecting run would, then everything runs: what it would have left
        // out is the observation, and a test it left out must still run to show its outcome.
        if (observing()) {
            return FilterResult.included("the run observes selection");
        }
        return selected;
    }

    /** What a selecting run includes, each verdict recorded. */
    private FilterResult select(TestDescriptor descriptor) {
        // Before the select flag: a refusal alongside a selection request is a contradiction, and
        // the safe reading runs everything. Deliberately redundant with the refusal the decision
        // itself forces; this one also gives every row its own verdict in the record.
        if (daemonRefusal() != null) {
            decide();
            record(descriptor, Verdict.DAEMON_REFUSED);
            return FilterResult.included("the daemon refused this run");
        }
        if (complementing()) {
            return complement(descriptor);
        }
        if (!selectionRequested()) {
            record(descriptor, Verdict.SELECTION_NOT_REQUESTED);
            return FilterResult.included("selection not requested");
        }
        boolean leaf = descriptor.getChildren().isEmpty();
        if (!leaf && !descriptor.isTest()) {
            // Excluding a container would drop its tests undecided. A leaf container (template or
            // dynamic factory) is judged here, since its children appear only at execution.
            return FilterResult.included("a container whose children are judged individually");
        }

        // After the container check (JUnit propagates a class @Tag to its methods), and before the
        // map is consulted: a pinned test must run even when the map is unusable.
        if (isPinned(descriptor)) {
            pinned.incrementAndGet();
            record(descriptor, Verdict.ALWAYS_RUN);
            return FilterResult.included("always-run by configuration");
        }

        Selector.Decision current = decide();
        String id = descriptor.getUniqueId().toString();
        Verdict verdict = FilterRule.verdictFor(inputs(), current, id, descriptor.isTest());
        if (verdict == Verdict.ENGINE_RUNS_EVERYTHING) {
            engineRunsEverything.incrementAndGet();
            record(descriptor, verdict);
            return FilterResult.included("its engine runs nothing once one of its tests is left out");
        }
        tally.merge(FilterRule.reasonOf(current, id, descriptor.isTest()), 1, Integer::sum);

        if (current.isFullRun()) {
            reportOnce("running everything: " + current.fullRunReason());
            record(descriptor, verdict);
            return FilterResult.included(current.fullRunReason());
        }
        if (!verdict.included()) {
            // Rounding up to the class only ever includes, so it cannot lose a failure.
            if (classGranularity() && anySiblingSelects(descriptor, current)) {
                record(descriptor, Verdict.CLASS_GRANULARITY);
                return FilterResult.included("another test in its class is selected");
            }
            record(descriptor, verdict);
            return FilterResult.excluded("not reachable from the change");
        }
        record(descriptor, verdict);
        return FilterResult.included(verdict.reasonToken());
    }


    /**
     * What a complement run includes: everything when the record was written on another test JVM or
     * cannot be read; else every test but those it lists as ran, matched by exact id. A container with
     * children is judged through them; a leaf container and a test of an engine that runs everything
     * always run, whatever the record lists. So does a leaf that is both container and test, such as a
     * Spock data-driven feature: a dry run reports it finished without running it.
     */
    private FilterResult complement(TestDescriptor descriptor) {
        Complement read = complementRecord();
        if (read.mismatch != null) {
            reportOnce("running everything: " + read.mismatch);
            record(descriptor, Verdict.FULL_RUN);
            return FilterResult.included(read.mismatch);
        }
        if (!descriptor.getChildren().isEmpty()) {
            return FilterResult.included("a container whose children are judged individually");
        }
        String id = descriptor.getUniqueId().toString();
        if (FilterRule.engineRunsEverything(id)) {
            complementRan.incrementAndGet();
            record(descriptor, Verdict.ENGINE_RUNS_EVERYTHING);
            return FilterResult.included("its engine runs nothing once one of its tests is left out");
        }
        if (descriptor.getType() == TestDescriptor.Type.TEST && read.ran.containsKey(id)) {
            leftOut.incrementAndGet();
            record(descriptor, Verdict.ALREADY_RAN);
            return FilterResult.excluded("the selecting run ran it");
        }
        complementRan.incrementAndGet();
        record(descriptor, Verdict.NOT_ALREADY_RAN);
        return FilterResult.included("the selecting run did not run it");
    }

    /** The complement record, read once; any doubt about it leaves out nothing. */
    private synchronized Complement complementRecord() {
        if (complement == null) {
            complement = readComplement(System.getProperty(COMPLEMENT_RECORD_PROPERTY));
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

    // Once per run: JUnit applies the filter per descriptor, and the map must not be parsed each time.
    private synchronized Selector.Decision decide() {
        if (decision == null) {
            reportTallyOnExit();
            for (String input : FilterRule.INPUTS) {
                decisions.note(AgentContract.INPUT_NOTE_PREFIX + input, orEmpty(inputs().apply(input)));
            }
            decision = FilterRule.decisionFrom(inputs(), mapDir());
        }
        return decision;
    }

    // A capture-only run must not deselect, or the map would only describe tests it already knew.
    private boolean selectionRequested() {
        return Boolean.parseBoolean(System.getProperty(SELECT_PROPERTY, "false"));
    }

    /** Whether this run leaves out what a selecting run's record lists as ran. */
    private boolean complementing() {
        String path = System.getProperty(COMPLEMENT_RECORD_PROPERTY);
        return path != null && !path.isEmpty();
    }

    /** Whether this run includes everything and records what selection would have done. */
    private boolean observing() {
        return Boolean.parseBoolean(System.getProperty(OBSERVE_PROPERTY, "false"));
    }

    /** Whether a selection is rounded up to whole classes. See {@link AgentContract#CLASS_GRANULARITY_PROPERTY}. */
    private boolean classGranularity() {
        return Boolean.parseBoolean(System.getProperty(CLASS_GRANULARITY_PROPERTY, "false"));
    }

    // Outermost, not innermost: a @Nested class shares static state with its enclosing class.
    // Memoised per class; each sibling still pays a full reasonForTest scan of the recorded ids.
    private boolean anySiblingSelects(TestDescriptor descriptor, Selector.Decision current) {
        TestDescriptor outermost = outermostClass(descriptor);
        if (outermost == null) {
            return false;
        }
        return classSelects.computeIfAbsent(
                outermost.getUniqueId().toString(), key -> subtreeSelects(outermost, current));
    }

    /** The highest ancestor that is still a class, or null when the descriptor is not in one. */
    private TestDescriptor outermostClass(TestDescriptor descriptor) {
        TestDescriptor found = null;
        for (TestDescriptor at = descriptor.getParent().orElse(null);
                at != null;
                at = at.getParent().orElse(null)) {
            if (at.getUniqueId().toString().contains("[class:")) {
                found = at;
            }
        }
        return found;
    }

    /** Whether any test in this subtree is something other than SKIPPED. */
    private boolean subtreeSelects(TestDescriptor at, Selector.Decision current) {
        if (at.isTest()
                && current.reasonForTest(at.getUniqueId().toString())
                        != Selector.Decision.Reason.SKIPPED) {
            return true;
        }
        for (TestDescriptor child : at.getChildren()) {
            if (subtreeSelects(child, current)) {
                return true;
            }
        }
        return false;
    }

    /** The daemon's own reason for refusing this run, or null when it did not refuse. */
    private String daemonRefusal() {
        return FilterRule.refusalFrom(inputs());
    }



    /** Read once: the change-set file is the same for the whole run, and large. */
    private java.util.function.UnaryOperator<String> inputs() {
        java.util.function.UnaryOperator<String> resolved = inputs;
        if (resolved == null) {
            synchronized (this) {
                if (inputs == null) {
                    inputs = FilterRule.resolveInputs(System::getProperty);
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
        String configured = System.getProperty(MAP_DIR_PROPERTY);
        return configured == null || configured.isEmpty() ? null : new File(configured);
    }


    /**
     * Says once, on stdout, what the selector decided.
     *
     * <p>Deselected tests are invisible in Gradle's report — they are simply absent rather than
     * shown as skipped — so without this a developer cannot tell a selected run from a broken one.
     */
    private void reportOnce(String message) {
        if (!reported) {
            reported = true;
            System.out.println("[yoriwake] " + message);
        }
    }
}
