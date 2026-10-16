"""
Unit tests for which guards `prove_guards.py --changed` selects.

THE FAILURE THIS EXISTS TO PREVENT is a change that skips the guard proving it. Selecting too many
guards costs minutes; selecting too few lets an unproved guard ride a commit that says "every guard
this run selected fired", which is true and says nothing about the one it left out.

Run: `python scripts/test_prove_guards.py`
"""

import os
import sys

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
    gradle = guard(path="plugin/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/report/Audit.kt", suite=pg.unit_suite)
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
    m = guard(path="plugin/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/report/Audit.kt", suite=pg.unit_suite)
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


def case_each_mutation_path_resolves_into_the_tree_under_test():
    """Product sources and the harness's own sit in one tree, so every path is relative to it."""
    failures = 0
    home = pg.yoriwake_checkout.root()
    for path in ("plugin/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/report/Audit.kt",
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


def case_every_functional_guard_resolves_to_one_class():
    """A renamed test would otherwise slow its guard back to the whole suite, unseen."""
    unresolved = [m[0] for m in pg.MUTATIONS if m[4] is pg.functional_suite
                  and pg.functional_class(m[5], pg.yoriwake_checkout.root()) is None]
    return fail(f"no single class for: {', '.join(unresolved)}") if unresolved else 0


def case_no_testkit_suite_runs_in_the_parallel_phase():
    """TestKit builds failed unmutated under load; only the alone phase keeps load away from them."""
    failures = 0
    for name in ("functional", "configuration guards", "break"):
        if name not in pg.EXCLUSIVE_SUITES:
            failures += fail(f"{name} starts TestKit builds and runs in the parallel phase")
        if name in pg.UNLOCKED_SUITES:
            failures += fail(f"{name} starts TestKit builds and runs without the Gradle lock")
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
    if [len(g) for g in groups] != sorted((len(g) for g in groups), reverse=True):
        failures += fail("the classes are not handed out most guards first")
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

CASES = (case_every_case_in_this_file_is_registered,
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
         case_every_functional_guard_resolves_to_one_class,
         case_no_testkit_suite_runs_in_the_parallel_phase,
         case_a_gradle_suite_not_named_safe_runs_under_the_lock,
         case_functional_guards_pair_by_class_and_configuration_guards_run_alone,
         case_guard_names_its_own_guards_and_refuses_an_unknown_one,
         case_a_removed_or_weakened_guard_is_named_against_the_base,
         case_cold_worker_trees_compile_one_at_a_time_before_the_guards)


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
