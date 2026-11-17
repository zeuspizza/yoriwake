"""Tests for `deps_pack.py`: what a dependency pack holds, what a job reports it downloaded, and
which earlier run a record job may take a pack from.

Cases return a count of failed checks; one `test_*` entry point asserts.

    python3 bench/test_deps_pack.py
"""

import os
import sys
import tarfile
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import deps_pack  # noqa: E402


def fail(message):
    print(f"  FAIL: {message}")
    return 1


def _write(top, relative, content="x"):
    path = os.path.join(top, *relative.split("/"))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(content)


def _trees(root):
    return deps_pack.roots({"HOME": os.path.join(root, "home")})


JAR = "org/slf4j/slf4j-api/2.0.16/slf4j-api-2.0.16.jar"
GRADLE_JAR = "files-2.1/org.slf4j/slf4j-api/2.0.16/0abc/slf4j-api-2.0.16.jar"
LEFT_OUT = {
    "repository": ["io/github/zeuspizza/yoriwake-maven-plugin/0.7.0-SNAPSHOT/plugin.jar",
                   "org/slf4j/slf4j-api/2.0.16/slf4j-api-2.0.16.pom.lastUpdated"],
    "modules-2": ["files-2.1/io.github.zeuspizza/yoriwake/0.7.0/0abc/yoriwake-0.7.0.jar",
                  "metadata-2.107/descriptors/io.github.zeuspizza/yoriwake/0.7.0/descriptor.bin",
                  "modules-2.lock"],
}


def case_a_pack_keeps_dependencies_and_leaves_out_the_plugin_under_test():
    failures = 0
    with tempfile.TemporaryDirectory() as root:
        trees = _trees(root)
        _write(trees["repository"], JAR)
        _write(trees["modules-2"], GRADLE_JAR)
        for name, paths in LEFT_OUT.items():
            for relative in paths:
                _write(trees[name], relative)
        out = os.path.join(root, "deps.tar")
        line = deps_pack.pack(trees, out)
        with tarfile.open(out) as tar:
            names = sorted(tar.getnames())
        if names != [f"modules-2/{GRADLE_JAR}", f"repository/{JAR}"]:
            failures += fail(f"the pack holds {names}")
        if not line.startswith("deps: packed 2 files"):
            failures += fail(f"the pack's line is {line!r}")
        restored = _trees(os.path.join(root, "elsewhere"))
        deps_pack.unpack(restored, out)
        for name, relative in (("repository", JAR), ("modules-2", GRADLE_JAR)):
            if not os.path.isfile(os.path.join(restored[name], *relative.split("/"))):
                failures += fail(f"{name}/{relative} was not restored")
    return failures


def case_a_pack_that_holds_the_plugin_under_test_does_not_restore_it():
    failures = 0
    with tempfile.TemporaryDirectory() as root:
        out = os.path.join(root, "planted.tar")
        planted = os.path.join(root, "plugin.jar")
        _write(root, "plugin.jar")
        with tarfile.open(out, "w") as tar:
            tar.add(planted, arcname=f"repository/{LEFT_OUT['repository'][0]}")
            tar.add(planted, arcname="elsewhere/plugin.jar")
        trees = _trees(root)
        line = deps_pack.unpack(trees, out)
        if line != "deps: restored 0 files" or os.path.exists(trees["repository"]):
            failures += fail(f"a planted plugin under test was restored: {line}")
    return failures


def case_a_member_that_climbs_out_of_its_tree_is_left_out():
    failures = 0
    with tempfile.TemporaryDirectory() as root:
        out = os.path.join(root, "climbing.tar")
        _write(root, "plugin.jar")
        with tarfile.open(out, "w") as tar:
            tar.add(os.path.join(root, "plugin.jar"),
                    arcname="modules-2/files-2.1/x/../io.github.zeuspizza/yoriwake/plugin.jar")
            tar.add(os.path.join(root, "plugin.jar"), arcname="repository/../../outside.jar")
            tar.add(os.path.join(root, "plugin.jar"), arcname=f"repository/{JAR}")
        trees = _trees(root)
        line = deps_pack.unpack(trees, out)
        if line != "deps: restored 1 files":
            failures += fail(f"members climbing out of their tree were restored: {line}")
    return failures


def case_restore_survives_a_missing_or_broken_pack_and_snapshots_anyway():
    failures = 0
    with tempfile.TemporaryDirectory() as root:
        trees = _trees(root)
        _write(trees["repository"], JAR)
        before = os.path.join(root, "before.json")
        deps_pack.restore(trees, os.path.join(root, "absent"), before)
        if deps_pack.report(trees, before) != "deps: downloaded 0 files, 0.0 MB":
            failures += fail("without a pack, the snapshot did not hold what was there")
        downloaded = os.path.join(root, "deps-in")
        _write(downloaded, "deps.tar", "not a tar")
        deps_pack.restore(trees, downloaded, before)
        if os.path.exists(downloaded) or not os.path.isfile(before):
            failures += fail("a broken pack was kept, or no snapshot was taken after it")
    return failures


def case_the_report_counts_only_what_came_after_the_snapshot():
    failures = 0
    with tempfile.TemporaryDirectory() as root:
        trees = _trees(root)
        _write(trees["repository"], JAR)
        before = os.path.join(root, "before.json")
        deps_pack.snapshot(trees, before)
        _write(trees["modules-2"], GRADLE_JAR, "x" * 2_500_000)
        _write(trees["repository"], LEFT_OUT["repository"][0])
        line = deps_pack.report(trees, before)
        if line != "deps: downloaded 1 files, 2.5 MB":
            failures += fail(f"one new 2.5 MB jar reported as {line!r}")
    return failures


def case_missing_trees_pack_and_report_as_empty():
    failures = 0
    with tempfile.TemporaryDirectory() as root:
        trees = _trees(root)
        before = os.path.join(root, "before.json")
        deps_pack.snapshot(trees, before)
        line = deps_pack.report(trees, before)
        if line != "deps: downloaded 0 files, 0.0 MB":
            failures += fail(f"no trees reported as {line!r}")
        out = os.path.join(root, "deps.tar")
        deps_pack.pack(trees, out)
        with tarfile.open(out) as tar:
            if tar.getnames():
                failures += fail(f"no trees packed {tar.getnames()}")
        if deps_pack.report(trees, os.path.join(root, "absent.json")) != line:
            failures += fail("a missing snapshot did not report as one of empty trees")
    return failures


def case_a_pack_is_named_for_its_project_and_pinned_commit():
    with tempfile.TemporaryDirectory() as root:
        toml = os.path.join(root, "kafka.toml")
        with open(toml, "w", encoding="utf-8") as handle:
            handle.write('name = "kafka"\ncommit = "54a57d7c70ee7f1811da282fc2adc594f3b55372"\n')
        name = deps_pack.pack_name(toml)
    return 0 if name == "deps-kafka-54a57d7c70ee" else fail(f"the pack is named {name}")


def _artifact(run, created, repository=1, head=1, branch="main", expired=False, name="deps-a-0123"):
    return {"name": name, "expired": expired, "created_at": created,
            "workflow_run": {"id": run, "repository_id": repository, "head_repository_id": head,
                             "head_branch": branch}}


def _api(artifacts, runs):
    """A stand-in for `gh api`: the listing, then each run by id; None for anything else."""
    asked = []

    def api(path):
        asked.append(path)
        if "/actions/artifacts?" in path:
            return {"artifacts": artifacts}
        run = path.rsplit("/", 1)[1]
        return runs.get(int(run)) if run.isdigit() else None

    return api, asked


SWEEP = {"path": ".github/workflows/sweep.yml", "event": "workflow_dispatch"}


def case_find_takes_the_newest_unexpired_pack_of_a_dispatched_sweep():
    failures = 0
    api, asked = _api([_artifact(10, "2026-11-01T00:00:00Z"), _artifact(20, "2026-11-10T00:00:00Z"),
                       _artifact(30, "2026-11-12T00:00:00Z", expired=True)],
                      {10: SWEEP, 20: SWEEP, 30: SWEEP})
    found = deps_pack.find("deps-a-0123", "o/r", "main", api=api)
    if found != 20:
        failures += fail(f"of two packs and an expired one, found {found}")
    if "name=deps-a-0123" not in asked[0]:
        failures += fail(f"the listing was not asked by name: {asked[0]}")
    return failures


def case_find_passes_over_a_newer_pack_no_trusted_sweep_made():
    failures = 0
    for what, newer, run in (
            ("a fork's pull request", _artifact(20, "2026-11-10T00:00:00Z", head=2), SWEEP),
            ("another branch", _artifact(20, "2026-11-10T00:00:00Z", branch="topic"), SWEEP),
            ("another workflow", _artifact(20, "2026-11-10T00:00:00Z"),
             {"path": ".github/workflows/verify.yml", "event": "workflow_dispatch"}),
            ("a sweep not dispatched", _artifact(20, "2026-11-10T00:00:00Z"),
             {"path": ".github/workflows/sweep.yml", "event": "pull_request"}),
            ("another name", _artifact(20, "2026-11-10T00:00:00Z", name="deps-a-0123-x"), SWEEP)):
        api, _ = _api([_artifact(10, "2026-11-01T00:00:00Z"), newer], {10: SWEEP, 20: run})
        found = deps_pack.find("deps-a-0123", "o/r", "main", api=api)
        if found != 10:
            failures += fail(f"a newer pack from {what} was taken: {found}")
    return failures


def case_find_without_an_answer_finds_nothing():
    failures = 0
    for what, api in (("a failed listing", lambda path: None),
                      ("an empty listing", lambda path: {"artifacts": []}),
                      ("a failed run lookup", _api([_artifact(10, "2026-11-01T00:00:00Z")], {})[0])):
        found = deps_pack.find("deps-a-0123", "o/r", "main", api=api)
        if found is not None:
            failures += fail(f"{what} found {found}")
    return failures


def test_deps_pack():
    failures = 0
    for name, case in sorted(globals().items()):
        if name.startswith("case_"):
            print(name)
            failures += case()
    assert failures == 0, f"{failures} check(s) failed"


if __name__ == "__main__":
    test_deps_pack()
    print("ok")
