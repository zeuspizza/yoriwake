package org.jacoco.agent.rt.internal_0b2;

/** Shaped like a JaCoCo runtime's agent class that never started, for the lookup's tests. */
public final class Agent {

    public static Agent getInstance() {
        throw new IllegalStateException("JaCoCo agent not started.");
    }

    public String getVersion() {
        return "0b2";
    }

    public byte[] getExecutionData(boolean reset) {
        return new byte[0];
    }
}
