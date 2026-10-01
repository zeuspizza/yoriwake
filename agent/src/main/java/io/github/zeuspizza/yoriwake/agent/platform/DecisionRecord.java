package io.github.zeuspizza.yoriwake.agent.platform;

import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.DECISIONS_FILE;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.DECISIONS_PART_SUFFIX;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.DECISIONS_VERSION;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.FORCING_KINDS_NOTE;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.NOTE_PREFIX;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.ROWS_NOTE;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.RULES_LINE_PREFIX;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.RULES_NOTE;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.RULE_UNKNOWN;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.VERSION_NOTE;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.WRITER_NOTE;

import io.github.zeuspizza.yoriwake.agent.contract.Tsv;
import io.github.zeuspizza.yoriwake.agent.host.AttachedLoader;
import io.github.zeuspizza.yoriwake.agent.select.Rules;
import io.github.zeuspizza.yoriwake.agent.select.Verdict;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the selector decided about every test it saw, and why, as a file a script can read. JUnit
 * puts a filter's reason in no artifact, and result XML shows only what ran.
 *
 * <p>TSV because the agent carries no dependencies. An output only, never read back, so it cannot
 * become a stale cache. Write failures are swallowed: this must never fail a host's build.
 */
public final class DecisionRecord {

    // Only the attached copy writes. A copy another class loader defined counts its writers from 1
    // again, so it would stage and publish under the attached copy's names: writes collided and a
    // nested launcher's record could stand as the task's. See AttachedLoader.
    private static final boolean ATTACHED = AttachedLoader.attached(DecisionRecord.class);

    // Per instance: each Platform discovery request gets its own record, so names must differ.
    private static final java.util.concurrent.atomic.AtomicLong WRITERS =
            new java.util.concurrent.atomic.AtomicLong();

    private final long writerId = WRITERS.incrementAndGet();

    // A record can be written more than once, and two writes must not share a staging path.
    private final java.util.concurrent.atomic.AtomicLong writes =
            new java.util.concurrent.atomic.AtomicLong();

    private static final String HEADER = "# test\tverdict\treason";

    // A filter is not promised a single thread, and a lost row would be invisible.
    private final List<Row> rows = Collections.synchronizedList(new ArrayList<Row>());

    /** Kept apart until written, because a row's rule line is computed from its verdict at exit. */
    private static final class Row {
        final String testId;
        final Verdict verdict;

        Row(String testId, Verdict verdict) {
            this.testId = testId;
            this.verdict = verdict;
        }
    }

    // Facts about the whole run, such as why it ran everything, which no per-test row can carry.
    private final List<String> notes = Collections.synchronizedList(new ArrayList<String>());

    void add(String testId, Verdict verdict) {
        rows.add(new Row(testId, verdict));
    }

    /**
     * Records one whole-run fact. A null or empty value is dropped rather than written as blank,
     * because absent and empty must not share a spelling.
     */
    void note(String key, String value) {
        if (key == null || key.isEmpty() || value == null || value.isEmpty()) {
            return;
        }
        notes.add(NOTE_PREFIX + Tsv.join(key, value));
    }

    int size() {
        return rows.size();
    }

    /**
     * Replaces any previous run's file, and writes one even when nothing was decided, so a stale
     * file from an earlier run is never left in place.
     */
    void writeTo(File dir) {
        writeTo(dir, null);
    }

    /**
     * As {@link #writeTo(File)}, with every rule behind each row when {@code rules} knows them: its
     * notes, then one rule line per row after the rows.
     */
    // Synchronized so two writes of one record never interleave.
    synchronized void writeTo(File dir, Rules rules) {
        if (dir == null || !ATTACHED) {
            return;
        }
        // One snapshot for both the row count and the rows, or a late row makes the declared count
        // mismatch and the reader discards the file as truncated.
        List<Row> snapshot;
        synchronized (rows) {
            snapshot = new ArrayList<Row>(rows);
        }
        StringBuilder out = new StringBuilder(HEADER).append('\n');
        // First, so a file without it is known to come from an older jar.
        out.append(NOTE_PREFIX).append(VERSION_NOTE).append('\t').append(DECISIONS_VERSION).append('\n');
        // The row count, before the rows: a kill mid-write otherwise leaves a plausible prefix.
        out.append(NOTE_PREFIX).append(ROWS_NOTE).append('\t').append(snapshot.size()).append('\n');
        // The file is one discovery request's answer, which may cover fewer tests than the task.
        out.append(NOTE_PREFIX).append(WRITER_NOTE).append('\t')
                .append(writerId).append(" of ").append(WRITERS.get()).append('\n');
        synchronized (notes) {
            for (String note : notes) {
                out.append(note).append('\n');
            }
        }
        if (rules != null) {
            out.append(NOTE_PREFIX).append(Tsv.join(RULES_NOTE, rules.basis())).append('\n');
            out.append(NOTE_PREFIX).append(Tsv.join(FORCING_KINDS_NOTE, rules.forcingKinds())).append('\n');
        }
        for (Row row : snapshot) {
            out.append(Tsv.join(row.testId, row.verdict.inclusionToken(), row.verdict.reasonToken())).append('\n');
        }
        if (rules != null && rules.computed()) {
            for (Row row : snapshot) {
                out.append(RULES_LINE_PREFIX).append(Tsv.join(row.testId, tokensFor(rules, row))).append('\n');
            }
        }
        long pid = ProcessHandle.current().pid();
        long write = writes.incrementAndGet();
        publish(dir, DECISIONS_FILE, out.toString(), pid, write);
        // One part per writer, not per write: the latest write of a record wins.
        publish(dir, DECISIONS_FILE + "." + pid + "-" + writerId + DECISIONS_PART_SUFFIX, out.toString(), pid, write);
    }

    /** Never throws: a rule that cannot be computed for one row costs that row's rules, not the record. */
    private static String tokensFor(Rules rules, Row row) {
        try {
            return rules.tokensFor(row.testId, row.verdict);
        } catch (Throwable failed) {
            return RULE_UNKNOWN;
        }
    }

    /** Writes {@code content} to {@code name} in {@code dir} by staging it and moving it into place. */
    private void publish(File dir, String name, String content, long pid, long write) {
        try {
            Files.createDirectories(dir.toPath());
            // Staged and moved, so a reader never sees a prefix.
            File target = new File(dir, name);
            // Staging named per JVM, writer and write: forks share the directory, and one JVM holds
            // a record per discovery request whose hooks run together. The final name is still
            // last-writer-wins, but never a blend of two writers.
            File staging = new File(
                    dir, name + "." + pid + "." + writerId + "." + write + ".writing");
            try (Writer writer = Files.newBufferedWriter(
                    staging.toPath(), StandardCharsets.UTF_8)) {
                writer.write(content);
            }
            try {
                Files.move(staging.toPath(), target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException notAtomic) {
                // Same directory, so unexpected; a non-atomic move still beats writing in place.
                // The message says which move failed, since the two have different causes.
                try {
                    Files.move(staging.toPath(), target.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException fallbackFailed) {
                    throw new IOException("the non-atomic fallback move failed: " + fallbackFailed,
                            fallbackFailed);
                }
            }
        } catch (IOException | RuntimeException swallowed) {
            // One line, no stack trace: this runs at shutdown in someone else's build.
            System.out.println("[yoriwake] could not write " + name + " (writer " + writerId
                    + ", write " + write + "): " + swallowed);
        }
    }
}
