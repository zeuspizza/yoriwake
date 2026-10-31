"""
Tests for `guard_costs.py`: the cost table a run's shard results derive, and the report of how
close the shards came to their projection.

Run: `python scripts/test_guard_costs.py`
"""

import contextlib
import io
import json
import os
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


def _shard(root, index, count, guards, seconds=None, phases=None, timed=True):
    """One shard's `--results` file: `guards` are (label, suite_class, seconds, baseline_seconds)."""
    path = os.path.join(root, f"guards-every-{index}-of-{count}")
    os.makedirs(path, exist_ok=True)
    result = {"shard": f"{index}/{count}", "mode": "full", "changed": None, "guard": [],
              "selected": 0, "refused": None,
              "guards": [{"label": label, "suite": "functional", "outcome": "fired", "detail": None,
                          **({"suite_class": key, "seconds": took, "baseline_seconds": base} if timed else {})}
                         for label, key, took, base in guards]}
    if timed:
        result.update(seconds=seconds, phases=phases or {"setup": seconds or 0}, idle_tail=None)
    with open(os.path.join(path, "full.json"), "w", encoding="utf-8") as handle:
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
        table = gc.derive(TABLE, gc.shard_results(root, "full.json"), {"run": 2})
    if table["classes"]["functional/OldTest"] != {"baseline": 110, "guard": 50}:
        failures += fail(f"OldTest's medians: {table['classes']['functional/OldTest']}")
    if table["classes"]["functional/NewTest"] != {"baseline": 9, "guard": 7}:
        failures += fail(f"a class new to the table: {table['classes'].get('functional/NewTest')}")
    if table["classes"]["maven/UntimedTest"] != TABLE["classes"]["maven/UntimedTest"] \
            or table["classes"]["maven/KnownTest"] != TABLE["classes"]["maven/KnownTest"]:
        failures += fail("a class the run did not time changed its figures")
    if table["estimated"] != ["maven/UntimedTest"]:
        failures += fail(f"estimated after the run: {table['estimated']}")
    if table["measured_by"] != [{"run": 2}]:
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


def case_report_names_the_shard_over_its_projection_and_the_spread():
    failures = 0
    shards, loads = pg.partition(pg.MUTATIONS, 4)
    with tempfile.TemporaryDirectory() as root:
        for index, shard in enumerate(shards, start=1):
            seconds = loads[index - 1]["wall"] + (1200 if index == 3 else 0)
            _shard(root, index, 4, [(m[0], None, 1, None) for m in shard], seconds=seconds,
                   phases={"setup": 60, "parallel": seconds - 60})
        lines = gc.report(gc.shard_results(root, "full.json"))
    text = "\n".join(lines)
    if len(lines) != 6 or not lines[2].startswith("shard 3/4: projected"):
        failures += fail(f"report lines: {lines}")
    if "furthest over its projection: shard 3, +20.0 min" not in text:
        failures += fail(f"the shard 20 min over its projection is not named: {text}")
    if "spread: " not in text or "shard 3 slowest" not in text:
        failures += fail(f"no spread naming the slowest shard: {text}")
    if "PARTIAL" in text:
        failures += fail(f"a complete run was reported partial: {text}")
    # One shard left no results: the others keep the projections the run used, and it measured nothing.
    for flags, partial in (({}, False), ({"guard": ["a label"], "selected": len(pg.MUTATIONS)}, True)):
        with tempfile.TemporaryDirectory() as root:
            for index, shard in enumerate(shards[:3], start=1):
                _shard(root, index, 4, [(m[0], None, 1, None) for m in shard], seconds=60)
                path = os.path.join(root, f"guards-every-{index}-of-4", "full.json")
                with open(path) as handle:
                    result = json.load(handle)
                result.update(flags)
                with open(path, "w") as handle:
                    json.dump(result, handle)
            missing = gc.report(gc.shard_results(root, "full.json"))
        if partial:
            if not missing[0].startswith("PARTIAL: 3 of 4 shards"):
                failures += fail(f"a --guard run missing a shard was not said to be partial: {missing[0]}")
            continue
        if [line.split(", measured")[0] for line in missing[:4]] != [line.split(", measured")[0] for line in lines[:4]] \
                or "measured nothing" not in missing[3]:
            failures += fail(f"a missing shard moved the others' projections: {missing[:4]} against {lines[:4]}")
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
         case_report_names_the_shard_over_its_projection_and_the_spread,
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
