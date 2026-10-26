package org.jacoco.agent.rt.internal_0a1;

/** Shaped like a started JaCoCo agent's class, for the lookup's tests. */
public final class Agent {

    private static final Agent INSTANCE = new Agent();

    public static Agent getInstance() {
        return INSTANCE;
    }

    public String getVersion() {
        return "0a1";
    }

    public byte[] getExecutionData(boolean reset) {
        return new byte[] {1};
    }
}
