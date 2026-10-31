"""The cost table the guard shards are balanced by, from what an every-guard run measured.

`prove_guards.py --shard I/N` splits the guards by `scripts/guard_costs.json`: what a baseline and a
guard of each functional and Maven class cost on CI. Every `--results` file records the seconds of
each guard's run and of the baseline it paid, and of each phase of its shard. This reads the shards'
results of one run:

    python3 scripts/guard_costs.py derive <shards-dir> [--out FILE] [--run ID] [--commit SHA]
    python3 scripts/guard_costs.py report <shards-dir>

`derive` writes the table that run measured: for each class it timed, the median of its guards'
seconds and of its baselines'. A class it did not time keeps its figures and stays `estimated`. It
writes the checked-in table unless `--out` names another file. `report` prints each shard's wall
as the table projected it and as the shard measured it, and the spread between the shards.

`<shards-dir>` holds one directory per shard, named `...-<index>-of-<count>`, each with that shard's
results in a file called `full.json` (`--file` names another).
"""

import argparse
import json
import os
import re
import statistics
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import prove_guards  # noqa: E402

SHARD_DIR = re.compile(r"-(\d+)-of-(\d+)$")


def _read(path):
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def shard_results(shards_dir, name):
    """Each shard's results under `shards_dir`, by its (index, count)."""
    found = {}
    for entry in sorted(os.listdir(shards_dir)) if os.path.isdir(shards_dir) else []:
        match = SHARD_DIR.search(entry)
        path = os.path.join(shards_dir, entry, name)
        if match and os.path.isfile(path):
            found[(int(match.group(1)), int(match.group(2)))] = (path, _read(path))
    return found


def untimed(results):
    """The results files written before guards were timed: nothing to derive a cost from."""
    return [path for path, result in results.values() if "phases" not in result]


def table_text(table):
    """The table as written to disk: one line per class, so a refresh reads as a diff of figures."""
    lines = ["{", ' "measured_by": [']
    lines += [f"  {json.dumps(run)}," for run in table["measured_by"]]
    lines[-1] = lines[-1].rstrip(",")
    lines += [" ],", ' "estimated": [']
    lines += [f"  {json.dumps(key)}," for key in table["estimated"]]
    if table["estimated"]:
        lines[-1] = lines[-1].rstrip(",")
    lines += [" ],", ' "classes": {']
    lines += [f"  {json.dumps(key)}: {json.dumps(cost)}," for key, cost in sorted(table["classes"].items())]
    lines[-1] = lines[-1].rstrip(",")
    lines += [" }", "}"]
    return "\n".join(lines) + "\n"


def derive(table, results, measured_by):
    """`table` with each class the results timed set to the medians of its guards' and baselines'
    seconds; a class they did not time is left as it was."""
    seconds, baselines = {}, {}
    for _path, result in results.values():
        for guard in result["guards"]:
            key = guard.get("suite_class")
            # A guard refused before it was proved may carry a baseline that failed early: not a cost.
            if not key or guard.get("outcome") == "not proved":
                continue
            if guard.get("seconds") is not None:
                seconds.setdefault(key, []).append(guard["seconds"])
            if guard.get("baseline_seconds") is not None:
                baselines.setdefault(key, []).append(guard["baseline_seconds"])
    classes = dict(table["classes"])
    for key in set(seconds) | set(baselines):
        old = classes.get(key, {})
        guard = round(statistics.median(seconds[key])) if key in seconds else old.get("guard")
        baseline = round(statistics.median(baselines[key])) if key in baselines else old.get("baseline", guard)
        classes[key] = {"baseline": baseline, "guard": guard if guard is not None else baseline}
    measured = set(seconds) | set(baselines)
    estimated = sorted(key for key in table["estimated"] if key not in measured)
    return {"measured_by": [measured_by], "estimated": estimated, "classes": classes}


def run_selection(results):
    """The guards the run selected, as the partition saw them: from its flags when they name the
    whole of a mode, so a shard that left no results does not change the others' projections; else
    the guards the shards held."""
    first = next(iter(results.values()))[1]
    if not first.get("changed") and not first.get("guard"):
        if first.get("mode") == "full":
            return list(prove_guards.MUTATIONS)
        if first.get("mode") == "fast":
            return [m for m in prove_guards.MUTATIONS
                    if prove_guards.SUITE_NAMES[m[4]] in prove_guards.FAST_SUITES]
    labels = {guard["label"] for _path, result in results.values() for guard in result["guards"]}
    return [m for m in prove_guards.MUTATIONS if m[0] in labels]


def report(results):
    """Lines: each shard's projected and measured wall, then the spread and the shard furthest over
    its projection. The projection is the table's, over the guards the shards held."""
    count = max(of for _index, of in results)
    selection = run_selection(results)
    _shards, loads = prove_guards.partition(selection, count)
    lines, measured, over = [], {}, {}
    # A guard set the run's flags do not name is split again from what the shards held, and that
    # split is not the run's once a shard left nothing: said first, so no projection is misread.
    selected = max(result.get("selected") or 0 for _path, result in results.values())
    if len(selection) < selected:
        lines.append(f"PARTIAL: {len(results)} of {count} shards left results, holding {len(selection)} of "
                     f"the {selected} selected guards; the projections below split those alone")
    held = {index: result for (index, _of), (_path, result) in results.items()}
    for index in range(1, count + 1):
        projected = loads[index - 1]["wall"] / 60
        result = held.get(index, {})
        took = result.get("seconds")
        phases = ", ".join(f"{name} {seconds / 60:.1f}" for name, seconds in result.get("phases", {}).items())
        if took is None:
            lines.append(f"shard {index}/{count}: projected {projected:.1f} min, measured nothing")
            continue
        measured[index] = took / 60
        over[index] = measured[index] - projected
        tail = f", idle tail {result['idle_tail'] / 60:.1f}" if result.get("idle_tail") else ""
        lines.append(f"shard {index}/{count}: projected {projected:.1f} min, measured "
                     f"{measured[index]:.1f} min ({phases}{tail})")
    if measured:
        slowest = max(measured, key=measured.get)
        fastest = min(measured, key=measured.get)
        lines.append(f"spread: {measured[slowest] - measured[fastest]:.1f} min, shard {slowest} "
                     f"slowest at {measured[slowest]:.1f}, shard {fastest} fastest at {measured[fastest]:.1f}")
        typical = statistics.median(over.values())
        worst = max(over, key=over.get)
        lines.append(f"furthest over its projection: shard {worst}, {over[worst]:+.1f} min "
                     f"(the median shard {typical:+.1f} min)")
    return lines


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    for command in ("derive", "report"):
        run = sub.add_parser(command)
        run.add_argument("shards_dir")
        run.add_argument("--file", default="full.json", help="the results file each shard directory holds")
        if command == "derive":
            run.add_argument("--out", default=prove_guards.COSTS_FILE,
                             help="where to write the table (default: the checked-in one)")
            run.add_argument("--run", help="the CI run the results came from")
            run.add_argument("--commit", help="the commit that run measured")
    args = parser.parse_args(argv)
    results = shard_results(args.shards_dir, args.file)
    if not results:
        print(f"REFUSING: no shard's {args.file} under {args.shards_dir}", file=sys.stderr)
        return 1
    if args.command == "report":
        for line in report(results):
            print(line)
        return 0
    old = untimed(results)
    if old:
        print(f"REFUSING: {old[0]} records no timings; it was written before guards were timed.",
              file=sys.stderr)
        return 1
    measured_by = {"run": int(args.run) if args.run and args.run.isdigit() else args.run,
                   "commit": args.commit[:8] if args.commit else None}
    table = derive(_read(prove_guards.COSTS_FILE), results, measured_by)
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as handle:
        handle.write(table_text(table))
    print(f"wrote {args.out}: {len(table['classes']) - len(table['estimated'])} class(es) measured, "
          f"{len(table['estimated'])} still estimated")
    return 0


if __name__ == "__main__":
    sys.exit(main())
