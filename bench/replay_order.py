"""Whether the learned order would have drawn more rows where a miss was possible than
`reach-stratified/v1`, replayed from archived results files.

For each project, the model is fitted on every OTHER project's archived rows (leave one project
out), and the project's own rows of each family are reordered by it inside their strata. The first
`FAMILY_TARGET` draws of each family are then compared: how many narrowed and induced a failure
(the rows where a miss was possible) under the archived order and under the learned one. A family
adopts the learned order only when its share is higher over all projects AND no project that had
such a row among the archived order's first draws has none under the learned order.

Read from the archived files alone: each results file, the map its rows were replayed against
(`replay.map`, relative to the repository the file belongs to), and the size of each target's file
from the git objects of the project's checkout at the stamped commit. A feature that cannot be read
is the bucket "unknown", never a guess. Nothing here reads which tests were missed.

    python3 bench/replay_order.py [RESULTS_DIR ...] [--json] [--write]

`--write` fits the model on every project and writes `bench/order/learned-v1.json` with the
families the replay adopted and the targets it was fitted on, which the sweep never ranks. With no
family adopted it writes nothing, and `pool/v1` uses the round-robin alone.
"""

import argparse
import glob
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import family_order  # noqa: E402
import target_ordering  # noqa: E402

DEFAULT_RESULTS = [os.path.join(HERE, "results")]
BASELINE = target_ordering.ORDERING


def counts_toward(row):
    """Narrowed and induced a failure: `run.counts_toward_narrowed_target`, the stops' own rule."""
    import run  # noqa: PLC0415 -- heavy, and only needed when reading real rows
    return run.counts_toward_narrowed_target(row)


def _size(checkouts, project, commit, path):
    repo = os.path.join(checkouts, project)
    if not commit or not os.path.isdir(repo):
        return None
    done = subprocess.run(["git", "-C", repo, "cat-file", "-s", f"{commit}:{path}"],
                          capture_output=True, text=True)
    return int(done.stdout) if done.returncode == 0 and done.stdout.strip().isdigit() else None


def load_runs(directories, hit=counts_toward):
    """project -> rows in draw order, each `{"target", "family", "features", "hit"}`, from every
    default-set file drawn under `reach-stratified/v1` in `directories`. A project's file in a later
    directory adds only targets the earlier ones lack. Returns `(projects, skipped)`."""
    projects, skipped = {}, {}
    for directory in directories:
        repo = os.path.dirname(os.path.dirname(os.path.abspath(directory)))
        checkouts = os.path.join(os.path.dirname(os.path.abspath(directory)), "checkouts")
        for path in sorted(glob.glob(os.path.join(directory, "*.json"))):
            with open(path, encoding="utf-8") as handle:
                data = json.load(handle)
            name = data.get("project")
            # The shared arm only: an isolated file measures the same targets against another map,
            # and a side file (`detekt-junk.json`) is not the project's sample.
            if os.path.basename(path) != f"{name}.json":
                continue
            if data.get("target_ordering") != BASELINE or data.get("target_shapes") != "default":
                skipped[os.path.basename(path)] = "not a default-set reach-stratified/v1 file"
                continue
            rows = [row for row in data.get("mutations") or [] if row.get("kind")]
            reach_by_map = {}
            entries = []
            for row in rows:
                map_dir = (row.get("replay") or {}).get("map")
                if map_dir and map_dir not in reach_by_map:
                    full = os.path.join(repo, map_dir)
                    reach_by_map[map_dir] = (target_ordering.reach_counts(full)
                                             if os.path.isdir(full) else None)
                reach = reach_by_map.get(map_dir) if map_dir else None
                file_path = row["target"].split("::", 1)[0]
                covering = (target_ordering.reach_of(target_ordering.qualified_name(file_path),
                                                     reach) if reach else None)
                entries.append({"target": (file_path, row["kind"], row.get("index", 0)),
                                "family": row["kind"], "covering": covering,
                                "size": _size(checkouts, name, data.get("commit"), file_path),
                                "hit": bool(hit(row))})
            known = projects.setdefault(name, [])
            seen = {entry["target"] for entry in known}
            known += [entry for entry in entries if entry["target"] not in seen]
    for entries in projects.values():
        stratum_of = _strata(entries)
        for entry in entries:
            entry["features"] = family_order.features(stratum_of[entry["target"]],
                                                      entry["covering"], entry["size"])
    return projects, skipped


def _strata(entries):
    """`reach-stratified/v1`'s terciles over the project's archived rows' reach; unknown without
    a map."""
    positive = sorted({e["covering"] for e in entries if e["covering"]})
    if not positive:
        return {e["target"]: (None if e["covering"] is None else 3) for e in entries}
    low, high = positive[len(positive) // 3], positive[(2 * len(positive)) // 3]
    return {e["target"]: (None if e["covering"] is None else 3 if e["covering"] <= 0
                          else 0 if e["covering"] >= high else 1 if e["covering"] >= low else 2)
            for e in entries}


def compare(projects, first=family_order.FAMILY_TARGET):
    """Per family, the leave-one-project-out comparison and its decision."""
    families = {}
    for name in sorted(projects):
        others = [entry for other, entries in projects.items() if other != name
                  for entry in entries]
        model = family_order.fit(others)
        for family in sorted({e["family"] for e in projects[name]}):
            own = [e for e in projects[name] if e["family"] == family]
            result = families.setdefault(family, {"projects": 0, "rows": 0, "first_draws": 0,
                                                  "baseline_hits": 0, "learned_hits": 0,
                                                  "projects_losing": [], "unscored": 0})
            result["projects"] += 1
            result["rows"] += len(own)
            scores = {}
            for e in own:
                value = family_order.score(model, family, e["features"])
                if value is None:
                    result["unscored"] += 1
                scores[e["target"]] = 0.0 if value is None else value
            ordered = family_order.learned([e["target"] for e in own],
                                           {e["target"]: e["features"]["stratum"] for e in own},
                                           scores, set())
            hit = {e["target"]: e["hit"] for e in own}
            baseline_hits = sum(hit[e["target"]] for e in own[:first])
            learned_hits = sum(hit[t] for t in ordered[:first])
            result["first_draws"] += len(own[:first])
            result["baseline_hits"] += baseline_hits
            result["learned_hits"] += learned_hits
            if baseline_hits and not learned_hits:
                result["projects_losing"].append(name)
    for result in families.values():
        draws = result["first_draws"]
        result["baseline_share"] = round(result["baseline_hits"] / draws, 4) if draws else None
        result["learned_share"] = round(result["learned_hits"] / draws, 4) if draws else None
        result["adopted"] = (result["learned_hits"] > result["baseline_hits"]
                             and not result["projects_losing"])
    return families


def table(families):
    lines = ["| Family | Projects | Rows | First draws | Miss-possible, reach-stratified/v1 "
             "| Miss-possible, learned | Projects losing their last | Adopted |",
             "|---|---:|---:|---:|---:|---:|---|---|"]
    for family, r in sorted(families.items()):
        lines.append(f"| `{family}` | {r['projects']} | {r['rows']} | {r['first_draws']} "
                     f"| {r['baseline_hits']} ({r['baseline_share']:.1%}) "
                     f"| {r['learned_hits']} ({r['learned_share']:.1%}) "
                     f"| {', '.join(r['projects_losing']) or 'none'} "
                     f"| {'yes' if r['adopted'] else 'no'} |")
    return "\n".join(lines)


def model_file(projects, families):
    """What the sweep reads: the adopted families, the model fitted on every project, and the
    targets it was fitted on, which it never ranks."""
    adopted = sorted(f for f, r in families.items() if r["adopted"])
    if not adopted:
        return None
    every = [e for entries in projects.values() for e in entries]
    return {"ordering": family_order.LEARNED, "adopted": adopted,
            "model": {f: m for f, m in family_order.fit(every).items() if f in adopted},
            "archived": {name: sorted(list(e["target"]) for e in entries
                                      if e["family"] in adopted)
                         for name, entries in sorted(projects.items())}}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("results", nargs="*", default=DEFAULT_RESULTS,
                        help="results directories, earliest first (default bench/results)")
    parser.add_argument("--json", action="store_true", help="print the comparison as JSON")
    parser.add_argument("--write", action="store_true",
                        help=f"write {os.path.relpath(family_order.MODEL_PATH, HERE)} when a "
                             f"family is adopted")
    args = parser.parse_args(argv)
    projects, skipped = load_runs(args.results)
    families = compare(projects)
    if args.json:
        print(json.dumps({"projects": sorted(projects), "skipped": skipped,
                          "first_draws_per_family": family_order.FAMILY_TARGET,
                          "families": families}, indent=1))
    else:
        print(f"{len(projects)} project(s); first {family_order.FAMILY_TARGET} draws of each "
              f"family, leave one project out\n")
        print(table(families))
        for name, why in sorted(skipped.items()):
            print(f"  skipped {name}: {why}")
    if args.write:
        written = model_file(projects, families)
        if written is None:
            print("no family adopted the learned order; nothing written")
        else:
            os.makedirs(os.path.dirname(family_order.MODEL_PATH), exist_ok=True)
            with open(family_order.MODEL_PATH, "w", encoding="utf-8") as handle:
                json.dump(written, handle, indent=1, sort_keys=True)
                handle.write("\n")
            print(f"wrote {family_order.MODEL_PATH}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
