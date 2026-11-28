"""Tests for `history_replay.py`: the walk over consecutive commits, its counts, shards and merge.

Builds are stubbed: each commit's runs are described by the tests they ran and failed. Git
fixtures in a temporary directory for the window. Cases return a count of failed checks; one
`test_*` entry point asserts.

    python3 bench/test_history_replay.py
"""

import json
from collections import Counter
import os
import shutil
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import history_replay as hr  # noqa: E402
from test_revert import Repo, fail  # noqa: E402

TMP = []
SUITE = ["T::a", "T::b", "T::c", "U::d"]


def report(ran, failed=(), counts=None):
    result = hr.Report()
    for test in ran:
        cls, name = test.split("::")
        result.counts[(cls, name)] = (counts or {}).get(test, 1)
    result.failed = {tuple(test.split("::")) for test in failed}
    return result


class Stubs:
    """Runs per commit: `full[sha]` is `(status, failed)`, `selected[sha]` the tests the selecting
    run ran (the whole suite when absent); `flaky` passes alone; `staging` refuses commits."""

    def __init__(self, full=None, selected=None, flaky=(), staging=(), explain=None, clock=None):
        self.full, self.selected = full or {}, selected or {}
        self.flaky, self.staging, self.explained = set(flaky), set(staging), explain
        self.at, self.calls, self.clock = None, [], clock

    def prepare(self, sha):
        self.calls.append(("prepare", sha))
        if sha in self.staging:
            return None
        self.at = sha
        return f"staged-{sha}"

    def record(self):
        self.calls.append(("record", self.at))
        status, failed = self.full.get(self.at, (hr.OK, ()))
        if self.clock is not None:
            self.clock.now += 10
        return hr.Arm(status, report(SUITE if status == hr.OK else [], failed), 1.0)

    def select(self, base):
        self.calls.append(("select", self.at, base))
        status, failed = self.full.get(self.at, (hr.OK, ()))
        ran = self.selected.get(self.at, SUITE)
        return hr.Arm(hr.OK if status == hr.OK else status,
                      report(ran, [t for t in failed if t in ran]), 1.0)

    def explain(self, base):
        self.calls.append(("explain", self.at, base))
        return (self.explained or {}).get(self.at)

    def rerun_alone(self, test, module):
        self.calls.append(("alone", f"{test[0]}::{test[1]}"))
        return f"{test[0]}::{test[1]}" in self.flaky


class Clock:
    def __init__(self):
        self.now = 0.0

    def __call__(self):
        return self.now


def pairs(*names):
    shas = ["p0", *names]
    return [{"sha": sha, "parent": shas[i]} for i, sha in enumerate(names)]


def walk(stubs, window, budget=1e9, clock=None, explain=False):
    return hr.walk(window, prepare=stubs.prepare, record=stubs.record, select=stubs.select,
                   rerun_alone=stubs.rerun_alone, budget_seconds=budget,
                   explain=stubs.explain if explain else None,
                   clock=clock or Clock())


def case_three_commits_are_three_measured_pairs_after_one_recording_at_the_parent():
    failures = 0
    stubs = Stubs()
    initial, rows = walk(stubs, pairs("c1", "c2", "c3"))
    if initial.get("sha") != "p0" or initial.get("status") != hr.OK:
        failures += fail(f"the walk did not start with a recording at the parent: {initial}")
    if [row["outcome"] for row in rows] != [hr.MEASURED] * 3:
        failures += fail(f"three commits did not give three measured pairs: {rows}")
    if [c for c in stubs.calls if c[0] == "record"][0] != ("record", "p0"):
        failures += fail(f"the first recording was not at the parent: {stubs.calls}")
    if sum(1 for c in stubs.calls if c[0] == "record") != 4:
        failures += fail("each commit did not record once after its selecting run")
    return failures


def case_a_failure_new_at_the_commit_is_missed_only_when_not_selected():
    failures = 0
    stubs = Stubs(full={"c1": (hr.OK, ["T::b"]), "c2": (hr.OK, ["T::c"])},
                  selected={"c1": ["T::a"], "c2": ["T::c"]})
    _, rows = walk(stubs, pairs("c1", "c2"))
    if rows[0]["missed"] != ["T::b"] or rows[0]["at_risk"] != ["T::b"]:
        failures += fail(f"an unselected new failure was not missed: {rows[0]}")
    if rows[1]["missed"] or rows[1]["at_risk"] != ["T::c"]:
        failures += fail(f"a selected new failure was missed: {rows[1]}")
    return failures


def case_a_test_new_at_the_commit_that_fails_unselected_is_missed_and_at_risk():
    failures = 0

    class New(Stubs):
        def record(self):
            arm = super().record()
            if self.at == "c1":
                arm.report.counts[("N", "new")] = 1
                arm.report.failed.add(("N", "new"))
            return arm

    stubs = New(full={"c1": (hr.OK, ["T::a"])}, selected={"c1": ["T::b"]})
    _, rows = walk(stubs, pairs("c1"))
    if rows[0]["at_risk"] != ["N::new", "T::a"] or rows[0]["missed"] != ["N::new", "T::a"]:
        failures += fail(f"a new failing test was not at risk and missed: {rows[0]}")
    return failures


def case_a_missed_test_that_passes_alone_is_flaky_and_not_counted():
    failures = 0
    stubs = Stubs(full={"c1": (hr.OK, ["T::b", "T::c"])}, selected={"c1": ["T::a"]},
                  flaky=["T::b"])
    _, rows = walk(stubs, pairs("c1"))
    if rows[0]["missed"] != ["T::c"] or rows[0]["flaky"] != ["T::b"]:
        failures += fail(f"the flaky test was counted or the other dropped: {rows[0]}")
    if hr.sums(rows)["missed"] != 1 or hr.sums(rows)["flaky"] != 1:
        failures += fail(f"the sums count the flaky test: {hr.sums(rows)}")
    return failures


def case_a_test_failing_at_parent_and_commit_is_counted_apart():
    failures = 0
    stubs = Stubs(full={"p0": (hr.OK, ["T::c"]), "c1": (hr.OK, ["T::c"])},
                  selected={"c1": ["T::a"]})
    _, rows = walk(stubs, pairs("c1"))
    if rows[0]["missed"] or rows[0]["at_risk"] or rows[0]["failing_both"] != 1:
        failures += fail(f"a test failing at both was missed or not counted apart: {rows[0]}")
    if ("alone", "T::c") in stubs.calls:
        failures += fail("a test failing at both was re-run as a candidate miss")
    return failures


def case_an_unbuildable_commit_excludes_it_and_the_next_and_records_again():
    failures = 0
    stubs = Stubs(full={"c2": (hr.BUILD_FAILED, ())})
    _, rows = walk(stubs, pairs("c1", "c2", "c3", "c4"))
    got = [(row["outcome"], row.get("reason")) for row in rows]
    want = [(hr.MEASURED, None), (hr.EXCLUDED, "does-not-build"),
            (hr.EXCLUDED, "parent-did-not-build"), (hr.MEASURED, None)]
    if got != want:
        failures += fail(f"outcomes after an unbuildable commit: {got}")
    if ("select", "c3", "staged-c2") in stubs.calls or ("record", "c3") not in stubs.calls:
        failures += fail(f"the walk did not record again at the next buildable commit: {stubs.calls}")
    if ("select", "c4", "staged-c3") not in stubs.calls:
        failures += fail("the pair after the re-recording did not select against it")
    return failures


def case_a_timed_out_full_run_is_excluded_and_so_is_the_next_pair():
    failures = 0
    stubs = Stubs(full={"c1": (hr.TIMED_OUT, ())})
    _, rows = walk(stubs, pairs("c1", "c2"))
    if [row.get("reason") for row in rows] != ["timed-out", "parent-timed-out"]:
        failures += fail(f"a timed-out full run: {rows}")
    return failures


def case_a_commit_the_harness_cannot_stage_is_excluded():
    failures = 0
    stubs = Stubs(staging=["c1"])
    _, rows = walk(stubs, pairs("c1", "c2"))
    if [row.get("reason") for row in rows] != ["not-staged", "parent-not-staged"]:
        failures += fail(f"an unstaged commit: {rows}")
    return failures


def case_the_selecting_run_bases_on_the_parent_as_staged():
    failures = 0
    stubs = Stubs()
    walk(stubs, pairs("c1", "c2"))
    selects = [c for c in stubs.calls if c[0] == "select"]
    if selects != [("select", "c1", "staged-p0"), ("select", "c2", "staged-c1")]:
        failures += fail(f"the selecting runs' bases were not the staged parents: {selects}")
    return failures


def case_the_budget_ends_the_shard_and_counts_the_rest():
    failures = 0
    clock = Clock()
    stubs = Stubs(clock=clock)
    _, rows = walk(stubs, pairs("c1", "c2", "c3", "c4"), budget=25, clock=clock)
    if [row.get("reason") for row in rows] != [None, None, "budget", "budget"]:
        failures += fail(f"the budget did not end the shard: {rows}")
    if hr.sums(rows)["excluded"] != {"budget": 2} or not hr.sums(rows)["sums"]:
        failures += fail(f"the budget's pairs were not counted: {hr.sums(rows)}")
    return failures


def case_executions_count_each_invocation_and_a_forced_run_counts_the_suite():
    failures = 0
    directory = tempfile.mkdtemp(prefix="history-replay-")
    TMP.append(directory)
    path = os.path.join(directory, "TEST-P.xml")
    with open(path, "w", encoding="utf-8") as handle:
        handle.write('<testsuite><testcase classname="P" name="p[1]"/>'
                     '<testcase classname="P" name="p[2]"/><testcase classname="P" name="r()"/>'
                     '<testcase classname="P" name="r()"/>'
                     '<testcase classname="P" name="s"><skipped/></testcase>'
                     '<testcase classname="P" name="f"><failure/></testcase></testsuite>')
    read = hr.read_reports([path], 0)
    if read.executions != 5 or read.skipped != 1 or read.failed != {("P", "f")}:
        failures += fail(f"executions {read.executions}, skipped {read.skipped}, "
                         f"failed {read.failed}")
    if hr.read_reports([path], os.path.getmtime(path) + 10).executions != 0:
        failures += fail("a report older than the run was read")
    stubs = Stubs()
    _, rows = walk(stubs, pairs("c1"))
    if rows[0]["selected_executions"] != rows[0]["full_executions"] or rows[0]["narrowed"]:
        failures += fail(f"a selecting run that ran everything did not count the suite: {rows[0]}")
    return failures


def case_sums_add_up_and_the_share_is_over_measured_pairs():
    failures = 0
    stubs = Stubs(full={"c2": (hr.BUILD_FAILED, ())}, selected={"c1": ["T::a"], "c4": ["T::a"]})
    _, rows = walk(stubs, pairs("c1", "c2", "c3", "c4"))
    s = hr.sums(rows)
    if (s["drawn"], s["measured"], s["narrowed"]) != (4, 2, 2) or not s["sums"]:
        failures += fail(f"the sums: {s}")
    if (s["selected_executions"], s["full_executions"], s["skipped_share"]) != (2, 8, 0.75):
        failures += fail(f"the share is not over the measured pairs: {s}")
    rows.append({"sha": "x", "outcome": hr.EXCLUDED, "reason": "unheard-of"})
    if hr.sums(rows)["sums"]:
        failures += fail("a reason outside the vocabulary still adds up")
    return failures


def case_optimistic_leaves_out_the_loosened_tests_from_the_safe_run():
    failures = 0
    loosened = {"loosened": [{"test": "[engine:junit-jupiter]/[class:T]/[method:b()]"},
                             {"test": "[engine:junit-jupiter]/[class:U]"}]}
    stubs = Stubs(full={"c1": (hr.OK, ["T::b"])}, explain={"c1": [loosened]})
    _, rows = walk(stubs, pairs("c1", "c2"), explain=True)
    first = rows[0]["optimistic"]
    if first != {"executions": 2, "loosened": 2, "missed": ["T::b"], "flaky": []}:
        failures += fail(f"the optimistic decision: {first}")
    if rows[0]["missed"]:
        failures += fail("the optimistic miss leaked into the safe row")
    if rows[1]["optimistic"] != {"unanswered": True}:
        failures += fail(f"an unanswered ask was not said: {rows[1]['optimistic']}")
    s = hr.sums(rows)["optimistic"]
    if (s["answered"], s["executions"], s["missed"], s["at_risk"]) != (1, 2, 1, 1):
        failures += fail(f"the optimistic sums: {s}")
    return failures


def case_the_isolated_map_selects_and_records_beside_the_shared_one():
    failures = 0
    stubs = Stubs(full={"c1": (hr.OK, ["T::b"])}, selected={"c1": ["T::a", "T::b"]})
    seen = []

    def iso_select(base):
        seen.append(("iso-select", base))
        return hr.Arm(hr.OK, report(["T::a"]), 1.0)

    def iso_record():
        seen.append(("iso-record", stubs.at))
        return hr.Arm(hr.OK, report(SUITE), 1.0)

    _, rows = hr.walk(pairs("c1"), prepare=stubs.prepare, record=stubs.record, select=stubs.select,
                      rerun_alone=stubs.rerun_alone, budget_seconds=1e9, clock=Clock(),
                      isolated={"record": iso_record, "select": iso_select})
    if seen != [("iso-record", "p0"), ("iso-select", "staged-p0"), ("iso-record", "c1")]:
        failures += fail(f"the isolated map's runs: {seen}")
    if rows[0]["isolated"].get("missed") != ["T::b"] or rows[0]["missed"]:
        failures += fail(f"the isolated row: {rows[0]}")
    if hr.sums(rows)["isolated"]["measured"] != 1:
        failures += fail(f"the isolated sums: {hr.sums(rows)}")
    return failures


def case_shards_cover_every_commit_once_in_order():
    failures = 0
    window = pairs(*[f"c{i}" for i in range(11)])
    for count in (1, 3, 4, 11):
        joined = [p for i in range(1, count + 1) for p in hr.shard_of(window, i, count)]
        if joined != window:
            failures += fail(f"{count} shards do not cover the window once in order")
    try:
        hr.parse_shard("5/4")
        failures += fail("a shard past its count was accepted")
    except ValueError:
        pass
    return failures


def case_the_window_is_the_first_parent_commits_after_its_start():
    failures = 0
    root = os.path.join(tempfile.mkdtemp(prefix="history-window-"), "repo")
    TMP.append(os.path.dirname(root))
    repo = Repo(root)
    for name in ("a", "b"):
        repo.commit(name, {f"{name}.txt": name})
    repo.git("checkout", "-q", "-b", "side")
    repo.commit("s", {"s.txt": "s"})
    repo.git("checkout", "-q", "main")
    repo.commit("c", {"c.txt": "c"})
    repo.git("merge", "-q", "--no-ff", "-m", "merge", "side")
    merge = repo.git("rev-parse", "HEAD").strip()
    got = hr.window(root, merge, repo.shas["a"])
    if [p["sha"] for p in got] != [repo.shas["b"], repo.shas["c"], merge]:
        failures += fail(f"the window is not the first-parent commits after its start: {got}")
    if got[-1]["parent"] != repo.shas["c"]:
        failures += fail("a merge's parent is not its first parent")
    if [p["sha"] for p in hr.window(root, merge, commits=2)] != [repo.shas["c"], merge]:
        failures += fail("the last N commits are not the newest first-parent ones")
    return failures


def case_the_plan_sizes_shards_and_the_merge_names_a_missing_one():
    failures = 0
    entries = hr.plan(["jsoup-history", "moshi"], "full", tip=lambda url: "tip")
    jsoup = [e for e in entries if e["project"] == "jsoup-history"]
    if len(jsoup) != 2 or "--from" not in jsoup[0]["args"]:
        failures += fail(f"jsoup-history's plan: {jsoup}")
    latest = hr.plan(["moshi", "jsoup-history"], "latest", tip=lambda url: f"tip-of-{url}")
    if latest[0]["args"][:4] != ["--to", "tip-of-https://github.com/square/moshi.git", "--commits",
                                 "20"]:
        failures += fail(f"the later releases' window: {latest}")
    shenyu = hr.plan(["shenyu"], "full", tip=None)
    budget = int(shenyu[0]["args"][shenyu[0]["args"].index("--budget-minutes") + 1])
    per_shard = -(-200 // len(shenyu))
    if (per_shard + 1) * hr.PAIR_SECONDS["shenyu"] > budget * 60 + hr.PAIR_SECONDS["shenyu"]:
        failures += fail(f"shenyu's shards hold more pairs than their budget: {len(shenyu)} shards, "
                         f"{budget} minutes")
    if "--isolated" not in hr.plan(["grpc-java"], "full", {"grpc-java"}, tip=None)[0]["args"]:
        failures += fail("the isolated arm was not planned")
    directory = tempfile.mkdtemp(prefix="history-merge-")
    TMP.append(directory)
    planned = [{"project": "demo", "shard": i, "of": 3} for i in (1, 2, 3)]
    for index, rows in ((1, pairs("c1")), (3, pairs("c3"))):
        stubs = Stubs()
        _, walked = walk(stubs, rows)
        with open(os.path.join(directory, hr.shard_file("demo", index, 3)), "w") as handle:
            json.dump({"schema": hr.SCHEMA, "project": "demo", "plugin_commit": "abc",
                       "first": rows[0]["sha"], "last": rows[-1]["sha"], "rows": walked}, handle)
    merged = hr.merge(directory, planned)
    if merged["missing"] != ["demo shard 2 of 3"]:
        failures += fail(f"the missing shard was not named: {merged['missing']}")
    if merged["projects"]["demo"]["sums"]["drawn"] != 2:
        failures += fail("a missing shard counted as anything")
    if "demo shard 2 of 3" not in hr.page(merged):
        failures += fail("the page does not name the missing shard")
    return failures


def case_a_poms_own_arg_line_is_staged_to_carry_the_agents():
    failures = 0
    root = tempfile.mkdtemp(prefix="history-argline-")
    TMP.append(root)
    os.makedirs(os.path.join(root, "m"))
    for path, text in (("pom.xml", "<argLine>-Xss640k</argLine>"),
                       ("m/pom.xml", "<argLine>@{argLine} -Xmx1g</argLine>")):
        with open(os.path.join(root, path), "w", encoding="utf-8") as handle:
            handle.write(text)
    hr.prepend_argline(root)
    got = [open(os.path.join(root, p), encoding="utf-8").read() for p in ("pom.xml", "m/pom.xml")]
    if got != ["<argLine>@{argLine} -Xss640k</argLine>", "<argLine>@{argLine} -Xmx1g</argLine>"]:
        failures += fail(f"the staged argLines: {got}")
    return failures


def case_a_root_module_report_reruns_without_a_module():
    failures = 0
    builds = hr.Builds.__new__(hr.Builds)
    builds.reactor, builds.checkout, builds.module = True, "/c", ""
    got = [builds._module_of(p) for p in ("/c/target/surefire-reports/TEST-a.xml",
                                          "/c/m/n/target/surefire-reports/TEST-b.xml")]
    if got != ["", "m/n"]:
        failures += fail(f"the modules of root and nested reports: {got}")
    return failures


def case_the_reactor_explain_asks_in_the_environment_of_the_map():
    builds = hr.Builds.__new__(hr.Builds)
    builds.reactor, builds.counter, builds.logs = True, 0, "/logs"
    builds.run = type("Run", (), {"MAVEN_PLUGIN_VERSION": "1"})
    builds.map_dirs = lambda: []
    calls = []
    builds._maven = lambda arguments, log, select=False: calls.append(arguments)
    builds.explain("base")
    if not calls or "-Dmaven.test.failure.ignore=true" not in calls[0]:
        return fail(f"the reactor explain command: {calls}")
    return 0


def case_a_gradle_run_that_only_failed_tests_is_measured():
    failures = 0
    directory = tempfile.mkdtemp(prefix="history-gradle-")
    TMP.append(directory)
    logs = {"tests": "* What went wrong:\nExecution failed for task ':m:test'.\n> There were "
                     "failing tests.\n\n* Try:\n",
            "both": "* What went wrong:\nCompilation failed.\n\n* What went wrong:\n> There "
                    "were failing tests.\n\n* Try:\n",
            "none": "BUILD FAILED\n"}
    want = {"tests": hr.OK, "both": hr.BUILD_FAILED, "none": hr.BUILD_FAILED}
    for name, text in logs.items():
        path = os.path.join(directory, name)
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(text)
        if hr._gradle_status(1, path) != want[name]:
            failures += fail(f"a {name} log read as {hr._gradle_status(1, path)}")
    if hr._gradle_status(0, "") != hr.OK or hr._gradle_status(hr.STALLED, "") != hr.TIMED_OUT:
        failures += fail("exit codes 0 and stalled are not ok and timed-out")
    return failures


def case_a_selecting_run_that_fails_to_build_is_select_failed():
    failures = 0

    class Broken(Stubs):
        def select(self, base):
            super().select(base)
            return hr.Arm(hr.BUILD_FAILED, report([]), 1.0)

    _, rows = walk(Broken(), pairs("c1", "c2"))
    if [row.get("reason") for row in rows] != ["select-failed", "select-failed"]:
        failures += fail(f"a selecting run that failed to build: {rows}")
    return failures


def case_the_shared_row_lists_only_the_flaky_tests_it_missed():
    failures = 0
    loosened = {"loosened": [{"test": "[engine:junit-jupiter]/[class:T]/[method:b()]"}]}
    stubs = Stubs(full={"c1": (hr.OK, ["T::b", "T::c"])}, selected={"c1": ["T::a", "T::b"]},
                  flaky=["T::b", "T::c"], explain={"c1": [loosened]})
    _, rows = walk(stubs, pairs("c1"), explain=True)
    if rows[0]["flaky"] != ["T::c"] or rows[0]["optimistic"]["flaky"] != ["T::b"]:
        failures += fail(f"the flaky tests: {rows[0]['flaky']}, {rows[0]['optimistic']}")
    return failures


def case_progress_files_the_unwalked_pairs_as_budget():
    failures = 0
    seen = []
    window = pairs("c1", "c2", "c3")
    hr.walk(window, prepare=Stubs().prepare, record=Stubs().record, select=Stubs().select,
            rerun_alone=lambda t, m: False, budget_seconds=1e9, clock=Clock(),
            progress=lambda initial, rows: seen.append(hr.sums(hr.with_unwalked(window, rows))))
    if [(s["measured"], s["excluded"].get("budget", 0), s["sums"]) for s in seen] != \
            [(0, 3, True), (1, 2, True), (2, 1, True), (3, 0, True)]:
        failures += fail(f"the partial files' sums: {seen}")
    return failures


def case_the_merge_names_shards_that_walked_different_windows():
    failures = 0
    directory = tempfile.mkdtemp(prefix="history-chain-")
    TMP.append(directory)
    for index, rows in ((1, pairs("c1")), (2, [{"sha": "x2", "parent": "x1"}])):
        _, walked = walk(Stubs(), rows)
        with open(os.path.join(directory, hr.shard_file("demo", index, 2)), "w") as handle:
            json.dump({"schema": hr.SCHEMA, "project": "demo", "rows": walked}, handle)
    merged = hr.merge(directory, [{"project": "demo", "shard": i, "of": 2} for i in (1, 2)])
    if not any("does not follow" in entry for entry in merged["missing"]):
        failures += fail(f"a break between shards was not named: {merged['missing']}")
    return failures


def case_a_project_that_measured_no_commit_is_named():
    directory = tempfile.mkdtemp(prefix="history-excluded-")
    TMP.append(directory)
    rows = [{"sha": f"c{i}", "parent": f"c{i - 1}", "outcome": hr.EXCLUDED, "reason": "does-not-build"}
            for i in (1, 2)]
    with open(os.path.join(directory, hr.shard_file("demo", 1, 1)), "w") as handle:
        json.dump({"schema": hr.SCHEMA, "project": "demo", "rows": rows}, handle)
    merged = hr.merge(directory, [{"project": "demo", "shard": 1, "of": 1}])
    if merged["missing"] != ["demo: none of its 2 commits measured"] \
            or "demo: none of its 2 commits measured" not in hr.page(merged):
        return fail(f"an all-excluded project was not named: {merged['missing']}")
    return 0


def _record(outcome="narrowed", included=(), excluded=(), **notes):
    decided = ([(t, "included/COVERS_CHANGE") for t in included]
               + [(t, "excluded/NO_EDGE") for t in excluded])
    return {"notes": {"outcome": outcome, **notes}, "included": list(included),
            "decided": decided, "rules": None,
            "verdicts": Counter(verdict for _, verdict in decided)}


def case_every_measured_pair_names_why_it_did_not_narrow():
    failures = 0
    records = {"c1": {"map": _record(included=SUITE)},
               "c2": {},
               "c3": {"a": _record("full-run", included=SUITE, **{"full-run-kind": "setup"}),
                      "b": _record(included=["T::a"], excluded=["T::b"])}}
    stubs = Stubs(selected={"c3": ["T::a"]})
    plain = stubs.select

    def select(base):
        arm = plain(base)
        arm.records = records[stubs.at]
        return arm
    stubs.select = select
    _, rows = walk(stubs, pairs("c1", "c2", "c3"))
    got = [(row.get("cause"), row.get("cause_detail")) for row in rows]
    wanted = [("wide:COVERS_CHANGE", "4 of 4"), ("no-record", None), ("forced:setup", None)]
    if got != wanted:
        failures += fail(f"the pairs' causes were {got}, wanted {wanted}")
    if sorted(rows[2].get("causes") or {}) != ["a", "b"] or "causes" in rows[0]:
        failures += fail(f"the reactor's per-map causes: {rows[2].get('causes')}")
    if rows[2].get("forced") is not None:
        failures += fail("a stub's detail was read as forced")
    if hr.forced(records["c3"]) != {"a": {"outcome": "full-run", "kind": "setup", "reason": None}}:
        failures += fail(f"forced from the records: {hr.forced(records['c3'])}")
    old = {key: value for key, value in rows[0].items() if key not in ("cause", "cause_detail")}
    page = hr.page({"projects": {"demo": {"sums": hr.sums(rows + [old]), "first": "c1",
                                          "last": "c9", "rows": rows + [old]}},
                    "missing": [], "caveats": []})
    for line in ("| demo | `forced:setup` | 1 |", "| demo | `unrecorded` | 1 |",
                 "| demo | `no-record` | 1 |"):
        if line not in page:
            failures += fail(f"the page lacks {line!r}:\n{page}")
    return failures


def test_history_replay():
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
    test_history_replay()
    print("ok")
