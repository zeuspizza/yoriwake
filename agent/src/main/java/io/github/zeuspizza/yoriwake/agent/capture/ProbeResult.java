package io.github.zeuspizza.yoriwake.agent.capture;

/** Outcome of the agent-reachability probe. */
public final class ProbeResult {

    private final String version;
    private final int executionDataBytes;
    private final String diagnostic;
    private int records = -1;
    private String directory;
    private int failures;

    private ProbeResult(String version, int executionDataBytes, String diagnostic) {
        this.version = version;
        this.executionDataBytes = executionDataBytes;
        this.diagnostic = diagnostic;
    }

    /**
     * The agent answered. {@code executionDataBytes} is a size, not the data itself — the probe
     * proves reachability and does not persist coverage; per-test capture does that.
     */
    public static ProbeResult reachable(String version, int executionDataBytes) {
        return new ProbeResult(version, executionDataBytes, null);
    }

    public static ProbeResult failed(String diagnostic) {
        return new ProbeResult(null, 0, diagnostic);
    }

    /** End-of-run summary of a capture session. */
    public static ProbeResult captured(int records, String directory, int failures) {
        ProbeResult result = new ProbeResult(null, 0, null);
        result.records = records;
        result.directory = directory;
        result.failures = failures;
        return result;
    }

    public boolean isReachable() {
        return diagnostic == null && !isCapture();
    }

    public String version() {
        return version;
    }

    public int executionDataBytes() {
        return executionDataBytes;
    }

    public String diagnostic() {
        return diagnostic;
    }

    public boolean isCapture() {
        return records >= 0;
    }

    public int records() {
        return records;
    }

    public int failures() {
        return failures;
    }

    public String render() {
        if (isCapture()) {
            return "CAPTURED records=" + records + " failures=" + failures + " dir=" + directory;
        }
        return isReachable()
                ? "REACHABLE jacoco=" + version + " executionDataBytes=" + executionDataBytes
                : "FAILED " + diagnostic;
    }
}
