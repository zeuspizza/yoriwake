"""Writes a sweep's run page: one section per merged project, from the merged results.

The guest builds write nothing to the run page (each measuring step runs them with
`GITHUB_STEP_SUMMARY` unset), so this is all a reader sees there: which projects are missing and
why, where the results are, and per project the record's figures, one line per row with each arm's
tests, failures and seconds, and the tests that already fail on the unmutated tree, named once.

Every figure is a recorded field of the merged results (docs/sweep-data-dictionary.md); a field an
older file lacks is an empty cell. The already-failing tests by name are not in the results file,
so they are read from the record job's `baseline-cache.json` in its unpacked state.

It reports and never judges: it exits 0, and a file it cannot read is a line on the page.

    python3 bench/sweep_page.py merged unpacked >> "$GITHUB_STEP_SUMMARY"

`merged/` is `merge.py --out`, with `sweep.json` at its top or under one shape-set folder;
`unpacked/<project>/` is the record job's state, as the merge step unpacks it.
"""

import argparse
import glob
import json
import os
import sys

# The induced failures the selected arm did not catch, or that count as neither caught nor missed.
# One test can sit in two of them (a test failing unmutated is also one that ran but passed).
NOT_CAUGHT = ("missed", "ran_but_passed", "undetermined", "nondeterministic", "container_failed",
              "fails_unmutated", "shared_key")

SKIPPED_NOTE = ("† full arm skipped: the complement's classes and seconds when it ran, else the "
                "forced selected arm stood as the full run.")


def find_sweep(merged_dir):
    """The one `sweep.json` under `merged_dir`, or None."""
    found = sorted(os.path.join(root, "sweep.json") for root, _, names in os.walk(merged_dir)
                   if "sweep.json" in names)
    return found[0] if found else None


def _cell(value):
    return "" if value is None else str(value)


def _seconds(value):
    return "" if not isinstance(value, (int, float)) else f"{value:.0f}"


def _code(text):
    """Inline code a table cell can hold."""
    return "`" + str(text).replace("`", "'").replace("|", "\\|") + "`"


def short_target(target):
    """A row's target as file and member: the path's last segment, then the rest unchanged."""
    if not target:
        return ""
    path, sep, member = str(target).partition("::")
    return os.path.basename(path) + sep + member


def caught(row):
    """Induced failures the selected arm ran and saw fail; None when the row has no full figure."""
    induced = row.get("full_failures")
    if not isinstance(induced, int):
        return None
    lost = {test for field in NOT_CAUGHT for test in row.get(field) or []}
    return max(induced - len(lost), 0)


def row_line(position, row):
    skipped = bool(row.get("full_arm_skipped"))
    if skipped and row.get("complement_classes") is not None:
        full_tests = f"{row['complement_classes']} classes †"
        full_seconds = _seconds(row.get("complement_seconds"))
    else:
        full_tests = _cell(row.get("full_distinct")) + (" †" if skipped else "")
        full_seconds = _seconds(row.get("full_seconds"))
    induced, already = row.get("full_failures"), row.get("already_failing")
    failed = "" if induced is None else str(induced) if already is None else f"{induced} + {already}"
    missed = row.get("missed")
    cells = [str(position), _code(short_target(row.get("target"))),
             _cell(row.get("decision_outcome") or row.get("status")),
             _cell(row.get("selected_distinct")), _cell(caught(row)),
             _seconds(row.get("selected_seconds")), full_tests, failed, full_seconds,
             "" if not isinstance(missed, list) else str(len(missed))]
    return "| " + " | ".join(cells) + " |"


def record_line(data):
    parts = []
    if isinstance(data.get("capture_seconds"), (int, float)):
        parts.append(f"capture {_seconds(data['capture_seconds'])} s")
    if isinstance(data.get("baseline_seconds"), (int, float)):
        repeats = data.get("baseline_repeats")
        parts.append(f"baseline {_seconds(data['baseline_seconds'])} s"
                     + (f" ({repeats} repeats)" if repeats is not None else ""))
    preflight = data.get("preflight")
    if isinstance(preflight, dict) and isinstance(preflight.get("seconds"), (int, float)):
        parts.append(f"preflight {_seconds(preflight['seconds'])} s")
    if data.get("rows_past_target") is not None:
        parts.append(f"{data['rows_past_target']} rows past the target")
    return "Record: " + (", ".join(parts) if parts else "no figures recorded") + "."


def already_failing(unpacked_dir, name, data):
    """Lines naming the project's already-failing tests once, or saying where its set came from."""
    caches = sorted(glob.glob(os.path.join(unpacked_dir, name, "logs", "**", "baseline-cache.json"),
                              recursive=True))
    if not caches:
        if data.get("paired_with"):
            return [f"Already failing: from the paired file {_code(data['paired_with'])}, whose "
                    f"set this arm's rows share."]
        return ["Already failing: no baseline cache in this run's record state (a project an "
                "earlier run measured keeps its set in that run's artifacts)."]
    names = set()
    for path in caches:
        try:
            with open(path, encoding="utf-8") as handle:
                cached = json.load(handle)
            keys = cached.get("already_failing_methods")
            if keys is None:
                keys = cached.get("already_failing") or []
            names.update(f"{test[0]}::{test[1]}" for test in keys)
        except (OSError, ValueError, TypeError, IndexError, AttributeError) as error:
            return [f"Already failing: {_code(os.path.relpath(path, unpacked_dir))} could not be "
                    f"read ({type(error).__name__})."]
    if not names:
        return ["Already failing on the unmutated tree: none."]
    return [f"Already failing on the unmutated tree ({len(names)}):", ""] + [
        f"- {_code(test)}" for test in sorted(names)]


def project_section(name, path, unpacked_dir):
    try:
        with open(path, encoding="utf-8") as handle:
            data = json.load(handle)
        rows = data.get("mutations") or []
        if not isinstance(rows, list) or not all(isinstance(row, dict) for row in rows):
            raise ValueError("its rows are not a list of objects")
    except (OSError, ValueError) as error:
        return [f"- **{name}**: {_code(os.path.basename(path))} could not be read "
                f"({type(error).__name__}).", ""]
    narrowed = sum(1 for row in rows if row.get("decision_outcome") == "narrowed")
    missed = sum(len(row.get("missed") or []) for row in rows)
    lines = ["<details>",
             f"<summary>{name}: {len(rows)} rows, {narrowed} narrowed, {missed} missed</summary>",
             "", record_line(data), "",
             "| # | target | outcome | selected tests | caught | selected s | full tests "
             "| failed (induced + already) | full s | missed |",
             "|---|---|---|---|---|---|---|---|---|---|"]
    lines += [row_line(position, row) for position, row in enumerate(rows, start=1)]
    lines.append("")
    if any(row.get("full_arm_skipped") for row in rows):
        lines += [SKIPPED_NOTE, ""]
    lines += already_failing(unpacked_dir, name, data)
    lines += ["", "</details>", ""]
    return lines


def page(merged_dir, unpacked_dir, run=None):
    sweep_path = find_sweep(merged_dir)
    if sweep_path is None:
        return f"## Sweep\n\nNo `sweep.json` under `{merged_dir}`: nothing was merged.\n"
    try:
        with open(sweep_path, encoding="utf-8") as handle:
            sweep = json.load(handle)
        projects, missing = list(sweep.get("projects") or []), dict(sweep.get("missing") or {})
    except (OSError, ValueError, TypeError, AttributeError) as error:
        return f"## Sweep\n\n`{sweep_path}` could not be read ({type(error).__name__}).\n"
    arm = sweep.get("arm") or "unknown"
    lines = [f"## Sweep run {run}, {arm} arm" if run else f"## Sweep, {arm} arm", ""]
    if missing:
        lines += [f"### Missing ({len(missing)})", ""]
        lines += [f"- **{name}**: {reason}" for name, reason in sorted(missing.items())]
        lines.append("")
    where = "the `sweep-results` artifact of this run holds the merged results"
    if run:
        # Named, not checked: the merge step may have stopped before it created the release.
        where += (f"; the draft release `sweep-{run}`, once the merge step has created it, holds them "
                  f"with the maps and decision records")
    lines += [f"Results: {where}.", "", f"### Projects ({len(projects)})", ""]
    folder = os.path.dirname(sweep_path)
    for name in projects:
        path = os.path.join(folder, f"{name}.json")
        try:
            lines += project_section(name, path, unpacked_dir)
        except Exception as error:  # noqa: BLE001 -- one odd file must not cost the others their sections
            lines += [f"- **{name}**: {_code(os.path.basename(path))} could not be shown "
                      f"({type(error).__name__}).", ""]
    return "\n".join(lines)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("merged", help="the merge's output directory")
    parser.add_argument("unpacked", help="the record jobs' unpacked state, one directory per project")
    args = parser.parse_args(argv)
    print(page(args.merged, args.unpacked, os.environ.get("GITHUB_RUN_ID")))
    return 0


if __name__ == "__main__":
    sys.exit(main())
