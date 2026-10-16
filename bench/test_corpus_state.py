"""
Unit tests for the corpus-state instrument.

THE FAILURE THIS EXISTS TO PREVENT is a project that prints `0 missed of 0` and reads as a pass, so
the two zero-evidence verdicts are tested before the happy path and the file is ordered that way.
Every case builds a results tree by hand: the point is what the VERDICT is, and a fixture whose
answer is known is the only way to say that without a sweep.

Run: `python bench/test_corpus_state.py`
"""

import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import corpus_state as cs
import run  # noqa: E402


def git(root, *args):
    subprocess.run(["git", "-C", root, *args], check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def write(root, relative, text):
    path = os.path.join(root, relative)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(text)
    return path


def narrowed_row(**overrides):
    """A row where a miss WAS possible: it broke something and selection ran less than everything."""
    row = {"target": "m/src/main/java/A.java::body::f", "kind": "body", "status": "checked",
           "full_distinct": 100, "full_failures": 1, "selected_distinct": 5,
           "missed": [], "undetermined": [], "nondeterministic": [],
           "decision_outcome": "narrowed", "sweep_limit": 16}
    row.update(overrides)
    return row


def forced_row(**overrides):
    row = {"target": "m/src/main/java/B.java::body::g", "kind": "body", "status": "checked",
           "full_distinct": 100, "full_failures": 1, "selected_distinct": 100,
           "missed": [], "undetermined": [], "nondeterministic": [],
           "decision_outcome": "full-run", "full_run_kind": "constant-changed", "sweep_limit": 16}
    row.update(overrides)
    return row


def results(root, name, rows, stamp="deadbee", targets_available=40, mutations_rejected=None,
            digest=None, digest_root=None, omit_digest=False, header=None):
    # THE DIGEST OF THE TREE THE TEST BUILT, not a literal. Freshness is decided on the selector's
    # content, so a fixture that hardcoded one would go stale the first time anyone edited the
    # selector -- which is the whole failure mode this file exists to catch, reproduced in its own
    # helper. `None` means "write whatever the fixture repo currently hashes to".
    write(root, f"results/{name}.json", json.dumps({
        "project": name, "commit": "c" * 40, "module": "m", "plugin_commit": stamp,
        **({} if omit_digest else {"selector_digest": digest if digest is not None
                                   else run.selector_digest(
                                       digest_root if digest_root is not None else root)}),
        "targets_available": targets_available, "mutations": rows,
        "mutations_rejected": mutations_rejected, **(header or {}),
    }))
    return os.path.join(root, "results")


def table(root, lines):
    return write(root, "qualification.txt", "\n".join(lines) + "\n")


def verdict_of(root, name, rows, *, blockers="none", stamp=None, targets_available=40,
               mutations_rejected=None, digest=None, omit_digest=False,
               project=None, header=None):
    """One project's verdict, over a real one-commit git repo so the stamp question is real."""
    repo = os.path.join(root, "repo")
    os.makedirs(repo, exist_ok=True)
    if not os.path.isdir(os.path.join(repo, ".git")):
        git(repo, "init", "-q", "-b", "main")
        git(repo, "config", "user.email", "t@e")
        git(repo, "config", "user.name", "t")
        write(repo, "plugin/src/main/Selector.kt", "// v1\n")
        git(repo, "add", "-A")
        git(repo, "commit", "-q", "-m", "selector")
    head = subprocess.run(["git", "-C", repo, "rev-parse", "--short", "HEAD"],
                          capture_output=True, text=True, check=True).stdout.strip()
    # HASHED OVER THE FIXTURE REPO, which is where the selector source lives. Hashing `root`
    # produced the empty digest and made every case read stale -- the fixture disagreeing with the
    # thing it is a fixture for.
    directory = results(root, name, rows, stamp=stamp or head, targets_available=targets_available,
                        mutations_rejected=mutations_rejected, digest_root=repo,
                        digest=digest, omit_digest=omit_digest, header=header)
    qualified = cs.qualification(table(root, [
        f"QUALIFIED  {name}  module=m blockers={blockers} tests=10"]))
    return cs.examine(name, directory, qualified, project or {}, repo), repo, head


def case_the_two_subtracted_counts_reach_the_verdict():
    """Both numbers that come OUT of the headline need a reader here, or they stop being written.

    `container_failed` is subtracted from `missed`; `mutations_rejected` is the share of the sample
    the compile gate threw away before any row existed. A project can read `clean` with a fifth of
    its targets discarded, and the verdict has to be able to say so.
    """
    failed = 0
    with tempfile.TemporaryDirectory() as root:
        entry, _repo, _head = verdict_of(
            root, "p",
            [narrowed_row(container_failed=["k.DeleteTopicsRequestTest::initializationError"]),
             narrowed_row()],
            mutations_rejected=3)
        if entry["container_failed"] != 1:
            print(f"  FAIL: container_failed read as {entry['container_failed']!r}, expected 1")
            failed += 1
        if entry["mutations_rejected"] != 3:
            print(f"  FAIL: mutations_rejected read as {entry['mutations_rejected']!r}, expected 3")
            failed += 1

    # ABSENT IS NOT ZERO: a file written before the gate existed says nothing about rejections.
    with tempfile.TemporaryDirectory() as root:
        entry, _repo, _head = verdict_of(root, "p", [narrowed_row()])
        if entry["mutations_rejected"] is not None:
            print(f"  FAIL: an absent field read as {entry['mutations_rejected']!r}, expected None")
            failed += 1
    return failed


def case_rows_with_no_miss_possible_are_no_question_asked():
    """anki-android's shape: real rows, every one of them forced, and `0 missed of 0` on the table."""
    failed = 0
    with tempfile.TemporaryDirectory() as root:
        entry, _, _ = verdict_of(root, "p", [forced_row(), forced_row()])
        if entry["verdict"] != cs.NO_QUESTION_ASKED:
            print(f"  FAIL: {entry['verdict']}, expected {cs.NO_QUESTION_ASKED}")
            failed += 1
        if "constant-changed" not in (entry["reason"] or ""):
            print(f"  FAIL: the forcing causes are not named: {entry['reason']}")
            failed += 1
    return failed


def case_a_declining_plugin_is_cannot_answer_and_never_clean():
    failed = 0
    with tempfile.TemporaryDirectory() as root:
        entry, _, _ = verdict_of(root, "p", [forced_row()], blockers="in-jvm-parallelism")
        if entry["verdict"] != cs.CANNOT_ANSWER:
            print(f"  FAIL: {entry['verdict']}, expected {cs.CANNOT_ANSWER}")
            failed += 1
        if "declines" not in (entry["reason"] or ""):
            print(f"  FAIL: the blocker's own sentence is missing: {entry['reason']}")
            failed += 1
    return failed


def case_a_genuine_miss_is_dirty():
    failed = 0
    with tempfile.TemporaryDirectory() as root:
        entry, _, _ = verdict_of(root, "p", [narrowed_row(missed=["a.B::c"])])
        if entry["verdict"] != cs.DIRTY:
            print(f"  FAIL: {entry['verdict']}, expected {cs.DIRTY}")
            failed += 1
    return failed


def case_only_undetermined_or_nondeterministic_is_not_clean():
    failed = 0
    with tempfile.TemporaryDirectory() as root:
        entry, _, _ = verdict_of(root, "p", [
            narrowed_row(undetermined=["a.B::c"]),
            narrowed_row(nondeterministic=["a.B::d"]),
        ])
        if entry["verdict"] != cs.DIRTY:
            print(f"  FAIL: {entry['verdict']}, expected {cs.DIRTY}")
            failed += 1
    return failed


def case_only_shared_key_failures_are_not_clean():
    """A failure on a key another test may stand for is neither missed nor clean, as undetermined."""
    failed = 0
    with tempfile.TemporaryDirectory() as root:
        entry, _, _ = verdict_of(root, "p", [narrowed_row(shared_key=["a.B::v = 1"])])
        if entry["verdict"] != cs.DIRTY or "shared key" not in entry.get("reason", ""):
            print(f"  FAIL: {entry['verdict']} ({entry.get('reason')}), expected {cs.DIRTY}")
            failed += 1
    return failed


def case_a_build_failed_row_is_dirty():
    """`assert_recall` exits 1 on one of these, so a verdict that called it clean would disagree."""
    failed = 0
    with tempfile.TemporaryDirectory() as root:
        entry, _, _ = verdict_of(root, "p", [narrowed_row(), {"target": "x", "status": "build-failed"}])
        if entry["verdict"] != cs.DIRTY:
            print(f"  FAIL: {entry['verdict']}, expected {cs.DIRTY}")
            failed += 1
    return failed


def case_a_file_from_before_a_selector_commit_is_refused_naming_both_stamps():
    failed = 0
    with tempfile.TemporaryDirectory() as root:
        entry, repo, old = verdict_of(root, "p", [narrowed_row()])
        if entry["verdict"] != cs.CLEAN:
            print(f"  FAIL: the baseline was not clean: {entry['verdict']} {entry['reason']}")
            failed += 1
        # A commit that CHANGES THE SELECTOR, after the sweep.
        write(repo, "plugin/src/main/Selector.kt", "// v2\n")
        git(repo, "add", "-A")
        git(repo, "commit", "-q", "-m", "selector changed")
        qualified = cs.qualification(os.path.join(root, "qualification.txt"))
        entry = cs.examine("p", os.path.join(root, "results"), qualified, {}, repo)
        if entry["verdict"] != cs.STALE_STAMP:
            print(f"  FAIL: {entry['verdict']}, expected {cs.STALE_STAMP}")
            failed += 1
        # BOTH SELECTORS, named as digests rather than as commits. The question the refusal has
        # to answer is "which selector measured this, and which is here now" -- and that
        # is now decided on the selector's content, so a commit sha would name
        # something that no longer governs the verdict.
        now = run.selector_digest(repo)
        reason = entry["reason"] or ""
        if now not in reason:
            print(f"  FAIL: the refusal does not name the current selector {now}: {reason}")
            failed += 1
        if "measured against selector" not in reason:
            print(f"  FAIL: the refusal does not name the selector it was measured against: {reason}")
            failed += 1
    return failed


def case_a_file_with_no_selector_digest_is_stale_not_current():
    """Every results file written before the selector digest existed is this shape.

    Absent is not equal. A file that never recorded which selector measured it cannot be shown to
    describe the one in the tree, and reading it as current publishes rows against an unknown
    selector -- the precise failure the staleness rule exists to prevent.
    """
    failed = 0
    with tempfile.TemporaryDirectory() as root:
        # `omit_digest` leaves the key OUT, which is what every file written before the digest
        # looks like. (A JSON null would be a different shape and is not what those files have.)
        entry, repo, _ = verdict_of(root, "p", [narrowed_row()], digest=None, omit_digest=True)
        if entry["verdict"] != cs.STALE_STAMP:
            print(f"  FAIL: {entry['verdict']}, expected stale-stamp -- a file with no selector "
                  f"digest was read as current: {entry['reason']}")
            failed += 1
        elif "no selector digest" not in (entry["reason"] or ""):
            print(f"  FAIL: the refusal does not say the digest is absent: {entry['reason']}")
            failed += 1
    return failed


def case_a_documentation_commit_does_not_age_a_results_file_out():
    """A HEAD-based stamp gets this wrong, and would falsify the publishing commit's own check."""
    failed = 0
    with tempfile.TemporaryDirectory() as root:
        entry, repo, _ = verdict_of(root, "p", [narrowed_row()])
        write(repo, "docs/benchmarks.md", "a new table\n")
        write(repo, "results/p.json", "{}")
        git(repo, "add", "-A")
        git(repo, "commit", "-q", "-m", "docs and results")
        qualified = cs.qualification(os.path.join(root, "qualification.txt"))
        entry = cs.examine("p", os.path.join(root, "results"), qualified, {}, repo)
        if entry["verdict"] != cs.CLEAN:
            print(f"  FAIL: {entry['verdict']} -- a docs commit aged the sweep out: {entry['reason']}")
            failed += 1
    return failed


def case_a_qualified_project_with_no_results_file_is_never_swept():
    failed = 0
    with tempfile.TemporaryDirectory() as root:
        os.makedirs(os.path.join(root, "results"))
        qualified = cs.qualification(table(root, ["QUALIFIED  p  module=m blockers=none"]))
        entry = cs.examine("p", os.path.join(root, "results"), qualified, {})
        if entry["verdict"] != cs.NEVER_SWEPT:
            print(f"  FAIL: {entry['verdict']}, expected {cs.NEVER_SWEPT}")
            failed += 1
    return failed


def case_a_file_whose_depth_is_unrecorded_is_not_clean():
    """A row that cannot say how hard its project was asked is not publishable evidence."""
    failed = 0
    with tempfile.TemporaryDirectory() as root:
        entry, _, _ = verdict_of(root, "p", [narrowed_row(sweep_limit=None)])
        if entry["verdict"] != cs.UNSTATED_DEPTH:
            print(f"  FAIL: {entry['verdict']}, expected {cs.UNSTATED_DEPTH}")
            failed += 1
    with tempfile.TemporaryDirectory() as root:
        entry, _, _ = verdict_of(root, "p", [narrowed_row()], targets_available=None)
        if entry["verdict"] != cs.UNSTATED_DEPTH:
            print(f"  FAIL: {entry['verdict']}, expected {cs.UNSTATED_DEPTH}")
            failed += 1
    return failed


def case_a_resumed_file_reports_its_depths_rather_than_one_of_them():
    failed = 0
    with tempfile.TemporaryDirectory() as root:
        entry, _, _ = verdict_of(root, "p", [narrowed_row(sweep_limit=8),
                                             narrowed_row(sweep_limit=16)])
        if entry["depth"] != [8, 16]:
            print(f"  FAIL: depth {entry['depth']}, expected [8, 16]")
            failed += 1
    return failed


def case_a_fixture_is_in_no_denominator_and_the_key_is_what_puts_it_there():
    """The half that matters: removing the key moves it INTO the corpus count."""
    failed = 0
    with tempfile.TemporaryDirectory() as root:
        marked, _, _ = verdict_of(root, "p", [narrowed_row()], project={"fixture": True})
        if not marked["fixture"]:
            print("  FAIL: `fixture = true` did not mark the entry")
            failed += 1
    with tempfile.TemporaryDirectory() as root:
        plain, _, _ = verdict_of(root, "p", [narrowed_row()], project={})
        if plain["fixture"]:
            print("  FAIL: a project with no key was filed as a fixture")
            failed += 1
    # And the four files that actually carry it, so the key cannot quietly disappear from them.
    for name in ("demo", "demo-android", "demo-kotlin", "detekt-junk"):
        if not cs.load_project(name).get("fixture"):
            print(f"  FAIL: {name}.toml does not carry `fixture = true`, so it would enter the "
                  f"corpus denominator as a project")
            failed += 1
    return failed


def case_an_unstable_guest_leaves_the_denominator_and_says_why():
    """kafka-core: a guest whose own tests fail unmutated cannot evidence recall.

    The exclusion must state its evidence. A project dropped from a denominator on a bare boolean
    is the sampling bias this corpus has already withdrawn a figure over, so a value that is not a
    non-empty string is refused rather than honoured.
    """
    failed = 0
    why = "three unmutated runs gave three different failing sets"
    with tempfile.TemporaryDirectory() as root:
        entry, _, _ = verdict_of(root, "p", [narrowed_row()], project={"unstable_suite": why})
        if entry["verdict"] != cs.UNSTABLE_SUITE:
            print(f"  FAIL: a declared unstable guest was {entry['verdict']}, not unstable-suite")
            failed += 1
        if entry["reason"] != why:
            print(f"  FAIL: the declared evidence did not reach the verdict ({entry['reason']!r})")
            failed += 1
        # The measurement is not erased by the exclusion -- only what the figure is drawn from.
        if entry["rows"] != 1:
            print(f"  FAIL: the excluded project stopped reporting its rows ({entry['rows']})")
            failed += 1
    for bad, what in ((True, "a bare boolean"), ("", "an empty string"), ("   ", "blank space")):
        with tempfile.TemporaryDirectory() as root:
            entry, _, _ = verdict_of(root, "p", [narrowed_row()], project={"unstable_suite": bad})
            if entry["verdict"] == cs.UNSTABLE_SUITE:
                print(f"  FAIL: {what} excluded a project while stating no evidence")
                failed += 1
    with tempfile.TemporaryDirectory() as root:
        plain, _, _ = verdict_of(root, "p", [narrowed_row()], project={})
        if plain["verdict"] == cs.UNSTABLE_SUITE:
            print("  FAIL: a project with no key was excluded as unstable")
            failed += 1
    # And the file that actually carries it, so the exclusion cannot quietly lose its evidence.
    declared = cs.load_project("kafka-core").get("unstable_suite")
    if not isinstance(declared, str) or not declared.strip():
        print("  FAIL: kafka-core.toml does not carry `unstable_suite` with its evidence")
        failed += 1
    return failed


def case_the_composition_counts_only_rows_where_a_miss_was_possible():
    """A fixture tree whose answer is known by hand, reproduced through the public surface."""
    failed = 0
    rows = [narrowed_row(), narrowed_row(), forced_row(),
            narrowed_row(status="no-effect", full_failures=0)]
    possible = sum(1 for row in rows if cs.miss_was_possible(row))
    if possible != 2:
        print(f"  FAIL: {possible} rows where a miss was possible, expected 2")
        failed += 1
    with tempfile.TemporaryDirectory() as root:
        entry, _, _ = verdict_of(root, "p", rows)
        if entry["miss_possible"] != 2 or entry["rows"] != 4:
            print(f"  FAIL: {entry['miss_possible']} of {entry['rows']}, expected 2 of 4")
            failed += 1
        if entry["verdict"] != cs.CLEAN:
            print(f"  FAIL: {entry['verdict']}, expected {cs.CLEAN}: {entry['reason']}")
            failed += 1
    return failed


def case_a_blocked_project_is_cannot_answer_rather_than_absent():
    failed = 0
    with tempfile.TemporaryDirectory() as root:
        os.makedirs(os.path.join(root, "results"), exist_ok=True)
        table(root, ["BLOCKED    p  the build failed (exit 1) before anything was captured"])
        entries = cs.survey(os.path.join(root, "results"),
                            os.path.join(root, "qualification.txt"))
        if len(entries) != 1 or entries[0]["verdict"] != cs.CANNOT_ANSWER:
            print(f"  FAIL: {entries}")
            failed += 1
    return failed


def case_a_project_whose_rows_carry_no_decision_cannot_answer():
    """moshi and kotlinx-serialization. Both ran every test in both arms and recorded
    no account of why, so neither row set is evidence about selection. `sweep_corpus` gates on this
    and this did not, which would have published them as `no-question-asked` -- a verdict whose
    meaning is "rows ran and none could have missed anything"."""
    failed = 0
    with tempfile.TemporaryDirectory() as root:
        blind = {"target": "x", "status": "checked", "full_distinct": 80, "selected_distinct": 80,
                 "full_failures": 1, "missed": [], "undetermined": [], "nondeterministic": [],
                 "sweep_limit": 16}
        entry, _, _ = verdict_of(root, "p", [blind, dict(blind, target="y")],
                                 blockers="not-on-junit-platform")
        if entry["verdict"] != cs.CANNOT_ANSWER:
            print(f"  FAIL: {entry['verdict']}, expected {cs.CANNOT_ANSWER}")
            failed += 1
        # The reason must name the MECHANISM a reader can act on, not just the symptom.
        if "JUnit Platform" not in (entry["reason"] or ""):
            print(f"  FAIL: the reason does not name the mechanism: {entry['reason']}")
            failed += 1
    # One attributed row among unattributed ones is NOT the vacuous case.
    with tempfile.TemporaryDirectory() as root:
        blind = {"target": "x", "status": "checked", "full_distinct": 80, "selected_distinct": 80,
                 "full_failures": 1, "missed": [], "undetermined": [], "nondeterministic": [],
                 "sweep_limit": 16}
        entry, _, _ = verdict_of(root, "p", [blind, narrowed_row()])
        if entry["verdict"] == cs.CANNOT_ANSWER:
            print("  FAIL: one unattributed row made the whole project unanswerable")
            failed += 1
        if entry.get("unattributed_rows") != 1:
            print(f"  FAIL: unattributed_rows={entry.get('unattributed_rows')}, expected 1")
            failed += 1
    return failed


def case_a_row_that_died_in_the_build_is_not_clean():
    """`run.py` writes six statuses and this read two. A project whose rows died printed `clean`."""
    failed = 0
    with tempfile.TemporaryDirectory() as root:
        entry, _, _ = verdict_of(root, "p", [narrowed_row(), {"target": "x", "status": "did-not-build"}])
        if entry["verdict"] != cs.DIRTY:
            print(f"  FAIL: {entry['verdict']}, expected {cs.DIRTY} -- a dead row was invisible")
            failed += 1
    with tempfile.TemporaryDirectory() as root:
        entry, _, _ = verdict_of(root, "p", [{"target": "x", "status": "stalled"}])
        if entry["verdict"] == cs.CLEAN:
            print("  FAIL: a project whose only row stalled reported clean")
            failed += 1
        if "the mutation broke nothing" in (entry["reason"] or ""):
            print("  FAIL: a stalled row was blamed on a harmless mutation")
            failed += 1
    return failed


def case_a_stale_file_reports_the_misses_it_records():
    """A recorded miss is evidence about a selector whether or not that selector has moved."""
    failed = 0
    with tempfile.TemporaryDirectory() as root:
        entry, repo, _ = verdict_of(root, "p", [narrowed_row(missed=["a.B::c", "a.B::d"])])
        write(repo, "plugin/src/main/Selector.kt", "// v2\n")
        git(repo, "add", "-A")
        git(repo, "commit", "-q", "-m", "selector changed")
        qualified = cs.qualification(os.path.join(root, "qualification.txt"))
        entry = cs.examine("p", os.path.join(root, "results"), qualified, {}, repo)
        if entry["verdict"] != cs.STALE_STAMP:
            print(f"  FAIL: {entry['verdict']}, expected {cs.STALE_STAMP}")
            failed += 1
        if entry["missed"] != 2:
            print(f"  FAIL: missed={entry['missed']}, expected 2 -- an unmeasured zero was published")
            failed += 1
    return failed


def case_an_empty_or_missing_qualification_table_is_refused():
    """`all([])` is True, so an empty corpus printed a zero composition and exited 0."""
    import subprocess as sp
    failed = 0
    with tempfile.TemporaryDirectory() as root:
        os.makedirs(os.path.join(root, "results"), exist_ok=True)
        for table in (None, ""):
            if table is not None:
                write(root, "qualification.txt", table)
            done = sp.run([sys.executable, os.path.join(HERE, "corpus_state.py"),
                           "--results", os.path.join(root, "results"),
                           "--qualification", os.path.join(root, "qualification.txt")],
                          capture_output=True, text=True)
            if done.returncode == 0:
                print(f"  FAIL: exited 0 on a {'missing' if table is None else 'empty'} table")
                failed += 1
            if "refusing" not in (done.stderr + done.stdout):
                print("  FAIL: the refusal did not say it was refusing")
                failed += 1
    return failed


def case_the_json_mode_gates_too():
    """--json is the mode a table generator uses, and it returned 0 on a corpus with a miss."""
    import subprocess as sp
    failed = 0
    with tempfile.TemporaryDirectory() as root:
        verdict_of(root, "p", [narrowed_row(missed=["a.B::c"])])
        done = sp.run([sys.executable, os.path.join(HERE, "corpus_state.py"), "--json",
                       "--results", os.path.join(root, "results"),
                       "--qualification", os.path.join(root, "qualification.txt")],
                      capture_output=True, text=True)
        if done.returncode == 0:
            print("  FAIL: --json exited 0 on a corpus with a missed failure")
            failed += 1
    return failed


def case_a_file_from_before_the_grammar_stamp_is_reported_unstamped_not_matching():
    """A grammar change ages nothing, and it must still be visible beside the figure.

    A file with no `grammar_digest` cannot say which rules drew it, so it is named as unstamped --
    counting it as drawn under the current grammar would be the matching claim nothing supports.
    """
    failed = 0
    with tempfile.TemporaryDirectory() as root:
        entry, _repo, _head = verdict_of(root, "p", [narrowed_row()])
        if entry.get("grammar") is not None:
            print(f"  FAIL: a file with no grammar stamp read as {entry.get('grammar')!r}")
            failed += 1
        if entry["verdict"] != cs.CLEAN:
            print(f"  FAIL: a missing grammar stamp changed the verdict to {entry['verdict']}")
            failed += 1
    counted = [{"project": "now", "grammar": "aaa"}, {"project": "old", "grammar": None},
               {"project": "other", "grammar": "bbb"}]
    line = cs.grammar_line(counted, "aaa")
    for expected, what in (("1 of 3 project(s)", "only the matching file counted as drawn under it"),
                           ("unstamped, written before the stamp existed: old", "the unstamped file"),
                           ("drawn under another grammar: other", "the file from another grammar")):
        if expected not in line:
            print(f"  FAIL: {what} is not reported: {line!r}")
            failed += 1
    return failed


def case_every_case_in_this_file_is_registered():
    """A test that is defined and never called has never run, and looks like one that passes."""
    import re as _re
    source = open(__file__, encoding="utf-8").read()
    defined = {m.group(1) for m in _re.finditer(r"^def (case_\w+)", source, _re.M)}
    orphans = sorted(defined - {c.__name__ for c in CASES})
    if orphans:
        print(f"  FAIL: {len(orphans)} case(s) defined and never registered: {', '.join(orphans)}")
        return 1
    return 0


def case_a_file_assert_recall_refuses_is_never_clean():
    """Clean means `assert_recall` passes, and it refuses in two shapes this verdict once passed.

    Above the rejection ceiling the sample was shaped by the generator. And a row whose every
    induced failure was a container or a test failing unmutated broke nothing selection could skip,
    so it is neither a row where a miss was possible nor an answered one.
    """
    failed = 0
    with tempfile.TemporaryDirectory() as root:
        entry, _, _ = verdict_of(root, "p", [narrowed_row() for _ in range(16)],
                                 mutations_rejected=5)
        if entry["verdict"] != cs.DIRTY or "ceiling" not in entry["reason"]:
            print(f"  FAIL: 5 rejected of 21 offered read {entry['verdict']}: {entry['reason']}")
            failed += 1
    with tempfile.TemporaryDirectory() as root:
        entry, _, _ = verdict_of(root, "p", [narrowed_row() for _ in range(16)],
                                 mutations_rejected=1)
        if entry["verdict"] != cs.CLEAN:
            print(f"  FAIL: 1 rejected of 17 offered read {entry['verdict']}, expected clean")
            failed += 1
    with tempfile.TemporaryDirectory() as root:
        entry, _, _ = verdict_of(root, "p", [
            narrowed_row(container_failed=["k.ATest::initializationError"]),
            narrowed_row(fails_unmutated=["k.BTest::alwaysRed"])])
        if entry["verdict"] != cs.DIRTY or entry["miss_possible"]:
            print(f"  FAIL: rows whose every failure was set aside read {entry['verdict']} with "
                  f"{entry['miss_possible']} miss-possible row(s): {entry['reason']}")
            failed += 1
    return failed


def case_a_setup_forced_project_is_failed_and_its_rows_count_nowhere():
    """A project stopped as setup-forced is a failure with its reason, never clean."""
    failed = 0
    stopped = {"project_outcome": run.SETUP_FORCED,
               "setup_forced": {"reason": "changed paths the coverage map cannot see: core/derby.log",
                                "at": "preflight", "rows": []}}
    with tempfile.TemporaryDirectory() as root:
        entry, _, _ = verdict_of(root, "p", [narrowed_row()], header=stopped)
        if entry["verdict"] != cs.SETUP_FORCED or "core/derby.log" not in (entry["reason"] or ""):
            print(f"  FAIL: a setup-forced project read as {entry['verdict']}: {entry['reason']}")
            failed += 1
    with tempfile.TemporaryDirectory() as root:
        entry, _, _ = verdict_of(root, "p", [narrowed_row()],
                                 header={"project_outcome": "measured"})
        if entry["verdict"] != cs.CLEAN:
            print(f"  FAIL: a measured project read as {entry['verdict']}")
            failed += 1
    return failed


def case_a_file_holding_control_rows_is_refused():
    """A planted-miss control's rows are in no figure, wherever the file was copied to."""
    failed = 0
    with tempfile.TemporaryDirectory() as root:
        entry, _, _ = verdict_of(root, "p", [narrowed_row(control=True)])
        if entry["verdict"] != cs.DIRTY or "control" not in (entry["reason"] or ""):
            print(f"  FAIL: a file holding control rows read as {entry['verdict']}")
            failed += 1
    return failed


def case_a_results_directory_holds_one_shape_set_and_no_history_rows():
    """A figure drawn from one directory speaks for one population."""
    failed = 0
    with tempfile.TemporaryDirectory() as root:
        entry, _, _ = verdict_of(root, "p", [narrowed_row()], header={"target_shapes": "recall-arm"})
        if entry["verdict"] != cs.DIRTY or "recall-arm" not in (entry["reason"] or ""):
            print(f"  FAIL: a recall-arm file in the default directory read as {entry['verdict']}")
            failed += 1
    with tempfile.TemporaryDirectory() as root:
        entry, repo, _ = verdict_of(root, "p", [narrowed_row()], header={"target_shapes": "recall-arm"})
        named = cs.examine("p", os.path.join(root, "results"), cs.qualification(
            table(root, ["QUALIFIED  p  module=m blockers=none tests=10"])), {}, repo, "recall-arm")
        if named["verdict"] == cs.DIRTY and "set" in (named["reason"] or ""):
            print(f"  FAIL: a named set's own file was refused in its own directory: {named['reason']}")
            failed += 1
    with tempfile.TemporaryDirectory() as root:
        entry, _, _ = verdict_of(root, "p", [narrowed_row(), {**narrowed_row(), "kind": "history"}])
        if entry["verdict"] != cs.DIRTY or "history" not in (entry["reason"] or ""):
            print(f"  FAIL: a history row among mutation rows read as {entry['verdict']}")
            failed += 1
    return failed


CASES = (case_a_setup_forced_project_is_failed_and_its_rows_count_nowhere,
         case_a_results_directory_holds_one_shape_set_and_no_history_rows,
         case_a_file_holding_control_rows_is_refused,
         case_a_file_assert_recall_refuses_is_never_clean,
         case_the_two_subtracted_counts_reach_the_verdict,
         case_a_file_with_no_selector_digest_is_stale_not_current,
         case_rows_with_no_miss_possible_are_no_question_asked,
         case_a_project_whose_rows_carry_no_decision_cannot_answer,
         case_a_row_that_died_in_the_build_is_not_clean,
         case_a_stale_file_reports_the_misses_it_records,
         case_an_empty_or_missing_qualification_table_is_refused,
         case_the_json_mode_gates_too,
         case_every_case_in_this_file_is_registered,
         case_a_declining_plugin_is_cannot_answer_and_never_clean,
         case_a_genuine_miss_is_dirty,
         case_only_undetermined_or_nondeterministic_is_not_clean,
         case_only_shared_key_failures_are_not_clean,
         case_a_build_failed_row_is_dirty,
         case_a_file_from_before_a_selector_commit_is_refused_naming_both_stamps,
         case_a_documentation_commit_does_not_age_a_results_file_out,
         case_a_qualified_project_with_no_results_file_is_never_swept,
         case_a_file_whose_depth_is_unrecorded_is_not_clean,
         case_a_resumed_file_reports_its_depths_rather_than_one_of_them,
         case_a_fixture_is_in_no_denominator_and_the_key_is_what_puts_it_there,
         case_an_unstable_guest_leaves_the_denominator_and_says_why,
         case_the_composition_counts_only_rows_where_a_miss_was_possible,
         case_a_blocked_project_is_cannot_answer_rather_than_absent,
         case_a_file_from_before_the_grammar_stamp_is_reported_unstamped_not_matching)


def test_corpus_state():
    """The pytest entry point. Asserts, where the cases only count.

    Without it pytest collects nothing from this file, and the suite that decides whether a
    project's recall evidence counts as clean runs only when a human types the command.
    """
    assert sum(case() for case in CASES) == 0


def main():
    failed = 0
    for case in CASES:
        print(case.__name__)
        failed += case()
    print(f"\n{'all passed' if not failed else 'FAILURES'}: {failed} failed checks")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
