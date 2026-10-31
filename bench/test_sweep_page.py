"""Tests for `sweep_page.py`: the run page shows each merged project's recorded rows, names the
missing projects and the already-failing tests, and a file it cannot read is a line, not an exit.

Cases return a count of failed checks; one `test_*` entry point asserts.

    python3 bench/test_sweep_page.py
"""

import contextlib
import io
import json
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import sweep_page  # noqa: E402


def fail(message):
    print(f"  FAIL: {message}")
    return 1


def _row(member, outcome="narrowed", status="checked", selected=4, full=10, induced=3,
         already=1, missed=(), **fields):
    row = {"target": f"core/src/main/java/a/Thing.java::body::{member}", "kind": "body",
           "status": status, "decision_outcome": outcome, "selected_distinct": selected,
           "selected_seconds": 12.4, "full_distinct": full, "full_failures": induced,
           "already_failing": already, "full_seconds": 30.6, "missed": list(missed),
           "full_arm_skipped": False, "complement_classes": None, "complement_seconds": None}
    row.update(fields)
    return row


def _project(name, rows, **header):
    return {"project": name, "capture_seconds": 61.2, "baseline_seconds": 40.0,
            "baseline_repeats": 2, "rows_past_target": 1, "preflight": {"seconds": 9.0},
            "mutations": rows, **header}


def _sweep(root, projects, missing=None, folder="", arm="full", raw=None):
    """`merged/` as merge.py writes it, under `folder` for a named shape set; `raw` writes a
    project's file as given text instead of JSON."""
    merged = os.path.join(root, "merged", folder)
    os.makedirs(merged, exist_ok=True)
    for name, data in projects.items():
        with open(os.path.join(merged, f"{name}.json"), "w", encoding="utf-8") as handle:
            handle.write(raw[name] if raw and name in raw else json.dumps(data))
    with open(os.path.join(merged, "sweep.json"), "w", encoding="utf-8") as handle:
        json.dump({"arm": arm, "projects": list(projects), "missing": missing or {}}, handle)
    return os.path.join(root, "merged")


def _cache(root, name, xml=(), methods=None):
    scratch = os.path.join(root, "unpacked", name, "logs", name)
    os.makedirs(scratch)
    with open(os.path.join(scratch, "baseline-cache.json"), "w", encoding="utf-8") as handle:
        json.dump({"key": "k", "repeats": 2, "already_failing": [list(t) for t in xml],
                   "result_keys": "per-method" if methods is not None else "xml",
                   "already_failing_methods": None if methods is None else [list(t) for t in methods]},
                  handle)


def _page(root, merged, run="41"):
    return sweep_page.page(merged, os.path.join(root, "unpacked"), run)


def _section(text, name):
    start = text.index(f"<summary>{name}:")
    return text[start:text.index("</details>", start)]


def _table(section):
    return [line for line in section.splitlines() if line.startswith("| ") and line[2].isdigit()]


def _cells(line):
    return [cell.strip() for cell in line.strip("|").split(" | ")]


def case_each_project_is_a_section_in_sweep_order_holding_its_rows():
    problems = 0
    with tempfile.TemporaryDirectory() as root:
        rows = [_row("narrow"), _row("forced", outcome="full-run", selected=10),
                _row("quiet", outcome=None, status="stalled", induced=None, already=None)]
        merged = _sweep(root, {"okio": _project("okio", rows), "demo": _project("demo", rows[:1])})
        text = _page(root, merged)
        if not text.index("<summary>okio:") < text.index("<summary>demo:"):
            problems += fail("the sections are not in sweep.json's order")
        okio = _section(text, "okio")
        table = _table(okio)
        if len(table) != 3:
            problems += fail(f"okio's table holds {len(table)} lines, not 3: {table}")
        first = _cells(table[0])
        if first[:4] != ["1", "`Thing.java::body::narrow`", "narrowed", "4"] or first[5] != "12" \
                or first[6] != "10" or first[8] != "31":
            problems += fail(f"the narrowed row is not its recorded figures: {first}")
        if _cells(table[1])[2] != "full-run" or _cells(table[2])[2] != "stalled":
            problems += fail(f"a row's outcome is not its decision, else its status: {table}")
        if "okio: 3 rows, 1 narrowed, 0 missed" not in okio:
            problems += fail(f"okio's summary line: {okio.splitlines()[0]}")
        if "Record: capture 61 s, baseline 40 s (2 repeats), preflight 9 s, 1 rows past the target." \
                not in okio:
            problems += fail("the record's figures are not above the table")
        if "sweep-results" not in text or "sweep-41" not in text or "## Sweep run 41, full arm" not in text:
            problems += fail("the page does not name the run, its arm and where the results are")
    return problems


def case_a_skipped_full_arm_shows_the_complement_marked():
    problems = 0
    with tempfile.TemporaryDirectory() as root:
        rows = [_row("complement", full_arm_skipped=True, full_seconds=None, complement_classes=7,
                     complement_seconds=8.2),
                _row("forced", outcome="full-run", full_arm_skipped=True, full_seconds=None)]
        text = _page(root, _sweep(root, {"demo": _project("demo", rows)}))
        table = _table(_section(text, "demo"))
        complement, forced = _cells(table[0]), _cells(table[1])
        if complement[6] != "7 classes †" or complement[8] != "8":
            problems += fail(f"the complement's classes and seconds are not in the full columns: {complement}")
        if forced[6] != "10 †" or forced[8] != "":
            problems += fail(f"a forced row's skipped full arm is not marked: {forced}")
        if sweep_page.SKIPPED_NOTE not in text:
            problems += fail("the mark is not explained")
    return problems


def case_failed_reads_induced_plus_already_and_missed_counts_the_misses():
    problems = 0
    with tempfile.TemporaryDirectory() as root:
        rows = [_row("missing", missed=["a.T::x", "a.T::y"])]
        text = _page(root, _sweep(root, {"demo": _project("demo", rows)}))
        cells = _cells(_table(_section(text, "demo"))[0])
        if cells[7] != "3 + 1" or cells[9] != "2":
            problems += fail(f"failed {cells[7]!r}, missed {cells[9]!r}, not '3 + 1' and '2'")
        if "demo: 1 rows, 1 narrowed, 2 missed" not in text:
            problems += fail("the summary line does not count the misses")
    return problems


def case_caught_is_induced_less_the_missed_and_the_neither_sets():
    problems = 0
    with tempfile.TemporaryDirectory() as root:
        rows = [_row("one", missed=["a.T::x"], ran_but_passed=["a.T::y"]),
                _row("all", undetermined=["a.T::u"], shared_key=["a.T::s"], container_failed=["a.T::c"],
                     nondeterministic=["a.T::n"])]
        cells = [_cells(line) for line in _table(_section(_page(root, _sweep(
            root, {"demo": _project("demo", rows)})), "demo"))]
        if cells[0][4] != "1":
            problems += fail(f"3 induced, 1 missed, 1 ran but passed: caught {cells[0][4]!r}, not 1")
        if cells[1][4] != "0":
            problems += fail(f"3 induced, 4 neither: caught {cells[1][4]!r}, not 0")
        rows = [_row("twice", ran_but_passed=["a.T::f"], fails_unmutated=["a.T::f"])]
        cells = _cells(_table(_section(_page(root, _sweep(root, {"once": _project("once", rows)})),
                                       "once"))[0])
        if cells[4] != "2":
            problems += fail(f"a test in two sets counts once: caught {cells[4]!r}, not 2")
    return problems


def case_a_row_missing_fields_is_empty_cells_and_the_page_still_exits_0():
    problems = 0
    with tempfile.TemporaryDirectory() as root:
        old = {"target": "A.java::body::x", "status": "checked", "selected_distinct": 3}
        merged = _sweep(root, {"demo": _project("demo", [old])})
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = sweep_page.main([merged, os.path.join(root, "unpacked")])
        cells = _cells(_table(_section(out.getvalue(), "demo"))[0])
        if code != 0 or cells[3] != "3" or any(cells[i] for i in (4, 5, 6, 7, 8, 9)):
            problems += fail(f"exit {code}; an older row's absent fields are not empty cells: {cells}")
    return problems


def case_a_row_without_arms_names_its_status():
    problems = 0
    with tempfile.TemporaryDirectory() as root:
        rows = [{"target": "A.java::body::x", "status": "build-failed"},
                {"target": "B.java::body::y", "status": "rejected"}]
        table = _table(_section(_page(root, _sweep(root, {"demo": _project("demo", rows)})), "demo"))
        for line, status in zip(table, ("build-failed", "rejected")):
            cells = _cells(line)
            if cells[2] != status or any(cells[3:]):
                problems += fail(f"a {status} row: {cells}")
    return problems


def case_missing_projects_are_listed_with_their_reasons_before_the_sections():
    problems = 0
    with tempfile.TemporaryDirectory() as root:
        merged = _sweep(root, {"demo": _project("demo", [_row("x")])},
                        missing={"kafka": "its record job failed"})
        text = _page(root, merged)
        if "- **kafka**: its record job failed" not in text \
                or text.index("kafka") > text.index("<summary>demo:"):
            problems += fail("the missing project is not named with its reason before the sections")
    return problems


def case_the_already_failing_tests_are_each_named_once_after_the_table():
    problems = 0
    with tempfile.TemporaryDirectory() as root:
        merged = _sweep(root, {"demo": _project("demo", [_row("x")]),
                               "okio": _project("okio", [_row("x")])})
        _cache(root, "demo", xml=[("a.T", "x")],
               methods=[("a.T", "x > [1]"), ("a.T", "x > [2]"), ("b.U", "y")])
        _cache(root, "okio", xml=[("c.V", "z")])
        text = _page(root, merged)
        demo = _section(text, "demo")
        named = [line for line in demo.splitlines() if line.startswith("- `")]
        if named != ["- `a.T::x > [1]`", "- `a.T::x > [2]`", "- `b.U::y`"] \
                or demo.index("(3):") < demo.index("| 1 |"):
            problems += fail(f"demo's per-method set is not named once after the table: {named}")
        if "- `c.V::z`" not in _section(text, "okio"):
            problems += fail("a cache of XML keys alone is not named")
    return problems


def case_no_baseline_cache_says_where_the_set_came_from():
    problems = 0
    with tempfile.TemporaryDirectory() as root:
        merged = _sweep(root, {"demo": _project("demo", [_row("x")]),
                               "iso": _project("iso", [_row("x")], paired_with="results/iso.json")})
        text = _page(root, merged)
        if "no baseline cache" not in _section(text, "demo"):
            problems += fail("a project without a cache does not say so")
        if "from the paired file `results/iso.json`" not in _section(text, "iso"):
            problems += fail("a paired arm does not name its paired file")
    return problems


def case_a_project_file_that_is_not_json_is_a_line_and_the_rest_are_written():
    problems = 0
    with tempfile.TemporaryDirectory() as root:
        merged = _sweep(root, {"broken": {}, "demo": _project("demo", [_row("x")])},
                        raw={"broken": "{not json"})
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = sweep_page.main([merged, os.path.join(root, "unpacked")])
        text = out.getvalue()
        if code != 0 or "- **broken**: `broken.json` could not be read" not in text \
                or "<summary>demo:" not in text:
            problems += fail(f"exit {code}; an unreadable file is not a line beside the others: {text}")
    return problems


def case_an_unreadable_sweep_file_or_an_odd_row_is_a_line_and_exits_0():
    problems = 0
    with tempfile.TemporaryDirectory() as root:
        merged = os.path.join(root, "merged")
        os.makedirs(merged)
        for text in ("{not json", "[]"):
            with open(os.path.join(merged, "sweep.json"), "w", encoding="utf-8") as handle:
                handle.write(text)
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                code = sweep_page.main([merged, os.path.join(root, "unpacked")])
            if code != 0 or "could not be read" not in out.getvalue():
                problems += fail(f"sweep.json {text!r}: exit {code}, {out.getvalue()!r}")
    with tempfile.TemporaryDirectory() as root:
        odd = _row("odd", ran_but_passed=5)
        merged = _sweep(root, {"odd": _project("odd", [odd]), "demo": _project("demo", [_row("x")])})
        text = _page(root, merged)
        if "<summary>demo:" not in text or "**odd**: `odd.json` could not be shown" not in text:
            problems += fail(f"a row the page cannot show cost the other projects their sections: {text}")
    return problems


def case_a_shape_set_folder_is_read_as_the_default_set_is():
    problems = 0
    with tempfile.TemporaryDirectory() as root:
        merged = _sweep(root, {"demo": _project("demo", [_row("x")])}, folder="pool-v1")
        if len(_table(_section(_page(root, merged), "demo"))) != 1:
            problems += fail("the shape set's sweep.json and project file were not read")
    return problems


def case_no_sweep_file_is_said_and_exits_0():
    problems = 0
    with tempfile.TemporaryDirectory() as root:
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = sweep_page.main([os.path.join(root, "merged"), os.path.join(root, "unpacked")])
        if code != 0 or "nothing was merged" not in out.getvalue():
            problems += fail(f"exit {code}: {out.getvalue()!r}")
    return problems


def case_a_target_with_a_pipe_stays_in_its_cell():
    problems = 0
    with tempfile.TemporaryDirectory() as root:
        rows = [_row("x", target="src/A.kt::constant::a|b")]
        line = _table(_section(_page(root, _sweep(root, {"demo": _project("demo", rows)})), "demo"))[0]
        if "`A.kt::constant::a\\|b`" not in line:
            problems += fail(f"a pipe in a target is not escaped: {line}")
    return problems


CASES = (
    case_each_project_is_a_section_in_sweep_order_holding_its_rows,
    case_a_skipped_full_arm_shows_the_complement_marked,
    case_failed_reads_induced_plus_already_and_missed_counts_the_misses,
    case_caught_is_induced_less_the_missed_and_the_neither_sets,
    case_a_row_missing_fields_is_empty_cells_and_the_page_still_exits_0,
    case_a_row_without_arms_names_its_status,
    case_missing_projects_are_listed_with_their_reasons_before_the_sections,
    case_the_already_failing_tests_are_each_named_once_after_the_table,
    case_no_baseline_cache_says_where_the_set_came_from,
    case_a_project_file_that_is_not_json_is_a_line_and_the_rest_are_written,
    case_an_unreadable_sweep_file_or_an_odd_row_is_a_line_and_exits_0,
    case_a_shape_set_folder_is_read_as_the_default_set_is,
    case_no_sweep_file_is_said_and_exits_0,
    case_a_target_with_a_pipe_stays_in_its_cell,
)


def main():
    problems = 0
    for case in CASES:
        print(case.__name__)
        problems += case()
    if problems:
        print(f"\n{problems} failed check(s)")
        return 1
    print(f"\n{len(CASES)} cases, all green")
    return 0


def test_sweep_page():
    assert main() == 0


if __name__ == "__main__":
    sys.exit(main())
