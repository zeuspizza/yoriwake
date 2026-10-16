# A comment that changes nothing.
"""
What a sweep will cost before it starts, and what it cost after. Neither is a stop or a
cap: the cost follows from the sample rules, and this only says what it is.

PROJECTION, per project, from its last results file (or its qualification run when it has none):
fixed work -- a warm-up and a baseline per repeat, each about one baseline run, the capture, and
the preflight -- plus each planned row at a per-row cost:

  timed row           the compile gate, the selected arm and the full arm
  untimed, forced     the gate and the selected arm, which ran everything and is the ground truth
  untimed, narrowed   the gate, the selected arm, and the full arm -- under `--ground-truth auto`
                      the complement instead, and under `check` the complement as well, priced at
                      what earlier rows' complements cost, else at the full arm

The gate is a row's `gate_seconds` (compile and rejected draws); until rows record it, one
corpus-wide estimate per row, the same for every project: `UNRECORDED_ROW_SECONDS`. A row's
`followup_seconds` -- the re-runs a missed failure triggers -- is never in either figure: re-runs
happen only on rows that missed, so pricing them would charge a project for missing. Stalls are a
term of their own: a project whose earlier rows stalled is said to have, never averaged in.

REPORT, after the sweep, from the results files it wrote: hours per family (the row's `kind`) and
per row where a miss was possible, per project and in total, split by `ground_truth` and `timed`.

    python bench/cost.py --project grpc-java --limit 16 --timing-cap 8
    python bench/cost.py --report bench/results/ktlint.json
"""

import argparse
from collections import defaultdict
import json
import os
import statistics
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import assert_recall  # noqa: E402  (which rows a miss was possible on, defined once)
import run  # noqa: E402

# THE GATE BEFORE ROWS RECORD IT, one estimate for the whole corpus:
# over the 0.1 sweep's 31 shared files, a row's wall clock beyond its recorded arms was 34 s in the
# median file. The pooled 72 s carries iceberg's timed-out row and three stalled projects, which
# this tool prices as stalls, not as the per-row cost.
UNRECORDED_ROW_SECONDS = 34.0
ANSWERED = ("checked", "no-effect")


def _median(values, default):
    values = [v for v in values if isinstance(v, (int, float))]
    return statistics.median(values) if values else default


def row_cost(row):
    """What one row cost in seconds: its gate and every arm it ran, never its follow-up re-runs."""
    gate = row.get("gate_seconds")
    return ((gate if isinstance(gate, (int, float)) else UNRECORDED_ROW_SECONDS)
            + sum(row.get(arm) or 0 for arm in ("selected_seconds", "full_seconds",
                                                 "complement_seconds")))


def per_row(data):
    """The per-row seconds the projection prices with, from a results file's answered rows."""
    rows = [r for r in data.get("mutations") or [] if r.get("status") in ANSWERED]
    suite = data.get("baseline_seconds") or 0
    full = _median([r.get("full_seconds") for r in rows], suite)
    return {
        "gate": _median([r.get("gate_seconds") for r in rows], UNRECORDED_ROW_SECONDS),
        "selected": _median([r.get("selected_seconds") for r in rows], suite),
        "full": full,
        "complement": _median([r.get("complement_seconds") for r in rows], full),
        "forced_share": (sum(1 for r in rows if r.get("decision_outcome") != "narrowed") / len(rows)
                         if rows else 1.0),
        "stalled_rows": sum(1 for r in data.get("mutations") or [] if r.get("status") == "stalled"),
    }


def project(data, rows, timing_cap=None, recall_only=False, ground_truth="full"):
    """One project's projected hours for a sweep of `rows` rows under these rules."""
    unit = per_row(data)
    suite = data.get("baseline_seconds") or unit["full"]
    repeats = data.get("baseline_repeats") or run.DEFAULT_BASELINE_REPEATS
    fixed = (data.get("capture_seconds") or suite) + suite * (1 + repeats) + unit["selected"]
    timed = 0 if recall_only else rows if timing_cap is None else min(timing_cap, rows)
    untimed = rows - timed
    # `check` runs both the full arm and the complement on an untimed narrowed row.
    truth = (unit["complement"] if ground_truth == "auto"
             else unit["full"] + (unit["complement"] if ground_truth == "check" else 0))
    timed_row = unit["gate"] + unit["selected"] + unit["full"]
    untimed_row = (unit["gate"] + unit["selected"]
                   + (1 - unit["forced_share"]) * truth)
    seconds = fixed + timed * timed_row + untimed * untimed_row
    return {"rows": rows, "timed_rows": timed, "hours": round(seconds / 3600, 2),
            "fixed_hours": round(fixed / 3600, 2),
            "timed_row_minutes": round(timed_row / 60, 1),
            "untimed_row_minutes": round(untimed_row / 60, 1),
            "stalled_rows_before": unit["stalled_rows"]}


def from_qualification(seconds):
    """A stand-in results header for a project swept never: every arm one qualification run."""
    return {"capture_seconds": seconds, "baseline_seconds": seconds, "mutations": []}


def report(data):
    """What a finished file cost, per family and per row where a miss was possible."""
    rows = [r for r in data.get("mutations") or [] if r.get("status") in ANSWERED]
    hours = sum(row_cost(r) for r in rows) / 3600
    possible = sum(1 for r in rows if r.get("status") == "checked"
                   and assert_recall.narrowed_at_all(r))
    families, split = defaultdict(float), defaultdict(float)
    for row in rows:
        families[row.get("kind") or "unknown"] += row_cost(row) / 3600
        split[f"{row.get('ground_truth') or 'full'}/"
              f"{'timed' if row.get('timed') else 'untimed'}"] += row_cost(row) / 3600
    return {"project": data.get("project"), "rows": len(rows), "hours": round(hours, 2),
            "miss_possible_rows": possible,
            "hours_per_miss_possible_row": round(hours / possible, 2) if possible else None,
            "hours_per_family": {k: round(v, 2) for k, v in sorted(families.items())},
            "hours_by_ground_truth_and_timing": {k: round(v, 2) for k, v in sorted(split.items())},
            "followup_hours_left_out": round(sum(r.get("followup_seconds") or 0
                                                 for r in rows) / 3600, 2)}


def total(reports):
    """The corpus line: hours and hours per miss-possible row over every project's report."""
    hours = sum(r["hours"] for r in reports)
    possible = sum(r["miss_possible_rows"] for r in reports)
    return {"projects": len(reports), "hours": round(hours, 2), "miss_possible_rows": possible,
            "hours_per_miss_possible_row": round(hours / possible, 2) if possible else None}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--project", nargs="+", default=[], help="project names to project")
    parser.add_argument("--limit", type=int, default=16)
    parser.add_argument("--timing-cap", type=int, default=None)
    parser.add_argument("--recall-only", action="store_true")
    parser.add_argument("--ground-truth", choices=run.GROUND_TRUTH_MODES, default="full")
    parser.add_argument("--report", nargs="+", default=[], help="results files to report on")
    args = parser.parse_args(argv)
    for name in args.project:
        path = os.path.join(run.RESULTS, f"{name}.json")
        with open(path, encoding="utf-8") as handle:
            data = json.load(handle)
        print(json.dumps({"project": name, **project(data, args.limit, args.timing_cap,
                                                     args.recall_only, args.ground_truth)}))
    reports = []
    for path in args.report:
        with open(path, encoding="utf-8") as handle:
            reports.append(report(json.load(handle)))
        print(json.dumps(reports[-1]))
    if reports:
        print(json.dumps({"total": total(reports)}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
