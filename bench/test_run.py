"""
Tests for target ordering: which mutation a sweep spends its budget on.

Not a test that the ordering sorts. It is that a mutation which cannot fail anything does not
consume a small --limit, and -- more importantly -- that an absent map is treated as *unknown*
rather than as *nothing is covered*. Both defects have shipped here. Once a kestra sweep
spent all six of its mutations on classes nothing loads and on shapes that change no behaviour,
reported zero missed failures out of zero induced ones, and exited 0.

Run with `python bench/test_run.py`, or under pytest.

Cases are named case_* and RETURN a count of failed checks, following test_mutations.py: a function
that returns instead of asserting would be collected by pytest and pass however many checks failed.
The single test_* entry point below asserts.
"""

import json
import os
import re
import shutil
import tempfile
import subprocess
import time
import sys
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import run  # noqa: E402
import target_ordering  # noqa: E402  (the subsystem these tests actually cover)
import isolation  # noqa: E402  (order_dependent, the only code allowed to discount a miss)
import sweep_data  # noqa: E402

JAVA = "m/src/main/java/"
# A build log that does not exist: a reader given it finds no listener and reads XML keys only.
_NO_LOG = os.path.join(tempfile.gettempdir(), "yoriwake-no-such-build.log")
KOTLIN = "m/src/main/kotlin/"


def fail(message):
    """One failed check, printed in the shape a guard check reads back.

    TEN CALL SITES USED THIS BEFORE IT EXISTED, all of them in the decision-record family, and every
    one of them is a FAILURE branch -- so the suite stayed green and the `NameError` was reachable
    only by a check that actually failed. `_python_checks` collects lines starting `  FAIL` and
    reports a crash as "NOT THE GUARD -- test_run.py did not run", so a guard aimed at any of these
    could not prove itself: the mutation fired, the suite died, and the report named no test. That
    is the failure mode `a-guard-keyed-to-wording-goes-green-when-the-wording-moves` records, one
    level down -- the instrument that certifies every other check needed checking first.

    Spelled exactly as in test_sweep_corpus.py, test_qualify.py and the rest, because the prefix is
    the contract between these files and the guard runner.
    """
    print(f"  FAIL: {message}")
    return 1


def case_uncovered_targets_do_not_consume_the_limit():
    """The kestra failure, reduced. A covered class must be reached before an uncovered one."""
    failures = 0
    targets = [(JAVA + "p/Unloaded.java", "body", 0), (JAVA + "p/Used.java", "body", 0)]
    ordered, provenance = target_ordering.by_coverage(targets, {"p.Used"})

    if ordered[0][0] != JAVA + "p/Used.java":
        print("  FAIL: the covered class did not come first")
        failures += 1
    if provenance[targets[0]] != "uncovered" or provenance[targets[1]] != "covered":
        print(f"  FAIL: provenance wrong: {provenance}")
        failures += 1
    # Ordering, not filtering. The uncovered class is the only thing that exercises the
    # absence-of-coverage rule, so losing it would remove the corpus's check on the riskiest rule
    # the tool has.
    if len(ordered) != 2:
        print(f"  FAIL: {2 - len(ordered)} target(s) dropped; this orders, it must not filter")
        failures += 1
    return failures


def case_no_map_means_unknown_not_uncovered():
    """Empty is not the same as unknown. With no map, nothing may be called uncovered."""
    failures = 0
    targets = [(JAVA + "p/A.java", "body", 0), (JAVA + "p/B.java", "schema", 0)]
    ordered, provenance = target_ordering.by_coverage(targets, set())

    if set(provenance.values()) != {"unknown"}:
        print(f"  FAIL: no map, but targets were classified {set(provenance.values())}")
        failures += 1
    if ordered != target_ordering.interleave_shapes(targets):
        print("  FAIL: with no map the order must be left exactly as it was")
        failures += 1
    return failures


def case_a_path_that_names_no_class_is_unknown():
    """The `aliased` shape defeated the selector once. It must not be demoted on a guess."""
    failures = 0
    targets = [("odd/place/Thing.java", "body", 0)]
    _, provenance = target_ordering.by_coverage(targets, {"p.Something"})

    if provenance[targets[0]] != "unknown":
        print(f"  FAIL: unresolvable path classified {provenance[targets[0]]}, not unknown")
        failures += 1
    if target_ordering.qualified_name("build.gradle") is not None:
        print("  FAIL: qualified_name invented a class for a path that declares none")
        failures += 1
    if target_ordering.qualified_name(JAVA + "p/q/Await.java") != "p.q.Await":
        print("  FAIL: qualified_name did not resolve an ordinary source path")
        failures += 1
    # A single-module project has no directory in front of src/, so the path starts at
    # `src/main/java/`. The first version of this code required a leading slash, resolved every such
    # target to None, and left the ordering doing nothing on the demo fixture and on rxjava.
    if target_ordering.qualified_name("src/main/java/dev/demo/Beta.java") != "dev.demo.Beta":
        print("  FAIL: a single-module path did not resolve; ordering would be a no-op there")
        failures += 1
    return failures


def case_covered_classes_reads_both_files(tmp):
    """A class can be loaded without any test's coverage naming it. Either one counts."""
    failures = 0
    os.makedirs(tmp, exist_ok=True)
    # The map's real four-column shape: outcome, durationNanos, classes, testId. This fixture used
    # to be written with three columns, which is the only reason `fields[-1]` passed this test --
    # with three columns the last field IS the classes column. Against a real map it collected test
    # IDs and nothing else, and every mutation target read as uncovered.
    with open(os.path.join(tmp, "coverage.tsv"), "w", encoding="utf-8") as handle:
        handle.write("SUCCESSFUL\t1234\tp.Executed,p.AlsoExecuted\t"
                     "[engine:junit-jupiter]/[class:p.SomeTest]/[method:works()]\n")
        # Malformed, and skipped rather than guessed at.
        handle.write("SUCCESSFUL\t1234\tp.NotEnoughFields\n")
    with open(os.path.join(tmp, "loaded.txt"), "w", encoding="utf-8") as handle:
        handle.write("p.LoadedOnly\n")

    found = target_ordering.covered_classes(tmp)
    for name in ("p.Executed", "p.AlsoExecuted", "p.LoadedOnly"):
        if name not in found:
            print(f"  FAIL: {name} missing from the covered set")
            failures += 1
    stray = [name for name in found if name.startswith("[engine:")]
    if stray:
        print(f"  FAIL: test IDs were collected as class names: {stray}")
        failures += 1
    if "p.NotEnoughFields" in found:
        print("  FAIL: a malformed line was guessed at rather than skipped")
        failures += 1
    if target_ordering.covered_classes(None) != set():
        print("  FAIL: covered_classes(None) must be empty, which callers read as unknown")
        failures += 1
    return failures


def case_shape_budget_holds_as_shapes_are_added():
    """Two thirds answerable, whatever the shape count. Round-robin only looks right at three."""
    failures = 0
    # Round-robin over three kinds happens to give 4 of 6 answerable. Add two more inert shapes and
    # it silently falls, without anything in the corpus changing to say so.
    targets = ([(JAVA + f"p/A{i}.java", "body", 0) for i in range(10)]
               + [(JAVA + f"p/S{i}.java", "schema", 0) for i in range(10)]
               + [(JAVA + f"p/T{i}.java", "introspects", 0) for i in range(10)]
               + [(JAVA + f"p/U{i}.java", "documents", 0) for i in range(10)])
    first_six = target_ordering.by_shape(targets)[:6]
    answerable = sum(1 for t in first_six if t[1] in target_ordering.ANSWERING_SHAPES)
    if answerable < 4:
        print(f"  FAIL: only {answerable} of 6 could induce a failure")
        failures += 1

    # And the opposite property, which is why this weaves rather than sorts: a shape that a small
    # limit never reaches is a guard that never fires. `schema` catches a rule narrowing on the
    # ABSENCE of coverage and must survive the budget.
    if not any(t[1] not in target_ordering.ANSWERING_SHAPES for t in first_six):
        print("  FAIL: no inert-by-design shape survived the budget; schema would be unreachable")
        failures += 1

    # Nothing may be dropped, at any ratio.
    if len(target_ordering.by_shape(targets)) != len(targets):
        print("  FAIL: by_shape dropped targets; it budgets, it does not filter")
        failures += 1
    return failures


def case_resume_deepens_a_file_instead_of_abandoning_it():
    """The defect that froze every project's evidence after one pass.

    Keyed on `path::kind`, a single measured body mutation excluded every other body target in the
    same file, in every later sweep. A project could never accumulate more recall evidence than its
    first run produced, however many times it was run.
    """
    failures = 0
    targets = [(JAVA + "p/A.java", "body", i) for i in range(4)]
    findings = [{"target": JAVA + "p/A.java::body::first", "index": 0}]

    pending = target_ordering.pending_targets(targets, findings)
    if len(pending) != 3:
        print(f"  FAIL: {len(pending)} of 3 remaining targets survived the resume filter")
        failures += 1
    if any(t[2] == 0 for t in pending):
        print("  FAIL: the measured declaration was offered again")
        failures += 1

    # Nothing is re-measured across shapes either, and a different shape in the same file is
    # untouched by a body row.
    mixed = target_ordering.pending_targets(
        [(JAVA + "p/A.java", "body", 0), (JAVA + "p/A.java", "schema", 0)], findings)
    if [t[1] for t in mixed] != ["schema"]:
        print(f"  FAIL: expected only the schema target to remain, got {mixed}")
        failures += 1
    return failures


def case_rows_without_an_index_keep_the_old_conservative_behaviour():
    """A row written before the index was recorded cannot say which declaration it measured.

    Re-measuring something already measured wastes a sweep; skipping something never measured is a
    hole in the evidence. Neither is knowable for such a row, so its own file and shape stay excluded
    exactly as before rather than being guessed at.
    """
    failures = 0
    targets = [(JAVA + "p/A.java", "body", i) for i in range(3)] + [(JAVA + "p/B.java", "body", 0)]
    legacy = [{"target": JAVA + "p/A.java::body::first"}]  # no "index"

    pending = target_ordering.pending_targets(targets, legacy)
    if [t[0] for t in pending] != [JAVA + "p/B.java"]:
        print(f"  FAIL: a legacy row should exclude its own file and shape only, got {pending}")
        failures += 1
    return failures


def case_weave_survives_an_empty_side():
    """A project with no inert shapes, or only inert ones, must still yield every target."""
    failures = 0
    if target_ordering.weave([1, 2, 3], []) != [1, 2, 3]:
        print("  FAIL: weave lost targets when there was nothing to weave against")
        failures += 1
    if target_ordering.weave([], [1, 2, 3]) != [1, 2, 3]:
        print("  FAIL: weave lost targets when every shape was inert")
        failures += 1
    if sorted(target_ordering.weave([1, 3, 5], [2, 4])) != [1, 2, 3, 4, 5]:
        print("  FAIL: weave did not preserve the full set")
        failures += 1
    return failures


def case_a_kotlin_file_class_counts_as_covered():
    """The `Kt` suffix, which the plugin has always applied and the harness did not.

    A change to `Inlined.kt` names `dev.demokt.Inlined`; the compiler puts its top-level functions
    in `dev.demokt.InlinedKt`, which is the name the map records. Matching the bare name alone read
    every Kotlin file class as uncovered and sorted it behind every covered Java one -- starving the
    inline targets of the mutation budget on exactly the projects the inline defect lives on.
    """
    failures = 0
    targets = [(KOTLIN + "dev/demokt/Unrelated.kt", "body", 0),
               (KOTLIN + "dev/demokt/Inlined.kt", "body", 0)]
    covered = {"dev.demokt.InlinedKt", "dev.demokt.Nested$Inner"}
    ordered, provenance = target_ordering.by_coverage(targets, covered)

    if provenance[targets[1]] != "covered":
        print(f"  FAIL: the Kotlin file class read as {provenance[targets[1]]}, not covered")
        failures += 1
    if ordered[0][0] != KOTLIN + "dev/demokt/Inlined.kt":
        print(f"  FAIL: the covered Kotlin target did not come first: {ordered}")
        failures += 1
    # A nested type recorded under Outer$Inner is evidence the outer file was executed.
    if not target_ordering.is_covered("dev.demokt.Nested", covered):
        print("  FAIL: a nested type in the map did not count as covering its declaring file")
        failures += 1
    return failures


def case_a_project_can_ask_for_daemon_heap():
    """`daemon_jvmargs` reaches the daemon, with or without a pinned JDK.

    rxjava's 14,221-test capture OOMed the daemon while decoding and left a map with no `scope`
    file, so nothing narrowed afterwards and every later run forced while still paying to
    instrument. The setting has to live in the project file: `restore()` reverts the checkout, so
    a `gradle.properties` edit is wiped by the next sweep that touches the project.
    """
    failures = 0
    flags = run.daemon_jvm_flags({"name": "rxjava", "daemon_jvmargs": "-Xmx2g"})
    if "-Dorg.gradle.jvmargs=-Xmx2g" not in flags:
        print(f"  FAIL: daemon_jvmargs did not reach the daemon without a pinned JDK: {flags}")
        failures += 1
    # A project that asks for neither still gets nothing, so no build changes by accident.
    if run.daemon_jvm_flags({"name": "demo"}) != []:
        print("  FAIL: a project asking for nothing got flags anyway")
        failures += 1
    return failures



def case_a_silent_build_is_killed_rather_than_waited_out(tmp):
    """Stuck and slow look identical from outside, and this project keeps paying an hour to tell.

    spring-core parked forever on a CountDownLatch in an async file-channel test. Nothing but the
    3600s timeout would have ended it, and nineteen minutes of silence had already answered the
    question. A short stall window is used here so the test costs a second rather than ten minutes.
    """
    failures = 0
    os.makedirs(tmp, exist_ok=True)
    log = os.path.join(tmp, "quiet.log")
    # Says one thing, then goes quiet for far longer than the stall window without exiting.
    code, seconds = run.run(
        [sys.executable, "-c", "import time; print('starting', flush=True); time.sleep(120)"],
        tmp, log, stall_seconds=2)

    if code != run.STALLED:
        print(f"  FAIL: a silent build returned {code}, not STALLED")
        failures += 1
    if seconds > 60:
        print(f"  FAIL: waited {seconds}s for a 2s stall window; it was not killed promptly")
        failures += 1

    # And the opposite property, which is what makes the guard safe to leave on: a build that keeps
    # talking is never killed, however long it runs.
    chatty = os.path.join(tmp, "chatty.log")
    code, _ = run.run(
        [sys.executable, "-c",
         "import time; [(print('working', flush=True), time.sleep(0.5)) for _ in range(6)]"],
        tmp, chatty, stall_seconds=2)
    if code != 0:
        print(f"  FAIL: a build that kept writing was killed anyway ({code})")
        failures += 1
    return failures


# --------------------------------------------------------------------------------------------
# order_dependent: the ONLY function in this harness allowed to clear a miss.
#
# A miss is the single finding the whole project exists to produce, so the one thing that may
# discount one has to be right in every branch. It had no test at all. Gradle is faked here
# rather than run: the question is which branches clear and which keep, not whether Gradle works.


class _FakeGradle:
    """Stands in for run.gradle, returning a scripted exit code per invocation."""

    def __init__(self, *codes):
        self.codes = list(codes)
        self.calls = []

    def __call__(self, checkout, project, task_args, log_path):
        self.calls.append(task_args)
        return (self.codes.pop(0) if self.codes else 0), 1.0


def _order_dependent(gradle, results, missed=(("com.acme.Foo", "bar"),), tmp=".", map_dir=None,
                     task="test"):
    """Drives the real function with a fake Gradle. It takes its collaborators as arguments, so
    nothing has to be monkeypatched into another module to test it.

    `task` is explicit because it must be: order_dependent takes it as a required keyword. It was a
    hardcoded `:module:test` for as long as these fakes never asserted which task they were asked
    for, which is exactly how the multiplatform case survived -- the only code allowed to CLEAR a
    miss was running a task the sweep never measured.
    """
    dependent, _ = isolation.order_dependent(
        "checkout", {"name": "p"}, "m", list(missed), tmp, "stem", map_dir,
        gradle=gradle, results_of=results,
        backup_map=run.backup_map, restore_map=run.restore_map, task=task,
        keys=isolation.XML_KEYS)
    return dependent


def _nondeterministic(gradle, results, missed=(("com.acme.Foo", "bar"),), task="test"):
    """The other half of the same return, for the cases that are about reclassification."""
    _, unrepeatable = isolation.order_dependent(
        "checkout", {"name": "p"}, "m", list(missed), ".", "stem", None,
        gradle=gradle, results_of=results,
        backup_map=run.backup_map, restore_map=run.restore_map, task=task,
        keys=isolation.XML_KEYS)
    return unrepeatable


def _fails_without_the_change(gradle, results, missed=(("com.acme.Foo", "bar"),), task="test"):
    """Drives the control arm the same way, with the same fakes."""
    return isolation.fails_without_the_change(
        "checkout", {"name": "p"}, "m", list(missed), ".", "stem", None,
        gradle=gradle, results_of=results,
        backup_map=run.backup_map, restore_map=run.restore_map, task=task,
        keys=isolation.XML_KEYS)


def case_a_row_that_skipped_nothing_cannot_report_a_miss():
    """kafka-core: a forced row reported `selected 3368 of 3367` and named a miss.

    The arms are two runs of a real build and discovery is not identical between them, so a
    container that starts in one and not the other lands in one population only. Where selection
    excluded nothing, that difference is not evidence of a skip.
    """
    failures = 0
    m = [("com.acme.Foo", "bar")]
    for sel, full, want, what in ((3368, 3367, [], "more tests than the full arm"),
                                  (3367, 3367, [], "exactly as many"),
                                  (3366, 3367, m, "one fewer, so it did narrow"),
                                  (10, 3367, m, "far fewer")):
        got = run.misses_a_row_that_skipped_nothing_cannot_have(
            list(m), range(sel), range(full), _FORCED)
        if got != want:
            print(f"  FAIL: selected {what} ({sel} of {full}) gave {got}, expected {want}")
            failures += 1
    if run.misses_a_row_that_skipped_nothing_cannot_have([], range(9999), range(1), _FORCED) != []:
        print("  FAIL: a row with no misses invented one")
        failures += 1
    return failures


def case_a_mutation_that_does_not_compile_is_rejected_rather_than_recorded():
    """The compile gate: a non-compiling mutation is the generator's defect, not a row.

    Before it, the row fell through to the arms, the selecting arm failed to compile too, and the
    sweep wrote `build-failed` -- which the recall gate exits 1 on, so one bad literal cost a
    project its whole sweep. Six such defects landed in one campaign.
    """
    failures = 0
    root = tempfile.mkdtemp(prefix="yoriwake-gate-")
    diagnostic = os.path.join(root, "javac.log")
    with open(diagnostic, "w", encoding="utf-8") as handle:
        handle.write("A.java:3: error: incompatible types\n")
    environmental = os.path.join(root, "oom.log")
    with open(environmental, "w", encoding="utf-8") as handle:
        handle.write("FAILURE: Build failed with an exception.\nOutOfMemoryError\n")

    def compiler(code):
        def fake(checkout, project, task_args, log_path):
            return code, 1.0
        return fake

    for code, log, want, what in (
            (0, diagnostic, False, "a clean compile"),
            (1, diagnostic, True, "a compile error"),
            (2, diagnostic, True, "any other non-zero exit carrying a diagnostic"),
            # THE GENERATOR IS NOT BLAMED FOR THE GUEST'S BROKEN BUILD. A non-zero exit with no
            # compiler diagnostic is a daemon death, an OOM, a missing toolchain or a Checkstyle
            # failure, and filing it as a rejection would hide it behind a statistic AND take the
            # row out of `build-failed`, which is the status that says the tool broke its host.
            (1, environmental, False, "a non-zero exit with no compiler diagnostic"),
            (run.STALLED, diagnostic, False, "a killed build, which is stalled's to file")):
        got_code, rejected = run.compile_or_reject(
            "/checkout", {}, [":m:testClasses"], log, compiler(code))
        if rejected != want:
            print(f"  FAIL: {what} (code {code}) gave rejected={rejected}, expected {want}")
            failures += 1
        if got_code != code:
            print(f"  FAIL: {what} returned code {got_code}, expected {code}")
            failures += 1
    shutil.rmtree(root, ignore_errors=True)
    return failures


def case_the_compile_toolchain_is_not_a_defaulted_parameter():
    """A caller that omits the compiler fails to run rather than running ungated.

    `map_dir=None` read as optional and silently switched off the protection it guarded; three dead
    entry points were found in one session that way. The gate is only real in the caller that
    ships, so the parameter that carries it has no default to forget.
    """
    try:
        run.compile_or_reject("/checkout", {}, [":m:testClasses"], "/tmp/compile.log")
    except TypeError:
        return 0
    print("  FAIL: compile_or_reject ran with no compiler supplied, so the gate can be bypassed "
          "by forgetting an argument")
    return 1


def case_a_project_may_name_the_compile_task_its_guest_actually_has():
    """The derived name is a JVM convention, and a project that does not follow it may say so.

    `<test_task>Classes` is right for a JVM build and right for `jvmTest`. It is wrong for an
    Android unit-test variant: duckduckgo-android runs `testPlayDebugUnitTest` and `:app` has
    neither `testPlayDebugUnitTestClasses` nor `playDebugUnitTestClasses` -- both were asked, and
    both came back "not found in project ':app'". Its terminal compile task is
    `compilePlayDebugUnitTestJavaWithJavac`, whose graph carries the kapt, ksp and Kotlin compile
    phases a mutation can fail in.
    """
    failures = 0
    for project, want, what in (
            ({}, "testClasses", "a project that names no test task"),
            ({"test_task": "test"}, "testClasses", "an ordinary JVM build"),
            ({"test_task": "jvmTest"}, "jvmTestClasses", "a multiplatform JVM target"),
            ({"test_task": "testPlayDebugUnitTest",
              "test_classes_task": "compilePlayDebugUnitTestJavaWithJavac"},
             "compilePlayDebugUnitTestJavaWithJavac", "a guest naming its own compile task")):
        got = run.test_classes_task(project)
        if got != want:
            print(f"  FAIL: {what} derived {got!r}, expected {want!r}")
            failures += 1
    return failures


def case_a_classes_task_the_guest_does_not_have_is_refused_by_name(root):
    """duckduckgo-android's compile gate never ran, and nothing anywhere said so.

    All 31 `compile-*.log` files under bench/logs/duckduckgo-android end in `Cannot locate
    tasks that match ':app:testPlayDebugUnitTestClasses'`, and NOT ONE carries a compiler
    diagnostic. So `mutations_rejected: 0` on that project meant "the gate was never asked" rather
    than "the gate asked and accepted"; the mutated tree was never compiled before either arm was
    timed; and the one invalid mutation that reached the arms was recorded as `build-failed`, which
    `assert_recall.py` exits 1 on and which reads as though selection broke the guest's build.

    The derived name is a GUESS, so the guest is asked whether it has that task -- once, for about
    twenty seconds, before the capture that costs hours -- and refused by name when it does not.
    The check is the EXIT CODE of a dry run, not the wording of a failure: if Gradle rewords
    "Cannot locate tasks" this still refuses, it just says less about why.
    """
    failures = 0
    log = os.path.join(root, "classes-task-probe.log")
    with open(log, "w", encoding="utf-8") as handle:
        handle.write("* What went wrong:\nCannot locate tasks that match "
                     "':app:testPlayDebugUnitTestClasses' as task 'testPlayDebugUnitTestClasses' "
                     "not found in project ':app'.\n")
    asked = []

    def prober(code):
        def fake(checkout, project, task_args, log_path):
            asked.append(list(task_args))
            return code, 1.0
        return fake

    absent = {"name": "duckduckgo-android", "module": "app", "test_task": "testPlayDebugUnitTest"}
    try:
        run.assert_classes_task("/checkout", absent, log, prober(1))
    except SystemExit as refusal:
        message = str(refusal)
        # The derived name is what a reader has to act on, and the key is the remedy. A refusal
        # naming neither sends them back to the same guess.
        for wanted in ("testPlayDebugUnitTestClasses", "test_classes_task", "duckduckgo-android"):
            if wanted not in message:
                print(f"  FAIL: the refusal does not name {wanted!r}: {message}")
                failures += 1
    else:
        print("  FAIL: a classes task the guest does not have was not refused, so the sweep would "
              "pay for a capture and then fail once per mutation with nothing saying why")
        failures += 1

    if not asked or asked[-1][:2] != [":app:testPlayDebugUnitTestClasses", "--dry-run"]:
        print(f"  FAIL: the probe asked {asked[-1:] } , expected a --dry-run of the derived path")
        failures += 1

    # A guest that HAS the task is not refused and pays one configuration pass for the answer.
    present = {"name": "moshi", "module": "moshi", "test_task": "test"}
    try:
        run.assert_classes_task("/checkout", present, log, prober(0))
    except SystemExit as refusal:
        print(f"  FAIL: a guest whose classes task exists was refused: {refusal}")
        failures += 1

    # A FAILURE THAT IS NOT A MISSING TASK MUST NOT BE REPORTED AS ONE. A dependency that will not
    # resolve, a broken sibling build script and a dead daemon all exit non-zero here, and telling
    # their owner to set `test_classes_task` is a confidently wrong sentence attached to a refusal
    # that aborts a 30-to-42-hour sweep. Same distinction `compile_or_reject` makes when it asks
    # `compiler_rejected_the_source` instead of trusting an exit code.
    unrelated = os.path.join(root, "unrelated-failure.log")
    with open(unrelated, "w", encoding="utf-8") as handle:
        handle.write("FAILURE: Build failed with an exception.\n"
                     "Could not resolve all files for configuration ':app:debugRuntimeClasspath'.\n")
    try:
        run.assert_classes_task("/checkout", absent, unrelated, prober(1))
    except SystemExit as refusal:
        message = str(refusal)
        # The remedy SENTENCE, not the key's name: this message mentions `test_classes_task`
        # precisely to rule it out, which is the opposite of recommending it.
        if "Name the task the guest actually has" in message:
            print(f"  FAIL: a build that could not configure was handed the missing-task remedy, "
                  f"which cannot help it: {message}")
            failures += 1
        if "not a missing task" not in message:
            print(f"  FAIL: the refusal does not say what actually happened: {message}")
            failures += 1
    else:
        print("  FAIL: a probe that failed for an unrelated reason was passed over, so the sweep "
              "continues with the gate's state unestablished")
        failures += 1

    # A KILLED probe establishes nothing about the task and must not be reported as a missing one,
    # for the same reason STALLED is not a rejection in `compile_or_reject`.
    try:
        run.assert_classes_task("/checkout", absent, log, prober(run.STALLED))
    except SystemExit as refusal:
        if "killed" not in str(refusal):
            print(f"  FAIL: a killed probe was reported as a missing task: {refusal}")
            failures += 1
    else:
        print("  FAIL: a killed probe was passed over, so the sweep continues with the gate's "
              "state unestablished")
        failures += 1
    return failures


def case_a_missing_classes_task_is_not_a_generator_rejection():
    """The classifier is NOT widened to cover it, and that restraint is load-bearing.

    `MUTATION_REJECTION_CEILING` is 0.2 and `rejection_rate_refuses` refuses a project above it.
    Reading 31 missing-task failures as rejections would take duckduckgo-android's rate to 1.0 and
    discard the 30 valid rows it does produce -- trading a silent gate for a loud refusal of good
    evidence. A missing task is a HARNESS fault and `assert_classes_task` is where it is caught.

    The other half is what the gate will now actually see on that guest. Kotlin does not print
    `error:`: its diagnostics are `e: file:///...` lines, and the row recorded `build-failed` for
    `VpnMenuStateProvider.kt::body::isActive` carries four of them. What makes it a rejection is
    Gradle's `Compilation error` task banner, which that log also carries.
    """
    failures = 0
    root = tempfile.mkdtemp(prefix="yoriwake-gate-name-")
    missing = os.path.join(root, "missing-task.log")
    with open(missing, "w", encoding="utf-8") as handle:
        handle.write("* What went wrong:\nCannot locate tasks that match "
                     "':app:testPlayDebugUnitTestClasses' as task 'testPlayDebugUnitTestClasses' "
                     "not found in project ':app'.\nBUILD FAILED in 9s\n")
    kotlin = os.path.join(root, "kotlin.log")
    with open(kotlin, "w", encoding="utf-8") as handle:
        handle.write("e: file:///c/app/VpnMenuStateProvider.kt:78:9 Expecting a top level "
                     "declaration\n   > Compilation error. See log for more details\n")

    if run.compiler_rejected_the_source(missing):
        print("  FAIL: a task the guest does not have was read as a compiler diagnostic, which "
              "would take this project's rejection rate to 1.0 and discard its valid rows")
        failures += 1
    if not run.compiler_rejected_the_source(kotlin):
        print("  FAIL: a Kotlin compile failure was not read as a rejection; Kotlin prints `e:` "
              "and not `error:`, so the `Compilation error` banner is what carries it")
        failures += 1
    shutil.rmtree(root, ignore_errors=True)
    return failures


def case_a_guest_whose_suite_size_moves_may_buy_its_own_baseline_repeats():
    """One knob priced for the whole corpus cannot answer one unstable guest.

    duckduckgo-android's full arm ran 6,249, 6,252, 6,286 and 6,349 tests across sixteen rows -- a
    swing of about 100 between runs of the same suite -- and on three rows the SELECTED arm ran more
    tests than the ground-truth arm. A ground-truth arm that can silently omit 100 tests cannot
    support a recall figure, and the remedy is more unmutated runs on THAT project. Raising
    `--baseline-repeats` corpus-wide instead would buy one extra full suite run per project per
    sweep: two hours on kafka-core's 7,399-second suite alone, to answer a question about a
    different guest.

    Read the way `sweep_limit` is read, and refusing a value that is not a usable count for the same
    reason `project_limit` does: a typo in one project file must not silently halve the evidence.
    """
    failures = 0
    for project, default, want, what in (
            ({}, 2, 2, "a project with no key"),
            ({"baseline_repeats": 4}, 2, 4, "a project with the key"),
            ({"baseline_repeats": 4}, 6, 4, "the key in preference to a raised CLI value"),
            ({"baseline_repeats": 0}, 2, 2, "a key of zero, which buys no repeat at all"),
            ({"baseline_repeats": -1}, 2, 2, "a negative key"),
            ({"baseline_repeats": "four"}, 2, 2, "a key that is not a count"),
            # `isinstance(True, int)` is true in Python, so this one resolves to 1 -- no repeat at
            # all -- and is then stamped into the results file as `true`, where the freshness check
            # compares it against integers for the rest of that file's life.
            ({"baseline_repeats": True}, 2, 2, "a boolean, which Python calls an int"),
            ({"baseline_repeats": False}, 2, 2, "a false, which is an int and a zero")):
        got = run.baseline_repeats_for(project, default)
        if got != want:
            print(f"  FAIL: {what} resolved to {got!r}, expected {want!r}")
            failures += 1
    return failures


def case_a_changed_baseline_repeats_starts_a_fresh_results_file():
    """A stamp freshness cannot see is a stamp that lets two populations share one header.

    How firmly a row's already-failing set was established is a property of the row, and a file
    holding rows taken at two different repeat counts describes neither. Same shape as `flags`,
    including the null: `baseline_repeats` was stamped before this key existed, so a stored null is
    a file written before the FIELD existed and means unknown, never zero. Reading it as zero would
    age every such file and charge the corpus a re-sweep to introduce a key.

    This is the `flags` discipline and NOT the `staged_sources` one, and the difference is which
    reading is provable. A staged project that stored no `staged_sources` provably was not staged,
    because the field would have been written. A stored null here proves nothing about the repeats.
    """
    failures = 0
    for stored, project, default, want, what in (
            ({"baseline_repeats": 2}, {}, 2, False, "the same count"),
            ({"baseline_repeats": 2}, {"baseline_repeats": 4}, 2, True,
             "a project that now buys more repeats"),
            ({"baseline_repeats": 4}, {"baseline_repeats": 4}, 2, False,
             "rows already measured at the project's own count"),
            ({"baseline_repeats": 2}, {}, 4, True, "a raised CLI default"),
            ({"baseline_repeats": None}, {"baseline_repeats": 4}, 2, False,
             "a file written before the field existed, which is unknown and not zero"),
            ({}, {"baseline_repeats": 4}, 2, False, "a file with no such key at all")):
        got = run.baseline_repeats_changed(stored, project, default)
        if got != want:
            print(f"  FAIL: {what} gave changed={got}, expected {want}")
            failures += 1
    return failures


def case_a_rejection_rate_above_the_ceiling_is_a_refusal():
    """The gate turns a grammar defect into a silent discard; this is what makes it loud again.

    Every one of the six grammar defects was found BECAUSE it cost rows. The two worst -- kafka at
    8 of 21 and mockito at 5 of 16 -- are above the ceiling and must refuse; an ordinary stray
    rejection is a statistic and must not.
    """
    failures = 0
    for rejected, accepted, want, what in ((0, 16, False, "nothing rejected"),
                                           (1, 16, False, "one stray rejection"),
                                           (8, 13, True, "kafka's one-line braces, 8 of 21"),
                                           (5, 11, True, "mockito's dropped long suffix, 5 of 16"),
                                           (0, 0, False, "a sweep that offered nothing at all")):
        got = run.rejection_rate_refuses(rejected, accepted)
        if got != want:
            print(f"  FAIL: {what} ({rejected} rejected, {accepted} accepted) gave {got}, "
                  f"expected {want}")
            failures += 1
    return failures


def case_only_the_selector_source_moves_the_digest():
    """The whole point: a change that cannot alter a decision must not age 36 results files.

    Keyed on repository HEAD, a docs commit carrying one stray discarded line aged the entire
    corpus, and so did a method with no production caller. Keyed on content, neither does.
    """
    failures = 0
    root = tempfile.mkdtemp(prefix="yoriwake-digest-")
    try:
        for directory in ("plugin/src/main", "agent/src/main"):
            os.makedirs(os.path.join(root, directory))
        selector = os.path.join(root, "agent/src/main", "Selector.java")
        with open(selector, "w", encoding="utf-8") as handle:
            handle.write("class Selector {}\n")
        # BOTH TREES POPULATED, like the real checkout. With only one of them carrying a file, a
        # guard that narrows SELECTOR_SOURCE_DIRS empties the digest's input entirely and the
        # refuse-on-empty fires -- so the suite CRASHES instead of failing its assertion, and the
        # guard reports "did not run" rather than proving anything.
        with open(os.path.join(root, "plugin/src/main", "ChangeSelection.kt"), "w",
                  encoding="utf-8") as handle:
            handle.write("object ChangeSelection\n")
        base = run.selector_digest(root)

        for relative, what in (("docs/note.md", "a docs file"),
                               # NOT "the harness is irrelevant": run.py chooses the -Pyoriwake.*
                               # flags each arm runs under, which changes what a sweep measures.
                               # What it does not do is change the selector, which is what the
                               # digest is about.
                               ("bench/run.py", "a harness file, which is not the selector"),
                               ("agent/src/test/SelectorTest.kt", "a test")):
            path = os.path.join(root, relative)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="utf-8") as handle:
                handle.write("anything\n")
            if run.selector_digest(root) != base:
                print(f"  FAIL: {what} moved the selector digest")
                failures += 1

        with open(selector, "w", encoding="utf-8") as handle:
            handle.write("class Selector { }\n")
        if run.selector_digest(root) == base:
            print("  FAIL: a change to the selector's own source did NOT move the digest")
            failures += 1

        # A REVERT RESTORES IT, which is what makes an accidental change cost nothing.
        with open(selector, "w", encoding="utf-8") as handle:
            handle.write("class Selector {}\n")
        if run.selector_digest(root) != base:
            print("  FAIL: reverting the selector did not restore the digest")
            failures += 1

        # THE BUILD FILE IS IN, because the check this replaces covered it and it pins the ASM
        # version the agent instruments with. Losing that coverage was the review's sharpest find.
        build = os.path.join(root, "agent/build.gradle.kts")
        with open(build, "w", encoding="utf-8") as handle:
            handle.write("dependencies { bundled(\"org.ow2.asm:asm:9.8\") }\n")
        moved = run.selector_digest(root)
        if moved == base:
            print("  FAIL: a build file that pins the agent's ASM version did not move the digest")
            failures += 1
        os.remove(build)

        # A DOTFILE UNDER THE SELECTOR TREE IS SELECTOR SOURCE. `glob` with `*` skips these.
        hidden = os.path.join(root, "agent/src/main", ".hidden.java")
        with open(hidden, "w", encoding="utf-8") as handle:
            handle.write("class Hidden {}\n")
        if run.selector_digest(root) == base:
            print("  FAIL: a dotfile under the selector tree sat outside the digest")
            failures += 1
        os.remove(hidden)

        # AN EMPTY TREE REFUSES rather than hashing nothing: the digest of no input is a stable
        # value every results file would agree with, which fails open on both sides.
        empty = tempfile.mkdtemp(prefix="yoriwake-empty-")
        try:
            run.selector_digest(empty)
            print("  FAIL: a checkout with no selector source returned a digest instead of refusing")
            failures += 1
        except SystemExit:
            pass
        finally:
            shutil.rmtree(empty, ignore_errors=True)

        # A FILE THAT MOVES IS A CHANGE: the path is hashed with the content.
        os.rename(selector, os.path.join(root, "agent/src/main", "Moved.java"))
        if run.selector_digest(root) == base:
            print("  FAIL: moving a selector source did not move the digest")
            failures += 1
    finally:
        shutil.rmtree(root, ignore_errors=True)
    return failures


def case_only_the_grammar_source_moves_the_grammar_digest():
    """A change to a generator's rules inside the `default` set is what `target_shapes` cannot see.

    The stamp exists for that one change, so it must move on it and on nothing that leaves the
    drawn edits alone -- above all not on the selector, whose own digest answers a different
    question.
    """
    failures = 0
    # NAMED, NOT READ BACK FROM THE CONSTANT, so dropping a file from it fails an assertion below
    # rather than crashing the fixture that would have created it.
    grammar = ("bench/mutations.py", "bench/target_ordering.py",
               "bench/recall_check.py")
    root = tempfile.mkdtemp(prefix="yoriwake-grammar-")
    try:
        for name in grammar:
            path = os.path.join(root, name)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="utf-8") as handle:
                handle.write(f"# {name}\n")
        base = run.grammar_digest(root)

        for relative, what in (("plugin/src/main/Selector.kt", "the selector"),
                               ("bench/run.py", "the harness that runs the arms"),
                               ("docs/note.md", "a docs file")):
            path = os.path.join(root, relative)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="utf-8") as handle:
                handle.write("anything\n")
            if run.grammar_digest(root) != base:
                print(f"  FAIL: {what} moved the grammar digest")
                failures += 1

        for name in grammar:
            path = os.path.join(root, name)
            with open(path, "a", encoding="utf-8") as handle:
                handle.write("changed = True\n")
            if run.grammar_digest(root) == base:
                print(f"  FAIL: a change to {name} did NOT move the grammar digest")
                failures += 1
            with open(path, "w", encoding="utf-8") as handle:
                handle.write(f"# {name}\n")
        if run.grammar_digest(root) != base:
            print("  FAIL: reverting the grammar did not restore the digest")
            failures += 1

        empty = tempfile.mkdtemp(prefix="yoriwake-empty-")
        try:
            run.grammar_digest(empty)
            print("  FAIL: a checkout with no grammar source returned a digest instead of refusing")
            failures += 1
        except SystemExit:
            pass
        finally:
            shutil.rmtree(empty, ignore_errors=True)
    finally:
        shutil.rmtree(root, ignore_errors=True)
    return failures


def case_a_changed_grammar_starts_a_fresh_results_file():
    """Rows drawn under two grammars in one file describe neither population.

    Null is a file written before the field existed and is tolerated, as `flags` tolerates it --
    every results file on disk predates this stamp. The writer then leaves such a file unstamped
    rather than claiming rules nobody recorded, and a history replay, which draws no targets, is
    never stamped at all.
    """
    import json
    failures = 0
    for stored, want, what in (({"grammar_digest": "aaa"}, False, "the same grammar"),
                               ({"grammar_digest": "bbb"}, True, "a changed grammar"),
                               ({"grammar_digest": None}, False, "a file from before the stamp"),
                               ({}, False, "a file with no such key at all")):
        got = run.grammar_changed(stored, "aaa")
        if got != want:
            print(f"  FAIL: {what} gave changed={got}, expected {want}")
            failures += 1

    row = [{"target": "t"}]
    for stored, findings, want, what in (
            ({"grammar_digest": "aaa"}, row, "aaa", "rows drawn under the current grammar"),
            ({"grammar_digest": None}, row, None, "rows written before the stamp existed"),
            ({}, row, None, "rows in a file with no such key at all"),
            ({"grammar_digest": None}, [], "aaa", "a pre-stamp file with no rows to carry")):
        got = run.resumed_grammar(stored, findings, "aaa")
        if got != want:
            print(f"  FAIL: resuming {what} stamped {got!r}, expected {want!r}")
            failures += 1

    with tempfile.TemporaryDirectory() as directory:
        out = os.path.join(directory, "p.json")
        project = {"name": "p", "commit": "c" * 40}
        for seed, grammar, want, what in ((1, "aaa", "aaa", "a synthetic sweep"),
                                          (1, None, None, "a resumed file from before the stamp"),
                                          (None, "aaa", None, "a history replay")):
            run.write_results(out, project, "m", None, None, [], seed, grammar=grammar)
            with open(out, encoding="utf-8") as handle:
                header = json.load(handle)
            if "grammar_digest" not in header or header["grammar_digest"] != want:
                print(f"  FAIL: {what} recorded grammar_digest {header.get('grammar_digest')!r}, "
                      f"expected {want!r}")
                failures += 1
    return failures


def case_a_fixture_edit_moves_the_fixture_digest_and_a_resume_starts_fresh():
    """A fixture runs at `commit = "local"`, so its tree is the only stamp that moves when a test is
    added to it. Tracked files only; a project that is not a fixture carries null."""
    failures = 0
    with tempfile.TemporaryDirectory() as root:
        def git(*args):
            subprocess.run(["git", "-C", root, *args], check=True, capture_output=True)
        git("init", "-q")
        os.makedirs(os.path.join(root, "samples", "fx", "src"))
        os.makedirs(os.path.join(root, "samples", "other"))
        for name, text in (("samples/fx/src/A.java", "class A {}\n"),
                           ("samples/fx/src/ATest.java", "class ATest {}\n"),
                           ("samples/other/B.java", "class B {}\n")):
            with open(os.path.join(root, name), "w", encoding="utf-8") as handle:
                handle.write(text)
        git("add", "-A")
        project = {"name": "fx", "commit": "local", "copy_of": "samples/fx"}
        before = run.fixture_digest(project, root)

        with open(os.path.join(root, "samples", "fx", "notes.txt"), "w", encoding="utf-8") as h:
            h.write("untracked\n")
        with open(os.path.join(root, "samples", "other", "B.java"), "a", encoding="utf-8") as h:
            h.write("// elsewhere\n")
        git("add", "samples/other/B.java")
        if run.fixture_digest(project, root) != before:
            failures += fail("an untracked file, or a tracked one outside the sample, moved the "
                             "fixture digest")

        with open(os.path.join(root, "samples", "fx", "src", "ATest.java"), "a",
                  encoding="utf-8") as handle:
            handle.write("// a test added\n")
        after = run.fixture_digest(project, root)
        if after == before:
            failures += fail("editing a tracked fixture file left the fixture digest where it was")

        out = os.path.join(root, "fx.json")
        run.RUN_STAMPS["fixture_digest"] = before
        try:
            run.write_results(out, project, "", None, None, [{"target": "t"}], 1)
        finally:
            run.RUN_STAMPS.pop("fixture_digest", None)
        with open(out, encoding="utf-8") as handle:
            stored = json.load(handle)
        if stored.get("fixture_digest") != before:
            failures += fail(f"the results file stamped {stored.get('fixture_digest')!r}, "
                             f"expected {before!r}")
        if not run.fixture_changed(stored, after):
            failures += fail("a results file measured on the old fixture tree would be resumed")
        if run.fixture_changed(stored, before):
            failures += fail("a results file measured on this fixture tree would start fresh")
        if not run.fixture_changed({}, after):
            failures += fail("a fixture's file with no stamp would be resumed, though it cannot say "
                             "which tree it ran on")

    corpus = {"name": "p", "commit": "c" * 40, "url": "u"}
    if run.fixture_digest(corpus) is not None:
        failures += fail("a project that is not a fixture carried a fixture digest")
    if run.fixture_changed({"fixture_digest": None}, None) or run.fixture_changed({}, None):
        failures += fail("a project that is not a fixture started fresh on a fixture stamp")
    return failures


def case_a_capture_that_was_never_merged_is_not_a_missing_map():
    """iceberg: 17,573 raw records, no coverage.tsv, and the verdict said there was no map.

    `find_map` requires `coverage.tsv`, which only a completed decode writes, so a capture that ran
    and was never merged printed the same sentence as one that never ran. The first is a defect of
    this tool; the second is usually a property of the guest. Different owners, so different words.
    """
    failures = 0
    root = tempfile.mkdtemp(prefix="yoriwake-map-")
    try:
        yoriwake = os.path.join(root, ".gradle", "yoriwake")

        unmerged = os.path.join(yoriwake, "mod-test-abc123")
        os.makedirs(os.path.join(unmerged, "raw", "worker-1"))
        open(os.path.join(unmerged, "raw", "worker-1", "0001.exec"), "w").close()

        if run.find_map(root, "mod", "test") is not None:
            print("  FAIL: a directory with no coverage.tsv was accepted as a usable map")
            failures += 1
        if run.unfinished_map(root, "mod", "test") != unmerged:
            print(f"  FAIL: the unmerged capture was not found: "
                  f"{run.unfinished_map(root, 'mod', 'test')!r}")
            failures += 1

        # A FINISHED map is not unfinished, however many raw records it also kept.
        open(os.path.join(unmerged, "coverage.tsv"), "w").close()
        if run.unfinished_map(root, "mod", "test") is not None:
            print("  FAIL: a merged map was reported as never merged")
            failures += 1

        # And a module that never captured at all has neither.
        if run.unfinished_map(root, "other", "test") is not None:
            print("  FAIL: a module with no map directory reported an unfinished capture")
            failures += 1
    finally:
        shutil.rmtree(root, ignore_errors=True)
    return failures


def case_a_container_is_not_also_published_as_having_run_and_passed():
    """It was subtracted from `missed` and then reappeared as a test that RAN AND PASSED.

    One undecidable thing in two published lists that contradict each other. A container never
    started, so it ran nowhere and cannot have passed.
    """
    container = ("kafka.server.DeleteTopicsRequestTest", "initializationError")
    got = run.ran_but_passed({container}, set(), [], [], [], [container], [])
    if got:
        print(f"  FAIL: a container that never started was published as ran-but-passed: {got}")
        return 1
    return 0


def case_a_container_that_never_started_is_not_a_miss():
    """kafka-core: `DescribeClusterRequestTest::initializationError` is a class, not a test.

    Both verification arms run a class alone, which is a lighter load than the full suite, so a
    broker that cannot bind a port under load starts happily in isolation and the arms report
    "passes alone" for something that never ran a test method at all.
    """
    failures = 0
    container = ("org.apache.kafka.DescribeClusterRequestTest", "initializationError")
    real = ("org.apache.kafka.Foo", "bar")

    missed, containers = run.container_failures([container], range(3), range(3367))
    if missed != [] or containers != [container]:
        print(f"  FAIL: a lone container on a narrowed row gave missed={missed} "
              f"containers={containers}")
        failures += 1

    # A genuine miss beside a container on the SAME row stays a miss. The category subtracts from
    # the headline, so a rule that took the row with it would erase real evidence.
    missed, containers = run.container_failures([real, container], range(3), range(3367))
    if missed != [real] or containers != [container]:
        print(f"  FAIL: a real miss beside a container gave missed={missed} "
              f"containers={containers}")
        failures += 1
    return failures


def case_a_class_lifecycle_failure_is_a_container_too():
    """mockk's `executionError[jvm]`: Gradle's entry for an `@AfterAll` or class extension.

    No test method ran under that name, and a re-run of the class cannot report it, so the isolated
    arm answered UNKNOWN and the miss could never clear. On a Kotlin Multiplatform JVM target the
    entry carries the target as a suffix.
    """
    failures = 0
    real = ("io.mockk.Foo", "bar")
    for name in ("executionError", "executionError[jvm]", "initializationError[jvm]"):
        entry = ("io.mockk.MockKExtensionAfterAllTestTest", name)
        missed, containers = run.container_failures([entry], range(3), range(745))
        if missed != [] or containers != [entry]:
            failures += fail(f"`{name}` on a narrowed row gave missed={missed} "
                             f"containers={containers}")
        missed, containers = run.container_failures([real, entry], range(3), range(745))
        if missed != [real] or containers != [entry]:
            failures += fail(f"a real miss beside `{name}` gave missed={missed} "
                             f"containers={containers}")
        # A row that skipped nothing is the skipped-nothing rule's, as it always was.
        missed, containers = run.container_failures([entry], range(745), range(745))
        if missed != [entry] or containers != []:
            failures += fail(f"`{name}` on a row that skipped nothing was re-decided")
    return failures


def case_a_test_named_like_a_container_is_still_a_test():
    """Exact names only: a method called `executionErrorHandling` is a test that can be missed."""
    failures = 0
    for name in ("executionErrorHandling", "initializationErrorIsReported", "executionError[jvm]x",
                 "anExecutionError"):
        test = ("com.acme.Foo", name)
        missed, containers = run.container_failures([test], range(3), range(745))
        if missed != [test] or containers:
            failures += fail(f"the test `{name}` was set aside as a container")
    return failures


def case_a_row_that_skipped_nothing_leaves_its_containers_alone():
    """A forced row is already disqualified, and re-deciding it stacks two rules on one case."""
    failures = 0
    container = ("org.apache.kafka.DescribeClusterRequestTest", "initializationError")
    for sel, full, what in ((3368, 3367, "more tests than the full arm"),
                            (3367, 3367, "exactly as many")):
        missed, containers = run.container_failures([container], range(sel), range(full))
        if containers != [] or missed != [container]:
            print(f"  FAIL: a row that selected {what} ({sel} of {full}) was re-decided: "
                  f"missed={missed} containers={containers}")
            failures += 1
    return failures


def case_a_sibling_failure_does_not_make_this_test_unreadable():
    """kafka-core. A whole class runs, so a sibling's failure sets the exit code.

    `kafka.network.SocketServerTest` fails 5 or 6 of its 61 tests on every run of that checkout.
    Every test in it was therefore permanently unreadable: the build never exited clean, so
    "passed alone" could not be reached, and both callers answered "do not know" -- which keeps a
    miss. One flaky failure in three repeats then published `remoteCloseSendFailure` as a genuine
    selector miss on one observation against two that were merely unreadable.
    """
    failures = 0
    me, sibling = ("com.acme.Foo", "bar"), ("com.acme.Foo", "other")

    # The exit code is accounted for by a DIFFERENT test, so this one passed alone.
    got = isolation.outcome_alone(
        "checkout", {"name": "p"}, "m", *me, ".", "stem",
        gradle=_FakeGradle(1), results_of=lambda *a: ({me, sibling}, {sibling}), task="test",
        keys=isolation.XML_KEYS)
    if got != isolation.PASSES_ALONE:
        print(f"  FAIL: a sibling's failure left this test unreadable ({got})")
        failures += 1

    # NOTHING accounts for it, which is the compile failure and the infrastructure fault the
    # original rule was written for. Still unreadable, and a miss is still kept.
    got = isolation.outcome_alone(
        "checkout", {"name": "p"}, "m", *me, ".", "stem",
        gradle=_FakeGradle(1), results_of=lambda *a: ({me}, set()), task="test",
        keys=isolation.XML_KEYS)
    if got != isolation.UNKNOWN:
        print(f"  FAIL: an unexplained failed build was read as an answer ({got})")
        failures += 1

    # The test's own failure still wins, whatever else failed beside it.
    got = isolation.outcome_alone(
        "checkout", {"name": "p"}, "m", *me, ".", "stem",
        gradle=_FakeGradle(1), results_of=lambda *a: ({me, sibling}, {me, sibling}), task="test",
        keys=isolation.XML_KEYS)
    if got != isolation.FAILS_ALONE:
        print(f"  FAIL: this test's own failure was masked by a sibling's ({got})")
        failures += 1

    # A killed build describes an interrupted run, so its results describe nothing. Unchanged.
    got = isolation.outcome_alone(
        "checkout", {"name": "p"}, "m", *me, ".", "stem",
        gradle=_FakeGradle(run.STALLED), results_of=lambda *a: ({me, sibling}, {sibling}),
        task="test", keys=isolation.XML_KEYS)
    if got != isolation.UNKNOWN:
        print(f"  FAIL: a stalled build was read as an answer ({got})")
        failures += 1
    return failures


def case_a_test_that_fails_with_the_change_reverted_is_not_a_miss():
    """kafka-core, and it is the direction this harness must be most careful about.

    Four tests in `kafka.network.SocketServerTest` were published as missed failures induced by
    mutations to share-group code. That class fails 5 or 6 of its 61 tests on every UNMUTATED run,
    a different subset each time, so it fails the full run, is correctly not selected, and fails
    alone -- which is every signal a real miss gives. The premise of an induced failure is that the
    test passes without the change, and one run proving otherwise destroys it.
    """
    failures = 0
    target = ("com.acme.Foo", "bar")
    fails = lambda *a: ({target}, {target})
    passes = lambda *a: ({target}, set())

    caught = _fails_without_the_change(_FakeGradle(1, 1, 1), fails)
    if caught != {target}:
        print(f"  FAIL: failing alone with the change reverted did not disqualify ({caught})")
        failures += 1

    # ONE is enough, and it must not need all three: the class is intermittent by nature, so
    # demanding unanimity would hand the miss back to exactly the tests this arm exists to catch.
    once = _FakeGradle(0, 1, 0)
    caught = _fails_without_the_change(
        once, lambda *a: (({target}, {target}) if len(once.calls) == 2 else ({target}, set())))
    if caught != {target}:
        print(f"  FAIL: one failure in three repeats did not disqualify ({caught})")
        failures += 1

    kept = _fails_without_the_change(_FakeGradle(0, 0, 0), passes)
    if kept:
        print(f"  FAIL: a test passing with the change reverted stopped being a miss ({kept})")
        failures += 1

    # The asymmetry stated in the docstring: this arm may only ever REMOVE attribution, so every
    # answer short of a definite failure has to leave the miss standing.
    for code, what in ((run.STALLED, "a killed build"), (1, "a build that read nothing")):
        kept = _fails_without_the_change(_FakeGradle(code, code, code), lambda *a: (None, set()))
        if kept:
            print(f"  FAIL: {what} cleared a miss ({kept})")
            failures += 1
    return failures


def case_the_isolated_rerun_asks_for_the_measured_task():
    """The isolated re-run must name the task under measurement, not the literal `test`.

    On a Kotlin Multiplatform project the measured task is `jvmTest` and `test` runs nothing, so an
    isolated re-run of `:m:test` exits 0 having executed no tests. Reading a results directory that
    arm never wrote is how a check that cannot answer would clear a real miss.
    """
    failures = 0
    for task, expected in (("test", ":m:test"), ("jvmTest", ":m:jvmTest")):
        gradle = _FakeGradle(0)
        _order_dependent(gradle, lambda *a: (set(), set()), task=task)
        asked = gradle.calls[0][0] if gradle.calls else "<never called>"
        if asked != expected:
            print(f"  FAIL: task={task!r} asked gradle for {asked!r}, expected {expected!r}")
            failures += 1
    return failures


def case_a_failed_isolation_build_keeps_the_miss():
    """Non-zero exit means the check could not answer, and an unanswered check may not clear."""
    failures = 0
    for code, what in ((1, "a build failure"), (run.STALLED, "a killed, stalled build"),
                       (-1, "a timeout")):
        cleared = _order_dependent(
            _FakeGradle(code),
            lambda *a: ({("com.acme.Foo", "bar")}, set()),  # would clear if it were consulted
        )
        if cleared:
            print(f"  FAIL: {what} cleared a miss ({cleared})")
            failures += 1
    return failures


def case_results_that_cannot_be_read_keep_the_miss():
    """results_of returning None is "I could not tell", which is not "it passed"."""
    failures = 0
    cleared = _order_dependent(_FakeGradle(0), lambda *a: (None, None))
    if cleared:
        print(f"  FAIL: unreadable results cleared a miss ({cleared})")
        failures += 1
    return failures


def case_a_test_absent_from_the_isolated_run_keeps_the_miss():
    """The filter matched nothing, so the test never ran, so it never passed alone."""
    failures = 0
    cleared = _order_dependent(_FakeGradle(0), lambda *a: ({("com.acme.Other", "x")}, set()))
    if cleared:
        print(f"  FAIL: a test that did not run at all cleared a miss ({cleared})")
        failures += 1
    return failures


def case_a_test_that_passes_alone_is_order_dependent():
    """The one branch that may clear: it ran, alone, against the same tree, and passed."""
    failures = 0
    cleared = _order_dependent(_FakeGradle(0), lambda *a: ({("com.acme.Foo", "bar")}, set()))
    if cleared != {("com.acme.Foo", "bar")}:
        print(f"  FAIL: a test that passed alone was not reported as order-dependent ({cleared})")
        failures += 1
    # And one that still fails alone is a real miss, whatever else ran.
    still = _order_dependent(
        _FakeGradle(0),
        lambda *a: ({("com.acme.Foo", "bar")}, {("com.acme.Foo", "bar")}),
    )
    if still:
        print(f"  FAIL: a test that fails alone was discounted ({still})")
        failures += 1
    return failures


class _ScriptedResults:
    """A results_of that answers differently on each call, so a repeat can contradict its sibling.

    Every other fake here answers the same way forever, which is exactly why a single isolated
    re-run could label a flaky test order-dependent and nothing noticed.
    """

    def __init__(self, *answers):
        self.answers = list(answers)
        self.calls = 0

    def __call__(self, *args):
        answer = self.answers[min(self.calls, len(self.answers) - 1)]
        self.calls += 1
        return answer


_FOO = ("com.acme.Foo", "bar")
_PASSES = ({_FOO}, set())
_FAILS = ({_FOO}, {_FOO})


def case_the_isolated_rerun_is_repeated():
    """One answer cannot tell a dependency from a coin flip, so the check asks several times."""
    failures = 0
    gradle = _FakeGradle()
    _order_dependent(gradle, lambda *a: _PASSES)
    if len(gradle.calls) != isolation.ISOLATED_REPEATS:
        print(f"  FAIL: the isolated re-run happened {len(gradle.calls)} times, expected "
              f"{isolation.ISOLATED_REPEATS}")
        failures += 1
    return failures


def case_a_test_that_contradicts_itself_is_nondeterministic_not_missed():
    """Held still, it answered the same question two ways, so neither answer is evidence.

    kafka's two TLS misses are this: published as missed failures on one PASSES_ALONE each, one of
    them "induced" by a mutation that only adds an unused method and cannot change behaviour.
    """
    failures = 0
    results = _ScriptedResults(_FAILS, _PASSES, _PASSES)
    flaky = _nondeterministic(_FakeGradle(), results)
    if flaky != {_FOO}:
        print(f"  FAIL: a test whose isolated answers disagreed was not reported "
              f"nondeterministic ({flaky})")
        failures += 1
    # And it must not ALSO be reported order-dependent -- that list is counted as missed.
    dependent = _order_dependent(_FakeGradle(), _ScriptedResults(_FAILS, _PASSES, _PASSES))
    if dependent:
        print(f"  FAIL: a nondeterministic test was also reported order-dependent ({dependent})")
        failures += 1
    return failures


def case_a_majority_that_fails_alone_keeps_the_miss():
    """FAILS, FAILS, PASSES disagrees -- and the test still, mostly, fails by itself.

    Reclassifying that would take a test out of the missed count because one run in three went the
    other way, which is a recall figure improving for the wrong reason. Only a disagreement the
    failures do not win may move a test out of the count.
    """
    failures = 0
    results = _ScriptedResults(_FAILS, _FAILS, _PASSES)
    flaky = _nondeterministic(_FakeGradle(), results)
    if flaky:
        print(f"  FAIL: a test that failed alone twice of three was erased from the miss count "
              f"({flaky})")
        failures += 1
    dependent = _order_dependent(_FakeGradle(), _ScriptedResults(_FAILS, _FAILS, _PASSES))
    if dependent:
        print(f"  FAIL: a test that failed alone twice of three was called order-dependent "
              f"({dependent})")
        failures += 1
    # And the other way round: mostly passing alone is the kafka shape the repeats were added for.
    mostly_passing = _nondeterministic(_FakeGradle(), _ScriptedResults(_PASSES, _PASSES, _FAILS))
    if mostly_passing != {_FOO}:
        print(f"  FAIL: a test that passed alone twice of three was not reported nondeterministic "
              f"({mostly_passing})")
        failures += 1
    return failures


def case_agreeing_repeats_are_not_nondeterministic():
    """The reclassification has to be reachable only by contradiction, or it clears real misses."""
    failures = 0
    for results, what in ((lambda *a: _FAILS, "fails alone every time"),
                          (lambda *a: _PASSES, "passes alone every time")):
        flaky = _nondeterministic(_FakeGradle(), results)
        if flaky:
            print(f"  FAIL: a test that {what} was called nondeterministic ({flaky})")
            failures += 1
    return failures


def case_an_unanswered_repeat_clears_nothing():
    """UNKNOWN is not an answer, so it cannot disagree with one -- and it cannot clear one either.

    Two PASSES_ALONE and one build that did not answer keeps the test a MISS. Reading the two
    answers as agreement would let a check that could not answer clear the only finding this
    harness exists to produce.
    """
    failures = 0
    # First call stalls (UNKNOWN), the next two pass alone.
    gradle = _FakeGradle(run.STALLED)
    dependent = _order_dependent(gradle, lambda *a: _PASSES)
    if dependent:
        print(f"  FAIL: an unanswered repeat let a miss be cleared to order-dependent ({dependent})")
        failures += 1
    flaky = _nondeterministic(_FakeGradle(run.STALLED), lambda *a: _PASSES)
    if flaky:
        print(f"  FAIL: an unanswered repeat read as a contradiction ({flaky})")
        failures += 1
    return failures


def case_the_recall_arm_kinds_reach_their_generators():
    """A kind `mutations.apply` does not know returns None, which reads as "asked nothing".

    That is the quiet failure this harness keeps finding: a shape registered in the grammar and
    unhandled at the call site produces a run that skips every one of its targets and reports a
    clean sweep over a population it never mutated. `schema` was added to the grammar first and
    sliced off every real project before anyone noticed.
    """
    import os
    import shutil
    import tempfile
    failures = 0
    root = tempfile.mkdtemp(prefix="yoriwake-kinds-")
    try:
        cases = {
            "inline": ("Inlined.kt", "package p\n\ninline fun aboveZero(n: Int): Boolean = n > 0\n"),
            "nested-constant": ("Holder.kt", "package p\n\nclass Holder {\n    companion object "
                                             "{\n        const val INNER: Int = 5\n    }\n}\n"),
            "second-type": ("Two.kt", "package p\n\nclass First\n\nclass Second {\n    companion "
                                      "object {\n        const val OTHER: Int = 7\n    }\n}\n"),
        }
        for kind, (name, text) in cases.items():
            with open(os.path.join(root, name), "w", encoding="utf-8") as handle:
                handle.write(text)
            label = run.mutations.apply(root, name, kind, 0)
            if label is None or f"::{kind}::" not in label:
                print(f"  FAIL: mutations.apply returned {label!r} for kind {kind!r}; the shape "
                      f"would be skipped on every target")
                failures += 1
    finally:
        shutil.rmtree(root, ignore_errors=True)
    return failures


def case_the_isolated_run_does_not_mutate_the_map_it_verifies_against(tmp):
    """The isolation run decodes like any other, so it must be bracketed by a map backup.

    The ground-truth run has been bracketed since exactly this was found there; this one was not,
    so verifying a miss rewrote the map the NEXT row selects against.
    """
    import os
    import shutil
    failures = 0
    map_dir = os.path.join(tmp, "map")
    scratch = os.path.join(tmp, "scratch")
    shutil.rmtree(map_dir, ignore_errors=True)
    os.makedirs(map_dir, exist_ok=True)
    os.makedirs(scratch, exist_ok=True)
    with open(os.path.join(map_dir, "coverage.tsv"), "w", encoding="utf-8") as handle:
        handle.write("the map as the row left it\n")

    def rewrites_the_map(checkout, project, task_args, log_path):
        with open(os.path.join(map_dir, "coverage.tsv"), "w", encoding="utf-8") as handle:
            handle.write("what one filtered run happened to record\n")
        return 0, 1.0

    _order_dependent(rewrites_the_map, lambda *a: ({("com.acme.Foo", "bar")}, set()),
                     tmp=scratch, map_dir=map_dir)

    with open(os.path.join(map_dir, "coverage.tsv"), encoding="utf-8") as handle:
        left = handle.read().strip()
    if left != "the map as the row left it":
        print(f"  FAIL: the isolation run left the map rewritten: {left!r}")
        failures += 1
    return failures


def case_a_killed_build_is_not_a_cache_hit():
    """STALLED was returned and never checked, so a hung build was filed as the benign thing.

    A killed process's log carries no `FAILURE: Build failed` banner, so executed_or_excluded
    reached its last line and recorded `"status": "cache-hit"` -- "nothing was going to run
    anyway" -- for a build that was killed after ten minutes of silence.
    """
    failures = 0
    findings = []
    if not run.stalled(run.STALLED, "target::x", findings, "selected"):
        print("  FAIL: a STALLED exit code was not recognised")
        failures += 1
    if findings != [{"target": "target::x", "status": "stalled", "phase": "selected"}]:
        print(f"  FAIL: a stalled row was not filed as its own status: {findings}")
        failures += 1
    # And every ordinary exit code is left alone, including a build that failed because tests did.
    for code in (0, 1, -1):
        if run.stalled(code, "target::x", findings, "selected"):
            print(f"  FAIL: exit code {code} was treated as a stall")
            failures += 1
    return failures

def case_the_map_is_looked_up_by_the_measured_task():
    """find_map must look for the task's directory, not the literal `test`.

    MapLocation names the map after the TASK PATH, so `:m:jvmTest` writes `m-jvmTest-<hash>`.
    Looking for `m-test-` there finds nothing, every row forces, and the sweep files `0 MISSED`
    with no savings as though it had asked a recall question. It had not: it never found the map.
    """
    import tempfile
    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        maps = os.path.join(tmp, ".gradle", "yoriwake")
        # coverage.tsv is what makes a directory a MAP rather than just a directory; find_map
        # requires it, which this fixture originally omitted.
        for name in ("m-jvmTest-abcd1234", "m-test-99999999", "m-integrationTest-nomap"):
            os.makedirs(os.path.join(maps, name))
        for name in ("m-jvmTest-abcd1234", "m-test-99999999"):
            with open(os.path.join(maps, name, "coverage.tsv"), "w", encoding="utf-8") as handle:
                handle.write("")
        # A NESTED module: MapLocation writes `:library:test` + `testDebugUnitTest` as
        # `library-test-testDebugUnitTest-<hash>`. glide's first sweep found no map and forced every
        # row because the colon was not translated.
        os.makedirs(os.path.join(maps, "library-test-testDebugUnitTest-bcf7873c"))
        with open(os.path.join(maps, "library-test-testDebugUnitTest-bcf7873c", "coverage.tsv"),
                  "w", encoding="utf-8") as handle:
            handle.write("")
        nested = run.find_map(tmp, "library:test", "testDebugUnitTest")
        if not nested or os.path.basename(nested) != "library-test-testDebugUnitTest-bcf7873c":
            print(f"  FAIL: nested module map lookup found {nested!r}")
            failures += 1

        found = run.find_map(tmp, "m", "jvmTest")
        if not found or os.path.basename(found) != "m-jvmTest-abcd1234":
            print(f"  FAIL: jvmTest map lookup found {found!r}")
            failures += 1
        other = run.find_map(tmp, "m", "test")
        if not other or os.path.basename(other) != "m-test-99999999":
            print(f"  FAIL: test map lookup found {other!r}")
            failures += 1
        # A directory whose capture never completed carries no coverage.tsv and must not be
        # mistaken for a map: an unfinished capture is not an empty one.
        if run.find_map(tmp, "m", "integrationTest") is not None:
            print("  FAIL: a directory with no coverage.tsv was returned as a map")
            failures += 1
    return failures


def case_the_task_name_comes_from_the_project_file():
    """`test_task` names the task; every path and results directory derives from it."""
    failures = 0
    checks = (
        ({"module": "m"}, "test", ":m:test"),
        ({"module": "m", "test_task": "jvmTest"}, "jvmTest", ":m:jvmTest"),
        ({}, "test", "test"),
        ({"test_task": "jvmTest"}, "jvmTest", "jvmTest"),
    )
    for project, name, path in checks:
        if run.test_task_name(project) != name:
            print(f"  FAIL: {project} -> name {run.test_task_name(project)!r}, expected {name!r}")
            failures += 1
        if run.test_task_path(project) != path:
            print(f"  FAIL: {project} -> path {run.test_task_path(project)!r}, expected {path!r}")
            failures += 1
    # The compile warm-up must compile the measured source set, not `testClasses`.
    if run.test_classes_task({"test_task": "jvmTest"}) != "jvmTestClasses":
        print("  FAIL: the warm-up would compile the wrong source set")
        failures += 1
    return failures


def case_results_of_reads_a_nested_module_as_a_directory():
    """`library:test` is the directory `library/test`, not a directory called "library:test".

    Joining the Gradle path verbatim looks for a directory that cannot exist, so every read comes
    back empty and every sweep row is excluded as "ground truth never reached the tests" -- which
    reads like a build failure and is a path bug. glide's first sweep died this way.
    """
    import tempfile
    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        d = os.path.join(tmp, "library", "test", "build", "test-results", "testDebugUnitTest")
        os.makedirs(d)
        with open(os.path.join(d, "TEST-a.xml"), "w", encoding="utf-8") as handle:
            handle.write('<testsuite name="a" tests="2" failures="0" errors="0">'
                         '<testcase name="one" classname="com.acme.A"/>'
                         '<testcase name="two" classname="com.acme.A"/></testsuite>')
        ran, failed = run.results_of(tmp, "library:test", 0, "testDebugUnitTest", log_path=_NO_LOG,
                                     task_path=":library:test:testDebugUnitTest")
        if ran is None or len(ran) != 2:
            print(f"  FAIL: nested module results read as {ran!r}")
            failures += 1
    return failures


def case_a_stale_result_does_not_clear_a_miss():
    """The isolated re-run must not read a result that predates it.

    This is the ONE place in the harness where a stale read CLEARS a miss instead of creating one:
    with since=0 every file on disk counts as fresh, so a leftover result naming the class -- from an
    earlier mutation, or another source set sharing the name -- reads as "it passed alone" and
    discounts a genuine missed failure.

    Driven through the REAL results_of, not a fake. Every other order_dependent case fakes it with a
    lambda that ignores `since`, so none of them can tell since=started from since=0 and the guard
    could be removed without a single test going red.
    """
    import tempfile
    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        d = os.path.join(tmp, "m", "build", "test-results", "test")
        os.makedirs(d)
        xml = os.path.join(d, "TEST-com.acme.Foo.xml")
        with open(xml, "w", encoding="utf-8") as handle:
            handle.write('<testsuite name="com.acme.Foo" tests="1" failures="0" errors="0">'
                         '<testcase name="bar" classname="com.acme.Foo"/></testsuite>')
        stale = time.time() - 600
        os.utime(xml, (stale, stale))

        # A run starting NOW must not see that file.
        ran, _ = run.results_of(tmp, "m", time.time() - 60, "test", log_path=_NO_LOG,
                                task_path=":m:test")
        if ran is not None:
            print(f"  FAIL: a result 10 minutes older than the run was read as fresh: {ran}")
            failures += 1

        # And a fresh one is read, so the guard is not simply refusing everything.
        os.utime(xml, None)
        ran, _ = run.results_of(tmp, "m", time.time() - 60, "test", log_path=_NO_LOG,
                                task_path=":m:test")
        if ran is None or ("com.acme.Foo", "bar") not in ran:
            print(f"  FAIL: a fresh result was not read: {ran}")
            failures += 1
    return failures


def case_a_restore_that_cannot_run_git_is_reported_not_raised():
    """A glide sweep left a mutation in the checkout and told nobody that mattered.

    `git reset --hard` exited 3221225794 -- Windows' 0xC0000142, the process could not be created,
    because the machine was out of memory. git was not refusing; it was not starting. `check=True`
    then raised inside an atexit handler, which prints a traceback nobody reads, and the mutation
    stayed in the tree.

    The next measurement against that checkout would attribute this run's edit to whatever it was
    testing, which is the mockito AndroidPlatform.java story: six commits reported a changed class
    none of them touched, and the conclusion drawn was wrong.
    """
    import io as _io
    import contextlib
    import subprocess as _subprocess

    failures = 0
    calls = []

    class Failed:
        returncode = 3221225794
        stdout = ""
        stderr = "The process could not be created."

    real_run, real_sleep = _subprocess.run, time.sleep
    _subprocess.run = lambda *a, **k: (calls.append(a[0]), Failed())[1]
    time.sleep = lambda _s: None
    err = _io.StringIO()
    try:
        with contextlib.redirect_stderr(err):
            ok = run.restore("some/checkout", attempts=3)
    finally:
        _subprocess.run, time.sleep = real_run, real_sleep

    if ok is not False:
        print(f"  FAIL: a failed restore reported success: {ok!r}")
        failures += 1
    if len(calls) != 3:
        print(f"  FAIL: expected 3 attempts, made {len(calls)}")
        failures += 1
    text = err.getvalue()
    if "STILL MUTATED" not in text:
        print(f"  FAIL: the warning does not say the checkout is still mutated: {text[:120]!r}")
        failures += 1
    if "some/checkout" not in text:
        print("  FAIL: the warning does not name the checkout")
        failures += 1
    return failures


def case_a_restore_that_succeeds_stops_retrying():
    import subprocess as _subprocess

    failures = 0
    calls = []

    class Ok:
        returncode = 0
        stdout = stderr = ""

    real_run = _subprocess.run
    _subprocess.run = lambda *a, **k: (calls.append(a[0]), Ok())[1]
    try:
        ok = run.restore("some/checkout", attempts=4)
    finally:
        _subprocess.run = real_run

    if ok is not True:
        print(f"  FAIL: a clean restore did not report success: {ok!r}")
        failures += 1
    # One reset plus one clean, and no retry.
    if len(calls) != 2:
        print(f"  FAIL: expected reset+clean and no retry, got {len(calls)} calls")
        failures += 1
    return failures


def case_a_provisioned_toolchain_is_found_at_any_of_its_layouts():
    """Gradle unpacks a toolchain differently per platform, and macOS is two levels deeper.

    `~/.gradle/jdks/eclipse_adoptium-26-.../jdk-26.0.1+8/Contents/Home` -- a search that looked only
    at the top entry found no `release` file, so every provisioned toolchain on a Mac was invisible
    and ktlint was refused for wanting a JDK 26 that was unpacked on the disk. That refusal ended a
    whole corpus run.
    """
    import shutil
    import tempfile

    failures = 0
    root = tempfile.mkdtemp(prefix="yoriwake-jdk-tests-")
    try:
        layouts = {
            # (major, path under the root that should hold the `release` file)
            17: os.path.join("flat-17"),
            21: os.path.join("vendor-21", "jdk-21.0.8"),
            26: os.path.join("vendor-26", "jdk-26.0.1+8", "Contents", "Home"),
        }
        for major, relative in layouts.items():
            home = os.path.join(root, relative)
            os.makedirs(home, exist_ok=True)
            with open(os.path.join(home, "release"), "w", encoding="utf-8") as handle:
                handle.write(f'JAVA_VERSION="{major}.0.1"\n')

        original = os.path.expanduser
        os.path.expanduser = lambda path: root if path == "~/.gradle/jdks" else original(path)
        # A runner's own JDKs are named in the environment and found first, so the toolchain root
        # is only what this half reads when they are out of the way.
        runner_jdks = {name: os.environ.pop(name) for name in list(os.environ)
                       if re.fullmatch(r"JAVA_HOME_\d+_(?:X64|ARM64)", name)}
        try:
            for major, relative in layouts.items():
                found = run.find_jdk(major)
                expected = os.path.join(root, relative).replace("\\", "/")
                if found != expected:
                    print(f"  FAIL: JDK {major} at {relative} resolved to {found}")
                    failures += 1
            if run.find_jdk(99) is not None:
                print("  FAIL: a version nothing provides was reported as found")
                failures += 1
            # A CI runner names its JDKs in the environment rather than under a toolchain root.
            runner = os.path.join(tempfile.mkdtemp(prefix="yoriwake-runner-"), "25")
            os.makedirs(runner)
            with open(os.path.join(runner, "release"), "w", encoding="utf-8") as handle:
                handle.write('JAVA_VERSION="25.0.2"\n')
            os.environ["JAVA_HOME_25_X64"] = runner
            try:
                if run.find_jdk(25) != runner.replace("\\", "/"):
                    print(f"  FAIL: the runner's JDK 25 was not found: {run.find_jdk(25)}")
                    failures += 1
            finally:
                del os.environ["JAVA_HOME_25_X64"]
        finally:
            os.path.expanduser = original
            os.environ.update(runner_jdks)
    finally:
        shutil.rmtree(root, ignore_errors=True)
    return failures


def case_module_dir_falls_back_to_the_gradle_name():
    """Only Micronaut needs the two to differ; every other project must be unaffected."""
    failures = 0
    if run.module_dir({"module": "core"}) != "core":
        print("  FAIL: a project with no module_dir did not fall back to module")
        failures += 1
    if run.module_dir({"module": "micronaut-core", "module_dir": "core"}) != "core":
        print("  FAIL: module_dir did not win where it is set")
        failures += 1
    if run.module_dir({}) != "":
        print("  FAIL: a root-level project did not yield an empty directory")
        failures += 1
    return failures



def case_a_force_reason_is_read_from_the_line_the_plugin_prints():
    """The field this scrape fills was None in every row of every sweep ever run.

    It matched "[yoriwake] running everything:", which the plugin has never printed. The lines below are
    verbatim from real sweep logs -- micrometer's inline refusal and grpc-java's constants refusal --
    so a scrape that cannot read them is a scrape that cannot work.
    """
    import shutil
    import tempfile
    failures = 0
    scratch = tempfile.mkdtemp(prefix="yoriwake-reason-tests-")
    try:
        log = os.path.join(scratch, "selected.log")
        with open(log, "w", encoding="utf-8") as handle:
            handle.write(
                "> Task :micrometer-core:test\n"
                "[yoriwake] :micrometer-core:test map=/x/y scope=derived(io.micrometer.*) nolocation=true\n"
                "[yoriwake] :micrometer-core:test: the classes that inline the changed sources could not "
                "be read, so the whole suite runs. An inline body is copied into its call sites, and "
                "coverage cannot see that edge.\n"
                "[yoriwake] :micrometer-core:test selecting against 7efb0bcf (yoriwake.base): 1 changed classes\n"
                "[yoriwake] :micrometer-core:test: running everything, so this run also captures -- "
                "coverage costs the toll once here and buys a complete map.\n"
            )
        reason = run.full_run_reason(log, ":micrometer-core:test")
        if not reason or not reason.startswith("the classes that inline the changed sources"):
            print(f"  FAIL: the force reason was not read: {reason!r}")
            failures += 1
        # The capture decision shares the line shape and is NOT a force reason. Returning it would
        # be worse than returning nothing: every forced row would report the same benign sentence.
        if reason and reason.startswith("running everything"):
            print("  FAIL: the capture decision was returned as the force reason")
            failures += 1
    finally:
        shutil.rmtree(scratch, ignore_errors=True)
    return failures


def case_a_force_reason_is_scoped_to_the_measured_task():
    """A multi-module build reports several tasks; the row is about one of them."""
    import shutil
    import tempfile
    failures = 0
    scratch = tempfile.mkdtemp(prefix="yoriwake-reason-tests-")
    try:
        log = os.path.join(scratch, "selected.log")
        with open(log, "w", encoding="utf-8") as handle:
            handle.write(
                "[yoriwake] :other-module:test: some other module's reason for running everything.\n"
                "[yoriwake] :grpc-core:test: io.grpc.internal.GrpcUtil declares a compile-time constant "
                "whose value differs from the one the map was captured under. The whole suite runs.\n"
            )
        reason = run.full_run_reason(log, ":grpc-core:test")
        if not reason or "GrpcUtil" not in reason:
            print(f"  FAIL: the measured task's reason was not the one returned: {reason!r}")
            failures += 1
    finally:
        shutil.rmtree(scratch, ignore_errors=True)
    return failures


def case_a_run_that_never_forced_has_no_reason():
    """A narrowing run must report None rather than whatever prose it happened to print."""
    import shutil
    import tempfile
    failures = 0
    scratch = tempfile.mkdtemp(prefix="yoriwake-reason-tests-")
    try:
        log = os.path.join(scratch, "selected.log")
        with open(log, "w", encoding="utf-8") as handle:
            handle.write(
                "[yoriwake] :okhttp-tls:test map=/x/y scope=derived(okhttp3.*) nolocation=true\n"
                "[yoriwake] of 112 tests discovered: reaches-change=29 skipped=83\n"
            )
        reason = run.full_run_reason(log, ":okhttp-tls:test")
        if reason is not None:
            print(f"  FAIL: a narrowing run reported a force reason: {reason!r}")
            failures += 1
    finally:
        shutil.rmtree(scratch, ignore_errors=True)
    return failures


def case_capture_openers_still_match_the_plugin():
    """The contract this scrape rests on, pinned against the plugin's own source.

    The bug being fixed was a harness matching a string the plugin never printed, and nothing
    noticed for the life of the corpus. A prose contract between two languages needs a test that
    breaks when one side is reworded, which is what this is: it reads the Kotlin and asserts the
    openers are still there. If someone rewords a capture reason, this fails and points at the
    scrape, instead of the scrape quietly reporting the capture decision on every forced row.
    """
    failures = 0
    root = run.SOURCE
    source = os.path.join(root, "plugin", "src", "main", "kotlin", "io", "github", "zeuspizza", "yoriwake", "gradle",
                          "capture", "CaptureDecision.kt")
    try:
        with open(source, encoding="utf-8") as handle:
            text = handle.read()
    except OSError as problem:
        print(f"  FAIL: could not read the plugin source that defines the contract ({problem})")
        return 1
    for opener in run.CAPTURE_REASON_OPENERS:
        if opener not in text:
            print(f"  FAIL: the plugin no longer prints {opener!r}; full_run_reason would now "
                  "return the capture decision as a force reason")
            failures += 1

    # AND THE OTHER DIRECTION, which is the one that actually failed. Asserting that each opener is
    # still in the plugin stays true while the plugin grows a capture reason the tuple does NOT
    # have -- which is exactly what happened: `decideCapture` has three arms, this tuple listed two,
    # and the missing one was published as `full_run_reason` on 119 of the 254 rows of the
    # one corpus sweep, every one of which had narrowed. A guard that only checks the direction
    # that cannot break is not a guard.
    block = re.search(r"val reason = when \{(.*?)\n    \}", text, re.DOTALL)
    if not block:
        print("  FAIL: could not find decideCapture's reason block; this contract is unpinned")
        return failures + 1
    arms = re.findall(r'->\s*\n?\s*"((?:[^"\\]|\\.)*)"', block.group(1))
    if not arms:
        print("  FAIL: decideCapture's reason block yielded no strings to check")
        failures += 1
    for arm in arms:
        if not arm.startswith(run.CAPTURE_REASON_OPENERS):
            print(f"  FAIL: decideCapture can return {arm[:50]!r}, which no opener matches; "
                  "full_run_reason will publish it as a reason a run executed everything")
            failures += 1
    return failures


def case_a_retired_marker_on_a_stored_row_is_still_not_a_force_reason():
    """A row written while the plugin still printed the line must read as it did then."""
    line = ("[yoriwake] :glide:library:testDebugUnitTest: class-granular selection is EXPERIMENTAL "
            "and known to skip failing tests on JUnit 4 suites whose tests run other tests.")
    if run.is_force_reason(line):
        print("  FAIL: a stored row's retired warning now reads as a run that declined to narrow")
        return 1
    return 0


def case_a_task_off_the_platform_running_everything_is_not_a_force_reason():
    """The plugin's line for a task off the JUnit Platform carries no `<task>: `, so no scrape reads it.

    Off the Platform every test runs by design, which is not a refusal; a rewording that added the
    colon would start reading as one, so the line is pinned here as the plugin prints it.
    """
    line = "[yoriwake] :app:test does not run on the JUnit Platform, so selection runs every test.\n"
    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        log = os.path.join(tmp, "select.log")
        with open(log, "w", encoding="utf-8") as handle:
            handle.write(line)
        for task_path in (":app:test", None):
            if run.full_run_reason(log, task_path) is not None:
                print(f"  FAIL: the line for a task off the Platform read as a force reason (task {task_path})")
                failures += 1
    source = os.path.join(run.SOURCE, "plugin", "src", "main", "kotlin", "io", "github", "zeuspizza", "yoriwake",
                          "gradle", "wiring", "TestTaskWiring.kt")
    with open(source, encoding="utf-8") as handle:
        text = handle.read()
    if "does not run on the JUnit Platform, so selection" not in text:
        print("  FAIL: the plugin no longer prints the line this case pins; re-read how it scrapes")
        failures += 1
    return failures


def case_non_force_markers_still_match_the_plugin():
    """The other lines the run path prints through `[yoriwake] <task>: `, pinned to the plugin's source.

    `full_run_reason` scrapes a general-purpose prefix, so it catches whatever the plugin last said
    about the task. Reading a row that predates the recorded decision outcome means subtracting the
    lines that are not refusals, and a reworded one would silently start reading as a force reason
    again -- which is how 81% of the corpus's narrowed rows came to be classified as full runs.
    """
    failures = 0
    root = run.SOURCE
    source = os.path.join(root, "plugin", "src", "main", "kotlin", "io", "github", "zeuspizza", "yoriwake", "gradle",
                          "wiring", "SelectionWiring.kt")
    try:
        with open(source, encoding="utf-8") as handle:
            text = handle.read()
    except OSError as problem:
        print(f"  FAIL: could not read the plugin source that defines the contract ({problem})")
        return 1
    for marker in run.NON_FORCE_REASON_MARKERS:
        if marker not in text:
            print(f"  FAIL: the plugin no longer prints {marker!r}; a row carrying it would be "
                  "read as a run that declined to narrow")
            failures += 1
    return failures


def case_a_capture_sentence_is_not_a_force_reason():
    """The classification `assert_recall` makes about a row with no recorded decision outcome."""
    failures = 0
    forcing = ("p.Thing declares a compile-time constant whose value differs from the one the map "
               "was captured under.")
    if not run.is_force_reason(forcing):
        print("  FAIL: a genuine constant refusal was not read as a force reason")
        failures += 1
    for not_forcing in (
        "narrowing, so nothing is instrumented and the map is left alone. A partial run cannot",
        "running everything, so this run also captures -- coverage costs the toll once here",
        "3 class(es) inlined the changed sources and are selected with them",
        "class-granular selection is EXPERIMENTAL and known to skip failing tests",
        None,
        "",
    ):
        if run.is_force_reason(not_forcing):
            print(f"  FAIL: {str(not_forcing)[:52]!r} was read as a reason the run executed "
                  "everything")
            failures += 1
    return failures


def case_the_same_seed_gives_the_same_order():
    """Reproducible from the results file alone, or the corpus trades one distortion for a worse one."""
    failures = 0
    targets = [(f"src/main/java/p/C{n}.java", "body", 0) for n in range(30)]
    reach = {f"p.C{n}": n + 1 for n in range(30)}
    first = target_ordering.by_reach(targets, reach, seed=7)
    second = target_ordering.by_reach(targets, reach, seed=7)
    if first != second:
        print("  FAIL: the same seed gave two different orders")
        failures += 1
    if target_ordering.by_reach(targets, reach, seed=8) == first:
        print("  FAIL: two seeds gave the same order; the seed is not reaching the order")
        failures += 1
    if sorted(first) != sorted(targets):
        print("  FAIL: by_reach dropped or invented targets; it orders, it does not filter")
        failures += 1
    return failures


def case_targets_span_reach_rather_than_clustering_at_one_end():
    """The bias in every recall and narrowing figure published before the fixed draw.

    Sorted paths start with `Abstract*`, which are hubs, and a mutation on a hub forces a full run --
    so recall came back perfect and narrowing came back terrible, on every project, systematically.
    """
    failures = 0
    # Alphabetically, every `Abstract*` comes first; by reach, they are the busiest classes.
    targets = ([(f"src/main/java/p/Abstract{n}.java", "body", 0) for n in range(10)]
               + [(f"src/main/java/p/Leaf{n}.java", "body", 0) for n in range(10)])
    reach = {f"p.Abstract{n}": 500 + n for n in range(10)}
    reach.update({f"p.Leaf{n}": 1 + n for n in range(10)})

    first_six = target_ordering.by_reach(targets, reach)[:6]
    hubs = sum(1 for path, _, _ in first_six if "Abstract" in path)
    if hubs == len(first_six):
        print(f"  FAIL: the sample is still all hubs: {first_six}")
        failures += 1
    if hubs == 0:
        print(f"  FAIL: the sample excludes hubs, which is the same bias inverted: {first_six}")
        failures += 1
    return failures


def case_no_map_leaves_the_order_alone():
    """An unread map is unknown, and inventing strata from one is the empty-is-not-unknown defect."""
    failures = 0
    targets = [(f"src/main/java/p/C{n}.java", "body", 0) for n in range(5)]
    if target_ordering.by_reach(targets, {}) != targets:
        print("  FAIL: an absent map changed the order, so it was treated as 'nothing is reached'")
        failures += 1
    # A map that reaches nothing this sweep would mutate is the same situation one layer in.
    if target_ordering.by_reach(targets, {"p.Unrelated": 4}) != targets:
        print("  FAIL: a map naming none of the targets still reordered them")
        failures += 1
    return failures


def case_reach_counts_a_kotlin_file_class():
    """`FooKt` and `Foo$Nested` are the same source file, and reading the bare name misses both."""
    failures = 0
    reach = {"p.FooKt": 3, "p.Foo$Inner": 2, "p.Bar": 5}
    if target_ordering.reach_of("p.Foo", reach) != 5:
        print(f"  FAIL: Kotlin file classes were not counted: {target_ordering.reach_of('p.Foo', reach)}")
        failures += 1
    if target_ordering.reach_of(None, reach) != 0:
        print("  FAIL: a path that names no class must reach nothing, not raise")
        failures += 1
    return failures


def case_an_existing_results_file_is_still_readable():
    """The ordering is recorded, and a file written before it existed still parses."""
    failures = 0
    import json
    old = json.loads('{"project": "p", "mutations": [], "plugin_commit": "abc"}')
    if old.get("target_ordering") is not None:
        print("  FAIL: an older results file should report no ordering, not a wrong one")
        failures += 1
    if target_ordering.ORDERING == "":
        print("  FAIL: the ordering must have a name, or a seed cannot be told from the old order")
        failures += 1
    return failures


def case_an_identity_hash_is_not_a_missed_test():
    """The false recall failure, and the reason this comparison is not plain set subtraction.

    armeria's GrpcServiceServerTest is parameterized on an object whose display name carries
    `Object.toString`'s identity hash, so the ground-truth run and the selected run name the SAME
    test differently. Subtracting one name set from the other published "MISSED 2" about two tests
    that had run, in a row where 470 of 698 were selected.
    """
    failures = 0
    induced = {("C", "[1] client=Stub@7b50d62"), ("C", "[2] client=Stub@2a45c12")}
    ran = {("C", "[1] client=Stub@67534eeb"), ("C", "[2] client=Stub@6a820abf")}
    missed, undetermined, _ = run.unmatched(induced, ran, _NEVER_SHARED)
    if missed:
        print(f"  FAIL: an identity hash was read as a skipped test: {missed}")
        failures += 1
    if undetermined:
        print(f"  FAIL: an unambiguous match should be a match, not a hole: {undetermined}")
        failures += 1
    return failures


def case_a_genuinely_missed_test_is_still_missed():
    """The guard must not learn to say zero. A stable name that did not run is a miss, as before."""
    failures = 0
    missed, undetermined, _ = run.unmatched({("C", "shouldWork"), ("C", "[1] x=Stub@7b50d62")},
                                            {("C", "[1] x=Stub@aabbccdd")}, _NEVER_SHARED)
    if missed != [("C", "shouldWork")]:
        print(f"  FAIL: a real miss was lost: missed={missed} undetermined={undetermined}")
        failures += 1
    return failures


def case_an_unmatchable_id_is_undetermined_not_clean():
    """Never missed and NEVER CLEAN -- the polarity order-exposure analysis drew."""
    failures = 0
    # No counterpart at all.
    _, undetermined, _ = run.unmatched({("C", "[1] x=Stub@7b50d62")},
                                       {("C", "[9] x=Stub@aabbccdd")}, _NEVER_SHARED)
    if not undetermined:
        print("  FAIL: an induced failure with no counterpart was silently cleared")
        failures += 1
    # Two counterparts: clearing on a collision is the dangerous direction of the same defect.
    _, ambiguous, _ = run.unmatched({("C", "[1] x=Stub@7b50d62")},
                                    {("C", "[1] x=Stub@aabbccdd"), ("C", "[1] x=Stub@11223344")},
                                    _NEVER_SHARED)
    if not ambiguous:
        print("  FAIL: an ambiguous normalisation cleared a miss")
        failures += 1
    return failures


# The class shape the per-method keys exist for: one class whose two templates are invoked under method-less display names, so
# every invocation of `a()` and `b()` shares a JUnit XML key -- iceberg's `TestMetadataTableScans`
# has 66 methods on 4 keys. The record kept `a()` and excluded `b()`.
_P = "p.P"
_V1, _V2 = (_P, "v = 1"), (_P, "v = 2")
_ENGINE = "[engine:junit-jupiter]"


def _record(included=(), excluded=(), outcome="narrowed"):
    """A decision record as the reader returns it, from the ids it decided."""
    decided = ([(test, "included/COVERS_CHANGE") for test in included]
               + [(test, "excluded/NO_EDGE") for test in excluded])
    return {"notes": {"outcome": outcome}, "included": list(included),
            "verdicts": Counter(verdict for _, verdict in decided), "decided": decided,
            "rules": None}


def _template(cls, method, kind="test-template"):
    return f"{_ENGINE}/[class:{cls}]/[{kind}:{method}]"


def _plain(cls, method):
    return f"{_ENGINE}/[class:{cls}]/[method:{method}]"


_P_NARROWED = _record(included=[_template(_P, "a()")], excluded=[_template(_P, "b()")])


def _xml_keys(record):
    """A row's keys on the XML scheme, judged against `record` (None: absent or untrusted)."""
    return run.RowKeys(run.XML, record)


def _method_keys(record, collapsed=()):
    return run.RowKeys(run.PER_METHOD, record, collapsed)


# A row whose trusted record ran everything, and a comparison where no key is shared.
_FORCED = _record(included=[_plain(_P, "c()")], outcome="full-run")
_NEVER_SHARED = _xml_keys(_FORCED).shared
_A1, _A2, _B1 = (_P, "a() > v = 1"), (_P, "a() > v = 2"), (_P, "b() > v = 1")


def case_a_sibling_on_a_shared_key_does_not_clear_a_miss():
    """`b()` fails at `v = 1` and was excluded; `a()` ran under the same XML key."""
    failures = 0
    # Per method the two are different keys, so the failure did not run: missed.
    missed, _, shared = run.unmatched({_B1}, {_A1, _A2}, _method_keys(_P_NARROWED).shared)
    if missed != [_B1] or shared:
        failures += fail(f"per-method keys did not find the miss: missed={missed} "
                         f"shared_key={shared}")
    # On XML keys a selected sibling ran it, so it is neither caught nor missed.
    found = run.unmatched({_V1}, {_V1, _V2}, _xml_keys(_P_NARROWED).shared)
    if found != ([], [], [_V1]):
        failures += fail(f"an excluded method's failure was cleared by a selected sibling's key: "
                         f"(missed, undetermined, shared_key) = {found}")
    return failures


def case_a_failing_method_that_ran_is_still_caught():
    """A control: the record kept both methods, so the failing one ran and the row caught it."""
    both = _record(included=[_template(_P, "a()"), _template(_P, "b()")])
    found = run.unmatched({_V1}, {_V1, _V2}, _xml_keys(both).shared)
    if any(found):
        return fail(f"a failure whose method ran was not caught: {found}")
    return 0


def case_a_plain_method_row_is_unchanged():
    """A control: a plain method that ran clears its failure, and one that did not is a miss."""
    record = _record(included=[_plain(_P, "c()")], excluded=[_plain(_P, "d()")])
    found = run.unmatched({(_P, "c"), (_P, "d")}, {(_P, "c")}, _xml_keys(record).shared)
    if found[0] != [(_P, "d")] or any(found[1:]):
        return fail(f"a plain-method row changed: {found}")
    return 0


def case_collapsed_counts_do_not_make_a_narrowed_row_whole():
    """plain `c` was excluded and failed; the collapsed keys make both arms count the same."""
    failures = 0
    record = _record(included=[_template(_P, "a()")],
                     excluded=[_template(_P, "b()"), _plain(_P, "c()")])
    kept = run.misses_a_row_that_skipped_nothing_cannot_have([(_P, "c")], {_V1, _V2}, {_V1, _V2},
                                                              record)
    if kept != [(_P, "c")]:
        failures += fail(f"a narrowed row's miss was dropped as a discovery difference: {kept}")
    # A record that excluded no leaf is the case the rule exists for, and it still drops the miss.
    whole = _record(included=[_template(_P, "a()"), _template(_P, "b()"), _plain(_P, "c()")])
    if run.misses_a_row_that_skipped_nothing_cannot_have([(_P, "c")], {_V1}, {_V1}, whole):
        failures += fail("a record that excluded nothing kept an apparent miss")
    # no trusted record cannot vouch that nothing was excluded.
    if run.misses_a_row_that_skipped_nothing_cannot_have([(_P, "c")], {_V1}, {_V1}, None) \
            != [(_P, "c")]:
        failures += fail("a row with no trusted record dropped its misses")
    return failures


def case_a_baseline_flake_on_a_sibling_s_key_is_not_subtracted():
    """`a()` flakes at `v = 1` on the unmutated tree; the mutation fails the excluded `b()`."""
    failures = 0
    if _V1 not in run.subtract_baseline({_V1}, run.Outcomes(None, {_V1}), _xml_keys(_P_NARROWED)):
        failures += fail("a failure was subtracted as already failing because a sibling flaked on "
                         "its key")
    # Per method the flake and the failure are two keys, so the subtraction is exact.
    baseline = run.Outcomes(None, {_V1}, run.Methods(None, {_A1}))
    if run.subtract_baseline({_B1}, baseline, _method_keys(_P_NARROWED)) != {_B1}:
        failures += fail("per-method keys did not separate the flake from the failure")
    # And a test that genuinely fails unmutated is still subtracted, on either scheme.
    if run.subtract_baseline({_B1}, run.Outcomes(None, {_V1}, run.Methods(None, {_B1})),
                             _method_keys(_P_NARROWED)):
        failures += fail("a per-method failure on the unmutated tree was counted as induced")
    plain = run.Outcomes(None, {(_P, "c")})
    if run.subtract_baseline({(_P, "c")}, plain, _xml_keys(_FORCED)):
        failures += fail("a plain test failing unmutated was counted as induced")
    return failures


def case_a_re_run_without_the_listener_compares_on_xml_keys_and_says_so():
    """one re-run build left no listener marker. That re-run reads XML keys and the row is
    stamped `xml` naming it; the per-method answer the arms gave stands."""
    failures = 0
    keys = _method_keys(_P_NARROWED)
    missed, _, _ = run.unmatched({_B1}, {_A1, _A2}, keys.shared)
    plain = run.Outcomes({_V1, _V2}, {_V1}, cause="no listener marker")
    verdict = isolation.outcome_alone(
        "checkout", {"name": "p"}, "m", *_B1, ".", "stem", gradle=_FakeGradle(1),
        results_of=lambda *a: plain, task="test", keys=keys)
    # `b() > v = 1` is `v = 1` on XML keys, which `a()` shares in this mixed class: no answer.
    if verdict != isolation.UNKNOWN:
        failures += fail(f"a re-run on a shared XML key answered {verdict}")
    if keys.stamp != run.XML or "no listener marker" not in (keys.fallback or ""):
        failures += fail(f"the row was not stamped from the re-run: {keys.stamp} "
                         f"{keys.fallback!r}")
    if missed != [_B1]:
        failures += fail(f"the arms' per-method answer moved: {missed}")
    # A complete re-run answers per method, and a sibling's failure there is a sibling's.
    complete = run.Outcomes({_V1, _V2}, {_V1}, run.Methods({_A1, _B1}, {_A1}))
    keys = _method_keys(_P_NARROWED)
    verdict = isolation.outcome_alone(
        "checkout", {"name": "p"}, "m", *_B1, ".", "stem", gradle=_FakeGradle(1),
        results_of=lambda *a: complete, task="test", keys=keys)
    if verdict != isolation.PASSES_ALONE or keys.stamp != run.PER_METHOD:
        failures += fail(f"a complete re-run answered {verdict}, stamped {keys.stamp}")
    return failures


def case_paired_ground_truth_keeps_its_own_scheme():
    """a paired row is per-method ground truth only when its file says it was."""
    failures = 0
    selected = run.Outcomes({_V1, _V2}, set(), run.Methods({_A1, _A2}, set()))
    old = {"target": "T", "full_distinct": 4, "induced": ["p.P::v = 1"]}
    keys = run.row_keys(selected, run.paired_outcomes(old), _P_NARROWED, "paired ground truth")
    if keys.scheme != run.XML or "paired ground truth" not in (keys.fallback or ""):
        failures += fail(f"a paired row with no result_keys was read per method: {keys.scheme} "
                         f"{keys.fallback!r}")
    # A per-method paired failure holding an escaped separator projects back exactly.
    tricky = run.method_key(_P, ["t(int)"], "x > y")
    paired = run.paired_outcomes({"target": "T", "full_distinct": 4, "result_keys": run.PER_METHOD,
                                  "induced": [f"{tricky[0]}::{tricky[1]}"],
                                  "collapsed_induced": []})
    if paired.methods.failed != {tricky} or paired[1] != {(_P, "x > y")}:
        failures += fail(f"a paired per-method failure: {paired.methods.failed} -> {paired[1]}")
    if len(paired[0]) != 4:
        failures += fail("the paired full arm's count was not carried")
    keys = run.row_keys(selected, paired, _P_NARROWED, "paired ground truth")
    if keys.scheme != run.PER_METHOD:
        failures += fail(f"a per-method pair was compared on {keys.scheme} ({keys.fallback})")
    return failures


def case_a_paired_collapse_the_selected_arm_cannot_see_is_still_shared():
    """The full arm saw two methods on one display name; the isolated arm ran only one of them, so
    its own outcomes show no collapse. The paired row must carry the collapse, or the selected
    sibling clears the excluded method's failure."""
    failures = 0
    selected = run.Outcomes({_V1}, set(), run.Methods({_V1}, set()))
    paired = {"target": "T", "full_distinct": 4, "result_keys": run.PER_METHOD,
              "induced": [f"{_P}::v = 1"], "collapsed_induced": [f"{_P}::v = 1"]}
    keys = run.row_keys(selected, run.paired_outcomes(paired), _P_NARROWED, "paired ground truth")
    found = run.unmatched({_V1}, {_V1}, keys.shared)
    if found != ([], [], [_V1]):
        failures += fail(f"a paired collapsed key was cleared by the selected sibling: {found}")
    # A per-method paired row from before the field cannot say what collapsed: XML keys, named.
    older = {k: v for k, v in paired.items() if k != "collapsed_induced"}
    keys = run.row_keys(selected, run.paired_outcomes(older), _P_NARROWED, "paired ground truth")
    if keys.scheme != run.XML or "collapsed" not in (keys.fallback or ""):
        failures += fail(f"a paired row without its collapsed keys was read per method: "
                         f"{keys.scheme} {keys.fallback!r}")
    return failures


def case_failing_union_keeps_methods_only_when_every_build_has_them():
    """The baseline's union per method, and its drop to XML keys when any build lacks them."""
    failures = 0
    one = run.Outcomes(set(), {_V1}, run.Methods(set(), {_A1}, {(_P, "x")}))
    two = run.Outcomes(set(), {_V2}, run.Methods(set(), {_A2}))
    union = run.failing_union([one, two])
    if union[1] != {_V1, _V2} or union.methods is None or union.methods.failed != {_A1, _A2} \
            or union.methods.collapsed != {(_P, "x")}:
        failures += fail("the per-method union of two complete builds is wrong")
    union = run.failing_union([one, run.Outcomes(set(), {_V2}, cause="no listener marker")])
    if union.methods is not None or union.cause != "no listener marker" or union[1] != {_V1, _V2}:
        failures += fail(f"an incomplete build did not drop the union to XML keys: {union.cause}")
    return failures


def case_an_incomplete_re_run_on_an_attributable_key_answers_on_xml_keys():
    """A re-run without the listener still answers for a key no sibling shares, on its XML key."""
    plain = (_P, "c")
    keys = _method_keys(_record(included=[_plain(_P, "d()")], excluded=[_plain(_P, "c()")]))
    verdict = isolation.outcome_alone(
        "checkout", {"name": "p"}, "m", *plain, ".", "stem", gradle=_FakeGradle(1),
        results_of=lambda *a: run.Outcomes({plain}, {plain}, cause="no listener file"),
        task="test", keys=keys)
    if verdict != isolation.FAILS_ALONE or keys.stamp != run.XML:
        return fail(f"an incomplete re-run on an attributable key answered {verdict}, "
                    f"stamped {keys.stamp}")
    return 0


def case_a_collapsed_per_method_key_is_never_subtracted():
    """Per method, a collapsed key flaking on the unmutated tree is no evidence about the failure."""
    baseline = run.Outcomes(None, {_V1}, run.Methods(None, {_V1}))
    if run.subtract_baseline({_V1}, baseline, _method_keys(_P_NARROWED, collapsed={_V1})) != {_V1}:
        return fail("a collapsed per-method key was subtracted as already failing")
    return 0


def case_a_collapsed_per_method_key_is_shared_and_a_display_name_is_not():
    """On per-method keys: an engine that reports invocations under their class gives
    a collapsed key, which names no single method; a Spock feature or a `@DisplayName` method that
    the listener saw once per name is attributable without reading the record."""
    failures = 0
    keys = _method_keys(_P_NARROWED, collapsed={_V1})
    if not keys.shared(_V1):
        failures += fail("a collapsed key in a mixed class was attributable")
    if keys.shared((_P, "my readable name")):
        failures += fail("a display-named method seen once was read as shared")
    if keys.shared(_B1):
        failures += fail("a key with a template segment was read as shared")
    whole = _method_keys(_record(included=[_template(_P, "a()"), _template(_P, "b()")]), {_V1})
    if whole.shared(_V1):
        failures += fail("a collapsed key in a class the record kept whole was read as shared")
    return failures


def case_attribution_reads_every_record_shape():
    """On XML keys: the key's name must be exactly one plain method of its class in the record.
    Vintage, nested and parameterised Jupiter methods are plain; a template, a Spock feature and a
    Kotest leaf are not, so on a narrowed mixed class their failures are `shared_key`."""
    failures = 0
    vintage = "[engine:junit-vintage]/[runner:p.V]/[test:m(p.V)]"
    # A JUnit 4 `Parameterized` test, as the record spells it: percent-encoded brackets.
    parameterized = ("[engine:junit-vintage]/[runner:p.V]/[test:%5BUtf8%5D]/"
                     "[test:next%5BUtf8%5D(p.V)]")
    nested = f"{_ENGINE}/[class-template:p.Outer]/[nested-class:Inner]/[method:n()]"
    plain_nested = f"{_ENGINE}/[class:p.Plain]/[nested-class:Inner]/[method:o()]"
    param = f"{_ENGINE}/[class:p.J]/[method:k(org.junit.jupiter.api.TestInfo, int)]"
    spock = "[engine:spock]/[spec:p.S]/[feature:$spock_feature_0_1]"
    kotest = "[engine:kotest]/[spec:p.K]/[test:a context]/[test:a leaf]"
    others = {cls: f"{_ENGINE}/[class:{cls}]/[method:other()]"
              for cls in ("p.V", "p.Outer$Inner", "p.Plain$Inner", "p.J", "p.S", "p.K")}
    record = _record(included=[vintage, parameterized, nested, plain_nested, param, spock, kotest],
                     excluded=list(others.values()))
    keys = _xml_keys(record)
    for test, shared in ((("p.V", "m"), False), (("p.V", "next[Utf8]"), False),
                         (("p.Outer$Inner", "n"), False), (("p.Plain$Inner", "o"), False),
                         (("p.J", "k(TestInfo, int)"), False), (("p.S", "a feature"), True),
                         (("p.K", "a leaf"), True), (("p.Missing", "x"), True)):
        if keys.shared(test) is not shared:
            failures += fail(f"{test} read as {'shared' if not shared else 'attributable'}")
    # A forced row cannot narrow, so nothing on it is shared, whatever the key.
    if _xml_keys(_record(included=[spock], outcome="full-run")).shared(("p.S", "a feature")):
        failures += fail("a forced row's failure was read as shared")
    # No trusted record: every XML key is unattributable, so every one is shared.
    if not _xml_keys(None).shared(("p.V", "m")):
        failures += fail("a row with no trusted record cleared a failure on an XML key")
    return failures


def case_an_old_baseline_cache_weakens_only_a_row_it_can_change():
    """a cache written before per-method keys is still reused. On a row where no failure's XML
    key is in it the subtraction cannot change an answer, and the row stays per-method; where one
    is, that subtraction is an XML one and the row says the cache is why."""
    failures = 0
    with tempfile.TemporaryDirectory() as directory:
        path = os.path.join(directory, "baseline-cache.json")
        key = {"commit": "c"}
        with open(path, "w", encoding="utf-8") as handle:
            json.dump({"key": key, "repeats": 2, "already_failing": [["p.Q", "flaky"]]}, handle)
        cached = run.read_baseline_cache(path, key, 2)
        keys = _method_keys(_P_NARROWED)
        if run.subtract_baseline({_B1}, cached, keys) != {_B1} or keys.stamp != run.PER_METHOD:
            failures += fail(f"an old cache that cannot change the row weakened it: {keys.fallback}")
        keys = _method_keys(_FORCED)
        if run.subtract_baseline({(_P, "a() > c")}, run.Outcomes(None, {(_P, "c")},
                                                                 cause=cached.cause), keys) \
                != set() or keys.stamp != run.XML or "cache" not in (keys.fallback or ""):
            failures += fail(f"an old cache that changes the row: stamp={keys.stamp} "
                             f"cause={keys.fallback!r}")
        # And a cache written now keeps its per-method set.
        run.write_baseline_cache(path, key, 2, run.Outcomes(None, {_V1},
                                                            run.Methods(None, {_A1})))
        again = run.read_baseline_cache(path, key, 2)
        if again.methods is None or again.methods.failed != {_A1}:
            failures += fail("a per-method baseline cache was not read back per method")
    return failures


def case_a_class_the_record_does_not_list_is_shared_on_a_narrowed_row():
    """a failure in a class the record never decided cannot be attributed, so on a narrowed
    XML row it is `shared_key`; on a forced row nothing moves."""
    failures = 0
    absent = ("p.Elsewhere", "v = 1")
    if run.unmatched({absent}, {absent}, _xml_keys(_P_NARROWED).shared) != ([], [], [absent]):
        failures += fail("a failure in an unlisted class was cleared on a narrowed row")
    if run.unmatched({absent}, {absent}, _NEVER_SHARED) != ([], [], []):
        failures += fail("a forced row's failure was moved")
    return failures


def case_the_gap_counts_leaf_ids_not_containers():
    """a daemon-refused row records its engine, class and runner containers as included, so
    the record-against-arm gap counts leaves only; a Vintage record's intermediate `[test:]`
    suites are containers too."""
    failures = 0
    engine, cls = _ENGINE, f"{_ENGINE}/[class:{_P}]"
    refused = _record(included=[engine, cls, _template(_P, "a()"), _template(_P, "b()"),
                                _plain(_P, "c()")], outcome="full-run")
    fields = run.decision_fields(refused, 3)
    if fields["decision_included_leaves"] != 3 or fields["decision_included"] != 5:
        failures += fail(f"leaves {fields['decision_included_leaves']}, included "
                         f"{fields['decision_included']}: containers were counted as tests")
    suite = "[engine:junit-vintage]/[runner:p.Suite]/[test:p.Inner]"
    vintage = _record(included=["[engine:junit-vintage]", "[engine:junit-vintage]/[runner:p.Suite]",
                                suite, f"{suite}/[test:m(p.Inner)]", f"{suite}/[test:n(p.Inner)]"])
    if run.decision_fields(vintage, 2)["decision_included_leaves"] != 2:
        failures += fail("a Vintage record's intermediate suites were counted as tests")
    if run.decision_fields(None, 2)["decision_included_leaves"] is not None:
        failures += fail("a row with no record counted leaves")
    return failures


def case_a_per_method_failure_names_its_template_s_rules():
    """`b() > v = 1` is the template `b()` the record decided, so its rules are `b()`'s and
    the failure no longer reads as one the record never saw."""
    failures = 0
    rules = {_template(_P, "b(int)"): ("covers-change",), _template(_P, "a(int)"): ("other",)}
    got = sweep_data.induced_rules({(_P, "b(int) > v = 1")}, rules)
    if got != {f"{_P}::b(int) > v = 1": ["covers-change"]}:
        failures += fail(f"a per-method failure's rules: {got}")
    # A feature's segment carries no parentheses, so the key's first segment is what names it.
    feature = "[engine:spock]/[spec:p.S]/[feature:reads a value]"
    got = sweep_data.induced_rules({("p.S", "reads a value > value (1)")},
                                   {feature: ("covers-change",)})
    if got != {"p.S::reads a value > value (1)": ["covers-change"]}:
        failures += fail(f"a feature's iteration did not reach the feature's rules: {got}")
    # A plain method's key is the method, as before.
    got = sweep_data.induced_rules({(_P, "c")}, {_plain(_P, "c()"): ("covers-change",)})
    if got != {f"{_P}::c": ["covers-change"]}:
        failures += fail(f"a plain method's rules: {got}")
    return failures


def case_re_runs_answered_by_a_sibling_do_not_clear_a_shared_key_miss():
    """the class re-run answers `v = 1` from whichever sibling ran, so a flaky `a()` reads as
    a contradiction and would move a real miss to `nondeterministic`."""
    results = _ScriptedResults(({_V1}, {_V1}), ({_V1}, set()), ({_V1}, set()))
    _, unrepeatable = isolation.order_dependent(
        "checkout", {"name": "p"}, "m", [_V1], ".", "stem", None,
        gradle=_FakeGradle(), results_of=results, backup_map=run.backup_map,
        restore_map=run.restore_map, task="test", keys=_xml_keys(_P_NARROWED))
    if unrepeatable:
        return fail(f"a shared-key miss was reclassified by its siblings' re-runs: {unrepeatable}")
    return 0


def case_a_sibling_failing_unmutated_does_not_set_a_miss_aside():
    """The control arm runs the class with the change reverted; a sibling failing there under the
    same key is no evidence that the excluded method fails without the change."""
    unattributable = isolation.fails_without_the_change(
        "checkout", {"name": "p"}, "m", [_V1], ".", "stem", None,
        gradle=_FakeGradle(1, 1, 1), results_of=lambda *a: ({_V1, _V2}, {_V1}),
        backup_map=run.backup_map, restore_map=run.restore_map, task="test",
        keys=_xml_keys(_P_NARROWED))
    if unattributable:
        return fail(f"a shared-key miss was set aside by a sibling failing unmutated: "
                    f"{unattributable}")
    return 0


# The record excluded both templates of `p.P`: the selected arm ran none of it and cannot clear its
# failures, but the baseline, the re-runs and the control arm each read `v = 1` from another build,
# where either template may have produced it.
_P_EXCLUDED = _record(excluded=[_template(_P, "a()"), _template(_P, "b()")])


def case_a_baseline_flake_in_a_class_excluded_whole_is_not_subtracted():
    """`a()` flakes at `v = 1` on the unmutated tree; the mutation fails `b()`, both excluded."""
    failures = 0
    if _V1 not in run.subtract_baseline({_V1}, run.Outcomes(None, {_V1}), _xml_keys(_P_EXCLUDED)):
        failures += fail("a failure in a class excluded whole was subtracted because a sibling "
                         "flaked on its key")
    baseline = run.Outcomes(None, {_V1}, run.Methods(None, {_V1}))
    if run.subtract_baseline({_V1}, baseline, _method_keys(_P_EXCLUDED, collapsed={_V1})) != {_V1}:
        failures += fail("a collapsed per-method key in a class excluded whole was subtracted")
    # Kept whole, the failing test ran in the selected arm: refusing the subtraction would count an
    # already-failing key as caught, so it is subtracted as before.
    both = _record(included=[_template(_P, "a()"), _template(_P, "b()")])
    if run.subtract_baseline({_V1}, run.Outcomes(None, {_V1}), _xml_keys(both)):
        failures += fail("a failure already failing in a class kept whole was not subtracted")
    return failures


def case_re_runs_in_a_class_excluded_whole_do_not_clear_a_shared_key_miss():
    """The class re-run answers `v = 1` from whichever template ran; `p.P` was excluded whole."""
    failures = 0
    results = _ScriptedResults(({_V1}, {_V1}), ({_V1}, set()), ({_V1}, set()))
    _, unrepeatable = isolation.order_dependent(
        "checkout", {"name": "p"}, "m", [_V1], ".", "stem", None,
        gradle=_FakeGradle(), results_of=results, backup_map=run.backup_map,
        restore_map=run.restore_map, task="test", keys=_xml_keys(_P_EXCLUDED))
    if unrepeatable:
        failures += fail(f"a miss in a class excluded whole was reclassified by its siblings' "
                         f"re-runs: {unrepeatable}")
    # Per method, a re-run without the listener projects `b() > v = 1` onto `v = 1`, which `a()`
    # shares: that re-run cannot answer for it.
    verdict = isolation.outcome_alone(
        "checkout", {"name": "p"}, "m", *_B1, ".", "stem", gradle=_FakeGradle(1),
        results_of=lambda *a: run.Outcomes({_V1, _V2}, {_V1}, cause="no listener marker"),
        task="test", keys=_method_keys(_P_EXCLUDED))
    if verdict != isolation.UNKNOWN:
        failures += fail(f"a re-run in a class excluded whole answered {verdict} on a shared XML "
                         f"key")
    return failures


def case_a_sibling_failing_unmutated_in_a_class_excluded_whole_does_not_set_a_miss_aside():
    """The control arm runs `p.P` with the change reverted; `p.P` was excluded whole."""
    unattributable = isolation.fails_without_the_change(
        "checkout", {"name": "p"}, "m", [_V1], ".", "stem", None,
        gradle=_FakeGradle(1, 1, 1), results_of=lambda *a: ({_V1, _V2}, {_V1}),
        backup_map=run.backup_map, restore_map=run.restore_map, task="test",
        keys=_xml_keys(_P_EXCLUDED))
    if unattributable:
        return fail(f"a miss in a class excluded whole was set aside by a sibling failing "
                    f"unmutated: {unattributable}")
    return 0


def case_the_selected_arm_still_misses_a_class_excluded_whole():
    """A control: nothing of `p.P` ran in the selected arm, so its failure is missed, not shared."""
    found = run.unmatched({_V1}, {("p.Other", "x")}, _xml_keys(_P_EXCLUDED).shared)
    if found != ([_V1], [], []):
        return fail(f"a failure in a class excluded whole was not missed: {found}")
    return 0


def case_a_selected_arm_that_ran_a_class_its_record_excluded_whole_catches_nothing_there():
    """The record excluded `p.P` whole, yet the selected arm ran `v = 1`: the two disagree. A
    baseline flake on `v = 1` is no longer subtracted, so if the selected arm then cleared it, an
    already-failing key would count as caught."""
    keys = _xml_keys(_P_EXCLUDED)
    induced = run.subtract_baseline({_V1}, run.Outcomes(None, {_V1}), keys)
    found = run.unmatched(induced, {_V1, _V2}, keys.shared)
    if found != ([], [], [_V1]):
        return fail(f"a key the record excluded whole was caught by the selected arm: {found}")
    return 0


def case_a_test_that_failed_in_both_arms_is_not_ran_but_passed():
    """The other half of the identity-hash fix, and it was missing.

    `missed` was matched on the normalised name and `ran_but_passed` was not, so a test that failed
    in BOTH arms under two different hashes was excluded from `missed` (right) and then published as
    "selected and executed, so not a skip -- order-dependence or flakiness" (wrong).
    """
    failures = 0
    if run.matched_in(("C", "[1] x=Stub@7b50d62"), {("C", "[1] x=Stub@aabbccdd")}) is not True:
        print("  FAIL: a failure present under another identity hash was not matched")
        failures += 1
    if run.matched_in(("C", "plain"), {("C", "other")}) is not False:
        print("  FAIL: a test genuinely absent was reported as present")
        failures += 1
    # A stable name must still compare exactly: normalising is only for ids that carry a hash.
    if run.matched_in(("C", "plain"), {("C", "plain")}) is not True:
        print("  FAIL: an exact match stopped matching")
        failures += 1
    return failures


def case_ran_but_passed_uses_the_same_comparison_as_missed():
    """The CALL SITE, not the helper -- a unit test on the helper alone did not catch this.

    `missed` matched on the normalised name and this subtraction did not, so a test that failed in
    BOTH arms under two different identity hashes was published as "selected and executed, so not a
    skip -- order-dependence or flakiness". It failed, twice.
    """
    failures = 0
    hashed = ("C", "[1] x=Stub@7b50d62")
    # Failed in the selected arm too, under another hash: not a "ran but passed".
    if run.ran_but_passed({hashed}, {("C", "[1] x=Stub@aabbccdd")}, [], [], [], [], []):
        print("  FAIL: a test that failed in both arms was reported as having passed")
        failures += 1
    # Genuinely ran and passed: still reported, or the weaker signal is lost entirely.
    if run.ran_but_passed({("C", "plain")}, set(), [], [], [], [], []) != [("C", "plain")]:
        print("  FAIL: a real ran-but-passed was dropped")
        failures += 1
    # A miss, an undetermined row and a nondeterministic one are all neither.
    if run.ran_but_passed({("C", "a")}, set(), [("C", "a")], [], [], [], []):
        print("  FAIL: a missed test was also counted as ran-but-passed")
        failures += 1
    if run.ran_but_passed({("C", "b")}, set(), [], [("C", "b")], [], [], []):
        print("  FAIL: an undetermined test was also counted as ran-but-passed")
        failures += 1
    # The one the reclassification introduced: it was taken OUT of `missed`, and it never ran in
    # the selected arm, so landing it here would move it from one wrong published list to another.
    if run.ran_but_passed({("C", "c")}, set(), [], [], [("C", "c")], [], []):
        print("  FAIL: a nondeterministic test was counted as ran-but-passed")
        failures += 1
    return failures


def _decisions(directory, notes=(), rows=(), name=None, rules=()):
    """Writes a decisions.tsv the way the agent writes one, and returns the map directory."""
    lines = ["# test\tverdict\treason"]
    lines += [f"{run.DECISION_NOTE_PREFIX}{key}\t{value}" for key, value in notes]
    lines += [f"{test}\t{verdict}\t{reason}" for test, verdict, reason in rows]
    lines += [f"{run.DECISION_RULES_PREFIX}{test}\t{tokens}" for test, tokens in rules]
    with open(os.path.join(directory, name or run.DECISIONS_FILE), "w", encoding="utf-8") as handle:
        handle.write("\n".join(lines) + "\n")
    return directory


def case_a_cached_baseline_is_reused_only_for_the_same_tree():
    """--recall-only skips a baseline this exact tree already established, and nothing looser."""
    failures = 0
    with tempfile.TemporaryDirectory() as directory:
        path = os.path.join(directory, "baseline-cache.json")
        key = {"commit": "c" * 40, "staged_sources": ["a.kt"], "flags": ["--no-configure-on-demand"]}
        run.write_baseline_cache(path, key, 2, run.Outcomes(None, {("com.acme.T", "flaky")}))
        if run.read_baseline_cache(path, key, 2)[1] != {("com.acme.T", "flaky")}:
            failures += fail("the same tree did not reuse its own baseline")
        if run.read_baseline_cache(path, key, 1) is None:
            failures += fail("a set from MORE repeats than asked for was refused")
        for changed, what in ((dict(key, commit="d" * 40), "another commit"),
                              (dict(key, staged_sources=[]), "an unstaged tree"),
                              (dict(key, flags=[]), "other flags")):
            if run.read_baseline_cache(path, changed, 2) is not None:
                failures += fail(f"a baseline was reused for {what}")
        if run.read_baseline_cache(path, key, 4) is not None:
            failures += fail("a set from fewer repeats than asked for was reused")
        if run.read_baseline_cache(os.path.join(directory, "none.json"), key, 1) is not None:
            failures += fail("a missing cache read as an empty already-failing set")
    return failures


def case_a_replay_snapshot_is_the_map_as_read_and_is_kept_once():
    """The map a selecting arm reads, content-addressed, without the decision files or raw records."""
    failures = 0
    with tempfile.TemporaryDirectory() as directory:
        map_dir = os.path.join(directory, "map")
        os.makedirs(os.path.join(map_dir, "raw"))
        for name, text in (("coverage.tsv", "records\n"), ("schema-version", "3\n"),
                           (run.DECISIONS_FILE, "old decisions\n"),
                           (f"{run.DECISIONS_FILE}.1-1{run.DECISION_PART_SUFFIX}", "part\n")):
            with open(os.path.join(map_dir, name), "w", encoding="utf-8") as handle:
                handle.write(text)
        scratch = os.path.join(directory, "scratch")
        first = run.snapshot_for_replay(map_dir, scratch)
        if sorted(os.listdir(first)) != ["coverage.tsv", "schema-version"]:
            failures += fail(f"the snapshot holds {sorted(os.listdir(first))}")
        if run.snapshot_for_replay(map_dir, scratch) != first:
            failures += fail("the same map was kept twice")
        with open(os.path.join(map_dir, "coverage.tsv"), "a", encoding="utf-8") as handle:
            handle.write("a forced arm captured more\n")
        if run.snapshot_for_replay(map_dir, scratch) == first:
            failures += fail("a changed map reused the old snapshot")
        if run.snapshot_for_replay(None, scratch) is not None:
            failures += fail("no map produced a snapshot")
        # A second keep of the same row replaces the first's parts rather than adding to them.
        live = os.path.join(directory, "live-map")
        os.makedirs(live)
        kept = os.path.join(scratch, "decisions-row")
        for pid in ("11", "12"):
            for name in os.listdir(live):
                os.remove(os.path.join(live, name))
            with open(os.path.join(live, f"{run.DECISIONS_FILE}.{pid}-1{run.DECISION_PART_SUFFIX}"),
                      "w", encoding="utf-8") as handle:
                handle.write("part\n")
            run.keep_decisions(live, kept)
        if sorted(os.listdir(kept + ".parts")) != [f"{run.DECISIONS_FILE}.12-1{run.DECISION_PART_SUFFIX}"]:
            failures += fail(f"a second keep merged two runs' parts: {sorted(os.listdir(kept + '.parts'))}")

        # A row carries a replay block only when both halves exist.
        stem = os.path.join(scratch, "decisions-x")
        if run.replay_block(first, stem) is not None:
            failures += fail("a replay block named decisions that were never kept")
        os.makedirs(stem + ".parts")
        block = run.replay_block(first, stem)
        if not block or not block["decisions"].endswith(".parts") or block["digest"] != run.selector_digest():
            failures += fail(f"the replay block is {block}")
    return failures


def case_every_test_jvm_s_part_reaches_the_row():
    """`forkEvery` starts a JVM per N classes and `decisions.tsv` keeps only the last one's answer.

    duckduckgo-android: 371 rows survived of a 5,041-test arm, on the row that recorded a
    miss, so nothing could say whether the 83 tests the arm never ran had been deselected. Each JVM's
    part carries its own whole answer; the reader merges them.
    """
    failures = 0

    def part(directory, pid, rows, outcome="narrowed", declared=None):
        _decisions(directory, name=f"{run.DECISIONS_FILE}.{pid}-1{run.DECISION_PART_SUFFIX}",
                   notes=[("record-version", "2"), ("rows", str(len(rows) if declared is None else declared)),
                          ("outcome", outcome)],
                   rows=rows)

    with tempfile.TemporaryDirectory() as directory:
        started = time.time() - 5
        # The shared file is the LAST writer's answer only.
        _decisions(directory, notes=[("record-version", "2"), ("rows", "1"), ("outcome", "narrowed")],
                   rows=[("c", "included", "NOT_IN_MAP")])
        part(directory, 11, [("a", "included", "REACHES_CHANGE"), ("b", "excluded", "SKIPPED")])
        part(directory, 12, [("c", "included", "NOT_IN_MAP")])
        record = run.read_decision_record(directory, started)
        if record is None or sorted(record["included"]) != ["a", "c"]:
            failures += fail(f"the parts did not reach the row: {record and record['included']}")
        elif record["verdicts"] != {"included/REACHES_CHANGE": 1, "excluded/SKIPPED": 1,
                                    "included/NOT_IN_MAP": 1}:
            failures += fail(f"verdicts {dict(record['verdicts'])} are not the union of the parts")
        elif record["notes"].get("outcome") != "narrowed" or record["notes"].get("writer") != "2 parts":
            failures += fail(f"the agreed notes were not kept: {record['notes']}")

        # A test two writers both decided counts once.
        part(directory, 13, [("a", "included", "REACHES_CHANGE")])
        record = run.read_decision_record(directory, started)
        if record is None or record["included"].count("a") != 1:
            failures += fail("a test decided by two writers was counted twice")

        # Writers that disagree on the outcome publish no outcome, and say what each claimed.
        part(directory, 14, [("d", "included", "NOT_IN_MAP")], outcome="full-run")
        record = run.read_decision_record(directory, started)
        if record is None or "outcome" in record["notes"] \
                or record["notes"].get("writer-outcomes") != "full-run,narrowed":
            failures += fail(f"a disagreement between writers was resolved silently: {record and record['notes']}")

        # One torn part and the whole record is absent, never the other parts' short answer.
        part(directory, 15, [("e", "included", "NOT_IN_MAP")], declared=2)
        if run.read_decision_record(directory, started) is not None:
            failures += fail("a torn part was dropped and the rest published as the whole answer")

    with tempfile.TemporaryDirectory() as directory:
        # A part from before this arm is not this arm's answer; the shared file is read as before.
        stale = time.time() + 60
        part(directory, 21, [("x", "included", "NOT_IN_MAP")])
        os.utime(os.path.join(directory, f"{run.DECISIONS_FILE}.21-1{run.DECISION_PART_SUFFIX}"),
                 (stale - 120, stale - 120))
        _decisions(directory, notes=[("record-version", "2"), ("rows", "1"), ("outcome", "narrowed")],
                   rows=[("y", "included", "NOT_IN_MAP")])
        record = run.read_decision_record(directory, stale - 60)
        if record is None or record["included"] != ["y"]:
            failures += fail(f"a stale part displaced the shared file: {record and record['included']}")
        # A staging file is not a part.
        if any(p.endswith(".writing") for p in run.decision_parts(directory)):
            failures += fail("a staging file was read as a part")
    return failures


def case_a_refusal_reaches_the_row_as_its_own_kind():
    """A refused run is not a run nobody asked about, and the row has to be able to say which."""
    failures = 0
    with tempfile.TemporaryDirectory() as directory:
        started = time.time() - 5
        _decisions(directory,
                   notes=[("record-version", "2"), ("rows", "1"),
                          ("outcome", "full-run"),
                          ("full-run-kind", "daemon-refused"),
                          ("refusal-kind", "constant-changed"),
                          ("full-run-reason", "a constant the map was captured under changed")],
                   rows=[("a", "included", "DAEMON_REFUSED")])
        fields = run.decision_fields(run.read_decision_record(directory, started), 1)
        if fields["refusal_kind"] != "constant-changed":
            failures += fail(f"the refusal kind did not reach the row: {fields['refusal_kind']}")
        if fields["full_run_kind"] != "daemon-refused":
            failures += fail("which side refused was lost")
        if fields["decision_record_version"] != "2":
            failures += fail("the record version did not reach the row")
    return failures


def case_a_record_from_an_older_jar_is_version_skew_not_an_unattributed_row():
    """Absent tokens have two causes and only one of them is a defect in the run."""
    failures = 0
    with tempfile.TemporaryDirectory() as directory:
        started = time.time() - 5
        # No version note at all: written by a jar that predates it. A corpus checkout can resolve
        # one, and `plugin_commit` is blind to a dirty tree and cannot settle which happened.
        _decisions(directory, notes=[("outcome", "full-run")],
                   rows=[("a", "included", "FULL_RUN")])
        fields = run.decision_fields(run.read_decision_record(directory, started), 1)
        if fields["decision_record_version"] is not None:
            failures += fail("a record with no version note claimed one")
        if fields["decision_outcome"] != "full-run":
            failures += fail("an older record stopped being read at all")
    return failures


def case_a_truncated_decision_record_reads_as_absent():
    """A shutdown hook killed mid-write leaves a file that exists, parses, and is short."""
    failures = 0
    with tempfile.TemporaryDirectory() as directory:
        started = time.time() - 5
        # The writer declared three rows and the file holds one. Every other property of this file
        # is healthy: it exists, it is newer than the arm, and it parses.
        _decisions(directory,
                   notes=[("record-version", "2"), ("rows", "3"), ("outcome", "narrowed")],
                   rows=[("a", "included", "REACHES_CHANGE")])
        if run.read_decision_record(directory, started) is not None:
            failures += fail("a record whose tail was lost was read as a short answer")

        # And a complete one still reads.
        _decisions(directory,
                   notes=[("record-version", "2"), ("rows", "1"), ("outcome", "narrowed")],
                   rows=[("a", "included", "REACHES_CHANGE")])
        if run.read_decision_record(directory, started) is None:
            failures += fail("a complete record was rejected by the completeness check")
    return failures


def case_a_record_carrying_more_rows_than_it_declares_reads_as_absent():
    """The OTHER direction, which is the one the corpus actually holds, and it is not a lost tail.

    A search went looking for a writer that declares a count it does not hold. Across the 516
    decision files recorded under `bench/logs/`, 510 of the 513 that state a count declare
    exactly what they carry. The three that do not are spring-security twice and spring-framework
    once, and all three declare FEWER rows than
    they hold -- `#!rows 1` over 2,979 rows, `#!rows 3` over 2,216. A shutdown hook cut mid-write
    cannot produce that: it loses the tail, so it leaves a file holding fewer than it promised.

    The reader rejects both directions, because it compares rather than bounds. Only the losing-tail
    direction was ever tested, so the direction the corpus has was carried by an untested branch.

    Both readings are ABSENT rather than short. A plausible wrong answer is worse than no answer,
    and no answer is a state the harness already handles everywhere.
    """
    failures = 0
    with tempfile.TemporaryDirectory() as directory:
        started = time.time() - 5
        # The spring shape, reduced: the header promises one row and the body holds three.
        _decisions(directory,
                   notes=[("record-version", "2"), ("rows", "1"), ("outcome", "narrowed")],
                   rows=[("a", "included", "REACHES_CHANGE"),
                         ("b", "included", "REACHES_CHANGE"),
                         ("c", "excluded", "NOT_IN_MAP")])
        if run.read_decision_record(directory, started) is not None:
            failures += fail("a record holding more rows than it declares was read as an answer; "
                             "it cannot be a lost tail, and what it describes is unestablished")

        # A count that is not a number establishes nothing either way, and the file still parses.
        # Existing behaviour -- the reader only compares when `declared.isdigit()` -- pinned here
        # so a stricter comparison cannot start rejecting version skew as a mismatch.
        _decisions(directory,
                   notes=[("record-version", "2"), ("rows", "many"), ("outcome", "narrowed")],
                   rows=[("a", "included", "REACHES_CHANGE")])
        if run.read_decision_record(directory, started) is None:
            failures += fail("a row count that is not a number was treated as a mismatch")
    return failures


def case_a_record_torn_inside_its_notes_reads_as_absent():
    """The tear the row count cannot see, because the count is one of the notes that was lost."""
    failures = 0
    with tempfile.TemporaryDirectory() as directory:
        started = time.time() - 5
        # Cut after the version note. Every note after it is gone, including the outcome the row is
        # classified on -- so the file parses, holds rows, and describes nothing.
        _decisions(directory, notes=[("record-version", "2")],
                   rows=[("a", "included", "FULL_RUN")])
        if run.read_decision_record(directory, started) is not None:
            failures += fail("a record cut inside its notes was read as an answer")

        # And a record from a jar older than both notes carries neither, which is version skew and
        # still reads.
        _decisions(directory, notes=[("outcome", "narrowed")], rows=[("a", "included", "X")])
        if run.read_decision_record(directory, started) is None:
            failures += fail("a record predating the notes was rejected as torn")
    return failures


def case_prose_survives_the_writer_and_the_reader():
    """A refusal reason is prose, and prose from a Windows build carries backslashes and tabs."""
    failures = 0
    reason = "C:\\builds\\app declared a constant\tand the map disagreed"
    escaped = reason.replace("\\", "\\\\").replace("\t", "\\t")
    with tempfile.TemporaryDirectory() as directory:
        started = time.time() - 5
        _decisions(directory,
                   notes=[("record-version", "2"), ("rows", "0"),
                          ("outcome", "full-run"), ("full-run-kind", "daemon-refused"),
                          ("refusal-kind", "constant-changed"), ("full-run-reason", escaped)])
        fields = run.decision_fields(run.read_decision_record(directory, started), 0)
        if fields["decision_reason"] != reason:
            failures += fail(f"the reason did not round-trip: {fields['decision_reason']!r}")
    return failures


def case_the_decision_tokens_reach_the_row():
    """The whole point: a finished row says WHY, in tokens, without re-running anything."""
    failures = 0
    with tempfile.TemporaryDirectory() as directory:
        started = time.time() - 5
        _decisions(directory,
                   notes=[("outcome", "full-run"),
                          ("full-run-kind", "no-coverage-for-changed"),
                          ("full-run-reason", "the map has no coverage for p.Changed")],
                   rows=[("t1", "included", "FULL_RUN"), ("t2", "included", "FULL_RUN")])
        fields = run.decision_fields(run.read_decision_record(directory, started), 2)
    if fields["full_run_kind"] != "no-coverage-for-changed":
        print(f"  FAIL: the force kind did not reach the row: {fields['full_run_kind']!r}")
        failures += 1
    if fields["decision_outcome"] != "full-run":
        print(f"  FAIL: the outcome did not reach the row: {fields['decision_outcome']!r}")
        failures += 1
    # A forced row records what the selector let through and does NOT compare it against the XML;
    # see decision_fields on why that comparison is meaningless on a full run.
    if fields["decision_included"] != 2:
        print(f"  FAIL: the selector's own count did not reach the row: "
              f"{fields['decision_included']!r}")
        failures += 1
    return failures


def case_every_rule_behind_a_decision_reaches_the_row():
    """Each row's rule line is kept, counted per rule, and the coverage-only selection derived.

    A refused run is the case that matters: every row says DAEMON_REFUSED, and only the rule lines
    say what coverage alone would have run beneath the refusal.
    """
    failures = 0
    notes = [("record-version", "2"), ("rows", "3"), ("outcome", "full-run"),
             ("full-run-kind", "daemon-refused"), ("refusal-kind", "constant-changed"),
             ("rules", "from-refused-inputs"), ("forcing-kinds", "daemon-refused")]
    rows = [("a", "included", "DAEMON_REFUSED"), ("b", "included", "DAEMON_REFUSED"),
            ("c", "included", "DAEMON_REFUSED")]
    rules = [("a", "reaches-change,shares-jvm-changed-class"), ("b", "shares-jvm-changed-class"),
             ("c", "none")]
    with tempfile.TemporaryDirectory() as directory:
        started = time.time() - 5
        _decisions(directory, notes=notes, rows=rows, rules=rules)
        record = run.read_decision_record(directory, started)
        if record is None:
            return fail("a record with rule lines was not read at all")
        # The rows read exactly as before: a rule line is not a row.
        if record["verdicts"] != {"included/DAEMON_REFUSED": 3}:
            failures += fail(f"a rule line was read as a row: {dict(record['verdicts'])}")
        fields = run.decision_fields(record, 3)
        if fields["rules_basis"] != "from-refused-inputs" or fields["forcing_kinds"] != "daemon-refused":
            failures += fail(f"the rule notes did not reach the row: {fields['rules_basis']!r}, "
                             f"{fields['forcing_kinds']!r}")
        if fields["rule_counts"] != {"none": 1, "reaches-change": 1, "shares-jvm-changed-class": 2}:
            failures += fail(f"the per-rule counts are wrong: {fields['rule_counts']}")
        if fields["coverage_only_included"] != 1 or fields["coverage_only_digest"] != run.selected_digest(["a"]):
            failures += fail(f"the selection beneath the refusal is not coverage alone's: "
                             f"{fields['coverage_only_included']!r}")

        # A torn rule block costs the rules and never the rows.
        _decisions(directory, notes=notes, rows=rows, rules=rules[:2])
        record = run.read_decision_record(directory, started)
        if record is None or record["rules"] is not None:
            failures += fail("a torn rule block was read as the rules, or cost the rows")
        elif run.decision_fields(record, 3)["rule_counts"] is not None:
            failures += fail("a torn rule block still produced per-rule counts")

        # No rules recorded (an older jar, or a refusal with no change set) is absent, not "none".
        _decisions(directory, notes=[n for n in notes if n[0] != "rules"] + [("rules", "no-change-set")],
                   rows=rows)
        fields = run.decision_fields(run.read_decision_record(directory, started), 3)
        if fields["rules_basis"] != "no-change-set" or fields["coverage_only_included"] is not None:
            failures += fail(f"a record without rules claimed a coverage-only selection: {fields}")

    with tempfile.TemporaryDirectory() as directory:
        started = time.time() - 5
        # Parts merge their rules; one deciding part without them makes the task's rules absent.
        for pid, test, tokens in ((31, "a", "reaches-change"), (32, "b", "none")):
            _decisions(directory, name=f"{run.DECISIONS_FILE}.{pid}-1{run.DECISION_PART_SUFFIX}",
                       notes=[("record-version", "2"), ("rows", "1"), ("outcome", "narrowed"),
                              ("rules", "complete"), ("forcing-kinds", "none")],
                       rows=[(test, "included" if tokens != "none" else "excluded", "X")],
                       rules=[(test, tokens)])
        record = run.read_decision_record(directory, started)
        if record is None or record["rules"] != {"a": ("reaches-change",), "b": ("none",)}:
            failures += fail(f"the parts' rules were not merged: {record and record['rules']}")
        _decisions(directory, name=f"{run.DECISIONS_FILE}.33-1{run.DECISION_PART_SUFFIX}",
                   notes=[("record-version", "2"), ("rows", "1"), ("outcome", "narrowed")],
                   rows=[("c", "included", "X")])
        record = run.read_decision_record(directory, started)
        if record is None or record["rules"] is not None:
            failures += fail("a part without rules was merged into a task-wide count")
    return failures


def case_a_stale_decision_record_is_absent_not_an_answer():
    """The defect this harness has already shipped once, in first_contact's audit.json read.

    A record older than the arm that should have written it belongs to an EARLIER run. Reporting it
    is worse than reporting nothing, because it is indistinguishable from a correct answer.
    """
    failures = 0
    with tempfile.TemporaryDirectory() as directory:
        _decisions(directory, notes=[("outcome", "full-run"), ("full-run-kind", "map-unusable")])
        old = time.time() - 3600
        os.utime(os.path.join(directory, run.DECISIONS_FILE), (old, old))
        # The arm started well after that file was written.
        if run.read_decision_record(directory, time.time() - 60) is not None:
            print("  FAIL: a record older than the arm was reported as that arm's answer")
            failures += 1
        fields = run.decision_fields(run.read_decision_record(directory, time.time() - 60), 0)
    if fields["full_run_kind"] is not None or fields["decision_outcome"] is not None:
        print(f"  FAIL: a stale record still populated the row: {fields}")
        failures += 1
    return failures


def case_the_harness_own_map_restore_does_not_make_a_stale_record_look_fresh():
    """The freshness check is only worth anything if the sweep's own plumbing preserves mtimes.

    Every row calls `restore_map` before its selected arm, putting back a map that may carry the
    PREVIOUS row's `decisions.tsv`. If that copy stamped the file with the current time, a stale
    record would arrive looking newer than the arm and the freshness check would wave it through --
    which is the exact defect it exists to stop, reintroduced by the plumbing rather than by the
    reader. `shutil.copytree` copies with `copy2` and preserves mtime; this asserts it rather than
    trusting it.
    """
    failures = 0
    with tempfile.TemporaryDirectory() as directory:
        map_dir = os.path.join(directory, "map")
        os.makedirs(map_dir)
        _decisions(map_dir, notes=[("outcome", "narrowed")])
        old = time.time() - 7200
        os.utime(os.path.join(map_dir, run.DECISIONS_FILE), (old, old))

        backup = run.backup_map(map_dir, directory, "map-before-row")
        run.restore_map(backup, map_dir)

        if run.read_decision_record(map_dir, time.time() - 60) is not None:
            print("  FAIL: a record restored by the harness's own backup/restore read as fresh; "
                  "every row would inherit the previous row's explanation")
            failures += 1
    return failures


def case_a_narrowed_row_carries_no_force_kind():
    """A row that narrowed must not inherit the previous row's refusal."""
    failures = 0
    with tempfile.TemporaryDirectory() as directory:
        _decisions(directory,
                   notes=[("outcome", "narrowed")],
                   rows=[("t1", "included", "REACHES_CHANGE"), ("t2", "excluded", "SKIPPED")])
        fields = run.decision_fields(run.read_decision_record(directory, time.time() - 5), 1)
    if fields["full_run_kind"] is not None:
        print(f"  FAIL: a narrowed row carries a force kind: {fields['full_run_kind']!r}")
        failures += 1
    if fields["decision_outcome"] != "narrowed":
        print(f"  FAIL: outcome should be narrowed, was {fields['decision_outcome']!r}")
        failures += 1
    if fields["selected_verdicts"] != {"excluded/SKIPPED": 1, "included/REACHES_CHANGE": 1}:
        print(f"  FAIL: verdict tally wrong: {fields['selected_verdicts']}")
        failures += 1
    return failures


def case_a_record_that_disagrees_with_the_arm_says_so():
    """The record is written by the test JVM; the counts come from Gradle's XML. They can disagree."""
    failures = 0
    with tempfile.TemporaryDirectory() as directory:
        _decisions(directory,
                   notes=[("outcome", "narrowed")],
                   rows=[("t1", "included", "REACHES_CHANGE")])
        fields = run.decision_fields(run.read_decision_record(directory, time.time() - 5), 9)
    if fields["decision_matches_arm"] is not False:
        print("  FAIL: one included test against nine that ran was not flagged as a disagreement")
        failures += 1
    if fields["decision_included"] != 1:
        print(f"  FAIL: the selector's own count was not recorded: {fields['decision_included']!r}")
        failures += 1
    if fields["full_run_kind"] is not None or fields["decision_outcome"] != "narrowed":
        print("  FAIL: a disagreeing record should still be recorded, not dropped")
        failures += 1

    # A FULL run legitimately discovers more than it executes -- a disabled test is discovered,
    # filtered, and never reaches the XML. Measured at 25 of 3,707 on spring-boot. Comparing there
    # would report a disagreement on every forced row of a real project and mean nothing by it.
    with tempfile.TemporaryDirectory() as directory:
        _decisions(directory,
                   notes=[("outcome", "full-run"), ("full-run-kind", "map-unusable")],
                   rows=[(f"t{n}", "included", "FULL_RUN") for n in range(30)])
        forced = run.decision_fields(run.read_decision_record(directory, time.time() - 5), 25)
    if forced["decision_matches_arm"] is not None:
        print(f"  FAIL: a forced row compared discovery against execution and called it a "
              f"disagreement: {forced['decision_matches_arm']!r}")
        failures += 1
    if forced["decision_included"] != 30:
        print("  FAIL: a forced row must still record what the selector let through")
        failures += 1
    return failures


def case_the_digest_changes_when_one_test_changes():
    """Two rows selecting the same tests must agree; one differing test must not."""
    failures = 0
    same = run.selected_digest(["b", "a", "c"])
    if same != run.selected_digest(["c", "b", "a"]):
        print("  FAIL: the digest depends on the order the tests came back in")
        failures += 1
    if same == run.selected_digest(["a", "b", "d"]):
        print("  FAIL: a differing selected set produced the same digest")
        failures += 1
    if run.selected_digest([]) is not None:
        print("  FAIL: an empty selection must not share a spelling with an unread record")
        failures += 1
    return failures


def case_the_digest_ignores_an_identity_hash():
    """armeria's shape. Without this an identical tree digests differently on every run."""
    failures = 0
    first = run.selected_digest(["[class:C]/[method:x(blockingClient=Foo@1b6d3586)]"])
    second = run.selected_digest(["[class:C]/[method:x(blockingClient=Foo@7c53a9eb)]"])
    if first != second:
        print("  FAIL: an identity hash in a display name changes the digest of the same selection")
        failures += 1
    return failures


def case_a_map_with_no_stamp_records_null_never_a_guess():
    """A map with no capture-commit records absent; it never synthesises one from an mtime."""
    failures = 0
    with tempfile.TemporaryDirectory() as directory:
        with open(os.path.join(directory, "coverage.tsv"), "w", encoding="utf-8") as handle:
            handle.write("# header\na\tb\nc\td\n")
        commit, records, attributable = run.map_fingerprint(directory)
    if commit is not None:
        print(f"  FAIL: a map with no stamp reported a commit: {commit!r}")
        failures += 1
    if records != 2:
        print(f"  FAIL: record count wrong (comments must not count): {records}")
        failures += 1
    return failures


def case_a_map_that_attributes_nothing_says_so():
    """The health neither the stamp nor the record count can see.

    glide's map held 1,522 records and 1,220 of them named no class. The capture commit and the
    record count were identical before and after, so a reader comparing rows across that capture saw
    two maps that looked the same and answered differently.
    """
    failures = 0
    with tempfile.TemporaryDirectory() as directory:
        with open(os.path.join(directory, "coverage.tsv"), "w", encoding="utf-8") as handle:
            # Four records, one of which attributes to a class.
            handle.write("NONE\t0\t\t[yoriwake:unattributed]\n")
            handle.write("NONE\t0\t\t[yoriwake:unattributed]\n")
            handle.write("NONE\t0\t\t[yoriwake:unattributed]\n")
            handle.write("NONE\t0\tcom.acme.Thing\t[yoriwake:class][engine]/[class:T]\n")
        _, records, attributable = run.map_fingerprint(directory)
    if records != 4:
        print(f"  FAIL: the record count changed meaning: {records}")
        failures += 1
    if attributable != 0.25:
        print(f"  FAIL: attributable share wrong: {attributable}")
        failures += 1
    return failures


def case_a_missing_map_records_absent_not_zero():
    """Empty is not unknown. A map with no records and no map at all are different facts."""
    failures = 0
    commit, records, attributable = run.map_fingerprint(None)
    if commit is not None or records is not None or attributable is not None:
        print(f"  FAIL: no map should be absent, not zero: {commit!r}, {records!r}, {attributable!r}")
        failures += 1
    with tempfile.TemporaryDirectory() as directory:
        empty_commit, empty_records, empty_attributable = run.map_fingerprint(directory)
    if empty_records is not None:
        print(f"  FAIL: an unreadable coverage.tsv reported {empty_records!r} rather than absent")
        failures += 1
    if empty_commit is not None:
        print(f"  FAIL: an absent stamp reported {empty_commit!r}")
        failures += 1
    return failures


def case_elapsed_time_is_parsed_in_the_format_both_systems_print():
    """The Linux-only spelling that silently recorded nothing on the measuring machine.

    `ps -o etimes=` gives seconds and is a Linux extension; macOS answers "keyword not found" and
    prints nothing, which arrived here as "no daemon is running" on every row of a real sweep.
    `etime` is what both accept, and it is formatted rather than numeric.
    """
    failures = 0
    for text, expected in (("05:03", 303),
                           ("1:00:00", 3600),
                           ("2-03:04:05", 183845),
                           ("   07:35   ", 455),
                           ("", None),
                           ("nonsense", None),
                           ("12", None)):
        actual = run.parse_etime(text)
        if actual != expected:
            print(f"  FAIL: etime {text!r} parsed as {actual!r}, expected {expected!r}")
            failures += 1
    return failures


def case_machine_conditions_are_absent_rather_than_zero():
    """A machine that cannot be asked is not an idle machine with a freshly started daemon."""
    failures = 0
    conditions = run.machine_conditions()
    for key in ("load_average", "daemon_uptime_seconds"):
        if key not in conditions:
            print(f"  FAIL: {key} is not recorded at all")
            failures += 1
        elif conditions[key] is not None and not isinstance(conditions[key], (int, float)):
            print(f"  FAIL: {key} is neither a number nor absent: {conditions[key]!r}")
            failures += 1
    return failures



def case_a_project_that_names_no_sources_is_refused_rather_than_yielding_nothing():
    """An empty `sources` asks nothing, and a harness that answers it reports 0 missed of 0.

    `replay_commits` already refuses this one layer up. `mutation_targets` returned an empty list
    and printed nothing, so three project files swept with no targets at all and the summary read
    like a project with nothing to find.
    """
    failures = 0
    import io as _io
    import contextlib
    with tempfile.TemporaryDirectory() as tmp:
        said = _io.StringIO()
        with contextlib.redirect_stdout(said):
            targets = target_ordering.mutation_targets(tmp, {"name": "p", "sources": []})
        if targets != []:
            print(f"  FAIL: an empty `sources` yielded targets: {targets}")
            failures += 1
        if "sources" not in said.getvalue() or "p" not in said.getvalue():
            print(f"  FAIL: the refusal named neither the key nor the project: {said.getvalue()!r}")
            failures += 1
    return failures


def case_targets_come_only_from_the_declared_source_roots():
    """Real roots yield targets under those roots and nowhere else."""
    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        wanted = os.path.join(tmp, "mod", "src", "main", "java", "p")
        other = os.path.join(tmp, "other", "src", "main", "java", "p")
        for directory in (wanted, other):
            os.makedirs(directory)
            with open(os.path.join(directory, "C.java"), "w", encoding="utf-8") as handle:
                handle.write("package p;\npublic class C { public boolean on() { return true; } }\n")
        targets = target_ordering.mutation_targets(
            tmp, {"name": "p", "sources": ["mod/src/main/java"]})
        if not targets:
            print("  FAIL: a real source root produced no targets at all")
            failures += 1
        if any(not path.startswith("mod/") for path, _kind, _index in targets):
            print(f"  FAIL: targets came from outside the declared root: {targets}")
            failures += 1
    return failures


def case_every_empty_sources_file_says_why():
    """A project file may keep `sources = []`, but not silently.

    Same shape as the qualification-verdict guard: the reason lives beside the key, so a reader who
    finds a project producing no rows is not left to reconstruct whether that was a decision.
    """
    failures = 0
    projects = os.path.join(os.path.dirname(os.path.abspath(__file__)), "projects")
    for name in sorted(os.listdir(projects)):
        if not name.endswith(".toml"):
            continue
        lines = open(os.path.join(projects, name), encoding="utf-8").read().splitlines()
        for number, line in enumerate(lines):
            if line.strip() != "sources = []":
                continue
            above = lines[number - 1].strip() if number else ""
            preceding = " ".join(lines[max(0, number - 3):number])
            if not above.startswith("#") or "because" not in preceding:
                print(f"  FAIL: {name} carries `sources = []` with no reason beside it")
                failures += 1
    return failures




# ---- the baseline repeat: an intermittent test must not be published as an induced failure ----
#
# `isolation.py` has repeated the ISOLATED re-run since 5d5ec33. The baseline was still single-shot,
# so a test that happened to pass it and then failed the ground-truth arm was counted as induced --
# and a miss found from there is the most alarming line this harness can print.


def _baselines(*runs):
    """A fake gradle/results_of pair answering one scripted failure set per EXTRA run, in order.

    The timed baseline is not among them: `extra_baseline_failures` runs repeats two onwards only, so
    `runs[0]` is what the SECOND unmutated run answers. `None` means that run stalled.
    """
    calls = []

    def gradle(checkout, project, task_args, log_path):
        calls.append(log_path)
        return (run.STALLED if runs[len(calls) - 1] is None else 0), 1.0

    def results_of(checkout, results_dir, since, task_name, log_path):
        answer = runs[len(calls) - 1]
        return (set(), set() if answer is None else set(answer))

    return gradle, results_of, calls


def case_a_test_failing_on_every_baseline_repeat_is_already_failing():
    failures = 0
    flaky = ("com.acme.FlakyTest", "sometimes")
    gradle, results_of, _ = _baselines([flaky], [flaky])
    (_, found), _, _ = run.extra_baseline_failures(
        "checkout", {}, ":m:test", "m", "test", "/tmp",
        gradle=gradle, results_of=results_of, repeats=3)
    if found != {flaky}:
        print(f"  FAIL: {found}, expected the test that failed in both repeats")
        failures += 1
    return failures


def case_baseline_answers_that_disagree_resolve_to_already_failing():
    """The whole point. A test that cannot repeat its own answer is not evidence about selection.

    Resolved toward already-failing because that direction can only REMOVE a test from the recall
    question -- the same way `isolation.py` resolves an unknown isolated run toward keeping a miss.
    """
    failures = 0
    flaky = ("com.acme.FlakyTest", "sometimes")
    gradle, results_of, _ = _baselines([], [flaky])
    (_, found), _, _ = run.extra_baseline_failures(
        "checkout", {}, ":m:test", "m", "test", "/tmp",
        gradle=gradle, results_of=results_of, repeats=3)
    if flaky not in found:
        print(f"  FAIL: {found} -- a test that failed in one repeat of two was not excluded")
        failures += 1
    return failures


def case_a_test_that_passes_every_repeat_is_still_induced_later():
    """The repeat must not suppress a REAL induced failure, which is the other direction."""
    failures = 0
    gradle, results_of, _ = _baselines([], [])
    (_, found), _, _ = run.extra_baseline_failures(
        "checkout", {}, ":m:test", "m", "test", "/tmp",
        gradle=gradle, results_of=results_of, repeats=3)
    if found:
        print(f"  FAIL: {found} -- nothing failed any repeat, so nothing may be excluded")
        failures += 1
    # And the subtraction the row actually performs is unchanged by an empty set.
    broken = {("com.acme.RealTest", "breaks")}
    if broken - found != broken:
        print("  FAIL: an empty already-failing set removed an induced failure")
        failures += 1
    return failures


def case_the_timed_baseline_also_refuses_a_non_answer():
    """The hole the repeats' fix left one line above itself.

    `results_of` answers (None, None) when a run never reached the tests. On the timed baseline that
    became an empty already-failing set, so every pre-existing failure was counted as induced by the
    first mutation after it -- the same silent wrong-direction default the repeats were given a check
    for on review. Asserted through the same seam the repeats use, because main() is not callable
    from a test: the contract is that an absent `ran` is never folded in as "nothing failed".
    """
    failures = 0
    # The seam: results_of's THREE answers, and which of them may reach `already_failing`.
    for ran, failed, may_use in ((None, None, False), (set(), set(), True),
                                 ({("a", "b")}, {("a", "b")}, True)):
        usable = ran is not None
        if usable != may_use:
            print(f"  FAIL: ran={ran!r} treated as {'usable' if usable else 'unusable'}")
            failures += 1
    # And the guard is actually present in both call sites rather than only in the repeats.
    source = open(run.__file__, encoding="utf-8").read()
    guards = len(re.findall(r"reached no tests and established nothing", source))
    if guards != 2:
        print(f"  FAIL: {guards} baseline non-answer guard(s) in run.py, expected 2 "
              f"(the timed baseline and the reused-map path)")
        failures += 1
    return failures


def case_a_repeat_that_reached_no_tests_is_not_read_as_clean():
    """The one non-answer route the first version of this guard did not cover.

    `results_of` returns (None, None) for "this run never reached the tests", and folding that in as
    an empty set made a dead repeat indistinguishable from one that ran everything and found nothing
    failing -- so the log claimed N repeats while the flake filter had the strength of one.
    """
    failures = 0
    flaky = ("com.acme.FlakyTest", "sometimes")

    def gradle(checkout, project, task_args, log_path):
        return 0, 1.0

    answers = iter([(None, None), ({("a", "b")}, {flaky})])

    def results_of(checkout, results_dir, since, task_name, log_path):
        return next(answers)

    (_, found), answered, removed = run.extra_baseline_failures(
        "checkout", {}, ":m:test", "m", "test", "/tmp",
        gradle=gradle, results_of=results_of, repeats=3)
    if answered != 1:
        print(f"  FAIL: {answered} repeats counted as answering, expected 1 -- a dead run counted")
        failures += 1
    if found != {flaky}:
        print(f"  FAIL: {found}, expected only the repeat that answered")
        failures += 1
    if removed.get("com.acme.FlakyTest::sometimes") != [3]:
        print(f"  FAIL: {removed} -- the repeat that removed the test was not named")
        failures += 1
    return failures


def case_the_repeats_name_which_tests_they_removed():
    """One of three is noise; three of three is a broken test. A count cannot tell them apart."""
    failures = 0
    often = ("com.acme.T", "often")
    once = ("com.acme.T", "once")
    gradle, results_of, _ = _baselines([often], [often, once], [often])
    _, answered, removed = run.extra_baseline_failures(
        "checkout", {}, ":m:test", "m", "test", "/tmp",
        gradle=gradle, results_of=results_of, repeats=4)
    if answered != 3:
        print(f"  FAIL: answered={answered}, expected 3")
        failures += 1
    if removed.get("com.acme.T::often") != [2, 3, 4] or removed.get("com.acme.T::once") != [3]:
        print(f"  FAIL: {removed} -- the per-repeat provenance is wrong")
        failures += 1
    # ROUND-TRIPPED THROUGH JSON, because that is what the field is for and it is where this broke.
    # The first version keyed by the (class, name) tuple `results_of` returns; the dict looked right
    # to a test that only inspected it, and `write_results` threw mid-serialization, leaving an
    # unreadable results file and losing a whole project's rows.
    import json as _json
    try:
        _json.dumps({"baseline_removed": removed})
    except TypeError as error:
        print(f"  FAIL: the removed map does not survive JSON: {error}")
        failures += 1
    return failures


def case_the_repeat_count_is_read_from_its_knob():
    """Fails the moment the knob stops being consulted, in both directions.

    A default that is ignored is the same defect as a field with no reader: the flag is accepted, the
    output looks right, and the extra evidence was never bought.
    """
    failures = 0
    for repeats, expected in ((1, 0), (2, 1), (4, 3)):
        gradle, results_of, calls = _baselines([], [], [], [])
        run.extra_baseline_failures("checkout", {}, ":m:test", "m", "test", "/tmp",
                                    gradle=gradle, results_of=results_of, repeats=repeats)
        if len(calls) != expected:
            print(f"  FAIL: repeats={repeats} ran {len(calls)} extra build(s), expected {expected}")
            failures += 1
    if run.DEFAULT_BASELINE_REPEATS < 2:
        print("  FAIL: the default repeats fewer than twice, so disagreement cannot be seen at all")
        failures += 1
    return failures


def case_a_stalled_repeat_establishes_nothing_and_is_not_read_as_clean():
    """Silence is not "nothing failed". A killed run answers nothing and says so."""
    failures = 0
    flaky = ("com.acme.FlakyTest", "sometimes")
    gradle, results_of, calls = _baselines(None, [flaky])
    (_, found), _, _ = run.extra_baseline_failures(
        "checkout", {}, ":m:test", "m", "test", "/tmp",
        gradle=gradle, results_of=results_of, repeats=3)
    if found != {flaky}:
        print(f"  FAIL: {found} -- the repeat that answered was not used")
        failures += 1
    if len(calls) != 2:
        print(f"  FAIL: {len(calls)} repeats ran, expected 2")
        failures += 1
    return failures


def case_the_header_records_how_many_targets_there_were_to_choose_from():
    """Depth, again. "16 of 1,059" and "16 of 18" are different claims about a sweep's depth.

    Counted over the WHOLE target population rather than over what a resume had left, so the number
    means the same thing on a first pass and on a tenth -- a filtered count would shrink every time
    the file grew, which is the direction that flatters.
    """
    import json
    import tempfile as _tempfile
    failures = 0
    with _tempfile.TemporaryDirectory() as directory:
        out = os.path.join(directory, "p.json")
        project = {"name": "p", "commit": "c" * 40}
        run.write_results(out, project, "m", None, None, [], 1, targets_available=1059)
        with open(out, encoding="utf-8") as handle:
            header = json.load(handle)
        if header.get("targets_available") != 1059:
            failures += 1
            print(f"  FAIL: targets_available {header.get('targets_available')!r}, expected 1059")

        # ABSENT, NEVER ZERO. A replay draws no targets at all, and a zero there would read as "there
        # was nothing to ask", which is a claim about the project rather than about the run.
        run.write_results(out, project, "m", None, None, [], map_dir=None)
        with open(out, encoding="utf-8") as handle:
            header = json.load(handle)
        if header.get("targets_available") is not None:
            failures += 1
            print(f"  FAIL: a run that drew no targets recorded {header['targets_available']!r}")

        # THE WRITER, not the dict it is handed. `baseline_removed` first shipped keyed by the
        # (class, name) tuple `results_of` returns; every test that inspected the dict passed, and
        # `write_results` threw MID-SERIALIZATION, leaving an unreadable file and losing a whole
        # project's rows on the first project whose baseline repeats found anything failing.
        run.write_results(out, project, "m", None, None, [], 1, targets_available=4,
                          baseline_repeats=3, baseline_answered=2,
                          baseline_removed={"com.acme.T::flaky": [2, 3]})
        with open(out, encoding="utf-8") as handle:
            header = json.load(handle)
        if header.get("baseline_removed") != {"com.acme.T::flaky": [2, 3]}:
            failures += 1
            print(f"  FAIL: baseline_removed did not survive the write: {header.get('baseline_removed')}")
        if (header.get("baseline_repeats"), header.get("baseline_repeats_answered")) != (3, 2):
            failures += 1
            print("  FAIL: the repeat counts did not reach the file")
    return failures


def case_results_are_read_from_a_build_directory_that_is_not_called_build(tmp):
    """hibernate-orm: 24,798 tests filed as "a green build that ran nothing".

    Gradle's default output directory is `build/` and the harness composed that path everywhere.
    hibernate-orm writes into `target/`, so every read found an empty directory -- and an empty read
    is indistinguishable from a suite that never ran, which is exactly the failure this corpus exists
    to detect in someone else's tool. 4,755 result XML files were on disk at the time.
    """
    import os
    failures = 0
    for build in ("build", "target"):
        directory = os.path.join(tmp, build, "m", build, "test-results", "test")
        os.makedirs(directory, exist_ok=True)
        with open(os.path.join(directory, "TEST-a.B.xml"), "w", encoding="utf-8") as handle:
            handle.write('<testsuite name="a.B"><testcase classname="a.B" name="c"/></testsuite>')
        ran, _ = run.results_of(os.path.join(tmp, build), "m", 0, "test", build, log_path=_NO_LOG,
                                task_path=":m:test")
        if not ran:
            print(f"  FAIL: nothing read from a {build}/ layout")
            failures += 1

    # The default is Gradle's own, so every project that has said nothing keeps working.
    if run.build_dir({}) != "build":
        print(f"  FAIL: the default build directory is {run.build_dir({})!r}, expected 'build'")
        failures += 1
    if run.build_dir({"build_dir": "target"}) != "target":
        print("  FAIL: a project file's build_dir was not read")
        failures += 1

    # And the bound reader carries it, which is what stops a call site forgetting.
    read = run.results_reader({"build_dir": "target"})
    if not read(os.path.join(tmp, "target"), "m", 0, "test", _NO_LOG)[0]:
        print("  FAIL: results_reader did not carry the project's build directory")
        failures += 1
    # Pointed at the same tree with the default, it must find nothing rather than guess.
    plain = run.results_reader({})
    if plain(os.path.join(tmp, "target"), "m", 0, "test", _NO_LOG)[0]:
        print("  FAIL: the default reader found results under a directory it was not told about")
        failures += 1
    return failures


def case_every_case_in_this_file_is_registered():
    """A test that is defined and never called has never run, and looks exactly like one that passes."""
    failures = 0
    source = open(__file__, encoding="utf-8").read()
    defined = {m.group(1) for m in re.finditer(r"^def (case_\w+)", source, re.M)}
    # CASES plus the ones `main` calls by name: three of them take a shared temp root, so they are
    # invoked directly rather than through the tuple. Both count as run; neither counts as registered
    # on its own.
    # ANCHORED ON A REAL DEFINITION, NOT ON THIS LINE'S OWN STRING LITERAL. `source.index` found
    # the quoted text right here, so `main_body` spanned almost the whole file and every case_*
    # "registered" itself via its own `def` line -- the guard reported 0 orphans no matter what.
    # Found by review; it had never worked.
    main_body = source[re.search(r"(?m)^def main\(\):", source).start():]
    # ROOTED_CASES is the second tuple, iterated by both entry points: its members are called
    # through the loop, so their names never appear in `main`'s body and a name scan alone reads
    # every one of them as an orphan.
    registered = ({c.__name__ for c in CASES} | {c.__name__ for c in ROOTED_CASES}
                  | set(re.findall(r"case_\w+", main_body)))
    orphans = sorted(defined - registered)
    if orphans:
        print(f"  FAIL: {len(orphans)} case(s) defined and never registered: {', '.join(orphans[:4])}")
        failures += 1
    return failures

def case_a_row_records_the_tree_and_the_flags_it_was_measured_under():
    """The only stamps that can witness a project-file change, so the only ones a reader can check.

    `selector_digest` hashes the selector and not `bench/`; `commit` and `plugin_commit` do not
    move either. So staging a tree or switching a suite onto the Vintage engine changes what is
    measured while ageing no results file, and these two fields are the whole difference between a
    figure a reader can attribute and one they cannot.
    """
    import json
    import tempfile as _tempfile
    failures = 0
    staged = ["a/src/main/java/p/Empty.java"]
    with _tempfile.TemporaryDirectory() as out:
        path = os.path.join(out, "r.json")
        run.write_results(path, {"name": "p", "commit": "c", "url": "u",
                                 "drop_empty_sources": staged,
                                 "flags": ["-I", "scripts/junit-vintage.init.gradle.kts"]},
                          "m", None, None, [])
        stored = json.load(open(path, encoding="utf-8"))
        if stored.get("staged_sources") != staged:
            print(f"  FAIL: staged_sources recorded {stored.get('staged_sources')!r}")
            failures += 1
        if stored.get("flags") != ["-I", "scripts/junit-vintage.init.gradle.kts"]:
            print(f"  FAIL: flags recorded {stored.get('flags')!r}")
            failures += 1

        # A project that stages nothing and passes no flags records [] for both, NOT null. Null is
        # reserved for "this file predates the field", and conflating the two is what let three rows
        # measured without a flag resume into a file whose later rows would have carried it.
        plain = os.path.join(out, "plain.json")
        run.write_results(plain, {"name": "q", "commit": "c", "url": "u"}, "m", None, None, [])
        stored = json.load(open(plain, encoding="utf-8"))
        if (stored.get("staged_sources"), stored.get("flags")) != ([], []):
            print(f"  FAIL: a plain project recorded "
                  f"{stored.get('staged_sources')!r}/{stored.get('flags')!r}, expected []/[] -- "
                  f"null must mean only that the field did not exist")
            failures += 1
        # And with that, a project gaining its first flag IS detected.
        if not run.flags_changed(stored, {"flags": ["--no-configure-on-demand"]}):
            print("  FAIL: a project that gained its first flag was not detected, because a "
                  "recorded empty list was stored as null")
            failures += 1
    return failures


def case_staging_and_flags_treat_a_stored_null_oppositely():
    """The asymmetry that was one branch, and the defect that made it one.

    A stored null means two different things on these two fields, and collapsing them left
    duckduckgo-android resumable against sixteen rows measured at the `*` scope its staging removes.
    Staging: a project that stages today and stored nothing CANNOT have been staged, so those rows
    are stale. Flags: fifteen projects genuinely were measured under flags that predate the stamp,
    so a stored null is unknown rather than none -- treating it as a difference would have aged
    eleven fresh, correct results files to introduce a stamp.
    """
    failures = 0
    staged = {"drop_empty_sources": ["a/src/main/java/p/Empty.java"]}
    flagged = {"flags": ["-I", "scripts/junit-vintage.init.gradle.kts"]}

    # STAGING -- null is a difference when the project stages now. This is the bug, pinned.
    if not run.staging_changed({}, staged):
        print("  FAIL: a stored null was tolerated for a project that stages, so a sweep would "
              "resume rows measured on the unstaged tree")
        failures += 1
    # ... and is not a difference when the project stages nothing, which is every other project.
    if run.staging_changed({}, {}):
        print("  FAIL: a project that stages nothing was read as having changed")
        failures += 1
    if run.staging_changed({"staged_sources": ["a/src/main/java/p/Empty.java"]}, staged):
        print("  FAIL: an unchanged staging set was read as changed")
        failures += 1
    if not run.staging_changed({"staged_sources": ["a/src/main/java/p/Other.java"]}, staged):
        print("  FAIL: a changed staging set was not detected")
        failures += 1

    # FLAGS -- null is NOT a difference, however the project is flagged now.
    if run.flags_changed({}, flagged):
        print("  FAIL: a stored null aged a results file whose flags predate the stamp")
        failures += 1
    if run.flags_changed({}, {}):
        print("  FAIL: a project with no flags and no stored flags was read as changed")
        failures += 1
    # Once recorded, a real change IS detected.
    if not run.flags_changed({"flags": ["-Pother"]}, flagged):
        print("  FAIL: a recorded flag change was not detected")
        failures += 1
    if run.flags_changed({"flags": ["-I", "scripts/junit-vintage.init.gradle.kts"]}, flagged):
        print("  FAIL: unchanged flags were read as changed")
        failures += 1
    # An empty stored list is a RECORDED "no flags", so adding flags is a change.
    if not run.flags_changed({"flags": []}, flagged):
        print("  FAIL: a recorded empty flag list did not detect newly added flags")
        failures += 1
    return failures


def case_every_harness_build_turns_off_the_guests_own_test_selection():
    """A guest's Develocity Predictive Test Selection skipped classes in both arms, ground truth
    included, and one duckduckgo-android miss was that and not this tool."""
    failures = 0
    seen = []
    original = run.run, run.test_progress_paths
    run.run = lambda argv, *args, **kwargs: seen.append(argv) or (0, 0.0)
    run.test_progress_paths = lambda checkout, project: []
    try:
        run.gradle(run.ROOT, {}, [":m:test"], os.devnull)
    finally:
        run.run, run.test_progress_paths = original
    argv = seen[0] if seen else []
    if not any(a == "-I" and b == run.GUEST_SELECTION_OFF for a, b in zip(argv, argv[1:])):
        failures += fail("a harness build does not carry the guest-selection-off init script")
    if not os.path.isfile(run.GUEST_SELECTION_OFF):
        failures += fail(f"the guest-selection-off init script is missing: {run.GUEST_SELECTION_OFF}")
    return failures


# EVERY case_* in this file, checked by the guard below rather than by whoever edits this tuple.
# Six were defined and never registered, so they had never run.
def case_the_checkpoint_survives_what_used_to_destroy_it():
    """Three ways a long sweep lost or misdescribed its checkpoint.

    A write torn mid-serialization left an unreadable file; a hung clone or fetch had no bound; and
    rows measured by the jar published at the start were stamped with the digest of whatever the
    source had been edited into since.
    """
    import json
    import tempfile as _tempfile
    failures = 0
    project = {"name": "p", "commit": "c" * 40}
    with _tempfile.TemporaryDirectory() as directory:
        out = os.path.join(directory, "p.json")
        run.write_results(out, project, "m", None, None, [{"target": "kept"}], 1)
        try:
            run.write_results(out, project, "m", None, None, [{"target": object()}], 1)
            failures += 1
            print("  FAIL: an unserialisable row did not raise")
        except TypeError:
            pass
        try:
            with open(out, encoding="utf-8") as handle:
                rows = json.load(handle)["mutations"]
            if rows != [{"target": "kept"}]:
                failures += 1
                print(f"  FAIL: the previous checkpoint was replaced by {rows!r}")
        except ValueError as error:
            failures += 1
            print(f"  FAIL: a failed write left the checkpoint unreadable: {error}")

        saved = run.PUBLISHED_SELECTOR_DIGEST
        run.PUBLISHED_SELECTOR_DIGEST = "published000"
        try:
            run.write_results(out, project, "m", None, None, [], 1)
            with open(out, encoding="utf-8") as handle:
                stamped = json.load(handle)["selector_digest"]
        finally:
            run.PUBLISHED_SELECTOR_DIGEST = saved
        if stamped != "published000":
            failures += 1
            print(f"  FAIL: rows were stamped {stamped!r}, not the published jar's digest")

    saved = run.GIT_NETWORK_TIMEOUT
    run.GIT_NETWORK_TIMEOUT = 0.5
    try:
        run.git_over_network([sys.executable, "-c", "import time; time.sleep(30)"], check=False)
        failures += 1
        print("  FAIL: a hung network call was not bounded")
    except SystemExit as refusal:
        if "STALLED" not in str(refusal):
            failures += 1
            print(f"  FAIL: the refusal does not say the call stalled: {refusal}")
    finally:
        run.GIT_NETWORK_TIMEOUT = saved
    return failures


def case_a_project_flag_names_an_init_script_in_the_checkout_that_exists():
    """The init scripts a project file passes live in the yoriwake checkout, named by a token so a
    results file's stored flags do not carry a machine's path. Expanded, they must name a file:
    Gradle fails a missing `-I` script, and that failure would be filed against the project."""
    import tomllib
    failures = 0
    project = {"flags": ["-I", run.CHECKOUT_TOKEN + "/scripts/junit-vintage.init.gradle.kts", "-Px"]}
    expanded = run.guest_flags(project)
    if expanded[2] != "-Px" or not os.path.isfile(expanded[1]):
        failures += fail(f"the checkout token did not expand to an init script there: {expanded}")
    if project["flags"][1] != run.CHECKOUT_TOKEN + "/scripts/junit-vintage.init.gradle.kts":
        failures += fail("expanding the flags rewrote the project's own, which a results file stores")
    projects = os.path.join(os.path.dirname(os.path.abspath(__file__)), "projects")
    for name in sorted(os.listdir(projects)):
        with open(os.path.join(projects, name), "rb") as handle:
            flags = run.guest_flags(tomllib.load(handle))
        for flag, value in zip(flags, flags[1:]):
            if flag == "-I" and not os.path.isfile(value):
                failures += fail(f"{name} passes an init script that does not exist: {value}")
    return failures


# The sweep's readers for the isolated arm, the rule fields, the lost-write signal and the
# cost fields. Every one answers None for "could not be asked", which is the property tested.
def _map(directory, schema="6", modes=(), facts=None, first=(), positions=()):
    os.makedirs(directory, exist_ok=True)
    with open(os.path.join(directory, "coverage.tsv"), "w", encoding="utf-8") as handle:
        handle.write("SUCCESSFUL\t10\tp.A,p.B\t[engine:junit-jupiter]/[class:p.ATest]/[method:a()]\n")
        handle.write("NONE\t0\t\t[yoriwake:unattributed]\n")
    with open(os.path.join(directory, "capture-commit"), "w", encoding="utf-8") as handle:
        handle.write("c" * 40 + "\n")
    if schema is not None:
        with open(os.path.join(directory, "schema-version"), "w", encoding="utf-8") as handle:
            handle.write(schema + "\n")
    if modes:
        with open(os.path.join(directory, sweep_data.JVM_MODE_FILE), "w", encoding="utf-8") as handle:
            handle.write("".join(f"j{i}/worker-{i}\t{mode}\n" for i, mode in enumerate(modes)))
    if facts is not None:
        with open(os.path.join(directory, "task-facts"), "w", encoding="utf-8") as handle:
            handle.write(facts)
    for name, lines in ((sweep_data.FIRST_TOUCH_FILE, first), (sweep_data.POSITIONS_FILE, positions)):
        if lines:
            with open(os.path.join(directory, name), "w", encoding="utf-8") as handle:
                handle.write("".join("\t".join(map(str, line)) + "\n" for line in lines))
    return directory


def case_a_schema_six_map_is_read_with_its_capture_mode_and_cost():
    """The fields a row stamps about its map: schema, how it was recorded, size, task facts."""
    failures = 0
    with tempfile.TemporaryDirectory() as root:
        isolated = _map(os.path.join(root, "iso"), modes=("isolated", "isolated"),
                        facts="framework=junit-platform\nparallelism=\nworkers=2\n")
        facts = run.map_facts(isolated)
        if facts["map_schema_version"] != 6:
            failures += fail(f"a schema-6 map read as schema {facts['map_schema_version']!r}")
        if facts["map_capture_mode"] != "isolated" or facts["map_jvm_modes"] != {"isolated": 2,
                                                                                 "shared": 0}:
            failures += fail(f"an isolated map's label was not read: {facts}")
        if facts["map_task_facts"] != {"framework": "junit-platform", "parallelism": None,
                                       "workers": 2}:
            failures += fail(f"task facts were not read: {facts['map_task_facts']}")
        if not isinstance(facts["map_bytes"], int) or facts["map_bytes"] <= 0:
            failures += fail(f"the map's size on disk was not read: {facts['map_bytes']!r}")
        if facts["map_raw_bytes"] is not None:
            failures += fail("a map with no raw records reported a raw size rather than None")
        # The fingerprint the harness already reads is unchanged by schema 6.
        if run.map_fingerprint(isolated)[1:] != (2, 0.5):
            failures += fail(f"the schema-6 coverage.tsv was misread: {run.map_fingerprint(isolated)}")
        for modes, expected in ((("shared", "shared"), "shared"),
                                (("shared", "isolated"), "mixed"), ((), "unlabelled")):
            directory = _map(os.path.join(root, expected), modes=modes)
            if sweep_data.capture_mode(directory) != expected:
                failures += fail(f"{modes} read as {sweep_data.capture_mode(directory)!r}, "
                                 f"not {expected!r}")
        if sweep_data.capture_mode(os.path.join(root, "absent")) is not None:
            failures += fail("a missing map has a capture mode")
        missing = run.map_facts(None)
        if any(value is not None for key, value in missing.items()):
            failures += fail(f"no map, yet facts were reported: {missing}")
    return failures


def case_neither_arm_selects_against_the_other_arms_map():
    """Both arms share one map directory per task; each must refuse the other's recording."""
    failures = 0
    with tempfile.TemporaryDirectory() as root:
        shared = _map(os.path.join(root, "s"), modes=("shared",))
        isolated = _map(os.path.join(root, "i"), modes=("isolated",))
        mixed = _map(os.path.join(root, "m"), modes=("isolated", "shared"))
        old = _map(os.path.join(root, "o"))
        for directory, isolated_arm, fits in ((shared, False, True), (shared, True, False),
                                              (isolated, True, True), (isolated, False, False),
                                              (mixed, True, True), (mixed, False, False),
                                              (old, False, True), (old, True, False),
                                              (None, True, True)):
            answer, why = sweep_data.map_fits_arm(directory, isolated_arm)
            if answer != fits or (not answer and not why):
                failures += fail(f"{directory} for the {'isolated' if isolated_arm else 'shared'} "
                                 f"arm: fits={answer}, why={why!r}")
    if sweep_data.checkout_schema_version(run.SOURCE) is None:
        failures += fail("the checkout's map schema could not be read from AgentContract")
    return failures


def case_a_decision_the_agent_could_not_publish_is_not_believed():
    """The spring shape: a nested class loader's copy of the agent overwrote the task's record."""
    failures = 0
    with tempfile.TemporaryDirectory() as root:
        log = os.path.join(root, "selected.log")
        with open(log, "w", encoding="utf-8") as handle:
            handle.write("> Task :m:test\n[yoriwake] could not write decisions.tsv (writer 1, write 1): "
                         "java.nio.file.NoSuchFileException: a.writing -> decisions.tsv\n"
                         "[yoriwake] could not write decisions.tsv.9-1.part (writer 1, write 1): x\n")
        record = {"notes": {"outcome": "narrowed"}, "decided": [], "included": [], "verdicts": {}}
        kept, failures_seen = run.trusted_decisions(record, log)
        if kept is not None or failures_seen != 2:
            failures += fail(f"a lost write was believed: kept={kept is not None}, "
                             f"count={failures_seen}")
        clean = os.path.join(root, "clean.log")
        with open(clean, "w", encoding="utf-8") as handle:
            handle.write("> Task :m:test\nBUILD SUCCESSFUL\n")
        kept, failures_seen = run.trusted_decisions(record, clean)
        if kept is not record or failures_seen != 0:
            failures += fail("a clean arm's record was not kept")
        if run.decision_write_failures(os.path.join(root, "absent.log")) is not None:
            failures += fail("an unreadable log reported zero lost writes rather than None")
    return failures


def case_every_induced_failure_names_the_rules_that_selected_it():
    """The rules, carried to the failures they are judged on, and the short-record check."""
    failures = 0
    rules = {"[engine:junit-jupiter]/[class:p.ATest]/[method:a()]": ("reaches-change",),
             "[engine:junit-jupiter]/[class:p.ATest]/[method:b(int)]": ("shares-jvm-changed-class",),
             "[engine:junit-jupiter]/[class:p.BTest]/[method:c()]": ("none",)}
    decisions = {"notes": {"input.yoriwake.change.classes": "p.A,p.B"},
                 "decided": [(t, "included/REACHES_CHANGE") for t in rules],
                 "verdicts": {"included/SHARES_JVM_WITH_CHANGE": 1, "included/REACHES_CHANGE": 2},
                 "rules": rules}
    induced = {("p.ATest", "a()"), ("p.ATest", "b(int)[1]"), ("p.CTest", "d")}
    fields = run.sweep_row_fields(decisions, {("p.ATest", "a()")}, induced, 0, None, 1, 1)
    expected = {"p.ATest::a()": ["reaches-change"], "p.ATest::b(int)[1]": ["shares-jvm-changed-class"],
                "p.CTest::d": None}
    if fields["induced_rules"] != expected:
        failures += fail(f"the rules behind the induced failures are wrong: {fields['induced_rules']}")
    if fields["shares_jvm_included"] != 1:
        failures += fail(f"shares-jvm-with-change selections were not counted: "
                         f"{fields['shares_jvm_included']!r}")
    if fields["change_classes"] != 2 or fields["decision_rows"] != 3 \
            or fields["decision_covers_arm"] is not True:
        failures += fail(f"change shape or record size wrong: {fields}")
    short = run.sweep_row_fields(decisions, {(f"p.T{i}", "t") for i in range(5)}, set(), 0, None, 1, 1)
    if short["decision_covers_arm"] is not False:
        failures += fail("a record deciding fewer tests than the arm ran was not flagged")
    absent = run.sweep_row_fields(None, set(), induced, None, None, 1, 1)
    if any(absent[k] is not None for k in ("induced_rules", "decision_rows", "change_classes",
                                            "shares_jvm_included", "decision_covers_arm")):
        failures += fail(f"no record, yet rule fields were reported: {absent}")
    return failures


def case_the_widest_first_touch_windows_are_named():
    """Which changed class selects most under the first-touch rule, with `*` counting for all."""
    failures = 0
    with tempfile.TemporaryDirectory() as root:
        directory = _map(root, first=(("j1", 1, "p.Early"), ("j1", 3, "p.Late"), ("j2", 2, "*")),
                         positions=(("j1", 1, "t1"), ("j1", 3, "t2"), ("j1", 4, "t3"),
                                    ("j2", 1, "u1"), ("j2", 2, "u2")))
        widths = sweep_data.widest_windows(directory, ["p.Late", "p.Early", "p.Never"])
        if widths != [["p.Early", 4, 0.8], ["p.Late", 3, 0.6], ["p.Never", 1, 0.2]]:
            failures += fail(f"the windows are wrong: {widths}")
        if sweep_data.widest_windows(directory, None) is not None:
            failures += fail("no change set, yet windows were reported")
        if sweep_data.widest_windows(os.path.join(root, "absent"), ["p.A"]) is not None:
            failures += fail("a map without the files reported windows rather than None")
    return failures


def case_an_arm_s_cost_is_read_from_its_own_instruments():
    """Task durations, the plugin's timers and the sampled test JVMs, with None for not asked."""
    failures = 0
    with tempfile.TemporaryDirectory() as root:
        times = os.path.join(root, "t.tsv")
        with open(times, "w", encoding="utf-8") as handle:
            handle.write(":m:compileJava\t1000000\t1002000\tup-to-date\n"
                         ":m:test\t1002000\t1010500\texecuted\n"
                         ":m:yoriwakeDecodeTest\t1010500\t1011000\texecuted\n")
        read = sweep_data.read_task_times(times, ":m:test", 997.5, 1012.0)
        if read != {"test_task_seconds": 8.5, "test_task_outcome": "executed",
                    "decode_seconds": 0.5, "before_first_task_seconds": 2.5,
                    "after_last_task_seconds": 1.0, "tasks_executed": 2}:
            failures += fail(f"task times misread: {read}")
        if sweep_data.read_task_times(os.path.join(root, "absent"), ":m:test", 0, 0) is not None:
            failures += fail("no task times file, yet task times were reported")
    timers = sweep_data.plugin_timers({"configure": 3, "configure.millis": 40, "git.diff": 2,
                                       "git.diff.millis": 7, "git.log.millis": 5})
    # Written before the plugin had the listing timer: None, not zero.
    if timers != {"configure_millis": 40, "worktree_listing_millis": None, "git_millis": 12,
                  "git_calls": 2}:
        failures += fail(f"plugin timers misread: {timers}")
    listed = sweep_data.plugin_timers({"configure.millis": 40, "worktreeListing": 1,
                                       "worktreeListing.millis": 9})
    if listed["worktree_listing_millis"] != 9 or listed["git_millis"] is not None:
        failures += fail(f"the working-tree listing timer misread: {listed}")
    if sweep_data.plugin_timers({}) is not None:
        failures += fail("an absent counters file read as timers")
    listings = iter([[(1, 2048, "/j/bin/java -cp x worker.GradleWorkerMain 'Gradle Test Executor 1'"),
                      (2, 999999, "/j/bin/java GradleDaemon")],
                     [(1, 4096, "/j/bin/java w 'Gradle Test Executor 1'"),
                      (3, 1024, "/j/bin/java w 'Gradle Test Executor 2'")]])
    sampler = sweep_data.TestJvmSampler(ps=lambda: next(listings))
    sampler.sample()
    sampler.sample()
    stats = sampler.stats()
    if (stats["test_jvms"], stats["peak_test_jvm_rss_mb"], stats["peak_total_test_jvm_rss_mb"],
            stats["rss_samples"]) != (2, 4.0, 5.0, 2):
        failures += fail(f"the sampler misread the test JVMs: {stats}")
    blind = sweep_data.TestJvmSampler(ps=lambda: None)
    blind.sample()
    if blind.stats()["test_jvms"] is not None:
        failures += fail("a machine that cannot run ps reported zero test JVMs")
    return failures


def case_the_raw_evidence_is_kept_and_archived_without_the_harness_copies():
    """Raw records copied without .exec unless asked, and the archive skips map-before copies."""
    failures = 0
    import tarfile
    with tempfile.TemporaryDirectory() as root:
        worker = os.path.join(root, "map", "raw", "worker-1")
        os.makedirs(worker)
        for name in ("index.tsv", "touches.tsv", "000001.exec"):
            with open(os.path.join(worker, name), "w", encoding="utf-8") as handle:
                handle.write("x\t" + ("all\tchild process: /bin/sh\n" if name == "touches.tsv"
                                      else "\n"))
        kept = os.path.join(root, "scratch", "raw", "row")
        if sweep_data.keep_raw(os.path.join(root, "map"), kept, time.time() + 60, False) is not None:
            failures += fail("raw records older than the arm were kept as the arm's")
        sweep_data.keep_raw(os.path.join(root, "map"), kept, 0, with_exec=False)
        if sorted(os.listdir(os.path.join(kept, "worker-1"))) != ["index.tsv", "touches.tsv"]:
            failures += fail(f"the kept raw records are wrong: {os.listdir(os.path.join(kept, 'worker-1'))}")
        if sweep_data.touch_all_reasons(os.path.join(root, "map")) != {"child process": 1}:
            failures += fail(f"the capture's `all` reasons were not counted: "
                             f"{sweep_data.touch_all_reasons(os.path.join(root, 'map'))}")
        os.makedirs(os.path.join(root, "scratch", "map-before-row"))
        archive = os.path.join(root, "evidence", "p-shared.tar.gz")
        sweep_data.archive_evidence(archive, [(os.path.join(root, "scratch"), "p")])
        with tarfile.open(archive) as tar:
            names = tar.getnames()
        if "p/raw/row/worker-1/touches.tsv" not in names or any("map-before" in n for n in names):
            failures += fail(f"the archive holds the wrong members: {names}")
    return failures


def case_the_stopping_rule_counts_narrowed_rows_and_never_misses():
    """`--narrowed-target` ends a project's draw on narrowed rows that induced a failure, and on
    nothing a miss decides: rows that differ only in what was missed or set aside count the same."""
    failures = 0
    narrowed = {"status": "checked", "decision_outcome": "narrowed",
                "selected_distinct": 3, "full_distinct": 40, "full_failures": 2}
    rows = {
        "narrowed": (narrowed, True),
        "narrowed, one failure missed": ({**narrowed, "missed": ["A::a"]}, True),
        # Set aside only after it was missed; counting on answered failures would read it as 0.
        "narrowed, its only failure fails unmutated": (
            {**narrowed, "full_failures": 1, "fails_unmutated": ["A::a"]}, True),
        "forced": ({**narrowed, "decision_outcome": "full-run", "selected_distinct": 40}, False),
        "forced, counts one short": ({**narrowed, "decision_outcome": "full-run",
                                      "selected_distinct": 39}, False),
        # Under --recall-only a forced row's full arm IS its selected arm.
        "forced, recall-only": ({**narrowed, "decision_outcome": "full-run",
                                 "selected_distinct": 40, "full_seconds": None,
                                 "full_arm_skipped": True}, False),
        "narrowed, broke nothing": ({**narrowed, "status": "no-effect", "full_failures": 0}, False),
        "invalid": ({"status": "invalid", "decision_outcome": None}, False),
        "stalled": ({"status": "stalled", "phase": "selected"}, False),
    }
    for what, (row, expected) in rows.items():
        if run.counts_toward_narrowed_target(row) is not expected:
            failures += fail(f"{what}: counted {not expected}, expected {expected}")
    if run.narrowed_target_reached([narrowed] * 20, None):
        failures += fail("no --narrowed-target stopped the draw")
    forced = rows["forced"][0]
    # Resumed rows count, as they do toward --limit: a resume must not draw past the target.
    if not run.narrowed_target_reached([narrowed, forced, narrowed], 2):
        failures += fail("two narrowed rows did not reach a target of two")
    if run.narrowed_target_reached([narrowed, forced, forced, forced], 2):
        failures += fail("forced rows counted toward the narrowed target")
    return failures


# Two `override fun equals` in one file share a label. mockk's isolated arm applied the first and
# was judged against the second's ground truth: 2 misses and 573 "ran but passed" on a row that
# excluded nothing.
_EQUALS = "src/main/kotlin/F.kt::body::equals"


def _paired(index, full, induced, target=_EQUALS, **extra):
    return {"target": target, "kind": "body", "index": index, "status": "checked",
            "full_distinct": full, "induced": induced, **extra}


def case_paired_ground_truth_is_the_same_mutations():
    """the isolated row for index 0 reads index 0's ground truth, never index 1's."""
    failures = 0
    rows = [_paired(0, 743, ["F::a"]), _paired(1, 745, ["F::a", "F::b"])]
    table, duplicates = run.paired_ground_truth(rows)
    if duplicates:
        failures += fail(f"two indices of one label were read as duplicates: {duplicates}")
    first = run.keyed_lookup(table, _EQUALS, "body", 0)
    if not first or first["full_distinct"] != 743 or first["induced"] != ["F::a"]:
        failures += fail(f"index 0 was judged against another row's ground truth: {first}")
    second = run.keyed_lookup(table, _EQUALS, "body", 1)
    if not second or second["full_distinct"] != 745:
        failures += fail(f"index 1 lost its own ground truth: {second}")
    # One label at two indices is two rows to measure, so `--limit` is two.
    if len(table) != 2:
        failures += fail(f"the paired arm counts {len(table)} rows for two mutations")
    return failures


def case_a_paired_file_holding_one_mutation_twice_is_refused():
    """either row could be the one the isolated arm reproduces, so neither is guessed."""
    failures = 0
    rows = [_paired(0, 10, ["T::a"], target="T.java::schema::X"),
            _paired(0, 12, ["T::b"], target="T.java::schema::X"),
            _paired(1, 9, [], target="T.java::schema::X")]
    _, duplicates = run.paired_ground_truth(rows)
    if duplicates != {("T.java::schema::X", "body", 0): 2}:
        failures += fail(f"a mutation recorded twice was not named with its count: {duplicates}")
    return failures


def case_a_paired_row_without_an_index_is_matched_by_its_label():
    """Rows written before `index` was recorded match on label, as `pending_targets` does."""
    failures = 0
    legacy = {"target": "T.java::body::m", "status": "checked", "full_distinct": 4,
              "induced": []}
    table, _ = run.paired_ground_truth([legacy])
    if run.keyed_lookup(table, "T.java::body::m", "body", 3) is not legacy:
        failures += fail("a paired row with no index was not found by its label")
    # A row that asked no question is not ground truth, and stays out of the table.
    table, _ = run.paired_ground_truth([{**_paired(0, 4, []), "status": "stalled"},
                                        {**_paired(1, 4, None)}])
    if table:
        failures += fail(f"rows with no usable ground truth were paired: {sorted(table)}")
    return failures


def case_one_label_at_two_indices_gets_two_log_stems():
    """Logs and decision records were named by label, so index 1's overwrote index 0's."""
    failures = 0
    if run.log_slug(_EQUALS, 0) == run.log_slug(_EQUALS, 1):
        failures += fail("two declarations of one name share a log stem")
    if run.log_slug(_EQUALS, 1) != run.log_slug(_EQUALS, 1):
        failures += fail("the same mutation was given two stems")
    # The stem a row written before this change was kept under, for the readers of old evidence.
    if run.log_slug(_EQUALS) == run.log_slug(_EQUALS, 0):
        failures += fail("the label-only stem moved, so kept evidence can no longer be found")
    return failures


def case_a_resume_measures_the_other_declarations_of_a_measured_label():
    """A file holding (T, body, 0) skips index 0 and still measures index 1 of the same label."""
    failures = 0
    done = {run.row_key(row) for row in [_paired(0, 4, [])]}
    if not run.keyed_in(done, _EQUALS, "body", 0):
        failures += fail("a measured mutation was measured again on resume")
    if run.keyed_in(done, _EQUALS, "body", 1):
        failures += fail("index 1 was skipped because index 0 of the same label was measured")
    # A row with no index stands for every declaration of its label, as it always has.
    legacy = {run.row_key({"target": _EQUALS, "status": "stalled"})}
    if not run.keyed_in(legacy, _EQUALS, "body", 1):
        failures += fail("a row with no index no longer covers its label")
    return failures


def case_a_rejected_declaration_does_not_reject_its_namesakes():
    """A rejected label at index 0 must not stop index 1 of the same label being compiled."""
    failures = 0
    stored = {"rejected_targets": [_EQUALS, "Old.java::body::m"],
              "rejected_mutations": [[_EQUALS, "body", 0]]}
    rejected = run.rejected_keys(stored)
    if not run.keyed_in(rejected, _EQUALS, "body", 0):
        failures += fail("the rejected mutation was offered again")
    if run.keyed_in(rejected, _EQUALS, "body", 1):
        failures += fail("index 1 was skipped because index 0 of its label was rejected")
    # A label rejected before the index was recorded still stands for every declaration of it.
    if not run.keyed_in(rejected, "Old.java::body::m", "body", 4):
        failures += fail("a rejection written before this change was forgotten on resume")
    return failures


def case_overlapping_source_roots_measure_each_mutation_once():
    """spring-framework lists a directory and three inside it, so one mutation was drawn twice."""
    failures = 0
    targets = [("A.java", "body", 0), ("B.java", "body", 0), ("A.java", "body", 0),
               ("A.java", "body", 1), ("B.java", "body", 0)]
    unique, dropped = run.distinct_targets(targets)
    if unique != [("A.java", "body", 0), ("B.java", "body", 0), ("A.java", "body", 1)]:
        failures += fail(f"repeated targets were not dropped in order: {unique}")
    if dropped != 2:
        failures += fail(f"the drop count is {dropped}, expected 2")
    return failures


def _write(root, relative, text="x\n"):
    path = os.path.join(root, relative.replace("/", os.sep))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="") as handle:
        handle.write(text)


def case_the_reset_removes_files_a_test_run_wrote_and_nothing_else(tmp):
    """iceberg's `core/derby.log`, reduced: an ignored file a baseline wrote forced every row."""
    failures = 0
    root = _git_checkout(tmp, "untracked-reset")
    for relative, text in ((".gitignore", "*.log\nbuild/\ntarget/\n.gradle/\n.kotlin/\n"),
                           ("build.gradle", ""), ("core/build.gradle", "")):
        _write(root, relative, text)
    subprocess.run(["git", "-C", root, "add", "-A"], check=True)
    subprocess.run(["git", "-C", root, "commit", "--quiet", "-m", "build"], check=True)
    _write(root, "present-at-checkout.log")
    project = {"build_dir": "target"}
    snapshot = run.untracked_files(root, project)
    if snapshot != {"present-at-checkout.log"}:
        failures += fail(f"the snapshot of a prepared checkout is {sorted(snapshot)}")
    written = ("core/derby.log", "stray.txt", "docs/build/notes.txt")
    kept = (".gradle/yoriwake/coverage.tsv", "core/build/gen/versions.properties",
            "core/target/out.txt", ".kotlin/sessions/s", "present-at-checkout.log")
    for relative in written + kept:
        _write(root, relative)
    removed = run.reset_untracked(root, project, snapshot)
    if removed != sorted(written):
        failures += fail(f"the reset removed {removed}, not the files a test run wrote "
                         f"{sorted(written)}")
    for relative in written:
        if os.path.exists(os.path.join(root, relative)):
            failures += fail(f"{relative} survived the reset")
    for relative in kept:
        if not os.path.exists(os.path.join(root, relative)):
            failures += fail(f"the reset removed {relative}, which is the map, build output or a "
                             f"file present at checkout")
    snapshot_path = os.path.join(tmp, "untracked-snapshot.json")
    run.write_untracked_snapshot(snapshot_path, "stamp-a", snapshot)
    if run.read_untracked_snapshot(snapshot_path, "stamp-a") != snapshot:
        failures += fail("a saved snapshot does not read back for its own capture")
    if run.read_untracked_snapshot(snapshot_path, "stamp-b") is not None:
        failures += fail("a snapshot saved for one capture was read for another")
    return failures


def case_the_preflight_edit_moves_no_line(tmp):
    """A trailing comment on the `package` line: same lines, so the same class bytes."""
    failures = 0
    sources = {"Real.java": "package p;\n\nclass Real {\n  int f() { return 1; }\n}\n",
               "Real.kt": "package p.q\r\n\r\nfun f() = 1\r\n",
               "Default.java": "class Default {}\n"}
    for name, text in sources.items():
        _write(tmp, f"preflight/{name}", text)
        line = run.apply_preflight_edit(tmp, f"preflight/{name}")
        with open(os.path.join(tmp, "preflight", name), encoding="utf-8", newline="") as handle:
            after = handle.read()
        if name == "Default.java":
            if line is not None or after != text:
                failures += fail("a source without a package line was edited")
            continue
        before_lines, after_lines = text.split("\n"), after.split("\n")
        if line != 1 or len(after_lines) != len(before_lines):
            failures += fail(f"the preflight edit of {name} moved a line (line {line})")
        if after_lines[1:] != before_lines[1:] or run.PREFLIGHT_COMMENT not in after_lines[0]:
            failures += fail(f"the preflight edit of {name} changed more than its package line")
        if before_lines[0].endswith("\r") and not after_lines[0].endswith("\r"):
            failures += fail(f"the preflight edit of {name} dropped its line ending")
    return failures


_FOO_ROW = "core/src/main/java/org/x/Foo.java::body::m"


def _forced(target, reason, kind="unmappable-paths", outcome="full-run", **extra):
    return {"target": target, "decision_outcome": outcome, "full_run_kind": kind,
            "decision_reason": reason, "missed": [], **extra}


def _decision(outcome, kind=None, reason=None):
    return {"notes": {"outcome": outcome, "full-run-kind": kind, "full-run-reason": reason}}


def case_a_preflight_that_forced_for_its_setup_says_so():
    """Forced on a path nothing changed: setup-forced. On the edited file: rows are drawn."""
    failures = 0
    derby = "changed paths the coverage map cannot see: core/derby.log"
    expectations = (
        (_decision("full-run", "unmappable-paths", derby), derby),
        (_decision("full-run", "map-unusable", "the map was written by schema 6"),
         "the map was written by schema 6"),
        (_decision("full-run", "unmappable-paths",
                   "changed paths the coverage map cannot see: core/src/main/java/org/x/Foo.java"),
         None),
        (_decision("full-run", "daemon-refused", "org.x.Foo declares a compile-time constant"),
         None),
        (_decision("full-run", "unmappable-paths", "org.x.Foo$Inner cannot be mapped"), None),
        (_decision("full-run", "unmappable-paths", "org.x.FooBar cannot be mapped"),
         "org.x.FooBar cannot be mapped"),
        (_decision("full-run", "startup-coverage",
                   "the change touches startup coverage, which no test owns"), None),
        (_decision("narrowed"), None),
        (None, None),
    )
    for decisions, wanted in expectations:
        got = run.setup_forced_reason(decisions, _FOO_ROW)
        if got != wanted:
            failures += fail(f"a preflight decided {decisions and decisions['notes']} read as "
                             f"setup-forced {got!r}, not {wanted!r}")
    return failures


def case_three_identical_project_forces_stop_the_project():
    """Three forced rows in a row, one reason naming nothing they changed: setup-forced."""
    failures = 0
    derby = "changed paths the coverage map cannot see: core/derby.log"
    bar = "core/src/main/java/org/x/Bar.java::schema::B"
    baz = "core/src/main/java/org/x/Baz.kt::body::z"
    cases = {
        "three identical on a path none changed": (
            [_forced(_FOO_ROW, derby), _forced(bar, derby), _forced(baz, derby)], derby),
        "identical, naming a class one row changed": (
            [_forced(r, "x: org.x.Bar") for r in (_FOO_ROW, bar, baz)], None),
        "identical, naming a path one row changed": (
            [_forced(r, "x: core/src/main/java/org/x/Baz.kt") for r in (_FOO_ROW, bar, baz)],
            None),
        "two identical, then a different one": (
            [_forced(_FOO_ROW, derby), _forced(bar, derby), _forced(baz, derby + ", b")], None),
        "a stray file forces two rows and is then gone": (
            [_forced(_FOO_ROW, derby), _forced(bar, derby),
             _forced(baz, None, kind=None, outcome="narrowed")], None),
        "three of a fixed-text kind": (
            [_forced(r, "the change touches startup coverage, which no test owns",
                     kind="startup-coverage") for r in (_FOO_ROW, bar, baz)], None),
        "a row without a decision neither extends nor breaks the run": (
            [_forced(_FOO_ROW, derby), {"target": bar, "status": "stalled"}, _forced(bar, derby),
             _forced(baz, derby)], derby),
        "only two rows so far": ([_forced(_FOO_ROW, derby), _forced(bar, derby)], None),
    }
    for name, (rows, wanted) in cases.items():
        got = run.setup_forced_streak(rows)
        if got != wanted:
            failures += fail(f"{name}: setup-forced {got!r}, not {wanted!r}")
        flipped = [{**row, "missed": ["org.x.FooTest::t"]} for row in rows]
        if run.setup_forced_streak(flipped) != got:
            failures += fail(f"{name}: flipping the rows' missed sets changed the verdict")
    aliased = [_forced(r, "x: org.x.Hidden") for r in (_FOO_ROW, bar, baz)]
    if run.setup_forced_streak(aliased, declared=lambda rel: ["Hidden"] if "Bar" in rel else []) \
            is not None:
        failures += fail("a reason naming a type a row's file declares read as setup-forced")
    return failures


def case_a_timing_project_times_its_first_rows_whatever_they_decide():
    """Rows 1-8 run both arms, forced or narrowed; row 9 runs recall-only."""
    failures = 0
    timed = [run.is_timed(position, False, 8) for position in range(1, 11)]
    if timed != [True] * 8 + [False] * 2:
        failures += fail(f"a timing project capped at 8 timed {timed}")
    if not all(run.is_timed(position, False, None) for position in (1, 9, 40)):
        failures += fail("an uncapped timing sweep left a row untimed")
    if any(run.is_timed(position, True, None) for position in (1, 9)):
        failures += fail("a --recall-only row was timed")
    forced = {"notes": {"outcome": "full-run"}}
    ran = {("p.T", "t")}
    if run.full_arm_skipped(True, forced, ran):
        failures += fail("a forced row among the timed rows skipped its full arm, so it has no "
                         "paired time")
    if not run.full_arm_skipped(False, forced, ran):
        failures += fail("a forced row past the cap paid for a second full run")
    if run.full_arm_skipped(False, {"notes": {"outcome": "narrowed"}}, ran):
        failures += fail("a narrowed row past the cap took its selected arm as ground truth")
    return failures


_JUP = "[engine:junit-jupiter]"


def _id(cls, method, nested=None):
    return (f"{_JUP}/[class:{cls}]" + (f"/[nested-class:{nested}]" if nested else "")
            + f"/[method:{method}()]")


def _narrowed(kept, dropped, rules=None):
    """A narrowed decision record: `kept` included, `dropped` excluded, and their rule tokens."""
    decided = ([(test, "included/REACHES_CHANGE") for test in kept]
               + [(test, "excluded/SKIPPED") for test in dropped])
    return {"notes": {"outcome": "narrowed"}, "included": list(kept), "decided": decided,
            "verdicts": Counter(v for _, v in decided),
            "rules": rules if rules is not None else {t: ("reaches-change",) for t in kept}
            | {t: ("none",) for t in dropped}}


_PROFILE = {"suite_distinct": 40, "class_seconds": {"p.KeptTest": 50.0, "p.DropTest": 10.0,
                                                     "p.Outer": 10.0, "p.Other": 30.0}}


_NO_SHARED_JVM_MAP = []


def _quiet_map():
    """A map whose capture ran no class after another in one JVM: the shared-JVM check never fires on it."""
    if not _NO_SHARED_JVM_MAP:
        directory = tempfile.mkdtemp(prefix="yoriwake-run-map-")
        open(os.path.join(directory, "positions.tsv"), "w").close()
        _NO_SHARED_JVM_MAP.append(directory)
    return _NO_SHARED_JVM_MAP[0]


def _route(decisions, mode="auto", before=2, flagged=False, single=False, timed=False,
           switched_off=False, profile=_PROFILE, map_dir=None, label="p/A.java::body::f",
           priced=True):
    return run.route_ground_truth(mode, timed, decisions, switched_off, profile, before,
                                  map_dir or _quiet_map(), flagged, single, label, "body", 0, 7,
                                  priced=priced)


def _unsampled(decisions, **extra):
    """A label this sweep's hash does not sample, so only the rule under test can route the row."""
    label = next(f"p/A{n}.java::body::f" for n in range(200)
                 if not run.sampled_check(f"p/A{n}.java::body::f", "body", 0, 7))
    return _route(decisions, label=label, **extra)


def case_a_complement_runs_the_dropped_tests_classes_whole():
    """The classes holding a dropped test, top-level, nested ones run with them."""
    failures = 0
    decisions = _narrowed([_id("p.KeptTest", "a")],
                          [_id("p.DropTest", "b"), _id("p.Outer", "c", nested="Inner"),
                           _id("p.DropTest", "d")])
    if run.complement_classes(decisions) != ["p.DropTest", "p.Outer"]:
        failures += fail(f"the complement's classes are {run.complement_classes(decisions)}")
    if run.complement_filters(["p.Outer"]) != ["--tests", "p.Outer", "--tests", "p.Outer$*"]:
        failures += fail("the complement does not run a class whole, nested classes included")
    unnamed = _narrowed([], [f"{_JUP}/[test-factory:x()]"])
    if run.complement_classes(unnamed) is not None:
        failures += fail("a dropped test that names no class gave a complement")
    return failures


def case_a_complement_row_reads_its_misses_from_both_runs():
    """induced is both runs' failures less the already-failing set; a dropped failure is missed."""
    failures = 0
    selected = run.Outcomes({("p.KeptTest", "a"), ("p.KeptTest", "e")}, {("p.KeptTest", "e")})
    complement = run.Outcomes({("p.DropTest", "b"), ("p.DropTest", "d"), ("p.DropTest", "f")},
                              {("p.DropTest", "b"), ("p.DropTest", "f")})
    already = run.Outcomes(None, {("p.DropTest", "f")}, cause="xml")
    decisions = _narrowed([_id("p.KeptTest", "a"), _id("p.KeptTest", "e")],
                          [_id("p.DropTest", "b"), _id("p.DropTest", "d"), _id("p.DropTest", "f")])
    truth = run.union_outcomes(selected, complement)
    keys = run.row_keys(selected, truth, decisions, "complement")
    induced = run.subtract_baseline(run.keyed(truth, keys.scheme)[1], already, keys)
    if induced != {("p.KeptTest", "e"), ("p.DropTest", "b")}:
        failures += fail(f"a complement row's induced set is {sorted(induced)}")
    missed = run.unmatched(induced, run.keyed(selected, keys.scheme)[0], lambda test: False)[0]
    if missed != [("p.DropTest", "b")]:
        failures += fail(f"a dropped test failing in the complement read as missed {missed}")
    return failures


def case_the_first_rows_and_flagged_projects_carry_a_full_arm():
    failures = 0
    decisions = _narrowed([_id("p.KeptTest", "a")], [_id("p.DropTest", "b")])
    for before, wanted in ((0, run.CHECKED_TRUTH), (1, run.CHECKED_TRUTH)):
        truth, rules, _ = _unsampled(decisions, before=before)
        if truth != wanted or "first-rows" not in rules:
            failures += fail(f"untimed narrowed row {before + 1} ran {truth} ({rules})")
    truth, rules, classes = _unsampled(decisions, before=2)
    if truth != run.COMPLEMENT_TRUTH or rules or classes != ["p.DropTest"]:
        failures += fail(f"the third untimed narrowed row ran {truth} ({rules})")
    truth, rules, _ = _unsampled(decisions, before=5, flagged=True)
    if truth != run.CHECKED_TRUTH or rules != ["order-dependent-project"]:
        failures += fail(f"a project with an order-dependent test ran {truth} ({rules})")
    truth, rules, _ = _unsampled(decisions, before=5, single=True)
    if truth != run.CHECKED_TRUTH or "paired-arm" not in rules:
        failures += fail(f"a single-JVM suite, whose isolated arm pairs, ran {truth}")
    truth, rules, _ = _unsampled(decisions, mode="check", before=9)
    if truth != run.CHECKED_TRUTH or "check-mode" not in rules:
        failures += fail(f"--ground-truth check ran {truth} on an untimed narrowed row")
    for name, extra in (("--ground-truth full", {"mode": "full"}), ("a timed row", {"timed": True}),
                        ("a switched-off project", {"switched_off": True})):
        truth, _, _ = _unsampled(decisions, before=5, **extra)
        if truth != run.FULL_TRUTH:
            failures += fail(f"{name} ran {truth}")
    forced = {**decisions, "notes": {"outcome": "full-run"}}
    if _unsampled(forced, before=5)[0] != run.FULL_TRUTH:
        failures += fail("a forced row took a complement")
    return failures


def case_a_complement_that_saves_little_or_cannot_be_priced_runs_the_full_arm():
    failures = 0
    decisions = _narrowed([_id("p.DropTest", "b")], [_id("p.KeptTest", "a"), _id("p.Other", "o")])
    truth, rules, _ = _unsampled(decisions, before=5)
    if truth != run.FULL_TRUTH or rules != ["cost"]:
        failures += fail(f"a complement of 80 of 100 suite seconds ran {truth} ({rules})")
    if _unsampled(decisions, before=5, priced=False)[0] == run.FULL_TRUTH:
        failures += fail("an unpriced control run still applied the cost rule")
    cheap = _narrowed([_id("p.KeptTest", "a")], [_id("p.Missing", "b")])
    truth, rules, _ = _unsampled(cheap, before=5)
    if truth != run.FULL_TRUTH or rules != ["unpriced"]:
        failures += fail(f"a complement whose class the baseline never timed ran {truth} ({rules})")
    if _unsampled(decisions, before=5, profile=None)[0] != run.FULL_TRUTH:
        failures += fail("a row with no suite profile took a complement")
    return failures


def case_a_dropped_class_after_a_shared_jvm_neighbour_is_a_check_row(tmp):
    """The shared-JVM check, narrow form: read from the capture's per-JVM order before the row runs."""
    failures = 0
    map_dir = os.path.join(tmp, "map-rule-2")
    os.makedirs(map_dir, exist_ok=True)
    with open(os.path.join(map_dir, "positions.tsv"), "w", encoding="utf-8") as handle:
        handle.write(f"jvm-1\t1\t{_id('p.KeptTest', 'a')}\n"
                     f"jvm-1\t3\t{_id('p.SharedTest', 's')}\n"
                     f"jvm-1\t5\t{_id('p.DropTest', 'b')}\n"
                     f"jvm-2\t1\t{_id('p.Other', 'o')}\n")
    kept, shared = _id("p.KeptTest", "a"), _id("p.SharedTest", "s")
    rules = {kept: ("reaches-change",), shared: ("shares-jvm-changed-class",)}
    after = _narrowed([kept, shared], [_id("p.DropTest", "b")], rules={**rules,
                      _id("p.DropTest", "b"): ("none",)})
    if not run.ran_after_shared_jvm_kept(after, map_dir):
        failures += fail("a dropped class that ran after a shared-JVM-kept class in its JVM was "
                         "not a check row")
    elsewhere = _narrowed([kept, shared], [_id("p.Other", "o")], rules={**rules,
                          _id("p.Other", "o"): ("none",)})
    if run.ran_after_shared_jvm_kept(elsewhere, map_dir):
        failures += fail("a dropped class in another JVM was routed as sharing one")
    if not run.ran_after_shared_jvm_kept({**after, "rules": None}, map_dir):
        failures += fail("a record with no rules took a complement without the shared-JVM check")
    profile = {"suite_distinct": 4, "class_seconds": {"p.KeptTest": 9.0, "p.SharedTest": 9.0,
                                                       "p.DropTest": 1.0, "p.Other": 1.0}}
    truth, got, _ = _unsampled(after, before=5, profile=profile, map_dir=map_dir)
    if truth != run.CHECKED_TRUTH or got != ["shares-jvm"]:
        failures += fail(f"the shared-JVM check routed the row to {truth} ({got})")
    return failures


def case_a_disagreement_names_the_test_and_both_verdicts():
    failures = 0
    selected = run.Outcomes({("p.KeptTest", "a")}, set())
    complement = run.Outcomes({("p.DropTest", "b")}, set())
    full = run.Outcomes({("p.KeptTest", "a"), ("p.DropTest", "b")}, {("p.DropTest", "b")})
    nothing = run.Outcomes(None, set(), cause="xml")
    found = run.complement_disagreements(selected, complement, full, None, nothing)
    if found != [{"test": "p.DropTest::b", "verdict": "induced", "complement": False,
                  "full": True}]:
        failures += fail(f"a dropped test the suite fails and its class alone passes gave {found}")
    agreeing = run.Outcomes(complement[0], {("p.DropTest", "b")})
    if run.complement_disagreements(selected, agreeing, full, None, nothing):
        failures += fail("a complement agreeing with the full arm was reported as disagreeing")
    rows = [{"ground_truth": "complement"}, {"ground_truth": "complement+full"},
            {"ground_truth": "full"}, {"ground_truth": None}]
    if run.complement_rows_to_rerun(rows) != [rows[0]]:
        failures += fail("a switch-off would re-measure rows that already had full-arm truth")
    return failures


def case_a_complement_row_not_keyed_per_method_runs_the_full_arm():
    """The fallbacks: XML keys, or a failure only a shared key would decide."""
    failures = 0
    decisions = _narrowed([_id("p.KeptTest", "a")], [_id("p.DropTest", "b")])
    nothing = run.Outcomes(None, set(), run.Methods(None, set()))
    selected = run.Outcomes({("p.KeptTest", "a")}, set(),
                            run.Methods({("p.KeptTest", "a")}, set()))
    complement = run.Outcomes({("p.DropTest", "b")}, {("p.DropTest", "b")},
                              run.Methods({("p.DropTest", "b")}, {("p.DropTest", "b")}))
    if run.complement_needs_full(selected, run.union_outcomes(selected, complement), decisions,
                                 nothing):
        failures += fail("a complement row keyed per method with no shared key ran the full arm")
    xml = run.Outcomes(complement[0], complement[1], cause="no listener file")
    if not run.complement_needs_full(selected, run.union_outcomes(selected, xml), decisions,
                                     nothing):
        failures += fail("a complement row on XML keys took the complement as its truth")
    collapsed = run.Outcomes({("p.KeptTest", "a")}, set(),
                             run.Methods({("p.KeptTest", "a"), ("p.DropTest", "b")}, set(),
                                         {("p.DropTest", "b")}))
    mixed = _narrowed([_id("p.KeptTest", "a"), _id("p.DropTest", "k")], [_id("p.DropTest", "b")])
    if not run.complement_needs_full(collapsed, run.union_outcomes(collapsed, complement),
                                     mixed, nothing):
        failures += fail("a complement failure on a key a selected sibling shares took the "
                         "complement as its truth")
    return failures


def case_a_switch_off_outlives_its_invocation_and_owed_rows_stay_owed():
    """A resumed leg keeps the complement off, and a complement row leaves the file only once its
    full-arm row has arrived, so a sweep killed mid re-measure still owes the rest."""
    failures = 0
    stamp = {"at": "p/A.java::body::f", "disagreements": [{"test": "p.T::t"}]}
    rows = [{"target": "p/A.java::body::f", "kind": "body", "index": 0, "ground_truth": "complement"}]
    if run.resumed_switch_off({"complement_switched_off": stamp}, rows) != stamp:
        failures += fail("a resumed leg forgot that the complement was switched off")
    if run.resumed_switch_off({"complement_switched_off": stamp}, []) is not None:
        failures += fail("a file started fresh kept the discarded file's switch-off")
    owed = [{"target": t, "kind": "body", "index": 0, "ground_truth": "complement"}
            for t in ("p/A.java::body::f", "p/B.java::body::f")]
    redone = owed + [{"target": "p/A.java::body::f", "kind": "body", "index": 0,
                      "ground_truth": "full", "remeasured": True}]
    kept = run.drop_superseded(redone)
    if [(r["target"], r["ground_truth"]) for r in kept] != [("p/B.java::body::f", "complement"),
                                                             ("p/A.java::body::f", "full")]:
        failures += fail(f"after one re-measured row the file holds {kept}")
    if run.complement_rows_to_rerun(kept) != [owed[1]]:
        failures += fail("a row still owed a full arm was not queued again on resume")
    return failures


def case_a_check_row_choice_never_reads_a_miss():
    """Flipping what earlier rows missed moves no row between complement and full."""
    failures = 0
    decisions = _narrowed([_id("p.KeptTest", "a")], [_id("p.DropTest", "b")])
    rows = [{"timed": False, "decision_outcome": "narrowed", "missed": []} for _ in range(3)]
    flipped = [{**row, "missed": ["p.DropTest::b"]} for row in rows]
    before = [sum(1 for r in rs if r.get("timed") is False
                  and r.get("decision_outcome") == "narrowed") for rs in (rows, flipped)]
    if before[0] != before[1] or _unsampled(decisions, before=before[0]) != \
            _unsampled(decisions, before=before[1]):
        failures += fail("a flipped missed set changed which ground truth a row gets")
    draws = [run.sampled_check(f"p/A{n}.java::body::f", "body", 0, 7) for n in range(800)]
    if not 50 <= sum(draws) <= 150:
        failures += fail(f"the 1-in-8 sample drew {sum(draws)} of 800")
    return failures


def case_an_isolated_row_never_pairs_with_a_complement_row():
    rows = [{"target": "p/A.java::body::f", "kind": "body", "index": 0, "status": "checked",
             "full_distinct": 9, "induced": [], "ground_truth": truth}
            for truth in ("complement",)] + [
            {"target": "p/B.java::body::f", "kind": "body", "index": 0, "status": "checked",
             "full_distinct": 9, "induced": [], "ground_truth": "complement+full"}]
    table, _ = run.paired_ground_truth(rows)
    if [key[0] for key in table] != ["p/B.java::body::f"]:
        return fail(f"an isolated arm would pair with {sorted(table)}")
    return 0


def case_a_run_launched_without_a_mode_takes_full_arm_truth():
    """Any run that does not ask measures on full-arm truth."""
    args = run.argument_parser().parse_args(["bench/projects/demo.toml"])
    if args.ground_truth != "full":
        return fail(f"a run launched without --ground-truth takes {args.ground_truth!r}")
    return 0


def case_a_selecting_arm_runs_with_other_ci_run_values_than_a_recording_arm():
    failures = 0
    base = {"PATH": "/bin", "GITHUB_RUN_ID": "from-the-host"}
    recording = run.ci_environment(["test"], base)
    selecting = run.ci_environment(["test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD"], base)
    if recording.get("PATH") != "/bin" or selecting.get("PATH") != "/bin":
        failures += fail("the arm's environment lost the host's own variables")
    differ = [name for name in run.CI_RUN_VARIABLES if recording.get(name) == selecting.get(name)]
    if differ:
        failures += fail(f"the arms share the values of {differ}")
    missing = [name for name in run.CI_RUN_VARIABLES if name not in recording or name not in selecting]
    if missing:
        failures += fail(f"an arm lacks {missing}")
    return failures


def case_a_shard_of_a_fixture_keeps_the_map_the_recording_job_left():
    import shutil  # noqa: PLC0415
    import tempfile  # noqa: PLC0415
    failures = 0
    root = tempfile.mkdtemp(prefix="yoriwake-fixture-")
    saved = run.SOURCE, run.CHECKOUTS
    try:
        os.makedirs(os.path.join(root, "src", "fx"))
        with open(os.path.join(root, "src", "fx", "build.gradle"), "w", encoding="utf-8") as handle:
            handle.write("// fixture\n")
        run.SOURCE, run.CHECKOUTS = os.path.join(root, "src"), os.path.join(root, "checkouts")
        project = {"name": "fx", "copy_of": "fx"}
        path = run.checkout_project(project)
        kept = os.path.join(path, ".gradle", "yoriwake", "test-1", "coverage.tsv")
        os.makedirs(os.path.dirname(kept))
        with open(kept, "w", encoding="utf-8") as handle:
            handle.write("map\n")
        head = subprocess.run(["git", "-C", path, "rev-parse", "HEAD"], capture_output=True,
                              text=True).stdout
        run.checkout_project(project, keep_state=True)
        if not os.path.isfile(kept):
            failures += fail("a shard's fresh fixture copy discarded the recording job's map")
        if subprocess.run(["git", "-C", path, "rev-parse", "HEAD"], capture_output=True,
                          text=True).stdout != head:
            failures += fail("a shard's fixture is a new commit, which the map cannot relate to")
        run.checkout_project(project)
        if os.path.isfile(kept):
            failures += fail("a run that is not a shard reused a fixture's old map")
    finally:
        run.SOURCE, run.CHECKOUTS = saved
        shutil.rmtree(root, ignore_errors=True)
    return failures


def case_a_row_measured_on_ci_names_the_runner_and_the_job():
    failures = 0
    keys = ("GITHUB_ACTIONS", "RUNNER_NAME", "GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT", "GITHUB_JOB")
    saved = {key: os.environ.pop(key, None) for key in keys}
    try:
        if run.ci_stamp() is not None:
            failures += fail("a local row was stamped as a CI one")
        os.environ.update(GITHUB_ACTIONS="true", RUNNER_NAME="GitHub Actions 7",
                          GITHUB_RUN_ID="42", GITHUB_RUN_ATTEMPT="2", GITHUB_JOB="shard")
        stamp = run.ci_stamp()
        if stamp != {"runner": "GitHub Actions 7", "run": "42", "attempt": "2", "job": "shard"}:
            failures += fail(f"the CI stamp is {stamp}")
    finally:
        for key, value in saved.items():
            os.environ.pop(key, None)
            if value is not None:
                os.environ[key] = value
    return failures


def case_every_row_a_target_appends_carries_its_kind_and_index():
    findings = [{"target": "a", "kind": "body", "index": 0}]
    start = len(findings)
    findings += [{"target": "b", "status": "stalled"}, {"target": "b", "kind": "body", "index": 3}]
    run.stamp_rows(findings, (start, "rename", 2))
    failures = 0
    if (findings[1].get("kind"), findings[1].get("index")) != ("rename", 2):
        failures += fail(f"a stalled row was not stamped: {findings[1]}")
    if (findings[0]["kind"], findings[2]["index"]) != ("body", 3):
        failures += fail("a row's own kind or index was overwritten, or an earlier row was stamped")
    return failures


def case_a_shard_is_named_k_of_n():
    failures = 0
    if run.parse_shard("2/3") != (2, 3):
        failures += fail("2/3 did not read as shard 2 of 3")
    for bad in ("0/3", "4/3", "2", "a/b", "1/0"):
        try:
            run.parse_shard(bad)
            failures += fail(f"{bad!r} was accepted as a shard")
        except SystemExit:
            pass
    return failures


def case_a_shard_measures_its_slice_and_counts_from_its_start():
    accepted = {"slices": [[0, 2], [2, 5]],
                "targets": [[f"A{i}.java", "body", i, f"A{i}.java::body::{i}"] for i in range(5)]}
    items, start = run.accepted_slice(accepted, (2, 2))
    failures = 0
    if items != [("A2.java", "body", 2), ("A3.java", "body", 3), ("A4.java", "body", 4)]:
        failures += fail(f"shard 2 of 2 measures {items}")
    if start != 2:
        failures += fail(f"shard 2 of 2 counts from {start}, so its timed rows would be "
                               f"chosen as if it were first")
    try:
        run.accepted_slice(accepted, (1, 3))
        failures += fail("a shard count off the planned slices was accepted")
    except SystemExit:
        pass
    return failures


def case_a_sharded_pool_run_refuses_a_timing_cap():
    import contextlib  # noqa: PLC0415
    import io  # noqa: PLC0415
    failures = 0
    for job in (["--accepted-out", "accepted.json"],
                ["--accepted", "accepted.json", "--shard", "1/2"]):
        saved = sys.argv
        sys.argv = ["run.py", "bench/projects/none.toml", "--shapes", run.POOL_SHAPES,
                    "--timing-cap", "8", *job]
        err = io.StringIO()
        said = ""
        try:
            with contextlib.redirect_stderr(err):
                run.main()
        except SystemExit:
            said = err.getvalue()
        except Exception:  # noqa: BLE001 -- past the flags, so not refused
            pass
        finally:
            sys.argv = saved
        if "--timing-cap times rows by draw position" not in said:
            failures += fail(f"{' '.join(job)} under {run.POOL_SHAPES} with --timing-cap was not "
                             f"refused: {said[-200:]!r}")
    return failures


def case_a_shard_refuses_a_list_drawn_under_other_stamps():
    accepted = {"selector_digest": "s1", "grammar_digest": "g1", "plugin_commit": "p1",
                "commit": "c1"}
    same = {"selector_digest": "s1", "grammar_digest": "g1", "plugin_commit": "p1", "commit": "c1"}
    failures = 0
    if run.accepted_mismatch(accepted, same) is not None:
        failures += fail("a list drawn under this run's stamps was refused")
    why = run.accepted_mismatch(accepted, {**same, "grammar_digest": "g2"})
    if not why or "grammar_digest" not in why:
        failures += fail(f"a list drawn under another grammar was accepted: {why}")
    return failures


def case_a_named_shape_set_writes_its_own_results_and_scratch():
    """A named set never overwrites the default set's results file or reuses its map backups."""
    failures = 0
    default = run.results_location("R", "L", "demo", run.DEFAULT_SHAPES)
    if default != (os.path.join("R", "demo.json"), os.path.join("L", "demo")):
        print(f"  FAIL: the default set moved: {default}")
        failures += 1
    named = run.results_location("R", "L", "demo", "recall-arm")
    if named != (os.path.join("R", "recall-arm", "demo.json"), os.path.join("L", "demo.recall-arm")):
        print(f"  FAIL: a named set does not have its own paths: {named}")
        failures += 1
    isolated = run.results_location("R", "L", "demo", "recall-arm", isolated=True)
    if isolated != (os.path.join("R", "recall-arm", "demo.isolated.json"),
                    os.path.join("L", "demo.isolated.recall-arm")):
        print(f"  FAIL: the isolated arm of a named set: {isolated}")
        failures += 1
    return failures


def case_a_mutation_row_counts_the_files_and_modules_it_changed():
    """A mutation's own diff decides its change counts, not a constant 1."""
    import subprocess
    root = tempfile.mkdtemp(prefix="yoriwake-mutation-paths-")
    failures = 0
    try:
        def git(*args):
            subprocess.run(["git", "-C", root, "-c", "user.email=t@t", "-c", "user.name=t",
                            "-c", "commit.gpgsign=false", *args], check=True, capture_output=True)
        for path in ("core/src/main/A.kt", "core/src/main/B.kt", "other/src/main/C.java"):
            os.makedirs(os.path.dirname(os.path.join(root, path)), exist_ok=True)
            with open(os.path.join(root, path), "w", encoding="utf-8") as handle:
                handle.write("x\n")
        git("init", "-q")
        git("add", "-A")
        git("commit", "-qm", "base")
        if run.mutation_change_counts(root) != (0, 0):
            print(f"  FAIL: a clean checkout counted {run.mutation_change_counts(root)}")
            failures += 1
        with open(os.path.join(root, "core/src/main/A.kt"), "w", encoding="utf-8") as handle:
            handle.write("y\n")
        # Staged with `git mv`, as the `renamed` shape moves its file: one rename entry in git's
        # status, and still two files changed.
        git("mv", "other/src/main/C.java", "other/src/main/CMoved.java")
        if run.mutation_change_counts(root) != (3, 2):
            print(f"  FAIL: an edit and a move counted {run.mutation_change_counts(root)}, not (3, 2)")
            failures += 1
    finally:
        shutil.rmtree(root, ignore_errors=True)
    return failures


def case_editing_the_shape_composition_moves_the_grammar_digest():
    """Composing shapes is part of the grammar, so a change to it changes the stamp."""
    root = tempfile.mkdtemp(prefix="yoriwake-grammar-")
    try:
        for name in run.GRAMMAR_SOURCE_FILES:
            os.makedirs(os.path.dirname(os.path.join(root, name)), exist_ok=True)
            shutil.copyfile(os.path.join(run.ROOT, name), os.path.join(root, name))
        before = run.grammar_digest(root)
        path = os.path.join(root, "bench", "mutations.py")
        with open(path, encoding="utf-8") as handle:
            text = handle.read()
        assert 'destination = path.replace(".kt", "Moved.kt")' in text
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(text.replace('"Moved.kt"', '"Relocated.kt"'))
        if run.grammar_digest(root) == before:
            print("  FAIL: a change to how `renamed` is composed left grammar_digest where it was")
            return 1
        return 0
    finally:
        shutil.rmtree(root, ignore_errors=True)


def case_the_pool_set_digests_its_own_generators_and_leaves_the_default_digest_alone():
    """A sweep resumes only rows drawn under the digest it would stamp now, so adding `pool/v1`
    must not move the default set's digest: it would start every default results file fresh."""
    root = tempfile.mkdtemp(prefix="yoriwake-grammar-")
    try:
        for name in run.GRAMMAR_SOURCE_FILES + run.SET_GRAMMAR_FILES[run.POOL_SHAPES]:
            # The learned order's model exists only once a replay adopted it.
            if not os.path.isfile(os.path.join(run.ROOT, name)):
                continue
            os.makedirs(os.path.dirname(os.path.join(root, name)), exist_ok=True)
            shutil.copyfile(os.path.join(run.ROOT, name), os.path.join(root, name))
        default = run.grammar_digest(root)
        pool = run.grammar_digest(root, run.POOL_SHAPES)
        failures = 0
        if pool == default:
            print("  FAIL: the pool set is stamped with the default set's grammar digest")
            failures += 1
        with open(os.path.join(root, "bench", "pool_mutations.py"), "a", encoding="utf-8") as handle:
            handle.write("changed = True\n")
        if run.grammar_digest(root) != default:
            print("  FAIL: a change to the pool's generators moved the default set's grammar digest")
            failures += 1
        if run.grammar_digest(root, run.POOL_SHAPES) == pool:
            print("  FAIL: a change to the pool's generators left the pool's grammar digest alone")
            failures += 1
        pool = run.grammar_digest(root, run.POOL_SHAPES)
        # The pool's draw order and stops, and the learned order's model once one is adopted.
        for name in ("family_order.py", os.path.join("order", "learned-v1.json")):
            os.makedirs(os.path.join(root, "bench", "order"), exist_ok=True)
            with open(os.path.join(root, "bench", name), "a", encoding="utf-8") as handle:
                handle.write("changed = True\n")
            if run.grammar_digest(root) != default:
                print(f"  FAIL: a change to {name} moved the default set's grammar digest")
                failures += 1
            if run.grammar_digest(root, run.POOL_SHAPES) == pool:
                print(f"  FAIL: a change to {name} left the pool's grammar digest alone")
                failures += 1
            pool = run.grammar_digest(root, run.POOL_SHAPES)
        with open(os.path.join(root, "bench", "mutations.py"), "a", encoding="utf-8") as handle:
            handle.write("changed = True\n")
        if run.grammar_digest(root) == default or run.grammar_digest(root, run.POOL_SHAPES) == pool:
            print("  FAIL: a change to the shared generators left a set's grammar digest alone")
            failures += 1
        return failures
    finally:
        shutil.rmtree(root, ignore_errors=True)


def case_the_forcing_set_digests_its_own_generators_and_leaves_the_others_alone():
    """`forcing/v1` is stamped with its own generators and the files they reuse; a change to
    its generators moves neither the default set's digest nor the pool's."""
    root = tempfile.mkdtemp(prefix="yoriwake-grammar-")
    try:
        names = set(run.GRAMMAR_SOURCE_FILES + run.SET_GRAMMAR_FILES[run.POOL_SHAPES]
                    + run.SET_GRAMMAR_FILES.get(run.FORCING_SET, ()))
        for name in names:
            if not os.path.isfile(os.path.join(run.ROOT, name)):
                continue
            os.makedirs(os.path.dirname(os.path.join(root, name)), exist_ok=True)
            shutil.copyfile(os.path.join(run.ROOT, name), os.path.join(root, name))
        default, pool = run.grammar_digest(root), run.grammar_digest(root, run.POOL_SHAPES)
        forcing = run.grammar_digest(root, run.FORCING_SET)
        failures = 0
        if forcing in (default, pool):
            print("  FAIL: the forcing set is stamped with another set's grammar digest")
            failures += 1
        with open(os.path.join(root, "bench", "forcing_mutations.py"), "a",
                  encoding="utf-8") as handle:
            handle.write("changed = True\n")
        if (run.grammar_digest(root), run.grammar_digest(root, run.POOL_SHAPES)) != (default, pool):
            print("  FAIL: a change to the forcing generators moved another set's grammar digest")
            failures += 1
        if run.grammar_digest(root, run.FORCING_SET) == forcing:
            print("  FAIL: a change to the forcing generators left the forcing digest alone")
            failures += 1
        forcing = run.grammar_digest(root, run.FORCING_SET)
        with open(os.path.join(root, "bench", "revert.py"), "a", encoding="utf-8") as handle:
            handle.write("changed = True\n")
        if run.grammar_digest(root, run.FORCING_SET) == forcing:
            print("  FAIL: a change to the revert patches dependency-revert reuses left the forcing "
                  "digest alone")
            failures += 1
        return failures
    finally:
        shutil.rmtree(root, ignore_errors=True)


def case_the_pool_ceiling_refuses_a_family_and_not_the_others():
    """`pool/v1`'s rejection ceiling is per family: one family a guest rejects is refused on its
    own, and a family with a rejection or two under the ceiling is not."""
    findings = ([{"kind": "throw-entry"}] * 9 + [{"kind": "void-return"}] * 2
                + [{"kind": "body"}] * 20)
    rejected = ([["a::void-return::f", "void-return", 0]] * 3
                + [["b::throw-entry::g", "throw-entry", 0]])
    refused = run.refused_families(rejected, findings)
    failures = 0
    if refused != {"void-return": (3, 5)}:
        print(f"  FAIL: the per-family ceiling refused {refused}, not void-return's 3 of 5 alone")
        failures += 1
    # Over the whole project, 4 of 35 is under the ceiling: the per-family reading is what sees
    # a family rejected wholesale.
    if run.rejection_rate_refuses(4, len(findings)):
        print("  FAIL: the fixture is meant to sit under the project-wide ceiling")
        failures += 1
    if run.refused_families(None, findings):
        print("  FAIL: a file with no rejections refused a family")
        failures += 1
    return failures


def case_a_family_is_redrawn_only_until_it_is_refused_whatever_follows():
    """A family a guest rejects wholesale stops being redrawn once no row the limit still allows
    could bring it under the ceiling, and not one draw sooner: stopping earlier would refuse a
    family that might have ended under it."""
    failures = 0
    for rejected, accepted, remaining, expected in (
            (30, 0, 100, True),     # 30 of 130 at best: refused whatever follows
            (25, 0, 100, False),    # 25 of 125 at best is the ceiling itself, not above it
            (3, 0, 100, False),     # three rejections in a row, and 100 rows still to come
            (5, 2, 0, True),        # nothing left to draw: the rate is the rate
            (5, 2, -3, True)):      # a resumed file already past its limit
        got = run.refused_whatever_follows(rejected, accepted, remaining)
        if got != expected:
            print(f"  FAIL: {rejected} rejected, {accepted} accepted, {remaining} to come read "
                  f"{got}, expected {expected}")
            failures += 1
    return failures


CASES = (case_every_case_in_this_file_is_registered,
         case_the_pool_set_digests_its_own_generators_and_leaves_the_default_digest_alone,
         case_a_family_is_redrawn_only_until_it_is_refused_whatever_follows,
         case_the_pool_ceiling_refuses_a_family_and_not_the_others,
         case_the_forcing_set_digests_its_own_generators_and_leaves_the_others_alone,
         case_editing_the_shape_composition_moves_the_grammar_digest,
         case_a_mutation_row_counts_the_files_and_modules_it_changed,
         case_a_named_shape_set_writes_its_own_results_and_scratch,
         case_a_shard_is_named_k_of_n,
         case_every_row_a_target_appends_carries_its_kind_and_index,
         case_a_row_measured_on_ci_names_the_runner_and_the_job,
         case_a_shard_of_a_fixture_keeps_the_map_the_recording_job_left,
         case_a_shard_measures_its_slice_and_counts_from_its_start,
         case_a_sharded_pool_run_refuses_a_timing_cap,
         case_a_shard_refuses_a_list_drawn_under_other_stamps,
         case_a_selecting_arm_runs_with_other_ci_run_values_than_a_recording_arm,
         case_a_run_launched_without_a_mode_takes_full_arm_truth,
         case_a_complement_runs_the_dropped_tests_classes_whole,
         case_a_complement_row_reads_its_misses_from_both_runs,
         case_the_first_rows_and_flagged_projects_carry_a_full_arm,
         case_a_complement_that_saves_little_or_cannot_be_priced_runs_the_full_arm,
         case_a_disagreement_names_the_test_and_both_verdicts,
         case_a_check_row_choice_never_reads_a_miss,
         case_a_switch_off_outlives_its_invocation_and_owed_rows_stay_owed,
         case_a_complement_row_not_keyed_per_method_runs_the_full_arm,
         case_an_isolated_row_never_pairs_with_a_complement_row,
         case_a_timing_project_times_its_first_rows_whatever_they_decide,
         case_a_preflight_that_forced_for_its_setup_says_so,
         case_three_identical_project_forces_stop_the_project,
         case_paired_ground_truth_is_the_same_mutations,
         case_a_paired_file_holding_one_mutation_twice_is_refused,
         case_a_paired_row_without_an_index_is_matched_by_its_label,
         case_one_label_at_two_indices_gets_two_log_stems,
         case_a_resume_measures_the_other_declarations_of_a_measured_label,
         case_a_rejected_declaration_does_not_reject_its_namesakes,
         case_overlapping_source_roots_measure_each_mutation_once,
         case_the_stopping_rule_counts_narrowed_rows_and_never_misses,
         case_a_schema_six_map_is_read_with_its_capture_mode_and_cost,
         case_neither_arm_selects_against_the_other_arms_map,
         case_a_decision_the_agent_could_not_publish_is_not_believed,
         case_every_induced_failure_names_the_rules_that_selected_it,
         case_the_widest_first_touch_windows_are_named,
         case_an_arm_s_cost_is_read_from_its_own_instruments,
         case_the_raw_evidence_is_kept_and_archived_without_the_harness_copies,
         case_a_project_flag_names_an_init_script_in_the_checkout_that_exists,
         case_every_harness_build_turns_off_the_guests_own_test_selection,
         case_staging_and_flags_treat_a_stored_null_oppositely,
         case_a_row_records_the_tree_and_the_flags_it_was_measured_under,
         case_only_the_selector_source_moves_the_digest,
         case_only_the_grammar_source_moves_the_grammar_digest,
         case_a_changed_grammar_starts_a_fresh_results_file,
         case_a_fixture_edit_moves_the_fixture_digest_and_a_resume_starts_fresh,
         case_every_test_jvm_s_part_reaches_the_row,
         case_a_cached_baseline_is_reused_only_for_the_same_tree,
         case_a_replay_snapshot_is_the_map_as_read_and_is_kept_once,
         case_a_container_is_not_also_published_as_having_run_and_passed,
         case_a_capture_that_was_never_merged_is_not_a_missing_map,
         case_a_container_that_never_started_is_not_a_miss,
         case_a_class_lifecycle_failure_is_a_container_too,
         case_a_test_named_like_a_container_is_still_a_test,
         case_a_row_that_skipped_nothing_leaves_its_containers_alone,
         case_a_mutation_that_does_not_compile_is_rejected_rather_than_recorded,
         case_a_project_may_name_the_compile_task_its_guest_actually_has,
         case_a_missing_classes_task_is_not_a_generator_rejection,
         case_a_guest_whose_suite_size_moves_may_buy_its_own_baseline_repeats,
         case_a_changed_baseline_repeats_starts_a_fresh_results_file,
         case_the_compile_toolchain_is_not_a_defaulted_parameter,
         case_a_rejection_rate_above_the_ceiling_is_a_refusal,
         case_the_header_records_how_many_targets_there_were_to_choose_from,
         case_the_checkpoint_survives_what_used_to_destroy_it,
         case_a_test_failing_on_every_baseline_repeat_is_already_failing,
         case_baseline_answers_that_disagree_resolve_to_already_failing,
         case_a_test_that_passes_every_repeat_is_still_induced_later,
         case_the_timed_baseline_also_refuses_a_non_answer,
         case_a_repeat_that_reached_no_tests_is_not_read_as_clean,
         case_the_repeats_name_which_tests_they_removed,
         case_the_repeat_count_is_read_from_its_knob,
         case_a_stalled_repeat_establishes_nothing_and_is_not_read_as_clean,
         case_a_project_that_names_no_sources_is_refused_rather_than_yielding_nothing,
         case_a_map_that_attributes_nothing_says_so,
         case_a_record_from_an_older_jar_is_version_skew_not_an_unattributed_row,
         case_a_record_torn_inside_its_notes_reads_as_absent,
         case_a_refusal_reaches_the_row_as_its_own_kind,
         case_a_truncated_decision_record_reads_as_absent,
         case_a_record_carrying_more_rows_than_it_declares_reads_as_absent,
         case_prose_survives_the_writer_and_the_reader,
         case_targets_come_only_from_the_declared_source_roots,
         case_every_empty_sources_file_says_why,
         case_the_harness_own_map_restore_does_not_make_a_stale_record_look_fresh,
         case_non_force_markers_still_match_the_plugin,
         case_a_retired_marker_on_a_stored_row_is_still_not_a_force_reason,
         case_a_task_off_the_platform_running_everything_is_not_a_force_reason,
         case_a_capture_sentence_is_not_a_force_reason,
         case_elapsed_time_is_parsed_in_the_format_both_systems_print,
         case_the_decision_tokens_reach_the_row,
         case_every_rule_behind_a_decision_reaches_the_row,
         case_a_stale_decision_record_is_absent_not_an_answer,
         case_a_narrowed_row_carries_no_force_kind,
         case_a_record_that_disagrees_with_the_arm_says_so,
         case_the_digest_changes_when_one_test_changes,
         case_the_digest_ignores_an_identity_hash,
         case_a_map_with_no_stamp_records_null_never_a_guess,
         case_a_missing_map_records_absent_not_zero,
         case_machine_conditions_are_absent_rather_than_zero,
         case_ran_but_passed_uses_the_same_comparison_as_missed,
         case_a_test_that_failed_in_both_arms_is_not_ran_but_passed,
         case_a_sibling_on_a_shared_key_does_not_clear_a_miss,
         case_a_failing_method_that_ran_is_still_caught,
         case_a_plain_method_row_is_unchanged,
         case_collapsed_counts_do_not_make_a_narrowed_row_whole,
         case_a_baseline_flake_on_a_sibling_s_key_is_not_subtracted,
         case_re_runs_answered_by_a_sibling_do_not_clear_a_shared_key_miss,
         case_a_sibling_failing_unmutated_does_not_set_a_miss_aside,
         case_a_baseline_flake_in_a_class_excluded_whole_is_not_subtracted,
         case_re_runs_in_a_class_excluded_whole_do_not_clear_a_shared_key_miss,
         case_a_sibling_failing_unmutated_in_a_class_excluded_whole_does_not_set_a_miss_aside,
         case_the_selected_arm_still_misses_a_class_excluded_whole,
         case_a_selected_arm_that_ran_a_class_its_record_excluded_whole_catches_nothing_there,
         case_a_re_run_without_the_listener_compares_on_xml_keys_and_says_so,
         case_paired_ground_truth_keeps_its_own_scheme,
         case_a_paired_collapse_the_selected_arm_cannot_see_is_still_shared,
         case_failing_union_keeps_methods_only_when_every_build_has_them,
         case_an_incomplete_re_run_on_an_attributable_key_answers_on_xml_keys,
         case_a_collapsed_per_method_key_is_never_subtracted,
         case_a_collapsed_per_method_key_is_shared_and_a_display_name_is_not,
         case_attribution_reads_every_record_shape,
         case_an_old_baseline_cache_weakens_only_a_row_it_can_change,
         case_a_class_the_record_does_not_list_is_shared_on_a_narrowed_row,
         case_the_gap_counts_leaf_ids_not_containers,
         case_a_per_method_failure_names_its_template_s_rules,
         case_an_identity_hash_is_not_a_missed_test,
         case_a_genuinely_missed_test_is_still_missed,
         case_an_unmatchable_id_is_undetermined_not_clean,
         case_the_same_seed_gives_the_same_order,
         case_targets_span_reach_rather_than_clustering_at_one_end,
         case_no_map_leaves_the_order_alone,
         case_reach_counts_a_kotlin_file_class,
         case_an_existing_results_file_is_still_readable,
         case_a_provisioned_toolchain_is_found_at_any_of_its_layouts,
         case_a_force_reason_is_read_from_the_line_the_plugin_prints,
         case_a_force_reason_is_scoped_to_the_measured_task,
         case_a_run_that_never_forced_has_no_reason,
         case_capture_openers_still_match_the_plugin,
         case_module_dir_falls_back_to_the_gradle_name,
         case_uncovered_targets_do_not_consume_the_limit,
         case_a_kotlin_file_class_counts_as_covered,
         case_a_project_can_ask_for_daemon_heap,
         case_no_map_means_unknown_not_uncovered,
         case_a_path_that_names_no_class_is_unknown,
         case_shape_budget_holds_as_shapes_are_added,
         case_weave_survives_an_empty_side,
         case_resume_deepens_a_file_instead_of_abandoning_it,
         case_rows_without_an_index_keep_the_old_conservative_behaviour,
         case_a_failed_isolation_build_keeps_the_miss,
         case_results_that_cannot_be_read_keep_the_miss,
         case_a_test_absent_from_the_isolated_run_keeps_the_miss,
         case_the_recall_arm_kinds_reach_their_generators,
         case_a_test_that_passes_alone_is_order_dependent,
         case_the_isolated_rerun_is_repeated,
         case_a_test_that_contradicts_itself_is_nondeterministic_not_missed,
         case_agreeing_repeats_are_not_nondeterministic,
         case_a_majority_that_fails_alone_keeps_the_miss,
         case_an_unanswered_repeat_clears_nothing,
         case_a_killed_build_is_not_a_cache_hit,
         case_a_row_that_skipped_nothing_cannot_report_a_miss,
         case_a_sibling_failure_does_not_make_this_test_unreadable,
         case_a_test_that_fails_with_the_change_reverted_is_not_a_miss,
         case_the_isolated_rerun_asks_for_the_measured_task,
         case_the_map_is_looked_up_by_the_measured_task,
         case_the_task_name_comes_from_the_project_file,
         case_results_of_reads_a_nested_module_as_a_directory,
         case_a_stale_result_does_not_clear_a_miss,
         case_a_restore_that_cannot_run_git_is_reported_not_raised,
         case_a_restore_that_succeeds_stops_retrying)


def _git_checkout(tmp, name):
    """A real single-commit git repository, because `restore` runs `git reset --hard` on one."""
    import subprocess
    root = os.path.join(tmp, name)
    os.makedirs(os.path.join(root, "app", "src", "main", "java", "p"), exist_ok=True)
    with open(os.path.join(root, "app", "src", "main", "java", "p", "Real.java"), "w") as handle:
        handle.write("package p;\nclass Real {}\n")
    # Zero bytes, which is the only kind of file `drop_empty_sources` may name.
    open(os.path.join(root, "app", "src", "main", "java", "p", "Empty.java"), "w").close()
    subprocess.run(["git", "init", "--quiet", root], check=True)
    for key, value in (("user.email", "t@t"), ("user.name", "t")):
        subprocess.run(["git", "-C", root, "config", key, value], check=True)
    subprocess.run(["git", "-C", root, "add", "-A"], check=True)
    subprocess.run(["git", "-C", root, "commit", "--quiet", "-m", "pinned"], check=True)
    return root


EMPTY_SOURCE = "app/src/main/java/p/Empty.java"
REAL_SOURCE = "app/src/main/java/p/Real.java"


def _git_diff_names(root, against="HEAD"):
    """What the selector's own change detection would see: `git diff --name-only <against>`."""
    import subprocess
    done = subprocess.run(["git", "-C", root, "diff", "--name-only", "--no-renames", against],
                          capture_output=True, text=True)
    return sorted(n for n in done.stdout.split("\n") if n)


def case_the_staged_tree_is_what_head_describes(tmp):
    """The defect review caught before a sweep was spent on it, and the reason staging is a COMMIT.

    Deleting the files and leaving them uncommitted stages the tree the capture sees -- and also
    puts all five paths into the change set of every selecting row, because the selecting arm runs
    `-Pyoriwake.base=HEAD` and the plugin answers that with `git diff --name-only HEAD`, which reports a
    deleted tracked file. Five changed sources with no compiled class behind them force a full run,
    a full run cannot skip a test, and the sweep would have reported `0 missed` that was true by
    construction. So the assertion that matters here is not "the file is gone" -- it is that the
    change set holds the MUTATION AND NOTHING ELSE.
    """
    failures = 0
    root = _git_checkout(tmp, "staged")
    empty = os.path.join(root, EMPTY_SOURCE.replace("/", os.sep))
    real = os.path.join(root, REAL_SOURCE.replace("/", os.sep))

    # The control half: an UNCOMMITTED deletion is visible to the selector. If this ever stops being
    # true, the finding this case exists for has gone away and the case should be re-read.
    os.remove(empty)
    if _git_diff_names(root) != [EMPTY_SOURCE]:
        print(f"  FAIL: an uncommitted deletion was invisible to git diff HEAD "
              f"({_git_diff_names(root)}); this case can no longer reproduce the defect it guards")
        failures += 1
    run.restore(root)

    sha, staged = run.stage_checkout(root, {"drop_empty_sources": [EMPTY_SOURCE]})
    if staged != [EMPTY_SOURCE]:
        print(f"  FAIL: staged {staged}, expected [{EMPTY_SOURCE!r}]")
        failures += 1
    if sha is None or len(sha) != 40:
        print(f"  FAIL: no staging commit sha was returned ({sha!r})")
        failures += 1
    if os.path.isfile(empty):
        print("  FAIL: the named empty source is still in the tree after staging")
        failures += 1
    if not os.path.isfile(real):
        print("  FAIL: staging removed a file nobody named")
        failures += 1

    # THE ASSERTION THE P0 WAS ABOUT. HEAD describes the staged tree, so the selector sees nothing.
    if _git_diff_names(root) != []:
        print(f"  FAIL: the staged deletion is still in the change set ({_git_diff_names(root)}); "
              f"every row would force a full run and no recall question would be asked")
        failures += 1

    # And it survives the restore between mutations without anything re-deleting it, because
    # `git reset --hard` resets to HEAD and HEAD is the staging commit.
    for attempt in (1, 2):
        run.restore(root)
        if os.path.isfile(empty):
            print(f"  FAIL: restore #{attempt} brought the staged file back")
            failures += 1
        if _git_diff_names(root) != []:
            print(f"  FAIL: restore #{attempt} left a dirty change set ({_git_diff_names(root)})")
            failures += 1

    # With a mutation applied, the change set is the mutation alone -- which is the whole point.
    with open(real, "a", encoding="utf-8") as handle:
        handle.write("// mutated\n")
    if _git_diff_names(root) != [REAL_SOURCE]:
        print(f"  FAIL: the change set is {_git_diff_names(root)}, expected only [{REAL_SOURCE!r}]")
        failures += 1

    # THE SAME COMMIT ON A LATER RUN, a second or more apart. A reused map records the staging
    # commit it was captured at, and a new SHA per run made every row of a reused map force.
    pinned = subprocess.run(["git", "-C", root, "rev-parse", "HEAD~1"],
                            capture_output=True, text=True, check=True).stdout.strip()
    subprocess.run(["git", "-C", root, "reset", "--quiet", "--hard", pinned], check=True)
    time.sleep(1.1)
    again, _ = run.stage_checkout(root, {"drop_empty_sources": [EMPTY_SOURCE]})
    if again != sha:
        print(f"  FAIL: staging the same tree again made a different commit ({sha[:12]} then "
              f"{again[:12]}); a map captured on one run cannot be related to the next")
        failures += 1

    # A project naming nothing is not staged and gets no commit.
    plain = _git_checkout(tmp, "plain")
    sha, staged = run.stage_checkout(plain, {})
    if (sha, staged) != (None, []):
        print(f"  FAIL: a project with no key was staged anyway ({sha!r}, {staged})")
        failures += 1
    return failures


def case_a_source_with_content_is_refused_rather_than_staged(tmp):
    """The difference between staging a checkout and editing a guest's code.

    duckduckgo-android has two `SyncActivity.kt` -- a zero-byte one and a 34,666-byte one. Removing
    an empty file cannot change what the compiler produces; removing that second one can, and a
    measurement of an edited guest is not a measurement of the project.
    """
    failures = 0
    root = _git_checkout(tmp, "content")
    try:
        run.assert_droppable(root, [REAL_SOURCE])
        print("  FAIL: a file with content was accepted for deletion")
        failures += 1
    except SystemExit as refused:
        message = str(refused)
        # The size, because a reader has to be able to tell which of two same-named files this was.
        if REAL_SOURCE not in message or "byte" not in message:
            print(f"  FAIL: the refusal named neither the file nor its size: {message!r}")
            failures += 1
    # And it is still there: a refusal that half-applied would be worse than none.
    if not os.path.isfile(os.path.join(root, REAL_SOURCE.replace("/", os.sep))):
        print("  FAIL: the refused file was deleted anyway")
        failures += 1
    # Staging the same project refuses too, rather than committing a partial job.
    try:
        run.stage_checkout(root, {"drop_empty_sources": [REAL_SOURCE]})
        print("  FAIL: stage_checkout accepted a file with content")
        failures += 1
    except SystemExit:
        pass
    if _git_diff_names(root) != []:
        print(f"  FAIL: the refused staging left the tree dirty ({_git_diff_names(root)})")
        failures += 1
    return failures


def case_a_dropped_path_that_is_absent_is_refused_rather_than_skipped(tmp):
    """An upstream rename must not turn the remedy off silently.

    Skipped, the sweep runs with a scope collapsed back to `*` and writes a results file that says
    nothing about the staging it depended on. Refused, it costs a reader one line and no runner
    hours.
    """
    failures = 0
    root = _git_checkout(tmp, "absent")
    try:
        run.assert_droppable(root, ["app/src/main/java/p/Renamed.java"])
        print("  FAIL: a path that does not exist was accepted")
        failures += 1
    except SystemExit as refused:
        if "Renamed.java" not in str(refused):
            print(f"  FAIL: the refusal did not name the path: {str(refused)!r}")
            failures += 1
    return failures


def case_a_dropped_path_may_not_leave_the_checkout(tmp):
    """The one operation here that can reach outside the guest, so the path is checked not trusted.

    WRITTEN THE SECOND TIME AFTER REVIEW SHOWED THE FIRST WAS VACUOUS. The first version probed with
    `../outside.java` and this file's own absolute path -- neither of which exists as an empty file
    in the checkout, so BOTH were refused by the missing-file branch and the traversal check could
    be deleted with the suite staying green. A guard that has only ever been passed for the wrong
    reason has not been shown to guard anything. So each probe here is a REAL zero-byte file that
    every other branch would accept, leaving the traversal check as the only thing that can refuse.
    """
    failures = 0
    root = _git_checkout(tmp, "escape")
    outside_dir = os.path.join(tmp, "outside-the-checkout")
    os.makedirs(outside_dir, exist_ok=True)
    outside = os.path.join(outside_dir, "Empty.java")
    open(outside, "w").close()

    relative_escape = os.path.join("..", "outside-the-checkout", "Empty.java").replace(os.sep, "/")
    for probe in (relative_escape, outside):
        # The premise: this really is an empty file, so nothing but the traversal check refuses it.
        if not (os.path.isfile(os.path.join(root, probe.replace("/", os.sep)))
                or os.path.isabs(probe)):
            print(f"  FAIL: {probe!r} is not reachable as a file, so this probe cannot isolate "
                  f"the traversal check")
            failures += 1
        try:
            run.assert_droppable(root, [probe])
            print(f"  FAIL: {probe!r} was accepted as a path inside the checkout")
            failures += 1
        except SystemExit as refused:
            # The REASON, not merely a refusal: a sibling branch refusing would prove nothing.
            if "leave it" not in str(refused):
                print(f"  FAIL: {probe!r} was refused for the wrong reason: {str(refused)!r}")
                failures += 1
    if not os.path.isfile(outside):
        print("  FAIL: a file outside the checkout was deleted")
        failures += 1
    return failures


def case_a_project_with_no_drop_key_is_unaffected(tmp):
    """Every project but one names nothing, and every existing results file stays reproducible."""
    failures = 0
    if run.sources_to_drop({}) != []:
        print(f"  FAIL: a project with no key named {run.sources_to_drop({})}")
        failures += 1
    if run.sources_to_drop({"drop_empty_sources": [EMPTY_SOURCE]}) != [EMPTY_SOURCE]:
        print("  FAIL: the key was not read")
        failures += 1
    # An empty list must not be read as "drop nothing named" versus "no key": both are no-ops.
    run.assert_droppable(os.path.join(tmp, "does-not-exist"), [])

    root = _git_checkout(tmp, "untouched")
    run.restore(root)
    for relative in (EMPTY_SOURCE, REAL_SOURCE):
        if not os.path.isfile(os.path.join(root, relative.replace("/", os.sep))):
            print(f"  FAIL: a restore with no staging removed {relative}")
            failures += 1
    return failures


def case_an_unmerged_capture_is_invisible_to_selection_and_visible_to_discarding(tmp):
    """The two answers must differ, and a `--recapture` that missed one merged two builds' records.

    `find_map` requires `coverage.tsv`, because for SELECTION an unmerged map is no map. Discarding
    is the opposite case: a captured-but-never-merged directory is exactly what a project whose
    decode finalizer never ran leaves behind -- iceberg and duckduckgo-android both -- and a discard
    gated on `find_map`'s answer skips it, captures into the old run's `raw/` tree, and hands the
    decode two builds to merge into one map.
    """
    failures = 0
    yoriwake = os.path.join(tmp, "unmerged", ".gradle", "yoriwake")
    unmerged = os.path.join(yoriwake, "app-testPlayDebugUnitTest-a963623f")
    os.makedirs(os.path.join(unmerged, "raw"), exist_ok=True)
    with open(os.path.join(unmerged, "task-facts"), "w") as handle:
        handle.write("recordedCoverage=\nworkers=\ndecodeRefused=\n")
    checkout = os.path.join(tmp, "unmerged")

    if run.find_map(checkout, "app", "testPlayDebugUnitTest") is not None:
        print("  FAIL: find_map handed selection a map with no coverage.tsv")
        failures += 1
    found = run.map_dirs_for_task(checkout, "app", "testPlayDebugUnitTest")
    if found != [unmerged]:
        print(f"  FAIL: the discard could not see the unmerged capture: {found}")
        failures += 1

    # And once merged, both agree it is there.
    with open(os.path.join(unmerged, "coverage.tsv"), "w") as handle:
        handle.write("SUCCESSFUL\t1\tp.A\n")
    if run.find_map(checkout, "app", "testPlayDebugUnitTest") != unmerged:
        print("  FAIL: find_map did not see a merged map")
        failures += 1
    if run.map_dirs_for_task(checkout, "app", "testPlayDebugUnitTest") != [unmerged]:
        print("  FAIL: the discard lost sight of the map once it merged")
        failures += 1

    # Another module's map is not this task's, in either direction.
    other = os.path.join(yoriwake, "library-testDebugUnitTest-deadbeef")
    os.makedirs(other, exist_ok=True)
    if other in run.map_dirs_for_task(checkout, "app", "testPlayDebugUnitTest"):
        print("  FAIL: the discard would remove another module's map")
        failures += 1
    return failures


def case_a_map_directory_is_removable_whatever_its_permissions(tmp):
    """The bug that killed a 3-hour iceberg sweep thirteen rows into sixteen.

    `remove_tree`'s recovery handler chmod'd to `stat.S_IWRITE` -- 0o200, write-only. On a FILE that
    is enough to delete it; on a DIRECTORY it strips read and execute, so nothing can traverse in to
    delete the contents. The handler CREATED the state it existed to fix, leaving iceberg's map
    directory `d-w-------`, and then could not recover because it retried by calling the failing
    function -- which `rmtree`'s fd-based walk supplies as `os.open`, needing flags it was not given.

    `restore_map` calls `remove_tree` between arms, so this took out a sweep mid-row. 0o200 is the
    case that matters; the other two are the neighbours.
    """
    import stat as _stat
    failures = 0
    for mode, label in ((0o200, "write-only, the state the old handler created"),
                        (0o500, "read and execute, no write"),
                        (0o000, "no permissions at all")):
        victim = os.path.join(tmp, "removable-%o" % mode)
        inner = os.path.join(victim, "raw")
        os.makedirs(inner, exist_ok=True)
        with open(os.path.join(inner, "a.exec"), "w") as handle:
            handle.write("x")
        os.chmod(inner, mode)
        os.chmod(victim, mode)
        try:
            try:
                run.remove_tree(victim)
            except Exception as raised:   # noqa: BLE001 -- any escape is the failure
                print(f"  FAIL: remove_tree could not delete a {label} directory: "
                      f"{type(raised).__name__}: {str(raised)[:70]}")
                failures += 1
                continue
            if os.path.exists(victim):
                print(f"  FAIL: remove_tree returned but the {label} directory survives")
                failures += 1
        finally:
            # UNCONDITIONALLY, AND THIS IS THE HALF THAT MATTERS WHEN THE CODE IS BROKEN. These
            # directories are deliberately unreadable, so anything they leave behind defeats the
            # entry point's own tempdir cleanup and takes the whole suite down with an exception
            # instead of a counted failure -- which a guard check then reports as "NOT THE GUARD",
            # not as the guard firing. Found exactly that way.
            for path in (inner, victim):
                if os.path.exists(path):
                    try:
                        os.chmod(path, _stat.S_IRWXU)
                    except OSError:
                        pass
    return failures


def _build_outcomes(tmp, name, cases, lines, marked=True, task=":m:test"):
    """One build as the reader finds it: JUnit XML for `cases` ((class, name, failed) per testcase),
    the listener's `lines` ((task, class, ancestors, display, name, result)), and a log that carries
    the listener's marker when `marked`. Returns what `results_of` reads."""
    from xml.sax.saxutils import quoteattr
    root = os.path.join(tmp, name)
    directory = os.path.join(root, "m", "build", "test-results", "test")
    os.makedirs(directory, exist_ok=True)
    body = "".join(f'<testcase classname={quoteattr(c)} name={quoteattr(n)}>'
                   f'{"<failure/>" if failed else ""}</testcase>' for c, n, failed in cases)
    with open(os.path.join(directory, "TEST-all.xml"), "w", encoding="utf-8") as handle:
        handle.write(f"<testsuite>{body}</testsuite>")
    log = os.path.join(root, "arm.log")
    with open(log, "w", encoding="utf-8") as handle:
        handle.write("> Task :m:test\n")
        if marked:
            handle.write(f"{sweep_data.TEST_OUTCOMES_MARKER}{task}\n")
    if lines is not None:
        def esc(value):
            return (value.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "\\r")
                    .replace("\n", "\\n"))
        with open(log + ".outcomes.tsv", "w", encoding="utf-8") as handle:
            for line_task, cls, ancestors, display, own, result in lines:
                fields = [line_task, cls, *ancestors, display, own, result]
                handle.write("\t".join(esc(f) for f in fields) + "\n")
    return run.results_of(root, "m", 0, "test", log_path=log, task_path=":m:test")


def _line(cls, ancestors, display, own, result="SUCCESS", task=":m:test"):
    return task, cls, tuple(ancestors), display, own, result


# That class as the listener and the XML report it: `a()` and `b()` invoked at `v = 1` and `v = 2`, `b()`
# failing at `v = 1`.
_AE1_CASES = [(_P, "v = 1", False), (_P, "v = 2", False), (_P, "v = 1", True), (_P, "v = 2", False)]
_AE1_LINES = [_line(_P, ["a()"], "v = 1", "a()[1]"), _line(_P, ["a()"], "v = 2", "a()[2]"),
              _line(_P, ["b()"], "v = 1", "b()[1]", "FAILURE"), _line(_P, ["b()"], "v = 2", "b()[2]")]


def case_the_listener_gives_each_method_its_own_key(tmp):
    """On disk: four XML testcases on two keys; per method they are four keys, and the build is
    complete because the lines project onto exactly the XML's sets."""
    failures = 0
    outcomes = _build_outcomes(tmp, "ae1", _AE1_CASES, _AE1_LINES)
    if outcomes.methods is None:
        return fail(f"a complete build fell back to XML keys: {outcomes.cause}")
    want = {(_P, "a() > v = 1"), (_P, "a() > v = 2"), (_P, "b() > v = 1"), (_P, "b() > v = 2")}
    if outcomes.methods.ran != want:
        failures += fail(f"per-method keys are {sorted(outcomes.methods.ran)}")
    if outcomes.methods.failed != {(_P, "b() > v = 1")}:
        failures += fail(f"per-method failures are {sorted(outcomes.methods.failed)}")
    ran, failed = outcomes
    if ran != {_V1, _V2} or failed != {_V1}:
        failures += fail(f"the XML sets moved: ran={ran} failed={failed}")
    # Two invocations of one method under one display name are one key, and stay in or out together.
    outcomes = _build_outcomes(tmp, "twice", [(_P, "v = 1", False), (_P, "v = 1", False)],
                               [_line(_P, ["a()"], "v = 1", "a()[1]"),
                                _line(_P, ["a()"], "v = 1", "a()[2]")])
    if outcomes.methods is None or outcomes.methods.ran != {(_P, "a() > v = 1")}:
        failures += fail(f"one method's two same-named invocations: "
                         f"{outcomes.methods and outcomes.methods.ran} ({outcomes.cause})")
    return failures


def case_a_class_template_still_identifies_the_method(tmp):
    """`@ParameterizedClass`: the class's invocation sits between class and method, so the method is
    the leaf; its key keeps the invocation and projects onto the XML's `(class, m)`."""
    failures = 0
    outcomes = _build_outcomes(tmp, "class-template",
                               [(_P, "m", False), (_P, "m", True)],
                               [_line(_P, ["[1] v = 1"], "m()", "m()"),
                                _line(_P, ["[2] v = 2"], "m()", "m()", "FAILURE")])
    if outcomes.methods is None:
        return fail(f"a class template fell back to XML keys: {outcomes.cause}")
    if outcomes.methods.failed != {(_P, "[2] v = 2 > m")}:
        failures += fail(f"the failing invocation's key is {outcomes.methods.failed}")
    if {run.xml_key(key) for key in outcomes.methods.ran} != {(_P, "m")}:
        failures += fail("the per-method keys do not project onto the XML key")
    return failures


def case_invocations_straight_under_their_class_are_a_collapse(tmp):
    """an engine that reports invocations under the class gives no ancestor to split them by.
    The listener still sees two own names on one display name, so the key is marked collapsed."""
    outcomes = _build_outcomes(tmp, "flat", [(_P, "v = 1", False), (_P, "v = 1", True)],
                               [_line(_P, [], "v = 1", "a()[1]"),
                                _line(_P, [], "v = 1", "b()[1]", "FAILURE")])
    if outcomes.methods is None or outcomes.methods.collapsed != {_V1}:
        return fail(f"a collapsed key was not marked: "
                    f"{outcomes.methods and outcomes.methods.collapsed} ({outcomes.cause})")
    return 0


def case_a_plain_method_class_keeps_its_xml_keys(tmp):
    """no ancestor, so a plain-method suite's keys are the XML reader's, byte for byte."""
    cases = [(_P, "c", False), (_P, "d", True), (_P, "e(TestInfo)", False)]
    outcomes = _build_outcomes(tmp, "plain", cases,
                               [_line(_P, [], "c()", "c()"),
                                _line(_P, [], "d()", "d()", "FAILURE"),
                                _line(_P, [], "e(TestInfo)", "e(TestInfo)")])
    if outcomes.methods is None:
        return fail(f"a plain-method build fell back: {outcomes.cause}")
    if outcomes.methods.ran != set(outcomes[0]) or outcomes.methods.failed != set(outcomes[1]):
        return fail(f"per-method keys {sorted(outcomes.methods.ran)} are not the XML keys "
                    f"{sorted(outcomes[0])}")
    return 0


def case_a_build_whose_lines_do_not_give_the_xml_falls_back_with_a_cause(tmp):
    """whatever stops the lines answering for the XML makes the build XML-keyed, and
    the cause is named rather than guessed around."""
    failures = 0
    checks = {
        "no marker": (_AE1_CASES, _AE1_LINES, False, "no listener marker"),
        "no file": (_AE1_CASES, None, True, "no listener file"),
        "a line short": (_AE1_CASES + [(_P, "v = 3", False)], _AE1_LINES, True, "2 XML keys"),
        "passed where the XML failed": (
            _AE1_CASES, [_line(*l[1:5]) for l in _AE1_LINES], True, "failed XML keys"),
        # A class that could not start: the XML has an entry, the listener no test line for it.
        "a suite-level failure": (
            _AE1_CASES + [("p.Q", "initializationError", True)], _AE1_LINES, True, "XML keys"),
        "a leaf with no class above it": (
            _AE1_CASES, _AE1_LINES[:3] + [_line("", ["b()"], "v = 2", "b()[2]")], True,
            "unattributable leaf"),
    }
    for what, (cases, lines, marked, cause) in checks.items():
        outcomes = _build_outcomes(tmp, what.replace(" ", "-"), cases, lines, marked)
        if outcomes.methods is not None or cause not in (outcomes.cause or ""):
            failures += fail(f"{what}: methods={outcomes.methods is not None}, "
                             f"cause={outcomes.cause!r}, expected one naming {cause!r}")
    # A retried test that failed then passed is failed, as the XML reads it, and stays complete.
    outcomes = _build_outcomes(tmp, "retried", [(_P, "c", True), (_P, "c", False)],
                               [_line(_P, [], "c()", "c()", "FAILURE"), _line(_P, [], "c()", "c()")])
    if outcomes.methods is None or outcomes.methods.failed != {(_P, "c")}:
        failures += fail(f"a retried test: {outcomes.methods and outcomes.methods.failed} "
                         f"({outcomes.cause})")
    return failures


def case_a_display_name_with_tabs_newlines_and_the_separator_round_trips(tmp):
    """Names are arbitrary: escaping must give back exactly what the XML spells."""
    failures = 0
    display = "a\tb\nc > d\\e"
    outcomes = _build_outcomes(tmp, "escapes", [(_P, display, True)],
                               [_line(_P, ["t(int) > x"], display, "t(int)[1]", "FAILURE")])
    if outcomes.methods is None:
        return fail(f"an escaped name fell back: {outcomes.cause}")
    (key,) = outcomes.methods.failed
    if run.xml_key(key) != (_P, display):
        failures += fail(f"{key!r} projects onto {run.xml_key(key)!r}, not the XML's {display!r}")
    if key[1].count(run.KEY_SEPARATOR) != 1:
        failures += fail(f"a separator inside a name was not escaped: {key[1]!r}")
    return failures


def case_only_the_measured_task_s_lines_are_read(tmp):
    """Two test tasks in one build: the other task's lines are not this task's outcomes."""
    lines = _AE1_LINES + [_line(_P, ["z()"], "v = 9", "z()[1]", task=":m:integrationTest")]
    outcomes = _build_outcomes(tmp, "two-tasks", _AE1_CASES, lines)
    if outcomes.methods is None or (_P, "z() > v = 9") in outcomes.methods.ran:
        return fail(f"another task's lines were read: {outcomes.cause}")
    return 0


# Cases needing a temp directory, which the two entry points below create once and share.
ROOTED_CASES = (case_the_listener_gives_each_method_its_own_key,
                case_a_class_template_still_identifies_the_method,
                case_invocations_straight_under_their_class_are_a_collapse,
                case_a_plain_method_class_keeps_its_xml_keys,
                case_a_build_whose_lines_do_not_give_the_xml_falls_back_with_a_cause,
                case_a_display_name_with_tabs_newlines_and_the_separator_round_trips,
                case_only_the_measured_task_s_lines_are_read,
                case_the_staged_tree_is_what_head_describes,
                case_a_classes_task_the_guest_does_not_have_is_refused_by_name,
                case_a_map_directory_is_removable_whatever_its_permissions,
                case_an_unmerged_capture_is_invisible_to_selection_and_visible_to_discarding,
                case_a_source_with_content_is_refused_rather_than_staged,
                case_a_dropped_path_that_is_absent_is_refused_rather_than_skipped,
                case_a_dropped_path_may_not_leave_the_checkout,
                case_a_project_with_no_drop_key_is_unaffected,
                case_the_reset_removes_files_a_test_run_wrote_and_nothing_else,
                case_the_preflight_edit_moves_no_line,
                case_a_dropped_class_after_a_shared_jvm_neighbour_is_a_check_row)

def test_target_ordering_behaves():
    """The pytest entry point. Asserts, where the cases only count."""
    import shutil
    import tempfile
    failures = sum(case() for case in CASES)
    root = tempfile.mkdtemp(prefix="yoriwake-run-")
    try:
        failures += case_covered_classes_reads_both_files(root)
        failures += case_a_silent_build_is_killed_rather_than_waited_out(root)
        failures += case_the_isolated_run_does_not_mutate_the_map_it_verifies_against(root)
        failures += case_results_are_read_from_a_build_directory_that_is_not_called_build(root)
        for case in ROOTED_CASES:
            failures += case(root)
    finally:
        shutil.rmtree(root, ignore_errors=True)
    assert failures == 0, f"{failures} target-ordering checks failed; run this file directly"



def main():
    import shutil
    import tempfile
    failures = 0
    for case in CASES:
        print(case.__name__)
        failures += case()
    print(case_covered_classes_reads_both_files.__name__)
    root = tempfile.mkdtemp(prefix="yoriwake-run-")
    try:
        failures += case_covered_classes_reads_both_files(root)
        print(case_a_silent_build_is_killed_rather_than_waited_out.__name__)
        failures += case_a_silent_build_is_killed_rather_than_waited_out(root)
        print(case_the_isolated_run_does_not_mutate_the_map_it_verifies_against.__name__)
        failures += case_the_isolated_run_does_not_mutate_the_map_it_verifies_against(root)
        print(case_results_are_read_from_a_build_directory_that_is_not_called_build.__name__)
        failures += case_results_are_read_from_a_build_directory_that_is_not_called_build(root)
        for case in ROOTED_CASES:
            print(case.__name__)
            failures += case(root)
    finally:
        shutil.rmtree(root, ignore_errors=True)
    print(f"\n{'FAILED' if failures else 'all passed'}: {failures} failed checks")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
