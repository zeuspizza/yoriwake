"""
Asserts what the selecting CI run actually did.

Two claims, and both matter. The change must not skip a test it breaks -- that is recall, and
getting it wrong makes the tool dangerous. And it must skip the tests it cannot reach -- that is the
saving, and getting it wrong makes the tool pointless while still looking green.

Checking only the second is how a selector that silently runs nothing passes its own CI.
"""

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


def decisions():
    """{test id: (verdict, reason)}, or {} when no run has written one.

    Absence is not treated as failure by the caller: this file arrived after the recall and
    selectivity checks below, and those must keep working on a run that predates it.
    """
    found = {}
    for path in glob.glob(DECISIONS):
        with open(path, encoding="utf-8", errors="replace") as handle:
            for line in handle:
                if not line.strip() or line.startswith("#"):
                    continue
                parts = line.rstrip("\n").split("\t")
                if len(parts) == 3:
                    found[parts[0]] = (parts[1], parts[2])
    return found


def main():
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

    # Recall. Alpha.twice is now wrong, so both tests that assert on it must have run and failed.
    for test in ("dev.demo.AlphaTest.doubles", "dev.demo.AlphaTest.doublesAgain"):
        if test not in ran:
            problems.append(f"{test} was skipped, but the change breaks it")
        elif test not in failed:
            problems.append(f"{test} ran but did not fail, so the mutation did not take")

    # Selectivity. Beta and Gamma cannot be reached from a change to Alpha.
    for skipped in ("dev.demo.BetaTest", "dev.demo.GammaTest"):
        leaked = sorted(t for t in ran if t.startswith(skipped + "."))
        if leaked:
            problems.append(f"{skipped} should have been skipped entirely, but ran: {leaked}")

    # A run that discovered nothing at all would satisfy every "did not run" check above.
    if not ran:
        problems.append("no tests ran at all; the map or the build is broken, not the change set")

    # Why, not just what: a selector that was never consulted also passes the checks above.
    made = decisions()
    if made:
        excluded = {t for t, (verdict, _) in made.items() if verdict == "excluded"}
        if not excluded:
            problems.append(
                "the decision record shows nothing was excluded, so this run did not select at all"
            )
        for test, (verdict, reason) in sorted(made.items()):
            if "BetaTest" in test or "GammaTest" in test:
                if verdict != "excluded":
                    problems.append(f"{test} was {verdict} ({reason}); it cannot reach the change")
                elif reason != "SKIPPED":
                    problems.append(f"{test} was excluded for {reason}, not SKIPPED")
            if "AlphaTest" in test and verdict != "included":
                problems.append(f"{test} was {verdict} ({reason}); the change breaks it")
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
