"""
Asks the CURRENT selector every recall question a sweep already paid for, in seconds and offline.

WHY THIS EXISTS. A change to the agent's selection logic moved `selector_digest`, aged every results
file, and was answerable only by a corpus sweep -- 30 to 42 hours -- or, for one row, a hand-written
replay. Most of that time is Gradle re-running suites whose answers are already on disk: each row
keeps the map its selecting arm read, the decision record naming every test that arm discovered,
the inputs the plugin handed the test JVM, and which tests failed in the full arm. Re-deciding those
tests is a question for the selector alone.

WHAT IT CAN AND CANNOT ANSWER. It replays the TEST-JVM decision: `Selector`, `SelectionFilter` and
everything they call, against recorded inputs. It does NOT re-run the daemon side -- `ChangeSelection`,
scope derivation, the inline scan, the digest rule -- because those produce the inputs it replays; a
change there needs real builds (`run.py --only-target`, or the reduced arm). Nor can it see a test the
recorded arm never discovered. Both limits are printed with every report rather than implied.

THE SELF-CHECK IS PART OF EVERY RUN. Replaying a row with the jar that recorded it must reproduce
every recorded verdict. A row where it does not is reported as DRIFT and asks no recall question:
the replay is then a second opinion, and a second opinion that disagrees with the shipped rule is
the defect this instrument would otherwise become.

    python bench/replay_decisions.py                       # every results file
    python bench/replay_decisions.py bench/results/duckduckgo-android.json
    python bench/replay_decisions.py --results-dir bench/logs/duckduckgo-android --json

WHAT THE LOADED-CLASS UNION BUYS (`--union-effect`). The same rows, decided twice: against each
row's map as kept, and against that map without its loaded-class union. A test left out in the first
pass and run in the second is one a capture with no union would not have left out. This asks no
recall question, so it also takes the rows whose change failed nothing, and it compares the current
selector with itself: a row whose first pass does not reproduce its record under the selector that
recorded it is DRIFT and is not counted.

    python bench/replay_decisions.py --union-effect --root <where the kept logs were unpacked> <results>...
"""

import argparse
import glob
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

import run  # noqa: E402  (the decision-record reader lives with the writer's other reader)

AGENT_JAR = os.path.join(run.SOURCE, "agent", "build", "libs", "agent.jar")
REPLAY_SOURCE = os.path.join(HERE, "Replay.java")
# Written by the agent beside its other notes: `#!input.<system property>`. See SelectionRun.
INPUT_NOTE_PREFIX = "input."
# A leaf container -- a test template or a dynamic-test factory -- is judged by its subtree, not as a
# test. The record does not carry the descriptor's type, so it is read from the id's last segment.
# The loaded-class union in a map directory: the list, the packages it speaks for, and how it was made.
UNION_FILES = ("loaded.txt", "loaded-scope", "loaded-provenance")
CONTAINER_SEGMENT = re.compile(r"\[(test-template|test-factory|dynamic-container)[^\]]*\]$")
# The descriptor ids a class and method can be read from, for the three engines the corpus runs.
CLASS_SEGMENT = re.compile(r"\[(?:class|runner|spec|nested-class):([^\]]+)\]")
METHOD_SEGMENT = re.compile(r"\[(?:method|test|feature|test-template|iteration):([^\]]+)\]")


def name_of(test_id):
    """`(class, method)` from a JUnit Platform unique id, or None when the id carries neither."""
    classes = CLASS_SEGMENT.findall(test_id)
    methods = METHOD_SEGMENT.findall(test_id)
    if not classes or not methods:
        return None
    method = methods[-1]
    # Vintage spells a method `name(fully.qualified.Class)`, Jupiter `name(args)`; the results XML
    # spells both as `name`, with `()` removed by the harness.
    method = method.split("(", 1)[0]
    return classes[-1], method


def rows_to_replay(paths, statuses=("checked",)):
    """Every row that carries what a replay needs, with the reason each other row was left out."""
    rows, skipped = [], {}
    for path in paths:
        try:
            with open(path, encoding="utf-8") as handle:
                data = json.load(handle)
        except (OSError, ValueError):
            skipped[os.path.basename(path)] = "unreadable"
            continue
        for row in data.get("mutations") or []:
            replay = row.get("replay") or {}
            if row.get("status") not in statuses:
                reason = "the row asked no recall question"
            elif not replay.get("map") or not replay.get("decisions"):
                reason = "recorded before replay inputs were kept"
            elif row.get("induced") is None:
                reason = "no induced-failure ids recorded"
            else:
                rows.append((data.get("project"), row))
                continue
            skipped[reason] = skipped.get(reason, 0) + 1
    return rows, skipped


def load_record(decisions_path, root=ROOT):
    """The merged decision record at a kept path: a `.parts` directory or a single `.tsv`."""
    return run.read_kept_decisions(os.path.join(root, decisions_path))


def without_union(map_dir, view):
    """A directory at `view` holding links to everything in `map_dir` but its loaded-class union."""
    if not os.path.isdir(map_dir):
        return map_dir  # the selector refuses it as it refuses the kept map
    os.makedirs(view)
    for name in os.listdir(map_dir):
        if name not in UNION_FILES:
            # Absolute: a relative target would resolve against the view directory.
            os.symlink(os.path.abspath(os.path.join(map_dir, name)), os.path.join(view, name))
    return view


def decide(rows, jar=AGENT_JAR, root=ROOT, strip_union=False):
    """Runs every row's decision through the current selector in one JVM.

    Returns, per row, `(project, row, recorded ids and verdicts, replayed verdict by id, refusal)`;
    the last is why the row was not replayed, with nothing replayed, or None.
    """
    if not os.path.isfile(jar):
        raise SystemExit(f"no agent jar at {jar}; run ./gradlew :agent:jar in {run.SOURCE} first")
    work = tempfile.mkdtemp(prefix="yoriwake-replay-")
    try:
        return _decide(rows, jar, root, strip_union, work)
    finally:
        shutil.rmtree(work, ignore_errors=True)


def _decide(rows, jar, root, strip_union, work):
    manifest, prepared = [], []
    for index, (project, row) in enumerate(rows):
        record = load_record(row["replay"]["decisions"], root)
        if record is None:
            prepared.append((project, row, None, "the kept decision record is torn or missing"))
            continue
        inputs = {k[len(INPUT_NOTE_PREFIX):]: v for k, v in record["notes"].items()
                  if k.startswith(INPUT_NOTE_PREFIX)}
        if not inputs:
            prepared.append((project, row, None, "the record carries no inputs"))
            continue
        ids = [(test, verdict) for test, verdict in record["decided"] if "\t" not in test and "\n" not in test]
        base = os.path.join(work, str(index))
        with open(base + ".inputs", "w", encoding="utf-8") as handle:
            handle.writelines(f"{k}\t{v}\n" for k, v in sorted(inputs.items()))
        with open(base + ".ids", "w", encoding="utf-8") as handle:
            handle.writelines(
                f"{test}\t{'container' if CONTAINER_SEGMENT.search(test) else 'test'}\n"
                for test, _ in ids)
        map_dir = os.path.join(root, row["replay"]["map"])
        if strip_union:
            map_dir = without_union(map_dir, base + ".map")
        manifest.append("\t".join((map_dir, base + ".inputs", base + ".ids", base + ".out")))
        prepared.append((project, row, (ids, base + ".out"), None))
    with open(os.path.join(work, "manifest"), "w", encoding="utf-8") as handle:
        handle.write("\n".join(manifest) + "\n")
    if manifest:
        subprocess.run(["java", "-cp", jar, REPLAY_SOURCE, os.path.join(work, "manifest")],
                       check=True)
    decided = []
    for project, row, job, refusal in prepared:
        if job is None:
            decided.append((project, row, None, None, refusal))
            continue
        ids, out = job
        if os.path.exists(out + ".refused"):
            with open(out + ".refused", encoding="utf-8") as handle:
                decided.append((project, row, None, None, handle.read().strip()))
            continue
        replayed = {}
        with open(out, encoding="utf-8") as handle:
            for line in handle:
                test, verdict, reason = line.rstrip("\n").split("\t")
                replayed[test] = f"{verdict}/{reason}"
        decided.append((project, row, ids, replayed, None))
    return decided


def replay(rows, jar=AGENT_JAR, root=ROOT):
    """Every row's decision under the current selector, as its recall answer. Returns per-row verdicts."""
    return [{"project": project, "target": row["target"], "verdict": "not-replayed", "reason": refusal}
            if refusal is not None else judge(project, row, ids, replayed)
            for project, row, ids, replayed, refusal in decide(rows, jar, root)]


def trusted_union(map_dir):
    """Whether the kept map holds a union the selector would read: all three files, provenance `full`
    (keyed to a build-script filter or not)."""
    if not all(os.path.isfile(os.path.join(map_dir, name)) for name in UNION_FILES):
        return False
    with open(os.path.join(map_dir, "loaded-provenance"), encoding="utf-8") as f:
        how = f.read().strip()
    return how == "full" or how.startswith("full filtered ")


def union_effect(rows, jar=AGENT_JAR, root=ROOT):
    """Per row, the tests left out against its map as kept and run against it without its union."""
    results = []
    kept, stripped = decide(rows, jar, root), decide(rows, jar, root, strip_union=True)
    for (project, row, ids, as_kept, refusal), (_, _, _, bare, bare_refusal) in zip(kept, stripped):
        entry = {"project": project, "target": row["target"]}
        if refusal is not None or bare_refusal is not None:
            entry.update(verdict="not-replayed", reason=refusal or bare_refusal)
        elif not trusted_union(os.path.join(root, row["replay"]["map"])):
            entry.update(verdict="no-union")
        elif (row["replay"].get("digest") == run.selector_digest()
              and any(as_kept.get(test) != old for test, old in ids)):
            entry.update(verdict="drift")
        else:
            tests = [test for test, _ in ids if not CONTAINER_SEGMENT.search(test)]
            left_out = [test for test in tests if as_kept[test].startswith("excluded/")]
            only_through_union = [test for test in left_out if bare[test].startswith("included/")]
            entry.update(verdict="union", tests=len(tests), left_out=len(left_out),
                         only_through_union=len(only_through_union),
                         differs_from_record=sum(1 for test, old in ids if as_kept.get(test) != old))
        results.append(entry)
    return results


def print_union_effect(results, skipped):
    by_project = {}
    for entry in results:
        by_project.setdefault(entry["project"], []).append(entry)
    print(f"{'project':28} {'rows':>5} {'rows gaining':>12} {'tests':>8} {'left out':>9} {'only through union':>18}")
    total = [0, 0, 0, 0, 0]
    for project in sorted(by_project):
        rows = [e for e in by_project[project] if e["verdict"] == "union"]
        figures = [len(rows), sum(1 for e in rows if e["only_through_union"]), sum(e["tests"] for e in rows),
                   sum(e["left_out"] for e in rows), sum(e["only_through_union"] for e in rows)]
        total = [a + b for a, b in zip(total, figures)]
        print(f"{project:28} {figures[0]:5} {figures[1]:12} {figures[2]:8} {figures[3]:9} {figures[4]:18}")
    print(f"{'all':28} {total[0]:5} {total[1]:12} {total[2]:8} {total[3]:9} {total[4]:18}")
    others = {}
    for entry in results:
        if entry["verdict"] != "union":
            key = entry["verdict"] + (": " + entry["reason"] if entry.get("reason") else "")
            others[key] = others.get(key, 0) + 1
    print(f"\nRows not counted: {others or 'none'}; rows left out before the replay: {skipped or 'none'}")
    print(f"Rows whose first pass differs from their record (decided by another selector): "
          f"{sum(1 for e in results if e.get('differs_from_record'))}")
    print("Both passes replay the test-JVM decision against recorded inputs, under the current selector "
          f"({run.selector_digest()}). A row the build refused before the test JVM decided is the same in both.")


def judge(project, row, recorded, replayed):
    """The replayed row: DRIFT when it does not reproduce its own record, else its recall answer."""
    drift = [(test, old, replayed.get(test)) for test, old in recorded if replayed.get(test) != old]
    entry = {"project": project, "target": row["target"], "tests": len(recorded)}
    included = {test for test, verdict in replayed.items() if verdict.startswith("included/")}
    entry["included"] = len(included)
    if drift and row.get("replay", {}).get("digest") == run.selector_digest():
        entry.update(verdict="drift", drifted=len(drift), example=drift[0])
        return entry
    entry["changed_verdicts"] = len(drift)
    selected = {name_of(test) for test in included}
    discovered = {name_of(test) for test, _ in recorded}
    missed, undetermined = [], []
    for failing in row["induced"]:
        klass, _, method = failing.partition("::")
        key = (klass, method.removesuffix("()").split("(", 1)[0].split("[", 1)[0])
        if key not in discovered:
            undetermined.append(failing)
        elif key not in selected:
            missed.append(failing)
    entry.update(verdict="missed" if missed else "ok", missed=missed, undetermined=undetermined)
    return entry


def refusals(results):
    """Why a replay may not pass, however its rows answered: it replayed nothing, or a published
    row's kept decision record could not be read, so that row was never asked."""
    found = [f"{e['project']} {e['target']}: {e['reason']}" for e in results
             if e["verdict"] == "not-replayed"]
    if not any(e["verdict"] != "not-replayed" for e in results):
        found.insert(0, "no row was replayed, so this vouches for a selector nothing asked")
    return found


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[1])
    parser.add_argument("results", nargs="*", help="results files (default: every one)")
    parser.add_argument("--results-dir", default=os.path.join(HERE, "results"))
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--root", default=ROOT, help="the directory the rows' kept paths are relative to")
    parser.add_argument("--union-effect", action="store_true",
                        help="count the tests each row leaves out only through its map's loaded-class union")
    args = parser.parse_args(argv)
    paths = args.results or sorted(glob.glob(os.path.join(args.results_dir, "*.json")))
    if args.union_effect:
        rows, skipped = rows_to_replay(paths, statuses=("checked", "no-effect"))
        results = union_effect(rows, root=args.root) if rows else []
        if args.json:
            print(json.dumps({"results": results, "skipped": skipped}, indent=1))
        else:
            print_union_effect(results, skipped)
        return 0 if any(e["verdict"] == "union" for e in results) else 1
    rows, skipped = rows_to_replay(paths)
    results = replay(rows, root=args.root) if rows else []
    if args.json:
        print(json.dumps({"results": results, "skipped": skipped}, indent=1))
    else:
        for entry in results:
            if entry["verdict"] == "drift":
                print(f"DRIFT   {entry['project']}  {entry['target']}: {entry['drifted']} verdict(s) "
                      f"differ from the record under the same selector, e.g. {entry['example']}")
            elif entry["verdict"] == "not-replayed":
                print(f"--      {entry['project']}  {entry['target']}: {entry['reason']}")
            else:
                print(f"{entry['verdict'].upper():7} {entry['project']}  {entry['target']}: "
                      f"{entry['included']}/{entry['tests']} included, "
                      f"{entry['changed_verdicts']} verdict(s) changed"
                      f"{', MISSED ' + ', '.join(entry['missed']) if entry['missed'] else ''}"
                      f"{', ' + str(len(entry['undetermined'])) + ' undetermined' if entry['undetermined'] else ''}")
        print(f"\n{len(results)} row(s) replayed against selector {run.selector_digest()}; left out: "
              f"{skipped or 'none'}")
        print("Replays the test-JVM decision only. A change to ChangeSelection, scope derivation or "
              "the inline scan changes the INPUTS replayed here and needs real builds.")
    refused = refusals(results)
    for why in refused:
        print(f"REFUSED: {why}")
    failed = any(e["verdict"] in ("missed", "drift") for e in results)
    return 1 if failed or refused else 0


if __name__ == "__main__":
    sys.exit(main())
