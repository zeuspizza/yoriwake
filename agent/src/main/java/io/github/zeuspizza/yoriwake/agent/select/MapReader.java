package io.github.zeuspizza.yoriwake.agent.select;

import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.COVERAGE_FILE;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.EFFECTIVE_SCOPE_FILE;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.FIRST_TOUCH_FILE;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.LOADED_FILE;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.LOADED_PROVENANCE_FILE;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.LOADED_SCOPE_FILE;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.MAP_SCHEMA_VERSION;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.MAP_SCHEMA_VERSION_FILE;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.NAMED_TOUCH_FILE;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.POSITIONS_FILE;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.RAW_DIR;
import static io.github.zeuspizza.yoriwake.agent.contract.AgentContract.SCOPE_FILE;

import io.github.zeuspizza.yoriwake.agent.contract.Tsv;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;

/**
 * Reads the decoded coverage map from inside the test JVM.
 *
 * <p>Plain text because the host's test JVM has no {@code org.jacoco.core}; the plugin decodes on
 * the daemon side.
 *
 * <p>Every failure is reported as unusable, never as an empty map: an empty map would mean "no test
 * covers anything" and select nothing, while unusable means "unknown" and runs everything.
 */
public final class MapReader {

    private MapReader() {}

    /** One test's entry in the map. */
    public static final class Entry {
        private final String testId;
        private final String outcome;
        private final long durationNanos;
        private final Set<String> coveredClasses;

        Entry(String testId, String outcome, long durationNanos, Set<String> coveredClasses) {
            this.testId = testId;
            this.outcome = outcome;
            this.durationNanos = durationNanos;
            this.coveredClasses = coveredClasses;
        }

        public String testId() {
            return testId;
        }

        public String outcome() {
            return outcome;
        }

        public long durationNanos() {
            return durationNanos;
        }

        public Set<String> coveredClasses() {
            return coveredClasses;
        }
    }

    /** Where a record ran: an opaque id for one test JVM, and the record's sequence in it. */
    public static final class Position {
        private final String jvm;
        private final int sequence;

        Position(String jvm, int sequence) {
            this.jvm = jvm;
            this.sequence = sequence;
        }

        public String jvm() {
            return jvm;
        }

        public int sequence() {
            return sequence;
        }
    }

    /** The first sequence at or before which a JVM loaded, read or executed a class. */
    public static final class FirstTouch {
        private final String jvm;
        private final int sequence;
        private final String className;

        FirstTouch(String jvm, int sequence, String className) {
            this.jvm = jvm;
            this.sequence = sequence;
            this.className = className;
        }

        public String jvm() {
            return jvm;
        }

        public int sequence() {
            return sequence;
        }

        /** A binary class name, or {@code *} for every class. */
        public String className() {
            return className;
        }
    }

    /** Whether a map records the instrumentation scope its records were captured under. */
    public enum ScopeProvenance {
        /**
         * The map names every scope its records were captured under.
         *
         * <p>Deliberately not compared with the live scope: the question is whether a probe would
         * have fired when the records were captured, and adding a package would otherwise refuse.
         */
        RECORDED,
        /** The map names no scope: it predates the file, or a run could not read its own. */
        UNKNOWN,
    }

    /** Either a usable map or the reason there is not one. */
    public static final class Result {

        /**
         * Why a map is unusable, as a value to branch on. {@link #unusableReason()} is prose for a
         * person and is not stable.
         */
        public enum Reason {
            /** No map directory, or no records in it yet. A first run on a fresh checkout. */
            NO_MAP,
            /** Records without the version marker a completed capture writes: a capture died. */
            CAPTURE_INCOMPLETE,
            /** Written before this format was versioned at all. */
            SCHEMA_TOO_OLD,
            /** A version marker naming a format this build does not read. */
            SCHEMA_MISMATCH,
            /** Present but not readable: an I/O error, or an entry that does not parse. */
            UNREADABLE,
        }

        private final List<Entry> entries;
        private List<String> instrumentationScope = Collections.emptyList();
        private Set<String> loadedClasses;
        private List<String> loadedScope = Collections.emptyList();
        private String effectiveScope;
        private Map<String, List<Position>> positions = Collections.emptyMap();
        private List<FirstTouch> firstTouches = Collections.emptyList();
        private List<FirstTouch> namedTouches = Collections.emptyList();
        private final String unusableReason;
        private final Reason reason;

        private Result(List<Entry> entries, String unusableReason, Reason reason) {
            this.entries = entries;
            this.unusableReason = unusableReason;
            this.reason = reason;
        }

        static Result usable(List<Entry> entries) {
            return new Result(Collections.unmodifiableList(entries), null, null);
        }

        static Result unusable(Reason reason, String message) {
            return new Result(Collections.emptyList(), message, reason);
        }

        public boolean isUsable() {
            return unusableReason == null;
        }

        public List<Entry> entries() {
            return entries;
        }

        /** Why no map could be read. Never null when {@link #isUsable()} is false. */
        public String unusableReason() {
            return unusableReason;
        }

        /**
         * The same answer as a value a caller can switch on, or null when the map is usable.
         *
         * @see Reason
         */
        public Reason reason() {
            return reason;
        }

        /**
         * Package prefixes the capture was watching, or empty when the map does not record them.
         * Empty means unknown, never "watching nothing".
         */
        public List<String> instrumentationScope() {
            return instrumentationScope;
        }

        /**
         * Classes the test task loaded, or null when unknown: none recorded, recorded by a
         * selecting run (a fraction of the suite), or unreadable.
         *
         * <p>This is what makes absence meaningful: a class the task never loaded cannot have been
         * executed by any of its tests.
         */
        public Set<String> loadedClasses() {
            return loadedClasses;
        }

        /**
         * Whether the union can speak about this class at all. The recorder filters to package
         * prefixes, so outside them absence proves nothing. False when no scope was recorded.
         */
        public boolean unionSpeaksFor(String className) {
            for (String prefix : loadedScope) {
                if (className.equals(prefix) || className.startsWith(prefix + ".")) {
                    return true;
                }
            }
            return false;
        }

        /** Where a test ran, possibly more than once; empty when the map does not say. */
        public List<Position> positionsOf(String testId) {
            List<Position> found = positions.get(testId);
            return found == null ? Collections.<Position>emptyList() : found;
        }

        /** Every JVM's first touches, in no particular order. */
        public List<FirstTouch> firstTouches() {
            return firstTouches;
        }

        /** Every JVM's first lookups and reads after discovery, and executions by another class. */
        public List<FirstTouch> namedTouches() {
            return namedTouches;
        }

        /** Whether the map records the scope its records were captured under. */
        public ScopeProvenance scopeProvenance() {
            return effectiveScope == null ? ScopeProvenance.UNKNOWN : ScopeProvenance.RECORDED;
        }

        /** The recorded scope as written, or null when the map does not name one. */
        public String effectiveScope() {
            return effectiveScope;
        }
    }

    /** The same answer without parsing the potentially large coverage file. */
    public static ScopeProvenance scopeProvenanceOf(File mapDir) {
        if (mapDir == null) {
            return ScopeProvenance.UNKNOWN;
        }
        return readEffectiveScope(mapDir) == null ? ScopeProvenance.UNKNOWN : ScopeProvenance.RECORDED;
    }

    /** The recorded scopes verbatim, or null when the map names none. */
    public static String recordedScopeOf(File mapDir) {
        return mapDir == null ? null : readEffectiveScope(mapDir);
    }

    /**
     * Reads the map at this directory. The version is checked before the content, so a map in
     * another format is reported as such rather than as a parse error.
     */
    public static Result read(File mapDir) {
        if (mapDir == null) {
            return Result.unusable(Result.Reason.NO_MAP, "no map directory was configured");
        }
        File coverage = new File(mapDir, COVERAGE_FILE);
        if (!coverage.isFile()) {
            return Result.unusable(Result.Reason.NO_MAP, "no map at " + mapDir + "; this run will build one");
        }

        Result versionProblem = checkVersion(mapDir);
        if (versionProblem != null) {
            return versionProblem;
        }
        List<String> scope = readScope(new File(mapDir, SCOPE_FILE));
        Set<String> loaded = readLoaded(mapDir);
        List<String> loadedScope = loaded == null
                ? Collections.<String>emptyList()
                : readScope(new File(mapDir, LOADED_SCOPE_FILE));

        List<Entry> entries = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(coverage.toPath(), StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isEmpty()) {
                    continue;
                }
                Entry entry = parse(line);
                if (entry == null) {
                    return Result.unusable(
                            Result.Reason.UNREADABLE,
                            "malformed entry at " + coverage + " line " + lineNumber
                                    + "; rebuild the map by running the full suite");
                }
                entries.add(entry);
            }
        } catch (IOException e) {
            return Result.unusable(Result.Reason.UNREADABLE, "could not read " + coverage + ": " + e);
        }

        if (entries.isEmpty()) {
            return Result.unusable(Result.Reason.NO_MAP, "the map at " + mapDir + " is empty");
        }
        Map<String, List<Position>> positions = new HashMap<>();
        List<FirstTouch> firstTouches = new ArrayList<>();
        List<FirstTouch> namedTouches = new ArrayList<>();
        // Without them nothing says which tests ran after a class arrived, so nothing may narrow.
        for (String name : new String[] {POSITIONS_FILE, FIRST_TOUCH_FILE, NAMED_TOUCH_FILE}) {
            File file = new File(mapDir, name);
            if (!file.isFile()) {
                return Result.unusable(Result.Reason.UNREADABLE, "the map at " + mapDir + " has no "
                        + name + ", which says what each test JVM loaded before each test; rebuild it"
                        + " by running the full suite");
            }
            try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                String line;
                int lineNumber = 0;
                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    if (line.isEmpty()) {
                        continue;
                    }
                    String[] parts = Tsv.split(line);
                    Integer sequence = parts.length == 3 ? parseSequence(parts[1]) : null;
                    if (sequence == null) {
                        return Result.unusable(Result.Reason.UNREADABLE, "malformed entry at " + file
                                + " line " + lineNumber + "; rebuild the map by running the full suite");
                    }
                    if (POSITIONS_FILE.equals(name)) {
                        positions.computeIfAbsent(parts[2], id -> new ArrayList<>())
                                .add(new Position(parts[0], sequence));
                    } else {
                        (FIRST_TOUCH_FILE.equals(name) ? firstTouches : namedTouches)
                                .add(new FirstTouch(parts[0], sequence, parts[2]));
                    }
                }
            } catch (IOException e) {
                return Result.unusable(Result.Reason.UNREADABLE, "could not read " + file + ": " + e);
            }
        }
        Result result = Result.usable(entries);
        result.positions = positions;
        result.firstTouches = firstTouches;
        result.namedTouches = namedTouches;
        result.instrumentationScope = scope;
        result.loadedClasses = loaded;
        result.loadedScope = loadedScope;
        result.effectiveScope = readEffectiveScope(mapDir);
        return result;
    }

    /** The recorded instrumentation scope, or empty (unknown) on any failure. */
    private static List<String> readScope(File scopeFile) {
        if (!scopeFile.isFile()) {
            return Collections.emptyList();
        }
        try {
            List<String> prefixes = new ArrayList<>();
            for (String line : Files.readAllLines(scopeFile.toPath(), StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    prefixes.add(trimmed);
                }
            }
            return Collections.unmodifiableList(prefixes);
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    /**
     * The recorded scope, trimmed because the two compared sides are written by different code
     * paths. Null (unknown) when unreadable or empty.
     */
    private static String readEffectiveScope(File mapDir) {
        File file = new File(mapDir, EFFECTIVE_SCOPE_FILE);
        if (!file.isFile()) {
            return null;
        }
        try {
            String raw = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
            return raw.isEmpty() ? null : raw;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * The loaded union, or null when it cannot be trusted. A selecting run's set, or a partially
     * read one, is smaller than the truth, and a smaller set here means more skipping.
     */
    private static Set<String> readLoaded(File mapDir) {
        File provenance = new File(mapDir, LOADED_PROVENANCE_FILE);
        File loaded = new File(mapDir, LOADED_FILE);
        if (!provenance.isFile() || !loaded.isFile()) {
            return null;
        }
        try {
            String how = new String(Files.readAllBytes(provenance.toPath()), StandardCharsets.UTF_8).trim();
            if (!"full".equals(how)) {
                return null;
            }
            Set<String> names = new LinkedHashSet<>();
            for (String line : Files.readAllLines(loaded.toPath(), StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    names.add(trimmed);
                }
            }
            return names.isEmpty() ? null : Collections.unmodifiableSet(names);
        } catch (IOException e) {
            return null;
        }
    }

    private static Result checkVersion(File mapDir) {
        File version = new File(mapDir, MAP_SCHEMA_VERSION_FILE);
        if (!version.isFile()) {
            // A capture writes its metadata last, so records without a version marker are a
            // capture that died partway, not an old map.
            if (new File(mapDir, COVERAGE_FILE).isFile() || new File(mapDir, RAW_DIR).isDirectory()) {
                return Result.unusable(
                        Result.Reason.CAPTURE_INCOMPLETE,
                        "the map at " + mapDir + " was never finished: it holds records but no "
                                + MAP_SCHEMA_VERSION_FILE + ", which only a completed capture writes. A"
                                + " capture failed partway -- look for an earlier '[yoriwake] could not"
                                + " update the map' warning for the cause. Nothing will narrow"
                                + " until a capture completes; delete the directory and capture"
                                + " again.");
            }
            return Result.unusable(
                    Result.Reason.SCHEMA_TOO_OLD,
                    "the map at " + mapDir + " has no " + MAP_SCHEMA_VERSION_FILE
                            + ", so it predates format versioning; rebuild it");
        }
        String raw;
        try {
            raw = new String(Files.readAllBytes(version.toPath()), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return Result.unusable(Result.Reason.UNREADABLE, "could not read " + version + ": " + e);
        }
        int found;
        try {
            found = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return Result.unusable(Result.Reason.SCHEMA_MISMATCH, "unreadable schema version '" + raw + "' at " + version);
        }
        if (found != MAP_SCHEMA_VERSION) {
            return Result.unusable(
                    Result.Reason.SCHEMA_MISMATCH,
                    "the map at " + mapDir + " is schema version " + found + " but this build reads "
                            + MAP_SCHEMA_VERSION + "; rebuild it by running the full suite");
        }
        return null;
    }

    private static Integer parseSequence(String raw) {
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** outcome, durationNanos, comma-separated classes, testId. Null when the line is malformed. */
    private static Entry parse(String line) {
        String[] parts = Tsv.split(line);
        if (parts.length != 4) {
            return null;
        }
        long duration;
        try {
            duration = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        Set<String> classes = parts[2].isEmpty()
                ? Collections.emptySet()
                : new LinkedHashSet<>(Arrays.asList(parts[2].split(",")));
        return new Entry(parts[3], parts[0], duration, classes);
    }
}
