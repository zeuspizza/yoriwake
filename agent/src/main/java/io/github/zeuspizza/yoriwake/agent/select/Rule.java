package io.github.zeuspizza.yoriwake.agent.select;

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract;

/**
 * One rule that runs a test. A test can be run by several at once, and the decision record names
 * them all, so the effect of dropping one rule can be measured on recorded runs.
 */
public enum Rule {
    REACHES_CHANGE(AgentContract.RULE_REACHES_CHANGE),
    OWN_CLASS_CHANGED(AgentContract.RULE_OWN_CLASS_CHANGED),
    CHANGED_BYTES(AgentContract.RULE_CHANGED_BYTES),
    CLASS_SETUP(AgentContract.RULE_CLASS_SETUP),
    SHARES_JVM_CHANGED_CLASS(AgentContract.RULE_SHARES_JVM_CHANGED_CLASS),
    SHARES_JVM_CHANGED_BYTES(AgentContract.RULE_SHARES_JVM_CHANGED_BYTES),
    SHARES_JVM_UNOBSERVED(AgentContract.RULE_SHARES_JVM_UNOBSERVED),
    SHARES_JVM_UNPOSITIONED(AgentContract.RULE_SHARES_JVM_UNPOSITIONED),
    NOT_KNOWN_TO_PASS(AgentContract.RULE_NOT_KNOWN_TO_PASS),
    NOT_IN_MAP(AgentContract.RULE_NOT_IN_MAP),
    ALWAYS_RUN(AgentContract.RULE_ALWAYS_RUN),
    ENGINE_RUNS_EVERYTHING(AgentContract.RULE_ENGINE_RUNS_EVERYTHING),
    CLASS_GRANULARITY(AgentContract.RULE_CLASS_GRANULARITY);

    private final String token;

    Rule(String token) {
        this.token = token;
    }

    /** The spelling in a rule line of {@code decisions.tsv}. */
    public String token() {
        return token;
    }
}
