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

    /**
     * The root project's directory. A capturing JVM records the non-class files it reads beneath it,
     * and only those: the build-produced files a selecting run compares lie there.
     */
    public static final String CAPTURE_ROOT_PROPERTY = "yoriwake.internal.capture.root";

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

    /**
     * Names the framework outside the JUnit Platform whose test classes a selecting run may leave out
     * whole: {@link #NARROW_PLAIN_JUNIT4}. Unset, such a framework's tests all run.
     */
    public static final String NARROW_PLAIN_RUNNERS_PROPERTY = "yoriwake.plainRunners.narrow";

    /** {@link #NARROW_PLAIN_RUNNERS_PROPERTY}'s value for plain JUnit 4. */
    public static final String NARROW_PLAIN_JUNIT4 = "junit4";

    /** The changed classes, comma-separated. */
    public static final String CHANGED_CLASSES_PROPERTY = "yoriwake.change.classes";

    /** Changed paths no class could be derived from, comma-separated. Any one forces. */
    public static final String UNMAPPABLE_PATHS_PROPERTY = "yoriwake.change.unmappable";

    /**
     * Every changed path relative to the root project, before any is dropped as off the classpath
     * or declared unread, comma-separated. Only matched against tests' declared dependencies
     * ({@code @Tag("yoriwake-depends-on:<glob>")}), each of which it can only add.
     */
    public static final String CHANGED_PATHS_PROPERTY = "yoriwake.change.paths";

    /** The test task's project directory relative to the root project, {@code ""} for the root. */
    public static final String PROJECT_DIR_PROPERTY = "yoriwake.project.dir";

    /**
     * Set when every changed path was accounted for: off this task's classpath, or unreadable.
     * Without it an empty change set is indistinguishable from git having nothing to say.
     */
    public static final String ACCOUNTED_PROPERTY = "yoriwake.change.accountedFor";

    /** Changed classes whose absence from the map the daemon could prove means untested. */
    public static final String ABSENCE_PROVABLE_PROPERTY = "yoriwake.change.absenceProvable";

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
     * Build-produced non-class files on the classpath whose content differs from the capture's,
     * by their {@link #RESOURCE_TOUCH_FILE} path, comma-separated. Each selects the tests from its
     * first read on in every JVM that read it; a JVM whose reads were not recorded runs whole.
     */
    public static final String MOVED_FILES_PROPERTY = "yoriwake.change.movedFiles";

    /**
     * Changed paths the build's {@code pathIgnore} took out of the change set, comma-separated, each
     * as {@code <path> \t <entry>} with {@code %} and {@code ,} written as {@code %25} and {@code %2C}.
     * Each runs the tests from its first read on in every JVM whose reads the map recorded.
     */
    public static final String IGNORED_PATHS_PROPERTY = "yoriwake.change.ignored";

    /**
     * The file holding every list-valued change-set property, {@link #CHANGE_SET_FILE} in the map
     * directory. A change set can name tens of thousands of paths, more than one command line
     * carries, so those properties are never passed to the test JVM directly. Set means the file
     * alone answers for them; a file that cannot be read refuses as {@link
     * #CHANGE_SET_UNREADABLE_KIND}.
     */
    public static final String CHANGE_SET_FILE_PROPERTY = "yoriwake.change.file";

    /**
     * Which build forked this JVM: {@link #HOST_MAVEN}, or Gradle when absent. Read once, when the
     * agent starts, so a property a test runner applies later cannot change it.
     */
    public static final String HOST_PROPERTY = "yoriwake.host";

    /** The host name of Maven, whose contract properties come from {@link #RUN_FILE_PROPERTY}. */
    public static final String HOST_MAVEN = "maven";

    /**
     * Under Maven, the run file holding every contract property in {@code java.util.Properties}
     * form and ending with {@link #RUN_FILE_END}. One that cannot be read whole refuses the run.
     */
    public static final String RUN_FILE_PROPERTY = "yoriwake.run.file";

    /** The run file's name, beside the map. */
    public static final String RUN_FILE = "run.properties";

    /** The run file's last line, so a file cut short is not read as a smaller run. */
    public static final String RUN_FILE_END = "#end";

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
     * The system properties {@code systemPropertyIgnore} left out of this selecting run's
     * environment comparison, comma-separated. Set only when it left one out.
     */
    public static final String FINGERPRINT_IGNORED_PROPERTY = "yoriwake.fingerprint.ignored";

    /**
     * A fresh value per run of a selecting test task. Each decision record carries it, so the decode
     * merges only the records this run wrote.
     */
    public static final String RUN_TOKEN_PROPERTY = "yoriwake.run.token";

    /**
     * The selection record a complement run reads, {@link #COMPLEMENT_RECORD_FILE} beside the map. Set
     * only once the plugin found the record's stamp equal to this run's; the test JVM then leaves out
     * each test it lists as ran, unless its own identity differs from the record's.
     */
    public static final String COMPLEMENT_RECORD_PROPERTY = "yoriwake.complement.record";

    /** Test-id globs selection may never skip, comma-separated. */
    public static final String ALWAYS_RUN_PROPERTY = "yoriwake.alwaysRun";

    /**
     * The refusal kind of a run whose change-set file could not be written or read. Defined here
     * because the agent raises it too.
     */
    public static final String CHANGE_SET_UNREADABLE_KIND = "change-set-unreadable";

    /**
     * The refusal and full-run kind of a test JVM newer than the agent's ASM reads. Defined here
     * because the daemon refuses it from the launcher and the agent from the JVM it runs in.
     */
    public static final String TEST_JVM_UNSUPPORTED_KIND = "test-jvm-unsupported";

    /**
     * Lowers the class-file version the agent takes its ASM to read, for tests that need a test JVM
     * above it on an installed JDK. Never raises it.
     */
    public static final String AGENT_ASM_CEILING_PROPERTY = "yoriwake.internal.agentAsmCeiling";

    /**
     * The refusal kind of a complement run whose selection record does not match it. Defined here
     * because the agent raises it too, when the record's test JVM is not this one.
     */
    public static final String COMPLEMENT_RECORD_MISMATCH_KIND = "complement-record-mismatch";


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

    /**
     * The commit the map was last captured at. Absent means "unknown", never "current". Read by
     * the GitHub Action to check that a restored map's history is on the base branch.
     */
    public static final String CAPTURE_COMMIT_FILE = "capture-commit";

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

    /**
     * A complement run's copy of the selection record it validated, beside the map and outside
     * {@link #RAW_DIR}, which {@link #COMPLEMENT_RECORD_PROPERTY} names. Removed by its decode.
     */
    public static final String COMPLEMENT_RECORD_FILE = "complement.record";

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

    /**
     * {@code jvm \t sequence \t kind \t path}: the first record sequence at or before which each
     * JVM read each non-class file beneath the root, the path relative to the root project as the
     * classpath-file digests name it. Kind {@link #RESOURCE_READ} is a read by anything but the
     * JDK's class loader, {@link #RESOURCE_LOADER} one by it; {@link #FIRST_TOUCH_ANY} as the path
     * stands for every file. Every JVM whose reads were recorded has a {@link #RESOURCE_RECORDED}
     * row; one without reads everything from its first record.
     */
    public static final String RESOURCE_TOUCH_FILE = "resource-touch.tsv";

    /** A {@link #RESOURCE_TOUCH_FILE} kind: read by anything but the JDK's class loader. */
    public static final String RESOURCE_READ = "read";

    /** A {@link #RESOURCE_TOUCH_FILE} kind: a manifest read by the JDK's class loader. */
    public static final String RESOURCE_LOADER = "loader";

    /**
     * A {@link #RESOURCE_TOUCH_FILE} kind, with sequence 0: the JVM's reads were recorded. Its path
     * is {@link #RESOURCE_RECORDED_SOURCES} when source files it opened are among them, and empty
     * in a map recorded before they were.
     */
    public static final String RESOURCE_RECORDED = "recorded";

    /** The path of a {@link #RESOURCE_RECORDED} row whose JVM's reads include the source files it opened. */
    public static final String RESOURCE_RECORDED_SOURCES = "sources";

    /** A first-touch class meaning "every class": the JVM's loads and reads were not all observed. */
    public static final String FIRST_TOUCH_ANY = "*";

    /**
     * {@code jvm \t mode}: how each JVM of the map was recorded. A JVM with no row, or a map with no
     * file, reads as {@link #MODE_SHARED}. Selection does not read it: which tests shared a JVM is
     * already in {@link #POSITIONS_FILE}. A selecting run that falls back to a full run does, and
     * does not capture over a map any JVM of which is {@link #MODE_ISOLATED}.
     */
    public static final String JVM_MODE_FILE = "jvm-mode.tsv";

    /**
     * {@code jvm \t fingerprint-id}: the environment each JVM of the map was recorded in, named by
     * its block in {@link #ENVIRONMENT_FINGERPRINTS_FILE}. A JVM that ran a test and has no row
     * makes a selecting run run everything. Only the plugin reads it.
     */
    public static final String ENVIRONMENT_FILE = "environment.tsv";

    /**
     * {@code fingerprint-id \t component \t value}, one block per distinct environment: the JDK, OS
     * and architecture in plain text, each system property ({@code sys:<name>}) and environment
     * variable ({@code env:<name>}) as a salted digest of its value.
     */
    public static final String ENVIRONMENT_FINGERPRINTS_FILE = "environment-fingerprints.tsv";

    /** The JVM was forked for one test class and ran tests of that class alone. */
    public static final String MODE_ISOLATED = "isolated";

    /** Any other JVM. */
    public static final String MODE_SHARED = "shared";

    /** The raw records directory, beneath the map; {@link #RECORDS_DIR_PROPERTY} points at it. */
    public static final String RAW_DIR = "raw";


    /** One directory per test JVM, {@code worker-<id>}, so two workers never share a sequence. */
    public static final String WORKER_DIR_PREFIX = "worker-";

    /**
     * Before the plan's number in a record directory for a JVM's second and later test plans,
     * {@code worker-<id>-p<n>}: Surefire can run several plans in one JVM, and each plan's records
     * are complete or not on their own.
     */
    public static final String PLAN_DIR_SUFFIX = "-p";

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

    /**
     * A touch kind: a file that is not a class file was opened beneath the {@link
     * #CAPTURE_ROOT_PROPERTY}, an archive read whole included; its absolute path, or a jar entry
     * as {@code <jar>!/<entry>}. One read may give several rows, one per name it went by, or, for a
     * read the agent could not place, its path as given.
     */
    public static final String TOUCH_RESOURCE = "resource";

    /**
     * A touch kind: the JDK's own class loader read a jar's manifest, to open the jar or to define a
     * package from it; the value as {@link #TOUCH_RESOURCE}'s. What such a read takes from the
     * manifest is fixed, so it reaches a test only through the attributes the loader exposes.
     */
    public static final String TOUCH_LOADER_MANIFEST = "loader-manifest";

    /**
     * A touch kind: this JVM records {@link #TOUCH_RESOURCE} and {@link #TOUCH_LOADER_MANIFEST}
     * reads beneath the root it names. Without it, which files the JVM read is unknown.
     */
    public static final String TOUCH_RESOURCES_RECORDED = "resources-recorded";

    /** A touch kind: a jar was opened by something other than the JDK's own zip code; its path. */
    public static final String TOUCH_JAR = "jar";

    /**
     * A touch kind: from here on not everything was observed (a hook missing, a child process
     * started, native code outside the reviewed libraries, a class defined from bytes whose name
     * could not be read), so every class counts as touched; why.
     */
    public static final String TOUCH_ALL = "all";

    /**
     * The environment variables the test JVM read, one name per line, escaped as {@link Tsv} escapes
     * a field; {@link #ENV_READ_WHOLE} for a read of the whole environment. Names only, never a
     * value. Written per worker when every read was observed; the decode writes the union beside the
     * map under the same name when every worker wrote one.
     */
    public static final String ENV_READS_FILE = "env-reads.tsv";

    /** A line of {@link #ENV_READS_FILE} meaning a test read the whole environment. */
    public static final String ENV_READ_WHOLE = "*";

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

    /**
     * The tests still open when a worker's run ended without finishing, one id per line, escaped
     * as {@link Tsv} escapes: what was running when its JVM exited. Absent when none was open.
     */
    public static final String OPEN_TESTS_FILE = "open-tests";


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

    /** The properties left out of the environment comparison, copied from {@link #FINGERPRINT_IGNORED_PROPERTY}. */
    public static final String FINGERPRINT_IGNORED_NOTE = "fingerprint-ignored";

    /** How many changed paths {@code pathIgnore} took out, from {@link #IGNORED_PATHS_PROPERTY}. */
    public static final String PATHS_IGNORED_NOTE = "paths-ignored";

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

    /** Outcome: a complement run left out the tests a selecting run's record lists as ran. */
    public static final String RUN_COMPLEMENTED = "complemented";

    /** On a complement run: {@code leftOut \t ran}, the tests this writer left out and included. */
    public static final String COMPLEMENT_NOTE = "complement";

    /**
     * On a run that narrows a plain framework: whether its executor took the hook that leaves a
     * class out, as {@link #PLAIN_FILTER_HOOKED}, {@link #PLAIN_FILTER_UNHOOKED} or
     * {@link #PLAIN_FILTER_UNSUPPORTED}.
     */
    public static final String PLAIN_FILTER_NOTE = "plain-filter";

    /** The executor took the hook. */
    public static final String PLAIN_FILTER_HOOKED = "hooked";

    /** The executor's shape was not one the hook knows, so every class ran. */
    public static final String PLAIN_FILTER_UNHOOKED = "unhooked";

    /** The hook could not reach the judgement, or the judgement threw, so every class ran. */
    public static final String PLAIN_FILTER_UNSUPPORTED = "unsupported";

    /**
     * {@code <token>=<count>}, comma-separated: how many classes ran whole because of a runner or
     * shape selection does not judge, by the closed tokens below.
     */
    public static final String CLASS_RUNS_WHOLE_NOTE = "class-runs-whole";

    /** A JUnit 4 class whose runner is not on the allow-list. */
    public static final String CLASS_RUNS_WHOLE_JUNIT4_RUNNER = "junit4-runner";

    /** A JUnit 4 class whose tests' descriptions name another class, no method, or none at all. */
    public static final String CLASS_RUNS_WHOLE_JUNIT4_SHAPE = "junit4-shape";

    /** A JUnit 4 class whose runner cannot be filtered. */
    public static final String CLASS_RUNS_WHOLE_JUNIT4_NOT_FILTERABLE = "junit4-not-filterable";

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
     * The test ran at or after its JVM first read a build-produced classpath file that moved since
     * the capture, or in a JVM whose reads of such files were not recorded.
     */
    public static final String RULE_SHARES_JVM_MOVED_CLASSPATH_FILE = "shares-jvm-moved-classpath-file";

    /**
     * The test ran at or after its JVM first read a changed path {@code pathIgnore} declares no test
     * reads, a directory above it, or every file.
     */
    public static final String RULE_SHARES_JVM_IGNORED_PATH = "shares-jvm-ignored-path";

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

    /** Its {@code yoriwake-depends-on:} tag matches a changed path. */
    public static final String RULE_DECLARES_CHANGED_PATH = "declares-changed-path";

    /**
     * Its engine runs every test it discovered or none, so selection never leaves one out. Also the
     * console tally's spelling for such tests.
     */
    public static final String RULE_ENGINE_RUNS_EVERYTHING = "engine-runs-everything";

    /** Rounded up to its class because another test there is selected. */
    public static final String RULE_CLASS_GRANULARITY = "class-granularity";

    /** Its class uses a runner or construct selection does not leave out, so the whole class runs. */
    public static final String RULE_CLASS_RUNS_WHOLE = "class-runs-whole";
}
