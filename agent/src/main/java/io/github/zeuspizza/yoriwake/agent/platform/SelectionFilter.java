package io.github.zeuspizza.yoriwake.agent.platform;

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract;
import io.github.zeuspizza.yoriwake.agent.engines.PlatformEvents;
import io.github.zeuspizza.yoriwake.agent.host.AttachedLoader;
import io.github.zeuspizza.yoriwake.agent.select.AlwaysRun;
import io.github.zeuspizza.yoriwake.agent.select.DependsOn;
import io.github.zeuspizza.yoriwake.agent.select.FilterRule;
import io.github.zeuspizza.yoriwake.agent.select.PathGlob;
import io.github.zeuspizza.yoriwake.agent.select.SelectionRun;
import io.github.zeuspizza.yoriwake.agent.select.Selector;
import io.github.zeuspizza.yoriwake.agent.select.Verdict;
import java.util.Map;
import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.TestTag;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
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

    // The run's inputs, decision, tally and record; null for a nested launcher, which records nothing.
    private final SelectionRun run;

    /** Memo for {@link #anySiblingSelects}: outermost class id -> does anything in it select. */
    private final Map<String, Boolean> classSelects = new java.util.concurrent.ConcurrentHashMap<>();

    /** Required by {@link java.util.ServiceLoader}: JUnit instantiates filters with no arguments. */
    public SelectionFilter() {
        this(PlatformEvents.planExecuting());
    }

    SelectionFilter(boolean nested) {
        this.nested = nested;
        // Not counted as a writer: the writer note says how many records describe this task. Its
        // record hook is installed at construction: a run that discovers no tests never calls
        // apply(), and a refused run that discovered nothing must still record why.
        this.run = nested ? null : new SelectionRun();
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
            run.installRecordHook();
            return decideFor(descriptor);
        } catch (Throwable failure) {
            run.reportOnce("selection failed, so everything runs: " + failure);
            record(descriptor, Verdict.SELECTION_FAILED);
            return FilterResult.included("selection failed");
        }
    }

    /**
     * Whether a {@code yoriwake-depends-on:} tag of the test matches a changed path. A tag that
     * cannot be evaluated selects the test: it only ever adds, so doubt runs it.
     */
    private boolean declaresChangedPath(TestDescriptor descriptor) {
        for (TestTag tag : descriptor.getTags()) {
            String glob = DependsOn.globOf(tag.getName());
            if (glob == null) {
                continue;
            }
            try {
                String projectDir = run.inputs().apply(AgentContract.PROJECT_DIR_PROPERTY);
                String changed = run.inputs().apply(AgentContract.CHANGED_PATHS_PROPERTY);
                if (projectDir == null || changed == null || PathGlob.problem(glob) != null) {
                    return true;
                }
                String resolved = PathGlob.resolve(glob, projectDir);
                for (String path : changed.split(",")) {
                    if (!path.isEmpty() && PathGlob.matches(resolved, path)) {
                        return true;
                    }
                }
            } catch (Throwable unreadable) {
                return true;
            }
        }
        return false;
    }

    // By @Tag or by pattern, so pinning needs nothing on the host's compile classpath.
    private boolean isPinned(TestDescriptor descriptor) {
        try {
            for (TestTag tag : descriptor.getTags()) {
                if (AlwaysRun.TAG.equals(tag.getName())) {
                    return true;
                }
            }
            AlwaysRun alwaysRun = run.alwaysRun();
            boolean pinned = alwaysRun.matches(nameOf(descriptor));
            // Every name is matched, not only until one hits, so each pattern that matches counts.
            String sourced = sourcedNameOf(descriptor);
            if (sourced != null) {
                pinned |= alwaysRun.matches(sourced);
                pinned |= alwaysRun.matches(sourced.replace('$', '.'));
            }
            return pinned;
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
     * {@code com.acme.Outer$Inner.method}, from the class and method its engine names at discovery.
     * The unique id names a nested class by its simple name and carries no class at all for a vintage
     * test or a Spock feature, so a pattern naming the class would not reach them through it alone.
     */
    private static String sourcedNameOf(TestDescriptor descriptor) {
        TestSource source = descriptor.getSource().orElse(null);
        if (source instanceof MethodSource) {
            MethodSource method = (MethodSource) source;
            return method.getClassName() + "." + method.getMethodName();
        }
        if (source instanceof ClassSource) {
            return ((ClassSource) source).getClassName();
        }
        return null;
    }

    /**
     * Never throws: a row that cannot be recorded must not change what runs. On an observing run the
     * verdict is what a selecting run would have done, recorded beside the row of what ran.
     */
    private void record(TestDescriptor descriptor, Verdict verdict) {
        try {
            run.record(descriptor.getUniqueId().toString(), verdict);
        } catch (Throwable ignored) {
            // An id that cannot even be turned into a string is not worth failing a build over.
        }
    }

    private FilterResult decideFor(TestDescriptor descriptor) {
        FilterResult selected = select(descriptor);
        // Decided and recorded as a selecting run would, then everything runs: what it would have left
        // out is the observation, and a test it left out must still run to show its outcome.
        if (run.observing()) {
            return FilterResult.included("the run observes selection");
        }
        return selected;
    }

    /** What a selecting run includes, each verdict recorded. */
    private FilterResult select(TestDescriptor descriptor) {
        // Before the select flag: a refusal alongside a selection request is a contradiction, and
        // the safe reading runs everything. Deliberately redundant with the refusal the decision
        // itself forces; this one also gives every row its own verdict in the record.
        if (run.daemonRefusal() != null) {
            run.decide();
            record(descriptor, Verdict.DAEMON_REFUSED);
            return FilterResult.included("the daemon refused this run");
        }
        if (run.complementing()) {
            return complement(descriptor);
        }
        if (!run.selectionRequested()) {
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
            run.countPinned();
            record(descriptor, Verdict.ALWAYS_RUN);
            return FilterResult.included("always-run by configuration");
        }
        // Before the map too: a declared dependency is one coverage cannot see, so no rule below
        // could have selected the test for it, and a full run still records why it ran.
        if (declaresChangedPath(descriptor)) {
            record(descriptor, Verdict.DECLARES_CHANGED_PATH);
            return FilterResult.included("it declares a dependency on a changed path");
        }

        Selector.Decision current = run.decide();
        String id = descriptor.getUniqueId().toString();
        Verdict verdict = FilterRule.verdictFor(run.inputs(), current, id, descriptor.isTest());
        if (verdict == Verdict.ENGINE_RUNS_EVERYTHING) {
            run.countEngineRunsEverything();
            record(descriptor, verdict);
            return FilterResult.included("its engine runs nothing once one of its tests is left out");
        }
        run.count(FilterRule.reasonOf(current, id, descriptor.isTest()));

        if (current.isFullRun()) {
            run.reportOnce("running everything: " + current.fullRunReason());
            record(descriptor, verdict);
            return FilterResult.included(current.fullRunReason());
        }
        if (!verdict.included()) {
            // Rounding up to the class only ever includes, so it cannot lose a failure.
            if (run.classGranularity() && anySiblingSelects(descriptor, current)) {
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
        SelectionRun.Complement read = run.complementRecord();
        if (read.mismatch != null) {
            run.reportOnce("running everything: " + read.mismatch);
            record(descriptor, Verdict.FULL_RUN);
            return FilterResult.included(read.mismatch);
        }
        if (!descriptor.getChildren().isEmpty()) {
            return FilterResult.included("a container whose children are judged individually");
        }
        String id = descriptor.getUniqueId().toString();
        if (FilterRule.engineRunsEverything(id)) {
            run.countComplementRan();
            record(descriptor, Verdict.ENGINE_RUNS_EVERYTHING);
            return FilterResult.included("its engine runs nothing once one of its tests is left out");
        }
        if (descriptor.getType() == TestDescriptor.Type.TEST && read.ran.containsKey(id)) {
            run.countLeftOut();
            record(descriptor, Verdict.ALREADY_RAN);
            return FilterResult.excluded("the selecting run ran it");
        }
        run.countComplementRan();
        record(descriptor, Verdict.NOT_ALREADY_RAN);
        return FilterResult.included("the selecting run did not run it");
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
}
