"""Folds a sharded sweep back into one results file per project, or refuses to.

A CI sweep records each project in one job, which captures the map, runs the baseline and fixes the
accepted target list (`run.py --accepted-out`); shards then measure slices of that list
(`run.py --accepted ... --shard K/N`). This takes the header from the recording job and the rows
from the shards, in draw order, and gives the file a single job would have written.

What it refuses, because each would publish a sweep that is not the one planned: a shard that is
missing, doubled or stamped with another selector, grammar or commit; a row count off the accepted
list; a planned project with no output; a reduced arm missing a member; and an arm with no row
where a miss was possible.

The narrowed target stops a single job once enough of its rows narrowed, which a list fixed before
measuring cannot know. So shards measure the whole list, and the merge keeps rows in draw order up
to the one that reaches the target: the sample a single job would have drawn. The rows past it are
counted in `rows_past_target`.

    python3 bench/merge.py --arm full --planned demo okio --out bench/results DIR

`DIR/<project>/` holds `recording.json`, `accepted.json` and `shard-<k>-of-<n>.json`.
"""

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import corpus_state  # noqa: E402
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
    for row in ordered:
        kept.append(row)
        if run.narrowed_target_reached(kept, accepted.get("narrowed_target")):
            break
    merged = {key: value for key, value in recording.items() if key != "shard"}
    merged.update(mutations=kept, shards=count, rows_past_target=len(ordered) - len(kept))
    # The compile gate's rejections a single job would have met: those drawn before its last row.
    if "rejected" in accepted:
        met = [entry for entry in accepted["rejected"] if entry[3] < len(kept)]
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


def check_sweep(planned, merged, arm, isolated):
    """The sweep-level refusals over the merged files, and the summary that states what ran."""
    absent = [name for name in planned if name not in merged]
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
    return {"arm": arm, "projects": sorted(merged), "miss_possible_rows": possible,
            "isolated_arm": "ran" if isolated else "not run"}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("artifacts", help="a directory holding one directory per project")
    parser.add_argument("--planned", nargs="+", required=True)
    parser.add_argument("--arm", choices=("full", "reduced"), required=True)
    parser.add_argument("--isolated", action="store_true", help="the isolated arm ran")
    parser.add_argument("--out", required=True)
    args = parser.parse_args(argv)
    try:
        merged = {name: merge_directory(os.path.join(args.artifacts, name))
                  for name in args.planned if os.path.isdir(os.path.join(args.artifacts, name))}
        summary = check_sweep(args.planned, merged, args.arm, args.isolated)
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
