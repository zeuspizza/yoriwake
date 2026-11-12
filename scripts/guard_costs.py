"""The cost table the guard shards are balanced and projected by, from what a guard run measured.

`prove_guards.py --shard I/N` splits the guards by `scripts/guard_costs.json`: what a baseline and a
guard of each functional and Maven class cost on CI. Every `--results` file records the seconds of
each guard's run and of the baseline it paid, and of each phase of its shard. This reads the shards'
results of one run:

    python3 scripts/guard_costs.py derive <shards-dir> [--file F ...] [--out FILE] [--run ID] [--commit SHA]
    python3 scripts/guard_costs.py report <shards-dir> [--file F ...] [--job fast-shard|full-shard]

`derive` writes the table that run measured: for each class it timed, the median of its guards'
seconds and of its baselines'; the same for each other suite in each mode it ran; and the warm-up of the first step of
each shard, by that step's mode. What it did not time keeps its figures, and a class stays
`estimated`. It writes the checked-in table unless `--out` names another file. `report` prints each
shard's job as the table projects it and as the shard measured it, the spread between the shards,
and the worst shard's margin to the job's `timeout-minutes`.

`<shards-dir>` holds one directory per shard, named `...-<index>-of-<count>`, each with that shard's
results in a file called `full.json`. `--file`, repeated in the order the job ran its steps, names
others: `--file fast.json --file changed.json` for a pull request's shards.

The `fast/` rows of `suites` are derived the same way, from a pull request's artifacts: run it with
`--file fast.json --file changed.json` over a run whose guards job ran fast mode. Do not edit them
by hand.
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


def shard_results(shards_dir, names):
    """Each shard's results under `shards_dir`, by its (index, count): each of the files `names`
    that shard holds, by name, in the order `names` gives (the order its job ran them)."""
    found = {}
    for entry in sorted(os.listdir(shards_dir)) if os.path.isdir(shards_dir) else []:
        match = SHARD_DIR.search(entry)
        files = {name: (path, _read(path)) for name in names
                 for path in [os.path.join(shards_dir, entry, name)] if os.path.isfile(path)}
        if match and files:
            found[(int(match.group(1)), int(match.group(2)))] = files
    return found


def _files(results):
    """Every (path, result) of every shard."""
    return [pair for files in results.values() for pair in files.values()]


def untimed(results):
    """The results files written before guards were timed: nothing to derive a cost from."""
    return [path for path, result in _files(results) if "phases" not in result]


def _block(name, entries, last=False):
    """One JSON member of the table, its entries one per line."""
    lines = [f' "{name}": ' + ("[" if isinstance(entries, list) else "{")]
    items = entries if isinstance(entries, list) else [f"{json.dumps(key)}: {json.dumps(value)}"
                                                        for key, value in sorted(entries.items())]
    lines += [f"  {item if isinstance(entries, dict) else json.dumps(item)}," for item in items]
    if items:
        lines[-1] = lines[-1].rstrip(",")
    lines.append(" " + ("]" if isinstance(entries, list) else "}") + ("" if last else ","))
    return lines


def table_text(table):
    """The table as written to disk: one line per class, suite and mode, so a refresh reads as a
    diff of figures."""
    lines = ["{"]
    lines += _block("measured_by", table["measured_by"])
    lines += _block("estimated", table["estimated"])
    lines += _block("suites", table.get("suites", {}))
    lines += _block("warm_up", table.get("warm_up", {}))
    lines += _block("classes", table["classes"], last=True)
    return "\n".join(lines + ["}"]) + "\n"


def _medians(found, old):
    """{key: {"baseline", "guard"}}: each timed key's medians, a missing figure from `old`."""
    figures = {}
    seconds, baselines = found
    for key in set(seconds) | set(baselines):
        before = old.get(key, {})
        guard = round(statistics.median(seconds[key])) if key in seconds else before.get("guard")
        baseline = round(statistics.median(baselines[key])) if key in baselines else before.get("baseline", guard)
        figures[key] = {"baseline": baseline, "guard": guard if guard is not None else baseline}
    return figures


def derive(table, results, measured_by, first="full.json"):
    """`table` with each class the results timed set to the medians of its guards' and baselines'
    seconds, each suite without classes likewise under `suites` by the mode its steps ran, and the warm-up of each mode a
    shard's first step (`first`, the file its job wrote first) ran in under `warm_up`; what they did
    not time is left as it was."""
    by_class, by_suite = ({}, {}), ({}, {})
    for _path, result in _files(results):
        for guard in result["guards"]:
            # A guard refused before it was proved may carry a baseline that failed early: not a cost.
            if guard.get("outcome") == "not proved" or not guard.get("suite"):
                continue
            key = guard.get("suite_class")
            # A functional or Maven guard whose class did not resolve weighs nothing a class can use.
            if not key and guard["suite"] in prove_guards.CLASS_SUITES:
                continue
            seconds, baselines = by_class if key else by_suite
            # A suite's guards cost more in fast mode, where four of them build at once, than
            # beside the Gradle lock's queue: each suite is weighed per mode.
            key = key or f"{result['mode']}/{guard['suite']}"
            if guard.get("seconds") is not None:
                seconds.setdefault(key, []).append(guard["seconds"])
            if guard.get("baseline_seconds") is not None:
                baselines.setdefault(key, []).append(guard["baseline_seconds"])
    classes = {**table["classes"], **_medians(by_class, table["classes"])}
    suites = {**table.get("suites", {}), **_medians(by_suite, table.get("suites", {}))}
    # Only a shard's first step compiles from cold; a later one finds its trees compiled. A shard
    # that lost its first file has no first step to count.
    warm = {}
    for files in results.values():
        if first in files and "warm_up" in files[first][1].get("phases", {}):
            result = files[first][1]
            warm.setdefault(result["mode"], []).append(result["phases"]["warm_up"])
    warm_up = {**table.get("warm_up", {}),
               **{mode: round(statistics.median(found)) for mode, found in warm.items()}}
    measured = set(by_class[0]) | set(by_class[1])
    estimated = sorted(key for key in table["estimated"] if key not in measured)
    # A run names the modes its steps ran; an earlier run stays named for a mode this one did not
    # run, whose suites and warm-up are still its figures.
    modes = sorted({result["mode"] for _path, result in _files(results)})
    kept = [{**run, "modes": [mode for mode in run["modes"] if mode not in modes]}
            for run in table["measured_by"] if set(run.get("modes", [])) - set(modes)]
    return {"measured_by": [{**measured_by, "modes": modes}] + kept, "estimated": estimated,
            "suites": suites, "warm_up": warm_up, "classes": classes}


def run_selection(results):
    """The guards the run selected, as the partition saw them: from its flags when they name the
    whole of a mode, so a shard that left no results does not change the others' projections; else
    the guards the shards held. `results` holds one file per shard: (path, result)."""
    first = next(iter(results.values()))[1]
    if not first.get("changed") and not first.get("guard"):
        if first.get("mode") == "full":
            return list(prove_guards.MUTATIONS)
        if first.get("mode") == "fast":
            return [m for m in prove_guards.MUTATIONS
                    if prove_guards.SUITE_NAMES[m[4]] in prove_guards.FAST_SUITES]
    labels = {guard["label"] for _path, result in results.values() for guard in result["guards"]}
    return [m for m in prove_guards.MUTATIONS if m[0] in labels]


def _minutes(seconds):
    return seconds / 60


def _margin(limit, minutes):
    """How far `minutes` falls under `limit`, or goes over it."""
    return f"{abs(limit - minutes):.1f} min {'under' if minutes <= limit else 'over'}"


def report(results, job=None):
    """Lines: each shard's projected and measured job, then the spread, the shard furthest over its
    projection, and the projected and measured worst shard against the job's limit. A shard is
    projected by the table over the guards it held, step by step as its job ran them; a shard that
    left no results, by the table over the partition of the run's selection."""
    count = max(of for _index, of in results)
    names = list(dict.fromkeys(name for files in results.values() for name in files))
    job = job or ("fast-shard" if "changed.json" in names else "full-shard")
    limit = prove_guards.job_limit(job)
    lines, split = [], {}
    for name in names:
        files = {shard: files[name] for shard, files in results.items() if name in files}
        selection = run_selection(files)
        split[name] = (prove_guards.partition(selection, count)[0], next(iter(files.values()))[1]["mode"])
        # A guard set the run's flags do not name is split again from what the shards held, and that
        # split is not the run's once a shard left nothing: said first, so no projection is misread.
        selected = max(result.get("selected") or 0 for _path, result in files.values())
        # Counted as the shards wrote them: a guard renamed since the run is still one they held.
        holding = len({guard["label"] for _path, result in files.values() for guard in result["guards"]})
        if len(files) < count and len(selection) < selected and holding < selected:
            lines.append(f"PARTIAL: {len(files)} of {count} shards left {name}, holding {holding} of "
                         f"the {selected} selected guards; a shard without it is projected from those alone")
    by_label = {m[0]: m for m in prove_guards.MUTATIONS}
    held = {index: files for (index, _of), files in results.items()}
    projected, measured, over = {}, {}, {}
    for index in range(1, count + 1):
        files = held.get(index, {})
        steps, took, parts = [], 0.0, []
        for position, name in enumerate(names):
            if name in files:
                result = files[name][1]
                guards = [by_label[g["label"]] for g in result["guards"] if g["label"] in by_label]
                mode = result.get("mode", "full")
            else:
                guards, mode = split[name][0][index - 1], split[name][1]
            step = prove_guards.step_projection(guards, mode, first=position == 0)
            steps.append(step)
            seconds = files[name][1].get("seconds") if name in files else None
            took = None if seconds is None or took is None else took + seconds
            phases = ", ".join(f"{phase} {_minutes(value):.1f}" for phase, value
                               in (files[name][1].get("phases", {}) if name in files else {}).items())
            tail = files[name][1].get("idle_tail") if name in files else None
            parts.append(f"{name} projected {_minutes(step['total']):.1f} ({prove_guards.phases_text(step)})"
                         + (f", measured {_minutes(seconds):.1f} ({phases}"
                            + (f", idle tail {_minutes(tail):.1f}" if tail else "") + ")"
                            if seconds is not None else ", measured nothing"))
        projected[index] = _minutes(prove_guards.job_projection(steps))
        if took is None or not files:
            lines.append(f"shard {index}/{count}: projected {projected[index]:.1f} min, measured nothing; "
                         + "; ".join(parts))
            continue
        measured[index] = _minutes(took + prove_guards.JOB_SETUP)
        over[index] = measured[index] - projected[index]
        lines.append(f"shard {index}/{count}: projected {projected[index]:.1f} min, measured "
                     f"{measured[index]:.1f} min; " + "; ".join(parts))
    if measured:
        slowest = max(measured, key=measured.get)
        fastest = min(measured, key=measured.get)
        lines.append(f"spread: {measured[slowest] - measured[fastest]:.1f} min, shard {slowest} "
                     f"slowest at {measured[slowest]:.1f}, shard {fastest} fastest at {measured[fastest]:.1f}")
        typical = statistics.median(over.values())
        worst = max(over, key=over.get)
        lines.append(f"furthest over its projection: shard {worst}, {over[worst]:+.1f} min "
                     f"(the median shard {typical:+.1f} min)")
        heaviest = max(projected, key=projected.get)
        line = (f"worst shard: projected {projected[heaviest]:.1f} min (shard {heaviest}), measured "
                f"{measured[slowest]:.1f} min (shard {slowest}), "
                f"{(projected[heaviest] / measured[slowest] - 1) * 100:+.1f} %")
        if limit:
            line += (f"; {job}'s limit {limit} min: projected {_margin(limit, projected[heaviest])}, "
                     f"measured {_margin(limit, measured[slowest])}")
            # A shard cut at the limit writes nothing for the step it was killed in, so the measured
            # margin would otherwise be the slowest of the shards that did not reach it.
            unmeasured = [str(index) for index in range(1, count + 1) if index not in measured]
            if unmeasured:
                line += (f"; shard{'s' if len(unmeasured) > 1 else ''} {', '.join(unmeasured)} measured "
                         "nothing (possibly cut at the limit), so the measured margin covers only the rest")
        lines.append(line)
    return lines


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    for command in ("derive", "report"):
        run = sub.add_parser(command)
        run.add_argument("shards_dir")
        run.add_argument("--file", action="append",
                         help="a results file each shard directory holds, repeatable in the order the "
                              "shard's job wrote them (default: full.json)")
        if command == "report":
            run.add_argument("--job", choices=("fast-shard", "full-shard"),
                             help="the job whose timeout-minutes the margin is against (default: "
                                  "fast-shard when a shard holds changed.json, else full-shard)")
        if command == "derive":
            run.add_argument("--out", default=prove_guards.COSTS_FILE,
                             help="where to write the table (default: the checked-in one)")
            run.add_argument("--run", help="the CI run the results came from")
            run.add_argument("--commit", help="the commit that run measured")
    args = parser.parse_args(argv)
    names = args.file or ["full.json"]
    results = shard_results(args.shards_dir, names)
    if not results:
        print(f"REFUSING: no shard's {' or '.join(names)} under {args.shards_dir}", file=sys.stderr)
        return 1
    if args.command == "report":
        for line in report(results, args.job):
            print(line)
        return 0
    old = untimed(results)
    if old:
        print(f"REFUSING: {old[0]} records no timings; it was written before guards were timed.",
              file=sys.stderr)
        return 1
    measured_by = {"run": int(args.run) if args.run and args.run.isdigit() else args.run,
                   "commit": args.commit[:8] if args.commit else None}
    table = derive(_read(prove_guards.COSTS_FILE), results, measured_by, names[0])
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as handle:
        handle.write(table_text(table))
    print(f"wrote {args.out}: {len(table['classes']) - len(table['estimated'])} class(es) measured, "
          f"{len(table['estimated'])} still estimated")
    return 0


if __name__ == "__main__":
    sys.exit(main())
