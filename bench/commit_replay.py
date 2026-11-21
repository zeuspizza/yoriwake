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

A commit the harness classifies version-only (`forcing_mutations._version_only`: a version catalog,
or build-script lines that only move a version) is asked a second time on the same compiled tree
with `-Pyoriwake.narrowDependencies`, under `with_narrow_dependencies`, in the shape of the first
answer; one the time budget leaves no room for is `not-asked: budget`. The figure sums them in a
block of its own (`narrow_dependencies`): forced without the flag and narrowed with it is the
per-jar rule's doing (`per_jar`), as nothing else differs between the two asks. The headline stays
the answer without the flag.

What the figure may claim is printed with it (`CAVEATS`): a share of commits, not of seconds; a
decision, not a run, so it says nothing about recall.

    python3 bench/commit_replay.py bench/projects/okio.toml --commits 8 --out FILE
    python3 bench/commit_replay.py --merge DIR --planned okio moshi --out commit-replay.json

The first form runs after `run.py` has captured the project's map, in the same checkout, or on
the checkout a record job archived, with `--publish` on a runner that has not published the plugin
under test. The second sums the files of a sweep's replay jobs, found as
`DIR/replay-<project>/**/<project>/commit-replay.json`, into one figure.
"""

import argparse
import json
import os
import statistics
import subprocess
import sys
import time
import tomllib

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import forcing_mutations  # noqa: E402
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
    "Version-only commits are as the harness classifies them, which is looser than the plugin's "
    "classifier; one the plugin refuses comes back forced under its kind.",
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


def _share(row):
    selected, known = row.get("selected"), row.get("known")
    return round(selected / known, 4) if isinstance(selected, int) and isinstance(known, int) \
        and known else None


def narrow_dependencies_block(rows):
    """The paired ask over the version-only commits replayed: `asked` a second time, narrowed with
    the flag (and the tests' share, `shares` and its median), `per_jar` (forced without the flag,
    narrowed with it), forced with it by cause, unrecorded, and not asked by reason, with `sums`. None for rows written
    before the paired ask, which carry no `version_only`: absent, never zero."""
    if not any("version_only" in row for row in rows):
        return None
    block = _empty_block()
    for row in rows:
        if not row.get("version_only"):
            continue
        block["replayed"] += 1
        second = row.get("with_narrow_dependencies") or {}
        outcome = second.get("outcome")
        if outcome == NARROWED:
            block["narrowed"] += 1
            block["per_jar"] += row.get("outcome") == FORCED
            if _share(second) is not None:
                block["shares"].append(_share(second))
        elif outcome == FORCED:
            block["forced"] += 1
            cause = second.get("cause") or "unnamed"
            block["causes"][cause] = block["causes"].get(cause, 0) + 1
        elif outcome == UNRECORDED:
            block["unrecorded"] += 1
        else:
            reason = second.get("reason") or "unanswered"
            block["not_asked"][reason] = block["not_asked"].get(reason, 0) + 1
    return _closed_block(block)


def _empty_block():
    return {"replayed": 0, "narrowed": 0, "per_jar": 0, "shares": [], "forced": 0,
            "unrecorded": 0, "causes": {}, "not_asked": {}}


def _closed_block(block):
    block["shares"] = sorted(block["shares"])
    block["median_share"] = (round(statistics.median(block["shares"]), 4)
                             if block["shares"] else None)
    block["causes"], block["not_asked"] = _by_count(block["causes"]), _by_count(block["not_asked"])
    # The share narrowed is over the commits asked twice, never over those the budget cut.
    block["asked"] = block["replayed"] - sum(block["not_asked"].values())
    block["sums"] = (block["narrowed"] + block["forced"] + block["unrecorded"]
                     + sum(block["not_asked"].values()) == block["replayed"]
                     and sum(block["causes"].values()) == block["forced"])
    return block


def total_block(blocks):
    """Several projects' paired-ask blocks as one, or None when no project has one."""
    blocks = [block for block in blocks if block is not None]
    if not blocks:
        return None
    result = _empty_block()
    for block in blocks:
        for key in ("replayed", "narrowed", "per_jar", "forced", "unrecorded"):
            result[key] += block.get(key) or 0
        result["shares"] += block.get("shares") or []
        for field in ("causes", "not_asked"):
            for name, count in (block.get(field) or {}).items():
                result[field][name] = result[field].get(name, 0) + count
    _closed_block(result)
    result["projects"] = len(blocks)
    result["sums"] = result["sums"] and all(block.get("sums") for block in blocks)
    return result


def version_only(checkout, sha):
    """Whether the harness classifies `sha` version-only, as `forcing/v1`'s dependency reverts do."""
    parent = revert._parent(checkout, sha)
    paths = sorted({path for _, old, new in revert._changes(checkout, parent, sha)
                    for path in (old, new)})
    return forcing_mutations._version_only(checkout, parent, sha, paths)


def _git_bytes(checkout, *args, stdin=None):
    """`git` with bytes in and out: a patch read as text loses its CRLFs and any byte that is
    not UTF-8, and then no longer applies."""
    return subprocess.run(["git", "-C", checkout, "-c", "core.quotePath=false", *args],
                          input=stdin, capture_output=True)


def whole_patch(checkout, sha):
    """The diff of `sha` against its first parent over every path, as bytes, ready for
    `git apply -R`."""
    done = _git_bytes(checkout, "diff", "--binary", "-M", "--full-index",
                      revert._parent(checkout, sha), sha)
    if done.returncode != 0:
        raise subprocess.CalledProcessError(done.returncode, "git diff", done.stdout, done.stderr)
    return done.stdout


def _patch_id(checkout, patch):
    out = _git_bytes(checkout, "patch-id", "--stable", stdin=patch).stdout.split()
    return out[0].decode() if out else None


def draw(checkout, pin, count):
    """The window: `[{"sha", "patch", "duplicate", "version_only"}]`, newest first, at most
    `count`. A commit whose patch a newer drawn commit already made stays in the window, marked."""
    shas = revert._git(checkout, "rev-list", "--first-parent", "-n", str(count), pin).stdout.split()
    seen, drawn = set(), []
    for sha in shas:
        text = whole_patch(checkout, sha)
        patch_id = _patch_id(checkout, text) if text else None
        drawn.append({"sha": sha, "patch": text, "duplicate": patch_id in seen,
                      "version_only": bool(text) and version_only(checkout, sha)})
        if patch_id:
            seen.add(patch_id)
    return drawn


def replay(checkout, drawn, *, reset, compile_tests, explain, budget_seconds, clock=time.monotonic,
           explain_narrow=None):
    """One row per drawn commit, in order.

    `reset()` puts the tree back to the pin and returns whether it did; `compile_tests(sha)`
    compiles the reverted tree and returns its exit code; `explain(sha)` asks the explain task and
    returns the explanation it wrote, or None. `explain_narrow(sha)` asks it again on the same tree
    with `-Pyoriwake.narrowDependencies`, for a version-only commit. Once `budget_seconds` have
    passed, the commits and asks left are not asked, the commit being compiled included.
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
                row.update(_one(checkout, commit, reset, compile_tests, explain,
                                lambda: clock() - started >= budget_seconds))
                if explain_narrow is not None and row["outcome"] != NOT_ASKED:
                    row["version_only"] = commit.get("version_only", False)
                    if row["version_only"]:
                        row["with_narrow_dependencies"] = (
                            {"outcome": NOT_ASKED, "reason": "budget"}
                            if clock() - started >= budget_seconds
                            else _asked(explain_narrow(commit["sha"])))
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


def _one(checkout, commit, reset, compile_tests, explain, over_budget):
    if not reset():
        raise SystemExit(f"{checkout} could not be put back to the pin; stopping the replay")
    # `git apply` changes nothing unless the whole patch applies.
    if _git_bytes(checkout, "apply", "-R", "--whitespace=nowarn", "-",
                  stdin=commit["patch"]).returncode != 0:
        return {"reason": "does-not-apply"}
    compiled = compile_tests(commit["sha"])
    if compiled == STALLED:
        return {"reason": "build-stalled"}
    if compiled != 0:
        return {"reason": "does-not-compile"}
    # A compile can take most of a budget; the explain after it would overrun it.
    if over_budget():
        return {"reason": "budget"}
    return _asked(explain(commit["sha"]))


def _asked(explanation):
    """A row's outcome fields from one explanation."""
    outcome, detail = classify(explanation)
    if outcome == NOT_ASKED:
        return {"outcome": NOT_ASKED, "reason": detail}
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


def replay_project(project, checkout, commits, budget_minutes, scratch=None, publish=False):
    """The project's file: its rows and distribution, or why it was not replayed. With `publish`,
    the plugin under test is published first, for a run that did not record the map."""
    import run  # noqa: PLC0415  (Gradle, the restore and the map's place are the sweep's own)
    import sweep_data  # noqa: PLC0415

    # A `path` project measures in another project's clone, whose history holds its pin.
    if not (project.get("url") or project.get("path")) or project.get("commit") in (None, "local"):
        return not_replayed(project, "no upstream history")
    # A fixture build shares another project's checkout and pin: replaying it counts that window twice.
    if project.get("fixture"):
        return not_replayed(project, "a fixture: its checkout's own project is replayed")
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
    if publish:
        run.publish_plugin_under_test()
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

    def explain(sha, flags=(), stem="explain"):
        # The compile may have written an ignored file the selector would read as a change.
        if snapshot is not None:
            run.reset_untracked(checkout, project, set(snapshot) | created)
        since = time.time()
        run.gradle(checkout, project,
                   [*run.INIT_SCRIPT_ARGS, explain_task(task_path), "-Pyoriwake.base=HEAD",
                    *run.TIMING_ARM_FLAGS, *flags], os.path.join(logs, f"{stem}-{sha[:12]}.log"))
        return read_explanation(os.path.join(map_dir, "explain.json"), since)

    def explain_narrow(sha):
        return explain(sha, [run.NARROW_DEPENDENCIES_FLAG], "explain-narrow-dependencies")

    if not reset() or not at_pin(checkout, head):
        return not_replayed(project, "the checkout is not clean at the pin")
    started = time.monotonic()
    drawn = draw(checkout, project["commit"], commits)
    rows = replay(checkout, drawn, reset=reset, compile_tests=compile_tests, explain=explain,
                  budget_seconds=budget_minutes * 60, explain_narrow=explain_narrow)
    return {"schema": SCHEMA, "project": project["name"], "replayed": True,
            "pin": project["commit"], "plugin_commit": stamp.get("plugin_commit"),
            "commits": commits, "window": WINDOW_RULE, "budget_minutes": budget_minutes,
            "budget_reached": any(row.get("reason") == "budget" for row in rows),
            "untracked_reset": snapshot is not None,
            "tree_restored": at_pin(checkout, head),
            "seconds": round(time.monotonic() - started, 1),
            "rows": rows, "distribution": distribution(rows)}


def _find(directory, name):
    """A replay job's file for `name` under `directory/replay-<name>`, or None."""
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
                               "untracked_reset", "tree_restored", "seconds")}
            # Counted again from the rows, so the figure never rests on a file's own arithmetic.
            projects[name]["distribution"] = distribution(data["rows"])
            block = narrow_dependencies_block(data["rows"])
            if block is not None:
                projects[name]["narrow_dependencies"] = block
        except (OSError, ValueError, TypeError, KeyError, AttributeError):
            missing.append(name)
    def distinct(key):
        return sorted({str(entry[key]) for entry in projects.values() if entry.get(key) is not None})

    return {"schema": SCHEMA, "window": WINDOW_RULE,
            "commits": distinct("commits"), "plugin_commits": distinct("plugin_commit"),
            "figure": total([entry["distribution"] for entry in projects.values()]),
            "narrow_dependencies": total_block([entry.get("narrow_dependencies")
                                                for entry in projects.values()]),
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
    parser.add_argument("--publish", action="store_true",
                        help="publish the plugin under test first: the map was recorded on another runner")
    parser.add_argument("--merge", metavar="DIR", help="sum the replay jobs' files under DIR")
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
    result = replay_project(project, checkout, args.commits, args.budget_minutes, scratch,
                            publish=args.publish)
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
