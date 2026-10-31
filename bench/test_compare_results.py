"""Tests for `compare_results.py`: two sweeps of the same sample agree row for row on recall, or
the comparison says where they do not.

Cases return a count of failed checks; one `test_*` entry point asserts.

    python3 bench/test_compare_results.py
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import compare_results  # noqa: E402


def fail(message):
    print(f"  FAIL: {message}")
    return 1


def _row(index, status="checked", missed=(), **extra):
    return {"target": f"src/A{index}.java::body::{index}", "kind": "body", "index": index,
            "status": status, "missed": list(missed), **extra}


def _file(*rows):
    return {"project": "demo", "mutations": list(rows)}


def case_identical_sweeps_agree():
    report = compare_results.compare(_file(_row(0), _row(1, missed=["T::a"])),
                                     _file(_row(1, missed=["T::a"]), _row(0)))
    return fail(f"identical sweeps: {report}") if not report["agree"] else 0


def case_a_differing_missed_set_is_a_disagreement():
    report = compare_results.compare(_file(_row(0, missed=["T::a"])), _file(_row(0)))
    failures = 0
    if report["agree"]:
        failures += fail("a test missed in one sweep and not the other was an agreement")
    if not any("T::a" in line for line in report["differences"]):
        failures += fail(f"the difference does not name the test: {report['differences']}")
    return failures


def case_a_row_in_one_sweep_only_is_a_disagreement():
    report = compare_results.compare(_file(_row(0), _row(1)), _file(_row(0)))
    failures = 0
    if report["agree"]:
        failures += fail("a row missing from one sweep was an agreement")
    if not any("src/A1.java::body::1" in line for line in report["differences"]):
        failures += fail(f"the missing row is not named: {report['differences']}")
    return failures


def case_a_differing_status_is_a_disagreement():
    report = compare_results.compare(_file(_row(0)), _file(_row(0, status="no-effect")))
    return fail("checked against no-effect was an agreement") if report["agree"] else 0


def case_a_difference_confined_to_flagged_rows_is_reported_not_counted():
    flagged = _row(0, missed=["T::a"], nondeterministic=["T::a"])
    report = compare_results.compare(_file(flagged, _row(1)), _file(_row(0), _row(1)))
    failures = 0
    if not report["agree"]:
        failures += fail(f"a difference on a nondeterministic row counted: {report['differences']}")
    if report["flagged"] != ["src/A0.java::body::0"]:
        failures += fail(f"the flagged row was not reported: {report['flagged']}")
    order = _row(0, status="checked", missed=["T::b"], order_dependent=["T::b"])
    if not compare_results.compare(_file(_row(0)), _file(order))["agree"]:
        failures += fail("a difference on an order-dependent row counted")
    return failures


def case_sweeps_drawn_from_two_shape_sets_are_not_compared():
    a = {**_file(_row(0)), "target_shapes": "default"}
    b = {**_file(_row(0)), "target_shapes": "recall-arm"}
    report = compare_results.compare(a, b)
    failures = 0
    if report["agree"] or not any("recall-arm" in line for line in report["differences"]):
        failures += fail(f"two shape sets were compared row for row: {report}")
    return failures


def case_sweeps_of_two_fixture_trees_are_not_compared():
    a = {**_file(_row(0)), "fixture_digest": "aaa"}
    b = {**_file(_row(0)), "fixture_digest": "bbb"}
    failures = 0
    report = compare_results.compare(a, b)
    if report["agree"] or not any("fixture trees" in line for line in report["differences"]):
        failures += fail(f"two fixture trees were compared row for row: {report}")
    if not compare_results.compare(_file(_row(0)), _file(_row(0)))["agree"]:
        failures += fail("two files with no fixture stamp, as a corpus project's are, disagreed")
    return failures


def case_sweeps_of_two_build_tools_are_not_compared():
    gradle = {**_file(_row(0)), "build_tool": "gradle"}
    maven = {**_file(_row(0)), "build_tool": "maven"}
    failures = 0
    report = compare_results.compare(gradle, maven)
    if report["agree"] or not any("maven" in line for line in report["differences"]):
        failures += fail(f"a Gradle and a Maven sweep were compared row for row: {report}")
    if not compare_results.compare(_file(_row(0)), gradle)["agree"]:
        failures += fail("a file with no build_tool did not compare as a Gradle one")
    return failures


def test_compare_results():
    failures = 0
    for name, case in sorted(globals().items()):
        if name.startswith("case_"):
            print(name)
            failures += case()
    assert failures == 0, f"{failures} check(s) failed"


if __name__ == "__main__":
    test_compare_results()
    print("ok")
