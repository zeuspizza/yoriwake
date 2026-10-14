"""
Asserts what the selecting CI run actually did.

Two claims, and both matter. The change must not skip a test it breaks -- that is recall, and
getting it wrong makes the tool dangerous. And it must skip the tests it cannot reach -- that is the
saving, and getting it wrong makes the tool pointless while still looking green.

Checking only the second is how a selector that silently runs nothing passes its own CI.
"""

import argparse
import glob
import os
import sys
import xml.etree.ElementTree as ET

RESULTS = "samples/demo/build/test-results/test/*.xml"

# The decisions the selector made, one row per test: id, verdict, reason.
#
# The XML says what ran; only this says why the rest did not. Written by the agent at JVM exit;
# see io.github.zeuspizza.yoriwake.agent.DecisionRecord.
DECISIONS = "samples/demo/.gradle/yoriwake/*/decisions.tsv"

# What each mutation in `.github/ci.py` breaks, why the run must include it, and what it must
# skip. Change these together with ci.py's mutations.
#
# Alpha is reached through coverage, so the run narrows: its tests run because the map links them
# to the change, and the rest are skipped. Epsilon no test executes; a resource names it as a
# string, which only the resource scan sees. That makes its absence from the map unprovable, so the
# run widens to everything, for that reason and on that change. Were the scan lost, the absence
# would read as proof, the run would narrow, and the registry test would be skipped.
EXPECTED = {
    "alpha": {
        "broken": ("dev.demo.AlphaTest.doubles", "dev.demo.AlphaTest.doublesAgain"),
        "reason": "REACHES_CHANGE",
        "unreachable": ("dev.demo.BetaTest", "dev.demo.GammaTest"),
        "notes": {"input.yoriwake.change.classes": "dev.demo.Alpha"},
    },
    "epsilon": {
        "broken": ("dev.demo.EpsilonRegistryTest.everyRegisteredTypeExposesTheMethodsTheRegistryPromises",),
        "reason": "FULL_RUN",
        "unreachable": (),
        "notes": {
            "input.yoriwake.change.classes": "dev.demo.Epsilon",
            "outcome": "full-run",
            "full-run-kind": "no-coverage-for-changed",
        },
    },
}


def decisions(notes=None):
    """{test id: (verdict, reason)}, or {} when no run has written one; the run's `#!` notes go
    into `notes` when given.

    Absence is not treated as failure by the caller: this file arrived after the recall and
    selectivity checks below, and those must keep working on a run that predates it.
    """
    found = {}
    for path in glob.glob(DECISIONS):
        with open(path, encoding="utf-8", errors="replace") as handle:
            for line in handle:
                if line.startswith("#!") and notes is not None:
                    key, _, value = line[2:].rstrip("\n").partition("\t")
                    notes[key] = value
                if not line.strip() or line.startswith("#"):
                    continue
                parts = line.rstrip("\n").split("\t")
                if len(parts) == 3:
                    found[parts[0]] = (parts[1], parts[2])
    return found


def main(argv=None):
    parser = argparse.ArgumentParser(description="assert what a selecting run of the demo did")
    parser.add_argument("--mutated", choices=sorted(EXPECTED), default="alpha",
                        help="which ci.py mutation the run selected against")
    expected = EXPECTED[parser.parse_args(argv).mutated]
    broken_classes = {test.rsplit(".", 1)[0] for test in expected["broken"]}

    ran, failed = set(), set()
    for path in glob.glob(RESULTS):
        for case in ET.parse(path).getroot().iter("testcase"):
            # Gradle reports a method as `doubles()`; compare on the bare name so the expected
            # values below read like the source does.
            method = (case.get("name") or "").removesuffix("()")
            name = f"{case.get('classname')}.{method}"
            ran.add(name)
            if case.find("failure") is not None or case.find("error") is not None:
                failed.add(name)

    problems = []

    # Recall. The mutated class is now wrong, so every test that asserts on it must have run and failed.
    for test in expected["broken"]:
        if test not in ran:
            problems.append(f"{test} was skipped, but the change breaks it")
        elif test not in failed:
            problems.append(f"{test} ran but did not fail, so the mutation did not take")

    # Selectivity. These cannot be reached from the mutated class.
    for skipped in expected["unreachable"]:
        leaked = sorted(t for t in ran if t.startswith(skipped + "."))
        if leaked:
            problems.append(f"{skipped} should have been skipped entirely, but ran: {leaked}")

    # A run that discovered nothing at all would satisfy every "did not run" check above.
    if not ran:
        problems.append("no tests ran at all; the map or the build is broken, not the change set")

    # Why, not just what: a selector that was never consulted also passes the checks above.
    notes = {}
    made = decisions(notes)
    if made:
        excluded = {t for t, (verdict, _) in made.items() if verdict == "excluded"}
        if expected["unreachable"] and not excluded:
            problems.append(
                "the decision record shows nothing was excluded, so this run did not select at all"
            )
        for test, (verdict, reason) in sorted(made.items()):
            if any(skipped in test for skipped in expected["unreachable"]):
                if verdict != "excluded":
                    problems.append(f"{test} was {verdict} ({reason}); it cannot reach the change")
                elif reason != "SKIPPED":
                    problems.append(f"{test} was excluded for {reason}, not SKIPPED")
            # Included for the reason the change gives, not another: a test the map does not know
            # (NOT_IN_MAP) also runs and fails, so a map that was never read passes the checks above.
            if any(broken in test for broken in broken_classes) and (verdict, reason) != ("included", expected["reason"]):
                problems.append(f"{test} was {verdict} ({reason}); expected included ({expected['reason']})")
        # The run's own account of what changed and what it did: a widening for another cause, or
        # on another change, would otherwise pass for the expected one.
        for key, value in expected["notes"].items():
            if notes.get(key) != value:
                problems.append(f"the decision record's {key} is {notes.get(key)!r}, expected {value!r}")
        # A reason of SELECTION_NOT_REQUESTED everywhere means the flag never reached the test JVM,
        # which looks exactly like a suite nothing narrowed.
        if all(reason == "SELECTION_NOT_REQUESTED" for _, reason in made.values()):
            problems.append(
                "every decision reads SELECTION_NOT_REQUESTED: -Pyoriwake.select did not reach the "
                "test JVM, so this run proves nothing about selection"
            )
        print(f"decisions {len(made)}: {sum(1 for v, _ in made.values() if v == 'excluded')} excluded")
    elif os.environ.get("YORIWAKE_REQUIRE_DECISIONS") == "1":
        problems.append(f"no decision record under {DECISIONS}; the agent wrote none")

    print(f"ran {len(ran)}: {sorted(ran)}")
    print(f"failed {len(failed)}: {sorted(failed)}")

    if problems:
        for problem in problems:
            print(f"FAIL: {problem}", file=sys.stderr)
        return 1

    print("OK: the broken tests ran, the unreachable ones did not")
    return 0


if __name__ == "__main__":
    sys.exit(main())
