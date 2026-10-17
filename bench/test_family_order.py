"""Tests for `family_order.py`: `pool/v1`'s families take turns, each stops at its own target or
cap, the learned order ranks only targets it was not fitted on, and nothing reads a miss.

Cases return a count of failed checks; one `test_*` entry point asserts.

    python3 bench/test_family_order.py
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import family_order  # noqa: E402
import merge  # noqa: E402
import pool_mutations  # noqa: E402
import run  # noqa: E402
import target_ordering  # noqa: E402

SEED = target_ordering.DEFAULT_TARGET_SEED
FAMILIES = ("body", "throw-entry", "void-return", "override-add", "member-delete")


def fail(message):
    print(f"  FAIL: {message}")
    return 1


def _targets(per_family, families=FAMILIES):
    return [(f"src/main/java/p/{family.title().replace('-', '')}{i}.java", family, i)
            for i in range(per_family) for family in families]


def _row(target, narrowed, induced, missed=False):
    return {"target": f"{target[0]}::{target[1]}::m{target[2]}", "kind": target[1],
            "index": target[2], "status": "checked" if induced else "no-effect",
            "selected_distinct": 2 if narrowed else 10, "full_distinct": 10,
            "full_failures": 1 if induced else 0, "induced": ["T::t"] if induced else [],
            "missed": ["T::t"] if missed else []}


def draws(items, outcomes, cap, target, rejected=()):
    """The run loop's `pool/v1` draw, with each row's outcome handed in: the targets it measures,
    in order. A target in `rejected` fails the compile gate and is redrawn from its family."""
    items = list(items)
    findings, drawn = [], []
    for position, item in enumerate(items):
        if family_order.family_stop(item[1], findings, (), cap, target,
                                    run.counts_toward_narrowed_target):
            continue
        if item in rejected:
            pool_mutations.redraw(items, position, item[1])
            continue
        drawn.append(item)
        findings.append(_row(item, *outcomes.get(item, (False, False, False))))
    return drawn


def case_every_family_gets_a_first_draw_before_any_gets_a_second():
    failures = 0
    one_each = family_order.round_robin(_targets(1), SEED)
    if sorted(t[1] for t in one_each[:5]) != sorted(FAMILIES):
        failures += fail(f"five families, one target each, drew {[t[1] for t in one_each[:5]]}")
    # Ordered as the reach strata and coverage buckets left them: all of one family first.
    clustered = [t for family in FAMILIES for t in _targets(3) if t[1] == family]
    ordered = family_order.round_robin(clustered, SEED)
    for round_number in range(3):
        drawn = [t[1] for t in ordered[5 * round_number:5 * round_number + 5]]
        if sorted(drawn) != sorted(FAMILIES):
            failures += fail(f"round {round_number} drew {drawn}, not one of each family")
    for family in FAMILIES:
        if [t for t in ordered if t[1] == family] != [t for t in clustered if t[1] == family]:
            failures += fail(f"{family}'s targets lost the order the strata gave them")
    if ordered != family_order.round_robin(clustered, SEED):
        failures += fail("the same targets and seed gave two orders")
    if len({tuple(t[1] for t in family_order.round_robin(clustered, s)[:5])
            for s in range(6)}) == 1:
        failures += fail("the families' turn order does not depend on the seed")
    return failures


def case_a_rejected_draw_is_replaced_by_its_familys_next_target():
    ordered = family_order.round_robin(_targets(2), SEED)
    first = ordered[0]
    drawn = draws(ordered, {}, cap=10, target=4, rejected={first})
    if drawn[0][1] != first[1] or drawn[0] == first:
        return fail(f"after {first} was rejected the next draw was {drawn[0]}, not its family's")
    return 0


def case_a_family_at_its_target_draws_nothing_more_and_the_others_go_on():
    ordered = family_order.round_robin(_targets(6), SEED)
    # Every body row narrows and induces; no other family's does.
    outcomes = {t: (True, True) for t in ordered if t[1] == "body"}
    drawn = draws(ordered, outcomes, cap=6, target=2)
    failures = 0
    if sum(1 for t in drawn if t[1] == "body") != 2:
        failures += fail(f"body drew {sum(1 for t in drawn if t[1] == 'body')} rows, not its 2")
    for family in FAMILIES[1:]:
        if sum(1 for t in drawn if t[1] == family) != 6:
            failures += fail(f"{family} stopped with body instead of at its own cap")
    return failures


def case_a_family_at_its_row_cap_stops_below_its_target():
    ordered = family_order.round_robin(_targets(6), SEED)
    drawn = draws(ordered, {}, cap=3, target=4)
    failures = 0
    for family in FAMILIES:
        if sum(1 for t in drawn if t[1] == family) != 3:
            failures += fail(f"{family} did not stop at the cap of 3")
    findings = [_row(t, False, False) for t in drawn]
    stopped = family_order.counters(findings, 3, 4, run.counts_toward_narrowed_target)
    if any(entry["stopped"] != "cap" for entry in stopped.values()):
        failures += fail(f"the counters do not say the cap stopped each family: {stopped}")
    return failures


def case_flipping_every_rows_missed_set_leaves_the_draw_identical():
    ordered = family_order.round_robin(_targets(6), SEED)
    outcomes = {t: (i % 2 == 0, i % 3 != 0, False) for i, t in enumerate(ordered)}
    base = draws(ordered, outcomes, cap=4, target=2)
    flipped = {t: (narrowed, induced, True) for t, (narrowed, induced, _) in outcomes.items()}
    if draws(ordered, flipped, cap=4, target=2) != base:
        return fail("flipping every row's missed set changed the draw")
    return 0


def case_the_strata_are_reach_stratified_v1s():
    targets = [(f"src/main/java/p/C{i}.java", "body", 0) for i in range(9)]
    reach = {f"p.C{i}": i for i in range(9)}
    stratum_of = family_order.strata(targets, reach)
    first_round = target_ordering.by_reach(targets, reach, SEED)[:4]
    failures = 0
    if sorted(stratum_of[t] for t in first_round) != [0, 1, 2, 3]:
        failures += fail(f"by_reach's first round is not one target per stratum: "
                         f"{[stratum_of[t] for t in first_round]}")
    if stratum_of[targets[0]] != 3 or stratum_of[targets[8]] != 0:
        failures += fail("an unreached class or the busiest one is in the wrong stratum")
    if set(family_order.strata(targets, {}).values()) != {3}:
        failures += fail("without a map every target should be unreached")
    return failures


def _model(adopted=("body",)):
    rows = [{"family": "body", "features": family_order.features(s, c, z), "hit": h}
            for s, c, z, h in ((0, 100, 4000, False), (0, 90, 5000, False),
                               (2, 2, 800, True), (2, 3, 900, True), (1, 10, 1500, False))]
    return {"ordering": family_order.LEARNED, "adopted": list(adopted),
            "model": family_order.fit(rows), "archived": {}}


def case_the_learned_order_is_deterministic_and_never_orders_an_archived_target():
    targets = [(f"src/main/java/p/C{i}.java", "body", 0) for i in range(8)]
    # No map, so one stratum, and the largest files first: the ones the model ranks lowest.
    reach = None
    sizes = {t[0]: 5000 - 600 * i for i, t in enumerate(targets)}
    model = _model()
    first = family_order.draw_order(targets, SEED, reach, sizes, model, "p")
    failures = 0
    if first != family_order.draw_order(targets, SEED, reach, sizes, model, "p"):
        failures += fail("the same inputs gave two learned orders")
    if first == family_order.round_robin(targets, SEED):
        failures += fail("the learned order changed nothing, so this case proves nothing")
    archived = [targets[0], targets[1]]
    model["archived"] = {"p": [list(t) for t in archived]}
    for big_size in (100, 10 ** 9):
        ordered = family_order.draw_order(targets, SEED, reach, {**sizes, targets[0][0]: big_size},
                                          model, "p")
        if ordered[-2:] != [t for t in family_order.round_robin(targets, SEED) if t in archived]:
            failures += fail(f"an archived target was ranked: {ordered}")
    if family_order.ordering_name(model) == family_order.ORDERING:
        failures += fail("the learned order is stamped as the round-robin")
    if family_order.ordering_name(None) != family_order.ORDERING:
        failures += fail("with no adopted family the stamp is not the round-robin's")
    return failures


def case_a_projects_only_target_of_a_family_is_drawn_whatever_its_rank():
    model = _model()
    # A hub, the bucket the model ranks lowest, and archived besides.
    only = ("src/main/java/p/Hub.java", "body", 0)
    others = [(f"src/main/java/p/V{i}.java", "void-return", i) for i in range(3)]
    model["archived"] = {"p": [list(only)]}
    ordered = family_order.draw_order([only] + others, SEED, {"p.Hub": 100, "p.V0": 1},
                                      {only[0]: 10 ** 6}, model, "p")
    if only not in draws(ordered, {}, cap=4, target=4):
        return fail(f"the project's only body target was never drawn: {ordered}")
    return 0


def case_a_pool_merge_stops_each_family_at_its_own_target():
    stamps = {"project": "p", "commit": "c", "plugin_commit": "p1", "selector_digest": "s",
              "grammar_digest": "g", "target_shapes": run.POOL_SHAPES}
    order = [("src/A.java", "body", 0), ("src/B.java", "void-return", 0),
             ("src/A.java", "body", 1), ("src/B.java", "void-return", 1),
             ("src/A.java", "body", 2)]
    rows = [_row(t, t[1] == "body", t[1] == "body") for t in order]
    accepted = {**stamps, "family_target": 2, "narrowed_target": None, "slices": [[0, 5]],
                "targets": [[t[0], t[1], t[2], r["target"]] for t, r in zip(order, rows)],
                "rejected": [["x", "body", 9, 4], ["y", "void-return", 9, 4],
                             ["z", "body", 8, 2]]}
    # The recording job's own stamp: it measured nothing, so its counters are empty.
    recording = {**stamps, "family_order": {"ordering": family_order.ORDERING, "learned": [],
                                            "target": 2, "cap": 4, "families": {}}}
    merged = merge.merge_project(recording, accepted, [{**stamps, "shard": [1, 1],
                                                        "mutations": rows}])
    failures = 0
    kept = [(r["kind"], r["index"]) for r in merged["mutations"]]
    if kept != [("body", 0), ("void-return", 0), ("body", 1), ("void-return", 1)]:
        failures += fail(f"the merge kept {kept}")
    if [m[0] for m in merged["rejected_mutations"] or []] != ["y", "z"]:
        failures += fail(f"a rejection drawn after its family stopped was kept, or one before "
                         f"dropped: {merged['rejected_mutations']}")
    single = family_order.counters(merged["mutations"], 4, 2, run.counts_toward_narrowed_target)
    if merged["family_order"]["families"] != single:
        failures += fail(f"the merged file's family counters are not a single job's: "
                         f"{merged['family_order']['families']}, not {single}")
    if recording["family_order"]["families"] != {}:
        failures += fail("the merge changed the recording job's stamp in place")
    return failures


def case_a_recording_job_stops_a_family_at_its_cap_from_the_targets_it_accepted():
    # A recording job measures no row: only the targets it accepted can stop a family.
    recorded = [["src/A.java", "body", i, f"src/A.java::body::m{i}"] for i in range(3)]
    failures = 0
    if family_order.family_stop("body", [], recorded, 3, 4,
                                run.counts_toward_narrowed_target) != "cap":
        failures += fail("a family with its cap of targets accepted still draws")
    if family_order.family_stop("void-return", [], recorded, 3, 4,
                                run.counts_toward_narrowed_target) is not None:
        failures += fail("another family's accepted targets stopped void-return")
    return failures


def case_the_accepted_list_carries_the_family_target_merge_truncates_by():
    import argparse  # noqa: PLC0415
    import json  # noqa: PLC0415
    import tempfile  # noqa: PLC0415
    args = argparse.Namespace(limit=4, narrowed_target=None, timing_cap=None, recall_only=True,
                              ground_truth="full")
    saved = run.RUN_STAMPS.get("family_order")
    run.RUN_STAMPS["family_order"] = {"ordering": family_order.ORDERING, "learned": [],
                                      "target": 3, "cap": 4}
    try:
        with tempfile.TemporaryDirectory() as root:
            path = os.path.join(root, "accepted.json")
            run.write_accepted(path, {"name": "p", "commit": "c"}, "g", [], [], args)
            with open(path, encoding="utf-8") as handle:
                accepted = json.load(handle)
    finally:
        if saved is None:
            run.RUN_STAMPS.pop("family_order", None)
        else:
            run.RUN_STAMPS["family_order"] = saved
    if accepted.get("family_target") != 3:
        return fail(f"the accepted list has family_target {accepted.get('family_target')!r}, "
                    f"so merge.py would keep every row of a family past its target")
    return 0


def test_family_order():
    failures = 0
    for name, case in sorted(globals().items()):
        if name.startswith("case_"):
            print(name)
            failures += case()
    assert failures == 0, f"{failures} check(s) failed"


if __name__ == "__main__":
    test_family_order()
    print("ok")
