"""
Tests for the same-work guard in `scripts/measure-toll.sh`, which had none and needed them.

The guard decides whether a pair of timings is a measurement or a coincidence. Its whole job is to
refuse, and until now the only way to exercise it was a real 40-minute toll run on a project whose
suite happened to be flaky that afternoon.

THE FUNCTION UNDER TEST IS EXTRACTED FROM THE SHIPPING SCRIPT, not restated here. A test that
reimplements a shell function and then asserts against its own copy passes whatever the script does
-- which is precisely how `test_qualify.py`'s JSON case went on passing after the payload format
changed underneath it. `outcome()` is cut out of the real file by name and evaluated, so a change to
the script either keeps these green or breaks them.

Cases are named case_* and RETURN a count of failed checks. The single test_* entry point asserts.
"""

import os
import re
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import checkout  # noqa: E402
# The script lives in the yoriwake checkout; its guard is the harness's to prove.
SCRIPT = os.path.join(checkout.ROOT, "scripts", "measure-toll.sh")

# Gradle prints this only for a task that FAILED. A green task prints nothing, which is the whole
# reason the guard cannot see a green pair.
SUMMARY = "664 tests completed, 4 failed, 2 skipped"


def _outcome_function():
    """The real `outcome()` from the script, so these tests cannot drift from it."""
    with open(SCRIPT, encoding="utf-8") as handle:
        text = handle.read()
    match = re.search(r"^outcome\(\) \{.*?^\}", text, re.MULTILINE | re.DOTALL)
    if not match:
        raise AssertionError("could not find outcome() in scripts/measure-toll.sh")
    return match.group(0)


def _signature(log_text):
    """What the shipping `outcome()` makes of one arm's log."""
    scratch = tempfile.mkdtemp(prefix="yoriwake-toll-tests-")
    try:
        with open(os.path.join(scratch, "arm.log"), "w", encoding="utf-8") as handle:
            handle.write(log_text)
        script = f'log_dir="{scratch}"\n{_outcome_function()}\noutcome arm\n'
        done = subprocess.run(["bash", "-c", script], capture_output=True, text=True)
        return done.stdout
    finally:
        shutil.rmtree(scratch, ignore_errors=True)


def case_arms_that_failed_alike_compare_equal():
    """The pair that IS a measurement. Whitespace and task order aside, these are the same work."""
    failures = 0
    left = _signature(f"> Task :core:test\n{SUMMARY}\nio.k.WorkerTest > killed FAILED\n")
    right = _signature(f"> Task :core:test\n{SUMMARY}\nio.k.WorkerTest > killed FAILED\n")
    if left != right or not left.strip():
        print(f"  FAIL: identical arms did not compare equal: {left!r} vs {right!r}")
        failures += 1
    return failures


def case_a_different_failure_count_is_visible():
    """kestra's flake: one extra failing test, worth about a minute of wall clock."""
    failures = 0
    four = _signature(f"{SUMMARY}\nio.k.ProcessTest > inputAndOutputFiles FAILED\n")
    five = _signature("664 tests completed, 5 failed, 2 skipped\n"
                      "io.k.ProcessTest > inputAndOutputFiles FAILED\n"
                      "io.k.WorkerTest > killed FAILED\n")
    if four == five:
        print(f"  FAIL: arms with different failure counts compared equal: {four!r}")
        failures += 1
    return failures


def case_the_same_count_of_different_tests_is_visible():
    """The count alone is not the workload. Two arms can fail the same NUMBER of different tests."""
    failures = 0
    left = _signature(f"{SUMMARY}\nio.k.AaaTest > one FAILED\n")
    right = _signature(f"{SUMMARY}\nio.k.ZzzTest > two FAILED\n")
    if left == right:
        print(f"  FAIL: different failing tests compared equal: {left!r}")
        failures += 1
    return failures


def case_task_order_does_not_decide_the_verdict():
    """On a multi-task build Gradle need not schedule the tasks in the same order twice; file order
    alone would refuse two identical workloads."""
    failures = 0
    left = _signature("io.k.AaaTest > one FAILED\nio.k.ZzzTest > two FAILED\n")
    right = _signature("io.k.ZzzTest > two FAILED\nio.k.AaaTest > one FAILED\n")
    if left != right:
        print(f"  FAIL: the same failures in a different order compared unequal: "
              f"{left!r} vs {right!r}")
        failures += 1
    return failures


def case_ansi_colour_does_not_decide_the_verdict():
    """Gradle's console output is colour-coded; the codes are not part of the workload."""
    failures = 0
    plain = _signature(f"{SUMMARY}\nio.k.WorkerTest > killed FAILED\n")
    coloured = _signature(f"\x1b[31m{SUMMARY}\x1b[0m\n\x1b[1mio.k.WorkerTest > killed FAILED\x1b[0m\n")
    if plain != coloured:
        print(f"  FAIL: colour codes changed the signature: {plain!r} vs {coloured!r}")
        failures += 1
    return failures


def case_a_green_arm_yields_no_signature():
    """The guard's blind spot, asserted so it stays known rather than discovered.

    Gradle prints no summary for a task that passed -- checked against bench/logs/moshi. Two
    green arms therefore produce two empty signatures and compare equal, so the guard passes without
    having compared anything. The script says so on stdout rather than reporting a check it did not
    perform; this pins the underlying fact that makes that necessary.
    """
    failures = 0
    green = _signature("> Task :moshi:test\nBUILD SUCCESSFUL in 1m 7s\n")
    if green.strip():
        print(f"  FAIL: a green log produced a signature after all: {green!r}")
        failures += 1
    return failures


CASES = (case_arms_that_failed_alike_compare_equal,
         case_a_different_failure_count_is_visible,
         case_the_same_count_of_different_tests_is_visible,
         case_task_order_does_not_decide_the_verdict,
         case_ansi_colour_does_not_decide_the_verdict,
         case_a_green_arm_yields_no_signature)


def main():
    failed = 0
    for case in CASES:
        print(case.__name__)
        failed += case()
    if failed:
        print(f"\nFAILURES: {failed} failed checks")
    else:
        print("\nall passed: 0 failed checks")
    return failed


def test_measure_toll():
    assert main() == 0


if __name__ == "__main__":
    sys.exit(1 if main() else 0)
