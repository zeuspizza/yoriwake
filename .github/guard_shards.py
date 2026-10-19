"""Checks that a guard run split into shards proved every guard it selected, each exactly once.

Each guard job runs as several jobs, each proving the selected guards whose label hashes to its
shard (`prove_guards.py --shard <index>/<count> --results FILE`). A shard that filters too much, one
that never runs, or one that selected from another base would leave a guard proved nowhere while
every job is green, so this check compares two things the shard filter does not produce:

- the selection: what `prove_guards.py --list-selection` writes, recomputed by the check job itself
  with the same flags, never the union of what the shards say they ran;
- what ran: each shard's `--results` file, with every guard's outcome.

It fails on a selected guard that ran in no shard or in more than one, a guard that ran but is not
in the selection, a guard that did not fire, a shard that refused, selected by other flags or left
no result.

    python .github/guard_shards.py check <selection.json> <shards-dir> --count N --file NAME

`<shards-dir>` holds one directory per shard, named `...-<index>-of-<count>`, each with that
shard's results in a file called NAME.
"""

import argparse
import json
import os
import re
import sys

SHARD_DIR = re.compile(r"-(\d+)-of-(\d+)$")
# The flags a selection is made by: a shard selected by others proved another population.
SELECTED_BY = ("mode", "changed", "guard", "selected")


def _read(path):
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def check(selection_path, shards_dir, count, name):
    """Problems with the shards under `shards_dir`, as lines; empty when they proved the selection."""
    problems = []
    selection = _read(selection_path)
    expected = [guard["label"] for guard in selection["guards"]]
    shards = {}
    # No directory at all, as when no artifact matched, is every shard missing, said as such.
    for entry in sorted(os.listdir(shards_dir)) if os.path.isdir(shards_dir) else []:
        match = SHARD_DIR.search(entry)
        path = os.path.join(shards_dir, entry, name)
        if not match or not os.path.isfile(path):
            continue
        index, of = int(match.group(1)), int(match.group(2))
        if of != count:
            problems.append(f"{entry} is a shard of {of}, not of {count}")
            continue
        shards[index] = _read(path)
    for index in range(1, count + 1):
        if index not in shards:
            problems.append(f"shard {index} of {count} left no result")
    where = {}
    for index, result in sorted(shards.items()):
        print(f"shard {index}/{count}: {len(result['guards'])} guard(s)")
        if result.get("shard") != f"{index}/{count}":
            problems.append(f"shard {index} of {count} says it is shard {result.get('shard')}")
        for key in SELECTED_BY:
            if result.get(key) != selection.get(key):
                problems.append(f"shard {index} of {count} was selected by {key}={result.get(key)!r}, "
                                f"not {selection.get(key)!r}")
        if result.get("refused"):
            problems.append(f"shard {index} of {count} refused: {result['refused']}")
        for guard in result["guards"]:
            where.setdefault(guard["label"], []).append(index)
            if guard["outcome"] != "fired":
                detail = f" ({guard['detail']})" if guard.get("detail") else ""
                problems.append(f"{guard['label']} did not fire in shard {index}: {guard['outcome']}{detail}")
    for label in expected:
        if label not in where:
            problems.append(f"{label} ran in no shard")
        elif len(where[label]) > 1:
            problems.append(f"{label} ran in shards {where[label]}")
    for label in sorted(set(where) - set(expected)):
        problems.append(f"{label} ran but is not in the selection")
    return problems


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    run = sub.add_parser("check", help="check that the shards proved every selected guard once")
    run.add_argument("selection")
    run.add_argument("shards_dir")
    run.add_argument("--count", type=int, required=True)
    run.add_argument("--file", required=True, help="the results file each shard directory holds")
    args = parser.parse_args(argv)
    problems = check(args.selection, args.shards_dir, args.count, args.file)
    for problem in problems:
        print(f"FAIL: {problem}", file=sys.stderr)
    if not problems:
        selected = len(_read(args.selection)["guards"])
        print(f"OK: every one of the {selected} selected guard(s) fired, each in exactly one of "
              f"{args.count} shards" if selected else
              f"OK: nothing selected, and every one of {args.count} shards ran and proved nothing")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
