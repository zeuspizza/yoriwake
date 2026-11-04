"""
Tests for `guard_costs.py`: the cost table a run's shard results derive, and the report of how
close the shards came to their projection.

Run: `python scripts/test_guard_costs.py`
"""

import contextlib
import io
import json
import os
import re
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import guard_costs as gc  # noqa: E402
import prove_guards as pg  # noqa: E402

TABLE = {"measured_by": [{"run": 1, "commit": "a"}],
         "estimated": ["functional/OldTest", "maven/UntimedTest"],
         "classes": {"functional/OldTest": {"baseline": 10, "guard": 10},
                     "maven/UntimedTest": {"baseline": 20, "guard": 20},
                     "maven/KnownTest": {"baseline": 30, "guard": 30}}}


def fail(message):
    print(f"  FAIL: {message}")
    return 1


def _shard(root, index, count, guards, seconds=None, phases=None, timed=True, name="full.json",
           mode="full", suite="functional", **flags):
    """One shard's `--results` file: `guards` are (label, suite_class, seconds, baseline_seconds)."""
    path = os.path.join(root, f"guards-every-{index}-of-{count}")
    os.makedirs(path, exist_ok=True)
    result = {"shard": f"{index}/{count}", "mode": mode, "changed": None, "guard": [],
              "selected": 0, "refused": None,
              "guards": [{"label": label, "suite": suite, "outcome": "fired", "detail": None,
                          **({"suite_class": key, "seconds": took, "baseline_seconds": base} if timed else {})}
                         for label, key, took, base in guards]}
    result.update(flags)
    if timed:
        result.update(seconds=seconds, phases=phases or {"setup": seconds or 0}, idle_tail=None)
    with open(os.path.join(path, name), "w", encoding="utf-8") as handle:
        json.dump(result, handle)


def case_derive_takes_each_timed_classs_medians_and_keeps_the_rest():
    failures = 0
    with tempfile.TemporaryDirectory() as root:
        _shard(root, 1, 2, [("a", "functional/OldTest", 40, 100), ("b", "functional/OldTest", 50, None),
                            ("c", None, 5, None)])
        _shard(root, 2, 2, [("d", "functional/OldTest", 60, 120), ("e", "functional/NewTest", 7, 9)])
        _shard(root, 3, 3, [("f", "functional/OldTest", 1, 1)])
        with open(os.path.join(root, "guards-every-3-of-3", "full.json")) as handle:
            refused = json.load(handle)
        refused["guards"][0]["outcome"] = "not proved"
        with open(os.path.join(root, "guards-every-3-of-3", "full.json"), "w") as handle:
            json.dump(refused, handle)
        table = gc.derive(TABLE, gc.shard_results(root, ["full.json"]), {"run": 2})
    if table["classes"]["functional/OldTest"] != {"baseline": 110, "guard": 50}:
        failures += fail(f"OldTest's medians: {table['classes']['functional/OldTest']}")
    if table["classes"]["functional/NewTest"] != {"baseline": 9, "guard": 7}:
        failures += fail(f"a class new to the table: {table['classes'].get('functional/NewTest')}")
    if table["classes"]["maven/UntimedTest"] != TABLE["classes"]["maven/UntimedTest"] \
            or table["classes"]["maven/KnownTest"] != TABLE["classes"]["maven/KnownTest"]:
        failures += fail("a class the run did not time changed its figures")
    if table["estimated"] != ["maven/UntimedTest"]:
        failures += fail(f"estimated after the run: {table['estimated']}")
    if table["measured_by"] != [{"run": 2, "modes": ["full"]}]:
        failures += fail(f"measured_by: {table['measured_by']}")
    return failures


def case_derive_refuses_results_without_timings_and_writes_nothing():
    failures = 0
    with tempfile.TemporaryDirectory() as root:
        shards = os.path.join(root, "shards")
        _shard(shards, 1, 1, [("a", None, None, None)], timed=False)
        out = os.path.join(root, "table.json")
        err = io.StringIO()
        with contextlib.redirect_stderr(err), contextlib.redirect_stdout(io.StringIO()):
            code = gc.main(["derive", shards, "--out", out])
        if code != 1 or "full.json" not in err.getvalue() or "no timings" not in err.getvalue():
            failures += fail(f"results without timings: {code} {err.getvalue()!r}")
        if os.path.exists(out):
            failures += fail("a refused derive wrote a table")
    return failures


def case_derive_writes_a_table_the_partition_reads():
    failures = 0
    with tempfile.TemporaryDirectory() as root:
        shards = os.path.join(root, "shards")
        _shard(shards, 1, 1, [("a", "maven/SelectionFunctionalTest", 600, 700)], seconds=1300)
        out = os.path.join(root, "costs", "guard_costs.json")
        with contextlib.redirect_stdout(io.StringIO()):
            code = gc.main(["derive", shards, "--out", out, "--run", "42", "--commit", "0123456789"])
        with open(out, encoding="utf-8") as handle:
            table = json.load(handle)
        if code != 0 or table["classes"]["maven/SelectionFunctionalTest"] != {"baseline": 700, "guard": 600}:
            failures += fail(f"derive wrote {code} {table['classes'].get('maven/SelectionFunctionalTest')}")
        if table["measured_by"][0]["run"] != 42 or table["measured_by"][0]["commit"] != "01234567":
            failures += fail(f"measured_by: {table['measured_by']}")
        if "maven/SelectionFunctionalTest" in table["estimated"]:
            failures += fail("a measured class is still estimated")
        if set(table["classes"]) != set(pg.class_costs()):
            failures += fail("derive dropped or invented a class")
    return failures


def case_derive_reads_every_file_and_weighs_each_other_suite_by_mode():
    """A pull request's shard writes a fast step and a changed one; both feed the medians, a suite's
    guards are weighed per mode and never as a class, and only the first step's warm-up counts."""
    failures = 0
    table = {**TABLE, "measured_by": [{"run": 1, "commit": "a", "modes": ["fast", "full"]}],
             "warm_up": {"full": 999}}
    with tempfile.TemporaryDirectory() as root:
        for index, (fast, changed) in enumerate(((60, 20), (80, 40)), start=1):
            _shard(root, index, 2, [("u", None, fast, fast + 10)], seconds=fast, name="fast.json",
                   mode="fast", suite="unit", phases={"warm_up": 100 * index, "parallel": fast})
            _shard(root, index, 2, [("u", None, changed, None)], seconds=changed, name="changed.json",
                   suite="unit", phases={"warm_up": 7, "parallel": changed})
            path = os.path.join(root, f"guards-every-{index}-of-2", "changed.json")
            with open(path) as handle:
                result = json.load(handle)
            result["guards"].append({"label": "m", "suite": "maven", "outcome": "fired", "detail": None,
                                     "suite_class": "maven/KnownTest", "seconds": 300 * index,
                                     "baseline_seconds": 400})
            with open(path, "w") as handle:
                json.dump(result, handle)
        results = gc.shard_results(root, ["fast.json", "changed.json"])
        derived = gc.derive(table, results, {"run": 2}, "fast.json")
    if derived["classes"]["maven/KnownTest"] != {"baseline": 400, "guard": 450}:
        failures += fail(f"a class timed in the second file: {derived['classes']['maven/KnownTest']}")
    if derived["suites"] != {"fast/unit": {"baseline": 80, "guard": 70}, "full/unit": {"baseline": 30, "guard": 30}}:
        failures += fail(f"the suites by mode: {derived['suites']}")
    if any(key.startswith("unit") or "/unit" in key for key in derived["classes"]):
        failures += fail("a suite's guards were taken for a class")
    if derived["warm_up"] != {"fast": 150, "full": 999}:
        failures += fail(f"warm-up: only each shard's first step, the other mode kept: {derived['warm_up']}")
    if derived["measured_by"] != [{"run": 2, "modes": ["fast", "full"]}]:
        failures += fail(f"a run of both modes replaces the one before: {derived['measured_by']}")
    # An every-guard run after it: its own run names full mode, the pull request's still fast.
    with tempfile.TemporaryDirectory() as root:
        _shard(root, 1, 1, [("u", None, 25, 26)], seconds=25, suite="unit", phases={"warm_up": 140})
        after = gc.derive(derived, gc.shard_results(root, ["full.json"]), {"run": 3})
    if after["measured_by"] != [{"run": 3, "modes": ["full"]}, {"run": 2, "modes": ["fast"]}]:
        failures += fail(f"an every-guard run after a pull request's: {after['measured_by']}")
    if after["suites"]["fast/unit"] != derived["suites"]["fast/unit"] or after["warm_up"] != {"fast": 150, "full": 140}:
        failures += fail(f"the fast mode's figures did not survive a full run: {after['suites']} {after['warm_up']}")
    return failures


def _held(root, shards, name="full.json", mode="full", extra=None, count=None, **flags):
    """Each shard of `shards` as a results file holding its guards, measured at its job projection
    as the checked-in table gives it, plus `extra[index]` seconds."""
    for index, shard in enumerate(shards, start=1):
        step = pg.step_projection(shard, mode, first=name != "changed.json")
        seconds = step["total"] + (extra or {}).get(index, 0)
        _shard(root, index, count or len(shards), [(m[0], None, 1, None) for m in shard], seconds=seconds,
               phases={"parallel": seconds}, name=name, mode=mode, **flags)


def case_report_projects_each_shard_from_the_guards_it_held_against_the_jobs_limit():
    failures = 0
    shards, _loads = pg.partition(pg.MUTATIONS, 4)
    with tempfile.TemporaryDirectory() as root:
        _held(root, shards, extra={3: 1200})
        lines = gc.report(gc.shard_results(root, ["full.json"]))
    text = "\n".join(lines)
    if len(lines) != 7 or not lines[2].startswith("shard 3/4: projected"):
        failures += fail(f"report lines: {lines}")
    if "furthest over its projection: shard 3, +20.0 min" not in text:
        failures += fail(f"the shard 20 min over its projection is not named: {text}")
    if "spread: " not in text or "shard 3 slowest" not in text:
        failures += fail(f"no spread naming the slowest shard: {text}")
    if f"full-shard's limit {pg.job_limit('full-shard')} min" not in lines[-1] \
            or not re.search(r"measured [\d.]+ min \(shard 3\)", lines[-1]):
        failures += fail(f"the worst shard's line: {lines[-1]}")
    if "PARTIAL" in text:
        failures += fail(f"a complete run was reported partial: {text}")
    # The shards held another split than the table's: each is weighed by what it held.
    swapped = [shards[1], shards[0], *shards[2:]]
    with tempfile.TemporaryDirectory() as root:
        _held(root, swapped)
        lines = gc.report(gc.shard_results(root, ["full.json"]))
    for index, shard in enumerate(swapped, start=1):
        expected = pg.job_projection([pg.step_projection(shard, "full")]) / 60
        if not lines[index - 1].startswith(f"shard {index}/4: projected {expected:.1f} min"):
            failures += fail(f"shard {index} was not weighed by the guards it held: {lines[index - 1]}")
    return failures


def case_report_takes_a_pull_requests_two_steps_as_one_job():
    failures = 0
    fast = [m for m in pg.MUTATIONS if pg.SUITE_NAMES[m[4]] in pg.FAST_SUITES]
    fast_split, _loads = pg.partition(fast, 2)
    every, _loads = pg.partition(pg.MUTATIONS, 2)
    with tempfile.TemporaryDirectory() as root:
        _held(root, fast_split, name="fast.json", mode="fast")
        _held(root, every, name="changed.json", changed="base")
        results = gc.shard_results(root, ["fast.json", "changed.json"])
        lines = gc.report(results)
        other = gc.report(results, "full-shard")
    for index in (1, 2):
        steps = [pg.step_projection(fast_split[index - 1], "fast"),
                 pg.step_projection(every[index - 1], "full", first=False)]
        expected = pg.job_projection(steps) / 60
        if not lines[index - 1].startswith(f"shard {index}/2: projected {expected:.1f} min, measured {expected:.1f} min"):
            failures += fail(f"shard {index}'s job is not its two steps and its setup: {lines[index - 1]}")
    if f"fast-shard's limit {pg.job_limit('fast-shard')} min" not in lines[-1]:
        failures += fail(f"a pull request's shards are not held to fast-shard's limit: {lines[-1]}")
    if "full-shard's limit" not in other[-1]:
        failures += fail(f"--job full-shard did not name its limit: {other[-1]}")
    return failures


def case_a_shard_that_left_nothing_keeps_the_others_projections():
    failures = 0
    shards, _loads = pg.partition(pg.MUTATIONS, 4)
    with tempfile.TemporaryDirectory() as root:
        _held(root, shards)
        whole = gc.report(gc.shard_results(root, ["full.json"]))
    for flags, partial in (({"selected": len(pg.MUTATIONS)}, False),
                           ({"guard": ["a label"], "selected": len(pg.MUTATIONS)}, True)):
        with tempfile.TemporaryDirectory() as root:
            _held(root, shards[:3], count=4, **flags)
            missing = gc.report(gc.shard_results(root, ["full.json"]))
        if partial:
            if not missing[0].startswith("PARTIAL: 3 of 4 shards left full.json"):
                failures += fail(f"a --guard run missing a shard was not said to be partial: {missing[0]}")
            continue
        if [line.split(" min, measured")[0] for line in missing[:4]] != [line.split(" min, measured")[0] for line in whole[:4]] \
                or "measured nothing" not in missing[3]:
            failures += fail(f"a missing shard moved the others' projections: {missing[:4]} against {whole[:4]}")
        if "shard 4 measured nothing (possibly cut at the limit)" not in missing[-1]:
            failures += fail(f"the worst shard's line does not name the shard that measured nothing: {missing[-1]}")
    return failures


def case_the_checked_in_table_is_written_as_derive_writes_it():
    """A hand edit that reformats the table would turn the next refresh into a rewrite."""
    with open(pg.COSTS_FILE, encoding="utf-8") as handle:
        text = handle.read()
    if gc.table_text(json.loads(text)) != text:
        return fail("scripts/guard_costs.json is not in the form derive writes")
    return 0


CASES = (case_derive_takes_each_timed_classs_medians_and_keeps_the_rest,
         case_derive_refuses_results_without_timings_and_writes_nothing,
         case_derive_writes_a_table_the_partition_reads,
         case_derive_reads_every_file_and_weighs_each_other_suite_by_mode,
         case_report_projects_each_shard_from_the_guards_it_held_against_the_jobs_limit,
         case_report_takes_a_pull_requests_two_steps_as_one_job,
         case_a_shard_that_left_nothing_keeps_the_others_projections,
         case_the_checked_in_table_is_written_as_derive_writes_it)


def test_guard_costs():
    assert sum(case() for case in CASES) == 0


def main():
    failed = 0
    for case in CASES:
        print(case.__name__)
        failed += case()
    print(f"\n{'all passed' if not failed else 'FAILURES'}: {failed} failed checks")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
