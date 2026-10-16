"""Whether two sweeps of the same sample agree row for row on recall.

Rows are matched by target, kind and index. A row agrees when both sweeps hold it with the same
status and the same missed set. A row flagged nondeterministic or order-dependent in either sweep
may differ, since its outcome is not a property of the change alone: it is reported, not counted.
Anything else that differs, a row only one sweep holds included, is a disagreement.

    python3 bench/compare_results.py A.json B.json

Exits 1 when the sweeps disagree.
"""

import json
import sys

FLAGS = ("nondeterministic", "order_dependent")


def _rows(data):
    return {(row["target"], row.get("kind"), row.get("index")): row
            for row in data.get("mutations") or []}


def compare(a, b):
    left, right = _rows(a), _rows(b)
    differences, flagged = [], []
    for key in sorted(set(left) | set(right), key=str):
        one, other = left.get(key), right.get(key)
        if one is None or other is None:
            differences.append(f"{key[0]}: only in the {'second' if one is None else 'first'} sweep")
            continue
        status = (one.get("status"), other.get("status"))
        missed = (sorted(one.get("missed") or []), sorted(other.get("missed") or []))
        if status[0] == status[1] and missed[0] == missed[1]:
            continue
        if any(row.get(flag) for row in (one, other) for flag in FLAGS):
            flagged.append(key[0])
            continue
        if status[0] != status[1]:
            differences.append(f"{key[0]}: status {status[0]} against {status[1]}")
        else:
            differences.append(f"{key[0]}: missed {missed[0]} against {missed[1]}")
    return {"agree": not differences, "differences": differences, "flagged": flagged,
            "rows": len(set(left) | set(right))}


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    if len(argv) != 2:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    loaded = []
    for path in argv:
        with open(path, encoding="utf-8") as handle:
            loaded.append(json.load(handle))
    report = compare(*loaded)
    for line in report["differences"]:
        print(f"DIFFERS  {line}")
    for target in report["flagged"]:
        print(f"flagged  {target}: differs on a nondeterministic or order-dependent row")
    flagged = f", {len(report['flagged'])} flagged" if report["flagged"] else ""
    print(f"{report['rows']} row(s): {'agree' if report['agree'] else 'DISAGREE'}{flagged}")
    return 0 if report["agree"] else 1


if __name__ == "__main__":
    sys.exit(main())
