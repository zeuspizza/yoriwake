"""
Tests for the planted-miss controls: a control passes only when every project reported a
miss, and planting never writes the checkout it was cloned from.

Run with `python bench/test_controls.py`, or under pytest. Cases RETURN a count of failed
checks, as in test_run.py; the single test_* entry point asserts.
"""

import os
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import controls  # noqa: E402


def fail(message):
    print(f"  FAIL: {message}")
    return 1


def _control(*missed_per_row):
    return {"control": True,
            "mutations": [{"control": True, "status": "checked", "missed": list(m)}
                          for m in missed_per_row]}


def case_every_control_project_must_report_a_miss():
    failures = 0
    passing = {name: _control([], ["p.T::t"]) for name in controls.CONTROL_PROJECTS}
    if controls.control_failures(passing):
        failures += fail(f"controls that each reported a miss failed: "
                         f"{controls.control_failures(passing)}")
    silent = {**passing, "demo-kotlin": _control([], [])}
    said = controls.control_failures(silent)
    if len(said) != 1 or not said[0].startswith("demo-kotlin:"):
        failures += fail(f"a control project at zero misses was not named: {said}")
    absent = {**passing, "order-dependent": None}
    if not any(line.startswith("order-dependent:") for line in controls.control_failures(absent)):
        failures += fail("a control project with no results file passed")
    measured = {**passing, "demo": {"mutations": [{"status": "checked", "missed": ["p.T::t"]}]}}
    if not any(line.startswith("demo:") for line in controls.control_failures(measured)):
        failures += fail("a measurement's results file was read as a control's")
    return failures


def case_in_complement_mode_the_shared_jvm_miss_needs_its_rule():
    """Order-dependent's miss must come from a check row a shared-JVM rule routed."""
    failures = 0

    def miss(truth, rules):
        return {"control": True, "mutations": [{"control": True, "status": "checked",
                                                "missed": ["p.T::t"], "ground_truth": truth,
                                                "ground_truth_rules": rules}]}
    base = {name: _control(["p.T::t"]) for name in controls.CONTROL_PROJECTS}
    for name in controls.COMPLEMENT_CONTROLS:
        base[name]["mutations"][0]["ground_truth"] = "complement"
    routed = {**base, "order-dependent": miss("complement+full", ["first-rows", "shares-jvm"])}
    if controls.control_failures(routed, complement_mode=True):
        failures += fail("a shared-JVM miss a rule routed to a full arm failed the control")
    for truth, rules in (("complement", []), ("complement+full", ["first-rows"])):
        said = controls.control_failures({**base, "order-dependent": miss(truth, rules)},
                                         complement_mode=True)
        if not any(line.startswith("order-dependent:") for line in said):
            failures += fail(f"a shared-JVM miss on a {truth} row ({rules}) passed")
    if controls.control_failures({**base, "order-dependent": miss("complement", [])}):
        failures += fail("full-arm mode asked a shared-JVM rule of the miss")
    full_backed = {**routed, "demo": miss("complement+full", ["first-rows"])}
    if not any(line.startswith("demo:") for line in
               controls.control_failures(full_backed, complement_mode=True)):
        failures += fail("a complement-mode demo control whose every miss a full arm stood behind "
                         "passed")
    disagreeing = {**routed, "demo-kotlin": {**routed["demo-kotlin"],
                                             "complement_switched_off": {"at": "x"}}}
    if not any(line.startswith("demo-kotlin:") for line in
               controls.control_failures(disagreeing, complement_mode=True)):
        failures += fail("a control whose complement disagreed with a full arm passed")
    return failures


def case_planting_writes_only_the_clone():
    failures = 0
    with tempfile.TemporaryDirectory() as root:
        real = os.path.join(root, "real")
        os.makedirs(os.path.join(real, "a"))
        with open(os.path.join(real, "a", "S.java"), "w", encoding="utf-8") as handle:
            handle.write("class S { boolean f() { return x; } }\n")
        for args in (["init", "-q"], ["add", "-A"],
                     ["-c", "user.email=t@t", "-c", "user.name=t", "-c", "commit.gpgsign=false",
                      "commit", "-qm", "base"]):
            subprocess.run(["git", "-C", real, *args], check=True, capture_output=True)
        head = subprocess.run(["git", "-C", real, "rev-parse", "HEAD"], capture_output=True,
                              text=True).stdout
        clone = controls.plant(real, os.path.join(root, "clone"),
                               ("a/S.java", "return x;", "return true;"))
        with open(os.path.join(clone, "a", "S.java"), encoding="utf-8") as handle:
            if "return true;" not in handle.read():
                failures += fail("the clone does not hold the planted change")
        if subprocess.run(["git", "-C", clone, "status", "--porcelain"], capture_output=True,
                          text=True).stdout.strip():
            failures += fail("the plant was left uncommitted, so the harness refuses the clone")
        with open(os.path.join(real, "a", "S.java"), encoding="utf-8") as handle:
            if "return x;" not in handle.read():
                failures += fail("planting wrote the real checkout")
        if subprocess.run(["git", "-C", real, "rev-parse", "HEAD"], capture_output=True,
                          text=True).stdout != head or subprocess.run(
                ["git", "-C", real, "status", "--porcelain"], capture_output=True,
                text=True).stdout.strip():
            failures += fail("planting moved or dirtied the real checkout")
        try:
            controls.plant(real, os.path.join(root, "clone"), ("a/S.java", "absent", "x"))
            failures += fail("an anchor that matches nothing planted nothing and was accepted")
        except SystemExit:
            pass
    return failures


def case_the_planted_miss_matches_exactly_once():
    """An anchor that moved plants nothing, and the controls would pass against the real plugin."""
    path, old, new = controls.PLANTED_MISS
    with open(os.path.join(controls.yoriwake_checkout.ROOT, path), encoding="utf-8") as handle:
        count = handle.read().count(old)
    if count != 1 or old == new:
        return fail(f"the planted miss's anchor matches {count} times in {path}")
    return 0


CASES = (case_every_control_project_must_report_a_miss,
         case_in_complement_mode_the_shared_jvm_miss_needs_its_rule,
         case_planting_writes_only_the_clone,
         case_the_planted_miss_matches_exactly_once)


def test_controls():
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
