"""Tests for `merge.py`: shards of one project fold into the file a single job would have written,
and a sweep that lost a project, a shard or its evidence is refused rather than published.

Cases return a count of failed checks; one `test_*` entry point asserts.

    python3 bench/test_merge.py
"""

import json
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import merge  # noqa: E402

STAMPS = {"project": "demo", "commit": "c0ffee", "plugin_commit": "p1", "selector_digest": "s1",
          "grammar_digest": "g1"}


def fail(message):
    print(f"  FAIL: {message}")
    return 1


def _row(position, narrowed=False, broke=False):
    return {"target": f"src/A{position}.java::body::{position}", "kind": "body", "index": position,
            "status": "checked", "selected_distinct": 1 if narrowed else 5, "full_distinct": 5,
            "full_failures": 1 if broke else 0}


def _accepted(count, slices, narrowed_target=None):
    return {**STAMPS, "narrowed_target": narrowed_target, "slices": slices,
            "targets": [[f"src/A{i}.java", "body", i, f"src/A{i}.java::body::{i}"]
                        for i in range(count)]}


def _shard(rows, number, of):
    return {**STAMPS, "shard": [number, of], "mutations": rows}


def _recording():
    return {**STAMPS, "capture_seconds": 10.0, "baseline_seconds": 4.0, "mutations": []}


def _refused(call):
    try:
        call()
    except merge.Refused as refused:
        return str(refused)
    return None


def case_shards_fold_into_the_single_job_file_in_draw_order():
    rows = [_row(i) for i in range(5)]
    merged = merge.merge_project(_recording(), _accepted(5, [[0, 2], [2, 5]]),
                                 [_shard(rows[2:], 2, 2), _shard(rows[:2], 1, 2)])
    failures = 0
    if [r["index"] for r in merged["mutations"]] != [0, 1, 2, 3, 4]:
        failures += fail(f"rows out of draw order: {[r['index'] for r in merged['mutations']]}")
    if merged["capture_seconds"] != 10.0 or merged["baseline_seconds"] != 4.0:
        failures += fail("the header is not the recording job's")
    if "shard" in merged or merged.get("shards") != 2:
        failures += fail(f"the merged file does not say it came from 2 shards: {merged.get('shards')}")
    return failures


def case_a_missing_or_doubled_shard_is_refused():
    rows = [_row(i) for i in range(4)]
    accepted = _accepted(4, [[0, 2], [2, 4]])
    failures = 0
    why = _refused(lambda: merge.merge_project(_recording(), accepted, [_shard(rows[:2], 1, 2)]))
    if not why or "shard 2 of 2" not in why:
        failures += fail(f"a missing shard: {why}")
    why = _refused(lambda: merge.merge_project(_recording(), accepted,
                                               [_shard(rows[:2], 1, 2), _shard(rows[:2], 1, 2),
                                                _shard(rows[2:], 2, 2)]))
    if not why or "twice" not in why:
        failures += fail(f"a doubled shard: {why}")
    return failures


def case_a_row_count_off_the_list_is_refused():
    rows = [_row(i) for i in range(4)]
    accepted = _accepted(4, [[0, 2], [2, 4]])
    failures = 0
    why = _refused(lambda: merge.merge_project(_recording(), accepted,
                                               [_shard(rows[:2], 1, 2), _shard(rows[2:3], 2, 2)]))
    if not why or "src/A3.java::body::3" not in why:
        failures += fail(f"a target with no row: {why}")
    stray = dict(_row(9))
    why = _refused(lambda: merge.merge_project(_recording(), accepted,
                                               [_shard(rows[:2], 1, 2),
                                                _shard(rows[2:] + [stray], 2, 2)]))
    if not why or "not on the accepted list" not in why:
        failures += fail(f"a row off the list: {why}")
    return failures


def case_a_shard_measured_under_another_stamp_is_refused():
    rows = [_row(i) for i in range(2)]
    other = {**_shard(rows[1:], 2, 2), "selector_digest": "s2"}
    why = _refused(lambda: merge.merge_project(_recording(), _accepted(2, [[0, 1], [1, 2]]),
                                               [_shard(rows[:1], 1, 2), other]))
    return 0 if why and "selector_digest" in why else fail(f"a mixed selector: {why}")


def case_a_row_with_no_kind_or_index_is_refused_by_name_not_crashed_on():
    rows = [_row(0), {"target": "src/A1.java::body::1", "status": "stalled"}]
    why = _refused(lambda: merge.merge_project(_recording(), _accepted(2, [[0, 2]]),
                                               [_shard(rows, 1, 1)]))
    return 0 if why and "src/A1.java::body::1" in why else fail(f"a label-only row: {why}")


def case_rows_past_the_narrowed_target_are_dropped_and_counted():
    rows = [_row(0, narrowed=True), _row(1), _row(2, narrowed=True), _row(3, narrowed=True)]
    merged = merge.merge_project(_recording(), _accepted(4, [[0, 2], [2, 4]], narrowed_target=2),
                                 [_shard(rows[:2], 1, 2), _shard(rows[2:], 2, 2)])
    failures = 0
    if [r["index"] for r in merged["mutations"]] != [0, 1, 2]:
        failures += fail(f"kept {[r['index'] for r in merged['mutations']]}, not the prefix "
                         f"ending at the row that reached the target")
    if merged.get("rows_past_target") != 1:
        failures += fail(f"dropped rows not counted: {merged.get('rows_past_target')}")
    return failures


def case_rejections_drawn_past_the_narrowed_target_are_dropped_with_the_rows():
    rows = [_row(0, narrowed=True), _row(1, narrowed=True), _row(2), _row(3)]
    accepted = _accepted(4, [[0, 4]], narrowed_target=2)
    accepted["rejected"] = [["src/X.java::body::0", "body", 0, 1],
                            ["src/Y.java::body::1", "body", 1, 3]]
    merged = merge.merge_project(_recording(), accepted, [_shard(rows, 1, 1)])
    failures = 0
    if merged.get("rejected_targets") != ["src/X.java::body::0"] or merged.get("mutations_rejected") != 1:
        failures += fail(f"rejections after truncation: {merged.get('rejected_targets')} "
                         f"{merged.get('mutations_rejected')}")
    if merged.get("rejected_mutations") != [["src/X.java::body::0", "body", 0]]:
        failures += fail(f"rejected mutations after truncation: {merged.get('rejected_mutations')}")
    return failures


def case_the_list_run_writes_is_the_list_shard_and_merge_read():
    import argparse  # noqa: PLC0415
    import run  # noqa: PLC0415
    args = argparse.Namespace(limit=4, narrowed_target=1, timing_cap=None, recall_only=True,
                              ground_truth="full")
    targets = [[f"src/A{i}.java", "body", i, f"src/A{i}.java::body::{i}"] for i in range(2)]
    with tempfile.TemporaryDirectory() as root:
        path = os.path.join(root, "accepted.json")
        run.write_accepted(path, {"name": "demo", "commit": "c0ffee"}, "g1", targets,
                           [["src/R.java::body::0", "body", 0, 1]], args)
        with open(path, encoding="utf-8") as handle:
            accepted = json.load(handle)
    failures = 0
    for key in ("timing_cap", "recall_only", "ground_truth", "narrowed_target", "targets",
                "rejected") + merge.STAMPS:
        if key not in accepted:
            failures += fail(f"the accepted list lacks {key}, which shard.py or merge.py reads")
    accepted["slices"] = [[0, 2]]
    recording = {**{key: accepted[key] for key in merge.STAMPS}, "mutations": []}
    shard = {**recording, "shard": [1, 1],
             "mutations": [dict(_row(0, narrowed=True), target=targets[0][3]),
                           dict(_row(1), target=targets[1][3])]}
    merged = merge.merge_project(recording, accepted, [shard])
    if len(merged["mutations"]) != 1 or merged.get("mutations_rejected") != 0:
        failures += fail(f"a list run wrote did not merge as written: {merged['mutations']} "
                         f"{merged.get('mutations_rejected')}")
    return failures


def case_a_rerun_shard_replaces_only_its_rows():
    with tempfile.TemporaryDirectory() as root:
        project = os.path.join(root, "demo")
        os.makedirs(project)
        rows = [_row(i) for i in range(4)]
        for name, data in (("recording.json", _recording()),
                           ("accepted.json", _accepted(4, [[0, 2], [2, 4]])),
                           ("shard-1-of-2.json", _shard(rows[:2], 1, 2)),
                           ("shard-2-of-2.json", _shard(rows[2:], 2, 2))):
            with open(os.path.join(project, name), "w", encoding="utf-8") as handle:
                json.dump(data, handle)
        rerun = [dict(r, selected_distinct=2) for r in rows[2:]]
        with open(os.path.join(project, "shard-2-of-2.json"), "w", encoding="utf-8") as handle:
            json.dump(_shard(rerun, 2, 2), handle)
        merged = merge.merge_directory(project)
    picked = [r["selected_distinct"] for r in merged["mutations"]]
    return 0 if picked == [5, 5, 2, 2] else fail(f"after a rerun of shard 2: {picked}")


def case_a_sweep_missing_a_project_or_a_reduced_member_is_refused():
    miss = _row(0, narrowed=True, broke=True)
    demo = {**_recording(), "mutations": [miss]}
    failures = 0
    why = _refused(lambda: merge.check_sweep(["demo", "okio"], {"demo": demo}, arm="full",
                                             isolated=False))
    if not why or "okio" not in why:
        failures += fail(f"a missing project: {why}")
    reduced = {name: {**demo, "project": name} for name in merge.REDUCED_ARM[:-1]}
    why = _refused(lambda: merge.check_sweep(list(reduced), reduced, arm="reduced",
                                             isolated=False))
    if not why or merge.REDUCED_ARM[-1] not in why:
        failures += fail(f"a reduced arm without {merge.REDUCED_ARM[-1]}: {why}")
    return failures


def case_an_arm_with_no_row_where_a_miss_was_possible_is_refused():
    quiet = {**_recording(), "mutations": [_row(0, narrowed=True), _row(1, broke=True)]}
    why = _refused(lambda: merge.check_sweep(["demo"], {"demo": quiet}, arm="full",
                                             isolated=False))
    return 0 if why and "could have missed" in why else fail(f"an arm that asked nothing: {why}")


def case_the_sweep_says_whether_the_isolated_arm_ran():
    demo = {**_recording(), "mutations": [_row(0, narrowed=True, broke=True)]}
    summary = merge.check_sweep(["demo"], {"demo": demo}, arm="full", isolated=False)
    return 0 if summary.get("isolated_arm") == "not run" else fail(f"summary: {summary}")


def case_a_named_set_merges_into_its_own_directory_and_refuses_another_sets_shard():
    import tempfile
    failures = 0
    rows = [_row(i, narrowed=True, broke=True) for i in range(2)]
    named = {"target_shapes": "recall-arm"}
    other = {**_shard(rows[1:], 2, 2), **named, "target_shapes": "default"}
    why = _refused(lambda: merge.merge_project({**_recording(), **named},
                                               {**_accepted(2, [[0, 1], [1, 2]]), **named},
                                               [{**_shard(rows[:1], 1, 2), **named}, other]))
    if not why or "target_shapes" not in why:
        failures += fail(f"a shard drawn from another set was merged: {why}")
    with tempfile.TemporaryDirectory() as tmp:
        artifacts = os.path.join(tmp, "art", "p")
        os.makedirs(artifacts)
        for name, data in (("recording.json", {**_recording(), **named, "project": "p"}),
                           ("accepted.json", {**_accepted(2, [[0, 2]]), **named, "project": "p"}),
                           ("shard-1-of-1.json", {**_shard(rows, 1, 1), **named, "project": "p"})):
            with open(os.path.join(artifacts, name), "w", encoding="utf-8") as handle:
                json.dump(data, handle)
        out = os.path.join(tmp, "results")
        code = merge.main([os.path.join(tmp, "art"), "--planned", "p", "--arm", "full", "--out", out])
        if code != 0 or not os.path.isfile(os.path.join(out, "recall-arm", "p.json")):
            failures += fail(f"a named set's merge did not land in its own directory (exit {code})")
        if os.path.exists(os.path.join(out, "p.json")):
            failures += fail("a named set's merge wrote the default set's file")
        if not os.path.isfile(os.path.join(out, "recall-arm", "sweep.json")) or \
                os.path.exists(os.path.join(out, "sweep.json")):
            failures += fail("a named set's sweep summary is not beside its files")
    return failures


def test_merge():
    failures = 0
    for name, case in sorted(globals().items()):
        if name.startswith("case_"):
            print(name)
            failures += case()
    assert failures == 0, f"{failures} check(s) failed"


if __name__ == "__main__":
    test_merge()
    print("ok")
