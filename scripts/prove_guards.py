"""Reintroduces each defect the guards should catch, and reports whether they fire.

A guard that has only ever passed has not been shown to detect anything, and this project has
shipped a green test that could not fail more than once. This is what turns a green suite into a
suite known to go red.

Each guard is an entry in MUTATIONS: a literal fragment of a source, the defect to put in its
place, the suite to run, and the test that must be the one to fail. Every source is restored from
an in-memory copy and checked byte for byte afterwards.

    python3 scripts/prove_guards.py --mode fast           # the cheap suites, a few minutes
    python3 scripts/prove_guards.py                       # every guard, the gate for plugin/ and agent/
    python3 scripts/prove_guards.py --guard LABEL         # one guard, by its label
    python3 scripts/prove_guards.py --changed origin/main # the guards a change can affect
    python3 scripts/prove_guards.py --against origin/main # name guards a change removes or re-aims
    python3 scripts/prove_guards.py --shard 3/8           # the guards CI's shard 3 of 8 runs

Run from the repository root. Paths in MUTATIONS are relative to it. By default the guards run in
worker worktrees under `sandbox/prove-guards/`, and this tree is never written.

**DO NOT EDIT A MUTATED SOURCE WHILE THIS IS RUNNING WITH `--jobs 1`.** That mode mutates this tree
in place, and the restore writes back the whole in-memory `original`, so an edit made to a mutated
file mid-run is DISCARDED -- and silently, because the byte-identical check compares against the
pre-mutation digest and still passes. Only an edit that destroys a mutation's anchor surfaces at
all, as `MUTATION DID NOT APPLY`. A run whose tree changed underneath it also proves nothing about
the tree you end up with, so the honest recovery is to stop it, not to read its report.

**Two modes, because a harness nobody runs proves nothing.** `--mode full` is everything and is the
gate before a change to `plugin/` or `agent/`. `--mode fast` runs the suites cheap enough to sit in
front of an ordinary commit, and says how many guards it did NOT attempt -- a run that proved a
third of them must not read like a run that proved all of them. The split is by measured cost, not
by guess; see SUITES. Count what a run will attempt from its first line, not from a duration
written anywhere: the table grows, and every written duration has gone stale.
"""
import argparse, atexit, contextlib, glob, itertools, json, os, re, shutil, signal, subprocess, sys, hashlib, math, time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                                "bench"))
import checkout as yoriwake_checkout  # noqa: E402  (the one place the tree is resolved)

# Every source this script reads or writes is UTF-8, stated rather than inherited. The Kotlin
# sources carry em-dashes; under a non-UTF-8 default locale the first read of one aborts the run
# before any mutation applies, which diagnoses nothing and looks like a broken harness.
ENCODING = "utf-8"


def read_source(path):
    with open(path, encoding=ENCODING) as handle:
        return handle.read()


def write_source(path, text):
    with open(path, "w", encoding=ENCODING) as handle:
        handle.write(text)


def source(path):
    """Where a mutation's repository-relative path is on disk, in the tree under test.

    Resolved at call time rather than import, because each worker points the setting at its own
    tree after it starts.
    """
    return os.path.join(yoriwake_checkout.root(), path)


# Guards prove test outcomes, and the functional suite's coverage would only add time; the same
# arguments everywhere, so the warm-up's configuration cache entry is the one the runs reuse.
# A mutation may leave the compiler something to warn about; it is judged on its test, not refused.
GRADLE_ARGS = ["-Pyoriwake.functional.coverage=false", "-Pyoriwake.kotlinWarningsAsErrors=false"]


def gradle(args):
    """`./gradlew` in the tree under test."""
    return subprocess.run(["./gradlew"] + GRADLE_ARGS + args, capture_output=True, text=True,
                          cwd=yoriwake_checkout.root())


def _restore_on_signal(path, original):
    """Puts `path` back if this process is interrupted. Returns a callable that disarms it.

    The `finally` around each mutation is enough for an exception and useless for a kill: SIGINT
    and SIGTERM do not run it, and neither does a `kill -9` (nothing can help there). What survives
    is a mutated source that looks like a hand edit. Registering the undo BEFORE the write is the
    half that matters -- armed after it, the window this exists to close is still open.
    """
    def put_back(*_):
        try:
            write_source(path, original)
        except Exception:
            pass
        raise SystemExit(130)

    previous = {}
    for received in (signal.SIGINT, signal.SIGTERM):
        try:
            previous[received] = signal.signal(received, put_back)
        except (ValueError, OSError):
            # Not the main thread, or a platform without it. The atexit hook below still stands.
            pass
    at_exit = lambda: write_source(path, original)  # noqa: E731
    atexit.register(at_exit)

    def disarm():
        for received, handler in previous.items():
            try:
                signal.signal(received, handler)
            except (ValueError, OSError):
                pass
        try:
            atexit.unregister(at_exit)
        except Exception:
            pass
    return disarm

PY_SRC = "bench/run.py"
SWEEP_SRC = "bench/sweep_corpus.py"
CORPUS_SRC = "bench/corpus_state.py"
CONTROLS_SRC = "bench/controls.py"
COST_SRC = "bench/cost.py"
SELECTION_FILTER = "agent/src/main/java/io/github/zeuspizza/yoriwake/agent/platform/SelectionFilter.java"
SELECTION_RUN = "agent/src/main/java/io/github/zeuspizza/yoriwake/agent/select/SelectionRun.java"
CLASS_VERDICTS = "agent/src/main/java/io/github/zeuspizza/yoriwake/agent/select/ClassVerdicts.java"
JUNIT4_SELECTION = "agent/src/main/java/io/github/zeuspizza/yoriwake/agent/engines/JUnit4Selection.java"
LOADED_CLASS_RECORDER = "agent/src/main/java/io/github/zeuspizza/yoriwake/agent/capture/LoadedClassRecorder.java"
SELECTOR = "agent/src/main/java/io/github/zeuspizza/yoriwake/agent/select/Selector.java"
CHANGE_DETECTION = "core/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/change/ChangeDetection.kt"
DECISION_RECORD = "agent/src/main/java/io/github/zeuspizza/yoriwake/agent/platform/DecisionRecord.java"
FILTER_RULE = "agent/src/main/java/io/github/zeuspizza/yoriwake/agent/select/FilterRule.java"
RULES = "agent/src/main/java/io/github/zeuspizza/yoriwake/agent/select/Rules.java"
REPLAY_SRC = "bench/replay_decisions.py"
BUILD_MEMO = "plugin/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/facts/BuildMemo.kt"
YORIWAKE_PLUGIN = "plugin/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/YoriwakePlugin.kt"
TEST_TASK_WIRING = "plugin/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/wiring/TestTaskWiring.kt"
JACOCO_SCOPING = "plugin/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/wiring/JacocoScoping.kt"
AUDIT_TASK = "plugin/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/tasks/AuditTask.kt"
DECODE_TASK = "plugin/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/tasks/DecodeTask.kt"
DECODE_RUN = "core/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/capture/DecodeRun.kt"
AGENT_CONTRACT = "agent/src/main/java/io/github/zeuspizza/yoriwake/agent/contract/AgentContract.java"
# ChangeSelection.kt was split by responsibility; each anchor followed its function, unchanged.
SELECTION = "core/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/change/Selection.kt"
CLASSPATH_SCOPE = "core/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/change/ClasspathScope.kt"
NOT_READ = "core/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/change/NotRead.kt"
PATH_IGNORE = "core/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/change/PathIgnore.kt"
SELECTION_WIRING = "plugin/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/wiring/SelectionWiring.kt"
SELECTING_STEP = "core/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/change/SelectingStep.kt"
SELECTION_SETUP = "core/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/change/SelectionSetup.kt"
PATH_GLOB = "agent/src/main/java/io/github/zeuspizza/yoriwake/agent/select/PathGlob.java"
LAST_LOOK = "plugin/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/wiring/LastLook.kt"
CLASSPATH_FILES = "core/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/change/ClasspathFiles.kt"
WORKING_TREE = "core/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/change/WorkingTree.kt"
WORKING_TREE_GRADLE = "plugin/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/change/WorkingTreeGradle.kt"
PATH_IGNORE_VALIDATION = "plugin/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/change/PathIgnoreValidation.kt"
MAP_PROVENANCE = "core/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/capture/MapProvenance.kt"
TRACKED_TEXT = "core/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/change/TrackedText.kt"
DEVELOCITY_DETECTION = "plugin/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/wiring/DevelocityDetection.kt"
FORCING_PATHS = "core/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/change/ForcingPaths.kt"
PROJECT_FACTS = "plugin/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/facts/ProjectFacts.kt"
CLASSPATH_FACTS = "plugin/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/facts/ClasspathFacts.kt"
COVERAGE_DECODER = "core/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/capture/CoverageDecoder.kt"
SETTINGS = "plugin/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/Settings.kt"
RUN_PLAN = "core/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/RunPlan.kt"
CAPTURE_SESSION = "agent/src/main/java/io/github/zeuspizza/yoriwake/agent/capture/CaptureSession.java"
JUNIT4_EVENTS = "agent/src/main/java/io/github/zeuspizza/yoriwake/agent/engines/JUnit4Events.java"
TESTNG_EVENTS = "agent/src/main/java/io/github/zeuspizza/yoriwake/agent/engines/TestNgEvents.java"
TOUCH_RECORDER = "agent/src/main/java/io/github/zeuspizza/yoriwake/agent/capture/TouchRecorder.java"
# Whether a class is the JDK's own, judged by the runtime image rather than by the loader alone.
JDK_CODE = "agent/src/main/java/io/github/zeuspizza/yoriwake/agent/capture/JdkCode.java"
READ_HOOKS = "agent/src/main/java/io/github/zeuspizza/yoriwake/agent/capture/ReadHooks.java"
REVIEWED_LIBRARIES = "agent/src/main/java/io/github/zeuspizza/yoriwake/agent/capture/ReviewedLibraries.java"
GUARD_HOOKS = "agent/src/main/java/io/github/zeuspizza/yoriwake/agent/capture/GuardHooks.java"
JAR_IDENTITY = "agent/src/main/java/io/github/zeuspizza/yoriwake/agent/capture/JarIdentity.java"
ATTACHED_LOADER = "agent/src/main/java/io/github/zeuspizza/yoriwake/agent/host/AttachedLoader.java"
CAPTURE_CLAIM = "agent/src/main/java/io/github/zeuspizza/yoriwake/agent/capture/CaptureClaim.java"
PLATFORM_EVENTS = "agent/src/main/java/io/github/zeuspizza/yoriwake/agent/engines/PlatformEvents.java"
PARALLEL_READ_BACK = "agent/src/main/java/io/github/zeuspizza/yoriwake/agent/engines/ParallelReadBack.java"
MAVEN_HOST = "agent/src/main/java/io/github/zeuspizza/yoriwake/agent/host/MavenHost.java"
MAVEN_DECLINES = "maven/src/main/kotlin/io/github/zeuspizza/yoriwake/maven/Declines.kt"
MAVEN_LISTENER = "maven/src/main/kotlin/io/github/zeuspizza/yoriwake/maven/ChainedListener.kt"
MAVEN_RECORDING = "maven/src/main/kotlin/io/github/zeuspizza/yoriwake/maven/Recording.kt"
RAN_TESTS = "agent/src/main/java/io/github/zeuspizza/yoriwake/agent/platform/RanTests.java"
SELECTION_RECORD_FILE = "agent/src/main/java/io/github/zeuspizza/yoriwake/agent/select/SelectionRecordFile.java"
SELECTION_RECORD = "core/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/capture/SelectionRecord.kt"
CAPTURE_DECISION = "core/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/capture/CaptureDecision.kt"
EXPLAIN_TASK = "plugin/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/tasks/ExplainTask.kt"
ENVIRONMENT_FINGERPRINT = "core/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/capture/EnvironmentFingerprint.kt"
JVM_ARG_FILES = "core/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/capture/JvmArgFiles.kt"
CLASS_FILE_CEILING = "core/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/change/ClassFileCeiling.kt"
SCHEDULE = "core/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/capture/Schedule.kt"
# The audit's computation, split from its types (Audit.kt) and its rendering. The anchors moved
# with it, four columns left: the functions were members of `object Audit` and are now top level.
AUDIT_COMPUTE = "core/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/report/AuditCompute.kt"
AUDIT = "core/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/report/Audit.kt"
# The payback engine moved out of Audit.kt; its mutation anchors moved with it, which is the
# check that the move changed no behaviour -- an anchor left behind refuses before any suite runs.
PAYBACK = "core/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/report/Payback.kt"
SCOPE = "core/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/bytecode/InstrumentationScope.kt"
CAFFEINE_PROJECT = "bench/projects/caffeine.toml"
QUALIFY_SRC = "bench/qualify.py"
CONFIG_COST = "bench/config_cost.py"
TASK_ARTIFACTS = "core/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/bytecode/TaskArtifacts.kt"
DIGEST_VISITOR = "core/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/bytecode/DigestVisitor.kt"
RECORDABILITY = "core/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/bytecode/Recordability.kt"
ABSENCE_EVIDENCE = "core/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/change/AbsenceEvidence.kt"
PY_ISOLATION = "bench/isolation.py"
TARGET_ORDERING = "bench/target_ordering.py"
MUTATIONS_SRC = "bench/mutations.py"
POOL_MUTATIONS = "bench/pool_mutations.py"
FORCING_MUTATIONS = "bench/forcing_mutations.py"
FAMILY_ORDER = "bench/family_order.py"
REPLAY_ORDER = "bench/replay_order.py"
SWEEP_DATA = "bench/sweep_data.py"
OWN_PROCESSES = "bench/own_processes.py"
PREFLIGHT = "bench/sweep_preflight.py"
README_CHARTS = "bench/readme_charts.py"
INIT_SCRIPT = "scripts/yoriwake.init.gradle.kts"
# The plugin's own build file. A dependency VERSION is part of the selector's behaviour here: ASM
# decides which class files can be read at all, and one corpus guest compiles to a version an older
# ASM refuses.
PLUGIN_BUILD = "plugin/build.gradle.kts"
# The user docs whose names, flags, tokens and printed lines the reference suite checks against the
# code.
REFERENCE_DOC = "docs/reference.md"
CONTRACT_DOC = "docs/contract.md"
# The two halves of the audit's split between resolving a classpath and scanning it.
CLASSPATH_REFUSAL = (
    "            InlineScan.Kind.CLASSPATH_UNRESOLVED,\n"
    "        )\n"
    "    },\n"
    ")")
AUDIT_CLASSPATH_BLOCKER = (
    "InlineScan.Kind.CLASSPATH_UNRESOLVED -> Blocker(\n"
    '                    Audit.BlockerKind.CLASSPATH_UNRESOLVED_HERE,')


def _python_checks(script):
    """A Python suite's failing checks, or its crash reported as one.

    A mutation that makes the module unimportable exits non-zero with an empty check list, which
    would read as "nothing caught it" -- the same confusion the Gradle runners guard against, in the
    other direction.
    """
    # `-B`, AND IT IS LOAD-BEARING RATHER THAN TIDY. CPython invalidates a cached `.pyc` on
    # (mtime, size), and two guards that replace the same fragment leave the source at the SAME
    # SIZE -- so when their writes land inside one mtime second, the second guard's subprocess
    # imports bytecode compiled from the FIRST guard's mutation. The guard then fires on a mutation
    # it did not make and reports a failure under a name that is not its own.
    #
    # Measured: the Kotlin-constant guard run alone reports its own failure three times
    # out of three, and run straight after the Java-constant guard reports the JAVA failure three
    # times out of three. Clearing `__pycache__` between them fixes it, which is what named the
    # cause. It reads as flakiness because whether the two writes share a second depends on the
    # machine, and a guard suite that is occasionally right about the wrong line is worse than one
    # that is reliably wrong.
    # AND THE CACHE THAT IS ALREADY THERE IS PURGED, because `-B` only stops this run WRITING
    # bytecode -- CPython still READS a `__pycache__` left by anything else under the same
    # (mtime, size) rule, which is the unreliable comparison this whole guard exists to defeat.
    # Stopping the writer alone would have left the defect reachable from any earlier plain run.
    script = source(script)
    for stale in glob.glob(os.path.join(os.path.dirname(script) or ".", "__pycache__")):
        shutil.rmtree(stale, ignore_errors=True)
    done = subprocess.run([sys.executable, "-B", script], capture_output=True, text=True,
                          env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"})
    failures = [l.strip() for l in done.stdout.splitlines() if l.startswith("  FAIL")]
    if failures:
        return failures
    if done.returncode != 0:
        lines = (done.stderr or done.stdout).strip().splitlines()
        # A suite that asked a dirty tree for its commit names the files that dirtied it, and they
        # are the only evidence of what wrote into a worker tree mid-run: kept whole, on one line,
        # because a guard's result keeps only its first entry and a report takes one line each.
        refusal = next((i for i, line in enumerate(lines) if line.startswith("REFUSING:")), None)
        if refusal is not None:
            paths = [line.strip() for line in
                     itertools.takewhile(lambda line: line.startswith("  "), lines[refusal + 1:])]
            return [f"NOT THE GUARD -- {script} did not run: {lines[refusal]} {'; '.join(paths)}"]
        return [f"NOT THE GUARD -- {script} did not run: {lines[-1][:70] if lines else '?'}"]
    return []


def python_suite():
    """The sweep harness's own checks, which report failures rather than exiting non-zero."""
    return _python_checks("bench/test_run.py")


def process_suite():
    """How the harness runs a build: what it files as killed, and what it leaves behind."""
    return _python_checks("bench/test_process.py")


def preflight_suite():
    """The sweep pre-flight's checks, and which Gradle processes the harness may call its own."""
    return _python_checks("bench/test_sweep_preflight.py")


def mutations_suite():
    """The mutation grammar's own checks. A generator that emits source the guest cannot compile
    costs a project every row in its file, and `assert_recall.py` exits 1 on one such row."""
    return _python_checks("bench/test_mutations.py")


def revert_suite():
    """The revert set's checks: eligibility, the reverse patches and their variants, the strata
    and the draw, over fixture repositories and javac."""
    return _python_checks("bench/test_revert.py")


def order_suite():
    """`pool/v1`'s draw order and per-family stops, and the learned order's replay, over fixture
    rows, results files and `git init`s in temporary directories."""
    return [line for script in ("bench/test_family_order.py", "bench/test_replay_order.py")
            for line in _python_checks(script)]


def controls_suite():
    """The sweep rules that run without a build: the planted-miss controls' verdict and the
    plant, and the cost projection and report."""
    return [line for script in ("bench/test_controls.py", "bench/test_cost.py")
            for line in _python_checks(script)]


def sharding_suite():
    """The CI sweep without a build: the merge's refusals and truncation, the slicing, and the
    tier a change asks for."""
    return [line for script in ("bench/test_merge.py", "bench/test_shard.py", "bench/test_tier.py")
            for line in _python_checks(script)]


def scan_suite():
    """The private-reference scan's checks: what a push or pull request publishes, by path and text."""
    return _python_checks("bench/test_scan_private.py")


def ci_suite():
    """The CI scripts' checks: the workflows' shapes and the rules their steps run."""
    return _python_checks(".github/test_ci.py")


def sweep_suite():
    """The corpus summary's classification gate, which runs without a corpus."""
    return _python_checks("bench/test_sweep_corpus.py")


def replay_suite():
    """The offline decision replay's checks, which run the agent jar through Replay.java.

    The jar is built HERE, from the tree the suite runs in: a jar left by some earlier build would
    answer for source that is not the source under test, in a worker copy most of all.
    """
    built = gradle(["-q", ":agent:shadowJar"])
    if built.returncode != 0:
        tail = (built.stderr or built.stdout).strip().splitlines()
        return [f"NOT THE GUARD -- the agent jar did not build: {tail[-1][:70] if tail else '?'}"]
    return _python_checks("bench/test_replay_decisions.py")


def corpus_suite():
    """The per-project verdict, which decides what the published figure is allowed to contain.

    It owns the staleness rule that says whether a results file still describes the selector in
    the tree -- the single check standing between a published number and rows measured by a
    different selector.
    """
    return _python_checks("bench/test_corpus_state.py")


def agent_suite():
    """The agent's own tests: the decision record, the selector, the capture lifecycle.

    Its own suite rather than folded into `unit`, because the agent is a separate module whose tests
    do not need the plugin compiled -- and because nothing in this harness pointed at it at all until
    a two-writer defect shipped to a real run.
    """
    done = gradle([":agent:test", "--rerun"])
    if done.returncode == 0:
        return []
    lines = (done.stdout + done.stderr).splitlines()
    named = [l.strip() for l in lines if "Test > " in l and "FAILED" in l]
    if not named:
        compile_errors = [l.strip() for l in lines if l.startswith("e: ")]
        return [f"NOT THE GUARD -- the mutation did not compile: {compile_errors[0]}"] \
            if compile_errors else ["the agent suite failed without naming a test"]
    return named


def jdk_identity_suite():
    """The agent's tests that need a JVM the ordinary agent tests cannot be: a patched and upgraded
    java.base, and a boot class path with java.lang opened.

    Its own suite because each task forks a JVM with its own arguments, which is what the judgment
    under test reads; the agent `test` task cannot run them.
    """
    done = gradle([":agent:jdkIdentityTest", ":agent:jdkNestTest", "--rerun", "--continue"])
    if done.returncode == 0:
        return []
    lines = (done.stdout + done.stderr).splitlines()
    named = [l.strip() for l in lines if "Test > " in l and "FAILED" in l]
    if not named:
        compile_errors = [l.strip() for l in lines if l.startswith("e: ")]
        return [f"NOT THE GUARD -- the mutation did not compile: {compile_errors[0]}"] \
            if compile_errors else ["the jdk identity suite failed without naming a test"]
    return named


def sbom_suite():
    """The release SBOM's check against the jars it describes. Gradle exits non-zero when it fires."""
    done = gradle([":plugin:checkSbom", "--rerun"])
    if done.returncode == 0:
        return []
    lines = (done.stdout + done.stderr).splitlines()
    failed = next((i for i, l in enumerate(lines) if "Execution failed for task ':plugin:checkSbom'" in l), None)
    if failed is not None:
        # The check's own message is the first "> " line after Gradle's "Execution failed" line: a
        # line before it can be a task or a "> Configure project" header over a compile warning.
        return [next((l.strip() for l in lines[failed + 1:] if l.startswith("> ")), "checkSbom failed")]
    compile_errors = [l.strip() for l in lines if l.startswith("e: ")]
    return [f"NOT THE GUARD -- the mutation did not compile: {compile_errors[0]}"] \
        if compile_errors else ["the SBOM suite failed somewhere other than checkSbom"]


def portal_suite():
    """The release's Portal upload, against a stand-in Portal: it must send the features the jar
    declares. `check_portal_upload.py` exits non-zero when it fires."""
    built = gradle([":plugin:publishAllPublicationsToBuildLocalRepository", "-PreleaseVersion=0.0.0-guard"])
    if built.returncode != 0:
        return ["the portal suite failed building the release repository"]
    done = subprocess.run([sys.executable, os.path.join("scripts", "check_portal_upload.py"), "0.0.0-guard"],
                          capture_output=True, text=True, cwd=yoriwake_checkout.root())
    if done.returncode == 0:
        return []
    return [next((l.strip() for l in done.stdout.splitlines() if l.startswith("FAIL")), "the portal check failed")]


def qualify_suite():
    """The qualification harness's checks, including the one that keeps a verdict in one place."""
    return _python_checks("bench/test_qualify.py")


def config_cost_suite():
    """The configure-cost instrument's own checks.

    Added after a review found that the instrument this project re-takes a published table with had
    no mutation aimed at it at all: reverting its attribution fix left every suite green.
    """
    return _python_checks("bench/test_config_cost.py")


def unit_suite():
    """The plugin's fast unit tests.

    Cheaper than the TestKit guards, and where the safety invariants that need no real build are
    pinned: what an unknown change set resolves, and what the audit reports about a git outage.
    """
    done = gradle(
        [":plugin:test",
         "--tests", "*ChangeSelectionTest*", "--tests", "*AuditTest*", "--tests", "*BuildMemoTest*",
         "--tests", "*InstrumentationScopeTest*",
         # A class missing from this filter proves nothing: its guards report "nothing caught it",
         # and are right. Widening it raises the cost of every guard on this suite, not only the
         # new ones, so the registry cost below is re-measured rather than adjusted.
         # The digest primitive and the walk that feeds it.
         "--tests", "*RecordabilityTest*", "--tests", "*TaskArtifactsTest*",
         # The regex that decides whether a changed path yields a class name at all -- the first
         # decision in the whole pipeline.
         "--tests", "*ChangeDetectionTest*",
         # The reader that decides whether a class inlined a changed source, the mechanism the
         # misses seen in real projects are shaped like.
         "--tests", "*InlinedSourcesTest*",
         # The working-tree listing, which decides what enters a change set beyond `git diff`: a
         # Kotlin session marker in it once forced every row of a project.
         "--tests", "*WorkingTreeTest*",
         # The JVM argument files a test JVM is started with, and the fingerprint they feed.
         "--tests", "*JvmArgFilesTest*", "--tests", "*EnvironmentFingerprintTest*",
         # Map provenance: the digest a trusted-map list is compared with, and the list of files it
         # covers.
         "--tests", "*MapProvenanceTest*", "--tests", "*MapFilesReadTest*",
         # The Develocity decline: what reads a task's Develocity switches.
         "--tests", "*DevelocityDetectionTest*",
         # The complement run: the selection record a complement run leaves tests out by, its
         # merge, its stamp and its removal.
         "--tests", "*SelectionRecordTest*",
         # The full-run marker: how commit messages are read for it.
         "--tests", "*CommitScanTest*",
         # What a forced run says about its untracked paths: one git call however many there are.
         "--tests", "*ForcingPathsTest*",
         # Which moved classpath files reach the selector, and which force the run.
         "--tests", "*ClasspathFilesTest*",
         # pathIgnore's grammar, checked against Gradle's own, and what it may never leave out.
         "--tests", "*PathIgnoreTest*",
         "--rerun"])
    if done.returncode == 0:
        return []
    lines = (done.stdout + done.stderr).splitlines()
    named = [l.strip() for l in lines if "Test > " in l and "FAILED" in l]
    if not named:
        compile_errors = [l.strip() for l in lines if l.startswith("e: ")]
        return [f"NOT THE GUARD -- the mutation did not compile: {compile_errors[0]}"] \
            if compile_errors else ["the unit suite failed without naming a test"]
    return named


# The functional suite runs on every supported Gradle, one task per version; the harness runs only
# the floor's. No guard here needs the 9.x leg -- each fires on the first version it runs on -- and
# the second leg would double the harness's most expensive suite. `-x` because the 9.x task
# finalizes `functionalTest`, and a `--tests` filter does not reach a finalizer.
FUNCTIONAL_FLOOR_ONLY = [":plugin:functionalTest", "-x", ":plugin:functionalTestGradle9"]
FUNCTIONAL_FILTER = "*FunctionalTest"
FUNCTIONAL_SOURCES = os.path.join("plugin", "src", "functionalTest")
_FUNCTIONAL_CLASSES = {}


def functional_class(expect, checkout):
    """The one class of `functional_suite`'s filter whose test names contain `expect`, else None.

    None runs the whole suite, so a renamed or moved test costs time, never a proof.
    """
    key = (expect, checkout)
    if key not in _FUNCTIONAL_CLASSES:
        found = set()
        if expect:
            pattern = os.path.join(checkout, FUNCTIONAL_SOURCES, "**", FUNCTIONAL_FILTER + ".kt")
            for path in glob.glob(pattern, recursive=True):
                with open(path, encoding=ENCODING) as f:
                    if any(expect in name for name in re.findall(r"fun `([^`]+)`", f.read())):
                        found.add(os.path.basename(path)[:-len(".kt")])
        _FUNCTIONAL_CLASSES[key] = found.pop() if len(found) == 1 else None
    return _FUNCTIONAL_CLASSES[key]


def _suite_run(suite, test_class):
    return suite(test_class) if test_class else suite()


def _class_of(suite, expect):
    """The class a guard's suite runs alone, or None for the whole suite."""
    return functional_class(expect, yoriwake_checkout.root()) if suite is functional_suite else None


def functional_suite(test_class=None):
    """The TestKit builds that drive a real Gradle build end to end, one class per feature.

    With `test_class`, that class alone: a guard fires only when the test it names fails, so the
    other classes cost minutes and decide nothing. `--rerun` forces the tests and lets Gradle's own
    up-to-date checks rebuild what a mutation or its restore touched; `--rerun-tasks` recompiled and
    re-published the plugin on every run.
    """
    done = gradle(FUNCTIONAL_FLOOR_ONLY + ["--tests", f"*.{test_class}" if test_class else FUNCTIONAL_FILTER,
                                           "--rerun"])
    if done.returncode == 0:
        return []
    lines = (done.stdout + done.stderr).splitlines()
    named = [l.strip() for l in lines if "Test > " in l and "FAILED" in l]
    if not named:
        compile_errors = [l.strip() for l in lines if l.startswith("e: ")]
        return [f"NOT THE GUARD -- the mutation did not compile: {compile_errors[0]}"] \
            if compile_errors else ["the functional suite failed without naming a test"]
    return named


def maven_suite():
    """Real Maven builds over the Maven plugin's fixtures: the declines, the capture and its forks."""
    done = gradle([":maven:functionalTest", "--tests", "*DeclineFunctionalTest*", "--tests", "*CaptureFunctionalTest*",
                   "--tests", "*ForkingFunctionalTest*", "--rerun"])
    if done.returncode == 0:
        return []
    lines = (done.stdout + done.stderr).splitlines()
    named = [l.strip() for l in lines if "Test > " in l and "FAILED" in l]
    if not named:
        compile_errors = [l.strip() for l in lines if l.startswith("e: ")]
        return [f"NOT THE GUARD -- the mutation did not compile: {compile_errors[0]}"] \
            if compile_errors else ["the Maven suite failed without naming a test"]
    return named


def decoder_suite():
    """The decoder's own tests, where a map that parses and means something else is caught."""
    done = gradle([":plugin:test", "--tests", "*CoverageDecoderTest*", "--rerun"])
    if done.returncode == 0:
        return []
    lines = (done.stdout + done.stderr).splitlines()
    named = [l.strip() for l in lines if "Test > " in l and "FAILED" in l]
    if not named:
        compile_errors = [l.strip() for l in lines if l.startswith("e: ")]
        return [f"NOT THE GUARD -- the mutation did not compile: {compile_errors[0]}"] \
            if compile_errors else ["the decoder suite failed without naming a test"]
    return named


def reference_suite():
    """The checks that the reference and the contract name what the code has: flags, the extension,
    published tokens, quoted names, files, tasks and printed lines."""
    done = gradle([":plugin:test", "--tests", "*ReferenceTest*", "--tests", "*PublishedTokensTest*",
                   "--rerun"])
    if done.returncode == 0:
        return []
    lines = (done.stdout + done.stderr).splitlines()
    named = [l.strip() for l in lines if "Test > " in l and "FAILED" in l]
    if not named:
        compile_errors = [l.strip() for l in lines if l.startswith("e: ")]
        return [f"NOT THE GUARD -- the mutation did not compile: {compile_errors[0]}"] \
            if compile_errors else ["the reference suite failed without naming a test"]
    return named


def guard_suite():
    """The configuration-work guards. Gradle exits non-zero when one fires."""
    done = gradle(FUNCTIONAL_FLOOR_ONLY + ["--tests", "*ConfigurationWorkGuardTest*", "--rerun"])
    if done.returncode == 0:
        return []
    lines = (done.stdout + done.stderr).splitlines()
    # The test name, not the stack: a compile error also exits non-zero, and reporting that as
    # "the guard fired" would be exactly the vacuous pass this script exists to catch.
    named = [l.strip() for l in lines if "ConfigurationWorkGuardTest >" in l and "FAILED" in l]
    if not named:
        compile_errors = [l.strip() for l in lines if l.startswith("e: ")]
        return [f"NOT THE GUARD -- the mutation did not compile: {compile_errors[0]}"] \
            if compile_errors else ["the guard suite failed without naming a test"]
    return named


BREAK_FILE = ("plugin/src/functionalTest/kotlin/io/github/zeuspizza/yoriwake/gradle/breaking/"
              "BreakAttempt.kt")
BREAK_FLOOR_ONLY = [":plugin:breakAttempts", "-x", ":plugin:breakAttemptsGradle9"]


def break_suite():
    """The break suite's self-test: one attempt of each outcome its helper tells apart.

    The attempts live in their own task, which `functionalTest` excludes, so no other suite runs
    them. Floor Gradle only, as for the functional suite, and `--rerun` for the same reason.
    """
    done = gradle(BREAK_FLOOR_ONLY + ["--tests", "*AttemptSelfTest*", "--rerun"])
    if done.returncode == 0:
        return []
    lines = (done.stdout + done.stderr).splitlines()
    named = [l.strip() for l in lines if "Test > " in l and "FAILED" in l]
    if not named:
        compile_errors = [l.strip() for l in lines if l.startswith("e: ")]
        return [f"NOT THE GUARD -- the mutation did not compile: {compile_errors[0]}"] \
            if compile_errors else ["the break suite failed without naming a test"]
    return named


# (name, function, seconds) -- ordered cheapest first, so a red baseline surfaces before the
# expensive suites are started.
#
# `seconds` is ONE run of that suite with a mutation applied, on the maintainers' machine (an M1 Max)
# with a warm daemon: what orders the work and splits the modes, not a promise. Mutated rather than
# idle, because a plugin mutation forces a Kotlin recompile and an idle timing does not carry it.
# When the costs were last reconciled with a whole run, baselines plus each suite's cost times its
# guard count came within a few seconds of the measured total.
#
# How many guards each suite proves is READ OFF THE MUTATION TABLE, not written beside it -- a
# hand-kept count goes wrong as soon as a guard is added:
#
#   python3 -c "import sys, collections; sys.path[:0] = ['scripts']; import prove_guards as g; \
#   print(collections.Counter(g.SUITE_NAMES[m[4]] for m in g.MUTATIONS))"
#
# Widening a suite's `--tests` filter costs every guard already on that suite, not only the new
# ones, so a widened suite is re-measured rather than adjusted. And the cheap-looking suites are not
# always cheap: `python_suite` runs `test_run.py`, which costs more per guard than the unit suite.
SUITES = [
    ("sweep", sweep_suite, 0.03),
    # Pure Python over dicts and one temporary directory.
    ("sharding", sharding_suite, 0.5),
    # Pure Python; a few `git init`s in temporary directories.
    ("scan", scan_suite, 1),
    # Pure Python over the workflow files and a dozen `git init`s; 3.7s for one unmutated run.
    ("ci", ci_suite, 3.7),
    # Pure Python; two `git init`s and a local clone in a temporary directory.
    ("controls", controls_suite, 0.3),
    # Pure Python; one `git init` in a temporary directory.
    ("preflight", preflight_suite, 0.3),
    # Real child processes that stall or run past a short limit; 5.6s for one unmutated run.
    ("process", process_suite, 5.6),
    ("corpus", corpus_suite, 0.6),
    # Pure Python plus one JVM for Replay.java against the jar the build already made.
    ("replay", replay_suite, 3),
    # Pure Python over temp files, so it is the cheapest suite here after `sweep`.
    ("mutations", mutations_suite, 0.5),
    # Pure Python; three `git init`s in temporary directories, and the import of `run.py`.
    ("order", order_suite, 3),
    # Pure Python over one fixture repository, with javac for the gate's stand-in.
    ("revert", revert_suite, 10.5),
    ("decoder", decoder_suite, 5),
    # 5.7s for one run with a Settings.kt mutation applied, its Kotlin recompile included; a doc
    # mutation recompiles nothing.
    ("reference", reference_suite, 6),
    ("agent", agent_suite, 6),
    # About 1.4s warm with a mutation applied, both tasks.
    ("jdk identity", jdk_identity_suite, 1.5),
    # 19.0-20.2s over three unmutated runs with the moved-classpath-files class, warm daemon, on
    # an Apple M1 Max; 18.9-19.3s without it.
    ("unit", unit_suite, 19.5),
    ("qualify", qualify_suite, 12),
    ("config cost", config_cost_suite, 1),
    # The shaded jars and both direct SBOMs, then the merge and its check; an estimate until a
    # guard run measures it.
    ("sbom", sbom_suite, 10),
    # The release repository built at a throwaway version, then one upload to a stand-in Portal.
    ("portal", portal_suite, 3),
    ("configuration guards", guard_suite, 17),
    ("python", python_suite, 33),
    ("functional", functional_suite, 68),
    # An estimate: the three classes' twenty-seven tests, about 8s each warm on an Apple M1 Max, plus
    # the plugin's publish, until a guard run measures it.
    ("maven", maven_suite, 230),
    # 50s wall for one unmutated run, the plugin and agent compiled from
    # scratch in a fresh worktree; its eight tests took 39s of it.
    ("break", break_suite, 50),
]

SUITE_NAMES = {suite: name for name, suite, _cost in SUITES}

# One run of each functional class on a GitHub runner, in seconds, for balancing the shards: the
# registry's one functional figure cannot tell a 30s class from a 230s one. Derived from the job
# logs of the every-guard run 39274620985 (8 shards, 574 guards): in each shard, each class's
# interval between its tree's `<Class> done in <tree> at <N>s` lines, divided by its guards in that
# shard plus the baseline each shard pays, averaged over the shards that ran it. Re-measured by
# repeating that on a later every-guard run.
FUNCTIONAL_CLASS_SECONDS = {
    "AuditAndExplainFunctionalTest": 52,
    "BootClassPathFunctionalTest": 50,
    "BranchPolicyFunctionalTest": 81,
    "CaptureFunctionalTest": 97,
    "ChangedBytesFunctionalTest": 40,
    "ClassFileCeilingFunctionalTest": 55,
    "ComplementFunctionalTest": 214,
    "ConfigurationCacheFunctionalTest": 40,
    "DecisionRulesFunctionalTest": 26,
    # Estimated from MultiProjectFunctionalTest, as many builds of the same shape, until an
    # every-guard run measures it.
    "DeclaredDependenciesFunctionalTest": 36,
    # Estimated from DecisionRulesFunctionalTest, as many builds of the same shape, until an
    # every-guard run measures it.
    "LargeChangeFunctionalTest": 30,
    "DeclineFunctionalTest": 65,
    "DevelocityFunctionalTest": 79,
    "EnvironmentFingerprintFunctionalTest": 82,
    "FullRunRequestFunctionalTest": 101,
    "GeneratedClasspathFilesFunctionalTest": 120,
    "HostCoverageFunctionalTest": 60,
    # Estimated from its local time (21s for eight builds) doubled for a runner, until an
    # every-guard run measures it.
    "InJvmParallelismCharacterizationTest": 45,
    "InitScriptFunctionalTest": 54,
    "IsolatedCaptureFunctionalTest": 71,
    "JvmSharedFunctionalTest": 172,
    # Estimated from HostCoverageFunctionalTest by local run time (40 s to its 15 s), until an
    # every-guard run measures it.
    "KoverFunctionalTest": 160,
    # Plus 8 s measured locally for the two plain JUnit 4 class-failure tests, until an every-guard
    # run measures it again.
    "MapDatingFunctionalTest": 237,
    "MapProvenanceFunctionalTest": 98,
    # Estimated from TestWrittenFilesFunctionalTest by test count (17 of its 29), until an
    # every-guard run measures it.
    "PathIgnoreFunctionalTest": 61,
    # An estimate: 46-48 s locally per Gradle version for its 10 tests, scaled as SelectionFunctionalTest.
    "PlainRunnerSelectionFunctionalTest": 90,
    "MultiProjectFunctionalTest": 36,
    "MultiplatformFunctionalTest": 140,
    "NamedTestsFunctionalTest": 111,
    "NestedLoaderFunctionalTest": 40,
    "ObservationFunctionalTest": 79,
    "RefusalFunctionalTest": 96,
    "RevertAfterUndatedCaptureFunctionalTest": 224,
    "ScheduledFullRunFunctionalTest": 72,
    "SelectionFunctionalTest": 197,
    # Estimated from its local time (27s for nine tests) doubled for a runner, until an
    # every-guard run measures it.
    "SequentialRecordingFunctionalTest": 55,
    "TestLauncherFunctionalTest": 42,
    "TestRetryFunctionalTest": 79,
    "TestWrittenFilesFunctionalTest": 104,
}

# The fast set: what runs on every pull request, so it must stay well inside a runner's patience.
# The next suite added to it should come with a measurement, not an estimate. `python`,
# `functional` and `agent` are what full mode is for: they are most of the harness's time, and a
# change to the agent or plugin sources runs full mode anyway. Treat the summary line the script
# PRINTS as the authority on what a mode covers.
FAST_SUITES = {"sweep", "sharding", "scan", "ci", "controls", "preflight", "corpus", "mutations", "order",
               "revert",
               "decoder", "reference",
               "jdk identity", "unit", "qualify", "config cost", "configuration guards"}

# The two staging fragments the guards below reintroduce defects into. Named constants because
# each is multi-line or backslash-heavy, and a mutation whose anchor no longer matches is a
# refusal this script makes before any suite runs.
STAGED_COMMIT_STEP = (
    '    subprocess.run(["git", "-C", checkout, "-c", "user.email=harness@yoriwake", "-c", "user.name=yoriwake",\n'
    '                    "-c", "commit.gpgsign=false",\n'
    '                    "commit", "--quiet", "--no-verify", "-m", STAGING_SUBJECT], check=True,\n'
    '                   env={**os.environ, "GIT_AUTHOR_DATE": pinned_date,\n'
    '                        "GIT_COMMITTER_DATE": pinned_date})')
CONDITION_STAMPS_WRITTEN = (
    '            "staged_sources": sorted(sources_to_drop(project)),\n'
    '            "flags": list(project.get("flags") or []),')
STAGING_CHANGED_RETURN = (
    '    return list(stored.get("staged_sources") or []) != sorted(sources_to_drop(project))')
STAGED_TRAVERSAL_CHECK = (
    '        if os.path.isabs(relative) or ".." in relative.replace("\\\\", "/").split("/"):')
# The decision record's completeness proof. Named because two guards replace it in different
# directions and a fragment spelled twice is a fragment that drifts.
COMPLETENESS = "    if declared is not None and declared.isdigit() and int(declared) != found:"

# (label, file, old, new, suite, the test that must be the one to fail)
#
# THE LAST FIELD MEANS TWO DIFFERENT THINGS, and getting it wrong makes a guard report success while
# proving a DIFFERENT test. It is matched as a substring against whatever the suite returns:
#
#   python suites  (`_python_checks`) return the printed `  FAIL: ...` check text
#                  -> expect must be a phrase from that SENTENCE, never the case function's name
#   Gradle suites  (unit/agent/functional/...) return `<Class> > <test name> FAILED` lines
#                  -> expect must be a phrase from the TEST NAME, never the assertion message
#
# AND FOR A GRADLE SUITE, TWO ASSERTIONS IN ONE @Test ARE INDISTINGUISHABLE. Both mutations report
# the same test name, so a guard aimed at one is "proved" by the other firing. The fix is to split
# the assertions into separate @Test methods, not to reword the expect.
MUTATIONS = [
    ("freshness check removed (a stale record is believed)", PY_SRC,
     "        return os.path.getmtime(path) >= since",
     "        return True", python_suite,
     "a record older than the arm was reported as that arm's answer"),
    ("digest stops normalising the identity hash", PY_SRC,
     'sorted(UNSTABLE_ID.sub("@?", test) for test in test_ids)', "sorted(test_ids)", python_suite,
     "an identity hash in a display name changes the digest"),
    ("map_fingerprint guesses 0 records when unreadable", PY_SRC,
     "    except OSError:\n        records = None", "    except OSError:\n        records = 0",
     python_suite,
     "an unreadable coverage.tsv reported 0 rather than absent"),
    # The staged-checkout refusal. A `drop_empty_sources` entry may only ever name a zero-byte file:
    # removing one cannot change what the guest compiles, and removing anything else edits the
    # guest's code. duckduckgo-android has two files called `SyncActivity.kt` -- one empty, one
    # 34,666 bytes -- so the size check is the whole distance between staging a checkout and
    # silently measuring an edited project.
    # THE ROOT CAUSE OF THE WHOLE FRESHNESS FAMILY, and it is in the WRITER rather than either
    # predicate. Writing these as `... or None` collapses "this project passes nothing" and
    # "this file predates the field" into one stored value, so the null tolerance that is
    # correct for an old file swallows a real transition from no flags to flags. It cost
    # duckduckgo-android a resumed sweep: three rows measured without
    # `--no-configure-on-demand` were about to be mixed with rows that would have had it.
    ("the condition stamps collapse an empty list back to null", PY_SRC,
     CONDITION_STAMPS_WRITTEN,
     '            "staged_sources": sorted(sources_to_drop(project)) or None,\n'
     '            "flags": list(project.get("flags") or []) or None,',
     python_suite, "null must mean only that the field did not exist"),
    # THE TWO FRESHNESS RULES THAT DECIDE WHETHER A SWEEP RESUMES, and they are the only stamps
    # that can witness a project-file change: `selector_digest` hashes the selector and not
    # `bench/`, and `commit` does not move either. Their null handling is deliberately
    # OPPOSITE; as one branch, it left duckduckgo-android resumable against sixteen rows measured
    # at the `*` scope its staging removes.
    ("staging freshness starts tolerating a stored null again", PY_SRC,
     STAGING_CHANGED_RETURN,
     '    if stored.get("staged_sources") is None:\n        return False\n'
     '    return list(stored.get("staged_sources") or []) != sorted(sources_to_drop(project))',
     python_suite, "a stored null was tolerated for a project that stages"),
    ("flags freshness stops tolerating a stored null, ageing every pre-stamp file", PY_SRC,
     '    if stored.get("flags") is None:\n        return False\n',
     '',
     python_suite, "a stored null aged a results file whose flags predate the stamp"),
    # THE LOAD-BEARING HALF OF STAGING, and the defect review caught before a sweep paid for
    # it. Staging deletes the named empty sources and COMMITS the deletion, so HEAD describes
    # the tree being measured. Left uncommitted, the deletion is reported by
    # `git diff --name-only HEAD` -- which is exactly what the selecting arm asks the plugin
    # for -- so all five paths join the change set of every row, force a full run, and the
    # sweep reports a `0 missed` that is true by construction.
    ("the staged deletion stops being committed, so it enters every change set", PY_SRC,
     STAGED_COMMIT_STEP, "    pass",
     python_suite, "the staged deletion is still in the change set"),
    # The traversal refusal. Its first test was VACUOUS -- both probes were caught by the
    # missing-file branch, so this check could be deleted with the suite staying green.
    ("the staged-drop traversal check stops refusing a path outside the checkout", PY_SRC,
     STAGED_TRAVERSAL_CHECK, "        if False:",
     python_suite, "was accepted as a path inside the checkout"),
    ("the staged-drop size check stops refusing a file with content", PY_SRC,
     "        size = os.path.getsize(path)\n        if size != 0:",
     "        size = os.path.getsize(path)\n        if False:",
     python_suite, "a file with content was accepted for deletion"),
    # THE COMPILE GATE'S OWN EXISTENCE, and the defect is that it had none. `test_classes_task`
    # derives `<test_task>Classes`, which `:app` on duckduckgo-android does not have -- so all 31
    # of that project's `compile-*.log` files end in "Cannot locate tasks" with no compiler
    # diagnostic anywhere, `mutations_rejected: 0` meant "never asked", and one invalid mutation
    # reached the arms as a `build-failed` row. A dry run of the task answers it in one
    # configuration pass, before the capture that costs hours.
    ("the classes-task check stops refusing a task the guest does not have", PY_SRC,
     '    code, _ = probe(checkout, project, [path, "--dry-run"], log_path)\n'
     '    if code == 0:\n        return',
     '    code, _ = probe(checkout, project, [path, "--dry-run"], log_path)\n'
     '    if True:\n        return',
     python_suite, "was not refused, so the sweep would pay for a capture"),
    # The other half: the refusal is only survivable because a guest may name the task it has.
    ("a guest's own classes-task name stops being read", PY_SRC,
     '    stated = project.get("test_classes_task")\n    if stated:\n        return str(stated)',
     '    stated = None\n    if stated:\n        return str(stated)',
     python_suite, "a guest naming its own compile task derived"),
    # THE THIRD MEMBER OF THE FRESHNESS FAMILY, and it is here for the reason the other two are:
    # `selector_digest` hashes the selector and not `bench/`, and `commit` does not move for a
    # project-file edit, so a stamp nothing reads back lets two populations share one header. How
    # firmly a row's already-failing set was established is a property of THAT ROW --
    # duckduckgo-android's suite swings ~100 tests between runs of itself, which is what the key
    # exists to span.
    ("a project's own baseline-repeats count stops being read", PY_SRC,
     '    stated = project.get("baseline_repeats")',
     '    stated = None  # noqa',
     python_suite, "a project with the key resolved to"),
    ("baseline-repeats freshness stops seeing a changed count", PY_SRC,
     '    return stored.get("baseline_repeats") != baseline_repeats_for(project, default)',
     '    return False',
     python_suite, "a project that now buys more repeats gave changed=False"),
    # The null half, mutated the way `flags` is: null means the FIELD did not exist, so reading it
    # as a mismatch ages every file written before the key and charges the corpus a re-sweep to
    # introduce a stamp.
    ("baseline-repeats freshness stops tolerating a stored null", PY_SRC,
     '    if stored.get("baseline_repeats") is None:\n        return False\n',
     '',
     python_suite, "a file written before the field existed"),
    # THE GRAMMAR STAMP, the fourth member of the family and the one `target_shapes` could not
    # be: a change to a generator's rules inside the `default` set leaves `target_shapes` alone.
    ("grammar freshness stops seeing a changed grammar", PY_SRC,
     '    return recorded is not None and recorded != current',
     '    return False',
     python_suite, "a changed grammar gave changed=False"),
    ("grammar freshness stops tolerating a stored null, ageing every pre-stamp file", PY_SRC,
     '    return recorded is not None and recorded != current',
     '    return recorded != current',
     python_suite, "a file from before the stamp gave changed=True"),
    ("the grammar digest stops covering the body generator", PY_SRC,
     '                        "bench/recall_check.py")',
     '                        )',
     python_suite, "a change to bench/recall_check.py did NOT move"),
    ("a resumed pre-stamp file is stamped with rules nobody recorded", PY_SRC,
     '    if findings and stored.get("grammar_digest") is None:\n        return None',
     '    if False:\n        return None',
     python_suite, "resuming rows written before the stamp existed stamped"),
    ("a history replay is stamped with a grammar that drew no targets", PY_SRC,
     '"grammar_digest": grammar if target_seed is not None else None,',
     '"grammar_digest": grammar,',
     python_suite, "a history replay recorded grammar_digest"),
    # THE DECISION RECORD'S COMPLETENESS PROOF, which three instruments rest on and nothing had
    # ever shown could fail. The writer counts from the same snapshot it iterates, so a file whose
    # header disagrees with its body cannot come from one write. Of the records once checked, the
    # few that disagreed all declared FEWER rows than they carried, which a lost tail cannot
    # produce. The reader COMPARES rather than bounds, which is what catches that direction.
    ("the completeness check bounds instead of comparing, missing the direction the corpus has",
     PY_SRC, COMPLETENESS,
     "    if declared is not None and declared.isdigit() and int(declared) > found:",
     python_suite, "was read as an answer; it cannot be a lost tail"),
    ("the completeness check stops rejecting a record whose tail was lost", PY_SRC,
     COMPLETENESS, "    if False:",
     python_suite, "a record whose tail was lost was read as a short answer"),
    # The tear the count cannot see, because the count is one of the notes that was lost.
    ("the notes-tear check stops rejecting a record cut between its first two notes", PY_SRC,
     "    if notes.get(DECISION_VERSION_NOTE) is not None and declared is None:",
     "    if False:",
     python_suite, "a record cut inside its notes was read as an answer"),
    # ASM DECIDES WHAT CAN BE READ AT ALL, so its version is selector behaviour and not packaging.
    # rxjava's pinned commit sets `sourceCompatibility = VERSION_26`, so all 4,599 of its compiled
    # classes are class-file major 70; ASM 9.8 throws `Unsupported class file major version 70` on
    # every one, `scan` turns that into `unknown`, and the inline scan refuses the whole walk. The
    # project has carried `inline-scan-incomplete` for that reason and it was read as an SMAP defect.
    ("ASM goes back to a version that cannot read a corpus guest's class files", PLUGIN_BUILD,
     'val asmVersion = "9.10.1"', 'val asmVersion = "9.8"',
     unit_suite, "a class file from a newer JDK is read"),
    # The refusal's own diagnosis. `Scan.unknown` has two causes and this branch named one of them,
    # which sent every reader of an unreadable class to a parser that was not at fault.
    ("an unreadable class is blamed on its SMAP again", TASK_ARTIFACTS,
     'return@walkOwnClasses "${entry.absolutePath}" +',
     'return@walkOwnClasses "${entry.absolutePath} carries an SMAP this parser did not understand" + "" +',
     unit_suite, "a scan that could not read something is a different kind"),
    # THE TWO FAILURES THE AUDIT USED TO CONFLATE. Resolving a classpath and scanning it have
    # unrelated remedies, and they were one `runCatching`. bitwarden-android carried
    # `inline-scan-incomplete` in bench/qualification.txt because yoriwakeAudit runs no tasks and an
    # Android unit-test classpath carries entries mapped from a transform task's output -- an
    # exception that appears in that project's audit.log and in NONE of its capture, baseline or
    # selecting-arm logs. The table stated a blocker the selector does not have.
    ("a classpath that will not resolve is folded back into the scan's failure", TASK_ARTIFACTS,
     CLASSPATH_REFUSAL, CLASSPATH_REFUSAL.replace("CLASSPATH_UNRESOLVED", "INCOMPLETE"),
     unit_suite, "a classpath that will not resolve is a different refusal"),
    ("the audit reports an unresolvable classpath as the selector's own blocker", AUDIT_COMPUTE,
     AUDIT_CLASSPATH_BLOCKER,
     AUDIT_CLASSPATH_BLOCKER.replace("CLASSPATH_UNRESOLVED_HERE", "INLINE_SCAN_INCOMPLETE"),
     unit_suite, "a classpath this instrument cannot resolve is not reported"),
    # THE HALF THAT MAKES ATTACHING TO MULTIPLATFORM SAFE. Widening the gate alone was tried on
    # kotlinx-coroutines: the plugin attached and derived thirteen packages, every one
    # belonging to a DIFFERENT module, with the module's own absent -- because a multiplatform
    # module has no SourceSetContainer and no src/main/*, so it contributed nothing to a union its
    # siblings filled. A non-empty scope over the wrong packages does not decline; it writes a map
    # claiming to speak for a task whose classes it never saw. Without this, the plugin withdraws
    # the attach.
    ("the multiplatform layout drops out of the scope union", PROJECT_FACTS,
     "                    .filter { it.isDirectory } +\n"
     "                    YoriwakePlugin.multiplatformSourceDirs(candidate.projectDir)",
     "                    .filter { it.isDirectory }",
     unit_suite, "a multiplatform module contributes its own packages"),
    # The same rule, through a real multiplatform build: its capture must scope the packages of
    # both commonMain and jvmMain.
    ("the multiplatform layout drops out of a real build's scope", PROJECT_FACTS,
     "                    .filter { it.isDirectory } +\n"
     "                    YoriwakePlugin.multiplatformSourceDirs(candidate.projectDir)",
     "                    .filter { it.isDirectory }",
     functional_suite, "a capture scopes the module's commonMain and jvmMain packages"),
    ("a module with no JVM target is attached to anyway", YORIWAKE_PLUGIN,
     "            MULTIPLATFORM_JVM_MARKERS\n"
     "                .any { File(project.projectDir, it).isDirectory }",
     "            MULTIPLATFORM_JVM_MARKERS\n                .any { true }",
     unit_suite, "a multiplatform module with no JVM target is not mistaken"),
    # A PROBE FAILURE THAT IS NOT A MISSING TASK MUST NOT BE REPORTED AS ONE. The refusal aborts a
    # 30-to-42-hour sweep, and a dependency that will not resolve or a dead daemon exits non-zero
    # here just as a missing task does -- so the remedy `test_classes_task` would be attached to a
    # failure it cannot fix. Same distinction `compile_or_reject` makes with its log read.
    ("the classes-task probe blames a missing task for any failure", PY_SRC,
     "    if code != STALLED and not task_is_missing(log_path):",
     "    if False:",
     python_suite, "was handed the missing-task remedy"),
    ("a boolean is accepted as a baseline-repeat count", PY_SRC,
     "    if isinstance(stated, bool):\n        return default",
     "    if False:\n        return default",
     python_suite, "a boolean, which Python calls an int"),
    # THE GATE AND THE SCOPE MUST AGREE. Accepting src/jvmTest alone while the scope contributed
    # only commonMain and jvmMain let a module attach and contribute none of its own packages --
    # the condition the plugin withdraws the attach for, reachable through the change meant to
    # prevent it.
    ("the multiplatform gate stops requiring a scopable module", YORIWAKE_PLUGIN,
     "            hasJvmTarget(project) && multiplatformSourceDirs(project.projectDir).isNotEmpty()",
     "            hasJvmTarget(project)",
     unit_suite, "a multiplatform module this plugin cannot scope is not attached to"),
    # ONE FILE THAT DECLARES NOTHING USED TO COLLAPSE A WHOLE BUILD TO `*`. kotest has two, wholly
    # commented out including their package line, and that alone took every module in that build to
    # a scope of `*` -- a map over the entire classpath, which can support no claim about the module
    # under test. Neither an empty file nor a commented-out one compiles to a class, so neither can
    # put anything in the default package.
    #
    # THE OTHER DIRECTION MUST NOT MOVE: a file with CODE and no package line genuinely is in the
    # default package and must still collapse. arrow has five (suspendapp-test-app/.../main.kt), and
    # its `*` is correct rather than defective. The mutation below removes exactly that distinction.
    ("a file that declares nothing collapses the build again", SCOPE,
     "        return if (sawCode) PackageScan.DefaultPackage else PackageScan.DeclaresNothing",
     "        return PackageScan.DefaultPackage",
     unit_suite, "a file that declares nothing at all does not collapse"),
    # AND AN UNREADABLE FILE MUST KEEP COLLAPSING, which is the half that costs recall. Reading it as
    # "declares nothing" would narrow the scope past a class nobody could see. Nothing tested this
    # branch until review found it: every existing test's files were readable.
    ("an unreadable source file reads as declaring nothing, narrowing the scope", SCOPE,
     "            .getOrDefault(PackageScan.DefaultPackage)",
     "            .getOrDefault(PackageScan.DeclaresNothing)",
     unit_suite, "a source file that cannot be read still collapses"),
    # The other half of the empty-pathspec defect. `replay_commits` refuses an empty `sources`; this
    # is the same question asked of the mutation grammar, which used to answer it with an empty list
    # and no sentence anywhere.
    # The staging name, back to per-process. Several records live in one test JVM -- the Platform
    # makes a discovery request each -- and a shared staging path means one publishes a blend while
    # the other finds its own file already moved.
    # EVERY TEST JVM'S ANSWER, not the last one's. duckduckgo-android's `forkEvery = 100` left 371
    # rows of a 5,041-test arm in the shared file, on the row that recorded a miss.
    ("each writer stops publishing its own decision part", DECISION_RECORD,
     '        publish(dir, DECISIONS_FILE + "." + pid + "-" + writerId + DECISIONS_PART_SUFFIX, out.toString(), pid, write);',
     '',
     agent_suite, "every writer's whole answer survives in its own part"),
    # A REUSED MAP HAS TO BE RELATABLE TO THE NEXT RUN'S HEAD. A staging commit dated "now" gave
    # every run a new SHA, so every row of a reused map on a staging project forced a full run.
    ("the staging commit is dated now again, so every run stages a new SHA", PY_SRC,
     '                   env={**os.environ, "GIT_AUTHOR_DATE": pinned_date,\n'
     '                        "GIT_COMMITTER_DATE": pinned_date})',
     '                   env=None)',
     python_suite, "staging the same tree again made a different commit"),
    # THE REPLAY'S CLAIM TO BE A REPLAY. A row recorded by the current selector that does not
    # reproduce its own verdicts is a second opinion, and answering its recall question anyway is
    # how an offline check would publish a zero that the shipped rule does not agree with.
    ("the replay answers a row that does not reproduce its own record", REPLAY_SRC,
     '    if drift and row.get("replay", {}).get("digest") == run.selector_digest():',
     '    if False:',
     replay_suite, "a record the selector cannot reproduce was answered"),
    ("the replay stops reporting a skipped failing test as missed", REPLAY_SRC,
     '        elif key not in selected:',
     '        elif False:',
     replay_suite, "a skipped failing test was not reported missed"),
    ("the replay reads a test the recorded arm never saw as decided", REPLAY_SRC,
     '        if key not in discovered:\n            undetermined.append(failing)',
     '        if False:\n            undetermined.append(failing)',
     replay_suite, "an undiscovered failing test was not undetermined"),
    # A ROW'S KEPT PARTS ARE ONE RUN'S. The stem is the row's label, so a later run of the same row
    # writes to the same directory, and a leftover part merges two runs into one replayed answer.
    ("kept decision parts stop being cleared, so two runs of a row merge", PY_SRC,
     '    shutil.rmtree(stem + ".parts", ignore_errors=True)\n    if parts:\n'
     '        os.makedirs(stem + ".parts")',
     '    if parts:\n        os.makedirs(stem + ".parts", exist_ok=True)',
     python_suite, "a second keep merged two runs' parts"),
    ("the harness stops reading the decision parts", PY_SRC,
     '    parts = [p for p in decision_parts(map_dir) if _fresh(p, since)]',
     '    parts = []',
     python_suite, "the parts did not reach the row"),
    ("a torn decision part is dropped and the rest published as the whole answer", PY_SRC,
     '    if any(record is None for record in records):\n        return None',
     '    records = [record for record in records if record is not None]',
     python_suite, "a torn part was dropped"),
    # Re-anchored: the staging file is declared outside the try, so a failed move can
    # delete it.
    ("the decision record's staging file goes back to one name per process", DECISION_RECORD,
     '                dir, name + "." + pid + "." + writerId + "." + write + ".writing");',
     '                dir, name + "." + pid + ".writing");',
     agent_suite, "do not fight over one staging file"),
    # The constant generator's own version of the braces defect: a value pattern that cannot see an
    # escaped quote corrupts the literal and the guest build stops compiling.
    ("the constant pattern stops understanding escaped quotes", MUTATIONS_SRC,
     r'(?:int|long|short|byte|double|float|String)\s+\w+\s*=\s*)(?P<value>\"(?:\\.|[^\"\\])*\"|-?(?:0[xXbB][0-9a-fA-F_]+|\d[\d_]*)[lLuUfFdD]?)',
     r'(?:int|long|short|byte|double|float|String)\s+\w+\s*=\s*)(?P<value>\"[^\"]*\"|-?(?:0[xXbB][0-9a-fA-F_]+|\d[\d_]*)[lLuUfFdD]?)', mutations_suite,
     # A CHECK name, not the case name: `_python_checks` returns the `  FAIL <check>` lines, so an
     # expect matched against the case name can never be satisfied and the guard reports as unproved.
     "the mutated line is a complete assignment"),
    ("the Java constant pattern only knows decimal digits again", MUTATIONS_SRC,
     r'(?:int|long|short|byte|double|float|String)\s+\w+\s*=\s*)(?P<value>\"(?:\\.|[^\"\\])*\"|-?(?:0[xXbB][0-9a-fA-F_]+|\d[\d_]*)[lLuUfFdD]?)',
     r'(?:int|long|short|byte|double|float|String)\s+\w+\s*=\s*)(?P<value>\"(?:\\.|[^\"\\])*\"|-?\d+)', mutations_suite,
     "a hex literal with separators is replaced whole"),
    ("the Kotlin constant pattern only knows decimal digits again", MUTATIONS_SRC,
     '    r"(?m)^(?P<head>[^\\S\\n]*(?:public|private|internal)?\\s*const\\s+val\\s+\\w+(?:\\s*:\\s*\\w+)?\\s*=\\s*)"\n    r"(?P<value>\\"(?:\\\\.|[^\\"\\\\])*\\"|-?(?:0[xXbB][0-9a-fA-F_]+|\\d[\\d_]*)[lLuUfFdD]?)"',
     '    r"(?m)^(?P<head>[^\\S\\n]*(?:public|private|internal)?\\s*const\\s+val\\s+\\w+(?:\\s*:\\s*\\w+)?\\s*=\\s*)"\n    r"(?P<value>\\"(?:\\\\.|[^\\"\\\\])*\\"|-?\\d+)"', mutations_suite,
     "no fragment of the binary literal survives"),
    # The `pool/v1` generators, one guard each. Each reintroduces a defect class the default
    # generators already paid for, or one that would edit something the family excludes.
    ("the void-return edit goes braceless", POOL_MUTATIONS,
     '    _write(path, _entry(text, match, ("if (true) {", "    " + statement, "}")))',
     '    _write(path, _entry(text, match, ("if (true) " + statement,)))', mutations_suite,
     "one return at its entry, braced on its own lines"),
    ("a constructor is drawn as a method for throw-entry", POOL_MUTATIONS,
     'if m.group("type") not in _JAVA_NOT_A_TYPE and m.group("name") not in _JAVA_NOT_A_TYPE]',
     'if True]', mutations_suite,
     "Shapes.java yields no throw-entry target"),
    ("override-add adds equals to a class with no hashCode", POOL_MUTATIONS,
     "        for name, declared, lines in OVERRIDES[extension]:",
     "        for name, declared, lines in reversed(OVERRIDES[extension]):", mutations_suite,
     "hashCode is added before equals"),
    ("member-delete leaves the deleted method's Javadoc dangling", POOL_MUTATIONS,
     '        found.append((_with_leading_comment(text, start), end, match.group("name")))',
     '        found.append((start, end, match.group("name")))', mutations_suite,
     "it is gone whole, with its Javadoc"),
    ("a project that names no sources yields no targets in silence again", TARGET_ORDERING,
     '    if not project.get("sources"):', '    if False:', python_suite,
     "the refusal named neither the key nor the project"),
    ("a narrowed row inherits a force kind", PY_SRC,
     '"full_run_kind": notes.get("full-run-kind"),',
     '"full_run_kind": notes.get("full-run-kind") or "map-unusable",', python_suite,
     "a narrowed row carries a force kind"),
    ("record/arm disagreement stops being flagged", PY_SRC,
     '"decision_matches_arm": (len(included) == selected_distinct) if narrowed else None,',
     '"decision_matches_arm": True if narrowed else None,', python_suite,
     "one included test against nine that ran was not flagged as a disagreement"),
    ("a forced row compares discovery against execution again", PY_SRC,
     '"decision_matches_arm": (len(included) == selected_distinct) if narrowed else None,',
     '"decision_matches_arm": len(included) == selected_distinct,', python_suite,
     "a forced row compared discovery against execution"),
    ("decision_included stops being recorded", PY_SRC,
     '"decision_included": len(included),', '"decision_included": None,', python_suite,
     "the selector's own count"),
    ("the third capture opener is dropped again (the real defect)", PY_SRC,
     '    "narrowing, so nothing is instrumented and the map is left alone",\n', "", python_suite,
     "which no opener matches"),

    # -- the configuration-work guards --------------------------------------------------------
    ("git is asked once per Test task again (the memo stops memoising)", BUILD_MEMO,
     "        instances.computeIfAbsent(key) { create() }",
     "        create()", guard_suite, "the git command set does not grow"),
    ("an eighth git command joins the hoisted path", CHANGE_DETECTION,
     '        mergeBase(git, "@{upstream}")?.let {',
     '        git.run(listOf("log", "-1"))\n'
     '        mergeBase(git, "@{upstream}")?.let {', guard_suite,
     "the git command set does not grow"),
    ("the scope is derived once per Test task again", JACOCO_SCOPING,
     "        return if (buildMemo == null) compute() else buildMemo.value(SCOPE_KEY + project.rootDir.path, compute)",
     "        return compute()", guard_suite, "the git command set does not grow"),
    ("the cross-project walk goes back to once per classpathFacts call", PROJECT_FACTS,
     "    return if (memo == null) compute() else memo.value(YoriwakePlugin.FACTS_KEY + project.rootDir.path, compute)",
     "    return compute()", guard_suite, "the git command set does not grow"),
    # Hashed once per `Test` task, the untracked and ignored tree kept a build with a large
    # node_modules and hundreds of test tasks configuring for over ten minutes.
    ("the working tree is hashed once per Test task again", TEST_TASK_WIRING,
     '            m.provider(YoriwakePlugin.CAPTURE_SNAPSHOT_KEY + rootDir.path + "\\u0000" + start.path) {',
     '            m.provider(YoriwakePlugin.CAPTURE_SNAPSHOT_KEY + rootDir.path + "\\u0000" + mapDir.path) {',
     guard_suite, "a capture hashes the working tree once"),
    # Named for what each actually breaks. The first was labelled "encoded as not asked again",
    # which is not what removing this branch does -- it reclassifies a refusal as an ordinary forced
    # run, which is a different defect and the one the test catches.
    ("a refusal is counted as an ordinary forced run", SWEEP_SRC,
     '        elif outcome == "full-run" and row.get("full_run_kind") == "daemon-refused":',
     '        elif False:', sweep_suite,
     "a kindless daemon refusal was not counted as refused"),
    ("a row in no bucket stops being named", SWEEP_SRC,
     '            classified["unclassified"].append(row)',
     '            pass', sweep_suite,
     "the unclassified row was not counted"),
    # And the sum itself, which neither of the two above touches: `classification_sums` is computed
    # from the three buckets against the answered population and never reads `unclassified`.
    ("the three-way sum stops being checked", SWEEP_SRC,
     '        "classification_sums": (\n'
     '            len(classified["narrowed"]) + len(classified["forced"]) + len(classified["refused"])\n'
     '            + len(classified["requested"]) == len(answered)\n'
     '        ),',
     '        "classification_sums": True,', sweep_suite,
     "a row in no bucket was reported as a corpus that adds up"),
    # The filter branch. Note what is NOT here: nulling `daemonRefusal()` in the decide() call
    # alone changes nothing observable, because this branch forces before the decision is consulted.
    # The two are deliberate defence in depth and no single mutation can expose the second; that is
    # said in SelectionFilter rather than dressed up as a guard here.
    ("the refused run's own rows stop naming the refusal", SELECTION_FILTER,
     '            record(descriptor, Verdict.DAEMON_REFUSED);',
     '            record(descriptor, Verdict.SELECTION_NOT_REQUESTED);',
     functional_suite, "a run the daemon refused is a refusal in the record"),
    # -- the constant guard's runtime-escape branch ---------------------------------------------
    #
    # A change to shipped selection logic, so each of its three halves gets a mutation rather than
    # the rule getting one. The first two fail in the direction that SKIPS a test; the third is the
    # cost half, and it is the one that brought back armeria's 698 tests the last time it was wrong.
    ("a private constant written into a field goes back to being exempt", RECORDABILITY,
     "        val carriable = declared.filterNot { it.isSerialVersionUid }.mapTo(mutableSetOf()) { it.value }\n"
     "        if (carriable.isEmpty()) {",
     "        val carriable = mutableSetOf<Any>()\n"
     "        if (carriable.isEmpty()) {", unit_suite,
     "cached into a field escapes at runtime"),
    # The fourth escape route, one indirection from a measured miss. Clearing the tracked operand on
    # a call is what made `private final Integer x =
    # CONST` exempt while `private final int x = CONST` forced.
    ("a call between the constant load and the field write hides the escape again", RECORDABILITY,
     "            override fun visitMethodInsn(\n"
     "                opcode: Int,\n"
     "                owner: String,\n"
     "                name: String,\n"
     "                descriptor: String,\n"
     "                isInterface: Boolean,\n"
     "            ) {\n"
     "                // Deliberately NOT cleared.\n"
     "            }",
     "            override fun visitMethodInsn(\n"
     "                opcode: Int,\n"
     "                owner: String,\n"
     "                name: String,\n"
     "                descriptor: String,\n"
     "                isInterface: Boolean,\n"
     "            ) {\n"
     "                pushed = null\n"
     "            }", unit_suite,
     "a call between the load and the write does NOT hide the escape"),
    ("a conditional field initialiser hides the escape again", RECORDABILITY,
     "            override fun visitLabel(label: org.objectweb.asm.Label) {\n"
     "                // Deliberately NOT cleared.\n"
     "            }",
     "            override fun visitLabel(label: org.objectweb.asm.Label) {\n"
     "                pushed = null\n"
     "            }", unit_suite,
     "control flow between the load and the write does NOT hide the escape"),
    ("a jump between the constant load and the field write hides the escape again", RECORDABILITY,
     "            override fun visitJumpInsn(opcode: Int, label: org.objectweb.asm.Label) {\n"
     "                // Deliberately NOT cleared.\n"
     "            }",
     "            override fun visitJumpInsn(opcode: Int, label: org.objectweb.asm.Label) {\n"
     "                pushed = null\n"
     "            }", unit_suite,
     "control flow between the load and the write does NOT hide the escape"),
    ("a class whose instructions could not be walked is exempted rather than forced", RECORDABILITY,
     "        }.getOrElse { true }",
     "        }.getOrElse { false }", unit_suite,
     "cannot be read forces rather than exempting"),
    ("a constant written into a static field stops counting as an escape", RECORDABILITY,
     "                if ((opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC) && pushed in values) {",
     "                if (opcode == Opcodes.PUTFIELD && pushed in values) {", unit_suite,
     "a constant written into a STATIC field is detected"),
    ("serialVersionUID loses its carve-out and collides with a same-valued field", RECORDABILITY,
     "        val carriable = declared.filterNot { it.isSerialVersionUid }.mapTo(mutableSetOf()) { it.value }",
     "        val carriable = declared.mapTo(mutableSetOf()) { it.value }", unit_suite,
     "serialVersionUID beside a field written with the same literal"),
    # -- a parameterised container reported as a test, measured on gradle ------------------------
    #
    # Four failing tests were skipped because a Spock data-driven feature carries a coverage record
    # of its own, describing none of the work its iterations did. Both halves get a mutation: the
    # rule, and the filter's use of it. Each fails in the direction that SKIPS a test.
    ("a descriptor that says it is a test is judged on its own record alone again", SELECTOR,
     "            if (!hasRecordedChildren(knownTestIds, testId)) {\n"
     "                return exact;\n"
     "            }\n"
     "            return subtreeReason(testId);",
     "            return exact;", agent_suite,
     "a parameterised feature whose iterations reach the change is not skipped"),
    ("the subtree short-circuit starts changing answers", SELECTOR,
     "            if (!hasRecordedChildren(knownTestIds, testId)) {\n"
     "                return exact;\n"
     "            }\n"
     "            return subtreeReason(testId);",
     "            if (hasRecordedChildren(knownTestIds, testId)) {\n"
     "                return exact;\n"
     "            }\n"
     "            return subtreeReason(testId);", agent_suite,
     "a parameterised feature whose iterations reach the change is not skipped"),
    # CLASS GRANULARITY. It can only ever INCLUDE, so it cannot lose a failure --
    # what it can do is become the default nobody chose, which is a narrowing change for every
    # adopter made by a one-word edit.
    # A declared dependency is one coverage cannot see: a changed path a test's tag or a task's
    # dependsOn matches must run its declarers, or the declaration protects nothing.
    ("a test's declared dependency no longer selects it", SELECTION_FILTER,
     "        if (declaresChangedPath(descriptor)) {",
     "        if (false) {",
     agent_suite, "a test whose declared dependency matches a changed path is selected"),
    ("a task's declared dependency no longer runs it in full", SELECTING_STEP,
     "        if (declaredHit != null && !widening.forces) {",
     "        if (false && declaredHit != null && !widening.forces) {",
     functional_suite, "a path the build declares for a task runs that task in full"),
    ("a double star in a declared dependency matches exactly one directory", PATH_GLOB,
     "            for (int next = s; next <= segments.length; next++) {",
     "            for (int next = s + 1; next <= Math.min(s + 1, segments.length); next++) {",
     agent_suite, "a star stays within a directory, a double star crosses any number"),
    ("class granularity becomes the default nobody asked for", SELECTION_RUN,
     'Boolean.parseBoolean(HostBuild.current().property(CLASS_GRANULARITY_PROPERTY))',
     '!"false".equals(HostBuild.current().property(CLASS_GRANULARITY_PROPERTY))',
     agent_suite, "without the flag the same test is still excluded"),
    # And the nested-class specification, which is the half a reader would get wrong: rounding to
    # the INNERMOST class leaves the enclosing class's other tests skipped, which is the shared
    # static state that actually breaks them.
    ("class granularity rounds to the innermost class instead of the outermost", SELECTION_FILTER,
     '            if (at.getUniqueId().toString().contains("[class:")) {',
     '            if (found == null && at.getUniqueId().toString().contains("[class:")) {',
     agent_suite, "rounding is to the outermost class, so a nested sibling is included"),
    ("the filter stops asking the container-aware rule", FILTER_RULE,
     "        return isTest ? decision.reasonForTest(id) : decision.subtreeReason(id);",
     "        return isTest ? decision.reasonFor(id) : decision.subtreeReason(id);", agent_suite,
     "a parameterised test whose iterations reach the change is included"),
    ("an unknown change set stops resolving the classpath", CLASSPATH_FACTS,
     "        if (changedPaths == null ||\n"
     "            reachesOutsideModule(ownDir, ownPath, changedPaths, facts.moduleDirs)\n"
     "        ) {",
     "        if (reachesOutsideModule(ownDir, ownPath, changedPaths.orEmpty(), facts.moduleDirs)) {",
     unit_suite, "an unknown change set resolves the classpath"),
    ("the audit stops seeing a git failure from a later project", AUDIT_TASK,
     "        val gitUnavailable = gitFailed.getOrElse(false)",
     "                val gitUnavailable = false", functional_suite,
     "a build whose git cannot answer says so in its audit"),
    # THE LIMIT THAT USED TO FAIL A GUEST BUILD, in both directions. The first restores the throw;
    # the second is the one that matters, because a decline which stops selecting and keeps
    # capturing trades a loud failure for a blended map that outlives the run and is believed by
    # every later one.
    ("in-JVM parallelism fails the build again instead of declining", TEST_TASK_WIRING,
     "            source ?: return@doFirst\n",
     "            check(source == null) { \"in-JVM parallelism\" }\n            if (source == null) return@doFirst\n",
     functional_suite, "in-JVM parallel execution is declined"),
    ("capture proceeds under the parallelism decline, so the map can be blended", TEST_TASK_WIRING,
     '            test.systemProperty(RECORDS_DIR_PROPERTY, "")\n'
     "            runCatching {\n"
     "                jacoco?.isEnabled = false",
     "            runCatching {\n"
     "                jacoco?.isEnabled = true",
     # The agent's own read-back now keeps the map whole too; the host's JaCoCo left on is what shows.
     functional_suite, "a run declined over in-JVM parallelism leaves no host file"),
    # The unpack, back inside configuration. Every first build after an install then configures
    # twice, which is invisible in a build log and costs every two-arm cache measurement a third arm.
    ("the agent is unpacked during configuration again", TEST_TASK_WIRING,
     "        val agent = AgentJar.locationIn(File(project.cacheDir(), AgentJar.DIRECTORY))",
     "        val agent = AgentJar.extractTo(File(project.cacheDir(), AgentJar.DIRECTORY))!!",
     functional_suite, "reuses its configuration-cache entry"),
    ("the Isolated Projects decline stops firing", YORIWAKE_PLUGIN,
     "        if (declineIsolatedProjects(project, extension)) {\n            return\n        }\n",
     "", functional_suite, "Isolated Projects gets a named decline"),
    # The Gradle floor. Below 8.14 API the plugin reads is missing and the failure is swallowed into
    # an empty set of declared modules, which drops a sibling module's changes: a skip.
    ("the Gradle version decline stops firing", YORIWAKE_PLUGIN,
     "        if (declineOldGradle(project, extension)) {\n            return\n        }\n",
     "", functional_suite, "older than 8_14 gets a named decline"),
    # The ordering, which is the whole fix. A decline decided in this project's own afterEvaluate
    # races a host that applies `java` in one of its own, and the loser is the audit: the refusal is
    # baked into a task the real configuration then finds already registered.
    ("the no-host-plugin decline goes back to racing the host's own afterEvaluate", YORIWAKE_PLUGIN,
     "        project.gradle.projectsEvaluated { declareNoHostPluginDecline(project, configured, extension) }",
     "        project.afterEvaluate { declareNoHostPluginDecline(project, configured, extension) }",
     functional_suite, "applies java from its own afterEvaluate"),
    ("a build with no host plugin goes back to saying nothing", YORIWAKE_PLUGIN,
     "        declineWhenNoHostPlugin(project, configured, extension)\n", "",
     functional_suite, "no host plugin is declined by name"),
    ("records from another schema version are merged again", COVERAGE_DECODER,
     "        refuseForeignWorkers(workers)\n\n", "", decoder_suite,
     "records left by another version of the agent are refused"),
    ("map health goes back to measuring only what this run captured", COVERAGE_DECODER,
     "            mapUnattributable = mapTestRecords.count { line ->\n"
     "                val parts = line.split('\t')\n"
     "                parts.size >= 4 && parts[2].isBlank()\n"
     "            },\n"
     "            mapTests = mapTestRecords.size,",
     "            mapUnattributable = unattributable,\n"
     "            mapTests = captured.size,", decoder_suite,
     "map health is measured over the whole map"),
    ("a disabled jacoco extension is configured as though it worked", JACOCO_SCOPING,
     "        if (!jacoco.isEnabled) {", "        if (false) {", functional_suite,
     "jacoco extension is switched off is declined before the run"),
    ("the map location goes back to reading rootProject", TEST_TASK_WIRING,
     '    gradle.startParameter.projectCacheDir ?: File(rootDir, ".gradle")',
     '    gradle.startParameter.projectCacheDir ?: rootProject.file(".gradle")',
     functional_suite, "Isolated Projects gets a named decline"),
    ("the audit recognises one decline token again, not every kind", AUDIT_COMPUTE,
     "        val declined = blockers.any { it.token in DECLINED_TOKENS }",
     '        val declined = blockers.any { it.token == "scope-not-applied" }',
     functional_suite, "Isolated Projects gets a named decline"),
    ("a project file may carry its own qualification verdict again", CAFFEINE_PROJECT,
     "# The qualification verdict for this project lives",
     "# NOT QUALIFIED.\n# The qualification verdict for this project lives",
     qualify_suite,
     "states a qualification verdict"),
    # The Python side of the same rule. `recordedTime` had no reader here at all, and the
    # three answers it distinguishes -- a number, a map that records no durations, and a payload
    # written before the field existed -- collapse into a plausible zero with one edit.
    ("a map that records no durations reports zero seconds to the corpus", QUALIFY_SRC,
     '        return {"recorded_seconds": "unknown", "recorded_workers": "unknown"}',
     '        return {"recorded_seconds": 0, "recorded_workers": 1}',
     qualify_suite,
     "recorded_seconds read as 0, expected 'unknown'"),
    ("a refused merge stops being a blocker, so a map that stopped updating looks healthy", AUDIT_COMPUTE,
     "    facts.decodeRefused?.let { reason ->", "    (null as String?)?.let { reason ->",
     unit_suite, "a refused merge is its own blocker"),
    ("the same test id is summed once per record again, so a retry counts twice", AUDIT_COMPUTE,
     "                    if (timedById.containsKey(id)) duplicatedIds += id\n"
     "                    timedById[id] = maxOf(nanos, timedById[id] ?: 0L)",
     "                    if (timedById.containsKey(id)) duplicatedIds += id\n"
     "                    timedById[id] = (timedById[id] ?: 0L) + nanos",
     unit_suite, "a test id recorded twice contributes one duration"),
    ("any outcome that is not NONE is a test again, duration and all", AUDIT_COMPUTE,
     "        if (parts[OUTCOME] !in TEST_OUTCOMES && parts[OUTCOME] !in NON_TEST_OUTCOMES) {",
     "            if (false) {",
     unit_suite, "a record whose outcome is not an outcome is malformed"),
    # The statement, not the arithmetic. `meanShare` counts tests rather than seconds, so break-even
    # assumes the selected tests are of average length -- true of no suite with a few long
    # integration tests. The assumption is only honest where the number is, and a line that loses it
    # reads as a measurement.
    ("the break-even line stops saying it counts tests rather than seconds", PAYBACK,
     '"than seconds -- so it assumes the tests a change selects are of average length, " +',
     '"than seconds. " +',
     unit_suite, "the assumption about test length sits in the line that makes it"),
    # The build-wide payback aggregate had no mutation at all: nine tests and nothing proving any
    # of them could fail. Its failure mode is going QUIET -- an aggregate that reads nothing prints
    # a refusal, which looks exactly like a build nobody has audited yet.
    ("the build-wide aggregate reads no task records and refuses instead", PAYBACK,
     "        val dirs = mapRoot.listFiles()?.filter(File::isDirectory).orEmpty().sortedBy { it.name }",
     "        val dirs = emptyList<File>()", unit_suite,
     "a task record round-trips through the file a sibling audit reads"),
    # The weighting is the whole of the unit: without it the aggregate answers best-case, which is
    # the one thing it exists not to do.
    ("the aggregate stops weighting the saving by the forced-run share", PAYBACK,
     "        val expected = (saved * (1.0 - share)).toLong()",
     "        val expected = saved", unit_suite,
     "the aggregate moves when the forced-run share moves"),
    # And the refusal that keeps an unweighted figure from being published at all.
    ("an unsupplied forced-run share is defaulted instead of refused", PAYBACK,
     "        val share = forcedShare?.let {", "        val share = (forcedShare ?: \"0.0\").let {",
     unit_suite, "a project whose forced-run share is unknown reports no net figure"),
    # A torn or unreadable record read as a task that never ran, which shrinks the denominator.
    ("an unreadable task record goes back to being dropped", PAYBACK,
     '                    dir.name, missing = "its record names no task, so it was written mid-run",',
     "                    dir.name, missing = null,", unit_suite,
     "an unreadable task record is a refusal, not a task that never ran"),
    # The per-row attribution stamp. Written per instrument version it labelled two quantities the
    # same, which is what put the retracted summing formula in a published column unmarked.
    ("the attribution stamp goes back to one label for every row", CONFIG_COST,
     "        attribution = SUMMED_PARTS_ATTRIBUTION if attributed is not None else NO_TIMERS",
     "        attribution = \"containing-timer\"", config_cost_suite,
     "a row with no containing timer was stamped"),
    # The instrument that re-took a published table. Reverting this made every row read 2x high and
    # the residual go negative, and nothing in this harness pointed at the file.
    ("the configure-cost total goes back to summing a timer with the timers inside it", CONFIG_COST,
     "    attributed = mechanisms.get(CONTAINING_TIMER)\n",
     "    attributed = None\n",
     config_cost_suite,
     "the container was summed with its contents"),
    ("a map with no durations reports zero seconds instead of unknown", PAYBACK,
     "        if (timedRecords == 0) return null",
     "        if (false) return null", unit_suite,
     "a map whose records carry no duration says so"),
    ("an unknown worker count is assumed to be one", PAYBACK,
     "            workers = workers,",
     "            workers = workers ?: 1,", unit_suite,
     "an unknown worker count is reported as unknown"),
    # The comparison, not a constant. `if (false)` was tried at both sites below and did not
    # compile: the first body reads `instrumented` through a smart cast the constant condition
    # discards, and the second returns before a `cost` the code after it dereferences. A mutation
    # that does not compile proves nothing about the guard it was aimed at, so both are rewritten as
    # defects that do: a flipped comparison, and a refusal that names the wrong missing input.
    ("the payback verdict divides a figure larger than the run it came from", PAYBACK,
     "        if (instrumented != null && wallClockNanos > instrumented * 1_000_000_000L) {",
     "        if (instrumented != null && wallClockNanos < instrumented * 1_000_000_000L) {",
     unit_suite, "recorded time above the observed wall clock is refused"),
    # The refusal names the WRONG missing input, which is the realistic defect: every branch here
    # returns prose, and prose that names the wrong arm sends the reader to run the wrong one.
    ("a missing configure cost is reported as a missing duration", PAYBACK,
     '        if (cost == null) {\n'
     '            return listOf(\n'
     '                "PAYBACK NOT ANSWERED: no configure cost has been measured',
     '        if (cost == null) {\n'
     '            return listOf(\n'
     '                "PAYBACK NOT ANSWERED: this map records no per-test durations',
     unit_suite, "no configure cost refuses by naming that input"),
    ("a real sub-second duration rounds to zero seconds again", PAYBACK,
     "        nanos >= 1_000_000L -> String.format(Locale.ROOT, \"%dms\", nanos / 1_000_000L)\n"
     "        else -> String.format(Locale.ROOT, \"%.2fms\", nanos / 1_000_000.0)",
     "        else -> String.format(Locale.ROOT, \"%.1fs\", nanos / 1_000_000_000.0)", unit_suite,
     "a real duration under a second is never printed as zero"),
    ("the pay verdict is stated in the wrong direction", PAYBACK,
     "            if (savedNanos > costPerBuild) {",
     "            if (savedNanos < costPerBuild) {", unit_suite,
     "a configure cost larger than the saving says selection cannot pay"),
    ("the per-task configure cost truncates to whole milliseconds again", PAYBACK,
     "        val perTestTaskNanos: Long get() = nanos / testTasks.coerceAtLeast(1)",
     "        val perTestTaskNanos: Long get() =\n"
     "            (nanos / 1_000_000L / testTasks.coerceAtLeast(1)) * 1_000_000L", unit_suite,
     "a configure cost under a millisecond per task is not rounded away"),
    # THE FINDING REVIEW CAUGHT, and the guard that stops it coming back. The change set is also the
    # ANCHOR SET for `Selector.isNestedUnder`: a digest-derived name in it can certify a prefix
    # nothing covers and suppress the full run that absence of coverage requires. So digest-derived
    # names reach the selector as a separate set, and this mutation is the union returning on the
    # daemon side; the agent-side half is "changed bytes join the change set the selector anchors on".
    ("digest-derived names go back into the change set the selector anchors on", SELECTION,
     "        (prefixes + inliners).distinct().sorted(),",
     "        (prefixes + inliners + digest?.added.orEmpty()).distinct().sorted(),",
     unit_suite, "a digest-derived name never reaches the change set the selector anchors on"),
    # The other half of the same review. A test that fails alone twice of three is mostly a real
    # miss, and erasing it is a recall figure improving for the wrong reason.
    ("a majority that fails alone is erased from the miss count again", PY_ISOLATION,
     "            if alone.count(FAILS_ALONE) > alone.count(PASSES_ALONE):\n                continue\n",
     "",
     # The PRINTED check, not the case name: `_python_checks` returns the `  FAIL:` lines a case
     # emits, so naming the function here reported the guard as proving a different test -- which
     # is the harness catching its own author, and the reason that check exists.
     python_suite, "failed alone twice of three was erased"),
    # The control arm, and the inversion is the one that would be believed: a test that PASSES with
    # the change reverted is the textbook induced failure, so disqualifying on that answer would
    # erase precisely the real misses while leaving the environmental ones in the count.
    ("the control arm disqualifies on the wrong answer", PY_ISOLATION,
     '                                 phase="control arm") == FAILS_ALONE:',
     '                                 phase="control arm") == PASSES_ALONE:',
     python_suite, "failing alone with the change reverted did not disqualify"),
    # The other direction of the same rule. Reading a failed build as "passed alone" whenever the
    # results mention ANY failure -- including this test's own -- would clear real misses wholesale.
    ("an unexplained failed build is read as an answer again", PY_ISOLATION,
     "    if {other for other in failed if other != test}:",
     "    if True:",
     python_suite, "an unexplained failed build was read as an answer"),
    # The inversion that would be believed: `>` instead of `>=` lets the exactly-equal row through,
    # which is the ordinary forced row and the common case.
    ("a forced row can report a miss again", PY_SRC,
     "    if not missed or len(selected_ran) < len(full_ran):",
     "    if not missed or len(selected_ran) <= len(full_ran):",
     python_suite, "exactly as many"),
    # THE MISS-POSSIBLE POPULATION. The refusal once counted `narrowed_rows` while
    # printing "rows where a miss was possible" -- a strictly wider population, because a row can
    # narrow without breaking anything. An arm made of those would have passed having asked nothing.
    ("the reduced arm counts narrowed rows instead of miss-possible ones", SWEEP_SRC,
     '        "miss_possible_rows": sum(1 for r in checked\n'
     '                                  if r.get("full_failures") and assert_recall.answered_failures(r)\n'
     '                                  and assert_recall.narrowed_at_all(r)),',
     '        "miss_possible_rows": sum(1 for r in checked\n'
     '                                  if assert_recall.narrowed_at_all(r)),',
     sweep_suite, "only the row that both narrowed AND broke something"),
    # And the build files the old pathspec covered. Dropping them was the review's sharpest find:
    # `agent/build.gradle.kts` pins the ASM version the agent instruments with.
    ("the digest stops covering the plugin and agent build files", PY_SRC,
     'SELECTOR_SOURCE_FILES = ("plugin/build.gradle.kts", "core/build.gradle.kts", "agent/build.gradle.kts")',
     'SELECTOR_SOURCE_FILES = ()',
     python_suite, "did not move the digest"),
    # THE SELECTOR DIGEST. It decides whether a results file still describes the
    # selector in the tree, so getting it wrong publishes rows measured by a different selector as
    # current evidence -- the exact failure the stale check exists to prevent, wearing its name.
    ("the digest stops covering the agent, so agent-only changes read fresh", PY_SRC,
     'SELECTOR_SOURCE_DIRS = ("plugin/src/main", "core/src/main", "agent/src/main")',
     'SELECTOR_SOURCE_DIRS = ("plugin/src/main", "core/src/main")',
     python_suite, "did NOT move the digest"),
    # And the absent-digest REASON. Note what this guard does NOT claim: with the branch removed
    # the verdict is still stale, because `None != current` is also true -- so safety does not
    # depend on it. What it buys is a reason that says the digest is absent rather than one that
    # prints `None` as though it were a selector somebody could look up. Claiming more than that
    # would be a guard asserting a property it cannot lose.
    ("the absent-digest refusal stops saying the digest is absent", CORPUS_SRC,
     "    if digest is None:\n        return {**entry, \"verdict\": STALE_STAMP,",
     "    if False:\n        return {**entry, \"verdict\": STALE_STAMP,",
     corpus_suite, "does not say the digest is absent"),
    # THE EVIDENCE REQUIREMENT ON A DENOMINATOR EXCLUSION. Honouring a bare `true`
    # would let a project leave the recall denominator while stating nothing, which is the silent
    # sampling change this corpus has already withdrawn a figure over.
    ("a guest can leave the denominator without stating why", CORPUS_SRC,
     "        if not stated:",
     "        if False:",
     corpus_suite, "excluded a project while stating no evidence"),
    # An unstamped file counted as drawn under the current grammar is the matching claim nothing
    # supports.
    ("an unstamped grammar is counted as the current one", CORPUS_SRC,
     '    under = [e for e in counted if e.get("grammar") == current]',
     '    under = [e for e in counted if e.get("grammar") in (None, current)]',
     corpus_suite, "only the matching file counted as drawn under it"),
    # THE UNMERGED-CAPTURE DISTINCTION. Collapsing it back is silent: both
    # cases print "no map", one of them wrongly blaming the guest for a defect of ours.
    ("an unmerged capture is reported as a missing map again", PY_SRC,
     "        if os.path.isdir(os.path.join(directory, \"raw\")):\n            return directory",
     "        if False:\n            return directory",
     python_suite, "the unmerged capture was not found"),
    # AND THE OTHER HALF OF THE CONTAINER CATEGORY, found by review: a container subtracted from
    # `missed` reappeared as a test that RAN AND PASSED, so one undecidable thing was published in
    # two lists that contradict each other.
    ("a container is republished as a test that ran and passed", PY_SRC,
     "    skip = (set(missed) | set(undetermined) | set(nondeterministic) | set(container_failed)\n"
     "            | set(shared_key))",
     "    skip = (set(missed) | set(undetermined) | set(nondeterministic)\n"
     "            | set(shared_key))",
     python_suite, "published as ran-but-passed"),
    # THE CONTAINER CATEGORY. It SUBTRACTS from the miss count, which is the one
    # direction that needs a guard: a rule that quietly took real misses with it would improve every
    # recall figure this corpus publishes and nothing would look wrong.
    # The inversion that matters: route every miss into the category, not only containers.
    ("the container rule swallows every miss, not only containers", PY_SRC,
     "    containers = [test for test in missed if is_container_failure(test[1])]",
     "    containers = list(missed)",
     python_suite, "a real miss beside a container"),
    # And the scope removed, so a forced row is re-decided by a second rule after the first
    # already disqualified it.
    ("the container split stops being scoped to narrowed rows", PY_SRC,
     "    if len(selected_ran) >= len(full_ran):\n        return missed, []",
     "    if False:\n        return missed, []",
     python_suite, "was re-decided"),
    # THE COMPILE GATE. It is the one change here that can take a row out of a count
    # without anybody seeing it, which is exactly why it ships with these three.
    # The inversion that would be believed: the gate simply stops rejecting, and every sweep looks
    # the way it did before -- `build-failed` rows and all.
    ("the compile gate stops rejecting anything", PY_SRC,
     "    return code, compiler_rejected_the_source(log_path)",
     "    return code, False",
     python_suite, "a compile error"),
    # And the plausible mis-write of the same line: `!= 0` swallows a KILLED build as a rejected
    # mutation, which files a stalled sweep as a grammar defect and loses the stall entirely.
    ("a killed build is read as a rejected mutation", PY_SRC,
     "    if code in (0, STALLED):\n        return code, False",
     "    if code == 0:\n        return code, False",
     python_suite, "a killed build"),
    # AND THE GUEST'S OWN BROKEN BUILD IS NOT THE GENERATOR'S FAULT: dropping the diagnostic check
    # launders an OOM or a dead daemon into a grammar defect and takes the row out of build-failed.
    ("any non-zero exit is laundered into a generator rejection", PY_SRC,
     "    return code, compiler_rejected_the_source(log_path)",
     "    return code, True",
     python_suite, "no compiler diagnostic"),
    # The ceiling raised until it cannot fire. The ceiling exists because the gate converts the
    # signal that exposed every grammar defect -- rows and whole sweeps lost -- into a silent
    # discard.
    ("the rejection ceiling is raised until it never refuses", PY_SRC,
     "MUTATION_REJECTION_CEILING = 0.2",
     "MUTATION_REJECTION_CEILING = 1.0",
     python_suite, "one-line braces"),
    # The suffix regression: mockito and rxjava lost five rows apiece to
    # "error: integer number too large" because the bump dropped the `L` off a long constant.
    ("the numeric bump drops the type suffix again", MUTATIONS_SRC,
     '        suffix = text[-1] + suffix\n', '', mutations_suite,
     "-1172445254382395149L bumps to -1172445254382395148L"),
    # And the half the suffix did not cover. A hex literal may fill the full unsigned width and
    # wrap into the signed range; the same value written in decimal is simply out of range.
    ("a hex literal is re-rendered as decimal again", MUTATIONS_SRC,
     "    if base != 10:", "    if False:", mutations_suite,
     "0x9E3779B9 bumps to 0x9E3779BA"),
    # ------------------------------------------------ the bytes rule: changed bytes select, apart
    #
    # A class whose bytes changed with no source change (a weaver, a post-processor) selects
    # the tests that recorded it, through a set kept apart from the change set. Every one of these
    # is a way for the rule to go QUIET, and quiet here is a skipped test: no answer looks exactly
    # like "the output agrees with the map".
    ("the digest comparison stops noticing that a class moved", SELECTION,
     "                if (recorded[name] != value) added += name",
     "                if (false) added += name",
     unit_suite,
     "a class whose bytecode moved since the capture is handed to the selector apart from the "
     "change set"),
    # An `expect` default's bytes live in the `actual`'s class, which no changed path names: in a
    # real multiplatform build only this comparison runs the default's caller.
    ("the digest comparison stops noticing an expect default", SELECTION,
     "                if (recorded[name] != value) added += name",
     "                if (false) added += name",
     functional_suite, "a changed expect default runs its caller"),
    # ------------------------------------------------ the release SBOM: it lists what the jars carry
    #
    # An SBOM that drifts from the jar it describes is worse than none, and nothing a user runs would
    # notice: the check on every build is what notices, so each way of drifting must trip it.
    ("the SBOM drops a library the plugin jar bundles", PLUGIN_BUILD,
     '.filterNot { "project_path=" in it["purl"] as String }',
     '.filterNot { "project_path=" in it["purl"] as String }.filter { it["name"] != "asm-tree" }',
     sbom_suite, "the SBOM lists"),
    ("the SBOM lists the core module as a library the plugin jar bundles", PLUGIN_BUILD,
     '.filterNot { "project_path=" in it["purl"] as String }', '',
     sbom_suite, "the SBOM lists"),
    ("the SBOM stops naming where ASM was relocated", PLUGIN_BUILD,
     'val packageOf = mapOf("org.jacoco:org.jacoco.core" to "org.jacoco.core", "org.ow2.asm" to "org.objectweb.asm")',
     'val packageOf = mapOf("org.jacoco:org.jacoco.core" to "org.jacoco.core")',
     sbom_suite, "the plugin jar carries"),
    # The Portal refuses a version that drops a feature declaration an earlier one made, after the
    # signed files are sent; the upload must carry what the jar's descriptor declares.
    ("the release upload stops sending the jar's declared features", PLUGIN_BUILD,
     "    requests.forEach { request -> features.forEach { (feature, state) -> request.addFeatureCompatibility(feature, state) } }",
     "    requests.forEach { request -> features.size }",
     portal_suite, "FAIL: the request does not declare the configuration cache"),
    ("the SBOM goes out without the serial number GitHub's attestation requires", PLUGIN_BUILD,
     '            "serialNumber" to serial) + bom',
     '            "serialNumber" to null) + bom',
     sbom_suite, "the SBOM lacks bomFormat, specVersion or serialNumber"),
    ("the selector stops selecting on changed bytes", SELECTOR,
     "        Set<String> changedBytes = bytesOnly(change.changedBytes(), changedClassPrefixes);",
     "        Set<String> changedBytes = new HashSet<>();",
     agent_suite, "a class whose bytes changed selects the tests that recorded it and no others"),
    # An edited test class JaCoCo could not instrument records nothing of itself, so its known tests
    # run by a rule of their own, not through their coverage of it.
    ("an edited own test class's known tests are not selected", SELECTOR,
     "            if (ofChangedOwnClass(test.testId(), ownTestClasses, changedClassPrefixes)) {",
     "            if (false) {",
     agent_suite, "an edited test class with no coverage of itself runs its known tests"),
    ("the own-class rule stops being recorded", SELECTOR,
     "                add(byTest, test.testId(), Rule.OWN_CLASS_CHANGED);",
     "",
     agent_suite, "an edited test class names own-class-changed on its tests"),
    ("the plugin stops handing changed bytes to the test JVM", SELECTING_STEP,
     "                CHANGED_BYTES_PROPERTY to if (widening.forces) emptySet() else widening.changedBytes,",
     "                CHANGED_BYTES_PROPERTY to emptySet<String>(),",
     functional_suite, "a class rewritten after compilation selects the tests that recorded it"),
    # A suite that runs builds writes thousands of files under buildSrc's or a TestKit project's
    # `build/`. Counted as changes they force every run, and on spring-boot they made a command line
    # the OS refused, so the test JVM never started.
    ("the Kotlin plugin's session markers enter the change set again", WORKING_TREE,
     '".gradle" in directories || ".kotlin" in directories ||',
     '".gradle" in directories ||',
     unit_suite, "build output and gradle state are not snapshotted"),
    ("build output of another Gradle build enters the change set again", WORKING_TREE,
     "                directories[i] == \"build\" && isGradleBuild(",
     "                false && isGradleBuild(",
     functional_suite, "build output of another Gradle build below the root is not a change"),
    # A selecting run filters the working tree once per build while configuring, memoised by build
    # root and excluded directories. Keyed without the root, a build sharing the memo would be
    # handed another's listing and miss its own new ignored files.
    ("one build's working-tree listing is handed to another", WORKING_TREE_GRADLE,
     '    val key = YoriwakePlugin.WORKTREE_KEY + rootDir.path + "\\u0000" +',
     '    val key = YoriwakePlugin.WORKTREE_KEY +',
     unit_suite, "two builds in one memo each get their own working-tree listing"),
    # Filtered once per `Test` task, the listing took minutes on spring-boot's 527 tasks.
    ("the working tree is filtered once per Test task again", WORKING_TREE_GRADLE,
     "    return memo.value(key) { memo.time(YoriwakePlugin.WORKTREE_LISTING_COUNTER, filtered) }",
     "    return memo.time(YoriwakePlugin.WORKTREE_LISTING_COUNTER, filtered)",
     guard_suite, "the git command set does not grow with the number of projects"),
    ("the unmappable paths are passed on the test JVM's command line again", SELECTING_STEP,
     "        writeChangeSet(\n            host, changeSetFile,",
     "        host.property(UNMAPPABLE_PATHS_PROPERTY, scoped.change.unmappablePaths.sorted().joinToString(\",\"))\n"
     "        writeChangeSet(\n            host, changeSetFile,",
     functional_suite, "a change set longer than a command line still starts the test JVM"),
    # The change set left the test JVM's command line, and with it the task's inputs: Gradle then
    # found a task whose change had grown to a forcing one up to date, and ran nothing.
    ("the change set stops being an input of the test task", SELECTION_WIRING,
     "    test.inputs.property(\n        \"yoriwake.changeSet\",",
     "    if (false) test.inputs.property(\n        \"yoriwake.changeSet\",",
     functional_suite, "a change in a module this task cannot see selects nothing"),
    # The change set is fixed at configuration. A task earlier in the same build that writes into the
    # tree, or a continuous build's later cycle, changes a resource no class change reveals, and the
    # test that reads it was skipped on the old change set.
    ("a tree edited after configuration selects on the configured change set", SELECTION_WIRING,
     "    refuseAStaleChangeSet(project, test, mapDir, against, stamp, paths, jacoco, changeSetFile, buildMemo, observing, notReadByTests)",
     "    if (false) refuseAStaleChangeSet(project, test, mapDir, against, stamp, paths, jacoco, changeSetFile, buildMemo, observing, notReadByTests)",
     functional_suite, "a file edited after configuration runs the whole suite"),
    # The configuration-time listing is shared by every task of a build; the staleness check's is
    # not. An ignored file a task writes between two `Test` tasks is seen only by the later task's
    # own fresh listing, which `git diff` cannot stand in for.
    ("the staleness check stops listing ignored files afresh", SELECTION_WIRING,
     "            val drift = WorkingTree.drift(rootDir, mapDir, excluded) as? WorkingTree.Drift.Moved",
     "            val drift = WorkingTree.Drift.Moved(emptySet()) as? WorkingTree.Drift.Moved",
     functional_suite,
     "a task between two test tasks that adds an ignored file makes the later one run everything"),
    # The base was widened only to the common ancestor with the capture stamp. A map captured on
    # another branch, then used on one forked before that branch's commit, recorded a resource the
    # ancestor shares with the tree, and the test that reads it was skipped.
    ("a map captured off this history selects without the capture commit's diff", SELECTION_SETUP,
     "        val stamp = age.stamp\n",
     "        val stamp: String? = null\n",
     functional_suite, "a map captured on another branch counts what differs from its capture commit"),
    # The comparison left out every path under build output or Gradle's state, tracked ones too, so
    # an edit to a committed TestKit fixture under a `build/` selected on the configured change set.
    ("a tracked file under a build directory is compared as build output", SELECTION_WIRING,
     "        val tracked = listed?.first.orEmpty().toSet()",
     "        val tracked = emptySet<String>()",
     functional_suite, "a tracked file under a build directory edited after configuration"),
    ("a change set git cannot compute again at execution selects on the configured one",
     SELECTION_WIRING,
     "        if (now == configured) {\n            return@doFirst",
     "        if (now == configured || now == null) {\n            return@doFirst",
     functional_suite, "a change set git cannot compute again when the task runs"),
    # Fallbacks never overwrite a map recorded in isolation. The run git could not report changes
    # for kept capture as configured, so its decode merged shared-JVM records into that map.
    ("a run git cannot report changes for rewrites an isolated map", SELECTING_STEP,
     '        // left as it is, as by every other fallback.\n        applyCapture(\n',
     '        // left as it is, as by every other fallback.\n        if (false) applyCapture(\n',
     functional_suite, "a selecting run git cannot report changes for leaves an isolated map alone"),
    ("a change-set file that cannot be read narrows as an empty change set", FILTER_RULE,
     "            if (AgentContract.REFUSED_PROPERTY.equals(name)) {\n                return refusal;",
     "            if (AgentContract.REFUSED_PROPERTY.equals(name)) {\n                return null;",
     agent_suite, "a change-set file that is missing or cut short refuses"),
    # A DRY RUN. spring-boot-webmvc-test's selecting arm once never started a test JVM, and its
    # eight rows were filed as mutations that broke nothing.
    ("a selecting arm that decided nothing is filed as a mutation that broke nothing", PY_SRC,
     "    if decisions is not None or selected_ran:\n        return None",
     "    if True:\n        return None",
     sweep_suite, "an arm with no decision record and no test run was not invalid"),
    ("invalid rows count in the summary's figures", SWEEP_SRC,
     '    rows = [r for r in rows if r.get("status") != "invalid"]',
     "    rows = list(rows)",
     sweep_suite, "invalid rows were counted as rows"),
    ("the charts stop saying why they left rows out", README_CHARTS,
     '                skipped[why] = skipped.get(why, 0) + 1',
     '                skipped["rows"] = skipped.get("rows", 0) + 1',
     sweep_suite, "the charts did not say how many rows they left out and why"),
    # The same dry run reused a map qualify.py had captured under an older plugin commit.
    ("a map this run did not capture is reused", SWEEP_DATA,
     "        if found.get(key) != wanted[key]:",
     "        if False:",
     sweep_suite, "a map captured with another"),
    # A reap that matched every GradleDaemon on the machine stopped other projects' builds.
    ("a reap signals every Gradle process on the machine again", OWN_PROCESSES,
     "    ours, _foreign = split()",
     "    ours = [p for group in split() for p in group]",
     preflight_suite, "signalled"),
    ("a Kotlin daemon is no longer claimed through the daemon that started it", OWN_PROCESSES,
     "            if pid not in ours and ppid in ours:",
     "            if False:",
     preflight_suite, "ours were"),
    # An idle daemon works in its own state directory, so its cwd says nothing about whose it is.
    ("an idle daemon is judged by its working directory instead of its last build", OWN_PROCESSES,
     "        if pid in last_builds:",
     "        if False:",
     preflight_suite, "idle daemons were claimed by their cwd"),
    ("the pre-flight refuses over any Gradle daemon again", PREFLIGHT,
     'MEASURING = ("bench/run.py", "sweep_corpus.py")',
     'MEASURING = ("GradleDaemon", "bench/run.py", "sweep_corpus.py")',
     preflight_suite, "expected run.py"),
    ("the pre-flight stops naming a loaded machine", PREFLIGHT,
     "    if load is not None and cpus and load > BUSY_SHARE * cpus:",
     "    if False:",
     preflight_suite, "was not named"),
    ("changed bytes no test recorded stop forcing", SELECTOR,
     "            if (!recordedByAny(map.entries(), name)) {",
     "            if (false) {",
     agent_suite, "a class whose bytes changed and that no test recorded forces a full run"),
    # The nested-class rule's anchor set, from the agent's side: bytes merged into the change set
    # certify an uncovered same-named nested prefix.
    ("changed bytes join the change set the selector anchors on", SELECTOR,
     "        Collection<String> changedClassPrefixes = change.changedClasses();",
     "        Collection<String> changedClassPrefixes = new ArrayList<>(change.changedClasses());\n"
     "        changedClassPrefixes.addAll(change.changedBytes());",
     agent_suite, "changed bytes never anchor the nested-class rule"),
    # A comparison that could not run is not agreement, with a change set and without one.
    ("a missing digest table reads as unchanged bytes", SELECTION,
     "    if (bytesUnknown) return bytesRefusal(digest)",
     "    if (false) return bytesRefusal(digest)",
     unit_suite, "a missing table forces, and says it was the table"),
    ("an unfinished bytes walk reads as agreement on an empty change set", SELECTION,
     "        return if (bytesUnknown) bytesRefusal(digest)",
     "        return if (false) bytesRefusal(digest)",
     unit_suite, "a walk that could not finish forces, even on an empty change set"),
    # A test that depends on a class through a framework's scan reads its annotations and never
    # executes it, so coverage holds no edge; only the annotation digest sees `@Profile("a")` become
    # `@Profile("b")`. Treating every recorded digest as a match is the silent skip.
    ("an annotation change on a recorded class reads as unchanged again", SELECTION,
     "            if (before == current) continue",
     "            if (before != null) continue",
     unit_suite, "a changed annotation forces a full run and names it"),
    # The exemption's boundary. A test class this task runs is exempt because its own tests are
    # selected anyway; a helper in the same test output (a `@TestConfiguration`, a fixture) runs no
    # test of its own, so exempting the whole output skips whatever scans it.
    ("every class in this task's test output is exempt from the annotation refusal", SELECTION,
     "            if (prefix in ownTestClasses && Recordability.declaresTestMethod(bytes)) continue",
     "            if (prefix in ownTestClasses) continue",
     unit_suite, "an annotation change on a test helper that declares no test still forces"),
    # A framework can wire a class from its annotation alone (a Dagger module whose `@Binds` are
    # abstract, a scanned bean), so no test need load it for one to depend on it. Reading the
    # annotations and ignoring them makes an annotated class nothing loads provable by absence again.
    ("a class-level annotation stops keeping an unloaded class from narrowing", ABSENCE_EVIDENCE,
     "            if (annotations.isEmpty() || (discovered && Recordability.declaresTestMethod(bytes))) {",
     "            if (true) {",
     functional_suite, "a class-level annotation keeps a class nothing executes or loads forcing"),
    # ------------------------------------------------ once-per-JVM code and class-file readers
    #
    # A JVM runs a static initialiser, or builds a cached context, once, and coverage credits
    # it to the first test that triggers it; a test that reflects on a class or reads its class
    # file executes none of it. So a change selects every test that ran at or after its class was
    # first loaded, executed or read in that JVM. Each mutation below makes the rule go quiet, and
    # quiet is a later dependent test skipped.
    ("the selector stops selecting what ran after a changed class arrived in its JVM", SELECTOR,
     "            for (String id : ranAfterFirstTouch(map, tests, changedClassPrefixes, changedBytes, exempt)) {",
     "            for (String id : new HashSet<String>()) {",
     agent_suite, "a test that ran after its JVM first loaded a changed class is selected"),
    # At, not after: a test that loads a class in its own window and executes none of it (a
    # reflective check) is dated at its own record.
    ("a test whose own window first touched the class is no longer at or after it", SELECTOR,
     "                if (first != null && position.sequence() >= first) {",
     "                if (first != null && position.sequence() > first) {",
     functional_suite, "a test that only introspects a class another test executes is selected when it changes"),
    ("a test the map cannot place is let go", SELECTOR,
     "            if (positions.isEmpty()) {\n                after.add(test.testId());",
     "            if (positions.isEmpty()) {\n                if (false) after.add(test.testId());",
     agent_suite, "a test the map holds no position for runs whenever something changed"),
    ("a JVM whose loads and reads were not all observed reads as touching nothing", SELECTOR,
     "        if (AgentContract.FIRST_TOUCH_ANY.equals(className) || changedBytes.contains(className)) {",
     "        if (changedBytes.contains(className)) {",
     agent_suite, "a JVM whose touches were not all observed selects every test that ran in it"),
    # The absence rule's side of it: a class a reader parsed was never loaded, so the loaded union
    # alone would call it untested.
    ("a class only read as a class file is proven untested by its absence again", SELECTOR,
     "            if (AgentContract.FIRST_TOUCH_ANY.equals(touch.className())\n"
     "                    || matchesSourceFile(touch.className(), prefix, changedClassPrefixes)) {\n"
     "                return false;",
     "            if (false) {\n                return false;",
     agent_suite, "a class some JVM read the class file of is not proven untested by its absence"),
    # A class defined from bytes no file holds (a proxy, a generated subclass) is seen only here;
    # one loaded from a class directory or a jar is also read through the read hooks.
    ("the agent stops seeing class loads", TOUCH_RECORDER,
     "                        recorder.loaded(name);",
     "                        name.length();",
     agent_suite, "a class definition is a first touch, and a redefinition of a loaded class is not"),
    ("the read hooks are installed with no sink behind them", READ_HOOKS,
     '            holder.getField("SINK").set(null, reads);',
     "            holder.getClass();",
     functional_suite, "a test that reads a class file is selected when the class it reads changes"),
    ("an observation the agent could not complete is filed as complete", CAPTURE_SESSION,
     "        if (incomplete != null && !touchesIncomplete) {",
     "        if (false) {",
     agent_suite, "an observation that is not complete says so once, at the first record"),
    # Without the agent attached nothing is observed, which must read as "everything touched".
    ("the agent is no longer attached to a JUnit Platform test JVM", TEST_TASK_WIRING,
     '                (task as Test).jvmArgs("-javaagent:$path")',
     "                (task as Test).path",
     functional_suite, "a test that ran before the changed class was first loaded in its JVM stays skipped"),
    ("a carried failure is matched by the escaped id", COVERAGE_DECODER,
     "        val idOf = { line: String -> Tsv.unescape(testIdOf(line)) }",
     "        val idOf = { line: String -> testIdOf(line) }",
     decoder_suite, "a carried failure finds its test by the id the map escaped"),
    ("touches with no end marker are trusted", COVERAGE_DECODER,
     "        if (lines == null || lines.lastOrNull { it.isNotBlank() } != TOUCHES_COMPLETE) {",
     "        if (lines == null) {",
     decoder_suite, "touches without their end marker touch every class from the first record"),
    ("a class read as a file is no longer dated", COVERAGE_DECODER,
     "                TOUCH_READ -> namesForPath(parts[2]).forEach(named)",
     "                TOUCH_READ -> Unit",
     decoder_suite, "a load or a class-file read before a record dates the class at that record"),
    ("a class's first execution no longer dates it", COVERAGE_DECODER,
     "            inScopeClasses.forEach { touch(it, sequence) }",
     "            inScopeClasses.size",
     decoder_suite, "each test is placed at its sequence in its worker's JVM"),
    # A merge keeps an old test's position only with the touches of the JVM it ran in, and two
    # captures must never share a JVM id, or one capture's order dates the other's tests.
    ("a merge drops the positions of tests this run did not recapture", COVERAGE_DECODER,
     "            existing(POSITIONS_FILE).filter { Tsv.split(it).last() !in recaptured }",
     "            existing(POSITIONS_FILE).filter { false }",
     decoder_suite, "a recaptured test replaces its position and a retained one keeps its JVM's touches"),
    ("two captures give their workers the same JVM id", COVERAGE_DECODER,
     "        val run = UUID.randomUUID().toString().substring(0, 8)",
     '        val run = "run"',
     decoder_suite, "a recaptured test replaces its position and a retained one keeps its JVM's touches"),
    # A test retried by test-retry or Develocity runs again in a new JVM of the same task, so one
    # capture can hold a pass and a failure for it. Written as passing, it would be skipped
    # by a change it does not reach; read in the rounds' order, a test that passed and then failed
    # would keep a passing line beside its failing one, and the map would not say it was flaky.
    ("a flaky test is written as passing", COVERAGE_DECODER,
     "                OUTCOME_FLAKY + line.substring(was.length)\n",
     "                OUTCOME_SUCCESSFUL + line.substring(was.length)\n",
     functional_suite, "a test that passed and then failed when its class was retried is flaky, not successful"),
    ("the flaky rule reads the rounds' order", COVERAGE_DECODER,
     "            .filterValues { OUTCOME_SUCCESSFUL in it && OUTCOME_FAILED in it }\n",
     "            .filterValues { it.first() == OUTCOME_FAILED && OUTCOME_SUCCESSFUL in it }\n",
     decoder_suite, "a test that passed and then failed is flaky too, whichever JVM ran first"),
    # the test-class exemption. A test class that declares a test and that nothing else names
    # is instantiated by its engine alone, so its load at discovery opens no window; a lookup by
    # name, a read after discovery or another class's execution of it does. Each mutation below
    # lets such a class narrow when something could reach it.
    ("a test class another class names is exempt again", SELECTION,
     "            !artifacts.namedOutsideItself(prefix)",
     "            true",
     functional_suite, "a test class production code names is not exempt, so its edit selects every test in its JVM"),
    ("the selector dates an exempt class the map holds no test of by named touches", SELECTOR,
     "                if (prefix.equals(testClassOf(id))) {\n                    known.add(prefix);",
     "                if (true) {\n                    known.add(prefix);",
     agent_suite, "a class called exempt that the map holds no test of is dated like any class"),
    ("named touches stop dating an exempt test class", SELECTOR,
     "            for (MapReader.FirstTouch touch : map.namedTouches()) {",
     "            for (MapReader.FirstTouch touch : new ArrayList<MapReader.FirstTouch>()) {",
     agent_suite, "an exempt test class opens its window at its first named touch"),
    # Its own code can hand its Class to a later test, and project code run during discovery is
    # handed every test class; neither leaves a touch, so both date the class.
    ("the selector stops dating an exempt test class by its own first test", SELECTOR,
     "            if (ofChangedOwnClass(test.testId(), exempt, exempt)) {",
     "            if (false) {",
     agent_suite, "an exempt test class opens its window at its own first test, not at its discovery load"),
    ("a test class's own first test stops being recorded as a named touch", COVERAGE_DECODER,
     "                owners.firstOrNull()?.let { if (inScope(it)) touchNamed(it, sequence) }",
     "                owners.size",
     decoder_suite, "a test class's own first test dates it, and a later capture of its tests elsewhere keeps that"),
    ("project code run during discovery stops dating what discovery touched", COVERAGE_DECODER,
     "            discovery.touched.forEach { touchNamed(it, 0) }",
     "            discovery.touched.size",
     functional_suite, "a test that depends on what a class orderer read from an edited test class at discovery runs"),
    ("the lookup hooks are installed with no sink behind them", READ_HOOKS,
     '            holder.getField("LOOKUP").set(null, lookups);',
     "            holder.getClass();",
     functional_suite, "a test class looked up by a name built at runtime is dated at that lookup"),
    ("lookups and reads after discovery stop dating a test class", COVERAGE_DECODER,
     "if (inScope(name)) { touch(name, sequence); if (planStarted) touchNamed(name, sequence) } }",
     "if (inScope(name)) { touch(name, sequence) } }",
     decoder_suite, "lookups and reads during discovery do not date a test class, and those after do"),
    ("another class's execution of a test class stops dating it", COVERAGE_DECODER,
     "            inScopeClasses.filter { name -> owners.none { ownedBy(name, it) } }.forEach { touchNamed(it, sequence) }",
     "            owners.size",
     decoder_suite, "lookups and reads during discovery do not date a test class, and those after do"),
    ("touches with no plan start are read as though discovery ended at once", COVERAGE_DECODER,
     "        if (!planStarted) touchNamed(FIRST_TOUCH_ANY, 0)",
     "        if (false) touchNamed(FIRST_TOUCH_ANY, 0)",
     decoder_suite, "touches that never marked the plan's start date every test class from the start"),
    ("a lookup the agent could not observe stops dating every test class", COVERAGE_DECODER,
     "                TOUCH_LOOKUP -> if (parts[2] == FIRST_TOUCH_ANY) touchNamed(FIRST_TOUCH_ANY, if (planStarted) sequence else 0)",
     "                TOUCH_LOOKUP -> if (parts[2] == FIRST_TOUCH_ANY) Unit",
     decoder_suite, "a lookup the agent could not observe dates every test class from there"),
    ("an engine not known to own its classes no longer falls back", COVERAGE_DECODER,
     "        if (unknownEngine) touchNamed(FIRST_TOUCH_ANY, 0)",
     "        if (false) touchNamed(FIRST_TOUCH_ANY, 0)",
     decoder_suite, "a JVM that ran an engine not known to own its classes dates every test class from the start"),
    ("lookups the agent could not observe are filed as observed", CAPTURE_SESSION,
     "        if (touches.lookupsIncomplete() != null && !lookupsIncomplete) {",
     "        if (false) {",
     agent_suite, "lookups the agent could not observe are filed as a lookup of every class"),
    ("a call that can reach any class no longer counts as a lookup of every class", TOUCH_RECORDER,
     "            } else {\n                touchNamed(AgentContract.TOUCH_LOOKUP, AgentContract.FIRST_TOUCH_ANY);",
     "            } else {\n                named.hashCode();",
     agent_suite, "a call that can reach any class, such as the whole loaded set, counts as a lookup of every class"),
    # A class file read by anything but the JDK's own loaders is a read; calling every read a load
    # would drop the reader's touch.
    ("every class-file read is taken for the JDK defining a class", TOUCH_RECORDER,
     "    private static boolean definingAClass() {",
     "    private static boolean definingAClass() {\n        if (true) { return true; }",
     functional_suite, "a test that reads a class file is selected when the class it reads changes"),
    # Only the JDK's own frames may stand between a class-file read and the JDK's definition: a
    # loader override, a transformer or a URL handler running inside the definition is a reader.
    ("project code running inside a class definition is taken for the JDK reading the class", TOUCH_RECORDER,
     "                    if (!JdkCode.isJdk(frame.getDeclaringClass())) {",
     "                    if (false) {",
     agent_suite, "a class file read by project code running inside a class definition is a read"),
    # The agent's own leading frames are passed over; judging them as the project's would take every
    # JDK definition read for a reader's read.
    ("the agent's own frames are taken for project code standing inside a class definition", TOUCH_RECORDER,
     "                    if (!pastAgent && frame.getClassName().startsWith(AGENT_PACKAGE)) {",
     "                    if (false) {",
     agent_suite, "a class file read with only the JDK's frames between it and the definition is not a read"),
    # a channel the agent cannot follow is either observed or, once used, dates every class.
    # A child process runs out of sight, so it touches every class from the record it started in.
    ("a child process no longer touches every class", TOUCH_RECORDER,
     '            touch(AgentContract.TOUCH_ALL, "a child process was started");',
     '            if (false) touch(AgentContract.TOUCH_ALL, "a child process was started");',
     agent_suite, "a child process touches every class, once"),
    ("the child-process hook is installed with no sink behind it", READ_HOOKS,
     '            holder.getField("PROCESS").set(null, processes);',
     "            holder.getClass();",
     functional_suite, "a test that starts a child process selects every later test in its JVM"),
    # A second copy of a class, defined by a loader of the host's own, reaches it as a lookup does.
    ("a class another loader defines is filed as an ordinary load", TOUCH_RECORDER,
     "                        recorder.definedElsewhere(name);",
     "                        recorder.loaded(name);",
     functional_suite, "a class loader that defines its own copy of a test class dates that class"),
    ("a class another loader defined no longer dates it", COVERAGE_DECODER,
     "                TOUCH_DEFINED -> named(parts[2])",
     "                TOUCH_DEFINED -> Unit",
     decoder_suite, "a class another loader defined dates it as a lookup would, and a child process dates everything"),
    ("a class defined from bytes with no readable name is dropped", TOUCH_RECORDER,
     "                        recorder.touch(AgentContract.TOUCH_ALL, UNNAMED);",
     "                        recorder.hashCode();",
     agent_suite, "a class defined from bytes no name can be read from touches every class"),
    ("a hidden class is no longer kept by the name in its bytes", TOUCH_RECORDER,
     "                touchNamed(AgentContract.TOUCH_DEFINED, name.replace('/', '.'));",
     "                name.length();",
     agent_suite, "a hidden class defined from outside the JDK is kept by the name in its bytes, and the JDK's own are not"),
    ("a call from outside the JDK is taken for the JDK's own", TOUCH_RECORDER,
     "        return caller != null && !throughAProxy[0] && JdkCode.isJdk(caller);",
     "        return caller != null && !throughAProxy[0];",
     agent_suite, "a hidden class defined from outside the JDK is kept by the name in its bytes, and the JDK's own are not"),
    # Reflection and method-handle frames call on someone else's behalf, and a method reference's
    # proxy is hidden: judged as the caller, either made a test's own call look like the JDK's.
    ("a call made through a method handle is taken for the JDK's own", TOUCH_RECORDER,
     "                    } else if (frame != hooked && !passesOver(frame)) {",
     "                    } else if (frame != hooked) {",
     agent_suite, "a hooked method called through reflection or a method handle is judged by who called it"),
    # A proxy dispatches to a handle its maker chose: JDK code that calls the proxy did not ask
    # for the hooked call, so a walk that passed over a proxy class records it.
    ("a hooked call through a proxy JDK code calls is taken for the JDK's own", TOUCH_RECORDER,
     "        return caller != null && !throughAProxy[0] && JdkCode.isJdk(caller);",
     "        return caller != null && JdkCode.isJdk(caller);",
     agent_suite, "a hooked method reached through an interface proxy that JDK code calls is judged as the project's"),
    ("a varargs collector, a constant bootstrap or an interpreted lambda form is taken for the caller", TOUCH_RECORDER,
     '        return name.startsWith("java.lang.invoke.")',
     '        return name.startsWith("java.lang.invoke.none.")',
     agent_suite, "a hooked method called through reflection or a method handle is judged by who called it"),
    ("a method reference handed to JDK code is taken for the JDK's own", TOUCH_RECORDER,
     "                    StackWalker.Option.SHOW_HIDDEN_FRAMES)).walk(frames -> {",
     "                    StackWalker.Option.RETAIN_CLASS_REFERENCE)).walk(frames -> {",
     agent_suite, "a hooked method called through reflection or a method handle is judged by who called it"),
    # The same three, each where a real Gradle build selects the test it would skip. Showing
    # hidden frames shows reflection frames too, so the walk must pass over both: a hidden copy
    # defined through Method.invoke or a method handle, and the foreign-function API reached through
    # a method reference JDK code calls.
    ("a call made through a method handle is taken for the JDK's own, functional", TOUCH_RECORDER,
     "                    } else if (frame != hooked && !passesOver(frame)) {",
     "                    } else if (frame != hooked) {",
     functional_suite, "a hidden copy of a class defined through Method invoke dates that class"),
    ("a varargs collector, a constant bootstrap or an interpreted lambda form is taken for the caller, functional",
     TOUCH_RECORDER,
     '        return name.startsWith("java.lang.invoke.")',
     '        return name.startsWith("java.lang.invoke.none.")',
     functional_suite, "a hidden copy of a class defined through a method handle dates that class"),
    ("a method reference handed to JDK code is taken for the JDK's own, functional", TOUCH_RECORDER,
     "                    StackWalker.Option.SHOW_HIDDEN_FRAMES)).walk(frames -> {",
     "                    StackWalker.Option.RETAIN_CLASS_REFERENCE)).walk(frames -> {",
     functional_suite, "the foreign-function API reached through a method reference JDK code calls selects"),
    ("native code reached through the foreign-function API no longer touches every class", TOUCH_RECORDER,
     '                touch(AgentContract.TOUCH_ALL, "native code was reached through the foreign-function API"\n'
     '                        + (from != null ? ": from " + from.getName() : ""));',
     "                touchNamed(AgentContract.TOUCH_LOOKUP, AgentContract.FIRST_TOUCH_ANY);",
     agent_suite, "native code reached through the foreign-function API from outside the JDK touches every class"),
    # Native code reads any file out of sight, so a native library touches every class from its
    # load unless its sources were reviewed and it is loaded by the class that ships with it.
    ("a native library not on the reviewed list no longer touches every class", TOUCH_RECORDER,
     '                touch(AgentContract.TOUCH_ALL, "a native library that is not on the reviewed list was loaded: " + file\n'
     '                        + (caller != null ? " from " + caller.getName() : ""));',
     "                touchNamed(AgentContract.TOUCH_LOOKUP, AgentContract.FIRST_TOUCH_ANY);",
     agent_suite, "a native library that is not on the reviewed list touches every class from its load"),
    # A native load is judged by what the JDK opened: a by-name attempt that found no file is
    # not one, and a library the JDK did open always is.
    # netty-tcnative's BoringSSL build counts only by its bytes, against the resource of the one
    # jar that declares that artifact at a version reviewed; its OpenSSL build shares its names.
    ("a tcnative library is identified without comparing its bytes", JAR_IDENTITY,
     "                    if (x != y) {\n                        return \"\";",
     "                    if (false) {\n                        return \"\";",
     agent_suite, "a library whose bytes differ, or that no jar holds, is not identified"),
    ("the jar holding the bytes may be any artifact", JAR_IDENTITY,
     "            if (!artifact.equals(declared)) {",
     "            if (false) {",
     agent_suite, "the same bytes in a jar of the OpenSSL build or of two artifacts are not identified"),
    ("a jar declaring two artifacts identifies the library", JAR_IDENTITY,
     "                if (declared != null) {\n                    return jar.getName() + \" declares more",
     "                if (false) {\n                    return jar.getName() + \" declares more",
     agent_suite, "the same bytes in a jar of the OpenSSL build or of two artifacts are not identified"),
    ("any version of the BoringSSL build is identified", JAR_IDENTITY,
     "            return artifact + \" \" + version + \" is not a version reviewed\";",
     "            return null;",
     functional_suite, "netty-tcnative's BoringSSL build at a version nobody reviewed selects every later test"),
    ("netty-tcnative is listed by its name alone", REVIEWED_LIBRARIES,
     "\"libnetty_tcnative(_[a-z0-9_]+)?[0-9]*\\\\.(so|dylib|jnilib)\",\n            \"identity:io.netty\", \"netty-tcnative-boringssl-static\", \"2.0.84.Final\"},",
     "\"libnetty_tcnative(_[a-z0-9_]+)?[0-9]*\\\\.(so|dylib|jnilib)\"},",
     agent_suite, "netty-tcnative not identified as the BoringSSL build by its bytes touches every class from its load"),
    # The host's compression libraries count for RocksDB only at a version reviewed.
    ("a host compression library counts at any version", REVIEWED_LIBRARIES,
     "\"libz\\\\.so\\\\.1\\\\.(2\\\\.11|3)|",
     "\"libz\\\\.so(\\\\.[0-9.]+)?|",
     agent_suite, "the host's compression libraries RocksDB loads count only at a version reviewed, by RocksDB itself"),
    ("a library the JDK resolved and opened is not judged", TOUCH_RECORDER,
     "    void resolved(Object loaded) {\n        judgeLoad(loaded);\n",
     "    void resolved(Object loaded) {\n",
     functional_suite, "a test that loads a native library not on the reviewed list selects every later test in its JVM"),
    ("a load that opened nothing is judged as one", READ_HOOKS,
     "        body.visitVarInsn(Opcodes.ALOAD, 0);\n        body.visitJumpInsn(Opcodes.IFNULL, done);\n",
     "        body.visitVarInsn(Opcodes.ALOAD, 0);\n        body.visitInsn(Opcodes.POP);\n",
     # On Linux the JDK drops a missing file before this method; on macOS it reaches it.
     agent_suite, "the holder reports a library the JDK opened, and nothing for a miss"),
    ("every attempt is judged though the JDK's resolution is hooked", TOUCH_RECORDER,
     "        if (!resolving) {\n            judgeLoad(loaded);",
     "        if (true) {\n            judgeLoad(loaded);",
     agent_suite, "once the JDK's resolution is hooked, an attempt is not a load and only a library it opened counts"),
    ("the native-library hook is installed with no sink behind it", READ_HOOKS,
     '            holder.getField("RESOLVED").set(null, resolved);',
     "            holder.getClass();",
     functional_suite, "a test that loads a native library not on the reviewed list selects every later test in its JVM"),
    ("a native library the host loads is taken for one the JDK loads", TOUCH_RECORDER,
     "            if (caller != null && JdkCode.isJdk(caller)) {",
     "            if (caller != null) {",
     agent_suite, "a native library that is not on the reviewed list touches every class from its load"),
    # A scanner's own zip code reads entries no hook sees; an archive is known by its content, not
    # its name, and a jar nested in one counts too.
    # Re-anchored: the content test moved to a class the plugin shares.
    ("an archive the test opens itself counts only when its name ends in .jar", TOUCH_RECORDER,
     '            boolean archive = path.endsWith(".jar") || ZipContent.holdsAZip(file);',
     '            boolean archive = path.endsWith(".jar");',
     functional_suite, "a test that reads a class out of a zip with its own zip code is selected when the class changes"),
    ("an archive a JDK zip entry holds is taken for a plain entry", TOUCH_RECORDER,
     "                    if (entryHoldsAZip(zip, entry)) {",
     "                    if (false) {",
     agent_suite, "an archive entry that is an archive itself counts as every class in the outer archive"),
    ("a jar nested in an archive a scanner opened is not listed", COVERAGE_DECODER,
     "        if (depth > MAX_ARCHIVE_DEPTH) return null",
     "        return emptyList()",
     functional_suite, "a test that reads a class out of a jar nested in an archive with no extension is selected when it changes"),
    # The JDK's own code is the code its runtime image holds: the boot class path, a patched
    # module and an upgraded one put other code on the JDK's loaders, so the loader alone cannot say.
    ("a class outside the image's java and jdk modules is judged by its loader alone", JDK_CODE,
     "        if (!module.isNamed() || !imageModule(module.getName())) {\n"
     "            return false;\n"
     "        }\n"
     "        URL file = lookup.apply(type);\n"
     '        return file != null && "jrt".equals(file.getProtocol());',
     "        return true;",
     agent_suite, "a class on the boot class path is not the JDK's own"),
    ("a class outside the image's java and jdk modules is judged by its loader alone, functional", JDK_CODE,
     "        if (!module.isNamed() || !imageModule(module.getName())) {\n"
     "            return false;\n"
     "        }\n"
     "        URL file = lookup.apply(type);\n"
     '        return file != null && "jrt".equals(file.getProtocol());',
     "        return true;",
     functional_suite, "a native library loaded by a class on the boot class path selects every later test in its JVM"),
    ("a class outside the image's java and jdk modules is judged by its loader alone, appended at run time", JDK_CODE,
     "        if (!module.isNamed() || !imageModule(module.getName())) {\n"
     "            return false;\n"
     "        }\n"
     "        URL file = lookup.apply(type);\n"
     '        return file != null && "jrt".equals(file.getProtocol());',
     "        return true;",
     functional_suite,
     "a native library loaded by a class an agent appends to the bootstrap search selects every later test in its JVM"),
    ("a test JVM that patches an image module is observed as complete", TOUCH_RECORDER,
     "            incomplete = hooks.reads != null ? hooks.reads : JdkCode.imageAltered();",
     "            incomplete = hooks.reads;",
     functional_suite, "a test JVM that patches the JDK's base module runs every test after a change"),
    ("a patched class is taken for the JDK's own", JDK_CODE,
     '        return file != null && "jrt".equals(file.getProtocol());',
     "        return true;",
     jdk_identity_suite, "a class patched into java.base is not the JDK's own"),
    ("an image module the application loader defines is taken for the JDK's own", JDK_CODE,
     "        if (loader != null && loader != PLATFORM) {",
     "        if (false) {",
     agent_suite, "an image module the application loader defines is the project's"),
    ("a module outside java and jdk is taken for the JDK's own", JDK_CODE,
     '        return name.startsWith("java.") || name.startsWith("jdk.");',
     "        return true;",
     agent_suite, "only java and jdk modules are the JDK's"),
    # A hidden class stands for its nest host's file, and code outside the JDK can join a JDK nest.
    ("a hidden class the project defined into a JDK nest is taken for the JDK's own", JDK_CODE,
     "        if (PROJECT_DEFINED.contains(baseName(type))) {",
     "        if (false) {",
     jdk_identity_suite, "a hidden class defined from outside the JDK into a JDK nest is not the JDK's own"),
    ("a class named like a proxy is passed over", JDK_CODE,
     "        return isJdk(frame) || java.lang.reflect.Proxy.isProxyClass(frame) || methodHandleProxy(frame);",
     "        return true;",
     agent_suite, "a class that only carries a proxy's name is judged as the caller"),
    # Cost, not safety: the JDK's hidden classes judged the project's record what the JDK does.
    ("the JDK's own hidden classes count as the project's", JDK_CODE,
     "        return host != type && !hidden(host) ? host : null;",
     "        return null;",
     agent_suite, "the JDK's own hidden classes are the JDK's"),
    ("the JDK's own lambda forms count as the project's", JDK_CODE,
     '        if (host == type && type.getClassLoader() == null && "java.lang.invoke".equals(type.getPackageName())) {',
     "        if (false) {",
     agent_suite, "the JDK's own lambda forms are the JDK's"),
    # Code inside a patched or upgraded image module passes no hook, so the capture is incomplete.
    ("an upgraded image module is observed as complete", JDK_CODE,
     '            if (argument.startsWith("--upgrade-module-path")) {',
     "            if (false) {",
     agent_suite, "an upgrade module path marks the capture incomplete"),
    ("arguments that cannot be read are taken for an unaltered image", JDK_CODE,
     '            return "the JVM\'s own arguments could not be read: " + t;',
     "            return null;",
     agent_suite, "arguments that cannot be read mark the capture incomplete"),
    ("a patched system module is observed as complete", JDK_CODE,
     "            if (patchesSystemModule(argument)) {",
     "            if (false) {",
     agent_suite, "a patch of the base module marks the capture incomplete"),
    # Each entry this list gained after its first review, struck out: its test must fail.
    ("Netty's macOS DNS resolver is not reviewed", REVIEWED_LIBRARIES,
     '"libnetty_resolver_dns_native_macos(_(x86_64|aarch_64))?[0-9]*\\\\.(dylib|jnilib)"',
     '"libnetty_resolver_dns_native_macos_struck"',
     functional_suite, "Netty's macOS DNS resolver and Amazon Corretto Crypto Provider narrow as reviewed libraries"),
    ("Amazon Corretto Crypto Provider is not reviewed", REVIEWED_LIBRARIES,
     '"libamazonCorrettoCryptoProvider\\\\.(so|dylib)"',
     '"libamazonCorrettoCryptoProvider_struck"',
     functional_suite, "Netty's macOS DNS resolver and Amazon Corretto Crypto Provider narrow as reviewed libraries"),
    ("a reviewed library's file counts as reviewed whatever class loads it", REVIEWED_LIBRARIES,
     '            boolean own = loadingClass.equals(owner) || loadingClass.startsWith(owner + "$");',
     "            boolean own = true;",
     agent_suite, "a reviewed library's file loaded by any other class touches every class"),
    ("any file a reviewed class loads counts as reviewed", REVIEWED_LIBRARIES,
     "            if (own && PATTERNS[i].matcher(fileName).matches()) {",
     "            if (own) {",
     agent_suite, "every reviewed entry matches the names its loader gives the file, and nothing else"),
    # A conditional reviewed library counts only while the call that would turn its unreviewed
    # feature on is wrapped in the loader that loads it, at a version reviewed, and that call,
    # turned on, touches every class.
    ("a conditional reviewed library counts without its guard", TOUCH_RECORDER,
     "        if (entry.length == 2) {",
     "        if (entry.length > 0) {",
     agent_suite, "SQLite whose guard cannot be armed touches every class from its load"),
    ("a guarded call that turns its feature on is not recorded", TOUCH_RECORDER,
     "            if (Boolean.TRUE.equals(pair[1])) {",
     "            if (Boolean.FALSE.equals(pair[1])) {",
     agent_suite, "SQLite loaded with its guard armed counts as a lookup of every class, until a connection enables extensions"),
    ("a guard class defined without its guard is not recorded", TOUCH_RECORDER,
     "            touch(AgentContract.TOUCH_ALL, (String) why);",
     "            why.getClass();",
     agent_suite, "a guard class defined without its guard touches every class"),
    # Netty's in-tree io_uring transport counts as reviewed only for the operations Netty itself
    # submits: any other, an open among them, must report before the ring sees it.
    ("the io_uring entry check lets every operation through", GUARD_HOOKS,
     "        body.visitLookupSwitchInsn(report, allowed, targets);",
     "        body.visitInsn(Opcodes.POP);\n        body.visitJumpInsn(Opcodes.GOTO, proceed);",
     agent_suite, "an operation Netty submits goes on unreported, any other is reported before the body runs"),
    ("the io_uring entry check is not injected", GUARD_HOOKS,
     "                        writeCheck(mv, holder, argument, check[3], allowed);\n",
     "",
     agent_suite, "an operation Netty submits goes on unreported, any other is reported before the body runs"),
    ("an open is among the io_uring operations allowed", GUARD_HOOKS,
     "\"0,2,6,9,10,11,13,14,15,16,19,",
     "\"0,2,6,9,10,11,13,14,15,16,18,19,",
     agent_suite, "no operation that takes a path or names a file is allowed"),
    ("Netty's io_uring loads without its condition", REVIEWED_LIBRARIES,
     "\"libnetty_transport_native_io_uring42(_[a-z0-9_]+)?[0-9]*\\\\.so\",\n            \"io/netty/channel/uring/SubmissionQueue\",\n            \"META-INF/maven/io.netty/netty-transport-classes-io_uring/pom.properties\",\n            \"4.2.18.Final,4.2.19.Final\"},",
     "\"libnetty_transport_native_io_uring42(_[a-z0-9_]+)?[0-9]*\\\\.so\"},",
     agent_suite, "Netty's io_uring at a version nobody reviewed touches every class from its load"),
    ("Netty's io_uring is listed at a version nobody reviewed", REVIEWED_LIBRARIES,
     "\"4.2.18.Final,4.2.19.Final\"},",
     "\"4.2.17.Final,4.2.18.Final,4.2.19.Final\"},",
     functional_suite, "Netty's own io_uring transport at a version nobody reviewed selects every later test"),
    ("the wrapped native no longer reports its argument", GUARD_HOOKS,
     '        body.visitMethodInsn(Opcodes.INVOKEINTERFACE, consumer, "accept", "(Ljava/lang/Object;)V", true);\n'
     '        body.visitLabel(done);',
     "        body.visitInsn(Opcodes.POP2);\n        body.visitLabel(done);",
     agent_suite, "a wrapped native reports its argument before the native runs"),
    ("the renamed native stays public", GUARD_HOOKS,
     "                int hidden = (access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED))\n"
     "                        | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;",
     "                int hidden = access;",
     agent_suite, "the renamed native is private and synthetic"),
    ("a guard no JVM could arm counts as armed", GUARD_HOOKS,
     "        if (disarmed != null) {\n            return disarmed;",
     "        if (false) {\n            return disarmed;",
     agent_suite, "no guard is armed where native methods cannot be wrapped"),
    ("a guard class that cannot take the wrap counts as armed", GUARD_HOOKS,
     "            if (!declares(new ClassReader(in), wrap)) {",
     "            if (false) {",
     agent_suite, "a guard is armed in a loader that defined its class wrapped, and in no other"),
    ("a guard class defined without its wrap goes unreported", GUARD_HOOKS,
     '                failures.accept(className.replace(\'/\', \'.\') + " was defined without its guard: " + t);',
     "                t.getClass();",
     agent_suite, "a guard class defined without its native counts as a guard lost"),
    ("a conditional library counts at any version", GUARD_HOOKS,
     "                if (listed.equals(version)) {",
     "                if (version != null) {",
     agent_suite, "only a version reviewed, read beside the guard class, is listed"),
    ("the guard sink is never set", GUARD_HOOKS,
     '.getField("GUARD").set(null, guard);',
     '.getField("GUARD");',
     functional_suite, "a SQLite connection that enables extension loading selects every later test in its JVM"),
    # Gradle's native-platform counts as reviewed only while both natives that hide later reads,
    # a link and a working-directory change, are wrapped, at a version told apart by its natives' hash.
    ("Gradle native-platform is not reviewed", REVIEWED_LIBRARIES,
     '"libnative-platform\\\\.(so|dylib)"',
     '"libnative-platform_struck"',
     functional_suite, "a test that uses Gradle's native-platform and file events narrows as on any reviewed library"),
    ("Gradle file events is not reviewed", REVIEWED_LIBRARIES,
     '"libgradle-fileevents\\\\.(so|dylib)"',
     '"libgradle-fileevents_struck"',
     functional_suite, "a test that uses Gradle's native-platform and file events narrows as on any reviewed library"),
    ("native-platform's link native is not wrapped", GUARD_HOOKS,
     '{"net/rubygrapefruit/platform/internal/jni/PosixFileFunctions", "symlink",',
     '{"net/rubygrapefruit/platform/internal/jni/PosixFileFunctions", "symlink_struck",',
     functional_suite, "a link native-platform makes or a working directory it changes selects every later test"),
    ("native-platform's working-directory native is not wrapped", GUARD_HOOKS,
     '{"net/rubygrapefruit/platform/internal/jni/PosixProcessFunctions", "setWorkingDirectory",',
     '{"net/rubygrapefruit/platform/internal/jni/PosixProcessFunctions", "setWorkingDirectory_struck",',
     functional_suite, "a link native-platform makes or a working directory it changes selects every later test"),
    ("the wrapper loads every argument from one slot each", GUARD_HOOKS,
     "            slot += type.getSize();",
     "            slot += 1;",
     agent_suite, "a wrapped instance native with wide arguments passes each one on in its own slots"),
    ("a conditional library's guard classes after the first are not checked", TOUCH_RECORDER,
     "        for (String guardClass : guardClasses) {",
     "        for (String guardClass : new String[] {guardClasses[0]}) {",
     agent_suite, "native-platform counts as reviewed only while both its link and its working-directory natives are guarded"),
    ("a wrapped native without a boolean argument reports nothing turned on", GUARD_HOOKS,
     '            body.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Boolean", "TRUE", "Ljava/lang/Boolean;");',
     '            body.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Boolean", "FALSE", "Ljava/lang/Boolean;");',
     agent_suite, "a wrapped native without a boolean first argument reports every call"),
    ("the wrapper passes its arguments on unloaded", GUARD_HOOKS,
     "            body.visitVarInsn(type.getOpcode(Opcodes.ILOAD), slot);\n",
     "",
     agent_suite, "a wrapped native without a boolean first argument reports every call"),
    ("a version constant is not read", GUARD_HOOKS,
     "                    value[0] = (String) constant;",
     "                    value[0] = null;",
     agent_suite, "a version read from a class's string constant is listed only when it is one reviewed"),
    # A looked-up name is a descriptor only with a leading `L` AND a trailing `;`: `LoginTest` in
    # the default package, unwrapped, dates a class that does not exist and lets LoginTest go.
    ("a looked-up name starting with L is unwrapped as if it were a descriptor", COVERAGE_DECODER,
     '        val unwrapped = if (element.startsWith("L") && element.endsWith(";")) {',
     '        val unwrapped = if (element.startsWith("L")) {',
     decoder_suite, "a looked-up name that starts with L is kept whole, and only a descriptor is unwrapped"),
    # Files, FileChannel and a direct provider call all end in the default provider's own methods.
    ("the default file-system provider is no longer hooked", READ_HOOKS,
     '                    into.add(new String[] {Type.getInternalName(k), read[0], read[1], read[2], "SINK"});',
     "                    into.size();",
     functional_suite, "a test that reads a class file through the file-system provider is selected when it changes"),
    # isolated capture. `-Pyoriwake.isolatedCapture` forks a JVM per test class on a run that
    # records the map, so the rule above stays inside one class; it must never change how a
    # selecting run, or a run that captures nothing, runs the host's tests. The map says per JVM
    # whether it was isolated, and only a JVM that ran one class under the flag may say so.
    ("isolated capture no longer forks a JVM per test class", TEST_TASK_WIRING,
     "            test.forkEvery = 1",
     "            test.forkEvery = test.forkEvery",
     functional_suite, "with an isolated map a change reached from one test class selects it and not the classes after it"),
    # A narrowing run has already emptied the records directory by then, so only a selecting run
    # that falls back to capturing shows this.
    ("a selecting run with the isolation flag forks per test class too", TEST_TASK_WIRING,
     "        if (!settings.isolatedCapture || runPlan.selecting) {",
     "        if (!settings.isolatedCapture) {",
     functional_suite, "a selecting run that falls back to capturing keeps the host's fork settings"),
    ("a run whose capture a refusal switched off is still isolated", TEST_TASK_WIRING,
     "            if (test.systemProperties[RECORDS_DIR_PROPERTY]?.toString().isNullOrEmpty()) {",
     "            if (false) {",
     functional_suite, "the flag on a run declined over in-JVM parallelism changes nothing"),
    ("a selecting run that captures labels its JVMs isolated", TEST_TASK_WIRING,
     "                isolated = settings.isolatedCapture && !selecting,",
     "                isolated = settings.isolatedCapture,",
     functional_suite, "a selecting run that falls back to capturing labels its map shared"),
    ("a malformed isolation flag reads as on", SETTINGS,
     "    val isolatedCapture: Boolean = parseFlag(ISOLATED_CAPTURE, lookup(ISOLATED_CAPTURE))",
     "    val isolatedCapture: Boolean = lookup(ISOLATED_CAPTURE) != null",
     functional_suite, "an isolatedCapture value that is not a boolean fails the build and names the flag"),
    ("the decoder labels a JVM isolated without the run having asked", COVERAGE_DECODER,
     "        val mode = if (isolated && testClasses.size <= 1 && null !in testClasses) MODE_ISOLATED else MODE_SHARED",
     "        val mode = if (testClasses.size <= 1 && null !in testClasses) MODE_ISOLATED else MODE_SHARED",
     decoder_suite, "a capture that did not ask for isolation labels even a one-class JVM shared"),
    ("a JVM that ran several test classes is labelled isolated", COVERAGE_DECODER,
     "        val mode = if (isolated && testClasses.size <= 1 && null !in testClasses) MODE_ISOLATED else MODE_SHARED",
     "        val mode = if (isolated && null !in testClasses) MODE_ISOLATED else MODE_SHARED",
     decoder_suite, "an isolated capture labels a JVM that ran one test class isolated and one that ran two shared"),
    ("a JVM whose test names no class is labelled isolated", COVERAGE_DECODER,
     "        val mode = if (isolated && testClasses.size <= 1 && null !in testClasses) MODE_ISOLATED else MODE_SHARED",
     "        val mode = if (isolated && testClasses.filterNotNull().size <= 1) MODE_ISOLATED else MODE_SHARED",
     decoder_suite, "a JVM that ran a test naming no class is not labelled isolated"),
    ("a merge drops the mode of a JVM a retained test ran in", COVERAGE_DECODER,
     "            (retained(JVM_MODE_FILE) + decoded.map(WorkerRecords::mode)).distinct().sorted(),",
     "            decoded.map(WorkerRecords::mode).distinct().sorted(),",
     decoder_suite, "a merge keeps the mode of every JVM a retained test ran in"),
    ("a mode the reader does not know reads as isolated", COVERAGE_DECODER,
     "        val isolated = rows(JVM_MODE_FILE).filter { it.size == 2 && it[1] == MODE_ISOLATED }.mapTo(HashSet()) { it[0] }",
     "        val isolated = rows(JVM_MODE_FILE).filter { it.size == 2 && it[1] != MODE_SHARED }.mapTo(HashSet()) { it[0] }",
     decoder_suite, "a JVM the mode file does not name, or names with another word, reads as shared"),
    # The Platform's TestNG engine discovers by a TestNG dry run, before the plan starts, and
    # TestNG calls every listener as if each test ran. Taken as real, the dry run handed TestNG's
    # listener the JVM's capture claim, and in a JVM running Jupiter and Vintage beside TestNG
    # the Platform's listener recorded nothing: spring-test's map held only its TestNG tests.
    ("a TestNG dry run is captured as if its tests ran", TESTNG_EVENTS,
     "            return Boolean.parseBoolean(System.getProperty(DRY_RUN_PROPERTY));",
     "            return false;",
     agent_suite, "a TestNG dry run reaches no capture"),
    # `constantDigests` recorded 6 entries on spring-beans where the jar-aware lookup found
    # 176, so 170 classes read as unrecorded and forced. The walk's reach is asserted against the
    # lookup's reach for that reason, and this is the mutation that says the assertion works.
    ("the digest walk stops reading this build's own jars", TASK_ARTIFACTS,
     "        for (jar in ownJars) {\n            var refusal: DigestScan.Refused? = null",
     "        for (jar in emptyList<File>()) {\n            var refusal: DigestScan.Refused? = null",
     unit_suite, "the names recorded are the names the lookup will ask about, jar included"),
    # Absence is not equality. An empty map here says "this build compiled nothing", which is a fact;
    # returning it when the walk could not START says the same thing about a question nobody asked.
    ("a walk that could not start answers an empty map instead of refusing", TASK_ARTIFACTS,
     "            return DigestScan.Refused(\n"
     '                "there is nothing to walk: no own output directory and no own jar on the classpath",\n'
     "                DigestScan.Kind.NOTHING_TO_SCAN,\n"
     "                DigestScan.Counts(),\n"
     "            )",
     "            return DigestScan.Found(emptyMap(), DigestScan.Counts())",
     unit_suite, "a module with no build directory refuses, and a module with no classes does not"),
    # The digest's stability. Feeding line numbers makes a comment-only edit move the digest, so the
    # rule would add every class in every file anyone commented on -- safe, useless, and the kind of
    # uselessness that gets a flag turned off rather than measured.
    ("the digest stops ignoring which line an instruction came from", DIGEST_VISITOR,
     "        override fun visitLineNumber(line: Int, start: Label?) = Unit",
     '        override fun visitLineNumber(line: Int, start: Label?) = feed("line", line, id(start))',
     unit_suite, "a comment-only edit does not move the digest"),
    # The read side of the same rule. A dropped line reads as "this class did not change", which is
    # the quiet direction, so the table is refused whole rather than half-read.
    ("a torn digest table is half-read instead of refused", COVERAGE_DECODER,
     "            if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return null",
     "            if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) continue",
     decoder_suite, "a truncated table is refused whole rather than half-read"),
    ("the configure cost stops being timed, so payback loses an axis", TEST_TASK_WIRING,
     "            return memo.time(CONFIGURE_COUNTER, body)",
     "            return body()", functional_suite,
     "the payback verdict names both axes from one real build"),
    # The scope scan, in both directions. A fixed bound and an unstripped comment are the two ways
    # a licence header decides a whole build's instrumentation scope, and they fail differently:
    # the bound widens the scope to `*`, the comment narrows it to a package nothing declares.
    ("the package scan stops after a fixed fifty lines again", SCOPE,
     "        for (line in lines) {", "        for (line in lines.take(50)) {", unit_suite,
     "a package declaration behind a licence header longer than fifty lines is still read"),
    ("a licence header stops being stripped, so its prose can become the scope", SCOPE,
     '                    line.startsWith("/*", i) -> { inBlockComment = true; i += 2 }',
     '                    line.startsWith("/*", i) -> { inBlockComment = false; i += 2 }',
     unit_suite, "prose inside a licence header does not become the scope"),
    ("the init script attaches under Isolated Projects again", INIT_SCRIPT,
     "if (!isolatedProjects) allprojects {", "if (true) allprojects {",
     functional_suite, "the init script declines Isolated Projects"),
    # A capture that cannot date the map leaves it as it was: merged undated, its records would
    # describe a commit no diff covers, and a revert would hide the difference.
    ("a capture that cannot date the map still merges its records", DECODE_RUN,
     "        if (!datesMap && hasWorkerRecords) {",
     "        if (false) {",
     # A capture whose fork halted, not a filtered run: since a build-script filter dates the map like
     # `--tests`, a filtered run's next run already refuses, and the gate is not what decides there.
     functional_suite, "a revert after a capture whose fork halted runs the test it hid"),
    ("a capture that cannot date the map still merges its records, fail-fast", DECODE_RUN,
     "        if (!datesMap && hasWorkerRecords) {",
     "        if (false) {",
     functional_suite, "a fail-fast capture leaves the map byte-identical"),
    ("a HEAD that moved during a capture is trusted", DECODE_RUN,
     "        val headMoved = startHead != headNow",
     "        val headMoved = false",
     functional_suite, "a commit during a capture in a repository without reflogs leaves the map as it was"),
    ("a reflog that changed during a capture is ignored", DECODE_RUN,
     "        val reflogMoved = startReflogs != reflogsNow",
     "        val reflogMoved = false",
     functional_suite, "a checkout to another branch and back during a capture leaves the map as it was"),
    # What a discarded capture still keeps: a failure only ever runs a test, and without it a test
    # that failed under a filter would be skipped on its older passing record.
    # Re-anchored: the decode also passes the classes Gradle's report saw fail.
    ("a discarded capture forgets the failures it saw", DECODE_RUN,
     "            val failures = runCatching { CoverageDecoder.carryFailures(mapDir, reported) }.getOrElse { problem ->",
     "            val failures = runCatching { 0 }.getOrElse { problem ->",
     functional_suite, "a class whose teardown fails after its tests passed still runs on the next unrelated change"),
    # The other half of what a discarded capture keeps: a failure only Gradle's report holds, from a
    # test JVM stopped before its records were written.
    ("a discarded capture forgets the failures only Gradle's report saw", DECODE_RUN,
     "                JUnitXmlFailures.classes(junitXml) { report, problem ->",
     "                JUnitXmlFailures.classes(null) { report, problem ->",
     functional_suite, "a failure only Gradle's report kept still runs on the next unrelated change"),
    ("the decode is never told where Gradle's report is", DECODE_TASK,
     "                task.junitXml = test.reports.junitXml.outputLocation.asFile.orNull",
     "                task.junitXml = (null as File?)",
     functional_suite, "a failure only Gradle's report kept still runs on the next unrelated change"),
    # A path touched during a dating capture stays in the change set until the next one.
    # Anchored on the decode handing the whole re-observation through.
    ("a tree that moved during a capture is trusted", DECODE_RUN,
     "                        WorkingTree.readStart(startStats, pending?.statsId),\n                    )\n",
     "                        WorkingTree.readStart(startStats, pending?.statsId),\n                    )?.let { "
     "WorkingTree.Reobserved(WorkingTree.readStart(datedSnapshot, pending?.snapshotId) ?: it.undated, it.moved) }\n",
     functional_suite, "a file edited during a capture stays in the change set until the next capture"),
    ("a start snapshot another build rewrote is read", WORKING_TREE,
     "        return if (text.startsWith(header)) text.removePrefix(header) else null",
     "        return text.substringAfter('\\n')",
     functional_suite, "a capture whose start snapshot another build rewrote writes no snapshot"),
    ("a path touched during a capture is trusted when its content returned", WORKING_TREE,
     "    private fun touched(startToken: String, nowToken: String) = startToken != nowToken",
     "    private fun touched(startToken: String, nowToken: String) = false",
     functional_suite,
     "a tracked file rewritten and restored during a capture stays in the change set until the next capture"),
    # A forced run names where each untracked forcing path came from, and only says so: the
    # classification never reaches the change set, and only a path the capture saw move is offered
    # the build-directory remedy.
    ("test-written files leave the change set", SELECTING_STEP,
     "                UNMAPPABLE_PATHS_PROPERTY to scoped.change.unmappablePaths,",
     "                UNMAPPABLE_PATHS_PROPERTY to scoped.change.unmappablePaths.filterNot { "
     "forcing.origins[it] == ForcingPaths.Origin.WRITTEN_DURING_CAPTURE },",
     functional_suite, "a file one test writes and a later test reads runs the reader after a hand edit"),
    ("every untracked forcing path is called test-written", FORCING_PATHS,
     "                path in moves -> Origin.WRITTEN_DURING_CAPTURE",
     "                true -> Origin.WRITTEN_DURING_CAPTURE",
     functional_suite, "an untracked fixture added after the capture forces the selecting run"),
    # One pathspec per forcing path overran the command line on a large untracked tree; git then
    # failed, and a run forced by tens of thousands of files said nothing about them.
    ("git is asked one pathspec per forcing path", FORCING_PATHS,
     "            tracked = { paths -> tree?.let { listed -> paths.filterTo(mutableSetOf(), listed::tracks) } },",
     "            tracked = { paths -> git(listOf(\"ls-files\", \"--\") + paths); "
     "tree?.let { listed -> paths.filterTo(mutableSetOf(), listed::tracks) } },",
     unit_suite, "git is asked the same arguments for ten thousand forcing paths as for ten"),
    ("a run whose forcing paths git could not tell apart says nothing", FORCING_PATHS,
     "            if (!classified.untold || count == 0) return emptyList()",
     "            if (classified.untold || count >= 0) return emptyList()",
     unit_suite, "when git cannot say what is tracked the forcing paths are still counted"),
    # A build in a subdirectory of its repository: a directory is looked up under the build's
    # prefix, or every untracked path there is offered as one glob over the whole tree above it.
    ("a directory is looked up only from the repository's top", FORCING_PATHS,
     '        fun holds(dir: String) = "$prefix$dir" in held || dir in held',
     "        fun holds(dir: String) = dir in held",
     unit_suite, "a build under a subdirectory of the repo names an untracked directory by its own path"),
    # A build-produced file on the test classpath that moved since the capture forces, named, unless
    # the map records which tests read it, and a map that records no such files forces once and
    # captures them: a file generated from git state or the clock reaches a test through no tracked
    # path.
    # A copy of a tracked text file git writes with CRLF compares by its LF form, and nothing else
    # does: not a generated file, not one pinned to eol=lf.
    ("a copy of a tracked text file compares by its bytes", CLASSPATH_FILES,
     "        return runCatching { text.digest(bytes, relative) }.getOrNull() ?: sha256(ByteArrayInputStream(bytes))",
     "        return sha256(ByteArrayInputStream(bytes))",
     unit_suite, "a copy of a tracked text file digests the same whatever its line endings"),
    ("every file with CRLF compares by its LF form", TRACKED_TEXT,
     "        val paths = index?.get(blobId(lf)) ?: return null\n",
     "        if (true) return hex(MessageDigest.getInstance(\"SHA-256\").digest(lf))\n"
     "        val paths = index?.get(blobId(lf)) ?: return null\n",
     unit_suite, "a file no tracked blob matches, or matched at another path, keeps its bytes digest"),
    ("a copy matches a tracked file at any path", TRACKED_TEXT,
     '            (path == relative || path.endsWith("/$relative")) &&',
     '            true &&',
     unit_suite, "a file no tracked blob matches, or matched at another path, keeps its bytes digest"),
    ("a copy need not match what git checked out", TRACKED_TEXT,
     "                runCatching { File(rootDir, path).readBytes().contentEquals(bytes) }.getOrDefault(false)",
     "                true",
     unit_suite, "a copy with carriage returns git did not write keeps its bytes digest"),
    ("a moved classpath file is ignored", SELECTION,
     "    if (verdict == ClasspathFilesVerdict.Unchanged || forces) return this",
     "    if (verdict is ClasspathFilesVerdict.Moved || verdict == ClasspathFilesVerdict.Unchanged || forces) return this",
     functional_suite, "a file that appeared in a sibling jar runs everything"),
    # A moved file the map records the readers of runs them, from each JVM's first read on; what
    # the class loader takes from a manifest, a file that appeared or is gone, and a JVM whose reads
    # are unknown still force.
    ("a moved file's readers are not selected", SELECTOR,
     "        if (!change.movedFiles().isEmpty()) {",
     "        if (false) {",
     functional_suite, "a generated resource that changed runs the test that reads it"),
    ("a JVM whose reads were not recorded is taken to have read nothing", SELECTOR,
     "                if (reads == null || !reads.recorded(position.jvm())) {",
     "                if (false) {",
     agent_suite, "a JVM whose reads were not recorded runs whole on a moved file"),
    # Re-anchored: a read of an entry counts as a read of the moved file holding it too.
    ("a whole-jar read is not a read of its entries", SELECTOR,
     '            if (file.equals(path) || file.startsWith(path + "!/") || path.startsWith(file + "!/")) {',
     '            if (file.equals(path) || path.startsWith(file + "!/")) {',
     functional_suite, "a build time a test reads from the whole jar runs that test"),
    # Re-anchored: a jar entry read is recorded under every name its jar went by.
    ("every manifest read is taken for the class loader's", TOUCH_RECORDER,
     "                        String kind = MANIFEST.equals(entry.getName()) && loaderReadingManifest()",
     "                        String kind = MANIFEST.equals(entry.getName())",
     functional_suite, "a revision stamped into a sibling jar's manifest runs the test that reads it"),
    # A read is kept under each name that can match the walked file: the resolved one carries the
    # case on disk and outlives a link the test removes.
    ("a read is recorded under its given path alone", TOUCH_RECORDER,
     "                    : java.util.Arrays.asList(absolute, resolved);",
     "                    : java.util.Collections.singletonList(absolute);",
     agent_suite, "a file read through a link inside the root is kept under the file it reached, after the link is gone"),
    ("a read under an alias of the root is dropped", TOUCH_RECORDER,
     "                return root + dir.relativize(path);",
     "                return null;",
     agent_suite, "a file read through another name for the root is kept beneath the root"),
    ("a read that cannot be placed is dropped", TOUCH_RECORDER,
     "        } catch (IOException | RuntimeException e) {\n            return java.util.Collections.singletonList(absolute);",
     "        } catch (IOException | RuntimeException e) {\n            return java.util.Collections.emptyList();",
     agent_suite, "a read whose place cannot be told is kept as given, which counts as a read of every file"),
    ("a manifest attribute the class loader keeps is handed over as a plain file", CLASSPATH_FILES,
     '                if (how != "changed" || path.endsWith(EXPOSED_SUFFIX) || path.endsWith(".class")) return null',
     '                if (how != "changed" || path.endsWith(".class")) return null',
     functional_suite, "an implementation version a test reads through its package runs everything"),
    ("a file gone from the classpath is handed over", CLASSPATH_FILES,
     '                if (how != "changed" || path.endsWith(EXPOSED_SUFFIX) || path.endsWith(".class")) return null',
     '                if (how == "appeared" || how == "unreadable" || path.endsWith(EXPOSED_SUFFIX) || path.endsWith(".class")) return null',
     functional_suite, "a file gone from a sibling jar runs everything"),
    # A moved file is handed to the selector only when a read of it is recorded as the file's. A
    # source file's reads are recorded as its class's, and an archive in a directory is read by its
    # entries, so neither can ever match a read: handed over, the test reading it is skipped.
    ("a moved source file is handed over", CLASSPATH_FILES,
     "                if (SOURCE_SUFFIXES.any(path::endsWith)) return null",
     "                if (false) return null",
     unit_suite, "a moved source file is not handed over, its reads are recorded as a class's"),
    ("an archive inside a directory is handed over", CLASSPATH_FILES,
     '                if ("!/" !in path && isArchive(File(rootDir, path))) return null',
     "                if (false) return null",
     unit_suite, "an archive inside a directory is not handed over, by its name or by its content"),
    # The agent records a zip it knows by its content, one behind a launch script among them, by its
    # entries: handed over as a plain file, it matches no read.
    ("a zip behind a launch script is handed over as a plain file again", CLASSPATH_FILES,
     "        if (ZipContent.holdsAZip(file)) return true\n", "",
     functional_suite, "a zip behind a launch script in a resource directory runs everything when it changed"),
    ("a read of an entry stops counting as a read of the moved file holding it", SELECTOR,
     'file.startsWith(path + "!/") || path.startsWith(file + "!/")', 'file.startsWith(path + "!/")',
     agent_suite, "a read of an entry of a moved file reads it"),
    ("the decode writes reads without the root", DECODE_RUN,
     "                rootDir = rootDir,",
     "                rootDir = null,",
     functional_suite, "a build time stamped into a sibling jar's manifest that no test reads runs nothing more"),
    ("a map without classpath digests is trusted", SELECTION,
     '        ?: return ClasspathFilesVerdict.Unrecorded("the map records no digests of this task\'s classpath files")',
     "        ?: return ClasspathFilesVerdict.Unchanged",
     functional_suite, "a map without classpath digests runs everything once"),
    # The test task's own doFirst actions run after the selecting comparison, and a manifest's
    # Class-Path puts jars on the class path no listed entry is: both reach a test unseen.
    ("the last look before the test JVM never forces", LAST_LOOK,
     "        if (!handed.isFile) return",
     "        if (!handed.isFile || true) return",
     functional_suite, "a resource the test task's own doFirst writes runs the test that reads it"),
    ("the last look stays before doFirst actions added ahead of the plugin", LAST_LOOK,
     "                if (own < 0 || look < 0 || look == own - 1) return",
     "                if (true) return",
     functional_suite, "a resource a doFirst from the root project's subprojects block writes runs the test that reads it"),
    ("a complement run hands the last look nothing to compare", SELECTION_WIRING,
     "                    runCatching { LastLook.write(mapDir, rootDir, buildDirs, classes = true, walk.digests) }",
     "                    runCatching { Unit }",
     functional_suite, "a classpath file the test task's doFirst writes from a property differently runs every test"),
    ("a manifest's Class-Path is not followed by the selecting walk", CLASSPATH_FILES,
     "                                    pending.addLast(named to true)",
     "                                    pending.size",
     functional_suite, "a jar only another jar's manifest names runs the test that reads it when it changed"),
    ("a manifest's Class-Path is not followed by the complement's classpath digest", CLASSPATH_FILES,
     "                                    pending.addLast(named to true)",
     "                                    pending.size",
     functional_suite, "a jar only another jar's manifest names, built from a property differently, runs every test"),
    # A task Develocity runs or chooses tests for is declined, and nothing of yoriwake's acts on it
    #: Test Distribution recorded half a suite's coverage even locally.
    ("Test Distribution is no longer detected", DEVELOCITY_DETECTION,
     '        RefusalKind.DEVELOCITY_TEST_DISTRIBUTION, "getTestDistribution", "distribution",',
     '        RefusalKind.DEVELOCITY_TEST_DISTRIBUTION, "getTestDistributionX", "distributionX",',
     functional_suite, "a test task with Test Distribution enabled declines and leaves the map byte-identical"),
    ("Predictive Test Selection is no longer detected", DEVELOCITY_DETECTION,
     '        RefusalKind.DEVELOCITY_TEST_SELECTION, "getPredictiveTestSelection", "predictiveSelection",',
     '        RefusalKind.DEVELOCITY_TEST_SELECTION, "getPredictiveTestSelectionX", "predictiveSelectionX",',
     functional_suite, "a test task with Develocity test selection enabled declines and leaves the map byte-identical"),
    ("an unreadable Develocity extension reads as off", DEVELOCITY_DETECTION,
     "                        .getOrElse { unread = unread ?: Undetermined(feature, describe(it)); null }",
     "                        .getOrElse { null }",
     unit_suite, "an extension whose getter throws is undetermined"),
    ("the Develocity decline is not registered", TEST_TASK_WIRING,
     "        declineUnderDevelocity(project, test, mapDir, agent)\n",
     "",
     functional_suite, "a test task with Test Distribution enabled declines and leaves the map byte-identical"),
    ("a declined run's records are decoded", DECODE_RUN,
     "        if (develocityDeclined.delete()) {",
     "        if (false) {",
     functional_suite, "a declined run's decode reports the decline and nothing else"),
    ("the agent is attached on a declined run", TEST_TASK_WIRING,
     "            if (declinedUnderDevelocity(task as Test)) return@doFirst\n",
     "",
     functional_suite, "a declined run starts its test JVM without the yoriwake agent"),
    ("the decline leaves yoriwake's configured agent attached", TEST_TASK_WIRING,
     "            agentArgument?.let { argument -> test.setJvmArgs(test.jvmArgs.orEmpty().filterNot { it == argument }) }\n",
     "",
     functional_suite, "a declined run starts its test JVM without the yoriwake agent"),
    ("the provenance check runs on a declined run", SELECTION_WIRING,
     "        // A declined run leaves the map exactly as it was: no check, no clear.\n"
     "        if (refusedAtExecution(test)) return@doFirst\n",
     "",
     functional_suite, "a declined selecting run leaves an unlisted map byte-identical"),
    ("the parallelism decline overrides the Develocity one", TEST_TASK_WIRING,
     "            // Already declined, with the host's JaCoCo left as the host set it, or with tests named,\n"
     "            // which select nothing and, unless the run observes, capture nothing.\n"
     "            if (declinedUnderDevelocity(test) || (!observing && declinedForNamedTests(test, planned))) return@doFirst\n",
     "",
     functional_suite, "a declined run with in-JVM parallelism keeps its token and the host's JaCoCo"),
    ("the unknown-age fallback captures on a declined run", SELECTION_WIRING,
     '    test.doFirst {\n        if (refusedAtExecution(test)) return@doFirst\n        // Read here, where',
     '    test.doFirst {\n        // Read here, where',
     functional_suite, "a declined selecting run on a map of unknown age writes nothing"),
    # Tests named with `--tests` all run on a selecting run: selection used to drop a named
    # test the change did not reach, and the build failed on "No tests found".
    ("the named-tests decline is not registered", TEST_TASK_WIRING,
     "        if (runPlan.asked != RunPlan.Kind.RECORD) declineNamedTests(test, mapDir, fromBuildScript, runPlan.observing)\n",
     "",
     functional_suite, "a test named with --tests runs every method under selection"),
    ("the stale-change-set check overrides the named-tests decline", SELECTION_WIRING,
     "        // A provenance refusal already runs everything over a cleared map, which has no drift to compare.\n"
     "        if (refusedAtExecution(test)) {\n",
     "        // A provenance refusal already runs everything over a cleared map, which has no drift to compare.\n"
     "        if (declinedUnderDevelocity(test)) {\n",
     functional_suite, "named tests decline when the tree changes after configuration"),
    # Tests an IDE adds to the filter after the build script ran all run on a selecting run, with
    # the configuration cache on: its test launcher adds them after the cache entry is
    # stored, so a decline decided from a stored provider let selection drop the test it named.
    ("patterns added for the run do not decline selection", TEST_TASK_WIRING,
     "        addedIncludePatterns.isNotEmpty() -> RefusalKind.TESTS_NAMED to\n"
     "            \"tests were named for this run (filter.includePatterns=${addedIncludePatterns.sorted()})\"\n",
     "",
     functional_suite, "a test named for this run after the build script ran runs every method under selection"),
    ("the named-tests decline reads the filter when the configuration cache stores", SELECTION_WIRING,
     "    test.doFirst {\n"
     "        if (declinedUnderDevelocity(test)) return@doFirst\n"
     "        val (kind, reason) = readFilterVerdict(test, fromBuildScript).namedTestsDecline() ?: return@doFirst\n",
     "    val stored = test.project.provider { readFilterVerdict(test, fromBuildScript) }\n"
     "    test.doFirst {\n"
     "        if (declinedUnderDevelocity(test)) return@doFirst\n"
     "        val (kind, reason) = stored.get().namedTestsDecline() ?: return@doFirst\n",
     functional_suite, "a test class an IDE asks for runs every method under selection"),
    # The explanation compares the filter with the build script's copy: compared with itself, it
    # never sees a pattern a `whenReady` hook added, and promises a narrowing the run declines.
    ("the explanation compares the filter with itself", EXPLAIN_TASK,
     "                    readFilterVerdict(test, buildScriptPatterns).namedTestsDecline()\n",
     "                    readFilterVerdict(test, TestPatterns.of(test.filter)).namedTestsDecline()\n",
     functional_suite, "the explanation names the decline for a test named for this run"),
    # A selecting run asked to run everything records instead, from the command line or a
    # commit message; a scan git cannot answer still leaves the parallelism refusal in force.
    ("a full run asked for on the command line still selects", RUN_PLAN,
     "            if (fullRun) {\n",
     "            if (false) {\n",
     functional_suite, "a full run asked for on the command line runs everything and records the map"),
    ("a commit asking for a full run still selects", RUN_PLAN,
     "                        read.marked?.let {\n",
     "                        read.marked.takeIf { false }?.let {\n",
     functional_suite, "a commit asking for a full run runs everything, records the map and names the commit"),
    # A message may hold any byte but NUL: cutting it at a record separator, as the scan once
    # framed its records, drops a marker written after one.
    ("a commit message is cut at a record separator byte", CHANGE_DETECTION,
     "        val commits = raw.split('\\u0000').mapNotNull { record ->\n",
     "        val commits = raw.split('\\u0000').map { it.substringBefore('\\u001e') }.mapNotNull { record ->\n",
     unit_suite, "a marker after a record separator byte in the same message is found"),
    # A branch the build lists in fullRunBranches runs everything and records: a matcher that
    # never matches leaves the default branch selecting, and its map never refreshes.
    ("a listed branch never matches", RUN_PLAN,
     "            fullRunBranches.firstOrNull { Glob.matches(it, name) }\n",
     "            fullRunBranches.firstOrNull { false && Glob.matches(it, name) }\n",
     functional_suite, "on a listed branch a selecting run runs everything and dates the map"),
    # An observing run decides as a selecting run does and runs every test anyway: the filter
    # returning the selecting verdict skips the tests the report exists to show failing.
    ("an observing run leaves out what selection would leave out", SELECTION_FILTER,
     "        if (run.observing()) {\n"
     "            return FilterResult.included(\"the run observes selection\");\n"
     "        }\n",
     "",
     functional_suite, "an observing run executes every test and records what selection would have left out"),
    # ...and captures as a recording run does: taking a selecting run's capture decision leaves a
    # narrowed observation's map uncaptured, and the map it reports against stops refreshing.
    ("an observing run takes a selecting run's capture decision", SELECTING_STEP,
     "    val decision = if (observing) OBSERVING_CAPTURE else decided\n",
     "    val decision = if (false) OBSERVING_CAPTURE else decided\n",
     functional_suite, "an observing run writes the map a recording run writes on the same state"),
    # A complement run leaves out a test only when the selecting run's record shows it ran to
    # an outcome under the same commit, tree, task, build, classpath, configuration and test JVM.
    # One guard per way a test that should run could be left out; each removes its check.
    ("a complement record lists an included test that never finished", DECISION_RECORD,
     "            String ran = row.verdict.included() ? RanTests.outcome(row.testId) : null;",
     "            String ran = row.verdict.included() ? \"SUCCESSFUL\" : null;",
     agent_suite, "each included row whose test passed or failed carries a ran line"),
    ("a complement record lists an aborted or skipped test as ran", RAN_TESTS,
     "        if (testId != null && (\"SUCCESSFUL\".equals(status) || \"FAILED\".equals(status))) {",
     "        if (testId != null && status != null) {",
     agent_suite, "only a passed or failed outcome is kept"),
    ("a complement run takes a record from another commit", SELECTION_RECORD,
     "        now.commit == null || now.commit != recorded.commit ->",
     "        false ->",
     functional_suite, "a commit since the selecting run runs every test"),
    ("a complement run takes a record on a tree that is not clean", SELECTION_RECORD,
     "        !now.clean -> \"the working tree, which is not clean\"",
     "        false -> \"the working tree, which is not clean\"",
     functional_suite, "an untracked file since the selecting run runs every test"),
    ("a selecting run records over a tree not clean where it started", SELECTION_RECORD,
     "            !start.stamp.clean -> return remove(",
     "            false -> return remove(",
     unit_suite, "a tree not clean where the run started removes the record"),
    ("a selecting run records over a tree not clean where it ended", SELECTION_RECORD,
     "            status.isNotEmpty() -> return remove(",
     "            false -> return remove(",
     unit_suite, "a tree not clean where the run ended removes the record"),
    ("a selecting run records though HEAD moved during it", SELECTION_RECORD,
     "            head != start.stamp.commit -> return remove(",
     "            false -> return remove(",
     unit_suite, "HEAD moving during the run removes the record"),
    ("a complement run takes a record over another classpath", SELECTION_RECORD,
     "        now.classpath != recorded.classpath ->",
     "        false ->",
     functional_suite, "a classpath resolved differently runs every test"),
    ("a complement run takes a record of another task configuration", SELECTION_RECORD,
     "        now.configuration != recorded.configuration ->",
     "        false ->",
     functional_suite, "a task configured differently runs every test"),
    ("a fail-fast selecting run leaves a record of results Gradle dropped", TEST_TASK_WIRING,
     "            if (test.failFast) {",
     "            if (false) {",
     functional_suite, "a fail-fast selecting run leaves no record"),
    ("a complement run takes a record from another test JVM", SELECTION_RUN,
     "            if (!here.equals(recorded)) {",
     "            if (false) {",
     agent_suite, "a complement run on another Java runtime than the record's runs everything"),
    ("a selecting run records parts from different test JVMs", SELECTION_RECORD,
     "            if (identity != null && identity != own) {",
     "            if (false) {",
     unit_suite, "parts from test JVMs on different runtimes remove the record"),
    ("a complement run takes a record of another task", SELECTION_RECORD,
     "        now.task != recorded.task ->",
     "        false ->",
     unit_suite, "a record of another task never matches"),
    ("a complement run takes a record of another build", SELECTION_RECORD,
     "        now.buildRoot != recorded.buildRoot || now.buildPath != recorded.buildPath ->",
     "        false ->",
     functional_suite, "a record another build of the same repository wrote runs every test"),
    ("a selecting run merges a part another run left", SELECTION_RECORD,
     "            if (notes[AgentContract.RUN_TOKEN_NOTE] != token) {",
     "            if (false) {",
     unit_suite, "a part another run left removes the record"),
    ("a complement run leaves out a test of a class the record names", SELECTION_FILTER,
     "        if (descriptor.getType() == TestDescriptor.Type.TEST && read.ran.containsKey(id)) {",
     "        if (descriptor.getType() == TestDescriptor.Type.TEST && read.ran.keySet().stream().anyMatch(k -> k.startsWith(id.substring(0, id.lastIndexOf('/') + 1)))) {",
     agent_suite, "a complement run leaves out exactly the tests the record lists as ran"),
    ("a complement run leaves out a leaf container the record lists", SELECTION_FILTER,
     "        if (descriptor.getType() == TestDescriptor.Type.TEST && read.ran.containsKey(id)) {",
     "        if (read.ran.containsKey(id)) {",
     agent_suite, "a complement run runs a leaf container whatever the record lists"),
    ("a complement run leaves out a test of an engine that runs everything", SELECTION_FILTER,
     "        if (FilterRule.engineRunsEverything(id)) {\n            run.countComplementRan();",
     "        if (false) {\n            run.countComplementRan();",
     agent_suite, "a complement run runs every test of an engine that runs everything"),
    # The test JVM has the last word on whether its tests run one at a time: a setting the plugin
    # did not see must still keep interleaved tests out of the map.
    ("a test JVM that sees parallel execution captures", PLATFORM_EVENTS,
     "        if (concurrent != null) {",
     "        if (false && concurrent != null) {",
     agent_suite, "a test JVM whose tests may run concurrently captures nothing and says why"),
    ("a worker that captured nothing over parallelism lets the decode go on", DECODE_RUN,
     "        if (unconfirmed.isNotEmpty()) {",
     "        if (false) {",
     functional_suite, "parallel execution a host's doFirst turns on records nothing"),
    # A flagged recording run turns the engines' parallel switches off, and only then captures.
    ("a flagged recording run leaves parallel execution on", TEST_TASK_WIRING,
     '                ParallelismDetector.SWITCHES.forEach { test.systemProperty(it, "false") }',
     '                ParallelismDetector.SWITCHES.forEach { test.systemProperty(it, "true") }',
     functional_suite, "a flagged recording run of a parallel suite records it one test at a time"),
    ("a flagged selecting run of a parallel suite is declined", TEST_TASK_WIRING,
     "            if (recordSequentially && platform) {\n                keepParallelism(",
     "            if (false) {\n                keepParallelism(",
     functional_suite, "a flagged selecting run narrows with the suite's own scheduling and captures nothing"),
    ("recordSequentially is always on", TEST_TASK_WIRING,
     "        val recordSequentially = settings.recordSequentially ?: extension.recordSequentially.getOrElse(false)",
     "        val recordSequentially = true",
     functional_suite, "the extension property alone records sequentially, and the command line turns it off"),
    ("the read-back ignores the vintage engine's switch", PARALLEL_READ_BACK,
     "        if (enabled(parameters.apply(VINTAGE))) {",
     "        if (false) {",
     agent_suite, "the vintage engine's switch on is unconfirmed, naming it"),
    ("a flagged selecting run overrides parallelism and captures", TEST_TASK_WIRING,
     "            if (recordSequentially && platform && recording) {",
     "            if (recordSequentially && platform) {",
     functional_suite, "a flagged selecting run on a map of unknown age keeps the suite's scheduling and records nothing"),
    ("a flagged TestNG task takes the JUnit Platform override", TEST_TASK_WIRING,
     "            val platform = test.options is org.gradle.api.tasks.testing.junitplatform.JUnitPlatformOptions\n",
     "            val platform = true\n",
     functional_suite, "the flag leaves a parallel TestNG suite declined, and says it covers the JUnit Platform only"),
    ("a run that kept its parallel scheduling is not counted by the schedule", DECODE_RUN,
     "            if (marker.delete()) recordSchedule(taskPath, fullRun = datesTheMap && (!selecting || fullRunMarker.delete()))\n",
     "            marker.delete()\n",
     functional_suite, "flagged selecting runs count toward the scheduled full run"),
    ("the build's recordSequentially is ignored", TEST_TASK_WIRING,
     "        val recordSequentially = settings.recordSequentially ?: extension.recordSequentially.getOrElse(false)",
     "        val recordSequentially = settings.recordSequentially ?: false",
     functional_suite, "the extension property alone records sequentially, and the command line turns it off"),
    ("the decision record keeps the plugin's word over the test JVM's", SELECTION_RUN,
     "            String parallelism = Concurrency.unconfirmed() != null",
     "            String parallelism = false",
     functional_suite, "a flagged run whose host turns parallel execution back on records nothing"),
    # A test an engine starts and never finishes is closed not known to pass, so the rest of the
    # capture can date the map; at JVM shutdown nothing is closed.
    ("a finished container leaves the tests beneath it open", CAPTURE_SESSION,
     '                closeOpen(container + "/");',
     '                if (false) closeOpen(container + "/");',
     agent_suite, "a test left open is closed not known to pass when its container finishes, and the next keeps its outcome"),
    ("a late finish of a test closed early takes the next test's window", CAPTURE_SESSION,
     "            if (!ended && !closedEarly.remove(id)) {",
     "            if (!ended) {",
     agent_suite, "a finish that comes after its container closed the test takes nothing from the next test"),
    ("the run's end leaves the tests still open", CAPTURE_SESSION,
     '                closeOpen("");',
     '                if (false) closeOpen("");',
     agent_suite, "a test still open when the plan finishes is closed not known to pass, and the worker completes"),
    # Under Maven the run file alone answers: Surefire lets a user property override a system one.
    ("the Maven host answers from system properties", MAVEN_HOST,
     "            read = read(runFile);\n",
     "            read = new HashMap<>();\n            for (String key : System.getProperties().stringPropertyNames()) {\n                read.put(key, System.getProperty(key));\n            }\n",
     agent_suite, "the run file answers, never a system property of the same name"),
    ("a run file cut short answers nothing, not a refusal", MAVEN_HOST,
     "            answers.clear();\n",
     "            answers.clear();\n            if (answers.isEmpty()) return answers;\n",
     agent_suite, "a run file cut short refuses the run and names only the map directory"),
    # A JVM that runs several test plans records each into its own directory.
    ("a JVM's later test plan is decoded as if it started the JVM", COVERAGE_DECODER,
     "        if (PLAN_SUFFIX.containsMatchIn(worker.name)) {",
     "        if (false) {",
     decoder_suite, "a JVM's later test plan is taken as having seen every class from its start"),
    ("every test plan records into the JVM's one directory", PLATFORM_EVENTS,
     "                captured == 1 ? worker : worker + AgentContract.PLAN_DIR_SUFFIX + captured, false);",
     "                worker, false);",
     agent_suite, "each test plan a JVM runs records into a directory of its own, each complete on its own"),
    ("a launcher a test started records its tests as the task's", PLATFORM_EVENTS,
     "        outermost = EXECUTING.incrementAndGet() == 1;",
     "        outermost = EXECUTING.incrementAndGet() > 0;",
     agent_suite, "a launcher started inside a test records none of its tests"),
    ("a selecting run merges a part cut short", SELECTION_RECORD,
     "            if (notes[AgentContract.ROWS_NOTE] != rows.toString()) {",
     "            if (false) {",
     unit_suite, "a part cut short removes the record"),
    ("a selection record cut short is read", SELECTION_RECORD_FILE,
     "        if (!String.valueOf(rows).equals(notes.get(AgentContract.ROWS_NOTE))) {",
     "        if (false) {",
     unit_suite, "a record whose rows were cut short reads as unusable"),
    ("a selecting run that did not narrow keeps an earlier record", SELECTION_RECORD,
     "        File(mapDir, AgentContract.SELECTION_FILE).delete()\n        return Outcome.Removed(reason)",
     "        return Outcome.Removed(reason)",
     unit_suite, "a selecting run that did not narrow removes the record"),
    ("the decode never removes an earlier selection record", DECODE_RUN,
     "        if (asksSelection && marker.isFile) {",
     "        if (false) {",
     functional_suite, "a selecting run that runs everything removes an earlier run's record"),
    ("a new selection record is not a complement run's input", SELECTION_WIRING,
     "    test.inputs.property(\"yoriwake.complement\", text?.let(::complementDigest) ?: \"none\")",
     "    test.inputs.property(\"yoriwake.complement\", \"none\")",
     functional_suite, "a complement run whose record was replaced since the last one is not up to date"),
    # Under a trusted-map list the map directory is unreviewed input a cache could have written, and
    # the record in it is outside the map's digest: only a record handed over as a directory is used.
    ("a complement run under a trusted-map list takes the record in the map directory", SELECTION_WIRING,
     "        from.isEmpty() && settings.trustedMaps != null -> RefusalKind.COMPLEMENT_RECORD_MISMATCH to",
     "        false -> RefusalKind.COMPLEMENT_RECORD_MISMATCH to",
     functional_suite, "with a trusted-map list, this build's own record runs every test"),
    # A complement leaves out a test only when it ran against identical classpath content: the
    # build's own outputs (generated resources, class files, jar entries) are digested by content.
    ("a complement's classpath digest names the build's outputs by path only again", SELECTION_RECORD,
     "ClasspathFiles.walk(built, emptyList(), rootDir, classes = true)",
     "ClasspathFiles.walk(emptyList(), emptyList(), rootDir, classes = true)",
     unit_suite, "the classpath digest follows what the build generated into a directory under it"),
    ("a complement run leaves out tests that ran against another generated classpath file", SELECTION_RECORD,
     "ClasspathFiles.walk(built, emptyList(), rootDir, classes = true)",
     "ClasspathFiles.walk(emptyList(), emptyList(), rootDir, classes = true)",
     functional_suite, "a file the build generates from a property differently runs every test"),
    # A walk that cannot vouch for every file must digest to a value no other run matches.
    ("a classpath walk that cannot vouch for every file digests alike in both runs", SELECTION_RECORD,
     "    private fun unmatched(reason: String) = \"$UNMATCHED${UUID.randomUUID()} $reason\"",
     "    private fun unmatched(reason: String) = \"$UNMATCHED $reason\"",
     unit_suite, "a classpath entry under the build that cannot be read digests to a value no run matches"),
    ("class files leave the complement's classpath digest", SELECTION_RECORD,
     "ClasspathFiles.walk(built, emptyList(), rootDir, classes = true)",
     "ClasspathFiles.walk(built, emptyList(), rootDir, classes = false)",
     unit_suite, "the classpath digest follows what the build generated into a directory under it"),
    # An unreadable selection record must not reach Gradle's configuration-time read.
    ("an unreadable selection record is handed to Gradle to read at configuration", SELECTION_WIRING,
     "    val text = if (!record.isFile || java.nio.file.Files.isReadable(record.toPath())) {",
     "    val text = if (true) {",
     functional_suite, "a record that cannot be read runs every test, as having no record"),
    # Not a way to skip a test: a complement run that captured nothing on purpose is decoded as one
    # whose listener never loaded, and warns that it recorded no coverage.
    ("a complement run's decode reads it as a capture", DECODE_RUN,
     "        if (complementing && complemented.isFile) {",
     "        if (false) {",
     functional_suite, "a selecting run and its complement at one commit run every test exactly once"),
    ("a planned undetermined decline skips the parallelism refusal", SELECTION_WIRING,
     "    return kind in NAMED_TESTS_REFUSALS && kind != planned?.token\n",
     "    return kind in NAMED_TESTS_REFUSALS\n",
     functional_suite, "a run declined because git cannot list the messages still refuses in-JVM parallelism"),
    ("the parallelism refusal overrides the named-tests decline", TEST_TASK_WIRING,
     "            if (declinedUnderDevelocity(test) || (!observing && declinedForNamedTests(test, planned))) return@doFirst\n",
     "            if (declinedUnderDevelocity(test)) return@doFirst\n",
     functional_suite, "named tests decline under in-JVM parallelism"),
    ("the provenance check overrides the named-tests decline", SELECTION_WIRING,
     "        // A declined run leaves the map exactly as it was: no check, no clear.\n"
     "        if (refusedAtExecution(test)) return@doFirst\n",
     "        // A declined run leaves the map exactly as it was: no check, no clear.\n"
     "        if (declinedUnderDevelocity(test)) return@doFirst\n",
     functional_suite, "named tests decline on a map the trusted list does not name, and leave it in place"),
    ("a later selection action overrides the named-tests decline", SELECTION_WIRING,
     "        .map { it.token } + DEVELOCITY_REFUSALS + NAMED_TESTS_REFUSALS\n",
     "        .map { it.token } + DEVELOCITY_REFUSALS\n",
     functional_suite, "named tests decline even when a change would force a full run"),
    # The host's JaCoCo file holds what an instrumented run executed: the agent's per-test
    # take used to leave it with almost nothing, and the host's report read close to zero.
    ("the host's JaCoCo file is not restored after a run", TEST_TASK_WIRING,
     "        afterTest.attachTo(test)\n",
     "",
     functional_suite, "the report of a capture run equals the report of a run without the plugin"),
    ("a failed run's host JaCoCo file is not restored", DECODE_TASK,
     "        afterTest.runIfPending(taskPath, logger)\n",
     "",
     functional_suite, "a failing test restores the host file before the host's report reads it"),
    # On a run given a trusted-map list, a map narrows only when the list names its exact digest
    #: a pull request can restore a map from a cache scope it wrote.
    # A list an earlier release wrote vouches for none of the files this version added to the digest:
    # recognising it may only change the refusal's reason, never trust the map.
    ("an earlier digest scheme's match is trusted", MAP_PROVENANCE,
     "                return Verdict.Untrusted(\n                    \"the trusted-map list was written by",
     "                if (true) return Verdict.Trusted(digest)\n"
     "                return Verdict.Untrusted(\n                    \"the trusted-map list was written by",
     unit_suite, "a file only this version digests, changed under an earlier list, is still not trusted"),
    ("a listed map is trusted whatever its content", MAP_PROVENANCE,
     "        if (listed != digest) {",
     "        if (false) {",
     functional_suite, "a map that differs from its trusted digest runs every test"),
    # A change above the large-change threshold runs everything without its analysis; counted
    # against the requested base, or an aging map would turn every small change into a full run.
    ("a change above the large-change threshold is analysed", SELECTION_SETUP,
     "            LargeChange.refusal(counted(asked.ref, changed), largeChange, asked.ref)?.let { reason ->",
     "            LargeChange.refusal(counted(asked.ref, changed), Int.MAX_VALUE, asked.ref)?.let { reason ->",
     functional_suite, "more changed paths than the threshold run everything and record the map"),
    ("the large-change threshold counts the change widened to the map's age", SELECTION_SETUP,
     "        val asked = widening.requested ?: widened\n",
     "        val asked = widened\n",
     functional_suite, "an old map does not push a small change over the threshold"),
    ("the large-change threshold counts paths pathIgnore takes out", SELECTION_WIRING,
     "            changed, pathIgnoreDeclaration(project, pathIgnore, sourceDirs, listOf(base), changed, buildMemo),",
     "            changed, pathIgnoreDeclaration(project, emptyList(), sourceDirs, listOf(base), changed, buildMemo),",
     functional_suite, "paths pathIgnore takes out are not counted"),
    ("the provenance check is not registered", SELECTION_WIRING,
     "        refuseAnUnverifiedMap(project, test, mapDir, listed.digest, jacoco, changeSetFile, observing)\n",
     "",
     functional_suite, "a map the list does not name runs every test"),
    ("the coverage file is left out of the digest", MAP_PROVENANCE,
     "        AgentContract.COVERAGE_FILE,\n",
     "",
     unit_suite, "an edited coverage file changes the digest"),
    ("an untrusted map is merged into instead of removed", MAP_PROVENANCE,
     "        (DIGESTED + AgentContract.MAP_DIGEST_FILE).forEach { File(mapDir, it).delete() }",
     "        Unit",
     functional_suite, "the capture after an untrusted map keeps none of its records"),
    ("the provenance verdict is not a task input", SELECTION_WIRING,
     "    test.inputs.property(\"yoriwake.mapProvenance\", provenance)\n",
     "    provenance.hashCode()\n",
     functional_suite, "a cached narrowed result is not reused under another verdict"),
    ("a later action replaces the provenance refusal", SELECTION_WIRING,
     "        if (refusedAtExecution(test)) {\n            return@doFirst\n        }\n"
     "        val git = ChangeDetection.directRunner(rootDir)",
     "        val git = ChangeDetection.directRunner(rootDir)",
     functional_suite, "an untrusted map whose snapshot drifted still reports its provenance"),
    # A 0.1.0 map may hold records merged without being dated, and nothing on disk tells them apart
    #: its version is refused and the map rebuilt.
    ("a map written by 0.1.0 is read", AGENT_CONTRACT,
     "    public static final int MAP_SCHEMA_VERSION = 7;",
     "    public static final int MAP_SCHEMA_VERSION = 6;",
     functional_suite, "a map from 0_1_0 is rebuilt before selection"),
    # Read before compilation, HEAD names the commit the compiled classes came from; read at the test
    # start, a commit during compilation would stamp older classes with a newer commit.
    ("the start reading of HEAD is skipped on a run that can date the map", TEST_TASK_WIRING,
     "        if (\"--tests\" in arguments || \"--fail-fast\" in arguments) {",
     "        if (true) {",
     functional_suite, "a commit during compilation leaves the map as it was"),
    # The divisor, end to end. The unit-suite mutation beside this one proves the audit REFUSES an
    # unknown worker count; this one proves the count that arrives is the one that really forked,
    # which no test asserted until a two-class fixture existed to fork over.
    ("the decode finalizer stops recording the real worker count", DECODE_RUN,
     "Audit.TaskFacts.recordWorkers(mapDir, decoded.workers)",
     "Audit.TaskFacts.recordWorkers(mapDir, 1)", functional_suite,
     "parallel forks are not refused"),
    ("git creeps back into the configuration phase", TEST_TASK_WIRING,
     "        val mapDir = MapLocation.forTask(project.cacheDir(), test.path)",
     # Qualified, because ChangeDetection moved out of this file's imports with the selection
     # wiring; unqualified, the mutation stopped compiling and proved nothing.
     "        io.github.zeuspizza.yoriwake.gradle.change.ChangeDetection.head(project.providers, "
     "project.rootDir, buildMemo)\n"
     "        val mapDir = MapLocation.forTask(project.cacheDir(), test.path)", guard_suite,
     "a build that never selects asks git only where a capture starts"),
    # EVERY RULE BEHIND EACH DECISION, recorded for an optimistic mode decided on evidence. A
    # rule that stops being recorded changes nothing that runs, so nothing but these would notice:
    # the sweep would count fewer tests under it and a looser mode would look cheaper than it is.
    ("a test that reaches the change stops recording reaches-change", SELECTOR,
     "            if (intersects(test, changed)) {\n"
     "                add(byTest, test.testId(), Rule.REACHES_CHANGE);",
     "            if (false) {\n"
     "                add(byTest, test.testId(), Rule.REACHES_CHANGE);",
     agent_suite, "a test two rules select records both, whichever one decided it"),
    ("a test that recorded changed bytes stops recording changed-bytes", SELECTOR,
     "                add(byTest, test.testId(), Rule.CHANGED_BYTES);", "",
     agent_suite, "a window is named by what opened it"),
    ("a class-scoped record that reaches the change stops recording class-setup", SELECTOR,
     "                        add(byTest, test.testId(), Rule.CLASS_SETUP);", "",
     agent_suite, "setup coverage, an unknown outcome, a test the map never saw and a pin are each a rule"),
    ("a window a changed class opened stops being recorded", SELECTOR,
     "                    && !AgentContract.FIRST_TOUCH_ANY.equals(name)) {\n"
     "                open(opened, Rule.SHARES_JVM_CHANGED_CLASS, touch);",
     "                    && !AgentContract.FIRST_TOUCH_ANY.equals(name)) {\n",
     agent_suite, "a test two rules select records both, whichever one decided it"),
    ("a window changed bytes opened stops being recorded", SELECTOR,
     "                open(opened, Rule.SHARES_JVM_CHANGED_BYTES, touch);", "",
     agent_suite, "a window is named by what opened it"),
    ("a window an unobserved touch opened stops being recorded", SELECTOR,
     "            if (AgentContract.FIRST_TOUCH_ANY.equals(name)) {\n"
     "                open(opened, Rule.SHARES_JVM_UNOBSERVED, touch);\n            }",
     "            if (AgentContract.FIRST_TOUCH_ANY.equals(name)) {\n            }",
     agent_suite, "a window is named by what opened it"),
    ("a test with no position stops recording that the JVM rule cannot date it", SELECTOR,
     "                add(byTest, test.testId(), Rule.SHARES_JVM_UNPOSITIONED);", "",
     agent_suite, "a window is named by what opened it"),
    ("a test not known to pass stops recording it", SELECTOR,
     "                add(byTest, test.testId(), Rule.NOT_KNOWN_TO_PASS);", "",
     agent_suite, "setup coverage, an unknown outcome, a test the map never saw and a pin are each a rule"),
    ("a test the map never saw stops recording not-in-map", RULES,
     "            found.add(Rule.NOT_IN_MAP);", "",
     agent_suite, "setup coverage, an unknown outcome, a test the map never saw and a pin are each a rule"),
    # A test's id names a nested class by its simple name and gives no class to a vintage test or a
    # Spock feature, so a pattern naming the class misses them, silently once it matched a sibling.
    # A submodule whose `ignore` setting hides its changes from git diff is then in no change set.
    ("the change set lets a submodule's ignore setting hide it again", CHANGE_DETECTION,
     '"diff", "--name-only", "--no-renames", "--ignore-submodules=none",',
     '"diff", "--name-only", "--no-renames",',
     functional_suite, "a data file committed inside a submodule that ignores all changes runs the test that reads it"),
    ("the working-tree listing lets a submodule's ignore setting hide it again", WORKING_TREE,
     '"diff", "--name-only", "--no-renames", "--ignore-submodules=none", "--relative"',
     '"diff", "--name-only", "--no-renames", "--relative"',
     functional_suite, "a submodule edit a capture saw and then reverted runs the test that reads it"),
    # The flag does not reach a submodule's own `.gitmodules`, which git reads when it asks that
    # submodule whether it is dirty.
    ("a submodule nested in one that ignores it hides its edits again", CHANGE_DETECTION,
     "        return spawn(workingDir, overrides + arguments)",
     "        return spawn(workingDir, arguments)",
     functional_suite, "a data file edited inside a submodule nested in one whose gitmodules ignores it runs the test that reads it"),
    ("an always-run pattern stops matching the name a test's source gives", SELECTION_FILTER,
     "            String sourced = sourcedNameOf(descriptor);",
     "            String sourced = null;",
     functional_suite, "a pin on a class keeps the tests of its nested class"),
    ("a pinned test stops recording always-run", RULES,
     "            found.add(Rule.ALWAYS_RUN);", "",
     agent_suite, "setup coverage, an unknown outcome, a test the map never saw and a pin are each a rule"),
    # A Kotest spec left out makes Kotest run no test at all, and the task still passes.
    ("a Kotest spec the change cannot reach is excluded again", FILTER_RULE,
     "        if (engineRunsEverything(id)) {\n            return Verdict.ENGINE_RUNS_EVERYTHING;",
     "        if (false) {\n            return Verdict.ENGINE_RUNS_EVERYTHING;",
     agent_suite, "a Kotest spec the change cannot reach is still included"),
    ("a Kotest spec stops recording that its engine runs it", RULES,
     "            found.add(Rule.ENGINE_RUNS_EVERYTHING);", "",
     agent_suite, "a Kotest spec records that its engine runs it whatever changed"),
    ("a test rounded up to its class stops recording the rounding", RULES,
     "            found.add(Rule.CLASS_GRANULARITY);", "",
     agent_suite, "a test rounded up to its class records the rounding"),
    ("a template stops carrying the rules of its invocations", RULES,
     "            found.addAll(children);", "",
     agent_suite, "a template carries the rules of the invocations beneath it"),
    ("a full-run kind after the first stops being named", SELECTOR,
     "                forcing.add(Decision.FullRunKind.NO_COVERAGE_FOR_CHANGED);", "",
     agent_suite, "a full run names every kind that held, and the coverage beneath it"),
    ("a refused run stops recording the selection beneath it", SELECTOR,
     "            if (!changeKnown) {\n                return Rules.notComputed(AgentContract.RULES_NO_CHANGE_SET, forcing);",
     "            if (true) {\n                return Rules.notComputed(AgentContract.RULES_NO_CHANGE_SET, forcing);",
     agent_suite, "a refused run records the refusal and the selection coverage alone makes beneath it"),
    ("a refusal before any change set is recorded as if its inputs were whole", FILTER_RULE,
     "            boolean changeKnown = property.apply(AgentContract.CHANGED_CLASSES_PROPERTY) != null;",
     "            boolean changeKnown = true;",
     agent_suite, "a refusal that came before any change set says the selection beneath it is unknown"),
    ("the decision record stops writing its rule lines", DECISION_RECORD,
     "        if (rules != null && rules.computed()) {", "        if (false) {",
     agent_suite, "every rule behind each row follows the rows, and leaves the rows as they were"),
    ("the test JVM stops handing its rules to the decision record", SELECTION_RUN,
     "                decisions.writeTo(mapDir(), current == null ? null : current.rules());",
     "                decisions.writeTo(mapDir(), null);",
     functional_suite, "a test two rules select records both, and the row keeps the one that decided it"),
    ("run.py stops reading the rule lines", PY_SRC,
     "                    rules[unescape_decision(test)] = tuple(tokens.split(\",\"))\n", "",
     python_suite, "the per-rule counts are wrong"),
    ("run.py reads a torn rule block as the rules", PY_SRC,
     "    elif rule_lines != found:", "    elif False:",
     python_suite, "a torn rule block was read as the rules"),
    ("the coverage-only selection counts a shared JVM as coverage", PY_SRC,
     "    return any(not token.startswith(SHARES_JVM_RULE_PREFIX)\n               and token not in (",
     "    return any(token not in (",
     python_suite, "the selection beneath the refusal is not coverage alone's"),
    ("a part without rules is merged into the task's rules", PY_SRC,
     '    if all(record.get("rules") is not None for record in records if record["decided"]):',
     "    if True:",
     python_suite, "a part without rules was merged into a task-wide count"),
    # A class loader built from the test classpath (spring-core-test's forked loader) defines the
    # agent a second time and runs a nested launcher with it. Only the attached copy, serving the
    # outer launcher, may select, capture or write; a nested launcher runs every test.
    ("a copy of the agent in another class loader passes for the attached one", ATTACHED_LOADER,
     "            return Class.forName(type.getName(), false, ClassLoader.getSystemClassLoader()) == type;",
     "            return Class.forName(type.getName(), false, ClassLoader.getSystemClassLoader()) != null;",
     agent_suite, "a copy another loader defined is not the attached copy, asked from either copy"),
    ("a copy of the filter deselects a nested launcher's tests", SELECTION_FILTER,
     "            if (!ATTACHED) {\n"
     "                return FilterResult.included(\"a copy of the agent in another class loader does not select\");\n"
     "            }\n", "",
     agent_suite, "a copy of the filter includes a test the attached filter deselects"),
    ("a filter a running test created deselects its nested launcher's tests", SELECTION_FILTER,
     "        this(PlatformEvents.planExecuting());", "        this(false);",
     agent_suite, "a filter created while a test plan executes serves a nested launcher and includes everything"),
    # Anchored on the count's increment, which also says whether the plan is outermost.
    ("the Platform listener stops saying a test plan is executing", PLATFORM_EVENTS,
     "        outermost = EXECUTING.incrementAndGet() == 1;\n", "        outermost = EXECUTING.get() == 0;\n",
     agent_suite, "a filter created while a test plan executes serves a nested launcher and includes everything"),
    ("a nested launcher's filter is counted as one of the task's writers", SELECTION_FILTER,
     "        this.run = nested ? null : new SelectionRun();",
     "        this.run = new SelectionRun();",
     functional_suite, "the decision record is the outer launcher's alone, whole and single-writer"),
    ("a copy of the decision record writes under the attached copy's names", DECISION_RECORD,
     "        if (dir == null || !ATTACHED) {", "        if (dir == null) {",
     agent_suite, "a copy of the decision record writes nothing"),
    ("a copy of the agent takes the capture claim", CAPTURE_CLAIM,
     "        if (!ATTACHED || !AttachedLoader.attached(owner)) {\n"
     "            return null;\n"
     "        }\n", "",
     agent_suite, "a copy never takes the capture claim, even when nothing holds it"),
    ("a copy of the Platform listener probes", PLATFORM_EVENTS,
     "        if (!ATTACHED) {\n"
     "            return;\n"
     "        }\n"
     "        outermost = EXECUTING.incrementAndGet() == 1;",
     "        outermost = EXECUTING.incrementAndGet() == 1;",
     agent_suite, "a copy of the Platform listener does not probe"),
    # A selecting run keeps the host's fork settings, so a fallback that captured would replace a map
    # recorded in isolation with shared-JVM records. Only a recording run updates such a map, and a
    # map with any isolated JVM counts: a full run supersedes every record it re-runs.
    ("a fallback captures over a map recorded in isolation", CAPTURE_DECISION,
     "    val keepsIsolation = mode != MODE_SHARED",
     "    val keepsIsolation = false",
     unit_suite, "a full run over a map recorded in isolation, wholly or in part, does not instrument"),
    ("a fallback captures over a map only partly recorded in isolation", CAPTURE_DECISION,
     "    val keepsIsolation = mode != MODE_SHARED",
     '    val keepsIsolation = mode == "isolated"',
     unit_suite, "a full run over a map recorded in isolation, wholly or in part, does not instrument"),
    ("a run refused over the map's age captures over a map recorded in isolation", SELECTING_STEP,
     '        ).takeUnless { it.capture }\n',
     '        ).takeUnless { true }\n',
     functional_suite, "a selecting run that refuses over the map's age leaves an isolated map alone"),
    # Cost, not safety: a map selection cannot read holds nothing isolation could keep.
    ("an unusable map labelled isolated is never rebuilt", CAPTURE_DECISION,
     "    val mode = if (fullRun && !current && mapUsable) CoverageDecoder.captureMode(mapDir) else MODE_SHARED",
     "    val mode = if (fullRun && !current) CoverageDecoder.captureMode(mapDir) else MODE_SHARED",
     unit_suite, "a full run over a shared map, or an unusable one labelled isolated, still instruments"),
    # `yoriwakeExplain` reads compiled classes; without the test task's compilation it digests the
    # bytes from before an edit and misses a changed constant the real run then forces on.
    ("yoriwakeExplain reads class files no compile task of this build produced", EXPLAIN_TASK,
     "                task.dependsOn(facts.classpath, facts.testOutputs)\n", "",
     functional_suite, "yoriwakeExplain before a compile answers what the selecting run then does"),
    # Cost, not safety: the JDK's own libraries include the platform loader's modules.
    ("a native library a JDK module on the platform loader loads counts as the host's", JDK_CODE,
     "        if (loader != null && loader != PLATFORM) {",
     "        if (loader != null) {",
     agent_suite, "a native library a JDK module on the platform loader loads is not recorded"),
    # A JVMTI agent reaches any class with no Java call the lookup hooks see.
    ("a native agent given with -agentlib is not noticed", TOUCH_RECORDER,
     '                if (argument.startsWith("-agentlib:") || argument.startsWith("-agentpath:")) {',
     '                if (argument.startsWith("-agentpath:")) {',
     functional_suite, "a native agent given with -agentlib dates every test class from the start"),
    ("a native agent given with -agentpath is not noticed", TOUCH_RECORDER,
     '                if (argument.startsWith("-agentlib:") || argument.startsWith("-agentpath:")) {',
     '                if (argument.startsWith("-agentlib:")) {',
     functional_suite, "a native agent given with -agentpath dates every test class from the start"),
    ("the whole loaded set is no longer hooked", READ_HOOKS,
     '        {"sun/instrument/InstrumentationImpl", "getAllLoadedClasses", "()[Ljava/lang/Class;", "0"},\n', "",
     functional_suite, "a test that asks for every loaded class dates every test class from that point"),
    ("where every child process starts is no longer hooked", READ_HOOKS,
     '        {"java/lang/ProcessImpl", "start", "([Ljava/lang/String;Ljava/util/Map;Ljava/lang/String;"\n'
     '            + "[Ljava/lang/ProcessBuilder$Redirect;Z)Ljava/lang/Process;", "-2"},\n', "",
     functional_suite, "a child JVM started with Runtime exec that reads a class file selects every later test in its JVM"),
    # A test a dating capture did not report (its class's setup threw, it was deleted, a filter left
    # it out) kept its older SUCCESSFUL record under the newer stamp, and the next unrelated change
    # skipped it. Dropped, it is not in the map, and a test not in the map runs.
    ("a capture that dates the map keeps test records it did not re-observe", COVERAGE_DECODER,
     ".filterNot(superseded).filter { !datesTheMap || keptByDatingRun(it) }",
     ".filterNot(superseded)",
     decoder_suite, "a capture that dates the map drops every test record it did not re-observe"),
    ("a capture that dates the map keeps the positions of tests it dropped", COVERAGE_DECODER,
     "        val retainedPositions = if (datesTheMap) {",
     "        val retainedPositions = if (false) {",
     decoder_suite, "a capture that dates the map drops every test record it did not re-observe"),
    # Startup coverage forces a full run when touched, so dropping what a selecting run did not
    # replace only ever narrows.
    ("a selecting capture that dates the map drops startup coverage it did not replace", COVERAGE_DECODER,
     "            (selecting && id == UNATTRIBUTED_RECORD_ID) || id in unread",
     "            id in unread",
     decoder_suite, "a selecting capture that dates the map still keeps the startup coverage"),
    # A container that fails reports none of the tests it did not reach, and one that fails after
    # them leaves them passed; either way the class still fails.
    ("a failed container leaves its tests without a failed record", PLATFORM_EVENTS,
     "        if (result == null || result.getStatus() != TestExecutionResult.Status.SUCCESSFUL) {\n"
     "            failedBeneath(capture, identifier);\n"
     "        }\n",
     "",
     agent_suite, "a class container that fails records every test beneath it as failed"),
    # A test template whose argument source throws, or a test factory that throws, fails with no
    # invocation beneath it; its older SUCCESSFUL invocations then read as known to pass.
    ("a failed container with nothing beneath it records nothing failed", PLATFORM_EVENTS,
     "                if (identifier.isTest() || current.getChildren(identifier).isEmpty()) {",
     "                if (identifier.isTest()) {",
     agent_suite, "a class of test templates whose setup fails records each template as failed"),
    ("a failed test template stops recording itself", PLATFORM_EVENTS,
     "            beneath.add(container);\n", "",
     agent_suite, "a test template that fails before any invocation records itself as failed"),
    # A run that leaves tests out by tag, engine, category or group loaded only part of the suite;
    # a loaded-class union from it would read as the whole task's and skip more.
    ("a recording run filtered by the framework speaks for the whole task", TEST_TASK_WIRING,
     "        framework != null -> FilterVerdict(false, framework, byFramework = true)\n"
     "        else -> FilterVerdict(true, unfilteredDetail)",
     "        else -> FilterVerdict(true, unfilteredDetail)",
     functional_suite, "every framework filter dates the map but never speaks for the whole task"),
    # Undated, a build whose test task always filters by tag refuses selection forever; dating is
    # safe because the capture drops the records of what the filter left out.
    ("a recording run filtered by the framework no longer dates the map", TEST_TASK_WIRING,
     "        !(unfiltered || byFramework || byBuildScript) -> ",
     "        !(unfiltered || byBuildScript) -> ",
     functional_suite, "a capture that leaves tests out by tag dates the map without them"),
    # The same for a filter the build script sets on a JUnit Platform task: undated, every
    # selecting run on that task refuses and runs the whole suite.
    ("a build-script filter no longer dates the map", TEST_TASK_WIRING,
     "        !(unfiltered || byFramework || byBuildScript) -> ",
     "        !(unfiltered || byFramework) -> ",
     functional_suite, "a capture under a build-script filter dates the map without what it left out"),
    # A pattern an IDE's test launcher or a `whenReady` hook adds narrows one run; counted as the
    # build script's, that run would date the map with only what it ran.
    ("a pattern added for the run counts as the build script's", TEST_TASK_WIRING,
     "        !added.isEmpty() -> FilterVerdict(false, \"${added.describe()} added after the build script ran\")\n",
     "",
     functional_suite, "a pattern added after the build script ran leaves the map undated"),
    # The decode's inputs are fixed when the configuration cache stores its entry, before an IDE's
    # test launcher adds its patterns: without the marker the test task writes as it
    # executes, a recording request for one class dated the map and dropped every other record.
    ("a run whose filter gained patterns as it ran dates the map", TEST_TASK_WIRING,
     "                marker.parentFile.mkdirs()\n"
     "                marker.writeText(reason)\n",
     "                marker.parentFile.mkdirs()\n",
     functional_suite, "a recording request for one class leaves the map's age and other records alone"),
    # A reason an earlier run left behind, when its decode never ran, must not keep an unfiltered
    # capture from dating the map: undated for good, every selecting run refuses.
    ("an earlier run's undated reason outlives it", TEST_TASK_WIRING,
     "            marker.delete()\n"
     "            if (declinedUnderDevelocity(test)) return@doFirst\n"
     "            val reason = readFilterVerdict(test, fromBuildScript).undatedBy(test.failFast) ?: return@doFirst\n",
     "            if (declinedUnderDevelocity(test)) return@doFirst\n"
     "            val reason = readFilterVerdict(test, fromBuildScript).undatedBy(test.failFast) ?: return@doFirst\n",
     functional_suite, "an unfiltered capture dates the map over a reason an earlier filtered run left behind"),
    # `--tests` beside a build-script filter must stay a per-run filter, which leaves the map
    # undated, however the build script filters the task.
    ("command-line patterns lose to a build-script filter", TEST_TASK_WIRING,
     "        commandLine.isNotEmpty() -> FilterVerdict(false, \"--tests $commandLine\")\n"
     "        !added.isEmpty() -> FilterVerdict(false, \"${added.describe()} added after the build script ran\")\n"
     "        !live.isEmpty() -> FilterVerdict(\n"
     "            false,\n"
     "            listOfNotNull(\"${live.describe()} from the build script\", framework).joinToString(\" \"),\n"
     "            byBuildScript = onPlatform,\n"
     "        )\n",
     "        !added.isEmpty() -> FilterVerdict(false, \"${added.describe()} added after the build script ran\")\n"
     "        !live.isEmpty() -> FilterVerdict(\n"
     "            false,\n"
     "            listOfNotNull(\"${live.describe()} from the build script\", framework).joinToString(\" \"),\n"
     "            byBuildScript = onPlatform,\n"
     "        )\n"
     "        commandLine.isNotEmpty() -> FilterVerdict(false, \"--tests $commandLine\")\n",
     functional_suite, "--tests over a build-script filter leaves the map's stamp as it was"),
    # An unreadable blob used to yield no record, so a test that may just have failed kept its
    # older SUCCESSFUL one.
    ("an attributable test with an unreadable blob keeps its older record", COVERAGE_DECODER,
     "?: if (attributable) emptySet() else {",
     "?: if (false) emptySet() else {",
     decoder_suite, "an attributable test with an unreadable blob is recorded UNKNOWN"),
    # A window no test owns with an unreadable blob has nothing to replace its older coverage with,
    # and that coverage only ever forces or selects.
    ("a dating capture drops the older coverage of an unreadable window no test owns", COVERAGE_DECODER,
     "            (selecting && id == UNATTRIBUTED_RECORD_ID) || id in unread",
     "            selecting && id == UNATTRIBUTED_RECORD_ID",
     decoder_suite, "an unreadable window no test owns keeps its older coverage"),
    ("a readable sibling supersedes the older coverage of an unreadable window", COVERAGE_DECODER,
     " && !(selecting && id == UNATTRIBUTED_RECORD_ID) && id !in unread",
     " && !(selecting && id == UNATTRIBUTED_RECORD_ID)",
     decoder_suite, "an unreadable window no test owns keeps its older coverage"),
    # The wiring found the task broken (no agent on its classpath, a map directory overwritten)
    # and used to fail the build; degraded, it must still run everything.
    ("a test task whose wiring is broken still selects", TEST_TASK_WIRING,
     '            test.systemProperty(SELECT_PROPERTY, "false")\n'
     '            test.systemProperty(RECORDS_DIR_PROPERTY, "")\n'
     "            test.logger.warn(",
     "            test.logger.warn(",
     functional_suite, "a test task whose agent was taken off its classpath runs everything"),
    ("a map directory overwritten after configuration is not noticed", TEST_TASK_WIRING,
     "                mapDir.absolutePath != liveMapDir ->",
     "                false ->",
     functional_suite, "a test task whose map directory was overwritten runs everything"),
    # A LABEL IS NOT A MUTATION. Keyed by label, the paired file's last row of a shared name wins,
    # and the isolated arm judges one declaration against another's failures: mockk's isolated arm
    # reported 2 misses on a row that excluded nothing.
    ("paired ground truth keyed by label", PY_SRC,
     "    table = {row_key(row): row for row in usable}",
     '    table = {row["target"]: row for row in usable}',
     python_suite, "index 0 was judged against another row's ground truth"),
    # PER-METHOD KEYS. A key two methods share let a selected method clear an excluded
    # one's failure (iceberg's `TestMetadataTableScans`: 66 methods on 4 XML keys). The reader keeps
    # each method's own key, and every comparison asks one rule whether a key may stand for another.
    ("per-method keys are dropped for XML keys", PY_SRC,
     "    return Outcomes(ran, failed, methods, cause)",
     "    return Outcomes(ran, failed, methods and Methods(ran, failed), cause)",
     python_suite, "per-method keys are"),
    ("the shared-key rule stops firing in unmatched", PY_SRC,
     "        if test in exact:\n            if shared(test):\n                shared_key.append(test)\n"
     "            continue",
     "        if test in exact:\n            continue",
     python_suite, "cleared by a selected sibling's key"),
    ("the baseline subtracts a shared key", PY_SRC,
     "    return {test for test in full_failed if test not in already or keys.shared(test)}",
     "    return {test for test in full_failed if test not in already}",
     python_suite, "subtracted as already failing because a sibling flaked"),
    # A class the record excluded whole is read from another build by the baseline, the re-runs and
    # the control arm, where any of its tests may have produced the key.
    ("the shared-key rule exempts a class excluded whole", PY_SRC,
     "    return bool(index.excluded[cls])",
     "    return bool(index.included[cls] and index.excluded[cls])",
     python_suite, "excluded whole was subtracted because a sibling flaked"),
    ("a re-run clears a shared-key miss", PY_ISOLATION,
     "            if keys.shared((cls, name)):\n                continue\n            alone = [",
     "            alone = [",
     python_suite, "reclassified by its siblings' re-runs"),
    ("the control arm sets aside a shared-key miss", PY_ISOLATION,
     "            if keys.shared((cls, name)):\n                continue\n"
     "            for attempt in range(ISOLATED_REPEATS):",
     "            for attempt in range(ISOLATED_REPEATS):",
     python_suite, "set aside by a sibling failing unmutated"),
    # The isolated arm's selected run may keep one of two methods sharing a display name, so only
    # the paired row knows the key collapsed.
    ("paired ground truth drops its collapsed keys", PY_SRC,
     "        return Outcomes(ran, {xml_key(test) for test in tests}, Methods(None, tests, collapsed))",
     "        return Outcomes(ran, {xml_key(test) for test in tests}, Methods(None, tests))",
     python_suite, "a paired collapsed key was cleared by the selected sibling"),
    ("skipped-nothing ignores the record", PY_SRC,
     '    if decisions is None or not all(verdict.partition("/")[0] == "included"\n'
     '                                    for verdict in decisions["verdicts"]):',
     "    if False:",
     python_suite, "dropped as a discovery difference"),
    # Exact names only. A prefix match sets aside a real test whose name starts like a container's,
    # and `container_failed` is subtracted from the headline.
    # each setup-forced rule and the reset, dropped, lets a project whose setup forces
    # every row draw its rows, or removes nothing a test run wrote.
    ("the setup-forced streak never fires", PY_SRC,
     "                         or names_change(reason, row[\"target\"], declared) for row in last):\n"
     "        return None\n    return reason",
     "                         or names_change(reason, row[\"target\"], declared) for row in last):\n"
     "        return None\n    return None",
     python_suite, "three identical on a path none changed"),
    ("the setup-forced streak ignores what the rows changed", PY_SRC,
     "                         or names_change(reason, row[\"target\"], declared) for row in last):",
     "                         for row in last):",
     python_suite, "identical, naming a class one row changed"),
    ("the setup-forced streak counts a fixed-text kind", PY_SRC,
     'SETUP_FORCED_KINDS = ("unmappable-paths", "map-unusable")',
     'SETUP_FORCED_KINDS = ("unmappable-paths", "map-unusable", "startup-coverage")',
     python_suite, "three of a fixed-text kind"),
    ("the preflight never reads a setup force", PY_SRC,
     "            or not reason or names_change(reason, label, declared)):\n        return None\n"
     "    return reason",
     "            or not reason or names_change(reason, label, declared)):\n        return None\n"
     "    return None",
     python_suite, "read as setup-forced None"),
    ("the untracked reset removes nothing", PY_SRC,
     "    removed = sorted(untracked_files(checkout, project) - set(snapshot))",
     "    removed = []",
     python_suite, "survived the reset"),
    ("the untracked reset takes build output", PY_SRC,
     "    names = {\"build\", build_dir(project)}",
     "    names = {\"build\"}",
     python_suite, "which is the map, build output or a file present at checkout"),
    ("the sweep names no setup-forced project", SWEEP_SRC,
     "            if s.get(\"project_outcome\") == run.SETUP_FORCED]",
     "            if False]",
     sweep_suite, "the setup-forced projects named are"),
    ("the charts draw a setup-forced project", README_CHARTS,
     '        if data.get("project_outcome") == "setup-forced":',
     "        if False:",
     sweep_suite, "a setup-forced project's rows were drawn"),
    ("the corpus verdict ignores a setup-forced project", CORPUS_SRC,
     '    if data.get("project_outcome") == SETUP_FORCED:',
     "    if False:",
     corpus_suite, "a setup-forced project read as"),
    # the timing cap, dropped, times every row or leaves a forced timed row unpaired.
    ("the timing cap times every row", PY_SRC,
     "    return not recall_only and (timing_cap is None or position <= timing_cap)",
     "    return not recall_only",
     python_suite, "a timing project capped at 8 timed"),
    ("a timed forced row skips its full arm", PY_SRC,
     "    return (not timed and decisions is not None\n",
     "    return (decisions is not None\n",
     python_suite, "skipped its full arm"),
    ("the sweep passes no timing cap", SWEEP_SRC,
     '    command += (["--timing-cap", str(TIMING_CAP)] if name in TIMING_SUBSET else ["--recall-only"])',
     '    command += ([] if name in TIMING_SUBSET else ["--recall-only"])',
     sweep_suite, "timing cap None"),
    ("the time chart draws untimed rows", README_CHARTS,
     '        timed = [r for r in p["rows"] if r.get("timed") is not False\n',
     '        timed = [r for r in p["rows"] if True\n',
     sweep_suite, "drew an untimed row"),
    ("the time chart pools timing caps silently", README_CHARTS,
     "    if not pool:\n        raise SystemExit(",
     "    if False:\n        raise SystemExit(",
     sweep_suite, "pooled without saying so"),
    # a control that accepts a silent project, or a reader that draws a control row.
    ("the controls accept a project that reported no miss", CONTROLS_SRC,
     "        if not missed:\n            failures.append(",
     "        if False:\n            failures.append(",
     controls_suite, "a control project at zero misses was not named"),
    ("the corpus verdict reads control rows", CORPUS_SRC,
     '    if data.get("control") or any(row.get("control") for row in rows):',
     "    if False:",
     corpus_suite, "a file holding control rows read as"),
    ("the charts draw a control row", README_CHARTS,
     '        if data.get("control") or any(row.get("control") for row in data.get("mutations") or []):',
     "        if False:",
     sweep_suite, "the charts drew a planted-miss control row"),
    # each complement rule, dropped, lets a row take less than full-arm truth where a
    # full arm must stand behind it.
    ("the complement skips its first-rows check", PY_SRC,
     '        ("first-rows", untimed_narrowed_before < COMPLEMENT_FIRST_ROWS),',
     '        ("first-rows", False),',
     python_suite, "untimed narrowed row 1 ran"),
    ("the complement ignores a shared-JVM neighbour", PY_SRC,
     "    return any(order[jvm][d] > order[jvm][k] for jvm in order",
     "    return False and any(order[jvm][d] > order[jvm][k] for jvm in order",
     python_suite, "was not a check row"),
    ("the complement is never priced", PY_SRC,
     "    if priced and share > COMPLEMENT_COST_SHARE:",
     "    if False:",
     python_suite, "80 of 100 suite seconds"),
    ("the complement answers on XML keys", PY_SRC,
     "    if keys.scheme != PER_METHOD:\n        return True\n    induced = subtract_baseline(",
     "    if keys.scheme != PER_METHOD:\n        return False\n    induced = subtract_baseline(",
     python_suite, "on XML keys took the complement"),
    ("a complement disagreement goes unseen", PY_SRC,
     '                          "full": test in by_full[which]})\n    return found\n',
     '                          "full": test in by_full[which]})\n    return []\n',
     python_suite, "its class alone passes gave"),
    ("the complement's failures leave the row's truth", PY_SRC,
     "    failed = set(selected[1] or ()) | set(complement[1] or ())",
     "    failed = set(selected[1] or ())",
     python_suite, "induced set is"),
    ("an isolated row pairs with a complement row", PY_SRC,
     '              and row.get("ground_truth") != COMPLEMENT_TRUTH]',
     "              ]",
     python_suite, "an isolated arm would pair with"),
    ("a plain run takes complement truth", PY_SRC,
     '    parser.add_argument("--ground-truth", choices=GROUND_TRUTH_MODES, default="full",',
     '    parser.add_argument("--ground-truth", choices=GROUND_TRUTH_MODES, default="auto",',
     python_suite, "a run launched without --ground-truth takes"),
    ("the shared-JVM control accepts any row", CONTROLS_SRC,
     "        elif complement_mode and name == SHARED_JVM_CONTROL and not any(",
     "        elif False and not any(",
     controls_suite, "passed"),
    ("the cost charges follow-up re-runs", COST_SRC,
     '                                                 "complement_seconds")))',
     '                                                 "complement_seconds", "followup_seconds")))',
     controls_suite, "charged a project for its follow-up re-runs"),
    ("the reduced arm compares on complement truth", SWEEP_SRC,
     '    if arm == "reduced":\n        return "check"',
     '    if arm == "reduced":\n        return requested',
     sweep_suite, "the reduced arm runs"),
    ("a resumed leg forgets the switch-off", PY_SRC,
     '    return previous.get("complement_switched_off") if findings else None',
     "    return None",
     python_suite, "forgot that the complement was switched off"),
    ("an owed complement row leaves before its full arm", PY_SRC,
     "            if not (row.get(\"ground_truth\") == COMPLEMENT_TRUTH and row_key(row) in replaced)]",
     "            if not row.get(\"ground_truth\") == COMPLEMENT_TRUTH]",
     python_suite, "after one re-measured row the file holds"),
    ("the controls overlook a complement disagreement", CONTROLS_SRC,
     "        if complement_mode and (data.get(\"complement_switched_off\")",
     "        if False and (data.get(\"complement_switched_off\")",
     controls_suite, "whose complement disagreed with a full arm passed"),
    ("the controls accept a miss a full arm stood behind", CONTROLS_SRC,
     "        elif complement_mode and name in COMPLEMENT_CONTROLS and not any(",
     "        elif False and not any(",
     controls_suite, "every miss a full arm stood behind"),
    # a named set's control that blesses a family, a forcing row or a null change it should fail.
    ("a set's control accepts a family with no miss", CONTROLS_SRC,
     '        if any(r.get("missed") for each in rows.values() for r in each):',
     "        if True:",
     controls_suite, "a family at zero misses was not named"),
    ("the no-force control accepts a family no project holds", CONTROLS_SRC,
     "        if not any(rows.values()):",
     "        if False:",
     controls_suite, "a family no fixture holds passed"),
    ("the no-force control accepts a row that still forced", CONTROLS_SRC,
     "                if not assert_recall.forcing_row_narrowed(row):",
     "                if False:",
     controls_suite, "a family whose row still forced passed"),
    ("the no-force control forgets the files tests read", CONTROLS_SRC,
     "            if family in READ_BY_A_TEST.get(name, ()) and not any(",
     "            if False and not any(",
     controls_suite, "read by a test of demo, narrowed with no miss passed"),
    ("the no-force plant leaves the services files read", CONTROLS_SRC,
     '    "services": r"(^|/)src/main/resources/META-INF/services/",',
     '    "services": r"(^|/)src/main/resources/META-INF/service/",',
     controls_suite, "services's plant leaves"),
    ("the no-force plugin leaves the rules behind the path rule on", CONTROLS_SRC,
     '"plants": (planted_no_force(),) + NO_FORCE_RULES,',
     '"plants": (planted_no_force(),),',
     controls_suite, "the no-force plugin plants 1 of the rules"),
    ("a control of the default set runs on a corpus checkout", PY_SRC,
     "CORPUS_CONTROL_SETS = (REVERT_SET, FORCING_SET, POOL_SHAPES)",
     "CORPUS_CONTROL_SETS = (REVERT_SET, FORCING_SET, POOL_SHAPES, DEFAULT_SHAPES)",
     python_suite, "a default control on a corpus checkout was accepted"),
    ("the pool corpus leg runs nowhere", PY_SRC,
     "CORPUS_CONTROL_SETS = (REVERT_SET, FORCING_SET, POOL_SHAPES)",
     "CORPUS_CONTROL_SETS = (REVERT_SET, FORCING_SET)",
     python_suite, "a pool control on a corpus checkout was refused"),
    ("the pool corpus leg asks the fixture families", CONTROLS_SRC,
     '"families": POOL_CORPUS_FAMILIES,',
     '"families": POOL_FIXTURE_FAMILIES,',
     controls_suite, "the pool legs ask"),
    ("a pool kind is asked by no leg", CONTROLS_SRC,
     'POOL_CORPUS_FAMILIES = ("aliased", "constant", "renamed", "schema")',
     'POOL_CORPUS_FAMILIES = ("aliased", "constant", "renamed")',
     controls_suite, "the pool legs ask"),
    ("the forcing corpus leg asks the fixture families", CONTROLS_SRC,
     '"families": FORCING_CORPUS_FAMILIES,',
     '"families": FORCING_FIXTURE_FAMILIES,',
     controls_suite, "not every family once"),
    ("a family off the uncontrolled list passes with no miss-possible row", CONTROLS_SRC,
     "            if family not in UNCONTROLLED:",
     "            if False:",
     controls_suite, "a family off the uncontrolled list passed"),
    ("the uncontrolled list is not printed", CONTROLS_SRC,
     '    notes = [f"UNCONTROLLED:',
     '    notes = [] if True else [f"UNCONTROLLED:',
     controls_suite, "is not printed with its reason"),
    ("an uncontrolled family's lost miss passes", CONTROLS_SRC,
     "        if not any(_miss_possible(r) for each in rows.values() for r in each):",
     "        if family in UNCONTROLLED or not any(_miss_possible(r) for each in rows.values()\n"
     "                                             for r in each):",
     controls_suite, "an uncontrolled family's row that narrowed"),
    ("the pool corpus leg draws every family", CONTROLS_SRC,
     '"--families", *POOL_CORPUS_FAMILIES)',
     ")",
     controls_suite, "draws families it does not ask"),
    ("a forcing row coverage reached goes unnoted", CONTROLS_SRC,
     'and _miss_possible(row) and not row.get("missed")):',
     "and False):",
     controls_suite, "coverage reached is not noted"),
    ("a measurement draws only some families", PY_SRC,
     "    if families and not (control and shapes == POOL_SHAPES):",
     "    if False:",
     python_suite, "--families on a measurement was accepted"),
    ("a control's stalled row reads as a measurement's", PY_SRC,
     '            if control:\n                row.setdefault("control", True)',
     '            if False:\n                row.setdefault("control", True)',
     python_suite, "was not stamped a control row"),
    ("the null change accepts an empty selection", CONTROLS_SRC,
     '            elif not row.get("selected_distinct"):',
     "            elif False:",
     controls_suite, "a null change that selected no test passed"),
    ("the null change accepts a full run of any kind", CONTROLS_SRC,
     '                    or row.get("full_run_kind") != EMPTY_CHANGE_SET):',
     "                    ):",
     controls_suite, "a null change forced for another kind passed"),
    ("the neutral edit's miss goes unreported", CONTROLS_SRC,
     '            elif row.get("missed"):',
     "            elif False:",
     controls_suite, "a neutral edit that reported a miss passed"),
    ("the neutral edit's induced failure goes unreported", CONTROLS_SRC,
     '            elif row.get("induced"):',
     "            elif False:",
     controls_suite, "a neutral edit that induced a failure passed"),
    ("the neutral control accepts a fixture without a neutral edit", CONTROLS_SRC,
     "        if not edits:",
     "        if False:",
     controls_suite, "a fixture without a neutral-edit row passed"),
    ("the no-force control does not say which families only narrowed", CONTROLS_SRC,
     "        unread = [family for family in families if family not in read]",
     "        unread = []",
     controls_suite, "the families only asked to narrow are not printed"),
    ("the no-force plant leaves a dependency revert's properties file read", CONTROLS_SRC,
     '|(^|/)gradle\\.properties$",',
     '",',
     controls_suite, "plant leaves gradle.properties read"),
    ("the neutral set measures without --control", PY_SRC,
     "    if shapes == NEUTRAL_SET and not control:",
     "    if False:",
     python_suite, "the neutral set without --control was accepted"),
    ("a control of another set runs on a corpus checkout", PY_SRC,
     "            and shapes not in CORPUS_CONTROL_SETS:",
     "            and False:",
     python_suite, "a neutral control on a corpus checkout was accepted"),
    ("the container names accept any suffix", PY_SRC,
     "    return CONTAINER_FAILURE.fullmatch(name) is not None",
     "    return CONTAINER_FAILURE.match(name) is not None",
     python_suite, "was set aside as a container"),
    # THE BREAK SUITE'S HELPER PROVES EACH ATTEMPT BEFORE IT CLASSIFIES IT. Without the
    # full-run check an attempt whose change cannot fail its target reads as a gap; without the
    # left-out check one that narrowed nothing reads as held; without the declared-verdict check one
    # that held by another rule reads as reaching the boundary it aimed at.
    ("the break helper stops asking whether the change fails the target under a full run", BREAK_FILE,
     "        if (!observed.targetFailsUnderFullRun) {",
     "        if (false && !observed.targetFailsUnderFullRun) {",
     break_suite, "does not fail the target under a full run is vacuous"),
    ("the break helper stops asking whether the selecting run left anything out", BREAK_FILE,
     "        if (observed.leftOut.isEmpty() && observed.forcedKinds.isEmpty()) {",
     "        if (false && observed.leftOut.isEmpty() && observed.forcedKinds.isEmpty()) {",
     break_suite, "leaves no test out is vacuous"),
    ("the break helper stops comparing the recorded verdict with the declared one", BREAK_FILE,
     "        if (!onTarget(observed, aim, held)) {",
     "        if (false && !onTarget(observed, aim, held)) {",
     break_suite, "ran by another rule than the one it aims at is off target"),
    # A changed file no class or build script names may still be read: found by a pattern, walked
    # to, listed in another file, or handed over by path. Dropping it skips the test that reads it.
    ("a changed path outside a source tree is dropped as unread again", SELECTION,
     "        ChangeDetection.split(restriction.kept, facts.rootDir),",
     '        ChangeDetection.split(restriction.kept.filter { "src/" in it }, facts.rootDir),',
     functional_suite, "a file the build script finds by its extension and hands to the tests forces the run"),
    ("any file under an absent module's src drops again", CLASSPATH_SCOPE,
     "            owner != null && owner.second !in present && isClassSource(path, owner.first)",
     '            owner != null && owner.second !in present && path.startsWith("${owner.first}/src/")',
     functional_suite, "a resource of a module off the classpath the build script hands a test by path forces the run"),
    # The two exemptions from that: an IDE's state, and what the build declares unread.
    ("an IDE state directory is a change again", NOT_READ,
     '    private val IDE_STATE_DIRECTORIES = setOf(".idea", ".vscode", ".fleet")',
     '    private val IDE_STATE_DIRECTORIES = setOf<String>()',
     functional_suite, "a map with no working-tree snapshot refuses once, then narrows"),
    ("a file notReadByTests lists is a change again", SELECTION,
     "    val read = paths.filterNot { NotRead.isIdeState(it) || declared(it) }",
     "    val read = paths.filterNot { NotRead.isIdeState(it) }",
     functional_suite, "a changed file notReadByTests lists is no change"),
    # PATHIGNORE. A declared path leaves the change set; what compiles or what the build reads
    # never does, every use is named, and a read the map recorded runs the tests after it.
    ("an entry outside pathIgnore's grammar is accepted", PATH_IGNORE_VALIDATION,
     "    problem(entry)?.let { why ->",
     "    problem(entry)?.takeIf { false }?.let { why ->",
     unit_suite, "entries written for another dialect fail the build naming pathIgnore and the entry"),
    ("a pathIgnore star crosses directories", PATH_IGNORE,
     '        if (pattern[p] == "**") return (s..segments.size).any { matchFrom(pattern, p + 1, segments, it) }',
     '        if ("*" in pattern[p]) return (s..segments.size).any { matchFrom(pattern, p + 1, segments, it) }',
     unit_suite, "each pattern matches what Gradle's include matches"),
    ("pathIgnore protects nothing", PATH_IGNORE,
     "        matched.keys.forEach { path -> protection(path, declaration)?.let { protectedBy[path] = it } }",
     "        matched.keys.forEach { _ -> }",
     unit_suite, "a double star alone leaves out only unprotected paths"),
    ("pathIgnore leaves out a file under a src directory Gradle does not report", PATH_IGNORE,
     '            "src" in directories || declaration.sourceDirs.any { path.startsWith("$it/") } -> Protection.SOURCE_SET',
     '            declaration.sourceDirs.any { path.startsWith("$it/") } -> Protection.SOURCE_SET',
     unit_suite, "a file under any src directory is kept as source-set"),
    ("pathIgnore leaves out a build file", PATH_IGNORE,
     "            isBuildFile(segments.last(), directories) ||",
     "            false ||",
     unit_suite, "build files and build logic are kept as build-file"),
    ("pathIgnore leaves out a path outside the Gradle root", PATH_IGNORE,
     "            path in declaration.outsideRoot -> Protection.OUTSIDE_ROOT",
     "            false -> Protection.OUTSIDE_ROOT",
     unit_suite, "a path outside the Gradle root is kept as outside-root"),
    ("pathIgnore leaves out a path a compiled class names", PATH_IGNORE,
     "            val named = runCatching { namedInClasses(candidates) }.getOrNull() ?: candidates",
     "            val named = emptySet<String>()",
     unit_suite, "a path a compiled class names is kept as named-in-classes"),
    ("the selecting run places no changed path outside the Gradle root", SELECTION_WIRING,
     "        if (null in outside) paths.toSet() else outside.flatMap { it.orEmpty() }.toSet(),",
     "        emptySet(),",
     functional_suite, "a declared path outside a build below the repository root stays in the change set"),
    ("a change set pathIgnore emptied is not accounted for", SELECTING_STEP,
     "            accountedFor(paths, scoped).toString(),",
     "            accountedFor(scoped.ignore.kept, scoped).toString(),",
     functional_suite, "a change to declared paths only runs nothing extra and names each path"),
    ("a declared file's content is not a task input", SELECTION_WIRING,
     "            (ignore?.let { \":\" + ignoredContentDigest(project, it, paths) } ?: \"\"),",
     "            \"\",",
     functional_suite, "editing the same declared file twice runs the task both times"),
    ("yoriwakeExplain applies no pathIgnore", EXPLAIN_TASK,
     "        val scoped = scopedChange(paths, facts, notReadByTests.getOrElse(emptyList()), ignore)",
     "        val scoped = scopedChange(paths, facts, notReadByTests.getOrElse(emptyList()), null)",
     functional_suite, "a change to declared paths only runs nothing extra and names each path"),
    # A class a framework can only run or skip whole is left out only when it holds a test and
    # every one is skipped; any other answer runs it, and its skipped tests are named as rounded up.
    # Plain JUnit 4: a class is left out only through Gradle's own early return, only when the run
    # asked for it, only for runners that run exactly the class's own methods, ignored tests unjudged.
    # narrowPlainRunners reaches the test JVM only on a selecting or observing run of a JUnit 4
    # task, is a task input there, and only a run that had it clears the audit's blocker.
    ("plain JUnit 4 is narrowed without narrowPlainRunners", TEST_TASK_WIRING,
     "            if (narrowPlainRunners && junit4 && (selecting || observing)) {",
     "            if (junit4 && (selecting || observing)) {",
     functional_suite, "without narrowPlainRunners, every class runs"),
    ("a recording run hands narrowPlainRunners to the agent", TEST_TASK_WIRING,
     "            if (narrowPlainRunners && junit4 && (selecting || observing)) {",
     "            if (narrowPlainRunners && junit4) {",
     functional_suite, "a recording run with the flag hands the agent nothing to judge"),
    ("an observing run hands narrowPlainRunners to no agent", TEST_TASK_WIRING,
     "            if (narrowPlainRunners && junit4 && (selecting || observing)) {",
     "            if (narrowPlainRunners && junit4 && selecting) {",
     functional_suite, "an observing run records what it would leave out and leaves out nothing"),
    ("the audit takes the flag for the agent's hook", AUDIT,
     "                    it.copy(plainNarrowed = notes.isNotEmpty() && notes.all { note -> note == AgentContract.PLAIN_FILTER_HOOKED })",
     "                    it.copy(plainNarrowed = true)",
     functional_suite, "the audit stops asking for the JUnit Platform once a selecting run narrowed plain JUnit 4"),
    ("narrowPlainRunners is not a task input", TEST_TASK_WIRING,
     "            test.inputs.property(NARROW_PLAIN_RUNNERS_PROPERTY, true)\n",
     "",
     functional_suite, "the audit stops asking for the JUnit Platform once a selecting run narrowed plain JUnit 4"),
    ("the audit drops the Platform blocker for any plain JUnit 4 task", AUDIT_COMPUTE,
     "        facts.framework == TaskFacts.JUNIT4 && facts.plainNarrowed == true -> Unit",
     "        facts.framework == TaskFacts.JUNIT4 -> Unit",
     functional_suite, "the audit stops asking for the JUnit Platform once a selecting run narrowed plain JUnit 4"),
    ("the build's narrowPlainRunners is ignored", TEST_TASK_WIRING,
     "        val narrowPlainRunners = settings.narrowPlainRunners ?: extension.narrowPlainRunners.getOrElse(false)",
     "        val narrowPlainRunners = settings.narrowPlainRunners ?: false",
     functional_suite, "the build's narrowPlainRunners alone narrows"),
    ("the agent's answer to leave a JUnit 4 class out is not taken", JUNIT4_SELECTION,
     "            return current.judge(runner);",
     "            current.judge(runner);\n            return false;",
     functional_suite, "a class whose tests a change cannot reach is left out when its runner is judged"),
    ("a JUnit 4 runner off the allow-list is judged", JUNIT4_SELECTION,
     "        if (!ALLOWED_RUNNERS.contains(runner.getClass().getName())) {",
     "        if (false) {",
     agent_suite, "a runner that generates tests, or any runner not on the list, keeps its class"),
    ("a JUnit 4 test named for another class is judged as the class's own", JUNIT4_SELECTION,
     "            if (!className.equals(invoke(leaf, \"getClassName\")) || invoke(leaf, \"getMethodName\") == null) {",
     "            if (invoke(leaf, \"getMethodName\") == null) {",
     agent_suite, "JUnit 3's runner over another class's tests keeps its class"),
    ("an ignored JUnit 3 test, which runs anyway, is not judged", JUNIT4_SELECTION,
     "            if (!JUNIT3_RUNNER.equals(runner.getClass().getName()) && ignored(leaf)) {",
     "            if (ignored(leaf)) {",
     functional_suite, "a class whose tests a change cannot reach is left out when its runner is judged"),
    ("an observing run leaves a plain JUnit 4 class out", JUNIT4_SELECTION,
     "        if (run.observing() || !judged.leaveOut()) {",
     "        if (!judged.leaveOut()) {",
     functional_suite, "an observing run records what it would leave out and leaves out nothing"),
    ("an ignored JUnit 4 test is judged", JUNIT4_SELECTION,
     "            if (!JUNIT3_RUNNER.equals(runner.getClass().getName()) && ignored(leaf)) {",
     "            if (false) {",
     functional_suite, "a class whose tests a change cannot reach is left out when its runner is judged"),
    ("Gradle's JUnit 4 executor is rewritten on every run", LOADED_CLASS_RECORDER,
     "            if (AgentContract.NARROW_PLAIN_JUNIT4.equals(\n"
     "                    HostBuild.current().property(AgentContract.NARROW_PLAIN_RUNNERS_PROPERTY))) {",
     "            if (true) {",
     functional_suite, "without narrowPlainRunners, every class runs"),
    ("a class with no test is left out", CLASS_VERDICTS,
     "        boolean allSkipped = !ids.isEmpty();",
     "        boolean allSkipped = true;",
     agent_suite, "a class with no test runs"),
    ("a class is left out when any one of its tests is skipped", CLASS_VERDICTS,
     "            allSkipped &= verdict == Verdict.SKIPPED;",
     "            allSkipped |= verdict == Verdict.SKIPPED;",
     agent_suite, "one test reaching the change keeps the class"),
    ("a kept class's skipped tests are recorded as skipped", CLASS_VERDICTS,
     "                entry.setValue(Verdict.CLASS_GRANULARITY);",
     "                entry.getValue();",
     agent_suite, "one test reaching the change keeps the class, and the rest are rounded up to it"),
    ("a pinned test no longer keeps its class", CLASS_VERDICTS,
     "        if (pinned.test(id)) {",
     "        if (false) {",
     agent_suite, "a pinned test keeps its class"),
    ("a reason to run a class whole leaves its tests to the selector", CLASS_VERDICTS,
     "        return keepReason != null ? Verdict.CLASS_RUNS_WHOLE : verdict;",
     "        return verdict;",
     agent_suite, "a keep reason keeps the class"),
    ("a forced run leaves no paths-ignored note", SELECTION_RUN,
     "            if (pathsIgnored > 0) {",
     "            if (pathsIgnored > 0 && daemonRefusal() == null && decision != null && !decision.isFullRun()) {",
     functional_suite, "a forced run still names what it left out"),
    ("a read ignored path opens no window", SELECTOR,
     "            Map<String, Integer> opens = ignoredPathWindows(map, change.ignoredPaths());",
     "            Map<String, Integer> opens = new HashMap<>();",
     agent_suite, "a read ignored path runs the tests from its first read on in the JVM that read it"),
    ("a read of a directory above an ignored path is not a read of it", SELECTOR,
     '            if (file.equals(path) || file.startsWith(path + "/")) {',
     "            if (file.equals(path)) {",
     agent_suite, "a read of a directory above an ignored path reads it"),
    ("a JVM that read every file did not read an ignored path", SELECTOR,
     "    public static boolean readsIgnored(String path, Collection<String> ignored) {\n"
     "        if (AgentContract.FIRST_TOUCH_ANY.equals(path)) {",
     "    public static boolean readsIgnored(String path, Collection<String> ignored) {\n"
     "        if (false) {",
     agent_suite, "a JVM that read every file from a point runs from there on an ignored path"),
    ("a test with no position stays skipped when a JVM read an ignored path", SELECTOR,
     "            return !opens.isEmpty();",
     "            return false;",
     agent_suite, "a test the map holds no position for runs once a JVM read an ignored path"),
    ("a read of an entry of an ignored archive is not a read of it", SELECTOR,
     '            if (path.startsWith(file + "!/")) {',
     "            if (false) {",
     agent_suite, "a read of an entry of an ignored archive reads it"),
    ("a JVM whose record leaves out the sources it opened vouches for an ignored source file", SELECTOR,
     "                    if (reads == null || !reads.recordedSources(position.jvm())) {",
     "                    if (false) {",
     agent_suite, "an ignored source file runs every test of a JVM whose record leaves out the sources it opened"),
    ("pathIgnore leaves out a path git printed in quotes", PATH_IGNORE,
     '            path.startsWith("\\"") -> Protection.GIT_QUOTED',
     "            false -> Protection.GIT_QUOTED",
     unit_suite, "a path git printed in quotes is kept as git-quoted"),
    ("the selecting run knows no build this one includes", SELECTION_WIRING,
     "    runCatching { project.gradle.includedBuilds.map { it.projectDir } }.getOrDefault(emptyList())",
     "    emptyList<File>()",
     functional_suite, "a declared path in a build this one includes stays in the change set"),
    ("a source file a test opens is not a read of its path", TOUCH_RECORDER,
     '                if (!path.endsWith(".class")) {',
     "                if (false) {",
     agent_suite, "a source file read beneath the root is kept by its path too"),
    # THE ENVIRONMENT FINGERPRINT. A map recorded under another JDK, other system properties
    # or another value of a listed variable says which tests a change reaches somewhere else.
    ("the environment comparison always matches", ENVIRONMENT_FINGERPRINT,
     "        for (fingerprint in recorded) {",
     "        for (fingerprint in recorded.take(0)) {",
     functional_suite, "a map recorded on another JDK runs everything, names both, and records this one"),
    ("a map with no fingerprint reads as a match", ENVIRONMENT_FINGERPRINT,
     "            ?: return Check(Verdict.Unrecorded(UNRECORDED), current.ignored)",
     "            ?: return Check(Verdict.Matches, current.ignored)",
     functional_suite, "a map recorded with no fingerprint runs everything once"),
    ("the environment check is not registered", SELECTION_WIRING,
     "    refuseAChangedEnvironment(test, mapDir, environment, jacoco, changeSetFile, observing)",
     "    if (false) refuseAChangedEnvironment(test, mapDir, environment, jacoco, changeSetFile, observing)",
     functional_suite, "another value of a system property runs everything, and no value is printed or stored"),
    # Without it the old fingerprint stays, and every later selecting run refuses again.
    ("a refusal over the environment calls the map current and records nothing", SELECTING_STEP,
     "                ageKnown = false,\n            ),\n        )\n    }\n\n    /**\n     * Hands the list-valued",
     "                ageKnown = true,\n            ),\n        )\n    }\n\n    /**\n     * Hands the list-valued",
     functional_suite, "a map recorded on another JDK runs everything, names both, and records this one"),
    ("environmentInclude adds no variable to the comparison", TEST_TASK_WIRING,
     '            validNames(extension.environmentInclude.getOrElse(emptyList()), "environmentInclude", project.path),',
     '            validNames(emptyList(), "environmentInclude", project.path),',
     functional_suite, "a listed variable with another value runs everything"),
    ("a system property's value is stored in plain text", ENVIRONMENT_FINGERPRINT,
     "                else -> components[PROPERTY + name] = digest(salt, PROPERTY + name, rules.normalize(value))",
     "                else -> components[PROPERTY + name] = rules.normalize(value)",
     functional_suite, "another value of a system property runs everything, and no value is printed or stored"),
    ("yoriwakeExplain stops comparing the environment", EXPLAIN_TASK,
     "        environment?.let { rules ->",
     "        environment?.takeIf { false }?.let { rules ->",
     functional_suite, "a map recorded on another JDK runs everything, names both, and records this one"),
    # The java launcher expands an `@file` argument into the arguments the file holds.
    ("a JVM argument file is not read for the fingerprint", TEST_TASK_WIRING,
     "JvmArgFiles.expand(test.allJvmArgs, test.workingDir)", "test.allJvmArgs",
     functional_suite, "another value of a system property from a JVM argument file runs everything"),
    ("an argument file that cannot be read sets nothing", JVM_ARG_FILES,
     '            ?: return listOf("-D@$path=${UUID.randomUUID()}")', "            ?: return emptyList()",
     unit_suite, "an argument file that cannot be read matches no other run"),
    # Gradle lists the properties it sets ahead of an argument file and sets them after the JVM
    # starts, so the last value on the list is not always the one a test sees.
    ("a property given twice is compared by its last value only", ENVIRONMENT_FINGERPRINT,
     '        properties.mapValues { it.value.joinToString("\\u0000") }',
     "        properties.mapValues { it.value.last() }",
     unit_suite, "a property given twice is compared by both values"),
    # NEW JDK RELEASES. A class or test JVM newer than a reader of it must be refused by
    # name; without the check the run still forces, but as an unreadable digest or as nothing.
    ("a class above the ceiling is not refused", CLASS_FILE_CEILING,
     "    if (versions.maxMajor > ceiling) return ceilingRefusal(versions, jacocoVersion, jacocoCeiling)",
     "    if (false) return ceilingRefusal(versions, jacocoVersion, jacocoCeiling)",
     functional_suite, "a class compiled for a newer Java than the plugin reads runs every test"),
    ("the JaCoCo ceiling is ignored", CLASS_FILE_CEILING,
     "    val ceiling = minOf(ClassFileSupport.asmCeiling(), jacocoCeiling)",
     "    val ceiling = ClassFileSupport.asmCeiling()",
     functional_suite, "classes newer than the host's JaCoCo officially instruments run every test"),
    ("a test JVM above the agent's ceiling is not refused", CLASS_FILE_CEILING,
     "    if (jvmMajor != null && jvmMajor > agentCeiling) return testJvmRefusal(jvmMajor, agentCeiling, classes)",
     "    if (false) return testJvmRefusal(jvmMajor!!, agentCeiling, classes)",
     functional_suite, "a test JVM newer than the agent reads runs every test"),
    ("the agent backstop does not force", SELECTOR,
     "            return Decision.fullRun(reason, Decision.FullRunKind.TEST_JVM_UNSUPPORTED, knownTestIds);",
     "            ;",
     agent_suite, "an over-ceiling JVM runs every test"),
    # THE SCHEDULED FULL RUN. Past the configured bound a selecting run runs everything; an
    # unreadable count is due, or a broken counter would postpone the bound forever.
    ("the scheduled full run never falls due", SCHEDULE,
     "        if (!mapExists) return null",
     "        if (true) return null",
     functional_suite, "the Nth build after a full run runs every test and starts the count again"),
    ("an unreadable count reads as zero", SCHEDULE,
     '            val n = countIn(count) ?: return "the count of builds since the last full run ($COUNT_FILE) could not be read"',
     "            val n = countIn(count) ?: 0",
     functional_suite, "a count that cannot be read runs every test and says so"),
    # The sharded sweep: the merge, the slicing, and a shard's reuse of the recording job.
    ("the merge accepts a sweep missing a shard", "bench/merge.py",
     "    if missing:\n        raise Refused(f\"{recording['project']}: no output from \"",
     "    if False:\n        raise Refused(f\"{recording['project']}: no output from \"",
     sharding_suite, "a missing shard"),
    ("the merge mixes rows measured under another selector", "bench/merge.py",
     "            if data.get(stamp) != recording.get(stamp):",
     "            if False:", sharding_suite, "a mixed selector"),
    ("the merge keeps rows past the narrowed target", "bench/merge.py",
     "        if run.narrowed_target_reached(kept, accepted.get(\"narrowed_target\")):",
     "        if False:", sharding_suite, "not the prefix ending at the row that reached the target"),
    ("the merge publishes an arm that asked no recall question", "bench/merge.py",
     "    if not possible:", "    if False:", sharding_suite, "an arm that asked nothing"),
    ("a slice is planned around a row no job can finish", "bench/shard.py",
     "        if overhead + seconds > JOB_LIMIT_SECONDS:", "        if False:", sharding_suite,
     "a row longer than a job's limit was planned"),
    ("a recording run without a map passes on to its shards", "bench/run.py",
     "    if not recording or capture_map:", "    if True:", python_suite,
     "a recording run without a map was not refused"),
    ("a build killed at the timeout passes as one that ran", "bench/process.py",
     "                return STALLED, round(time.time() - started, 1)\n            size = ",
     "                return -1, round(time.time() - started, 1)\n            size = ", process_suite,
     "a build killed at the timeout was not dumped and filed as stalled"),
    ("the harness goes on while a killed build's daemon still writes", "bench/run.py",
     "    if result[0] == STALLED:\n        stop_daemons(checkout)",
     "    if result[0] == STALLED:\n        pass", python_suite,
     "a build that returned"),
    ("a shard measures a list drawn under another grammar", "bench/run.py",
     "        if accepted.get(key) != stamps.get(key):", "        if False:", python_suite,
     "a list drawn under another grammar was accepted"),
    ("a shard times its rows as if it were the first", "bench/run.py",
     "    return [(path, kind, index) for path, kind, index, _ in accepted[\"targets\"][start:end]], start",
     "    return [(path, kind, index) for path, kind, index, _ in accepted[\"targets\"][start:end]], 0",
     python_suite, "so its timed rows would be chosen as if it were first"),
    ("a shard copies its fixture afresh, as a commit the map cannot relate to", "bench/run.py",
     "        if keep_state and os.path.isdir(os.path.join(path, \".git\")):",
     "        if False:", python_suite, "discarded the recording job's map"),
    ("a CI row stops naming its runner and job", "bench/run.py",
     "    if os.environ.get(\"GITHUB_ACTIONS\") != \"true\":", "    if True:", python_suite,
     "the CI stamp is"),
    ("the workflow's plan drops the reduced arm's ground truth", "bench/sweep_corpus.py",
     "                                        ground_truth=ground_truth_for(name, args.arm,\n"
     "                                                                      args.ground_truth))[2:]}",
     "                                        ground_truth=args.ground_truth)[2:]}",
     sweep_suite, "the reduced arm's rules were not carried"),
    ("a change to selection logic alone is swept rather than replayed", "bench/tier.py",
     "    elif all(path.startswith(SELECTION) for path in selector):", "    elif False:",
     sharding_suite, "expected replay"),
    ("a capture rewrite is swept as a small capture change", "bench/tier.py",
     "    if major_release or CAPTURE_SESSION in selector:", "    if major_release:",
     sharding_suite, "expected full"),
    ("a replay that replayed nothing passes", "bench/replay_decisions.py",
     "    if not any(e[\"verdict\"] != \"not-replayed\" for e in results):", "    if False:",
     replay_suite, "a replay of zero rows passed"),
    ("the time chart takes a ratio across two jobs", "bench/readme_charts.py",
     "    return row.get(\"full_ci\", row.get(\"ci\")) == row.get(\"ci\")", "    return True",
     sweep_suite, "a ratio whose arms ran in two jobs was drawn into the median"),
    ("the time chart quotes absolute seconds from a shared runner", "bench/readme_charts.py",
     "    on_ci = any(r.get(\"ci\") for p in drawn.values() for r in p[\"rows\"])",
     "    on_ci = False", sweep_suite, "quoted an absolute time measured on a shared runner"),
    # A shard's rows, and what the scan reads.
    ("a stalled row on a shard carries no kind or index", "bench/run.py",
     '            row.setdefault("kind", kind)\n', "            pass\n", python_suite,
     "a stalled row was not stamped"),
    ("the merge keeps rejections drawn past the narrowed target", "bench/merge.py",
     "                   else entry[3] < len(kept))]",
     "                   else True)]", sharding_suite,
     "rejections after truncation"),
    ("the scan skips a file whose name git quotes", "bench/scan_private.py",
     '        hits += scan_added(sha, git(root, "-c", "core.quotePath=false", "show", "--format=",',
     '        hits += scan_added(sha, git(root, "show", "--format=",', scan_suite,
     "in a file git quotes was not scanned"),
    ("the scan passes a path it cannot read", "bench/scan_private.py",
     '            hits.append(f"commit {sha[:9]} {line[4:]}: unscannable path: rename it")',
     "            pass", scan_suite, "a path git must still quote passed unscanned"),
    ("the scan stops reading a pull request's title and body", "bench/scan_private.py",
     '    return scan_text(label, text or "", MESSAGE_RULES)', "    return []", scan_suite,
     "a plan id in a pull request title passed"),
    # The changelog check: a line under a released version, the release entry it must let through,
    # and the workflow that runs it.
    ("the changelog check stops refusing a released version", ".github/ci.py",
     "        if version in released:", "        if False:", ci_suite,
     "a line added under 0.2.0 should be one problem"),
    ("the changelog check reads released versions after the change", ".github/ci.py",
     "    released = {match.group(1) for match in map(VERSION_HEADING.match, before.split(\"\\n\")) if match}",
     "    released = {match.group(1) for match in map(VERSION_HEADING.match, after.split(\"\\n\")) if match}",
     ci_suite, "a release entry was refused"),
    ("the changelog workflow stops running the check", ".github/workflows/changelog.yml",
     '        run: python3 .github/ci.py changelog "$BASE" "$HEAD"', "        run: true", ci_suite,
     "no step runs the changelog check"),
    # The revert set: which commits it reverts, how, and in what order.
    ("a test-compile failure counts as build-failed again", "bench/revert.py",
     '    if compiles:\n        return "test-compile"', '    if compiles:\n        return None',
     revert_suite, "read as None, not test-compile"),
    ("a revert reaches paths outside the source roots", "bench/revert.py",
     "    paths = sorted({p for _, old, new in changes for p in (old, new) if _under(p, roots)})",
     "    paths = sorted({p for _, old, new in changes for p in (old, new)})",
     revert_suite, "the revert reaches beyond the roots"),
    ("a reverse patch that does not apply takes a slot", "bench/revert.py",
     "    if not applies(checkout, text):", "    if False:", revert_suite,
     "a commit whose lines a later commit changed was drawn"),
    ("one patch reverted twice is drawn twice", "bench/revert.py",
     '        if candidate.get("patch_id") in seen:', "        if False:", revert_suite,
     "of the two identical patches were drawn"),
    ("a stratum is drawn out before the next is reached", "bench/revert.py",
     "            if by_stratum[name]:\n                ordered.append(by_stratum[name].pop(0))",
     "            while by_stratum[name]:\n                ordered.append(by_stratum[name].pop(0))",
     revert_suite, "a stratum was drawn twice before every one was drawn once"),
    ("the revert draw reads whether a row missed", "bench/revert.py",
     "    reached = sum(1 for row in rows if counts_toward(row))",
     '    reached = sum(1 for row in rows if counts_toward(row) and not row.get("missed"))',
     revert_suite, "missed set changed the draw"),
    ("a second revert shortfall widens the window again", "bench/revert.py",
     "    if WIDENED_WINDOW not in by_window:", "    if True:", revert_suite,
     "a second shortfall widened again"),
    ("a failed compile task with no compiler diagnostic is a revert rejection", "bench/revert.py",
     "    if compiles and not DIAGNOSTIC.search(log):", "    if False:", revert_suite,
     "Java heap space' read as main-compile"),
    ("an unresolved dependency is a revert rejection", "bench/revert.py",
     "    if UNRESOLVED.search(log):\n        return None", "    if False:\n        return None",
     revert_suite, "Could not resolve all files"),
    ("a revert row is labelled several-modules for code it left in place", "bench/revert.py",
     '        dropped.add("several-modules")', "        pass", revert_suite,
     "which it left in place"),
    ("a main-compile revert rejection is retried as the full revert", "bench/revert.py",
     '    if reason != "test-compile":', "    if reason is None:", revert_suite,
     "a main-compile failure gave"),
    ("a revert results file is held to the shared rejection ceiling", "bench/corpus_state.py",
     '    if rejected and data.get("target_shapes") != run.REVERT_SET \\',
     "    if rejected and True \\", corpus_suite, "offered in revert/v1 read"),
    ("every set skips the shared rejection ceiling", "bench/corpus_state.py",
     '    if rejected and data.get("target_shapes") != run.REVERT_SET \\',
     "    if rejected and False \\", corpus_suite, "offered in recall-arm read"),
    # The `forcing/v1` set: its generators, the cap a project, the Groovy gate and the flag-off
    # finding.
    ("a resource edit lands on a continued properties line", FORCING_MUTATIONS,
     '            if not above.endswith("\\\\"):', "            if True:", mutations_suite,
     "a value continued over two lines is left alone"),
    ("the services edit empties a file naming one provider", FORCING_MUTATIONS,
     "    if len(providers) < 2 or index >= len(providers):",
     "    if index >= len(providers):", mutations_suite, "and is never emptied"),
    ("the build-script edit takes a script outside the measured module", FORCING_MUTATIONS,
     '        relative = f"{module}/{name}" if module else name', "        relative = name",
     mutations_suite, "the measured module's script is the one drawn"),
    ("the build-script property is named where the fingerprint never looks", FORCING_MUTATIONS,
     'PROPERTY = ("mutation.marker", "true")', 'PROPERTY = ("yoriwake.mutated", "true")',
     mutations_suite, "which its fingerprint leaves out"),
    ("groovy-body globs no Groovy source", FORCING_MUTATIONS,
     '    for relative in _files(checkout, roots, ["*.groovy"]):',
     '    for relative in _files(checkout, roots, ["*.java"]):', mutations_suite,
     "a .groovy source under the roots is globbed"),
    ("the groovy-body edit goes braceless", FORCING_MUTATIONS,
     '    _write(path, pool_mutations._entry(text, match, ("if (true) {", "    " + throw, "}")))',
     '    _write(path, pool_mutations._entry(text, match, ("if (true) " + throw,)))',
     mutations_suite, "the second method throws at its entry"),
    ("a forcing family draws a second row a project", FORCING_MUTATIONS,
     "    return rows >= limit", "    return rows > limit", mutations_suite,
     "a second row of a family is not drawn"),
    ("a forcing family is drawn out before the next is reached", FORCING_MUTATIONS,
     "            if by_family[family]:\n                ordered.append(by_family[family].pop(0))",
     "            while by_family[family]:\n                ordered.append(by_family[family].pop(0))",
     mutations_suite, "every family's first draw comes before any second"),
    ("a narrowing flag leaves the forcing rows flag-off", FORCING_MUTATIONS,
     '        if name in NARROWING_FLAGS and value.strip().lower() != "false":',
     "        if False:", mutations_suite, "a narrowing flag makes the rows a flag-on population"),
    ("groovyc's startup failure reads as build-failed", PY_SRC,
     '    return ("error:" in log or "Compilation error" in log or "startup failed:" in log',
     '    return ("error:" in log or "Compilation error" in log', mutations_suite,
     "groovyc's startup failed: is a rejection"),
    ("Gradle's Groovy compile banner reads as build-failed", PY_SRC,
     '            or ("Compilation failed" in log and GROOVY_COMPILE_TASK.search(log) is not None))',
     "            or False)", mutations_suite,
     "Gradle's Groovy Compilation failed banner is a rejection"),
    ("a dependency revert also reverts code", FORCING_MUTATIONS,
     "    if not paths or None in kinds.values():", "    if not paths:", revert_suite,
     "the version-only commits drawn were"),
    ("a build-script line that moves no version is a dependency revert", FORCING_MUTATIONS,
     "    return all(VERSION_LINE.search(line[1:]) for line in changed)",
     "    return True", revert_suite, "the version-only commits drawn were"),
    ("a commit that adds a dependency is a dependency revert", FORCING_MUTATIONS,
     "    if not changed or removed * 2 != len(changed):", "    if not changed:", revert_suite,
     "the version-only commits drawn were"),
    ("an unresolved artifact is build-failed for a dependency revert", FORCING_MUTATIONS,
     '    if revert.UNRESOLVED.search(log):\n        return "dependency-resolution"',
     '    if False:\n        return "dependency-resolution"', revert_suite,
     "not dependency-resolution"),
    ("a forcing row that narrowed with the flags off passes the recall gate",
     "bench/assert_recall.py",
     '        if narrowed and population == "flag-off":', "        if False:", sweep_suite,
     "a narrowed forcing row with flags [] exited 0"),
    ("a forcing results file is held to the shared rejection ceiling", "bench/corpus_state.py",
     '            and data.get("target_shapes") != run.FORCING_SET \\\n', "", corpus_suite,
     "offered in forcing/v1 read"),
    ("the forcing set sweeps the whole corpus", "bench/sweep_corpus.py",
     "    if args.set == run.FORCING_SET:", "    if False:", sweep_suite, "forcing/v1 planned"),
    ("the forcing set is stamped with the default grammar digest", PY_SRC,
     '                     FORCING_SET: ("bench/forcing_mutations.py", "bench/pool_mutations.py",',
     '                     "unused": ("bench/forcing_mutations.py", "bench/pool_mutations.py",',
     python_suite, "the forcing set is stamped with another set's grammar digest"),
    ("a forcing row the selector narrowed onto every test reads as forced",
     "bench/assert_recall.py",
     '    return narrowed_at_all(finding) or finding.get("decision_outcome") == "narrowed"',
     "    return narrowed_at_all(finding)", corpus_suite, "a forcing file with flags [] read"),
    ("a forcing file that narrowed with the flags off is not dirty", "bench/corpus_state.py",
     '        if escaped and population == "flag-off":\n            return {**entry, "verdict": DIRTY,',
     '        if False:\n            return {**entry, "verdict": DIRTY,', corpus_suite,
     "a forcing file with flags [] read"),
    ("a forcing survey prints a recall composition", "bench/corpus_state.py",
     '    if shape_set == run.FORCING_SET:\n        print(f"\\nCOMPOSITION: none',
     '    if False:\n        print(f"\\nCOMPOSITION: none', corpus_suite,
     "a forcing survey printed a recall composition"),
    ("the forcing set runs on the reduced arm", "bench/sweep_corpus.py",
     '    if args.set == run.FORCING_SET and args.arm == "reduced":', "    if False:",
     sweep_suite, "forcing/v1 ran on the reduced arm"),
    # `pool/v1`'s family round-robin, its per-family stops and the learned order.
    ("the draw reads whether a row missed", FAMILY_ORDER,
     '    reached = sum(1 for row in findings if row.get("kind") == kind and counts_toward(row))',
     '    reached = sum(1 for row in findings if row.get("kind") == kind and counts_toward(row)\n'
     '                  and not row.get("missed"))', order_suite,
     "flipping every row's missed set changed the draw"),
    ("a family gets a second draw before every family got one", FAMILY_ORDER,
     "    families = sorted(queues, key=lambda family: _rank(seed, family))",
     "    return [t for f in sorted(queues, key=lambda f: _rank(seed, f)) for t in queues[f]]",
     order_suite, "not one of each family"),
    ("a family stops at the project's target, not its own", FAMILY_ORDER,
     '    reached = sum(1 for row in findings if row.get("kind") == kind and counts_toward(row))',
     "    reached = sum(1 for row in findings if counts_toward(row))", order_suite,
     "stopped with body instead of at its own cap"),
    ("a family's row cap counts every family's rows", FAMILY_ORDER,
     "    if family_rows(kind, findings, recorded) >= cap:",
     "    if len(findings) + len(recorded) >= cap:", order_suite,
     "did not stop at the cap of 3"),
    ("the learned order ranks an archived target", FAMILY_ORDER,
     "    fresh = [t for t in ordered if t not in archived]",
     "    fresh, archived = list(ordered), set()", order_suite,
     "an archived target was ranked"),
    ("the learned order is stamped as the round-robin", FAMILY_ORDER,
     '    return f"{ORDERING}+{LEARNED}" if adopted else ORDERING', "    return ORDERING",
     order_suite, "the learned order is stamped as the round-robin"),
    ("a pool merge stops every family at the project's target", "bench/merge.py",
     '    pool = recording.get("target_shapes") == run.POOL_SHAPES',
     "    pool = False", order_suite, "the merge kept"),
    ("a pool merge keeps a rejection drawn after its family's last row", "bench/merge.py",
     "               if (entry[3] <= stopped_at.get(entry[1], len(ordered)) if pool",
     "               if (entry[3] < stopped_at.get(entry[1], len(ordered)) if pool", order_suite,
     "a rejection drawn after its family stopped was kept, or one before dropped"),
    ("a pool merge keeps the recording job's empty family counters", "bench/merge.py",
     "    if pool and stops is not None:", "    if False:", order_suite,
     "the merged file's family counters are not a single job's"),
    ("a recording job's family cap ignores the targets it accepted", FAMILY_ORDER,
     "            + sum(1 for entry in recorded if entry[1] == kind))", "            )",
     order_suite, "a family with its cap of targets accepted still draws"),
    ("the accepted list does not carry the pool's family target", PY_SRC,
     '                   "family_target": (RUN_STAMPS.get("family_order") or {}).get("target"),',
     '                   "family_target": None,', order_suite,
     "the accepted list has family_target"),
    ("a sharded pool run takes a timing cap", PY_SRC,
     "    if sharding and args.timing_cap is not None and args.shapes == POOL_SHAPES:",
     "    if False:", python_suite, "with --timing-cap was not refused"),
    ("a sharded pool plan takes a timing cap", "bench/sweep_corpus.py",
     "        if args.set == run.POOL_SHAPES and timed:", "        if False:", sweep_suite,
     "pool/v1 was planned with a timing cap on a sharded run"),
    ("the order replay reads whether a row missed", REPLAY_ORDER,
     "    return run.counts_toward_narrowed_target(row)",
     '    return run.counts_toward_narrowed_target(row) and not row.get("missed")', order_suite,
     "every row's missed set flipped changed the comparison"),
    ("a project losing its last miss-possible row does not block adoption", REPLAY_ORDER,
     '                             and not result["projects_losing"])', ")", order_suite,
     "a family where a project loses its last such row was adopted"),
    ("the pool's draw order is not in its grammar digest", PY_SRC,
     '{POOL_SHAPES: ("bench/pool_mutations.py", "bench/family_order.py",',
     '{POOL_SHAPES: ("bench/pool_mutations.py",', python_suite,
     "a change to family_order.py left the pool's grammar digest alone"),
    # The docs' claims about names. Each starts where the claim does: a doc that names something
    # the code lacks, or code that gains something the doc does not name.
    ("the reference's Properties table lists a flag Settings does not read", REFERENCE_DOC,
     "| `yoriwake.base=<ref>` |", "| `yoriwake.baseRef=<ref>` |", reference_suite,
     "every property the table lists is read"),
    ("Settings reads a flag the reference's Properties table does not list", SETTINGS,
     '        const val BASE = SettingNames.BASE\n',
     '        const val BASE = SettingNames.BASE\n        const val BASE_REF = "yoriwake.baseRef"\n',
     reference_suite, "every flag the plugin reads is listed"),
    ("the reference names a -P flag Settings does not read", REFERENCE_DOC,
     "**`-Pyoriwake.fullRun`**", "**`-Pyoriwake.fullrun`**", reference_suite,
     "every -P flag the docs name is read"),
    ("the plugin reads a Gradle property outside Settings", YORIWAKE_PLUGIN,
     "public fun isDisabled(project: Project): Boolean = Settings.of(project).disabled",
     "public fun isDisabled(project: Project): Boolean =\n"
     "            project.providers.gradleProperty(Settings.DISABLED).isPresent", reference_suite,
     "only Settings reads Gradle properties"),
    ("the reference names a -D property the plugin never names", REFERENCE_DOC,
     "`-Dpts.enabled=true`", "`-Dpts.enable=true`", reference_suite,
     "every -D flag the reference names is in the code"),
    ("the plugin reads a system property of the daemon", SETTINGS,
     "            Settings { project.providers.gradleProperty(it).orNull }",
     "            Settings { project.providers.gradleProperty(it).orNull ?: project.providers.systemProperty(it).orNull }",
     reference_suite, "the plugin reads no undocumented -D flag"),
    ("the extension's enabled defaults to false", YORIWAKE_PLUGIN,
     "enabled.convention(true)", "enabled.convention(false)", reference_suite,
     "each extension default is the table's"),
    ("the reference's extension table gives a property another type", REFERENCE_DOC,
     "| `fullRunEveryDays` | `Int` |", "| `fullRunEveryDays` | `Long` |", reference_suite,
     "each extension property has the table's type"),
    ("the reference's extension table leaves a property out", REFERENCE_DOC,
     "| `notReadByTests` | `List<String>` |", "| `notReadByTest` | `List<String>` |",
     reference_suite, "every extension property is listed"),
    ("an extension example sets a property the table does not list", REFERENCE_DOC,
     "    fullRunEveryDays = 7   ", "    fullRunEveryWeeks = 1  ", reference_suite,
     "every extension example names only listed properties"),
    ("the contract leaves a published token out", CONTRACT_DOC,
     "`git-unavailable`, ", "", reference_suite, "the contract lists every published token"),
    ("the contract lists a token the code does not publish", CONTRACT_DOC,
     "| framework | `junit-platform` |", "| framework | `junit-platform`, `junit-vintage` |",
     reference_suite, "the contract lists no other token"),
    ("the reference quotes a reason the code does not have", REFERENCE_DOC,
     "| `not-in-map` |", "| `not-in-the-map` |",
     reference_suite, "every quoted name exists in the code"),
    ("the reference lists a map file the code does not write", REFERENCE_DOC,
     "- `selection.tsv`:", "- `selections.tsv`:",
     reference_suite, "every quoted file exists in the code"),
    ("the reference's Tasks table leaves a registered task out", REFERENCE_DOC,
     "| `yoriwakeDecode<Task>` |", "| `yoriwakeDecoder<Task>` |",
     reference_suite, "every registered task is listed"),
    ("the reference's Tasks table names a task the plugin does not register", REFERENCE_DOC,
     "| `yoriwakeAudit<Task>` |", "| `yoriwakeAudits<Task>` |", reference_suite,
     "every task the docs name is registered"),
    ("the reference quotes a printed line the code does not print", REFERENCE_DOC,
     "`:test map updated: N records captured, M known`", "`:test map refreshed: N records captured, M known`",
     reference_suite, "every printed line is printed"),
    # forkCount=0 runs Surefire inside Maven's own JVM, which no agent joins: it must be declined
    # by name rather than wired and silently capture nothing.
    ("forkCount 0 is not declined", MAVEN_DECLINES,
     "        if (forkCount != null && forks(forkCount) == 0) {",
     "        if (false) {",
     maven_suite, "forkCount 0 runs Surefire inside Maven's own JVM and is declined"),
    # Surefire fails its mojo when a test fails, and the decode must run then too, as Gradle's
    # finalizer does: otherwise a failing capture never reaches the map.
    ("the Maven decode does not run after a failed test", MAVEN_LISTENER,
     "    override fun mojoFailed(event: ExecutionEvent) {\n        recording.ended(event, succeeded = false)\n",
     "    override fun mojoFailed(event: ExecutionEvent) {\n",
     maven_suite, "a run with a failing test still decodes and dates the map"),
    # Classes this session did not compile cannot be vouched for, so their run captures nothing.
    ("a Maven run that compiled nothing captures", MAVEN_RECORDING,
     "        if (!compiledHere(project)) {",
     "        if (false) {",
     maven_suite, "a goal typed on its own did not compile the classes and captures nothing"),
    # A reactor module this invocation did not build came from a repository: its bytes are not the
    # checkout's, so the run must not stamp them at HEAD.
    ("a Maven run against a module from a repository captures", MAVEN_RECORDING,
     "        if (outside.isNotEmpty()) {",
     "        if (false) {",
     maven_suite, "a module built against a reactor module from a repository captures nothing"),
    # Surefire hands the JUnit Platform its parallel switch through its own parameters: missed there,
    # interleaved tests would blend into one another's coverage.
    ("Surefire's parameters are left out of the parallelism check", MAVEN_RECORDING,
     "        return ParallelismDetector.detect(parameters, arguments(resolvedArgLine(project, configuration)), classpath)",
     "        return ParallelismDetector.detect(emptyMap(), arguments(resolvedArgLine(project, configuration)), classpath)",
     maven_suite, "parallel execution enabled for the JUnit Platform is declined and captures nothing"),
    # What reaches the JVM is the authority: an argLine without JaCoCo's agent records no coverage.
    ("a Maven argLine without JaCoCo's agent is captured", MAVEN_RECORDING,
     "        if (!reachesJacoco(project, configuration)) {",
     "        if (false) {",
     maven_suite, "an argLine that drops JaCoCo's agent captures nothing"),
    # JUnit 4 reports a failing @BeforeClass, @AfterClass or @ClassRule against the class: each
    # test beneath it is recorded failed, so a capture that dates the map leaves none known to pass.
    ("a JUnit 4 class-level failure is dropped", JUNIT4_EVENTS,
     "            } else if (classNameOf(description) != null) {",
     "            } else if (false) {",
     functional_suite, "a plain JUnit 4 class whose teardown fails after its tests passed still runs"),
    # A runner test's fixture class failing inside JUnitCore is that nested run's, not a test of the run.
    ("a nested run's class-level failure is recorded", CAPTURE_SESSION,
     "        if (nestedRuns) {\n            // A nested run reports its own class-level failures",
     "        if (false) {\n            // A nested run reports its own class-level failures",
     agent_suite, "a class-level failure inside an open test is a nested run's, and records nothing"),
]

# THE TESTS EACH SUITE RUNS, so a change to a test selects the guards that test is what proves.
SUITE_TESTS = {
    "sweep": ("bench/test_sweep_corpus.py",),
    "scan": ("bench/test_scan_private.py",),
    "ci": (".github/test_ci.py",),
    "sharding": ("bench/test_merge.py", "bench/test_shard.py", "bench/test_tier.py"),
    "controls": ("bench/test_controls.py", "bench/test_cost.py"),
    "preflight": ("bench/test_sweep_preflight.py",),
    "process": ("bench/test_process.py",),
    "corpus": ("bench/test_corpus_state.py",),
    "replay": ("bench/test_replay_decisions.py", "bench/Replay.java"),
    "mutations": ("bench/test_mutations.py",),
    "order": ("bench/test_family_order.py", "bench/test_replay_order.py"),
    "revert": ("bench/test_revert.py",),
    "decoder": ("plugin/src/test/",),
    "reference": ("plugin/src/test/", REFERENCE_DOC, CONTRACT_DOC),
    # The agent `test` task puts the boot fixture on the boot class path, so it proves agent guards.
    "agent": ("agent/src/test/", "agent/src/bootFixture/"),
    "jdk identity": ("agent/src/jdkIdentity/", "agent/src/bootFixture/"),
    "unit": ("plugin/src/test/",),
    "qualify": ("bench/test_qualify.py",),
    "config cost": ("bench/test_config_cost.py",),
    "sbom": ("plugin/build.gradle.kts", "agent/build.gradle.kts"),
    "portal": ("plugin/build.gradle.kts", "scripts/check_portal_upload.py"),
    "configuration guards": ("plugin/src/functionalTest/",),
    "python": ("bench/test_run.py",),
    "functional": ("plugin/src/functionalTest/",),
    "break": ("plugin/src/functionalTest/",),
    "maven": ("maven/src/functionalTest/", "samples/demo-maven/"),
}
# A change to how the Gradle suites are BUILT can break any of them, so it selects all of them.
GRADLE_SUITES = {"decoder", "agent", "jdk identity", "unit", "configuration guards", "functional",
                 "replay", "break", "sbom", "portal", "reference", "maven"}
# Run with NOTHING beside them. The functional suite has failed unmutated in a worker while only
# Python suites ran next to it, and passed alone in the same tree: its TestKit builds compare
# file timestamps, and load moves them. Its guards run after the parallel phase, one at a time. The
# configuration guards start TestKit builds too, and since the suites below run unlocked beside each
# other, holding the lock would no longer keep that load away from them.
EXCLUSIVE_SUITES = {"functional", "configuration guards", "break"}
# Gradle suites that start no TestKit build and compare no file timestamps, so they run in parallel
# across the workers, each in its own worktree. Named rather than the locked ones listed, so a
# suite added later runs under the lock until shown safe.
UNLOCKED_SUITES = {"agent", "unit", "decoder", "reference"}


# Functional guards run two at a time in the alone phase, each class in one tree only, so its
# baseline runs beside one other build and never twice. Earned by measurement: one guard of each
# functional class, its class baseline and mutated run beside another class's, three rounds: every
# baseline passed, and every guard fired its named test. Configuration guards still run one at a
# time after them.
ALONE_SLOTS = 2


def _alone_bins(exclusive, slots):
    """The alone phase: one group per functional class, most guards first, each handed whole to
    whichever tree is free -- a fixed split by guard count left one tree idle for minutes -- and
    the guards that run one at a time after them. One slot pairs nothing."""
    by_class, serial = {}, []
    for index in exclusive:
        suite, expect = MUTATIONS[index][4], MUTATIONS[index][5]
        if suite is functional_suite and slots > 1:
            by_class.setdefault(_class_of(suite, expect), []).append(index)
        else:
            serial.append(index)
    return sorted(by_class.values(), key=len, reverse=True), serial


def _init_alone(slots):
    _enter(slots.get())
    _WORKER["baselined"] = {}
    _WORKER["gradle_lock"] = contextlib.nullcontext()


def _prove_bin(indices):
    return os.getcwd(), [_prove_in_worker(index) for index in indices]


def _locked(name):
    """Whether a suite's runs take the Gradle lock: every Gradle suite not named safe without it."""
    return name in GRADLE_SUITES and name not in UNLOCKED_SUITES
BUILD_FILES = ("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradle/",
               "agent/build.gradle.kts", "plugin/build.gradle.kts", "core/build.gradle.kts", "maven/build.gradle.kts")


def _under(path, prefixes):
    return any(path == p or (p.endswith("/") and path.startswith(p)) for p in prefixes)


def changed_files(base):
    """Every path that differs from `base` in this tree, untracked files included."""
    names = set()
    for command in (["git", "-C", yoriwake_checkout.root(), "diff", "--name-only", base],
                    ["git", "-C", yoriwake_checkout.root(), "ls-files", "--others",
                     "--exclude-standard"]):
        done = subprocess.run(command, capture_output=True, text=True, check=True)
        names |= {line for line in done.stdout.splitlines() if line}
    return names


def guards_at(base):
    """The mutation table as `base` had it, keyed by label, to find guards that are new or edited.

    Also carries, under the key None, the names of suites whose RUNNER changed since `base`: a
    different `--tests` list is a different proof for every guard that suite runs.
    """
    namespace = _harness_at(base)
    if namespace is None:
        return {}
    table = _table(namespace["MUTATIONS"])
    then = {fn.__name__: _code_of(fn) for _n, fn, _c in namespace["SUITES"]}
    table[None] = {name for name, fn, _c in SUITES if then.get(fn.__name__) != _code_of(fn)}
    return table


def _harness_at(base):
    """This script as `base` had it, executed, or None when `base` has no such file."""
    done = subprocess.run(["git", "-C", yoriwake_checkout.root(), "show",
                           f"{base}:scripts/prove_guards.py"], capture_output=True, text=True)
    if done.returncode != 0:
        return None
    namespace = {"__name__": "prove_guards_at_base", "__file__": __file__}
    exec(compile(done.stdout, f"prove_guards.py@{base}", "exec"), namespace)
    return namespace


def _table(mutations):
    return {m[0]: (m[1], m[2], m[3], m[4].__name__, m[5]) for m in mutations}


# What a guard's entry fixes, by position in `_table`'s value. Its replacement text is left out: a
# replacement that no longer breaks anything is caught by the guard not firing.
GUARD_FIELDS = (("target", 0), ("anchor", 1), ("suite", 3), ("expected test", 4))


def weakened(then, then_fast, mutations, fast):
    """Each guard `then` had that `mutations` removes or re-aims, and each suite `fast` no longer
    runs: one line apiece, empty when the change lowers no bar. A new guard lowers none."""
    now = _table(mutations)
    lines = []
    for label, entry in sorted((k, v) for k, v in then.items() if k is not None):
        if label not in now:
            lines.append(f"{label}: removed")
            continue
        lines.extend(f"{label}: its {field} changed" for field, at in GUARD_FIELDS
                     if entry[at] != now[label][at])
    lines.extend(f"the {name} suite left the fast set" for name in sorted(set(then_fast) - set(fast)))
    return lines


def _code_of(fn):
    return (fn.__code__.co_code, fn.__code__.co_consts)


def why_affected(mutation, changed, before):
    """The reason a change touches this guard, or None. Never selects by guess."""
    label, path, old, new, suite, expect = mutation
    name = SUITE_NAMES[suite]
    if path in changed:
        return "its target changed"
    if any(_under(c, SUITE_TESTS.get(name, ())) for c in changed):
        return "the tests that prove it changed"
    if name in GRADLE_SUITES and any(_under(c, BUILD_FILES) for c in changed):
        return "a build file changed"
    if name in (before.get(None) or ()):
        return "the suite that proves it was edited"
    if before.get(label) != (path, old, new, suite.__name__, expect):
        return "the guard is new or edited"
    return None


# ------------------------------------------------------------------ parallel workers
#
# EACH WORKER OWNS A GIT WORKTREE of this repository, synced to its working tree (tracked edits,
# deletions and untracked files), and mutates ONLY that. The worker runs in its tree with
# `YORIWAKE_CHECKOUT` naming it, so the harness under test builds and reads the worker's tree and
# never the real one. The real tree is not written -- its `.git` gains only worktree bookkeeping --
# so an edit made there during a run is safe, unlike with `--jobs 1`. Worktrees are kept between
# runs under sandbox/prove-guards/, so their Gradle build directories stay warm; `git clean` resets
# them without touching what is ignored.

WORKER_ROOT = os.path.join("sandbox", "prove-guards")
_WORKER = {}


def _sync_worktree(repo, path):
    """A worktree of `repo` at `path`, at its HEAD plus its uncommitted edits. `repo` is not written."""
    def git(*args):
        return subprocess.run(["git", "-C", repo, *args], capture_output=True, text=True,
                              check=True).stdout
    head = git("rev-parse", "HEAD").strip()
    # Against HEAD rather than the index, so a staged edit or a `git rm` reaches the worker too.
    edited = (git("diff", "--name-only", "--no-renames", "--diff-filter=d", "HEAD").splitlines()
              + git("ls-files", "--others", "--exclude-standard").splitlines())
    deleted = set(git("diff", "--name-only", "--no-renames", "--diff-filter=D", "HEAD").splitlines())
    if not os.path.exists(os.path.join(path, ".git")):
        subprocess.run(["git", "-C", repo, "worktree", "add", "--detach", "--force", path, head],
                       check=True, capture_output=True)
    else:
        subprocess.run(["git", "-C", path, "checkout", "--quiet", "--detach", "--force", head],
                       check=True, capture_output=True)
        subprocess.run(["git", "-C", path, "clean", "-fdq"], check=True)
    for name in edited:
        if name in deleted or not os.path.isfile(os.path.join(repo, name)):
            continue
        target = os.path.join(path, name)
        os.makedirs(os.path.dirname(target) or ".", exist_ok=True)
        shutil.copyfile(os.path.join(repo, name), target)
    for name in deleted:
        if os.path.isfile(os.path.join(path, name)):
            os.remove(os.path.join(path, name))
    # Committed on the worker's own detached HEAD, so the worker is a clean tree: the harness's
    # checks refuse to stamp one with uncommitted changes, and would fail unmutated in it.
    if edited or deleted:
        subprocess.run(["git", "-C", path, "add", "-A"], check=True, capture_output=True)
        subprocess.run(["git", "-C", path, "-c", "user.email=guards@yoriwake", "-c",
                        "user.name=guards", "-c", "commit.gpgsign=false", "commit", "--quiet",
                        "--no-verify", "-m", "the working state the guards run against"],
                       check=True, capture_output=True)


def prepare_workers(count):
    """`count` worker trees, each synced to the real tree's working state."""
    real = yoriwake_checkout.root()
    trees = []
    for index in range(count):
        tree = os.path.join(real, WORKER_ROOT, f"worker-{index}")
        _sync_worktree(real, tree)
        trees.append(tree)
    return trees


# What each Gradle suite compiles before it can run. A cold tree compiles all of it on its first
# baseline, and four cold trees compiling Kotlin at once ran the compiler out of memory: a worker's
# baselines then failed unmutated and every guard it held went unproved. So each tree compiles
# these first, one tree at a time, and the parallel phase only recompiles what a mutation touched.
WARM_UP = {
    "unit": ":plugin:testClasses", "decoder": ":plugin:testClasses", "reference": ":plugin:testClasses",
    "agent": ":agent:testClasses", "jdk identity": ":agent:testClasses",
    "functional": ":plugin:functionalTestClasses",
    "configuration guards": ":plugin:functionalTestClasses",
    "break": ":plugin:functionalTestClasses",
    "maven": ":maven:functionalTestClasses",
}


def warm_up_tasks(selected):
    """The compile tasks the selected guards' suites need, in a stable order."""
    return sorted({WARM_UP[SUITE_NAMES[m[4]]] for m in selected if SUITE_NAMES[m[4]] in WARM_UP})


def compile_errors(output, limit=10):
    """The compiler's own error lines in a failed Gradle build's output, else what Gradle says went
    wrong. Gradle's last line alone ("BUILD FAILED in 1m 3s") once left a red nightly unexplained."""
    lines = output.splitlines()
    errors = [line.strip() for line in lines
              if line.startswith("e: ") or ": error: " in line]
    if not errors:
        for i, line in enumerate(lines):
            if line.strip() == "* What went wrong:":
                errors = [l.strip() for l in lines[i + 1:i + 4] if l.strip()]
                break
    return errors[:limit]


def _warm_up(trees, tasks):
    """Compiles `tasks` in each tree, one tree at a time. Returns a refusal, with the compiler's
    error lines below it, or None."""
    for tree in trees:
        done = subprocess.run(["./gradlew", "-q", *GRADLE_ARGS, *tasks], capture_output=True, text=True, cwd=tree)
        if done.returncode != 0:
            output = (done.stderr or "") + "\n" + (done.stdout or "")
            tail = (done.stderr or done.stdout).strip().splitlines()
            refusal = f"{tree} does not compile unmutated: {tail[-1] if tail else '?'}"
            return "\n".join([refusal] + ["    " + line for line in compile_errors(output)])
    return None


def _enter(tree):
    """Makes this process work in a worker's tree: as its cwd, and by setting."""
    os.chdir(tree)
    os.environ[yoriwake_checkout.SETTING] = tree


def _init_worker(slots, gradle_lock):
    _enter(slots.get())
    _WORKER["baselined"] = {}
    _WORKER["gradle_lock"] = gradle_lock


def _run_suite(suite, test_class=None):
    """A suite in this worker's tree -- one GRADLE suite at a time across every worker.

    Measured: four functional suites at once failed unmutated in every worker. TestKit
    keeps its daemons and configuration cache under the machine's shared temp directory, so
    concurrent TestKit builds read each other's state, and the other Gradle suites failed beside
    them under the same load. Python suites share nothing and still run in parallel.
    """
    if _locked(SUITE_NAMES[suite]):
        with _WORKER["gradle_lock"]:
            return _suite_run(suite, test_class)
    return _suite_run(suite, test_class)


def _prove_in_worker(index):
    """One guard, in this worker's own tree: its suite unmutated once, then mutated."""
    label, path, old, new, suite, expect = MUTATIONS[index]
    name = SUITE_NAMES[suite]
    test_class = _class_of(suite, expect)
    key = (name, test_class)
    if key not in _WORKER["baselined"]:
        _WORKER["baselined"][key] = _run_suite(suite, test_class)
    standing = _WORKER["baselined"][key]
    if standing:
        scope = f" ({test_class})" if test_class else ""
        return index, None, (f"the {name} suite{scope} fails unmutated in {os.getcwd()}: "
                             f"{standing[0]}")
    target = source(path)
    original = read_source(target)
    write_source(target, original.replace(old, new, 1))
    try:
        return index, _run_suite(suite, test_class), None
    finally:
        write_source(target, original)


def _run_parallel(args, selected, skipped, reasons):
    """The guard loop across worker trees. Same verdicts, same closing sentence, real tree untouched."""
    import concurrent.futures
    import multiprocessing

    slots_needed = min(args.jobs, len(selected))
    print(f"preparing {slots_needed} worker tree(s) under {WORKER_ROOT}/", flush=True)
    paths = prepare_workers(slots_needed)
    tasks = warm_up_tasks(selected)
    if tasks:
        print(f"compiling {' '.join(tasks)} in each worker tree, one at a time", flush=True)
        refusal = _warm_up(paths, tasks)
        if refusal:
            _refuse(refusal)
            return 1
    context = multiprocessing.get_context("spawn")
    manager = context.Manager()
    slots = manager.Queue()
    for path in paths:
        slots.put(path)
    gradle_lock = manager.Lock()
    # One Gradle guard, then enough others to fill the remaining workers, repeated: Gradle suites
    # run one at a time, so queueing them together would leave every other worker waiting on the
    # lock. Costliest first within each kind, so the long ones are not left to run alone at the end.
    cost = {fn: c for _n, fn, c in SUITES}
    wanted = {m[0] for m in selected}
    chosen = sorted((i for i in range(len(MUTATIONS)) if MUTATIONS[i][0] in wanted),
                    key=lambda i: -cost[MUTATIONS[i][4]])
    exclusive = [i for i in chosen if SUITE_NAMES[MUTATIONS[i][4]] in EXCLUSIVE_SUITES]
    chosen = [i for i in chosen if i not in exclusive]
    gradle = [i for i in chosen if _locked(SUITE_NAMES[MUTATIONS[i][4]])]
    others = [i for i in chosen if not _locked(SUITE_NAMES[MUTATIONS[i][4]])]
    indices = []
    while gradle or others:
        if gradle:
            indices.append(gradle.pop(0))
        indices.extend(others[:max(1, slots_needed - 1)])
        del others[:max(1, slots_needed - 1)]
    failures = 0
    results = []
    if indices:
        with concurrent.futures.ProcessPoolExecutor(max_workers=slots_needed, mp_context=context,
                                                    initializer=_init_worker,
                                                    initargs=(slots, gradle_lock)) as pool:
            for future in concurrent.futures.as_completed([pool.submit(_prove_in_worker, i)
                                                           for i in indices]):
                results.append(future.result())
                failures += _report(*results[-1])
    slots = min(ALONE_SLOTS, len(paths))
    bins, serial = _alone_bins(exclusive, slots)
    if len(bins) > 1:
        print(f"\n{sum(map(len, bins))} functional guard(s) in {len(bins)} classes, {slots} at a time, "
              f"each class whole to whichever of {', '.join(paths[:slots])} is free:",
              flush=True)
        alone = manager.Queue()
        for path in paths[:slots]:
            alone.put(path)
        phase = time.monotonic()
        last = {}
        with concurrent.futures.ProcessPoolExecutor(max_workers=slots, mp_context=context,
                                                    initializer=_init_alone, initargs=(alone,)) as pool:
            for future in concurrent.futures.as_completed([pool.submit(_prove_bin, b) for b in bins]):
                tree, results = future.result()
                for result in results:
                    failures += _report(*result)
                last[tree] = time.monotonic() - phase
                cls = _class_of(functional_suite, MUTATIONS[results[0][0]][5])
                print(f"    {cls} done in {tree} at {last[tree]:.0f}s", flush=True)
        if len(last) > 1:
            print(f"    idle tail: {max(last.values()) - min(last.values()):.0f}s", flush=True)
    else:
        serial = [i for b in bins for i in b] + serial
    if serial:
        print(f"\n{len(serial)} guard(s) whose suite runs alone, in {paths[0]}:", flush=True)
        here, setting = os.getcwd(), os.environ.get(yoriwake_checkout.SETTING)
        _enter(paths[0])
        _WORKER.update(baselined={}, gradle_lock=contextlib.nullcontext())
        try:
            for index in serial:
                failures += _report(*_prove_in_worker(index))
        finally:
            os.chdir(here)
            if setting is None:
                os.environ.pop(yoriwake_checkout.SETTING, None)
            else:
                os.environ[yoriwake_checkout.SETTING] = setting
    print()
    if failures:
        print(f"{failures} guard(s) did not fire. A guard that cannot fail is not a guard.")
        return 1
    return _closing(args, selected, skipped)


def _not_proof(line):
    """A suite result that says the suite broke, not that any check in it fired."""
    return line.startswith("NOT THE GUARD") or line.endswith("failed without naming a test")


# What this run decided, for `--results`: each guard's outcome by label, and the first refusal that
# stopped it, so a guard the run never reached is written as not proved, with the reason.
_RUN = {"outcomes": {}, "refused": None, "selection": 0}


def _refuse(message):
    """Prints a refusal that stops the run, and keeps its first line for `--results`."""
    print(f"REFUSING: {message}")
    _RUN["refused"] = _RUN["refused"] or message.splitlines()[0]


def _report(index, fired, refusal):
    """Prints one guard's verdict. Returns 1 when it does not count as proved."""
    label, _path, _old, _new, _suite, expect = MUTATIONS[index]
    outcomes = _RUN["outcomes"]
    if refusal:
        print(f"{label:62} -> NOT PROVED: {refusal}", flush=True)
        outcomes[label] = ("not proved", refusal)
        return 1
    # A CRASH IS NOT A GUARD FIRING, with or without an `expect`. Only the expect check used to see
    # this, so a guard with none was proved by a mutation that merely stopped its suite running.
    if fired and all(_not_proof(line) for line in fired):
        print(f"{label:62} -> NOT PROVED: {fired[0][:64]}", flush=True)
        outcomes[label] = ("not proved", fired[0])
        return 1
    if fired:
        print(f"{label:62} -> {len(fired)} fired: {fired[0][:64]}", flush=True)
        if expect and not any(expect in line for line in fired):
            print(f"{'':62}    *** but not `{expect}` -- this proves a different test ***")
            outcomes[label] = ("wrong test", fired[0])
            return 1
        outcomes[label] = ("fired", fired[0])
        return 0
    print(f"{label:62} -> *** NOTHING CAUGHT IT ***", flush=True)
    outcomes[label] = ("nothing caught", None)
    return 1


def _place(units, count, shards):
    """Each (weight, name, guards) unit, heaviest first, to the shard with the least weight so far,
    then the fewest units, then the lowest index; returns each shard's weight."""
    load, held = [0.0] * count, [0] * count
    for weight, _name, guards in sorted(units, key=lambda unit: (-unit[0], unit[1])):
        lightest = min(range(count), key=lambda i: (load[i], held[i], i))
        load[lightest] += weight
        held[lightest] += 1
        shards[lightest].extend(guards)
    return load


def class_seconds(name):
    """One run of a functional class on CI: measured, the median of those measured for a class not
    yet measured, and the whole suite for a guard whose class does not resolve."""
    known = sorted(FUNCTIONAL_CLASS_SECONDS.values())
    if name is None:
        return sum(known)
    return FUNCTIONAL_CLASS_SECONDS.get(name, known[len(known) // 2])


def partition(mutations, count):
    """`mutations` in `count` shards balanced by cost, each in `mutations`' order, and each shard's
    projected (functional, other) seconds. Computed from the selection, the weights above and the
    checkout only, so every machine splits a commit's selection alike.

    A functional class's guards share one tree's run and its one baseline, so they stay together
    unless the class outweighs one tree's share of the functional phase; then it splits into
    near-equal chunks. Functional chunks weigh CI seconds and the other guards their suite's
    registry seconds; the two scales are balanced apart, never added.
    """
    by_class, other = {}, []
    for m in mutations:
        if m[4] is functional_suite:
            by_class.setdefault(_class_of(m[4], m[5]), []).append(m)
        else:
            other.append(m)
    rate = class_seconds
    weights = {name: (len(guards) + 1) * rate(name) for name, guards in by_class.items()}
    share = sum(weights.values()) / (count * ALONE_SLOTS) if weights else 0
    units = []
    for name, guards in by_class.items():
        pieces = 1 if weights[name] <= share else min(len(guards), math.ceil(weights[name] / share))
        for piece in range(pieces):
            chunk = guards[piece * len(guards) // pieces:(piece + 1) * len(guards) // pieces]
            units.append(((len(chunk) + 1) * rate(name), name or "", chunk))
    shards = [[] for _ in range(count)]
    functional = _place(units, count, shards)
    cost = {fn: c for _n, fn, c in SUITES}
    rest = _place([(cost.get(m[4], 0), m[0], [m]) for m in other], count, shards)
    order = {m[0]: i for i, m in enumerate(mutations)}
    return [sorted(shard, key=lambda m: order[m[0]]) for shard in shards], list(zip(functional, rest))


def in_shard(mutations, shard):
    """The guards of `mutations` that `shard`, an (index, count) pair, runs, in their order."""
    index, count = shard
    return partition(mutations, count)[0][index - 1]


def shard_projections(mutations, count):
    """One line per shard: its guards and projected weights, so the balance reads before a dispatch."""
    shards, loads = partition(mutations, count)
    return [f"shard {i + 1}/{count}: {len(shard)} guard(s), functional ~{loads[i][0]:.0f}s on CI, "
            f"other ~{loads[i][1]:.0f}s by the registry" for i, shard in enumerate(shards)]


def parse_shard(text):
    """`I/N` as (I, N), or None unless 1 <= I <= N."""
    match = re.fullmatch(r"(\d+)/(\d+)", text)
    if not match:
        return None
    index, count = int(match.group(1)), int(match.group(2))
    return (index, count) if 1 <= index <= count else None


def _write_json(path, payload):
    with open(path, "w", encoding=ENCODING) as handle:
        json.dump(payload, handle, indent=1)
        handle.write("\n")


def _selection_header(args, selection):
    """What a selection was made by, so a check can tell a shard that selected otherwise."""
    return {"shard": args.shard, "mode": args.mode, "changed": args.changed, "guard": args.guard,
            "selected": len(selection)}


def write_results(path, args, selection, ran):
    """`--results`: each guard this run held, with its outcome. A guard the run stopped before is
    not proved, with the refusal that stopped it."""
    stopped = _RUN["refused"] or "the run ended before it was proved"
    guards = []
    for mutation in ran:
        outcome, detail = _RUN["outcomes"].get(mutation[0], ("not proved", stopped))
        guards.append({"label": mutation[0], "suite": SUITE_NAMES.get(mutation[4]),
                       "outcome": outcome, "detail": detail})
    _write_json(path, {**_selection_header(args, selection), "refused": _RUN["refused"],
                       "guards": guards})


def _shard_line(args, selected):
    """A shard's closing line: the rest of its run's selection is the other shards' to prove."""
    if args.shard:
        print(f"shard {args.shard}: the other {_RUN['selection'] - len(selected)} guard(s) this "
              f"run selected are the other shards' to prove.")


def _closing(args, selected, skipped):
    """The closing sentence of a parallel run that proved everything it selected."""
    print(f"every guard this run selected fired ({len(selected)} of {len(MUTATIONS)}), each in a "
          f"worker tree, with this tree never written.")
    if skipped:
        if args.changed:
            print(f"{len(skipped)} guard(s) NOT attempted: no change since {args.changed} touches "
                  f"their target, their tests, a build file or the guard. Run without --changed "
                  f"before pushing a batch.")
        else:
            unproved = {}
            for mutation in skipped:
                unproved[SUITE_NAMES[mutation[4]]] = unproved.get(SUITE_NAMES[mutation[4]], 0) + 1
            named = ", ".join(f"{name} {count}" for name, count in sorted(unproved.items()))
            print(f"{len(skipped)} guard(s) NOT attempted ({named}). Run --mode full before "
                  f"committing to plugin/ or agent/.")
    _shard_line(args, selected)
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--mode", choices=("fast", "full"), default="full",
                        help="fast: the cheap suites only. full (default): every guard.")
    parser.add_argument("--changed", nargs="?", const="HEAD", default=None, metavar="BASE",
                        help="only the guards a change since BASE (default HEAD, so the working "
                             "tree) can affect: their target, the tests that prove them, a build "
                             "file, or the guard itself changed")
    parser.add_argument("--guard", action="append", default=[], metavar="LABEL",
                        help="only the guard with this label (repeatable): a task proves the guards "
                             "it adds or re-anchors this way; the full run waits for the release "
                             "check")
    parser.add_argument("--jobs", type=int, default=4, metavar="N",
                        help="guards run in parallel, each worker in its own git worktree under "
                             "sandbox/prove-guards/ (default 4; 1 runs in this tree, one at a "
                             "time)")
    parser.add_argument("--against", metavar="BASE",
                        help="run nothing; name each guard removed or re-aimed since BASE, and each "
                             "suite taken out of the fast set, and exit 1 if there is any")
    parser.add_argument("--shard", metavar="I/N",
                        help="only shard I of N of the selected guards, split by measured cost with "
                             "a functional class's guards together, as CI runs them; with the same "
                             "flags, reproduces one CI shard")
    parser.add_argument("--results", metavar="FILE",
                        help="write each guard this run held and its outcome to FILE as JSON, on "
                             "every exit")
    parser.add_argument("--list-selection", metavar="FILE",
                        help="run nothing; write the guards these flags select, before any shard, "
                             "to FILE as JSON")
    args = parser.parse_args(argv)
    _RUN.update(outcomes={}, refused=None, selection=0)
    held = {"selection": [], "ran": []}
    try:
        return _main(args, held)
    finally:
        if args.results and not args.against:
            write_results(args.results, args, held["selection"], held["ran"])


def _main(args, held):
    """`main` after its flags: fills `held` with the selection and the guards this run proves."""
    shard = None
    if args.shard is not None:
        shard = parse_shard(args.shard)
        if shard is None:
            _refuse(f"--shard takes I/N with 1 <= I <= N, not {args.shard!r}.")
            return 1

    if args.against:
        namespace = _harness_at(args.against)
        if namespace is None:
            print(f"{args.against} has no scripts/prove_guards.py, so no guard can have been weakened.")
            return 0
        lines = weakened(_table(namespace["MUTATIONS"]), namespace["FAST_SUITES"], MUTATIONS,
                         FAST_SUITES)
        for line in lines:
            print(f"WEAKENED  {line}")
        print(f"{len(lines)} guard change(s) since {args.against} lower the bar a change is held to."
              if lines else f"no guard removed or re-aimed since {args.against}.")
        return 1 if lines else 0

    # THE MUTATION TABLE IS THE POPULATION, and the registry only says what each suite costs and
    # when to baseline it. Selecting from the registry instead looked equivalent and is not: a suite
    # missing from it would drop its mutations from BOTH modes, and full mode would report "every
    # guard fired" over a population quietly smaller than the table. So a mutation whose suite the
    # registry does not know is a refusal, not an omission -- staged and confirmed, because the
    # first version of this split had exactly that hole.
    unregistered = sorted({m[0] for m in MUTATIONS if m[4] not in SUITE_NAMES})
    if unregistered:
        _refuse(f"{len(unregistered)} mutation(s) name a suite that is in no registry entry, "
                f"so their cost is unknown and nothing baselines them: {unregistered[0]}")
        return 1

    registered = {name for name, _fn, _cost in SUITES}
    if FAST_SUITES - registered:
        _refuse(f"the fast set names {sorted(FAST_SUITES - registered)}, which no registry "
                f"entry defines, so that suite would be silently absent from fast mode.")
        return 1

    active = {name for name in registered if args.mode == "full" or name in FAST_SUITES}
    selected = [m for m in MUTATIONS if SUITE_NAMES[m[4]] in active]
    reasons = {}
    if args.guard:
        unknown = sorted(set(args.guard) - {m[0] for m in MUTATIONS})
        if unknown:
            _refuse(f"no guard is labelled {unknown[0]!r}.")
            return 1
        selected = [m for m in MUTATIONS if m[0] in args.guard]
    if args.changed:
        changed, before = changed_files(args.changed), guards_at(args.changed)
        reasons = {m[0]: why_affected(m, changed, before) for m in selected}
        selected = [m for m in selected if reasons[m[0]]]
    held["selection"] = selected
    _RUN["selection"] = len(selected)
    if args.list_selection:
        _write_json(args.list_selection, {**_selection_header(args, selected), "guards": [
            {"label": m[0], "suite": SUITE_NAMES[m[4]]} for m in selected]})
        print(f"{len(selected)} of {len(MUTATIONS)} guards selected, written to "
              f"{args.list_selection}; none was run.")
        if shard:
            for line in shard_projections(selected, shard[1]):
                print(line)
        return 0
    if args.changed and not selected:
        print(f"nothing since {args.changed} can affect any guard ({len(changed)} changed "
              f"file(s)); no guard was run, and none is claimed.")
        return 0
    skipped = [m for m in MUTATIONS if m not in selected]
    if not selected:
        # A run that proves nothing must not print the sentence a run that proved everything prints.
        _refuse(f"{args.mode} mode selected 0 of {len(MUTATIONS)} mutations.")
        return 1
    # AFTER SELECTION, so a shard is a slice of exactly what the unsharded run would prove, and
    # every step below sees only the shard's guards.
    if shard:
        whole, selected = selected, in_shard(selected, shard)
        if not selected:
            print(f"shard {args.shard} holds none of the {len(whole)} guard(s) this run selected; "
                  f"no guard was run here, and none is claimed.")
            shards, _loads = partition(whole, shard[1])
            for i, held_there in enumerate(shards):
                for m in held_there:
                    print(f"  {m[0]} is shard {i + 1}'s")
            return 0
        projection = shard_projections(whole, shard[1])[shard[0] - 1]
    held["ran"] = selected

    # STRUCTURALLY. The baseline set is DERIVED from the mutations about to run rather than
    # declared beside them, so a mode cannot run a suite it did not verify unmutated. A declared
    # list once fell five mutations behind the mutation table; a derivation cannot fall behind.
    # The baseline set is DERIVED from the mutations about to run, so a mode
    # cannot execute a suite it did not verify unmutated. A refusal used to sit here as well, and it
    # could not fire -- it computed `running` minus a filter of `running`, which is empty by
    # construction. The check that does the work is the registry check above: a suite the registry
    # does not know refuses before any suite runs.
    running = {SUITE_NAMES[m[4]] for m in selected}
    needed = [(name, fn) for name, fn, _cost in SUITES if name in running]

    # ANCHORS FIRST, BEFORE A SINGLE SUITE RUNS. Every mutation is pinned to literal source text, so
    # an ordinary refactor moves one and the mutation stops applying. That was already reported --
    # as one line in a report twenty minutes long, arriving after the run it invalidated, and
    # noticed only by whoever read to the end. Checking every anchor is a handful of file reads, so
    # it happens at second zero and stops the run.
    # A PATH THAT RESOLVES TO NO FILE is refused first, naming where it was looked for: a moved or
    # renamed source fails here.
    absent = [(label, source(path)) for label, path, _old, _new, _suite, _expect in selected
              if not os.path.isfile(source(path))]
    if absent:
        _refuse(f"{len(absent)} mutation target(s) do not exist, so the guard cannot apply:\n"
                + "\n".join(f"  {label}  ({path})" for label, path in absent))
        return 1

    moved = [(label, path) for label, path, old, _new, _suite, _expect in selected
             if old not in read_source(source(path))]
    if moved:
        _refuse(f"{len(moved)} anchor(s) no longer appear in their source. The mutation "
                f"cannot apply, so the guard it aims at would be reported unproved after a full run:\n"
                + "\n".join(f"  {label}  ({path})" for label, path in moved))
        return 1

    # AND AN ANCHOR THAT APPEARS MORE THAN ONCE IS NOT AN ANCHOR. `replace(old, new, 1)` takes the
    # FIRST match, so a guard whose text occurs several times mutates whichever happens to sort
    # first -- and then proves whatever that line happens to break, under the name of the line it
    # meant. Two guards here anchored on a regex fragment shared by three patterns in
    # `mutations.py` and hit the Java one only by position; both fired, neither was shown to prove
    # its own rule. Checking presence and not uniqueness is what let that stand.
    #
    # Refused rather than disambiguated automatically: which of several matches a guard MEANT is a
    # question only its author can answer, and guessing here would reproduce the defect one layer up.
    ambiguous = [(label, path, read_source(source(path)).count(old))
                 for label, path, old, _new, _suite, _expect in selected
                 if read_source(source(path)).count(old) > 1]
    if ambiguous:
        _refuse(f"{len(ambiguous)} anchor(s) appear more than once in their source, so the "
                f"mutation lands on whichever match sorts first and the guard proves a line it does "
                f"not name:\n"
                + "\n".join(f"  {label}  ({path}, {count} matches)" for label, path, count in ambiguous))
        return 1

    print(f"mode={args.mode}{f' changed since {args.changed}' if args.changed else ''}: "
          f"{len(selected)} of {len(MUTATIONS)} guards, {len(needed)} of {len(SUITES)} suites"
          f"{f', {args.jobs} workers' if args.jobs > 1 else ''}"
          f"{f', shard {args.shard} of the {len(whole)} selected' if shard else ''}")
    if shard:
        print(projection)
    if args.jobs > 1:
        return _run_parallel(args, selected, skipped, reasons)
    # THE BASELINE, FIRST. Every line below reads "the guard fired" from a failing suite, so a suite
    # that was already failing would report every mutation as caught while catching nothing.
    print("baseline, unmutated:")
    # A functional guard that runs one class is baselined on that class, just before its first
    # mutation; the whole suite here only if some guard falls back to it.
    narrowed = {_class_of(m[4], m[5]) for m in selected if m[4] is functional_suite}
    for name, suite in needed:
        if suite is functional_suite and None not in narrowed:
            continue
        standing = suite()
        print(f"  {name:22} -> {len(standing)} failing"
              + ("" if not standing else f"  <-- MUST BE 0: {standing[0]}"))
        if standing:
            print("\nThe suite fails before any mutation, so nothing below can be believed.")
            _RUN["refused"] = f"the {name} suite fails unmutated: {standing[0]}"
            return 1
    print()

    failures = 0
    baselined = set()
    for mutation in selected:
        label, relative, old, new, suite, expect = mutation
        test_class = _class_of(suite, expect)
        if test_class and test_class not in baselined:
            standing = suite(test_class)
            print(f"  baseline {test_class:44} -> {len(standing)} failing"
                  + ("" if not standing else f"  <-- MUST BE 0: {standing[0]}"))
            if standing:
                print("\nThe class fails before any mutation, so its guards cannot be believed.")
                _RUN["refused"] = f"{test_class} fails unmutated: {standing[0]}"
                return 1
            baselined.add(test_class)
        path = source(relative)
        original = read_source(path)
        digest = hashlib.sha256(original.encode(ENCODING)).hexdigest()
        if old not in original:
            # Unreachable via the pre-flight check above, which refuses before any suite runs. Kept
            # for the one case that check cannot cover: a source edited while this is running, which
            # is how a mutation once survived into a working tree. It says so rather than being
            # counted among the guards this script has shown to fail.
            print(f"{label:62} -> MUTATION DID NOT APPLY; the source changed mid-run")
            _RUN["outcomes"][label] = ("not proved", "the source changed mid-run")
            failures += 1
            continue
        # ARMED BEFORE THE WRITE, because a `finally` is exactly what a kill skips. An
        # interrupted run used to leave its mutation in the tree, where `git status` reports it as
        # an ordinary edit and nothing says a machine wrote it -- two survived that way and were
        # caught only because a later pre-flight refused. `run.py` carries the same
        # atexit-plus-signal restore for its checkouts, for the same reason.
        undo = _restore_on_signal(path, original)
        write_source(path, original.replace(old, new, 1))
        try:
            fired = _suite_run(suite, test_class)
        finally:
            write_source(path, original)
            undo()
            assert hashlib.sha256(read_source(path).encode(ENCODING)).hexdigest() == digest, \
                f"restore failed: {path}"
        failures += _report(MUTATIONS.index(mutation), fired, None)

    print()
    if failures:
        print(f"{failures} guard(s) did not fire. A guard that cannot fail is not a guard.")
        return 1
    # WHAT WAS NOT ATTEMPTED, every time, in the same sentence as what passed. A fast run that
    # printed the full run's closing line would be the vacuous pass this script exists to catch,
    # wearing the words of the script that catches it.
    print(f"every guard this mode runs fired ({len(selected)} of {len(MUTATIONS)}), and every "
          f"source restored byte-identical.")
    if skipped:
        unproved = {}
        for mutation in skipped:
            unproved[SUITE_NAMES[mutation[4]]] = unproved.get(SUITE_NAMES[mutation[4]], 0) + 1
        named = ", ".join(f"{name} {count}" for name, count in sorted(unproved.items()))
        print(f"{len(skipped)} guard(s) NOT attempted ({named}). Run --mode full before committing "
              f"to plugin/ or agent/.")
    _shard_line(args, selected)
    return 0


if __name__ == "__main__":
    sys.exit(main())
