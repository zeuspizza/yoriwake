package io.github.zeuspizza.yoriwake.agent.engines;

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract;
import io.github.zeuspizza.yoriwake.agent.select.AlwaysRun;
import io.github.zeuspizza.yoriwake.agent.select.ClassVerdicts;
import io.github.zeuspizza.yoriwake.agent.select.FilterRule;
import io.github.zeuspizza.yoriwake.agent.select.SelectionRun;
import io.github.zeuspizza.yoriwake.agent.select.Selector;
import io.github.zeuspizza.yoriwake.agent.select.Verdict;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Whether a selecting run leaves out a plain JUnit 4 test class, asked by Gradle's rewritten
 * executor (see {@link JUnit4Hook}) once its own filters have run, with the class's runner.
 *
 * <p>Only a runner that runs exactly the class's own methods, one description each, may be left
 * out, and only when every test it describes now is one selection skips. Anything else runs, and
 * so does every class when anything here fails: the executor runs a class unless told otherwise.
 *
 * <p>The executor reaches this class through the system class loader by name, and passes the runner
 * as {@code Object}: Gradle's loader filters the application classpath, and this agent cannot
 * compile against JUnit.
 */
public final class JUnit4Selection {

    /**
     * Runners whose description is the class's own methods and nothing else: JUnit 4's default
     * runner, Mockito's, which only adds strictness around it, and JUnit 3's for a {@code TestCase}.
     */
    static final Set<String> ALLOWED_RUNNERS = new HashSet<>(Arrays.asList(
            "org.junit.runners.BlockJUnit4ClassRunner",
            "org.junit.runners.JUnit4",
            "org.mockito.junit.MockitoJUnitRunner",
            "org.mockito.junit.MockitoJUnitRunner$Silent",
            "org.mockito.junit.MockitoJUnitRunner$Strict",
            "org.mockito.junit.MockitoJUnitRunner$StrictStubs",
            "org.junit.internal.runners.JUnit38ClassRunner"));

    private static final String IGNORE = "org.junit.Ignore";
    /** JUnit 3's runner, which runs a method whatever {@code @Ignore} it carries. */
    private static final String JUNIT3_RUNNER = "org.junit.internal.runners.JUnit38ClassRunner";
    private static final String FILTERABLE = "org.junit.runner.manipulation.Filterable";

    /** Set at install, on a run that narrows plain JUnit 4; null elsewhere, which runs every class. */
    private static volatile JUnit4Selection active;

    private final SelectionRun run;
    /** Whether Gradle's executor took the rewrite; null until an executor was seen. */
    private volatile String executor;
    private volatile boolean failed;
    /** Per token, how many classes ran whole, and the first such class, for the note and the console. */
    private final Map<String, Integer> wholeCounts = new LinkedHashMap<>();
    private final Map<String, String> wholeFirst = new LinkedHashMap<>();
    private int leftOut;

    private JUnit4Selection() {
        this.run = new SelectionRun(this::noteAtExit);
    }

    /** Starts judging for this JVM. Called once, from premain, when the run narrows plain JUnit 4. */
    static void activate() {
        if (active == null) {
            active = new JUnit4Selection();
        }
    }

    /** Called by the rewrite: whether an executor took it, {@code hooked} or {@code unhooked}. */
    static void executorRewritten(String outcome) {
        JUnit4Selection current = active;
        if (current != null && !AgentContract.PLAIN_FILTER_HOOKED.equals(current.executor)) {
            current.executor = outcome;
        }
    }

    /**
     * Called by Gradle's rewritten executor with a class's runner: true leaves the class out, as
     * Gradle leaves out a class its own filters emptied. Never throws.
     */
    public static boolean leaveOut(Object runner) {
        JUnit4Selection current = active;
        if (current == null) {
            return false;
        }
        try {
            return current.judge(runner);
        } catch (Throwable failure) {
            current.failed = true;
            current.run.reportOnce("plain JUnit 4 selection failed, so the class runs: " + failure);
            return false;
        }
    }

    private synchronized boolean judge(Object runner) throws Exception {
        // A complement run never reads the flag; this keeps a misdirected one from leaving anything out.
        if (run.complementing()) {
            return false;
        }
        Object description = runner.getClass().getMethod("getDescription").invoke(runner);
        List<Object> leaves = new ArrayList<>();
        leavesOf(description, leaves);
        String className = (String) invoke(description, "getClassName");
        String keepReason = keepReason(runner, className, leaves);
        List<String> ids = new ArrayList<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (Object leaf : leaves) {
            // An ignored test never runs and the capture never records it, so judging it would keep
            // its class on every run as never seen. JUnit 3 runs it anyway, so there it is judged.
            if (!JUNIT3_RUNNER.equals(runner.getClass().getName()) && ignored(leaf)) {
                continue;
            }
            String id = JUnit4Events.idOf(leaf);
            if (id != null) {
                ids.add(id);
                names.put(id, invoke(leaf, "getClassName") + "." + invoke(leaf, "getMethodName"));
            }
        }
        boolean counted = run.daemonRefusal() == null && run.selectionRequested();
        // As the Platform's filter: a run that did not ask to select reads no map.
        Selector.Decision decision = counted || run.daemonRefusal() != null ? run.decide() : null;
        AlwaysRun pins = run.alwaysRun();
        ClassVerdicts judged = ClassVerdicts.of(run.inputs(), decision,
                id -> pins.matches(names.get(id)) | pins.matches(names.get(id).replace('$', '.')), ids, keepReason);
        for (Map.Entry<String, Verdict> row : judged.verdicts().entrySet()) {
            if (row.getValue() == Verdict.ALWAYS_RUN) {
                run.countPinned();
            } else if (counted) {
                run.count(FilterRule.reasonOf(decision, row.getKey(), true));
            }
            run.record(row.getKey(), row.getValue());
        }
        if (counted && decision.isFullRun()) {
            run.reportOnce("running everything: " + decision.fullRunReason());
        }
        if (keepReason != null) {
            wholeCounts.merge(keepReason, 1, Integer::sum);
            wholeFirst.putIfAbsent(keepReason, className);
        }
        // Decided and recorded as a selecting run would, then the class runs: what it would have left
        // out is the observation.
        if (run.observing() || !judged.leaveOut()) {
            return false;
        }
        leftOut++;
        return true;
    }

    /** Why the class runs whole whatever selection says, or null when its runner and shape are judged. */
    static String keepReason(Object runner, String className, List<Object> leaves) throws Exception {
        if (!ALLOWED_RUNNERS.contains(runner.getClass().getName())) {
            return AgentContract.CLASS_RUNS_WHOLE_JUNIT4_RUNNER;
        }
        Class<?> filterable = Class.forName(FILTERABLE, false, runner.getClass().getClassLoader());
        if (!filterable.isInstance(runner)) {
            return AgentContract.CLASS_RUNS_WHOLE_JUNIT4_NOT_FILTERABLE;
        }
        if (leaves.isEmpty() || className == null) {
            return AgentContract.CLASS_RUNS_WHOLE_JUNIT4_SHAPE;
        }
        for (Object leaf : leaves) {
            if (!className.equals(invoke(leaf, "getClassName")) || invoke(leaf, "getMethodName") == null) {
                return AgentContract.CLASS_RUNS_WHOLE_JUNIT4_SHAPE;
            }
        }
        return null;
    }

    /** The descriptions beneath {@code description} that have no children, in order. */
    static void leavesOf(Object description, List<Object> leaves) throws Exception {
        Collection<?> children = (Collection<?>) invoke(description, "getChildren");
        if (children.isEmpty()) {
            leaves.add(description);
            return;
        }
        for (Object child : children) {
            leavesOf(child, leaves);
        }
    }

    private static boolean ignored(Object leaf) throws Exception {
        for (Object annotation : (Collection<?>) invoke(leaf, "getAnnotations")) {
            if (IGNORE.equals(((java.lang.annotation.Annotation) annotation).annotationType().getName())) {
                return true;
            }
        }
        return false;
    }

    private static Object invoke(Object target, String method) throws Exception {
        Method found = target.getClass().getMethod(method);
        return found.invoke(target);
    }

    /** The plain filter's notes, added as the record is written at exit. */
    private synchronized void noteAtExit() {
        String outcome = failed ? AgentContract.PLAIN_FILTER_UNSUPPORTED
                : executor == null ? AgentContract.PLAIN_FILTER_UNHOOKED : executor;
        run.decisions().note(AgentContract.PLAIN_FILTER_NOTE, outcome);
        if (!wholeCounts.isEmpty()) {
            List<String> counts = new ArrayList<>();
            for (Map.Entry<String, Integer> count : wholeCounts.entrySet()) {
                counts.add(count.getKey() + "=" + count.getValue());
            }
            run.decisions().note(AgentContract.CLASS_RUNS_WHOLE_NOTE, String.join(",", counts));
        }
        if (run.observing()) {
            return;
        }
        StringBuilder line = new StringBuilder("[yoriwake] plain JUnit 4 selects whole classes: " + leftOut
                + " left out");
        for (Map.Entry<String, String> first : wholeFirst.entrySet()) {
            line.append("; ").append(wholeCounts.get(first.getKey())).append(" ran whole (")
                    .append(first.getKey()).append(", first ").append(first.getValue()).append(')');
        }
        System.out.println(line);
    }
}
