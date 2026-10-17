"""
The order `pool/v1` draws its targets in, and when each of its families stops.

    family-round-robin/v1   every family of the set takes a turn, so each gets its first draw
                            before any gets a second. Inside a family the targets keep the order
                            `reach-stratified/v1` and the coverage buckets gave them, so a family's
                            draws still span the map's strata.
    learned/v1              inside a family and a stratum, targets ordered by a score of how likely
                            they are to narrow and induce a failure, fitted on archived rows from
                            features alone. Used only for the families a leave-one-project-out
                            replay adopted (`replay_order.py`); every other family keeps the
                            round-robin alone.

A family stops at its own narrowed-and-induced target or at its row cap, whichever comes first. The
cap is the project's `sweep_limit`, per family. Both are fixed before the sweep and stamped on every
row.

NOTHING HERE READS WHETHER A ROW MISSED. The order is a function of the targets, the seed, the map's
reach counts, file sizes and a model fitted without outcomes of the selected arm; the stops read row
counts and `counts_toward`, which looks at decisions and induced failures only. So the rows measured
are the rows they would be whatever the misses were, and the missed count over them is a count over
a sample its own outcomes did not choose.

WHY A FILE OF ITS OWN: `target_ordering.py` is in the default set's `grammar_digest`. This file is
digested with `pool/v1` only.
"""

import hashlib
import json
import math
import os

import target_ordering

ORDERING = "family-round-robin/v1"
LEARNED = "learned/v1"

# The narrowed-and-induced rows a family needs in a project before it stops drawing.
FAMILY_TARGET = 4

HERE = os.path.dirname(os.path.abspath(__file__))
# Written by `replay_order.py --write` only when the replay adopts the learned order for a family.
MODEL_PATH = os.path.join(HERE, "order", "learned-v1.json")


def _rank(seed, text):
    return hashlib.sha256(f"{seed}:{text}".encode("utf-8")).hexdigest()


def round_robin(ordered, seed):
    """`ordered` with families taking turns: each round draws the next target of every family that
    has one left, families in a seeded order. Each family's targets keep their relative order."""
    queues = {}
    for target in ordered:
        queues.setdefault(target[1], []).append(target)
    families = sorted(queues, key=lambda family: _rank(seed, family))
    result = []
    position = 0
    while any(position < len(queues[family]) for family in families):
        for family in families:
            if position < len(queues[family]):
                result.append(queues[family][position])
        position += 1
    return result


def strata(targets, reach):
    """target -> `reach-stratified/v1`'s stratum: 0 hub, 1 mid, 2 leaf, 3 unreached, by the
    terciles of the reach values present among `targets`, as `target_ordering.by_reach` cuts them.
    Every target is 3 when there is no map."""
    scores = {target: target_ordering.reach_of(target_ordering.qualified_name(target[0]), reach)
              for target in targets} if reach else {}
    positive = sorted({score for score in scores.values() if score > 0})
    if not positive:
        return {target: 3 for target in targets}
    low = positive[len(positive) // 3]
    high = positive[(2 * len(positive)) // 3]
    return {target: (3 if score <= 0 else 0 if score >= high else 1 if score >= low else 2)
            for target, score in scores.items()}


# ------------------------------------------------------------------------------------- stops

def family_rows(kind, findings, recorded=()):
    """Rows of `kind` this project holds, counting targets a recording job accepted for shards."""
    return (sum(1 for row in findings if row.get("kind") == kind)
            + sum(1 for entry in recorded if entry[1] == kind))


def family_stop(kind, findings, recorded, cap, target, counts_toward):
    """Why `kind` draws nothing more -- "target" or "cap" -- or None while it still draws.

    `counts_toward` decides whether a row narrowed and induced a failure; it must not read what was
    missed."""
    reached = sum(1 for row in findings if row.get("kind") == kind and counts_toward(row))
    if target is not None and reached >= target:
        return "target"
    if family_rows(kind, findings, recorded) >= cap:
        return "cap"
    return None


def counters(findings, cap, target, counts_toward):
    """Per family: rows, narrowed-and-induced rows, and why it stopped, for the results file."""
    result = {}
    for kind in sorted({row.get("kind") for row in findings if row.get("kind")}):
        result[kind] = {
            "rows": family_rows(kind, findings),
            "narrowed_induced": sum(1 for row in findings
                                    if row.get("kind") == kind and counts_toward(row)),
            "stopped": family_stop(kind, findings, (), cap, target, counts_toward)}
    return result


def kept_in_draw_order(rows, target, counts_toward):
    """The rows a single job would have measured from a list fixed before measuring: in draw order,
    a family's rows after the one that reached its target are dropped, the others' kept. Returns
    `(kept, stopped_at)`, where `stopped_at[kind]` is the accepted-list position of the row that
    stopped `kind`."""
    reached, kept, stopped_at = {}, [], {}
    for position, row in enumerate(rows):
        kind = row.get("kind")
        if kind in stopped_at:
            continue
        kept.append(row)
        if counts_toward(row):
            reached[kind] = reached.get(kind, 0) + 1
            if target is not None and reached[kind] >= target:
                stopped_at[kind] = position
    return kept, stopped_at


# ---------------------------------------------------------------------------- learned order

FEATURES = ("stratum", "covering", "size")


def _log_bucket(value):
    if value is None:
        return "unknown"
    return 0 if value <= 0 else 1 + int(math.log2(value))


def features(stratum, covering, size):
    """A target's buckets: its stratum, its covering-test count and its file size in bytes, each
    None when unknown. Family is the model's key, not a feature."""
    return {"stratum": "unknown" if stratum is None else stratum,
            "covering": _log_bucket(covering), "size": _log_bucket(size)}


def fit(rows):
    """A model from `[{"family", "features", "hit"}]`, where `hit` is whether the archived row
    narrowed and induced a failure. Counts only, so the fit is deterministic and readable."""
    model = {}
    for row in rows:
        entry = model.setdefault(row["family"], {"rows": 0, "hits": 0, "buckets": {}})
        entry["rows"] += 1
        entry["hits"] += 1 if row["hit"] else 0
        for name in FEATURES:
            key = f"{name}={row['features'][name]}"
            bucket = entry["buckets"].setdefault(key, [0, 0])
            bucket[0] += 1
            bucket[1] += 1 if row["hit"] else 0
    return model


# How many rows' weight the family's own rate carries against a bucket's: a bucket seen twice
# moves the score less than one seen twenty times.
PRIOR_WEIGHT = 2


def score(model, family, buckets):
    """The log-likelihood ratio, summed over features, of a target's buckets against its family's
    base rate. None for a family the model has no rows of."""
    entry = model.get(family)
    if not entry or not entry["rows"]:
        return None
    base = (entry["hits"] + 1) / (entry["rows"] + 2)
    total = 0.0
    for name in FEATURES:
        rows, hits = entry["buckets"].get(f"{name}={buckets[name]}", (0, 0))
        rate = (hits + PRIOR_WEIGHT * base) / (rows + PRIOR_WEIGHT)
        total += math.log(rate / base)
    return total


def learned(ordered, stratum_of, scores, archived):
    """One family's targets, `ordered` by the round-robin, reordered inside each stratum by `scores`
    (highest first, the round-robin's order breaking ties). Each position keeps its stratum, so the
    strata are reached as before.

    `archived` targets are not ordered: they follow every other target of the family, in the
    round-robin's order. They were measured before, and the selector has since been fixed on the
    ones that missed, so ranking them would redraw solved cases first. They are still drawn, so a
    project whose only target of a family is archived keeps it."""
    fresh = [t for t in ordered if t not in archived]
    queues = {}
    for position, target in enumerate(fresh):
        queues.setdefault(stratum_of.get(target), []).append((-scores[target], position, target))
    for queue in queues.values():
        queue.sort()
    result = [queues[stratum_of.get(target)].pop(0)[2] for target in fresh]
    return result + [t for t in ordered if t in archived]


def load_model(path=MODEL_PATH):
    """The adopted model, or None when no family adopted the learned order."""
    if not os.path.isfile(path):
        return None
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def ordering_name(model):
    """The `target_ordering` stamp: the learned order is its own version."""
    adopted = sorted((model or {}).get("adopted") or [])
    return f"{ORDERING}+{LEARNED}" if adopted else ORDERING


def draw_order(ordered, seed, reach=None, sizes=None, model=None, project=None):
    """`pool/v1`'s draw: the round-robin of `ordered`, with each adopted family's targets in the
    learned order. `sizes` maps a target's path to its size in bytes."""
    result = round_robin(ordered, seed)
    adopted = set((model or {}).get("adopted") or [])
    if not adopted:
        return result
    stratum_of = strata(ordered, reach or {})
    archived = {tuple(t) for t in (model.get("archived") or {}).get(project, [])}
    for family in sorted(adopted):
        own = [t for t in result if t[1] == family]
        if not own:
            continue
        scores = {}
        for target in own:
            covering = (target_ordering.reach_of(target_ordering.qualified_name(target[0]), reach)
                        if reach else None)
            value = score(model["model"], family,
                          features(stratum_of.get(target), covering, (sizes or {}).get(target[0])))
            scores[target] = 0.0 if value is None else value
        reordered = iter(learned(own, stratum_of, scores, archived))
        result = [next(reordered) if t[1] == family else t for t in result]
    return result
