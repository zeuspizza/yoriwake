"""Folds a sharded sweep back into one results file per project, or refuses to.

A CI sweep records each project in one job, which captures the map, runs the baseline and fixes the
accepted target list (`run.py --accepted-out`); shards then measure slices of that list
(`run.py --accepted ... --shard K/N`). This takes the header from the recording job and the rows
from the shards, in draw order, and gives the file a single job would have written.

What it refuses, because each would publish a sweep that is not the one planned: a shard that is
missing, doubled or stamped with another selector, grammar or commit; a row count off the accepted
list; a planned project with no output that is not declared missing; a reduced arm missing a member;
and an arm with no row where a miss was possible.

A project whose recording or shard job failed is declared with `--missing NAME=REASON` rather than
holding back every other project's rows: the summary lists it with its reason, so the sweep says
which projects it lacks, and the workflow still fails the run.

A later run that re-measures some of those projects completes the earlier sweep with
`--completes DIR`, the earlier run's unpacked release (its `merged/` and `art/`). The result is one
sweep of both: the earlier run's plan, each project's file from the run that measured it, and a
project still missing with this run's reason if it declared one. It refuses a project the earlier
run merged or did not plan, another arm, and, as any sweep does, files measured under more than one
selector or grammar.

The narrowed target stops a single job once enough of its rows narrowed, which a list fixed before
measuring cannot know. So shards measure the whole list, and the merge keeps rows in draw order up
to the one that reaches the target: the sample a single job would have drawn. `pool/v1` stops per
family, so there each family's rows are kept up to the one that reaches that family's target. The
rows past it are counted in `rows_past_target`.

With `--isolated`, the sweep also ran a paired isolated arm: each slice measured its own rows a
second time against a map recorded with a fresh test JVM per class, reusing their ground truth.
Each project's `isolated-recording.json` and `isolated-shard-<k>-of-<n>.json` become
`<project>.isolated.json`, holding the isolated row of every row the shared file kept, and
`sweep.json` states per project whether that arm ran and both arms' figures over the rows both
measured. An isolated arm that did not finish costs a project that file, never its shared rows.

    python3 bench/merge.py --arm full --planned demo okio --out bench/results DIR
    python3 bench/merge.py --arm full --planned demo okio --missing "okio=its record job failed" ...
    python3 bench/merge.py --arm full --planned okio --completes previous --out bench/results DIR

`DIR/<project>/` holds `recording.json`, `accepted.json` and `shard-<k>-of-<n>.json`, and with a
paired isolated arm `isolated-recording.json` and `isolated-shard-<k>-of-<n>.json`.
"""

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import corpus_state  # noqa: E402
import family_order  # noqa: E402
import run  # noqa: E402
from sweep_corpus import REDUCED_ARM  # noqa: E402

# What every file of one project must agree on, or its rows describe different things.
# `fixture_digest` included: two trees of one fixture are two samples, as two shape sets are.
STAMPS = ("project", "commit", "plugin_commit", "selector_digest", "grammar_digest",
          "fixture_digest")


class Refused(Exception):
    pass


def _key(row):
    if "kind" not in row or "index" not in row:
        raise Refused(f"{row.get('target')}: a row without its kind and index cannot be placed on "
                      f"the accepted list")
    return row["target"], row["kind"], row["index"]


def merge_project(recording, accepted, shards):
    """The results file a single job would have written, from the recording job's header, the
    accepted list and one results file per shard."""
    for name, data in [("the accepted list", accepted)] + [
            (f"shard {s.get('shard')}", s) for s in shards]:
        for stamp in STAMPS:
            if data.get(stamp) != recording.get(stamp):
                raise Refused(f"{recording.get('project')}: {name} has {stamp} "
                              f"{data.get(stamp)!r}, the recording job {recording.get(stamp)!r}")
    # The shape set is a results file's stamp, which the accepted list does not carry.
    for shard in shards:
        if shard.get("target_shapes") != recording.get("target_shapes"):
            raise Refused(f"{recording.get('project')}: shard {shard.get('shard')} has target_shapes "
                          f"{shard.get('target_shapes')!r}, the recording job "
                          f"{recording.get('target_shapes')!r}")
    count = len(accepted["slices"])
    seen = {}
    for shard in shards:
        number, of = shard["shard"]
        if of != count:
            raise Refused(f"{recording['project']}: shard {number} of {of}, but the list was cut "
                          f"into {count}")
        if number in seen:
            raise Refused(f"{recording['project']}: shard {number} of {count} is here twice")
        seen[number] = shard
    missing = [n for n in range(1, count + 1) if n not in seen]
    if missing:
        raise Refused(f"{recording['project']}: no output from "
                      + ", ".join(f"shard {n} of {count}" for n in missing))

    position = {(label, kind, index): at
                for at, (_, kind, index, label) in enumerate(accepted["targets"])}
    rows = {}
    for shard in shards:
        for row in shard.get("mutations") or []:
            key = _key(row)
            if key not in position:
                raise Refused(f"{recording['project']}: {key[0]} is not on the accepted list")
            rows[key] = row
    absent = [label for (label, kind, index) in position if (label, kind, index) not in rows]
    if absent:
        raise Refused(f"{recording['project']}: {len(absent)} accepted target(s) have no row: "
                      + ", ".join(absent[:5]))

    kept = []
    ordered = sorted(rows.values(), key=lambda row: position[_key(row)])
    pool = recording.get("target_shapes") == run.POOL_SHAPES
    if pool:
        # `pool/v1` stops per family: a family's rows after the one that reached its target go,
        # the other families' stay.
        kept, stopped_at = family_order.kept_in_draw_order(
            ordered, accepted.get("family_target"), run.counts_toward_narrowed_target)
    else:
        for row in ordered:
            kept.append(row)
            if run.narrowed_target_reached(kept, accepted.get("narrowed_target")):
                break
    merged = {key: value for key, value in recording.items() if key != "shard"}
    merged.update(mutations=kept, shards=count, rows_past_target=len(ordered) - len(kept))
    # The recording job measured no row, so its per-family counters are empty; a single job's are
    # counted over the rows it kept.
    stops = merged.get("family_order")
    if pool and stops is not None:
        merged["family_order"] = {**stops, "families": family_order.counters(
            kept, stops["cap"], stops["target"], run.counts_toward_narrowed_target)}
    # The compile gate's rejections a single job would have met: those drawn before its last row,
    # or for `pool/v1`, before the row that stopped their family.
    if "rejected" in accepted:
        met = [entry for entry in accepted["rejected"]
               if (entry[3] <= stopped_at.get(entry[1], len(ordered)) if pool
                   else entry[3] < len(kept))]
        merged.update(rejected_targets=[label for label, _, _, _ in met] or None,
                      rejected_mutations=[[label, kind, index] for label, kind, index, _ in met]
                      or None,
                      mutations_rejected=len(met))
    return merged


def merge_directory(path):
    """`merge_project` over a project's directory of downloaded artifacts."""
    def load(name):
        with open(os.path.join(path, name), encoding="utf-8") as handle:
            return json.load(handle)
    shards = [load(name) for name in sorted(os.listdir(path)) if name.startswith("shard-")]
    return merge_project(load("recording.json"), load("accepted.json"), shards)


RAN, DID_NOT_FINISH, NOT_RUN = "ran", "did-not-finish", "not run"


def arm_figures(data, keys):
    """One arm's figures over the rows `keys` names: how many, how many narrowed, the tests and
    seconds of their selected arms, and what recording the arm's map took."""
    rows = [row for row in data.get("mutations") or [] if run.row_key(row) in keys]
    seconds = [row.get("selected_seconds") for row in rows]
    return {"rows": len(rows),
            "narrowed": sum(1 for row in rows if row.get("decision_outcome") == "narrowed"),
            "tests_run": sum(row.get("selected_distinct") or 0 for row in rows),
            # None when a row has no time: a sum over the others would read as the arm's.
            "selected_seconds": (round(sum(seconds), 1)
                                 if all(isinstance(s, (int, float)) for s in seconds) else None),
            "recording_seconds": data.get("capture_seconds")}


def merge_isolated(merged, recording, shards):
    """`(file, state)` of one project's paired isolated arm: `<project>.isolated.json`, or None
    where the arm did not finish, and the project's entry of `sweep.json`.

    The file holds the isolated row of each row the merged shared file kept, in that order: the
    rows past the narrowed target go with their shared rows. A slice whose isolated arm left no
    file takes its rows out of the comparison and nothing else: the state says how many slices
    measured it, and both arms' figures are over the rows both have. A recording that did not
    finish, no slice at all and a file under other stamps than the shared arm's are each
    `did-not-finish` with the reason, and the shared file is what it was.
    """
    if merged.get("build_tool") == run.maven_build.MAVEN:
        return None, {"status": NOT_RUN, "reason": "a Maven project, which has no isolated capture"}
    if recording is None:
        return None, {"status": DID_NOT_FINISH, "reason": "the isolated recording left no map"}
    count = merged.get("shards")
    for name, data in [("the isolated recording", recording)] + [
            (f"isolated shard {s.get('shard')}", s) for s in shards]:
        for stamp in STAMPS + ("target_shapes",):
            if data.get(stamp) != merged.get(stamp):
                return None, {"status": DID_NOT_FINISH,
                              "reason": f"{name} has {stamp} {data.get(stamp)!r}, the shared arm "
                                        f"{merged.get(stamp)!r}"}
    have = {(s.get("shard") or [None])[0] for s in shards} & set(range(1, (count or 0) + 1))
    if not have:
        return None, {"status": DID_NOT_FINISH,
                      "reason": f"0 of {count} slices measured their isolated arm"}
    rows = {}
    for shard in shards:
        for row in shard.get("mutations") or []:
            if "kind" in row and "index" in row:
                rows[run.row_key(row)] = row
    shared_keys = [run.row_key(row) for row in merged.get("mutations") or []]
    kept = [rows[key] for key in shared_keys if key in rows]
    data = {**{key: value for key, value in recording.items() if key != "shard"},
            "baseline_seconds": merged.get("baseline_seconds"),
            "paired_with": f"{merged.get('project')}.json",
            "mutations": kept, "shards": count}
    paired = {run.row_key(row) for row in kept
              if row.get("status") in ("checked", "no-effect")}
    return data, {"status": RAN, "slices_measured": len(have), "slices": count,
                  "paired_rows": len(paired), "shared_rows": len(shared_keys),
                  "shared": arm_figures(merged, paired), "isolated": arm_figures(data, paired)}


def isolated_directory(path, merged):
    """`merge_isolated` over a project's directory of downloaded artifacts."""
    def load(name):
        with open(os.path.join(path, name), encoding="utf-8") as handle:
            return json.load(handle)
    names = sorted(os.listdir(path)) if os.path.isdir(path) else []
    # Whatever is wrong with these files, the shared files beside them are still written.
    try:
        recording = load("isolated-recording.json") if "isolated-recording.json" in names else None
        shards = [load(name) for name in names if name.startswith("isolated-shard-")]
        return merge_isolated(merged, recording, shards)
    except Exception as error:
        return None, {"status": DID_NOT_FINISH,
                      "reason": f"an isolated file could not be read ({type(error).__name__})"}


def check_sweep(planned, merged, arm, isolated, missing=None):
    """The sweep-level refusals over the merged files, and the summary that states what ran.

    `missing` maps each project declared missing to why; one also merged is a contradiction.
    """
    missing = missing or {}
    # Two selectors' rows in one recall figure describe neither; commits that share one may differ.
    # `maven_digest` is on Maven files only, so a sweep of both build tools agrees on the others.
    for stamp in ("selector_digest", "grammar_digest", "maven_digest"):
        by_value = {}
        for name, data in sorted(merged.items()):
            if data.get(stamp) is not None:
                by_value.setdefault(data[stamp], []).append(name)
        if len(by_value) > 1:
            raise Refused(f"the merged files were measured under {len(by_value)} {stamp}s: "
                          + "; ".join(f"{value} ({', '.join(names)})"
                                      for value, names in sorted(by_value.items())))
    unplanned = sorted(name for name in missing if name not in planned)
    if unplanned:
        raise Refused(f"declared missing but not planned: {', '.join(unplanned)}")
    both = sorted(name for name in missing if name in merged)
    if both:
        raise Refused(f"declared missing but merged: {', '.join(both)}")
    absent = [name for name in planned if name not in merged and name not in missing]
    if absent:
        raise Refused(f"no output for planned project(s): {', '.join(absent)}")
    if arm == "reduced":
        short = [name for name in REDUCED_ARM if name not in merged]
        if short:
            raise Refused(f"the reduced arm needs {', '.join(REDUCED_ARM)}; {', '.join(short)} "
                          f"is missing, and a partial arm is not the arm")
    possible = sum(1 for data in merged.values() for row in data.get("mutations") or []
                   if corpus_state.miss_was_possible(row))
    if not possible:
        raise Refused(f"the {arm} arm has no row that could have missed a failure, so its recall "
                      f"figure would be a statement about nothing")
    commits = sorted({data.get("plugin_commit") for data in merged.values()} - {None})
    return {"arm": arm, "projects": sorted(merged), "missing": dict(sorted(missing.items())),
            "miss_possible_rows": possible, "isolated_arm": RAN if isolated else NOT_RUN,
            "plugin_commits": commits}


def complete(directory, arm, planned, merged, missing):
    """This run's projects folded into the earlier sweep unpacked at `directory`: the plan, the
    merged files and the missing projects of both, for `check_sweep` to judge as one sweep."""
    found = [os.path.join(root, "sweep.json") for root, _, names
             in os.walk(os.path.join(directory, "merged")) if "sweep.json" in names]
    if len(found) != 1:
        raise Refused(f"{directory}/merged holds {len(found)} sweep.json files, not one")
    with open(found[0], encoding="utf-8") as handle:
        earlier = json.load(handle)
    if earlier.get("arm") != arm:
        raise Refused(f"the earlier sweep is the {earlier.get('arm')} arm, this one the {arm} arm")
    # Only what the earlier sweep lacks: a project in both would hold two draws of one sweep.
    redone = sorted(name for name in planned if name in earlier["projects"])
    if redone:
        raise Refused(f"the earlier sweep already merged {', '.join(redone)}")
    unplanned = sorted(name for name in planned if name not in earlier["missing"])
    if unplanned:
        raise Refused(f"the earlier sweep did not plan {', '.join(unplanned)}")
    unplanned = sorted(name for name in missing if name not in planned)
    if unplanned:
        raise Refused(f"declared missing but not planned: {', '.join(unplanned)}")
    union = {name: merge_directory(os.path.join(directory, "art", name))
             for name in earlier["projects"]}
    union.update(merged)
    # A project this run planned is missing only if this run says so; the earlier reason would hide
    # one that left no output.
    still = {name: reason for name, reason in earlier["missing"].items() if name not in planned}
    return earlier["projects"] + sorted(earlier["missing"]), union, {**still, **missing}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("artifacts", help="a directory holding one directory per project")
    parser.add_argument("--planned", nargs="+", required=True)
    parser.add_argument("--missing", action="append", default=[], metavar="NAME=REASON",
                        help="a planned project whose recording or shard job failed, and why")
    parser.add_argument("--arm", choices=("full", "reduced"), required=True)
    parser.add_argument("--isolated", action="store_true", help="the isolated arm ran")
    parser.add_argument("--out", required=True)
    parser.add_argument("--completes", metavar="DIR",
                        help="the earlier sweep's unpacked release, which this run completes")
    args = parser.parse_args(argv)
    try:
        missing = {}
        for entry in args.missing:
            name, _, reason = entry.partition("=")
            if not name or not reason:
                raise Refused(f"--missing takes NAME=REASON, not {entry!r}")
            missing[name] = reason
        merged = {name: merge_directory(os.path.join(args.artifacts, name))
                  for name in args.planned
                  if name not in missing and os.path.isdir(os.path.join(args.artifacts, name))}
        planned, own = args.planned, sorted(merged)
        if args.completes:
            planned, merged, missing = complete(args.completes, args.arm, planned, merged, missing)
        summary = check_sweep(planned, merged, args.arm, args.isolated, missing)
        isolated_files = {}
        if args.isolated:
            # Of this run's projects: an earlier run's isolated files are not carried over.
            summary["isolated"] = {}
            for name in own:
                isolated_files[name], summary["isolated"][name] = isolated_directory(
                    os.path.join(args.artifacts, name), merged[name])
            # The sweep's own word for the arm is its best project's: ran with one file written.
            states = {state["status"] for state in summary["isolated"].values()}
            summary["isolated_arm"] = next((s for s in (RAN, DID_NOT_FINISH) if s in states),
                                           NOT_RUN)
    except Refused as refused:
        print(f"REFUSED: {refused}", file=sys.stderr)
        return 1
    os.makedirs(args.out, exist_ok=True)
    for name, data in merged.items():
        # A named shape set lands in its own directory, as `run.py` writes it.
        shapes = data.get("target_shapes") or run.DEFAULT_SHAPES
        out_path, _ = run.results_location(args.out, "", name, shapes)
        os.makedirs(os.path.dirname(out_path), exist_ok=True)
        with open(out_path, "w", encoding="utf-8") as handle:
            json.dump(data, handle, indent=1)
        if isolated_files.get(name) is not None:
            with open(run.results_location(args.out, "", name, shapes, isolated=True)[0], "w",
                      encoding="utf-8") as handle:
                json.dump(isolated_files[name], handle, indent=1)
    # Beside the files it summarises: a named set's sweep never replaces the default set's.
    shapes = {data.get("target_shapes") or run.DEFAULT_SHAPES for data in merged.values()}
    if len(shapes) > 1:
        print(f"REFUSED: one sweep holds projects of the {sorted(shapes)} shape sets", file=sys.stderr)
        return 1
    sweep_dir = args.out if shapes <= {run.DEFAULT_SHAPES} else os.path.join(
        args.out, run.shape_folder(sorted(shapes - {run.DEFAULT_SHAPES})[0]))
    os.makedirs(sweep_dir, exist_ok=True)
    with open(os.path.join(sweep_dir, "sweep.json"), "w", encoding="utf-8") as handle:
        json.dump(summary, handle, indent=1)
    print(json.dumps(summary))
    return 0


if __name__ == "__main__":
    sys.exit(main())
