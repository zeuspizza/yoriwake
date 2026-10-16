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

import os
import re
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import assert_selection  # noqa: E402
import ci  # noqa: E402
import functional_shards  # noqa: E402
import matrix_check  # noqa: E402


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
    ):
        if needle not in text:
            problems += fail(f"jdk-ea.yml: {why}")
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


def case_only_a_docs_change_skips_the_checks():
    """A wrong "docs only" skips every check, which GitHub counts as passing: each way in is tested."""
    problems = 0
    cases = {
        ("README.md",): False,
        ("docs/ci.md", "CHANGELOG.md"): False,
        ("docs/contract.md",): True,
        ("docs/reference.md", "README.md"): True,
        ("README.md", "plugin/build.gradle.kts"): True,
        ("docs/images/chart.svg",): False,
        (".github/workflows/verify.yml",): True,
        (): True,
        ("",): True,
    }
    for paths, expected in cases.items():
        if ci.needs_checks(list(paths)) != expected:
            problems += fail(f"needs_checks({list(paths)}) should be {expected}")
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
    ):
        if needle not in table:
            problems += fail(f"guard-table.yml: {why}")
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


CASES = (
    case_a_code_file_moved_into_docs_still_needs_the_checks,
    case_only_a_docs_change_skips_the_checks,
    case_a_change_to_product_sources_runs_every_guard,
    case_the_guard_workflows_are_read_only_and_cover_every_pull_request,
    case_every_verify_job_waits_for_the_change_and_runs_unless_told_not_to,
    case_the_source_scan_finds_the_test_classes_and_only_them,
    case_shards_that_partition_the_suite_pass,
    case_every_way_a_test_class_can_go_untested_fails,
    case_every_sharded_suite_runs_all_its_shards,
    case_no_downloaded_shard_is_every_shard_missing,
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
