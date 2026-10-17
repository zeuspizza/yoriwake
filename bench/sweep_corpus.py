"""Sweeps every qualified project, one at a time, and says what each one answered.

WHY A DRIVER AND NOT A SHELL LOOP. Three things have to hold across a corpus run and none of them
is a one-liner:

* **One measurement at a time.** Every figure here is a wall clock, and two sweeps sharing a machine
  measure the scheduler. Daemons are reaped between projects for the same reason -- a daemon warmed
  by the previous project is a difference between rows that has nothing to do with either project.
* **A sweep that answers nothing must not stop the corpus.** `run.py` exits non-zero when no row
  induced a failure, which is correct for one invocation and wrong here: it is a finding about that
  project, and the twenty after it were never attempted. `qualify.py --all` learned this the
  expensive way, at the twelfth of twenty-one projects.
* **A partial corpus is a useful corpus.** Each project's summary is appended the moment it lands,
  so a run that is interrupted -- or that simply outlasts the evening -- still leaves everything it
  measured.

This produced the first corpus figure: 18 projects, 16 targets each, about eleven hours.

The full arm runs the lighter 0.1.0 sweep: forced rows run once outside a fixed timing subset,
each project stops drawing targets at four narrowed rows, and the isolated arm runs on single-JVM
suites only. See TIMING_SUBSET below and docs/sweep-data-dictionary.md.

Usage:
    python bench/sweep_corpus.py --limit 16 --skip rxjava kafka
"""

import argparse
from collections import Counter
import glob
import json
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import assert_recall  # noqa: E402  (narrowed_at_all lives with the gate, not in a second copy)
import cost  # noqa: E402
import run  # noqa: E402  (coverage_only: which rules coverage alone applies, defined once)
import own_processes  # noqa: E402

BENCH = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(BENCH)
# Per-project sweep logs, beside the per-row Gradle logs the harness already writes there.
SCRATCH = os.path.join(BENCH, "logs")
SUMMARY = os.path.join(SCRATCH, "sweep-summary.jsonl")
os.makedirs(SCRATCH, exist_ok=True)


def qualified(path):
    """The projects the qualification screen passed, in the order it recorded them.

    Read from qualification.txt rather than from the project files, because a project that does not
    build, does not run its tests hermetically or does not attach the plugin cannot answer a recall
    question -- and a sweep of one measures the machine's patience.
    """
    names = []
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            match = re.match(r"^QUALIFIED\s+(\S+)", line)
            if match:
                names.append(match.group(1))
    return names


def qualification_seconds(path):
    """`{project: seconds}` of each QUALIFIED row's qualification run, for projecting a project that
    has no results file yet."""
    seconds = {}
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            match = re.match(r"^QUALIFIED\s+(\S+).*?\sseconds=([0-9.]+)", line)
            if match:
                seconds[match.group(1)] = float(match.group(2))
    return seconds


def project_limit(path, default):
    """A project's own `sweep_limit`, or the corpus-wide default.

    Read here rather than in `run.py` so `--limit` keeps exactly one meaning at the place it is
    passed: how many targets THIS invocation may spend. A project that has not stated a depth takes
    the default and says nothing, which is what every project did before this key existed.

    An unreadable or non-integer value falls back to the default rather than raising: a typo in one
    project file must not abandon the twenty projects after it, which is the same rule the driver
    applies to a sweep that exits non-zero.
    """
    try:
        import tomllib  # noqa: PLC0415 -- only this function needs it
        with open(path, "rb") as handle:
            stated = tomllib.load(handle).get("sweep_limit")
    except (OSError, ValueError):
        return default
    return stated if isinstance(stated, int) and stated > 0 else default


def reap():
    """Between projects, not during one. See the module docstring.

    Only the harness's own daemons: another project's, or an IDE's, is not this sweep's to stop.
    See `own_processes`.
    """
    own_processes.reap_own()
    time.sleep(3)


def result_key_fields(rows):
    """Which keys the rows that compared their arms matched failures on, and why any fell back.

    THREE COUNTS, NOT TWO: a row with no `result_keys` was written before per-method keys and
    matched XML keys without the shared-key rule, which is neither scheme -- reading its absence as
    either would describe rows nobody stamped. The fallback causes are grouped with their numbers
    blanked, since a completeness cause carries the counts of the build it describes.
    """
    compared = [r for r in rows if r.get("status") in ("checked", "no-effect")]
    stamps = Counter(r.get("result_keys") or "unstamped" for r in compared)
    causes = Counter(re.sub(r"\d+", "N", r["result_keys_fallback"])
                     for r in compared if r.get("result_keys_fallback"))
    return {"result_keys": {scheme: stamps.get(scheme, 0)
                            for scheme in ("per-method", "xml", "unstamped")},
            "result_keys_fallbacks": dict(causes.most_common()) or None}


def results_file(name, shapes, isolated=False):
    """The results file `run.py` writes for this project under this shape set."""
    return run.results_location(os.path.join(BENCH, "results"), "", name, shapes, isolated)[0]


def summarise(name, capture_mode="shared", shapes=run.DEFAULT_SHAPES):
    """What this project's results file now says, or why it says nothing.

    `capture_mode` picks the arm's file: `<name>.json` for the shared arm, `<name>.isolated.json`
    for the isolated one.

    Reports the fields that travel: missed failures (portable), selection share (never a speedup),
    and the count of rows that asked a recall question at all. A sweep whose mutations induced no
    failures asked nothing, and reporting its zero as a safety result is the failure mode this
    corpus exists to avoid.
    """
    path = results_file(name, shapes, capture_mode == "isolated")
    if not os.path.isfile(path):
        return {"project": name, "capture_mode": capture_mode, "shape_set": shapes,
                "status": "no results file"}
    try:
        with open(path, encoding="utf-8") as handle:
            data = json.load(handle)
    except (OSError, ValueError) as error:
        return {"project": name, "shape_set": shapes, "status": f"unreadable ({type(error).__name__})"}

    rows = data.get("mutations") or []
    # A SELECTING ARM THAT DECIDED NOTHING asked nothing, and is not a row of this sample: kept out
    # of every count below, and counted here so it cannot shrink the sample unseen. See
    # run.undecided_row.
    invalid = [r for r in rows if r.get("status") == "invalid"]
    rows = [r for r in rows if r.get("status") != "invalid"]
    checked = [r for r in rows if r.get("status") == "checked"]
    narrowed = [r for r in checked
                if r.get("selected_distinct") is not None
                and r.get("full_distinct")
                and r["selected_distinct"] < r["full_distinct"]]
    reasons = {}
    for row in rows:
        reason = (row.get("full_run_reason") or "")[:60]
        if reason:
            reasons[reason] = reasons.get(reason, 0) + 1

    # EVERY FIELD BELOW HAS A READER HERE, and that is the whole rule. `timing_method` was added to
    # `write_results` and never appeared in a mutation sweep because a second writer was the one
    # reaching disk, and nobody noticed for weeks; `undetermined` read zero across a whole corpus
    # while a results file held two. A column that is printed is a column somebody checks.
    forced = [r for r in rows if r.get("decision_outcome") == "full-run"]
    kinds = {}
    for row in forced:
        kind = row.get("full_run_kind") or "unrecorded"
        kinds[kind] = kinds.get(kind, 0) + 1

    # THE THREE-WAY SUM, over the rows that carry a decision token, classified BY that token.
    #
    # Not over `checked`, and not by comparing arms: `narrowed` above is defined by
    # `selected_distinct < full_distinct` within checked rows, which is a different key over a
    # different population -- on the one results file carrying decision tokens the two definitions
    # disagreed by three rows of fifteen. Both are kept, under their own names, so every published
    # narrowing figure keeps its meaning.
    #
    # The gate is that the three counts SUM to the answered population. Counting `unrecorded` inside
    # one bucket cannot see a row that entered no bucket, and a row in no bucket is exactly the
    # defect this exists to catch: a refused run used to be recorded as `selection-not-requested`,
    # which the forced filter above misses and the narrowed comparison never sees.
    answered = [r for r in rows if r.get("decision_outcome")]
    classified = {"narrowed": [], "forced": [], "refused": [], "requested": [], "unclassified": []}
    for row in answered:
        outcome = row.get("decision_outcome")
        if outcome == "narrowed":
            classified["narrowed"].append(row)
        # ON THE COARSE TOKEN, which says which SIDE refused, and not on the presence of the fine
        # one. A refusal whose kind failed to arrive -- an older plugin, a hand-set fixture -- is
        # still a refusal, and bucketing it as an ordinary forced run puts it back in the population
        # this classification exists to separate. The fine kind is the breakdown, below.
        # A full run the user asked for travels the refusal channel too, and is not the tool
        # refusing: counted beside the refusals, never inside them.
        elif (outcome == "full-run" and row.get("full_run_kind") == "daemon-refused"
              and row.get("refusal_kind") in assert_recall.REQUESTED_DECLINES):
            classified["requested"].append(row)
        elif outcome == "full-run" and row.get("full_run_kind") == "daemon-refused":
            classified["refused"].append(row)
        elif outcome == "full-run" and row.get("full_run_kind"):
            classified["forced"].append(row)
        else:
            # `selection-not-requested`, `not-decided`, and a full run with no kind at all. Named
            # rather than absorbed: each is a row nobody can attribute, and the sum is what makes
            # them visible.
            classified["unclassified"].append(row)
    refusal_kinds = {}
    for row in classified["refused"]:
        # `unnamed` rather than dropping the row: a refusal that named no kind is still a refusal,
        # and a breakdown that silently omitted it would not sum to its own bucket.
        kind = row.get("refusal_kind") or "unnamed"
        refusal_kinds[kind] = refusal_kinds.get(kind, 0) + 1
    # A row whose selector record disagrees with the tests the arm actually ran is describing
    # something else. Counted rather than dropped: silence here is what this summary forbids.
    # False only, never None: None means the row forced, where discovery and execution counts
    # legitimately differ and the comparison was deliberately not made.
    mismatched = sum(1 for r in rows if r.get("decision_matches_arm") is False)
    # The widest gap between what the selector says it let through and what Gradle says ran. Two
    # units: record leaf ids against XML keys, which a parameterized method expands and a
    # method-less display name collapses (see docs/sweep-data-dictionary.md). A diagnostic, never a
    # safety measure. Rows written before the leaf count are left out, not read as zero.
    gaps = [r["decision_included_leaves"] - r["selected_distinct"] for r in rows
            if isinstance(r.get("decision_included_leaves"), int)
            and isinstance(r.get("selected_distinct"), int)]
    # A map re-captured part-way through a sweep makes the rows before it incomparable with the ones
    # after. One value means one map; more than one is a finding, not a detail.
    fingerprints = {(r.get("map_capture_commit"), r.get("map_records")) for r in rows
                    if r.get("map_capture_commit") is not None}
    # THE HEALTH THE FINGERPRINT CANNOT SEE. Two maps with the same capture commit and the same
    # record count answered differently on glide, because 1,220 of its 1,522 records named no class.
    # A row whose map attributes almost nothing cannot narrow, and the reason is the map rather than
    # the selector -- which is a different remedy. Absent is reported as absent: a results file
    # written before this field existed says nothing about its maps' health, and must not read as
    # healthy.
    attributable = [r["map_attributable"] for r in rows
                    if isinstance(r.get("map_attributable"), (int, float))]
    uptimes = [c.get("daemon_uptime_seconds")
               for r in rows
               for c in (r.get("selected_conditions"), r.get("full_conditions"))
               if isinstance(c, dict) and c.get("daemon_uptime_seconds") is not None]
    loads = [c.get("load_average")
             for r in rows
             for c in (r.get("selected_conditions"), r.get("full_conditions"))
             if isinstance(c, dict) and c.get("load_average") is not None]
    # THE DEPTH THIS FILE'S ROWS WERE ASKED AT, and every distinct value rather than one of them.
    # `run.py` resumes by skipping already-measured targets, so a file accumulates rows from several
    # invocations; a scalar would describe only the last. `mixed` is a finding, not a formatting
    # choice -- rows drawn at different depths are not one sample.
    #
    # AND THIS IS THE READER RULE 4b ASKS FOR. A field with no reader stops being written and nobody
    # notices; `undetermined` read zero across a whole corpus while a results file held two.
    limits = sorted({r["sweep_limit"] for r in rows if isinstance(r.get("sweep_limit"), int)})
    unstamped = sum(1 for r in rows if not isinstance(r.get("sweep_limit"), int))
    # THE STOPPING RULE, read the same way and for the same reason: rows drawn under different
    # rules are not one sample. `narrowed_target_rows` is how many rows counted toward it.
    targets = sorted({r["narrowed_target"] for r in rows
                      if isinstance(r.get("narrowed_target"), int)})
    return {
        "project": name,
        "status": "swept",
        "shape_set": shapes,
        **sweep_fields(data, rows),
        "plugin_commit": data.get("plugin_commit"),
        # How hard this project was asked, and out of how much. "16 of 1,059" and "16 of 18" are
        # different claims and a bare 16 reads the same either way.
        "sweep_limit": (limits[0] if len(limits) == 1 else
                        ("mixed" if limits else None)),
        "sweep_limits": limits or None,
        # Rows written before the limit was stamped, or by a path that does not stamp it. Counted
        # rather than absorbed: a file that is half-stamped must not report one depth.
        "rows_without_a_limit": unstamped,
        "narrowed_target": (targets[0] if len(targets) == 1 else
                            ("mixed" if targets else None)),
        "narrowed_targets": targets or None,
        "rows_without_a_narrowed_target": sum(
            1 for r in rows if not isinstance(r.get("narrowed_target"), int)),
        "narrowed_target_rows": sum(1 for r in rows if run.counts_toward_narrowed_target(r)),
        # True: forced rows ran once and carry no `full_seconds`, so no time-saved figure is read
        # from this project. None: written before the stamp existed.
        "recall_only": data.get("recall_only"),
        # `setup-forced`: stopped before its rows could count, with the decision's reason.
        "project_outcome": data.get("project_outcome"),
        "setup_forced_reason": (data.get("setup_forced") or {}).get("reason"),
        "targets_available": data.get("targets_available"),
        # THE READER THE GATE NEEDS. A rejected mutation consumes no row, so it is invisible in
        # every other column here -- which is the point of the gate and also its whole hazard. A
        # rate that climbs across a sweep is a grammar defect regenerating, and this is where a
        # person sees it without opening a results file.
        "mutations_rejected": data.get("mutations_rejected"),
        "target_ordering": data.get("target_ordering"),
        "target_seed": data.get("target_seed"),
        "rows": len(rows),
        "invalid_rows": len(invalid),
        "invalid_reasons": sorted({r.get("invalid_reason") or "unstated" for r in invalid}) or None,
        "asked": len(checked),
        "missed": sum(len(r.get("missed") or []) for r in checked),
        "order_dependent": sum(len(r.get("order_dependent") or []) for r in checked),
        # THE READER RULE 4b ASKS FOR, and this bucket needs one more than most: it is the only
        # thing that SUBTRACTS from `missed`, so a sweep where it fires and nobody sees it is a
        # recall figure that improved silently.
        "fails_unmutated": sum(len(r.get("fails_unmutated") or []) for r in checked),
        # READ, not merely recorded. The first version of this summary printed an `undetermined`
        # column that `summarise` never wrote, so the whole corpus reported zero of them while
        # armeria's results file held two -- a field with no reader, which is the shape the rule
        # above exists to forbid, reproduced in the summary of the very run that motivated it.
        "undetermined": sum(len(r.get("undetermined") or []) for r in checked),
        # THE THIRD SUBTRACTOR, and it needs a reader here for the same reason `fails_unmutated`
        # does: it takes failures OUT of the headline, so a sweep where it fires and nobody sees it
        # is a recall figure that improved without anyone deciding it should.
        "container_failed": sum(len(r.get("container_failed") or []) for r in checked),
        # THE FOURTH SUBTRACTOR: failures whose key may stand for another test.
        "shared_key": sum(len(r.get("shared_key") or []) for r in checked),
        **result_key_fields(rows),
        "ran_but_passed": sum(len(r.get("ran_but_passed") or []) for r in checked),
        "induced": sum(r.get("full_failures") or 0 for r in checked),
        "narrowed_rows": len(narrowed),
        # THE POPULATION A RECALL CLAIM IS ABOUT, which is NOT `narrowed_rows` above. That counts
        # rows where selection ran less than everything; this also requires the mutation to have
        # broken something, because a row that narrowed and induced no failure gave selection
        # nothing to miss. The two disagree -- this file's own `answered` comment records them
        # differing by three rows of fifteen on a real results file -- and substituting one for the
        # other let the reduced arm's refusal pass an arm that had asked nothing at all.
        "miss_possible_rows": sum(1 for r in checked
                                  if r.get("full_failures") and assert_recall.answered_failures(r)
                                  and assert_recall.narrowed_at_all(r)),
        "narrowest": min(
            (r["selected_distinct"] / r["full_distinct"] for r in narrowed), default=None),
        "top_reason": max(reasons, key=reasons.get) if reasons else None,
        # Why the forced rows forced, as tokens. This is the column the first corpus figure could
        # not print: 66 of its 87 forced rows carried no usable reason at all.
        "forced_rows": len(forced),
        "force_kinds": kinds or None,
        # The three-way classification and its own arithmetic, so a reader can check the sum rather
        # than trust it.
        "answered_rows": len(answered),
        # ROWS THAT LEFT THE POPULATION, which the sum cannot see. A row whose decision record was
        # absent or discarded has no outcome, so it never enters `answered` -- and a corpus where
        # every record was unreadable classifies nothing and reports a clean 0 == 0. Counted here so
        # a vacuous pass and a complete one do not print the same.
        "unattributed_rows": len(rows) - len(answered),
        "classified": {k: len(v) for k, v in classified.items()},
        "refusal_kinds": refusal_kinds or None,
        # THE RELEASE GATE'S EVIDENCE: every row a changed environment refused. The arms differ
        # only in CI per-run variables (run.CI_RUN_VARIABLES), so each one is a value that moves per
        # build and forces every build; the release stops until it is normalized or documented.
        "environment_changed_rows": [
            r.get("target") or r.get("label") or "<unnamed row>"
            for r in classified["refused"] if r.get("refusal_kind") == "environment-changed"
        ] or None,
        "classification_sums": (
            len(classified["narrowed"]) + len(classified["forced"]) + len(classified["refused"])
            + len(classified["requested"]) == len(answered)
        ),
        "unclassified_rows": [
            r.get("target") or r.get("label") or "<unnamed row>"
            for r in classified["unclassified"]
        ] or None,
        "map_attributable": (
            [min(attributable), max(attributable)] if attributable else None
        ),
        "map_health_unknown_rows": sum(1 for r in rows if r.get("map_attributable") is None),
        "decision_mismatches": mismatched,
        "decision_vs_arm_gap": [min(gaps), max(gaps)] if gaps else None,
        "maps": len(fingerprints),
        "daemon_uptime_range": [min(uptimes), max(uptimes)] if uptimes else None,
        "load_range": [min(loads), max(loads)] if loads else None,
    }


def _tally(values):
    counts = {}
    for value in values:
        counts[str(value)] = counts.get(str(value), 0) + 1
    return counts or None


def _peak(rows, arm, key):
    values = [r[arm][key] for r in rows if isinstance(r.get(arm), dict)
              and isinstance(r[arm].get(key), (int, float))]
    return max(values) if values else None


def sweep_fields(data, rows):
    """The sweep's fields, per project: the arm, the rules behind every decision and every induced
    failure, the recording cost, what the map and the test JVMs cost, and whether any decision
    record was lost. Each has its reader here; docs/sweep-data-dictionary.md lists them.
    """
    rule_counts = {}
    for row in rows:
        for rule, count in (row.get("rule_counts") or {}).items():
            rule_counts[rule] = rule_counts.get(rule, 0) + count
    # THE OPTIMISTIC-MODE QUESTION, per induced failure: which rules selected it. A failure whose
    # rules hold no coverage-only one is a failure coverage alone would have missed; one with a
    # single rule is lost the moment that rule is dropped.
    with_rules, beyond_coverage, sole, unseen = 0, 0, {}, 0
    for row in rows:
        for tokens in (row.get("induced_rules") or {}).values():
            if tokens is None:
                unseen += 1
                continue
            with_rules += 1
            if not run.coverage_only(tokens):
                beyond_coverage += 1
            if len(tokens) == 1:
                sole[tokens[0]] = sole.get(tokens[0], 0) + 1
    capture = data.get("capture_arm") or {}
    baseline = data.get("baseline_arm") or {}
    facts = data.get("capture_facts") or {}
    return {
        "capture_mode": data.get("capture_mode") or "shared",
        "capture_max_parallel_forks": data.get("capture_max_parallel_forks"),
        "paired_with": data.get("paired_with"),
        "capture_seconds": data.get("capture_seconds"),
        "baseline_seconds": data.get("baseline_seconds"),
        "map_capture_modes": _tally(r.get("map_capture_mode") for r in rows),
        "map_schema_versions": _tally(r.get("map_schema_version") for r in rows),
        "rows_with_rules": sum(1 for r in rows if r.get("rule_counts") is not None),
        "rules_basis": _tally(r.get("rules_basis") for r in rows if r.get("decision_outcome")),
        "forcing_kinds": _tally(r.get("forcing_kinds") for r in rows if r.get("forcing_kinds")),
        "rule_counts": dict(sorted(rule_counts.items())) or None,
        "shares_jvm_included": sum(r.get("shares_jvm_included") or 0 for r in rows),
        "coverage_only_included": sum(r.get("coverage_only_included") or 0 for r in rows),
        "decision_included_total": sum(r.get("decision_included") or 0 for r in rows),
        "induced_with_rules": with_rules,
        "induced_unseen_by_record": unseen,
        "induced_beyond_coverage": beyond_coverage,
        "induced_sole_rule": dict(sorted(sole.items())) or None,
        # A LOST RECORD IS NOT A ROW THAT DECIDED NOTHING. Counted so a project whose records were
        # all lost -- the spring modules' nested class loader -- reads as that, not as unattributed.
        "decision_write_failure_rows": sum(1 for r in rows if r.get("decision_write_failures")),
        "decision_short_rows": sum(1 for r in rows if r.get("decision_covers_arm") is False),
        "peak_test_jvm_rss_mb": {"baseline": baseline.get("peak_test_jvm_rss_mb"),
                                 "capture": capture.get("peak_test_jvm_rss_mb"),
                                 "selected": _peak(rows, "selected_arm", "peak_test_jvm_rss_mb"),
                                 "full": _peak(rows, "full_arm", "peak_test_jvm_rss_mb")},
        "test_jvms": {"baseline": baseline.get("test_jvms"), "capture": capture.get("test_jvms")},
        "map_bytes": facts.get("map_bytes"),
        "map_raw_bytes": facts.get("map_raw_bytes"),
        "capture_touch_all_reasons": data.get("capture_touch_all_reasons"),
        "evidence_bytes": (data.get("evidence") or {}).get("bytes"),
        "environment": data.get("environment"),
    }


# THE REDUCED ARM, and the three projects in it are a considered default rather than a law.
#
# WHY IT EXISTS. The corpus answers a BREADTH question and costs a lower bound of 32.3 hours: 29.2h
# of timed arms plus 3.0h of capture and baseline over 459 rows, excluding clone, per-row compile
# and isolation re-runs. After a change to the selector the question is RECALL, not breadth, and
# paying a day and a half to ask it is what made every fix unaffordable.
#
# HOW THESE THREE WERE CHOSEN. Ranked by rows-where-a-miss-was-possible per hour, from the recorded
# timings of an earlier corpus run:
#
#     ktlint         5 miss-possible rows   0.05 h   104 rows/hour
#     resilience4j   4                      0.06 h    71
#     grpc-java      3                      0.06 h    50
#     apache-groovy  5                      0.10 h    49
#     iceberg       16 rows                 9.7  h
#
# Twelve rows where a miss was possible for about a tenth of an hour, against iceberg's 9.7 hours.
# The repository's own fixtures (demo, demo-kotlin, demo-android) rank higher still and are
# deliberately absent: they prove the harness rather than a guest, and a recall claim drawn from
# this project's own sample code is a claim about nothing anybody else runs.
#
# WHAT IT IS NOT. Three projects are not the corpus. A figure from this arm is a recall claim and
# may never be quoted as the corpus figure or compared with one as a series -- two figures
# drawn from different samples may not be compared that way. Every line this
# arm prints carries its name for that reason.
#
# A FIXED LIST, NOT A COMPUTED ONE. Recomputing the members per run from current timings would let
# the sample drift silently between sweeps, which is the sampling-bias defect this corpus has
# already had to withdraw a figure over. The ranking above justifies the list; the list is then
# stated and changes only when somebody changes it on purpose.
REDUCED_ARM = ("ktlint", "resilience4j", "grpc-java")


# THE LIGHTER 0.1.0 SWEEP, on the full arm only (the reduced arm keeps its own definition). The full
# sweep was priced at 45-50 hours, a second suite run on every forced row and an isolated arm on
# every project among them. Three rules, each a constant so the sample cannot drift
# between sweeps (see REDUCED_ARM on why not computed), and each stated in
# docs/sweep-data-dictionary.md.
#
# 1. EVERY PROJECT BUT THESE RUNS `--recall-only`: a forced row's selected arm ran everything, so
#    it is the ground truth and the full arm is not paid for twice. Its `full_seconds` is then
#    absent, never invented. These six keep the second arm, for the paired time-saved figure. They
#    span the corpus's suite lengths -- median full arm of the last results file, with the rows
#    that narrowed, because a project that never narrows has no saving to time:
#
#        grpc-java            6 s    1,536 tests    8 of 16 rows narrowed
#        apache-groovy       24 s    1,102 tests   10 of 11   (15 test JVMs, the multi-JVM case)
#        mockito             48 s    2,356 tests   12 of 16
#        spring-boot         85 s    3,682 tests    8 of 13
#        rxjava             181 s   13,921 tests   14 of 16
#        bitwarden-android  626 s    7,341 tests    7 of 16   (Android)
#
#    iceberg (1,039 s) is the longer end and was left out on price: its forced rows' second arms
#    are 17 minutes each, and bitwarden-android already covers a suite of ten minutes.
TIMING_SUBSET = ("grpc-java", "apache-groovy", "mockito", "spring-boot", "rxjava",
                 "bitwarden-android")
#    FROM 0.2 EACH TIMES ITS FIRST `TIMING_CAP` ROWS ONLY, both arms paired whatever
#    they decide; later rows run as on any other project. Re-checked
#    on the finished 0.1 files (median of per-row selected-to-full ratios, file order):
#    the first 8 rows sit within 4.1% of all rows' median and the first 6 within 7.0%, spring-boot
#    the widest both times (5.094 and 4.939 against 5.313); both under the 18% run-to-run floor.
TIMING_CAP = 8
# 2. THE STOPPING RULE: no more targets once this many rows narrowed and induced a failure, with the
#    project's `sweep_limit` (or `--limit`) still the cap. See run.counts_toward_narrowed_target for
#    why it cannot bias the recall figure.
NARROWED_TARGET = 4
# 3. THE ISOLATED ARM ONLY WHERE ISOLATION CHANGES SOMETHING: a suite whose shared capture recorded
#    one test JVM for the task, where every test shares one JVM's state and the shared-JVM rule
#    widens most. A suite already forked across JVMs is partly isolated by its own build. Read from
#    the shared results file (`isolated_arm_skip_reason`). These run it whatever that count says:
#    the four modules isolated capture was first measured on,
#    so the 0.1.0 figure can be read against that decision, and spring-test, added for its three
#    engines in one JVM.
ISOLATED_ALSO = ("opentelemetry-java", "spring-framework", "spring-boot-webmvc-test", "micrometer",
                 "spring-test")


def shared_arm_command(name, project, limit, recapture, lighter, ground_truth="full",
                       shapes=run.DEFAULT_SHAPES):
    """`run.py`'s arguments for one project's shared arm. `lighter` applies the 0.1.0 stopping rule
    above; both arms time only the timing subset, its first `TIMING_CAP` rows, from 0.2."""
    command = [sys.executable, os.path.join(BENCH, "run.py"), project, "--limit", str(limit)]
    if lighter:
        command += ["--narrowed-target", str(NARROWED_TARGET)]
    command += (["--timing-cap", str(TIMING_CAP)] if name in TIMING_SUBSET else ["--recall-only"])
    if ground_truth != "full":
        command += ["--ground-truth", ground_truth]
    command += ["--recapture"] if recapture else []
    return command + (["--shapes", shapes] if shapes != run.DEFAULT_SHAPES else [])


def ground_truth_for(name, arm, requested):
    """The `--ground-truth` one project's shared arm runs under.

    The reduced arm compares every recall outcome with 0.1's, so it runs `check`: the complement
    beside the full arm on every untimed narrowed row, the comparison on full-arm truth and the
    complement given a second witness. A full-arm project that runs an isolated arm needs full-arm
    truth on every row its isolated arm pairs with, so `auto` becomes `check` there too.
    """
    if arm == "reduced":
        return "check"
    if requested == "auto" and name in ISOLATED_ALSO:
        return "check"
    return requested


def cost_projection(names, results_dir, limits, seconds, arm, requested):
    """`{project: projection}` for the projects about to be swept, from each one's last
    results file or, with none, its qualification run. Informational: it caps and stops nothing."""
    projected = {}
    for name in names:
        try:
            with open(os.path.join(results_dir, f"{name}.json"), encoding="utf-8") as handle:
                data = json.load(handle)
        except (OSError, ValueError):
            if name not in seconds:
                continue
            data = cost.from_qualification(seconds[name])
        projected[name] = cost.project(
            data, limits[name], timing_cap=TIMING_CAP if name in TIMING_SUBSET else None,
            recall_only=name not in TIMING_SUBSET,
            ground_truth=ground_truth_for(name, arm, requested))
    return projected


def isolated_arm_skip_reason(name, shared_results):
    """(None, workers) when the isolated arm runs for this project, else (why not, workers).

    `workers` is `capture_facts.map_task_facts.workers` of the shared results file: how many test
    JVMs recorded the task's map. The map's own count rather than `capture_arm.test_jvms`, which is
    sampled from `ps` and can miss a short-lived JVM. Unknown does not run: a shared capture that
    says nothing about its JVMs is no evidence that isolation would change anything, and the
    summary line says which of the two reasons it was.
    """
    try:
        with open(shared_results, encoding="utf-8") as handle:
            data = json.load(handle)
    except (OSError, ValueError):
        data = {}
    facts = (data.get("capture_facts") or {}).get("map_task_facts") or {}
    workers = facts.get("workers")
    if name in ISOLATED_ALSO or workers == 1:
        return None, workers
    if not isinstance(workers, int):
        return "isolated arm: not run (test JVM count unknown)", workers
    return "isolated arm: not run (multi-JVM suite)", workers


def setup_forced_projects(summaries):
    """`<project>: <reason>` for each summary whose project stopped as setup-forced."""
    return [f"{s['project']}: {s.get('setup_forced_reason')}" for s in summaries
            if s.get("project_outcome") == run.SETUP_FORCED]


def argument_parser():
    parser = argparse.ArgumentParser()
    parser.add_argument("--limit", type=int, default=16)
    parser.add_argument("--set", default=run.DEFAULT_SHAPES, choices=run.SHAPE_SETS,
                        help="the shape set to sweep (default: default); a named set writes its "
                             "own results directory")
    parser.add_argument("--qualification", default=os.path.join(BENCH, "qualification.txt"))
    # EXTEND, NOT REPLACE: `--skip a --skip b` used to keep only `b`, so every 0.1.0 sweep run that
    # repeated the flag swept the projects it was told to leave out.
    parser.add_argument("--only", nargs="+", action="extend", help="restrict to these project names")
    parser.add_argument("--skip", nargs="+", action="extend", default=[],
                        help="qualified projects to leave out")
    parser.add_argument("--print-commands", action="store_true",
                        help="print each selected project's run.py flags as JSON and sweep nothing")
    parser.add_argument("--arm", choices=("full", "reduced"), default="full",
                        help="full: every qualified project, a breadth figure. reduced: the three "
                             "projects that buy the most recall evidence per hour, for "
                             "revalidating a selector change -- a RECALL claim, never a corpus one")
    # RE-CAPTURE, and it is not garnish. A map is recorded by one selector and read by another, and
    # `run.py` reuses whatever map is on disk unless told otherwise -- so a corpus swept after a
    # change to selection would report rows measured against maps captured under the selector that
    # change replaced. The rows would still be stamped correctly and the figure would still be
    # wrong about what it describes. Passed through per project rather than set here, so a sweep
    # that deliberately reuses maps is still one flag away.
    parser.add_argument("--recapture", action="store_true",
                        help="rebuild each project's map before sweeping it")
    # THE ISOLATED ARM, ON BY DEFAULT. After each project's shared arm, the same rows again
    # against a map recorded with -Pyoriwake.isolatedCapture, reusing the shared rows' ground truth.
    # It always re-captures: the map on disk is the shared arm's.
    parser.add_argument("--no-isolated-arm", action="store_true",
                        help="sweep the shared arm only")
    # COMPLEMENT GROUND TRUTH, passed through to every project's shared arm. `auto` only
    # once the maintainer has enabled it; the reduced arm always runs `check`.
    parser.add_argument("--ground-truth", choices=run.GROUND_TRUTH_MODES, default="full",
                        help="--ground-truth for run.py on the full arm (default full); the reduced "
                             "arm runs check")
    return parser


def main(argv=None):
    parser = argument_parser()
    args = parser.parse_args(argv)

    names = qualified(args.qualification)
    if not names:
        raise SystemExit(f"no QUALIFIED rows in {args.qualification}; refusing to sweep nothing")
    # THE REDUCED ARM'S OWN GATES ARE RECALL GATES: it would drop grails-core and refuse a sweep
    # whose every row forced, which is what this set expects of its rows.
    if args.set == run.FORCING_SET and args.arm == "reduced":
        raise SystemExit(f"{run.FORCING_SET} runs on the full arm, which it limits to the reduced "
                         f"arm's projects and grails-core; the reduced arm reads its rows as recall "
                         f"rows")
    if args.arm == "reduced":
        if args.only or args.skip:
            # BOTH, and the omission of --skip was the bug: it is applied AFTER the completeness
            # check below, so it could drop a member the check had just confirmed was present and
            # the run would still report under the arm's name.
            raise SystemExit("--arm reduced names its own projects; --only or --skip would "
                             "silently redefine the arm, and a figure from a redefined arm is not "
                             "a figure from this arm. Use --arm full --only for a deliberate "
                             "subset.")
        names = [n for n in names if n in set(REDUCED_ARM)]
        missing = [n for n in REDUCED_ARM if n not in set(names)]
        if missing:
            raise SystemExit(f"the reduced arm needs {', '.join(REDUCED_ARM)} and "
                             f"{', '.join(missing)} is not QUALIFIED; a partial arm measures "
                             f"something other than the arm and must not be reported as it")
    if args.only:
        names = [n for n in names if n in set(args.only)]
    names = [n for n in names if n not in set(args.skip)]
    # `forcing/v1` RUNS ON THE REDUCED ARM'S PROJECTS ONLY, and on the one whose measured root is
    # Groovy: each row costs a suite run for an answer the change's path fixes, so the rest of the
    # corpus would add hundreds of runs and no information.
    if args.set == run.FORCING_SET:
        names = [n for n in names
                 if n in set(REDUCED_ARM + run.forcing_mutations.GROOVY_PROJECTS)]
    # A SELECTION THAT MATCHED NOTHING IS NOT AN EMPTY CORPUS. Run once with `--only "$KEEP"` under
    # zsh, which does not word-split an unquoted variable: the whole 18-name string arrived as one
    # argument, nothing matched, and this printed "0 qualified projects" and exited 0. Six hours
    # were spent waiting for a run that had already finished successfully doing nothing, which is
    # the exact shape of failure the tool under measurement exists to prevent.
    if not names:
        raise SystemExit(
            f"--only/--skip selected none of the {len(qualified(args.qualification))} qualified "
            f"projects; refusing to report an empty sweep as a finished one"
        )
    if args.print_commands:
        # THE WORKFLOW SHARDS EVERY PROJECT, and `run.py` refuses a sharded `pool/v1` run with a
        # timing cap: refused here, before any project is recorded.
        timed = [n for n in names if n in TIMING_SUBSET]
        if args.set == run.POOL_SHAPES and timed:
            raise SystemExit(f"{run.POOL_SHAPES} cannot be sharded with a timing cap, and "
                             f"{', '.join(timed)} would get one; leave them out with --skip or "
                             f"sweep them unsharded")
        # THE WORKFLOW'S PLAN, from the rules above rather than a copy of them: each project's
        # `run.py` flags, without this machine's paths.
        print(json.dumps([
            {"project": name,
             "args": shared_arm_command(name, f"bench/projects/{name}.toml",
                                        project_limit(os.path.join(BENCH, "projects",
                                                                   f"{name}.toml"), args.limit),
                                        recapture=True, lighter=args.arm == "full",
                                        shapes=args.set,
                                        ground_truth=ground_truth_for(name, args.arm,
                                                                      args.ground_truth))[2:]}
            for name in names]))
        return 0
    print(f"arm={args.arm}: {len(names)} projects to sweep, --limit {args.limit} each: "
          f"{' '.join(names)}", flush=True)
    if args.arm == "full":
        print(f"  lighter 0.1.0 rules: --narrowed-target {NARROWED_TARGET}; --recall-only except "
              f"{' '.join(TIMING_SUBSET)}, which time their first {TIMING_CAP} rows; isolated arm on single-JVM suites and "
              f"{' '.join(ISOLATED_ALSO)}", flush=True)
    if args.arm == "reduced":
        print("  THIS IS A RECALL ARM, NOT THE CORPUS FIGURE. Three projects cannot speak for "
              "breadth, and two figures drawn from different samples may not be quoted "
              "as a series. Publish it as the reduced arm or not at all.",
              flush=True)

    # WHAT THIS SWEEP WILL COST, before it starts; it moves no cap and stops nothing.
    limits = {name: project_limit(os.path.join(BENCH, "projects", f"{name}.toml"), args.limit)
              for name in names}
    projected = cost_projection(names, os.path.dirname(results_file("x", args.set)), limits,
                                qualification_seconds(args.qualification), args.arm,
                                args.ground_truth)
    print(f"  projected: {sum(p['hours'] for p in projected.values()):.1f} h over "
          f"{len(projected)} project(s); " + ", ".join(
              f"{n} {p['hours']} h" for n, p in projected.items()), flush=True)
    with open(SUMMARY, "a", encoding="utf-8") as handle:
        handle.write(json.dumps({"cost_projection": projected, "arm": args.arm}) + "\n")

    summaries = []
    for position, name in enumerate(names, start=1):
        project = os.path.join(BENCH, "projects", f"{name}.toml")
        if not os.path.isfile(project):
            print(f"[{position}/{len(names)}] {name}: no project file, skipped", flush=True)
            continue
        # DEPTH IS A PROPERTY OF THE PROJECT, not of the invocation. Half of all mutation targets
        # break nothing and only a fraction of the rest narrow, so the rows-where-a-miss-was-possible
        # per target ranges from 0.12 on glide to 0.89 on detekt -- one corpus-wide `--limit` buys
        # four times the evidence on one project that it does on another, for the same wall clock.
        # `sweep_limit` in the project file is that project's own depth; `--limit` is the default for
        # a project that has not stated one.
        limit = project_limit(project, args.limit)
        log = os.path.join(SCRATCH, f"sweep-{name}.log")
        print(f"[{position}/{len(names)}] {name} -> {log}  (--limit {limit}"
              f"{', from its project file' if limit != args.limit else ''})", flush=True)
        reap()
        started = time.time()
        with open(log, "w", encoding="utf-8") as handle:
            # NOT check=True. A sweep exits non-zero when nothing it mutated induced a failure,
            # which is a finding about that project and not a reason to abandon the twenty after it.
            code = subprocess.run(
                # RE-CAPTURED BY DEFAULT IN THE REDUCED ARM, because the arm exists to revalidate a
                # SELECTOR CHANGE and a map on disk was captured by the selector being replaced.
                # Rows measured against it would be stamped correctly and still describe the wrong
                # thing. The full arm keeps `--recapture` opt-in: it is usually answering a breadth
                # question against maps it just built.
                shared_arm_command(name, project, limit,
                                   recapture=args.recapture or args.arm == "reduced",
                                   lighter=args.arm == "full", shapes=args.set,
                                   ground_truth=ground_truth_for(name, args.arm,
                                                                 args.ground_truth)),
                stdout=handle, stderr=subprocess.STDOUT, cwd=ROOT,
            ).returncode
        summary = summarise(name, shapes=args.set)
        # ON THE ROW AND NOT ONLY ON THE BANNER. `sweep-summary.jsonl` is a durable log read
        # long after the run that wrote it, and a reduced-arm line that does not say so is a recall
        # figure a later reader can mistake for a corpus one.
        summary["arm"] = args.arm
        summary["exit"] = code
        summary["seconds"] = round(time.time() - started, 1)
        summaries.append(summary)
        with open(SUMMARY, "a", encoding="utf-8") as handle:
            handle.write(json.dumps(summary) + "\n")
        print("    " + json.dumps(summary), flush=True)

        # Not reaped first: the daemon the shared arm warmed is the one its rows were timed in.
        shared_results = results_file(name, args.set)
        if args.no_isolated_arm or not os.path.isfile(shared_results):
            continue
        skip, workers = (isolated_arm_skip_reason(name, shared_results) if args.arm == "full"
                         else (None, None))
        if skip:
            # A LINE, not silence: a project with no isolated row must read as not asked, never as
            # an isolated arm that found nothing.
            summary = {"project": name, "capture_mode": "isolated", "status": skip,
                       "map_workers": workers, "arm": args.arm}
            summaries.append(summary)
            with open(SUMMARY, "a", encoding="utf-8") as handle:
                handle.write(json.dumps(summary) + "\n")
            print(f"[{position}/{len(names)}] {name}: {skip}, {workers} test JVMs", flush=True)
            continue
        isolated_log = os.path.join(SCRATCH, f"sweep-{name}.isolated.log")
        print(f"[{position}/{len(names)}] {name}, isolated arm -> {isolated_log}", flush=True)
        started = time.time()
        with open(isolated_log, "w", encoding="utf-8") as handle:
            code = subprocess.run(
                [sys.executable, os.path.join(BENCH, "run.py"), project, "--isolated-capture",
                 "--pair-with", shared_results, "--recapture"]
                + (["--shapes", args.set] if args.set != run.DEFAULT_SHAPES else []),
                stdout=handle, stderr=subprocess.STDOUT, cwd=ROOT,
            ).returncode
        summary = summarise(name, capture_mode="isolated", shapes=args.set)
        summary["arm"] = args.arm
        summary["exit"] = code
        summary["seconds"] = round(time.time() - started, 1)
        summaries.append(summary)
        with open(SUMMARY, "a", encoding="utf-8") as handle:
            handle.write(json.dumps(summary) + "\n")
        print("    " + json.dumps(summary), flush=True)

    print(f"{args.arm} sweep finished", flush=True)

    # WHAT IT COST, beside what it was projected to: hours per family and per row where
    # a miss was possible, split by ground truth and timing; follow-up re-runs left out.
    reports = []
    for name in dict.fromkeys(s["project"] for s in summaries):
        try:
            with open(results_file(name, args.set), encoding="utf-8") as handle:
                reports.append(cost.report(json.load(handle)))
        except (OSError, ValueError):
            continue
    if reports:
        every = cost.total(reports)
        print(f"  cost: {every['hours']} h, {every['hours_per_miss_possible_row']} h per row where "
              f"a miss was possible (projected {sum(p['hours'] for p in projected.values()):.1f} h)",
              flush=True)
        with open(SUMMARY, "a", encoding="utf-8") as handle:
            handle.write(json.dumps({"cost_report": reports, "cost_total": every,
                                     "arm": args.arm}) + "\n")

    # A SETUP ERROR, NOT A MEASUREMENT. Listed after the sweep so the other projects
    # still ran, and failing it so the setup is fixed and the project re-run from its first row.
    setup_forced = setup_forced_projects(summaries)
    if setup_forced:
        print("\nSETUP-FORCED projects, failed and in no figure; fix each setup and re-run:",
              flush=True)
        for line in setup_forced:
            print(f"  {line}", flush=True)

    # THE GATE. A row that entered no bucket is a row nobody can attribute, and the whole reason
    # this classification exists is that such a row used to be invisible: a refused run recorded
    # `selection-not-requested`, which the forced filter misses and the narrowed comparison never
    # sees, so it was counted nowhere and no count looked wrong.
    # THIS RUN'S SUMMARIES, not every summary ever appended. `sweep-summary.jsonl` is a durable log
    # across invocations, so gating on the file means one bad line from a sweep last week fails
    # every sweep after it, for a corpus it did not measure.
    unclassified = [
        f"{s['project']}: {s['classified']} does not sum to {s['answered_rows']} answered rows; "
        f"{s.get('unclassified_rows')}"
        for s in summaries if s.get("classification_sums") is False
    ]
    # And a corpus that classified nothing is not a corpus that passed. A row whose decision record
    # was absent or discarded leaves the population before the sum sees it, so 0 == 0 is reported by
    # a project whose every record was unreadable and by one with no rows at all.
    silent = [f"{s['project']}: {s['unattributed_rows']} of {s['rows']} rows carry no decision at all"
              for s in summaries
              if s.get("rows") and s.get("unattributed_rows") == s.get("rows")]
    if unclassified or silent:
        print("\nrows that entered no bucket:", flush=True)
        for line in unclassified + silent:
            print(f"  {line}", flush=True)
        return 1
    if setup_forced:
        return 1
    # The plugin broke the build it was a guest in: no test JVM of the selecting arm reported.
    undecided = [f"{s['project']}: {s['invalid_rows']} row(s) left out, {s['invalid_reasons']}"
                 for s in summaries if s.get("invalid_rows")]
    if undecided:
        print("\nrows whose selecting arm decided nothing (invalid, left out of every figure):",
              flush=True)
        for line in undecided:
            print(f"  {line}", flush=True)
        return 1

    # AFTER the classification gate rather than before it, so a zero-evidence arm reports BOTH
    # findings instead of returning on the first. A cheap zero is still a zero that measured
    # nothing: the whole value of this arm is asking the recall question fast, and an arm that
    # asked it of no row is the "0 missed of 0" shape one layer up.
    #
    # COUNTED ON `miss_possible_rows`, not on `narrowed_rows`. Those are different populations and
    # this gate was written against the wrong one -- a row that narrows without breaking anything
    # gave selection nothing to miss, so an arm full of them would have printed a positive figure
    # and passed having asked nothing.
    if args.arm == "reduced":
        # A MEMBER THAT NEVER RAN MEANS THIS IS NOT THE ARM. A missing project file skips a project
        # with a printed line and a `continue`, and three-minus-one projects reported under the
        # arm's name is a different sample wearing it.
        swept = {s["project"] for s in summaries}
        absent = [n for n in REDUCED_ARM if n not in swept]
        if absent:
            print(f"\nREFUSING: {', '.join(absent)} did not run, so this is not the reduced arm "
                  f"and must not be reported as it", flush=True)
            return 1
        asked = sum(s.get("miss_possible_rows") or 0 for s in summaries)
        print(f"\nreduced arm: {asked} row(s) where a miss was possible", flush=True)
        if not asked:
            print("REFUSING: the reduced arm asked no recall question -- no row both narrowed and "
                  "induced a failure, so nothing here is evidence that selection did not skip a "
                  "failing test. This is not a pass.", flush=True)
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
