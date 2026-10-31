"""Tests for `shard.py`: an accepted list is cut into contiguous slices that each fit a job.

Cases return a count of failed checks; one `test_*` entry point asserts.

    python3 bench/test_shard.py
"""

import contextlib
import io
import json
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import shard  # noqa: E402
import sweep_corpus  # noqa: E402


def fail(message):
    print(f"  FAIL: {message}")
    return 1


def case_slices_are_contiguous_cover_the_list_and_fit_the_budget():
    """The budget is the slice's rows; its setup and warm-up come on top."""
    costs = [1000.0] * 25
    slices = shard.plan(costs, overhead=500.0, budget=10_000)
    failures = 0
    flat = [i for start, end in slices for i in range(start, end)]
    if flat != list(range(25)):
        failures += fail(f"slices do not cover the list in order: {slices}")
    for start, end in slices:
        if sum(costs[start:end]) > 10_000:
            failures += fail(f"slice {start}:{end} is over budget")
    if len(slices) != 3:
        failures += fail(f"10 rows of 1,000 s fit 10,000 s of rows; got {slices}")
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


def case_a_row_over_the_default_budget_gets_its_own_slice():
    slices = shard.plan([600.0, 6000.0, 600.0], overhead=shard.SETUP_SECONDS)
    return 0 if slices == [[0, 1], [1, 2], [2, 3]] else fail(f"a 100-minute row: {slices}")


def _sliced(accepted, bench=None, priced_from=None, factor="3"):
    """`shard.py`'s slices and its per-slice costs for this list, with `bench` as the bench
    directory a record job's header and the qualification table are read from."""
    with tempfile.TemporaryDirectory() as root:
        path = os.path.join(root, "accepted.json")
        with open(path, "w", encoding="utf-8") as handle:
            json.dump(accepted, handle)
        saved = sweep_corpus.BENCH
        sweep_corpus.BENCH = bench or root
        err = io.StringIO()
        try:
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(err):
                shard.main([path, "--factor", factor]
                           + (["--priced-from", priced_from] if priced_from else []))
        finally:
            sweep_corpus.BENCH = saved
        with open(path, encoding="utf-8") as handle:
            slices = json.load(handle)["slices"]
    minutes = [float(line.split(", ")[1].split()[0]) for line in err.getvalue().splitlines()]
    return slices, minutes


def _accepted(rows, **rules):
    return {"project": "kafka", "targets": [[f"A{i}.java", "body", i, f"A{i}"] for i in range(rows)],
            **rules}


def case_published_rows_are_priced_as_runners_measured_them():
    """18-minute rows from the last published sweep: five to a 90-minute slice, the factor unused."""
    failures = 0
    with tempfile.TemporaryDirectory() as root:
        published = os.path.join(root, "kafka.json")
        rows = [{"status": "checked", "decision_outcome": "narrowed", "gate_seconds": 0,
                 "selected_seconds": 540, "full_seconds": 540}] * 21
        with open(published, "w", encoding="utf-8") as handle:
            json.dump({"baseline_seconds": 360, "mutations": rows}, handle)
        slices, minutes = _sliced(_accepted(21, recall_only=True), priced_from=published)
    if len(slices) != 5 or any(m > 90 for m in minutes):
        failures += fail(f"21 rows of 18 minutes: {slices}, {minutes} min of rows")
    return failures


def case_a_record_header_alone_prices_every_untimed_row_at_both_arms():
    """No published rows: the record job's own baseline, a runner's, and no row assumed forced."""
    failures = 0
    with tempfile.TemporaryDirectory() as bench:
        os.makedirs(os.path.join(bench, "results"))
        with open(os.path.join(bench, "results", "kafka.json"), "w", encoding="utf-8") as handle:
            json.dump({"baseline_seconds": 720, "mutations": []}, handle)
        slices, minutes = _sliced(_accepted(21, recall_only=True), bench=bench)
    # The gate's corpus-wide estimate plus two 12-minute suite runs.
    if not minutes or any(abs(m / (end - start) - 24.6) > 0.1
                          for m, (start, end) in zip(minutes, slices)) or any(m > 90 for m in minutes):
        failures += fail(f"untimed rows from a 12-minute header: {slices}, {minutes} min of rows")
    return failures


def case_the_factor_scales_only_the_qualification_table():
    failures = 0
    with tempfile.TemporaryDirectory() as bench:
        with open(os.path.join(bench, "qualification.txt"), "w", encoding="utf-8") as handle:
            handle.write("QUALIFIED kafka commit=abc seconds=600\n")
        _, at_one = _sliced(_accepted(1, recall_only=True), bench=bench, factor="1")
        _, at_three = _sliced(_accepted(1, recall_only=True), bench=bench, factor="3")
    if not at_one or abs(at_three[0] - 3 * at_one[0]) > 1:
        failures += fail(f"qualification seconds at factor 1 and 3: {at_one}, {at_three}")
    return failures


def case_only_slices_that_time_a_row_pay_for_a_warm_up():
    fixed = shard.fixed_seconds([[0, 4], [4, 8], [8, 12], [12, 16]], warmup=600.0,
                                recall_only=False, timing_cap=8)
    setup = shard.SETUP_SECONDS
    expected = [setup + 600, setup + 600, setup, setup]
    return 0 if fixed == expected else fail(f"timing cap 8 over 16 rows: {fixed}, not {expected}")


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
