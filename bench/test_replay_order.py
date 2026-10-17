"""Tests for `replay_order.py`: the leave-one-project-out comparison of the learned order with
`reach-stratified/v1`, from archived results files, maps and checkouts alone.

Cases return a count of failed checks; one `test_*` entry point asserts.

    python3 bench/test_replay_order.py
"""

import contextlib
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import family_order  # noqa: E402
import replay_order  # noqa: E402

PROJECTS = ("alpha", "beta", "gamma")


def fail(message):
    print(f"  FAIL: {message}")
    return 1


def _git(repo, *args):
    subprocess.run(["git", "-C", repo, *args], check=True, capture_output=True,
                   env={**os.environ, "GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@t",
                        "GIT_COMMITTER_NAME": "t", "GIT_COMMITTER_EMAIL": "t@t"})


def _row(path, kind, index, hit, missed=False):
    narrowed = hit
    return {"target": f"{path}::{kind}::m{index}", "kind": kind, "index": index,
            "status": "checked" if hit else "no-effect",
            "selected_distinct": 2 if narrowed else 10, "full_distinct": 10,
            "full_failures": 1 if hit else 0, "missed": ["T::t"] if missed else [],
            "replay": {"map": "bench/logs/map"}}


def _corpus(root, missed=False):
    """Three projects whose archived order drew four large files that broke nothing before two
    small ones that narrowed and induced; every class equally reached, so one stratum. `constant`
    rows never narrow, so no order can gain there."""
    results = os.path.join(root, "bench", "results")
    os.makedirs(results)
    os.makedirs(os.path.join(root, "bench", "logs", "map"))
    classes = [f"p.Big{i}" for i in range(4)] + [f"p.Small{i}" for i in range(2)]
    with open(os.path.join(root, "bench", "logs", "map", "coverage.tsv"), "w",
              encoding="utf-8") as handle:
        handle.write("".join(f"SUCCESSFUL\t1\t{name}\tT{i}\n"
                             for name in classes for i in range(3)))
    for name in PROJECTS:
        repo = os.path.join(root, "bench", "checkouts", name)
        os.makedirs(os.path.join(repo, "src/main/java/p"))
        for cls in classes:
            with open(os.path.join(repo, f"src/main/java/p/{cls[2:]}.java"), "w",
                      encoding="utf-8") as handle:
                handle.write("x" * (20000 if cls.startswith("p.Big") else 300))
        _git(repo, "init", "-q")
        _git(repo, "add", ".")
        _git(repo, "commit", "-qm", "pin")
        commit = subprocess.run(["git", "-C", repo, "rev-parse", "HEAD"], capture_output=True,
                                text=True, check=True).stdout.strip()
        rows = [_row(f"src/main/java/p/{cls[2:]}.java", "body", 0, cls.startswith("p.Small"),
                     missed) for cls in classes]
        rows += [_row(f"src/main/java/p/{cls[2:]}.java", "constant", 0, False, missed)
                 for cls in classes[:5]]
        with open(os.path.join(results, f"{name}.json"), "w", encoding="utf-8") as handle:
            json.dump({"project": name, "commit": commit,
                       "target_ordering": "reach-stratified/v1", "target_shapes": "default",
                       "mutations": rows}, handle)
        # An isolated arm's file measures the same targets against another map: never read.
        with open(os.path.join(results, f"{name}.isolated.json"), "w", encoding="utf-8") as handle:
            json.dump({"project": name, "mutations": [{"kind": "body", "target": "x::body::y"}]},
                      handle)
    return results


def _replay(results, *extra):
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        replay_order.main([results, "--json", *extra])
    text = out.getvalue()
    return json.loads(text[:text.rindex("}") + 1])


def case_the_replay_reproduces_its_comparison_from_the_archived_files_alone():
    root = tempfile.mkdtemp(prefix="yoriwake-replay-")
    try:
        results = _corpus(root)
        first, second = _replay(results), _replay(results)
        failures = 0
        if first != second:
            failures += fail("two replays of the same files disagree")
        body, constant = first["families"]["body"], first["families"]["constant"]
        if (body["baseline_hits"], body["learned_hits"]) != (0, 6):
            failures += fail(f"body: {body['baseline_hits']} -> {body['learned_hits']}, "
                             f"expected 0 -> 6 (two small files a project, fitted on the others)")
        if not body["adopted"] or constant["adopted"]:
            failures += fail(f"adopted body {body['adopted']}, constant {constant['adopted']}")
        if first["projects"] != list(PROJECTS):
            failures += fail(f"read projects {first['projects']}")
        return failures
    finally:
        shutil.rmtree(root, ignore_errors=True)


def case_the_replay_never_reads_what_was_missed():
    root = tempfile.mkdtemp(prefix="yoriwake-replay-")
    flipped_root = tempfile.mkdtemp(prefix="yoriwake-replay-")
    try:
        base = _replay(_corpus(root))
        flipped = _replay(_corpus(flipped_root, missed=True))
        if base["families"] != flipped["families"]:
            return fail("every row's missed set flipped changed the comparison")
        return 0
    finally:
        shutil.rmtree(root, ignore_errors=True)
        shutil.rmtree(flipped_root, ignore_errors=True)


def case_a_project_losing_its_last_miss_possible_row_blocks_adoption():
    projects = {}
    for name, hits in (("a", (False, False, False, False, True, True)),
                       ("b", (False, False, False, False, True, True)),
                       ("c", (True, False, False, False, False, False))):
        projects[name] = [{"target": (f"src/main/java/p/{name}{i}.java", "body", 0),
                           "family": "body", "hit": hit,
                           "features": family_order.features(0, 3, 300 if hit != (name == "c")
                                                             else 20000)}
                          for i, hit in enumerate(hits)]
    families = replay_order.compare(projects)
    body = families["body"]
    failures = 0
    if "c" not in body["projects_losing"]:
        failures += fail(f"c's only row where a miss was possible fell out of its first draws, "
                         f"and the replay says {body['projects_losing']}")
    if body["adopted"]:
        failures += fail("a family where a project loses its last such row was adopted")
    return failures


def case_the_written_model_never_ranks_the_targets_it_was_fitted_on():
    root = tempfile.mkdtemp(prefix="yoriwake-replay-")
    try:
        results = _corpus(root)
        projects, _ = replay_order.load_runs([results])
        model = replay_order.model_file(projects, replay_order.compare(projects))
        failures = 0
        if model is None or model["adopted"] != ["body"]:
            return fail(f"the model adopted {model and model['adopted']}")
        archived = [tuple(t) for t in model["archived"]["alpha"]]
        if sorted(archived) != sorted(e["target"] for e in projects["alpha"]
                                      if e["family"] == "body"):
            failures += fail("the model does not list the targets it was fitted on")
        fresh = ("src/main/java/p/New.java", "body", 0)
        ordered = family_order.draw_order(archived + [fresh], 1, None, {fresh[0]: 20000}, model,
                                          "alpha")
        if ordered[0] != fresh:
            failures += fail(f"an archived target was drawn before a fresh one: {ordered}")
        return failures
    finally:
        shutil.rmtree(root, ignore_errors=True)


def test_replay_order():
    failures = 0
    for name, case in sorted(globals().items()):
        if name.startswith("case_"):
            print(name)
            failures += case()
    assert failures == 0, f"{failures} check(s) failed"


if __name__ == "__main__":
    test_replay_order()
    print("ok")
