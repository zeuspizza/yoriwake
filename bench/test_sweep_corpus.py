"""
Tests for the corpus summary's classification gate, with the results files faked.

The output this module is written against is a summary that adds up. Three counts that sum to the
answered total look correct whether or not any row was classified correctly -- and the defect this
gate exists to catch is a row that entered NO bucket, which every count-inside-a-bucket check reads
as a smaller corpus rather than as a missing row.

Run with `python bench/test_sweep_corpus.py`, or under pytest.

Cases are named case_* and RETURN a count of failed checks, following test_run.py: a function that
returns instead of asserting would be collected by pytest and pass however many checks failed. The
single test_* entry point below asserts.
"""

import json
import os
import re
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sweep_corpus  # noqa: E402


def fail(message):
    print(f"  FAIL: {message}")
    return 1


def _results(name, rows, directory, **header):
    """Writes a results file the way run.py writes one, and points the summary at it."""
    os.makedirs(os.path.join(directory, "results"), exist_ok=True)
    path = os.path.join(directory, "results", f"{name}.json")
    with open(path, "w", encoding="utf-8") as handle:
        json.dump({"plugin_commit": "abc1234", "mutations": rows, **header}, handle)
    return path


def _summarise(name, rows, **header):
    with tempfile.TemporaryDirectory() as directory:
        _results(name, rows, directory, **header)
        original = sweep_corpus.BENCH
        sweep_corpus.BENCH = directory
        try:
            return sweep_corpus.summarise(name)
        finally:
            sweep_corpus.BENCH = original


def _row(outcome, **extra):
    row = {"status": "checked", "decision_outcome": outcome,
           "selected_distinct": 1, "full_distinct": 10}
    row.update(extra)
    return row


def case_every_answered_row_lands_in_exactly_one_bucket():
    failures = 0
    summary = _summarise("demo", [
        _row("narrowed"),
        _row("full-run", full_run_kind="map-unusable"),
        _row("full-run", full_run_kind="daemon-refused", refusal_kind="constant-changed"),
    ])
    if summary["classified"] != {"narrowed": 1, "forced": 1, "refused": 1, "requested": 0, "unclassified": 0}:
        failures += fail(f"the three-way split is wrong: {summary['classified']}")
    if not summary["classification_sums"]:
        failures += fail("three rows that add up were reported as not adding up")
    if summary["refusal_kinds"] != {"constant-changed": 1}:
        failures += fail(f"the refusal kinds did not travel: {summary['refusal_kinds']}")
    return failures


def case_a_row_a_changed_environment_refused_is_named_for_the_release_gate():
    failures = 0
    summary = _summarise("demo", [
        _row("narrowed"),
        _row("full-run", full_run_kind="daemon-refused", refusal_kind="environment-changed", target="A.java#1"),
        _row("full-run", full_run_kind="daemon-refused", refusal_kind="environment-unrecorded", target="B.java#1"),
    ])
    if summary["environment_changed_rows"] != ["A.java#1"]:
        failures += fail(f"the environment-changed rows are not listed: {summary['environment_changed_rows']}")
    if _summarise("demo", [_row("narrowed")])["environment_changed_rows"] is not None:
        failures += fail("a sweep with no environment refusal lists one")
    return failures


def case_a_full_run_the_user_asked_for_is_not_a_refusal():
    failures = 0
    summary = _summarise("demo", [
        _row("full-run", full_run_kind="daemon-refused", refusal_kind="full-run-requested"),
        _row("full-run", full_run_kind="daemon-refused", refusal_kind="full-run-commit"),
        _row("full-run", full_run_kind="daemon-refused", refusal_kind="full-run-branch"),
        _row("full-run", full_run_kind="daemon-refused", refusal_kind="decline-undetermined"),
    ])
    if summary["classified"]["requested"] != 3 or summary["classified"]["refused"] != 1:
        failures += fail(f"a requested full run was counted as the tool refusing: {summary['classified']}")
    if not summary["classification_sums"]:
        failures += fail("the requested bucket fell out of the sum")
    if summary["refusal_kinds"] != {"decline-undetermined": 1}:
        failures += fail(f"the refusal breakdown counts requests: {summary['refusal_kinds']}")
    return failures


def case_a_row_in_no_bucket_is_named_rather_than_absorbed():
    failures = 0
    # The defect this gate exists for: before the refusal channel, a run the daemon refused wrote
    # `selection-not-requested`, which the forced filter misses and the narrowed comparison never
    # sees. It was counted nowhere, and no count looked wrong.
    summary = _summarise("demo", [
        _row("narrowed"),
        _row("selection-not-requested", target="the row that used to vanish"),
    ])
    if summary["classification_sums"]:
        failures += fail("a row in no bucket was reported as a corpus that adds up")
    if summary["classified"]["unclassified"] != 1:
        failures += fail(f"the unclassified row was not counted: {summary['classified']}")
    if summary["unclassified_rows"] != ["the row that used to vanish"]:
        failures += fail(f"the row was not named: {summary['unclassified_rows']}")
    return failures


def case_a_refusal_whose_kind_did_not_arrive_is_still_a_refusal():
    failures = 0
    # Version skew, or a fixture that set one property and not the other. Bucketing on the presence
    # of the FINE token put such a row back among the ordinary forced runs -- the population this
    # classification exists to separate -- and the sum still balanced, so the gate saw nothing.
    summary = _summarise("demo", [_row("full-run", full_run_kind="daemon-refused")])
    if summary["classified"]["refused"] != 1:
        failures += fail(f"a kindless daemon refusal was not counted as refused: {summary['classified']}")
    if summary["refusal_kinds"] != {"unnamed": 1}:
        failures += fail(f"the breakdown dropped the kindless refusal: {summary['refusal_kinds']}")
    return failures


def case_a_corpus_that_classified_nothing_is_not_a_corpus_that_passed():
    failures = 0
    # Every row's decision record was absent or discarded, so nothing enters the classified
    # population and the sum reports a clean 0 == 0. That must not print the same as a complete one.
    summary = _summarise("demo", [
        {"status": "checked", "selected_distinct": 1, "full_distinct": 10},
        {"status": "checked", "selected_distinct": 1, "full_distinct": 10},
    ])
    if summary["unattributed_rows"] != 2:
        failures += fail(f"rows with no decision at all were not counted: {summary}")
    if summary["answered_rows"] != 0 or not summary["classification_sums"]:
        failures += fail("the vacuous sum changed shape; the gate reads unattributed_rows for this")
    return failures


def case_a_forced_row_with_no_kind_is_unclassified_not_forced():
    failures = 0
    # "full-run with no kind" is a row nobody can attribute either. Counting it as forced would put
    # the corpus back where it was: 66 of 87 forced rows carrying no usable reason, and the summary
    # reporting a complete forcing distribution over them.
    summary = _summarise("demo", [_row("full-run", target="forced, reason unknown")])
    if summary["classified"]["forced"] != 0:
        failures += fail("a forced row with no kind was counted as attributed")
    if summary["classified"]["unclassified"] != 1:
        failures += fail("a forced row with no kind was not flagged")
    return failures


def case_the_arm_comparison_bucket_keeps_its_own_meaning():
    failures = 0
    # `narrowed_rows` is defined by comparing arms within checked rows; `classified["narrowed"]` is
    # defined by the decision token over answered rows. They are two different keys over two
    # different populations, they disagreed by three rows of fifteen on the one real results file
    # that carried tokens, and every published narrowing figure came from the first.
    summary = _summarise("demo", [
        _row("full-run", full_run_kind="map-unusable", selected_distinct=1, full_distinct=10),
    ])
    if summary["narrowed_rows"] != 1:
        failures += fail("the arm-comparison bucket changed meaning")
    if summary["classified"]["narrowed"] != 0:
        failures += fail("the token-based bucket adopted the arm comparison's answer")
    return failures


def case_a_results_file_with_no_tokens_at_all_sums_vacuously():
    failures = 0
    # An older corpus file carries no decision tokens. There is nothing to classify, and the gate
    # must not fire on that: zero equals zero, and the summary says so rather than claiming a defect.
    summary = _summarise("demo", [{"status": "checked", "selected_distinct": 1, "full_distinct": 10}])
    if not summary["classification_sums"]:
        failures += fail("a file with no decision tokens was reported as failing the gate")
    if summary["answered_rows"] != 0:
        failures += fail(f"rows with no token were counted as answered: {summary['answered_rows']}")
    return failures


# ---- depth: what a published row can say about how hard its project was asked -----------------


def case_the_depth_a_row_was_swept_at_is_reported():
    """The reader rule 4b asks for. Without one the field stops being written and nobody notices."""
    failures = 0
    summary = _summarise("demo", [_row("narrowed", sweep_limit=8), _row("narrowed", sweep_limit=8)],
                         targets_available=1059)
    if summary["sweep_limit"] != 8:
        failures += fail(f"depth reported as {summary['sweep_limit']!r}, expected 8")
    if summary["targets_available"] != 1059:
        failures += fail(f"targets_available {summary['targets_available']!r}, expected 1059")
    if summary["rows_without_a_limit"]:
        failures += fail("rows carrying a limit were counted as unstamped")
    return failures


def case_a_row_that_narrowed_without_breaking_anything_asked_no_recall_question():
    """The P0 this review found: the refusal counted the wrong population.

    `narrowed_rows` counts rows where selection ran less than everything. A recall question also
    needs the mutation to have BROKEN something -- a row that narrowed and induced no failure gave
    selection nothing to miss. An arm made entirely of those would have printed a positive figure
    and passed having asked nothing, which is the shape the refusal exists to catch.
    """
    failures = 0
    summary = _summarise("demo", [
        _row("narrowed", full_failures=0),
        _row("narrowed", full_failures=0),
        _row("narrowed", full_failures=2),
    ])
    if summary["narrowed_rows"] != 3:
        failures += fail(f"narrowed_rows is {summary['narrowed_rows']}, expected 3 -- it counts "
                         f"every narrowed row and must keep doing so")
    if summary["miss_possible_rows"] != 1:
        failures += fail(f"miss_possible_rows is {summary['miss_possible_rows']}, expected 1 -- "
                         f"only the row that both narrowed AND broke something asked the question")
    return failures


def case_the_arm_a_summary_came_from_is_recorded_on_it():
    """`sweep-summary.jsonl` outlives the run, and a recall line that does not say so reads as
    a corpus line to whoever finds it next."""
    failures = 0
    summary = _summarise("demo", [_row("narrowed")])
    # `summarise` itself does not stamp the arm -- main() does, because only main() knows it.
    if "arm" in summary:
        failures += fail("summarise() stamped an arm it cannot know; main() owns that")
    return failures


def case_the_reduced_arm_is_the_stated_three_projects():
    """A fixed list, and the test fails if somebody edits it without editing the ranking with it.

    Recomputing the members per run from current timings would let the sample drift silently
    between sweeps, which is the sampling-bias defect this corpus already withdrew a figure over.
    """
    failures = 0
    if sweep_corpus.REDUCED_ARM != ("ktlint", "resilience4j", "grpc-java"):
        failures += fail(f"the reduced arm changed to {sweep_corpus.REDUCED_ARM} -- update the "
                         f"rows-per-hour ranking beside it, or the list has no justification")
    # THE FIXTURES ARE DELIBERATELY ABSENT. They rank higher on evidence-per-hour and prove the
    # harness rather than a guest, so a recall claim drawn from them is about this repo's own code.
    for fixture in ("demo", "demo-kotlin", "demo-android", "detekt-junk"):
        if fixture in sweep_corpus.REDUCED_ARM:
            failures += fail(f"{fixture} is a fixture and cannot carry a recall claim about a guest")
    return failures


def case_containers_that_never_started_reach_the_summary():
    """The reader the third subtractor needs. It takes failures OUT of the headline.

    A sweep where it fires and nobody sees it is a recall figure that improved without anyone
    deciding it should -- the same shape as `fails_unmutated`, which is why it is counted here.
    """
    failures = 0
    summary = _summarise("demo", [
        _row("narrowed",
             container_failed=["kafka.server.DeleteTopicsRequestTest::initializationError"]),
        _row("narrowed"),
    ])
    if summary["container_failed"] != 1:
        failures += fail(f"container_failed reported as {summary['container_failed']!r}, expected 1")
    return failures


def case_shared_keys_and_each_key_scheme_reach_the_summary():
    """`shared_key` subtracts from the headline, so it is counted; and a file mixing per-method,
    XML and unstamped rows reports three counts, never folding the unstamped into either."""
    failures = 0
    summary = _summarise("demo", [
        _row("narrowed", shared_key=["p.P::v = 1"], result_keys="xml",
             result_keys_fallback="selected arm: listener lines give 3 XML keys, the XML 4"),
        _row("narrowed", result_keys="xml",
             result_keys_fallback="selected arm: listener lines give 7 XML keys, the XML 9"),
        _row("narrowed", result_keys="per-method", result_keys_fallback=None),
        _row("narrowed"),
        {"target": "s", "status": "stalled"},
    ])
    if summary["shared_key"] != 1:
        failures += fail(f"shared_key reported as {summary['shared_key']!r}, expected 1")
    if summary["result_keys"] != {"per-method": 1, "xml": 2, "unstamped": 1}:
        failures += fail(f"result_keys reported as {summary['result_keys']!r}")
    if summary["result_keys_fallbacks"] != {
            "selected arm: listener lines give N XML keys, the XML N": 2}:
        failures += fail(f"fallback causes reported as {summary['result_keys_fallbacks']!r}")
    return failures


def case_the_gap_reads_leaf_ids_and_is_absent_without_them():
    """The record-against-arm gap counts leaf ids; a row written before the leaf count carries no
    gap at all rather than one read off the old count or as zero."""
    failures = 0
    summary = _summarise("demo", [_row("narrowed", decision_included=12,
                                       decision_included_leaves=4, selected_distinct=3)])
    if summary["decision_vs_arm_gap"] != [1, 1]:
        failures += fail(f"the gap is {summary['decision_vs_arm_gap']!r}, expected [1, 1]")
    summary = _summarise("demo", [_row("narrowed", decision_included=12, selected_distinct=3)])
    if summary["decision_vs_arm_gap"] is not None:
        failures += fail(f"a row with no leaf count gave a gap: {summary['decision_vs_arm_gap']!r}")
    return failures


def case_a_resumed_file_is_summarised_as_mixed_rather_than_as_one_depth():
    """`run.py` resumes by skipping measured targets, so one file holds rows from several limits.

    Reporting one of them would be a claim about a sample that does not exist. `mixed` is the
    finding: rows drawn at different depths are not one sample and must not be quoted as one.
    """
    failures = 0
    summary = _summarise("demo", [_row("narrowed", sweep_limit=8),
                                  _row("narrowed", sweep_limit=16)])
    if summary["sweep_limit"] != "mixed":
        failures += fail(f"a two-depth file reported {summary['sweep_limit']!r}, expected 'mixed'")
    if summary["sweep_limits"] != [8, 16]:
        failures += fail(f"both depths did not survive: {summary['sweep_limits']}")
    return failures


def case_a_file_whose_rows_carry_no_limit_says_so_rather_than_reporting_one():
    """A half-stamped file must not report a depth. Absent is not a value."""
    failures = 0
    summary = _summarise("demo", [_row("narrowed"), _row("narrowed", sweep_limit=16)])
    if summary["rows_without_a_limit"] != 1:
        failures += fail(f"{summary['rows_without_a_limit']} unstamped rows counted, expected 1")
    summary = _summarise("demo", [_row("narrowed")])
    if summary["sweep_limit"] is not None:
        failures += fail(f"a file with no depth at all reported {summary['sweep_limit']!r}")
    if summary["targets_available"] is not None:
        failures += fail("an absent targets_available was reported as a value")
    return failures


def case_a_project_file_can_state_its_own_depth():
    """Half of all targets break nothing, so one corpus-wide limit buys different evidence per
    project -- four times as much on detekt as on glide for the same wall clock."""
    failures = 0
    with tempfile.TemporaryDirectory() as directory:
        path = os.path.join(directory, "p.toml")
        with open(path, "w", encoding="utf-8") as handle:
            handle.write('name = "p"\nsweep_limit = 32\n')
        if sweep_corpus.project_limit(path, 16) != 32:
            failures += fail("a project file's own sweep_limit did not override --limit")

        with open(path, "w", encoding="utf-8") as handle:
            handle.write('name = "p"\n')
        if sweep_corpus.project_limit(path, 16) != 16:
            failures += fail("a project with no sweep_limit did not take the corpus default")

        # A typo in one project file must not abandon the twenty projects after it.
        with open(path, "w", encoding="utf-8") as handle:
            handle.write('name = "p"\nsweep_limit = "deep"\n')
        if sweep_corpus.project_limit(path, 16) != 16:
            failures += fail("a non-integer sweep_limit was not ignored")
        with open(path, "w", encoding="utf-8") as handle:
            handle.write("name = not toml at all [[[\n")
        if sweep_corpus.project_limit(path, 16) != 16:
            failures += fail("an unparseable project file did not fall back to the default")
        if sweep_corpus.project_limit(os.path.join(directory, "absent.toml"), 16) != 16:
            failures += fail("a missing project file did not fall back to the default")
    return failures


def case_each_arm_is_summarised_from_its_own_file_with_its_rules():
    """The isolated arm reads `<name>.isolated.json`, and the rule fields reach the summary."""
    failures = 0
    rows = [_row("narrowed", rule_counts={"reaches-change": 2, "shares-jvm-changed-class": 1},
                 rules_basis="complete", shares_jvm_included=1, coverage_only_included=2,
                 decision_included=3, map_capture_mode="isolated", map_schema_version=6,
                 induced_rules={"p.T::a": ["reaches-change"], "p.T::b": ["shares-jvm-changed-class"],
                                "p.T::c": None},
                 selected_arm={"peak_test_jvm_rss_mb": 300.0}, decision_write_failures=0,
                 decision_covers_arm=True),
            _row("full-run", full_run_kind="no-coverage-for-changed", decision_write_failures=2,
                 decision_covers_arm=False, selected_arm={"peak_test_jvm_rss_mb": 410.5})]
    with tempfile.TemporaryDirectory() as directory:
        os.makedirs(os.path.join(directory, "results"))
        with open(os.path.join(directory, "results", "demo.isolated.json"), "w") as handle:
            json.dump({"mutations": rows, "capture_mode": "isolated",
                       "capture_max_parallel_forks": 10, "capture_seconds": 90.0,
                       "baseline_seconds": 30.0,
                       "capture_arm": {"peak_test_jvm_rss_mb": 200.0, "test_jvms": 12},
                       "capture_facts": {"map_bytes": 1000, "map_raw_bytes": 9000},
                       "evidence": {"bytes": 1234}}, handle)
        original = sweep_corpus.BENCH
        sweep_corpus.BENCH = directory
        try:
            shared = sweep_corpus.summarise("demo")
            isolated = sweep_corpus.summarise("demo", capture_mode="isolated")
        finally:
            sweep_corpus.BENCH = original
    if shared.get("status") != "no results file" or shared.get("capture_mode") != "shared":
        failures += fail(f"the shared arm read the isolated arm's file: {shared}")
    expected = {"capture_mode": "isolated", "capture_max_parallel_forks": 10,
                "rule_counts": {"reaches-change": 2, "shares-jvm-changed-class": 1},
                "rows_with_rules": 1, "shares_jvm_included": 1, "coverage_only_included": 2,
                "induced_with_rules": 2, "induced_unseen_by_record": 1,
                "induced_beyond_coverage": 1,
                "induced_sole_rule": {"reaches-change": 1, "shares-jvm-changed-class": 1},
                "decision_write_failure_rows": 1, "decision_short_rows": 1,
                "map_capture_modes": {"isolated": 1, "None": 1}, "map_bytes": 1000,
                "evidence_bytes": 1234}
    for key, value in expected.items():
        if isolated.get(key) != value:
            failures += fail(f"{key}: {isolated.get(key)!r}, expected {value!r}")
    if isolated.get("peak_test_jvm_rss_mb") != {"baseline": None, "capture": 200.0,
                                                 "selected": 410.5, "full": None}:
        failures += fail(f"peak memory per arm misread: {isolated.get('peak_test_jvm_rss_mb')}")
    return failures


def case_a_selecting_arm_that_decided_nothing_is_invalid_not_no_effect():
    """spring-boot's dry run: the test JVM never started, and eight rows were filed `no-effect`."""
    import run  # noqa: PLC0415
    failures = 0
    row = run.undecided_row("A.java::body", "selected", None, set(), 1, "selected-a.log")
    if not row or row.get("status") != "invalid" or "no decision record" not in row.get("invalid_reason", ""):
        failures += fail(f"an arm with no decision record and no test run was not invalid: {row}")
    record = {"notes": {"outcome": "narrowed"}, "included": []}
    if run.undecided_row("A.java::body", "selected", record, set(), 0, "x.log") is not None:
        failures += fail("a run that narrowed to no test was declared invalid; it decided")
    if run.undecided_row("A.java::body", "selected", None, {("p.T", "t")}, 0, "x.log") is not None:
        failures += fail("an arm that ran tests without a record (a non-Platform task) was declared invalid")
    return failures


def case_invalid_rows_leave_every_figure_and_are_counted():
    failures = 0
    invalid = {"status": "invalid", "invalid_reason": "the selecting arm wrote no decision record"}
    summary = _summarise("demo", [_row("narrowed"), invalid, dict(invalid)])
    if summary["rows"] != 1 or summary["invalid_rows"] != 2:
        failures += fail(f"invalid rows were counted as rows: rows={summary['rows']} "
                         f"invalid={summary.get('invalid_rows')}")
    if summary["unattributed_rows"] != 0:
        failures += fail("invalid rows entered the unattributed count, as if a decision had been lost")
    if summary["invalid_reasons"] != ["the selecting arm wrote no decision record"]:
        failures += fail(f"the reason did not reach the summary: {summary['invalid_reasons']}")
    return failures


def case_charts_leave_invalid_rows_out_and_say_why():
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import readme_charts  # noqa: PLC0415
    failures = 0
    with tempfile.TemporaryDirectory() as directory:
        path = _results("demo", [
            _row("narrowed"),
            {"status": "invalid", "invalid_reason": "no decision"},
            {"status": "no-effect", "selected_distinct": 0, "full_distinct": 0},
        ], directory, project="demo")
        projects, skipped = readme_charts.load("shared", [path])
    if len(projects.get("demo", {}).get("rows", [])) != 1:
        failures += fail(f"an invalid row was drawn: {projects}")
    said = readme_charts.left_out(skipped)
    if "2 rows left out" not in said or "1 status invalid" not in said or "1 no test counts" not in said:
        failures += fail(f"the charts did not say how many rows they left out and why: {said}")
    return failures


def _bare_map(directory):
    os.makedirs(directory, exist_ok=True)
    return directory


def case_only_a_map_this_harness_captured_for_this_run_is_reused():
    """The dry run's shape: a map qualify.py captured under an older plugin was measured against."""
    failures = 0
    import sweep_data  # noqa: PLC0415
    wanted = sweep_data.capture_stamp("shared", "5f97c7d", "93b23c40")
    with tempfile.TemporaryDirectory() as root:
        bare = _bare_map(os.path.join(root, "bare"))
        answer, why = sweep_data.stamp_fits(bare, wanted)
        if answer or "qualify.py" not in (why or ""):
            failures += fail(f"a map with no harness stamp was reused: fits={answer}, why={why!r}")
        for key, other in (("plugin_commit", "0ld0000"), ("arm", "isolated"),
                           ("project_commit", "deadbeef")):
            directory = _bare_map(os.path.join(root, key))
            sweep_data.write_capture_stamp(directory, {**wanted, key: other})
            answer, why = sweep_data.stamp_fits(directory, wanted)
            if answer or key not in (why or ""):
                failures += fail(f"a map captured with another {key} was reused: {why!r}")
        own = _bare_map(os.path.join(root, "own"))
        sweep_data.write_capture_stamp(own, wanted)
        if sweep_data.stamp_fits(own, wanted) != (True, None):
            failures += fail("this run's own capture was not reused")
    return failures


def case_the_lighter_sweep_times_only_its_subset():
    """The full arm passes the stopping rule everywhere and `--recall-only` outside the timing
    subset; the reduced arm keeps its own command; both subsets name qualified projects."""
    failures = 0
    timed, untimed = sweep_corpus.TIMING_SUBSET[0], "ktlint"
    for name, recall_only in ((timed, False), (untimed, True)):
        command = sweep_corpus.shared_arm_command(name, "p.toml", 16, recapture=False, lighter=True)
        if ("--recall-only" in command) is not recall_only:
            failures += fail(f"{name}: --recall-only {'missing' if recall_only else 'passed'}")
        cap = command[command.index("--timing-cap") + 1] if "--timing-cap" in command else None
        if cap != (None if recall_only else str(sweep_corpus.TIMING_CAP)):
            failures += fail(f"{name}: timing cap {cap!r}")
        target = command[command.index("--narrowed-target") + 1] \
            if "--narrowed-target" in command else None
        if target != str(sweep_corpus.NARROWED_TARGET):
            failures += fail(f"{name}: narrowed target {target!r}, not {sweep_corpus.NARROWED_TARGET}")
        if command[command.index("--limit") + 1] != "16":
            failures += fail(f"{name}: the cap was not passed: {command}")
    reduced = sweep_corpus.shared_arm_command(untimed, "p.toml", 8, recapture=True, lighter=False)
    if "--recall-only" not in reduced or "--narrowed-target" in reduced or "--recapture" not in reduced:
        failures += fail(f"the reduced arm's command changed: {reduced}")
    names = set(sweep_corpus.qualified(os.path.join(sweep_corpus.BENCH, "qualification.txt")))
    for subset in ("TIMING_SUBSET", "ISOLATED_ALSO"):
        absent = [n for n in getattr(sweep_corpus, subset) if n not in names]
        if absent:
            failures += fail(f"{subset} names projects that are not QUALIFIED: {absent}")
    return failures


def case_the_isolated_arm_runs_on_single_jvm_suites_and_the_override():
    failures = 0
    override = sweep_corpus.ISOLATED_ALSO[0]
    with tempfile.TemporaryDirectory() as directory:
        cases = (
            ("one-jvm", {"capture_facts": {"map_task_facts": {"workers": 1}}}, None),
            ("forked", {"capture_facts": {"map_task_facts": {"workers": 15}}},
             "isolated arm: not run (multi-JVM suite)"),
            ("unrecorded", {"capture_facts": None}, "isolated arm: not run (test JVM count unknown)"),
            (override, {"capture_facts": {"map_task_facts": {"workers": 15}}}, None),
        )
        for name, header, expected in cases:
            path = _results(name, [], directory, **header)
            reason, _ = sweep_corpus.isolated_arm_skip_reason(name, path)
            if reason != expected:
                failures += fail(f"{name}: {reason!r}, expected {expected!r}")
        reason, _ = sweep_corpus.isolated_arm_skip_reason(
            "absent", os.path.join(directory, "absent.json"))
        if reason != "isolated arm: not run (test JVM count unknown)":
            failures += fail(f"an unreadable shared file ran the isolated arm: {reason!r}")
    return failures


def case_the_summary_states_the_stopping_rule_each_row_was_drawn_under():
    failures = 0
    rows = [_row("narrowed", narrowed_target=8, full_failures=1),
            _row("full-run", narrowed_target=8, selected_distinct=10, full_failures=1),
            _row("narrowed", full_failures=1)]
    summary = _summarise("demo", rows, recall_only=True)
    expected = {"narrowed_target": 8, "narrowed_targets": [8], "rows_without_a_narrowed_target": 1,
                "narrowed_target_rows": 2, "recall_only": True}
    for key, value in expected.items():
        if summary.get(key) != value:
            failures += fail(f"{key}: {summary.get(key)!r}, expected {value!r}")
    mixed = _summarise("demo", [_row("narrowed", narrowed_target=8),
                                _row("narrowed", narrowed_target=4)])
    if mixed.get("narrowed_target") != "mixed":
        failures += fail(f"rows under two stopping rules read as one: {mixed.get('narrowed_target')!r}")
    return failures


def case_repeated_skip_and_only_accumulate():
    failures = 0
    args = sweep_corpus.argument_parser().parse_args(
        ["--skip", "kafka-core", "--skip", "anki-android", "arrow", "--only", "a", "--only", "b"])
    if args.skip != ["kafka-core", "anki-android", "arrow"]:
        failures += fail(f"repeated --skip kept {args.skip!r}")
    if args.only != ["a", "b"]:
        failures += fail(f"repeated --only kept {args.only!r}")
    if sweep_corpus.argument_parser().parse_args([]).skip != []:
        failures += fail("no --skip is not an empty list")
    return failures


def case_a_setup_forced_project_is_named_with_its_reason_and_fails_the_sweep():
    """The sweep names each setup-forced project and its reason, and its rows are no figure."""
    import run  # noqa: PLC0415
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import readme_charts  # noqa: PLC0415
    failures = 0
    reason = "changed paths the coverage map cannot see: core/derby.log"
    stopped = {"project_outcome": run.SETUP_FORCED,
               "setup_forced": {"reason": reason, "at": "rows", "rows": []}}
    summary = _summarise("iceberg", [_row("full-run")], **stopped)
    measured = _summarise("demo", [_row("narrowed")], project_outcome="measured")
    named = sweep_corpus.setup_forced_projects([summary, measured])
    if named != [f"iceberg: {reason}"]:
        failures += fail(f"the setup-forced projects named are {named}")
    with tempfile.TemporaryDirectory() as directory:
        path = _results("iceberg", [_row("full-run")], directory, project="iceberg", **stopped)
        projects, skipped = readme_charts.load("shared", [path])
    if projects or "setup-forced" not in readme_charts.left_out(skipped):
        failures += fail(f"a setup-forced project's rows were drawn: {projects}, {skipped}")
    return failures


def case_a_time_chart_states_its_cap_and_never_pools_caps_silently():
    """Chart 2 draws timed rows only, says its cap, and refuses capped beside uncapped."""
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import readme_charts  # noqa: PLC0415
    failures = 0
    timed_row = _row("narrowed", selected_seconds=5.0, full_seconds=10.0, timed=True)
    untimed_row = _row("narrowed", selected_seconds=10.0, full_seconds=10.0, timed=False)
    with tempfile.TemporaryDirectory() as directory:
        capped = _results("grpc-java", [timed_row, untimed_row], directory, project="grpc-java",
                          timing_cap=8, recall_only=False, baseline_seconds=6)
        uncapped = _results("mockito", [_row("narrowed", selected_seconds=1.0, full_seconds=2.0)],
                            directory, project="mockito", recall_only=False, baseline_seconds=40)
        projects, _ = readme_charts.load("shared", [capped])
        both, _ = readme_charts.load("shared", [capped, uncapped])
    if projects["grpc-java"].get("timing_cap") != 8 or both["mockito"].get("timing_cap") is not None:
        failures += fail("a file's timing cap was not read, or a file without one read as capped")
    if readme_charts.timing_caps_note(projects, pool=False) != " (first 8 rows per project)":
        failures += fail(f"a capped chart says {readme_charts.timing_caps_note(projects, False)!r}")
    try:
        readme_charts.timing_caps_note(both, pool=False)
        failures += fail("capped and uncapped files were pooled without saying so")
    except SystemExit:
        pass
    if "uncapped" not in readme_charts.timing_caps_note(both, pool=True):
        failures += fail("a pooled chart does not state that it pools uncapped files")
    svg = readme_charts.time_chart(projects, "light", "")
    if "+50%" not in svg:
        failures += fail("the time chart drew an untimed row's ground-truth arm into the median")
    return failures


def case_a_time_chart_of_ci_rows_quotes_no_absolute_time_and_no_ratio_across_jobs():
    """CI runners are shared: a ratio of two arms from one job is a measurement, an absolute time
    or a ratio across jobs is not."""
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import readme_charts  # noqa: PLC0415
    failures = 0
    job = {"runner": "GitHub Actions 3", "run": "9", "attempt": "1", "job": "shard"}
    other = dict(job, runner="GitHub Actions 4")
    one_job = _row("narrowed", selected_seconds=5.0, full_seconds=10.0, timed=True, ci=job)
    two_jobs = _row("narrowed", selected_seconds=1.0, full_seconds=10.0, timed=True, ci=job,
                    full_ci=other)
    with tempfile.TemporaryDirectory() as directory:
        path = _results("grpc-java", [one_job, two_jobs], directory, project="grpc-java",
                        recall_only=False, baseline_seconds=777)
        projects, _ = readme_charts.load("shared", [path])
    svg = readme_charts.time_chart(projects, "light", "")
    if "median +50%" not in svg:
        failures += fail("a ratio whose arms ran in two jobs was drawn into the median")
    if "1 row timed across two jobs" not in svg:
        failures += fail("the chart did not say it left out a row timed across two jobs")
    if re.search(r"\d+s<|\d+s,", svg) or "777" in svg:
        failures += fail("the chart quoted an absolute time measured on a shared runner")
    if "tests" not in svg:
        failures += fail("CI rows were not placed by suite size in tests")
    return failures


def case_the_charts_refuse_a_control_row():
    """A planted-miss control's rows measured a plugin with a defect put in on purpose."""
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import readme_charts  # noqa: PLC0415
    with tempfile.TemporaryDirectory() as directory:
        path = _results("demo", [_row("narrowed", control=True)], directory, project="demo")
        try:
            readme_charts.load("shared", [path])
        except SystemExit:
            return 0
    return fail("the charts drew a planted-miss control row")


def case_the_reduced_arm_compares_on_full_arm_truth():
    """The reduced arm runs the complement beside the full arm; timed rows stay full."""
    failures = 0
    for name in sweep_corpus.REDUCED_ARM:
        truth = sweep_corpus.ground_truth_for(name, "reduced", "full")
        command = sweep_corpus.shared_arm_command(name, "p.toml", 8, recapture=True, lighter=False,
                                                  ground_truth=truth)
        given = command[command.index("--ground-truth") + 1] if "--ground-truth" in command else None
        if given != "check":
            failures += fail(f"{name}: the reduced arm runs {command}")
        timed = name in sweep_corpus.TIMING_SUBSET
        if ("--timing-cap" in command) is not timed or ("--recall-only" in command) is timed:
            failures += fail(f"{name}: the reduced arm's timing is {command}")
    if sweep_corpus.ground_truth_for("ktlint", "full", "full") != "full":
        failures += fail("the full arm runs a complement nobody asked for")
    if "--ground-truth" in sweep_corpus.shared_arm_command("ktlint", "p.toml", 8, False, True):
        failures += fail("a full-arm-truth command passes --ground-truth")
    paired = sweep_corpus.ISOLATED_ALSO[0]
    if sweep_corpus.ground_truth_for(paired, "full", "auto") != "check":
        failures += fail("a project whose isolated arm pairs took complement-only rows")
    return failures


def case_the_cost_projection_moves_no_cap():
    """The projection is printed and summarised, never consulted for a limit."""
    failures = 0
    with tempfile.TemporaryDirectory() as directory:
        _results("grpc-java", [_row("narrowed", selected_seconds=9e6, full_seconds=9e6,
                                    status="checked")], directory, project="grpc-java",
                 baseline_seconds=9e6, capture_seconds=9e6)
        table = os.path.join(directory, "qualification.txt")
        with open(table, "w", encoding="utf-8") as handle:
            handle.write("QUALIFIED  ktlint  module=x blockers=none seconds=40.6 test_seconds=45 "
                         "tests=9\n")
        seconds = sweep_corpus.qualification_seconds(table)
        projected = sweep_corpus.cost_projection(["grpc-java", "ktlint", "absent"],
                                                 os.path.join(directory, "results"),
                                                 {"grpc-java": 16, "ktlint": 8, "absent": 8},
                                                 seconds, "full", "full")
    if seconds != {"ktlint": 40.6}:
        failures += fail(f"qualification seconds read as {seconds}")
    if set(projected) != {"grpc-java", "ktlint"} or projected["grpc-java"]["timed_rows"] != 8:
        failures += fail(f"the projection is {projected}")
    if projected["grpc-java"]["hours"] < 1000:
        failures += fail("a project whose rows cost days was not projected so")
    if sweep_corpus.shared_arm_command("grpc-java", "p.toml", 16, False, True)[4] != "16":
        failures += fail("a huge projection moved the project's cap")
    return failures


def case_the_workflow_reads_each_project_s_flags_from_the_sweep_itself():
    import contextlib  # noqa: PLC0415
    import io  # noqa: PLC0415
    failures = 0
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        code = sweep_corpus.main(["--arm", "reduced", "--print-commands"])
    try:
        plan = {entry["project"]: entry["args"] for entry in json.loads(out.getvalue())}
    except ValueError:
        return fail(f"--print-commands printed more than JSON: {out.getvalue()[:200]!r}")
    if code != 0 or sorted(plan) != sorted(sweep_corpus.REDUCED_ARM):
        failures += fail(f"the reduced arm planned {sorted(plan)}, exit {code}")
    grpc = plan.get("grpc-java") or []
    if "--timing-cap" not in grpc or any("--ground-truth" not in a for a in plan.values()):
        failures += fail(f"the reduced arm's rules were not carried: {plan}")
    if any(arg.startswith("/") for args in plan.values() for arg in args):
        failures += fail("a planned command names a path of this machine")
    return failures


def test_sweep_corpus():
    failures = 0
    for name, case in sorted(globals().items()):
        if name.startswith("case_"):
            print(name)
            failures += case()
    assert failures == 0, f"{failures} failed checks"


if __name__ == "__main__":
    test_sweep_corpus()
    print("\nall checks passed")
