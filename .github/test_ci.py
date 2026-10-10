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

import ci  # noqa: E402


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
    problems = 0
    with tempfile.TemporaryDirectory() as root:
        source = os.path.join(root, "Alpha.java")
        original = "int twice(int n) {\n    return n * 2;\n}\n"
        with open(source, "w", encoding="utf-8") as handle:
            handle.write(original)
        ci.main(["mutate", source])
        ci.main(["restore", source])
        with open(source, encoding="utf-8") as handle:
            if handle.read() != original:
                problems += fail("restore did not return the file to its original bytes")
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

        ci.discard_results(root)
        if os.path.exists(results):
            problems += fail("a previous step's results survived; the next step would assert on them")
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


CASES = (
    case_finds_a_map_under_a_task_directory_it_could_not_predict,
    case_find_map_prints_a_readable_file_because_the_workflow_greps_it,
    case_no_map_is_reported_rather_than_returned_as_success,
    case_the_newest_map_wins_when_a_project_has_several,
    case_the_mutation_changes_exactly_its_line,
    case_a_mutation_that_would_be_silent_fails_loudly,
    case_restore_is_the_mutation_backwards,
    case_discard_removes_results_and_leaves_the_map_alone_by_default,
    case_discard_on_a_clean_tree_is_not_an_error,
    case_a_tag_pin_is_reported,
    case_a_sha_without_its_version_comment_is_reported,
    case_every_workflow_pins_its_actions_by_sha,
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
