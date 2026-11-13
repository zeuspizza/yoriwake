"""
Unit tests for which guards `prove_guards.py --changed` selects.

THE FAILURE THIS EXISTS TO PREVENT is a change that skips the guard proving it. Selecting too many
guards costs minutes; selecting too few lets an unproved guard ride a commit that says "every guard
this run selected fired", which is true and says nothing about the one it left out.

Run: `python scripts/test_prove_guards.py`
"""

import argparse
import contextlib
import io
import json
import math
import os
import subprocess
import sys
import tempfile
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import prove_guards as pg  # noqa: E402


def fail(message):
    print(f"  FAIL: {message}")
    return 1


def guard(path="bench/run.py", suite=pg.python_suite, label="g", old="a", new="b", expect="x"):
    return (label, path, old, new, suite, expect)


def unchanged(mutation):
    label, path, old, new, suite, expect = mutation
    return {label: (path, old, new, suite.__name__, expect)}


def case_a_guard_whose_target_changed_is_selected():
    m = guard()
    return 0 if pg.why_affected(m, {"bench/run.py"}, unchanged(m)) else \
        fail("a change to a guard's own target did not select it")


def case_a_change_to_the_tests_that_prove_a_guard_selects_it():
    failures = 0
    m = guard(path="agent/src/main/java/io/github/zeuspizza/yoriwake/agent/Selector.java", suite=pg.agent_suite)
    if not pg.why_affected(m, {"agent/src/test/kotlin/io/github/zeuspizza/yoriwake/agent/SelectorTest.kt"}, unchanged(m)):
        failures += fail("a changed agent test did not select an agent-suite guard")
    p = guard()
    if not pg.why_affected(p, {"bench/test_run.py"}, unchanged(p)):
        failures += fail("a changed test_run.py did not select a python-suite guard")
    return failures


def case_a_build_file_selects_every_gradle_suite_guard_and_no_python_one():
    failures = 0
    gradle = guard(path="core/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/report/Audit.kt", suite=pg.unit_suite)
    python = guard()
    if not pg.why_affected(gradle, {"gradle/libs.versions.toml"}, unchanged(gradle)):
        failures += fail("a changed build file did not select a Gradle-suite guard")
    if pg.why_affected(python, {"gradle/libs.versions.toml"}, unchanged(python)):
        failures += fail("a changed build file selected a python-suite guard")
    return failures


def case_a_new_or_edited_guard_is_selected_even_when_nothing_else_moved():
    failures = 0
    m = guard()
    if not pg.why_affected(m, set(), {}):
        failures += fail("a guard absent at the base was not selected")
    edited = unchanged(m)
    edited["g"] = ("bench/run.py", "a", "c", "python_suite", "x")
    if not pg.why_affected(m, set(), edited):
        failures += fail("a guard whose mutation changed was not selected")
    return failures


def case_an_edited_suite_runner_selects_its_guards():
    m = guard(path="core/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/report/Audit.kt", suite=pg.unit_suite)
    before = unchanged(m)
    before[None] = {"unit"}
    return 0 if pg.why_affected(m, set(), before) else \
        fail("a guard whose suite runner changed was not selected")


def case_an_unrelated_change_selects_nothing():
    m = guard(path="agent/src/main/java/io/github/zeuspizza/yoriwake/agent/Selector.java", suite=pg.agent_suite)
    return fail("an unrelated docs change selected a guard") \
        if pg.why_affected(m, {"docs/reference.md", "bench/run.py"}, unchanged(m)) else 0


def case_every_suite_names_the_tests_it_runs():
    """A suite missing from SUITE_TESTS would never be selected by a change to its own tests."""
    missing = sorted(name for name, _fn, _cost in pg.SUITES if name not in pg.SUITE_TESTS)
    return fail(f"no test paths for: {', '.join(missing)}") if missing else 0


def case_a_suite_that_merely_crashed_proves_no_guard():
    """A mutation that stops a suite running is not a guard firing, `expect` or no `expect`."""
    import contextlib
    import io
    failures = 0
    # Synthetic, because the table itself no longer carries a guard without an `expect`.
    pg.MUTATIONS.append(guard(expect=None))
    index = len(pg.MUTATIONS) - 1
    try:
        for crash in ("NOT THE GUARD -- bench/test_run.py did not run: TypeError",
                      "the unit suite failed without naming a test"):
            with contextlib.redirect_stdout(io.StringIO()):
                counted = pg._report(index, [crash], None)
            if counted != 1:
                failures += fail(f"an empty-expect guard was proved by `{crash}`")
        with contextlib.redirect_stdout(io.StringIO()):
            if pg._report(index, ["FAIL: the check it names"], None) != 0:
                failures += fail("a real failure line no longer proves an empty-expect guard")
    finally:
        pg.MUTATIONS.pop()
    return failures


# What `checkout.require_clean` prints when a suite asks a dirty tree for its commit: the closing
# advice is the last line, so a cut last line never named the files.
DIRTY_REFUSAL = ("REFUSING: the yoriwake checkout at /w has uncommitted changes, so no commit describes "
                 "what would be built and stamped:\n  ?? docs/stray.md\n   M plugin/build.gradle.kts\n"
                 "Commit, stash or remove them there, or point YORIWAKE_CHECKOUT at a clean checkout.")


def _crashed(directory, message):
    """`_python_checks` over a stand-in suite that exits with `message`, as `require_clean` does."""
    script = os.path.join(directory, "test_standin.py")
    with open(script, "w", encoding="utf-8") as handle:
        handle.write(f"import sys\nsys.exit({message!r})\n")
    return pg._python_checks(script)


def case_a_suite_stopped_by_a_dirty_tree_names_the_files():
    """The refusal's paths are the only evidence of what dirtied a worker tree mid-run."""
    import tempfile
    with tempfile.TemporaryDirectory() as directory:
        result = _crashed(directory, DIRTY_REFUSAL)
    if len(result) != 1:
        return fail(f"a crash should be one entry, got {result}")
    detail = result[0]
    missing = [part for part in ("NOT THE GUARD", "uncommitted changes", "docs/stray.md",
                                 "plugin/build.gradle.kts") if part not in detail]
    if missing or "\n" in detail:
        return fail(f"the crash detail is not one line naming the refusal and its files: {detail!r}")
    return 0


def case_only_the_refusal_block_is_read_as_dirty_files():
    """Indented lines after the refusal, such as a traceback printed at exit, are not files."""
    import tempfile
    trailing = DIRTY_REFUSAL + "\nException ignored in atexit callback:\n  File \"/w/bench/run.py\", line 9"
    with tempfile.TemporaryDirectory() as directory:
        detail = _crashed(directory, trailing)[0]
    if "atexit" in detail or "line 9" in detail or "docs/stray.md" not in detail:
        return fail(f"the detail read past the refusal's own paths: {detail!r}")
    return 0


def case_a_dirty_tree_crash_is_not_proved_and_its_files_reach_the_results():
    import argparse
    import contextlib
    import io
    import json
    import tempfile
    failures = 0
    pg.MUTATIONS.append(guard(label="dirty"))
    index = len(pg.MUTATIONS) - 1
    try:
        with tempfile.TemporaryDirectory() as directory:
            fired = _crashed(directory, DIRTY_REFUSAL)
            with contextlib.redirect_stdout(io.StringIO()):
                counted = pg._report(index, fired, None)
            if counted != 1:
                failures += fail("a guard whose suite stopped on a dirty tree was proved")
            out = os.path.join(directory, "results.json")
            args = argparse.Namespace(shard=None, mode="full", changed=None, guard=["dirty"])
            pg.write_results(out, args, [pg.MUTATIONS[index]], [pg.MUTATIONS[index]])
            with open(out, encoding="utf-8") as handle:
                stored = json.load(handle)["guards"][0]
        if stored["outcome"] != "not proved" or "docs/stray.md" not in stored["detail"]:
            failures += fail(f"the results do not carry an unproved guard naming the files: {stored}")
    finally:
        pg.MUTATIONS.pop()
        pg._RUN["outcomes"].pop("dirty", None)
    return failures


def case_any_other_crash_keeps_its_short_last_line():
    import tempfile
    with tempfile.TemporaryDirectory() as directory:
        result = _crashed(directory, "Traceback (most recent call last):\n  ...\nTypeError: " + "x" * 200)
    if len(result) != 1 or not result[0].startswith("NOT THE GUARD") or "TypeError" not in result[0]:
        return fail(f"a traceback crash lost its prefix or its error: {result}")
    if len(result[0].split("did not run: ", 1)[1]) > 70:
        return fail(f"a traceback crash was not cut to 70 characters: {result[0]!r}")
    return 0


def case_each_mutation_path_resolves_into_the_tree_under_test():
    """Product sources and the harness's own sit in one tree, so every path is relative to it."""
    failures = 0
    home = pg.yoriwake_checkout.root()
    for path in ("core/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/report/Audit.kt",
                 "scripts/yoriwake.init.gradle.kts", "bench/run.py", "bench/projects/caffeine.toml"):
        if pg.source(path) != os.path.join(home, path):
            failures += fail(f"{path} did not resolve into the tree at {home}: {pg.source(path)}")
    return failures


def case_every_mutation_anchor_matches_exactly_once_in_its_file():
    """The pre-flight refusal, asked of the whole table: a missing file or a moved anchor is a guard
    that cannot apply, and a repeated anchor is a guard that mutates a line it does not name."""
    failures = 0
    for label, path, old, _new, _suite, _expect in pg.MUTATIONS:
        where = pg.source(path)
        if not os.path.isfile(where):
            failures += fail(f"{label}: no file at {where}")
            continue
        count = pg.read_source(where).count(old)
        if count != 1:
            failures += fail(f"{label}: its anchor matches {count} times in {where}")
    return failures


def _repo(root, files):
    import subprocess
    os.makedirs(root)
    for name, text in files.items():
        os.makedirs(os.path.dirname(os.path.join(root, name)) or root, exist_ok=True)
        with open(os.path.join(root, name), "w", encoding="utf-8") as handle:
            handle.write(text)
    for args in (["init", "-q"], ["add", "-A"],
                 ["-c", "user.email=t@t", "-c", "user.name=t", "-c", "commit.gpgsign=false",
                  "commit", "-qm", "base"]):
        subprocess.run(["git", "-C", root, *args], check=True, capture_output=True)


def _status(root):
    import subprocess
    return subprocess.run(["git", "-C", root, "status", "--porcelain", "--untracked-files=all"],
                          capture_output=True, text=True, check=True).stdout


def case_a_worker_tree_carries_the_uncommitted_state_and_leaves_its_source_unwritten():
    """Each worker gets a worktree carrying the real tree's tracked edits, deletions and new files."""
    import tempfile
    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        repo = os.path.join(tmp, "repo")
        _repo(repo, {"kept.txt": "base\n", "edited.txt": "base\n", "gone.txt": "base\n",
                     "kept-then-removed.txt": "base\n"})
        with open(os.path.join(repo, "edited.txt"), "w", encoding="utf-8") as handle:
            handle.write("edited\n")
        with open(os.path.join(repo, "new.txt"), "w", encoding="utf-8") as handle:
            handle.write("new\n")
        os.remove(os.path.join(repo, "gone.txt"))
        import subprocess
        # Staged as well as unstaged: a `git rm` and a `git add` are working state too.
        with open(os.path.join(repo, "staged.txt"), "w", encoding="utf-8") as handle:
            handle.write("staged\n")
        subprocess.run(["git", "-C", repo, "add", "staged.txt"], check=True)
        subprocess.run(["git", "-C", repo, "rm", "-q", "kept-then-removed.txt"], check=True)
        before = _status(repo)
        worker = os.path.join(tmp, "worker")
        for _ in range(2):  # created, then reused
            pg._sync_worktree(repo, worker)
            with open(os.path.join(worker, "kept.txt"), "w", encoding="utf-8") as handle:
                handle.write("a mutation left behind\n")
        pg._sync_worktree(repo, worker)
        for name, text in (("kept.txt", "base\n"), ("edited.txt", "edited\n"), ("new.txt", "new\n"),
                           ("staged.txt", "staged\n")):
            if pg.read_source(os.path.join(worker, name)) != text:
                failures += fail(f"the worker tree's {name} is not the source tree's")
        for name in ("gone.txt", "kept-then-removed.txt"):
            if os.path.exists(os.path.join(worker, name)):
                failures += fail(f"{name}, deleted in the source tree, is present in the worker tree")
        if _status(repo) != before:
            failures += fail("syncing a worker tree changed the tree it was synced from")
        # Clean, because the harness's own checks refuse to stamp a tree with uncommitted changes.
        if _status(worker):
            failures += fail(f"the worker tree is not clean after syncing: {_status(worker)!r}")
    return failures


def case_a_tracked_edit_and_a_new_file_are_both_changes():
    """`--changed` sees an edit to a tracked file and a file git does not track yet."""
    import tempfile
    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        home = os.path.join(tmp, "yoriwake")
        _repo(home, {"plugin/src/main/A.kt": "a\n"})
        for name in ("plugin/src/main/A.kt", "bench/new.py"):
            os.makedirs(os.path.dirname(os.path.join(home, name)), exist_ok=True)
            with open(os.path.join(home, name), "w", encoding="utf-8") as handle:
                handle.write("b\n")
        setting = os.environ.get(pg.yoriwake_checkout.SETTING)
        try:
            os.environ[pg.yoriwake_checkout.SETTING] = home
            changed = pg.changed_files("HEAD")
        finally:
            if setting is None:
                os.environ.pop(pg.yoriwake_checkout.SETTING, None)
            else:
                os.environ[pg.yoriwake_checkout.SETTING] = setting
        for name in ("plugin/src/main/A.kt", "bench/new.py"):
            if name not in changed:
                failures += fail(f"a change to {name} was not seen: {sorted(changed)}")
    return failures


def case_a_removed_or_weakened_guard_is_named_against_the_base():
    """A pull request must not quietly lower the bar it is judged by."""
    failures = 0
    kept = guard(label="kept")
    gone = guard(label="gone")
    moved = guard(label="moved", path="bench/cost.py")
    retargeted = guard(label="retargeted", expect="another test")
    then = {**unchanged(kept), **unchanged(gone), **unchanged(guard(label="moved")),
            **unchanged(guard(label="retargeted"))}
    lines = pg.weakened(then, {"python"}, [kept, moved, retargeted], {"python"})
    for label, why in (("gone", "removed"), ("moved", "target"), ("retargeted", "expected test")):
        if not any(line.startswith(f"{label}: ") and why in line for line in lines):
            failures += fail(f"{label} was not named as {why}: {lines}")
    if any(line.startswith("kept: ") for line in lines):
        failures += fail(f"an unchanged guard was named: {lines}")
    out_of_fast = pg.weakened(unchanged(kept), {"python", "unit"}, [kept], {"python"})
    if not any("unit" in line and "fast" in line for line in out_of_fast):
        failures += fail(f"a suite taken out of the fast set was not named: {out_of_fast}")
    if pg.weakened(unchanged(kept), {"python"}, [kept, guard(label="new")], {"python", "unit"}):
        failures += fail("a new guard or a suite added to the fast set was named as weakening")
    return failures


def case_cold_worker_trees_compile_one_at_a_time_before_the_guards():
    """Four cold trees compiling Kotlin at once ran the compiler out of memory and failed a
    worker's baselines; each tree compiles first, alone, only what the selected suites run."""
    failures = 0
    python_only = [guard()]
    unit = [guard(suite=pg.unit_suite)]
    agent = [guard(suite=pg.agent_suite)]
    functional = [guard(suite=pg.functional_suite)]
    if pg.warm_up_tasks(python_only):
        failures += fail(f"a Python-only run compiles: {pg.warm_up_tasks(python_only)}")
    for selected, task in ((unit, ":plugin:testClasses"), (agent, ":agent:testClasses"),
                           (functional, ":plugin:functionalTestClasses")):
        if task not in pg.warm_up_tasks(selected):
            failures += fail(f"{selected[0][4].__name__} does not compile {task} first")
    if ":plugin:functionalTestClasses" in pg.warm_up_tasks(unit):
        failures += fail("a unit-only run compiles the functional sources")
    return failures


def case_a_tree_that_does_not_compile_names_the_compiler_errors():
    """The refusal used to carry only Gradle's last line, which names no file."""
    failures = 0
    kotlin = ("e: file:///w/plugin/src/functionalTest/kotlin/A.kt:12:5 Unresolved reference 'x'.\n"
              "\nFAILURE: Build failed with an exception.\n\n* What went wrong:\n"
              "Execution failed for task ':plugin:compileFunctionalTestKotlin'.\n"
              "> Compilation error. See log for more details\n\nBUILD FAILED in 1m 3s\n")
    java = "/w/agent/src/main/java/B.java:7: error: cannot find symbol\nBUILD FAILED in 9s\n"
    plain = "FAILURE: Build failed with an exception.\n\n* What went wrong:\nTask 'x' not found.\n\nBUILD FAILED in 2s\n"
    for output, expected in ((kotlin, "A.kt:12:5 Unresolved reference"), (java, "B.java:7: error: cannot find symbol"),
                             (plain, "Task 'x' not found.")):
        found = pg.compile_errors(output)
        if not any(expected in line for line in found):
            failures += fail(f"expected a line with {expected!r}, got {found}")
    if pg.compile_errors("e: one\n" * 30) != ["e: one"] * 10:
        failures += fail("the error lines are not capped at ten")
    return failures


def case_every_case_in_this_file_is_registered():
    import re
    source = open(__file__, encoding="utf-8").read()
    defined = set(re.findall(r"^def (case_\w+)", source, re.M))
    orphans = sorted(defined - {c.__name__ for c in CASES})
    return fail(f"defined and never registered: {', '.join(orphans)}") if orphans else 0


def _functional_tree(tmp, files):
    for name, body in files.items():
        path = os.path.join(tmp, "plugin/src/functionalTest/kotlin/dev", name)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w") as f:
            f.write(body)


def case_a_functional_guard_runs_the_one_class_that_holds_its_test():
    """The class, so the guard's test runs with its neighbours and nothing else."""
    import tempfile
    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        _functional_tree(tmp, {
            "AlphaFunctionalTest.kt": "class AlphaFunctionalTest {\n  @Test fun `a change selects its tests`() {}\n"
                                      "  @Test fun `a change selects its tests, twice`() {}\n}\n",
            "BetaFunctionalTest.kt": "class BetaFunctionalTest {\n  @Test fun `a refusal names itself`() {}\n}\n",
            "GammaFunctionalTest.kt": "class GammaFunctionalTest {\n  @Test fun `a refusal names itself again`() {}\n}\n",
            "WiringTest.kt": "class WiringTest {\n  @Test fun `only the wiring knows this`() {}\n}\n",
        })
        expected = {
            "a change selects its tests": "AlphaFunctionalTest",
            "a refusal names itself": None,  # in two classes
            "nothing has this name": None,
            "only the wiring knows this": None,  # the suite's own filter never runs WiringTest
            "": None,
            None: None,
        }
        for expect, cls in expected.items():
            got = pg.functional_class(expect, tmp)
            if got != cls:
                failures += fail(f"`{expect}` resolved to {got}, not {cls}")
    return failures


def _runs_whole(mutations, checkout):
    """The functional and Maven guards of `mutations` that would run their whole class or suite: no
    one class under `checkout` holds a test whose name contains their `expect`."""
    sources = {pg.functional_suite: pg.FUNCTIONAL_SOURCES, pg.maven_suite: pg.MAVEN_SOURCES}
    return [m[0] for m in mutations if m[4] in sources
            and pg.functional_class(m[5], checkout, sources[m[4]]) is None]


def case_every_functional_and_maven_guard_runs_only_its_named_tests():
    """A guard that runs its whole class costs every test the class gains, and the shards'
    projections drift with it; a renamed test would also slow its guard back to the whole suite,
    unseen."""
    failures = 0
    whole = _runs_whole(pg.MUTATIONS, pg.yoriwake_checkout.root())
    if whole:
        failures += fail(f"no one class holds the test named by: {', '.join(whole)}")
    with tempfile.TemporaryDirectory() as tmp:
        _functional_tree(tmp, {"AlphaFunctionalTest.kt": "class AlphaFunctionalTest {\n"
                                                         "  @Test fun `a change selects its tests`() {}\n}\n"})
        mutations = [guard(suite=pg.functional_suite, label="named", expect="a change selects"),
                     guard(suite=pg.functional_suite, label="renamed", expect="a change picks")]
        if _runs_whole(mutations, tmp) != ["renamed"]:
            failures += fail(f"a guard whose test is gone was not named: {_runs_whole(mutations, tmp)}")
    return failures


def _maven_classes_the_whole_suite_misses(root):
    """The Maven test classes under `root` outside the break package that the whole-suite run's
    `--tests` filters match none of, read off the arguments `maven_suite` hands Gradle."""
    import fnmatch
    import re
    passed = []
    saved = pg.gradle
    pg.gradle = lambda args: passed.append(args) or subprocess.CompletedProcess(args, 0, "", "")
    try:
        pg.maven_suite()
    finally:
        pg.gradle = saved
    filters = [a for i, a in enumerate(passed[0]) if i > 0 and passed[0][i - 1] == "--tests"]
    missed = []
    sources = os.path.join(root, pg.MAVEN_SOURCES)
    for directory, _dirs, files in os.walk(sources):
        if "breaking" in os.path.relpath(directory, sources).split(os.sep):
            continue
        for name in files:
            if not name.endswith(".kt"):
                continue
            with open(os.path.join(directory, name), encoding="utf-8") as f:
                text = f.read()
            if not re.search(r"@(Test|ParameterizedTest)\b", text):
                continue
            package = re.search(r"^package ([\w.]+)", text, re.M)
            qualified = f"{package.group(1)}.{name[:-3]}" if package else name[:-3]
            if not any(fnmatch.fnmatchcase(qualified, pattern) for pattern in filters):
                missed.append(name[:-3])
    return sorted(missed)


def case_the_maven_whole_suite_runs_every_maven_class():
    """A hand-kept list of classes went stale: three classes were added after it and never ran."""
    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        tree = os.path.join(tmp, pg.MAVEN_SOURCES, "kotlin", "dev")
        os.makedirs(os.path.join(tree, "breaking"))
        with open(os.path.join(tree, "ScopeFunctionalTest.kt"), "w") as f:
            f.write("package dev\nclass ScopeFunctionalTest {\n  @Test fun `a scope`() {}\n}\n")
        with open(os.path.join(tree, "breaking", "SomeAttempt.kt"), "w") as f:
            f.write("package dev.breaking\nclass SomeAttempt {\n  @Test fun `an attempt`() {}\n}\n")
        missed = _maven_classes_the_whole_suite_misses(tmp)
        if missed:
            failures += fail(f"the whole Maven suite does not run {', '.join(missed)}")
    missed = _maven_classes_the_whole_suite_misses(pg.yoriwake_checkout.root())
    if missed:
        failures += fail(f"the whole Maven suite does not run {', '.join(missed)} of this checkout")
    return failures


def case_no_testkit_suite_runs_in_the_parallel_phase():
    """TestKit builds failed unmutated under load; only the alone phase keeps load away from them."""
    failures = 0
    for name in ("functional", "configuration guards", "break"):
        if name not in pg.EXCLUSIVE_SUITES:
            failures += fail(f"{name} starts TestKit builds and runs in the parallel phase")
        if name in pg.UNLOCKED_SUITES:
            failures += fail(f"{name} starts TestKit builds and runs without the Gradle lock")
    return failures


def case_the_maven_break_suite_runs_under_the_lock_as_the_maven_suite_does():
    """Real Maven and no TestKit build: locked beside the other suites, not held for the alone phase."""
    failures = 0
    for name in ("maven", "maven break"):
        if not pg._locked(name):
            failures += fail(f"{name} runs Gradle without the lock")
        if name in pg.EXCLUSIVE_SUITES:
            failures += fail(f"{name} starts no TestKit build and waits for the alone phase")
    return failures


def case_a_gradle_suite_not_named_safe_runs_under_the_lock():
    failures = 0
    for name in pg.GRADLE_SUITES:
        if pg._locked(name) == (name in pg.UNLOCKED_SUITES):
            failures += fail(f"{name}: locked={pg._locked(name)} disagrees with UNLOCKED_SUITES")
    if pg._locked("python"):
        failures += fail("a suite that starts no Gradle build is locked")
    if not pg.UNLOCKED_SUITES <= pg.GRADLE_SUITES:
        failures += fail("UNLOCKED_SUITES names something that is not a Gradle suite")
    return failures


def case_functional_guards_pair_by_class_and_configuration_guards_run_alone():
    """A class baselined in two trees at once is the load the pairs were not shown to survive."""
    failures = 0
    exclusive = [i for i, m in enumerate(pg.MUTATIONS) if pg.SUITE_NAMES[m[4]] in pg.EXCLUSIVE_SUITES]
    groups, serial = pg._alone_bins(exclusive, 2)
    seen = set()
    for indices in groups:
        classes = {pg._class_of(pg.MUTATIONS[i][4], pg.MUTATIONS[i][5]) for i in indices}
        if len(classes) != 1:
            failures += fail(f"one task holds {sorted(map(str, classes))}")
        if classes & seen:
            failures += fail(f"{classes} is split across tasks, so two trees could baseline it")
        seen |= classes
        if any(pg.MUTATIONS[i][4] is not pg.functional_suite for i in indices):
            failures += fail("a guard is paired but is not a functional guard")
    weights = [pg._class_weight("functional", pg._class_of(pg.MUTATIONS[g[0]][4], pg.MUTATIONS[g[0]][5]),
                                len(g)) for g in groups]
    if weights != sorted(weights, reverse=True):
        failures += fail("the classes are not handed out heaviest first")
    # Guard count and cost disagree: the heavier class goes first though it has fewer guards.
    light = [i for i in exclusive if pg._class_of(pg.MUTATIONS[i][4], pg.MUTATIONS[i][5])
             == "DecisionRulesFunctionalTest"][:3]
    heavy = [i for i in exclusive if pg._class_of(pg.MUTATIONS[i][4], pg.MUTATIONS[i][5])
             == "MapDatingFunctionalTest"][:1]
    if len(light) == 3 and heavy and pg._alone_bins(light + heavy, 2)[0] != [heavy, light]:
        failures += fail("a class with more guards went before a heavier one")
    if sorted(i for g in groups for i in g) != sorted(i for i in exclusive if i not in serial):
        failures += fail("a guard of the alone phase is in no task and not run one at a time")
    if any(pg.MUTATIONS[i][4] is pg.functional_suite for i in serial):
        failures += fail("a functional guard runs one at a time though pairs are on")
    single, rest = pg._alone_bins(exclusive, 1)
    if single or rest != exclusive:
        failures += fail("one slot did not leave the order as it was")
    return failures

def case_guard_names_its_own_guards_and_refuses_an_unknown_one():
    """A task proves its own guards by label; a mistyped label must not prove nothing quietly."""
    import contextlib, io
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        code = pg.main(["--guard", "no guard is called this"])
    if code != 1 or "no guard is labelled" not in out.getvalue():
        return fail(f"an unknown --guard label did not refuse: {code} {out.getvalue()!r}")
    return 0


def _run(argv):
    """`main(argv)`'s exit code and what it printed."""
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        code = pg.main(argv)
    return code, out.getvalue()


def _results(path):
    with open(path) as f:
        return json.load(f)


def _shard_of(label, mutations, count=8):
    """The shard, 1 to `count`, the partition puts a guard in."""
    shards, _loads = pg.partition(mutations, count)
    return next(i + 1 for i, shard in enumerate(shards) if any(m[0] == label for m in shard))


def case_every_guard_lands_in_exactly_one_shard_and_always_the_same_one():
    """A guard in no shard is proved nowhere while every shard is green."""
    failures = 0
    shards = [pg.in_shard(pg.MUTATIONS, (index, 8)) for index in range(1, 9)]
    labels = [m[0] for shard in shards for m in shard]
    if sorted(labels) != sorted(m[0] for m in pg.MUTATIONS):
        failures += fail("the 8 shards are not a partition of the table")
    if [pg.in_shard(pg.MUTATIONS, (index, 8)) for index in range(1, 9)] != shards:
        failures += fail("the same table split twice gave different shards")
    # Fewer guards than shards: the rest hold none, and none is lost.
    three = pg.MUTATIONS[:3]
    split, _loads = pg.partition(three, 8)
    if sorted(len(shard) for shard in split) != [0] * 5 + [1] * 3:
        failures += fail(f"3 guards in 8 shards were split {[len(shard) for shard in split]}")
    return failures


# How many of a suite's builds a shard runs at once, as the partition weighs them.
SLOTS = {"functional": pg.ALONE_SLOTS, "maven": 1}


def _heavy_classes(mutations):
    """Each functional and Maven class of `mutations`, as (suite, class), with its guards."""
    classes = {}
    for m in mutations:
        if pg.SUITE_NAMES.get(m[4]) in SLOTS:
            classes.setdefault((pg.SUITE_NAMES[m[4]], pg._class_of(m[4], m[5])), []).append(m)
    return classes


def case_two_chunks_of_a_class_on_one_shard_are_one_cut():
    """A shard that takes two chunks of a split class runs them as one run of its table order."""
    failures = 0
    classes = _heavy_classes(pg.MUTATIONS)
    for count in range(2, 13):
        split, _loads = pg.partition(pg.MUTATIONS, count)
        held = sorted(m[0] for shard in split for m in shard)
        if held != sorted(m[0] for m in pg.MUTATIONS):
            failures += fail(f"{count} shards do not hold every guard exactly once")
        for (suite, name), guards in classes.items():
            labels = [m[0] for m in guards]
            for shard in split:
                part = [m[0] for m in shard if m[0] in labels]
                if part and labels[labels.index(part[0]):labels.index(part[0]) + len(part)] != part:
                    failures += fail(f"{suite}/{name} is not a cut on one of {count} shards: {part}")
    return failures


def case_a_class_stays_whole_unless_it_outweighs_a_shard_share():
    """A class's guards stay on one shard unless the class outweighs a shard's share; then they are
    cut into runs of the table's order, not dealt out."""
    failures = 0
    split, _loads = pg.partition(pg.MUTATIONS, 8)
    classes = _heavy_classes(pg.MUTATIONS)
    totals = {}
    for (suite, name), guards in classes.items():
        totals[suite] = totals.get(suite, 0) + pg._class_weight(suite, name, len(guards))
    for (suite, name), guards in classes.items():
        weight = pg._class_weight(suite, name, len(guards))
        share = totals[suite] / (8 * SLOTS[suite])
        allowed = 1 if weight <= share else min(len(guards), math.ceil(weight / share))
        labels = [m[0] for m in guards]
        held = [[m[0] for m in shard if m[0] in labels] for shard in split]
        held = [h for h in held if h]
        if len(held) > allowed:
            failures += fail(f"{suite}/{name} ({len(guards)} guards, {weight}s) spread over "
                             f"{len(held)} shards; at most {allowed} for a {share:.0f}s share")
        # Each shard's part of a class is a run of its guards in table order, not every other one.
        for part in held:
            first = labels.index(part[0])
            if labels[first:first + len(part)] != part:
                failures += fail(f"{suite}/{name} was dealt out, not cut: {part}")
    for name in ("JvmSharedFunctionalTest",):
        if sum(any(pg._class_of(m[4], m[5]) == name and m[4] is pg.functional_suite for m in shard)
               for shard in split) < 2:
            failures += fail(f"{name}, heavier than a tree's share, was not split")
    maven = [m for m in pg.MUTATIONS if m[4] is pg.maven_suite
             and pg._class_of(m[4], m[5]) == "SelectionFunctionalTest"]
    if sum(any(m in shard for m in maven) for shard in split) < 2:
        failures += fail("the Maven SelectionFunctionalTest, heavier than a shard's share, was not split")
    # One class alone outweighs every share: each guard its own chunk, and no endless split.
    functional = [m for m in pg.MUTATIONS if m[4] is pg.functional_suite]
    ten = [m for m in functional if pg._class_of(m[4], m[5]) == "JvmSharedFunctionalTest"][:10]
    split, _loads = pg.partition(ten, 8)
    if sum(map(len, split)) != len(ten) or max(map(len, split)) > 2:
        failures += fail(f"one class of {len(ten)} guards was split {[len(s) for s in split]}")
    return failures


def case_each_class_guard_pays_its_own_baseline():
    """A guard of a class is baselined on its own named tests, so no baseline is shared and any cut
    of a class is free; a guard whose class does not resolve runs the whole suite, baselined once."""
    failures = 0
    for guards in (1, 3, pg.JOBS, pg.JOBS + 3):
        for suite in ("maven", "functional"):
            baseline, guard = pg.class_cost(suite, "SelectionFunctionalTest")
            weight = pg._class_weight(suite, "SelectionFunctionalTest", guards)
            if weight != guards * (baseline + guard):
                failures += fail(f"{guards} {suite} guards of one class weigh {weight}s, "
                                 f"not each guard and its baseline, {guards * (baseline + guard)}s")
            baseline, guard = pg.class_cost(suite, None)
            if pg._class_weight(suite, None, guards) != baseline + guards * guard:
                failures += fail(f"{guards} {suite} guards of no class do not weigh one whole-suite baseline")
    return failures


def _indices(suite, count, test_class=None):
    """The `MUTATIONS` indices of the first `count` guards of `suite`, of one class when named."""
    found = [i for i, m in enumerate(pg.MUTATIONS) if m[4] is suite
             and (test_class is None or pg._class_of(m[4], m[5]) == test_class)]
    assert len(found) >= count, (suite.__name__, test_class, len(found))
    return found[:count]


def _by_cost(indices):
    """`indices` as the parallel phase orders them: costliest suite first."""
    cost = {fn: c for _n, fn, c in pg.SUITES}
    return sorted(indices, key=lambda i: -cost[pg.MUTATIONS[i][4]])


def case_the_maven_guards_run_in_one_lane_grouped_by_class():
    """Each worker baselines a class once, so a class's guards spread over workers paid a baseline
    in each; one lane for all of a shard's Maven guards pays one per class."""
    failures = 0
    classes = sorted({pg._class_of(m[4], m[5]) for m in pg.MUTATIONS if m[4] is pg.maven_suite})
    first, second = classes[0], classes[1]
    a = _indices(pg.maven_suite, 3, first)
    b = _indices(pg.maven_suite, 1, second)
    unit, replay = _indices(pg.unit_suite, 2), _indices(pg.replay_suite, 1)
    # Mixed so the classes' guards are not adjacent before the lane groups them.
    chosen = _by_cost([a[0], b[0], a[1], unit[0], replay[0], a[2], unit[1]])
    lane, rest = pg.parallel_tasks(chosen, 4)
    if sorted(lane) != sorted(a + b) or lane not in (a + b, b + a):
        failures += fail(f"the lane is {lane}, not {first}'s {a} and {second}'s {b}, each class together")
    # Three workers beside the lane: one Gradle guard, then two others.
    if rest != [replay[0]] + unit:
        failures += fail(f"the other guards are {rest}, not one Gradle guard then the others: {replay + unit}")
    # Two workers beside the lane: each Gradle guard, then one other.
    replays = _indices(pg.replay_suite, 2)
    _lane, rest = pg.parallel_tasks(_by_cost(a[:1] + replays + unit), 3)
    if rest != [replays[0], unit[0], replays[1], unit[1]]:
        failures += fail(f"two workers beside the lane were handed {rest}, not each Gradle guard then one other")
    others = _by_cost(unit + replay)
    if pg.parallel_tasks(others, 4) != ([], [replay[0]] + unit):
        failures += fail(f"without a Maven guard: {pg.parallel_tasks(others, 4)}")
    lane, rest = pg.parallel_tasks(_by_cost(a + b), 4)
    if rest or sorted(lane) != sorted(a + b):
        failures += fail(f"only Maven guards: lane {lane}, others {rest}")
    lane, rest = pg.parallel_tasks(chosen, 1)
    if lane or rest[:4] not in (a + b, b + a) or sorted(rest[4:]) != sorted(unit + replay):
        failures += fail(f"one worker: lane {lane}, list {rest}")
    return failures


def case_a_worker_baselines_each_guards_own_tests_once():
    """Each guard of a class runs its own named tests, so it is baselined on them: another guard's
    baseline of the class proved other tests. The same tests are baselined once per worker."""
    failures = 0
    saved = (pg._class_of, dict(pg._WORKER))
    calls = []
    with _synthetic([("a1", True), ("b1", True), ("a2", True), ("a3", True)]):
        def quick(test_class=None, expect=None):
            calls.append((test_class, expect))
            return []
        pg.SUITES.append(("synthetic lane", quick, 0))
        pg.SUITE_NAMES[quick] = "synthetic lane"
        expects = {"a1": "one", "a2": "two", "a3": "one", "b1": "one"}
        pg.MUTATIONS[:] = [(m[0], m[1], m[2], m[3], quick, expects[m[0]]) for m in pg.MUTATIONS]
        classes = {"a1": "ATest", "a2": "ATest", "a3": "ATest", "b1": "BTest"}
        pg._WORKER.update(baselined={}, gradle_lock=contextlib.nullcontext())
        try:
            timings = {}
            for i, m in enumerate(pg.MUTATIONS):
                pg._class_of = lambda suite, expect, name=classes[m[0]]: name
                timings[m[0]] = pg._prove_in_worker(i)[3]
        finally:
            pg._class_of = saved[0]
            pg._WORKER.clear()
            pg._WORKER.update(saved[1])
    paid = sorted(label for label, t in timings.items() if t["baseline_seconds"] is not None)
    if paid != ["a1", "a2", "b1"]:
        failures += fail(f"baselines paid by {paid}, not by each guard whose tests had none: {timings}")
    if any(t["seconds"] is None for t in timings.values()):
        failures += fail(f"a guard's run has no seconds: {timings}")
    # Baseline and mutated run alike are filtered to the guard's own tests.
    want = [("ATest", "one")] * 2 + [("BTest", "one")] * 2 + [("ATest", "two")] * 2 + [("ATest", "one")]
    if calls != want:
        failures += fail(f"the suite was run as {calls}, not {want}")
    return failures


def case_one_tree_baselines_each_guards_own_tests_once():
    """`--jobs 1` keeps its own baselines, by the same rule as a worker's."""
    failures = 0
    saved = pg._class_of
    calls = []
    with _synthetic([("a1", True), ("a2", True), ("a3", True)]) as tmp:
        def recording(test_class=None, expect=None):
            calls.append((test_class, expect))
            for name in ("a1", "a2", "a3"):
                with open(os.path.join(tmp, f"{name}.txt")) as f:
                    if "mutated" in f.read():
                        return [f"FAIL: ATest > {expect} FAILED"]
            return []
        pg.SUITES.append(("synthetic recording", recording, 0))
        pg.SUITE_NAMES[recording] = "synthetic recording"
        expects = {"a1": "one", "a2": "two", "a3": "one"}
        pg.MUTATIONS[:] = [(m[0], m[1], m[2], m[3], recording, expects[m[0]]) for m in pg.MUTATIONS]
        pg._class_of = lambda suite, expect: "ATest"
        try:
            code, out = _run(["--guard", "a1", "--guard", "a2", "--guard", "a3", "--jobs", "1"])
        finally:
            pg._class_of = saved
    # The whole suite's baseline first (it is no class suite), then each guard's tests.
    want = [(None, None), ("ATest", "one"), ("ATest", "one"), ("ATest", "two"), ("ATest", "two"),
            ("ATest", "one")]
    if code != 0 or calls != want:
        failures += fail(f"one tree ran {calls}, not {want}: {code} {out!r}")
    return failures


def case_a_class_guard_runs_only_the_tests_its_expect_names():
    """The class's other tests decide nothing about the guard; without a class the suite runs."""
    failures = 0
    saved = pg.gradle
    seen = []
    pg.gradle = lambda args: seen.append(args) or subprocess.CompletedProcess(args, 0, "", "")
    try:
        pg.maven_suite("SelectionFunctionalTest", "a sibling's classes keep its edits")
        pg.functional_suite("MapDatingFunctionalTest", "--tests over a filter, as it was")
        pg.functional_suite()
    finally:
        pg.gradle = saved
    filters = [args[args.index("--tests") + 1] for args in seen]
    want = ["*.SelectionFunctionalTest.*a sibling's classes keep its edits*",
            "*.MapDatingFunctionalTest.*--tests over a filter, as it was*",
            pg.FUNCTIONAL_FILTER]
    if filters != want:
        failures += fail(f"the filters were {filters}, not {want}")
    if seen[0][0] != ":maven:functionalTest" or seen[1][:3] != pg.FUNCTIONAL_FLOOR_ONLY:
        failures += fail(f"the filtered runs were not their suites' tasks: {seen[:2]}")
    return failures


def case_a_filter_that_runs_no_test_refuses_the_guard():
    """Gradle fails a run whose filter matched nothing, naming no test: the guard's baseline fails,
    and the guard is refused as failing unmutated, never proved."""
    saved = (pg.gradle, pg._class_of, dict(pg._WORKER))
    output = ("> No tests found for given includes: [*.SelectionFunctionalTest.*renamed*]"
              "(--tests filter)\n\nBUILD FAILED in 9s\n")
    with _synthetic([("renamed", True)]):
        pg.MUTATIONS[:] = [(m[0], m[1], m[2], m[3], pg.maven_suite, "renamed") for m in pg.MUTATIONS]
        pg.gradle = lambda args: subprocess.CompletedProcess(args, 1, output, "")
        pg._class_of = lambda suite, expect: "SelectionFunctionalTest"
        pg._WORKER.update(baselined={}, gradle_lock=contextlib.nullcontext())
        try:
            index, fired, refusal, _timing = pg._prove_in_worker(0)
            failed = pg._report(index, fired, refusal)
        finally:
            pg.gradle, pg._class_of = saved[0], saved[1]
            pg._WORKER.clear()
            pg._WORKER.update(saved[2])
            pg._RUN["outcomes"].pop("renamed", None)
            pg._RUN["timings"].pop("renamed", None)
    if fired is not None or not refusal or "fails unmutated" not in refusal or failed != 1:
        return fail(f"a filter that ran no test gave fired={fired}, refusal={refusal}, failed={failed}")
    return 0


def case_every_class_guard_has_a_cost_and_an_unknown_one_weighs_its_suites_median():
    failures = 0
    for (suite, name), guards in _heavy_classes(pg.MUTATIONS).items():
        if f"{suite}/{name}" not in pg.class_costs():
            failures += fail(f"{guards[0][0]}'s class {suite}/{name} has no cost in the table")
    for suite in SLOTS:
        known = [cost for key, cost in pg.class_costs().items() if key.startswith(suite + "/")]
        baselines = sorted(c["baseline"] for c in known)
        guards = sorted(c["guard"] for c in known)
        median = (baselines[len(baselines) // 2], guards[len(guards) // 2])
        if pg.class_cost(suite, "NotYetMeasuredFunctionalTest") != median:
            failures += fail(f"a {suite} class missing from the table costs "
                             f"{pg.class_cost(suite, 'NotYetMeasuredFunctionalTest')}, not the median {median}")
        if pg.class_cost(suite, None) != (sum(baselines), sum(guards)):
            failures += fail(f"a {suite} guard whose class does not resolve does not cost the whole suite")
    # The two suites share class names; each is weighed by its own entry.
    for suite in SLOTS:
        cost = pg.class_costs()[f"{suite}/SelectionFunctionalTest"]
        if pg.class_cost(suite, "SelectionFunctionalTest") != (cost["baseline"], cost["guard"]):
            failures += fail(f"{suite}/SelectionFunctionalTest is not weighed by its own entry")
    if pg.class_costs()["functional/SelectionFunctionalTest"] == pg.class_costs()["maven/SelectionFunctionalTest"]:
        failures += fail("the table cannot tell the two SelectionFunctionalTest classes apart")
    return failures


def case_the_shards_projected_walls_are_within_one_heavy_unit_of_each_other():
    """Functional and Maven time add up in a shard, so they are balanced together, on its wall."""
    failures = 0
    for run in range(2):
        split, loads = pg.partition(pg.MUTATIONS, 8)
        walls = [load["wall"] for load in loads]
        heaviest = max(pg._class_weight(suite, name, len(guards)) / SLOTS[suite]
                       for shard in split for (suite, name), guards in _heavy_classes(shard).items())
        if max(walls) - min(walls) > heaviest:
            failures += fail(f"the 8 shards' projected walls {[round(w) for w in walls]} differ by "
                             f"more than one heavy unit ({heaviest:.0f}s)")
        for shard, load in zip(split, loads):
            parts = load["functional"] / pg.ALONE_SLOTS + load["maven"]
            if abs(parts - load["wall"]) > 1:
                failures += fail(f"a shard's wall {load['wall']:.0f}s is not its parts' {parts:.0f}s")
    # Without Maven guards, the other guards still do not move a functional guard.
    no_maven = [m for m in pg.MUTATIONS if m[4] is not pg.maven_suite]
    functional = [m for m in no_maven if m[4] is pg.functional_suite]
    alone, _loads = pg.partition(functional, 8)
    beside, _loads = pg.partition(no_maven, 8)
    if [[m for m in shard if m[4] is pg.functional_suite] for shard in beside] != alone:
        failures += fail("the other guards moved a functional guard to another shard")
    return failures


def _over_target(mutations, count=8):
    """Each job whose worst projected shard, with `OVER_PROJECTION` on top, passes `TARGET` or the
    job's limit: an every-guard shard, and a pull request's fast step followed by every guard."""
    problems = []
    fast = [m for m in mutations if pg.SUITE_NAMES[m[4]] in pg.FAST_SUITES]
    every, _loads = pg.partition(mutations, count)
    quick, _loads = pg.partition(fast, count)
    jobs = {"full-shard": [[pg.step_projection(shard, "full")] for shard in every],
            "fast-shard": [[pg.step_projection(a, "fast"), pg.step_projection(b, "full", first=False)]
                           for a, b in zip(quick, every)]}
    for job, shards in jobs.items():
        worst = max(range(count), key=lambda i: pg.job_projection(shards[i]))
        seconds = pg.job_projection(shards[worst]) * (1 + pg.OVER_PROJECTION)
        limit = pg.job_limit(job)
        for name, ceiling in (("the target", pg.TARGET), (f"{job}'s limit", (limit or math.inf) * 60)):
            if seconds > ceiling:
                problems.append(f"{job}'s shard {worst + 1}/{count} projects {seconds / 60:.0f} min with "
                                f"{pg.OVER_PROJECTION:.0%} over, past {name} of {ceiling / 60:.0f} min")
    return problems


def case_the_worst_shard_stays_under_the_target_with_room_to_run_over():
    """A change that adds enough guards, or slow enough ones, to push a shard past the target is
    told on its own pull request, not by a release check cut at the limit."""
    failures = 0
    problems = _over_target(pg.MUTATIONS)
    if problems:
        failures += fail("; ".join(problems))
    table = dict(pg._cost_table())
    slow = {key: {"baseline": 3600, "guard": 3600} for key in table["classes"]}
    with _cost_table({**table, "classes": slow}):
        problems = _over_target(pg.MUTATIONS)
    if not any("full-shard's shard" in p and "past the target" in p for p in problems):
        failures += fail(f"guards an hour each were not refused: {problems}")
    return failures


@contextlib.contextmanager
def _cost_table(table):
    """`table` as the cost table while the block runs, the checked-in one after."""
    saved = dict(pg._TABLE), dict(pg._COSTS)
    pg._TABLE.clear(), pg._COSTS.clear()
    pg._TABLE.update(table)
    try:
        yield
    finally:
        pg._TABLE.clear(), pg._COSTS.clear()
        pg._TABLE.update(saved[0]), pg._COSTS.update(saved[1])


def _of(suite, count, test_class=None):
    """The first `count` guards of `suite`, of one class when it is named."""
    found = [m for m in pg.MUTATIONS if m[4] is suite
             and (test_class is None or pg._class_of(m[4], m[5]) == test_class)]
    assert len(found) >= count, (suite.__name__, test_class, len(found))
    return found[:count]


def case_a_step_is_projected_as_the_phases_it_runs():
    """The parallel phase is the Gradle lock's queue unless the unlocked work outlasts it spread
    over the workers; the configuration guards and the break suite run alone; only a cold step
    compiles."""
    failures = 0
    table = {"classes": {"maven/SelectionFunctionalTest": {"baseline": 100, "guard": 50}},
             "suites": {"full/unit": {"baseline": 40, "guard": 30}, "full/configuration guards":
                        {"baseline": 20, "guard": 10}, "full/break": {"baseline": 60, "guard": 40},
                        "fast/unit": {"baseline": 90, "guard": 70}},
             "warm_up": {"fast": 180}}
    with _cost_table(table):
        maven = _of(pg.maven_suite, 3, "SelectionFunctionalTest")
        step = pg.step_projection(maven, "full", first=False)
        if step["parallel"] != pg._class_weight("maven", "SelectionFunctionalTest", 3) or step["total"] != step["parallel"]:
            failures += fail(f"locked Maven guards alone: {step}")
        unit = _of(pg.unit_suite, 20)
        step = pg.step_projection(maven[:1] + unit, "full", first=False)
        unlocked = pg.JOBS * 40 + 20 * 30
        if step["parallel"] != (150 + unlocked) / pg.JOBS:
            failures += fail(f"unlocked work that outlasts the lock's queue: {step['parallel']} for {unlocked}s unlocked")
        if pg.step_projection(unit, "fast", first=False)["parallel"] != (pg.JOBS * 90 + 20 * 70) / pg.JOBS:
            failures += fail("a fast step's suite is not weighed by its fast-mode figures")
        alone = _of(pg.guard_suite, 2) + _of(pg.break_suite, 1)
        step = pg.step_projection(alone, "full", first=False)
        if step["serial"] != 20 + 2 * 10 + 60 + 40 or step["parallel"] != 0:
            failures += fail(f"configuration and break guards are not in the serial phase: {step}")
        if pg.step_projection(unit, "fast")["warm_up"] != 180 or pg.step_projection(unit, "fast", first=False)["warm_up"]:
            failures += fail("only a step that starts cold pays the warm-up")
        if pg.step_projection(unit, "full")["warm_up"] != 180:
            failures += fail("a mode without a measured warm-up does not take the other mode's")
    with _cost_table({"classes": {}}):
        registry = {n: c for n, _fn, c in pg.SUITES}
        if pg.suite_cost("agent", "full") != (registry["agent"], registry["agent"]) or pg.warm_up_cost("full") != 0:
            failures += fail("a table without suites or warm-up does not fall back to the registry")
    with _cost_table({"classes": {}, "suites": {"full/agent": {"baseline": 3, "guard": 4}}}):
        if pg.suite_cost("agent", "fast") != (3, 4):
            failures += fail("a suite measured in one mode is not used for the other")
    return failures


def case_every_shards_job_is_its_steps_plus_setup_and_the_limit_is_the_workflows():
    failures = 0
    split, _loads = pg.partition(pg.MUTATIONS, 8)
    lines = pg.shard_projections(pg.MUTATIONS, 8, job="full-shard")
    limit = pg.job_limit("full-shard")
    if not limit or not pg.job_limit("fast-shard") or pg.job_limit("no-such-job") is not None:
        failures += fail(f"the shard jobs' limits: {limit}, {pg.job_limit('fast-shard')}")
    for shard, line in zip(split, lines):
        step = pg.step_projection(shard, "full")
        if abs(step["total"] - sum(step[p] for p in ("warm_up", "parallel", "functional", "serial"))) > 1e-6:
            failures += fail(f"a step is not the sum of its phases: {step}")
        minutes = pg.job_projection([step]) / 60
        if f"; job ~{minutes:.0f} min, {limit - minutes:.0f} min under full-shard's {limit}" not in line:
            failures += fail(f"the job and its margin: {line}")
    if any("; job ~" in line for line in pg.shard_projections(pg.MUTATIONS, 8, first=False)):
        failures += fail("a step that is not its job's only one was given a job's margin")
    return failures


def case_a_steps_place_in_its_ci_job_follows_its_flags():
    failures = 0
    def step(**flags):
        return pg._step_on_ci(argparse.Namespace(**{"mode": "full", "changed": None, "guard": None, **flags}))
    expected = {"every guard": ({}, {"mode": "full", "first": True, "job": "full-shard"}),
                "fast mode": ({"mode": "fast"}, {"mode": "fast", "first": True, "job": None}),
                "--changed": ({"changed": "origin/main"}, {"mode": "full", "first": False, "job": None}),
                "--guard": ({"guard": ["x"]}, {"mode": "full", "first": True, "job": None})}
    for name, (flags, want) in expected.items():
        if step(**flags) != want:
            failures += fail(f"{name}: {step(**flags)}")
    return failures


def case_a_job_without_a_limit_is_projected_without_a_margin():
    failures = 0
    lines = pg.shard_projections(pg.MUTATIONS, 8, job="no-such-job")
    if len(lines) != 8 or any("; job ~" not in line or " under " in line for line in lines):
        failures += fail(f"a job with no limit: {lines[:1]}")
    return failures


def case_a_worker_pays_a_class_baseline_once_and_times_each_run():
    """`derive` reads a guard's seconds and its baseline's apart: one baseline per tests per tree."""
    failures = 0
    saved = (pg._class_of, dict(pg._WORKER))
    with _synthetic([("first", True), ("second", True)]):
        def slow(test_class=None, expect=None):
            time.sleep(0.05)
            return []
        pg.SUITES.append(("synthetic slow", slow, 0))
        pg.SUITE_NAMES[slow] = "synthetic slow"
        pg.MUTATIONS[:] = [(m[0], m[1], m[2], m[3], slow, m[5]) for m in pg.MUTATIONS]
        pg._class_of = lambda suite, expect: "SlowTest"
        pg._WORKER.update(baselined={}, gradle_lock=contextlib.nullcontext())
        try:
            timings = [pg._prove_in_worker(i)[3] for i in range(2)]
        finally:
            pg._class_of = saved[0]
            pg._WORKER.clear()
            pg._WORKER.update(saved[1])
    first, second = timings
    if {first["suite_class"], second["suite_class"]} != {"synthetic slow/SlowTest"}:
        failures += fail(f"the guards' suite and class are {first['suite_class']}, {second['suite_class']}")
    if not (first["baseline_seconds"] or 0) >= 0.05 or second["baseline_seconds"] is not None:
        failures += fail(f"the class's baseline was not paid once, by the first guard: {timings}")
    if not all((t["seconds"] or 0) >= 0.05 for t in timings):
        failures += fail(f"a guard's run has no seconds: {timings}")
    return failures


def case_a_wait_for_the_lock_is_not_counted_as_a_guards_seconds():
    """Four workers share one Gradle lock; a guard waiting for it did not cost those seconds."""
    import threading
    saved = dict(pg._WORKER)

    def quick():
        return []
    pg.SUITE_NAMES[quick] = "maven"
    lock, held = threading.Lock(), threading.Event()

    def other_worker():
        with lock:
            held.set()
            time.sleep(0.5)
    pg._WORKER.update(gradle_lock=lock)
    try:
        thread = threading.Thread(target=other_worker)
        thread.start()
        held.wait()
        start = time.monotonic()
        _fired, seconds = pg._run_suite(quick)
        waited = time.monotonic() - start
        thread.join()
    finally:
        del pg.SUITE_NAMES[quick]
        pg._WORKER.clear()
        pg._WORKER.update(saved)
    if waited < 0.3 or seconds > 0.2:
        return fail(f"a run that waited {waited:.2f}s for the lock was timed at {seconds}s")
    return 0


def case_a_shard_that_is_not_one_of_n_refuses():
    failures = 0
    for shard in ("0/8", "9/8", "x/8", "8"):
        code, out = _run(["--shard", shard])
        if code != 1 or "--shard" not in out:
            failures += fail(f"--shard {shard} did not refuse naming the flag: {code} {out!r}")
    return failures


@contextlib.contextmanager
def _synthetic(guards):
    """A git tree holding one file per guard, and the harness pointed at it with `guards` as its
    table, each run by a suite that fires when its file holds the mutation, or never."""
    saved = (pg.MUTATIONS[:], pg.SUITES[:], dict(pg.SUITE_NAMES))
    previous = os.environ.get(pg.yoriwake_checkout.SETTING)

    def fires(path):
        def suite():
            with open(pg.source(path)) as f:
                return ["FAIL: synthetic > it fired"] if "mutated" in f.read() else []
        return suite

    def silent():
        return []

    with tempfile.TemporaryDirectory() as tmp:
        subprocess.run(["git", "init", "-q", tmp], check=True)
        table = []
        for label, caught in guards:
            path = f"{label}.txt"
            with open(os.path.join(tmp, path), "w") as f:
                f.write("anchor\n")
            suite = fires(path) if caught else silent
            name = f"synthetic {len(table)}"
            pg.SUITES.append((name, suite, 0))
            pg.SUITE_NAMES[suite] = name
            table.append((label, path, "anchor", "mutated", suite, "synthetic"))
        subprocess.run(["git", "-C", tmp, "add", "-A"], check=True)
        subprocess.run(["git", "-C", tmp, "-c", "user.email=t@t", "-c", "user.name=t",
                        "-c", "commit.gpgsign=false", "commit", "-qm", "base"], check=True)
        pg.MUTATIONS[:] = table
        os.environ[pg.yoriwake_checkout.SETTING] = tmp
        try:
            yield tmp
        finally:
            pg.MUTATIONS[:], pg.SUITES[:] = saved[0], saved[1]
            pg.SUITE_NAMES.clear()
            pg.SUITE_NAMES.update(saved[2])
            if previous is None:
                os.environ.pop(pg.yoriwake_checkout.SETTING, None)
            else:
                os.environ[pg.yoriwake_checkout.SETTING] = previous


def case_an_empty_shard_succeeds_and_says_whose_guards_it_left():
    """Empty is not never-ran: the check needs a result from every shard."""
    failures = 0
    with _synthetic([("alpha", True)]) as tmp:
        result = os.path.join(tmp, "result.json")
        home = _shard_of("alpha", [m for m in pg.MUTATIONS if m[0] == "alpha"])
        other = home % 8 + 1
        # A guard the base does not have is selected by --changed even when nothing else moved.
        for argv in (["--guard", "alpha"], ["--mode", "full", "--changed", "HEAD"]):
            code, out = _run(argv + ["--shard", f"{other}/8", "--results", result, "--jobs", "1"])
            if code != 0:
                failures += fail(f"{argv} on an empty shard exited {code}: {out!r}")
            got = _results(result)
            if got["guards"] != [] or got["selected"] != 1 or got["shard"] != f"{other}/8":
                failures += fail(f"{argv} on an empty shard wrote {got}")
            if f"alpha is shard {home}'s" not in out:
                failures += fail(f"{argv} on an empty shard did not say whose alpha is: {out!r}")
    return failures


def case_a_shard_writes_each_guards_outcome_and_a_silent_one_fails_it():
    failures = 0
    with _synthetic([("caught", True), ("missed", False)]) as tmp:
        result = os.path.join(tmp, "result.json")
        caught = [m for m in pg.MUTATIONS if m[0] == "caught"]
        code, out = _run(["--guard", "caught", "--shard", f"{_shard_of('caught', caught)}/8",
                          "--results", result, "--jobs", "1"])
        got = _results(result)["guards"]
        if code != 0 or [(g["label"], g["outcome"]) for g in got] != [("caught", "fired")]:
            failures += fail(f"a guard that fired in its shard: {code} {got} {out!r}")
        code, out = _run(["--guard", "missed", "--results", result, "--jobs", "1"])
        got = _results(result)["guards"]
        if code != 1 or [(g["label"], g["outcome"]) for g in got] != [("missed", "nothing caught")]:
            failures += fail(f"a guard that did not fire: {code} {got} {out!r}")
    return failures


def case_the_parallel_run_ci_uses_writes_outcomes_and_refusals():
    """CI runs the default worker trees, so `--results` is proved there too: a guard's outcome, and
    a warm-up refusal written against every guard it stopped."""
    failures = 0
    saved = (pg.prepare_workers, pg.warm_up_tasks, pg._warm_up, set(pg.EXCLUSIVE_SUITES))
    with _synthetic([("caught", True)]) as tmp:
        result = os.path.join(tmp, "result.json")
        # One tree, and the synthetic suite in the phase that runs in this process, so no worker
        # process has to import a suite defined here.
        pg.prepare_workers = lambda count: [tmp]
        pg.EXCLUSIVE_SUITES.update(name for name, _fn, _cost in pg.SUITES if name.startswith("synthetic"))
        try:
            code, out = _run(["--guard", "caught", "--results", result])
            got = _results(result)["guards"]
            if code != 0 or [(g["label"], g["outcome"]) for g in got] != [("caught", "fired")]:
                failures += fail(f"a parallel run that fired wrote {code} {got} {out!r}")
            whole = _results(result)
            if got and got[0]["seconds"] is None:
                failures += fail(f"a guard that ran has no seconds: {got}")
            if abs(sum(whole["phases"].values()) - whole["seconds"]) > 1:
                failures += fail(f"the phases {whole['phases']} do not sum to the run's {whole['seconds']}s")
            pg.warm_up_tasks = lambda selected: [":compile"]
            pg._warm_up = lambda trees, tasks: f"{trees[0]} does not compile unmutated: e: boom"
            code, out = _run(["--guard", "caught", "--results", result])
            got = _results(result)
            if code != 1 or [(g["label"], g["outcome"]) for g in got["guards"]] != [("caught", "not proved")] \
                    or "does not compile unmutated" not in (got["refused"] or ""):
                failures += fail(f"a parallel run refused at warm-up wrote {code} {got}")
        finally:
            pg.prepare_workers, pg.warm_up_tasks, pg._warm_up = saved[:3]
            pg.EXCLUSIVE_SUITES.clear()
            pg.EXCLUSIVE_SUITES.update(saved[3])
    return failures


def case_a_refused_run_writes_every_guard_it_held_as_not_proved():
    """A shard that stops before its guards still says which it did not prove, and why."""
    failures = 0
    with _synthetic([("moved", True)]) as tmp:
        with open(os.path.join(tmp, "moved.txt"), "w") as f:
            f.write("the text is gone\n")
        result = os.path.join(tmp, "result.json")
        code, _out = _run(["--guard", "moved", "--results", result, "--jobs", "1"])
        got = _results(result)
        if code != 1 or [(g["label"], g["outcome"]) for g in got["guards"]] != [("moved", "not proved")] \
                or "anchor" not in (got["refused"] or ""):
            failures += fail(f"a refused run wrote {code} {got}")
        if (got["guards"][0]["seconds"], got["guards"][0]["baseline_seconds"]) != (None, None):
            failures += fail(f"a guard refused before its run has seconds: {got['guards'][0]}")
    return failures


def _results_file(directory, test_class, test, message, age=0):
    """The results file a Gradle test task leaves for a class with one failing test, written `age`
    seconds ago."""
    os.makedirs(directory, exist_ok=True)
    path = os.path.join(directory, f"TEST-io.github.zeuspizza.yoriwake.{test_class}.xml")
    escaped = message.replace("&", "&amp;").replace("<", "&lt;").replace('"', "&quot;").replace("\n", "&#10;")
    with open(path, "w", encoding="utf-8") as f:
        f.write(f'<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="{test_class}" tests="2" failures="1">\n'
                f'  <testcase name="one that passes()" classname="io.github.zeuspizza.yoriwake.{test_class}"/>\n'
                f'  <testcase name="{test}()" classname="io.github.zeuspizza.yoriwake.{test_class}">\n'
                f'    <failure message="{escaped}" type="java.lang.AssertionError">at {test_class}.kt:12</failure>\n'
                f'  </testcase>\n</testsuite>\n')
    if age:
        os.utime(path, (time.time() - age, time.time() - age))
    return path


def case_a_baseline_that_fails_prints_why_and_keeps_its_results():
    """A class that failed before any mutation, in one worker of one run, left only its test's
    name. The log now carries the failing test's message and the end of Gradle's output, the
    build's results are kept where a failed shard uploads them, and `--results` keeps one line."""
    failures = 0
    test = "a multithreaded reactor build dates every module"
    message = "\n".join(["java.lang.AssertionError: Expected the char sequence to contain the substring."]
                        + [f"the build printed {i}" for i in range(100)] + ["Substring <module b is dated>"])
    printed = "\n".join([f"> Task :step{i}" for i in range(200)]
                        + [f"ForkingFunctionalTest > {test}() FAILED", "", "BUILD FAILED in 2m 3s"])
    saved = (pg.prepare_workers, pg.warm_up_tasks, pg._class_of, pg.subprocess.run, pg.UNMUTATED_RESULTS,
             set(pg.EXCLUSIVE_SUITES))
    real = pg.subprocess.run

    def build(argv, **kwargs):
        if os.path.basename(argv[0]) not in ("gradlew", "gradlew.bat"):
            return real(argv, **kwargs)
        if ":passes" in argv:
            return subprocess.CompletedProcess(argv, 0, "BUILD SUCCESSFUL in 1s", "")
        # A build takes longer than a file system's clock is coarse.
        time.sleep(0.05)
        results = os.path.join(kwargs["cwd"], "maven", "build", "test-results", "functionalTest")
        _results_file(results, "ForkingFunctionalTest", test, message)
        # The results of a class the build was cut off writing.
        with open(os.path.join(results, "TEST-io.github.zeuspizza.yoriwake.CutShortTest.xml"), "w") as f:
            f.write('<testsuite name="CutShortTest"><testcase name="cut')
        return subprocess.CompletedProcess(argv, 1, printed, "")

    def standing():
        return ["FAIL: synthetic > it fails as it stands"]

    def checked_after_a_build():
        pg.gradle([":passes"])
        return ["FAIL: synthetic > its check after the build fails"]

    with _synthetic([("held", True), ("other", True), ("after", True)]) as tmp:
        # What an earlier build of another task left in the tree: not this baseline's failure.
        stale = _results_file(os.path.join(tmp, "plugin", "build", "test-results", "test"), "OlderTest",
                              "an earlier build's test", "the earlier build's failure", age=3600)
        # Forced: a machine that ignores build/ everywhere would stage nothing.
        subprocess.run(["git", "-C", tmp, "add", "-A", "-f"], check=True)
        subprocess.run(["git", "-C", tmp, "-c", "user.email=t@t", "-c", "user.name=t",
                        "-c", "commit.gpgsign=false", "commit", "-qm", "results"], check=True)
        for suite_name, suite in (("synthetic standing", standing), ("synthetic checked", checked_after_a_build)):
            pg.SUITES.append((suite_name, suite, 0))
            pg.SUITE_NAMES[suite] = suite_name
        pg.MUTATIONS[:] = [("held", "held.txt", "anchor", "mutated", pg.functional_suite, "dates every module"),
                           ("other", "other.txt", "anchor", "mutated", standing, "synthetic"),
                           ("after", "after.txt", "anchor", "mutated", checked_after_a_build, "synthetic")]
        result, kept = os.path.join(tmp, "result.json"), os.path.join(tmp, "kept")
        pg.prepare_workers = lambda count: [tmp]
        pg.warm_up_tasks = lambda selected: []
        pg._class_of = lambda suite, expect: "ForkingFunctionalTest" if suite is pg.functional_suite else None
        pg.subprocess.run = build
        pg.UNMUTATED_RESULTS = kept
        # In the phase that runs in this process, so no worker process imports a suite defined here.
        pg.EXCLUSIVE_SUITES.update(("synthetic standing", "synthetic checked"))
        try:
            code, out = _run(["--guard", "held", "--guard", "other", "--guard", "after", "--results", result])
            got = {g["label"]: (g["outcome"], g["detail"]) for g in _results(result)["guards"]}
            files = sorted(os.path.join(os.path.relpath(root, kept), name)
                           for root, _dirs, names in os.walk(kept) for name in names)
            whole = [open(os.path.join(kept, name), encoding="utf-8").read() for name in files]
            code_one, out_one = _run(["--guard", "held", "--jobs", "1"])
            # Nowhere to keep the results, under a file: the refusal and the message still stand.
            pg.UNMUTATED_RESULTS = os.path.join(result, "kept")
            code_nowhere, out_nowhere = _run(["--guard", "held", "--guard", "other"])
        finally:
            (pg.prepare_workers, pg.warm_up_tasks, pg._class_of, pg.subprocess.run,
             pg.UNMUTATED_RESULTS) = saved[:5]
            pg.EXCLUSIVE_SUITES.clear()
            pg.EXCLUSIVE_SUITES.update(saved[5])
        for how, exit_code, log, refusal in (("in a worker tree", code, out, "fails unmutated"),
                                             ("with --jobs 1", code_one, out_one, "MUST BE 0")):
            if exit_code != 1 or refusal not in log:
                failures += fail(f"{how}, a class failing as it stands was not refused: {exit_code} {log!r}")
            # Both ends of a long message: what was expected is its last line.
            for expected in (f"ForkingFunctionalTest > {test}", "Expected the char sequence to contain",
                             "the build printed 0", "Substring <module b is dated>", "BUILD FAILED in 2m 3s",
                             "> Task :step199", f"kept in {kept}", "CutShortTest.xml cannot be read"):
                if expected not in log:
                    failures += fail(f"{how}, the log of a failed baseline has no {expected!r}")
            for left_out in ("the build printed 50", "> Task :step0\n", "the earlier build's failure"):
                if left_out in log:
                    failures += fail(f"{how}, the log of a failed baseline holds {left_out!r}")
        if code_nowhere != 1 or any(expected not in out_nowhere for expected in (
                "fails unmutated", "Substring <module b is dated>", "could not be kept in",
                "the synthetic standing suite fails")):
            failures += fail(f"results that cannot be kept cost more than a line: {code_nowhere} {out_nowhere!r}")
        # The suite that started no build says nothing of the build before it, and the one whose
        # build passed nothing of that build: what failed was its check.
        if out.count("BUILD FAILED in 2m 3s") != 1 or "BUILD SUCCESSFUL in 1s" in out \
                or [got.get(label, ("", ""))[0] for label in ("other", "after")] != ["not proved"] * 2:
            failures += fail(f"a suite was given the output of a build that did not fail it, or proved: "
                             f"{got} {out!r}")
        outcome, detail = got.get("held", ("", ""))
        if outcome != "not proved" or "fails unmutated" not in detail or "\n" in detail:
            failures += fail(f"--results keeps {outcome!r} with {detail!r}, not the refusal's one line")
        if [name.rsplit(".", 2)[-2] for name in files] != ["CutShortTest", "ForkingFunctionalTest"] \
                or "the build printed 50" not in whole[-1]:
            failures += fail(f"the failed build's results are not kept whole, alone: {files}")
        if f"kept in {os.path.join(kept, os.path.dirname(files[0]))}" not in out if files else True:
            failures += fail(f"the log does not say where the results are kept: {out!r}")
        if not os.path.exists(stale):
            failures += fail("the tree's own results were moved, not copied")
    # Each shard job uploads the directory the script keeps them in: a path that drifted would
    # upload nothing and say nothing.
    repository = os.path.dirname(os.path.dirname(os.path.abspath(pg.__file__)))
    uploaded = f"path: {os.path.relpath(pg.UNMUTATED_RESULTS, repository).replace(os.sep, '/')}/\n"
    with open(pg.GUARDS_WORKFLOW, encoding="utf-8") as handle:
        if handle.read().count(uploaded) != 2:
            failures += fail(f"the two shard jobs do not each upload {uploaded!r}")
    return failures


def case_the_selection_list_is_what_a_changed_run_selects_and_runs_nothing():
    failures = 0
    changed = {"bench/run.py"}
    before = pg._table(pg.MUTATIONS)
    expected = [m[0] for m in pg.MUTATIONS if pg.why_affected(m, changed, before)]
    saved = (pg.changed_files, pg.guards_at, pg.prepare_workers)

    def no_workers(count):
        raise AssertionError("listing the selection started a worker")
    pg.changed_files, pg.guards_at, pg.prepare_workers = (lambda base: changed), (lambda base: before), no_workers
    try:
        with tempfile.TemporaryDirectory() as tmp:
            listed = os.path.join(tmp, "selection.json")
            code, out = _run(["--mode", "full", "--changed", "base", "--list-selection", listed])
            got = _results(listed)
            if code != 0 or not expected or [g["label"] for g in got["guards"]] != expected:
                failures += fail(f"--list-selection wrote {len(got['guards'])} guards, not the "
                                 f"{len(expected)} the run selects: {code} {out!r}")
            if (got["mode"], got["changed"]) != ("full", "base"):
                failures += fail(f"--list-selection did not record what it selected by: {got}")
    finally:
        pg.changed_files, pg.guards_at, pg.prepare_workers = saved
    return failures


def case_gradle_starts_through_the_wrapper_windows_can_run():
    """`./gradlew` is a shell script Windows cannot start; there the batch file runs, by absolute
    path, because Windows looks a bare program name up beside this process and not in `cwd=`.
    Nothing reads stdin, so an interrupt cannot leave `cmd.exe` asking to terminate the batch job."""
    failures = 0
    started = []

    def run(argv, **kwargs):
        started.append((argv[0], kwargs.get("cwd"), kwargs.get("stdin")))
        return subprocess.CompletedProcess(argv, 0, "", "")

    saved = (pg.os.name, pg.subprocess.run, os.environ.get(pg.yoriwake_checkout.SETTING))
    tree = os.path.abspath("tree")
    os.environ[pg.yoriwake_checkout.SETTING] = tree
    pg.subprocess.run = run
    try:
        for name, expected in (("nt", os.path.join(tree, "gradlew.bat")), ("posix", "./gradlew")):
            pg.os.name = name
            if pg.wrapper(tree) != expected:
                failures += fail(f"on {name} the wrapper is {pg.wrapper(tree)!r}, not {expected!r}")
            started.clear()
            pg.gradle(["tasks"])
            pg._warm_up([tree], [":compile"])
            if started != [(expected, tree, subprocess.DEVNULL)] * 2:
                failures += fail(f"on {name} Gradle started as {started}")
    finally:
        pg.os.name, pg.subprocess.run = saved[0], saved[1]
        if saved[2] is None:
            os.environ.pop(pg.yoriwake_checkout.SETTING, None)
        else:
            os.environ[pg.yoriwake_checkout.SETTING] = saved[2]
    return failures


# A source's bytes before a mutation, by its line endings: what a restore must give back.
LINE_ENDINGS = {"LF": b"before\nanchor\nafter\n", "CRLF": b"before\r\nanchor\r\nafter\r\n",
                "mixed": b"before\r\nanchor\nafter\r\n"}


def _windows_text_writes():
    """`write_source` as it behaves on Windows, where a text-mode write turns each '\\n' into '\\r\\n'."""
    def write(path, text):
        with open(path, "w", encoding=pg.ENCODING, newline="\r\n") as handle:
            handle.write(text)
    return write


def case_a_mutated_source_is_restored_byte_for_byte_whatever_its_line_endings():
    """Text-mode reads fold '\\r\\n' into '\\n' and text-mode writes emit the platform's ending, so a
    restore of the decoded text rewrote a CRLF checkout as LF here and an LF checkout as CRLF on
    Windows -- and a check that hashed decoded text passed both. Both execution paths, under this
    platform's writes and under Windows'."""
    failures = 0
    saved = pg.write_source
    try:
        for platform, writes in (("this platform", saved), ("Windows", _windows_text_writes())):
            pg.write_source = writes
            for endings, original in LINE_ENDINGS.items():
                with _synthetic([("caught", True)]) as tmp:
                    path = os.path.join(tmp, "caught.txt")
                    with open(path, "wb") as f:
                        f.write(original)
                    code, out = _run(["--guard", "caught", "--jobs", "1"])
                    with open(path, "rb") as f:
                        restored = f.read()
                    if code != 0 or restored != original:
                        failures += fail(f"--jobs 1, {endings} under {platform}: exit {code}, "
                                         f"restored {restored!r} from {original!r}: {out!r}")
                    pg._WORKER.update(baselined={}, gradle_lock=None)
                    _index, fired, refused, _timing = pg._prove_in_worker(0)
                    with open(path, "rb") as f:
                        restored = f.read()
                    if not fired or refused or restored != original:
                        failures += fail(f"worker, {endings} under {platform}: fired {fired}, "
                                         f"refused {refused}, restored {restored!r} from {original!r}")
    finally:
        pg.write_source = saved
        pg._WORKER.clear()
    return failures


def case_the_restore_check_compares_bytes_not_decoded_text():
    """A restore that changed only line endings decodes to the same text; the check must still refuse it."""
    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        path = os.path.join(tmp, "source.txt")
        original = LINE_ENDINGS["LF"]
        _windows_text_writes()(path, original.decode())
        try:
            pg.check_restored(path, original)
            failures += fail("an LF source rewritten as CRLF passed the restore check")
        except AssertionError:
            pass
        with open(path, "wb") as f:
            f.write(original)
        pg.check_restored(path, original)
    return failures


CASES = (case_every_case_in_this_file_is_registered,
         case_a_suite_stopped_by_a_dirty_tree_names_the_files,
         case_only_the_refusal_block_is_read_as_dirty_files,
         case_a_dirty_tree_crash_is_not_proved_and_its_files_reach_the_results,
         case_any_other_crash_keeps_its_short_last_line,
         case_a_guard_whose_target_changed_is_selected,
         case_a_change_to_the_tests_that_prove_a_guard_selects_it,
         case_a_build_file_selects_every_gradle_suite_guard_and_no_python_one,
         case_a_new_or_edited_guard_is_selected_even_when_nothing_else_moved,
         case_an_unrelated_change_selects_nothing,
         case_an_edited_suite_runner_selects_its_guards,
         case_every_suite_names_the_tests_it_runs,
         case_a_suite_that_merely_crashed_proves_no_guard,
         case_each_mutation_path_resolves_into_the_tree_under_test,
         case_every_mutation_anchor_matches_exactly_once_in_its_file,
         case_a_worker_tree_carries_the_uncommitted_state_and_leaves_its_source_unwritten,
         case_a_tracked_edit_and_a_new_file_are_both_changes,
         case_a_functional_guard_runs_the_one_class_that_holds_its_test,
         case_every_functional_and_maven_guard_runs_only_its_named_tests,
         case_the_maven_whole_suite_runs_every_maven_class,
         case_no_testkit_suite_runs_in_the_parallel_phase,
         case_the_maven_break_suite_runs_under_the_lock_as_the_maven_suite_does,
         case_a_gradle_suite_not_named_safe_runs_under_the_lock,
         case_functional_guards_pair_by_class_and_configuration_guards_run_alone,
         case_guard_names_its_own_guards_and_refuses_an_unknown_one,
         case_a_removed_or_weakened_guard_is_named_against_the_base,
         case_cold_worker_trees_compile_one_at_a_time_before_the_guards,
         case_a_tree_that_does_not_compile_names_the_compiler_errors,
         case_every_guard_lands_in_exactly_one_shard_and_always_the_same_one,
         case_two_chunks_of_a_class_on_one_shard_are_one_cut,
         case_a_class_stays_whole_unless_it_outweighs_a_shard_share,
         case_each_class_guard_pays_its_own_baseline,
         case_the_maven_guards_run_in_one_lane_grouped_by_class,
         case_a_worker_baselines_each_guards_own_tests_once,
         case_one_tree_baselines_each_guards_own_tests_once,
         case_a_class_guard_runs_only_the_tests_its_expect_names,
         case_a_filter_that_runs_no_test_refuses_the_guard,
         case_every_class_guard_has_a_cost_and_an_unknown_one_weighs_its_suites_median,
         case_the_shards_projected_walls_are_within_one_heavy_unit_of_each_other,
         case_the_worst_shard_stays_under_the_target_with_room_to_run_over,
         case_a_step_is_projected_as_the_phases_it_runs,
         case_every_shards_job_is_its_steps_plus_setup_and_the_limit_is_the_workflows,
         case_a_steps_place_in_its_ci_job_follows_its_flags,
         case_a_job_without_a_limit_is_projected_without_a_margin,
         case_a_worker_pays_a_class_baseline_once_and_times_each_run,
         case_a_wait_for_the_lock_is_not_counted_as_a_guards_seconds,
         case_a_shard_that_is_not_one_of_n_refuses,
         case_an_empty_shard_succeeds_and_says_whose_guards_it_left,
         case_a_shard_writes_each_guards_outcome_and_a_silent_one_fails_it,
         case_a_refused_run_writes_every_guard_it_held_as_not_proved,
         case_a_baseline_that_fails_prints_why_and_keeps_its_results,
         case_the_parallel_run_ci_uses_writes_outcomes_and_refusals,
         case_the_selection_list_is_what_a_changed_run_selects_and_runs_nothing,
         case_gradle_starts_through_the_wrapper_windows_can_run,
         case_a_mutated_source_is_restored_byte_for_byte_whatever_its_line_endings,
         case_the_restore_check_compares_bytes_not_decoded_text)


def test_prove_guards():
    assert sum(case() for case in CASES) == 0


def main():
    failed = 0
    for case in CASES:
        print(case.__name__)
        failed += case()
    print(f"\n{'all passed' if not failed else 'FAILURES'}: {failed} failed checks")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
