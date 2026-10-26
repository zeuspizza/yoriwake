package io.github.zeuspizza.yoriwake.agent.capture;

/**
 * The slice of JaCoCo's {@code IAgent} this agent needs.
 *
 * <p>{@code getExecutionData(reset = true)} returns and clears the data in one call, which is what
 * makes per-test attribution possible.
 */
public interface JacocoAgent {

    String version();

    /** Returns execution data accumulated since the last reset, and clears it. */
    byte[] takeExecutionData();

    /**
     * Another JaCoCo agent that has started in this JVM besides this one, described, or null. Its
     * coverage would be missing from this one's records, so a capture that ends with one is not
     * trusted.
     */
    default String anotherStarted() {
        return null;
    }
}
