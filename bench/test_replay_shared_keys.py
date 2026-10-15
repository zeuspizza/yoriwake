"""
Tests for the offline replay of the shared-key rule over results files and their kept records.

Run with `python bench/test_replay_shared_keys.py`, or under pytest.

Cases are named case_* and RETURN a count of failed checks, following test_run.py. The single
test_* entry point below asserts.
"""

import contextlib
import io
import json
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import replay_shared_keys  # noqa: E402
import run  # noqa: E402

ENGINE = "[engine:junit-jupiter]"
CLS = "p.P"


def fail(message):
    print(f"  FAIL: {message}")
    return 1


def _write(root, project, rows, records):
    """A results file of `rows`, and each row's kept record under the label-only stem a 0.1.0 row
    was kept under. `records` maps a target to its decided `(id, verdict)` pairs."""
    os.makedirs(os.path.join(root, "results"), exist_ok=True)
    path = os.path.join(root, "results", f"{project}.json")
    with open(path, "w", encoding="utf-8") as handle:
        json.dump({"project": project, "mutations": rows}, handle)
    scratch = os.path.join(root, "logs", project)
    os.makedirs(scratch, exist_ok=True)
    for target, decided in records.items():
        lines = [f"{run.DECISION_NOTE_PREFIX}outcome\tnarrowed",
                 f"{run.DECISION_NOTE_PREFIX}{run.DECISION_ROWS_NOTE}\t{len(decided)}"]
        lines += [f"{test}\t{verdict}\tREASON" for test, verdict in decided]
        with open(os.path.join(scratch, f"decisions-{run.log_slug(target)}.tsv"), "w",
                  encoding="utf-8") as handle:
            handle.write("\n".join(lines) + "\n")
    return path


def _row(target, induced, **extra):
    return {"target": target, "status": "checked", "selected_distinct": 4, "full_distinct": 10,
            "full_failures": len(induced), "induced": induced, "missed": [], **extra}


# `a()` kept and `b()` excluded, both invoked as `v = 1`; the failure was counted as caught.
MIXED = [(f"{ENGINE}/[class:{CLS}]/[test-template:a()]", "included"),
         (f"{ENGINE}/[class:{CLS}]/[test-template:b()]", "excluded")]


def _replay(root, path):
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        named, projects = replay_shared_keys.replay([path], os.path.join(root, "logs"))
        replay_shared_keys.main(["--logs", os.path.join(root, "logs"), path])
    return named, projects, out.getvalue()


def case_a_caught_failure_on_a_shared_key_is_named():
    failures = 0
    with tempfile.TemporaryDirectory() as root:
        path = _write(root, "p", [_row("T::body::x", [f"{CLS}::v = 1"])], {"T::body::x": MIXED})
        named, projects, out = _replay(root, path)
        if named != [("p", "T::body::x", "caught -> shared_key", f"{CLS}::v = 1")]:
            failures += fail(f"the shared-key row was not named: {named}")
        if projects["p"]["caught"] != 1 or "1 failure(s) would be shared_key" not in out:
            failures += fail(f"the per-project shared_key count is missing: {out}")
    return failures


def case_a_plain_method_file_names_nothing():
    failures = 0
    plain = [(f"{ENGINE}/[class:{CLS}]/[method:c()]", "included"),
             (f"{ENGINE}/[class:{CLS}]/[method:d()]", "excluded")]
    with tempfile.TemporaryDirectory() as root:
        path = _write(root, "p", [_row("T::body::x", [f"{CLS}::c"], missed=[]),
                                  _row("T::body::y", [f"{CLS}::d"], missed=[f"{CLS}::d"])],
                      {"T::body::x": plain, "T::body::y": plain})
        named, _, _ = _replay(root, path)
        if named:
            failures += fail(f"a plain-method file named rows: {named}")
    return failures


def case_a_row_with_no_kept_record_is_unreadable_never_clean():
    failures = 0
    with tempfile.TemporaryDirectory() as root:
        path = _write(root, "p", [_row("T::body::x", [f"{CLS}::v = 1"])], {})
        named, projects, _ = _replay(root, path)
        if named != [("p", "T::body::x", "unreadable", "no kept decision record")] \
                or projects["p"]["unreadable"] != 1:
            failures += fail(f"a row with no record was not named unreadable: {named}")
    return failures


def case_a_miss_taken_out_by_the_re_runs_on_a_shared_key_would_stay_missed():
    failures = 0
    with tempfile.TemporaryDirectory() as root:
        path = _write(root, "p", [_row("T::body::x", [f"{CLS}::v = 1"],
                                       nondeterministic=[f"{CLS}::v = 1"])],
                      {"T::body::x": MIXED})
        named, _, _ = _replay(root, path)
        if named != [("p", "T::body::x", "nondeterministic -> missed", f"{CLS}::v = 1")]:
            failures += fail(f"a reclassified shared-key miss was not named: {named}")
    return failures


def case_a_miss_taken_out_in_a_class_excluded_whole_would_stay_missed():
    """The record excluded both templates: the selected arm ran neither, but the re-runs answered
    `v = 1` from whichever of them ran."""
    failures = 0
    excluded = [(test, "excluded") for test, _ in MIXED]
    with tempfile.TemporaryDirectory() as root:
        path = _write(root, "p", [_row("T::body::x", [f"{CLS}::v = 1"], missed=[],
                                       nondeterministic=[f"{CLS}::v = 1"])],
                      {"T::body::x": excluded})
        named, projects, out = _replay(root, path)
        if named != [("p", "T::body::x", "nondeterministic -> missed", f"{CLS}::v = 1")]:
            failures += fail(f"a miss set aside in a class excluded whole was not named: {named}")
        if projects["p"]["nondeterministic -> missed"] != 1 \
                or "1 set aside would stay missed" not in out:
            failures += fail(f"the per-project set-aside count is missing: {out}")
    return failures


def test_replay_shared_keys():
    failures = 0
    for name, case in sorted(globals().items()):
        if name.startswith("case_"):
            print(name)
            failures += case()
    assert failures == 0, f"{failures} failed checks"


if __name__ == "__main__":
    test_replay_shared_keys()
    print("\nall checks passed")
