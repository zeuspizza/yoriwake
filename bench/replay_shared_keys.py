"""
Which rows on disk the shared-key rule would change, read offline from each row's kept record.

A row written before per-method keys matched its failures on JUnit XML keys, and where a class's
methods share those keys a selected method could stand in for an excluded one. This reads every
checked row with its kept decision record, applies `run.shares_a_key` as an XML-keyed row would
now, and names, per row:

- a failure counted as caught whose key may stand for another test: it would be `shared_key`;
- a failure the re-runs or the control arm took out of `missed` on such a key (`nondeterministic`,
  `fails_unmutated`): it would stay missed;
- a row the skipped-nothing rule could have emptied while no trusted record shows nothing was
  excluded: its dropped misses were never kept, so the row is named, not judged.

A row whose record is not kept is named unreadable, never clean. A baseline subtraction cannot be
re-checked: the full arm's failures before it are not kept. Rows that carry `result_keys` were
judged by the rule already and are left out. Nothing is written.

    python3 bench/replay_shared_keys.py [--logs DIR] [RESULTS ...]
"""

import argparse
import glob
import json
import os
import sys
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import replay_decisions  # noqa: E402
import run  # noqa: E402

# The change a caught failure on a shared key would undergo.
CAUGHT = "caught -> shared_key"
# Every list a checked row names a failure in when it did not count it as caught.
NOT_CAUGHT = ("missed", "undetermined", "nondeterministic", "container_failed", "fails_unmutated",
              "shared_key")


def record_for(row, scratch):
    """The row's kept decision record, its parts preferred, or None when none is on disk.

    The row's own `replay` path when it has one; otherwise the stems `run.py` keeps records under:
    with the index, and the label-only stem rows were kept under before the index was part of it.
    """
    stems = []
    kept = (row.get("replay") or {}).get("decisions")
    if kept:
        stems.append(os.path.basename(kept).removesuffix(".tsv").removesuffix(".parts"))
    if "index" in row:
        stems.append("decisions-" + run.log_slug(row["target"], row["index"]))
    stems.append("decisions-" + run.log_slug(row["target"]))
    for stem in stems:
        for path in (os.path.join(scratch, stem + ".parts"), os.path.join(scratch, stem + ".tsv")):
            if os.path.exists(path):
                return replay_decisions.load_record(path)
    return None


def judge(row, record):
    """What the shared-key rule would change in one XML-keyed row: `(change, failure)` pairs."""
    trusted = None if row.get("decision_write_failures") else record
    keys = run.RowKeys(run.XML, trusted)
    listed = {test for field in NOT_CAUGHT for test in row.get(field) or []}
    changes = [(CAUGHT, test) for test in row.get("induced") or []
               if test not in listed and keys.shared(tuple(test.split("::", 1)))]
    for field in ("nondeterministic", "fails_unmutated"):
        changes += [(f"{field} -> missed", test) for test in row.get(field) or []
                    if keys.shared(tuple(test.split("::", 1)))]
    selected, full = row.get("selected_distinct"), row.get("full_distinct")
    if isinstance(selected, int) and isinstance(full, int) and selected >= full \
            and row.get("full_failures") and not row.get("missed") \
            and (trusted is None or not all(verdict.partition("/")[0] == "included"
                                            for _, verdict in trusted["decided"])):
        changes.append(("skipped-nothing may have dropped misses", "the record does not show that "
                                                                   "nothing was excluded"))
    return changes


def replay(paths, logs):
    """Every named row, and per project the counts the report prints."""
    named, projects = [], {}
    for path in paths:
        try:
            with open(path, encoding="utf-8") as handle:
                data = json.load(handle)
        except (OSError, ValueError):
            continue
        project = os.path.basename(path).removesuffix(".json")
        scratch = os.path.join(logs, project)
        counts = projects.setdefault(project, Counter())
        for row in data.get("mutations") or []:
            if row.get("status") != "checked" or "result_keys" in row:
                continue
            counts["rows"] += 1
            if row.get("induced") is None:
                named.append((project, row["target"], "unreadable",
                              "the row keeps no failure names"))
                counts["unreadable"] += 1
                continue
            record = record_for(row, scratch)
            if record is None:
                named.append((project, row["target"], "unreadable", "no kept decision record"))
                counts["unreadable"] += 1
                continue
            changes = judge(row, record)
            if changes:
                counts["named"] += 1
            for change, test in changes:
                named.append((project, row["target"], change, test))
                counts["caught" if change == CAUGHT else change] += 1
    return named, projects


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[1])
    parser.add_argument("results", nargs="*", help="results files (default: every one)")
    parser.add_argument("--logs", default=os.path.join(HERE, "logs"),
                        help="where the kept decision records are (default: bench/logs)")
    args = parser.parse_args(argv)
    paths = args.results or sorted(glob.glob(os.path.join(HERE, "results", "*.json")))
    named, projects = replay(paths, args.logs)
    for project, target, change, test in named:
        print(f"{project}  {target}\n    {change}: {test}")
    print()
    for project, counts in sorted(projects.items()):
        if counts["rows"]:
            print(f"{project}: {counts['rows']} checked row(s), {counts['named']} named, "
                  f"{counts['caught']} failure(s) would be shared_key, "
                  f"{counts['nondeterministic -> missed'] + counts['fails_unmutated -> missed']} "
                  f"set aside would stay missed, {counts['unreadable']} unreadable")
    print(f"\n{sum(1 for _, _, c, _ in named if c == CAUGHT)} caught failure(s) "
          f"would be shared_key; no rule here moves a failure to caught.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
