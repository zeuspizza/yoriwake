"""recording_cost.py reads each arm's recording time and ranges it against the shared arm's.

    python3 bench/test_recording_cost.py
"""

import json
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import recording_cost  # noqa: E402


def fail(message):
    print(f"  FAIL: {message}")
    return 1


def _write(directory, name, results):
    with open(os.path.join(directory, name), "w", encoding="utf-8") as handle:
        json.dump(results, handle)


def case_each_arm_is_ranged_against_the_shared_arm():
    problems = 0
    with tempfile.TemporaryDirectory() as directory:
        _write(directory, "shared-1-demo.json", {"capture_seconds": 100})
        _write(directory, "shared-2-demo.json", {"capture_arm": {"tasks": {"test_task_seconds": 110}}})
        _write(directory, "isolated-4-1-demo.isolated.json", {"capture_seconds": 220, "capture_max_parallel_forks": 4})
        _write(directory, "isolated-4-2-demo.isolated.json", {"capture_seconds": 330, "capture_max_parallel_forks": 4})
        _write(directory, "notes.json", {"capture_seconds": 1})
        arms = recording_cost.collect(directory)
        if sorted(took for took, _ in arms.get("shared", [])) != [100, 110]:
            problems += fail(f"the shared arm read {arms.get('shared')}, not 100 and 110 seconds")
        rows = recording_cost.table(arms).splitlines()
        if not rows[2].startswith("| shared | 2 | 100-110 |"):
            problems += fail(f"the shared row is {rows[2]!r}")
        if rows[3] != "| isolated-4 | 2 | 220-330 | 4 | 2.0x-3.3x |":
            problems += fail(f"the isolated row is {rows[3]!r}")
    return problems


def case_a_file_without_a_recording_time_is_left_out():
    with tempfile.TemporaryDirectory() as directory:
        _write(directory, "shared-1-demo.json", {"status": "stalled"})
        found = recording_cost.collect(directory)
    return fail(f"a file with no recording time was read: {found}") if found else 0


CASES = (case_each_arm_is_ranged_against_the_shared_arm, case_a_file_without_a_recording_time_is_left_out)


def main():
    failed = 0
    for case in CASES:
        print(case.__name__)
        failed += case()
    print(f"\nFAILURES: {failed} failed checks" if failed else "\nall passed: 0 failed checks")
    return failed


def test_recording_cost():
    assert main() == 0


if __name__ == "__main__":
    sys.exit(1 if main() else 0)
