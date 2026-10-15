"""
Tests for the sweep cost projection and report: what they price, what they leave out,
and that neither reads a miss.

Run with `python bench/test_cost.py`, or under pytest. Cases RETURN a count of failed checks,
as in test_run.py; the single test_* entry point asserts.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cost  # noqa: E402


def fail(message):
    print(f"  FAIL: {message}")
    return 1


def _row(outcome, selected, full, kind="body", status="checked", **extra):
    return {"decision_outcome": outcome, "selected_seconds": selected, "full_seconds": full,
            "kind": kind, "status": status,
            "selected_distinct": 10 if outcome == "narrowed" else 99,
            "full_distinct": 99, **extra}


# A worked example: bitwarden-android, three rows, about ten minutes of build, compile and
# decode a row, then a selected arm and a full arm of ten to twelve minutes each.
BITWARDEN = {"project": "bitwarden-android", "capture_seconds": 1000, "baseline_seconds": 500,
             "baseline_repeats": 2,
             "mutations": [_row("full-run", 700, 740, gate_seconds=600),
                           _row("full-run", 700, 740, gate_seconds=600),
                           _row("narrowed", 700, 740, gate_seconds=600,
                                complement_seconds=120)]}


def case_the_worked_example_prices_a_timed_and_a_capped_row():
    failures = 0
    today = cost.project(BITWARDEN, 16)
    if not 32 <= today["timed_row_minutes"] <= 36 or today["timed_rows"] != 16:
        failures += fail(f"a timed bitwarden-android row is {today['timed_row_minutes']} min, "
                         f"not about 34")
    capped = cost.project(BITWARDEN, 16, timing_cap=8, ground_truth="auto")
    if capped["timed_rows"] != 8 or not 19 <= capped["untimed_row_minutes"] <= 23:
        failures += fail(f"a bitwarden-android row past the cap is "
                         f"{capped['untimed_row_minutes']} min, not about 21")
    checked = cost.project(BITWARDEN, 16, timing_cap=8, ground_truth="check")
    if not checked["untimed_row_minutes"] > cost.project(BITWARDEN, 16, timing_cap=8)[
            "untimed_row_minutes"]:
        failures += fail("a check-mode row was priced without the complement it also runs")
    if not capped["hours"] < today["hours"]:
        failures += fail("the cap and the complement made the sweep dearer")
    return failures


def case_follow_up_re_runs_are_in_neither_figure():
    failures = 0
    quiet = {**BITWARDEN}
    noisy = {**BITWARDEN, "mutations": [{**row, "followup_seconds": 5000.0}
                                        for row in BITWARDEN["mutations"]]}
    if cost.project(quiet, 16) != cost.project(noisy, 16):
        failures += fail("a project whose rows re-ran missed tests was projected dearer")
    if cost.report(quiet)["hours"] != cost.report(noisy)["hours"]:
        failures += fail("the report charged a project for its follow-up re-runs")
    if cost.report(noisy)["followup_hours_left_out"] != round(3 * 5000 / 3600, 2):
        failures += fail("the follow-ups left out are not said")
    if cost.row_cost({"selected_seconds": 10, "full_seconds": 20}) != \
            10 + 20 + cost.UNRECORDED_ROW_SECONDS:
        failures += fail("a row without `gate_seconds` is not priced at the corpus estimate")
    return failures


def case_the_report_splits_hours_per_family_and_per_miss_possible_row():
    failures = 0
    files = [
        {"project": "a", "mutations": [
            _row("narrowed", 60, 120, kind="body", gate_seconds=0, ground_truth="full", timed=True),
            _row("full-run", 120, 120, kind="schema", gate_seconds=0, timed=True)]},
        {"project": "b", "mutations": [
            _row("narrowed", 60, None, kind="body", gate_seconds=0, complement_seconds=60,
                 ground_truth="complement", timed=False),
            _row("narrowed", 60, 120, kind="body", status="no-effect", gate_seconds=0,
                 timed=False)]},
        {"project": "c", "mutations": [_row("full-run", 3600, None, gate_seconds=0,
                                            status="stalled")]},
    ]
    reports = [cost.report(data) for data in files]
    a, b, c = reports
    if a["hours_per_family"] != {"body": 0.05, "schema": 0.07}:
        failures += fail(f"hours per family: {a['hours_per_family']}")
    if a["miss_possible_rows"] != 1 or a["hours_per_miss_possible_row"] != 0.12:
        failures += fail(f"per miss-possible row: {a}")
    if b["hours_by_ground_truth_and_timing"] != {"complement/untimed": 0.03, "full/untimed": 0.05}:
        failures += fail(f"split by ground truth and timing: "
                         f"{b['hours_by_ground_truth_and_timing']}")
    if c["rows"] != 0 or c["hours_per_miss_possible_row"] is not None:
        failures += fail(f"a stalled row was priced as an answered one: {c}")
    every = cost.total(reports)
    if every["projects"] != 3 or every["miss_possible_rows"] != 2:
        failures += fail(f"the corpus total: {every}")
    stalled = cost.project(files[2], 4)
    if stalled["stalled_rows_before"] != 1:
        failures += fail("a project whose earlier rows stalled is not said to have")
    return failures


def case_neither_figure_reads_a_miss():
    flipped = {**BITWARDEN, "mutations": [{**row, "missed": ["p.T::t"]}
                                          for row in BITWARDEN["mutations"]]}
    if cost.project(flipped, 16, timing_cap=8) != cost.project(BITWARDEN, 16, timing_cap=8) \
            or cost.report(flipped) != cost.report(BITWARDEN):
        return fail("flipping the rows' missed sets moved a figure")
    return 0


def case_a_project_never_swept_is_projected_from_its_qualification_run():
    projected = cost.project(cost.from_qualification(300.0), 8)
    if not projected["hours"] or projected["timed_rows"] != 8:
        return fail(f"a project with no results file was projected as {projected}")
    return 0


CASES = (case_the_worked_example_prices_a_timed_and_a_capped_row,
         case_follow_up_re_runs_are_in_neither_figure,
         case_the_report_splits_hours_per_family_and_per_miss_possible_row,
         case_neither_figure_reads_a_miss,
         case_a_project_never_swept_is_projected_from_its_qualification_run)


def test_cost():
    assert sum(case() for case in CASES) == 0


def main():
    failures = 0
    for case in CASES:
        print(case.__name__)
        failures += case()
    print(f"\n{'all passed' if not failures else 'FAILED'}: {failures} failed checks")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
