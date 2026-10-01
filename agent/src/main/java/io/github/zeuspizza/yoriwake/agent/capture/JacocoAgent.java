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
}
