package io.github.zeuspizza.yoriwake.agent.select;

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract;
import io.github.zeuspizza.yoriwake.agent.contract.Tsv;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads a selection record ({@link AgentContract#SELECTION_FILE}): its notes, and the tests listed
 * as having run. One reader for the plugin, which checks the stamp, and the test JVM, which leaves
 * the listed tests out, so the two never read one file two ways.
 *
 * <p>A record of another version, one whose rows do not match its row count, or one holding a row
 * that is not a test that passed or failed, is unusable whole: it never lists fewer tests than it
 * says, and never one it does not.
 */
public final class SelectionRecordFile {

    private SelectionRecordFile() {}

    /** A read record, or why it is unusable. */
    public static final class Result {
        private final String failure;
        private final Map<String, String> notes;
        private final Map<String, String> ran;

        private Result(String failure, Map<String, String> notes, Map<String, String> ran) {
            this.failure = failure;
            this.notes = Collections.unmodifiableMap(notes);
            this.ran = Collections.unmodifiableMap(ran);
        }

        /** Why the record is unusable, or null when it is usable. */
        public String failure() {
            return failure;
        }

        /** Every note, by key; empty when unusable. */
        public Map<String, String> notes() {
            return notes;
        }

        /** Each test listed as having run, with its outcome; empty when unusable. */
        public Map<String, String> ran() {
            return ran;
        }

        static Result unusable(String why) {
            return new Result(why, new LinkedHashMap<String, String>(), new LinkedHashMap<String, String>());
        }
    }

    public static Result read(String text) {
        if (text == null) {
            return Result.unusable("there is no record");
        }
        Map<String, String> notes = new LinkedHashMap<>();
        Map<String, String> ran = new LinkedHashMap<>();
        int rows = 0;
        for (String line : text.split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith(AgentContract.NOTE_PREFIX)) {
                String[] fields = Tsv.split(line.substring(AgentContract.NOTE_PREFIX.length()));
                notes.put(fields[0], fields.length > 1 ? fields[1] : "");
                continue;
            }
            if (line.startsWith("#")) {
                continue;
            }
            String[] fields = Tsv.split(line);
            if (fields.length != 2 || fields[0].isEmpty()
                    || !(AgentContract.OUTCOME_SUCCESSFUL.equals(fields[1]) || "FAILED".equals(fields[1]))) {
                return Result.unusable("a row is not a test that passed or failed");
            }
            ran.put(fields[0], fields[1]);
            rows++;
        }
        if (!AgentContract.SELECTION_VERSION.equals(notes.get(AgentContract.VERSION_NOTE))) {
            return Result.unusable("its version is " + notes.get(AgentContract.VERSION_NOTE) + ", not "
                    + AgentContract.SELECTION_VERSION);
        }
        if (!String.valueOf(rows).equals(notes.get(AgentContract.ROWS_NOTE))) {
            return Result.unusable("it holds " + rows + " rows of " + notes.get(AgentContract.ROWS_NOTE));
        }
        return new Result(null, notes, ran);
    }
}
