"""Tests for `shard.py`: an accepted list is cut into contiguous slices that each fit a job.

Cases return a count of failed checks; one `test_*` entry point asserts.

    python3 bench/test_shard.py
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import shard  # noqa: E402


def fail(message):
    print(f"  FAIL: {message}")
    return 1


def case_slices_are_contiguous_cover_the_list_and_fit_the_budget():
    costs = [1000.0] * 25
    slices = shard.plan(costs, overhead=500.0, budget=10_800)
    failures = 0
    flat = [i for start, end in slices for i in range(start, end)]
    if flat != list(range(25)):
        failures += fail(f"slices do not cover the list in order: {slices}")
    for start, end in slices:
        if 500 + sum(costs[start:end]) > 10_800:
            failures += fail(f"slice {start}:{end} is over budget")
    if len(slices) != 3:
        failures += fail(f"10 rows of 1,000 s fit 10,800 s with 500 s overhead; got {slices}")
    return failures


def case_a_row_too_long_for_any_job_is_refused():
    try:
        shard.plan([100.0, 30_000.0], overhead=0.0, budget=10_800)
    except SystemExit as refused:
        return 0 if "6 h" in str(refused) else fail(f"refused without naming the limit: {refused}")
    return fail("a row longer than a job's limit was planned")


def case_a_row_over_the_budget_but_under_the_limit_gets_its_own_slice():
    slices = shard.plan([100.0, 12_000.0, 100.0], overhead=0.0, budget=10_800)
    return 0 if slices == [[0, 1], [1, 2], [2, 3]] else fail(f"slices: {slices}")


def case_timed_positions_cost_more_and_the_factor_scales_every_row():
    unit = {"timed_row_minutes": 10.0, "untimed_row_minutes": 2.0, "timed_rows": 2}
    costs = shard.row_costs(unit, count=4, factor=1.5)
    expected = [900.0, 900.0, 180.0, 180.0]
    return 0 if costs == expected else fail(f"costs {costs}, expected {expected}")


def test_shard():
    failures = 0
    for name, case in sorted(globals().items()):
        if name.startswith("case_"):
            print(name)
            failures += case()
    assert failures == 0, f"{failures} check(s) failed"


if __name__ == "__main__":
    test_shard()
    print("ok")
