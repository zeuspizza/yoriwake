"""
Unit tests for the qualification screen.

`qualify.py` had no tests at all, which is how three of its guards shipped in a state where they
could not fire: the 40-character SHA check sat after the checkout that raises first, the zero-test
blocker asked `is None` while the counter returns 0, and `test_counts` read whatever XML was on disk
including a previous run's. Every one of those is a pure function of a string, a dict or a
directory, so none of them needed a clone to test -- they were untested because nothing here forced
the question.

Run: `python bench/test_qualify.py`
"""

import glob
import os
import re
import shutil
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import qualify  # noqa: E402


SUITE = ('<?xml version="1.0" encoding="UTF-8"?>\n'
         '<testsuite name="com.acme.Test" tests="{tests}" skipped="0" failures="{failures}" '
         'errors="0" timestamp="2026-09-05T00:00:00" hostname="h" time="{seconds}">\n')


def _results(tmp, module, task, body, build="build"):
    parts = [tmp] + ([module] if module else []) + [build, "test-results", task]
    d = os.path.join(*parts)
    os.makedirs(d, exist_ok=True)
    path = os.path.join(d, "TEST-com.acme.Test.xml")
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(body)
    return path


def case_test_counts_reads_the_task_it_was_given():
    """`jvmTest` results live in build/test-results/jvmTest, not .../test."""
    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        _results(tmp, "m", "jvmTest", SUITE.format(tests=7, failures=1, seconds="2.5"))
        tests, failed, seconds = qualify.test_counts(tmp, "m", "jvmTest", 0)
        if (tests, failed, seconds) != (7, 1, 2.5):
            print(f"  FAIL: jvmTest results read as {(tests, failed, seconds)}, expected (7, 1, 2.5)")
            failures += 1
        # The same checkout, asked for the default task, must find nothing rather than borrow.
        if qualify.test_counts(tmp, "m", "test", 0) != (None, None, None):
            print("  FAIL: asking for `test` found the jvmTest results")
            failures += 1
    return failures


def case_stale_results_are_not_this_run_s_results():
    """A task reported UP-TO-DATE runs nothing and leaves the previous run's XML in place.

    Without the freshness filter this reads as QUALIFIED with test counts from a run that did not
    happen -- the same shape as the green build that ran nothing.
    """
    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        path = _results(tmp, "m", "test", SUITE.format(tests=3, failures=0, seconds="1.0"))
        old = time.time() - 600
        os.utime(path, (old, old))
        started = time.time() - 60
        if qualify.test_counts(tmp, "m", "test", started) != (None, None, None):
            print("  FAIL: XML older than the run was counted as this run's results")
            failures += 1
        # And a fresh file IS counted.
        os.utime(path, None)
        if qualify.test_counts(tmp, "m", "test", started)[0] != 3:
            print("  FAIL: fresh results were not counted")
            failures += 1
    return failures


def case_zero_tests_is_not_a_pass():
    """`tests == 0` must be treated as the green-build-that-ran-nothing case, not as QUALIFIED.

    The blocker asked `is None`, and test_counts returns 0 whenever XML exists but carries no
    parseable count -- so "QUALIFIED: 0 tests" printed one branch to the side of the guard.
    """
    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        _results(tmp, "m", "test", '<?xml version="1.0"?>\n<testsuite name="x">\n')
        tests, _, _ = qualify.test_counts(tmp, "m", "test", 0)
        if tests != 0:
            print(f"  FAIL: unparseable XML gave tests={tests!r}, expected 0")
            failures += 1
        if tests is None or tests:
            print("  FAIL: `not tests` would not catch this case")
            failures += 1
    return failures


def case_an_abbreviated_pin_is_refused_before_anything_is_cloned():
    """The SHA guard must run BEFORE checkout_project, which raises first.

    Placed after it, the guard could never fire and the printed verdict was the raw git error it
    exists to replace -- which is exactly what micrometer and junit5 printed.
    """
    failures = 0
    calls = []
    real = qualify.harness.checkout_project
    qualify.harness.checkout_project = lambda project: calls.append(project) or "/nope"
    try:
        verdict = qualify.qualify({"name": "p", "url": "https://example.invalid/r.git",
                                   "commit": "35c56a8e", "module": "m"})
        if verdict["qualified"]:
            print("  FAIL: an 8-character pin qualified")
            failures += 1
        if "40-character" not in (verdict["blocker"] or ""):
            print(f"  FAIL: blocker did not name the cause: {verdict['blocker']!r}")
            failures += 1
        if calls:
            print("  FAIL: the checkout was attempted before the pin was validated")
            failures += 1
    finally:
        qualify.harness.checkout_project = real
    return failures


def case_a_full_pin_passes_the_guard():
    """40 characters is accepted; a fixture with no url is exempt (demo pins the string 'local')."""
    failures = 0
    real = qualify.harness.checkout_project
    qualify.harness.checkout_project = lambda project: (_ for _ in ()).throw(RuntimeError("reached"))
    try:
        for project in ({"name": "p", "url": "u", "commit": "a" * 40, "module": "m"},
                        {"name": "f", "commit": "local", "module": ""}):
            verdict = qualify.qualify(project)
            if "40-character" in (verdict["blocker"] or ""):
                print(f"  FAIL: {project['commit']!r} was refused by the length guard")
                failures += 1
    finally:
        qualify.harness.checkout_project = real
    return failures


def case_a_failed_build_is_not_an_attachment_failure():
    """A build that fails before capture must not be reported as "the plugin never attached".

    AnkiDroid attached and then REFUSED -- it enables JUnit Jupiter in-JVM parallel execution, which
    would blend per-test coverage attribution -- so the task failed and no map directory was ever
    written. The first version of the guard read that absence as "the plugin never attached", about
    the very plugin whose message was in the log. A wrong diagnosis sends the reader somewhere else
    entirely.
    """
    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        os.makedirs(os.path.join(tmp, "m"), exist_ok=True)
        log = os.path.join(tmp, "qualify.log")
        with open(log, "w", encoding="utf-8") as handle:
            handle.write(
                "> Task :m:test FAILED" + chr(10)
                + "In-JVM parallel test execution is enabled on :m:test [yoriwake] refused"
                + chr(10)
            )

        real_run, real_checkout = qualify.run, qualify.harness.checkout_project
        qualify.harness.checkout_project = lambda project: tmp
        qualify.run = lambda *a, **k: (1, 12.0)
        try:
            os.makedirs(os.path.join(tmp, "build"), exist_ok=True)
            with open(os.path.join(tmp, "settings.gradle"), "w", encoding="utf-8") as handle:
                handle.write("")
            verdict = qualify.qualify({"name": "p", "url": "u", "commit": "a" * 40, "module": "m"})
        finally:
            qualify.run, qualify.harness.checkout_project = real_run, real_checkout

        blocker = verdict["blocker"] or ""
        if "never attached" in blocker:
            print(f"  FAIL: a failed build was diagnosed as an attachment failure: {blocker[:120]}")
            failures += 1
        if "failed" not in blocker:
            print(f"  FAIL: the blocker does not say the build failed: {blocker[:120]}")
            failures += 1
    return failures


import contextlib as _contextlib


@_contextlib.contextmanager
def _stubbed_qualify(answer):
    """Replaces qualify.qualify for the duration, and the two steps that reach outside this process.

    `reap` runs `pkill -f GradleDaemon`, which kills EVERY Gradle daemon on the machine -- an IDE's,
    and once another harness's own parallel workers, whose builds died mid-run with
    "daemon disappeared unexpectedly". `publish` writes the plugin into ~/.m2. Neither is what
    these cases test; the one case about the reap ordering fakes it explicitly.
    """
    original = qualify.qualify, qualify.reap, qualify.harness.publish_plugin_under_test
    qualify.qualify = answer
    qualify.reap = lambda: None
    qualify.harness.publish_plugin_under_test = lambda: None
    try:
        yield
    finally:
        qualify.qualify, qualify.reap, qualify.harness.publish_plugin_under_test = original


@_contextlib.contextmanager
def _project_files(names, borrowers=()):
    """Minimal project TOMLs on disk, since main_with_paths reads them itself."""
    directory = tempfile.mkdtemp(prefix="yoriwake-qualify-tests-")
    paths = []
    for name in names:
        path = os.path.join(directory, f"{name}.toml")
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(f'name = "{name}"\n')
            if name in borrowers:
                handle.write('path = "bench/checkouts/detekt"\n')
        paths.append(path)
    try:
        yield paths
    finally:
        shutil.rmtree(directory, ignore_errors=True)


def case_json_output_is_parseable_on_its_own():
    """`--all --json > out.json` ran for hours and produced a file that was not JSON.

    Every progress line -- hundreds over a --all run -- shared stdout with the payload. The run was
    correct, took just as long, and its output could not be read by anything, which for a
    machine-readable flag is the entire failure. Nothing about it looked wrong while it happened.

    DRIVES main(), which the first version of this test did not. It reimplemented main()'s stdout
    redirection inline and then asserted against its own copy, so it passed whether or not qualify.py
    did any of it -- and went on passing when the payload changed from one JSON array to one object
    per line. `injected-fakes-hide-the-caller-that-ships.md` is the same shape. `qualify` itself is
    stubbed, because qualifying anything for real needs a checkout and a Gradle build; everything
    between the argument list and the bytes on stdout is the real code.
    """
    import contextlib
    import io as _io
    import json as _json

    failures = 0
    verdicts = {
        "demo": {"name": "demo", "module": "", "qualified": True, "blocker": None, "evidence": {}},
        "other": {"name": "other", "module": "", "qualified": False,
                  "blocker": "maven, not gradle", "evidence": {}},
    }

    out, err = _io.StringIO(), _io.StringIO()
    with _stubbed_qualify(lambda project: verdicts[project["name"]]), \
            _project_files(list(verdicts)) as paths, \
            contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        qualify.main_with_paths(paths, as_json=True)

    lines = [line for line in out.getvalue().splitlines() if line.strip()]
    if len(lines) != 2:
        print(f"  FAIL: expected one JSON object per project on stdout, got {len(lines)} lines")
        return failures + 1
    for line in lines:
        try:
            _json.loads(line)
        except ValueError as broken:
            print(f"  FAIL: a payload line was not JSON on its own: {broken}")
            failures += 1
    if "--- " in out.getvalue():
        print("  FAIL: progress commentary leaked into the payload stream")
        failures += 1
    # The human-facing summary must land on stderr, where a person watching a redirected run reads
    # it. `qualify` itself is stubbed here, so its own per-project commentary is not in play; the
    # summary this function prints is.
    if "qualified" not in err.getvalue():
        print(f"  FAIL: the progress commentary did not reach stderr: {err.getvalue()!r}")
        failures += 1
    return failures


def case_a_verdict_is_written_before_a_later_project_dies():
    """The run that produced an empty file after half an hour of correct work.

    A verdict that exists only in a list is lost with the process. This asserts the bytes are on
    stdout before the project that kills the run is even attempted.
    """
    import contextlib
    import io as _io
    import json as _json

    failures = 0

    def scripted(project):
        if project["name"] == "second":
            raise RuntimeError("killed mid-corpus")
        return {"name": project["name"], "module": "", "qualified": True,
                "blocker": None, "evidence": {}}

    out, err = _io.StringIO(), _io.StringIO()
    try:
        with _stubbed_qualify(scripted), _project_files(["first", "second"]) as paths, \
                contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            qualify.main_with_paths(paths, as_json=True)
    except RuntimeError:
        pass

    lines = [line for line in out.getvalue().splitlines() if line.strip()]
    if len(lines) != 1:
        print(f"  FAIL: the completed verdict was not written before the crash: {lines}")
        return failures + 1
    if _json.loads(lines[0])["name"] != "first":
        print(f"  FAIL: wrong verdict survived: {lines[0]}")
        failures += 1
    return failures


def case_a_precondition_refusal_does_not_abandon_the_corpus():
    """ktlint wanted a JDK the machine did not have and ended a 25-project run at the twelfth.

    A SystemExit from the toolchain lookup is one project's problem. The run must continue, record
    the refusal as an attempt that never began, and exit non-zero so a script knows the corpus was
    not covered.
    """
    import contextlib
    import io as _io
    import json as _json

    failures = 0

    def scripted(project):
        if project["name"] == "needs_a_jdk":
            raise SystemExit("needs a JDK 26 daemon and none was found")
        return {"name": project["name"], "module": "", "qualified": True,
                "blocker": None, "evidence": {}}

    out, err = _io.StringIO(), _io.StringIO()
    with _stubbed_qualify(scripted), \
            _project_files(["aaa", "needs_a_jdk", "zzz"]) as paths, \
            contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = qualify.main_with_paths(paths, as_json=True)

    parsed = [_json.loads(line) for line in out.getvalue().splitlines() if line.strip()]
    if len(parsed) != 3:
        print(f"  FAIL: the run did not attempt every project: {[p['name'] for p in parsed]}")
        return failures + 1
    refused = [p for p in parsed if p["name"] == "needs_a_jdk"]
    if not refused or not (refused[0]["blocker"] or "").startswith(qualify.COULD_NOT_START):
        print(f"  FAIL: the refusal was not recorded as an attempt that never began: {refused}")
        failures += 1
    if code == 0:
        print("  FAIL: an incomplete corpus exited 0, which tells a script it was covered")
        failures += 1
    return failures


def case_a_borrowed_checkout_is_qualified_last():
    """detekt-junk is `path = bench/checkouts/detekt` and sorts BEFORE detekt.toml, because
    `-` precedes `.`. On a machine with no checkouts yet it could never qualify."""
    failures = 0
    with _project_files(["detekt-junk", "detekt"], borrowers={"detekt-junk"}) as paths:
        ordered = [os.path.basename(p) for p in sorted(paths, key=qualify._corpus_order)]
    if ordered[-1] != "detekt-junk.toml":
        print(f"  FAIL: the borrower did not sort last: {ordered}")
        failures += 1
    return failures


def case_a_thrown_refusal_reaches_the_blocker():
    """caffeine's blocker is architectural and was recorded as "the build failed (exit 1)".

    The plugin's most consequential decline -- `check(source == null)` for in-JVM parallel execution,
    where interleaved tests would share one coverage agent -- THROWS. Gradle prints a thrown message
    in its failure block rather than as a logged line, so the scan for `[yoriwake]`-prefixed lines found
    nothing and the reason was dropped. A permanent property of the project then read identically to
    a build that ran out of memory that evening.
    """
    failures = 0
    log = "\n".join([
        "> Task :caffeine:compileJava",
        "",
        "FAILURE: Build failed with an exception.",
        "",
        "* What went wrong:",
        "Execution failed for task ':caffeine:test'.",
        "> In-JVM parallel test execution is enabled on :caffeine:test. Tests interleaved inside "
        "one JVM share a single coverage agent.",
        "",
        "* Try:",
        "> Run with --stacktrace option to get the stack trace.",
    ])

    found = qualify.gradle_failure(log)
    # The `>` cause, not the "Execution failed for task" headline above it: the headline names the
    # task and not the problem, which is what made the old message useless.
    if not found.startswith("In-JVM parallel test execution is enabled"):
        print(f"  FAIL: took the headline instead of the cause: {found!r}")
        failures += 1

    if qualify.gradle_failure("> Task :a:b\nBUILD SUCCESSFUL in 1s") != "":
        print("  FAIL: invented a failure reason for a successful build")
        failures += 1
    return failures


def case_first_contact_reports_the_audit_s_blockers():
    """The blocker column comes from audit.json's tokens, not from re-reading its prose."""
    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        map_dir = os.path.join(tmp, ".gradle", "yoriwake", "m-test-abc")
        os.makedirs(map_dir)
        with open(os.path.join(map_dir, "audit.json"), "w", encoding="utf-8") as handle:
            handle.write(
                '{"state": "NARROWING ONLY", "blockers": ['
                '{"token": "not-on-junit-platform", "detail": "d", "remedy": "r"},'
                '{"token": "decode-refused", "detail": "d", "remedy": "r"},'
                '{"token": "smap-absent", "detail": "d", "remedy": null}]}'
            )
        evidence = qualify.read_audit(map_dir)
    # `decode-refused` is here because it is the newest token and the one most likely to be added
    # on the Kotlin side without anyone checking it survives the trip: the map stops updating and
    # the corpus would record a project that looks healthy.
    if evidence.get("blockers") != "not-on-junit-platform,decode-refused,smap-absent":
        print(f"  FAIL: blockers not read from the payload: {evidence}")
        failures += 1
    if evidence.get("narrowing") != "narrowing-only":
        print(f"  FAIL: the audit's own state was not carried through: {evidence}")
        failures += 1
    return failures


def case_recorded_time_is_read_back_and_its_absence_is_not_zero():
    """The field the plugin writes has a reader here, and its three answers stay three."""
    failures = 0
    payloads = {
        "present": ('{"state": "NARROWING", "blockers": [], "recordedTime": '
                    '{"nanos": 1500000000, "records": 12, "recordsWithoutDuration": 0, '
                    '"workers": 4, "isSuiteDuration": false, "instrumented": true}}',
                    {"recorded_seconds": 1.5, "recorded_workers": 4}),
        "null": ('{"state": "NO CONCLUSION", "blockers": [], "recordedTime": null}',
                 {"recorded_seconds": "unknown", "recorded_workers": "unknown"}),
        "older payload with no such key": ('{"state": "NARROWING", "blockers": []}',
                                           {"recorded_seconds": "absent",
                                            "recorded_workers": "absent"}),
        "present but the duration itself is null":
            ('{"state": "NARROWING", "blockers": [], "recordedTime": '
             '{"nanos": null, "records": 3, "recordsWithoutDuration": 3, '
             '"workers": 2, "isSuiteDuration": false, "instrumented": true}}',
             {"recorded_seconds": "unknown", "recorded_workers": 2}),
        "recorded but the forks were never counted":
            ('{"state": "NARROWING", "blockers": [], "recordedTime": '
             '{"nanos": 2000000000, "records": 3, "recordsWithoutDuration": 1, '
             '"workers": null, "isSuiteDuration": false, "instrumented": true}}',
             {"recorded_seconds": 2.0, "recorded_workers": "unknown"}),
    }
    for label, (payload, expected) in payloads.items():
        with tempfile.TemporaryDirectory() as tmp:
            map_dir = os.path.join(tmp, ".gradle", "yoriwake", "m-test-abc")
            os.makedirs(map_dir)
            with open(os.path.join(map_dir, "audit.json"), "w", encoding="utf-8") as handle:
                handle.write(payload)
            evidence = qualify.read_audit(map_dir)
        for key, want in expected.items():
            if evidence.get(key) != want:
                print(f"  FAIL: {label}: {key} read as {evidence.get(key)!r}, expected {want!r}")
                failures += 1
    return failures


def case_an_audit_that_could_not_run_clears_nothing():
    """`unknown` and `none` must not read the same: an audit that failed has cleared no blocker."""
    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        evidence = qualify.read_audit(tmp)
    if evidence.get("narrowing") != "unknown":
        print(f"  FAIL: a failed audit reported a narrowing verdict: {evidence}")
        failures += 1
    if evidence.get("blockers") == "none":
        print(f"  FAIL: a failed audit reported no blockers, which is not what it found: {evidence}")
        failures += 1
    return failures


def case_no_project_file_carries_its_own_qualification_verdict():
    """One derived fact, one home.

    `qualification.txt` is written by the harness from a real run. Twelve of the twenty-five project
    files also carried a verdict of their own -- eleven a `# NOT QUALIFIED.` template header nobody
    updated once the project passed, one a `# QUALIFIED` line with figures -- and at one point
    seven of those twelve contradicted the table outright, with nothing comparing them. A cached
    copy of a derived value goes stale silently. The cause prose stays in the project file; the
    verdict does not.
    """
    failures = 0
    projects = os.path.join(os.path.dirname(os.path.abspath(__file__)), "projects")
    for name in sorted(os.listdir(projects)):
        if not name.endswith(".toml"):
            continue
        with open(os.path.join(projects, name), encoding="utf-8") as handle:
            for number, line in enumerate(handle, 1):
                if re.match(r"^#\s*(NOT\s+)?QUALIFIED\b", line.strip(), re.I):
                    print(f"  FAIL: {name}:{number} states a qualification verdict; "
                          f"bench/qualification.txt is where that lives")
                    failures += 1
    return failures


def case_the_corpus_loop_reaps_between_projects():
    """A 40-project run was killed by the OS for low memory with 15 daemons and 6.8 GB resident.

    Every project starts a daemon and a project with its own `daemon_java_version` or
    `daemon_jvmargs` starts a different one, so a long run accumulates them and never gives any
    back. The kill is indistinguishable from the project having failed, which is why this is a
    guard rather than a note. Asserts the reap happens BETWEEN projects and not after the last one
    only -- reaping once at the end reclaims nothing while the run is alive.
    """
    failed = 0
    order = []
    real_reap, real_qualify = qualify.reap, qualify.qualify

    def fake_reap():
        order.append("reap")

    def fake_qualify(project, keep=False):
        order.append(project["name"])
        return {"name": project["name"], "module": "", "qualified": True, "blocker": None,
                "evidence": {}}

    paths = []
    try:
        qualify.reap = fake_reap
        qualify.qualify = fake_qualify
        for name in ("one", "two", "three"):
            handle = tempfile.NamedTemporaryFile("w", suffix=".toml", delete=False, encoding="utf-8")
            handle.write(f'name = "{name}"\nmodule = ""\n')
            handle.close()
            paths.append(handle.name)
        qualify.main_with_paths(paths, as_json=False, publish=lambda: None)
    finally:
        qualify.reap, qualify.qualify = real_reap, real_qualify
        for path in paths:
            os.unlink(path)

    if order != ["one", "reap", "two", "reap", "three", "reap"]:
        print(f"  FAIL: expected a reap after every project, got {order}")
        failed += 1
    return failed


def case_a_stale_map_directory_is_not_this_run_attaching():
    """kotlinx-coroutines qualified with 2,191 tests and no map, four days after a probe.

    Drives `qualify.attachment` -- the shipping decision -- rather than a copy of it. The first
    version of this case recomputed the logic inline and so passed whatever the real code did, which
    is the anti-pattern `main_with_paths` was extracted to avoid and the one that let this defect
    through in the first place.
    """
    failed = 0
    with tempfile.TemporaryDirectory() as checkout:
        stale = os.path.join(checkout, ".gradle", "yoriwake", "core-jvmTest-1fb574ab")
        os.makedirs(stale)
        open(os.path.join(stale, "task-facts"), "w").close()
        declined = ["[yoriwake] :core has Test tasks but applies none of [java], so predictive test "
                    "selection is NOT configured here and nothing will be captured or selected."]

        named, captured = qualify.attachment(checkout, declined, time.time() - 5)
        if named or captured:
            print(f"  FAIL: a declined build with a stale directory read as attached "
                  f"(named={named}, captured={captured})")
            failed += 1

        # A map from BEFORE this run -- the borrowed-checkout case -- must not count either.
        old_map = os.path.join(stale, "coverage.tsv")
        with open(old_map, "w", encoding="utf-8") as handle:
            handle.write("SUCCESSFUL\t1\tdev.demo.A\tid\n")
        os.utime(old_map, (1, 1))
        named, captured = qualify.attachment(checkout, declined, time.time() - 5)
        if captured:
            print("  FAIL: a map file older than this run counted as this run capturing one")
            failed += 1

        # The same file, written during the run, does count.
        os.utime(old_map, None)
        named, captured = qualify.attachment(checkout, declined, time.time() - 5)
        if not captured:
            print("  FAIL: a map written during this run did not count")
            failed += 1

        # And the other decline must still read as attached: anki-android names a map, then refuses.
        attached_log = ["[yoriwake] :app:testPlayDebugUnitTest map=/x/.gradle/yoriwake/app-1 scope=derived(a.*)",
                        "[yoriwake] :app:testPlayDebugUnitTest: in-JVM parallel test execution is enabled"]
        named, _ = qualify.attachment(checkout, attached_log, time.time() - 5)
        if not named:
            print("  FAIL: a build that attached and then refused read as never attached")
            failed += 1
    return failed


def case_results_are_counted_from_a_build_directory_that_is_not_called_build():
    """hibernate-orm: 24,798 executed tests read as "a green build that ran nothing".

    `build_dir` was threaded into `discard_previous_results` and `test_counts` and this suite was not
    touched, so only the "build" default was ever exercised -- and a regression in either call site
    hands the corpus a wrong verdict, which costs more than the run did.
    """
    failed = 0
    with tempfile.TemporaryDirectory() as tmp:
        _results(tmp, "m", "test", SUITE.format(tests=3, failures=0, seconds="1.5")
                 + "</testsuite>\n", build="target")

        # Told where the output is, it counts. Left on the default, it must find NOTHING rather
        # than guess -- an empty read is what makes a real run look like a build that ran nothing.
        found = qualify.test_counts(tmp, "m", "test", 0, "target")
        if found[0] != 3:
            print(f"  FAIL: counted {found[0]!r} from a target/ layout, expected 3")
            failed += 1
        if qualify.test_counts(tmp, "m", "test", 0) != (None, None, None):
            print("  FAIL: the default build directory found results it was not told about")
            failed += 1

        # And the discard honours it, or the next run reports UP-TO-DATE against stale XML.
        qualify.discard_previous_results(tmp, "m", "test", "target")
        if qualify.test_counts(tmp, "m", "test", 0, "target") != (None, None, None):
            print("  FAIL: discard_previous_results did not clear the target/ layout")
            failed += 1
    return failed


def case_a_maven_root_is_screened_only_when_the_project_file_says_maven():
    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        open(os.path.join(tmp, "pom.xml"), "w").close()
        if qualify.screen_build_system(tmp, {"build": "maven"}) is not None:
            print("  FAIL: a Maven root with build = \"maven\" did not pass the screen")
            failures += 1
        refused = qualify.screen_build_system(tmp, {}) or ""
        if "build = \"maven\"" not in refused:
            print(f"  FAIL: a Maven root without the key was refused without naming it: {refused}")
            failures += 1
    return failures


MAVEN_POM = ('<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
             '<groupId>dev.demo</groupId><artifactId>{}</artifactId><version>1.0</version>'
             '</project>')


def _maven_qualify(log_text):
    """`qualify` over a staged-looking Maven checkout whose one build writes `log_text`."""
    tmp = tempfile.mkdtemp(prefix="yoriwake-qualify-maven-")
    for relative, artifact in (("pom.xml", "root"), ("app/pom.xml", "app")):
        os.makedirs(os.path.dirname(os.path.join(tmp, relative)), exist_ok=True)
        with open(os.path.join(tmp, relative), "w", encoding="utf-8") as handle:
            handle.write(MAVEN_POM.format(artifact))

    def build(command, cwd, log, **_):
        with open(log, "w", encoding="utf-8") as handle:
            handle.write(log_text)
        return 1, 3.0

    saved = (qualify.run, qualify.harness.checkout_project, qualify.harness.stage_checkout,
             qualify.maven_build.stage, qualify.maven_build.executable)
    qualify.run = build
    qualify.harness.checkout_project = lambda project: tmp
    qualify.harness.stage_checkout = lambda checkout, project: (None, [])
    qualify.maven_build.stage = lambda checkout, project: []
    qualify.maven_build.executable = lambda checkout: "mvn"
    try:
        return qualify.qualify({"name": "m", "build": "maven", "module": "app", "url": "u",
                                "commit": "a" * 40})
    finally:
        (qualify.run, qualify.harness.checkout_project, qualify.harness.stage_checkout,
         qualify.maven_build.stage, qualify.maven_build.executable) = saved
        shutil.rmtree(tmp, ignore_errors=True)


def case_a_declined_maven_execution_is_not_qualified_and_names_its_token():
    verdict = _maven_qualify(
        "[WARNING] [yoriwake] app surefire:test (default-test) declined, no-jacoco-plugin: app has "
        "no jacoco-maven-plugin prepare-agent execution; every test runs and nothing is captured\n"
        "[INFO] --- surefire:3.5.6:test (default-test) @ app ---\n[INFO] BUILD SUCCESS\n")
    blocker = verdict["blocker"] or ""
    if verdict["qualified"] or "no-jacoco-plugin" not in blocker:
        print(f"  FAIL: a declined execution qualified, or its token is missing: {blocker[:160]}")
        return 1
    return 0


def case_a_maven_run_that_ran_no_tests_does_not_read_an_earlier_runs_reports():
    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        directory = os.path.join(tmp, "app", "target", "surefire-reports")
        os.makedirs(directory)
        path = os.path.join(directory, "TEST-com.acme.Test.xml")
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(SUITE.format(tests=5, failures=1, seconds="2.0"))
        os.utime(path, (time.time() - 3600, time.time() - 3600))
        counted = qualify.maven_test_counts(tmp, "app", "surefire-reports", time.time() - 60)
        if counted != (None, None, None):
            print(f"  FAIL: an earlier run's Surefire report was counted as this run's: {counted}")
            failures += 1
        os.utime(path, None)
        counted = qualify.maven_test_counts(tmp, "app", "surefire-reports", time.time() - 60)
        if counted != (5, 1, 2.0):
            print(f"  FAIL: this run's Surefire report read as {counted}")
            failures += 1
    return failures


def case_a_maven_fixture_copy_commits_no_target_and_target_survives_restore():
    failures = 0
    with tempfile.TemporaryDirectory() as source, tempfile.TemporaryDirectory() as checkouts:
        sample = os.path.join(source, "samples", "m")
        os.makedirs(os.path.join(sample, "target", "classes"))
        os.makedirs(os.path.join(sample, ".yoriwake", "yoriwake"))
        for relative in ("pom.xml", "target/classes/A.class", ".yoriwake/yoriwake/coverage.tsv"):
            open(os.path.join(sample, relative), "w").close()
        saved = qualify.harness.SOURCE, qualify.harness.CHECKOUTS
        qualify.harness.SOURCE, qualify.harness.CHECKOUTS = source, checkouts
        try:
            path = qualify.harness.checkout_project({"name": "m", "build": "maven",
                                                     "copy_of": "samples/m"})
        finally:
            qualify.harness.SOURCE, qualify.harness.CHECKOUTS = saved
        import subprocess  # noqa: PLC0415
        tracked = subprocess.run(["git", "-C", path, "ls-files"], capture_output=True,
                                 text=True).stdout.split()
        if any(name.startswith(("target/", ".yoriwake/")) for name in tracked):
            print(f"  FAIL: the fixture committed build state: {tracked}")
            failures += 1
        os.makedirs(os.path.join(path, "app", "target", "classes"))
        open(os.path.join(path, "app", "target", "classes", "B.class"), "w").close()
        qualify.harness.restore(path)
        if not os.path.exists(os.path.join(path, "app", "target", "classes", "B.class")):
            print("  FAIL: restore() removed the compiled reactor")
            failures += 1
    return failures


CASES = (case_a_maven_root_is_screened_only_when_the_project_file_says_maven,
         case_a_declined_maven_execution_is_not_qualified_and_names_its_token,
         case_a_maven_run_that_ran_no_tests_does_not_read_an_earlier_runs_reports,
         case_a_maven_fixture_copy_commits_no_target_and_target_survives_restore,
         case_results_are_counted_from_a_build_directory_that_is_not_called_build,
         case_a_stale_map_directory_is_not_this_run_attaching,
         case_the_corpus_loop_reaps_between_projects,
         case_test_counts_reads_the_task_it_was_given,
         case_first_contact_reports_the_audit_s_blockers,
         case_an_audit_that_could_not_run_clears_nothing,
         case_stale_results_are_not_this_run_s_results,
         case_zero_tests_is_not_a_pass,
         case_an_abbreviated_pin_is_refused_before_anything_is_cloned,
         case_a_full_pin_passes_the_guard,
         case_a_failed_build_is_not_an_attachment_failure,
         case_json_output_is_parseable_on_its_own,
         case_a_verdict_is_written_before_a_later_project_dies,
         case_a_precondition_refusal_does_not_abandon_the_corpus,
         case_a_borrowed_checkout_is_qualified_last,
         case_a_thrown_refusal_reaches_the_blocker,
         case_no_project_file_carries_its_own_qualification_verdict,
         case_recorded_time_is_read_back_and_its_absence_is_not_zero)


def main():
    failed = 0
    for case in CASES:
        print(case.__name__)
        failed += case()
    print(f"\n{'all passed' if not failed else 'FAILURES'}: {failed} failed checks")
    return 1 if failed else 0



if __name__ == "__main__":
    sys.exit(main())
