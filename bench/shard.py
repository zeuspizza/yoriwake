"""Cuts a project's accepted target list into slices, each one CI job of both arms.

Each slice is sized from the last local per-row times (`cost.py`'s projection), scaled by how much
slower a runner is than the machine that measured them, to fit half a job's six-hour limit: a slice
that runs long still finishes, and a killed one writes nothing, so its retry is clean.

    python3 bench/shard.py bench/logs/demo/accepted.json --factor 1.6

Writes `slices` into the accepted list and prints the shard numbers as a JSON list, for a workflow
matrix.
"""

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import cost  # noqa: E402
import sweep_corpus  # noqa: E402

BUDGET_SECONDS = 3 * 3600
JOB_LIMIT_SECONDS = 6 * 3600


def row_costs(unit, count, factor):
    """Seconds per accepted position: a timed row runs both arms, an untimed one may not."""
    return [round(60 * factor * (unit["timed_row_minutes"] if position < unit["timed_rows"]
                                 else unit["untimed_row_minutes"]), 1)
            for position in range(count)]


def plan(costs, overhead, budget=BUDGET_SECONDS):
    """Contiguous `[start, end)` slices in list order, each `overhead` plus its rows within `budget`
    where a row allows it. A row too long for a job at all is refused: no slicing can measure it."""
    slices, start, spent = [], 0, overhead
    for position, seconds in enumerate(costs):
        if overhead + seconds > JOB_LIMIT_SECONDS:
            raise SystemExit(f"row {position} is projected at {seconds / 3600:.1f} h, which with the "
                             f"job's setup does not fit the 6 h job limit")
        if position > start and spent + seconds > budget:
            slices.append([start, position])
            start, spent = position, overhead
        spent += seconds
    if start < len(costs):
        slices.append([start, len(costs)])
    return slices


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("accepted")
    parser.add_argument("--factor", type=float, required=True,
                        help="runner seconds per local second, measured on the demo")
    args = parser.parse_args(argv)
    with open(args.accepted, encoding="utf-8") as handle:
        accepted = json.load(handle)
    name = accepted["project"]
    try:
        with open(os.path.join(sweep_corpus.BENCH, "results", f"{name}.json"),
                  encoding="utf-8") as handle:
            last = json.load(handle)
    except (OSError, ValueError):
        last = cost.from_qualification(
            sweep_corpus.qualification_seconds(os.path.join(sweep_corpus.BENCH,
                                                            "qualification.txt"))[name])
    count = len(accepted["targets"])
    # Priced under the rules the recording job ran with, which the shards run with too.
    unit = cost.project(last, count, timing_cap=accepted.get("timing_cap"),
                        recall_only=bool(accepted.get("recall_only")),
                        ground_truth=accepted.get("ground_truth") or "full")
    # Each shard warms its own daemon on one unmutated run before its first row.
    overhead = args.factor * (last.get("baseline_seconds") or 0)
    accepted["slices"] = plan(row_costs(unit, count, args.factor), overhead) if count else []
    with open(args.accepted, "w", encoding="utf-8") as handle:
        json.dump(accepted, handle, indent=1)
    print(json.dumps(list(range(1, len(accepted["slices"]) + 1))))
    return 0


if __name__ == "__main__":
    sys.exit(main())
