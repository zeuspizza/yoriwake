package io.github.zeuspizza.yoriwake.agent.select;

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides which tests a change can reach. Every rule that forces a full run is evaluated before
 * any rule that narrows. Plain Java with no dependencies, because it runs in the host's test JVM.
 */
public final class Selector {

    private Selector() {}

    /** What the selector decided, and why. */
    public static final class Decision {
        private final Set<String> selectedTestIds;
        private final String fullRunReason;
        private final FullRunKind fullRunKind;
        private final Set<String> knownTestIds;
        private Set<String> notKnownToPass = Collections.emptySet();
        private Set<String> sharedJvmOnly = Collections.emptySet();
        private volatile Rules rules;

        private Decision(Set<String> selectedTestIds, String fullRunReason, FullRunKind fullRunKind,
                Set<String> knownTestIds) {
            this.selectedTestIds = selectedTestIds;
            this.fullRunReason = fullRunReason;
            this.fullRunKind = fullRunKind;
            this.knownTestIds = knownTestIds;
        }

        static Decision fullRun(String reason, FullRunKind kind, Set<String> knownTestIds) {
            return new Decision(null, reason, kind, knownTestIds);
        }

        static Decision selecting(Set<String> ids, Set<String> knownTestIds, Set<String> notKnownToPass,
                Set<String> sharedJvmOnly) {
            Decision decision = new Decision(Collections.unmodifiableSet(ids), null, null, knownTestIds);
            decision.notKnownToPass = Collections.unmodifiableSet(notKnownToPass);
            decision.sharedJvmOnly = Collections.unmodifiableSet(sharedJvmOnly);
            return decision;
        }

        /** True when nothing may be skipped. Distinct from "every test happened to be selected". */
        public boolean isFullRun() {
            return fullRunReason != null;
        }

        /** Never null when {@link #isFullRun()}. */
        public String fullRunReason() {
            return fullRunReason;
        }

        /** {@link #fullRunReason()} as a token, so tools need not parse prose. */
        public FullRunKind fullRunKind() {
            return fullRunKind;
        }

        public int knownTests() {
            return knownTestIds.size();
        }

        /** Every rule behind each test, for the decision record; null unless the test JVM computed it. */
        public Rules rules() {
            return rules;
        }

        void attach(Rules computed) {
            this.rules = computed;
        }

        /** Why nothing may be skipped, as one of a closed set; each value has a different remedy. */
        public enum FullRunKind {
            /** There is no usable map: absent, unreadable, or written by another schema version. */
            MAP_UNUSABLE("map-unusable"),

            /** The daemon side already refused; its own cause is {@code refusalKind} in explain.json. */
            DAEMON_REFUSED("daemon-refused"),

            /** Changed paths no compiled class can be derived from: resources, scripts, generated input. */
            UNMAPPABLE_PATHS("unmappable-paths"),

            /** The change set is empty, which cannot be told apart from not having one. */
            EMPTY_CHANGE_SET("empty-change-set"),

            /** The change touches coverage recorded before any test owned it. */
            STARTUP_COVERAGE("startup-coverage"),

            /** The map has no coverage for a changed class; uncovered and unrecorded look the same. */
            NO_COVERAGE_FOR_CHANGED("no-coverage-for-changed"),

            /** A class's compiled bytes changed with no source change, and no test recorded it. */
            NO_COVERAGE_FOR_CHANGED_BYTES("no-coverage-for-changed-bytes");

            private final String token;

            FullRunKind(String token) {
                this.token = token;
            }

            /** The stable spelling, for anything that writes or reads this without a JVM. */
            public String token() {
                return token;
            }
        }

        /** Why a test runs, so a report can say which rule is doing the work. */
        public enum Reason {
            /** Nothing may be skipped; see {@link Decision#fullRunReason()}. */
            FULL_RUN("full-run"),
            /** The test's recorded coverage intersects the change, or its own test class changed. */
            REACHES_CHANGE("reaches-change"),
            /**
             * The test ran at or after its JVM first loaded a changed class or read its file, so it
             * may depend on what that JVM did with it once without recording anything.
             */
            SHARES_JVM_WITH_CHANGE("shares-jvm-with-change"),
            /** The map does not record this test as having passed. */
            NOT_KNOWN_TO_PASS("not-known-to-pass"),
            /** The map has never seen this test, so no change could intersect it. */
            NOT_IN_MAP("not-in-map"),
            /** Nothing selected it. */
            SKIPPED("skipped");

            private final String token;

            Reason(String token) {
                this.token = token;
            }

            /** The spelling in the console tally; {@code decisions.tsv} rows carry a {@link Verdict}. */
            public String token() {
                return token;
            }
        }

        /** Which rule decided this test, in the order {@link #includes} applies them. */
        public Reason reasonFor(String testId) {
            if (isFullRun()) {
                return Reason.FULL_RUN;
            }
            if (!knownToMapAtClassGranularity(testId)) {
                return Reason.NOT_IN_MAP;
            }
            if (!selectedTestIds.contains(testId)) {
                return Reason.SKIPPED;
            }
            return reasonForSelected(testId);
        }

        private Reason reasonForSelected(String testId) {
            if (notKnownToPass.contains(testId)) {
                return Reason.NOT_KNOWN_TO_PASS;
            }
            return sharedJvmOnly.contains(testId) ? Reason.SHARES_JVM_WITH_CHANGE : Reason.REACHES_CHANGE;
        }

        /**
         * The decision for a descriptor that reports itself as a test, which is not always one.
         *
         * <p>Spock reports a data-driven feature as a test, but its own record holds only framework
         * machinery while its iterations hold the coverage. So SKIPPED falls back to the subtree;
         * this can only include.
         */
        public Reason reasonForTest(String testId) {
            Reason exact = reasonFor(testId);
            if (exact != Reason.SKIPPED) {
                return exact;
            }
            // Exact, not an approximation: without recorded children the subtree answer is SKIPPED
            // too, and this keeps two linear scans per descriptor off the discovery path.
            if (!hasRecordedChildren(knownTestIds, testId)) {
                return exact;
            }
            return subtreeReason(testId);
        }

        /**
         * The same decision for a leaf container (a test template or dynamic-test factory), judged
         * on its whole subtree because capture records the invocations beneath it.
         */
        public boolean includesSubtree(String containerId) {
            return subtreeReason(containerId) != Reason.SKIPPED;
        }

        /** Which rule decided a leaf container. */
        public Reason subtreeReason(String containerId) {
            if (isFullRun()) {
                return Reason.FULL_RUN;
            }
            String prefix = containerId + "/";
            boolean knownToMap = false;
            for (String known : knownTestIds) {
                if (known.equals(containerId) || known.startsWith(prefix)) {
                    knownToMap = true;
                    break;
                }
            }
            if (!knownToMap) {
                return Reason.NOT_IN_MAP;
            }
            Reason found = Reason.SKIPPED;
            for (String selected : selectedTestIds) {
                if (selected.equals(containerId) || selected.startsWith(prefix)) {
                    Reason reason = reasonForSelected(selected);
                    if (reason == Reason.REACHES_CHANGE) {
                        return reason;
                    }
                    if (found != Reason.SHARES_JVM_WITH_CHANGE) {
                        found = reason;
                    }
                }
            }
            return found;
        }

        /**
         * Test classes the map knows and this decision runs nothing from.
         *
         * <p>For builds not on the JUnit Platform, reduced to class names for Gradle's test filter.
         * Deselected classes, never selected ones: excluding these keeps a class the map has never
         * seen running. Empty for a full run.
         */
        public Set<String> classesWithNothingSelected() {
            if (isFullRun()) {
                return Collections.emptySet();
            }
            Map<String, String> classNameByContainer = new HashMap<>();
            for (String known : knownTestIds) {
                String container = classContainerOf(known);
                if (container == null) {
                    // An id with no class segment refuses the whole selection, not just itself: its
                    // coverage (e.g. a JUnit 4 runner's inner test) may belong to a parseable class.
                    return Collections.emptySet();
                }
                classNameByContainer.put(container, classNameOf(container));
            }
            Set<String> deselected = new HashSet<>();
            for (Map.Entry<String, String> entry : classNameByContainer.entrySet()) {
                if (entry.getValue() != null && !includesSubtree(entry.getKey())) {
                    deselected.add(entry.getValue());
                }
            }
            return deselected;
        }

        /**
         * Known test ids without a class segment, so the class-granularity refusal can be reported.
         * Empty for a full run, so another cause is never blamed on these ids.
         */
        public List<String> idShapesNotRecognised() {
            if (isFullRun()) {
                return Collections.emptyList();
            }
            List<String> unrecognised = new ArrayList<>();
            for (String known : knownTestIds) {
                if (classContainerOf(known) == null) {
                    unrecognised.add(known);
                }
            }
            return unrecognised;
        }

        /** Whether this test runs; delegates so it cannot drift from the rule the filter applies. */
        public boolean includes(String testId) {
            return reasonForTest(testId) != Reason.SKIPPED;
        }

        /** Capture records parameterised tests per invocation while discovery yields the template. */
        private boolean knownToMapAtClassGranularity(String testId) {
            if (knownTestIds.contains(testId)) {
                return true;
            }
            // Only a template counts as known through its children. Matching any test in the same
            // class would read a newly added method as known, and it would never run.
            return hasRecordedChildren(knownTestIds, testId);
        }

        /** How many tests the selection names. Absent tests are added by {@link #includes}. */
        public int selectedCount() {
            return isFullRun() ? knownTestIds.size() : selectedTestIds.size();
        }
    }

    /**
     * Decides what to run.
     *
     * @param map the decoded coverage map, or an unusable result
     * @param change what the daemon established about the change
     * @param discoveredTestIds every test that exists in this run
     */
    public static Decision decide(
            MapReader.Result map, ChangeSet change, Collection<String> discoveredTestIds) {
        Collection<String> changedClassPrefixes = change.changedClasses();
        Collection<String> unmappablePaths = change.unmappablePaths();
        Collection<String> unreadablePaths = change.unreadablePaths();
        Collection<String> absenceProvable = change.absenceProvable();
        Collection<String> ownTestClasses = change.ownTestClasses();
        boolean changeWasFullyAccountedFor = change.accountedFor();
        String daemonRefusal = change.daemonRefusal();

        if (!map.isUsable()) {
            // No map to name tests from; every discovered test is unknown and runs anyway.
            return Decision.fullRun(map.unusableReason(), Decision.FullRunKind.MAP_UNUSABLE,
                    Collections.<String>emptySet());
        }

        List<MapReader.Entry> tests = new ArrayList<>();
        List<MapReader.Entry> classScoped = new ArrayList<>();
        List<MapReader.Entry> global = new ArrayList<>();
        for (MapReader.Entry entry : map.entries()) {
            if (AgentContract.UNATTRIBUTED_RECORD_ID.equals(entry.testId())) {
                global.add(entry);
            } else if (entry.testId().startsWith(AgentContract.CLASS_SCOPED_RECORD_PREFIX)) {
                classScoped.add(entry);
            } else {
                tests.add(entry);
            }
        }

        Set<String> knownTestIds = new HashSet<>();
        for (MapReader.Entry test : tests) {
            knownTestIds.add(test.testId());
        }

        if (daemonRefusal != null) {
            return Decision.fullRun(daemonRefusal, Decision.FullRunKind.DAEMON_REFUSED, knownTestIds);
        }

        List<String> unreadable = new ArrayList<>();
        for (String path : unmappablePaths) {
            if (!unreadablePaths.contains(path)) {
                unreadable.add(path);
            }
        }
        unmappablePaths = unreadable;

        if (!unmappablePaths.isEmpty()) {
            // Coverage says nothing about build scripts, resources or generated sources, so
            // nothing can be ruled out.
            return Decision.fullRun(
                    "changed paths the coverage map cannot see: " + join(unmappablePaths),
                    Decision.FullRunKind.UNMAPPABLE_PATHS, knownTestIds);
        }

        if (changedClassPrefixes.isEmpty() && !changeWasFullyAccountedFor) {
            // The default base is HEAD, so committing and then testing yields an empty diff; that
            // is absence of information, not proof that nothing needs to run.
            return Decision.fullRun(
                    "the change set is empty, which cannot be told apart from not having one;"
                            + " pass -Pyoriwake.base=<ref> to select against a specific base",
                    Decision.FullRunKind.EMPTY_CHANGE_SET, knownTestIds);
        }

        // Never merged into the change set, which also anchors isNestedUnder.
        Set<String> changedBytes = bytesOnly(change.changedBytes(), changedClassPrefixes);

        for (MapReader.Entry entry : global) {
            if (intersects(entry, changedClassPrefixes) || recordsAny(entry, changedBytes)) {
                return Decision.fullRun(
                        "the change touches startup coverage, which no test owns",
                        Decision.FullRunKind.STARTUP_COVERAGE, knownTestIds);
            }
        }

        // A changed class the map has never seen forces: "uncovered" looks the same as "never
        // loaded", "outside the scope" or "from another module". Only untested() may narrow here.
        List<String> unknown = new ArrayList<>();
        for (String prefix : changedClassPrefixes) {
            if (knownToMap(map.entries(), prefix, changedClassPrefixes)) {
                continue;
            }
            if (ownTestClasses.contains(prefix)) {
                // Discovery runs it whether the map knows it or not.
                continue;
            }
            if (untested(map, prefix, absenceProvable, changedClassPrefixes)) {
                continue;
            }
            unknown.add(prefix);
        }
        if (!unknown.isEmpty()) {
            return Decision.fullRun(
                    "the map has no coverage for " + join(unknown)
                            + "; uncovered and unrecorded are indistinguishable",
                    Decision.FullRunKind.NO_COVERAGE_FOR_CHANGED, knownTestIds);
        }

        // No absence rule here: bytes that changed where no test looked are as unknown as a
        // changed class the map never saw.
        List<String> unrecorded = new ArrayList<>();
        for (String name : changedBytes) {
            if (!recordedByAny(map.entries(), name)) {
                unrecorded.add(name);
            }
        }
        if (!unrecorded.isEmpty()) {
            return Decision.fullRun(
                    "no test recorded " + join(unrecorded) + ", whose compiled bytes changed with"
                            + " no source change behind them",
                    Decision.FullRunKind.NO_COVERAGE_FOR_CHANGED_BYTES, knownTestIds);
        }

        Set<String> selected = alwaysRun(tests, discoveredTestIds);

        for (MapReader.Entry test : tests) {
            if (intersects(test, changedClassPrefixes) || recordsAny(test, changedBytes)) {
                selected.add(test.testId());
            }
        }

        // Setup coverage belongs to its classes: running any test in a class re-executes that
        // class's @BeforeAll and @AfterAll, so selecting those tests is enough.
        for (MapReader.Entry entry : classScoped) {
            if (!intersects(entry, changedClassPrefixes) && !recordsAny(entry, changedBytes)) {
                continue;
            }
            for (String scope : classScopesOf(entry)) {
                for (MapReader.Entry test : tests) {
                    if (test.testId().startsWith(scope)) {
                        selected.add(test.testId());
                    }
                }
            }
        }

        // Not left to the class's coverage of itself, which JaCoCo records none of for a class it
        // could not instrument.
        for (MapReader.Entry test : tests) {
            if (ofChangedOwnClass(test.testId(), ownTestClasses, changedClassPrefixes)) {
                selected.add(test.testId());
            }
        }

        Set<String> sharedJvmOnly = new HashSet<>();
        if (!changedClassPrefixes.isEmpty() || !changedBytes.isEmpty()) {
            Set<String> exempt = exemptKnownToMap(change.exemptTestClasses(), changedClassPrefixes, knownTestIds);
            for (String id : ranAfterFirstTouch(map, tests, changedClassPrefixes, changedBytes, exempt)) {
                if (selected.add(id)) {
                    sharedJvmOnly.add(id);
                }
            }
        }

        return Decision.selecting(selected, knownTestIds, notKnownToPass(tests), sharedJvmOnly);
    }

    /**
     * Every rule that would run each test under this change, each evaluated on its own rather than
     * stopping at the first, and every full-run kind whose condition holds. Beneath a refusal or a
     * full run too, where the inputs allow. Mirrors {@link #decide} rule for rule and feeds nothing
     * back into it.
     *
     * @param changeKnown false when the daemon refused before it had a change set to hand over
     */
    public static Rules rules(MapReader.Result map, ChangeSet change, boolean changeKnown) {
        List<Decision.FullRunKind> forcing = new ArrayList<>();
        if (!map.isUsable()) {
            forcing.add(Decision.FullRunKind.MAP_UNUSABLE);
            return Rules.notComputed(AgentContract.RULES_NO_MAP, forcing);
        }
        String basis = AgentContract.RULES_COMPLETE;
        if (change.daemonRefusal() != null) {
            forcing.add(Decision.FullRunKind.DAEMON_REFUSED);
            if (!changeKnown) {
                return Rules.notComputed(AgentContract.RULES_NO_CHANGE_SET, forcing);
            }
            basis = AgentContract.RULES_FROM_REFUSED_INPUTS;
        }
        Collection<String> changed = change.changedClasses();
        List<MapReader.Entry> entries = map.entries();

        List<MapReader.Entry> tests = new ArrayList<>();
        List<MapReader.Entry> classScoped = new ArrayList<>();
        List<MapReader.Entry> global = new ArrayList<>();
        for (MapReader.Entry entry : entries) {
            if (AgentContract.UNATTRIBUTED_RECORD_ID.equals(entry.testId())) {
                global.add(entry);
            } else if (entry.testId().startsWith(AgentContract.CLASS_SCOPED_RECORD_PREFIX)) {
                classScoped.add(entry);
            } else {
                tests.add(entry);
            }
        }
        Set<String> knownTestIds = new HashSet<>();
        for (MapReader.Entry test : tests) {
            knownTestIds.add(test.testId());
        }

        for (String path : change.unmappablePaths()) {
            if (!change.unreadablePaths().contains(path)) {
                forcing.add(Decision.FullRunKind.UNMAPPABLE_PATHS);
                break;
            }
        }
        if (changed.isEmpty() && !change.accountedFor()) {
            forcing.add(Decision.FullRunKind.EMPTY_CHANGE_SET);
        }
        Set<String> bytes = bytesOnly(change.changedBytes(), changed);
        for (MapReader.Entry entry : global) {
            if (intersects(entry, changed) || recordsAny(entry, bytes)) {
                forcing.add(Decision.FullRunKind.STARTUP_COVERAGE);
                break;
            }
        }
        for (String prefix : changed) {
            if (!knownToMap(entries, prefix, changed)
                    && !change.ownTestClasses().contains(prefix)
                    && !untested(map, prefix, change.absenceProvable(), changed)) {
                forcing.add(Decision.FullRunKind.NO_COVERAGE_FOR_CHANGED);
                break;
            }
        }
        for (String name : bytes) {
            if (!recordedByAny(entries, name)) {
                forcing.add(Decision.FullRunKind.NO_COVERAGE_FOR_CHANGED_BYTES);
                break;
            }
        }

        Map<String, EnumSet<Rule>> byTest = new HashMap<>();
        for (MapReader.Entry test : tests) {
            if (!AgentContract.OUTCOME_SUCCESSFUL.equals(test.outcome())) {
                add(byTest, test.testId(), Rule.NOT_KNOWN_TO_PASS);
            }
            if (intersects(test, changed)) {
                add(byTest, test.testId(), Rule.REACHES_CHANGE);
            }
            if (ofChangedOwnClass(test.testId(), change.ownTestClasses(), changed)) {
                add(byTest, test.testId(), Rule.OWN_CLASS_CHANGED);
            }
            if (recordsAny(test, bytes)) {
                add(byTest, test.testId(), Rule.CHANGED_BYTES);
            }
        }
        for (MapReader.Entry entry : classScoped) {
            if (!intersects(entry, changed) && !recordsAny(entry, bytes)) {
                continue;
            }
            for (String scope : classScopesOf(entry)) {
                for (MapReader.Entry test : tests) {
                    if (test.testId().startsWith(scope)) {
                        add(byTest, test.testId(), Rule.CLASS_SETUP);
                    }
                }
            }
        }
        if (!changed.isEmpty() || !bytes.isEmpty()) {
            Set<String> exempt = exemptKnownToMap(change.exemptTestClasses(), changed, knownTestIds);
            sharesJvm(map, tests, changed, bytes, exempt, byTest);
        }
        return new Rules(basis, forcing, byTest, knownTestIds);
    }

    /** {@link #ranAfterFirstTouch}, with each window kept apart by what opened it. */
    private static void sharesJvm(MapReader.Result map, List<MapReader.Entry> tests,
            Collection<String> changed, Set<String> bytes, Set<String> exempt,
            Map<String, EnumSet<Rule>> byTest) {
        List<String> dated = new ArrayList<>();
        for (String prefix : changed) {
            if (!exempt.contains(prefix)) {
                dated.add(prefix);
            }
        }
        Map<Rule, Map<String, Integer>> opened = new EnumMap<>(Rule.class);
        for (MapReader.FirstTouch touch : map.firstTouches()) {
            String name = touch.className();
            if (AgentContract.FIRST_TOUCH_ANY.equals(name)) {
                open(opened, Rule.SHARES_JVM_UNOBSERVED, touch);
            }
            if (bytes.contains(name)) {
                open(opened, Rule.SHARES_JVM_CHANGED_BYTES, touch);
            }
            if (touchesChange(name, dated, Collections.<String>emptySet(), changed)
                    && !AgentContract.FIRST_TOUCH_ANY.equals(name)) {
                open(opened, Rule.SHARES_JVM_CHANGED_CLASS, touch);
            }
        }
        if (!exempt.isEmpty()) {
            for (MapReader.FirstTouch named : map.namedTouches()) {
                String name = named.className();
                if (AgentContract.FIRST_TOUCH_ANY.equals(name)) {
                    open(opened, Rule.SHARES_JVM_UNOBSERVED, named);
                } else if (touchesChange(name, exempt, Collections.<String>emptySet(), changed)) {
                    open(opened, Rule.SHARES_JVM_CHANGED_CLASS, named);
                }
            }
        }
        for (MapReader.Entry test : tests) {
            List<MapReader.Position> positions = map.positionsOf(test.testId());
            if (positions.isEmpty()) {
                add(byTest, test.testId(), Rule.SHARES_JVM_UNPOSITIONED);
                continue;
            }
            for (Map.Entry<Rule, Map<String, Integer>> window : opened.entrySet()) {
                for (MapReader.Position position : positions) {
                    Integer opensAt = window.getValue().get(position.jvm());
                    if (opensAt != null && position.sequence() >= opensAt) {
                        add(byTest, test.testId(), window.getKey());
                        break;
                    }
                }
            }
        }
    }

    private static void open(Map<Rule, Map<String, Integer>> opened, Rule rule, MapReader.FirstTouch touch) {
        opened.computeIfAbsent(rule, key -> new HashMap<>()).merge(touch.jvm(), touch.sequence(), Math::min);
    }

    private static void add(Map<String, EnumSet<Rule>> byTest, String testId, Rule rule) {
        byTest.computeIfAbsent(testId, key -> EnumSet.noneOf(Rule.class)).add(rule);
    }

    /**
     * Every test that ran at or after its JVM first loaded, executed or read the file of a changed
     * class. Coverage credits code a JVM runs once (a static initialiser, a context built and
     * cached) to the first test that triggers it, and a test that only reflects on a class or reads
     * its class file executes none of it; either way the dependent test records nothing. Nothing
     * about a class can reach a test before the class arrived in that test's JVM, so the tests
     * before that point are the only ones this rule lets go.
     *
     * <p>A test the map holds no position for runs: nothing says it ran before anything.
     */
    private static Set<String> ranAfterFirstTouch(MapReader.Result map, List<MapReader.Entry> tests,
            Collection<String> changedClassPrefixes, Set<String> changedBytes, Set<String> exempt) {
        Map<String, Integer> firstTouchByJvm = new HashMap<>();
        List<String> dated = new ArrayList<>();
        for (String prefix : changedClassPrefixes) {
            if (!exempt.contains(prefix)) {
                dated.add(prefix);
            }
        }
        for (MapReader.FirstTouch touch : map.firstTouches()) {
            if (touchesChange(touch.className(), dated, changedBytes, changedClassPrefixes)) {
                firstTouchByJvm.merge(touch.jvm(), touch.sequence(), Math::min);
            }
        }
        // An exempt test class is dated only by what can reach it from outside its own tests.
        if (!exempt.isEmpty()) {
            for (MapReader.FirstTouch touch : map.namedTouches()) {
                if (touchesChange(touch.className(), exempt, Collections.<String>emptySet(), changedClassPrefixes)) {
                    firstTouchByJvm.merge(touch.jvm(), touch.sequence(), Math::min);
                }
            }
        }
        Set<String> after = new HashSet<>();
        for (MapReader.Entry test : tests) {
            List<MapReader.Position> positions = map.positionsOf(test.testId());
            if (positions.isEmpty()) {
                after.add(test.testId());
                continue;
            }
            for (MapReader.Position position : positions) {
                Integer first = firstTouchByJvm.get(position.jvm());
                if (first != null && position.sequence() >= first) {
                    after.add(test.testId());
                    break;
                }
            }
        }
        return after;
    }

    /**
     * @param prefixes the changed prefixes to match against
     * @param changeSet every prefix of this change, the anchor set of the nested-class rule
     */
    private static boolean touchesChange(String className, Collection<String> prefixes,
            Set<String> changedBytes, Collection<String> changeSet) {
        if (AgentContract.FIRST_TOUCH_ANY.equals(className) || changedBytes.contains(className)) {
            return true;
        }
        for (String prefix : prefixes) {
            if (matchesSourceFile(className, prefix, changeSet)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The exempt test classes the map holds tests of. One it holds none of cannot be the class its
     * own tests ran from, so it is dated like any other class.
     */
    private static Set<String> exemptKnownToMap(
            Collection<String> exempt, Collection<String> changedClassPrefixes, Set<String> knownTestIds) {
        Set<String> known = new HashSet<>();
        for (String prefix : exempt) {
            if (!changedClassPrefixes.contains(prefix)) {
                continue;
            }
            for (String id : knownTestIds) {
                if (prefix.equals(testClassOf(id))) {
                    known.add(prefix);
                    break;
                }
            }
        }
        return known;
    }

    /** Whether the test is one of a changed own test class's, as {@link #testClassOf} names it. */
    private static boolean ofChangedOwnClass(
            String testId, Collection<String> ownTestClasses, Collection<String> changedClassPrefixes) {
        String name = testClassOf(testId);
        return name != null && ownTestClasses.contains(name) && changedClassPrefixes.contains(name);
    }

    /**
     * The class an id's first class segment names, which for a nested class's test is the outer
     * class; null for an id with no class segment.
     */
    private static String testClassOf(String testId) {
        String container = classContainerOf(testId);
        return container == null ? null : classNameOf(container);
    }

    private static Set<String> notKnownToPass(List<MapReader.Entry> tests) {
        Set<String> failing = new HashSet<>();
        for (MapReader.Entry test : tests) {
            // Phrased as "not successful" so an unrecorded or unrecognised outcome resolves toward
            // running the test.
            if (!AgentContract.OUTCOME_SUCCESSFUL.equals(test.outcome())) {
                failing.add(test.testId());
            }
        }
        return failing;
    }

    private static Set<String> alwaysRun(List<MapReader.Entry> tests, Collection<String> discovered) {
        Set<String> always = new HashSet<>(notKnownToPass(tests));
        Map<String, MapReader.Entry> byId = new HashMap<>();
        for (MapReader.Entry test : tests) {
            byId.put(test.testId(), test);
        }
        for (String discoveredId : discovered) {
            // A test added since the map was built has no record, so no change could select it.
            // A template counts as recorded when the map holds its invocations (children of its id).
            if (!byId.containsKey(discoveredId)
                    && !hasRecordedChildren(byId.keySet(), discoveredId)) {
                always.add(discoveredId);
            }
        }
        return always;
    }

    /** Whether the map holds ids beneath this one, which is how a parameterised test appears. */
    private static boolean hasRecordedChildren(Set<String> knownIds, String id) {
        String childPrefix = id + "/";
        for (String known : knownIds) {
            if (known.startsWith(childPrefix)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> classScopesOf(MapReader.Entry entry) {
        String raw = entry.testId().substring(AgentContract.CLASS_SCOPED_RECORD_PREFIX.length());
        List<String> scopes = new ArrayList<>();
        for (String scope : raw.split("\\|")) {
            if (!scope.isEmpty()) {
                scopes.add(scope);
            }
        }
        return scopes;
    }

    /**
     * Whether a changed class can be proven untested rather than merely unrecorded.
     *
     * <p>The riskiest rule here: it narrows on the <em>absence</em> of coverage. It fires only when
     * all of the following hold, and forces the moment any of them cannot be established:
     *
     * <ul>
     *   <li>the daemon proved it was in the effective instrumentation scope, carries probes,
     *       declares no compile-time constant, is not Kotlin, carries no class-level annotation,
     *       and no read resource names it;
     *   <li>the map's recorded scope is the scope in force now;
     *   <li>no capturing JVM loaded it or read its class or source file;
     *   <li>the class is absent from a trustworthy loaded-class union.
     * </ul>
     *
     * <p>Coverage proves only that nothing <em>executed</em> the class; reflection can still
     * <em>load</em> and inspect it. Absence from the union is never a rule on its own.
     */
    private static boolean untested(
            MapReader.Result map,
            String prefix,
            Collection<String> absenceProvable,
            Collection<String> changedClassPrefixes) {
        if (!absenceProvable.contains(prefix)) {
            return false;
        }
        for (MapReader.FirstTouch touch : map.firstTouches()) {
            // Read as a class file without being loaded is still read: a reader depends on it.
            if (AgentContract.FIRST_TOUCH_ANY.equals(touch.className())
                    || matchesSourceFile(touch.className(), prefix, changedClassPrefixes)) {
                return false;
            }
        }
        Set<String> loaded = map.loadedClasses();
        if (loaded == null) {
            // No full recording run has ever produced a union, so nothing is known about loading.
            return false;
        }
        if (!map.unionSpeaksFor(prefix)) {
            // Outside a filtered union, absence only means nobody was looking.
            return false;
        }
        for (String name : loaded) {
            // The three-argument form: presence must be matched at least as broadly as coverage,
            // or evidence of loading (e.g. a nested `Outer$Inner`) becomes permission to skip.
            if (matchesSourceFile(name, prefix, changedClassPrefixes)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The id prefix every test in one class shares, or null for a shape this does not understand.
     *
     * <p>Tracks bracket depth because JUnit does not escape a {@code ']'} inside a segment value.
     */
    static String classContainerOf(String testId) {
        int start = testId.indexOf("[class:");
        if (start < 0) {
            return null;
        }
        int depth = 0;
        for (int i = start; i < testId.length(); i++) {
            char c = testId.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return testId.substring(0, i + 1);
                }
            }
        }
        return null;
    }

    /** The class name inside a container id, or null when it carries none. */
    static String classNameOf(String containerId) {
        int start = containerId.lastIndexOf("[class:");
        if (start < 0 || !containerId.endsWith("]")) {
            return null;
        }
        String name = containerId.substring(start + "[class:".length(), containerId.length() - 1);
        return name.isEmpty() ? null : name;
    }

    private static boolean knownToMap(
            List<MapReader.Entry> entries, String prefix, Collection<String> changeSet) {
        for (MapReader.Entry entry : entries) {
            for (String covered : entry.coveredClasses()) {
                if (matchesSourceFile(covered, prefix, changeSet)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The classes of {@code changedBytes} that no prefix of {@code changeSet} compiles to, by the
     * match reaches-change uses. The rest are the change set's to judge: a recompiled source moves
     * its own bytes too.
     */
    public static Set<String> bytesOnly(Collection<String> changedBytes, Collection<String> changeSet) {
        Set<String> only = new HashSet<>();
        for (String name : changedBytes) {
            boolean fromChangedSource = false;
            for (String prefix : changeSet) {
                if (matchesSourceFile(name, prefix, changeSet)) {
                    fromChangedSource = true;
                    break;
                }
            }
            if (!fromChangedSource) {
                only.add(name);
            }
        }
        return only;
    }

    /** By exact name: a test recorded these bytes only if it executed that very class. */
    private static boolean recordsAny(MapReader.Entry entry, Set<String> names) {
        if (names.isEmpty()) {
            return false;
        }
        for (String covered : entry.coveredClasses()) {
            if (names.contains(covered)) {
                return true;
            }
        }
        return false;
    }

    private static boolean recordedByAny(List<MapReader.Entry> entries, String name) {
        for (MapReader.Entry entry : entries) {
            if (entry.coveredClasses().contains(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean intersects(MapReader.Entry entry, Collection<String> prefixes) {
        for (String covered : entry.coveredClasses()) {
            for (String prefix : prefixes) {
                if (matchesSourceFile(covered, prefix, prefixes)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether a compiled class name came from the source file this prefix denotes.
     *
     * <p>Includes {@code UtilsKt}, where Kotlin puts the top-level declarations of {@code Utils.kt}.
     */
    static boolean matchesSourceFile(String covered, String prefix) {
        String fileClass = prefix + "Kt";
        return covered.equals(prefix)
                || covered.equals(fileClass)
                || covered.startsWith(prefix + "$")
                || covered.startsWith(fileClass + "$");
    }

    /**
     * As {@link #matchesSourceFile(String, String)}, plus nested types the change set itself names.
     *
     * @param changeSet every prefix derived from this change, needed to anchor the nested rule
     */
    static boolean matchesSourceFile(String covered, String prefix, Collection<String> changeSet) {
        return matchesSourceFile(covered, prefix) || isNestedUnder(covered, prefix, changeSet);
    }

    /**
     * Whether the covered class is a nested type of a class <em>this same change</em> names.
     *
     * <p>The declaration scan cannot see nesting, so {@code Comment} nested in {@code Forbidden}
     * yields prefix {@code pkg.Comment} for class {@code pkg.Forbidden$Comment}. The outer class
     * must be in the change set, or an unrelated {@code pkg.Other$Comment} could certify it.
     *
     * <p>Here the change set is also the anchor set, so widening it is not automatically safe: an
     * added name can certify a prefix nothing covers. Widen the selection set and this one
     * separately, or not at all.
     */
    private static boolean isNestedUnder(String covered, String prefix, Collection<String> changeSet) {
        int packageEnd = prefix.lastIndexOf('.');
        // Whole packages, not prefixes of them: dev.acme must not answer for dev.acmecorp.
        if (packageEnd < 0 || packageEnd != covered.lastIndexOf('.')) {
            return false;
        }
        if (!covered.regionMatches(0, prefix, 0, packageEnd + 1)) {
            return false;
        }
        int nameStart = packageEnd + 1;
        // An empty simple name would otherwise match the empty segment of a trailing '$'.
        if (nameStart >= prefix.length()) {
            return false;
        }
        int firstDollar = covered.indexOf('$', nameStart);
        if (firstDollar < 0) {
            return false; // Top-level; the strict match above already had its chance.
        }
        // The outermost class must be one this change names, or the coverage is somebody else's.
        if (!changeSet.contains(covered.substring(0, firstDollar))) {
            return false;
        }
        // No split(): this is hot, and split compiles a Pattern and allocates on each call.
        int nameLength = prefix.length() - nameStart;
        for (int start = nameStart; start <= covered.length(); ) {
            int end = covered.indexOf('$', start);
            if (end < 0) {
                end = covered.length();
            }
            if (end - start == nameLength && covered.regionMatches(start, prefix, nameStart, nameLength)) {
                return true;
            }
            start = end + 1;
        }
        return false;
    }

    private static String join(Collection<String> values) {
        List<String> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return String.join(", ", sorted);
    }
}
