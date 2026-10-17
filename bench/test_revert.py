"""Tests for `revert.py`: recent commits reverted at the pin, as rows of their own.

Cases return a count of failed checks; one `test_*` entry point asserts.

    python3 bench/test_revert.py
"""

import os
import shutil
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import revert  # noqa: E402
import run  # noqa: E402

MAIN = "core/src/main/java/dev/sample"
TEST = "core/src/test/java/dev/sample"
OTHER = "other/src/main/java/dev/sample"


def fail(message):
    print(f"  FAIL: {message}")
    return 1


def java(name, body="", public=True):
    return (f"package dev.sample;\n\n{'public ' if public else ''}class {name} {{\n{body}}}\n")


def method(signature, statement):
    return f"    {signature} {{\n        {statement}\n    }}\n\n"


def check(name, statement):
    """A test class: a `main` that throws when the code under test misbehaves."""
    return java(name, method("public static void main(String[] args)",
                             f"if (!({statement})) throw new AssertionError(\"{name}\");"))


class Repo:
    """A throwaway repository, one commit per scenario, its last commit the pin."""

    def __init__(self, root):
        self.root = root
        self.shas = {}
        os.makedirs(root)
        self.git("init", "-q", "-b", "main")

    def git(self, *args):
        return subprocess.run(["git", "-C", self.root, "-c", "user.email=t@t", "-c", "user.name=t",
                               "-c", "commit.gpgsign=false", *args],
                              check=True, capture_output=True, text=True).stdout

    def write(self, path, text, binary=False):
        full = os.path.join(self.root, path)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "wb" if binary else "w") as handle:
            handle.write(text)

    def read(self, path):
        full = os.path.join(self.root, path)
        return open(full, encoding="utf-8").read() if os.path.exists(full) else None

    def commit(self, name, changes=None):
        for path, text in (changes or {}).items():
            if text is None:
                self.git("rm", "-q", path)
            else:
                self.write(path, text)
        self.git("add", "-A")
        self.git("commit", "-q", "-m", name)
        self.shas[name] = self.git("rev-parse", "HEAD").strip()


def build_history(root):
    """Every scenario of the unit, each in files of its own so no commit moves another's context."""
    repo = Repo(root)
    files = {
        f"{MAIN}/Alpha.java": java("Alpha", method("public int twice(int n)", "return n * 2;")),
        f"{TEST}/AlphaTest.java": check("AlphaTest", "new Alpha().twice(2) == 4"),
        f"{MAIN}/Calc.java": java("Calc", method("public int one()", "return 1;")),
        f"{TEST}/CalcTest.java": check("CalcTest", "new Calc().one() == 1"),
        f"{MAIN}/Old.java": java("Old"),
        f"{MAIN}/Beta.java": java("Beta", method("int b()", "return 1;"), public=False),
        f"{MAIN}/Delta.java": java("Delta"),
        f"{MAIN}/Echo.java": java("Echo", method("int e()", "return 1;")),
        f"{OTHER}/Gamma.java": java("Gamma", method("int g()", "return 1;")),
        f"{MAIN}/Foxtrot.java": java("Foxtrot", method("int f()", "return 1;")),
        f"{MAIN}/Golf.java": java("Golf", method("int g()", "return 1;")),
        f"{MAIN}/Hotel.java": java("Hotel", method("int h()", "return 1;")),
        f"{MAIN}/India.java": java("India", method("int i()", "return 1;")),
        f"{TEST}/JulietTest.java": check("JulietTest", "true"),
        "docs/guide.md": "guide\n",
    }
    repo.commit("init", files)

    def edit(path, old, new):
        text = repo.read(path)
        assert old in text, (path, old)
        return text.replace(old, new)

    repo.commit("body", {
        f"{MAIN}/Alpha.java": edit(f"{MAIN}/Alpha.java", "n * 2", "n * 3"),
        f"{TEST}/AlphaTest.java": edit(f"{TEST}/AlphaTest.java", "== 4", "== 6")})
    repo.commit("tests only", {f"{TEST}/JulietTest.java":
                                 check("JulietTest", "1 == 1")})
    repo.commit("docs only", {"docs/guide.md": "guide, again\n"})
    repo.commit("adds a method", {
        f"{MAIN}/Calc.java":
            edit(f"{MAIN}/Calc.java", "    }\n", "    }\n\n" + method("public int three()",
                                                                          "return 3;").rstrip("\n")
                 + "\n"),
        f"{TEST}/CalcTest.java": check("CalcTest", "new Calc().three() == 3")})
    repo.commit("deletes a file", {f"{MAIN}/Old.java": None})
    repo.commit("adds a file", {f"{MAIN}/Fresh.java": java("Fresh")})
    repo.git("mv", f"{MAIN}/Beta.java", f"{MAIN}/BetaMoved.java")
    repo.commit("renames inside", {f"{MAIN}/BetaMoved.java":
                                     java("Beta", method("int b()", "return 2;"), public=False)})
    repo.git("mv", f"{MAIN}/Delta.java", f"{OTHER}/Delta.java")
    repo.commit("renames across")
    repo.commit("two modules", {
        f"{MAIN}/Echo.java": edit(f"{MAIN}/Echo.java", "return 1", "return 2"),
        f"{OTHER}/Gamma.java": edit(f"{OTHER}/Gamma.java", "return 1", "return 2"),
        "core/src/main/resources/app.properties": "key=value\n"})
    repo.commit("comment only", {f"{MAIN}/Foxtrot.java":
                                   edit(f"{MAIN}/Foxtrot.java", "    int f()",
                                        "    // why f exists\n    int f()")})
    repo.write(f"{MAIN}/blob.bin", bytes(range(256)) * 4, binary=True)
    repo.commit("binary", {f"{MAIN}/Golf.java":
                             edit(f"{MAIN}/Golf.java", "return 1", "return 2")})
    seven = method("int seven()", "return 7;")
    hotel = repo.read(f"{MAIN}/Hotel.java")
    repo.commit("adds seven", {f"{MAIN}/Hotel.java":
                                 hotel.replace("    int h()", seven + "    int h()")})
    repo.commit("removes seven", {f"{MAIN}/Hotel.java": hotel})
    repo.commit("adds seven again", {f"{MAIN}/Hotel.java":
                                       hotel.replace("    int h()", seven + "    int h()")})
    repo.commit("india b", {f"{MAIN}/India.java":
                              edit(f"{MAIN}/India.java", "return 1", "return 2")})
    repo.commit("india c", {f"{MAIN}/India.java":
                              edit(f"{MAIN}/India.java", "return 2", "return 3")})
    return repo


def project_for(repo):
    return {"name": "sample", "commit": repo.shas["india c"], "module": "core",
            "sources": ["core/src/main/java"]}


def javac_build(root):
    """A stand-in for the gate: javac over the module's main code, then its tests, logging the
    task that failed the way Gradle does."""
    def build(log_path):
        out = tempfile.mkdtemp(dir=os.path.dirname(log_path))
        try:
            for task, folder in (("compileJava", "src/main/java"),
                                 ("compileTestJava", "src/test/java")):
                sources = [os.path.join(d, f)
                           for d, _, fs in os.walk(os.path.join(root, "core", folder))
                           for f in fs if f.endswith(".java")]
                done = subprocess.run(["javac", "-d", out, "-cp", out, *sources],
                                      capture_output=True, text=True)
                if done.returncode:
                    with open(log_path, "w", encoding="utf-8") as handle:
                        handle.write(done.stderr + f"\n> Task :core:{task} FAILED\n")
                    return 1
            with open(log_path, "w", encoding="utf-8") as handle:
                handle.write("BUILD SUCCESSFUL\n")
            return 0
        finally:
            shutil.rmtree(out, ignore_errors=True)
    return build


def runs(root, test):
    """Whether one of the fixture's test classes passes against the tree as it stands."""
    out = tempfile.mkdtemp()
    try:
        sources = [os.path.join(d, f) for folder in ("src/main/java", "src/test/java")
                   for d, _, fs in os.walk(os.path.join(root, "core", folder)) for f in fs
                   if f.endswith(".java")]
        subprocess.run(["javac", "-d", out, *sources], check=True, capture_output=True)
        return subprocess.run(["java", "-cp", out, f"dev.sample.{test}"],
                              capture_output=True).returncode == 0
    finally:
        shutil.rmtree(out, ignore_errors=True)


def reset(root):
    subprocess.run(["git", "-C", root, "reset", "-q", "--hard"], check=True)
    subprocess.run(["git", "-C", root, "clean", "-fdq"], check=True)
    return True


FIXTURE = {}


def fixture():
    """One history for every case, built once; each case resets the tree before it edits it."""
    if not FIXTURE:
        tmp = tempfile.mkdtemp()
        repo = build_history(os.path.join(tmp, "repo"))
        project = project_for(repo)
        ordered, not_eligible = revert.candidates(repo.root, project, seed=7, window=50)
        FIXTURE.update(tmp=tmp, repo=repo, project=project, ordered=ordered,
                       not_eligible=not_eligible, by_sha={c["sha"]: c for c in ordered})
    reset(FIXTURE["repo"].root)
    return FIXTURE


def eligible(name):
    f = fixture()
    return f["by_sha"].get(f["repo"].shas[name])


def case_a_revert_restores_the_main_code_only_and_the_pinned_test_then_fails():
    failures = 0
    f = fixture()
    repo, candidate = f["repo"], eligible("body")
    if candidate is None:
        return fail("the body commit is not eligible")
    if candidate["paths"] != [f"{MAIN}/Alpha.java"]:
        failures += fail(f"the revert reaches beyond the roots: {candidate['paths']}")
    test_before = repo.read(f"{TEST}/AlphaTest.java")
    if not runs(repo.root, "AlphaTest"):
        failures += fail("the fixture's test fails at the pin")
    if not revert.apply(repo.root, candidate):
        return failures + fail("the reverse patch did not apply")
    if "n * 2" not in repo.read(f"{MAIN}/Alpha.java"):
        failures += fail("Alpha.java is not as it was before the commit")
    if repo.read(f"{TEST}/AlphaTest.java") != test_before:
        failures += fail("the pinned test was reverted too")
    if runs(repo.root, "AlphaTest"):
        failures += fail("the pinned test passes against the reverted code")
    return failures


def case_a_reverse_that_does_not_apply_is_counted_and_takes_no_slot():
    f = fixture()
    failures = 0
    if eligible("india b") is not None:
        failures += fail("a commit whose lines a later commit changed was drawn")
    if f["not_eligible"].get("does-not-apply", 0) < 1:
        failures += fail(f"no does-not-apply count: {f['not_eligible']}")
    if eligible("india c") is None:
        failures += fail("the later commit, which does revert cleanly, was not drawn")
    return failures


def case_a_commit_touching_only_tests_or_docs_is_not_eligible():
    f = fixture()
    failures = 0
    for name in ("tests only", "docs only"):
        if eligible(name) is not None:
            failures += fail(f"{name} was drawn")
    if f["not_eligible"].get("no-main-code", 0) < 2:
        failures += fail(f"no-main-code counted {f['not_eligible'].get('no-main-code')}")
    return failures


def case_a_test_compile_failure_is_retried_as_the_full_revert():
    f = fixture()
    repo, candidate = f["repo"], eligible("adds a method")
    if candidate is None:
        return fail("the commit that adds a method is not eligible")
    failures = 0
    revert.apply(repo.root, candidate)
    logs = os.path.join(f["tmp"], "logs-full")
    os.makedirs(logs, exist_ok=True)
    code, variant, reason = revert.gate(repo.root, candidate, javac_build(repo.root),
                                        lambda: reset(repo.root), os.path.join(logs, "gate.log"))
    if (code, variant, reason) != (0, "full", None):
        failures += fail(f"gate gave {(code, variant, reason)}, not a surviving full revert")
    if "three" in repo.read(f"{TEST}/CalcTest.java"):
        failures += fail("the full revert left the pinned test that calls the added method")
    if "three" in repo.read(f"{MAIN}/Calc.java"):
        failures += fail("the full revert left the added method")
    fields = run.revert_fields(candidate, variant)
    if fields["revert_variant"] != "full" or f"{TEST}/CalcTest.java" in fields["revert_scope"]:
        failures += fail(f"the row's fields do not say the tests were reverted: {fields}")
    if "member-deleted" not in fields["revert_labels"] or "member-added" in fields["revert_labels"]:
        failures += fail(f"an added method reverted is not a deleted member: "
                         f"{fields['revert_labels']}")
    return failures


def case_a_test_compile_failure_counts_as_build_failed_again():
    """The other variant fails the gate too: the row is a counted test-compile rejection."""
    failures = 0
    f = fixture()
    repo, candidate = f["repo"], eligible("adds a method")
    revert.apply(repo.root, candidate)
    logs = os.path.join(f["tmp"], "logs-twice")
    os.makedirs(logs, exist_ok=True)

    def broken(log_path):
        with open(log_path, "w", encoding="utf-8") as handle:
            handle.write("error: cannot find symbol\n"
                         "Execution failed for task ':core:compileTestJava'.\n")
        return 1

    code, variant, reason = revert.gate(repo.root, candidate, broken, lambda: reset(repo.root),
                                        os.path.join(logs, "gate.log"))
    if (variant, reason) != ("full", "test-compile"):
        failures += fail(f"a full revert failing its tests gave {(variant, reason)}")
    counts = revert.rejection_counts([[revert.label(candidate), reason, variant,
                                       candidate["labels"]]])
    if counts.get("test-compile", {}).get("count") != 1 \
            or counts["test-compile"]["labels"].get("member-added") != 1:
        failures += fail(f"rejections are not counted by reason and census label: {counts}")
    return failures


def case_the_gate_names_each_rejection_from_the_log():
    failures = 0
    for log, want in (
            ("> Task :core:compileJava FAILED\nerror: cannot find symbol", "main-compile"),
            ("> Task :core:compileKotlin FAILED\ne: Unresolved reference\n"
             "> Compilation error. See log for more details", "main-compile"),
            ("error: cannot find symbol\nExecution failed for task ':lib:compileJava'.",
             "main-compile"),
            ("> Task :okio:compileTestKotlinJvm FAILED\n> Compilation error", "test-compile"),
            ("> Task :app:compileDebugUnitTestKotlin FAILED\n> Compilation error", "test-compile"),
            ("Could not resolve all files for configuration ':core:compileClasspath'.\n"
             "> Task :core:compileJava FAILED\nerror: package org.sample does not exist", None),
            ("Could not resolve org.sample:lib:1.0.\n> Connection reset", None),
            ("> Task :core:compileJava FAILED\n> java.lang.OutOfMemoryError: Java heap space",
             None),
            ("> Task :okio:compileKotlinJvm FAILED\ne: java.lang.OutOfMemoryError\n"
             "Internal compiler error", None),
            ("> Task :core:compileJava FAILED\nGradle build daemon disappeared unexpectedly", None),
            ("> Task :core:checkstyleMain FAILED", "style-gate"),
            ("> Task :core:spotlessJavaCheck FAILED", "style-gate"),
            ("Gradle build daemon disappeared unexpectedly", None)):
        got = revert.gate_failure(log)
        if got != want:
            failures += fail(f"{log!r} read as {got}, not {want}")
    return failures


def case_deleting_and_adding_a_file_revert_to_its_return_and_its_removal():
    failures = 0
    f = fixture()
    repo = f["repo"]
    deleted, added = eligible("deletes a file"), eligible("adds a file")
    if deleted is None or added is None:
        return fail("a file deletion or addition is not eligible")
    revert.apply(repo.root, deleted)
    if repo.read(f"{MAIN}/Old.java") is None:
        failures += fail("reverting a deletion did not bring the file back")
    if "file-added" not in revert.revert_labels(deleted):
        failures += fail(f"a deleted file reverted is not an added one: "
                         f"{revert.revert_labels(deleted)}")
    reset(repo.root)
    revert.apply(repo.root, added)
    if repo.read(f"{MAIN}/Fresh.java") is not None:
        failures += fail("reverting an addition left the file")
    if "file-deleted" not in revert.revert_labels(added):
        failures += fail(f"an added file reverted is not a deleted one: "
                         f"{revert.revert_labels(added)}")
    return failures


def case_a_rename_inside_the_roots_reverts_both_sides_and_one_across_them_is_not_eligible():
    failures = 0
    f = fixture()
    repo, inside = f["repo"], eligible("renames inside")
    if inside is None:
        return fail("a rename inside the roots is not eligible")
    revert.apply(repo.root, inside)
    if repo.read(f"{MAIN}/BetaMoved.java") is not None or repo.read(f"{MAIN}/Beta.java") is None:
        failures += fail("the rename was not reverted on both sides")
    if eligible("renames across") is not None:
        failures += fail("a rename crossing the roots was drawn")
    if not f["not_eligible"].get("rename-crosses-roots"):
        failures += fail(f"the crossing rename was not counted: {f['not_eligible']}")
    return failures


def case_a_commit_over_two_modules_reverts_the_roots_and_names_what_it_dropped():
    failures = 0
    f = fixture()
    repo, candidate = f["repo"], eligible("two modules")
    if candidate is None:
        return fail("the two-module commit is not eligible")
    revert.apply(repo.root, candidate)
    if "return 1" not in repo.read(f"{MAIN}/Echo.java"):
        failures += fail("the measured module's file was not reverted")
    if "return 2" not in repo.read(f"{OTHER}/Gamma.java"):
        failures += fail("the other module's file was reverted")
    if repo.read("core/src/main/resources/app.properties") is None:
        failures += fail("the resource outside the roots was reverted")
    scope = run.revert_fields(candidate, "main")["revert_scope"]
    if scope != sorted([f"{OTHER}/Gamma.java", "core/src/main/resources/app.properties"]):
        failures += fail(f"revert_scope is {scope}")
    if candidate["stratum"] != "several-modules":
        failures += fail(f"stratum {candidate['stratum']}")
    files, modules = run.mutation_change_counts(repo.root)
    if (files, modules) != (1, 1):
        failures += fail(f"the real counts are {(files, modules)}, not one file in one module")
    fields = run.revert_fields(candidate, "main")
    if "several-modules" not in fields["census_labels"]:
        failures += fail(f"the census labels lost several-modules: {fields['census_labels']}")
    made = [name for name in ("several-modules", "other-module-code", "resource")
            if name in fields["revert_labels"]]
    if made:
        failures += fail(f"the row is labelled with {made}, which it left in place")
    return failures


def _gate_with(codes, log, reset_ok=True):
    """`revert.gate` over stub builds: each returns the next code and writes `log`."""
    f = fixture()
    calls = []

    def build(log_path):
        calls.append(log_path)
        with open(log_path, "w", encoding="utf-8") as handle:
            handle.write(log)
        return codes[len(calls) - 1]

    logs = tempfile.mkdtemp(dir=f["tmp"])
    result = revert.gate(f["repo"].root, eligible("adds a method"), build, lambda: reset_ok,
                         os.path.join(logs, "gate.log"), stalled=124)
    return result, len(calls)


def case_the_gate_retries_a_test_compile_failure_only():
    failures = 0
    result, builds = _gate_with([1, 0], "error: cannot find symbol\n"
                                        "> Task :core:compileJava FAILED\n")
    if (result, builds) != ((1, "main", "main-compile"), 1):
        failures += fail(f"a main-compile failure gave {result} after {builds} build(s)")
    result, _ = _gate_with([124], "")
    if result != (124, "main", None):
        failures += fail(f"a stalled build gave {result}, not a stall with no rejection")
    result, builds = _gate_with([1, 0], "error: cannot find symbol\n"
                                        "> Task :core:compileTestJava FAILED\n", reset_ok=False)
    if (result, builds) != ((1, "full", "test-compile"), 1):
        failures += fail(f"a reset that failed before the full revert gave {result} after "
                         f"{builds} build(s)")
    return failures


def case_comment_only_and_binary_commits_are_not_eligible():
    failures = 0
    f = fixture()
    for name, reason in (("comment only", "comment-only"), ("binary", "binary")):
        if eligible(name) is not None:
            failures += fail(f"the {name} commit was drawn")
        if not f["not_eligible"].get(reason):
            failures += fail(f"{reason} was not counted: {f['not_eligible']}")
    return failures


def case_two_commits_with_one_patch_id_are_drawn_once():
    f = fixture()
    shas = {f["repo"].shas["adds seven"], f["repo"].shas["adds seven again"]}
    drawn = [c for c in f["ordered"] if c["sha"] in shas]
    failures = 0
    if len(drawn) != 1:
        failures += fail(f"{len(drawn)} of the two identical patches were drawn")
    if not f["not_eligible"].get("duplicate-patch"):
        failures += fail(f"the duplicate was not counted: {f['not_eligible']}")
    return failures


def case_the_draw_order_is_fixed_by_seed_and_census_and_takes_strata_in_turn():
    failures = 0
    f = fixture()
    again, _ = revert.candidates(f["repo"].root, f["project"], seed=7, window=50)
    if [c["sha"] for c in again] != [c["sha"] for c in f["ordered"]]:
        failures += fail("the same seed and census gave another order")
    strata = [c["stratum"] for c in f["ordered"]]
    present = [s for s in revert.STRATA if s in strata]
    if len(present) != 3:
        failures += fail(f"the fixture reaches strata {present}, not all three")
    if sorted(strata[:len(present)]) != sorted(present):
        failures += fail(f"a stratum was drawn twice before every one was drawn once: {strata}")
    synthetic = [{"sha": f"{i:040x}", "stratum": "single-file", "patch_id": str(i)}
                 for i in range(12)]
    orders = {tuple(c["sha"] for c in revert.draw_order(synthetic, seed)[0]) for seed in (1, 2, 3)}
    if len(orders) == 1:
        failures += fail("the order does not depend on the seed")
    return failures


def _row(target, induced, missed):
    return {"target": target, "status": "checked" if induced else "no-effect",
            "selected_distinct": 2, "full_distinct": 10, "decision_outcome": "narrowed",
            "induced": ["T::t"] if induced else [], "missed": ["T::t"] if missed else [],
            "full_failures": 1 if induced else 0}


def draws(order, stratum_of, outcomes, limit, narrowed_target):
    """The loop's draw, with each row's outcome handed in: the targets it measures, in order."""
    findings, drawn = [], []
    for target in order:
        if not revert.should_draw(stratum_of[target], findings, stratum_of, limit, narrowed_target,
                                  run.counts_toward_narrowed_target):
            continue
        drawn.append(target)
        induced, missed = outcomes[target]
        findings.append(_row(target, induced, missed))
    return drawn


def case_flipping_a_rows_missed_set_changes_no_later_draw():
    order = [f"revert::{i:040x}" for i in range(12)]
    stratum_of = {t: revert.STRATA[i % 3] for i, t in enumerate(order)}
    outcomes = {t: (i % 4 != 0, False) for i, t in enumerate(order)}
    failures = 0
    base = draws(order, stratum_of, outcomes, limit=3, narrowed_target=2)
    for target in base:
        flipped = {**outcomes, target: (outcomes[target][0], not outcomes[target][1])}
        if draws(order, stratum_of, flipped, limit=3, narrowed_target=2) != base:
            failures += fail(f"flipping {target}'s missed set changed the draw")
    if len(set(stratum_of[t] for t in base)) != 3:
        failures += fail("the per-stratum stop left a stratum undrawn")
    if any(sum(1 for t in base if stratum_of[t] == s) > 3 for s in revert.STRATA):
        failures += fail("a stratum drew past its cap")
    return failures


def _survival(applied, survived, label="signature"):
    rows = [{"labels": [label], "survived": i < survived} for i in range(applied)]
    return revert.survival(rows)


def case_a_shortfall_widens_the_window_once():
    failures = 0
    healthy = _survival(9, 6)
    if revert.window_decision({200: healthy}) != {"window": 200, "short": [], "widen": False}:
        failures += fail(f"a healthy dry run widened: {revert.window_decision({200: healthy})}")
    short = _survival(9, 2)
    first = revert.window_decision({200: short})
    if first != {"window": 400, "short": ["overall", "signature"], "widen": True}:
        failures += fail(f"a shortfall did not widen to 400: {first}")
    second = revert.window_decision({200: short, 400: short})
    if second["window"] != 400 or second["widen"] or second["short"] != ["overall", "signature"]:
        failures += fail(f"a second shortfall widened again or hid its counts: {second}")
    only_family = revert.survival([{"labels": ["body-value"], "survived": True}] * 8
                                  + [{"labels": ["visibility"], "survived": False}] * 2)
    if revert.window_decision({200: only_family})["short"] != ["visibility"]:
        failures += fail("a revert-only family under a third did not widen the window")
    return failures


def case_a_shape_short_of_the_target_is_not_reached_by_revert():
    rows = [{**_row(f"revert::{i}", True, False), "revert_labels": ["signature", "body-value"]}
            for i in range(4)]
    rows += [{**_row("revert::x", True, False), "revert_labels": ["visibility"]},
             {**_row("revert::y", False, False), "revert_labels": ["visibility"]}]
    reached = revert.reach(rows, run.counts_toward_narrowed_target, target=4)
    failures = 0
    if revert.not_reached(reached) != ["visibility"]:
        failures += fail(f"not reached: {revert.not_reached(reached)}")
    if reached["visibility"] != {"rows": 2, "miss_possible": 1, "reached": False}:
        failures += fail(f"visibility: {reached['visibility']}")
    return failures


def case_the_set_has_its_own_results_path_and_grammar():
    failures = 0
    out, scratch = run.results_location("R", "L", "sample", run.REVERT_SET)
    if out != os.path.join("R", "revert-v1", "sample.json") or not scratch.endswith(".revert-v1"):
        failures += fail(f"revert/v1 writes {out}, {scratch}")
    if run.REVERT_SET not in run.SHAPE_SETS:
        failures += fail("revert/v1 is not a set run.py accepts")
    args = run.argument_parser().parse_args(["p.toml", "--shapes", "revert/v1"])
    if args.shapes != "revert/v1":
        failures += fail("--shapes revert/v1 is not accepted")
    if run.grammar_digest(files=run.REVERT_GRAMMAR_FILES) == run.grammar_digest():
        failures += fail("the revert set's grammar digest is the default's")
    return failures


def test_revert():
    failures = 0
    try:
        for name, case in sorted(globals().items()):
            if name.startswith("case_"):
                print(name)
                failures += case()
    finally:
        if FIXTURE:
            shutil.rmtree(FIXTURE["tmp"], ignore_errors=True)
    assert failures == 0, f"{failures} check(s) failed"


if __name__ == "__main__":
    test_revert()
    print("ok")
