package io.github.zeuspizza.yoriwake.agent.capture;

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract;
import io.github.zeuspizza.yoriwake.agent.contract.Tsv;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Persists one JaCoCo execution-data blob per test, plus an index describing them.
 *
 * <p>Layout, under the configured output directory:
 *
 * <pre>
 *   worker-&lt;id&gt;/index.tsv     sequence, durationNanos, byteCount, outcome, testId
 *   worker-&lt;id&gt;/000001.exec   raw bytes from IAgent.getExecutionData(true)
 *   worker-&lt;id&gt;/touches.tsv   sequence, kind, value: first loads and reads, then #complete
 * </pre>
 *
 * <p>One directory per worker, because Gradle forks parallel test JVMs and each has its own agent.
 * The bytes stay undecoded: decoding needs {@code org.jacoco.core}, and this jar runs inside the
 * host's test JVM, where every added dependency is a liability. Test ids live in the index, not in
 * file names, because they contain characters no filesystem agrees on.
 */
final class ExecRecordWriter implements Closeable {

    /** Buffered bytes before a flush, for runs with unusually large records. */
    private static final int FLUSH_THRESHOLD_BYTES = 64 * 1024 * 1024;

    /**
     * Records buffered before a flush, whatever their size, so a killed JVM loses seconds of work
     * rather than the whole capture. The flush runs inside the metered window, so its cost counts
     * as overhead.
     */
    private static final int FLUSH_THRESHOLD_RECORDS = 500;

    private final File workerDir;
    private final BufferedWriter index;
    private final BufferedWriter touches;
    private final java.util.List<String> touchLines = new java.util.ArrayList<>();
    private boolean touchesComplete;
    /** A touch line that did not reach disk: the touches can then never be marked complete. */
    private boolean touchesBroken;
    private final java.util.List<Pending> buffer = new java.util.ArrayList<>();
    private long bufferedBytes;
    private int sequence = 0;

    ExecRecordWriter(File outputDir, String workerId) throws IOException {
        this.workerDir = new File(outputDir, AgentContract.WORKER_DIR_PREFIX + workerId);
        if (!workerDir.mkdirs() && !workerDir.isDirectory()) {
            throw new IOException("Could not create " + workerDir);
        }
        File indexFile = new File(workerDir, AgentContract.INDEX_FILE);
        // The sequence restarts at 1 per writer, so reusing a directory would pair old index rows
        // with new coverage: a corrupted map that still parses.
        if (indexFile.exists() && indexFile.length() > 0) {
            throw new IOException(
                    "Capture directory already contains records: " + indexFile
                            + ". Point the capture at a fresh directory rather than reusing one.");
        }
        this.index = new BufferedWriter(
                new FileWriter(indexFile, StandardCharsets.UTF_8, false));
        this.touches = new BufferedWriter(
                new FileWriter(new File(workerDir, AgentContract.TOUCHES_FILE), StandardCharsets.UTF_8, false));
        writeSchemaVersion();
    }

    /**
     * Buffers one record and returns its sequence number.
     *
     * <p>Records are written in batches, because a file open, write and close per test costs far
     * more than the JaCoCo call itself. The batch is bounded by bytes as well as count, since
     * record size varies widely with how much the build instruments.
     */
    synchronized int write(String testId, long durationNanos, String outcome, byte[] executionData)
            throws IOException {
        int id = ++sequence;
        buffer.add(new Pending(id, testId, durationNanos, outcome, executionData));
        bufferedBytes += executionData.length;
        if (buffer.size() >= FLUSH_THRESHOLD_RECORDS || bufferedBytes >= FLUSH_THRESHOLD_BYTES) {
            flush();
        }
        return id;
    }

    /** Files first touches under the record that will be written next. */
    synchronized void touched(java.util.List<String[]> rows) {
        String sequenceOfNext = Integer.toString(sequence + 1);
        for (String[] row : rows) {
            touchLines.add(Tsv.join(sequenceOfNext, row[0], row[1]));
        }
    }

    /** Marks the touches complete; the end marker is written when the writer closes. */
    synchronized void completeTouches() {
        touchesComplete = true;
    }

    /** Writes everything buffered so far. */
    synchronized void flush() throws IOException {
        try {
            try {
                for (String line : touchLines) {
                    touches.write(line);
                    touches.newLine();
                }
                touchLines.clear();
                touches.flush();
            } catch (IOException e) {
                touchesBroken = true;
                throw e;
            }
            writeBuffered();
        } finally {
            // Rows written before a failure are already gone from the buffer, so they must reach
            // disk here or be lost.
            index.flush();
        }
    }

    private void writeBuffered() throws IOException {
        java.util.Iterator<Pending> pendings = buffer.iterator();
        while (pendings.hasNext()) {
            Pending pending = pendings.next();
            File target = new File(workerDir, String.format(AgentContract.EXEC_FILE_FORMAT, pending.sequence));
            try (OutputStream out = new FileOutputStream(target)) {
                out.write(pending.executionData);
            }
            index.write(Tsv.join(Integer.toString(pending.sequence), Long.toString(pending.durationNanos),
                    Integer.toString(pending.executionData.length), pending.outcome, pending.testId));
            index.newLine();

            // Removed only after both writes succeed, so a retry resumes here instead of
            // duplicating index rows already on disk.
            pendings.remove();
            bufferedBytes -= pending.executionData.length;
        }
    }

    /**
     * A record waiting to be written.
     *
     * <p>A plain class rather than a {@code record}: this jar runs in the host's test JVM, so its
     * bytecode floor is the oldest JVM a host tests on, and a record would raise it to 16.
     */
    private static final class Pending {
        final int sequence;
        final String testId;
        final long durationNanos;
        final String outcome;
        final byte[] executionData;

        Pending(int sequence, String testId, long durationNanos, String outcome, byte[] executionData) {
            this.sequence = sequence;
            this.testId = testId;
            this.durationNanos = durationNanos;
            this.outcome = outcome;
            this.executionData = executionData;
        }

        int sequence() { return sequence; }

        String testId() { return testId; }

        long durationNanos() { return durationNanos; }

        String outcome() { return outcome; }

        byte[] executionData() { return executionData; }
    }

    /**
     * Stamps the directory with the raw format's version, before any record.
     *
     * <p>A reader that finds an unrecognised version can say "rebuild it" instead of failing on a
     * malformed line.
     */
    private void writeSchemaVersion() throws IOException {
        File version = new File(workerDir, AgentContract.RAW_SCHEMA_VERSION_FILE);
        try (BufferedWriter out = new BufferedWriter(
                new FileWriter(version, StandardCharsets.UTF_8, false))) {
            out.write(Integer.toString(AgentContract.RAW_SCHEMA_VERSION));
            out.newLine();
        }
    }

    /**
     * Writes the run's overhead line beside the records.
     *
     * <p>Its own file rather than a line in the index, because the index is one row per test and a
     * summary row in it would have to be filtered out by every reader.
     */
    void writeOverhead(String line) throws IOException {
        flush();
        try (BufferedWriter out = new BufferedWriter(
                new FileWriter(new File(workerDir, AgentContract.OVERHEAD_FILE), StandardCharsets.UTF_8, false))) {
            out.write(line);
            out.newLine();
        }
    }

    /** See {@link AgentContract#PLAN_COMPLETE_FILE}. */
    void markPlanComplete() throws IOException {
        File marker = new File(workerDir, AgentContract.PLAN_COMPLETE_FILE);
        if (!marker.createNewFile() && !marker.isFile()) {
            throw new IOException("Could not create " + marker);
        }
    }

    int recordCount() {
        return sequence;
    }

    File directory() {
        return workerDir;
    }

    @Override
    public void close() throws IOException {
        // Closed even when the final flush fails; on Windows a leaked handle also blocks deleting
        // the directory.
        try {
            flush();
            if (touchesComplete && !touchesBroken) {
                touches.write(AgentContract.TOUCHES_COMPLETE);
                touches.newLine();
            }
        } finally {
            try {
                touches.close();
            } finally {
                index.close();
            }
        }
    }
}
