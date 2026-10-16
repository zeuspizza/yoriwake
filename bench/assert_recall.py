"""Turns a sweep's results into a pass or a fail.

The sweep itself reports and exits zero, because a harness that stops at the first bad mutation
measures less than one that finishes. This is the part that has an opinion, so CI can gate on it.

Exits non-zero when:

  - any mutation missed a failure -- a test that failed in the full run was never selected. This is
    the failure this whole project exists to prevent, and there is no acceptable count of it above
    zero.
  - nothing was actually asked. A sweep where every mutation broke no test reports "0 missed" while
    having tested nothing, and a green tick for that is worse than no tick: it is the silent-skip
    shape, one level up.

Also reports, and never fails on, the narrowing rate: how often selection ran less than everything,
and by how much. That is the number that decides whether the tool is worth its capture cost, and
until now nothing computed it -- every recall sweep had the data in its results file and threw it
away. It is reported rather than gated because narrowing is economics, not safety: a sweep that
narrows nothing is disappointing, while a sweep that misses a failure is broken.

Reports but does not fail on:

  - tests that gave different answers to identical isolated re-runs. Neither missed nor clean: the
    tree was held still and the test contradicted itself, so nothing it did in either arm is
    evidence about selection. A sweep where EVERY answered row was one of these still fails, for
    the same reason a sweep that asked nothing does.
  - failures that ran but stopped failing. The test was selected and executed, so selection did its
    job; it passed because something it depends on did not run before it, or because it is flaky.
    Both are real problems, neither is a skip, and failing the build on flakiness would train people
    to ignore this job.
"""

import json
import os
import statistics
import sys

# The capture openers come from `run.py` rather than being spelled again here. This project has been
# bitten more than once by one contract living in two files, only one of which gets updated --
# `ModuleContractTest` exists for that reason -- and this particular contract is already the subject
# of the defect the function below documents.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import run  # noqa: E402



# Refusal kinds a user asks for (`RefusalKind.requested` in the plugin): a full run requested on the
# command line, by a branch the build lists in `fullRunBranches`, or in a commit message, and tests
# named with `--tests`.
REQUESTED_DECLINES = {"full-run-requested", "full-run-branch", "full-run-commit", "full-run-scheduled", "tests-named"}

def declined_to_narrow(finding):
    """Whether the SELECTOR refused to narrow on this row, whatever the two arms' counts say.

    Comparing counts alone reported kestra as "1 of 4 narrowed, ran 99.8% of the suite" for a forced
    full run whose two arms happened to execute one different test -- and that invented figure was
    published as evidence that kestra's coverage is too dense to discriminate. It is not: its median
    class selects 2.1% of the suite.

    **AND THE OPPOSITE DEFECT SHIPPED HERE FOR LONGER.** The test used to be "does this row carry ANY
    `full_run_reason`", on the reasoning that only a declining run records one. That stopped being
    true the moment `fa84407` made the field populate: `run.py`'s scrape was missing one of the
    plugin's three CAPTURE openers, so *"narrowing, so nothing is instrumented and the map is left
    alone"* landed in the field on rows that had narrowed. Over one corpus run, **136 of 167
    genuinely-narrowed rows, 81%, read as full runs**, and thirteen of seventeen projects reported
    that nothing narrowed.

    So the TOKEN is asked when the row has one, and where it does not the counts decide and the
    caller says so. Guessing from prose is what produced the 81%.
    """
    outcome = finding.get("decision_outcome")
    if outcome is not None:
        # `not-decided` means discovery never asked, which is not a refusal and must not read as one.
        return outcome == "full-run"
    # No token: this row predates the selector recording its outcome. Fall back to the prose, but
    # only after the lines that are not refusals have been taken out of it.
    return run.is_force_reason(finding.get("full_run_reason"))


def narrowed_at_all(finding):
    """Whether selection ran less than everything on this row.

    MODULE-LEVEL, AND THAT IS THE POINT. `corpus_state.py` needs exactly this question to decide
    whether a row is one where a miss was POSSIBLE, and a second copy of it there would be one
    contract living in two files with only one of them updated -- which is the shape of most of the
    defects this harness has found in itself. Returns False on a row carrying neither count.
    """
    if not isinstance(finding.get("selected_distinct"), int):
        return False
    if not isinstance(finding.get("full_distinct"), int) or not finding["full_distinct"]:
        return False
    return (not declined_to_narrow(finding)
            and finding["selected_distinct"] < finding["full_distinct"])


def established_nothing(finding):
    """Whether a checked row answered no recall question at all.

    Any `undetermined`, `nondeterministic` or `shared_key` test makes a row a "neither", as the
    first two always have. So does a row whose every induced failure was set aside -- a container
    that never started, or a test that fails with the change reverted -- because `full_failures`
    counts those too, and a row whose only failures were set aside broke nothing selection could
    have skipped. Module-level for the same reason as `narrowed_at_all`: `corpus_state.py` asks the
    same question.
    """
    if finding.get("undetermined") or finding.get("nondeterministic") or finding.get("shared_key"):
        return True
    return answered_failures(finding) == 0


def answered_failures(finding):
    """How many of a row's induced failures were neither set aside nor undecidable."""
    set_aside = set()
    for key in ("undetermined", "nondeterministic", "container_failed", "fails_unmutated",
                "shared_key"):
        set_aside.update(finding.get(key) or [])
    return max(0, (finding.get("full_failures") or 0) - len(set_aside))


def report_narrowing(findings):
    """Prints how often selection narrowed, and by how much.

    Every finding counts, not only the ones that broke something: whether selection narrows is a
    property of the change, not of whether the mutation happened to fail a test. A finding with no
    counts is skipped rather than assumed to have narrowed -- an absent number must never read as a
    saving, which is the same rule the recall gate applies to a missing "missed" key.
    """
    measured = [
        f for f in findings
        if isinstance(f.get("selected_distinct"), int)
        and isinstance(f.get("full_distinct"), int)
        and f["full_distinct"] > 0
    ]
    if not measured:
        print("  narrowing: no finding carried both counts, so nothing can be said")
        return {"measured": 0, "narrowed": 0, "reasons": {}}

    # A run that DECLINED to narrow is a full run whatever the counts say. Comparing counts alone
    # reported kestra as "1 of 4 narrowed, ran 99.8% of the suite" for a forced full run whose two
    # arms happened to execute one different test -- and that invented figure was then published as
    # evidence that kestra's coverage is too dense to discriminate. It is not: its median class
    # selects 2.1% of the suite. A metric that turns a full run into a saving is the same defect as a
    # selector that does.
    #
    # **AND THE OPPOSITE DEFECT SHIPPED HERE FOR LONGER.** The test above used to be "does this row
    # carry ANY `full_run_reason`", on the reasoning that only a declining run records one. That was
    # true while the field was null on every row, and stopped being true the moment `fa84407` made it
    # populate: `run.py`'s scrape was missing one of the plugin's three CAPTURE openers, so the
    # sentence *"narrowing, so nothing is instrumented and the map is left alone"* landed in the
    # field -- on rows that had narrowed. Every one of those was then classified as a full run.
    #
    # Measured over one corpus run: **136 of 167 genuinely-narrowed rows, 81%, read as full
    # runs**, and thirteen of seventeen projects reported that NOTHING narrowed. spring-boot narrowed
    # on 13 rows at a median of 5.7% of its suite and this function scored it 0.
    #
    # The prose cannot be repaired by excluding the capture openers, and that was tried first. The
    # scrape matches `[yoriwake] <task>: <anything>`, and the run path prints at least four kinds of line
    # through that prefix: the three capture decisions, the inline-widening notice ("3 class(es)
    # inlined the changed sources"), the experimental-class-selection warning -- which is every one
    # of glide's fourteen narrowed rows -- and, occasionally, an actual refusal. Only 21 of the
    # corpus's 87 forced rows carry a real reason at all.
    #
    # So the token is asked when the row has one, and where it does not, the COUNTS decide and the
    # output says so. The alternative -- guessing from prose -- is what produced the 81%.
    # Rows with no token are classified from the counts plus whatever prose survives the filter, and
    # that is weaker than a token in both directions: a refusal the plugin has since reworded would
    # be read as a saving, and a forced run whose two arms happen to execute one different test still
    # reads as a 99.8% "narrowing". Said out loud rather than folded in silently, because a reader
    # who knows which rows those are can look at `max_percent` and see it.
    untokened = [f for f in measured if f.get("decision_outcome") is None]
    if untokened:
        print(f"  narrowing: {len(untokened)} of {len(measured)} rows predate the selector recording "
              f"its own outcome, and are classified from counts and scraped prose rather than from "
              f"a token")

    narrowed = [f for f in measured if narrowed_at_all(f)]
    fractions = sorted(100.0 * f["selected_distinct"] / f["full_distinct"] for f in narrowed)
    summary = {"measured": len(measured), "narrowed": len(narrowed), "reasons": {}}
    print(f"  narrowing: {len(narrowed)} of {len(measured)} changes ran less than everything", end="")
    if fractions:
        # statistics.median, not fractions[len // 2]: the latter is the upper of the two middle
        # values on an even-length list, which quietly overstates the typical narrowing.
        summary.update(
            min_percent=fractions[0],
            max_percent=fractions[-1],
            median_percent=statistics.median(fractions),
        )
        print(f"; when it did, it ran {fractions[0]:.1f}% to {fractions[-1]:.1f}% of the suite "
              f"(median {summary['median_percent']:.1f}%)")
    else:
        print("")

    # Grouped, because the same reason repeated is one finding rather than N. A full run whose
    # reason was never recorded is called out: it is the case that cannot be investigated later,
    # which is exactly what the per-target reason was added to prevent.
    reasons = {}
    for f in measured:
        if narrowed_at_all(f):
            continue
        # The TOKEN first, and the prose only where there is no token. `full_run_reason` is scraped
        # from a log line the selecting arm does not print on a forced run, so it read "no reason
        # recorded" on 47 of the 87 forced rows of one corpus run while the selector knew the
        # answer perfectly well. A row swept since carries `full_run_kind`.
        kind = f.get("full_run_kind")
        reason = kind or f.get("full_run_reason") or "no reason recorded"
        # .get, not [...]: this runs over every finding rather than only the well-formed ones the
        # recall gate filters, and a schema-drifted entry must not raise before the gate reports.
        reasons.setdefault(reason, []).append(f.get("target", "<unnamed>"))
    for reason, targets in sorted(reasons.items(), key=lambda kv: -len(kv[1])):
        print(f"    x{len(targets)} full run: {reason}")
    summary["reasons"] = {reason: len(targets) for reason, targets in reasons.items()}
    return summary


def ground_truth_mix(rows):
    """`{full, complement, complement+full: rows}` over answered rows: where each row's
    ground truth came from. A row written before the field ran its own full arm, so it is `full`."""
    mix = {run.FULL_TRUTH: 0, run.COMPLEMENT_TRUTH: 0, run.CHECKED_TRUTH: 0}
    for row in rows:
        if row.get("status") in ("checked", "no-effect"):
            mix[row.get("ground_truth") or run.FULL_TRUTH] += 1
    return mix


def ground_truth_line(mix):
    """The sentence every recall figure prints beside itself, so complement and full-arm truth are
    never pooled without saying so."""
    return ("  ground truth: " + ", ".join(f"{count} {name}" for name, count in mix.items())
            + " row(s)" + ("" if not mix.get(run.COMPLEMENT_TRUTH) else
                           "; complement rows ran only the dropped tests' classes, and their "
                           "`ran_but_passed` is not measured"))


def recall_outcome(row):
    """What a row says about recall: whether it induced a failure, and which failures it missed."""
    return row.get("status"), tuple(sorted(row.get("missed") or ()))


def reset_paths(data):
    """Every untracked or ignored path the sweep's reset removed, before a row or the preflight."""
    removed = set((data.get("preflight") or {}).get("untracked_reset") or ())
    for row in data.get("mutations") or []:
        removed.update(row.get("untracked_reset") or ())
    return removed


def compare_recall(previous, current):
    """Every mutation both files measured whose recall outcome differs, each with its cause.

    Read on full-arm truth only: a `complement+full` row's verdicts are its full arm's,
    and a `complement` row is refused, by raising, since no full arm stands behind it. A difference
    is the reset's when the earlier row forced on a path this sweep's reset removed -- a file an
    earlier test run wrote, which a clean checkout does not have; any other is `unexplained`.
    """
    rows = [r for r in current.get("mutations") or []
            if r.get("status") in ("checked", "no-effect")]
    complement = [r["target"] for r in rows if r.get("ground_truth") == run.COMPLEMENT_TRUTH]
    if complement:
        raise ValueError(f"{len(complement)} row(s) have only complement truth, which no 0.1 "
                         f"full-arm verdict may be compared with: {', '.join(complement[:3])}")
    before = {run.row_key(r): r for r in previous.get("mutations") or []
              if r.get("status") in ("checked", "no-effect")}
    removed = reset_paths(current)
    differences = []
    for row in rows:
        old = before.get(run.row_key(row))
        if old is None or recall_outcome(old) == recall_outcome(row):
            continue
        reason = old.get("decision_reason") or ""
        cause = ("reset" if old.get("full_run_kind") == "unmappable-paths"
                 and any(path in reason for path in removed) else "unexplained")
        differences.append({"target": row["target"], "before": recall_outcome(old),
                            "after": recall_outcome(row), "cause": cause})
    return differences


def main(path, summary_path=None):
    with open(path, encoding="utf-8") as handle:
        results = json.load(handle)

    findings = results.get("mutations", [])
    # ONE POPULATION A FILE. A history replay's rows count toward no recall figure; mixed into a
    # file of mutation rows they would be counted as if they did.
    if len({f.get("kind") == "history" for f in findings}) > 1:
        print(f"FAIL: {results.get('project')}: the file mixes history-replay rows with mutation "
              f"rows, and a replay's rows count toward no recall figure")
        return 1
    checked = [f for f in findings if f.get("status") == "checked"]
    # Required, not defaulted. `f.get("missed")` answers None for an entry that never carried the
    # key, which reads as "nothing was missed" -- a schema drift in the harness would turn this gate
    # green while telling it nothing. A checked mutation without the key is a broken result file.
    malformed = [f["target"] for f in checked if "missed" not in f]
    missed = [(f["target"], f["missed"]) for f in checked if f.get("missed")]
    passed_anyway = [(f["target"], f["ran_but_passed"]) for f in checked if f.get("ran_but_passed")]
    # NEITHER MISSED NOR CLEAN, and it needs a reader here or it stops being written and nobody
    # notices. A test whose display name carries an identity hash is named differently by the two
    # arms, so no comparison between them is possible -- and a sweep full of those would otherwise
    # print "0 missed" while having established nothing at all about the rows they came from.
    undetermined = [(f["target"], f["undetermined"]) for f in checked if f.get("undetermined")]
    # A SECOND KIND OF NEITHER, with its own cause. The line printed under `undetermined` names one
    # cause -- an identity hash in the display name -- and that sentence is false of a test whose
    # isolated re-runs disagreed with each other. Folding the two together would print the wrong
    # reason for half the rows, which is why `SCAN_THREW` was split out of `SCAN_EXHAUSTED`.
    nondeterministic = [
        (f["target"], f["nondeterministic"]) for f in checked if f.get("nondeterministic")
    ]
    # A THIRD "NEITHER", with its own cause and therefore its own reader. A container that never
    # started is not a test: no test method ran, so neither arm says anything about whether
    # selection would have chosen one. Reported and never gated -- the harness cannot decide it,
    # and failing the build on what the harness cannot decide teaches people to ignore this job.
    container_failed = [
        (f["target"], f["container_failed"]) for f in checked if f.get("container_failed")
    ]
    # A FOURTH "NEITHER", with its own cause: the selected arm ran the failure's key, but that key
    # may stand for a test other than the one that failed. Not folded into `undetermined`, whose
    # printed cause is the identity hash.
    shared_key = [(f["target"], f["shared_key"]) for f in checked if f.get("shared_key")]
    failures = sum(f.get("full_failures", 0) for f in checked)

    print(f"{results.get('project')}: {len(checked)} mutations asked a recall question, "
          f"{failures} genuine failures, {len(missed)} missed, "
          f"{sum(len(t) for _, t in undetermined)} undetermined, "
          f"{sum(len(t) for _, t in nondeterministic)} nondeterministic, "
          f"{sum(len(t) for _, t in shared_key)} on a shared key")
    print(ground_truth_line(ground_truth_mix(findings)))

    narrowing = report_narrowing(findings)

    for target, tests in passed_anyway:
        print(f"  ran but did not fail: {target}")
        for test in tests:
            print(f"      {test}")
    if passed_anyway:
        print("  ^ selected and executed, so not a skip. Order-dependence or flakiness; "
              "see the known limits in README.md.")

    for target, tests in undetermined:
        print(f"  UNDETERMINED, neither missed nor clean: {target}")
        for test in tests:
            print(f"      {test}")
    if undetermined:
        print("  ^ the two runs name these tests differently -- an identity hash in the display "
              "name -- so no comparison between them was possible. Not counted as missed and NOT "
              "counted as clean.")

    for target, tests in shared_key:
        print(f"  SHARED KEY, neither missed nor clean: {target}")
        for test in tests:
            print(f"      {test}")
    if shared_key:
        print("  ^ the selected arm ran these failures' keys, but each key may stand for a test "
              "other than the one that failed -- method-less display names shared across a "
              "class's methods, read where per-method outcomes were missing -- so nothing says "
              "the failing test ran. Not counted as missed and NOT counted as clean.")

    for target, tests in container_failed:
        print(f"  CONTAINER FAILED, neither missed nor clean: {target}")
        for test in tests:
            print(f"      {test}")
    if container_failed:
        print("  ^ these are classes, not tests -- one that never started, or whose @AfterAll or "
              "extension failed -- so no test method ran under the name for "
              "selection to have chosen or skipped. Both verification arms run a class alone, "
              "which is a far lighter load than the full suite, so neither can reproduce a "
              "container that only fails under one. Not counted as missed and NOT counted as "
              "clean. This names what this harness cannot decide, not a property of the guest.")

    for target, tests in nondeterministic:
        print(f"  NONDETERMINISTIC, neither missed nor clean: {target}")
        for test in tests:
            print(f"      {test}")
    if nondeterministic:
        print("  ^ these gave DIFFERENT answers to identical isolated re-runs against the same "
              "tree, so neither arm is evidence about selection and the ground truth that named "
              "them failures is not trustworthy about them. Not counted as missed and NOT counted "
              "as clean. A nondeterministic test is a real problem -- every recall claim here "
              "rests on ground truth -- and it is not this problem.")

    # A sweep whose every answered row established nothing asked the question and got no answer,
    # which is the same shape as "no row asked a recall question" one layer in: the rows exist, they
    # induced failures, and nothing was established about any of them. Both kinds of "neither" count
    # here -- a gate that fired on unmatched names and not on unrepeatable ones would go green on a
    # sweep whose whole finding was that its tests cannot repeat themselves.
    if checked and all(established_nothing(f) for f in checked):
        print(f"FAIL: all {len(checked)} answered mutations were UNDETERMINED, NONDETERMINISTIC, "
              f"or failed only in tests set aside as containers or as failing unmutated. No usable "
              f"comparison between the two runs was available, so this sweep establishes nothing "
              f"about recall.")
        return 1

    if malformed:
        for target in malformed:
            print(f"  MALFORMED (no 'missed' key): {target}")
        print(f"FAIL: {len(malformed)} checked mutations carry no 'missed' key. The harness and this "
              f"gate disagree about the result format, so a missed failure could not be seen.")
        return 1

    # A selecting run that never reached the tests because the BUILD died is the worst outcome
    # available: the tool broke a host build. It is gated here rather than reported, and separately
    # from missed failures, because until this existed it was filed as a cache hit and a sweep went
    # green while hiding it.
    broken = [f["target"] for f in findings if f.get("status") == "build-failed"]
    if broken:
        for target in broken:
            print(f"  BUILD FAILED before the tests ran: {target}")
        print(f"FAIL: {len(broken)} runs died before reaching the tests. Selection must never break "
              f"the build it is a guest in.")
        return 1

    # A rejected mutation costs no row and fails nothing on its own -- that is the whole point
    # of the gate, and a sweep whose only defect was a handful of rejections is a good sweep. What
    # is NOT a statistic is a rate: every one of the six grammar defects so far was found
    # because it cost rows or whole sweeps, and the gate converts exactly that signal into a silent
    # discard. Above the ceiling the sample has been shaped by the generator rather than by the
    # project, so this refuses instead of reporting a number drawn from what survived.
    rejected = results.get("mutations_rejected")
    if rejected:
        offered = rejected + len(findings)
        print(f"  {rejected} of {offered} mutations were rejected by the compile gate "
              f"({100.0 * rejected / offered:.1f}%) and consumed no row")
        for target in results.get("rejected_targets") or []:
            print(f"      {target}")
        if run.rejection_rate_refuses(rejected, len(findings)):
            print(f"FAIL: the generator wrote source this project would not compile on "
                  f"{100.0 * rejected / offered:.1f}% of its targets, above the "
                  f"{100.0 * run.MUTATION_REJECTION_CEILING:.0f}% ceiling. The sample was shaped by "
                  f"the grammar rather than by the project, so no figure drawn from what survived "
                  f"is a figure about this project. Fix the generator, do not read the rows.")
            return 1

    if missed:
        for target, tests in missed:
            print(f"  MISSED: {target}")
            for test in tests:
                print(f"      {test}")
        print(f"FAIL: {sum(len(t) for _, t in missed)} failing tests were never selected.")
        return 1

    # Keyed on genuine failures rather than on the count of "checked" mutations, because the two
    # have disagreed: older result files label every mutation checked, including the ones that broke
    # nothing. What makes a sweep meaningful is that something actually failed for selection to
    # have had the chance to miss.
    if failures == 0:
        # A replay is exempt, and only a replay. A mutation corpus exists to induce failures, so a
        # sweep of it that broke nothing measured nothing and must not report a pass. Real commits
        # are not engineered to break anything and mostly do not; requiring failures there would fail
        # every honest replay, and the answer it is being asked for -- how often selection narrows on
        # real changes -- does not depend on anything failing. Recall is still gated: a missed
        # failure above returns 1 whatever the corpus.
        if findings and all(f.get("kind") == "history" for f in findings):
            print("  (replay: real commits are not expected to break tests, so the "
                  "nothing-broke gate does not apply; a missed failure would still fail this job)")
        else:
            print("FAIL: no mutation broke anything, so recall was never actually tested. "
                  "The corpus needs targets whose behaviour the suite observes.")
            return 1

    print("OK")
    if summary_path:
        # Written as data as well as printed. The narrowing rate is the number that decides whether
        # the tool is worth its capture cost, and a CI job that wants to watch it for regressions
        # should not have to parse the sentence above.
        with open(summary_path, "w", encoding="utf-8") as handle:
            json.dump({"project": results.get("project"), "narrowing": narrowing}, handle, indent=1)
    return 0


def main_against(previous_path, current_path):
    """`--against`: the recall comparison with an earlier file; non-zero on any unexplained
    difference, or on complement-only rows."""
    with open(previous_path, encoding="utf-8") as handle:
        previous = json.load(handle)
    with open(current_path, encoding="utf-8") as handle:
        current = json.load(handle)
    try:
        differences = compare_recall(previous, current)
    except ValueError as refused:
        print(f"REFUSED: {refused}")
        return 1
    for difference in differences:
        print(f"  {difference['cause'].upper()}: {difference['target']} "
              f"{difference['before']} -> {difference['after']}")
    unexplained = [d for d in differences if d["cause"] == "unexplained"]
    print(f"{current.get('project')}: {len(differences)} recall outcome(s) differ from "
          f"{os.path.basename(previous_path)}, {len(unexplained)} unexplained")
    return 1 if unexplained else 0


if __name__ == "__main__":
    if len(sys.argv) == 4 and sys.argv[1] == "--against":
        raise SystemExit(main_against(sys.argv[2], sys.argv[3]))
    if len(sys.argv) not in (2, 3):
        print("usage: assert_recall.py <results.json> [narrowing-summary.json]\n"
              "       assert_recall.py --against <earlier results.json> <results.json>",
              file=sys.stderr)
        raise SystemExit(2)
    raise SystemExit(main(sys.argv[1], sys.argv[2] if len(sys.argv) == 3 else None))
