"""Cuts a project's accepted target list into slices, each one CI job of both arms.

Each slice holds at most 90 minutes of projected rows, plus its fixed cost: the job's setup, and
the warm-up run only when the slice times a row (`run.slice_times_a_row`). A row longer than that
gets a slice of its own; one that cannot fit a job's six-hour limit at all is refused.

Rows are priced in runner seconds. From the last published sweep's rows for this project
(`--priced-from`), which runners measured, so they are not scaled again; else from this record
job's own header, a runner's baseline, with every untimed row priced as one that narrows and so
runs both arms; else from the qualification table, which is local seconds scaled by `--factor`.

WHY 90 MINUTES. Smaller slices pack the account's 20 concurrent jobs tighter, so a
sweep ends sooner, but each slice pays its setup and a wait for a runner. Replaying sweep run
40826752236's measured jobs on 20 slots, with each project's slices following its own record job,
a 90-minute cut ended 20 minutes after a 60-minute one, on 71 fewer runner minutes and fewer jobs.
The 3-hour cut it replaces priced an untimed row at one arm and scaled runner seconds by the factor
a second time: in that run one kafka slice ran 308 minutes.

    python3 bench/shard.py bench/logs/demo/accepted.json --factor 1.5 \
        --priced-from published/merged/demo.json

Writes `slices` into the accepted list and prints the shard numbers as a JSON list.
"""

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import cost  # noqa: E402
import run  # noqa: E402
import sweep_corpus  # noqa: E402

BUDGET_SECONDS = 90 * 60
JOB_LIMIT_SECONDS = 6 * 3600
# Checkout, JDKs, cache restore, plugin publish and unpacking the record's state: 2 to 5 minutes a
# slice in sweep run 40826752236.
SETUP_SECONDS = 5 * 60


def row_costs(unit, count, factor):
    """Seconds per accepted position: a timed row runs both arms, an untimed one may not."""
    return [round(60 * factor * (unit["timed_row_minutes"] if position < unit["timed_rows"]
                                 else unit["untimed_row_minutes"]), 1)
            for position in range(count)]


def plan(costs, overhead, budget=BUDGET_SECONDS):
    """Contiguous `[start, end)` slices in list order, each holding rows within `budget` where a row
    allows it; `overhead` is the most a slice adds to its rows. A row too long for a job at all is
    refused: no slicing can measure it."""
    slices, start, spent = [], 0, 0.0
    for position, seconds in enumerate(costs):
        if overhead + seconds > JOB_LIMIT_SECONDS:
            raise SystemExit(f"row {position} is projected at {seconds / 3600:.1f} h, which with the "
                             f"job's setup does not fit the 6 h job limit")
        if position > start and spent + seconds > budget:
            slices.append([start, position])
            start, spent = position, 0.0
        spent += seconds
    if start < len(costs):
        slices.append([start, len(costs)])
    return slices


def fixed_seconds(slices, warmup, recall_only, timing_cap):
    """Each slice's cost beyond its rows: the setup, and the warm-up when it times a row."""
    return [SETUP_SECONDS + (warmup if run.slice_times_a_row(start, end, recall_only, timing_cap)
                             else 0)
            for start, end in slices]


def pricing(name, priced_from, factor):
    """The results data rows are priced from, and the runner seconds per second of it."""
    if priced_from:
        with open(priced_from, encoding="utf-8") as handle:
            return json.load(handle), 1.0
    try:
        with open(os.path.join(sweep_corpus.BENCH, "results", f"{name}.json"),
                  encoding="utf-8") as handle:
            return json.load(handle), 1.0
    except (OSError, ValueError):
        return cost.from_qualification(
            sweep_corpus.qualification_seconds(os.path.join(sweep_corpus.BENCH,
                                                            "qualification.txt"))[name]), factor


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("accepted")
    parser.add_argument("--factor", type=float, required=True,
                        help="runner seconds per local second, for the qualification table only")
    parser.add_argument("--priced-from",
                        help="this project's results file from the last published sweep")
    args = parser.parse_args(argv)
    with open(args.accepted, encoding="utf-8") as handle:
        accepted = json.load(handle)
    name = accepted["project"]
    last, factor = pricing(name, args.priced_from, args.factor)
    count = len(accepted["targets"])
    recall_only, timing_cap = bool(accepted.get("recall_only")), accepted.get("timing_cap")
    # Priced under the rules the recording job ran with, which the shards run with too. With no
    # answered rows to say how many force, every untimed row is priced as narrowing: both arms.
    unit = cost.project(last, count, timing_cap=timing_cap, recall_only=recall_only,
                        ground_truth=accepted.get("ground_truth") or "full",
                        forced_share_without_rows=0.0)
    warmup = factor * (last.get("baseline_seconds") or 0)
    costs = row_costs(unit, count, factor)
    accepted["slices"] = plan(costs, SETUP_SECONDS + warmup) if count else []
    for number, ((start, end), fixed) in enumerate(
            zip(accepted["slices"], fixed_seconds(accepted["slices"], warmup, recall_only,
                                                  timing_cap)), 1):
        print(f"slice {number}: rows {start + 1}-{end}, {sum(costs[start:end]) / 60:.0f} min of "
              f"rows and {fixed / 60:.0f} min fixed", file=sys.stderr)
    with open(args.accepted, "w", encoding="utf-8") as handle:
        json.dump(accepted, handle, indent=1)
    print(json.dumps(list(range(1, len(accepted["slices"]) + 1))))
    return 0


if __name__ == "__main__":
    sys.exit(main())
