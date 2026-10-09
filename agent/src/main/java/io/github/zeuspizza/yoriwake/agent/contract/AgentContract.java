package io.github.zeuspizza.yoriwake.agent.contract;

/**
 * Every name the plugin and the agent share, defined once. Specified in {@code docs/contract.md}.
 *
 * <p>The two sides meet only through properties, files and record text, where a drifted name
 * fails silently; compiling both against this class turns drift into a compile error.
 *
 * <p>Only compile-time constants belong here, so the daemon never needs this class at runtime.
 */
public final class AgentContract {

    private AgentContract() {}


    /** The map directory the agent reads. */
    public static final String MAP_DIR_PROPERTY = "yoriwake.map.dir";

    /** Where the agent writes per-test records. Capture is off unless it is set. */
    public static final String RECORDS_DIR_PROPERTY = "yoriwake.internal.capture.outputDir";

    /** Where the loaded-class recorder writes its per-worker lists. Set only when it is attached. */
    public static final String LOADED_DIR_PROPERTY = "yoriwake.loaded.out";

    /**
     * Set only on a test task that does not run on the JUnit Platform. On the Platform the listener
     * already captures, and a second path would take coverage out of its records.
     */
    public static final String JUNIT4_HOOK_PROPERTY = "yoriwake.junit4.hook";

    /** {@code true} when this run should narrow; anything else runs everything. */
    public static final String SELECT_PROPERTY = "yoriwake.select";

    /**
     * {@code true} when this run executes every test and records, beside each row, the verdict a
     * selecting run of the same inputs would have given. Set beside {@link #SELECT_PROPERTY}, which
     * still decides what that verdict is.
     */
    public static final String OBSERVE_PROPERTY = "yoriwake.observe";

    /**
     * Rounds a selection up to whole classes, for order-dependent tests sharing static state. Off by
     * default. It only ever includes more.
     */
    public static final String CLASS_GRANULARITY_PROPERTY = "yoriwake.select.classGranularity";

    /** The changed classes, comma-separated. */
    public static final String CHANGED_CLASSES_PROPERTY = "yoriwake.change.classes";

    /** Changed paths no class could be derived from, comma-separated. Any one forces. */
    public static final String UNMAPPABLE_PATHS_PROPERTY = "yoriwake.change.unmappable";

    /**
     * Set when every changed path was accounted for: off this task's classpath, or unreadable.
     * Without it an empty change set is indistinguishable from git having nothing to say.
     */
    public static final String ACCOUNTED_PROPERTY = "yoriwake.change.accountedFor";

    /** Changed classes whose absence from the map the daemon could prove means untested. */
    public static final String ABSENCE_PROVABLE_PROPERTY = "yoriwake.change.absenceProvable";

    /** Changed paths the build never reads and no compiled class can name. */
    public static final String UNREADABLE_PATHS_PROPERTY = "yoriwake.change.unreadable";

    /** Changed classes that are this task's own test classes, which discovery already runs. */
    public static final String OWN_TEST_CLASSES_PROPERTY = "yoriwake.change.ownTestClasses";

    /**
     * Changed test classes of this task that nothing else names: no other compiled class or
     * resource holds their name, and each declares a test. JUnit instantiates them, so what their
     * code does can reach another test only through a lookup, a read or an execution the agent
     * sees, and their discovery-time loading opens no window.
     */
    public static final String EXEMPT_TEST_CLASSES_PROPERTY = "yoriwake.change.exemptTestClasses";

    /**
     * Classes whose compiled bytes differ from the capture's while no changed source accounts for
     * them, comma-separated: a weaver's or post-processor's output. Selects the tests that
     * recorded one; one no test recorded forces.
     */
    public static final String CHANGED_BYTES_PROPERTY = "yoriwake.change.bytes";

    /**
     * The file holding every list-valued change-set property, {@link #CHANGE_SET_FILE} in the map
     * directory. A change set can name tens of thousands of paths, more than one command line
     * carries, so those properties are never passed to the test JVM directly. Set means the file
     * alone answers for them; a file that cannot be read refuses as {@link
     * #CHANGE_SET_UNREADABLE_KIND}.
     */
    public static final String CHANGE_SET_FILE_PROPERTY = "yoriwake.change.file";

    /**
     * Why the daemon refused this run, as prose. Its presence forces a full run and is checked
     * before {@link #SELECT_PROPERTY}, so a refusal always wins.
     */
    public static final String REFUSED_PROPERTY = "yoriwake.refused";

    /** Which refusal it was, as a machine-readable token. */
    public static final String REFUSED_KIND_PROPERTY = "yoriwake.refused.kind";

    /** Every decline that held on this run, as refusal tokens, comma-separated, first one first. */
    public static final String DECLINES_PROPERTY = "yoriwake.declines";

    /**
     * A fresh value per run of a selecting test task. Each decision record carries it, so the decode
     * merges only the records this run wrote.
     */
    public static final String RUN_TOKEN_PROPERTY = "yoriwake.run.token";

    /** Test-id globs selection may never skip, comma-separated. */
    public static final String ALWAYS_RUN_PROPERTY = "yoriwake.alwaysRun";

    /**
     * The refusal kind of a run whose change-set file could not be written or read. Defined here
     * because the agent raises it too.
     */
    public static final String CHANGE_SET_UNREADABLE_KIND = "change-set-unreadable";


    /** The directory under the project cache dir ({@code .gradle/}) that holds one map per task. */
    public static final String MAP_ROOT_DIR = "yoriwake";

    /**
     * The change-set properties {@link #CHANGE_SET_FILE_PROPERTY} names, in {@link
     * java.util.Properties} format and ending with {@link #CHANGE_SET_END}, so a file cut short is
     * refused rather than read as a smaller change set. Rewritten by every selecting run.
     */
    public static final String CHANGE_SET_FILE = "change-set";

    /** The last line of a whole {@link #CHANGE_SET_FILE}. */
    public static final String CHANGE_SET_END = "#end";

    /** The decoded map: {@code outcome \t durationNanos \t class,class \t testId} per record. */
    public static final String COVERAGE_FILE = "coverage.tsv";

    /** The map's format version. Written last, so records without it are a capture that died. */
    public static final String MAP_SCHEMA_VERSION_FILE = "schema-version";

    /**
     * The SHA-256 of the map's files, written after every decode that writes them. A content hash,
     * not a credential: a run given a trusted-map list compares the map's recomputed digest with the
     * list, never with this file. See {@code docs/contract.md}.
     */
    public static final String MAP_DIGEST_FILE = "map-digest";

    /** The instrumentation scope the map was captured under, one package prefix per line. */
    public static final String SCOPE_FILE = "scope";

    /**
     * The scope JaCoCo actually instrumented under, read off the live task. Absent means unknown; an
     * empty include list means everything. Compared for equality, never parsed.
     */
    public static final String EFFECTIVE_SCOPE_FILE = "effective-scope";

    /** Classes the test JVMs loaded, unioned across workers, one per line. */
    public static final String LOADED_FILE = "loaded.txt";

    /** How {@link #LOADED_FILE} was produced: {@code full}, or {@code selecting} for a partial run. */
    public static final String LOADED_PROVENANCE_FILE = "loaded-provenance";

    /** The filter the loaded union was recorded under. Absent means the union speaks for nothing. */
    public static final String LOADED_SCOPE_FILE = "loaded-scope";

    /**
     * Per-test decisions, written by the agent and deleted by the plugin before a run: a stale one
     * would describe a run that did not happen beside a fresh map.
     */
    public static final String DECISIONS_FILE = "decisions.tsv";

    /**
     * What an observing run's verdicts and outcomes say together, written by the decode after every
     * observing run and deleted before every run, so it never describes an earlier one.
     */
    public static final String OBSERVATION_FILE = "observation.json";

    /**
     * What a selecting run that narrowed left of the tests that ran to an outcome, stamped with what
     * decided what they exercised: {@code test \t outcome} per row, after {@code #!} notes. Written by
     * the decode after such a run, removed after any other selecting run; a complement run reads it.
     */
    public static final String SELECTION_FILE = "selection.tsv";

    /** The selection record's version, its {@link #VERSION_NOTE}. */
    public static final String SELECTION_VERSION = "1";

    /** A selection record note: the commit the tests ran at, with a clean tree at both ends. */
    public static final String STAMP_COMMIT_NOTE = "commit";

    /** A selection record note: the test task's path. */
    public static final String STAMP_TASK_NOTE = "task";

    /** A selection record note: the build's root directory, relative to the repository's top level. */
    public static final String STAMP_BUILD_ROOT_NOTE = "build-root";

    /** A selection record note: the project's path in the build tree, which names an included build. */
    public static final String STAMP_BUILD_PATH_NOTE = "build-path";

    /** A selection record note: a digest of the test runtime classpath, file by file. */
    public static final String STAMP_CLASSPATH_NOTE = "classpath";

    /**
     * A selection record note: a digest of the task's system properties, JVM arguments, include and
     * exclude patterns and framework filters.
     */
    public static final String STAMP_CONFIGURATION_NOTE = "configuration";

    /**
     * Ends each writer's own copy of its decisions, {@code decisions.tsv.<pid>-<writer>.part}.
     * {@link #DECISIONS_FILE} is last-writer-wins across test JVMs; the parts hold the whole task.
     */
    public static final String DECISIONS_PART_SUFFIX = ".part";

    /**
     * Where each test record ran: {@code jvm \t sequence \t testId}. A test can appear more than
     * once. The jvm is an opaque id the decoder assigns to one worker of one capture.
     */
    public static final String POSITIONS_FILE = "positions.tsv";

    /**
     * {@code jvm \t sequence \t class}: the first record sequence at or before which each class was
     * loaded, executed, or had its class or source file read in that JVM. {@link #FIRST_TOUCH_ANY}
     * as the class stands for every class.
     */
    public static final String FIRST_TOUCH_FILE = "first-touch.tsv";

    /**
     * {@code jvm \t sequence \t class}, as {@link #FIRST_TOUCH_FILE}, counting only what can reach a
     * test class from outside its own tests: a lookup by name or a read after discovery, or an
     * execution in another class's window. What an exempt test class is dated by.
     */
    public static final String NAMED_TOUCH_FILE = "named-touch.tsv";

    /** A first-touch class meaning "every class": the JVM's loads and reads were not all observed. */
    public static final String FIRST_TOUCH_ANY = "*";

    /**
     * {@code jvm \t mode}: how each JVM of the map was recorded. A JVM with no row, or a map with no
     * file, reads as {@link #MODE_SHARED}. Selection does not read it: which tests shared a JVM is
     * already in {@link #POSITIONS_FILE}. A selecting run that falls back to a full run does, and
     * does not capture over a map any JVM of which is {@link #MODE_ISOLATED}.
     */
    public static final String JVM_MODE_FILE = "jvm-mode.tsv";

    /** The JVM was forked for one test class and ran tests of that class alone. */
    public static final String MODE_ISOLATED = "isolated";

    /** Any other JVM. */
    public static final String MODE_SHARED = "shared";

    /** The raw records directory, beneath the map; {@link #RECORDS_DIR_PROPERTY} points at it. */
    public static final String RAW_DIR = "raw";


    /** One directory per test JVM, {@code worker-<id>}, so two workers never share a sequence. */
    public static final String WORKER_DIR_PREFIX = "worker-";

    /** {@code sequence \t durationNanos \t byteCount \t outcome \t testId} per record. */
    public static final String INDEX_FILE = "index.tsv";

    /** One JaCoCo execution-data blob per record, named by its sequence number. */
    public static final String EXEC_FILE_FORMAT = "%06d.exec";

    /**
     * {@code sequence \t kind \t value}: what the JVM loaded or read for the first time before the
     * record with that sequence was taken. Trusted only when it ends with {@link #TOUCHES_COMPLETE}.
     */
    public static final String TOUCHES_FILE = "touches.tsv";

    /** A touch kind: a class was defined; the value is its binary name. */
    public static final String TOUCH_LOADED = "loaded";

    /**
     * A touch kind: a class was defined by a class loader other than the JDK's application and
     * platform loaders, or as a hidden or anonymous class from code outside the JDK; the value is
     * its binary name. Such a definition can be a second copy of a class, so it reaches that class
     * as a lookup of its name does.
     */
    public static final String TOUCH_DEFINED = "defined";

    /** A touch kind: a class or source file was opened; the value is its path or jar entry name. */
    public static final String TOUCH_READ = "read";

    /**
     * A touch kind: a class was looked up by its name ({@code Class.forName}, a class loader,
     * {@code Lookup.findClass}); the value is the name, or {@link #FIRST_TOUCH_ANY} for a call that
     * can reach any class, such as a reviewed native library.
     */
    public static final String TOUCH_LOOKUP = "lookup";

    /** A touch kind with no value: the test plan started, so what follows is not discovery. */
    public static final String TOUCH_PLAN_STARTED = "plan-started";

    /** A touch kind: a jar was opened by something other than the JDK's own zip code; its path. */
    public static final String TOUCH_JAR = "jar";

    /**
     * A touch kind: from here on not everything was observed (a hook missing, a child process
     * started, native code outside the reviewed libraries, a class defined from bytes whose name
     * could not be read), so every class counts as touched; why.
     */
    public static final String TOUCH_ALL = "all";

    /** The last line of a {@link #TOUCHES_FILE} whose observation ended normally. */
    public static final String TOUCHES_COMPLETE = "#complete";

    /** The run's overhead line, written when capture ends. */
    public static final String OVERHEAD_FILE = "overhead.txt";

    /**
     * The raw record format's version, stamped before the first record. Named apart from
     * {@link #MAP_SCHEMA_VERSION_FILE} so neither format can be read as the other.
     */
    public static final String RAW_SCHEMA_VERSION_FILE = "raw-schema-version";

    /**
     * Written only once a worker's whole run ended normally. The decoder dates the map only when
     * every worker wrote it: a JVM that died mid-run leaves records that still decode cleanly.
     */
    public static final String PLAN_COMPLETE_FILE = "plan-complete";


    /** Each worker's list is {@code loaded-<worker>.txt}. */
    public static final String LOADED_WORKER_PREFIX = "loaded-";

    public static final String LOADED_WORKER_SUFFIX = ".txt";

    /**
     * Ends {@code loaded-<worker>.complete}, written when the test plan finished normally. A union is
     * evidence only if every run it came from finished, so the decoder counts these, not the lists.
     */
    public static final String COMPLETE_SUFFIX = ".complete";


    /**
     * The map's format version. Bumped only when a stale record could stay {@code SUCCESSFUL} while
     * being wrong, since a never-selected record is never re-observed; a bump discards every cached
     * map.
     */
    public static final int MAP_SCHEMA_VERSION = 7;

    /**
     * The raw record format's version. Bump it whenever the index columns, the record-id shapes or
     * the outcome values change.
     */
    public static final int RAW_SCHEMA_VERSION = 3;

    /** The decision record's version. Bumped when the meaning of a field changes, never when one is added. */
    public static final String DECISIONS_VERSION = "2";


    /**
     * Coverage from JVM and test-plan startup, which no test and no class owns. Running any test
     * re-executes it, so nothing narrower than the whole suite is safe when a change touches it.
     */
    public static final String UNATTRIBUTED_RECORD_ID = "[yoriwake:unattributed]";

    /**
     * Prefix for coverage owned by test classes rather than one test -- the window between two
     * tests, holding {@code @AfterAll}, {@code @BeforeAll} and construction. Followed by one or more
     * class unique-id prefixes joined by {@code |}.
     */
    public static final String CLASS_SCOPED_RECORD_PREFIX = "[yoriwake:class]";

    public static final String OUTCOME_SUCCESSFUL = "SUCCESSFUL";

    /** A window that is not a test and therefore has no result. */
    public static final String OUTCOME_NOT_A_TEST = "NONE";

    /** The platform reported no result, or the decoder found no in-scope class for a test. */
    public static final String OUTCOME_UNKNOWN = "UNKNOWN";

    /** A test the platform skipped. Recorded, so a re-enabled test is still selectable. */
    public static final String OUTCOME_SKIPPED = "SKIPPED";

    /**
     * A test that both passed and failed in one capture, as a retried test does when its retry
     * round's result differs from its first. Written by the decoder only, never by the agent; like
     * any outcome but {@link #OUTCOME_SUCCESSFUL}, it runs the test whatever changed.
     */
    public static final String OUTCOME_FLAKY = "FLAKY";


    /**
     * Marks a note about the run as a whole: {@code #!key \t value}. A plain {@code #} would be
     * indistinguishable from the header, and a reader skipping comments would skip these too.
     */
    public static final String NOTE_PREFIX = "#!";

    /** The record's own version, so a reader can tell a token this jar predates from one not produced. */
    public static final String VERSION_NOTE = "record-version";

    /** The rows the writer intended to write, so a reader can tell a file whose tail was lost. */
    public static final String ROWS_NOTE = "rows";

    /**
     * Which writer's answer survived, as {@code <n> of <m>}. The file stays last-writer-wins; this
     * makes the survivor say it holds one discovery request's answer rather than the task's.
     */
    public static final String WRITER_NOTE = "writer";

    /** One of the {@code RUN_*} values. */
    public static final String OUTCOME_NOTE = "outcome";

    /** The coarse token saying which side forced, when the outcome is {@link #RUN_FULL}. */
    public static final String FULL_RUN_KIND_NOTE = "full-run-kind";

    public static final String FULL_RUN_REASON_NOTE = "full-run-reason";

    /** The daemon's own refusal token, copied from {@link #REFUSED_KIND_PROPERTY}. */
    public static final String REFUSAL_KIND_NOTE = "refusal-kind";

    /** Every decline that held, copied from {@link #DECLINES_PROPERTY}. */
    public static final String DECLINES_NOTE = "declines";

    /**
     * What a selecting run of the same inputs would have noted as its outcome, on an observing run:
     * {@code outcome [\t full-run-kind [\t refusal-kind]]}. The {@link #OUTCOME_NOTE} says what ran.
     */
    public static final String OBSERVED_OUTCOME_NOTE = "observed-outcome";

    /** Prefixes each recorded input property, {@code input.<property>}, to re-decide offline. */
    public static final String INPUT_NOTE_PREFIX = "input.";

    public static final String RUN_NARROWED = "narrowed";

    public static final String RUN_FULL = "full-run";

    public static final String RUN_NOT_REQUESTED = "selection-not-requested";

    /** Discovery never asked, so there is no decision to describe. */
    public static final String RUN_NOT_DECIDED = "not-decided";


    /**
     * Starts a rule line, {@code #+test \t rule,rule}: every rule that would run that test,
     * whichever one decided it. One per row, after the rows, and only when {@link #RULES_NOTE} is
     * {@link #RULES_COMPLETE} or {@link #RULES_FROM_REFUSED_INPUTS}. A {@code #} line, so a reader
     * of the rows alone never sees one.
     */
    public static final String RULES_LINE_PREFIX = "#+";

    /**
     * Starts an observation line, {@code #?test \t verdict \t reason}: the row a selecting run would
     * have written for that test, on an observing run, whose own row says it ran. One per row, after
     * the rows and the rule lines.
     */
    public static final String OBSERVATION_LINE_PREFIX = "#?";

    /**
     * Starts a ran line, {@code #=test \t outcome}: an included row whose test finished
     * {@code SUCCESSFUL} or {@code FAILED} in the outermost test plan of this JVM. One per such row,
     * after the observation lines.
     */
    public static final String RAN_LINE_PREFIX = "#=";

    /** The run's {@link #RUN_TOKEN_PROPERTY}, noted only when the run was given one. */
    public static final String RUN_TOKEN_NOTE = "run-token";

    /**
     * Prefixes each of the test JVM's {@link #JVM_IDENTITY_PROPERTIES}, {@code jvm.<property>}, noted
     * beside {@link #RUN_TOKEN_NOTE}.
     */
    public static final String JVM_NOTE_PREFIX = "jvm.";

    /** The test JVM's system properties that say which runtime and platform it is, comma-separated. */
    public static final String JVM_IDENTITY_PROPERTIES = "java.version,java.vendor,java.vm.name,os.name,os.arch";

    /** What the rule lines were computed from: one of the {@code RULES_*} values. */
    public static final String RULES_NOTE = "rules";

    /**
     * Every full-run kind whose condition held, comma-separated in the order the selector checks
     * them, or {@link #RULE_NONE}. {@link #FULL_RUN_KIND_NOTE} names only the first.
     */
    public static final String FORCING_KINDS_NOTE = "forcing-kinds";

    /** The rules were computed from every input the selector reads. */
    public static final String RULES_COMPLETE = "complete";

    /**
     * The daemon refused after it had a change set, and the rules were computed from what it handed
     * over. A refusal the inline scan or the digest rule raised hands over the changed classes
     * without inline consumers and without changed bytes.
     */
    public static final String RULES_FROM_REFUSED_INPUTS = "from-refused-inputs";

    /** The daemon refused before it had a change set, so no rule could be computed. */
    public static final String RULES_NO_CHANGE_SET = "no-change-set";

    /** There was no usable map, so no rule could be computed. */
    public static final String RULES_NO_MAP = "no-map";

    /** Computing the rules threw. What ran is unaffected: the rules are computed after the decision. */
    public static final String RULES_FAILED = "failed";

    /** A rule line, or {@link #FORCING_KINDS_NOTE}, naming nothing. */
    public static final String RULE_NONE = "none";

    /** A rule line whose rules could not be computed for that one test. */
    public static final String RULE_UNKNOWN = "unknown";

    /** The test's own coverage holds a changed class. */
    public static final String RULE_REACHES_CHANGE = "reaches-change";

    /** Its own test class changed, and the map holds it as a test of that class. */
    public static final String RULE_OWN_CLASS_CHANGED = "own-class-changed";

    /** The test's own coverage holds a class whose bytes changed with no changed source behind them. */
    public static final String RULE_CHANGED_BYTES = "changed-bytes";

    /** A class-scoped record of the test's class (the window between two tests) reaches the change. */
    public static final String RULE_CLASS_SETUP = "class-setup";

    /** The test ran at or after its JVM first touched a changed class or its file. */
    public static final String RULE_SHARES_JVM_CHANGED_CLASS = "shares-jvm-changed-class";

    /** The test ran at or after its JVM first touched a class whose bytes changed. */
    public static final String RULE_SHARES_JVM_CHANGED_BYTES = "shares-jvm-changed-bytes";

    /**
     * The test ran at or after the point from which its JVM's touches were not all observed
     * ({@link #FIRST_TOUCH_ANY}): a child process, a native library off the reviewed list, a hook
     * missing. Which one is in that worker's raw {@link #TOUCHES_FILE}, not in the map.
     */
    public static final String RULE_SHARES_JVM_UNOBSERVED = "shares-jvm-unobserved";

    /** The map holds no position for the test, so the first-touch rule cannot date it. */
    public static final String RULE_SHARES_JVM_UNPOSITIONED = "shares-jvm-unpositioned";

    /** The map does not record the test as having passed. */
    public static final String RULE_NOT_KNOWN_TO_PASS = "not-known-to-pass";

    /** The map has never seen the test. */
    public static final String RULE_NOT_IN_MAP = "not-in-map";

    /** Pinned by pattern or tag. */
    public static final String RULE_ALWAYS_RUN = "always-run";

    /**
     * Its engine runs every test it discovered or none, so selection never leaves one out. Also the
     * console tally's spelling for such tests.
     */
    public static final String RULE_ENGINE_RUNS_EVERYTHING = "engine-runs-everything";

    /** Rounded up to its class because another test there is selected. */
    public static final String RULE_CLASS_GRANULARITY = "class-granularity";
}
