"""Tests for `commit_replay.py`: real commits reverted at the pin and asked what would run.

Git fixtures in a temporary directory, the build and the explain task stubbed. Cases return a
count of failed checks; one `test_*` entry point asserts.

    python3 bench/test_commit_replay.py
"""

import json
import os
import shutil
import sys
import tempfile
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import commit_replay  # noqa: E402
import revert  # noqa: E402
import run  # noqa: E402
import sweep_data  # noqa: E402
from test_revert import Repo, fail  # noqa: E402

MAP = ".gradle/yoriwake/core-test-0000"
NARROW = {"fullRun": False, "selected": 2, "known": 9}
UNMAPPABLE = {"fullRun": True, "fullRunKind": "unmappable-paths", "selected": 9, "known": 9,
              "unmappablePaths": 1, "unmappablePathsSample": ["README.md"]}
TMP = []


def history():
    """A repository whose last commit is the pin: code, a README edit, a merge, a commit that
    adds one file and deletes another, and one whose lines the pin has since rewritten."""
    root = os.path.join(tempfile.mkdtemp(prefix="commit-replay-"), "repo")
    TMP.append(os.path.dirname(root))
    repo = Repo(root)
    repo.commit("base", {".gitignore": "*.log\n.gradle/\n", "README.md": "one\n",
                         "core/src/A.java": "class A { int a = 1; }\n",
                         "core/src/Gone.java": "class Gone {}\n",
                         "core/src/C.java": "class C { int c = 1; }\n"})
    repo.commit("rewritten-later", {"core/src/C.java": "class C { int c = 2; }\n"})
    repo.commit("rewrite", {"core/src/C.java": "class C { int c = 3; }\n"})
    repo.commit("add-and-delete", {"core/src/Added.java": "class Added {}\n",
                                   "core/src/Gone.java": None})
    repo.git("checkout", "-q", "-b", "side")
    repo.commit("side-one", {"core/src/S.java": "class S { int s = 1; }\n"})
    repo.commit("side-two", {"core/src/T.java": "class T {}\n"})
    repo.git("checkout", "-q", "main")
    repo.commit("mainline", {"core/src/A.java": "class A { int a = 2; }\n"})
    repo.git("merge", "-q", "--no-ff", "-m", "merge", "side")
    repo.shas["merge"] = repo.git("rev-parse", "HEAD").strip()
    repo.commit("docs", {"README.md": "two\n"})
    repo.commit("code", {"core/src/A.java": "class A { int a = 3; }\n"})
    return repo


def pin(repo):
    return repo.git("rev-parse", "HEAD").strip()


def stubs(repo, answers=None, compiles=None, seen=None):
    """`reset`, `compile_tests` and `explain` over the fixture: an answer per commit name, a
    narrowing one by default; `seen` collects what the tree held when each was asked."""
    names = {sha: name for name, sha in repo.shas.items()}

    def reset():
        return run.restore(repo.root)

    def compile_tests(sha):
        return (compiles or {}).get(names[sha], 0)

    def explain(sha):
        if seen is not None:
            seen[names[sha]] = {path: repo.read(path) for path in
                                ("README.md", "core/src/A.java", "core/src/S.java",
                                 "core/src/T.java", "core/src/Added.java", "core/src/Gone.java")}
        return (answers or {}).get(names[sha], NARROW)

    return {"reset": reset, "compile_tests": compile_tests, "explain": explain}


def replayed(repo, count, budget_seconds=3600, clock=time.monotonic, **stubbed):
    drawn = commit_replay.draw(repo.root, pin(repo), count)
    return commit_replay.replay(repo.root, drawn, budget_seconds=budget_seconds, clock=clock,
                                **stubs(repo, **stubbed))


def by_name(repo, rows):
    names = {sha: name for name, sha in repo.shas.items()}
    return {names[row["sha"]]: row for row in rows}


def case_the_window_is_the_newest_first_parent_commits_and_no_more():
    repo = history()
    failures = 0
    rows = replayed(repo, 3)
    want = [repo.shas[name] for name in ("code", "docs", "merge")]
    if [row["sha"] for row in rows] != want:
        failures += fail(f"three commits drew {[row['sha'][:8] for row in rows]}")
    if len(replayed(repo, 50)) != 8:
        failures += fail("a window longer than the first-parent history did not stop at its end")
    if any(row["sha"] in (repo.shas["side-one"], repo.shas["side-two"])
           for row in replayed(repo, 50)):
        failures += fail("a side branch's commit was drawn beside its merge")
    return failures


def case_a_docs_commit_is_forced_by_its_cause_and_a_code_commit_narrows():
    repo = history()
    failures, seen = 0, {}
    rows = by_name(repo, replayed(repo, 2, answers={"docs": UNMAPPABLE}, seen=seen))
    docs, code = rows["docs"], rows["code"]
    if (docs["outcome"], docs.get("cause")) != ("forced", "full-run:unmappable-paths"):
        failures += fail(f"the README commit came back {docs}")
    if docs.get("unmappable_sample") != ["README.md"] or docs.get("unmappable_paths") != 1:
        failures += fail(f"the README commit kept no sample of its paths: {docs}")
    if (code["outcome"], code.get("selected"), code.get("known")) != ("narrowed", 2, 9):
        failures += fail(f"the code commit came back {code}")
    if seen["docs"]["README.md"] != "one\n" or seen["docs"]["core/src/A.java"] != repo.read(
            "core/src/A.java"):
        failures += fail("the README commit was not reverted alone on the pinned tree")
    return failures


def case_a_merge_is_reverted_as_its_diff_against_its_first_parent():
    repo = history()
    failures, seen = 0, {}
    replayed(repo, 3, seen=seen)
    tree = seen["merge"]
    if tree["core/src/S.java"] is not None or tree["core/src/T.java"] is not None:
        failures += fail("reverting the merge left the side branch's files in the tree")
    if tree["core/src/A.java"] != "class A { int a = 3; }\n":
        failures += fail("reverting the merge touched the first parent's own change")
    return failures


def case_a_reverse_that_does_not_apply_is_not_asked_and_the_tree_stays_clean():
    repo = history()
    failures = 0
    rows = by_name(repo, replayed(repo, 50))
    row = rows["rewritten-later"]
    if (row["outcome"], row.get("reason")) != ("not-asked", "does-not-apply"):
        failures += fail(f"a commit the pin has rewritten came back {row}")
    if not commit_replay.at_pin(repo.root, pin(repo)):
        failures += fail("the tree is not at the pin after the replay")
    return failures


def case_a_duplicate_patch_is_not_asked_and_the_sums_hold_with_it():
    repo = history()
    # The same lines changed again by a newer commit: one patch, two commits.
    repo.commit("undo", {"README.md": "one\n"})
    repo.commit("redo", {"README.md": "two\n"})
    failures = 0
    all_rows = replayed(repo, 4)
    rows = by_name(repo, all_rows)
    if (rows["docs"]["outcome"], rows["docs"].get("reason")) != ("not-asked", "duplicate"):
        failures += fail(f"the older of two equal patches came back {rows['docs']}")
    if rows["redo"]["outcome"] != "narrowed":
        failures += fail(f"the newer of two equal patches came back {rows['redo']}")
    if rows["undo"].get("reason") != "does-not-apply":
        failures += fail(f"a commit the pin has undone came back {rows['undo']}")
    counts = commit_replay.distribution(all_rows)
    if not counts["sums"] or (counts["drawn"], counts["replayed"]) != (4, 2):
        failures += fail(f"the distribution with a duplicate reads {counts}")
    return failures


def case_a_compile_failure_and_a_missing_answer_are_not_asked():
    repo = history()
    failures = 0
    rows = by_name(repo, replayed(repo, 2, compiles={"code": 1}, answers={"docs": None}))
    if rows["code"].get("reason") != "does-not-compile":
        failures += fail(f"a tree that does not compile came back {rows['code']}")
    if rows["docs"].get("reason") != "unanswered":
        failures += fail(f"a commit with no explanation came back {rows['docs']}")
    return failures


def case_a_reverse_that_adds_and_deletes_leaves_the_pin_and_the_map_as_they_were():
    repo = history()
    failures, seen = 0, {}
    map_file = os.path.join(MAP, "map")
    repo.write(map_file, "recorded")
    repo.write("core/derby.log", "written by the capture")
    before = repo.git("ls-files", "-s")
    replayed(repo, 50, seen=seen)
    tree = seen["add-and-delete"]
    if tree["core/src/Added.java"] is not None or tree["core/src/Gone.java"] != "class Gone {}\n":
        failures += fail(f"the reverse did not delete the added and restore the deleted: {tree}")
    if repo.git("ls-files", "-s") != before or not commit_replay.at_pin(repo.root, pin(repo)):
        failures += fail("the tree after the replay is not the pin")
    if repo.read("core/src/Gone.java") is not None or repo.read("core/src/Added.java") is None:
        failures += fail("the working tree kept the reverse's files")
    if repo.read(map_file) != "recorded" or repo.read("core/derby.log") is None:
        failures += fail("the restore removed the map or an ignored file")
    return failures


def project_file(repo, **over):
    project = {"name": "sample", "url": "https://example.invalid/sample.git", "commit": pin(repo),
               "module": "core", "sources": ["core/src"]}
    project.update(over)
    return project


def prepared(repo, monkey):
    """The fixture as `run.py` leaves a checkout: a stamped map, and the snapshot of the files
    present when it was captured. Returns the scratch directory."""
    map_dir = os.path.join(repo.root, MAP)
    os.makedirs(map_dir, exist_ok=True)
    stamp = sweep_data.capture_stamp("shared", "plugin-sha", pin(repo))
    sweep_data.write_capture_stamp(map_dir, stamp)
    repo.write("core/derby.log", "written by the capture")
    scratch = os.path.join(os.path.dirname(repo.root), "scratch")
    os.makedirs(scratch, exist_ok=True)
    project = project_file(repo)
    run.write_untracked_snapshot(os.path.join(scratch, run.UNTRACKED_SNAPSHOT), stamp,
                                 run.untracked_files(repo.root, project))
    monkey(run, "find_map", lambda checkout, module, task: map_dir)
    return scratch, map_dir


def patched():
    """`(monkey, undo)`: set a module attribute for one case."""
    saved = []

    def monkey(module, name, value):
        saved.append((module, name, getattr(module, name)))
        setattr(module, name, value)

    def undo():
        for module, name, value in reversed(saved):
            setattr(module, name, value)

    return monkey, undo


def case_a_file_written_since_the_capture_is_gone_before_the_explain_and_the_map_stays():
    repo = history()
    failures = 0
    monkey, undo = patched()
    try:
        scratch, map_dir = prepared(repo, monkey)
        present = {}

        def gradle(checkout, project, task_args, log_path):
            if any("yoriwakeExplain" in arg for arg in task_args):
                present[os.path.basename(log_path)] = (
                    repo.read("core/later.log"), repo.read("core/derby.log"), list(task_args))
                with open(os.path.join(map_dir, "explain.json"), "w", encoding="utf-8") as handle:
                    json.dump(UNMAPPABLE, handle)
            # What a guest's own build would leave behind: an ignored file.
            repo.write("core/later.log", "written by a build")
            return 0, 1.0

        monkey(run, "gradle", gradle)
        repo.write("core/later.log", "written after the capture")
        result = commit_replay.replay_project(project_file(repo), repo.root, 2, 30, scratch)
    finally:
        undo()
    if not result["replayed"] or not result["tree_restored"] or not result["untracked_reset"]:
        failures += fail(f"the project came back {result}")
    if result["plugin_commit"] != "plugin-sha" or result["pin"] != pin(repo):
        failures += fail("the file does not name the plugin commit and the pin of its map")
    if len(present) != 2 or any(later is not None or derby is None
                                for later, derby, _ in present.values()):
        failures += fail(f"the explain ran beside a file written since the capture: {present}")
    args = next(iter(present.values()))[2]
    if ":core:yoriwakeExplainTest" not in args or "-Pyoriwake.base=HEAD" not in args:
        failures += fail(f"the explain was asked as {args}")
    if any(arg.startswith("-Pyoriwake.select") for arg in args):
        failures += fail("the explain was asked to select")
    if repo.read("core/later.log") is not None:
        failures += fail("a file written by the last build is still in the tree")
    if not os.path.isfile(os.path.join(map_dir, sweep_data.CAPTURE_STAMP_FILE)):
        failures += fail("the restore removed the map")
    if result["distribution"]["causes"] != {"full-run:unmappable-paths": 2}:
        failures += fail(f"the distribution reads {result['distribution']}")
    return failures


def explained(repo, commits, answer):
    """`replay_project` over the fixture with the build stubbed: the result, and per explain log
    what the tree held. `answer(n)` is what the n-th explain writes, or None for nothing."""
    monkey, undo = patched()
    held = {}
    try:
        scratch, map_dir = prepared(repo, monkey)

        def gradle(checkout, project, task_args, log_path):
            if any("yoriwakeExplain" in arg for arg in task_args):
                written = answer(len(held))
                held[os.path.basename(log_path)] = {
                    path: repo.read(path) for path in ("core/src/Gone.java", "core/src/Added.java")}
                if written is not None:
                    with open(os.path.join(map_dir, "explain.json"), "w", encoding="utf-8") as out:
                        json.dump(written, out)
            return 0, 1.0

        monkey(run, "gradle", gradle)
        return commit_replay.replay_project(project_file(repo), repo.root, commits, 30, scratch), held
    finally:
        undo()


def case_a_file_the_reverse_brought_back_is_there_when_the_explain_runs():
    repo = history()
    failures = 0
    result, held = explained(repo, 5, lambda n: NARROW)
    tree = held.get(f"explain-{repo.shas['add-and-delete'][:12]}.log")
    if tree is None or tree["core/src/Gone.java"] != "class Gone {}\n" \
            or tree["core/src/Added.java"] is not None:
        failures += fail(f"the explain of the deleting commit ran on {tree}")
    if not result["tree_restored"] or repo.read("core/src/Gone.java") is not None:
        failures += fail("the file the reverse brought back outlived the replay")
    return failures


def case_an_explanation_left_by_the_commit_before_is_not_this_commits_answer():
    repo = history()
    failures = 0
    result, _ = explained(repo, 2, lambda n: UNMAPPABLE if n == 0 else None)
    rows = by_name(repo, result["rows"])
    if rows["code"]["outcome"] != "forced" or rows["docs"].get("reason") != "unanswered":
        failures += fail(f"the second commit was answered from the first's file: {rows}")
    return failures


def case_a_stalled_compile_is_not_a_commit_that_does_not_compile():
    repo = history()
    rows = by_name(repo, replayed(repo, 1, compiles={"code": commit_replay.STALLED}))
    if rows["code"].get("reason") != "build-stalled":
        return fail(f"a stalled compile came back {rows['code']}")
    return 0


def case_a_build_that_raises_leaves_the_pin_and_a_tree_that_cannot_be_reset_stops():
    repo = history()
    failures = 0
    stubbed = stubs(repo)

    def raising(sha):
        raise OSError("the wrapper could not be launched")

    try:
        commit_replay.replay(repo.root, commit_replay.draw(repo.root, pin(repo), 1),
                             budget_seconds=3600, **{**stubbed, "compile_tests": raising})
        failures += fail("the build's error was swallowed")
    except OSError:
        pass
    if not commit_replay.at_pin(repo.root, pin(repo)):
        failures += fail("the reverted commit stayed in the tree after the build raised")
    calls = []

    def stuck():
        calls.append(1)
        return len(calls) < 2 and stubbed["reset"]()

    try:
        commit_replay.replay(repo.root, commit_replay.draw(repo.root, pin(repo), 2),
                             budget_seconds=3600, **{**stubbed, "reset": stuck})
        failures += fail("a tree that could not be put back did not stop the replay")
    except SystemExit:
        pass
    return failures


def case_the_command_line_hands_the_build_an_absolute_checkout_and_reports_a_tree_left_dirty():
    repo = history()
    failures = 0
    monkey, undo = patched()
    form = os.path.join(os.path.dirname(repo.root), "sample.toml")
    with open(form, "w", encoding="utf-8") as handle:
        handle.write(f'url = "https://example.invalid/sample.git"\ncommit = "{pin(repo)}"\n')
    out = os.path.join(os.path.dirname(repo.root), "out.json")
    got = []
    here = os.getcwd()
    try:
        def replay_project(project, checkout, commits, budget_minutes, scratch=None):
            got.append((checkout, scratch))
            return {"replayed": True, "tree_restored": False,
                    "distribution": commit_replay.distribution([])}

        monkey(commit_replay, "replay_project", replay_project)
        os.chdir(os.path.dirname(repo.root))
        code = commit_replay.main([form, "--checkout", "repo", "--scratch", "scratch",
                                   "--commits", "1", "--out", out])
    finally:
        os.chdir(here)
        undo()
    if not got or not all(os.path.isabs(path) for path in got[0]) \
            or os.path.realpath(got[0][0]) != os.path.realpath(repo.root):
        failures += fail(f"the replay was handed {got}")
    if code != 1:
        failures += fail("a tree not put back to the pin exited zero")
    return failures


def case_a_project_without_history_or_without_this_runs_map_is_marked_not_replayed():
    repo = history()
    failures = 0
    monkey, undo = patched()
    try:
        scratch, map_dir = prepared(repo, monkey)
        monkey(run, "gradle", lambda *args: (_ for _ in ()).throw(AssertionError("a build ran")))
        local = commit_replay.replay_project(project_file(repo, commit="local", url=None),
                                             repo.root, 8, 30, scratch)
        other = commit_replay.replay_project(project_file(repo, commit="0" * 40), repo.root, 8, 30,
                                             scratch)
        os.remove(os.path.join(map_dir, sweep_data.CAPTURE_STAMP_FILE))
        unstamped = commit_replay.replay_project(project_file(repo), repo.root, 8, 30, scratch)
        monkey(run, "find_map", lambda checkout, module, task: None)
        mapless = commit_replay.replay_project(project_file(repo), repo.root, 8, 30, scratch)
    finally:
        undo()
    for label, result in (("no history", local), ("another pin", other),
                          ("no stamp", unstamped), ("no map", mapless)):
        if result["replayed"] or result["rows"] or not result.get("reason"):
            failures += fail(f"{label}: came back {result}")
    return failures


def case_an_explanation_is_classified_and_the_finer_kind_wins():
    failures = 0
    for explanation, want in (
            ({"fullRun": False}, ("narrowed", None)),
            ({"fullRun": True, "fullRunKind": "unmappable-paths", "refusalKind": "schema"},
             ("forced", "refusal:schema")),
            ({"fullRun": True, "fullRunKind": "unmappable-paths"},
             ("forced", "full-run:unmappable-paths")),
            ({"fullRun": True}, ("unrecorded", None)),
            ({"fullRun": True, "fullRunKind": None, "refusalKind": ""}, ("unrecorded", None)),
            (None, ("not-asked", "unanswered")),
            ({"selected": 3}, ("not-asked", "unanswered"))):
        got = commit_replay.classify(explanation)
        if got != want:
            failures += fail(f"{explanation} classified as {got}, not {want}")
    return failures


def case_an_explanation_older_than_the_question_is_no_answer():
    failures = 0
    directory = tempfile.mkdtemp(prefix="commit-replay-")
    TMP.append(directory)
    path = os.path.join(directory, "explain.json")
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(NARROW, handle)
    now = os.path.getmtime(path)
    if commit_replay.read_explanation(path, now + 60) is not None:
        failures += fail("a file written before the question was read as its answer")
    if commit_replay.classify(commit_replay.read_explanation(path, now + 60)) != (
            "not-asked", "unanswered"):
        failures += fail("a stale explanation did not count as unanswered")
    if commit_replay.read_explanation(path, now - 60) != NARROW:
        failures += fail("a fresh explanation was not read")
    if commit_replay.read_explanation(os.path.join(directory, "none.json"), 0) is not None:
        failures += fail("a missing explanation was read")
    return failures


ROWS = [{"sha": "a", "outcome": "narrowed", "selected": 2, "known": 9},
        {"sha": "b", "outcome": "narrowed", "selected": 9, "known": 9},
        {"sha": "c", "outcome": "forced", "cause": "full-run:unmappable-paths"},
        {"sha": "d", "outcome": "forced", "cause": "full-run:unmappable-paths"},
        {"sha": "e", "outcome": "forced", "cause": "refusal:schema"},
        {"sha": "f", "outcome": "unrecorded"},
        {"sha": "g", "outcome": "not-asked", "reason": "does-not-apply"},
        {"sha": "h", "outcome": "not-asked", "reason": "budget"}]


def case_the_distribution_sums_and_a_padded_list_does_not():
    failures = 0
    counts = commit_replay.distribution(ROWS)
    want = {"drawn": 8, "replayed": 6, "narrowed": 2, "selected_fewer": 1, "forced": 3,
            "unrecorded": 1, "causes": {"full-run:unmappable-paths": 2, "refusal:schema": 1},
            "not_asked": {"budget": 1, "does-not-apply": 1}, "sums": True}
    if counts != want:
        failures += fail(f"the distribution reads {counts}")
    if list(counts["causes"]) != ["full-run:unmappable-paths", "refusal:schema"]:
        failures += fail("the causes are not ordered by count")
    for padding in ({"sha": "x", "outcome": "skipped"}, {"sha": "x", "outcome": "forced"},
                    {"sha": "x", "outcome": "not-asked", "reason": "weather"}, {"sha": "x"}):
        if commit_replay.distribution(ROWS + [padding])["sums"]:
            failures += fail(f"a list padded with {padding} still sums")
    if not commit_replay.distribution([])["sums"]:
        failures += fail("an empty list does not sum")
    return failures


def case_the_budget_stops_the_loop_and_counts_the_rest():
    repo = history()
    failures = 0
    ticks = iter(range(0, 10_000, 40))
    rows = replayed(repo, 3, budget_seconds=100, clock=lambda: next(ticks))
    reasons = [row.get("reason") for row in rows]
    if reasons[0] is not None or reasons[-1] != "budget":
        failures += fail(f"a budget of 100 s over 40 s steps left {reasons}")
    counts = commit_replay.distribution(rows)
    if not counts["sums"] or counts["not_asked"].get("budget", 0) < 1:
        failures += fail(f"the distribution past the budget reads {counts}")
    if not commit_replay.at_pin(repo.root, pin(repo)):
        failures += fail("the tree is not at the pin after a replay the budget cut short")
    return failures


def case_the_whole_patch_covers_paths_outside_the_source_roots():
    repo = history()
    failures = 0
    text = commit_replay.whole_patch(repo.root, repo.shas["docs"])
    if "README.md" not in text or not revert.applies(repo.root, text):
        failures += fail("the whole patch of a README commit does not carry the README")
    if commit_replay.whole_patch(repo.root, repo.shas["base"]).count("diff --git") != 5:
        failures += fail("a root commit is not its diff against the empty tree")
    return failures


def case_the_explain_task_is_named_after_the_measured_task():
    failures = 0
    for path, want in ((":core:test", ":core:yoriwakeExplainTest"),
                       ("test", "yoriwakeExplainTest"),
                       (":a:b:jvmTest", ":a:b:yoriwakeExplainJvmTest")):
        if commit_replay.explain_task(path) != want:
            failures += fail(f"{path} asks {commit_replay.explain_task(path)}")
    return failures


def record(directory, name, rows=None, **over):
    """A record job's download: `replay-<name>/<name>/commit-replay.json`."""
    data = {"schema": commit_replay.SCHEMA, "project": name, "replayed": True, "pin": "p" * 40,
            "plugin_commit": "plugin-sha", "commits": 8, "budget_reached": False,
            "tree_restored": True, "seconds": 12.0, "rows": rows if rows is not None else ROWS,
            "distribution": {"narrowed": 99}}
    data.update(over)
    folder = os.path.join(directory, f"replay-{name}", name)
    os.makedirs(folder)
    with open(os.path.join(folder, commit_replay.FILE), "w", encoding="utf-8") as handle:
        json.dump(data, handle)


def downloads():
    directory = tempfile.mkdtemp(prefix="commit-replay-")
    TMP.append(directory)
    return directory


def case_two_projects_sum_and_a_missing_one_is_listed_not_counted():
    failures = 0
    directory = downloads()
    record(directory, "alpha")
    record(directory, "beta", rows=ROWS[:3], budget_reached=True)
    record(directory, "fixture", replayed=False, reason="no upstream history", rows=[])
    os.makedirs(os.path.join(directory, "replay-broken", "broken"))
    with open(os.path.join(directory, "replay-broken", "broken", commit_replay.FILE), "w") as out:
        out.write("{")
    merged = commit_replay.merge(directory, ["alpha", "beta", "fixture", "absent", "broken"])
    figure = merged["figure"]
    if (figure["drawn"], figure["replayed"], figure["narrowed"], figure["forced"]) != (11, 9, 4, 4):
        failures += fail(f"two projects sum to {figure}")
    if figure["causes"] != {"full-run:unmappable-paths": 3, "refusal:schema": 1}:
        failures += fail(f"the causes sum to {figure['causes']}")
    if not figure["sums"]:
        failures += fail("the summed figure does not add up")
    if merged["missing"] != ["absent", "broken"]:
        failures += fail(f"missing reads {merged['missing']}")
    if merged["not_replayed"] != {"fixture": "no upstream history"}:
        failures += fail(f"not replayed reads {merged['not_replayed']}")
    if merged["cut_short"] != ["beta"] or sorted(merged["projects"]) != ["alpha", "beta"]:
        failures += fail(f"projects read {sorted(merged['projects'])}, cut short "
                         f"{merged['cut_short']}")
    if merged["projects"]["alpha"]["distribution"]["narrowed"] != 2:
        failures += fail("a file's own arithmetic was trusted over its rows")
    if merged["plugin_commits"] != ["plugin-sha"] or merged["commits"] != ["8"]:
        failures += fail("the figure does not name its plugin commit and window")
    return failures


def case_a_run_that_completes_another_keeps_the_projects_it_did_not_plan():
    failures = 0
    first, second = downloads(), downloads()
    record(first, "alpha")
    record(first, "beta", rows=ROWS[:3])
    previous = commit_replay.merge(first, ["alpha", "beta", "gamma"])
    record(second, "beta")
    record(second, "gamma", rows=ROWS[:1])
    merged = commit_replay.merge(second, ["beta", "gamma"], previous)
    if sorted(merged["projects"]) != ["alpha", "beta", "gamma"] or merged["missing"]:
        failures += fail(f"the completed figure holds {sorted(merged['projects'])}, missing "
                         f"{merged['missing']}")
    if merged["figure"]["drawn"] != 8 + 8 + 1:
        failures += fail(f"the completed figure draws {merged['figure']['drawn']}")
    return failures


def case_the_command_line_merges_and_fails_a_figure_that_does_not_add_up():
    failures = 0
    directory = downloads()
    record(directory, "alpha")
    out = os.path.join(directory, "out", commit_replay.FILE)
    if commit_replay.main(["--merge", directory, "--planned", "alpha", "--out", out]) != 0:
        failures += fail("a merge that adds up exited non-zero")
    with open(out, encoding="utf-8") as handle:
        if json.load(handle)["figure"]["replayed"] != 6:
            failures += fail("the merged file does not hold the figure")
    record(directory, "beta", rows=ROWS + [{"sha": "x", "outcome": "skipped"}])
    if commit_replay.main(["--merge", directory, "--planned", "alpha", "beta", "--out", out]) != 1:
        failures += fail("a merge over a padded list exited zero")
    return failures


def test_commit_replay():
    failures = 0
    try:
        for name, case in sorted(globals().items()):
            if name.startswith("case_"):
                print(name)
                failures += case()
    finally:
        for directory in TMP:
            shutil.rmtree(directory, ignore_errors=True)
    assert failures == 0, f"{failures} check(s) failed"


if __name__ == "__main__":
    test_commit_replay()
    print("ok")
