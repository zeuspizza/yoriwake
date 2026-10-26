"""Guards for the shell steps a cross-OS job depends on.

Written because the steps these replace could both fail open. `find` printed nothing and the job
carried on; `sed` exited 0 when its pattern matched nothing, so a mutation step that did nothing
left the assertion reporting "ran but did not fail" -- a message about the tests rather than about
the step that broke.

It also checks that every workflow pins its actions by commit SHA, so a moved tag cannot change
what CI runs.

The style is this repository's: cases return a count of failed checks rather than asserting, because
a function that asserted would be collected by pytest and pass however many checks failed. One
`test_*` entry point asserts, so a pytest run sees exactly one test.

    python .github/test_ci.py
"""

import fnmatch
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import assert_selection  # noqa: E402
import ci  # noqa: E402
import functional_shards  # noqa: E402
import guard_shards  # noqa: E402
import matrix_check  # noqa: E402
import run_summary  # noqa: E402


def fail(message):
    print(f"  FAIL: {message}")
    return 1


def _map_at(root, task_dir, lines=3):
    """A map directory shaped like the plugin's: `<task>-<hash>/coverage.tsv`."""
    path = os.path.join(root, ".gradle", "yoriwake", task_dir)
    os.makedirs(path)
    with open(os.path.join(path, ci.MAP_FILE), "w", encoding="utf-8") as handle:
        handle.write("".join(f"dev.demo.Test{n}\tSUCCESSFUL\tdev.demo.Alpha\n" for n in range(lines)))
    return path


def case_finds_a_map_under_a_task_directory_it_could_not_predict():
    """The reason the workflow shelled out to `find`: the directory carries a hash."""
    problems = 0
    with tempfile.TemporaryDirectory() as root:
        expected = _map_at(root, "test-1fb574ab")
        found = ci.find_map(root)
        if found != [expected]:
            problems += fail(f"expected [{expected}], got {found}")
        if ci.records(expected) != 3:
            problems += fail(f"expected 3 records, got {ci.records(expected)}")
    return problems


def case_find_map_prints_a_readable_file_because_the_workflow_greps_it():
    """The defect this case exists for shipped: the workflow grepped a directory.

    `find_map()` returns directories and the CLI prints a file, and only the second is a contract
    with anything outside this module -- `verify.yml` does `grep -q ... "$map"` on what it prints.
    Asserting the return value alone is what let the mismatch through review.
    """
    problems = 0
    with tempfile.TemporaryDirectory() as root:
        _map_at(root, "test-1fb574ab")
        import contextlib
        import io

        captured = io.StringIO()
        with contextlib.redirect_stdout(captured):
            code = ci.main(["find-map", root])
        printed = captured.getvalue().strip()
        if code != 0:
            problems += fail(f"find-map exited {code} with a map present")
        if not os.path.isfile(printed):
            problems += fail(f"find-map printed {printed!r}, which is not a readable file; the "
                             f"workflow greps this path")
        elif os.path.basename(printed) != ci.MAP_FILE:
            problems += fail(f"find-map printed {printed!r}, not the map file")
    return problems


def case_no_map_is_reported_rather_than_returned_as_success():
    """A missing map is the state that must not read as an empty one."""
    problems = 0
    with tempfile.TemporaryDirectory() as root:
        if ci.find_map(root) != []:
            problems += fail("a project with no .gradle/yoriwake should find nothing")
        if ci.main(["find-map", root]) == 0:
            problems += fail("find-map exited 0 with no map; the job would carry on")
    return problems


def case_the_newest_map_wins_when_a_project_has_several():
    """One directory per test task, and a stale one must not shadow the fresh one."""
    problems = 0
    with tempfile.TemporaryDirectory() as root:
        old = _map_at(root, "test-aaaaaaaa")
        new = _map_at(root, "otherTest-bbbbbbbb")
        os.utime(old, (1, 1))
        os.utime(new, (10_000_000, 10_000_000))
        found = ci.find_map(root)
        if not found or found[0] != new:
            problems += fail(f"expected the newest map first, got {found}")
    return problems


def case_the_mutation_changes_exactly_its_line():
    problems = 0
    with tempfile.TemporaryDirectory() as root:
        source = os.path.join(root, "Alpha.java")
        with open(source, "w", encoding="utf-8") as handle:
            handle.write("int twice(int n) {\n    return n * 2;\n}\nint thrice(int n) { return n * 2; }\n")
        ci.edit(source, *ci.MUTATION)
        with open(source, encoding="utf-8") as handle:
            after = handle.read()
        if after.count("return n * 3;") != 1:
            problems += fail(f"expected one replacement, got {after.count('return n * 3;')}")
        if "int thrice(int n) { return n * 2; }" not in after:
            problems += fail("the second occurrence was changed; the mutation is not the one asserted")
    return problems


def case_a_mutation_that_would_be_silent_fails_loudly():
    """`sed` exits 0 here. That is the defect this file exists for."""
    problems = 0
    with tempfile.TemporaryDirectory() as root:
        source = os.path.join(root, "Alpha.java")
        with open(source, "w", encoding="utf-8") as handle:
            handle.write("int twice(int n) {\n    return n + n;\n}\n")
        try:
            ci.edit(source, *ci.MUTATION)
        except SystemExit:
            pass
        else:
            problems += fail("an absent pattern was accepted; the job would assert on an unmutated tree")
    return problems


def case_restore_is_the_mutation_backwards():
    """Byte for byte, line endings included: on Windows text mode wrote CRLF into an LF checkout."""
    problems = 0
    with tempfile.TemporaryDirectory() as root:
        source = os.path.join(root, "Alpha.java")
        original = b"int twice(int n) {\n    return n * 2;\n}\n"
        with open(source, "wb") as handle:
            handle.write(original)
        ci.main(["mutate", source])
        ci.main(["restore", source])
        with open(source, "rb") as handle:
            if handle.read() != original:
                problems += fail("restore did not return the file to its original bytes")
    return problems


def case_each_source_gets_its_own_mutation_and_restore():
    """Epsilon also has `return n * 2;`; Alpha's mutation applied to it would break the wrong test."""
    problems = 0
    with tempfile.TemporaryDirectory() as root:
        source = os.path.join(root, "Epsilon.java")
        original = 'public int twice(int n) {\n    return n * 2;\n}\npublic String label() { return "E"; }\n'
        with open(source, "w", encoding="utf-8") as handle:
            handle.write(original)
        ci.main(["mutate", source])
        with open(source, encoding="utf-8") as handle:
            after = handle.read()
        if "public String name()" not in after or "return n * 3;" in after:
            problems += fail(f"Epsilon was not given its own mutation: {after!r}")
        ci.main(["restore", source])
        with open(source, encoding="utf-8") as handle:
            if handle.read() != original:
                problems += fail("restore did not return Epsilon to its original bytes")
    return problems


def case_a_source_without_a_mutation_is_refused():
    try:
        ci.mutation_for("samples/demo/src/main/java/dev/demo/Beta.java")
    except SystemExit:
        return 0
    return fail("a file with no defined mutation was accepted")


def _selecting_run(root, ran, failed, decisions, notes):
    """Writes what assert_selection reads: one result file and one decision record."""
    results = os.path.join(root, "samples", "demo", "build", "test-results", "test")
    os.makedirs(results)
    cases = "".join(
        f'<testcase classname="{t.rsplit(".", 1)[0]}" name="{t.rsplit(".", 1)[1]}()">'
        + ("<failure/>" if t in failed else "") + "</testcase>"
        for t in ran
    )
    with open(os.path.join(results, "TEST-run.xml"), "w", encoding="utf-8") as handle:
        handle.write(f"<testsuite>{cases}</testsuite>")
    map_dir = os.path.join(root, "samples", "demo", ".gradle", "yoriwake", "test-1fb574ab")
    os.makedirs(map_dir)
    with open(os.path.join(map_dir, "decisions.tsv"), "w", encoding="utf-8") as handle:
        handle.write("".join(f"#!{key}\t{value}\n" for key, value in notes.items()))
        handle.write("".join(f"{t}\t{verdict}\t{reason}\n" for t, (verdict, reason) in decisions.items()))


def _asserts(ran, failed, decisions, notes, mutated="epsilon"):
    """assert_selection's exit for one round over a fake run, its printing swallowed."""
    import contextlib
    import io
    with tempfile.TemporaryDirectory() as root:
        _selecting_run(root, ran, failed, decisions, notes)
        here = os.getcwd()
        os.chdir(root)
        try:
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                return assert_selection.main(["--mutated", mutated])
        finally:
            os.chdir(here)


def case_the_epsilon_round_fails_unless_its_change_widened_the_run_for_its_reason():
    """Each check must be able to fail: a run that never read the map also runs the broken test."""
    problems = 0
    broken = "dev.demo.EpsilonRegistryTest.everyRegisteredTypeExposesTheMethodsTheRegistryPromises"
    others = [f"dev.demo.{name}.t" for name in ("AlphaTest", "BetaTest", "GammaTest", "DeltaShapeTest")]
    ran = [broken] + others
    widened = {t: ("included", "FULL_RUN") for t in ran}
    notes = {
        "input.yoriwake.change.classes": "dev.demo.Epsilon",
        "outcome": "full-run",
        "full-run-kind": "no-coverage-for-changed",
    }
    if _asserts(ran, {broken}, widened, notes) != 0:
        problems += fail("a correct Epsilon round was rejected")
    if _asserts(ran, {broken}, {**widened, broken: ("included", "NOT_IN_MAP")}, notes) == 0:
        problems += fail("the registry test included as NOT_IN_MAP passed; a map nobody read looks the same")
    # What losing the resource scan looks like: Epsilon's absence reads as proof, and the run narrows.
    narrowed = {broken: ("excluded", "SKIPPED"), **{t: ("excluded", "SKIPPED") for t in others}}
    if _asserts([], set(), narrowed, {**notes, "outcome": "narrowed", "full-run-kind": ""}) == 0:
        problems += fail("a run that skipped the registry test passed the Epsilon round")
    if _asserts(ran, {broken}, widened, {**notes, "full-run-kind": "unmappable-paths"}) == 0:
        problems += fail("a run widened for another reason passed as the resource scan's widening")
    return problems


def case_a_map_from_another_os_must_widen_the_run_and_say_why():
    """A map is per operating system: a run that narrowed from another OS's map must fail the job."""
    problems = 0
    broken = ["dev.demo.AlphaTest.doubles", "dev.demo.AlphaTest.doublesAgain"]
    others = [f"dev.demo.{name}.t" for name in ("BetaTest", "GammaTest")]
    widened = {t: ("included", "DAEMON_REFUSED") for t in broken + others}
    notes = {"full-run-kind": "daemon-refused", "refusal-kind": "environment-changed"}
    if _asserts(broken + others, set(broken), widened, notes, "alpha-on-another-os") != 0:
        problems += fail("a correct refusal of another operating system's map was rejected")
    narrowed = {**{t: ("included", "REACHES_CHANGE") for t in broken}, **{t: ("excluded", "SKIPPED") for t in others}}
    if _asserts(broken, set(broken), narrowed, {"outcome": "narrowed"}, "alpha-on-another-os") == 0:
        problems += fail("a run that narrowed from another operating system's map passed")
    if _asserts(broken + others, set(broken), widened, {**notes, "refusal-kind": "git-unavailable"}, "alpha-on-another-os") == 0:
        problems += fail("a run widened for another reason passed as the environment's refusal")
    return problems


def case_discard_removes_results_and_leaves_the_map_alone_by_default():
    """A selecting step reads the map the capturing step wrote; deleting it would delete the subject."""
    problems = 0
    with tempfile.TemporaryDirectory() as root:
        results = os.path.join(root, "build", "test-results", "test")
        os.makedirs(results)
        with open(os.path.join(results, "TEST-dev.demo.AlphaTest.xml"), "w", encoding="utf-8") as handle:
            handle.write("<testsuite/>")
        map_dir = _map_at(root, "test-1fb574ab")
        decisions = [os.path.join(map_dir, name) for name in ("decisions.tsv", "decisions.tsv.42-1.part")]
        for path in decisions:
            with open(path, "w", encoding="utf-8") as handle:
                handle.write("t\tincluded\tREACHES_CHANGE\n")

        ci.discard_results(root)
        if os.path.exists(results):
            problems += fail("a previous step's results survived; the next step would assert on them")
        if any(os.path.exists(path) for path in decisions):
            problems += fail("a previous round's decisions survived; the next round could be judged by them")
        if not os.path.exists(map_dir):
            problems += fail("the map was deleted without --map; the selecting step has nothing to read")

        ci.discard_results(root, drop_map=True)
        if os.path.exists(map_dir):
            problems += fail("--map did not delete the map")
    return problems


def case_discard_on_a_clean_tree_is_not_an_error():
    """The first step of a run has nothing to discard, and that is ordinary."""
    problems = 0
    with tempfile.TemporaryDirectory() as root:
        if ci.discard_results(root) != []:
            problems += fail("discarding nothing reported something removed")
        if ci.main(["discard", root]) != 0:
            problems += fail("discard on a clean tree exited non-zero")
    return problems


WORKFLOWS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "workflows")
USES = re.compile(r"^\s*(?:-\s*)?uses:\s*(\S+)(.*)$")
PINNED = re.compile(r"^[^@\s]+@[0-9a-f]{40}$")
VERSION_COMMENT = re.compile(r"^\s+#\s*v\d+\.\d+\.\d+\s*$")


def unpinned_uses(text):
    """Each `uses:` line not pinned to a full commit SHA with a `# vX.Y.Z` comment.

    A tag can be moved to other code without a commit here, so a tag pin changes what CI runs
    without anyone reviewing it; the comment is what tells a reader which release the SHA is.
    """
    found = []
    for number, line in enumerate(text.splitlines(), start=1):
        match = USES.match(line)
        if match and not (PINNED.match(match.group(1)) and VERSION_COMMENT.match(match.group(2))):
            found.append(f"line {number}: {line.strip()}")
    return found


def moving_runner_labels(text):
    """Each line naming `ubuntu-latest`: the label moves to a new image without a commit here, so
    CI changes underneath every job without anyone reviewing it."""
    return [f"line {number}: {line.strip()}" for number, line in enumerate(text.splitlines(), start=1)
            if "ubuntu-latest" in line]


def unexplained_uploads(text):
    """Each `upload-artifact` step without `retention-days` in its block, or without a `#` comment
    on the line above it: an artifact nobody sized is kept 90 days, and one nobody named a reader
    for cannot be sized. A step opened by `- if:` starts there."""
    lines = text.splitlines()
    found = []
    for number, line in enumerate(lines):
        if "actions/upload-artifact@" not in line or not line.lstrip().startswith(("- uses:", "uses:")):
            continue
        start = number
        if not line.lstrip().startswith("- "):
            start = next(i for i in range(number, -1, -1) if lines[i].lstrip().startswith("- "))
        indent = len(lines[start]) - len(lines[start].lstrip())
        end = next((i for i in range(start + 1, len(lines))
                    if lines[i].strip() and len(lines[i]) - len(lines[i].lstrip()) <= indent), len(lines))
        if not any(l.strip().startswith("retention-days:") for l in lines[start:end]):
            found.append(f"line {number + 1}: no retention-days")
        if start == 0 or not lines[start - 1].strip().startswith("#"):
            found.append(f"line {number + 1}: no comment naming its reader above the step")
    return found


def case_an_upload_without_a_retention_or_a_reader_is_reported():
    problems = 0
    good = ("      # The check job reads it.\n"
            "      - uses: actions/upload-artifact@0123456789012345678901234567890123456789 # v4.0.0\n"
            "        with:\n          name: a\n          retention-days: 1\n")
    if unexplained_uploads(good):
        problems += fail(f"an upload with a comment and a retention was reported: {unexplained_uploads(good)}")
    guarded = good.replace("      - uses:", "      - if: failure()\n        uses:")
    if unexplained_uploads(guarded):
        problems += fail(f"an upload opened by `- if:` was reported: {unexplained_uploads(guarded)}")
    if not any("retention" in p for p in unexplained_uploads(good.replace("          retention-days: 1\n", ""))):
        problems += fail("an upload without retention-days passed")
    if not any("comment" in p for p in unexplained_uploads("      run: x\n" + good.split("\n", 1)[1])):
        problems += fail("an upload whose line above is code passed")
    borrowed = good.replace("          retention-days: 1\n", "") + "      - run: true\n        retention-days: 1\n"
    if not any("retention" in p for p in unexplained_uploads(borrowed)):
        problems += fail("a retention-days of the next step was counted for this one")
    return problems


def case_every_upload_names_its_retention_and_its_reader():
    problems = 0
    for name in sorted(n for n in os.listdir(WORKFLOWS) if n.endswith((".yml", ".yaml"))):
        with open(os.path.join(WORKFLOWS, name), encoding="utf-8") as handle:
            for problem in unexplained_uploads(handle.read()):
                problems += fail(f"{name} {problem}")
    return problems


def gradle_notice_problems(text):
    """What lets setup-gradle's out-of-date notice back, or saves a cache under a live daemon.

    The build runs on the supported floor on purpose, and only the build-capture switch drops the
    action's notice about it; with capture off, a job that writes the cache must stop its own
    daemons, which the action stopped for captured builds, or a Windows save can hit a lock.
    """
    if "gradle/actions/setup-gradle" not in text:
        return []
    problems = []
    if not re.search(r"^env:\n(?:  .*\n)*  GRADLE_ACTIONS_SKIP_BUILD_RESULT_CAPTURE: 'true'$", text, re.M):
        problems.append("no top-level env setting GRADLE_ACTIONS_SKIP_BUILD_RESULT_CAPTURE to 'true'")
    lines = text.splitlines()
    start = next(i for i, line in enumerate(lines) if line.startswith("jobs:"))
    heads = [(i, m.group(1)) for i, line in enumerate(lines)
             if i > start and (m := re.match(r"^  ([\w-]+):\s*$", line))]
    for n, (head, name) in enumerate(heads):
        job = lines[head:heads[n + 1][0] if n + 1 < len(heads) else len(lines)]
        for k, line in enumerate(job):
            if "gradle/actions/setup-gradle" not in line:
                continue
            indent = len(line) - len(line.lstrip())
            step = []
            for later in job[k + 1:]:
                if later.strip() and len(later) - len(later.lstrip()) <= indent:
                    break
                step.append(later)
            if any("cache-read-only" in s or "cache-disabled" in s for s in step):
                continue
            body = [l for l in job if l.strip() and len(l) - len(l.lstrip()) >= 4]
            if not body or body[-1].strip() != "run: ./gradlew --stop":
                problems.append(f"job {name} writes the Gradle cache and does not end by stopping the daemons")
    return problems


def case_every_workflow_keeps_the_gradle_notice_off_and_saves_no_cache_under_a_daemon():
    problems = 0
    for name in sorted(n for n in os.listdir(WORKFLOWS) if n.endswith((".yml", ".yaml"))):
        with open(os.path.join(WORKFLOWS, name), encoding="utf-8") as handle:
            for problem in gradle_notice_problems(handle.read()):
                problems += fail(f"{name}: {problem}")
    return problems


def case_a_workflow_without_the_switch_or_a_writing_job_without_the_stop_is_reported():
    problems = 0
    head = "permissions:\n  contents: read\n\n"
    switch = "env:\n  GRADLE_ACTIONS_SKIP_BUILD_RESULT_CAPTURE: 'true'\n\n"
    setup = "      - uses: gradle/actions/setup-gradle@0123456789012345678901234567890123456789 # v6.4.0\n"
    stop = "      - name: stop\n        if: always()\n        run: ./gradlew --stop\n"
    writing = "jobs:\n  build:\n    steps:\n" + setup + "      - run: ./gradlew build\n"
    reading = ("jobs:\n  read:\n    steps:\n" + setup + "        with:\n          cache-read-only: true\n"
               "      - run: ./gradlew build\n")
    if gradle_notice_problems(head + switch + writing + stop):
        problems += fail(f"a complete workflow was reported: {gradle_notice_problems(head + switch + writing + stop)}")
    if not any("GRADLE_ACTIONS_SKIP" in p for p in gradle_notice_problems(head + writing + stop)):
        problems += fail("a workflow without the switch passed")
    if not any("job build" in p for p in gradle_notice_problems(head + switch + writing)):
        problems += fail("a cache-writing job without the stop passed")
    if gradle_notice_problems(head + switch + reading):
        problems += fail("a read-only job was required to stop its daemons")
    if gradle_notice_problems("on: push\njobs:\n  scan:\n    steps:\n      - run: true\n"):
        problems += fail("a workflow that sets no Gradle up was required to carry the switch")
    return problems


def case_a_tag_pin_is_reported():
    problems = 0
    if not unpinned_uses("    steps:\n      - uses: actions/checkout@v7\n"):
        problems += fail("`actions/checkout@v7` passed the pin check")
    return problems


def case_a_sha_without_its_version_comment_is_reported():
    problems = 0
    line = "      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1\n"
    if not unpinned_uses(line):
        problems += fail("a SHA pin without a `# vX.Y.Z` comment passed the pin check")
    if unpinned_uses(line.rstrip("\n") + " # v7.0.1\n"):
        problems += fail("a SHA pin with its version comment was reported")
    return problems


def case_no_workflow_names_a_moving_linux_runner():
    problems = 0
    for text, expected in (
        ("    runs-on: ubuntu-latest\n", 1),
        ("        runner: [ubuntu-latest, windows-latest]\n", 1),
        ("    runs-on: ubuntu-24.04\n        os: windows-latest\n", 0),
    ):
        if len(moving_runner_labels(text)) != expected:
            problems += fail(f"moving_runner_labels({text!r}) should report {expected} line(s)")
    for name in sorted(name for name in os.listdir(WORKFLOWS) if name.endswith((".yml", ".yaml"))):
        with open(os.path.join(WORKFLOWS, name), encoding="utf-8") as handle:
            for problem in moving_runner_labels(handle.read()):
                problems += fail(f"{name} {problem}")
    return problems


def case_every_workflow_pins_its_actions_by_sha():
    problems = 0
    names = sorted(name for name in os.listdir(WORKFLOWS) if name.endswith((".yml", ".yaml")))
    if not names:
        problems += fail(f"no workflows found under {WORKFLOWS}")
    for name in names:
        with open(os.path.join(WORKFLOWS, name), encoding="utf-8") as handle:
            for problem in unpinned_uses(handle.read()):
                problems += fail(f"{name} {problem}")
    return problems


def case_the_early_access_job_runs_weekly_bounded_and_read_only():
    """Its value is a weekly answer before a JDK ships; a run that hangs or writes is not that."""
    problems = 0
    with open(os.path.join(WORKFLOWS, "jdk-ea.yml"), encoding="utf-8") as handle:
        text = handle.read()
    for needle, why in (
        ("  schedule:\n    - cron: ", "it is not scheduled"),
        ("  workflow_dispatch:", "it cannot be dispatched on a release candidate"),
        ("permissions:\n  contents: read", "its token is not read-only"),
        ("    timeout-minutes: 30", "its job has no 30-minute bound"),
        ("release: EA", "it does not install an early-access build"),
        ("-Pyoriwake.functional.earlyAccessJdk=", "it does not name the JDK to the range test"),
        ("matrix_check.py early-access plugin/build/test-results/functionalTestGradle9",
         "it does not check its test ran"),
    ):
        if needle not in text:
            problems += fail(f"jdk-ea.yml: {why}")
    return problems


def case_the_early_access_check_passes_only_when_its_test_passed():
    """A skipped test is a green Gradle task, so the job's only proof is the case's own status."""
    problems = 0
    early = ("TestJvmRangeFunctionalTest", "test JVM on the newest early-access JDK(File)")
    for cases, expected in (
        ([early + ("passed",), ("TestJvmRangeFunctionalTest", JDK_11, "skipped")], None),
        ([early + ("skipped",)], "skipped"),
        ([early + ("failed",)], "failed"),
        ([("TestJvmRangeFunctionalTest", JDK_11, "passed")], "absent"),
    ):
        with tempfile.TemporaryDirectory() as root:
            directory = os.path.join(_cell(root, "linux/9.8.0/21", cases=cases), "functionalTest")
            found = matrix_check.early_access_problems(directory)
        if expected is None and found:
            problems += fail(f"a passed early-access case was refused: {found}")
        if expected is not None and (len(found) != 1 or f": {expected}," not in found[0]
                                     or matrix_check.EARLY_ACCESS_TEST not in found[0]):
            problems += fail(f"a {expected} early-access case was not named as {expected}: {found}")
    with tempfile.TemporaryDirectory() as root:
        found = matrix_check.early_access_problems(root)
    if len(found) != 1 or "ran no tests" not in found[0]:
        problems += fail(f"empty results were not refused as running no tests: {found}")
    return problems


def matrix_schedule_problems(text):
    """What would let a cell break on main unnoticed, or let a dispatch test something unchecked."""
    problems = []
    if not re.search(r"^  schedule:\n    - cron: ", text, re.M):
        problems.append("it is not scheduled")
    if not re.search(r"^  workflow_dispatch:\n    inputs:\n      candidate:\n", text, re.M):
        problems.append("it cannot be dispatched on a named candidate")
    if text.count("inputs.candidate") != 1 or \
            "${{ github.event_name == 'schedule' && github.sha || inputs.candidate }}" not in text:
        problems.append("a job reads the input itself, or a dispatch can fall back to the branch head")
    # Every checkout but the candidate job's own, which checks out the branch to test ancestry on,
    # and the run summary's, which reads only this run's own script and workflow.
    if text.count("ref: ${{ needs.candidate.outputs.sha }}") != text.count("uses: actions/checkout@") - 2 or \
            '--candidate "$CANDIDATE"' not in text:
        problems.append("the jobs do not all test the candidate the first job checked")
    report = re.search(r"^  report-failure:\n((?:    [^\n]*\n|\n)*)", text, re.M)
    if not report or "    if: failure() && github.event_name == 'schedule'\n" not in report.group(1) \
            or "issues: write" not in report.group(1) or "gh issue create" not in report.group(1):
        problems.append("a failed scheduled run opens no issue")
    elif not re.search(r"^    needs: \[candidate, cell, capture, select, summary\]\n", report.group(1), re.M):
        problems.append("a job the report does not wait for can fail without an issue")
    return problems


def case_the_matrix_runs_nightly_and_reports_a_failed_night():
    with open(os.path.join(WORKFLOWS, "matrix.yml"), encoding="utf-8") as handle:
        text = handle.read()
    problems = sum(fail(f"matrix.yml: {problem}") for problem in matrix_schedule_problems(text))
    for edited, expected in (
        (text.replace("  schedule:\n    - cron: '41 23 * * *'\n", ""), "not scheduled"),
        (text.replace("github.event_name == 'schedule' && github.sha || inputs.candidate", "inputs.candidate || github.sha"),
         "fall back"),
        (text.replace("ref: ${{ needs.candidate.outputs.sha }}", "ref: ${{ inputs.candidate }}", 1), "reads the input"),
        (text.replace("    if: failure() && github.event_name == 'schedule'\n", "    if: failure()\n"), "opens no issue"),
        (text.replace("ref: ${{ needs.candidate.outputs.sha }}\n", "", 1), "do not all test the candidate"),
        (text.replace("select, summary]\n    if: failure()", "select]\n    if: failure()"), "does not wait for"),
    ):
        if edited == text:
            problems += fail(f"the fixture edit for {expected!r} no longer applies to matrix.yml")
        elif not any(expected in problem for problem in matrix_schedule_problems(edited)):
            problems += fail(f"an edited matrix.yml was not reported for {expected!r}")
    return problems


def case_a_failure_that_lasts_is_one_issue_not_one_a_night():
    """A scheduled run that fails until someone fixes it would otherwise open an issue each night."""
    problems = 0
    for name in ("guards.yml", "sweep.yml", "matrix.yml"):
        with open(os.path.join(WORKFLOWS, name), encoding="utf-8") as handle:
            report = re.search(r"^  report-failure:\n((?:    [^\n]*\n|\n)*)", handle.read(), re.M)
        block = report.group(1) if report else ""
        for needle in ("gh issue list", "select(.title == env.TITLE)", 'gh issue comment "$open"',
                       'gh issue create -R "$REPO" --title "$TITLE"'):
            if needle not in block:
                problems += fail(f"{name}: a failed scheduled run does not reuse the open issue ({needle!r} is missing)")
    return problems

LABELS = ["linux/8.14/21", "linux/9.8.0/21"]
ROWS = [
    ("Gradle", "8.14", ["*/8.14/*"], ["MatrixCellIdentityTest"]),
    ("Gradle", "9.8.0", ["*/9.8.0/*"], ["MatrixCellIdentityTest"]),
    ("Daemon JDK", "21", ["*/*/21"], ["MatrixCellIdentityTest"]),
    ("OS", "Linux", ["linux/*/*"], ["MatrixCellIdentityTest"]),
    ("Test JVM JDK", "11", ["*/*/*"], ["TestJvmRangeFunctionalTest.test JVM on JDK 11"]),
]
IDENTITY = "the builds run on the Gradle and daemon JDK this cell asks for(File)"
JDK_11 = "test JVM on JDK 11(File)"


def _cell(artifacts, label, sha="a" * 40, cases=None, observed=None, task="functionalTest"):
    """A cell's artifact as the workflow uploads it: `sha.txt` and one task's JUnit XML."""
    _, gradle, jdk = label.split("/")
    directory = os.path.join(artifacts, "cell-" + label.replace("/", "-"))
    os.makedirs(os.path.join(directory, task))
    with open(os.path.join(directory, "sha.txt"), "w", encoding="utf-8") as handle:
        handle.write(sha + "\n")
    if cases is None:
        cases = [("MatrixCellIdentityTest", IDENTITY, "passed"), ("TestJvmRangeFunctionalTest", JDK_11, "passed")]
    by_class = {}
    for name, case, status in cases:
        by_class.setdefault(name, []).append((case, status))
    for name, entries in by_class.items():
        body = "".join(
            f'<testcase name="{case}" classname="io.example.{name}">'
            + {"passed": "", "skipped": "<skipped/>", "failed": "<failure message=\"x\"/>"}[status]
            + "</testcase>"
            for case, status in entries
        )
        out = f"yoriwake-cell {observed or f'gradle={gradle} jdk={jdk}'}" if name == "MatrixCellIdentityTest" else ""
        with open(os.path.join(directory, task, f"TEST-io.example.{name}.xml"), "w", encoding="utf-8") as handle:
            handle.write(f'<testsuite name="io.example.{name}">{body}<system-out><![CDATA[{out}]]></system-out></testsuite>')
    return directory


def _results(artifacts, labels=LABELS, rows=ROWS, allowlist=()):
    return matrix_check.results_problems(artifacts, "a" * 40, labels, rows, list(allowlist))[0]


def _expect(problems, fragment, what):
    if not any(fragment in problem for problem in problems):
        return fail(f"{what}: expected a problem naming {fragment!r}, got {problems}")
    return 0


def case_the_reference_table_and_the_matrix_cells_agree():
    """On the real files: every table row has its cells, every cell its rows."""
    with open(matrix_check.WORKFLOW, encoding="utf-8") as handle:
        labels = matrix_check.cells(handle.read())
    with open(matrix_check.REFERENCE, encoding="utf-8") as handle:
        rows = matrix_check.table_rows(handle.read())
    problems = 0
    for problem in matrix_check.table_problems(labels, rows):
        problems += fail(problem)
    return problems


def case_a_table_that_drifts_from_the_cells_is_reported():
    problems = 0
    problems += _expect(
        matrix_check.table_problems(LABELS, ROWS + [("Gradle", "9.9.0", ["*/9.9.0/*"], ["X"])]),
        "9.9.0", "a doc row with no cell")
    problems += _expect(
        matrix_check.table_problems(LABELS + ["linux/9.8.0/25"], ROWS),
        "Daemon JDK 25", "a cell with no row")
    problems += _expect(
        matrix_check.table_problems(LABELS, [("Gradle", "8.14", ["*/9.8.0/*"], ["X"])] + ROWS[1:]),
        "row Gradle '8.14'", "a row naming another version's cells")
    if matrix_check.table_problems(LABELS, ROWS):
        problems += fail(f"a matching table was reported: {matrix_check.table_problems(LABELS, ROWS)}")
    return problems


def case_a_malformed_row_or_axis_is_reported():
    """A row that renders but does not parse would otherwise be checked by nothing."""
    problems = 0
    text = "### Supported matrix\n| Axis | Value | Cells | Proved by |\n|---|---|---|---|\n| Engine | TestNG | all |\n"
    problems += _expect(matrix_check.table_problems(LABELS, ROWS + matrix_check.table_rows(text)),
                        "four columns", "a three-column row")
    problems += _expect(matrix_check.table_problems(LABELS, ROWS + [("Platform", "x", ["*/*/*"], ["X"])]),
                        "unknown axis", "an unknown axis")
    problems += _expect(matrix_check.table_problems(LABELS, ROWS + [("Engine", "x", ["*/*/*"], [])]),
                        "names no proving test", "a row with no proving test")
    return problems


def case_a_cell_line_that_cannot_be_read_or_lies_about_its_os_stops_the_check():
    problems = 0
    for line, what in (
        ("          - { os: linux, runner: ubuntu-latest, gradle: '9.8.0', jdk: '21', task: functionalTestGradle9 }",
         "reordered keys"),
        ("          - { os: macos, runner: ubuntu-latest, gradle: '9.8.0', task: functionalTestGradle9, jdk: '21' }",
         "a macOS label on a Linux runner"),
    ):
        try:
            matrix_check.cells(line)
        except SystemExit:
            continue
        problems += fail(f"{what} was accepted")
    return problems


def case_a_complete_run_is_green():
    problems = 0
    with tempfile.TemporaryDirectory() as artifacts:
        for label in LABELS:
            _cell(artifacts, label)
        for problem in _results(artifacts):
            problems += fail(f"a complete run reported {problem}")
    return problems


def case_a_cell_missing_a_test_the_reference_ran_fails_naming_it():
    with tempfile.TemporaryDirectory() as artifacts:
        _cell(artifacts, "linux/9.8.0/21", cases=[
            ("MatrixCellIdentityTest", IDENTITY, "passed"), ("TestJvmRangeFunctionalTest", JDK_11, "passed"),
            ("SelectionFunctionalTest", "a change selects(File)", "passed")])
        _cell(artifacts, "linux/8.14/21")
        return _expect(_results(artifacts), "SelectionFunctionalTest.a change selects", "a missing test")


def case_a_skip_passes_only_where_the_allowlist_names_it():
    problems = 0
    cases = [("MatrixCellIdentityTest", IDENTITY, "passed"), ("TestJvmRangeFunctionalTest", JDK_11, "passed"),
             ("DeclineFunctionalTest", "older Gradle(File)", "skipped")]
    with tempfile.TemporaryDirectory() as artifacts:
        for label in LABELS:
            _cell(artifacts, label, cases=cases)
        allowed = [("DeclineFunctionalTest.older Gradle", "*/*/*", "reason")]
        if _results(artifacts, allowlist=allowed):
            problems += fail(f"an allowlisted skip was reported: {_results(artifacts, allowlist=allowed)}")
        elsewhere = [("DeclineFunctionalTest.older Gradle", "*/9.8.0/*", "reason")]
        problems += _expect(_results(artifacts, allowlist=elsewhere), "linux/8.14/21: DeclineFunctionalTest",
                            "a skip allowlisted for other cells")
        problems += _expect(_results(artifacts), "skipped", "an unlisted skip")
    return problems


def case_a_cell_with_no_tests_fails():
    with tempfile.TemporaryDirectory() as artifacts:
        _cell(artifacts, "linux/9.8.0/21")
        _cell(artifacts, "linux/8.14/21", cases=[])
        return _expect(_results(artifacts), "linux/8.14/21: ran no tests", "a cell with zero tests")


def case_a_cell_that_ran_the_defaults_under_another_label_fails():
    """The identity test passes on the defaults; only the label comparison catches the cell."""
    with tempfile.TemporaryDirectory() as artifacts:
        _cell(artifacts, "linux/9.8.0/21")
        _cell(artifacts, "linux/9.8.0/25", observed="gradle=9.8.0 jdk=21")
        labels = ["linux/9.8.0/21", "linux/9.8.0/25"]
        return _expect(_results(artifacts, labels=labels), "linux/9.8.0/25: the builds ran on", "a mislabelled cell")


def case_a_row_whose_proving_test_ran_nowhere_fails():
    """Absent from every cell, the reference included, so the comparison cannot see it."""
    cases = [("MatrixCellIdentityTest", IDENTITY, "passed")]
    with tempfile.TemporaryDirectory() as artifacts:
        for label in LABELS:
            _cell(artifacts, label, cases=cases)
        return _expect(_results(artifacts), "row Test JVM JDK '11'", "a row whose test ran nowhere")


def case_a_row_whose_proving_test_was_skipped_fails_even_where_the_skip_is_allowed():
    """An allowlisted skip keeps the cell green, but the row the skipped test proves does not stand."""
    cases = [("MatrixCellIdentityTest", IDENTITY, "passed"), ("TestJvmRangeFunctionalTest", JDK_11, "skipped")]
    with tempfile.TemporaryDirectory() as artifacts:
        for label in LABELS:
            _cell(artifacts, label, cases=cases)
        allowed = [("TestJvmRangeFunctionalTest.test JVM on JDK 11", "*/*/*", "reason")]
        return _expect(_results(artifacts, allowlist=allowed), "did not pass", "a skipped proving test")

def case_a_failed_test_or_missing_identity_fails_the_cell():
    problems = 0
    with tempfile.TemporaryDirectory() as artifacts:
        _cell(artifacts, "linux/9.8.0/21")
        _cell(artifacts, "linux/8.14/21", cases=[
            ("MatrixCellIdentityTest", IDENTITY, "passed"), ("TestJvmRangeFunctionalTest", JDK_11, "failed")])
        problems += _expect(_results(artifacts), "linux/8.14/21: TestJvmRangeFunctionalTest.test JVM on JDK 11 failed",
                            "a failed test")
    with tempfile.TemporaryDirectory() as artifacts:
        _cell(artifacts, "linux/9.8.0/21")
        _cell(artifacts, "linux/8.14/21", cases=[("TestJvmRangeFunctionalTest", JDK_11, "passed")])
        problems += _expect(_results(artifacts), "identity test did not pass (absent)", "no identity test")
    return problems


def case_a_missing_or_wider_cell_fails():
    problems = 0
    with tempfile.TemporaryDirectory() as artifacts:
        _cell(artifacts, "linux/9.8.0/21")
        problems += _expect(_results(artifacts), "linux/8.14/21: no artifact", "a cell that uploaded nothing")
    with tempfile.TemporaryDirectory() as artifacts:
        _cell(artifacts, "linux/9.8.0/21")
        _cell(artifacts, "linux/8.14/21", cases=[
            ("MatrixCellIdentityTest", IDENTITY, "passed"), ("TestJvmRangeFunctionalTest", JDK_11, "passed"),
            ("OnlyHereTest", "extra(File)", "passed")])
        problems += _expect(_results(artifacts), "ran 1 test(s) the reference cell did not", "a test only one cell ran")
    with tempfile.TemporaryDirectory() as artifacts:
        _cell(artifacts, "linux/9.8.0/21")
        os.remove(os.path.join(_cell(artifacts, "linux/8.14/21"), "sha.txt"))
        problems += _expect(_results(artifacts), "linux/8.14/21: no sha.txt", "a cell with no sha.txt")
    return problems


def case_a_job_that_ran_another_commit_fails():
    problems = 0
    with tempfile.TemporaryDirectory() as artifacts:
        for label in LABELS:
            _cell(artifacts, label)
        os.makedirs(os.path.join(artifacts, "sha-select-linux-on-macos"))
        with open(os.path.join(artifacts, "sha-select-linux-on-macos", "sha.txt"), "w", encoding="utf-8") as handle:
            handle.write("b" * 40)
        problems += _expect(_results(artifacts), "sha-select-linux-on-macos: ran", "a cross-OS job on another sha")
    with tempfile.TemporaryDirectory() as artifacts:
        _cell(artifacts, "linux/9.8.0/21")
        _cell(artifacts, "linux/8.14/21", sha="c" * 40)
        problems += _expect(_results(artifacts), "linux/8.14/21: ran ccc", "a cell on another sha")
    return problems


def case_a_cell_with_two_tasks_results_fails():
    with tempfile.TemporaryDirectory() as artifacts:
        _cell(artifacts, "linux/9.8.0/21")
        directory = _cell(artifacts, "linux/8.14/21")
        os.makedirs(os.path.join(directory, "functionalTestGradle9"))
        return _expect(_results(artifacts), "one functional task", "results of two tasks")


def _sources(root, files):
    """Writes Kotlin sources under `root` and returns their directory."""
    for name, text in files.items():
        path = os.path.join(root, "src", name)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(text)
    return os.path.join(root, "src")


def _shard(root, index, count, classes):
    """One shard's results: a testcase per class (a nested class's name included)."""
    path = os.path.join(root, "shards", f"functional-linux-functionalTest-{index}-of-{count}")
    os.makedirs(path)
    cases = "".join(f'<testcase classname="{name}" name="t()" time="1.5"/>' for name in classes)
    with open(os.path.join(path, f"TEST-{index}.xml"), "w", encoding="utf-8") as handle:
        handle.write(f"<testsuite>{cases}</testsuite>")


SHARD_SOURCES = {
    "a/AlphaTest.kt": "package a\n\nclass AlphaTest {\n    @Test\n    fun one() {}\n}\n",
    "a/BetaTest.kt": "package a\n\nclass BetaTest {\n    @ParameterizedTest\n    fun many() {}\n}\n",
    # A base class, a fixture's source in a string, and the separately run package: no candidates.
    "a/Support.kt": "package a\n\nabstract class Support {\n    val fixture = \"\"\"\n        class Fixture {\n            @Test void x() {}\n        }\n    \"\"\"\n}\n",
    "breaking/AttemptTest.kt": "package io.github.zeuspizza.yoriwake.gradle.breaking\n\nclass AttemptTest {\n    @Test\n    fun t() {}\n}\n",
}


def case_the_source_scan_finds_the_test_classes_and_only_them():
    with tempfile.TemporaryDirectory() as root:
        found = functional_shards.candidates(_sources(root, SHARD_SOURCES))
    return 0 if found == {"a.AlphaTest", "a.BetaTest"} else fail(f"candidates were {sorted(found)}")


def case_shards_that_partition_the_suite_pass():
    with tempfile.TemporaryDirectory() as root:
        sources = _sources(root, SHARD_SOURCES)
        _shard(root, 1, 2, ["a.AlphaTest", "a.AlphaTest$Nested"])
        _shard(root, 2, 2, ["a.BetaTest"])
        problems = functional_shards.check(os.path.join(root, "shards"), 2, sources)
    return fail(f"a partition was rejected: {problems}") if problems else 0


def case_every_way_a_test_class_can_go_untested_fails():
    """Each check must be able to fail: a green shard set that skipped a class is the defect."""
    problems = 0
    scenarios = {
        "a class in no shard": ([["a.AlphaTest"], []], "a.BetaTest ran in no shard"),
        "a class in two shards": ([["a.AlphaTest", "a.BetaTest"], ["a.BetaTest"]], "ran in shards"),
        "a missing shard": ([["a.AlphaTest", "a.BetaTest"], None], "shard 2 of 2 left no results"),
        "a class the scan missed": ([["a.AlphaTest"], ["a.BetaTest", "a.Gamma"]], "the source scan missed it"),
    }
    for label, (shards, expected) in scenarios.items():
        with tempfile.TemporaryDirectory() as root:
            sources = _sources(root, SHARD_SOURCES)
            os.makedirs(os.path.join(root, "shards"))
            for index, classes in enumerate(shards, start=1):
                if classes is not None:
                    _shard(root, index, 2, classes)
            found = functional_shards.check(os.path.join(root, "shards"), 2, sources)
        if not any(expected in problem for problem in found):
            problems += fail(f"{label} passed the check: {found}")
    with tempfile.TemporaryDirectory() as root:
        os.makedirs(os.path.join(root, "shards", "functional-linux-functionalTest-1-of-1"))
        if not functional_shards.check(os.path.join(root, "shards"), 1, os.path.join(root, "none")):
            problems += fail("a check with no test classes to compare against passed")
    return problems


def case_every_sharded_suite_runs_all_its_shards():
    """The workflow's shard lists, the property's count and the check's count agree, 1..N; each
    check job runs when a shard fails, fails then, and reads its own suite's results."""
    problems = 0
    with open(os.path.join(WORKFLOWS, "verify.yml"), encoding="utf-8") as handle:
        text = handle.read()
    jobs = re.split(r"\n  (?=[\w-]+:\n)", text)
    sharded = [job for job in jobs if "-Pyoriwake.functional.shard=" in job]
    if not sharded:
        return fail("verify.yml runs no functional suite in shards")
    for job in sharded:
        name = job.split(":", 1)[0]
        listed = re.search(r"shard: \[([\d, ]+)\]", job)
        count = re.search(r"-Pyoriwake\.functional\.shard=\$\{\{ matrix\.shard \}\}/(\d+)", job)
        if not listed or not count:
            problems += fail(f"{name}: no shard list or shard count")
            continue
        n = int(count.group(1))
        if [int(x) for x in listed.group(1).split(",")] != list(range(1, n + 1)):
            problems += fail(f"{name}: shards {listed.group(1)} are not 1..{n}")
        check = next((j for j in jobs if re.search(rf"\n    needs: (\[changes, )?{re.escape(name)}\]?\n", j)), "") + "\n"
        if f"--count {n}\n" not in check:
            problems += fail(f"{name}: its check does not expect {n} shards")
        # Without these a failed shard skips its check, and GitHub counts a skipped required check
        # as passing.
        if not re.search(r"\n    if: always\(\)( &&[^\n]*)?\n", check):
            problems += fail(f"{name}: its check job does not run when a shard fails (if: always())")
        if f"if: needs.{name}.result != 'success'" not in check or "exit 1" not in check:
            problems += fail(f"{name}: its check job does not fail when a shard did not succeed")
        # One suite's check must read that suite's results, not another's.
        task = re.search(r"\./gradlew :plugin:(\w+)", job)
        upload = re.search(r"name: (functional-\w+-(\w+)-)\$\{\{ matrix\.shard \}\}-of-(\d+)", job)
        if not task or not upload or upload.group(2) != task.group(1) or upload.group(3) != str(n):
            problems += fail(f"{name}: its artifact name does not name its task and shard count")
            continue
        if f"path: plugin/build/test-results/{task.group(1)}/*.xml" not in job:
            problems += fail(f"{name}: it does not upload {task.group(1)}'s results")
        if f"pattern: {upload.group(1)}*\n" not in check:
            problems += fail(f"{name}: its check does not download its own artifacts ({upload.group(1)}*)")
    return problems


def case_no_downloaded_shard_is_every_shard_missing():
    with tempfile.TemporaryDirectory() as root:
        sources = _sources(root, SHARD_SOURCES)
        found = functional_shards.check(os.path.join(root, "absent"), 2, sources)
    expected = {"shard 1 of 2 left no results", "shard 2 of 2 left no results"}
    return 0 if expected <= set(found) else fail(f"a missing shards directory gave {found}")


GUARD_SELECTION = {"mode": "full", "changed": "base", "guard": []}


def _guard_selection(root, labels):
    """What `prove_guards.py --list-selection` writes for `labels`."""
    path = os.path.join(root, "selection.json")
    with open(path, "w", encoding="utf-8") as handle:
        json.dump({**GUARD_SELECTION, "shard": None, "selected": len(labels),
                   "guards": [{"label": label, "suite": "unit"} for label in labels]}, handle)
    return path


def _guard_shard(root, index, count, guards, selected, header=None):
    """One shard's `--results` file: `guards` maps a label to its outcome, `header` overrides its
    selection fields."""
    path = os.path.join(root, "shards", f"guards-pr-{index}-of-{count}")
    os.makedirs(path, exist_ok=True)
    with open(os.path.join(path, "changed.json"), "w", encoding="utf-8") as handle:
        json.dump({**GUARD_SELECTION, "shard": f"{index}/{count}", "selected": selected,
                   "refused": None, **(header or {}),
                   "guards": [{"label": label, "suite": "unit", "outcome": outcome, "detail": None}
                              for label, outcome in guards.items()]}, handle)


def _guard_check(root, selection, count=2):
    return guard_shards.check(selection, os.path.join(root, "shards"), count, "changed.json")


def case_guard_shards_that_partition_the_selection_and_fire_pass():
    problems = 0
    with tempfile.TemporaryDirectory() as root:
        selection = _guard_selection(root, ["a", "b", "c"])
        _guard_shard(root, 1, 2, {"a": "fired", "c": "fired"}, 3)
        _guard_shard(root, 2, 2, {"b": "fired"}, 3)
        found = _guard_check(root, selection)
        if found:
            problems += fail(f"a complete partition was rejected: {found}")
    # An empty selection is what --changed gives a change no guard can see; every shard ran empty.
    with tempfile.TemporaryDirectory() as root:
        selection = _guard_selection(root, [])
        _guard_shard(root, 1, 2, {}, 0)
        _guard_shard(root, 2, 2, {}, 0)
        found = _guard_check(root, selection)
        if found:
            problems += fail(f"an empty selection that every shard ran was rejected: {found}")
    return problems


def case_every_way_a_selected_guard_can_go_unproved_fails():
    """Each check must be able to fail: green shards that left a guard unproved are the defect."""
    problems = 0
    scenarios = {
        "a guard in no shard": ([{"a": "fired"}, {}], {}, "b ran in no shard"),
        "a guard in two shards": ([{"a": "fired", "b": "fired"}, {"b": "fired"}], {}, "b ran in shards [1, 2]"),
        "a missing shard": ([{"a": "fired", "b": "fired"}, None], {}, "shard 2 of 2 left no result"),
        "a guard that did not fire": ([{"a": "fired"}, {"b": "nothing caught"}], {}, "b did not fire in shard 2: nothing caught"),
        "a guard not in the selection": ([{"a": "fired", "z": "fired"}, {"b": "fired"}], {}, "z ran but is not in the selection"),
        "a shard on another base": ([{"a": "fired"}, {"b": "fired"}], {"changed": "other"}, "selected by changed='other'"),
        "a refused shard": ([{"a": "fired"}, {"b": "not proved"}], {"refused": "anchors moved"}, "shard 2 of 2 refused: anchors moved"),
        "a shard in another mode": ([{"a": "fired"}, {"b": "fired"}], {"mode": "fast"}, "selected by mode='fast'"),
        "a shard with other --guard labels": ([{"a": "fired"}, {"b": "fired"}], {"guard": ["b"]}, "selected by guard=['b']"),
        "a shard that selected another count": ([{"a": "fired"}, {"b": "fired"}], {"selected": 5}, "selected by selected=5"),
        "a result from another shard": ([{"a": "fired"}, {"b": "fired"}], {"shard": "1/2"}, "shard 2 of 2 says it is shard 1/2"),
    }
    for label, (shards, header, expected) in scenarios.items():
        with tempfile.TemporaryDirectory() as root:
            selection = _guard_selection(root, ["a", "b"])
            os.makedirs(os.path.join(root, "shards"))
            for index, guards in enumerate(shards, start=1):
                if guards is not None:
                    _guard_shard(root, index, 2, guards, 2, header if index == 2 else None)
            found = _guard_check(root, selection)
        if not any(expected in problem for problem in found):
            problems += fail(f"{label} passed the check: {found}")
    with tempfile.TemporaryDirectory() as root:
        found = _guard_check(root, _guard_selection(root, ["a"]))
    if not {"shard 1 of 2 left no result", "shard 2 of 2 left no result"} <= set(found):
        problems += fail(f"no downloaded shard at all gave {found}")
    with tempfile.TemporaryDirectory() as root:
        selection = _guard_selection(root, ["a"])
        _guard_shard(root, 1, 4, {"a": "fired"}, 1)
        found = _guard_check(root, selection)
    if not any("guards-pr-1-of-4 is a shard of 4, not of 2" in problem for problem in found):
        problems += fail(f"a shard of another count passed the check: {found}")
    return problems


def case_every_guard_job_runs_all_its_shards_under_its_required_name():
    """Each guard job's shards are 1..N, its check expects N, runs when a shard fails and fails
    then, and reads its own shards' results; the required names sit on the check jobs alone."""
    problems = 0
    with open(os.path.join(WORKFLOWS, "guards.yml"), encoding="utf-8") as handle:
        text = handle.read()
    jobs = re.split(r"\n  (?=[\w-]+:\n)", text)
    for check_name, shard_job in (("fast", "fast-shard"), ("full", "full-shard")):
        job = next((j for j in jobs if j.startswith(f"{shard_job}:\n")), None)
        check = next((j for j in jobs if j.startswith(f"{check_name}:\n")), None)
        if job is None or check is None:
            problems += fail(f"guards.yml has no {shard_job} job and {check_name} check")
            continue
        listed = re.search(r"shard: \[([\d, ]+)\]", job)
        counts = set(re.findall(r"--shard \$\{\{ matrix\.shard \}\}/(\d+)", job))
        if not listed or len(counts) != 1:
            problems += fail(f"{shard_job}: no shard list, or not one shard count: {sorted(counts)}")
            continue
        n = int(counts.pop())
        if [int(x) for x in listed.group(1).split(",")] != list(range(1, n + 1)):
            problems += fail(f"{shard_job}: shards {listed.group(1)} are not 1..{n}")
        checks = re.findall(r"guard_shards\.py check \S+ shards --count (\d+) --file (\S+)", check)
        results = re.findall(r"--results results/(\S+)", job)
        if not checks or any(int(count) != n for count, _file in checks):
            problems += fail(f"{check_name}: its checks do not each expect {n} shards: {checks}")
        if sorted(f for _count, f in checks) != sorted(results):
            problems += fail(f"{check_name}: checks {checks} are not one per results file {results}")
        # Without these a failed shard skips its check, and GitHub counts a skipped required check
        # as passing.
        if f"    needs: [changes, {shard_job}]\n" not in check:
            problems += fail(f"{check_name}: it does not wait for {shard_job}")
        if not re.search(r"\n    if: always\(\)( &&[^\n]*)?\n", check):
            problems += fail(f"{check_name}: it does not run when a shard fails (if: always())")
        if f"if: needs.{shard_job}.result != 'success'" not in check or "exit 1" not in check:
            problems += fail(f"{check_name}: it does not fail when a shard did not succeed")
        upload = re.search(r"name: (guards-\w+-)\$\{\{ matrix\.shard \}\}-of-(\d+)", job)
        if not upload or upload.group(2) != str(n):
            problems += fail(f"{shard_job}: its artifact name does not carry its shard count")
        elif f"pattern: {upload.group(1)}*\n" not in check:
            problems += fail(f"{check_name}: it does not download its own shards ({upload.group(1)}*)")
    for name in ("guards, fast and the change's own", "guards, every one"):
        holders = [j.split(":", 1)[0] for j in jobs if f"    name: {name}\n" in j]
        if len(holders) != 1 or holders[0] not in ("fast", "full"):
            problems += fail(f"the required check `{name}` is on {holders}, not on one check job")
    if "needs: [changes, full-shard, full]" not in text:
        problems += fail("a nightly failure in a shard or the check opens no issue")
    return problems


def case_only_a_docs_change_skips_the_checks():
    """A wrong "docs only" skips every check, which GitHub counts as passing: each way in is tested."""
    problems = 0
    cases = {
        ("README.md",): False,
        ("docs/ci.md", "CHANGELOG.md"): False,
        ("docs/contract.md",): True,
        ("docs/reference.md", "README.md"): True,
        ("SECURITY.md",): True,
        ("README.md", "plugin/build.gradle.kts"): True,
        ("docs/images/chart.svg",): False,
        (".github/workflows/verify.yml",): True,
        (): True,
        ("",): True,
    }
    for paths, expected in cases.items():
        if ci.needs_checks(list(paths)) != expected:
            problems += fail(f"needs_checks({list(paths)}) should be {expected}")
    # A push to main has no `changes` job: its path filter must let the same docs through. GitHub
    # applies the patterns in order, so the last one matching a doc decides.
    with open(os.path.join(WORKFLOWS, "verify.yml"), encoding="utf-8") as handle:
        listed = re.search(r"^  push:\n.*?^    paths: \[(.*?)\]$", handle.read(), re.M | re.S).group(1)
    patterns = [item.strip().strip("'") for item in listed.split(",")]
    for doc in sorted(ci.CHECKED_DOCS):
        # GitHub's leading `**/` also matches no directory at all.
        matching = [pattern for pattern in patterns if any(
            fnmatch.fnmatchcase(doc, glob) for glob in {pattern.lstrip("!"), pattern.lstrip("!").removeprefix("**/")})]
        if not matching or matching[-1].startswith("!"):
            problems += fail(f"verify.yml's push paths skip {doc}, which the checks read")
    return problems


def case_a_change_to_product_sources_runs_every_guard():
    """Full mode is the gate for the plugin's and agent's sources, so an unknown change is one."""
    problems = 0
    cases = {
        ("plugin/src/main/kotlin/A.kt",): True,
        ("README.md", "agent/src/main/java/A.java"): True,
        ("agent/src/test/kotlin/ATest.kt", "bench/run.py"): False,
        ("plugin/build.gradle.kts",): False,
        (): True,
        ("",): True,
    }
    for paths, expected in cases.items():
        if ci.touches_product(list(paths)) != expected:
            problems += fail(f"touches_product({list(paths)}) should be {expected}")
    return problems


def case_the_guard_workflows_are_read_only_and_cover_every_pull_request():
    """A pull request cannot rewrite the harness that judges it, and none goes unguarded."""
    problems = 0
    with open(os.path.join(WORKFLOWS, "guards.yml"), encoding="utf-8") as handle:
        guards = handle.read()
    with open(os.path.join(WORKFLOWS, "guard-table.yml"), encoding="utf-8") as handle:
        table = handle.read()
    for name, text in (("guards.yml", guards), ("guard-table.yml", table)):
        if re.search(r"^\s*pull_request_target:", text, re.M):
            problems += fail(f"{name}: runs a pull request's code with the base branch's token")
        if "permissions:\n  contents: read" not in text:
            problems += fail(f"{name}: its token is not read-only")
        if "secrets." in text:
            problems += fail(f"{name}: uses a secret")
    for needle, why in (
        ("  schedule:\n    - cron: ", "it is not scheduled nightly"),
        ("  workflow_dispatch:", "it cannot be dispatched before a release"),
        ("--mode fast", "it does not run fast mode"),
        ("--changed", "it does not run the guards the change can affect"),
        ("--mode full", "it does not run full mode"),
        ("touches-product", "full mode is not chosen by the product sources"),
        ("needs.changes.outputs.product != 'false'", "full mode runs only when product sources surely changed"),
        ("needs.changes.outputs.product != 'true'", "fast mode is not skipped when full mode runs"),
        ("    timeout-minutes: ", "a job has no time bound"),
    ):
        if needle not in guards:
            problems += fail(f"guards.yml: {why}")
    if not re.search(r"report-failure:\n(    [^\n]*\n)*    if: failure\(\) && github.event_name == 'schedule'", guards):
        problems += fail("guards.yml: a nightly failure opens no issue")
    for needle, why in (
        ("--against", "it does not compare the guard table with the base branch"),
        ("test_prove_guards.py", "it does not check every anchor"),
        ("labeled", "a maintainer's approval does not re-run it"),
        ("weakens-guards", "no label approves a weakened guard"),
        ("""          elif gh api "repos/$REPO/pulls/$PR" --jq '.labels[].name' | grep -qx weakens-guards; then\n"""
         """            echo "::notice::""", "a weakened guard passes without asking for the label when it runs"),
    ):
        if needle not in table:
            problems += fail(f"guard-table.yml: {why}")
    if "github.event.pull_request.labels" in table:
        problems += fail("guard-table.yml: it reads the labels the starting event carried, which a re-run "
                         "after the label changes still sees")
    return problems


def case_every_verify_job_waits_for_the_change_and_runs_unless_told_not_to():
    """On a pull request verify always starts, and each job runs unless `changes` said `false`: a
    trigger path filter would leave required checks waiting, and a job running only on `true` would
    be skipped, as passing, when the detector failed."""
    problems = 0
    with open(os.path.join(WORKFLOWS, "verify.yml"), encoding="utf-8") as handle:
        text = handle.read()
    trigger = text.split("\njobs:\n", 1)[0]
    if re.search(r"\n  pull_request:\n(    [^\n]*\n)*    paths", trigger):
        problems += fail("verify's pull_request trigger has a path filter: required checks would wait forever")
    # Retargeted to main, a pull request into another branch would bring its skipped checks along.
    if not re.search(r"\n  pull_request:\n    branches: \[main\]\n", trigger):
        problems += fail("verify runs on pull requests into any branch, not only main")
    jobs = re.split(r"\n  (?=[\w-]+:\n)", text.split("\njobs:\n", 1)[1])
    for job in jobs:
        header = re.match(r"\s*([\w-]+):\n", job)
        if not header or header.group(1) == "changes":
            continue
        name = header.group(1)
        needs = re.search(r"\n    needs: ([^\n]+)", job)
        condition = re.search(r"\n    if: ([^\n]+)", job)
        if not needs or "changes" not in needs.group(1):
            problems += fail(f"{name}: does not wait for the changes job")
        if not condition or "needs.changes.outputs.code != 'false'" not in condition.group(1):
            problems += fail(f"{name}: does not run unless the changes job said false")
    return problems


def case_a_code_file_moved_into_docs_still_needs_the_checks():
    """With renames detected, the move is listed under its new name only and passes as docs."""
    import subprocess

    with tempfile.TemporaryDirectory() as root:
        def git(*args):
            subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@e", *args], cwd=root,
                           check=True, capture_output=True)

        git("init", "-q")
        os.makedirs(os.path.join(root, "src"))
        with open(os.path.join(root, "src", "Alpha.java"), "w", encoding="utf-8") as handle:
            handle.write("class Alpha { int twice(int n) { return n * 2; } }\n" * 20)
        git("add", ".")
        git("commit", "-q", "-m", "base")
        os.makedirs(os.path.join(root, "docs"))
        git("mv", "src/Alpha.java", "docs/Alpha.java")
        git("commit", "-q", "-m", "moved")
        paths = ci.changed_paths("HEAD~1", "HEAD", cwd=root)
    if not paths or "src/Alpha.java" not in paths:
        return fail(f"the moved file's old path is not among the changes: {paths}")
    return 0 if ci.needs_checks(paths) else fail("a code file moved into docs/ passed as a docs-only change")


CHANGELOG_PREAMBLE = "# Changelog\n\nEvery release entry states what it does to a coverage map.\n\n"
CHANGELOG_LINKS = ("[Unreleased]: https://example.org/compare/v0.2.0...HEAD\n"
                   "[0.2.0]: https://example.org/compare/v0.1.0...v0.2.0\n")
# The changelog before 0.3.0's entry: Unreleased had no Fixed of its own, so a hunk appended to the
# next Fixed down landed in 0.2.0's.
CHANGELOG_BEFORE_ENTRY = (CHANGELOG_PREAMBLE
                          + "## [Unreleased]\n\n### Added\n\n- A new option. Map: left alone.\n\n"
                          + "### Changed\n\n- A changed default. Map: left alone.\n\n"
                          + "## [0.2.0] - 1999-10-13\n\n### Fixed\n\n- An older fix. Map: left alone.\n\n"
                          + CHANGELOG_LINKS)
MISPLACED = "- A recording build hashes ignored files once. Map: left alone."


def _changelog_problems(before, after, subdirectory=""):
    """The rule's verdict on a change of CHANGELOG.md from `before` to `after`, read through git
    from `subdirectory` of the repository."""
    with tempfile.TemporaryDirectory() as root:
        def git(*args):
            subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@e", *args], cwd=root,
                           check=True, capture_output=True)

        git("init", "-q")
        os.makedirs(os.path.join(root, subdirectory), exist_ok=True)
        for text, message in ((before, "base"), (after, "head")):
            with open(os.path.join(root, "CHANGELOG.md"), "w", encoding="utf-8", newline="") as handle:
                handle.write(text)
            git("add", ".")
            git("commit", "-q", "--allow-empty", "-m", message)
        change = ci.changelog_change("HEAD~1", "HEAD", cwd=os.path.join(root, subdirectory))
    if change is None:
        return None
    return ci.changelog_problems(*change)


def case_a_line_added_under_a_released_version_fails_naming_it():
    """A line that lands under a version already released ships as a change to that version."""
    after = CHANGELOG_BEFORE_ENTRY.replace("- An older fix. Map: left alone.\n",
                                           f"- An older fix. Map: left alone.\n{MISPLACED}\n")
    problems = _changelog_problems(CHANGELOG_BEFORE_ENTRY, after)
    if problems is None:
        return fail("git could not read a two-commit change")
    if len(problems) != 1:
        return fail(f"a line added under 0.2.0 should be one problem, got {problems}")
    missing = [part for part in (MISPLACED, "[0.2.0]", "## [Unreleased]", ":20:") if part not in problems[0]]
    return fail(f"the problem does not name {missing}: {problems[0]}") if missing else 0


def case_a_release_entry_passes():
    """A minor's entry renames Unreleased to its version, adds lines to it and repairs older ones."""
    after = (CHANGELOG_PREAMBLE
             + "## [Unreleased]\n\n## [0.3.0] - 1999-10-22\n\n**Your map:** left alone.\n\n"
             + "### Added\n\n- A new option. Map: left alone.\n\n"
             + "### Changed\n\n- A changed default. Map: left alone.\n- Benchmarks: a new figure.\n\n"
             + "## [0.2.0] - 1999-10-13\n\n### Fixed\n\n\n"
             + CHANGELOG_LINKS + "[0.3.0]: https://example.org/compare/v0.2.0...v0.3.0\n")
    before = CHANGELOG_BEFORE_ENTRY.replace("- An older fix. Map: left alone.\n", f"{MISPLACED}\n")
    problems = _changelog_problems(before, after)
    return fail(f"a release entry was refused: {problems}") if problems else 0


def case_a_fixed_section_added_under_unreleased_passes():
    after = CHANGELOG_BEFORE_ENTRY.replace("## [0.2.0]", f"### Fixed\n\n{MISPLACED}\n\n## [0.2.0]")
    problems = _changelog_problems(CHANGELOG_BEFORE_ENTRY, after)
    return fail(f"a line under Unreleased was refused: {problems}") if problems else 0


def case_a_patch_section_passes():
    """A patch adds its own section below Unreleased and takes its fixes out of Unreleased."""
    after = CHANGELOG_BEFORE_ENTRY.replace(
        "### Changed\n\n- A changed default. Map: left alone.\n\n",
        "## [0.2.1] - 1999-10-20\n\n### Fixed\n\n- A changed default. Map: left alone.\n\n")
    problems = _changelog_problems(CHANGELOG_BEFORE_ENTRY, after)
    return fail(f"a patch section was refused: {problems}") if problems else 0


def case_a_redated_release_fails():
    after = CHANGELOG_BEFORE_ENTRY.replace("## [0.2.0] - 1999-10-13", "## [0.2.0] - 1999-10-14")
    problems = _changelog_problems(CHANGELOG_BEFORE_ENTRY, after)
    if problems is None or len(problems) != 1 or "[0.2.0]" not in problems[0]:
        return fail(f"a redated release heading should be one problem naming 0.2.0, got {problems}")
    return 0


def case_a_release_renamed_to_a_new_version_fails():
    """The renamed heading opens a section of its own, so only the lost version can name it."""
    after = CHANGELOG_BEFORE_ENTRY.replace("## [0.2.0] - 1999-10-13", "## [0.2.1] - 1999-10-13")
    problems = _changelog_problems(CHANGELOG_BEFORE_ENTRY, after)
    if problems is None or len(problems) != 1 or "[0.2.0]" not in problems[0]:
        return fail(f"a released heading renamed to a new version should be one problem naming 0.2.0, "
                    f"got {problems}")
    return 0


def case_a_line_under_a_released_version_fails_whatever_breaks_lines_above_it():
    """Git breaks lines at a newline only; a separator or carriage return above must not shift
    which line the check reads."""
    problems = 0
    for separator in (" ", "\x0c", "\r"):
        before = CHANGELOG_BEFORE_ENTRY.replace("- An older fix.", f"- An{separator}older fix.")
        after = before.replace(f"- An{separator}older fix. Map: left alone.\n",
                               f"- An{separator}older fix. Map: left alone.\n{MISPLACED}\n")
        found = _changelog_problems(before, after)
        if found is None or len(found) != 1 or MISPLACED not in found[0]:
            problems += fail(f"a line under 0.2.0 below a {separator!r} should be one problem, got {found}")
    return problems


def case_the_changelog_check_reads_the_root_changelog_from_a_subdirectory():
    after = CHANGELOG_BEFORE_ENTRY.replace("- An older fix. Map: left alone.\n",
                                           f"- An older fix. Map: left alone.\n{MISPLACED}\n")
    problems = _changelog_problems(CHANGELOG_BEFORE_ENTRY, after, subdirectory=".github")
    if problems is None or len(problems) != 1:
        return fail(f"run from .github/, a line under 0.2.0 should be one problem, got {problems}")
    return 0


def case_preamble_link_and_blank_lines_pass():
    after = (CHANGELOG_BEFORE_ENTRY.replace("Every release entry", "Every release entry, patches too,")
             .replace("- An older fix. Map: left alone.\n", "- An older fix. Map: left alone.\n\n")
             + "[0.1.0]: https://example.org/releases/tag/v0.1.0\n")
    problems = _changelog_problems(CHANGELOG_BEFORE_ENTRY, after)
    return fail(f"a preamble edit, a link or a blank line was refused: {problems}") if problems else 0


def case_a_changelog_git_cannot_read_fails_the_check():
    """Silence would pass the check; a revision git cannot resolve must fail it, saying so."""
    import contextlib
    import io

    with tempfile.TemporaryDirectory() as root:
        subprocess.run(["git", "init", "-q"], cwd=root, check=True, capture_output=True)
        out = io.StringIO()
        cwd = os.getcwd()
        os.chdir(root)
        try:
            with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
                code = ci.main(["changelog", "HEAD~1", "HEAD"])
        finally:
            os.chdir(cwd)
    if code == 0:
        return fail("an unreadable change passed the changelog check")
    return 0 if "git" in out.getvalue() else fail(f"the failure does not say git failed: {out.getvalue()!r}")


def changelog_workflow_problems(text):
    """What would let a misplaced changelog line merge unchecked, or let the check do harm."""
    problems = []
    pull_request = re.search(r"^  pull_request:\n((?:    [^\n]*\n)*)", text, re.M)
    if not pull_request or "branches: [main]" not in pull_request.group(1):
        problems.append("it does not run on pull requests into main")
    elif "paths" in pull_request.group(1):
        problems.append("a path filter leaves the required check waiting on pull requests it skips")
    if not re.search(r"^  push:\n    branches: \[main\]\n", text, re.M):
        problems.append("it does not judge what lands on main")
    if "permissions:\n  contents: read" not in text:
        problems.append("its token is not read-only")
    if "secrets." in text:
        problems.append("it uses a secret")
    if "fetch-depth: 0" not in text:
        problems.append("a shallow checkout has no merge base to compare with")
    if "    timeout-minutes: " not in text:
        problems.append("the job has no time bound")
    if "ci.py changelog" not in text:
        problems.append("no step runs the changelog check")
    return problems


def case_the_changelog_check_runs_on_every_pull_request_and_push_to_main():
    with open(os.path.join(WORKFLOWS, "changelog.yml"), encoding="utf-8") as handle:
        text = handle.read()
    return sum(fail(f"changelog.yml: {problem}") for problem in changelog_workflow_problems(text))


def case_a_changelog_workflow_that_skips_or_stops_checking_is_reported():
    with open(os.path.join(WORKFLOWS, "changelog.yml"), encoding="utf-8") as handle:
        text = handle.read()
    problems = 0
    for edited, expected in (
        (text.replace("    branches: [main]\n  push:", "    branches: [main]\n    paths: ['CHANGELOG.md']\n  push:"),
         "a path filter"),
        (text.replace("ci.py changelog", "ci.py needs-checks"), "no step runs the changelog check"),
    ):
        if edited == text:
            problems += fail(f"the fixture edit for {expected!r} no longer applies to changelog.yml")
        elif not any(expected in problem for problem in changelog_workflow_problems(edited)):
            problems += fail(f"an edited changelog.yml was not reported for {expected!r}")
    return problems


FIXTURES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fixtures")


def _fixture(name):
    with open(os.path.join(FIXTURES, name), encoding="utf-8") as handle:
        return handle.read()


def _block(text):
    """A build's failure output around one "What went wrong" text."""
    return f"FAILURE: Build failed with an exception.\n\n* What went wrong:\n{text}\n\n* Try:\n> Run with --stacktrace.\n"


def case_a_dropped_or_rate_limited_download_is_a_download_failure():
    """The two failures that each cost a release check a long job: a TLS handshake the server ended,
    which no Gradle setting retries, and a rate limit that outlasted Gradle's own retries."""
    problems = 0
    for name in ("download-failure-handshake.txt", "download-failure-429.txt"):
        if not ci.download_failure(_fixture(name)):
            problems += fail(f"{name} was not read as a download failure")
    return problems


def case_a_failure_that_is_not_a_download_is_not_rerun():
    problems = 0
    for what, text in (
        ("a missing artifact", "Execution failed for task ':plugin:functionalTest'.\n> Could not resolve all files "
                               "for configuration ':plugin:testRuntimeClasspath'.\n   > Could not find "
                               "org.junit:junit-bom:5.11.4."),
        ("a 401", "Could not resolve all files for configuration ':compileClasspath'.\n> Could not GET "
                  "'https://repo.example.org/a.pom'. Received status code 401 from server: Unauthorized"),
        ("failing tests", "Execution failed for task ':plugin:test'.\n> There were failing tests. See the report."),
        ("a compile error", "Execution failed for task ':plugin:compileKotlin'.\n> Compilation error. See log."),
    ):
        if ci.download_failure(_block(text)):
            problems += fail(f"{what} was read as a download failure")
    if ci.download_failure("BUILD SUCCESSFUL in 3s\n"):
        problems += fail("a build with no failure block was read as a download failure")
    return problems


def _scripted(root, outcomes):
    """A command that prints and exits as `outcomes` says, one entry per run, counting its runs."""
    script = os.path.join(root, "build.py")
    with open(script, "w", encoding="utf-8") as handle:
        handle.write("import json, os, sys\n"
                     f"count = os.path.join({root!r}, 'runs')\n"
                     "runs = int(open(count).read()) if os.path.exists(count) else 0\n"
                     "open(count, 'w').write(str(runs + 1))\n"
                     f"text, code = json.load(open({os.path.join(root, 'outcomes.json')!r}))[runs]\n"
                     "sys.stdout.write(text)\nsys.exit(code)\n")
    with open(os.path.join(root, "outcomes.json"), "w", encoding="utf-8") as handle:
        json.dump(outcomes, handle)
    return [sys.executable, script]


def _retried(outcomes):
    """(exit code, runs, pauses, output) of the rerun subcommand over a scripted command."""
    import contextlib
    import io

    with tempfile.TemporaryDirectory() as root:
        pauses = []
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = ci.rerun_on_download_failure(_scripted(root, outcomes), pause=0, sleep=pauses.append)
        with open(os.path.join(root, "runs"), encoding="utf-8") as handle:
            runs = int(handle.read())
    return code, runs, pauses, out.getvalue()


def case_a_build_that_failed_on_a_download_runs_once_more():
    handshake = _fixture("download-failure-handshake.txt")
    problems = 0
    code, runs, pauses, out = _retried([[handshake, 1], ["BUILD SUCCESSFUL\n", 0]])
    if (code, runs, pauses) != (0, 2, [0]):
        problems += fail(f"a download failure then a pass should exit 0 after 2 runs and one pause, "
                         f"got exit {code}, {runs} runs, pauses {pauses}")
    if "Remote host terminated the handshake" not in out or "Could not GET" not in out:
        problems += fail("the first run's output, or the line naming why it reran, is missing")
    code, runs, _, _ = _retried([[handshake, 1], [handshake, 1]])
    if code == 0 or runs != 2:
        problems += fail(f"two download failures should fail after 2 runs, got exit {code}, {runs} runs")
    code, runs, pauses, _ = _retried([[_block("Compilation error. See log."), 3]])
    if (code, runs, pauses) != (3, 1, []):
        problems += fail(f"a compile error should exit 3 after one run and no pause, got exit {code}, "
                         f"{runs} runs, pauses {pauses}")
    return problems


def case_output_the_console_cannot_encode_passes_through_untouched():
    """A Windows runner's console is cp1252: a character Gradle wrote in another encoding, decoded
    and re-encoded on the way through, raised there and lost the build's exit code."""
    import io

    raw = b"caf\xe9 \xff\x81 done\n"
    console = io.TextIOWrapper(io.BytesIO(), encoding="cp1252", errors="strict")
    original = sys.stdout
    sys.stdout = console
    error = None
    try:
        code, _ = ci.run_teed([sys.executable, "-c", f"import sys; sys.stdout.buffer.write({raw!r})"])
        console.flush()
        passed = console.buffer.getvalue()
    except UnicodeError as raised:
        error = raised
    finally:
        sys.stdout = original
    if error is not None:
        return fail(f"teeing bytes the console cannot encode raised {error!r}")
    if code != 0 or passed != raw:
        return fail(f"the output did not pass through as written: exit {code}, {passed!r}")
    return 0


def case_on_windows_the_wrapper_script_runs_as_its_batch_file():
    """CreateProcess cannot start the shell script."""
    command = ci.runnable(["./gradlew", "build", "--console=plain"], windows=True)
    if command != [os.path.abspath("gradlew.bat"), "build", "--console=plain"]:
        return fail(f"./gradlew on Windows ran as {command}")
    if ci.runnable(["./gradlew", "build"], windows=False) != ["./gradlew", "build"]:
        return fail("./gradlew elsewhere was rewritten")
    return 0


def case_the_matrix_cell_builds_through_the_download_rerun():
    with open(os.path.join(WORKFLOWS, "matrix.yml"), encoding="utf-8") as handle:
        text = handle.read()
    if not re.search(r"\.github/ci\.py rerun-on-download-failure -- \./gradlew build", text):
        return fail("matrix.yml: the cell's build does not run through the download rerun")
    return 0


FUNCTIONAL_SHARD_JOBS = ("functional-shard", "functional-gradle9-shard", "functional-windows-shard",
                         "functional-gradle9-windows-shard")


def _job_block(text, job):
    """A job's lines in a workflow, from its `  <job>:` line to the next job's."""
    found = re.search(rf"^  {re.escape(job)}:\n((?:(?:    .*)?\n)*)", text, re.M)
    return found.group(1) if found else None


def functional_evidence_problems(text):
    """Each functional shard job that would leave nothing behind when a flake fails it."""
    problems = []
    for job in FUNCTIONAL_SHARD_JOBS:
        block = _job_block(text, job)
        if block is None:
            problems.append(f"{job}: the job is gone")
            continue
        uploads = [step for step in block.split("\n      - ") if "upload-artifact@" in step]
        evidence = [step for step in uploads if ".gradle-test-kit-" in step]
        listing = [step for step in block.split("\n      - ") if "ci.py list-test-kit-homes" in step]
        if not listing or "if: failure()" not in listing[0]:
            problems.append(f"{job}: no step lists what the TestKit homes' caches hold when it fails")
        if not evidence:
            problems.append(f"{job}: a flake leaves nothing to diagnose: no upload of the TestKit homes")
        elif not all("if: failure()" in step and "include-hidden-files: true" in step for step in evidence):
            problems.append(f"{job}: the TestKit homes' upload is not on failure, or skips the hidden "
                            f"`.gradle-test-kit-*` directories")
        elif not any("plugin/build/test-kit-homes.txt" in step for step in evidence):
            problems.append(f"{job}: the upload leaves out the listing of what the homes downloaded")
    return problems


def case_every_functional_shard_keeps_its_evidence_when_it_fails():
    with open(os.path.join(WORKFLOWS, "verify.yml"), encoding="utf-8") as handle:
        text = handle.read()
    return sum(fail(f"verify.yml: {problem}") for problem in functional_evidence_problems(text))


def case_a_shard_that_drops_its_evidence_is_reported():
    with open(os.path.join(WORKFLOWS, "verify.yml"), encoding="utf-8") as handle:
        text = handle.read()
    problems = 0
    block = _job_block(text, "functional-gradle9-windows-shard") or ""
    for edited, expected in (
        (text.replace(block, block.replace("include-hidden-files: true", "include-hidden-files: false")),
         "skips the hidden"),
        (text.replace(block, block.replace(".gradle-test-kit-", ".gradle-elsewhere-")),
         "leaves nothing to diagnose"),
        (text.replace(block, block.replace("        if: failure()\n        shell: bash\n        run: python .github/ci.py list",
                                           "        shell: bash\n        run: python .github/ci.py list", 1)),
         "when it fails"),
        (text.replace(block, block.replace("            plugin/build/test-kit-homes.txt\n", "", 1)),
         "leaves out the listing"),
    ):
        found = [p for p in functional_evidence_problems(edited) if p.startswith("functional-gradle9-windows-shard")]
        if edited == text:
            problems += fail(f"the fixture edit for {expected!r} no longer applies to verify.yml")
        elif not any(expected in problem for problem in found):
            problems += fail(f"an edited verify.yml was not reported for {expected!r}: {found}")
    return problems


def case_the_test_kit_listing_names_each_cached_file_and_its_size():
    """What separates a jar that never arrived from one that arrived and could not be read."""
    import contextlib
    import io
    with tempfile.TemporaryDirectory() as root:
        home = os.path.join(root, "functionalTestGradle9", "work", ".gradle-test-kit-9.8.0")
        files = os.path.join(home, "caches", "modules-2", "files-2.1", "org.junit", "junit-bom", "5.11.4", "ab12")
        os.makedirs(files)
        with open(os.path.join(files, "junit-bom-5.11.4.pom"), "w", encoding="utf-8") as handle:
            handle.write("x" * 7)
        open(os.path.join(files, "empty.jar"), "w").close()
        if hasattr(os, "symlink") and sys.platform != "win32":
            os.symlink(os.path.join(files, "gone"), os.path.join(files, "dangling.jar"))
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = ci.main(["list-test-kit-homes", root])
    listing = out.getvalue()
    problems = 0
    if code != 0:
        problems += fail(f"listing a home exited {code}")
    for needle in (".gradle-test-kit-9.8.0", "junit-bom-5.11.4.pom\t7\t", "empty.jar\t0\t", "dangling.jar\tERROR"):
        if needle not in listing:
            problems += fail(f"the listing does not show {needle!r}: {listing!r}")
    return problems


def case_no_test_kit_home_is_said_rather_than_silent():
    import contextlib
    import io
    with tempfile.TemporaryDirectory() as root:
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = ci.main(["list-test-kit-homes", root])
    if code != 0 or "no TestKit home" not in out.getvalue():
        return fail(f"an empty build directory was not reported as having no home: {code}, {out.getvalue()!r}")
    return 0


REPOSITORY = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BADGE = os.path.join(".github", "badges", "coverage.json")
GATED_MODULES = ("plugin", "agent")


def gate_minimums(root):
    """Each module's LINE minimum, as the whole percent its build file's gate enforces."""
    minimums = {}
    for module in GATED_MODULES:
        with open(os.path.join(root, module, "build.gradle.kts"), encoding="utf-8") as handle:
            found = re.findall(r'minimum = "0\.(\d\d)"\.toBigDecimal\(\)', handle.read())
        if len(found) != 1:
            raise ValueError(f"{module}/build.gradle.kts: expected one gate minimum, found {found}")
        minimums[module] = int(found[0])
    return minimums


def badge_problems(root):
    """The README's coverage badge claims the gates' minimums: what every commit on main meets."""
    import json

    minimums = gate_minimums(root)
    with open(os.path.join(root, BADGE), encoding="utf-8") as handle:
        badge = json.load(handle)
    expected = " · ".join(f"{module} ≥ {minimums[module]}%" for module in GATED_MODULES)
    if badge.get("message") != expected:
        return fail(f"{BADGE} says {badge.get('message')!r}, the build files' gates say {expected!r}")
    return 0


def case_the_coverage_badge_states_the_gates_minimums():
    """A ratchet that moves updates the badge in the same change, or this fails."""
    return badge_problems(REPOSITORY)


def case_a_badge_one_point_off_either_gate_is_reported():
    import io
    import json
    from contextlib import redirect_stdout

    problems = 0
    minimums = gate_minimums(REPOSITORY)
    for module in GATED_MODULES:
        off = dict(minimums, **{module: minimums[module] - 1})
        with tempfile.TemporaryDirectory() as root:
            for gated in GATED_MODULES:
                os.makedirs(os.path.join(root, gated))
                with open(os.path.join(root, gated, "build.gradle.kts"), "w", encoding="utf-8") as handle:
                    handle.write(f'minimum = "0.{minimums[gated]}".toBigDecimal()\n')
            os.makedirs(os.path.dirname(os.path.join(root, BADGE)))
            message = " · ".join(f"{gated} ≥ {off[gated]}%" for gated in GATED_MODULES)
            with open(os.path.join(root, BADGE), "w", encoding="utf-8") as handle:
                json.dump({"schemaVersion": 1, "message": message}, handle)
            printed = io.StringIO()
            with redirect_stdout(printed):
                reported = badge_problems(root)
        said = printed.getvalue()
        if reported != 1 or f"{module} ≥ {off[module]}%" not in said or f"{module} ≥ {minimums[module]}%" not in said:
            problems += fail(f"a badge off by one on {module} was not reported with both values: {said!r}")
    return problems



RELEASE = os.path.join(WORKFLOWS, "release.yml")
JOB = re.compile(r"^  ([a-z][a-z-]*):$", re.MULTILINE)


def release_jobs(text):
    """Each job of a workflow's text, by name: the lines from its key to the next job's."""
    body = text.split("\njobs:\n", 1)[1]
    starts = list(JOB.finditer(body))
    return {m.group(1): body[m.start():(starts[i + 1].start() if i + 1 < len(starts) else len(body))]
            for i, m in enumerate(starts)}


def gradle_tasks(job):
    """The tasks each `./gradlew` call in a job's text asks for, one list per call; `-x` exclusions
    and options left out. A call ends where its `run:` value does."""
    calls = []
    for match in re.finditer(r"\./gradlew\b", job):
        rest = job[match.end():]
        end = re.search(r"\n\s*(?:- |#|[a-z-]+:\s|$)", rest, re.MULTILINE)
        tokens = (rest[:end.start()] if end else rest).split()
        tasks, skip = [], False
        for token in tokens:
            if token in ("&&", "||", ";", "|", ">", ">>"):
                break
            if skip:
                skip = False
            elif token == "-x":
                skip = True
            elif not token.startswith("-"):
                tasks.append(token)
        calls.append(tasks)
    return calls


def release_problems(text):
    """What makes a release ship bytes other than the tested ones, or hand a key or an attesting
    token to a runner that does not need it."""
    found = []
    header = text.split("\njobs:\n", 1)[0]
    if re.search(r"id-token:|attestations:|contents:\s*write|write-all|secrets\.", header):
        found.append("the workflow-level permissions grant more than `contents: read`, which every job inherits")
    # A tag push releases and a dispatch rehearses; any other trigger would run this file's attesting
    # job on code no maintainer chose, a pull request's above all.
    triggers = re.search(r"^on:\n((?:  [^\n]*\n|\n)*)", text, re.MULTILINE)
    named = re.findall(r"^  ([a-z_]+):", triggers.group(1), re.MULTILINE) if triggers else []
    if sorted(named) != ["push", "workflow_dispatch"]:
        found.append(f"it runs on {named}; only a `v*` tag push and a dispatch may start it "
                     "(never pull_request, pull_request_target, workflow_call or schedule)")
    elif not re.search(r"^  push:\n    tags: \['v\*'\]\n(?!    )", triggers.group(1), re.MULTILINE):
        found.append("a push other than of a `v*` tag starts it")
    jobs = release_jobs(text)
    for name in ("verify", "attest", "publish", "publish-maven", "github-release"):
        if name not in jobs:
            found.append(f"no `{name}` job")
    if found:
        return found
    allowed = {
        "verify": [["build", ":maven:functionalTest"]],
        "publish": [[":plugin:signReleaseRepository"], [":plugin:publishReleaseRepository"]],
        "attest": [],
        "publish-maven": [],
        "github-release": [],
    }
    # Each key on the one job that uses it: Central's token never beside the signing key.
    keys = {"publish": {"SIGNING_KEY", "SIGNING_PASSWORD", "GRADLE_PUBLISH_KEY", "GRADLE_PUBLISH_SECRET"},
            "publish-maven": {"CENTRAL_USERNAME", "CENTRAL_PASSWORD"}}
    for name, job in jobs.items():
        if gradle_tasks(job) != allowed.get(name, []):
            found.append(f"`{name}` runs Gradle tasks {gradle_tasks(job)}; expected {allowed.get(name, [])}")
        read = set(re.findall(r"secrets\.(?!GITHUB_TOKEN)(\w+)", job))
        if read and (name not in keys or not read <= keys[name]):
            found.append(f"`{name}` reads {sorted(read)}; only `publish` holds keys, and `publish-maven` "
                         "only Central's token")
        if name != "attest" and re.search(r"id-token:\s*write|attestations:\s*write", job):
            found.append(f"`{name}` can attest; only `attest` may")
        if name != "github-release" and re.search(r"contents:\s*write", job):
            found.append(f"`{name}` can write the repository; only `github-release` may")
        if name not in ("verify", "publish") and "setup-gradle" in job:
            found.append(f"`{name}` sets up Gradle, and with it a cache; it runs no build")
    for name, needs in (("attest", "verify"), ("publish", "attest"), ("publish-maven", "publish"),
                        ("github-release", "publish"), ("github-release", "publish-maven")):
        line = re.search(r"^    needs: (.*)$", jobs[name], re.MULTILINE)
        if not line or needs not in re.findall(r"[\w-]+", line.group(1)):
            found.append(f"`{name}` does not wait for `{needs}`")
    for name in ("publish", "publish-maven", "github-release"):
        if not re.search(r"^    if: github\.event_name == 'push'$", jobs[name], re.MULTILINE):
            found.append(f"`{name}` is not limited to a tag push, so a rehearsal can reach it")
    verify = jobs["verify"]
    if not re.search(r"- name: a rehearsal runs on a release line's candidate\n"
                     r"        if: github\.event_name == 'workflow_dispatch'\n", verify):
        found.append("`verify`'s check of a dispatch's branch and candidate does not run on a dispatch")
    check = verify.find("main|release/*) ;;")
    if check < 0 or check > verify.find("./gradlew build"):
        found.append("`verify` builds a dispatch without first refusing one off `main` or `release/*`")
    if '[ "$CANDIDATE" = "$GITHUB_SHA" ]' not in verify:
        found.append("`verify` does not check a dispatch runs the candidate it names")
    publish = jobs["publish"]
    if "environment: release" not in publish:
        found.append("`publish` is not bound to the `release` environment")
    if "cache-disabled: true" not in publish:
        found.append("`publish` restores a Gradle cache beside the keys")
    if publish.count('-Pyoriwake.repository="$GITHUB_WORKSPACE/release/repository"') != 2:
        found.append("`publish` does not sign and upload the downloaded repository")
    for needed in ("-Pyoriwake.sign", "ORG_GRADLE_PROJECT_signingKey", "sha256sum -c SHA256SUMS"):
        if needed not in publish:
            found.append(f"`publish` lacks `{needed}`")
    if publish.find("name: release-signatures") > publish.find(":plugin:publishReleaseRepository"):
        found.append("`publish` saves the signatures only after the upload that cannot be taken back")
    central = jobs["publish-maven"]
    if "environment: release" not in central:
        found.append("`publish-maven` is not bound to the `release` environment")
    for needed in ("sha256sum -c SHA256SUMS", "name: release-signatures", "central_bundle.py", "--signed"):
        if needed not in central:
            found.append(f"`publish-maven` lacks `{needed}`, so it could upload files nobody signed or checked")
    if not re.search(r"      - name: the Maven Central bundle is complete\n        run: >-\n"
                     r"          python3 scripts/central_bundle\.py release/repository ", jobs["verify"]):
        found.append("`verify` does not check the Maven Central bundle, so a tag finds a refusal first")
    if "sha256sum -c SHA256SUMS" not in jobs["attest"] or "subject-checksums: release/SHA256SUMS" not in jobs["attest"]:
        found.append("`attest` does not check and attest the files `verify` listed")
    if "CHANGELOG.md" not in jobs["verify"]:
        found.append("`verify` does not check the CHANGELOG section the release notes need")
    if "contents: write" not in jobs["github-release"]:
        found.append("`github-release` cannot create the release")
    return found


def case_the_release_ships_the_tested_bytes_and_keeps_each_key_on_its_job():
    problems = 0
    with open(RELEASE, encoding="utf-8") as handle:
        for problem in release_problems(handle.read()):
            problems += fail(f"release.yml: {problem}")
    return problems


def case_a_release_that_rebuilds_or_spreads_a_key_is_reported():
    problems = 0
    with open(RELEASE, encoding="utf-8") as handle:
        text = handle.read()
    mutants = {
        "publish rebuilds": text.replace(
            "./gradlew :plugin:publishReleaseRepository", "./gradlew :plugin:shadowJar :plugin:publishReleaseRepository"),
        "verify holds a key": text.replace(
            "      - name: build and test\n",
            "      - name: build and test\n        env:\n          K: ${{ secrets.SIGNING_KEY }}\n", 1),
        "publish can attest": text.replace(
            "    environment: release\n",
            "    environment: release\n    permissions:\n      id-token: write\n", 1),
        "every job can attest": text.replace(
            "permissions:\n  contents: read\n", "permissions:\n  contents: read\n  id-token: write\n", 1),
        "publish unsigned": text.replace("-Pyoriwake.sign\n", "\n").replace("-Pyoriwake.sign ", ""),
        "publish uploads its own build": (lambda at: text[:at] + text[at:].replace(
            "$GITHUB_WORKSPACE/release/repository", "$GITHUB_WORKSPACE/plugin/build/local-repository", 1))(
            text.rfind('-Pyoriwake.repository="')),
        "publish skips the attestation": text.replace("    needs: attest\n", "    needs: verify\n", 1),
        "publish restores a cache": text.replace("cache-disabled: true", "cache-read-only: true", 1),
        "a pull request starts it": text.replace("on:\n", "on:\n  pull_request:\n", 1),
        "a fork's pull request starts it": text.replace("on:\n", "on:\n  pull_request_target:\n", 1),
        "a branch push starts it": text.replace("    tags: ['v*']\n", "    branches: [main]\n", 1),
        "a branch push beside the tag starts it": text.replace(
            "    tags: ['v*']\n", "    tags: ['v*']\n    branches: [main]\n", 1),
        "a rehearsal skips its own check": text.replace(
            "        if: github.event_name == 'workflow_dispatch'\n", "        if: false\n", 1),
        "a rehearsal publishes": text.replace(
            "    needs: attest\n    if: github.event_name == 'push'\n", "    needs: attest\n", 1),
        "a rehearsal on a tag ref publishes": text.replace(
            "    needs: attest\n    if: github.event_name == 'push'\n",
            "    needs: attest\n    if: startsWith(github.ref, 'refs/tags/v')\n", 1),
        "a rehearsal uploads to Central": text.replace(
            "    needs: publish\n    if: github.event_name == 'push'\n", "    needs: publish\n", 1),
        "a rehearsal makes a GitHub release": text.replace(
            "    needs: [publish, publish-maven]\n    if: github.event_name == 'push'\n",
            "    needs: [publish, publish-maven]\n", 1),
        "the GitHub release skips Central": text.replace(
            "    needs: [publish, publish-maven]\n", "    needs: publish\n", 1),
        "Central's job holds the signing key": text.replace(
            "          CENTRAL_PASSWORD: ${{ secrets.CENTRAL_PASSWORD }}\n",
            "          CENTRAL_PASSWORD: ${{ secrets.CENTRAL_PASSWORD }}\n          K: ${{ secrets.SIGNING_KEY }}\n", 1),
        "the Portal's job holds Central's token": text.replace(
            "          GRADLE_PUBLISH_KEY: ${{ secrets.GRADLE_PUBLISH_KEY }}\n",
            "          GRADLE_PUBLISH_KEY: ${{ secrets.GRADLE_PUBLISH_KEY }}\n          C: ${{ secrets.CENTRAL_PASSWORD }}\n", 1),
        "the rehearsal's bundle check is switched off": text.replace(
            "      - name: the Maven Central bundle is complete\n",
            "      - name: the Maven Central bundle is complete\n        if: false\n", 1),
        "Central gets an unsigned bundle": text.replace(" central-bundle.zip --signed", " central-bundle.zip", 1),
        "the rehearsal skips the Central bundle": text.replace(
            "      - name: the Maven Central bundle is complete\n", "      - name: nothing\n        if: false\n", 1).replace(
            "python3 scripts/central_bundle.py release/repository io.github.zeuspizza yoriwake-maven-plugin\n          \"$VERSION\"",
            "true\n          \"$VERSION\"", 1),
        "the release skips the Maven suite": text.replace(" :maven:functionalTest -PreleaseVersion", " -PreleaseVersion", 1),
        "a rehearsal runs anywhere": text.replace("            main|release/*) ;;\n", "", 1),
        "a rehearsal runs any commit": text.replace('[ "$CANDIDATE" = "$GITHUB_SHA" ] ||', "true ||", 1),
    }
    for what, mutant in mutants.items():
        if mutant == text:
            problems += fail(f"the mutant `{what}` changed nothing; its anchor moved")
        elif not release_problems(mutant):
            problems += fail(f"`{what}` passed the release check")
    return problems


def verify_step(text, name):
    """The script of the `verify` step with this name, or None."""
    step = re.search(rf"- name: {re.escape(name)}\n(?:        (?!run:).*\n)*        run: \|\n((?:          .*\n)+)",
                     release_jobs(text)["verify"])
    return "\n".join(line[10:] for line in step.group(1).splitlines()) + "\n" if step else None


def _run_step(script, cwd=None, **env):
    return subprocess.run(["bash", "-eu", "-c", script], cwd=cwd, capture_output=True, text=True,
                          env={**os.environ, **env}).returncode


def case_a_tag_releases_its_version_and_a_dispatch_rehearses():
    """The tag branch runs only on a release, so a pull request is where it can still be seen."""
    with open(RELEASE, encoding="utf-8") as handle:
        script = verify_step(handle.read(), "the version and its CHANGELOG section")
    if script is None:
        return fail("release.yml: `verify` has no step naming the version and its CHANGELOG section")
    problems = 0
    for event, ref, version, section in (("push", "v9.8.7", "9.8.7", "## [9.8.7]"),
                                         ("workflow_dispatch", "main", "0.0.0-rehearsal", "## [Unreleased]")):
        with tempfile.TemporaryDirectory() as tmp:
            output, env = os.path.join(tmp, "output"), os.path.join(tmp, "env")
            if _run_step(script, GITHUB_EVENT_NAME=event, GITHUB_REF_NAME=ref, GITHUB_OUTPUT=output, GITHUB_ENV=env):
                problems += fail(f"the version step failed on a {event} of {ref}")
                continue
            with open(output, encoding="utf-8") as handle:
                got_output = handle.read()
            with open(env, encoding="utf-8") as handle:
                got_env = handle.read()
        if got_output != f"version={version}\n" or got_env != f"VERSION={version}\nSECTION={section}\n":
            problems += fail(f"a {event} of {ref} named {got_output!r} {got_env!r}; expected {version} and {section!r}")
    return problems


def case_a_rehearsal_runs_only_on_a_release_lines_candidate():
    with open(RELEASE, encoding="utf-8") as handle:
        script = verify_step(handle.read(), "a rehearsal runs on a release line's candidate")
    if script is None:
        return fail("release.yml: `verify` has no step checking a dispatch's branch and candidate")
    head = "a" * 40
    problems = 0
    for ref, candidate, accepted in (("main", head, True), ("release/0.4", head, True), ("feature/x", head, False),
                                     ("v0.3.0", head, False), ("main", "b" * 40, False), ("main", "", False)):
        if (_run_step(script, GITHUB_REF_NAME=ref, GITHUB_SHA=head, CANDIDATE=candidate) == 0) != accepted:
            problems += fail(f"a dispatch from {ref} naming {candidate or 'nothing'!r} was "
                             f"{'refused' if accepted else 'let through'}")
    return problems


def case_the_changelog_check_finds_exactly_the_section_it_is_given():
    with open(RELEASE, encoding="utf-8") as handle:
        script = verify_step(handle.read(), "the CHANGELOG has this version's section")
    if script is None:
        return fail("release.yml: `verify` has no step checking the CHANGELOG section")
    problems = 0
    with tempfile.TemporaryDirectory() as tmp:
        with open(os.path.join(tmp, "CHANGELOG.md"), "w", encoding="utf-8") as handle:
            handle.write("# Changelog\n\n## [Unreleased]\n\n- moved from ## [9.8.7]\n\n## [9.8.10] - 1999-01-01\n")
        for section, found in (("## [Unreleased]", True), ("## [9.8.10]", True), ("## [9.8.1]", False),
                               ("## [9.8.7]", False)):
            if (_run_step(script, cwd=tmp, SECTION=section) == 0) != found:
                problems += fail(f"the CHANGELOG check {'missed' if found else 'found'} {section!r}")
    return problems


def case_security_names_the_release_key():
    """The verify steps name the key releases are signed with; a placeholder there tells a user to
    trust nothing in particular."""
    path = os.path.join(os.path.dirname(WORKFLOWS), "..", "SECURITY.md")
    with open(path, encoding="utf-8") as handle:
        text = handle.read()
    if "RELEASE_KEY_FINGERPRINT" in text:
        return fail("SECURITY.md still has the release key's fingerprint as a placeholder")
    named = set(re.findall(r"`([0-9A-F]{40})`", text))
    if not named:
        return fail("SECURITY.md names no 40-hex-digit release key fingerprint")
    key = os.path.join(os.path.dirname(path), "RELEASE-KEY.asc")
    shown = subprocess.run(["gpg", "--show-keys", "--with-colons", key], capture_output=True, text=True)
    published = {line.split(":")[9] for line in shown.stdout.splitlines() if line.startswith("fpr:")}
    if not published:
        return fail(f"gpg read no key from RELEASE-KEY.asc: {shown.stderr.strip()}")
    if not named <= published:
        return fail(f"SECURITY.md names {sorted(named)}, RELEASE-KEY.asc holds {sorted(published)}")
    return 0

def _sweep_plan(tier, only, arm=None):
    """The `--only` arguments sweep.yml's plan step passes for these inputs, from the step itself.
    With a list for `arm`, the `--arm` it asked `sweep_corpus.py` for is appended to it."""
    with open(os.path.join(WORKFLOWS, "sweep.yml"), encoding="utf-8") as handle:
        text = handle.read()
    step = re.search(r"\n      - id: plan\n(?:        [^\n]*\n)*?        run: \|\n((?:          [^\n]*\n|\n)+)", text)
    if not step:
        return None
    # Without -u: macOS's bash 3.2 reads an empty array as unbound, the runner's bash does not.
    script = "\n".join(line[10:] for line in step.group(1).splitlines()).replace("set -eu", "set -e", 1)
    with tempfile.TemporaryDirectory() as root:
        stub = os.path.join(root, "python3")
        calls = os.path.join(root, "calls")
        with open(stub, "w", encoding="utf-8") as handle:
            # Records what sweep_corpus.py was asked, and plans nothing.
            handle.write(f'#!/bin/sh\nprintf "%s\\n" "$@" > "{calls}"\necho "[]"\n')
        os.chmod(stub, 0o755)
        env = {**os.environ, "PATH": root + os.pathsep + os.environ.get("PATH", ""),
               "TIER": tier, "BASE": "", "MAJOR": "false", "ONLY": only,
               "GITHUB_OUTPUT": os.path.join(root, "output")}
        subprocess.run(["bash", "-c", script], env=env, check=True, capture_output=True)
        with open(calls, encoding="utf-8") as handle:
            argv = handle.read().split()
    if arm is not None and "--arm" in argv:
        arm.append(argv[argv.index("--arm") + 1])
    return argv[argv.index("--only") + 1:-1] if "--only" in argv else []


def case_a_scheduled_sweep_runs_the_full_arm_of_every_project_weekly():
    """A run with no inputs once planned nothing and ended green; a dispatch keeps its defaults."""
    problems = 0
    arm = []
    if _sweep_plan("", "", arm) != [] or arm != ["full"]:
        problems += fail(f"a run with no inputs asked for the {arm or 'no'} arm, not every project's full arm")
    with open(os.path.join(WORKFLOWS, "sweep.yml"), encoding="utf-8") as handle:
        text = handle.read()
    report = re.search(r"^  report-failure:\n((?:    [^\n]*\n|\n)*)", text, re.M)
    for needle, why in (
        ("  schedule:\n    - cron: ", "it is not scheduled"),
        ("  workflow_dispatch:\n", "it cannot be dispatched"),
        ('--factor "${FACTOR:-1.5}"', "a run with no inputs passes no factor to the slicer"),
        ("        default: auto\n", "a dispatch's tier no longer defaults to auto"),
        ("        default: demo\n", "a dispatch's only no longer defaults to demo"),
        ("        default: '1.5'\n", "a dispatch's factor no longer defaults to 1.5"),
    ):
        if needle not in text:
            problems += fail(f"sweep.yml: {why}")
    if not report or "    if: failure() && github.event_name == 'schedule'\n" not in report.group(1) \
            or "merge" not in report.group(1).split("\n", 1)[0]:
        problems += fail("sweep.yml: a failed scheduled run, or one whose merge declared a project "
                         "missing, opens no issue")
    return problems


def case_a_sweep_of_every_project_can_be_asked_for():
    """`only` defaults to the demo, which a dispatch leaving it empty gets; `all` means every project,
    and so does an empty value, which only a run without inputs (a schedule) passes."""
    problems = 0
    for only, want in (("all", []), ("demo", ["demo"]), ("okio detekt", ["okio", "detekt"]),
                       ("", [])):
        got = _sweep_plan("full", only)
        if got != want:
            problems += fail(f"only={only!r} swept {got if got else 'every project'}, not "
                             f"{want if want else 'every project'}")
    if _sweep_plan("reduced", "demo") != []:
        problems += fail("the reduced arm was restricted by only")
    return problems


def _sweep_step(text, opener):
    """A step's `run:` script from sweep.yml, runnable by macOS's bash 3.2 as by the runner's."""
    step = re.search(re.escape(opener)
                     + r"\n(?:        [^\n]*\n)*?        run: \|\n((?:          [^\n]*\n|\n)+)", text)
    script = "\n".join(line[10:] for line in step.group(1).splitlines()).replace("set -eu", "set -e", 1)
    # bash 3.2 has no mapfile; the runner's bash does.
    return ('mapfile(){ local __a=$2 __l; eval "$__a=()"; '
            'while IFS= read -r __l; do eval "$__a+=(\\"\\$__l\\")"; done; }\n' + script)


SAMPLER = os.path.join(os.path.dirname(WORKFLOWS), "..", "bench", "machine_sampler.sh")


def case_the_machine_sampler_logs_memory_disk_and_the_largest_processes():
    """What a runner shut down for want of memory or disk leaves in its job log."""
    problems = 0
    with tempfile.TemporaryDirectory() as root:
        meminfo = os.path.join(root, "meminfo")
        with open(meminfo, "w", encoding="utf-8") as handle:
            handle.write("MemTotal: 16384000 kB\nMemAvailable: 4096000 kB\nSwapTotal: 4096000 kB\n"
                         "SwapFree: 3072000 kB\n")
        sampler = subprocess.Popen(["sh", SAMPLER, "1"], stdout=subprocess.PIPE, text=True,
                                   env={**os.environ, "MACHINE_SAMPLER_MEMINFO": meminfo})
        time.sleep(2.5)
        sampler.terminate()
        lines = sampler.communicate()[0].splitlines()
        if len(lines) < 3 or "memory total 16000M" not in lines[0]:
            problems += fail(f"the sampler printed no header and two samples: {lines}")
        for line in lines[1:]:
            if not (line.startswith("[machine] ") and "memory used 12000M available 4000M, swap used 1000M" in line
                    and re.search(r"free on / \d+M, on the workspace \d+M; largest: \S", line)):
                problems += fail(f"a sample lacks memory, swap, disk or the largest processes: {line!r}")
        tools = os.path.join(root, "bin")
        os.makedirs(tools)
        for tool in ("ps", "awk", "sort"):
            os.symlink(shutil.which(tool), os.path.join(tools, tool))
        done = subprocess.run([shutil.which("sh"), SAMPLER, "1"], capture_output=True, text=True,
                              timeout=10, env={**os.environ, "PATH": tools})
        if done.returncode != 0 or done.stdout.strip() != "[machine] not sampling: df is not on PATH":
            problems += fail(f"a sampler without df did not say so once and exit 0: {done.returncode}, {done.stdout!r}")
    return problems


def case_every_measuring_step_samples_the_machine_until_it_ends():
    problems = 0
    with open(os.path.join(WORKFLOWS, "sweep.yml"), encoding="utf-8") as handle:
        text = handle.read()
    for opener in ("\n      - name: record and cut into slices", "\n      - name: measure this slice"):
        script = _sweep_step(text, opener)
        start = script.find("bench/machine_sampler.sh & sampler=$!")
        if start < 0 or "trap 'kill \"$sampler\" 2>/dev/null || true' EXIT" not in script \
                or start > script.find("python3 bench/run.py"):
            problems += fail(f"sweep.yml:{opener.split('name: ')[1]} does not sample the machine from "
                             f"before the build until the step ends")
    return problems


def case_a_failed_project_is_declared_missing_and_the_rest_still_merge():
    """One failed project must not cost the others their rows, and a sweep short of one must say so
    and fail: kafka's record failed and kafka-core lost a shard, so only okio is merged, the release
    notes name both, and the run ends red. A shard job without its own condition would be skipped by
    the failed record two jobs up, so every job after the records carries one."""
    problems = 0
    with open(os.path.join(WORKFLOWS, "sweep.yml"), encoding="utf-8") as handle:
        text = handle.read()
    for job in ("slices", "shard", "merge"):
        block = text[text.index(f"\n  {job}:\n"):]
        block = block[:block.index("\n    runs-on:")]
        if "!cancelled()" not in block:
            problems += fail(f"the {job} job does not run after a failed record job")
    slices = _sweep_step(text, "\n      - id: slices")
    merge = _sweep_step(text, "\n      - name: merge, and attach to a draft release")
    with tempfile.TemporaryDirectory() as root:
        bin_dir, bench = os.path.join(root, "bin"), os.path.join(root, "bench")
        os.makedirs(bin_dir)
        os.makedirs(bench)
        with open(os.path.join(bin_dir, "gh"), "w", encoding="utf-8") as handle:
            handle.write(f'#!/bin/sh\nprintf "%s\\n" "$@" > "{root}/gh-args"\n')
        os.chmod(os.path.join(bin_dir, "gh"), 0o755)
        with open(os.path.join(bench, "merge.py"), "w", encoding="utf-8") as handle:
            handle.write(f"import json, os, sys\njson.dump(sys.argv[1:], open({root!r} + '/merge-args', 'w'))\n"
                         "os.makedirs('merged', exist_ok=True)\n")
        for name, shards in (("okio", (1, 2)), ("kafka-core", (1,))):
            record = os.path.join(root, "downloaded", f"record-{name}", name)
            state = os.path.join(root, "state", name)
            os.makedirs(record)
            os.makedirs(os.path.join(state, "results"))
            os.makedirs(os.path.join(state, "logs", "evidence"))
            with open(os.path.join(record, "accepted.json"), "w", encoding="utf-8") as handle:
                json.dump({"slices": [[], []]}, handle)
            with open(os.path.join(state, "results", f"{name}.json"), "w", encoding="utf-8") as handle:
                handle.write("{}")
            subprocess.run(["tar", "-czf", os.path.join(record, "state.tar.gz"), "-C", state,
                            "results", "logs"], check=True)
            for k in shards:
                shard = os.path.join(root, "downloaded", f"shard-{name}-{k}-of-2")
                os.makedirs(os.path.join(shard, name))
                os.makedirs(os.path.join(shard, "evidence"))
                open(os.path.join(shard, name, f"shard-{k}-of-2.json"), "w").close()
                open(os.path.join(shard, "evidence", "e.tar.gz"), "w").close()
        shutil.copytree(os.path.join(root, "downloaded"), os.path.join(root, "records"))
        plan = json.dumps([{"project": n} for n in ("kafka", "kafka-core", "okio")])
        env = {**os.environ, "PATH": bin_dir + os.pathsep + os.environ.get("PATH", ""),
               "PLAN": plan, "ARM": "full", "RUN": "7", "GITHUB_OUTPUT": os.path.join(root, "output")}
        done = subprocess.run(["bash", "-c", slices], cwd=root, env=env, capture_output=True, text=True)
        if not os.path.exists(env["GITHUB_OUTPUT"]):
            return problems + fail(f"slices wrote no outputs: {done.stdout.strip()} {done.stderr.strip()}")
        outputs = dict(line.split("=", 1) for line in open(env["GITHUB_OUTPUT"]).read().splitlines())
        missing = json.loads(outputs.get("missing", "[]"))
        shards = json.loads(outputs.get("shards", "[]"))
        if done.returncode or [m["project"] for m in missing] != ["kafka"] or \
                sorted({s["project"] for s in shards}) != ["kafka-core", "okio"]:
            problems += fail(f"slices: exit {done.returncode}, missing {missing}, shards {shards}")
        env["MISSING"] = outputs.get("missing", "[]")
        done = subprocess.run(["bash", "-c", merge], cwd=root, env=env, capture_output=True, text=True)
        merged = json.load(open(os.path.join(root, "merge-args")))
        notes = open(os.path.join(root, "gh-args")).read()
        if done.returncode == 0:
            problems += fail("a sweep short of two projects ended green")
        if "PARTIAL" not in notes:
            problems += fail("a partial sweep's draft release is titled as a complete one")
        if sorted(os.listdir(os.path.join(root, "art"))) != ["okio"]:
            problems += fail(f"merged {os.listdir(os.path.join(root, 'art'))}, not okio alone")
        for name in ("kafka=its record job failed", "kafka-core=shard(s) 2 of 2 failed"):
            if name not in merged or name not in notes:
                problems += fail(f"{name!r} is not declared to the merge and in the notes: {merged}")
        reduced = {**env, "ARM": "reduced", "GITHUB_OUTPUT": os.path.join(root, "output-reduced")}
        if subprocess.run(["bash", "-c", slices], cwd=root, env=reduced,
                          capture_output=True).returncode == 0:
            problems += fail("a reduced arm short of a member still went on to measure")
    return problems


def _sweep_completion_plan(tier, only, completes, changed=(), reachable=True, workflow="sweep"):
    """sweep.yml's plan step for a completion: its exit code, its output, and what it asked
    `sweep_corpus.py` for (None if nothing). `gh` names the earlier run's head commit, `git` the
    paths changed since it, and `bench/tier.py` is the real one."""
    with open(os.path.join(WORKFLOWS, "sweep.yml"), encoding="utf-8") as handle:
        script = _sweep_step(handle.read(), "\n      - id: plan")
    with tempfile.TemporaryDirectory() as root:
        calls = os.path.join(root, "calls")
        stubs = {
            "gh": f'#!/bin/sh\necho "$*" >> "{root}/gh-calls"\necho "{workflow} 0ld5ha"\n',
            "git": "#!/bin/sh\ncase $1 in\n"
                   f"  cat-file) exit {0 if reachable else 1} ;;\n"
                   f"  diff) printf '%s\\n' {' '.join(changed)} ;;\nesac\n",
            "python3": "#!/bin/sh\ncase $* in\n"
                       f'  *sweep_corpus.py*) printf "%s\\n" "$@" > "{calls}"; echo "[]" ;;\n'
                       f'  *) exec "{sys.executable}" "$@" ;;\nesac\n',
        }
        for name, body in stubs.items():
            with open(os.path.join(root, name), "w", encoding="utf-8") as handle:
                handle.write(body)
            os.chmod(os.path.join(root, name), 0o755)
        env = {**os.environ, "PATH": root + os.pathsep + os.environ.get("PATH", ""),
               "TIER": tier, "BASE": "", "MAJOR": "false", "ONLY": only, "COMPLETES": completes,
               "GITHUB_OUTPUT": os.path.join(root, "output")}
        done = subprocess.run(["bash", "-c", script], cwd=os.path.dirname(os.path.dirname(WORKFLOWS)),
                              env=env, capture_output=True, text=True)
        asked = open(calls, encoding="utf-8").read().split() if os.path.exists(calls) else None
        asked_gh = os.path.exists(os.path.join(root, "gh-calls"))
    return done.returncode, done.stdout + done.stderr, asked, asked_gh


def case_a_completion_that_cannot_complete_is_refused_before_it_measures():
    """A completion re-runs some of an earlier full sweep's projects on the selector that run
    measured; anything else would cost hours of runners for a merge that must refuse."""
    problems = 0
    selector = "agent/src/main/java/io/github/zeuspizza/yoriwake/agent/select/Rule.java"
    for what, tier, only, completes, changed, reachable, needle in (
            ("the reduced arm", "reduced", "demo", "5", (), True, "tier reduced"),
            ("an auto tier", "auto", "demo", "5", (), True, "tier auto"),
            ("every project", "full", "all", "5", (), True, "only names"),
            ("no project", "full", "", "5", (), True, "only names"),
            ("a run id that is not one", "full", "demo", "5;x", (), True, "5;x"),
            ("a selector source changed", "full", "demo", "5", ("README.md", selector), True,
             selector),
            ("a head commit out of reach", "full", "demo", "5", (), False, "0ld5ha"),
            ("a mutation grammar changed", "full", "demo", "5", ("README.md", "bench/mutations.py"), True,
             "bench/mutations.py"),
            ("a revert grammar changed", "full", "demo", "5", ("bench/revert.py",), True,
             "bench/revert.py")):
        code, output, asked, asked_gh = _sweep_completion_plan(tier, only, completes, changed,
                                                               reachable)
        if code == 0 or asked is not None or needle not in output:
            problems += fail(f"a completion of {what}: exit {code}, planned {asked}, said {output!r}")
        if what == "a run id that is not one" and asked_gh:
            problems += fail("a run id that is not one reached gh")
    code, output, asked, _ = _sweep_completion_plan("full", "demo", "5", workflow="guards")
    if code == 0 or asked is not None or "not a sweep" not in output:
        problems += fail(f"a completion of another workflow's run: exit {code}, planned {asked}, said {output!r}")
    code, output, asked, _ = _sweep_completion_plan("full", "demo kafka", "5",
                                                    ("README.md", "bench/merge.py"))
    if code or asked is None or asked[asked.index("--only") + 1:-1] != ["demo", "kafka"]:
        problems += fail(f"a completion on the earlier run's selector: exit {code}, planned {asked}, "
                         f"said {output!r}")
    return problems


def _sweep_completion_merge(root, releases, missing_after):
    """sweep.yml's merge step for run 7, which completes run 5 by re-running demo. `releases` is what
    the releases API lists, each asset served from `asset-<id>.tar.gz`; the stubbed `merge.py`
    reports `missing_after` as missing from the completed sweep."""
    with open(os.path.join(WORKFLOWS, "sweep.yml"), encoding="utf-8") as handle:
        script = _sweep_step(handle.read(), "\n      - name: merge, and attach to a draft release")
    bin_dir, bench = os.path.join(root, "bin"), os.path.join(root, "bench")
    os.makedirs(bin_dir)
    os.makedirs(bench)
    with open(os.path.join(root, "releases.json"), "w", encoding="utf-8") as handle:
        json.dump(releases, handle)
    with open(os.path.join(bin_dir, "gh"), "w", encoding="utf-8") as handle:
        # The listing through the real jq, as gh's --jq would apply it; an asset by its id.
        handle.write(f"#!{sys.executable}\nimport json, shutil, subprocess, sys\nargs = sys.argv[1:]\n"
                     f"if '--paginate' in args:\n"
                     f"    subprocess.run(['jq', '-r', args[args.index('--jq') + 1], {root!r} + '/releases.json'], check=True)\n"
                     f"elif args[0] == 'api':\n"
                     f"    shutil.copyfileobj(open({root!r} + '/asset-' + args[-1].rsplit('/', 1)[1] + '.tar.gz', 'rb'), sys.stdout.buffer)\n"
                     f"else:\n"
                     f"    json.dump(args, open({root!r} + '/gh-args', 'w'))\n")
    os.chmod(os.path.join(bin_dir, "gh"), 0o755)
    with open(os.path.join(bench, "merge.py"), "w", encoding="utf-8") as handle:
        handle.write(f"import json, os, sys\njson.dump(sys.argv[1:], open({root!r} + '/merge-args', 'w'))\n"
                     "os.makedirs('merged', exist_ok=True)\n"
                     f"json.dump({{'missing': {missing_after!r}}}, open('merged/sweep.json', 'w'))\n")
    record = os.path.join(root, "downloaded", "record-demo", "demo")
    state = os.path.join(root, "state")
    os.makedirs(record)
    os.makedirs(os.path.join(state, "results"))
    os.makedirs(os.path.join(state, "logs", "evidence"))
    with open(os.path.join(record, "accepted.json"), "w", encoding="utf-8") as handle:
        json.dump({"slices": [[]]}, handle)
    open(os.path.join(state, "results", "demo.json"), "w").close()
    subprocess.run(["tar", "-czf", os.path.join(record, "state.tar.gz"), "-C", state, "results",
                    "logs"], check=True)
    shard = os.path.join(root, "downloaded", "shard-demo-1-of-1")
    os.makedirs(os.path.join(shard, "demo"))
    os.makedirs(os.path.join(shard, "evidence"))
    open(os.path.join(shard, "demo", "shard-1-of-1.json"), "w").close()
    open(os.path.join(shard, "evidence", "e.tar.gz"), "w").close()
    env = {**os.environ, "PATH": bin_dir + os.pathsep + os.environ.get("PATH", ""),
           "PLAN": json.dumps([{"project": "demo"}]), "MISSING": "[]", "ARM": "full", "RUN": "7",
           "COMPLETES": "5", "REPO": "o/r"}
    return subprocess.run(["bash", "-c", script], cwd=root, env=env, capture_output=True, text=True)


def _earlier_release(root, asset, project):
    """An earlier run's release tarball holding one merged project and its evidence."""
    tree = os.path.join(root, f"tree-{asset}")
    for directory in (f"art/{project}", "evidence", "merged"):
        os.makedirs(os.path.join(tree, directory))
    open(os.path.join(tree, "art", project, "accepted.json"), "w").close()
    open(os.path.join(tree, "evidence", f"{project}.tar.gz"), "w").close()
    subprocess.run(["tar", "-czf", os.path.join(root, f"asset-{asset}.tar.gz"), "-C", tree, "art",
                    "evidence", "merged"], check=True)


def _draft(tag, created, asset, draft=True):
    return {"tag_name": tag, "draft": draft, "created_at": created,
            "assets": [{"name": "notes.txt", "id": asset + 100}, {"name": f"{tag}.tar.gz", "id": asset}]}


def case_a_completion_publishes_both_runs_from_the_earlier_runs_newest_draft():
    """Each merge attempt of a run adds a draft under one tag; the newest saw the most artifacts.
    The completed release holds both runs' projects and evidence, since the replay job reads one
    tarball alone."""
    problems = 0
    # Not listed newest first, so only the dates say which draft is the newest.
    releases = [_draft("sweep-6", "2026-10-03T00:00:00Z", 6), _draft("sweep-5", "2026-10-01T00:00:00Z", 51),
                _draft("sweep-5", "2026-10-02T00:00:00Z", 52),
                _draft("sweep-5", "2026-10-04T00:00:00Z", 53, draft=False)]
    with tempfile.TemporaryDirectory() as root:
        for asset, project in ((51, "stale"), (52, "okio"), (53, "published"), (6, "other")):
            _earlier_release(root, asset, project)
        done = _sweep_completion_merge(root, releases, {})
        if done.returncode:
            return fail(f"a completion's merge failed: {done.stdout} {done.stderr}")
        merged = json.load(open(os.path.join(root, "merge-args")))
        if merged[-2:] != ["--completes", "previous"]:
            problems += fail(f"merge.py was not told what it completes: {merged}")
        listed = subprocess.run(["tar", "-tzf", os.path.join(root, "sweep-7.tar.gz")], check=True,
                                capture_output=True, text=True).stdout.split()
        projects = sorted({path.split("/")[1] for path in listed if path.startswith("art/") and path.count("/") > 1})
        if projects != ["demo", "okio"]:
            problems += fail(f"the completed release's art/ holds {projects}, not demo and okio")
        if "evidence/okio.tar.gz" not in listed:
            problems += fail("the completed release lacks the earlier run's evidence")
        created = json.load(open(os.path.join(root, "gh-args")))
        title = created[created.index("--title") + 1]
        if title != "Benchmark sweep 7 (full arm, completes 5)":
            problems += fail(f"a complete completion is titled {title!r}")
    with tempfile.TemporaryDirectory() as root:
        _earlier_release(root, 52, "okio")
        done = _sweep_completion_merge(root, releases[2:3], {"kafka": "its record job failed"})
        created = json.load(open(os.path.join(root, "gh-args")))
        title, notes = created[created.index("--title") + 1], created[created.index("--notes") + 1]
        if title != "Benchmark sweep 7 (full arm, completes 5, PARTIAL)" or \
                "kafka=its record job failed" not in notes:
            problems += fail(f"a completion still short of kafka: {title!r} {notes!r}")
    return problems


def case_a_completion_of_a_run_with_no_draft_is_refused_and_publishes_nothing():
    with tempfile.TemporaryDirectory() as root:
        _earlier_release(root, 6, "other")
        done = _sweep_completion_merge(root, [_draft("sweep-6", "2026-10-03T00:00:00Z", 6),
                                              _draft("sweep-5", "2026-10-03T00:00:00Z", 5, draft=False)],
                                       {})
        if done.returncode == 0 or "run 5" not in done.stdout + done.stderr:
            return fail(f"a completion of a run with no draft: exit {done.returncode}, {done.stdout!r}")
        if os.path.exists(os.path.join(root, "gh-args")) or os.path.exists(os.path.join(root, "merge-args")):
            return fail("a completion of a run with no draft merged or created a release")
    return 0


def case_a_record_job_archives_the_checkout_the_plan_names():
    """A project measured in another's clone has no `checkouts/<its name>`: a record job archiving
    that path fails, and its slices get nothing to measure."""
    problems = 0
    with open(os.path.join(WORKFLOWS, "sweep.yml"), encoding="utf-8") as handle:
        text = handle.read()
    record = text[text.index("\n  record:\n"):text.index("\n  slices:\n")]
    if "CHECKOUT: ${{ matrix.project.checkout }}" not in record:
        problems += fail("the record job does not take its checkout from the plan")
    if '"$CHECKOUT"' not in record or "checkouts/$NAME" in record:
        problems += fail("the record job does not archive the checkout the plan names")
    return problems


def job_summary_problems(text):
    """Each setup-gradle step that would write its summary on a passing job.

    The action writes one by default for every job, so a run of many jobs buries the run's own
    summary under them; `on-failure` keeps the one that explains a failed build, and `never` is for
    jobs whose builds fail by design, where a failed build says nothing.
    """
    lines = text.splitlines()
    problems = []
    for number, line in enumerate(lines):
        if "gradle/actions/setup-gradle@" not in line:
            continue
        indent = len(line) - len(line.lstrip())
        step = []
        for later in lines[number + 1:]:
            if later.strip() and len(later) - len(later.lstrip()) <= indent:
                break
            step.append(later.strip())
        if not any(entry in ("add-job-summary: on-failure", "add-job-summary: never") for entry in step):
            problems.append(f"line {number + 1}: setup-gradle does not set add-job-summary to on-failure or never")
    return problems


def case_every_setup_gradle_step_writes_its_summary_only_when_it_can_explain_a_failure():
    problems = 0
    for name in sorted(n for n in os.listdir(WORKFLOWS) if n.endswith((".yml", ".yaml"))):
        with open(os.path.join(WORKFLOWS, name), encoding="utf-8") as handle:
            for problem in job_summary_problems(handle.read()):
                problems += fail(f"{name} {problem}")
    return problems


def case_a_setup_gradle_step_that_writes_its_summary_on_success_is_reported():
    problems = 0
    setup = "      - uses: gradle/actions/setup-gradle@0123456789012345678901234567890123456789 # v6.4.0\n"
    after = "      - run: ./gradlew build\n        with:\n          add-job-summary: never\n"
    for setting in ("on-failure", "never"):
        text = setup + f"        with:\n          cache-read-only: true\n          add-job-summary: {setting}\n"
        if job_summary_problems(text + after):
            problems += fail(f"a step set to {setting} was reported: {job_summary_problems(text + after)}")
    for label, text in (("no input", setup), ("always", setup + "        with:\n          add-job-summary: always\n")):
        found = job_summary_problems("jobs:\n" + text + after)
        if found != ["line 2: setup-gradle does not set add-job-summary to on-failure or never"]:
            problems += fail(f"a step with {label} gave {found}")
    if job_summary_problems("jobs:\n  scan:\n    steps:\n      - run: true\n"):
        problems += fail("a workflow that sets no Gradle up was reported")
    return problems


def _junit(root, artifact, cases, name="TEST-a.xml"):
    """One artifact's JUnit file: `cases` maps a test name to None (passed), "skipped", or a
    failure message."""
    path = os.path.join(root, artifact)
    os.makedirs(path, exist_ok=True)
    body = ""
    for test, outcome in cases.items():
        inner = ("<skipped/>" if outcome == "skipped"
                 else f'<failure message="{outcome}">trace</failure>' if outcome else "")
        body += f'<testcase classname="dev.demo.{test.split(".")[0]}" name="{test.split(".")[1]}">{inner}</testcase>'
    with open(os.path.join(path, name), "w", encoding="utf-8") as handle:
        handle.write(f'<?xml version="1.0"?><testsuite name="s">{body}</testsuite>')


SUMMARY_WORKFLOW = """name: verify
on: push
jobs:
  changes:
    name: what changed
    runs-on: ubuntu-latest
  functional-shard:
    name: functional (shard ${{ matrix.shard }}/2)
    needs: changes
  functional:
    name: functional
    needs: [changes, functional-shard]
"""


def _summary(root, results, run_url=None, artifacts="results"):
    needs = {job: {"result": result, "outputs": {}} for job, result in results.items()}
    return run_summary.summary(SUMMARY_WORKFLOW, needs, os.path.join(root, artifacts), run_url)


GREEN = {"changes": "success", "functional-shard": "success", "functional": "success"}


def case_a_green_run_summarises_every_job_and_each_suites_shards():
    problems = 0
    with tempfile.TemporaryDirectory() as root:
        _junit(os.path.join(root, "results"), "functional-linux-functionalTest-1-of-2",
               {"AlphaTest.one": None, "AlphaTest.two": "skipped"})
        _junit(os.path.join(root, "results"), "functional-linux-functionalTest-2-of-2", {"BetaTest.one": None})
        text = _summary(root, GREEN, "https://example.org/run/1")
    for expected in ("## verify: success", "[The run](https://example.org/run/1)", "| what changed | success |",
                     "| functional (shard */2) | success |", "| functional | success |",
                     "| functional-linux-functionalTest | 2/2 | 3 | 0 | 1 |"):
        if expected not in text:
            problems += fail(f"a green run's summary lacks {expected!r}:\n{text}")
    if "What failed" in text:
        problems += fail(f"a green run's summary has a failure section:\n{text}")
    return problems


def case_a_failed_run_names_the_job_the_test_and_the_shard_that_left_nothing():
    problems = 0
    with tempfile.TemporaryDirectory() as root:
        _junit(os.path.join(root, "results"), "functional-linux-functionalTest-1-of-2",
               {"AlphaTest.one": "expected &lt;1&gt; | got 2&#10;at line 3", "AlphaTest.two": None})
        text = _summary(root, {**GREEN, "functional-shard": "failure", "functional": "failure"})
    for expected in ("## verify: failure", "| functional (shard */2) | failure |",
                     "| functional-linux-functionalTest | 1/2 | 2 | 1 | 0 |", "- job functional: failure",
                     "- functional-linux-functionalTest: shard 2 of 2 left no results",
                     "- functional-linux-functionalTest: AlphaTest.one: expected &lt;1&gt; \\| got 2\n"):
        if expected not in text:
            problems += fail(f"a failed run's summary lacks {expected!r}:\n{text}")
    if "at line 3" in text:
        problems += fail("a failure message's later lines were listed")
    return problems


def case_a_guard_that_did_not_fire_is_counted_and_listed():
    problems = 0
    with tempfile.TemporaryDirectory() as root:
        _guard_shard(root, 1, 2, {"a": "fired", "b": "nothing caught"}, 3)
        _guard_shard(root, 2, 2, {"c": "fired"}, 3)
        text = _summary(root, GREEN, artifacts="shards")
    for expected in ("| guards-pr: changed | unit | 2/2 | 2 | 1 |",
                     "- guards-pr: changed, shard 1/2: b: nothing caught"):
        if expected not in text:
            problems += fail(f"a guard run's summary lacks {expected!r}:\n{text}")
    return problems


def case_failure_detail_stops_at_twenty_lines_and_counts_the_rest():
    with tempfile.TemporaryDirectory() as root:
        _junit(os.path.join(root, "results"), "cell-linux-8.14-21", {f"T.t{n}": "boom" for n in range(25)})
        text = _summary(root, GREEN)
    listed = [line for line in text.splitlines() if line.startswith("- cell-linux-8.14-21: T.")]
    if len(listed) != 20 or "- and 5 more" not in text:
        return fail(f"25 failing cases gave {len(listed)} listed:\n{text}")
    return 0


def case_an_unreadable_file_is_a_line_not_an_exit_code():
    import contextlib
    import io
    problems = 0
    with tempfile.TemporaryDirectory() as root:
        _junit(os.path.join(root, "results"), "functional-linux-functionalTest-1-of-1", {"AlphaTest.one": None})
        with open(os.path.join(root, "results", "functional-linux-functionalTest-1-of-1", "TEST-b.xml"), "w",
                  encoding="utf-8") as handle:
            handle.write("<testsuite><testcase")
        workflow = os.path.join(root, "verify.yml")
        with open(workflow, "w", encoding="utf-8") as handle:
            handle.write(SUMMARY_WORKFLOW)
        out = io.StringIO()
        saved = {name: os.environ.pop(name, None) for name in ("GITHUB_STEP_SUMMARY", "NEEDS")}
        try:
            os.environ["NEEDS"] = json.dumps({"changes": {"result": "success"}})
            with contextlib.redirect_stdout(out):
                code = run_summary.main([workflow, os.path.join(root, "results")])
            target = os.path.join(root, "summary.md")
            os.environ["GITHUB_STEP_SUMMARY"] = target
            with contextlib.redirect_stdout(io.StringIO()) as quiet:
                run_summary.main([workflow, os.path.join(root, "results")])
            with open(target, encoding="utf-8") as handle:
                written = handle.read()
        finally:
            for name, value in saved.items():
                os.environ.pop(name, None)
                if value is not None:
                    os.environ[name] = value
    if code != 0:
        problems += fail(f"a malformed results file made the summary exit {code}")
    if "functional-linux-functionalTest-1-of-1/TEST-b.xml: not read" not in out.getvalue():
        problems += fail(f"the malformed file is not named:\n{out.getvalue()}")
    if "## verify: success" not in written or quiet.getvalue():
        problems += fail("with GITHUB_STEP_SUMMARY set, the summary was not written there alone")
    return problems


def case_skipped_and_cancelled_jobs_and_no_results_are_said_as_such():
    problems = 0
    with tempfile.TemporaryDirectory() as root:
        text = _summary(root, {"changes": "success", "functional-shard": "cancelled", "functional": "skipped",
                               "elsewhere": "success"})
    for expected in ("## verify: cancelled", "| functional (shard */2) | cancelled |", "| functional | skipped |",
                     "| elsewhere | success |", "No test or guard results", "- job functional (shard */2): cancelled"):
        if expected not in text:
            problems += fail(f"the summary lacks {expected!r}:\n{text}")
    return problems


# What branch protection requires on main; the run summary must never be one of them.
REQUIRED_CHECKS = (
    "unit and agent suites", "unit and agent suites on windows", "functional on Gradle 8.14",
    "functional on Gradle 9", "functional on Gradle 8.14 on windows", "functional on Gradle 9 on windows",
    "select on another machine", "select on windows, against a map captured on linux",
    "select on windows, against a map captured on windows", "select on linux, against a map captured on windows",
    "coverage gate", "guards, fast and the change's own", "guards, every one", "no private references",
    "no guard weakened",
)
# The workflows that end in one summary, and the jobs it need not wait for.
RUN_SUMMARIES = {"verify.yml": (), "guards.yml": ("report-failure",), "matrix.yml": ()}


def run_summary_problems(text, exempt=()):
    """What keeps a workflow's `run-summary` job from summing up every job, or makes it gate one."""
    block = _job_block(text, "run-summary")
    if block is None:
        return ["no run-summary job"]
    problems = []
    jobs = re.findall(r"^  ([\w-]+):\s*$", text.split("\njobs:\n", 1)[1], re.M)
    listed = re.search(r"^    needs: \[([^\]]*)\]", block, re.M)
    needs = {job.strip() for job in listed.group(1).split(",")} if listed else set()
    for job in jobs:
        if job != "run-summary" and job not in exempt and job not in needs:
            problems.append(f"run-summary does not wait for {job}")
    if not re.search(r"^    if: always\(\)", block, re.M):
        problems.append("run-summary does not run when a job failed (if: always())")
    name = re.search(r"^    name: (.+)$", block, re.M)
    if not name or name.group(1) in REQUIRED_CHECKS:
        problems.append(f"run-summary is named {name.group(1) if name else None!r}, a required check or none")
    if "python3 .github/run_summary.py" not in block or "NEEDS: ${{ toJSON(needs) }}" not in block:
        problems.append("run-summary does not hand the job results to .github/run_summary.py")
    return problems


def case_each_run_ends_in_one_summary_that_waits_for_every_job_and_gates_nothing():
    problems = 0
    for name, exempt in RUN_SUMMARIES.items():
        with open(os.path.join(WORKFLOWS, name), encoding="utf-8") as handle:
            text = handle.read()
        for problem in run_summary_problems(text, exempt):
            problems += fail(f"{name}: {problem}")
        if f"run_summary.py .github/workflows/{name} " not in text:
            problems += fail(f"{name}: run-summary reads another workflow's jobs")
    return problems


def case_a_run_summary_that_misses_a_job_or_gates_is_reported():
    problems = 0
    with open(os.path.join(WORKFLOWS, "guards.yml"), encoding="utf-8") as handle:
        text = handle.read()
    for edited, expected in (
        (text.replace("needs: [changes, fast-shard, fast, full-shard, full]",
                      "needs: [changes, fast-shard, fast, full-shard]"), "does not wait for full"),
        (text.replace("    name: run summary\n", "    name: guards, every one\n"), "a required check"),
        (text.replace("    if: always() && needs.changes.outputs.code != 'false'\n    runs-on: ubuntu-24.04\n    steps:\n"
                      "      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1\n"
                      "      - uses: actions/download-artifact@9000827ccba6bdab643e8b6fd33ac0654aef8333 # v8.0.2\n"
                      "        with:\n          pattern: guards-*",
                      "    if: needs.changes.outputs.code != 'false'\n    runs-on: ubuntu-24.04\n    steps:\n"
                      "      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1\n"
                      "      - uses: actions/download-artifact@9000827ccba6bdab643e8b6fd33ac0654aef8333 # v8.0.2\n"
                      "        with:\n          pattern: guards-*"), "if: always()"),
    ):
        found = run_summary_problems(edited, RUN_SUMMARIES["guards.yml"])
        if edited == text:
            problems += fail(f"the fixture edit for {expected!r} no longer applies to guards.yml")
        elif not any(expected in problem for problem in found):
            problems += fail(f"an edited guards.yml was not reported for {expected!r}: {found}")
    return problems


CASES = (
    case_every_functional_shard_keeps_its_evidence_when_it_fails,
    case_a_shard_that_drops_its_evidence_is_reported,
    case_the_test_kit_listing_names_each_cached_file_and_its_size,
    case_no_test_kit_home_is_said_rather_than_silent,
    case_a_dropped_or_rate_limited_download_is_a_download_failure,
    case_a_failure_that_is_not_a_download_is_not_rerun,
    case_a_build_that_failed_on_a_download_runs_once_more,
    case_on_windows_the_wrapper_script_runs_as_its_batch_file,
    case_output_the_console_cannot_encode_passes_through_untouched,
    case_the_matrix_cell_builds_through_the_download_rerun,
    case_the_matrix_runs_nightly_and_reports_a_failed_night,
    case_a_failure_that_lasts_is_one_issue_not_one_a_night,
    case_the_coverage_badge_states_the_gates_minimums,
    case_a_badge_one_point_off_either_gate_is_reported,
    case_a_code_file_moved_into_docs_still_needs_the_checks,
    case_a_line_added_under_a_released_version_fails_naming_it,
    case_a_release_entry_passes,
    case_a_fixed_section_added_under_unreleased_passes,
    case_a_patch_section_passes,
    case_a_redated_release_fails,
    case_a_release_renamed_to_a_new_version_fails,
    case_a_line_under_a_released_version_fails_whatever_breaks_lines_above_it,
    case_the_changelog_check_reads_the_root_changelog_from_a_subdirectory,
    case_preamble_link_and_blank_lines_pass,
    case_a_changelog_git_cannot_read_fails_the_check,
    case_the_changelog_check_runs_on_every_pull_request_and_push_to_main,
    case_a_changelog_workflow_that_skips_or_stops_checking_is_reported,
    case_only_a_docs_change_skips_the_checks,
    case_a_change_to_product_sources_runs_every_guard,
    case_the_guard_workflows_are_read_only_and_cover_every_pull_request,
    case_every_verify_job_waits_for_the_change_and_runs_unless_told_not_to,
    case_the_source_scan_finds_the_test_classes_and_only_them,
    case_shards_that_partition_the_suite_pass,
    case_every_way_a_test_class_can_go_untested_fails,
    case_every_sharded_suite_runs_all_its_shards,
    case_no_downloaded_shard_is_every_shard_missing,
    case_guard_shards_that_partition_the_selection_and_fire_pass,
    case_every_way_a_selected_guard_can_go_unproved_fails,
    case_every_guard_job_runs_all_its_shards_under_its_required_name,
    case_finds_a_map_under_a_task_directory_it_could_not_predict,
    case_find_map_prints_a_readable_file_because_the_workflow_greps_it,
    case_no_map_is_reported_rather_than_returned_as_success,
    case_the_newest_map_wins_when_a_project_has_several,
    case_the_mutation_changes_exactly_its_line,
    case_a_mutation_that_would_be_silent_fails_loudly,
    case_restore_is_the_mutation_backwards,
    case_each_source_gets_its_own_mutation_and_restore,
    case_a_source_without_a_mutation_is_refused,
    case_the_epsilon_round_fails_unless_its_change_widened_the_run_for_its_reason,
    case_a_map_from_another_os_must_widen_the_run_and_say_why,
    case_discard_removes_results_and_leaves_the_map_alone_by_default,
    case_discard_on_a_clean_tree_is_not_an_error,
    case_a_tag_pin_is_reported,
    case_a_sha_without_its_version_comment_is_reported,
    case_every_workflow_pins_its_actions_by_sha,
    case_no_workflow_names_a_moving_linux_runner,
    case_an_upload_without_a_retention_or_a_reader_is_reported,
    case_every_upload_names_its_retention_and_its_reader,
    case_every_workflow_keeps_the_gradle_notice_off_and_saves_no_cache_under_a_daemon,
    case_a_workflow_without_the_switch_or_a_writing_job_without_the_stop_is_reported,
    case_the_reference_table_and_the_matrix_cells_agree,
    case_a_table_that_drifts_from_the_cells_is_reported,
    case_a_malformed_row_or_axis_is_reported,
    case_a_cell_line_that_cannot_be_read_or_lies_about_its_os_stops_the_check,
    case_a_complete_run_is_green,
    case_a_cell_missing_a_test_the_reference_ran_fails_naming_it,
    case_a_skip_passes_only_where_the_allowlist_names_it,
    case_a_cell_with_no_tests_fails,
    case_a_cell_that_ran_the_defaults_under_another_label_fails,
    case_a_row_whose_proving_test_ran_nowhere_fails,
    case_a_row_whose_proving_test_was_skipped_fails_even_where_the_skip_is_allowed,
    case_a_failed_test_or_missing_identity_fails_the_cell,
    case_a_missing_or_wider_cell_fails,
    case_a_job_that_ran_another_commit_fails,
    case_a_cell_with_two_tasks_results_fails,
    case_the_early_access_job_runs_weekly_bounded_and_read_only,
    case_the_early_access_check_passes_only_when_its_test_passed,
    case_the_release_ships_the_tested_bytes_and_keeps_each_key_on_its_job,
    case_a_release_that_rebuilds_or_spreads_a_key_is_reported,
    case_a_tag_releases_its_version_and_a_dispatch_rehearses,
    case_a_rehearsal_runs_only_on_a_release_lines_candidate,
    case_the_changelog_check_finds_exactly_the_section_it_is_given,
    case_security_names_the_release_key,
    case_a_sweep_of_every_project_can_be_asked_for,
    case_a_scheduled_sweep_runs_the_full_arm_of_every_project_weekly,
    case_a_record_job_archives_the_checkout_the_plan_names,
    case_a_failed_project_is_declared_missing_and_the_rest_still_merge,
    case_a_completion_that_cannot_complete_is_refused_before_it_measures,
    case_a_completion_publishes_both_runs_from_the_earlier_runs_newest_draft,
    case_a_completion_of_a_run_with_no_draft_is_refused_and_publishes_nothing,
    case_the_machine_sampler_logs_memory_disk_and_the_largest_processes,
    case_every_measuring_step_samples_the_machine_until_it_ends,
    case_every_setup_gradle_step_writes_its_summary_only_when_it_can_explain_a_failure,
    case_a_setup_gradle_step_that_writes_its_summary_on_success_is_reported,
    case_a_green_run_summarises_every_job_and_each_suites_shards,
    case_a_failed_run_names_the_job_the_test_and_the_shard_that_left_nothing,
    case_a_guard_that_did_not_fire_is_counted_and_listed,
    case_failure_detail_stops_at_twenty_lines_and_counts_the_rest,
    case_an_unreadable_file_is_a_line_not_an_exit_code,
    case_skipped_and_cancelled_jobs_and_no_results_are_said_as_such,
    case_each_run_ends_in_one_summary_that_waits_for_every_job_and_gates_nothing,
    case_a_run_summary_that_misses_a_job_or_gates_is_reported,
)


def case_every_case_in_this_file_is_registered():
    """Six cases were once defined and never run. The guard is cheap; the silence was not."""
    defined = {
        name
        for name, value in globals().items()
        if name.startswith("case_") and callable(value) and name != "case_every_case_in_this_file_is_registered"
    }
    registered = {case.__name__ for case in CASES}
    missing = sorted(defined - registered)
    return fail(f"defined but not in CASES: {missing}") if missing else 0


def main():
    problems = 0
    for case in CASES + (case_every_case_in_this_file_is_registered,):
        print(case.__name__)
        problems += case()
    if problems:
        print(f"\n{problems} failed check(s)")
        return 1
    print(f"\n{len(CASES) + 1} cases, all green")
    return 0


def test_ci():
    assert main() == 0


if __name__ == "__main__":
    sys.exit(main())
