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


def case_why_rows_did_not_narrow_is_counted_per_project_and_cause():
    problems = 0
    with tempfile.TemporaryDirectory() as root:
        rows = [_row("narrow", narrowing_cause="narrowed"),
                _row("wide", narrowing_cause="wide:covers"),
                _row("wide2", narrowing_cause="wide:covers"),
                _row("refused", outcome="full-run", narrowing_cause="refused:constant-changed"),
                _row("old")]
        text = _page(root, _sweep(root, {"demo": _project("demo", rows)}))
        table = text[text.index("### Why rows did not narrow"):text.index("### Projects")]
        wanted = ["| demo | `wide:covers` | 2 |", "| demo | `refused:constant-changed` | 1 |",
                  "| demo | `unrecorded` | 1 |"]
        if [line for line in table.splitlines() if line.startswith("| demo")] != wanted:
            problems += fail(f"the cause table: {table}")
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


def case_a_paired_isolated_arm_is_shown_beside_the_shared_one_or_its_reason_is():
    arm = lambda narrowed, tests, seconds, recording: {  # noqa: E731
        "rows": 3, "narrowed": narrowed, "tests_run": tests, "selected_seconds": seconds,
        "recording_seconds": recording}
    states = {"demo": {"status": "ran", "paired_rows": 3, "shared": arm(1, 24, 37.2, 61.2),
                       "isolated": arm(3, 9, None, 140.0)},
              "part": {"status": "ran", "paired_rows": 2, "slices_measured": 1, "slices": 2,
                       "shared": arm(1, 8, 4.0, 61.2), "isolated": arm(2, 2, 3.0, 140.0)},
              "okio": {"status": "did-not-finish", "reason": "1 of 2 slices measured their "
                                                             "isolated arm"},
              "demo-maven": {"status": "not run", "reason": "a Maven project, which has no "
                                                            "isolated capture"}}
    failures = 0
    with tempfile.TemporaryDirectory() as root:
        merged = _sweep(root, {name: _project(name, [_row("a")]) for name in states})
        without = _page(root, merged)
        path = os.path.join(merged, "sweep.json")
        with open(path, encoding="utf-8") as handle:
            sweep = json.load(handle)
        for isolated in (states, {"demo": "odd", "okio": None}):
            with open(path, "w", encoding="utf-8") as handle:
                json.dump({**sweep, "isolated": isolated}, handle)
            text = _page(root, merged)
            if isolated is states:
                shown = text
            elif "<summary>demo:" not in text:
                failures += fail("an odd isolated entry cost the page its projects")
    if "Isolated capture" in without:
        failures += fail("a run without the arm has a section for it")
    if "| demo | 3 | 1 → 3 | 24 → 9 | 37 →  | 61 → 140 |" not in shown:
        failures += fail(f"both arms of demo: {shown.split('### Projects')[0]}")
    if "| part | 2 (1 of 2 slices) | 1 → 2 |" not in shown:
        failures += fail(f"a partly measured arm: {shown.split('### Projects')[0]}")
    for line in ("- **okio**: isolated arm did-not-finish: 1 of 2 slices",
                 "- **demo-maven**: isolated arm not run: a Maven project",
                 "(2 of 4 projects)"):
        if line not in shown:
            failures += fail(f"the page lacks {line!r}")
    if shown.index("Isolated capture") > shown.index("### Projects"):
        failures += fail("the arms are compared after the projects' rows")
    return failures


def _replay_file(root, **over):
    """A merged real-commit figure, as `commit_replay.py --merge` writes it."""
    alpha = {"drawn": 8, "replayed": 6, "narrowed": 2, "selected_fewer": 1, "forced": 3,
             "unrecorded": 1, "causes": {"full-run:unmappable-paths": 2, "refusal:schema": 1},
             "not_asked": {"does-not-apply": 2}, "sums": True}
    beta = {"drawn": 8, "replayed": 2, "narrowed": 1, "selected_fewer": 1, "forced": 1,
            "unrecorded": 0, "causes": {"full-run:unmappable-paths": 1},
            "not_asked": {"budget": 6}, "sums": True}
    figure = {"drawn": 16, "replayed": 8, "narrowed": 3, "selected_fewer": 2, "forced": 4,
              "unrecorded": 1, "causes": {"full-run:unmappable-paths": 3, "refusal:schema": 1},
              "not_asked": {"budget": 6, "does-not-apply": 2}, "sums": True}
    merged = {"schema": "commit-replay/v1", "window": "the newest commits", "commits": ["8"],
              "plugin_commits": ["abc123"], "figure": figure,
              "projects": {"alpha": {"distribution": alpha, "tree_restored": True},
                           "beta": {"distribution": beta, "tree_restored": True}},
              "missing": ["gamma"], "not_replayed": {"demo": "no upstream history"},
              "cut_short": ["beta"], "caveats": ["A share of commits, not of seconds."]}
    merged.update(over)
    path = os.path.join(root, "commit-replay.json")
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(merged, handle)
    return path


def case_the_real_commit_share_is_over_the_replayed_never_the_drawn():
    problems = 0
    with tempfile.TemporaryDirectory() as root:
        text = sweep_page.replay_section(_replay_file(root))
        if "**3 of 8 real commits narrowed (37.5%)**" not in text:
            problems += fail(f"the figure's line reads {text.splitlines()[2]!r}")
        if "16 commits were drawn and 8 not asked" not in text:
            problems += fail("the drawn and the not asked are not counted apart")
        for line in ("| `full-run:unmappable-paths` | 3 | 37.5% |", "| `no kind recorded` | 1 | 12.5% |",
                     "| `budget` | 6 | 37.5% |", "| alpha | 8 | 6 | 2 | 3 | 1 | 2 | "
                     "`full-run:unmappable-paths` |"):
            if line not in text:
                problems += fail(f"the page lacks {line!r}")
        if "- A share of commits, not of seconds." not in text:
            problems += fail("the page does not say what the figure may claim")
    return problems


def case_the_paired_ask_is_a_block_of_its_own_and_absent_without_one():
    problems = 0
    block = {"projects": 2, "replayed": 4, "narrowed": 3, "per_jar": 2, "shares": [0.1, 0.2, 0.5],
             "median_share": 0.2, "forced": 1, "unrecorded": 0, "asked": 4,
             "causes": {"refusal:dependency-added": 1}, "not_asked": {}, "sums": True}
    with tempfile.TemporaryDirectory() as root:
        text = sweep_page.replay_section(_replay_file(root, narrow_dependencies=block))
        for line in ("### Version-only commits with `-Pyoriwake.narrowDependencies`",
                     "**3 narrowed (75.0% of those asked)**, 2 of them forced without it, so by "
                     "the per-jar rule; median share of the tests 20.0%.",
                     "| `refusal:dependency-added` | 1 | 25.0% |",
                     "**3 of 8 real commits narrowed (37.5%)**"):
            if line not in text:
                problems += fail(f"the page lacks {line!r}")
        if "narrowDependencies" in sweep_page.replay_section(_replay_file(root)):
            problems += fail("a merge with no paired ask shows its block")
    return problems


def case_a_project_with_no_replay_file_is_named_not_counted_as_zero():
    problems = 0
    with tempfile.TemporaryDirectory() as root:
        text = sweep_page.replay_section(_replay_file(root))
        for line in ("No file from its record job (1): gamma.",
                     "Stopped by the time budget (1): beta.",
                     "Not replayed (1): demo (no upstream history)."):
            if line not in text:
                problems += fail(f"the page lacks {line!r}")
        if "| gamma |" in text or "| demo |" in text:
            problems += fail("a project that was not replayed has a row of counts")
        if "Tree not put back" in text:
            problems += fail("a tree that was put back is named as one that was not")
        with open(_replay_file(root), encoding="utf-8") as handle:
            dirty = json.load(handle)["projects"]
        dirty["alpha"]["tree_restored"] = False
        text = sweep_page.replay_section(_replay_file(root, projects=dirty))
        if "Tree not put back to the pin (1): alpha." not in text:
            problems += fail("a checkout left off its pin is not named")
        figure = {"drawn": 1, "replayed": 0, "narrowed": 0, "selected_fewer": 0, "forced": 0,
                  "unrecorded": 0, "causes": {}, "not_asked": {"budget": 1}, "sums": False}
        text = sweep_page.replay_section(_replay_file(root, figure=figure))
        if "(n/a)" not in text or "DO NOT ADD UP" not in text:
            problems += fail("no commit replayed, or counts that do not add up, are not said")
    return problems


def case_a_run_that_only_replayed_commits_has_that_section_alone():
    problems = 0
    with tempfile.TemporaryDirectory() as root:
        def printed(*extra):
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                code = sweep_page.main([os.path.join(root, "merged"),
                                        os.path.join(root, "unpacked"), *extra])
            return code, out.getvalue()

        path = _replay_file(root)
        code, text = printed("--commit-replay", path)
        if code != 0 or "real commits narrowed" not in text or "nothing was merged" in text:
            problems += fail(f"exit {code}: {text!r}")
        code, text = printed("--commit-replay", os.path.join(root, "absent.json"))
        if code != 0 or "nothing was merged" not in text:
            problems += fail("an absent figure changed the page of a run with no rows")
        _sweep(root, {"demo": _project("demo", [_row("x")])})
        code, text = printed("--commit-replay", path)
        if code != 0 or not text.index("## Real commits replayed") < text.index("<summary>demo:"):
            problems += fail("a run with rows and a figure does not show both, the figure first")
        with open(path, "w", encoding="utf-8") as handle:
            handle.write("{")
        code, text = printed("--commit-replay", path)
        if code != 0 or "could not be read" not in text:
            problems += fail(f"an unreadable figure: exit {code}")
    return problems


CASES = (
    case_the_real_commit_share_is_over_the_replayed_never_the_drawn,
    case_a_project_with_no_replay_file_is_named_not_counted_as_zero,
    case_a_run_that_only_replayed_commits_has_that_section_alone,
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
    case_a_paired_isolated_arm_is_shown_beside_the_shared_one_or_its_reason_is,
    case_why_rows_did_not_narrow_is_counted_per_project_and_cause,
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
