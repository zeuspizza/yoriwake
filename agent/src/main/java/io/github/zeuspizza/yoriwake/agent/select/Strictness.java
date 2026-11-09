package io.github.zeuspizza.yoriwake.agent.select;

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract;
import java.util.EnumSet;
import java.util.Locale;

/**
 * How strictly a selecting run applies the rules: {@link #SAFE} applies every one;
 * {@link #OPTIMISTIC} drops the two shared-JVM windows the change itself opens, and runs fewer tests
 * than the safe rule because the invocation asked for exactly that.
 */
public enum Strictness {
    SAFE(EnumSet.noneOf(Rule.class)),
    OPTIMISTIC(EnumSet.of(Rule.SHARES_JVM_CHANGED_CLASS, Rule.SHARES_JVM_CHANGED_BYTES));

    private final EnumSet<Rule> loosened;

    Strictness(EnumSet<Rule> loosened) {
        this.loosened = loosened;
    }

    /**
     * The rules this strictness does not apply. Changing the set changes which tests run, so it is
     * measured before it changes.
     */
    public EnumSet<Rule> loosened() {
        return loosened.isEmpty() ? EnumSet.noneOf(Rule.class) : EnumSet.copyOf(loosened);
    }

    /** The spelling of {@link AgentContract#MODE_PROPERTY} and the {@link AgentContract#MODE_NOTE}. */
    public String token() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * The strictness a {@link AgentContract#MODE_PROPERTY} value asks for. Anything but
     * {@code optimistic}, missing included, is {@link #SAFE}: doubt runs the test.
     */
    public static Strictness of(String value) {
        return value != null && OPTIMISTIC.token().equalsIgnoreCase(value.trim()) ? OPTIMISTIC : SAFE;
    }
}
