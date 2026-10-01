package io.github.zeuspizza.yoriwake.agent.select;

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every rule that would run each test under one change, and every full-run kind that held, for the
 * decision record. Recording only: {@link Selector#decide} never reads it, so what runs cannot
 * depend on it. Computed beneath a full run too, so a record says what coverage alone would have
 * selected.
 */
public final class Rules {

    private final String basis;
    private final List<Selector.Decision.FullRunKind> forcing;
    private final Map<String, EnumSet<Rule>> byTest;
    private final Set<String> knownTestIds;
    // Built on first lookup: the rules and the knownness of every id that has recorded children.
    private Map<String, EnumSet<Rule>> beneath;
    private Set<String> knownParents;

    Rules(String basis, List<Selector.Decision.FullRunKind> forcing, Map<String, EnumSet<Rule>> byTest,
            Set<String> knownTestIds) {
        this.basis = basis;
        this.forcing = Collections.unmodifiableList(new ArrayList<>(forcing));
        this.byTest = byTest;
        this.knownTestIds = knownTestIds;
    }

    static Rules notComputed(String basis, List<Selector.Decision.FullRunKind> forcing) {
        return new Rules(basis, forcing, null, Collections.<String>emptySet());
    }

    /** One of the {@code AgentContract.RULES_*} values. */
    public String basis() {
        return basis;
    }

    /** Whether each test's rules are known, so rule lines are worth writing. */
    public boolean computed() {
        return byTest != null;
    }

    /** {@code AgentContract.FORCING_KINDS_NOTE}'s value. */
    public String forcingKinds() {
        if (forcing.isEmpty()) {
            return AgentContract.RULE_NONE;
        }
        List<String> tokens = new ArrayList<>();
        for (Selector.Decision.FullRunKind kind : forcing) {
            tokens.add(kind.token());
        }
        return String.join(",", tokens);
    }

    /**
     * Every rule that runs this test, including those its recorded children carry, as they would
     * run it through the subtree fallback. The filter-level rules come from the row's verdict.
     */
    public synchronized Set<Rule> rulesFor(String id, Verdict verdict) {
        EnumSet<Rule> found = EnumSet.noneOf(Rule.class);
        if (!computed()) {
            return found;
        }
        indexChildren();
        EnumSet<Rule> own = byTest.get(id);
        if (own != null) {
            found.addAll(own);
        }
        EnumSet<Rule> children = beneath.get(id);
        if (children != null) {
            found.addAll(children);
        }
        if (!knownTestIds.contains(id) && !knownParents.contains(id)) {
            found.add(Rule.NOT_IN_MAP);
        }
        if (FilterRule.engineRunsEverything(id)) {
            found.add(Rule.ENGINE_RUNS_EVERYTHING);
        }
        if (verdict == Verdict.ALWAYS_RUN) {
            found.add(Rule.ALWAYS_RUN);
        }
        if (verdict == Verdict.CLASS_GRANULARITY) {
            found.add(Rule.CLASS_GRANULARITY);
        }
        return found;
    }

    /** A rule line's value: the rules' tokens in declaration order, or {@code none}. */
    public String tokensFor(String id, Verdict verdict) {
        Set<Rule> rules = rulesFor(id, verdict);
        if (rules.isEmpty()) {
            return AgentContract.RULE_NONE;
        }
        List<String> tokens = new ArrayList<>();
        for (Rule rule : rules) {
            tokens.add(rule.token());
        }
        return String.join(",", tokens);
    }

    /** Every id that is a parent of a recorded one, as the subtree rule matches it: a prefix ending at a '/'. */
    private void indexChildren() {
        if (beneath != null) {
            return;
        }
        Map<String, EnumSet<Rule>> index = new HashMap<>();
        Set<String> parents = new HashSet<>();
        for (String known : knownTestIds) {
            EnumSet<Rule> rules = byTest.get(known);
            for (int slash = known.indexOf('/'); slash >= 0; slash = known.indexOf('/', slash + 1)) {
                String parent = known.substring(0, slash);
                parents.add(parent);
                if (rules != null) {
                    index.computeIfAbsent(parent, key -> EnumSet.noneOf(Rule.class)).addAll(rules);
                }
            }
        }
        knownParents = parents;
        beneath = index;
    }
}
