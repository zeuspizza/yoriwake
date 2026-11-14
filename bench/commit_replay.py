"""How often a commit people wrote narrows, and why the rest run everything: a decision-only replay.

A sweep's rows are generated edits of one file under a module's source roots, where a path no
build input explains (a README, a workflow file) cannot occur. On real commits such paths are
common. This asks the selector about real commits, without running a test:

For each of a project's last `--commits` first-parent commits up to and including its pin, newest
first, the reverse of the commit's whole diff (every path, a merge as its diff against its first
parent) is applied to the pinned tree, the measured task's test classes are compiled, and
`yoriwakeExplain<Task>` is asked with `-Pyoriwake.base=HEAD` what a selecting run would do. The map
was captured at the pin, so map, compiled classes and classpath agree except for that commit's own
paths. The tree is put back to the pin, and to the files present when the map was captured,
before and after each commit.

Every drawn commit ends in exactly one outcome:

- `narrowed`;
- `forced`, with its cause: `refusal:<kind>` when the explanation carries the finer `refusalKind`,
  else `full-run:<kind>` from its `fullRunKind`;
- `unrecorded`: it would run everything and names no kind;
- `not-asked`, with its reason: `duplicate` (the same patch as a newer drawn commit),
  `empty-diff`, `does-not-apply`, `does-not-compile`, `build-stalled` (the compile was stopped
  for writing nothing), `unanswered` (the explain task wrote no explanation) or `budget` (the
  time budget ran out first).

What the figure may claim is printed with it (`CAVEATS`): a share of commits, not of seconds; a
decision, not a run, so it says nothing about recall.

    python3 bench/commit_replay.py bench/projects/okio.toml --commits 8 --out FILE
    python3 bench/commit_replay.py --merge DIR --planned okio moshi --out commit-replay.json

The first form runs after `run.py` has captured the project's map, in the same checkout. The
second sums the files of a sweep's record jobs, found as `DIR/replay-<project>/**/<project>/
commit-replay.json`, into one figure.
"""

import argparse
import json
import os
import sys
import time
import tomllib

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import revert  # noqa: E402
from process import STALLED  # noqa: E402

SCHEMA = "commit-replay/v1"
FILE = "commit-replay.json"
DEFAULT_COMMITS = 8
DEFAULT_BUDGET_MINUTES = 30
WINDOW_RULE = "the most recent first-parent commits up to and including the pin, newest first"

NARROWED, FORCED, UNRECORDED, NOT_ASKED = "narrowed", "forced", "unrecorded", "not-asked"
NOT_ASKED_REASONS = ("duplicate", "empty-diff", "does-not-apply", "does-not-compile",
                     "build-stalled", "unanswered", "budget")
# How many of an explanation's unmappable paths a row keeps.
SAMPLE = 5

CAVEATS = (
    "A share of commits, not of seconds.",
    "A decision, not a run: no test ran, so this says nothing about recall, and a rule of the "
    "test JVM that an explanation cannot see may still widen a narrowed run.",
    "One window of commits per project at this sweep's pins, counted per project: two projects "
    "measured in one clone count a commit once each, under their own maps.",
    "Each commit is reverted alone on the pinned tree, so the tests selected come from the pin's "
    "coverage, not from the commit's parent's.",
    "Commits not asked are counted apart, never as narrowed or forced.",
    "Each commit is asked under the CI values of the build that recorded the map, so a build "
    "that a per-run CI variable forces is not seen here; a sweep's selecting rows show it.",
)


def classify(explanation):
    """`(outcome, detail)` of one explanation: the cause of a forced commit, the reason of one
    not asked, else None. The finer token wins and names its vocabulary."""
    if not isinstance(explanation, dict) or not isinstance(explanation.get("fullRun"), bool):
        return NOT_ASKED, "unanswered"
    if not explanation["fullRun"]:
        return NARROWED, None
    if explanation.get("refusalKind"):
        return FORCED, f"refusal:{explanation['refusalKind']}"
    if explanation.get("fullRunKind"):
        return FORCED, f"full-run:{explanation['fullRunKind']}"
    return UNRECORDED, None


def read_explanation(path, since):
    """The explanation written at or after `since`, or None: an older file is another run's."""
    try:
        if os.path.getmtime(path) < since:
            return None
        with open(path, encoding="utf-8") as handle:
            return json.load(handle)
    except (OSError, ValueError):
        return None


def distribution(rows):
    """Counts over `rows`, with the sums a reader would otherwise have to check: narrowed, forced
    and unrecorded make up the replayed commits, the replayed and the not asked make up the drawn,
    and the causes make up the forced. `sums` is false when a row carries anything else."""
    causes, not_asked = {}, {}
    counts = {NARROWED: 0, FORCED: 0, UNRECORDED: 0}
    fewer = 0
    for row in rows:
        outcome = row.get("outcome")
        if outcome in counts:
            counts[outcome] += 1
        if outcome == FORCED and row.get("cause"):
            causes[row["cause"]] = causes.get(row["cause"], 0) + 1
        if outcome == NOT_ASKED and row.get("reason") in NOT_ASKED_REASONS:
            not_asked[row["reason"]] = not_asked.get(row["reason"], 0) + 1
        if outcome == NARROWED and _fewer(row):
            fewer += 1
    return _checked({"drawn": len(rows), "replayed": sum(counts.values()),
                     "narrowed": counts[NARROWED], "selected_fewer": fewer,
                     "forced": counts[FORCED], "unrecorded": counts[UNRECORDED],
                     "causes": _by_count(causes), "not_asked": _by_count(not_asked)})


def _fewer(row):
    selected, known = row.get("selected"), row.get("known")
    return isinstance(selected, int) and isinstance(known, int) and selected < known


def _by_count(counts):
    return dict(sorted(counts.items(), key=lambda entry: (-entry[1], entry[0])))


def _checked(result):
    result["sums"] = (result["narrowed"] + result["forced"] + result["unrecorded"]
                      == result["replayed"]
                      and result["replayed"] + sum(result["not_asked"].values()) == result["drawn"]
                      and sum(result["causes"].values()) == result["forced"])
    return result


def total(distributions):
    """Several projects' distributions as one."""
    result = {key: 0 for key in ("drawn", "replayed", "narrowed", "selected_fewer", "forced",
                                 "unrecorded")}
    causes, not_asked = {}, {}
    for entry in distributions:
        for key in result:
            result[key] += entry.get(key) or 0
        for name, count in (entry.get("causes") or {}).items():
            causes[name] = causes.get(name, 0) + count
        for name, count in (entry.get("not_asked") or {}).items():
            not_asked[name] = not_asked.get(name, 0) + count
    result.update(causes=_by_count(causes), not_asked=_by_count(not_asked))
    _checked(result)
    result["sums"] = result["sums"] and all(entry.get("sums") for entry in distributions)
    return result


def whole_patch(checkout, sha):
    """The diff of `sha` against its first parent over every path, ready for `git apply -R`."""
    return revert._git(checkout, "diff", "--binary", "-M", "--full-index",
                       revert._parent(checkout, sha), sha).stdout


def draw(checkout, pin, count):
    """The window: `[{"sha", "patch", "duplicate"}]`, newest first, at most `count`. A commit
    whose patch a newer drawn commit already made stays in the window, marked."""
    shas = revert._git(checkout, "rev-list", "--first-parent", "-n", str(count), pin).stdout.split()
    seen, drawn = set(), []
    for sha in shas:
        text = whole_patch(checkout, sha)
        patch_id = revert._patch_id(checkout, text) if text else None
        drawn.append({"sha": sha, "patch": text, "duplicate": patch_id in seen})
        if patch_id:
            seen.add(patch_id)
    return drawn


def replay(checkout, drawn, *, reset, compile_tests, explain, budget_seconds, clock=time.monotonic):
    """One row per drawn commit, in order.

    `reset()` puts the tree back to the pin and returns whether it did; `compile_tests(sha)`
    compiles the reverted tree and returns its exit code; `explain(sha)` asks the explain task and
    returns the explanation it wrote, or None. Once `budget_seconds` have passed, the commits
    left are not asked.
    """
    rows, started = [], clock()
    try:
        for commit in drawn:
            row = {"sha": commit["sha"], "outcome": NOT_ASKED}
            if commit["duplicate"]:
                row["reason"] = "duplicate"
            elif not commit["patch"]:
                row["reason"] = "empty-diff"
            elif clock() - started >= budget_seconds:
                row["reason"] = "budget"
            else:
                began = clock()
                row.update(_one(checkout, commit, reset, compile_tests, explain))
                row["seconds"] = round(clock() - began, 1)
            rows.append(row)
            print(f"  {row['sha'][:12]} {row['outcome']} "
                  f"{row.get('cause') or row.get('reason') or ''}".rstrip(), flush=True)
    finally:
        # Also on an interrupt or a build that could not be launched: no reverted commit stays.
        restored = reset()
    if not restored:
        raise SystemExit(f"{checkout} could not be put back to the pin after the replay")
    return rows


def _one(checkout, commit, reset, compile_tests, explain):
    if not reset():
        raise SystemExit(f"{checkout} could not be put back to the pin; stopping the replay")
    # `git apply` changes nothing unless the whole patch applies.
    if revert._git(checkout, "apply", "-R", "--whitespace=nowarn", "-", stdin=commit["patch"],
                   check=False).returncode != 0:
        return {"reason": "does-not-apply"}
    compiled = compile_tests(commit["sha"])
    if compiled == STALLED:
        return {"reason": "build-stalled"}
    if compiled != 0:
        return {"reason": "does-not-compile"}
    explanation = explain(commit["sha"])
    outcome, detail = classify(explanation)
    if outcome == NOT_ASKED:
        return {"reason": detail}
    row = {"outcome": outcome, "selected": explanation.get("selected"),
           "known": explanation.get("known"),
           "unmappable_paths": explanation.get("unmappablePaths"),
           "unmappable_sample": list(explanation.get("unmappablePathsSample") or [])[:SAMPLE]}
    if detail:
        row["cause"] = detail
    return row


def at_pin(checkout, head):
    """Whether the checkout is on `head` with nothing changed and nothing untracked."""
    now = revert._git(checkout, "rev-parse", "HEAD").stdout.strip()
    return now == head and not revert._git(checkout, "status", "--porcelain").stdout.strip()


def not_replayed(project, reason):
    return {"schema": SCHEMA, "project": project["name"], "replayed": False, "reason": reason,
            "rows": [], "distribution": distribution([])}


def explain_task(task_path):
    """`:m:test` as `:m:yoriwakeExplainTest`."""
    head, _, name = task_path.rpartition(":")
    return f"{head}:yoriwakeExplain{name[:1].upper()}{name[1:]}" if head else \
        f"yoriwakeExplain{name[:1].upper()}{name[1:]}"


def replay_project(project, checkout, commits, budget_minutes, scratch=None):
    """The project's file: its rows and distribution, or why it was not replayed."""
    import run  # noqa: PLC0415  (Gradle, the restore and the map's place are the sweep's own)
    import sweep_data  # noqa: PLC0415

    if not project.get("url") or project.get("commit") in (None, "local"):
        return not_replayed(project, "no upstream history")
    if run.maven_build.is_maven(project):
        return not_replayed(project, "a Maven project")
    module = project.get("module") or ""
    map_dir = run.find_map(checkout, module, run.test_task_name(project))
    if not map_dir:
        return not_replayed(project, "no map in the checkout")
    try:
        with open(os.path.join(map_dir, sweep_data.CAPTURE_STAMP_FILE), encoding="utf-8") as handle:
            stamp = json.load(handle)
    except (OSError, ValueError):
        return not_replayed(project, "the map carries no capture stamp")
    if stamp.get("project_commit") != project["commit"]:
        return not_replayed(project, "the map was captured at another commit")
    if scratch is None:
        scratch = run.results_location(run.RESULTS, os.path.join(HERE, "logs"), project["name"],
                                       run.DEFAULT_SHAPES, stamp.get("arm") == "isolated")[1]
    logs = os.path.join(scratch, "commit-replay")
    os.makedirs(logs, exist_ok=True)
    snapshot = run.read_untracked_snapshot(os.path.join(scratch, run.UNTRACKED_SNAPSHOT), stamp)
    head = revert._git(checkout, "rev-parse", "HEAD").stdout.strip()
    task_path = run.test_task_path(project)
    classes = run.test_classes_task(project)
    classes_args = [f":{module}:{classes}" if module else classes]

    def reset():
        if not run.restore(checkout):
            return False
        if snapshot is not None:
            run.reset_untracked(checkout, project, snapshot)
        return True

    created = set()

    def compile_tests(sha):
        # A file the reverse brought back is untracked, and is the tree this commit is asked on.
        created.clear()
        if snapshot is not None:
            created.update(run.untracked_files(checkout, project) - set(snapshot))
        return run.gradle(checkout, project, classes_args,
                          os.path.join(logs, f"compile-{sha[:12]}.log"))[0]

    def explain(sha):
        # The compile may have written an ignored file the selector would read as a change.
        if snapshot is not None:
            run.reset_untracked(checkout, project, set(snapshot) | created)
        since = time.time()
        run.gradle(checkout, project,
                   [*run.INIT_SCRIPT_ARGS, explain_task(task_path), "-Pyoriwake.base=HEAD",
                    *run.TIMING_ARM_FLAGS], os.path.join(logs, f"explain-{sha[:12]}.log"))
        return read_explanation(os.path.join(map_dir, "explain.json"), since)

    if not reset() or not at_pin(checkout, head):
        return not_replayed(project, "the checkout is not clean at the pin")
    started = time.monotonic()
    drawn = draw(checkout, project["commit"], commits)
    rows = replay(checkout, drawn, reset=reset, compile_tests=compile_tests, explain=explain,
                  budget_seconds=budget_minutes * 60)
    return {"schema": SCHEMA, "project": project["name"], "replayed": True,
            "pin": project["commit"], "plugin_commit": stamp.get("plugin_commit"),
            "commits": commits, "window": WINDOW_RULE, "budget_minutes": budget_minutes,
            "budget_reached": any(row.get("reason") == "budget" for row in rows),
            "untracked_reset": snapshot is not None,
            "tree_restored": at_pin(checkout, head),
            "seconds": round(time.monotonic() - started, 1),
            "rows": rows, "distribution": distribution(rows)}


def _find(directory, name):
    """A record job's file for `name` under `directory/replay-<name>`, or None."""
    for root, _, names in os.walk(os.path.join(directory, f"replay-{name}")):
        if FILE in names and os.path.basename(root) == name:
            return os.path.join(root, FILE)
    return None


def merge(directory, planned, previous=None):
    """One figure from the planned projects' files. A project with no readable file is listed as
    missing, one not replayed with its reason; neither counts as zero. `previous` is the merged
    file of an earlier run this one completes: its projects stay unless this run planned them."""
    projects, missing, skipped = {}, [], {}
    if previous:
        projects.update({name: entry for name, entry in (previous.get("projects") or {}).items()
                         if name not in planned})
        skipped.update({name: reason for name, reason
                        in (previous.get("not_replayed") or {}).items() if name not in planned})
        missing += [name for name in previous.get("missing") or [] if name not in planned]
    for name in planned:
        path = _find(directory, name)
        try:
            with open(path, encoding="utf-8") as handle:
                data = json.load(handle)
            if data.get("schema") != SCHEMA:
                raise ValueError(data.get("schema"))
            if not data.get("replayed"):
                skipped[name] = data.get("reason") or "not said"
                continue
            projects[name] = {key: data.get(key) for key in
                              ("pin", "plugin_commit", "commits", "budget_reached",
                               "tree_restored", "seconds")}
            # Counted again from the rows, so the figure never rests on a file's own arithmetic.
            projects[name]["distribution"] = distribution(data["rows"])
        except (OSError, ValueError, TypeError, KeyError, AttributeError):
            missing.append(name)
    def distinct(key):
        return sorted({str(entry[key]) for entry in projects.values() if entry.get(key) is not None})

    return {"schema": SCHEMA, "window": WINDOW_RULE,
            "commits": distinct("commits"), "plugin_commits": distinct("plugin_commit"),
            "figure": total([entry["distribution"] for entry in projects.values()]),
            "projects": dict(sorted(projects.items())), "missing": sorted(missing),
            "not_replayed": dict(sorted(skipped.items())),
            "cut_short": sorted(name for name, entry in projects.items()
                                if entry.get("budget_reached")),
            "caveats": list(CAVEATS)}


def _write(path, data):
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(data, handle, indent=1, sort_keys=True)
        handle.write("\n")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("project", nargs="?", help="the project file to replay")
    parser.add_argument("--checkout", help="the project's checkout (default: where run.py put it)")
    parser.add_argument("--scratch", help="the project's log directory (default: run.py's)")
    parser.add_argument("--commits", type=int, default=DEFAULT_COMMITS)
    parser.add_argument("--budget-minutes", type=float, default=DEFAULT_BUDGET_MINUTES)
    parser.add_argument("--merge", metavar="DIR", help="sum the record jobs' files under DIR")
    parser.add_argument("--planned", nargs="*", default=[], help="with --merge: the projects")
    parser.add_argument("--completes", metavar="FILE",
                        help="with --merge: the merged file of the run this one completes")
    parser.add_argument("--out", required=True)
    args = parser.parse_args(argv)
    if args.merge:
        previous = None
        if args.completes and os.path.isfile(args.completes):
            with open(args.completes, encoding="utf-8") as handle:
                previous = json.load(handle)
        merged = merge(args.merge, args.planned, previous)
        _write(args.out, merged)
        figure = merged["figure"]
        print(f"{figure['narrowed']} of {figure['replayed']} replayed commits narrowed, "
              f"{figure['drawn']} drawn over {len(merged['projects'])} projects; "
              f"missing: {merged['missing'] or 'none'}")
        return 0 if figure["sums"] else 1
    if not args.project:
        parser.error("name a project file, or --merge DIR")
    with open(args.project, "rb") as handle:
        project = tomllib.load(handle)
    project.setdefault("name", os.path.splitext(os.path.basename(args.project))[0])
    import run  # noqa: PLC0415
    # Absolute: a build is launched from inside the checkout, by its wrapper's path.
    checkout = os.path.abspath(args.checkout) if args.checkout else run.checkout_dir(project)
    scratch = os.path.abspath(args.scratch) if args.scratch else None
    result = replay_project(project, checkout, args.commits, args.budget_minutes, scratch)
    _write(args.out, result)
    if not result["replayed"]:
        print(f"{project['name']}: not replayed ({result['reason']})")
        return 0
    counts = result["distribution"]
    print(f"{project['name']}: {counts['narrowed']} of {counts['replayed']} replayed commits "
          f"narrowed, {counts['drawn']} drawn; forced by {counts['causes'] or 'nothing'}; "
          f"not asked {counts['not_asked'] or 'none'}")
    if not result["tree_restored"]:
        print(f"{project['name']}: THE CHECKOUT IS NOT BACK AT THE PIN", file=sys.stderr)
        return 1
    return 0 if counts["sums"] else 1


if __name__ == "__main__":
    sys.exit(main())
