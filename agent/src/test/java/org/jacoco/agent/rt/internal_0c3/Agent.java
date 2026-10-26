package org.jacoco.agent.rt.internal_0c3;

/** Shaped like a JaCoCo runtime's agent class that starts when a test says so. */
public final class Agent {

    private static final Agent INSTANCE = new Agent();

    public static volatile boolean started;

    public static Agent getInstance() {
        if (!started) {
            throw new IllegalStateException("JaCoCo agent not started.");
        }
        return INSTANCE;
    }

    public String getVersion() {
        return "0c3";
    }

    public byte[] getExecutionData(boolean reset) {
        return new byte[0];
    }
}
