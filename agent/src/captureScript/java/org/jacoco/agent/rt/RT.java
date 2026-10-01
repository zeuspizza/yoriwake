package org.jacoco.agent.rt;

/**
 * Stands in for JaCoCo's runtime entry point in the scripted capture JVMs, which find the agent by
 * this name exactly as a host's test JVM does.
 */
public final class RT {

    private static final ScriptedAgent AGENT = new ScriptedAgent();

    private RT() {}

    public static ScriptedAgent getAgent() {
        return AGENT;
    }
}
