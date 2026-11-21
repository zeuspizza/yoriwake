"""Tests run and missed over consecutive real commits: a history replay that runs the tests.

`commit_replay.py` asks the selector about real commits without running a test, so it cannot say
what a commit ran or missed. This walks a window of a project's first-parent history commit by
commit, as a CI that refreshes its map on every merge would:

- at the parent of the window's first commit, a recording full run: the map, and that commit's
  truth;
- for each commit C after it: a selecting run against the map recorded at C's parent (its base is
  the parent as the harness staged it, so the staging commits are in no change set), then a
  recording full run at C, which is both C's ground truth and the map for C's child.

Test executions are the `testcase` entries of the JUnit XML reports each run wrote, each
parameterised or repeated invocation counted; skipped entries are counted apart. A failure at risk
is a test that failed in C's full run and passed, or did not exist, at the parent; it is missed when
the selecting run did not run it. Each missed test is re-run alone (its class, without the plugin)
at C up to three times, and one that passes is listed as flaky and not counted. Tests failing at
both the parent and C are counted apart, never as missed.

Optimistic mode is a decision, not a run: before each selecting run, the explain task is asked with
`yoriwake.mode=optimistic`, and the tests it loosens are taken out of the safe selecting run's
executions. With `--isolated` (Gradle only), a second map recorded with a fresh test JVM per class
walks beside the shared one: a second selecting run against it and a second recording per commit.

A project file with `scope = "reactor"` (Maven only) runs the whole reactor's Surefire tests,
`mvn test` at the root with every module, instead of the one measured module of a sweep's arms.

Every commit of the window ends in one outcome: `measured`, or `excluded` with its reason:
`does-not-build` (C's full run failed for a reason other than tests), `not-staged` (the harness
could not stage C), `parent-did-not-build` or `parent-timed-out` (no map and no truth at the
parent), `timed-out` (C's full run), `select-failed` (the selecting run failed for a non-test
reason) or `budget` (the time budget ran out first). After an excluded commit the walk records again
at the next one, so every measured pair is one commit's change.

    python3 bench/history_replay.py bench/projects/jsoup-history.toml --shard 1/4 --out FILE
    python3 bench/history_replay.py plan --window full --projects jsoup-history moshi
    python3 bench/history_replay.py merge DIR --plan PLAN.json --out history.json
    python3 bench/history_replay.py page history.json
"""

import argparse
import json
import math
import os
import re
import subprocess
import sys
import time
import tomllib
import xml.etree.ElementTree as ET
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import commit_replay  # noqa: E402
import maven_build  # noqa: E402
from process import STALLED  # noqa: E402

SCHEMA = "history-replay/v1"
MEASURED, EXCLUDED = "measured", "excluded"
EXCLUDED_REASONS = ("does-not-build", "not-staged", "parent-did-not-build", "parent-timed-out",
                    "timed-out", "select-failed", "budget")
OK, BUILD_FAILED, TIMED_OUT = "ok", "build-failed", "timed-out"
RERUNS = 3
DEFAULT_BUDGET_MINUTES = 300
# How many failing tests a row names; the counts are always whole.
NAMED = 50
# The corpus projects walk this many first-parent commits up to their pin.
CORPUS_COMMITS = 50
LATEST_COMMITS = 20
# A shard's commits fit about this much of a six-hour job.
SHARD_SECONDS = 5 * 3600
# Seconds per pair on a runner: one recording full run plus one selecting run. Estimates from each
# project's full-run size until measured pairs replace them; they size shards, nothing else.
PAIR_SECONDS = {"shenyu": 2700, "httpcomponents-client": 540, "commons-io": 540,
                "jsoup-history": 180, "moshi": 580, "resilience4j": 580, "grpc-java": 580,
                "grails-core": 1800}
# What a second, isolated map adds per pair.
ISOLATED_PAIR_SECONDS = 720
# A Maven run of the whole reactor is stopped after this long and the commit filed `timed-out`.
REACTOR_TIMEOUT_SECONDS = 2 * 3600
# Blastradius's published figures over the same windows (its HISTORICAL_REPLAY_ANALYSIS.md): the
# window and its full-suite executions over 200 pairs.
BLASTRADIUS = {
    "shenyu": {"window": "69cd1d5..3a411e0", "pairs": 200, "full_executions": 527508},
    "httpcomponents-client": {"window": "ef34bfa..4dae8da", "pairs": 200,
                              "full_executions": 443593},
    "jsoup-history": {"window": "b62e362..9d2241f", "pairs": 200, "full_executions": 351882},
    "commons-io": {"window": "d403237..ffd9dda", "pairs": 200, "full_executions": 1178122},
}
CAVEATS = (
    "Real history rarely fails a test, so missed failures of few at risk is weak evidence on its "
    "own; the mutation sweep remains the recall figure.",
    "A recording run's failures stand for a plain run's.",
    "Optimistic mode is the explain task's decision on the same tree, not a run.",
    "A missed test re-run alone is its class run without the plugin, three times at most.",
    "Commits excluded are counted apart, never as measured.",
)


class Report:
    """What one run's JUnit XML says: executions per test key, failing keys, skipped entries, and
    the module directory each key's report sits in."""

    def __init__(self):
        self.counts, self.failed, self.skipped, self.modules = Counter(), set(), 0, {}

    @property
    def executions(self):
        return sum(self.counts.values())

    @property
    def ran(self):
        return set(self.counts)


class Arm:
    """One build: its status (`ok`, `build-failed`, `timed-out`), report and seconds."""

    def __init__(self, status, report=None, seconds=None, detail=None):
        self.status, self.report, self.seconds, self.detail = status, report or Report(), seconds, detail


def key(case):
    """A testcase's `(class, name)`, with any identity hash taken out, as `run.stable_name` does."""
    import run  # noqa: PLC0415
    return run.stable_name((case.get("classname") or "", (case.get("name") or "").removesuffix("()")))


def read_reports(paths, since, module_of=lambda path: ""):
    """A `Report` from the `TEST-*.xml` files among `paths` written at or after `since`."""
    report = Report()
    for path in paths:
        try:
            if os.path.getmtime(path) < since:
                continue
            root = ET.parse(path).getroot()
        except (OSError, ET.ParseError):
            continue
        for case in root.iter("testcase"):
            if case.find("skipped") is not None:
                report.skipped += 1
                continue
            name = key(case)
            report.counts[name] += 1
            report.modules.setdefault(name, module_of(path))
            if case.find("failure") is not None or case.find("error") is not None:
                report.failed.add(name)
    return report


def named(keys):
    return [f"{cls}::{name}" for cls, name in sorted(keys)]


def loosened_keys(explanations, ran):
    """The keys of `ran` that the optimistic explanations leave out: a recorded id naming a method
    matches that method, one naming only a class matches every test of the class."""
    import run  # noqa: PLC0415
    methods, classes = set(), set()
    for explanation in explanations:
        for entry in explanation.get("loosened") or []:
            test = entry.get("test") if isinstance(entry, dict) else None
            cls = run.record_class(test or "")
            if not cls:
                continue
            plain = run.record_plain_name(test)
            if plain is None:
                classes.add(cls)
            else:
                methods.add((cls, plain))
    return {k for k in ran if k in methods or k[0] in classes}


def measure(selected, full, parent, rerun_alone, explanations=None):
    """A measured pair's fields from the selecting run, C's full run, the parent's full run and,
    when asked, the optimistic explanations (None: not asked; []: none answered)."""
    at_risk = full.failed - parent.failed
    both = full.failed & parent.failed
    missed = at_risk - selected.ran
    optimistic = None
    if explanations:
        left_out = loosened_keys(explanations, selected.ran)
        optimistic_ran = selected.ran - left_out
        candidates = at_risk - optimistic_ran
    else:
        candidates = missed
    flaky = {test for test in candidates if rerun_alone(test, selected.modules.get(test)
                                                         or full.modules.get(test))}
    missed -= flaky
    if explanations:
        opt_missed = candidates - flaky
        optimistic = {"executions": selected.executions - sum(selected.counts[k] for k in left_out),
                      "loosened": len(left_out), "missed": named(opt_missed)}
    elif explanations is not None:
        optimistic = {"unanswered": True}
    return {"selected_executions": selected.executions, "full_executions": full.executions,
            "full_skipped": full.skipped, "narrowed": selected.executions < full.executions,
            "failed_at_parent": len(parent.failed), "failed_at_commit": len(full.failed),
            "failed_at_commit_named": named(full.failed)[:NAMED],
            "at_risk": named(at_risk), "missed": named(missed), "flaky": named(flaky),
            "failing_both": len(both), "optimistic": optimistic}


def walk(pairs, *, prepare, record, select, rerun_alone, budget_seconds, explain=None,
         isolated=None, clock=time.monotonic):
    """`(initial, rows)`: the recording at the window's first parent and one row per pair, in
    order.

    `pairs` are `{"sha", "parent"}`, oldest first, each one's parent the one before it.
    `prepare(sha)` checks the commit out and stages it, returning the staged commit or None;
    `record()` and `select(base)` run the full recording and the selecting run as `Arm`s;
    `explain(base)` returns the optimistic explanations, or None when none answered;
    `rerun_alone(test, module)` says whether a test passed in any of its lone re-runs.
    `isolated`, when given, is `{"record", "select"}` for the isolated map beside the shared one;
    its `select(base)` runs against that map and its `record()` re-records it at the commit.
    Once `budget_seconds` have passed, the pairs left are excluded `budget`.
    """
    started = clock()
    rows = []
    base, truth, reason = None, None, None
    iso_ready = False
    staged = prepare(pairs[0]["parent"]) if pairs else None
    initial = {"sha": pairs[0]["parent"] if pairs else None}
    if pairs and staged is None:
        reason = "parent-did-not-build"
        initial["status"] = "not-staged"
    elif pairs:
        full = record()
        initial.update(status=full.status, executions=full.report.executions,
                       seconds=full.seconds)
        base, truth, reason = _after(full, staged)
        if isolated is not None and base is not None:
            iso_ready = isolated["record"]().status == OK
    for pair in pairs:
        row = {"sha": pair["sha"], "parent": pair["parent"]}
        if clock() - started >= budget_seconds:
            rows.append({**row, "outcome": EXCLUDED, "reason": "budget"})
            continue
        began = clock()
        staged = prepare(pair["sha"])
        if staged is None:
            row.update(outcome=EXCLUDED, reason="not-staged")
            base, truth, reason, iso_ready = None, None, "parent-did-not-build", False
        elif base is None:
            full = record()
            row.update(outcome=EXCLUDED, reason=reason, full_status=full.status)
            base, truth, reason = _after(full, staged)
            iso_ready = isolated is not None and base is not None \
                and isolated["record"]().status == OK
        else:
            # Asked before the selecting run, on the map that run reads; [] when none answered.
            explanations = (explain(base) or []) if explain is not None else None
            selected = select(base)
            full = record()
            row["seconds_by_run"] = {"select": selected.seconds, "full": full.seconds}
            if full.status == TIMED_OUT:
                row.update(outcome=EXCLUDED, reason="timed-out")
            elif full.status != OK:
                row.update(outcome=EXCLUDED, reason="does-not-build", detail=full.detail)
            elif selected.status != OK:
                row.update(outcome=EXCLUDED, reason="select-failed", detail=selected.status)
            else:
                row["outcome"] = MEASURED
                row.update(measure(selected.report, full.report, truth, rerun_alone,
                                   explanations))
                row["forced"] = selected.detail
            if isolated is not None and full.status == OK:
                row["isolated"] = _isolated(isolated, base, iso_ready, row, full, truth,
                                            rerun_alone)
                iso_ready = row["isolated"].get("recorded", False)
            base, truth, reason = _after(full, staged)
        row["seconds"] = round(clock() - began, 1)
        rows.append(row)
        print(f"  {pair['sha'][:12]} {row['outcome']} {row.get('reason') or ''}"
              f"{_summary(row)}".rstrip(), flush=True)
    return initial, rows


def _after(full, staged):
    """The map, truth and next exclusion reason after a recording at `staged`."""
    if full.status == OK:
        return staged, full.report, None
    return None, None, "parent-timed-out" if full.status == TIMED_OUT else "parent-did-not-build"


def _isolated(isolated, base, ready, row, full, truth, rerun_alone):
    """The isolated map's selecting run at this commit, measured against the shared full run,
    then its recording here for the next commit. The shared row's fields are not touched."""
    result = {}
    if ready and row["outcome"] == MEASURED:
        selected = isolated["select"](base)
        if selected.status == OK:
            fields = measure(selected.report, full.report, truth, rerun_alone)
            result = {key: fields[key] for key in ("selected_executions", "narrowed", "missed",
                                                   "flaky")}
        else:
            result["select_status"] = selected.status
    result["recorded"] = isolated["record"]().status == OK
    return result


def _summary(row):
    if row.get("outcome") != MEASURED:
        return ""
    return (f" selected {row['selected_executions']}/{row['full_executions']}, "
            f"missed {len(row['missed'])} of {len(row['at_risk'])} at risk")


def sums(rows):
    """A project's counts from its rows, with `sums` true when measured and excluded make up the
    drawn. Shares are over measured pairs only; the narrowed pairs' misses are counted apart."""
    measured = [row for row in rows if row.get("outcome") == MEASURED]
    excluded = Counter(row.get("reason") for row in rows if row.get("outcome") == EXCLUDED)
    narrowed = [row for row in measured if row.get("narrowed")]
    result = {"drawn": len(rows), "measured": len(measured), "narrowed": len(narrowed),
              "excluded": dict(sorted(excluded.items(), key=lambda e: (-e[1], str(e[0])))),
              "selected_executions": sum(r["selected_executions"] for r in measured),
              "full_executions": sum(r["full_executions"] for r in measured),
              "at_risk": sum(len(r["at_risk"]) for r in measured),
              "missed": sum(len(r["missed"]) for r in measured),
              "at_risk_narrowed": sum(len(r["at_risk"]) for r in narrowed),
              "missed_narrowed": sum(len(r["missed"]) for r in narrowed),
              "flaky": sum(len(r["flaky"]) for r in measured),
              "failing_both": sum(r["failing_both"] for r in measured)}
    result["skipped_share"] = (round(1 - result["selected_executions"] / result["full_executions"], 4)
                               if result["full_executions"] else None)
    answered = [r for r in measured if (r.get("optimistic") or {}).get("executions") is not None]
    if any(r.get("optimistic") is not None for r in measured):
        result["optimistic"] = {
            "answered": len(answered),
            "executions": sum(r["optimistic"]["executions"] for r in answered),
            "full_executions": sum(r["full_executions"] for r in answered),
            "missed": sum(len(r["optimistic"]["missed"]) for r in answered),
            "at_risk": sum(len(r["at_risk"]) for r in answered)}
    paired = [r for r in measured if "selected_executions" in (r.get("isolated") or {})]
    if any("isolated" in r for r in rows):
        result["isolated"] = {
            "measured": len(paired),
            "selected_executions": sum(r["isolated"]["selected_executions"] for r in paired),
            "full_executions": sum(r["full_executions"] for r in paired),
            "missed": sum(len(r["isolated"]["missed"]) for r in paired),
            "at_risk": sum(len(r["at_risk"]) for r in paired)}
    result["sums"] = (result["measured"] + sum(excluded.values()) == result["drawn"]
                      and all(reason in EXCLUDED_REASONS for reason in excluded))
    return result


def window(checkout, to, start=None, commits=None):
    """`[{"sha", "parent"}]`, oldest first: the first-parent commits after `start` up to and
    including `to`, or the last `commits` of them up to `to`."""
    import revert  # noqa: PLC0415
    if start:
        shas = revert._git(checkout, "rev-list", "--first-parent", "--reverse",
                           f"{start}..{to}").stdout.split()
        if commits:
            shas = shas[-commits:]
    else:
        shas = list(reversed(revert._git(checkout, "rev-list", "--first-parent", "-n",
                                         str(commits or CORPUS_COMMITS), to).stdout.split()))
    pairs = []
    for sha in shas:
        parent = revert._git(checkout, "rev-parse", "--verify", "--quiet", f"{sha}^1").stdout.strip()
        if parent:
            pairs.append({"sha": sha, "parent": parent})
    return pairs


def parse_shard(text):
    index, _, count = (text or "1/1").partition("/")
    index, count = int(index), int(count or 1)
    if not 1 <= index <= count:
        raise ValueError(f"shard {text}: not i/n with 1 <= i <= n")
    return index, count


def shard_of(pairs, index, count):
    """The `index`-th of `count` runs of consecutive pairs, sizes differing by one at most."""
    size, extra = divmod(len(pairs), count)
    start = (index - 1) * size + min(index - 1, extra)
    return pairs[start:start + size + (1 if index <= extra else 0)]


# ---- builds -------------------------------------------------------------------------------------

def reactor(project):
    """Whether the project's file asks for the whole Maven reactor."""
    if project.get("scope") == "reactor":
        if not maven_build.is_maven(project):
            raise SystemExit(f"{project['name']}: scope = \"reactor\" is for Maven projects")
        return True
    return False


def _gradle_status(code, log):
    import run  # noqa: PLC0415
    if code == 0:
        return OK
    if code == STALLED:
        return TIMED_OUT
    try:
        with open(log, encoding="utf-8", errors="replace") as handle:
            blocks = run.FAILURE_BLOCK.findall(handle.read())
    except OSError:
        blocks = []
    tests_only = blocks and all("failing tests" in b or "tests completed" in b for b in blocks)
    return OK if tests_only else BUILD_FAILED


class Builds:
    """The runs of one project in one checkout, logged under `logs`."""

    def __init__(self, project, checkout, logs, isolated_map=False):
        import run  # noqa: PLC0415
        self.run, self.project, self.checkout, self.logs = run, project, checkout, logs
        self.maven = maven_build.is_maven(project)
        self.reactor = reactor(project)
        if self.maven and not self.reactor:
            raise SystemExit(f"{project['name']}: a Maven project walks its whole reactor; set "
                             f"scope = \"reactor\"")
        if isolated_map and self.maven:
            raise SystemExit(f"{project['name']}: the isolated map is not proven on Maven")
        self.module = project.get("module") or ""
        self.task = run.test_task_path(project)
        self.counter = 0
        os.makedirs(logs, exist_ok=True)

    def _log(self, stem):
        self.counter += 1
        return os.path.join(self.logs, f"{self.counter:04d}-{stem}.log")

    # The reports a run may have written.
    def report_paths(self):
        if self.reactor:
            found = []
            for root, dirs, files in os.walk(self.checkout):
                dirs[:] = [d for d in dirs if d not in (".git", ".yoriwake", "node_modules")]
                if os.path.basename(root) == "surefire-reports" and \
                        os.path.basename(os.path.dirname(root)) == "target":
                    found += [os.path.join(root, f) for f in files
                              if f.startswith("TEST-") and f.endswith(".xml")]
            return found
        directory = os.path.join(self.checkout, self.run.module_path(self.run.module_dir(self.project)),
                                 self.run.build_dir(self.project), "test-results",
                                 self.run.test_task_name(self.project))
        try:
            return [os.path.join(directory, f) for f in os.listdir(directory) if f.endswith(".xml")]
        except OSError:
            return []

    def _module_of(self, path):
        relative = os.path.relpath(path, self.checkout).replace(os.sep, "/")
        return relative.split("/target/surefire-reports/")[0] if self.reactor else self.module

    def _maven(self, arguments, log, select=False):
        maven_build.remove_stale_resources(self.checkout)
        command = ([maven_build.executable(self.checkout), "-B", "--no-transfer-progress",
                    "-Dstyle.color=never", *arguments,
                    f"-D{maven_build.VERSION_PROPERTY}={self.run.MAVEN_PLUGIN_VERSION}",
                    *maven_build.GUEST_OFF, *maven_build.fork_cap_flags(self.run.fork_cap(self.project)),
                    *self.run.guest_flags(self.project)])
        env = self.run.ci_environment(["-Pyoriwake.select"] if select else [],
                                      maven_build.environment(self.project, self.run.find_jdk))
        code, seconds = self.run.run(command, self.checkout, log, timeout=REACTOR_TIMEOUT_SECONDS,
                                     env=env)
        status = OK if code == 0 else TIMED_OUT if code == STALLED else BUILD_FAILED
        return status, seconds

    def _arm(self, stem, gradle_args, maven_args, select=False):
        log = self._log(stem)
        since = time.time()
        if self.reactor:
            status, seconds = self._maven(maven_args, log, select)
        else:
            code, seconds = self.run.gradle(self.checkout, self.project, gradle_args, log)
            status = _gradle_status(code, log)
        report = read_reports(self.report_paths(), since, self._module_of)
        detail = None
        if status == BUILD_FAILED:
            detail = os.path.basename(log)
        elif select:
            detail = self.forced(log)
        return Arm(status, report, seconds, detail)

    def forced(self, log):
        """Why the selecting run ran everything, per measured execution that did; None when none."""
        if not self.reactor:
            reason = self.run.full_run_reason(log, self.task)
            return {self.task: reason} if self.run.is_force_reason(reason) else None
        reasons = {}
        pattern = re.compile(r"^\[yoriwake\] (\S+ surefire:test \([^)]*\)): (.*)$")
        for line in maven_build.plugin_lines(log):
            found = pattern.match(line)
            if found and found.group(1) not in reasons and self.run.is_force_reason(found.group(2)):
                reasons[found.group(1)] = found.group(2)[:300]
        return reasons or None

    def record(self, isolated=False):
        flags = [self.run.ISOLATED_CAPTURE_FLAG] if isolated else []
        return self._arm("record-isolated" if isolated else "record",
                         [*self.run.INIT_SCRIPT_ARGS, self.task, *flags, "--rerun-tasks",
                          "--continue", *self.run.TIMING_ARM_FLAGS],
                         ["test", "-Dmaven.test.failure.ignore=true"])

    def select(self, base, isolated=False):
        return self._arm("select-isolated" if isolated else "select",
                         [*self.run.INIT_SCRIPT_ARGS, self.task, "-Pyoriwake.select",
                          f"-Pyoriwake.base={base}", "--continue", *self.run.TIMING_ARM_FLAGS],
                         ["test", "-Dmaven.test.failure.ignore=true", "-Dyoriwake.select",
                          f"-Dyoriwake.base={base}"], select=True)

    def explain(self, base):
        """The optimistic explanations written by this ask, or None when none was."""
        log = self._log("explain-optimistic")
        since = time.time()
        if self.reactor:
            plugin = (f"{maven_build.PLUGIN_GROUP}:{maven_build.PLUGIN_ARTIFACT}:"
                      f"{self.run.MAVEN_PLUGIN_VERSION}:explain")
            self._maven(["test-compile", plugin, f"-Dyoriwake.base={base}",
                         "-Dyoriwake.mode=optimistic"], log)
            root = maven_build.maps_root(self.checkout)
            paths = [os.path.join(root, d, "explain.json") for d in
                     (os.listdir(root) if os.path.isdir(root) else [])]
        else:
            self.run.gradle(self.checkout, self.project,
                            [*self.run.INIT_SCRIPT_ARGS, commit_replay.explain_task(self.task),
                             f"-Pyoriwake.base={base}", "-Pyoriwake.mode=optimistic",
                             *self.run.TIMING_ARM_FLAGS], log)
            directory = self.run.find_map(self.checkout, self.module,
                                          self.run.test_task_name(self.project))
            paths = [os.path.join(directory, "explain.json")] if directory else []
        found = [e for e in (commit_replay.read_explanation(p, since) for p in paths)
                 if isinstance(e, dict)]
        return found or None

    def rerun_alone(self, test, module):
        """Whether `test` passed in any of up to `RERUNS` runs of its class alone, without the
        plugin. A test the re-runs never report did not pass."""
        cls = test[0]
        for attempt in range(RERUNS):
            log = self._log(f"alone-{attempt + 1}")
            since = time.time()
            if self.reactor:
                self._maven((["-pl", module, "-am"] if module else [])
                            + ["test", f"-Dtest={cls}", "-Dsurefire.failIfNoSpecifiedTests=false",
                               f"-D{maven_build.ENABLED_PROPERTY}=false", "-Djacoco.skip=true",
                               "-Dmaven.test.failure.ignore=true"], log)
            else:
                self.run.gradle(self.checkout, self.project,
                                [self.task, "--tests", cls, *self.run.TIMING_ARM_FLAGS], log)
            report = read_reports(self.report_paths(), since)
            if test in report.counts and test not in report.failed:
                return True
        return False


class MapSwap:
    """Two maps in one map directory, the shared one in place between calls: the isolated one is
    swapped in for its own runs and out again after."""

    def __init__(self, builds, scratch):
        self.builds, self.scratch = builds, scratch
        self.saved = None

    def _dir(self):
        run = self.builds.run
        return run.find_map(self.builds.checkout, self.builds.module,
                            run.test_task_name(self.builds.project))

    def around(self, action):
        run = self.builds.run
        shared = self._dir()
        if shared is None:
            return Arm(BUILD_FAILED, detail="no shared map to swap out")
        kept = run.backup_map(shared, self.scratch, "map-shared")
        if self.saved:
            run.restore_map(self.saved, shared)
        try:
            result = action()
            now = self._dir()
            if now is not None:
                self.saved = run.backup_map(now, self.scratch, "map-isolated")
            return result
        finally:
            run.restore_map(kept, shared)

    def record(self):
        return self.around(lambda: self.builds.record(isolated=True))

    def select(self, base):
        return self.around(lambda: self.builds.select(base, isolated=True))


def replay_project(project, checkout, pairs, budget_minutes, logs, *, explain=True, isolated=False,
                   publish=False):
    """The shard's file: the initial recording, its rows and their sums."""
    import run  # noqa: PLC0415
    if publish:
        run.publish_plugin_under_test(maven=maven_build.is_maven(project))
    builds = Builds(project, checkout, logs, isolated_map=isolated)

    def prepare(sha):
        run.checkout_commit(checkout, sha)
        try:
            run.stage_checkout(checkout, project)
            if builds.maven:
                maven_build.stage(checkout, project)
        except (SystemExit, subprocess.CalledProcessError) as refused:
            print(f"  {sha[:12]} could not be staged: {refused}", flush=True)
            return None
        return subprocess.run(["git", "-C", checkout, "rev-parse", "HEAD"], capture_output=True,
                              text=True, check=True).stdout.strip()

    swap = MapSwap(builds, os.path.join(logs, "maps")) if isolated else None
    started = time.monotonic()
    initial, rows = walk(pairs, prepare=prepare, record=builds.record, select=builds.select,
                         rerun_alone=builds.rerun_alone, budget_seconds=budget_minutes * 60,
                         explain=builds.explain if explain else None,
                         isolated={"record": swap.record, "select": swap.select} if swap else None)
    return {"schema": SCHEMA, "project": project["name"], "plugin_commit": run.plugin_commit(),
            "scope": "reactor" if builds.reactor else f"module {builds.module or '(root)'}",
            "first": pairs[0]["sha"] if pairs else None, "last": pairs[-1]["sha"] if pairs else None,
            "budget_minutes": budget_minutes, "isolated": isolated,
            "seconds": round(time.monotonic() - started, 1), "initial": initial, "rows": rows,
            "sums": sums(rows)}


# ---- plan, merge and page -----------------------------------------------------------------------

def load_project(path):
    with open(path, "rb") as handle:
        project = tomllib.load(handle)
    project.setdefault("name", os.path.splitext(os.path.basename(path))[0])
    return project


def window_args(project, mode):
    """The CLI arguments naming a project's window: its own range in the full run, the latest
    commits of its default branch in the later releases' runs."""
    if mode == "latest":
        return ["--to", "origin/HEAD", "--commits", str(LATEST_COMMITS)]
    if project.get("history_from") and project.get("history_to"):
        return ["--from", project["history_from"], "--to", project["history_to"]]
    return ["--to", project["commit"], "--commits", str(CORPUS_COMMITS)]


def plan(names, mode, isolated=(), projects_dir=os.path.join(HERE, "projects")):
    """One entry per shard job: the project, its shard, the window's arguments and the budget."""
    entries = []
    for name in names:
        project = load_project(os.path.join(projects_dir, f"{name}.toml"))
        args = window_args(project, mode)
        commits = (int(args[args.index("--commits") + 1]) if "--commits" in args
                   else BLASTRADIUS.get(name, {}).get("pairs", CORPUS_COMMITS))
        pair = PAIR_SECONDS.get(name, 900) + (ISOLATED_PAIR_SECONDS if name in isolated else 0)
        count = max(1, math.ceil(commits * pair / SHARD_SECONDS))
        # The last pair started inside the budget still has to finish inside the six hours.
        budget = max(60, 340 - math.ceil(1.5 * pair / 60))
        for index in range(1, count + 1):
            entry_args = [*args, "--shard", f"{index}/{count}", "--budget-minutes", str(budget)]
            if name in isolated:
                entry_args.append("--isolated")
            entries.append({"project": name, "shard": index, "of": count, "args": entry_args})
    return entries


def shard_file(name, index, count):
    return f"history-{name}-{index}of{count}.json"


def merge(directory, planned):
    """`history.json` from the shard files under `directory`: per project its rows in window order
    and their sums. A planned shard with no readable file is named missing, never counted zero."""
    found = {}
    for root, _, files in os.walk(directory):
        for name in files:
            if name.startswith("history-") and name.endswith(".json"):
                found[name] = os.path.join(root, name)
    projects, missing = {}, []
    for entry in planned:
        name = shard_file(entry["project"], entry["shard"], entry["of"])
        try:
            with open(found[name], encoding="utf-8") as handle:
                data = json.load(handle)
            if data.get("schema") != SCHEMA or data.get("project") != entry["project"]:
                raise ValueError(name)
        except (KeyError, OSError, ValueError):
            missing.append(f"{entry['project']} shard {entry['shard']} of {entry['of']}")
            continue
        project = projects.setdefault(entry["project"], {"shards": {}, "plugin_commits": set()})
        project["shards"][entry["shard"]] = data
        project["plugin_commits"].add(data.get("plugin_commit"))
    result = {}
    for name, project in projects.items():
        shards = [project["shards"][i] for i in sorted(project["shards"])]
        rows = [row for shard in shards for row in shard.get("rows") or []]
        result[name] = {"scope": shards[0].get("scope"), "first": shards[0].get("first"),
                        "last": shards[-1].get("last"),
                        "plugin_commits": sorted(str(c) for c in project["plugin_commits"]),
                        "shards": sorted(project["shards"]),
                        "initial": [shard.get("initial") for shard in shards],
                        "sums": sums(rows), "rows": rows,
                        "blastradius": BLASTRADIUS.get(name)}
    return {"schema": SCHEMA, "projects": dict(sorted(result.items())), "missing": missing,
            "caveats": list(CAVEATS)}


def _share(selected, full):
    return f"{selected:,} / {full:,}" if full else "-"


def page(merged):
    """The run page: one row per project, Blastradius's published figures beside its four."""
    lines = ["## Tests run and missed over consecutive real commits", "",
             "| Project | Commit range | Commits (excluded, why) | Test executions selected / full "
             "| Skipped | Missed failures (of at risk) | Narrowed pairs: missed (of at risk) |",
             "|---|---|---|---|---|---|---|"]
    for name, project in merged["projects"].items():
        s = project["sums"]
        excluded = ", ".join(f"{n} {why}" for why, n in s["excluded"].items()) or "none"
        skipped = f"{s['skipped_share']:.1%}" if s["skipped_share"] is not None else "-"
        span = f"{(project['first'] or '')[:7]}..{(project['last'] or '')[:7]}"
        lines.append(f"| {name} | {span} | {s['drawn']} ({excluded}) | "
                     f"{_share(s['selected_executions'], s['full_executions'])} | {skipped} | "
                     f"{s['missed']} of {s['at_risk']} | {s['missed_narrowed']} of "
                     f"{s['at_risk_narrowed']} |")
        theirs = project.get("blastradius")
        if theirs:
            lines.append(f"| {name}, Blastradius (published) | {theirs['window']} | "
                         f"{theirs['pairs']} | - / {theirs['full_executions']:,} | | | |")
    extra = [(n, p["sums"]) for n, p in merged["projects"].items()
             if "optimistic" in p["sums"] or "isolated" in p["sums"]]
    if extra:
        lines += ["", "| Project | Arm | Pairs | Test executions selected / full "
                      "| Missed failures (of at risk) |", "|---|---|---|---|---|"]
        for name, s in extra:
            for arm, key_count, key_selected in (("optimistic (a decision)", "answered", "executions"),
                                                 ("isolated map", "measured", "selected_executions")):
                block = s.get(arm.split()[0])
                if block:
                    lines.append(f"| {name} | {arm} | {block[key_count]} | "
                                 f"{_share(block[key_selected], block['full_executions'])} | "
                                 f"{block['missed']} of {block['at_risk']} |")
    if merged["missing"]:
        lines += ["", f"Missing, so not the planned run: {'; '.join(merged['missing'])}."]
    bad = [name for name, p in merged["projects"].items() if not p["sums"]["sums"]]
    if bad:
        lines += ["", f"Counts that do not add up: {', '.join(bad)}."]
    lines += [""] + [f"- {caveat}" for caveat in merged["caveats"]]
    return "\n".join(lines) + "\n"


def _write(path, data):
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(data, handle, indent=1, sort_keys=True)
        handle.write("\n")


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    if argv[:1] == ["plan"]:
        parser = argparse.ArgumentParser(prog="history_replay.py plan")
        parser.add_argument("--window", choices=("full", "latest"), default="full")
        parser.add_argument("--projects", nargs="+", required=True)
        parser.add_argument("--isolated", nargs="*", default=[])
        args = parser.parse_args(argv[1:])
        print(json.dumps(plan(args.projects, args.window, set(args.isolated))))
        return 0
    if argv[:1] == ["merge"]:
        parser = argparse.ArgumentParser(prog="history_replay.py merge")
        parser.add_argument("directory")
        parser.add_argument("--plan", required=True, help="the plan job's JSON, or a file of it")
        parser.add_argument("--out", required=True)
        args = parser.parse_args(argv[1:])
        text = args.plan
        if os.path.isfile(text):
            with open(text, encoding="utf-8") as handle:
                text = handle.read()
        merged = merge(args.directory, json.loads(text))
        _write(args.out, merged)
        print(f"{len(merged['projects'])} projects merged; missing: {merged['missing'] or 'none'}")
        return 0 if not merged["missing"] and all(p["sums"]["sums"]
                                                  for p in merged["projects"].values()) else 1
    if argv[:1] == ["page"]:
        with open(argv[1], encoding="utf-8") as handle:
            sys.stdout.write(page(json.load(handle)))
        return 0
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("project", help="the project file")
    parser.add_argument("--checkout", help="the project's clone (default: where run.py puts it)")
    parser.add_argument("--from", dest="start", help="the window starts after this commit")
    parser.add_argument("--to", help="the window's last commit (default: the project's pin)")
    parser.add_argument("--commits", type=int, help="the last N first-parent commits up to --to")
    parser.add_argument("--shard", default="1/1", help="i/n: the i-th of n runs of the window")
    parser.add_argument("--budget-minutes", type=float, default=DEFAULT_BUDGET_MINUTES)
    parser.add_argument("--isolated", action="store_true",
                        help="also walk a map recorded with a fresh test JVM per class (Gradle)")
    parser.add_argument("--no-explain", action="store_true", help="skip the optimistic ask")
    parser.add_argument("--publish", action="store_true",
                        help="publish the plugin under test first")
    parser.add_argument("--logs", help="where the runs' logs go (default: bench/logs/<name>/history)")
    parser.add_argument("--out", required=True)
    args = parser.parse_args(argv)
    project = load_project(args.project)
    import run  # noqa: PLC0415
    checkout = os.path.abspath(args.checkout) if args.checkout else run.checkout_project(project)
    index, count = parse_shard(args.shard)
    pairs = shard_of(window(checkout, args.to or project["commit"], args.start, args.commits),
                     index, count)
    if not pairs:
        raise SystemExit(f"{project['name']}: the window holds no commit")
    logs = os.path.abspath(args.logs or os.path.join(HERE, "logs", project["name"], "history",
                                                     f"{index}of{count}"))
    print(f"{project['name']}: shard {index}/{count}, {len(pairs)} commits "
          f"{pairs[0]['sha'][:12]}..{pairs[-1]['sha'][:12]}", flush=True)
    result = replay_project(project, checkout, pairs, args.budget_minutes, logs,
                            explain=not args.no_explain, isolated=args.isolated,
                            publish=args.publish)
    result["shard"], result["of"] = index, count
    _write(args.out, result)
    s = result["sums"]
    print(f"{project['name']}: {s['measured']} of {s['drawn']} measured, selected "
          f"{s['selected_executions']} of {s['full_executions']} executions, missed {s['missed']} "
          f"of {s['at_risk']} at risk; excluded {s['excluded'] or 'none'}")
    return 0 if s["sums"] else 1


if __name__ == "__main__":
    sys.exit(main())
