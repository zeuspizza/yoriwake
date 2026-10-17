"""Tests for the recall gate.

The gate decides whether a sweep's "0 missed" means anything. An untested gate is the same shape as
an untested selector: it reports success and nobody knows whether it could ever have reported
anything else. Every test here is written so that it FAILS if the gate stops being able to fail.

Run: python -m pytest bench/test_assert_recall.py
"""

import json
import os
import subprocess
import sys
import tempfile
import unittest

# Imported as well as driven as a subprocess: the gate's behaviour is tested end to end by
# `run_gate`, but `narrowed_at_all` and `declined_to_narrow` are public API with a second
# consumer in `corpus_state`, and a subprocess cannot exercise them directly.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import assert_recall  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
GATE = os.path.join(HERE, "assert_recall.py")


def mutation(**overrides):
    """One 'checked' mutation that missed nothing, as the harness writes it."""
    entry = {
        "target": "src/main/java/A.java::body::f",
        "kind": "body",
        "status": "checked",
        "full_distinct": 9,
        "full_failures": 2,
        "selected_distinct": 3,
        "missed": [],
        "ran_but_passed": [],
        "selected_seconds": 1.0,
        "full_seconds": 2.0,
    }
    entry.update(overrides)
    return entry


def run_gate(results):
    """Runs the gate over a results object and returns (exit code, output)."""
    with tempfile.TemporaryDirectory() as directory:
        path = os.path.join(directory, "results.json")
        with open(path, "w", encoding="utf-8") as handle:
            if isinstance(results, str):
                handle.write(results)
            else:
                json.dump(results, handle)
        finished = subprocess.run(
            [sys.executable, GATE, path],
            capture_output=True,
            text=True,
        )
        return finished.returncode, finished.stdout + finished.stderr


class AFigureHoldsOnePopulation(unittest.TestCase):
    """A history replay's rows count toward no recall figure, so a file mixing them is refused."""

    def test_history_rows_among_mutation_rows_are_refused(self):
        code, out = run_gate({"project": "p", "mutations": [
            mutation(), mutation(target="src/main/java/B.java::history::abc", kind="history")]})
        self.assertEqual(code, 1, out)
        self.assertIn("history", out)

    def test_a_replay_alone_is_still_read(self):
        code, out = run_gate({"project": "p", "mutations": [
            mutation(target="x::history::abc", kind="history")]})
        self.assertNotIn("mixes", out)
        self.assertEqual(code, 0, out)


class ForcedFullRunsAreNotNarrowing(unittest.TestCase):
    """A recorded reason means the selector declined; the counts do not get a vote."""

    def test_a_forced_full_run_with_uneven_counts_is_not_narrowing(self):
        # kestra reported "1 of 4 narrowed, ran 99.8% of the suite" for a forced full run whose two
        # arms executed one different test. That invented figure was published as evidence that
        # kestra's coverage is too dense to discriminate, which the map does not support.
        code, out = run_gate({"project": "kestra", "mutations": [
            mutation(selected_distinct=663, full_distinct=664,
                     full_run_reason="the map has no coverage for io.kestra.core.utils.GroupValue"),
        ]})
        self.assertEqual(0, code, out)
        self.assertIn("0 of 1 changes ran less than everything", out)
        self.assertIn("GroupValue", out)

    def test_a_genuine_narrowing_still_counts(self):
        code, out = run_gate({"project": "detekt", "mutations": [
            mutation(selected_distinct=114, full_distinct=2259),
        ]})
        self.assertEqual(0, code, out)
        self.assertIn("1 of 1 changes ran less than everything", out)


class UndeterminedRows(unittest.TestCase):
    """Neither missed nor clean, and it needs a reader here or it stops being written."""

    def test_undetermined_rows_are_named_in_the_report(self):
        code, out = run_gate({"project": "p", "mutations": [
            mutation(full_failures=3, undetermined=["C::[1] x=Stub@7b50d62"]),
            mutation(full_failures=2),
        ]})
        self.assertEqual(0, code, out)
        self.assertIn("UNDETERMINED", out)
        self.assertIn("C::[1] x=Stub@7b50d62", out)
        self.assertIn("NOT", out)

    def test_a_sweep_that_could_answer_nothing_fails(self):
        # Every answered row undetermined is "no row asked a recall question" one layer in: the rows
        # exist, they induced failures, and nothing was established about any of them. A gate that
        # printed "0 missed" there would be reporting a result it does not have.
        code, out = run_gate({"project": "p", "mutations": [
            mutation(full_failures=3, undetermined=["C::[1] x=Stub@a1b2c3d4"]),
        ]})
        self.assertEqual(1, code, out)
        self.assertIn("establishes nothing about recall", out)

    def test_a_sweep_with_no_undetermined_rows_is_unaffected(self):
        code, out = run_gate({"project": "p", "mutations": [mutation(full_failures=3)]})
        self.assertEqual(0, code, out)
        self.assertIn("0 undetermined", out)


class NondeterministicRows(unittest.TestCase):
    """A test that answered its own isolated re-run two different ways.

    A second kind of "neither missed nor clean", kept apart from `undetermined` because the cause
    line differs: these were named identically by both runs, and it is the test that will not
    repeat itself. Reported and not gated -- failing on flakiness trains people to ignore the job --
    but never silently folded into the clean count.
    """

    def test_nondeterministic_rows_are_named_in_the_report(self):
        code, out = run_gate({"project": "p", "mutations": [
            mutation(full_failures=3, nondeterministic=["C::flakyTls"]),
            mutation(full_failures=2),
        ]})
        self.assertEqual(0, code, out)
        self.assertIn("NONDETERMINISTIC", out)
        self.assertIn("C::flakyTls", out)
        self.assertIn("1 nondeterministic", out)

    def test_the_cause_line_is_not_the_identity_hash_one(self):
        """The `undetermined` cause line names an identity hash, which is false of these rows."""
        code, out = run_gate({"project": "p", "mutations": [
            mutation(full_failures=3, nondeterministic=["C::flakyTls"]),
            mutation(full_failures=2),
        ]})
        self.assertEqual(0, code, out)
        self.assertIn("identical isolated re-runs", out)
        self.assertNotIn("an identity hash in the display name", out)

    def test_a_sweep_that_could_only_report_flakiness_fails(self):
        """Every answered row reclassified is a sweep that established nothing, same as above."""
        code, out = run_gate({"project": "p", "mutations": [
            mutation(full_failures=3, nondeterministic=["C::flakyTls"]),
        ]})
        self.assertEqual(1, code, out)
        self.assertIn("establishes nothing about recall", out)

    def test_a_sweep_with_no_nondeterministic_rows_is_unaffected(self):
        code, out = run_gate({"project": "p", "mutations": [mutation(full_failures=3)]})
        self.assertEqual(0, code, out)
        self.assertIn("0 nondeterministic", out)


class ReplayExemption(unittest.TestCase):
    """A replay measures narrowing on real commits, which are not engineered to break anything."""

    def test_a_replay_that_broke_nothing_still_passes(self):
        code, out = run_gate({"project": "detekt", "mutations": [
            mutation(kind="history", status="no-effect", full_failures=0,
                     selected_distinct=3, full_distinct=100),
        ]})
        self.assertEqual(0, code, out)
        self.assertIn("replay", out)

    def test_a_replay_that_missed_a_failure_still_fails(self):
        """The exemption is for the nothing-broke gate only. Recall is never exempt."""
        code, out = run_gate({"project": "detekt", "mutations": [
            mutation(kind="history", full_failures=1, missed=["dev.ATest::f"]),
        ]})
        self.assertEqual(1, code, out)
        self.assertIn("MISSED", out)

    def test_a_mutation_sweep_that_broke_nothing_still_fails(self):
        """The exemption must not leak to the corpus it was written to keep honest."""
        code, out = run_gate({"project": "demo", "mutations": [
            mutation(kind="body", status="no-effect", full_failures=0),
        ]})
        self.assertEqual(1, code, out)
        self.assertIn("no mutation broke anything", out)

    def test_a_mixed_result_is_not_exempt(self):
        code, out = run_gate({"project": "demo", "mutations": [
            mutation(kind="history", status="no-effect", full_failures=0),
            mutation(kind="body", status="no-effect", full_failures=0),
        ]})
        self.assertEqual(1, code, out)


class NarrowingReport(unittest.TestCase):
    """The narrowing rate is reported, never gated.

    It answers whether the tool is worth its capture cost, which is an economic question. Failing a
    build on it would conflate that with the safety question the rest of this gate exists for.
    """

    def test_it_reports_how_often_and_how_far_selection_narrowed(self):
        code, out = run_gate({"project": "demo", "mutations": [
            mutation(selected_distinct=1, full_distinct=100),
            mutation(selected_distinct=5, full_distinct=100),
            mutation(selected_distinct=100, full_distinct=100),
        ]})
        self.assertEqual(0, code, out)
        self.assertIn("2 of 3 changes ran less than everything", out)
        self.assertIn("1.0% to 5.0%", out)

    def test_a_sweep_that_narrowed_nothing_still_passes(self):
        """Disappointing is not broken. Only a missed failure fails this gate."""
        code, out = run_gate({"project": "demo", "mutations": [
            mutation(selected_distinct=100, full_distinct=100),
        ]})
        self.assertEqual(0, code, out)
        self.assertIn("0 of 1 changes ran less than everything", out)

    def test_a_finding_without_counts_is_not_counted_as_narrowing(self):
        """An absent number must never read as a saving -- the rule the whole project rests on."""
        entry = mutation()
        del entry["selected_distinct"]
        code, out = run_gate({"project": "demo", "mutations": [entry]})
        self.assertEqual(0, code, out)
        self.assertIn("no finding carried both counts", out)

    def test_a_full_run_with_no_recorded_reason_is_called_out(self):
        """The case that cannot be investigated later is the one worth naming."""
        code, out = run_gate({"project": "demo", "mutations": [
            mutation(selected_distinct=100, full_distinct=100, full_run_reason=None),
        ]})
        self.assertEqual(0, code, out)
        self.assertIn("no reason recorded", out)

    def test_identical_full_run_reasons_are_grouped(self):
        code, out = run_gate({"project": "demo", "mutations": [
            mutation(selected_distinct=9, full_distinct=9, full_run_reason="a resource changed"),
            mutation(selected_distinct=9, full_distinct=9, full_run_reason="a resource changed"),
        ]})
        self.assertEqual(0, code, out)
        self.assertIn("x2 full run: a resource changed", out)


    def test_the_median_is_the_middle_of_an_even_length_list(self):
        """fractions[len//2] takes the upper of the two middles, overstating typical narrowing."""
        code, out = run_gate({"project": "demo", "mutations": [
            mutation(selected_distinct=10, full_distinct=100),
            mutation(selected_distinct=20, full_distinct=100),
        ]})
        self.assertEqual(0, code, out)
        self.assertIn("median 15.0%", out)

    def test_a_finding_without_a_target_does_not_crash_the_gate(self):
        """report_narrowing runs over every finding, not just the well-formed ones."""
        entry = mutation(selected_distinct=9, full_distinct=9)
        del entry["target"]
        code, out = run_gate({"project": "demo", "mutations": [entry, mutation()]})
        self.assertEqual(0, code, out)
        self.assertIn("full run:", out)


class NarrowingSummaryFile(unittest.TestCase):
    """The narrowing rate is written as data, not only printed, so CI can watch it."""

    def test_it_writes_a_summary_when_a_path_is_given(self):
        with tempfile.TemporaryDirectory() as directory:
            results = os.path.join(directory, "results.json")
            summary = os.path.join(directory, "summary.json")
            with open(results, "w", encoding="utf-8") as handle:
                json.dump({"project": "demo", "mutations": [
                    mutation(selected_distinct=1, full_distinct=100),
                    mutation(selected_distinct=100, full_distinct=100),
                ]}, handle)
            finished = subprocess.run(
                [sys.executable, GATE, results, summary], capture_output=True, text=True)
            self.assertEqual(0, finished.returncode, finished.stdout + finished.stderr)
            with open(summary, encoding="utf-8") as handle:
                written = json.load(handle)
            self.assertEqual(2, written["narrowing"]["measured"])
            self.assertEqual(1, written["narrowing"]["narrowed"])

    def test_no_summary_is_written_when_no_path_is_given(self):
        code, out = run_gate({"project": "demo", "mutations": [mutation()]})
        self.assertEqual(0, code, out)


class LogSlug(unittest.TestCase):
    """Per-target log names must stay distinct, or a finding loses the log that explains it."""

    def test_labels_that_collide_after_truncation_still_get_distinct_stems(self):
        import importlib.util
        spec = importlib.util.spec_from_file_location("run", os.path.join(HERE, "run.py"))
        run = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(run)
        shared = "src/main/java/com/example/deeply/nested/package/AVeryLongClassName.java"
        first = run.log_slug(shared + "::body::alpha")
        second = run.log_slug(shared + "::body::beta")
        # The visible tails are identical once truncated to 60 characters; only the hash of the FULL
        # label separates them, which is why the hash is there.
        self.assertNotEqual(first, second)


class RecallGate(unittest.TestCase):

    def test_a_clean_sweep_passes(self):
        code, out = run_gate({"project": "demo", "mutations": [mutation()]})
        self.assertEqual(0, code, out)
        self.assertIn("OK", out)

    def test_a_missed_failure_fails(self):
        """The whole point. If this ever passes, the gate is decoration."""
        code, out = run_gate({
            "project": "demo",
            "mutations": [mutation(missed=["dev.ATest::f"])],
        })
        self.assertEqual(1, code, out)
        self.assertIn("MISSED", out)
        self.assertIn("dev.ATest::f", out)

    def test_a_sweep_where_nothing_broke_fails(self):
        """'0 missed' from mutations that broke nothing asked no question at all.

        This is the silent-skip shape one level up: a green tick for having measured nothing.
        """
        code, out = run_gate({
            "project": "demo",
            "mutations": [mutation(status="no-effect", full_failures=0, missed=[])],
        })
        self.assertEqual(1, code, out)
        self.assertIn("no mutation broke anything", out)

    def test_an_empty_corpus_fails(self):
        code, out = run_gate({"project": "demo", "mutations": []})
        self.assertEqual(1, code, out)

    def test_a_results_file_with_no_mutations_key_fails(self):
        """Schema drift must not be read as a clean sweep."""
        code, out = run_gate({"project": "demo"})
        self.assertEqual(1, code, out)

    def test_a_checked_mutation_with_no_missed_key_fails(self):
        """The subtlest one: dict.get returns None, which is falsy, which reads as 'nothing missed'.

        A harness that stopped writing the key would silently turn this gate green.
        """
        entry = mutation()
        del entry["missed"]
        code, out = run_gate({"project": "demo", "mutations": [entry]})
        self.assertEqual(1, code, out)
        self.assertIn("MALFORMED", out)

    def test_truncated_json_fails_loudly(self):
        code, out = run_gate('{"project": "demo", "mutations": [')
        self.assertNotEqual(0, code, out)

    def test_ran_but_passed_is_reported_but_does_not_fail(self):
        """Selected and executed is not a skip.

        The test ran; it passed because something it depends on did not run first, or because it is
        flaky. Failing the build on flakiness would train people to ignore this job, so it is
        reported and the gate stays green.
        """
        code, out = run_gate({
            "project": "demo",
            "mutations": [mutation(ran_but_passed=["dev.BTest::g"])],
        })
        self.assertEqual(0, code, out)
        self.assertIn("ran but did not fail", out)
        self.assertIn("dev.BTest::g", out)


class ContainerFailures(unittest.TestCase):
    """A class that never started is neither missed nor clean, and is reported not gated."""

    def test_container_failures_are_named_and_do_not_fail_the_gate(self):
        code, out = run_gate({"project": "p", "mutations": [
            mutation(container_failed=["kafka.server.DeleteTopicsRequestTest::initializationError"]),
        ]})
        self.assertEqual(0, code, out)
        self.assertIn("CONTAINER FAILED, neither missed nor clean", out)
        self.assertIn("DeleteTopicsRequestTest", out)

    def test_the_cause_line_says_it_is_the_harness_that_cannot_decide(self):
        _, out = run_gate({"project": "p", "mutations": [
            mutation(container_failed=["A::initializationError"]),
        ]})
        self.assertIn("cannot decide", out)

    def test_a_sweep_whose_every_failure_was_set_aside_establishes_nothing(self):
        # `full_failures` counts containers and tests that fail unmutated too, so a row whose only
        # failures were those broke nothing selection could have skipped.
        code, out = run_gate({"project": "p", "mutations": [
            mutation(full_failures=1, container_failed=["A::initializationError"]),
            mutation(full_failures=1, fails_unmutated=["B::alwaysRed"]),
        ]})
        self.assertEqual(1, code, out)
        self.assertIn("establishes nothing", out)

    def test_a_real_miss_on_another_row_still_fails(self):
        code, out = run_gate({"project": "p", "mutations": [
            mutation(container_failed=["A::initializationError"]),
            mutation(missed=["B::realTest"]),
        ]})
        self.assertEqual(1, code, out)
        self.assertIn("MISSED", out)


class SharedKeys(unittest.TestCase):
    """A failure on a key another test may stand for: named, neither missed nor clean."""

    def test_a_row_whose_only_failures_share_a_key_is_neither(self):
        code, out = run_gate({"project": "p", "mutations": [
            mutation(full_failures=1, shared_key=["p.P::v = 1"]),
        ]})
        self.assertEqual(1, code, out)
        self.assertIn("SHARED KEY, neither missed nor clean", out)
        self.assertIn("may stand for a test other than the one that failed", out)
        self.assertIn("establishes nothing", out)

    def test_a_shared_key_is_not_counted_as_answered(self):
        row = mutation(full_failures=3, shared_key=["p.P::v = 1"])
        self.assertEqual(2, assert_recall.answered_failures(row))
        self.assertTrue(assert_recall.established_nothing(row))


class RejectedMutations(unittest.TestCase):
    """The compile gate's count, and the ceiling that stops it being a silent discard."""

    def test_a_sweep_whose_only_defect_was_rejections_still_passes(self):
        """A rejection costs no row and fails nothing. That is the whole point of the gate."""
        code, out = run_gate({"project": "p", "mutations_rejected": 1,
                              "rejected_targets": ["A.java::constant::X"],
                              "mutations": [mutation() for _ in range(16)]})
        self.assertEqual(0, code, out)
        self.assertIn("rejected by the compile gate", out)

    def test_the_rejected_targets_are_named(self):
        """A count says how bad; the ids say which shape, and the shape is what gets fixed."""
        _, out = run_gate({"project": "p", "mutations_rejected": 1,
                           "rejected_targets": ["A.java::constant::SERIAL_VERSION_UID"],
                           "mutations": [mutation() for _ in range(16)]})
        self.assertIn("SERIAL_VERSION_UID", out)

    def test_a_rejection_rate_above_the_ceiling_fails(self):
        """mockito's dropped long suffix: 5 of 16. The sample was shaped by the grammar."""
        code, out = run_gate({"project": "p", "mutations_rejected": 5,
                              "rejected_targets": [f"A.java::constant::C{i}" for i in range(5)],
                              "mutations": [mutation() for _ in range(11)]})
        self.assertEqual(1, code, out)
        self.assertIn("above the", out)

    def test_a_revert_file_above_the_ceiling_passes_and_names_its_rejections(self):
        """Reverts have no shared ceiling: a real commit the gate rejects is counted instead."""
        code, out = run_gate({"project": "p", "target_shapes": "revert/v1",
                              "mutations_rejected": 5,
                              "rejected_targets": [f"revert::{i:040x}" for i in range(5)],
                              "mutations": [mutation() for _ in range(11)]})
        self.assertEqual(0, code, out)
        self.assertIn("5 of 16 mutations were rejected", out)

    def test_a_named_mutation_set_above_the_ceiling_still_fails(self):
        """The exemption is the revert set's alone."""
        code, out = run_gate({"project": "p", "target_shapes": "recall-arm",
                              "mutations_rejected": 5,
                              "rejected_targets": [f"A.java::constant::C{i}" for i in range(5)],
                              "mutations": [mutation() for _ in range(11)]})
        self.assertEqual(1, code, out)
        self.assertIn("above the", out)
    def pool(self, refused_rows, other_rows):
        """A `pool/v1` file whose throw-entry family is refused: 5 of 7 draws rejected."""
        return {"project": "p", "target_shapes": "pool/v1", "mutations_rejected": 5,
                "rejected_mutations": [[f"A.java::throw-entry::f{i}", "throw-entry", i]
                                       for i in range(5)],
                "mutations": refused_rows + other_rows}

    def test_a_refused_family_is_named_and_the_others_still_stand(self):
        code, out = run_gate(self.pool([mutation(kind="throw-entry")] * 2,
                                       [mutation(kind="void-return")] * 20))
        self.assertEqual(0, code, out)
        self.assertIn("REFUSED family throw-entry", out)
        self.assertIn("2 row(s) of refused families", out)

    def test_a_refused_familys_failures_do_not_pass_the_nothing_broke_gate(self):
        """Its rows are not read, so failures only they induced tested nothing."""
        code, out = run_gate(self.pool([mutation(kind="throw-entry")] * 2,
                                       [mutation(kind="void-return", full_failures=0,
                                                 status="no-effect")] * 20))
        self.assertEqual(1, code, out)
        self.assertIn("no mutation broke anything", out)

    def test_a_miss_in_a_refused_family_still_fails(self):
        """A test that should have run and did not is a defect whatever shaped the sample."""
        code, out = run_gate(self.pool([mutation(kind="throw-entry", missed=["k.ATest::a"])],
                                       [mutation(kind="void-return")] * 20))
        self.assertEqual(1, code, out)
        self.assertIn("MISSED", out)

    def test_a_build_failed_row_still_fails_beside_rejections(self):
        """The gate discards non-compiling mutations; it does not soften a real broken build."""
        code, out = run_gate({"project": "p", "mutations_rejected": 1,
                              "rejected_targets": ["A.java::constant::X"],
                              "mutations": [mutation(), {"target": "B", "status": "build-failed"}]})
        self.assertEqual(1, code, out)
        self.assertIn("BUILD FAILED", out)

    def test_a_results_file_without_the_field_is_unaffected(self):
        """Every file written before the gate existed. Absent is absent, never a rate of zero."""
        code, out = run_gate({"project": "p", "mutations": [mutation()]})
        self.assertEqual(0, code, out)
        self.assertNotIn("compile gate", out)


class NarrowedAtAll(unittest.TestCase):
    """`narrowed_at_all` and `declined_to_narrow`, which became public API with two consumers.

    They were nested closures inside `report_narrowing` until `corpus_state.miss_was_possible` needed
    the same question and imported them rather than growing a second copy. Nothing tested them
    directly, and the prose-fallback branch is the one that once read **136 of 167 genuinely-narrowed
    rows, 81%, as full runs** -- so it is the branch most worth pinning.
    """

    def row(self, **kw):
        base = {"selected_distinct": 5, "full_distinct": 100}
        base.update(kw)
        return base

    def test_the_token_decides_when_the_row_carries_one(self):
        self.assertTrue(assert_recall.narrowed_at_all(self.row(decision_outcome="narrowed")))
        self.assertFalse(assert_recall.narrowed_at_all(self.row(decision_outcome="full-run")))

    def test_not_decided_is_not_a_refusal(self):
        """Discovery never asked. That is not the selector declining, and must not read as one."""
        self.assertTrue(assert_recall.narrowed_at_all(self.row(decision_outcome="not-decided")))

    def test_a_row_with_no_token_falls_back_to_counts_and_prose(self):
        """The tokenless shape. Counts decide, after the non-refusal prose is filtered out."""
        self.assertTrue(assert_recall.narrowed_at_all(self.row()))
        self.assertTrue(assert_recall.narrowed_at_all(self.row(
            full_run_reason="narrowing, so nothing is instrumented and the map is left alone")))

    def test_a_real_force_reason_still_declines(self):
        """The kestra guard: a forced run whose two arms differ by one test is not a 99.8% saving."""
        forced = self.row(selected_distinct=99, full_distinct=100,
                          full_run_reason="declares a compile-time constant the map did not record")
        self.assertFalse(assert_recall.narrowed_at_all(forced))

    def test_equal_counts_never_narrow(self):
        self.assertFalse(assert_recall.narrowed_at_all(
            self.row(selected_distinct=100, decision_outcome="narrowed")))

    def test_a_row_missing_either_count_is_not_narrowed(self):
        """Absent is not a saving. corpus_state runs this over every row, including malformed ones."""
        for bad in ({"selected_distinct": None, "full_distinct": 100},
                    {"selected_distinct": 5, "full_distinct": None},
                    {"selected_distinct": 5, "full_distinct": 0},
                    {}):
            self.assertFalse(assert_recall.narrowed_at_all(bad), bad)

    def test_the_two_consumers_agree(self):
        """corpus_state imports this rather than copying it; assert that the import is live."""
        import corpus_state
        self.assertIs(corpus_state.assert_recall.narrowed_at_all, assert_recall.narrowed_at_all)


class AgainstAnEarlierSweep(unittest.TestCase):
    """The 0.2 reduced sweep's comparison with 0.1 reads full-arm truth only."""

    @staticmethod
    def row(target, status="checked", missed=(), **extra):
        return {"target": target, "kind": "body", "index": 0, "status": status,
                "missed": list(missed), **extra}

    def test_a_complement_row_is_refused_and_a_checked_one_reads_its_full_arm(self):
        before = {"mutations": [self.row("a")]}
        with self.assertRaises(ValueError):
            assert_recall.compare_recall(before, {"mutations": [
                self.row("a", ground_truth="complement")]})
        checked = {"mutations": [self.row("a", missed=["p.T::t"], ground_truth="complement+full")]}
        self.assertEqual([d["cause"] for d in assert_recall.compare_recall(before, checked)],
                         ["unexplained"])

    def test_a_difference_on_a_path_the_reset_removed_is_the_reset_s(self):
        before = {"mutations": [self.row("a", status="no-effect", full_run_kind="unmappable-paths",
                                         decision_reason="changed paths the coverage map cannot "
                                                         "see: core/derby.log")]}
        after = {"preflight": {"untracked_reset": ["core/derby.log"]},
                 "mutations": [self.row("a", missed=["p.T::t"])]}
        self.assertEqual(assert_recall.compare_recall(before, after)[0]["cause"], "reset")
        self.assertEqual(assert_recall.compare_recall(before, {"mutations": after["mutations"]})
                         [0]["cause"], "unexplained")

    def test_equal_outcomes_and_unmatched_rows_are_no_difference(self):
        before = {"mutations": [self.row("a"), self.row("b", missed=["p.T::t"])]}
        after = {"mutations": [self.row("a", timed=False, ground_truth="full"), self.row("c")]}
        self.assertEqual(assert_recall.compare_recall(before, after), [])


class GroundTruthMix(unittest.TestCase):
    """Every recall figure says where its rows' ground truth came from."""

    def test_rows_before_the_field_are_full_arm_truth(self):
        rows = [{"status": "checked"}, {"status": "no-effect", "ground_truth": "complement"},
                {"status": "checked", "ground_truth": "complement+full"},
                {"status": "invalid", "ground_truth": "complement"}]
        self.assertEqual(assert_recall.ground_truth_mix(rows),
                         {"full": 1, "complement": 1, "complement+full": 1})

    def test_a_figure_with_complement_rows_says_what_they_cannot_see(self):
        line = assert_recall.ground_truth_line({"full": 2, "complement": 1, "complement+full": 0})
        self.assertIn("1 complement", line)
        self.assertIn("ran_but_passed", line)
        self.assertNotIn("ran_but_passed", assert_recall.ground_truth_line(
            {"full": 2, "complement": 0, "complement+full": 0}))


if __name__ == "__main__":
    unittest.main()
