package io.github.zeuspizza.yoriwake.agent.capture;

/** Reaches the capture package's package-private surface from tests in other packages. */
public final class CaptureAccess {

    private CaptureAccess() {}

    public static final String PROBE_FILE_PROPERTY = ProbeReporter.ToFile.TARGET_PROPERTY;

    public static AgentLookup.Unavailable notOnClasspath(Throwable cause) {
        return AgentLookup.Unavailable.notOnClasspath(cause);
    }

    public static AgentLookup.Unavailable notAttached(Throwable cause) {
        return AgentLookup.Unavailable.notAttached(cause);
    }
}
