package io.github.zeuspizza.yoriwake.agent.select;

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract;
import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * The test-JVM selection rule, apart from the JUnit Platform it runs inside.
 *
 * <p>{@link SelectionFilter} applies it to each discovered descriptor, and offline tooling can apply
 * it to recorded runs, so both run the shipped rule rather than a copy. It lives outside the filter
 * because the filter cannot load without the JUnit Platform, which the agent jar does not carry.
 */
public final class FilterRule {

    private FilterRule() {
    }

    /**
     * Every property the decision reads, recorded into the decision record as {@code input.<name>}.
     *
     * <p>With these and the map it read, a recorded decision can be recomputed offline.
     */
    public static final String[] INPUTS = {
            AgentContract.SELECT_PROPERTY,
            AgentContract.CHANGED_CLASSES_PROPERTY,
            AgentContract.UNMAPPABLE_PATHS_PROPERTY,
            AgentContract.ACCOUNTED_PROPERTY,
            AgentContract.ABSENCE_PROVABLE_PROPERTY,
            AgentContract.UNREADABLE_PATHS_PROPERTY,
            AgentContract.OWN_TEST_CLASSES_PROPERTY,
            AgentContract.EXEMPT_TEST_CLASSES_PROPERTY,
            AgentContract.CHANGED_BYTES_PROPERTY,
            AgentContract.REFUSED_PROPERTY,
            AgentContract.REFUSED_KIND_PROPERTY,
            AgentContract.CLASS_GRANULARITY_PROPERTY,
            AgentContract.ALWAYS_RUN_PROPERTY,
    };

    /**
     * The inputs that can grow with the change set, which the daemon hands over in {@link
     * AgentContract#CHANGE_SET_FILE} rather than as properties.
     */
    public static final Set<String> CHANGE_SET_INPUTS = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList(
                    AgentContract.CHANGED_CLASSES_PROPERTY,
                    AgentContract.UNMAPPABLE_PATHS_PROPERTY,
                    AgentContract.ABSENCE_PROVABLE_PROPERTY,
                    AgentContract.UNREADABLE_PATHS_PROPERTY,
                    AgentContract.OWN_TEST_CLASSES_PROPERTY,
                    AgentContract.EXEMPT_TEST_CLASSES_PROPERTY,
                    AgentContract.CHANGED_BYTES_PROPERTY)));

    /**
     * The decision's inputs as the daemon meant them: {@code system}, with every name in {@link
     * #CHANGE_SET_INPUTS} answered from the file {@link AgentContract#CHANGE_SET_FILE_PROPERTY}
     * names, when it is set.
     *
     * <p>A named file that cannot be read whole is a refusal, so the run executes everything: its
     * change set is unknown, and an empty one would narrow.
     */
    public static UnaryOperator<String> resolveInputs(UnaryOperator<String> system) {
        String path = system.apply(AgentContract.CHANGE_SET_FILE_PROPERTY);
        if (path == null || path.isEmpty()) {
            return system;
        }
        Properties file = new Properties();
        String failure = null;
        try {
            String text = new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
            if (text.endsWith(AgentContract.CHANGE_SET_END + "\n")) {
                file.load(new StringReader(text));
            } else {
                failure = "it was cut short";
            }
        } catch (Exception e) {
            failure = e.toString();
        }
        if (failure == null) {
            return name -> CHANGE_SET_INPUTS.contains(name) ? file.getProperty(name) : system.apply(name);
        }
        String refusal = "the change set in " + path + " could not be read (" + failure + ")";
        return name -> {
            if (CHANGE_SET_INPUTS.contains(name)) {
                return null;
            }
            String own = system.apply(AgentContract.REFUSED_PROPERTY);
            // A refusal the daemon already made keeps its own reason and kind.
            if (own != null && !own.isEmpty()) {
                return system.apply(name);
            }
            if (AgentContract.REFUSED_PROPERTY.equals(name)) {
                return refusal;
            }
            if (AgentContract.REFUSED_KIND_PROPERTY.equals(name)) {
                return AgentContract.CHANGE_SET_UNREADABLE_KIND;
            }
            return system.apply(name);
        };
    }

    /**
     * Engines that run nothing once any test they discovered is excluded, so selection never
     * excludes one of their tests. Kotest 5 executes its own list of the specs it discovered, not
     * the filtered plan: with one spec left out the task runs no test at all and still passes.
     * JUnit Jupiter, Vintage, the Platform Suite, the TestNG engine, Spock, jqwik and ArchUnit each
     * ran the rest when one class was left out, so they are not here.
     */
    private static final Set<String> ENGINES_THAT_RUN_EVERYTHING = Set.of("kotest");

    /** Whether this test runs on one of {@link #ENGINES_THAT_RUN_EVERYTHING}, at any depth. */
    public static boolean engineRunsEverything(String id) {
        for (String engine : ENGINES_THAT_RUN_EVERYTHING) {
            if (id.contains("[engine:" + engine + "]")) {
                return true;
            }
        }
        return false;
    }

    /** The decision a test JVM makes from these properties and this map. */
    public static Selector.Decision decisionFrom(UnaryOperator<String> property, File mapDir) {
        ChangeSet change = ChangeSet.of(
                        splitProperty(property, AgentContract.CHANGED_CLASSES_PROPERTY),
                        splitProperty(property, AgentContract.UNMAPPABLE_PATHS_PROPERTY))
                .withAccountedFor(Boolean.parseBoolean(orEmpty(property, AgentContract.ACCOUNTED_PROPERTY)))
                .withAbsenceProvable(splitProperty(property, AgentContract.ABSENCE_PROVABLE_PROPERTY))
                .withUnreadablePaths(splitProperty(property, AgentContract.UNREADABLE_PATHS_PROPERTY))
                .withOwnTestClasses(splitProperty(property, AgentContract.OWN_TEST_CLASSES_PROPERTY))
                .withExemptTestClasses(splitProperty(property, AgentContract.EXEMPT_TEST_CLASSES_PROPERTY))
                .withChangedBytes(splitProperty(property, AgentContract.CHANGED_BYTES_PROPERTY))
                // Answers before the map or change set is consulted, so a refused run cannot be
                // narrowed by a later rule.
                .withDaemonRefusal(refusalFrom(property));
        MapReader.Result map = MapReader.read(mapDir);
        Selector.Decision decision = Selector.decide(map, change, Collections.<String>emptyList());
        // After the decision and apart from it, so recording every rule cannot change what runs.
        try {
            // Unset, not empty: a daemon that refused before it had a change set sets no classes.
            boolean changeKnown = property.apply(AgentContract.CHANGED_CLASSES_PROPERTY) != null;
            decision.attach(Selector.rules(map, change, changeKnown));
        } catch (Throwable failure) {
            decision.attach(Rules.notComputed(AgentContract.RULES_FAILED,
                    Collections.<Selector.Decision.FullRunKind>emptyList()));
        }
        return decision;
    }

    /**
     * One test's verdict, as {@link SelectionFilter} records it. Covers everything but pinned tests
     * and class-granular rounding, which need the descriptor and can only include.
     */
    public static Verdict verdictFor(UnaryOperator<String> property, Selector.Decision decision,
                                     String id, boolean isTest) {
        if (refusalFrom(property) != null) {
            return Verdict.DAEMON_REFUSED;
        }
        if (!Boolean.parseBoolean(orEmpty(property, AgentContract.SELECT_PROPERTY))) {
            return Verdict.SELECTION_NOT_REQUESTED;
        }
        if (decision.isFullRun()) {
            return Verdict.FULL_RUN;
        }
        if (engineRunsEverything(id)) {
            return Verdict.ENGINE_RUNS_EVERYTHING;
        }
        return Verdict.of(reasonOf(decision, id, isTest));
    }

    // `reasonForTest`, not `reasonFor`: Spock reports a data-driven feature as a test, and its own
    // record describes none of the work its iterations did.
    public static Selector.Decision.Reason reasonOf(
            Selector.Decision decision, String id, boolean isTest) {
        return isTest ? decision.reasonForTest(id) : decision.subtreeReason(id);
    }

    public static String refusalFrom(UnaryOperator<String> property) {
        String reason = orEmpty(property, AgentContract.REFUSED_PROPERTY);
        return reason.isEmpty() ? null : reason;
    }

    private static String orEmpty(UnaryOperator<String> property, String name) {
        String value = property.apply(name);
        return value == null ? "" : value;
    }

    private static List<String> splitProperty(UnaryOperator<String> property, String name) {
        String raw = orEmpty(property, name);
        List<String> values = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return values;
    }
}
