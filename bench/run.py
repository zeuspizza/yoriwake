"""
Measures whether predictive test selection ever skips a test that matters.

The headline number is missed failures, not speed. A selector that skips a test it should have run
does not crash and does not fail a build -- it produces a smaller number and a green tick -- so the
only way to tell a working selector from a broken one is to break something on purpose, find out
from a full run which tests genuinely fail, and check every one of them was selected.

Ground truth therefore comes from a real full run and never from the coverage map, because the map
is the artefact under test.

Usage:
    python bench/run.py bench/projects/detekt.toml [--limit N]
"""

import argparse
import glob
import hashlib
from collections import Counter
import json
import os
import re
import shutil
import stat
import atexit
import signal
import subprocess
import sys
import time
import tomllib
import urllib.parse
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
import checkout as yoriwake_checkout  # noqa: E402  (the one place the checkout is resolved)
# The plugin, its init scripts and the sample fixtures live in the yoriwake checkout, not here.
SOURCE = yoriwake_checkout.ROOT
INIT_SCRIPT = os.path.join(SOURCE, "scripts", "yoriwake.init.gradle.kts")
# The script applies the released plugin unless told otherwise; every build here names the snapshot
# `publish_plugin_under_test` installs.
INIT_SCRIPT_ARGS = ["-I", INIT_SCRIPT, yoriwake_checkout.init_script_version_flag()]
# Every harness build carries this, whichever arm: a guest's own Develocity test selection skipped
# classes in both arms, ground truth included. See the script's own note.
GUEST_SELECTION_OFF = os.path.join(HERE, "guest-selection-off.init.gradle")
# The public flag the isolated arm records with; Settings.ISOLATED_CAPTURE, pinned by
# test_module_contract.py.
ISOLATED_CAPTURE_FLAG = "-Pyoriwake.isolatedCapture"

# The two timing arms differ in one thing that is not instrumentation: the capture arm applies the
# plugin and the baseline does not, so the capture arm alone pays to STORE a configuration-cache
# entry for a configured plugin. Measured on okhttp-tls: a selecting run costs 40s with
# a cold cache, 10s with a warm one, and 14s with no cache at all -- so ~26s of what was being
# reported as overhead was cache serialisation, on a suite whose tests take 7s.
#
# Disabling the cache on BOTH arms removes that asymmetry. It does not measure what a real build
# does -- a real build reuses the entry -- but it measures the thing the number CLAIMS to be, which
# is the cost of instrumenting. The cache's own cost is a separate question and belongs to a
# separate measurement.
TIMING_ARM_FLAGS = ["--no-configuration-cache"]
CHECKOUTS = os.path.join(HERE, "checkouts")
PROJECTS = os.path.join(HERE, "projects")
RESULTS = os.path.join(HERE, "results")
# A CONTROL RUN'S FILES: rows measured under a plugin with a miss planted in it, which
# every reader refuses in a figure.
CONTROL_RESULTS = os.path.join(RESULTS, "control")

sys.path.insert(0, HERE)
from recall_check import targets_in  # noqa: E402  (shares the mutation grammar)
sys.path.insert(0, HERE)
import mutations  # noqa: E402
import config_cost  # noqa: E402  (the plugin's counters file has one reader)
import sweep_data  # noqa: E402
# Re-exported deliberately. These moved out on a seam the review named; every existing caller,
# including the tests, still says `run.STALLED` and `run.run`.
from process import STALLED, STALL_SECONDS, run, stalled  # noqa: E402,F401
from isolation import order_dependent as _order_dependent  # noqa: E402
from isolation import fails_without_the_change as _fails_without_the_change  # noqa: E402

# The target-ordering subsystem now lives beside this file. Imported by name rather than with a
# star so the boundary is visible here, and so `run.by_coverage` keeps working for every caller and
# for the tests that already reach for it.
from target_ordering import (  # noqa: E402
    ANSWERING_SHAPES,
    DEFAULT_SHAPES,
    DEFAULT_TARGET_SEED,
    SHAPE_SETS,
    ORDERING,
    by_coverage,
    by_reach,
    by_shape,
    covered_classes,
    interleave_shapes,
    mutation_targets,
    pending_targets,
    qualified_name,
    reach_counts,
    reach_of,
    weave,
)
import revert  # noqa: E402

# REVERTS OF REAL COMMITS, a set of its own: no generator draws its rows, so it is registered here
# rather than in the mutation grammar, and its own sources are what its `grammar_digest` hashes.
REVERT_SET = revert.SET
SHAPE_SETS = SHAPE_SETS + (REVERT_SET,)
# `pool/v1` lives in its own file, digested with that set only: see `pool_mutations`.
import pool_mutations  # noqa: E402
from pool_mutations import POOL_SHAPES  # noqa: E402
SHAPE_SETS = SHAPE_SETS + (POOL_SHAPES,)
# `pool/v1`'s draw order and per-family stops, digested with that set only: see `family_order`.
import family_order  # noqa: E402

# `forcing/v1` likewise: see `forcing_mutations`.
import forcing_mutations  # noqa: E402
from forcing_mutations import FORCING_SET  # noqa: E402
SHAPE_SETS = SHAPE_SETS + (FORCING_SET,)

# THE NULL CHANGE AND THE NEUTRAL EDIT, a control's two rows and never a measurement: no figure
# draws from them, so they are not in SHAPE_SETS, which `sweep_corpus.py --set` offers. The null
# change edits nothing and must run the whole suite as `empty-change-set`; the neutral edit is the
# preflight's comment on a `package` line and must report no miss.
NEUTRAL_SET = "neutral/v1"
NULL_CHANGE, NEUTRAL_EDIT = "null-change", "neutral-edit"
# The null change's target names no file: it has none.
NULL_CHANGE_PATH = "(nothing)"
# The sets whose control may run on a corpus checkout.
CORPUS_CONTROL_SETS = (REVERT_SET, FORCING_SET, POOL_SHAPES)



def wrapper(checkout):
    """The Gradle wrapper to drive this checkout with.

    `./gradlew` is a shell script, so on Windows CreateProcess needs the batch file. A checkout with
    no wrapper of its own -- the sample fixture, which is a standalone build inside the yoriwake
    checkout -- borrows that checkout's; Gradle takes the working directory as the project directory
    either way.
    """
    for base in (checkout, SOURCE):
        batch = os.path.join(base, "gradlew.bat")
        script = os.path.join(base, "gradlew")
        if os.name == "nt" and os.path.isfile(batch):
            return batch
        if os.path.isfile(script):
            return script
    raise FileNotFoundError("no Gradle wrapper for " + checkout)



def _make_removable(path):
    """Gives owner rwx to `path` and everything under it, top-down, so `rmtree` can always descend.

    Top-down and before descending is the whole point: a directory left at 0o200 cannot be listed,
    so a bottom-up or repair-on-failure approach cannot reach its contents. Owner rwx is what a
    directory needs to be traversed and emptied, and is harmless on a file. Errors are swallowed
    because this is best-effort preparation -- `remove_tree` raises if the tree actually survives.
    """
    try:
        os.chmod(path, stat.S_IRWXU)
    except OSError:
        pass
    if os.path.isdir(path) and not os.path.islink(path):
        try:
            entries = os.listdir(path)
        except OSError:
            return
        for name in entries:
            _make_removable(os.path.join(path, name))


def remove_tree(path):
    """Deletes a directory, and says so when it could not.

    `ignore_errors=True` was silently doing nothing on Windows, where git marks objects under
    `.git` read-only and a running Gradle daemon holds files open. The copy that followed then hit
    FileExistsError, several frames away from the cause. A benchmark harness that cannot reset its
    own checkout must stop rather than measure whatever was left behind.
    """
    # MADE REMOVABLE UP FRONT RATHER THAN REPAIRED ON FAILURE, and this replaced an `onexc`
    # handler that was broken two ways at once (it killed a 3-hour iceberg sweep thirteen rows into
    # sixteen):
    #
    #   1. It chmod'd to `stat.S_IWRITE`, which is 0o200 -- write-only. Correct enough for a FILE,
    #      but on a DIRECTORY it strips read and execute, so nothing can traverse in to delete the
    #      contents. The handler CREATED the condition it existed to fix: iceberg's map directory
    #      was left `d-w-------`, and every later attempt died with `PermissionError: [Errno 13]`
    #      on `os.open(name, O_RDONLY, dir_fd=...)`.
    #   2. It then called `function(target)` to retry -- but `rmtree`'s fd-based walk passes
    #      `os.open`, which needs flags, so the retry itself raised
    #      `TypeError: open() missing required argument 'flags'`. An `onexc` handler cannot
    #      usefully re-invoke that function.
    #
    # Walking top-down and chmod'ing BEFORE descending is deterministic and has neither problem: a
    # 0o200 directory is made traversable before anything tries to read it. The handler below stays
    # only as a backstop for a race, and no longer re-invokes anything.
    def force_writable(_function, target, _exc):
        try:
            os.chmod(target, stat.S_IRWXU)
        except OSError:
            pass

    # RETRIED, BECAUSE A LIVE DAEMON CAN WRITE INTO THE DIRECTORY WHILE IT IS BEING EMPTIED.
    # `restore_map` removes a map directory between arms, and the decode finalizer of
    # the build that just ran is still writing to it: an iceberg sweep died here with the tree
    # emptied and a single `task-facts` re-created at the same second as the removal. That is a race
    # on a regenerable directory, not a permission fault, and the first attempt had already done
    # almost all the work.
    #
    # Two passes and a short settle, which is the same shape `restore()` uses for a transient git
    # failure. If the directory still stands after that, something is holding it open for real and
    # the loud message below is the right answer.
    # AND THE RETRY IS WHAT MAKES THE PERMISSION CASES WORK, not the pre-pass. Measured
    # by removing the pre-pass: `rmtree`'s first pass still fails on an unreadable directory, but
    # `force_writable` chmods each one as it reports it, so the second pass succeeds. The pre-pass
    # only saves that second pass. It is kept because one clean pass is better than two, but NO
    # GUARD IS REGISTERED FOR IT -- one was, and it could not fail, which is worse than none.
    for attempt in range(3):
        _make_removable(path)
        shutil.rmtree(path, onexc=force_writable)
        if not os.path.isdir(path):
            return
        time.sleep(1 + attempt)
    raise RuntimeError(f"could not remove {path}; close any Gradle daemon holding it")


# A BOUND ON EVERY GIT CALL THAT TALKS TO A REMOTE. Gradle goes through `process.run`'s stall
# guard; a clone or fetch against a slow, rate-limited or credential-prompting remote had none, so
# it hung until CI's job timeout killed the whole job -- or, locally, forever. Generous, because a
# large guest's first clone is legitimately slow; it is a backstop for a hang, not a budget.
GIT_NETWORK_TIMEOUT = 1800


def git_over_network(command, check):
    try:
        return subprocess.run(command, check=check, timeout=GIT_NETWORK_TIMEOUT)
    except subprocess.TimeoutExpired:
        raise SystemExit(f"STALLED: `{' '.join(command)}` did not finish in "
                         f"{GIT_NETWORK_TIMEOUT}s; refusing this project rather than hanging "
                         f"the sweep") from None


def checkout_dir(project):
    """The directory a project is measured in: its `path`, or its own clone under `CHECKOUTS`."""
    if project.get("path"):
        return os.path.abspath(os.path.join(ROOT, project["path"]))
    return os.path.join(CHECKOUTS, project["name"])


def clone_owner(project):
    """The project whose own clone a `path` project points at, as its file reads, or None.

    detekt-junk measures in detekt's clone, kafka-core in kafka's. A machine that swept the owner
    first has that clone; a job that records one project alone does not.
    """
    path = checkout_dir(project)
    if os.path.dirname(path) != os.path.abspath(CHECKOUTS):
        return None
    owner_file = os.path.join(PROJECTS, os.path.basename(path) + ".toml")
    if not os.path.isfile(owner_file):
        return None
    with open(owner_file, "rb") as handle:
        owner = tomllib.load(handle)
    if owner.get("path") or owner.get("copy_of") or not (owner.get("url") and owner.get("commit")):
        return None
    if checkout_dir(owner) != path:
        return None
    # The clone is the owner's, at its pin, while every row of this project is stamped with its own.
    if owner["commit"] != project.get("commit"):
        raise SystemExit(f"{project.get('name')} is measured in {owner['name']}'s clone, pinned at "
                         f"{owner['commit']}, but names {project.get('commit')}: pin both to one commit")
    return owner


def checkout_project(project, keep_state=False):
    """Clones at the pinned commit, once. A pinned commit that has vanished must fail loudly.

    `keep_state` reuses a fixture's existing copy: a shard measures against the map the recording
    job left in it, which records that copy's commit.

    A `path` entry uses an existing checkout instead, for developing the harness and for projects
    already on the machine. It is deliberately not pinned: the caller owns that checkout, so the
    results record whatever commit it is on rather than pretending to control it. One that points
    at another project's clone and finds none makes it from that project's file (`clone_owner`).
    """
    if project.get("copy_of"):
        # A fixture of the yoriwake checkout, copied out and made a repository of its own. The
        # harness hard-resets what it manages, so it must never manage a directory inside another
        # checkout.
        source = os.path.join(SOURCE, project["copy_of"])
        path = os.path.join(CHECKOUTS, project["name"])
        if keep_state and os.path.isdir(os.path.join(path, ".git")):
            return path
        if os.path.isdir(path):
            remove_tree(path)
        shutil.copytree(source, path, ignore=shutil.ignore_patterns("build", ".gradle"))
        subprocess.run(["git", "init", "--quiet", path], check=True)
        with open(os.path.join(path, ".gitignore"), "w", encoding="utf-8") as handle:
            handle.write("build/\n.gradle/\n")
        subprocess.run(["git", "-C", path, "add", "."], check=True)
        subprocess.run(["git", "-C", path, "-c", "user.email=b@e", "-c", "user.name=b",
                        "commit", "--quiet", "-m", "fixture"], check=True)
        return path
    if project.get("path"):
        path = checkout_dir(project)
        # A `path` into another project's clone that is not there yet -- a CI job measuring this
        # project alone -- is cloned as that project clones it, at that project's pin.
        parent = None if os.path.isdir(os.path.join(path, ".git")) else clone_owner(project)
        return checkout_project(parent) if parent else path
    path = checkout_dir(project)
    if not os.path.isdir(os.path.join(path, ".git")):
        os.makedirs(CHECKOUTS, exist_ok=True)
        # core.longpaths must be set ON THE CLONE, not after it. Setting it afterwards -- which is
        # what this did, and what the note below has claimed since resilience4j -- is too late: the
        # clone has already tried to write the working tree and failed on every path over 260
        # characters. spring-framework fails 11,460 files that way and leaves a checkout that looks
        # cloned. The later `git config` still runs, for checkouts made before this fix.
        git_over_network(["git", "-c", "core.longpaths=true", "clone", project["url"], path],
                         check=True)
    # Windows refuses paths over 260 characters, and a deep package under bench/checkouts
    # reaches that easily. resilience4j failed its first sweep in one second with "unable to create
    # file ... Filename too long", leaving a checkout that looked cloned and was missing test
    # sources. Three other projects are recorded as CI-only for what may be this same cause.
    #
    # Set per checkout rather than machine-wide: enabling long paths in the registry needs
    # administrator rights, and a benchmark that demands them is a benchmark nobody runs. Harmless
    # where the limit does not exist. The clone above sets the same flag inline, because by the time
    # this line runs the working tree has already been written.
    subprocess.run(["git", "-C", path, "config", "core.longpaths", "true"], check=False)
    # An existing checkout is never re-cloned, so a commit newer than the clone is simply absent and
    # `checkout` dies with "fatal: reference is not a tree". That is what blocked micrometer and
    # junit5 after their pins were corrected -- not a shallow clone, which this is not: the clone
    # above passes no --depth. Fetch the specific commit and retry before giving up, so bumping a pin
    # on a checkout that already exists is an ordinary operation rather than a manual delete.
    commit = project["commit"]
    resolved = subprocess.run(["git", "-C", path, "rev-parse", "--verify", "--quiet", commit + "^{commit}"],
                              capture_output=True)
    if resolved.returncode != 0:
        git_over_network(["git", "-C", path, "fetch", "--quiet", "origin", commit], check=False)
        git_over_network(["git", "-C", path, "fetch", "--quiet", "--all", "--tags"], check=False)
    subprocess.run(["git", "-C", path, "checkout", "--force", commit], check=True)
    subprocess.run(["git", "-C", path, "clean", "-fd", "--quiet"], check=False)
    return path


def assert_owns(checkout):
    """Refuses to manage a directory that is not its own git repository.

    `restore` runs `git reset --hard`. A `path` project pointing inside another repository -- the
    sample fixture pointed inside this one -- would reset the tool's own working tree and destroy
    uncommitted work. The benchmark may only ever hard-reset a checkout whose git top level is
    itself.
    """
    result = subprocess.run(["git", "-C", checkout, "rev-parse", "--show-toplevel"],
                            capture_output=True, text=True)
    toplevel = os.path.normcase(os.path.abspath(result.stdout.strip() or checkout))
    if result.returncode != 0 or toplevel != os.path.normcase(os.path.abspath(checkout)):
        raise SystemExit(
            f"refusing to manage {checkout}: it is not its own git repository "
            f"(top level is {result.stdout.strip() or 'unknown'}). "
            "Point `path` at a standalone checkout, or use `url` so the harness clones one."
        )


def sources_to_drop(project):
    """The repo-relative paths a project stages out of its checkout before every capture.

    A HARNESS KEY RATHER THAN A PLUGIN PROPERTY, and that is a budget decision. No
    `yoriwake.*` property excludes a file from scope derivation, and adding one would
    change `plugin/` -- which moves `selector_digest` and ages every results file on disk. A key read
    has the same effect for this corpus at no digest cost.

    THE COST IS THAT THE DIGEST CANNOT WITNESS IT. `selector_digest` deliberately excludes
    `bench/`, so this changes how a project is measured without ageing any results file. That
    is correct -- selection did not change -- but it means the staleness check is NOT evidence that
    measurement conditions were constant. Any figure drawn from a staged project says so.
    """
    return list(project.get("drop_empty_sources") or [])


def staging_changed(stored, project):
    """Do this project's staged sources differ from the ones the stored results were measured on?

    NULL IS A DIFFERENCE HERE, and the asymmetry with `flags_changed` below is the whole point. If a
    project stages sources today and the stored file records none, those rows CANNOT have been
    staged -- the field would have been written -- so they are provably measured on another tree and
    resuming them is the mixed-tree resume the caller exists to stop. duckduckgo-android is the one
    project this reaches, and its stored rows were taken at the `*` scope the staging removes.

    These two were once ONE branch, which meant a stored null was tolerated for both.
    That silently left duckduckgo-android resumable against its own pre-staging rows.
    """
    return list(stored.get("staged_sources") or []) != sorted(sources_to_drop(project))


def flags_changed(stored, project):
    """Do this project's Gradle flags differ from the ones the stored results were measured under?

    NULL MEANS UNKNOWN HERE, NOT NONE, and that is the opposite of `staging_changed` above. Fifteen
    projects carried non-empty `flags` when this field was added and no results file recorded them,
    so treating a stored null as "none" would have aged all fifteen -- eleven of them fresh and
    correct -- and charged the corpus a re-sweep to introduce a stamp. The cost of the choice is
    that a flag change made before the stamp existed cannot be detected, which is why the four
    changed before it had their results files moved aside by hand once.
    """
    if stored.get("flags") is None:
        return False
    return list(stored.get("flags") or []) != list(project.get("flags") or [])


def assert_droppable(checkout, relative_paths):
    """Refuses unless every named path is a zero-byte file inside the checkout.

    Deleting an empty file cannot change what the compiler produces. Deleting anything else can, and
    that is the difference between staging a checkout and editing a guest's code -- a measurement of
    the second is not a measurement of the project.

    A MISSING PATH IS REFUSED TOO, rather than skipped. Upstream renames a file and a silently
    skipped path turns the remedy off with nothing saying so: the scope collapses to `*` again, the
    capture instruments the whole classpath, and the results file records a number with no note that
    the staging it depended on did not happen.

    AND THE NEAR MISS IS REAL. duckduckgo-android has two files called `SyncActivity.kt` -- the
    zero-byte one under `.../ui/` and a 34,666-byte one under `.../ui/dashboard/`. The size check is
    what stands between staging the first and editing the second.

    Called once, before any build, so a refusal is a non-zero exit rather than a raise inside the
    atexit handler, which cannot act on one.
    """
    for relative in relative_paths:
        # A deletion is the one operation here that can reach outside the checkout, so the path is
        # checked before it is joined rather than trusted because a project file is ours.
        if os.path.isabs(relative) or ".." in relative.replace("\\", "/").split("/"):
            raise SystemExit(
                f"refusing to drop {relative!r}: `drop_empty_sources` names repo-relative paths "
                f"inside the checkout, and this one can leave it."
            )
        path = os.path.join(checkout, relative.replace("/", os.sep))
        if not os.path.isfile(path):
            raise SystemExit(
                f"{relative} is named in `drop_empty_sources` and is not a file in {checkout}. "
                f"Refusing rather than skipping it: a path that upstream renamed would otherwise "
                f"turn this remedy off silently, and the capture would widen its scope back to `*` "
                f"with nothing in the results file saying so."
            )
        size = os.path.getsize(path)
        if size != 0:
            raise SystemExit(
                f"refusing to drop {relative}: it is {size} bytes, not empty. `drop_empty_sources` "
                f"stages a checkout by removing files that cannot change what the guest compiles. "
                f"Removing one with content edits the guest's code, and a measurement of that is "
                f"not a measurement of this project."
            )


STAGING_SUBJECT = "yoriwake-harness: stage empty sources out of the measured tree"


def stage_checkout(checkout, project):
    """Removes a project's `drop_empty_sources` files AND COMMITS the removal. Returns (sha, paths).

    Returns `(None, [])` for the projects that name nothing, which is every project but one.

    A COMMIT, NOT A DIRTY WORKING TREE, and this is the whole correctness of the feature
    (found in review before any sweep was spent). The first version deleted the files
    and left them uncommitted, re-deleting after every `git reset --hard`. That stages the tree the
    capture sees, and it also poisons every question asked of it: the selecting arm runs with
    `-Pyoriwake.base=HEAD`, the plugin answers that with `git diff --name-only --no-renames HEAD`, and an
    uncommitted deletion of a TRACKED file is reported by that diff. So all five paths would enter
    the change set of every row, on top of the mutation -- each one a changed source with no
    compiled class behind it, which is exactly the shape that makes the selector refuse and force a
    full run. A forced run cannot skip a test, so the sweep would have reported `0 missed` that was
    true by construction and answered no recall question. That is the defect class this repository
    exists to detect in other people's tools, and it would have cost a 16-row sweep to learn.

    Committing the removal makes HEAD describe the tree being measured. `git diff HEAD` then reports
    the mutation and nothing else, and `restore()` needs no special case at all: `git reset --hard`
    resets to HEAD, which IS the staged tree. That is why `restore` takes no staging argument.

    Safe to call straight after `checkout_project`, which has just done `git checkout --force
    <pinned commit>` and `git clean -fd` -- so HEAD is the pinned commit and the tree is clean, and
    this cannot stack a second staging commit on a first.
    """
    relative_paths = sources_to_drop(project)
    if not relative_paths:
        return None, []
    assert_droppable(checkout, relative_paths)
    for relative in relative_paths:
        path = os.path.join(checkout, relative.replace("/", os.sep))
        try:
            os.remove(path)
        except OSError as failed:
            # LOUD, like its sibling below. A staging step that half-applied leaves a checkout whose
            # scope silently widens back to `*`, and the results file would say nothing about it.
            raise SystemExit(
                f"could not stage {relative} out of {checkout}: {failed}. Refusing to measure this "
                f"project rather than capture it at a scope that says nothing about the build."
            )
    subprocess.run(["git", "-C", checkout, "add", "-A", "--"] +
                   [p.replace("/", os.sep) for p in relative_paths], check=True)
    # THE SAME SHA ON EVERY RUN. Identity, message, parent and tree were already fixed; the two
    # timestamps were not, so each run staged a new commit -- and a map captured on one run could not
    # be related to the next run's HEAD. The plugin then widened its base to the pinned commit, the
    # five staged deletions entered the change set as classes the map had never seen, and every row
    # of a reused map forced a full run (duckduckgo-android, `--only-target`). Dated as
    # the pinned commit, which is the one date every run agrees on.
    pinned_date = subprocess.run(["git", "-C", checkout, "log", "-1", "--format=%cI", "HEAD"],
                                 capture_output=True, text=True, check=True).stdout.strip()
    subprocess.run(["git", "-C", checkout, "-c", "user.email=harness@yoriwake", "-c", "user.name=yoriwake",
                    "-c", "commit.gpgsign=false",
                    "commit", "--quiet", "--no-verify", "-m", STAGING_SUBJECT], check=True,
                   env={**os.environ, "GIT_AUTHOR_DATE": pinned_date,
                        "GIT_COMMITTER_DATE": pinned_date})
    sha = subprocess.run(["git", "-C", checkout, "rev-parse", "HEAD"],
                         capture_output=True, text=True, check=True).stdout.strip()
    print(f"  staged {len(relative_paths)} empty source(s) out of the tree at {sha[:12]}; "
          f"HEAD now describes what is measured", flush=True)
    return sha, sorted(relative_paths)


def restore(checkout, attempts=4):
    """Returns the checkout to its pinned state, and shouts if it cannot.

    `git checkout -- .` restores the working tree from the index, and `git mv` stages its rename --
    so a moved file survived, and the next mutation was applied to an already-mutated tree. The
    sweep found `IsPartOfUtilsMovedMoved.kt` before this was noticed.

    RETRIED, BECAUSE THE FAILURE MODE IS NOT GIT REFUSING -- IT IS GIT NOT STARTING. Once a
    glide sweep died here with exit 3221225794, which is Windows' 0xC0000142: the process could not
    be created, because the machine was out of memory. The mutation stayed in the tree. `check=True`
    then raised inside an `atexit` handler, which prints a traceback nobody reads and leaves a
    CHECKOUT WITH A MUTATION STILL IN IT -- and the next measurement against that checkout is
    measuring the previous run's edit. That is the mockito `AndroidPlatform.java` story exactly: six
    commits reported a changed class none of them touched, and the conclusion drawn was wrong.

    So a transient failure is retried, and a permanent one is reported as loudly as this module can
    report anything, rather than raised into a handler that cannot act on it.

    TAKES NO STAGING ARGUMENT, AND THAT IS THE POINT. A project with `drop_empty_sources` is staged
    by `stage_checkout`, which COMMITS the removal -- so HEAD already describes the staged tree and
    `git reset --hard` restores it like any other pinned state. An earlier version of the staging
    feature re-deleted the files here on every call; see `stage_checkout` for why a dirty tree was
    the wrong mechanism and what it would have cost.
    """
    last = None
    for attempt in range(attempts):
        result = subprocess.run(
            ["git", "-C", checkout, "reset", "--hard", "--quiet"],
            capture_output=True, text=True)
        if result.returncode == 0:
            subprocess.run(["git", "-C", checkout, "clean", "-fdq"], check=False)
            return True
        last = result
        # Spawning failed rather than git refusing; give the machine a moment to free something.
        time.sleep(2 * (attempt + 1))

    print(
        f"\n!! THE CHECKOUT AT {checkout} IS STILL MUTATED AND COULD NOT BE RESTORED.\n"
        f"!! `git reset --hard` exited {last.returncode if last else '?'} on {attempts} attempts.\n"
        f"!! {(last.stderr or '').strip()[:200]}\n"
        "!! DO NOT MEASURE ANYTHING AGAINST THIS CHECKOUT until `git -C <checkout> status` is clean:\n"
        "!! the next run would attribute this run's mutation to whatever it is testing.\n",
        file=sys.stderr, flush=True)
    return False


# THE UNTRACKED-FILE RESET. `restore` puts tracked files back and `git clean -fdq`
# removes untracked ones, but never an IGNORED file -- and a guest's own tests write ignored files
# into its tree: iceberg's `core/derby.log`. The plugin hashes untracked and ignored files, so one
# that a baseline or an earlier row rewrote forced every later row for a reason outside the change
# (14 of iceberg's 16 rows in the 0.1 sweep). A clean CI checkout has no such file when the test
# task decides; this puts each row back to the files present when the checkout was prepared and
# the map captured, mirroring what the plugin's working-tree listing sees.
UNTRACKED_SNAPSHOT = "untracked-snapshot.json"
GRADLE_BUILD_SCRIPTS = ("build.gradle", "build.gradle.kts", "settings.gradle",
                        "settings.gradle.kts")


def is_build_state(path, project, is_gradle_build):
    """Whether an untracked path is build output or Gradle's own state, which the reset never
    touches: anything under `.gradle/`, `.kotlin/` or `.git/` (the map lives under `.gradle/`), and
    anything under a `build/` (or the project's `build_dir`) beside a Gradle build script."""
    directories = path.split("/")[:-1]
    if any(d in (".gradle", ".kotlin", ".git") for d in directories):
        return True
    names = {"build", build_dir(project)}
    return any(d in names and is_gradle_build("/".join(directories[:i]))
               for i, d in enumerate(directories))


def untracked_files(checkout, project):
    """Every untracked or ignored file in the checkout, less build state, as repo-relative paths.

    Listed with `--directory`, so a wholly untracked directory -- every `build/` among them, with
    hundreds of thousands of files on a large guest -- is one entry: build state is dropped whole,
    and only the rest is walked. Listing each file instead cost that walk on every row.
    """
    listed = subprocess.run(
        ["git", "-C", checkout, "ls-files", "-z", "--others", "--directory", "--", ".",
         ":(exclude,glob)**/.gradle/**"],
        capture_output=True, check=True).stdout.decode("utf-8", "surrogateescape")
    seen = {}

    def is_gradle_build(directory):
        if directory not in seen:
            at = os.path.join(checkout, directory) if directory else checkout
            seen[directory] = any(os.path.isfile(os.path.join(at, s)) for s in GRADLE_BUILD_SCRIPTS)
        return seen[directory]

    found = set()
    for entry in filter(None, listed.split("\0")):
        if not entry.endswith("/"):
            if not is_build_state(entry, project, is_gradle_build):
                found.add(entry)
            continue
        # A directory entry is build state when a file inside it would be.
        if is_build_state(entry + "-", project, is_gradle_build):
            continue
        for root, directories, files in os.walk(os.path.join(checkout, entry)):
            relative = os.path.relpath(root, checkout).replace(os.sep, "/")
            for name in files:
                path = f"{relative}/{name}"
                if not is_build_state(path, project, is_gradle_build):
                    found.add(path)
    return found


def reset_untracked(checkout, project, snapshot):
    """Removes every untracked or ignored file that is not in `snapshot`; returns them, sorted.

    Only files written SINCE the snapshot go. A file present when the checkout was prepared, or
    written by the capture whose map every row selects against, stays: the map recorded it.
    """
    removed = sorted(untracked_files(checkout, project) - set(snapshot))
    for relative in removed:
        try:
            os.remove(os.path.join(checkout, relative))
        except FileNotFoundError:
            pass
    return removed


def read_untracked_snapshot(path, stamp):
    """The snapshot saved for this capture stamp, or None. A snapshot from another capture is not
    this map's: the files a capture wrote are part of what it recorded."""
    try:
        with open(path, encoding="utf-8") as handle:
            stored = json.load(handle)
    except (OSError, ValueError):
        return None
    if stored.get("capture_stamp") != stamp:
        return None
    return set(stored.get("paths") or [])


def write_untracked_snapshot(path, stamp, paths):
    with open(path, "w", encoding="utf-8") as handle:
        json.dump({"capture_stamp": stamp, "paths": sorted(paths)}, handle, indent=1)


# THE PREFLIGHT: a behaviour-neutral edit, selected like a row, before any row is
# drawn. A trailing comment on the `package` line moves no line, so the compiled class is byte for
# byte the same and only a cause outside the change can force the run. The null change is no use:
# an empty change set runs the whole suite by definition (`empty-change-set`).
PREFLIGHT_COMMENT = " // yoriwake-harness preflight: a behaviour-neutral edit"
PACKAGE_LINE = re.compile(r"^\s*package\s+[\w.`]+\s*;?\s*$")


def apply_preflight_edit(checkout, relative):
    """Appends a comment to the file's `package` line. Returns the 1-based line, or None."""
    path = os.path.join(checkout, relative)
    with open(path, encoding="utf-8", newline="") as handle:
        lines = handle.read().split("\n")
    for number, line in enumerate(lines):
        stripped = line.rstrip("\r")
        if PACKAGE_LINE.match(stripped):
            lines[number] = stripped + PREFLIGHT_COMMENT + line[len(stripped):]
            with open(path, "w", encoding="utf-8", newline="") as handle:
                handle.write("\n".join(lines))
            return number + 1
    return None


def control_refusal(project, shapes, control, families=None):
    """Why a run may not measure `project` under `shapes` as asked, or None."""
    if shapes == NEUTRAL_SET and not control:
        return f"{NEUTRAL_SET} is a control's null change and neutral edit; pass --control"
    if families and not (control and shapes == POOL_SHAPES):
        return "--families narrows a pool/v1 control's draw; a measurement draws every family"
    # A REVERT, FORCING OR POOL CONTROL NEEDS HISTORY, A GROOVY ROOT OR A SHAPE no fixture copied
    # fresh has, so it runs on a corpus checkout. Its map is stamped with the control's arm, so the
    # next measurement of that checkout re-captures rather than reusing it.
    if control and not (project.get("fixture") and project.get("copy_of")) \
            and shapes not in CORPUS_CONTROL_SETS:
        return ("--control measures a planted plugin, on a fixture copied fresh each run only, or "
                f"{' or '.join(CORPUS_CONTROL_SETS)} on a corpus checkout; never another set on a "
                "checkout whose map another run could reuse")
    return None


def neutral_targets(checkout, project):
    """`neutral/v1`'s targets: the null change, then a neutral edit for every default body target's
    file in path order; the first that has a `package` line is the row."""
    sources = [path for path, kind, _ in distinct_targets(mutation_targets(checkout, project))[0]
               if kind in ("body", "aliased")]
    return ([(NULL_CHANGE_PATH, NULL_CHANGE, 0)]
            + [(path, NEUTRAL_EDIT, 0) for path in sorted(dict.fromkeys(sources))])


def apply_neutral(checkout, relative, kind):
    """Applies a `neutral/v1` row. Returns its label, or None when the file has no package line."""
    if kind == NULL_CHANGE:
        return f"{NULL_CHANGE_PATH}::{NULL_CHANGE}::nothing"
    line = apply_preflight_edit(checkout, relative)
    return None if line is None else f"{relative}::{NEUTRAL_EDIT}::line-{line}"


# THE KINDS WHOSE REASON IS ABOUT THE PROJECT, NOT THE CHANGE. `unmappable-paths` lists the paths
# that forced; `map-unusable` is the map itself. Every other kind either is fixed text that still
# depends on the change (`startup-coverage`: "the change touches startup coverage, which no test
# owns"), or is the daemon's verdict on the change (`daemon-refused`), so three identical reasons
# of those kinds say nothing about the setup.
SETUP_FORCED_KINDS = ("unmappable-paths", "map-unusable")
SETUP_FORCED_STREAK = 3


def change_names(label, declared=None):
    """The path and class names a row's change touched; a reason about the setup names none."""
    relative = label.split("::", 1)[0]
    stem = os.path.splitext(os.path.basename(relative))[0]
    names = {stem, stem + "Moved"} | set(declared(relative) if declared else ())
    return relative, {n for n in names if n}


def names_change(reason, label, declared=None):
    """Whether a decision reason names the row's changed file or one of its classes."""
    relative, names = change_names(label, declared)
    if relative in reason or os.path.basename(relative) in reason:
        return True
    return any(re.search(rf"(?<![\w$]){re.escape(name)}(?!\w)", reason) for name in names)


def setup_forced_reason(decisions, label, declared=None):
    """The reason a run forced for its setup rather than its change, or None.

    Reads the decision only: a forced run of a project-level kind whose reason names nothing the
    change touched.
    """
    if not decisions:
        return None
    notes = decisions["notes"]
    reason = notes.get("full-run-reason") or ""
    if (notes.get("outcome") != "full-run" or notes.get("full-run-kind") not in SETUP_FORCED_KINDS
            or not reason or names_change(reason, label, declared)):
        return None
    return reason


SETUP_FORCED = "setup-forced"
# The exit of a project stopped as setup-forced: not a sweep that answered nothing (1), a setup to
# fix before the project is re-run from its first row.
SETUP_FORCED_EXIT = 3


def declared_in(checkout):
    """The types each source of the checkout declares, for `names_change`; empty when unreadable."""
    def declared(relative):
        try:
            return mutations.declared_types(os.path.join(checkout, relative))
        except (OSError, UnicodeDecodeError):
            return []
    return declared


def run_preflight(checkout, project, candidates, scratch, map_dir, map_backup, snapshot,
                  select_flags, task_args, classes_args):
    """Selects the behaviour-neutral edit once, like a row. Returns the record stamped `preflight`,
    whose `setup_forced` holds the reason when the run forced for a cause outside the edit."""
    record = {"target": None, "setup_forced": None}
    if not restore(checkout):
        return {**record, "skipped": "the checkout could not be restored"}
    record["untracked_reset"] = (reset_untracked(checkout, project, snapshot)
                                 if snapshot is not None else False)
    if map_dir:
        restore_map(map_backup, map_dir)
    relative, line = None, None
    for relative in candidates:
        line = apply_preflight_edit(checkout, relative)
        if line is not None:
            break
    if line is None:
        restore(checkout)
        return {**record, "skipped": "no body target's source has a package line to edit"}
    record.update(target=relative, line=line)
    print(f"--- preflight: a comment on line {line} of {relative}", flush=True)
    code, rejected = compile_or_reject(checkout, project, classes_args,
                                       os.path.join(scratch, "compile-preflight.log"), gradle)
    if code == STALLED or rejected:
        restore(checkout)
        return {**record, "skipped": "the edited tree did not compile" if rejected
                else "the compile stalled"}
    log = os.path.join(scratch, "selected-preflight.log")
    started = time.time()
    code, seconds = gradle(checkout, project, [*INIT_SCRIPT_ARGS, *task_args,
                                               "-Pyoriwake.select", "-Pyoriwake.base=HEAD"]
                           + select_flags + TIMING_ARM_FLAGS, log)
    decisions, _ = trusted_decisions(read_decision_record(map_dir, started), log)
    restore(checkout)
    if map_dir:
        restore_map(map_backup, map_dir)
    notes = decisions["notes"] if decisions else {}
    record.update(seconds=seconds, decision_outcome=notes.get("outcome"),
                  full_run_kind=notes.get("full-run-kind"),
                  decision_reason=notes.get("full-run-reason") or None,
                  setup_forced=setup_forced_reason(decisions, relative, declared_in(checkout)))
    if code == STALLED:
        record["skipped"] = "the selected arm stalled"
    print(f"    preflight {record['decision_outcome'] or 'decided nothing'}"
          f"{' (' + record['full_run_kind'] + ')' if record['full_run_kind'] else ''}", flush=True)
    return record


def is_timed(position, recall_only, timing_cap):
    """Whether the row at this 1-based position in the draw order runs both arms for its paired
    time: every row of a timing sweep, or its first `timing_cap` rows, forced or not.
    Decided from the position alone, before either arm runs."""
    return not recall_only and (timing_cap is None or position <= timing_cap)


def full_arm_skipped(timed, decisions, selected_ran):
    """Whether an untimed row takes its selected arm as the full arm: it forced, every writer
    agreeing, and reached tests. A timed row runs the full arm whatever it decided."""
    return (not timed and decisions is not None
            and decisions["notes"].get("outcome") == "full-run" and bool(selected_ran))


def setup_forced_streak(findings, declared=None):
    """The reason when the last three rows that recorded a decision all forced, of a project-level
    kind, with one byte-identical reason naming no path or class any of them changed; else None.

    Reads decisions and changed paths only, never what a row missed. A cause that went away before
    the third row breaks the run of identical reasons, and the narrowed row counts like any other.
    """
    decided = [row for row in findings if row.get("decision_outcome") is not None]
    last = decided[-SETUP_FORCED_STREAK:]
    if len(last) < SETUP_FORCED_STREAK:
        return None
    reasons = {row.get("decision_reason") for row in last}
    if len(reasons) != 1:
        return None
    reason = reasons.pop()
    if not reason or any(row.get("decision_outcome") != "full-run"
                         or row.get("full_run_kind") not in SETUP_FORCED_KINDS
                         or names_change(reason, row["target"], declared) for row in last):
        return None
    return reason


# COMPLEMENT GROUND TRUTH. A narrowed row's selected arm already ran every test it kept,
# with the change in place, so its ground truth needs only the tests it dropped: the complement,
# every test class holding one, run whole through the full arm's init script and fork settings so
# each dropped test keeps its class and JVM context. It ships OFF (`--ground-truth full`): a dropped
# test run apart from the suite can pass where the suite fails it, and a miss would vanish. `check`
# runs both arms on every untimed narrowed row and compares them; `auto` runs the complement alone
# except on check rows, and is enabled by the maintainer only after a replay of the complement and
# the planted-miss controls agree.
GROUND_TRUTH_MODES = ("full", "check", "auto")
FULL_TRUTH, COMPLEMENT_TRUTH, CHECKED_TRUTH = "full", "complement", "complement+full"
# Every project's first untimed narrowed rows carry a full arm before it relies on less.
COMPLEMENT_FIRST_ROWS = 2
# One in this many of the remaining narrowed rows is a check row, chosen from the target's hash.
COMPLEMENT_SAMPLE = 8
# Past this share of the baseline suite's time the complement saves little; the full arm is truer.
COMPLEMENT_COST_SHARE = 0.5
SUITE_PROFILE = "suite-profile.json"


def dropped_leaves(decisions):
    """The record's leaf ids the selection did not include."""
    leaves = record_leaves(decisions)
    return [test for test, verdict in decisions["decided"]
            if test in leaves and verdict.partition("/")[0] != "included"]


def complement_classes(decisions):
    """The top-level classes holding a dropped test, sorted, or None when a dropped test names no
    class (the complement cannot then be run as whole classes)."""
    classes = set()
    for test in dropped_leaves(decisions):
        name = record_class(test)
        if name is None:
            return None
        classes.add(name.split("$", 1)[0])
    return sorted(classes)


def complement_filters(classes):
    """Gradle `--tests` arguments that run each class whole, its nested classes included."""
    return [arg for name in classes for arg in ("--tests", name, "--tests", name + "$*")]


def kept_through_shared_jvm(decisions):
    """Classes of tests the selection kept only through a `shares-jvm-*` rule, or None when the
    record carries no rules."""
    rules = decisions.get("rules")
    if rules is None:
        return None
    included = set(decisions["included"])
    return {record_class(test) for test, tokens in rules.items()
            if test in included and not coverage_only(tokens)
            and any(token.startswith(SHARES_JVM_RULE_PREFIX) for token in tokens)} - {None}


def capture_class_order(map_dir):
    """`{jvm: {class: first position}}` from the map's positions, or None when it has none."""
    order = {}
    try:
        with open(os.path.join(map_dir, sweep_data.POSITIONS_FILE), encoding="utf-8") as handle:
            for line in handle:
                fields = line.rstrip("\n").split("\t", 2)
                if len(fields) != 3 or not fields[1].isdigit():
                    continue
                name = record_class(fields[2])
                if name is not None:
                    slot = order.setdefault(fields[0], {})
                    slot[name] = min(int(fields[1]), slot.get(name, int(fields[1])))
    except (OSError, TypeError):
        return None
    return order


def ran_after_shared_jvm_kept(decisions, map_dir):
    """The shared-JVM check: a dropped test's class ran, in the capture, in the same test JVM after a
    class the selection kept through a `shares-jvm-*` rule. True when either side cannot be read:
    a rule that cannot be checked routes the row to a full arm."""
    kept = kept_through_shared_jvm(decisions)
    order = capture_class_order(map_dir) if map_dir else None
    if kept is None or order is None:
        return True
    dropped = {record_class(test) for test in dropped_leaves(decisions)} - {None}
    return any(order[jvm][d] > order[jvm][k] for jvm in order for d in dropped & set(order[jvm])
               for k in kept & set(order[jvm]))


def sampled_check(label, kind, index, seed):
    """The sampled check: one in `COMPLEMENT_SAMPLE`, by the target's hash and the sweep's seed."""
    digest = hashlib.sha256(f"{label}\0{kind}\0{index}\0{seed}".encode()).hexdigest()
    return int(digest, 16) % COMPLEMENT_SAMPLE == 0


def check_rules(mode, untimed_narrowed_before, shares_jvm, flagged_project, single_jvm, sampled):
    """Every reason a narrowed row also runs the full arm beside the complement; empty when none.

    All of them, not the first: a row that is a check row for being among the first two is named
    for the shared-JVM rule too when that also holds, which is what the order-dependent control
    asks to see. Reads the row's position, its decision, the map, flags known before the sweep and
    a hash -- never what an arm failed or missed.
    """
    return [rule for rule, holds in (
        ("check-mode", mode == "check"),
        ("first-rows", untimed_narrowed_before < COMPLEMENT_FIRST_ROWS),
        ("paired-arm", single_jvm),
        ("shares-jvm", shares_jvm),
        ("order-dependent-project", flagged_project),
        ("sampled", sampled)) if holds]


def complement_share(classes, profile):
    """The complement's share of the unmutated suite's time, or None when it cannot be priced."""
    seconds = (profile or {}).get("class_seconds")
    if not seconds or any(name not in seconds for name in classes):
        return None
    total = sum(seconds.values())
    return sum(seconds[name] for name in classes) / total if total else None


def class_seconds(checkout, module, since, task, build):
    """`{top-level class: seconds}` from the fresh result XML of an unmutated full run."""
    parts = ([checkout] + ([module_path(module)] if module else [])
             + [build, "test-results", task, "*.xml"])
    seconds = {}
    for path in glob.glob(os.path.join(*parts)):
        if os.path.getmtime(path) < since:
            continue
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        for suite in root.iter("testsuite"):
            name = (suite.get("name") or "").split("$", 1)[0]
            try:
                seconds[name] = seconds.get(name, 0.0) + float(suite.get("time") or 0)
            except ValueError:
                continue
    return seconds


def read_suite_profile(path, key):
    try:
        with open(path, encoding="utf-8") as handle:
            stored = json.load(handle)
    except (OSError, ValueError):
        return None
    return stored if stored.get("key") == key else None


def write_suite_profile(path, key, suite_distinct, seconds):
    with open(path, "w", encoding="utf-8") as handle:
        json.dump({"key": key, "suite_distinct": suite_distinct, "class_seconds": seconds}, handle,
                  indent=1)


def union_outcomes(selected, complement):
    """The row's ground truth from its selected arm and its complement: what either ran and what
    either failed. Per method only when both builds' outcomes are complete."""
    ran = set(selected[0] or ()) | set(complement[0] or ())
    failed = set(selected[1] or ()) | set(complement[1] or ())
    if selected.methods is None or complement.methods is None:
        return Outcomes(ran, failed, cause=(selected.cause if selected.methods is None
                                            else complement.cause) or "no per-method outcomes")
    return Outcomes(ran, failed, Methods(selected.methods.ran | complement.methods.ran,
                                         selected.methods.failed | complement.methods.failed,
                                         selected.methods.collapsed | complement.methods.collapsed))


def truth_verdicts(selected, truth, decisions, already_failing):
    """What one ground truth says of a row, on its own keys: `(induced, ran_but_passed)`, as
    `class::name` sets, before any re-run sets a test aside."""
    keys = row_keys(selected, truth, decisions, "ground truth")
    selected_ran, selected_failed = keyed(selected, keys.scheme)
    induced = subtract_baseline(keyed(truth, keys.scheme)[1], already_failing, keys)
    missed, undetermined, shared_key = unmatched(induced, selected_ran, keys.shared)
    passed = ran_but_passed(induced, selected_failed, missed, undetermined, (), (), shared_key)
    return ({f"{c}::{n}" for c, n in induced}, {f"{c}::{n}" for c, n in passed})


def complement_disagreements(selected, complement, full, decisions, already_failing):
    """Where the complement and the full arm disagree about a row: each test either one calls
    induced or ran-but-passed and the other does not, with both verdicts. Empty when they agree,
    which is also when the row status (failures or none) agrees."""
    by_complement = truth_verdicts(selected, union_outcomes(selected, complement), decisions,
                                   already_failing)
    by_full = truth_verdicts(selected, full, decisions, already_failing)
    found = []
    for which, name in ((0, "induced"), (1, "ran-but-passed")):
        for test in sorted(by_complement[which] ^ by_full[which]):
            found.append({"test": test, "verdict": name,
                          "complement": test in by_complement[which],
                          "full": test in by_full[which]})
    return found


def route_ground_truth(mode, timed, decisions, switched_off, profile, untimed_narrowed_before,
                       map_dir, flagged_project, single_jvm, label, kind, index, seed,
                       priced=True):
    """`(ground truth, why, complement classes)` for one row under `mode`; `why` is a list.

    `full` with nothing to say under `--ground-truth full`, on a timed row (its paired time needs
    the full arm) and on a row that did not narrow. `full` with why when the complement cannot
    stand in: switched off for the project, a dropped test that names no class, a suite that cannot
    be priced, or (when `priced`) a complement costing more than `COMPLEMENT_COST_SHARE` of it.
    Otherwise `complement+full` on a check row, why its rules, and `complement` elsewhere. Reads
    nothing any arm failed.
    """
    narrowed = bool(decisions) and decisions["notes"].get("outcome") == "narrowed"
    if mode == "full" or timed or not narrowed:
        return FULL_TRUTH, [], None
    if switched_off:
        return FULL_TRUTH, ["switched-off"], None
    classes = complement_classes(decisions)
    if not classes:
        return FULL_TRUTH, ["unnamed-class" if classes is None else "nothing-dropped"], None
    share = complement_share(classes, profile)
    if share is None or (profile or {}).get("suite_distinct") is None:
        return FULL_TRUTH, ["unpriced"], None
    if priced and share > COMPLEMENT_COST_SHARE:
        return FULL_TRUTH, ["cost"], None
    rules = check_rules(mode, untimed_narrowed_before,
                        ran_after_shared_jvm_kept(decisions, map_dir), flagged_project, single_jvm,
                        sampled_check(label, kind, index, seed))
    return (CHECKED_TRUTH if rules else COMPLEMENT_TRUTH), rules, classes


def complement_needs_full(selected, union, decisions, already_failing):
    """Whether a complement row's failures cannot be keyed per method with no sibling sharing a key:
    an XML fallback, or an induced failure a shared key would decide. Such a row runs the full
    arm too."""
    keys = row_keys(selected, union, decisions, "complement")
    if keys.scheme != PER_METHOD:
        return True
    induced = subtract_baseline(keyed(union, keys.scheme)[1], already_failing, keys)
    return bool(unmatched(induced, keyed(selected, keys.scheme)[0], keys.shared)[2])


def resumed_switch_off(previous, findings):
    """The switch-off a resumed file carries, or None. A file started fresh carries none: its rows
    were discarded with the stamp."""
    return previous.get("complement_switched_off") if findings else None


def drop_superseded(findings):
    """`findings` without each complement row a re-measured row of the same mutation replaced.

    A complement row leaves the file only when its full-arm row has arrived, so a sweep killed
    during the re-measure pass resumes with the rows it still owes, and re-measures them.
    """
    replaced = {row_key(row) for row in findings if row.get("remeasured")}
    return [row for row in findings
            if not (row.get("ground_truth") == COMPLEMENT_TRUTH and row_key(row) in replaced)]


def complement_rows_to_rerun(findings):
    """The rows whose only ground truth is a complement: after a disagreement switches the
    complement off for the project, each is measured again with a full arm."""
    return [row for row in findings if row.get("ground_truth") == COMPLEMENT_TRUTH]


# CI PER-RUN VARIABLES, with other values on a selecting arm than on the arm that recorded its map,
# as a pull request's build differs from the default branch's that saved it. An
# `environment-changed` row naming one is a per-build value forcing every build: the release
# check's evidence that the environment fingerprint compares nothing that moves per run.
CI_RUN_VARIABLES = ("GITHUB_RUN_ID", "GITHUB_SHA", "GITHUB_REF", "GITHUB_EVENT_NAME", "GITHUB_HEAD_REF",
                    "BUILD_NUMBER", "CI_JOB_ID")
RECORDING_CI_VALUES = dict(zip(CI_RUN_VARIABLES, ("1001", "a" * 40, "refs/heads/main", "push", "", "41", "7001")))
SELECTING_CI_VALUES = dict(zip(CI_RUN_VARIABLES, ("1002", "b" * 40, "refs/pull/1/merge", "pull_request",
                                                  "feature", "42", "7002")))


def ci_environment(task_args, base=None):
    """The environment an arm runs in: [base] with the CI per-run values of its kind of arm."""
    values = SELECTING_CI_VALUES if "-Pyoriwake.select" in task_args else RECORDING_CI_VALUES
    return {**(os.environ if base is None else base), **values}


def gradle(checkout, project, task_args, log_path):
    """Drives one Gradle build for the harness, with the build cache off.

    Not a way to flatter the tool -- both arms lose the cache equally, and the selected arm is the
    one that benefits more from having it. It is that a cached test task DOES NOT RUN, and a
    measurement of which tests selection chose cannot be taken from a run where nothing executed.
    A restored task leaves a full set of test-results XML with fresh timestamps, which the harness
    read as "what selection ran"; that is how a replay of three real detekt commits reported
    2256/2256, 2259/2259 and 46/2259 having executed no tests at all.

    Deliberately NOT --rerun-tasks, which also defeats up-to-date checks and would rebuild the world
    on every mutation. The narrow flag is the honest one.

    What the cache means for a real user is a separate question, already answered separately and
    more usefully: a comparison with Develocity measured a warm-cache run at 25 seconds and no
    tests, which is the honest statement that where the cache hits, no selector matters.
    """
    # WHAT THE ARM COST BESIDE ITS WALL CLOCK, kept per log path so no caller's signature moves:
    # task durations from a build-event listener, the plugin's own timers, and the test JVMs'
    # memory sampled from outside. See sweep_data and docs/sweep-data-dictionary.md.
    times = log_path + ".tasks.tsv"
    counters = log_path + ".counters.tsv"
    outcomes = log_path + ".outcomes.tsv"
    for stale in (times, counters, outcomes):
        if os.path.exists(stale):
            os.remove(stale)
    instruments = ["-I", sweep_data.TASK_TIMES_INIT, f"-Dyoriwake.harness.taskTimes={times}"]
    # EACH TEST'S OUTCOME PER METHOD, which the XML cannot give (see `results_of`). Only on builds
    # without the configuration cache: 11 guests enable it in their own `gradle.properties`, and an
    # init-script test listener is not known to be safe under it. A build without the listener is
    # read on XML keys, and says so.
    if "--no-configuration-cache" in task_args:
        instruments += ["-I", sweep_data.TEST_OUTCOMES_INIT,
                        f"-D{sweep_data.TEST_OUTCOMES_PROPERTY}={outcomes}"]
    if any(arg == INIT_SCRIPT for arg in task_args):
        instruments.append(f"-Pyoriwake.internal.counters={counters}")
    launched = time.time()
    with sweep_data.TestJvmSampler() as sampler:
        result = run([wrapper(checkout), "-I", GUEST_SELECTION_OFF] + daemon_jvm_flags(project) +
                     guest_flags(project) + task_args + instruments +
                     ["--no-build-cache", "--console=plain"],
                     checkout, log_path,
                     progress_paths=test_progress_paths(checkout, project),
                     env=ci_environment(task_args))
    ended = time.time()
    # THE LISTENER'S LAST LINES CAN LAND AFTER THE CLIENT RETURNS: build events reach the service
    # asynchronously, and a selecting arm's `:test` line was missing when read at once. Waited for
    # only when the log shows the test task ran, so a compile-only build costs nothing.
    if test_task_ran(log_path, project.get("module") or "", test_task_name(project)):
        wanted = ":" + test_task_path(project).lstrip(":") + "\t"
        deadline = time.time() + 10
        while time.time() < deadline:
            try:
                with open(times, encoding="utf-8") as handle:
                    if any(line.startswith(wanted) for line in handle):
                        break
            except OSError:
                pass
            time.sleep(0.2)
    ARM_STATS[log_path] = arm_record(log_path, project, sampler, launched, ended)
    return result


# One entry per build this process ran, keyed by its log path. See `gradle`.
ARM_STATS = {}


def arm_record(log_path, project, sampler, launched, ended):
    """What one build cost: test JVMs and their peak memory, and where its wall clock went."""
    try:
        counters = config_cost.read_counters(log_path + ".counters.tsv")
    except config_cost.Unreadable:
        counters = None
    return {**sampler.stats(),
            "tasks": sweep_data.read_task_times(log_path + ".tasks.tsv", test_task_path(project),
                                                launched, ended),
            "plugin": sweep_data.plugin_timers(counters),
            "decision_write_failures": decision_write_failures(log_path)}


def arm_stats(log_path):
    """The record `gradle` kept for the build that wrote `log_path`, or None."""
    return ARM_STATS.get(log_path) if log_path else None


# Stands for the yoriwake checkout in a project file's `flags`, so an init script that lives there
# can be named without naming a machine.
CHECKOUT_TOKEN = "{yoriwake}"


def guest_flags(project):
    """A project's own `flags`, with `CHECKOUT_TOKEN` expanded to the checkout.

    Expanded where a build is invoked and stored as written: a results file compares the flags it
    was measured under, and an absolute path would make the same flags differ between machines.
    """
    return [flag.replace(CHECKOUT_TOKEN, SOURCE) for flag in project.get("flags", [])]


def daemon_jvm_flags(project):
    """Daemon JVM settings a project needs: which JDK runs it, and how much heap it gets.

    `daemon_jvmargs` exists because the map decode shares the daemon's heap with the build that
    just ran. Measured on rxjava's 14,221-test capture: decoding it needs between
    256m and 384m and takes about five seconds, in a JVM doing nothing else. The default daemon
    heap survives that in isolation and did not survive it after a 14,221-test build, which is why
    that capture died with OutOfMemoryError and left a map with no `scope` file.

    It belongs here rather than in the checkout's `gradle.properties` because `restore()` reverts
    the checkout, so a setting left there is wiped by the next sweep that touches the project.

    Both arms of a comparison get the same flags, so raising the heap does not favour one of them.

    `daemon_java_version` is distinct from a toolchain, and the distinction is what kept ktlint out
    of the corpus. A toolchain compiles and runs TESTS on a chosen JDK; ktlint's `build-logic` is
    itself compiled for JVM 26, so the daemon that evaluates the build must be 26 or the build fails
    before any toolchain is consulted: "Dependency requires at least JVM runtime version 26. This
    build uses a Java 21 JVM."

    Found by version rather than named by path, so the project file stays portable and no committed
    file holds one machine's directory layout.
    """
    jvmargs = project.get("daemon_jvmargs")
    jvmargs = [f"-Dorg.gradle.jvmargs={jvmargs}"] if jvmargs else []

    version = project.get("daemon_java_version")
    if not version:
        return jvmargs
    home = find_jdk(int(version))
    if home is None:
        raise SystemExit(
            f"{project['name']} needs a JDK {version} daemon and none was found. Gradle provisions "
            f"toolchains under ~/.gradle/jdks; building any project that requests {version} will "
            f"put one there. Refusing to run on the wrong JVM rather than reporting a failure that "
            f"is this machine's and not the project's."
        )
    return [f"-Dorg.gradle.java.home={home}"] + jvmargs


def find_jdk(version):
    """A JDK of this major version, from Gradle's provisioned toolchains or the usual local roots."""
    # A GitHub runner names each JDK it holds as JAVA_HOME_<major>_<arch>.
    for arch in ("X64", "ARM64"):
        home = os.environ.get(f"JAVA_HOME_{version}_{arch}")
        if home and os.path.isfile(os.path.join(home, "release")):
            return home.replace("\\", "/")
    roots = [os.path.expanduser("~/.gradle/jdks"), os.path.expanduser("~/.jdks")]
    for root in roots:
        if not os.path.isdir(root):
            continue
        for entry in sorted(os.listdir(root)):
            for candidate in _java_homes(os.path.join(root, entry)):
                release = os.path.join(candidate, "release")
                if not os.path.isfile(release):
                    continue
                with open(release, encoding="utf-8", errors="replace") as handle:
                    text = handle.read()
                # JAVA_VERSION="26.0.1" -- match the major only, since a JDK is identified by it and
                # the patch level moves under us whenever Gradle re-provisions.
                match = re.search(r'JAVA_VERSION="(\d+)', text)
                if match and int(match.group(1)) == version:
                    return candidate.replace("\\", "/")
    return None


def _java_homes(entry):
    """The places a JAVA_HOME can sit under one directory in a toolchain root.

    Gradle's provisioned layout is not flat, and on macOS it is two levels deeper than elsewhere:
    `~/.gradle/jdks/eclipse_adoptium-26-aarch64-os_x.2/jdk-26.0.1+8/Contents/Home`. Looking only at
    the top entry -- which is what this did -- finds no `release` file, so every provisioned
    toolchain on a Mac was invisible and ktlint was refused for wanting a JDK 26 that was sitting
    unpacked on the disk.

    Bounded to the layouts that exist rather than walked, so a large toolchain root stays cheap.
    """
    yield entry
    yield os.path.join(entry, "Contents", "Home")
    try:
        children = sorted(os.listdir(entry))
    except OSError:
        return
    for child in children:
        nested = os.path.join(entry, child)
        if os.path.isdir(nested):
            yield nested
            yield os.path.join(nested, "Contents", "Home")


def test_classes_task(project):
    """The compile task for the measured test source set.

    `jvmTest` compiles from `jvmTestClasses`, not `testClasses`. Asking for the wrong one is not a
    smaller warm-up, it is NO warm-up: either Gradle fails (and the caller ignores the exit code) or
    the task is NO-SOURCE. Either way the tree is uncompiled when the first timed arm starts, the
    selected arm pays the Kotlin compile because it always goes first, and the savings figure is
    biased against selection with nothing in the output saying so -- which is the exact artefact the
    warm-up exists to remove.

    `<test_task>Classes` is a JVM convention, and a guest that does not follow it may name its own
    task instead. duckduckgo-android is the one that forced this: it runs `testPlayDebugUnitTest`
    and `:app` has NEITHER `testPlayDebugUnitTestClasses` NOR `playDebugUnitTestClasses` -- both
    were asked and both came back "not found in project ':app'". Its terminal compile
    task is `compilePlayDebugUnitTestJavaWithJavac`, whose graph carries the kapt, ksp and Kotlin
    compile phases a mutation can fail in.

    The derivation stays the default because it is right for every JVM guest and for `jvmTest`.
    What changed is that being wrong is no longer silent: see `assert_classes_task`.
    """
    stated = project.get("test_classes_task")
    if stated:
        return str(stated)
    name = test_task_name(project)
    return f"{name}Classes"


def assert_classes_task(checkout, project, log_path, probe):
    """Refuses unless the guest actually has the compile task the warm-up and the gate ask for.

    THE GATE NEVER RAN ON duckduckgo-android, and nothing anywhere said so. All 31 `compile-*.log`
    files under `bench/logs/duckduckgo-android/` end in `Cannot locate tasks that match
    ':app:testPlayDebugUnitTestClasses'` and not one carries a compiler diagnostic. Three things
    followed, and each of them reads as something it is not:

      * `mutations_rejected: 0` meant "the gate was never asked", not "the gate asked and accepted";
      * the mutated tree was never compiled before either arm was timed, which is the bias the
        warm-up exists to remove -- the selected arm always goes first and always paid for it;
      * one invalid mutation reached the arms and was recorded as `build-failed`, which
        `assert_recall.py` exits 1 on and which reads as though selection broke the guest's build.

    ASKED ONCE, BEFORE THE CAPTURE, because that is the only cheap moment. A dry run of the task is
    one configuration pass -- 16 to 30 seconds on duckduckgo-android, the heaviest guest in the
    corpus at 195 build files -- against a capture that costs hours and a sweep that costs tens of
    them. Across 34 projects it is minutes on a 30-to-42-hour sweep.

    THE CHECK IS THE EXIT CODE, NOT THE WORDING. Matching "Cannot locate tasks" would go quiet the
    day Gradle rewords it, and go quiet into exactly the silent per-mutation failure this replaces
    -- the failure mode `a-guard-keyed-to-wording-goes-green-when-the-wording-moves` records. A dry
    run that exits 0 proves the task exists; anything else refuses. The wording is read only to say
    WHY, and a reworded failure still refuses, just less helpfully.

    A NOT-FOUND TASK IS A HARNESS FAULT AND NEVER A GENERATOR REJECTION. `MUTATION_REJECTION_CEILING`
    is 0.2 and `rejection_rate_refuses` refuses a project above it, so classifying these as
    rejections would take duckduckgo-android's rate to 1.0 and discard the 30 valid rows it does
    produce. That is why this refuses here rather than teaching `compiler_rejected_the_source` a
    new shape.

    `probe` HAS NO DEFAULT, for the reason `compile_or_reject` gives: a defaulted collaborator is
    where a protection goes missing in silence.
    """
    task = test_classes_task(project)
    module = project.get("module") or ""
    path = f":{module}:{task}" if module else task
    code, _ = probe(checkout, project, [path, "--dry-run"], log_path)
    if code == 0:
        return
    name = project.get("name") or "this project"
    # WHICH FAILURE IT WAS, read from the log, because the remedies are unrelated and only one of
    # them is `test_classes_task`. The exit code alone says the probe failed; a dependency that
    # would not resolve, a daemon that died and a broken sibling build script all exit non-zero
    # here, and telling their owner to name a different compile task is a confidently wrong
    # sentence. This is the same distinction `compile_or_reject` refuses to blur when it asks
    # `compiler_rejected_the_source` rather than trusting an exit code.
    #
    # THE REFUSAL DOES NOT DEPEND ON THE WORDING. Any non-zero exit still stops the sweep, which is
    # the safe direction and the reason the check exists; the log is read only to say WHY, so a
    # Gradle that rewords "Cannot locate tasks" loses the specific remedy and keeps the refusal.
    if code != STALLED and not task_is_missing(log_path):
        raise SystemExit(
            f"the probe for {path} on {name} failed for a reason that is not a missing task, so "
            f"whether the compile gate can run here is unestablished. This is the guest's build "
            f"failing to configure -- an unresolved dependency, a broken sibling script, a daemon "
            f"that died -- and not something `test_classes_task` can fix. Refusing rather than "
            f"sweeping under a gate whose state is unknown. Log: {log_path}"
        )
    if code == STALLED:
        raise SystemExit(
            f"the probe for {path} was killed before it answered, so whether {name} has that task "
            f"is unestablished. Everything below it -- the warm-up compile and the mutation gate --"
            f" depends on the answer, and a sweep that guesses it is the sweep that produced 31 "
            f"silent failures and a `build-failed` row. Log: {log_path}"
        )
    raise SystemExit(
        f"{name} does not have {path}, so the compile gate cannot run on it and the mutated tree "
        f"would never be compiled before either arm is timed. `{task}` is DERIVED from "
        f"`test_task`, which is a JVM convention an Android unit-test variant does not follow. "
        f"Name the task the guest actually has with `test_classes_task` in its project file -- for "
        f"duckduckgo-android that is `compilePlayDebugUnitTestJavaWithJavac`, whose graph carries "
        f"the kapt, ksp and Kotlin compile phases a mutation can fail in. Refusing rather than "
        f"continuing: this failure is silent once per mutation, and it reported "
        f"`mutations_rejected: 0` for a gate that was never asked. Log: {log_path}"
    )


def module_path(module):
    """A Gradle path as a FILE path: `library:test` is the directory `library/test`.

    One home, because it already drifted once. qualify.py translated the colons and run.py did not,
    so on a nested module every results read came back empty and every sweep row was filed as
    "ground truth never reached the tests" -- which reads like a build failure and was a path bug.
    """
    return (module or "").replace(":", os.sep)


def results_reader(project):
    """[results_of] bound to this project's build directory.

    Bound once and passed down rather than threaded as an argument through `isolation.py` and
    the analysis scripts, which take the reader as a function precisely so they need to know nothing
    about project files. A call site that forgets the directory reads an empty one, and an empty
    read is indistinguishable from a build that ran nothing -- which is the defect this exists for.
    """
    build = build_dir(project)

    def read(checkout, module, since, task, log_path):
        return results_of(checkout, module, since, task, build, log_path=log_path,
                          task_path=test_task_path(project))

    return read


def build_dir(project):
    """The directory this build writes its output into, which is not always `build`.

    Gradle's default is `build/` and almost every project takes it, so the harness composed
    `<module>/build/test-results/<task>` everywhere. hibernate-orm does not: it sets its layout to
    `target/`, and that made the screen report "the build exited 0 but ran NO TESTS --
    a green build that ran nothing" for a run that executed **24,798 tests**, captured a 34,561-record
    map, and left **4,755 result XML files** in `hibernate-core/target/test-results/test`.

    That is the same defect `module_dir` exists for, reached by a different route and pointing the
    other way: `module_dir` was a real directory under a wrong NAME, this is the right name under a
    wrong ROOT. Both end in the harness reading an empty directory and filing a real run as nothing --
    which is precisely the failure shape this corpus exists to detect in someone else's tool.

    There is no way to derive it. Gradle exposes the layout to the build, not to a caller outside it,
    so a project that moves its output has to say so here.
    """
    return project.get("build_dir") or "build"


def module_dir(project):
    """The DIRECTORY a module's build output lands in, which is not always its Gradle name.

    Usually the two are the same and `module` answers both questions. Micronaut is where they part:
    `settings.gradle` says `include "core"`, and `io.micronaut.build.shared.settings` then renames
    every subproject with a `micronaut-` prefix, so the task is `:micronaut-core:test` while the
    output is still under `core/build/`. Reading results from a directory named after the Gradle
    project found nothing, and micronaut-core was recorded as "a green build that ran nothing" while
    7,394 tests (2 skipped) sat in `core/build/test-results/test`.

    `module_dir` in a project file overrides it. There is no way to derive one from the other -- the
    rename happens inside a settings plugin -- so it is stated rather than guessed.
    """
    return project.get("module_dir") or project.get("module") or ""


def test_progress_paths(checkout, project):
    """Where a running test task writes while Gradle's console says nothing.

    Gradle streams two binary files here for the whole of a test task and writes the XML only at the
    end, so this is the only continuous signal that a silent suite is alive. Given to
    `process.run`'s stall guard, which otherwise reads a long test task as a hung one -- armeria's
    `:core:test` was killed that way while its worker was still working.

    Returns the directory whether or not it exists yet: `progress_mark` treats an absent path as no
    progress, which is correct for the window before the task starts writing.
    """
    return [os.path.join(checkout, module_dir(project), build_dir(project), "test-results",
                         test_task_name(project), "binary")]


def test_task_name(project):
    """The name of the Gradle task that actually RUNS the JVM tests.

    Not always `test`. In a Kotlin Multiplatform build it is `jvmTest`, and asking for `test` there
    is the worst outcome this harness can produce: kotlinx-coroutines compiled everything, printed
    `BUILD SUCCESSFUL in 6m 25s`, exited 0 and ran ZERO tests. A green build that ran nothing is the
    exact failure this project exists to prevent, and the harness would have scored it as a pass.

    So it is configurable per project, and every task path and results directory is derived from it
    rather than spelled `test` in ten places.
    """
    return str(project.get("test_task") or "test")


def test_task_path(project):
    """`:module:task`, or just `task` for a root-level build."""
    module = project.get("module") or ""
    name = test_task_name(project)
    return f":{module}:{name}" if module else name


def test_task_ran(log_path, module, task):
    """Whether Gradle executed the test task, however few tests it then ran.

    Separates "the build never got there" from "it ran and selected nothing". The second is the
    catastrophic case this harness exists to find; filing it as a build error hides it.
    """
    try:
        with open(log_path, encoding="utf-8", errors="replace") as handle:
            log = handle.read()
    except OSError:
        return False
    marker = f":{module}:{task}" if module else f":{task}"
    # Matched by task path then classified by outcome. An earlier version required the line to END
    # with the task path, which no line with an outcome suffix ever does -- so `> Task :test FAILED`
    # read as "the task never ran", while the SKIPPED and FROM-CACHE checks below it could never
    # fire at all. It went unnoticed because the only caller used it as a fallback when results were
    # missing; the moment it became the gate on whether a run may be measured, everything was
    # excluded.
    #
    # FAILED counts as executed: a mutation that breaks tests is the whole point, and the task
    # failing is how it says so.
    skipped = ("SKIPPED", "FROM-CACHE", "UP-TO-DATE", "NO-SOURCE")
    for line in log.splitlines():
        if not line.startswith("> Task "):
            continue
        # Named `logged`, not `task`: this used to rebind the parameter it was comparing against,
        # which was harmless only because `marker` is computed before the loop.
        logged = line[len("> Task "):].split()[0]
        if logged == marker:
            return not any(word in line for word in skipped)
    return False


# THE TWO KEY SCHEMES A ROW'S FAILURES CAN BE MATCHED ON. The JUnit XML key `(class, name)` names a
# test by its display name, so where a class's invocations carry no method in that name every method
# shares a few keys, and a selected method stands in for an excluded one. The per-method key splits
# them using `test-outcomes.init.gradle`'s lines. See docs/sweep-data-dictionary.md (`result_keys`).
PER_METHOD, XML = "per-method", "xml"
# Joins a per-method key's segments: the template, factory or feature above the test, then the
# test's own display name. Escaped inside a segment, with the backslash, so it splits back exactly.
KEY_SEPARATOR = sweep_data.KEY_SEPARATOR


def _escape_segment(value):
    return value.replace("\\", "\\\\").replace(KEY_SEPARATOR, " \\> ")


def _unescape_segment(value):
    return value.replace(" \\> ", KEY_SEPARATOR).replace("\\\\", "\\")


def method_key(cls, ancestors, display_name):
    """A test's per-method key: `(class, "b() > v = 1")`, or the XML key where no ancestor sits
    between class and test, so a plain-method suite's keys are the XML reader's, byte for byte."""
    leaf = display_name.removesuffix("()")
    return cls, KEY_SEPARATOR.join([_escape_segment(a) for a in ancestors] + [_escape_segment(leaf)])


def has_ancestor(test):
    """Whether a per-method key names a template, factory or feature above the test."""
    return KEY_SEPARATOR in test[1]


def xml_key(test):
    """The XML key a per-method key projects onto: its class and the test's own display name."""
    return test[0], _unescape_segment(test[1].split(KEY_SEPARATOR)[-1])


class Methods:
    """One build's per-method key sets, and the keys two different tests of one class shared.

    `collapsed` holds keys with no ancestor that two tests with different own names produced: an
    engine that reports invocations straight under their class, or two methods given one display
    name. Such a key names no single method (see `shares_a_key`).
    """

    def __init__(self, ran, failed, collapsed=frozenset()):
        self.ran, self.failed, self.collapsed = ran, failed, set(collapsed)


class Outcomes(tuple):
    """`(ran, failed)` on XML keys, which every count reads, with the per-method sets beside them.

    Unpacks as the pair the reader always returned. `methods` is None when this build's per-method
    outcomes are not complete, and `cause` then says why.
    """

    def __new__(cls, ran, failed, methods=None, cause=None):
        self = super().__new__(cls, (ran, failed))
        self.methods, self.cause = methods, cause
        return self


def method_outcomes(lines, ran, failed, marked):
    """`(Methods, None)` when the listener's lines give exactly the XML's sets, else `(None, cause)`.

    Complete means: the listener marked the measured task, no line lost its class, and the lines,
    projected onto `(class, display name)`, give the XML's ran set and its failed set, a key failing
    when any of its lines failed (a retried test), as the XML reader reads it. Anything else -- a
    guest that strips listeners, a suite-level failure with no test line, a display name the XML
    spells differently -- falls back to XML keys rather than guessing.
    """
    if not marked:
        return None, "no listener marker"
    if lines is None:
        return None, "no listener file"
    keyed_ran, keyed_failed, own_names = set(), set(), {}
    projected_ran, projected_failed = set(), set()
    for cls, ancestors, display, name, result in lines:
        if not cls:
            return None, f"unattributable leaf {name!r}"
        key = method_key(cls, ancestors, display)
        projected = (cls, display.removesuffix("()"))
        keyed_ran.add(key)
        projected_ran.add(projected)
        if result == "FAILURE":
            keyed_failed.add(key)
            projected_failed.add(projected)
        if not ancestors:
            own_names.setdefault(key, set()).add(name)
    if projected_ran != set(ran):
        return None, (f"listener lines give {len(projected_ran)} XML keys, the XML "
                      f"{len(set(ran))}")
    if projected_failed != set(failed):
        return None, (f"listener lines give {len(projected_failed)} failed XML keys, the XML "
                      f"{len(set(failed))}")
    collapsed = {key for key, names in own_names.items() if len(names) > 1}
    return Methods(keyed_ran, keyed_failed, collapsed), None


def listener_marked(log_path, task_path):
    """Whether the build's log carries the listener's marker for the measured task."""
    wanted = sweep_data.TEST_OUTCOMES_MARKER + ":" + task_path.lstrip(":")
    try:
        with open(log_path, encoding="utf-8", errors="replace") as handle:
            return any(line.rstrip() == wanted for line in handle)
    except OSError:
        return False


def results_of(checkout, module, since, task, build="build", *, log_path, task_path):
    """(tests that ran, tests that failed), or (None, None) when no fresh results exist.

    Freshness matters: a build that stopped early leaves the previous mutation's XML in place, and
    reading it compares one mutation's selection against another's ground truth. That produced a
    confident, internally consistent, meaningless number once already.

    Returns `Outcomes`: the pair on XML keys, and the per-method sets when the build that wrote
    `log_path` left complete listener lines for `task_path` (`method_outcomes`). The task path is
    passed, not derived from `module`: that names a directory, which a settings plugin can rename
    away from the Gradle path (see `module_dir`).
    """
    # `module` is a GRADLE PATH and this is a FILE path: `library:test` is the directory
    # `library/test`, and joining it verbatim looks for a directory named "library:test" that cannot
    # exist. qualify.py's test_counts already translated it; this did not, so on a nested module
    # every read returned nothing and every row was excluded as "ground truth never reached the
    # tests" -- which reads like a build failure and is a path bug.
    # `build` is Gradle's default output directory and almost every project takes it.
    # hibernate-orm does not -- it writes into `target/` -- and reading the default there found an
    # empty directory and filed 24,798 executed tests as "a green build that ran nothing".
    parts = ([checkout] + ([module_path(module)] if module else [])
             + [build, "test-results", task, "*.xml"])
    pattern = os.path.join(*parts)
    fresh = [p for p in glob.glob(pattern) if os.path.getmtime(p) >= since]
    if not fresh:
        return Outcomes(None, None, cause="no fresh results")
    ran, failed = set(), set()
    for path in fresh:
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        for case in root.iter("testcase"):
            key = (case.get("classname"), (case.get("name") or "").removesuffix("()"))
            ran.add(key)
            if case.find("failure") is not None or case.find("error") is not None:
                failed.add(key)
    methods, cause = method_outcomes(
        sweep_data.read_test_outcomes(log_path + ".outcomes.tsv", task_path), ran, failed,
        listener_marked(log_path, task_path))
    return Outcomes(ran, failed, methods, cause)


def mutation_change_counts(checkout):
    """`(files, modules)` the applied mutation changed: tracked edits, deletions and new files
    against HEAD, git-ignored output left out. Modules as the first path segment, as the history
    replay counts them."""
    # `--no-renames`: the `renamed` shape stages its move, which git otherwise reports as one entry.
    done = subprocess.run(["git", "-C", checkout, "-c", "core.quotePath=false", "status",
                           "--porcelain", "--no-renames", "--untracked-files=all"],
                          capture_output=True, text=True, check=True)
    paths = [line[3:] for line in done.stdout.splitlines() if line.strip()]
    return len(paths), len({p.split("/", 1)[0] for p in paths if "/" in p})


def map_dirs_for_task(checkout, module, task):
    """Every map directory belonging to this module's task, MERGED OR NOT.

    `find_map` deliberately returns None for a directory with no `coverage.tsv`, because for
    SELECTION an unmerged map is no map. But for DISCARDING one it is the case that matters most: a
    captured-but-never-merged directory is exactly what iceberg and duckduckgo-android leave behind,
    and a `--recapture` that could not see it would capture straight into the old run's `raw/` tree
    and then decode two builds' records into one map.

    Same prefix rule as `find_map`, and the reason it is a separate function rather than a flag on
    that one is that their answers must differ: selection may never be handed an unmerged map, and
    discarding must never miss one.
    """
    readable = module.strip(":").replace(":", "-")
    prefix = f"{readable}-{task}-" if readable else f"{task}-"
    return sorted(d for d in glob.glob(os.path.join(checkout, ".gradle", "yoriwake", "*"))
                  if os.path.isdir(d) and os.path.basename(d).startswith(prefix))


def find_map(checkout, module, task):
    """The map belonging to the module under test.

    Taking the first directory under .gradle/yoriwake was fine while every benchmark had one module. On a
    multi-module project it picked whichever map sorted first -- a different module's -- and the
    harness then restored one module's coverage over another's before every mutation. The map
    directory is named after the Gradle task path, so the module's own map is identifiable.
    """
    # Named after the TASK, not the literal string "test": MapLocation.sanitize() builds the
    # directory from the task path, so `:mod:jvmTest` writes `mod-jvmTest-<hash>`. Looking for
    # `mod-test-` there finds nothing, the sweep forces every row, and files `0 MISSED` with no
    # savings as though it had asked a recall question. It had not: it never located the map.
    # The module path is sanitised the SAME WAY MapLocation.sanitize does it, colons to dashes.
    # A nested module is where this bites: `:library:test` with `testDebugUnitTest` is written as
    # `library-test-testDebugUnitTest-<hash>`, and looking for `library:test-testDebugUnitTest-`
    # matches nothing. glide's first sweep ran that way -- "no map for this module; selection will
    # force every run", then every row excluded as "ground truth never reached the tests" -- which
    # is the same defect as the task half of this name, found and fixed one layer too shallow.
    readable = module.strip(":").replace(":", "-")
    prefix = f"{readable}-{task}-" if readable else f"{task}-"
    candidates = [d for d in glob.glob(os.path.join(checkout, ".gradle", "yoriwake", "*"))
                  if os.path.isfile(os.path.join(d, "coverage.tsv"))]
    exact = [d for d in candidates if os.path.basename(d).startswith(prefix)]
    if exact:
        return exact[0]
    # No map for this module means capture recorded nothing here, which the caller reports rather
    # than working around: restoring some other module's map would be worse than having none.
    return None


def unfinished_map(checkout, module, task):
    """A map directory for this module whose capture never finished, or None.

    `find_map` requires `coverage.tsv`, which only a COMPLETED decode writes -- so a capture
    that ran, wrote thousands of raw records and then never merged them is indistinguishable from
    one that never ran at all. Both print "no map for this module", and the first is a defect of
    ours while the second is usually a property of the guest.

    iceberg is the case: `.gradle/yoriwake/iceberg-core-test-09480036/` holds 17,573 `.exec` files under
    `raw/`, both markers, a 665 KB `decisions.tsv` and no `coverage.tsv`, because its decode
    finalizer never ran. The verdict recorded against it said there was no map, which sent every
    reader to the wrong question.

    `MapReader` already draws this line -- its `CAPTURE_INCOMPLETE` names exactly this shape -- so
    this is the harness catching up with the plugin rather than a new rule.
    """
    readable = module.strip(":").replace(":", "-")
    prefix = f"{readable}-{task}-" if readable else f"{task}-"
    for directory in sorted(glob.glob(os.path.join(checkout, ".gradle", "yoriwake", "*"))):
        if not os.path.basename(directory).startswith(prefix):
            continue
        if os.path.isfile(os.path.join(directory, "coverage.tsv")):
            continue
        if os.path.isdir(os.path.join(directory, "raw")):
            return directory
    return None


def misses_a_row_that_skipped_nothing_cannot_have(missed, selected_ran, full_ran, decisions):
    """Drops apparent misses from a row where selection excluded nothing. Returns what survives.

    kafka-core's MINIMUM_THROTTLE_RECORDS_DELIVERY_LIMIT row forced a full run and reported
    `selected 3368 of 3367` -- the selected arm ran MORE distinct tests than the full arm -- while
    naming `DescribeClusterRequestTest::initializationError` as a test that was never selected.

    The two arms are two runs of a real build, and discovery is not identical between them: a
    container that starts in one and not the other is in one population and not the other. That is
    a fact about the guest's suite, never about selection, and where selection excluded nothing
    there is no skip for it to be evidence of.

    Deliberately `>=` rather than `==`: the arms disagreeing by a test or two is the very
    phenomenon being ruled out, so demanding an exact match would let the row through on the same
    wobble this exists to catch.

    This can only ever REMOVE a miss, so it is written to fire on one condition and no other -- a
    row that narrowed by even one test keeps every miss it recorded.

    AND THE RECORD MUST AGREE (`decisions`, the trusted record or None). The counts are of XML keys,
    and where a class's invocations share them a narrowed row can count as many keys as the full arm
    while having excluded methods: an excluded plain method failing then read as a discovery
    difference. So the counts drop misses only when a trusted record excluded no leaf.
    """
    if not missed or len(selected_ran) < len(full_ran):
        return missed
    if decisions is None or not all(verdict.partition("/")[0] == "included"
                                    for verdict in decisions["verdicts"]):
        print(f"    the selected arm ran {len(selected_ran)} distinct tests against the full arm's "
              f"{len(full_ran)}, but its record excluded tests or cannot be trusted, so its "
              f"{len(missed)} miss(es) stand", flush=True)
        return missed
    print(f"    the selected arm ran {len(selected_ran)} distinct tests against the full arm's "
          f"{len(full_ran)}, so it skipped nothing; {len(missed)} apparent miss(es) are a "
          f"discovery difference between two runs, not a skip", flush=True)
    return []


# The synthetic entries a build reports for a CLASS rather than a test: `initializationError` for a
# class that could not start, `executionError` for one whose `@AfterAll` or class extension failed.
# No test method ran under either name, so neither arm is evidence about whether selection would
# have chosen one. A Kotlin Multiplatform JVM target appends itself, `executionError[jvm]`. Exact
# names only: a test method called `executionErrorHandling` is a test.
CONTAINER_FAILURE = re.compile(r"(?:initializationError|executionError)(?:\[[^\]]*\])?")


def is_container_failure(name):
    return CONTAINER_FAILURE.fullmatch(name) is not None


def container_failures(missed, selected_ran, full_ran):
    """Splits a row's misses into genuine ones and containers that never started.

    Returns `(missed, containers)`.

    `initializationError` is JUnit's synthetic name for a class whose initialisation threw, and the
    two verification arms this harness already runs CANNOT decide it. Both re-run the class alone,
    which is a far lighter load than the full suite, so a broker that cannot bind a port under load
    starts happily in isolation and the arm reports "passes alone" -- order-dependent -- for
    something that is not a test at all. kafka-core carries three of these.

    `executionError`, a class's `@AfterAll` or extension failing, is the same kind of entry: an
    isolated re-run of the class cannot report it at all, so it answers UNKNOWN and the miss never
    clears (mockk's `executionError[jvm]`, 0.1.0). What it can hide is a change that breaks only a
    class's lifecycle in a class the selector excluded; that stays named here, never counted as
    clean.

    So it gets the standing this repository already gives a test the arms cannot settle: counted,
    named, and subtracted from the headline -- never missed, never clean. The category is a
    statement about what this harness cannot decide, not about the guest.

    SCOPED TO ROWS THAT NARROWED, with the same comparison
    `misses_a_row_that_skipped_nothing_cannot_have` uses and for the same reason. A row where
    selection excluded nothing is already disqualified, and re-deciding it here would stack two
    rules on one case -- which is how a count ends up subtracted twice.
    """
    if len(selected_ran) >= len(full_ran):
        return missed, []
    containers = [test for test in missed if is_container_failure(test[1])]
    if not containers:
        return missed, []
    return [test for test in missed if not is_container_failure(test[1])], containers


# THE DISCARD CEILING, stated here rather than discovered per project. A generator that cannot
# produce compilable source for a fifth of a project's targets is not a project with a statistic;
# it is the bug class the compile gate exists to stop. The gate below is what makes that invisible:
# every one of the six grammar defects found so far was found BECAUSE it cost rows or whole sweeps,
# and a discard costs neither. Set below the two worst observed -- kafka lost 8 of its 21 rows to
# one-line braces (38%), mockito and rxjava 5 of 16 apiece to a dropped long suffix (31%) --
# because a ceiling above those would have caught neither.
MUTATION_REJECTION_CEILING = 0.2


def compile_or_reject(checkout, project, task_args, log_path, compile_with):
    """Compiles the mutated tree, and says whether the mutation survives it.

    Returns `(code, rejected)`. A non-zero compile means the GENERATOR wrote source this guest will
    not accept. That is a fact about the grammar and never about selection, so the mutation is
    discarded and the next target is taken.

    Before this existed the row fell through to the arms, the selecting arm failed to compile too,
    and `executed_or_excluded` filed it as `build-failed` -- which `assert_recall.py` exits 1 on, so
    one bad literal cost a project its whole sweep. Six such defects landed in one campaign and two
    of them were introduced by the fix for a third.

    STALLED is NOT a rejection. A killed build is `stalled`'s finding to file, and says nothing
    about whether the mutation would have compiled.

    `compile_with` HAS NO DEFAULT on purpose. This protection is only real in the caller that ships,
    and a defaulted collaborator is exactly where that goes missing in silence -- three dead entry
    points were found in one session that way, one of them a safety mechanism whose own test handed
    it the input it was meant to protect. A caller that omits it fails to run rather than running
    ungated.
    """
    code, _ = compile_with(checkout, project, task_args, log_path)
    if code in (0, STALLED):
        return code, False
    # A NON-ZERO EXIT IS NOT ENOUGH, and treating it as one launders the guest's own broken build
    # into a generator defect. The gate exists to discard source THIS GENERATOR wrote badly; a
    # daemon that died, a missing toolchain, an OOM and a Checkstyle failure all exit non-zero and
    # none of them is the grammar's fault. Filing them as rejections would hide a broken build
    # behind a statistic and take the row out of `build-failed`, which is the one status that says
    # the tool broke its host.
    return code, compiler_rejected_the_source(log_path)


def compiler_rejected_the_source(log_path):
    """Whether the compile log carries a COMPILER diagnostic, as opposed to any other failure.

    `javac` and `kotlinc` both prefix a diagnostic with `error:`; Gradle additionally prints a
    `Compilation error` task banner. `groovyc` prints neither: its diagnostics follow `startup
    failed:`, and Gradle's banner for a Groovy compile task reads `Compilation failed`. Anything
    non-zero without one of those is the build failing for a reason the generator did not cause, and
    the caller keeps it as `build-failed`.
    """
    try:
        with open(log_path, encoding="utf-8", errors="replace") as handle:
            log = handle.read()
    except OSError:
        # A log we cannot read cannot be shown to be the generator's fault, and the safe reading of
        # "cannot tell" is the one that keeps the row rather than discarding it.
        return False
    return ("error:" in log or "Compilation error" in log or "startup failed:" in log
            or ("Compilation failed" in log and GROOVY_COMPILE_TASK.search(log) is not None))


# `compileGroovy`, `compileTestGroovy`, `compileIntegrationTestGroovy`: the tasks whose failure
# banner is a Groovy compiler's.
GROOVY_COMPILE_TASK = re.compile(r"compile\w*Groovy\b")


def task_is_missing(log_path):
    """Whether this probe's log says the task could not be located, as opposed to anything else.

    Gradle's wording, and wording is exactly what
    `a-guard-keyed-to-wording-goes-green-when-the-wording-moves` says not to rest a decision on --
    so nothing rests on it. The caller refuses either way; this only chooses which sentence it
    prints. A reworded failure loses the `test_classes_task` remedy and still stops the sweep.
    """
    try:
        with open(log_path, encoding="utf-8", errors="replace") as handle:
            log = handle.read()
    except OSError:
        # Cannot tell. The caller's general refusal is the honest one.
        return False
    return "Cannot locate tasks that match" in log or "not found in project" in log


def refused_families(rejected_mutations, findings, ceiling=MUTATION_REJECTION_CEILING):
    """`pool/v1`'s ceiling, which is per family: `{family: (rejected, offered)}` for each family
    whose rejection rate is above it.

    Per family because what a guest's gates reject differs by family: a Kotlin build with
    `allWarningsAsErrors` rejects every `void-return` and nothing else. Over the whole project those
    rejections would refuse every family's rows, or hide under the others' accepted ones. A family
    above the ceiling is refused on its own, and the other families' rows still stand.
    """
    rejected = Counter(key[1] for key in rejected_mutations or [])
    accepted = Counter(row.get("kind") for row in findings)
    return {family: (count, count + accepted[family]) for family, count in sorted(rejected.items())
            if rejection_rate_refuses(count, accepted[family], ceiling)}


def refused_whatever_follows(rejected, accepted, remaining, ceiling=MUTATION_REJECTION_CEILING):
    """Whether a `pool/v1` family with `rejected` draws discarded and `accepted` rows is above the
    ceiling even if every one of the `remaining` rows the limit still allows were its own.

    Past that point the family is refused at the end of the sweep whatever is drawn next, so a
    further draw of it buys a compile and no row anyone reads. Without the bound, a family a guest
    rejects wholesale is redrawn target by target through the whole project.
    """
    return rejection_rate_refuses(rejected, accepted + max(remaining, 0), ceiling)


def rejection_rate_refuses(rejected, accepted, ceiling=MUTATION_REJECTION_CEILING):
    """Whether a project's rejection rate is a refusal rather than a statistic.

    The gate above turns a grammar defect from something that costs rows -- which is how all six
    were found -- into a silent discard. Without a ceiling a seventh would shrink a project's
    sampled population and nothing anywhere would look wrong.

    The denominator is every mutation the generator offered this sweep, accepted or rejected:
    a rejection count alone has no scale, and 5 rejections means something different at 16 rows
    than at 200.
    """
    offered = rejected + accepted
    return bool(offered) and rejected / offered > ceiling


# THE SOURCE THAT CAN CHANGE A DECISION. Named here rather than implied, so a reader can check the
# claim this digest makes instead of trusting it: everything under these two trees is hashed, and
# nothing outside them is.
SELECTOR_SOURCE_DIRS = ("plugin/src/main", "agent/src/main")

# AND THE TWO BUILD FILES, because the old `git log -- plugin agent` check covered them and this
# one must not lose coverage the rule it replaces had. They are not decoration: `agent/build.gradle
# .kts` pins `org.ow2.asm:asm:9.8`, and an ASM version floor is exactly what stops the selector
# working on geode -- a dependency bump here changes what the agent can instrument without touching
# one line of `src/main`.
SELECTOR_SOURCE_FILES = ("plugin/build.gradle.kts", "agent/build.gradle.kts")


def selector_digest(root=None):
    """A digest of the selector's own source, for deciding whether a results file is still current.

    WHY THIS EXISTS INSTEAD OF A COMMIT. `plugin_commit` below stamps repository HEAD, so ANY commit
    touching `plugin/` or `agent/` aged out all 36 results files -- including ones that cannot
    change a decision. It happened twice in one day: a planning document carried a stray
    `git log -1` whose result was discarded, and `Selector.includes()` was rewritten to delegate to
    the rule the filter actually applies -- a method with no production caller at all. Each cost the
    whole corpus, and the second could not have changed a single row.

    The rule that matters was always narrower than the check enforcing it. What invalidates a
    figure is a change to `Selector`, to `ChangeSelection`'s reachability, or to what the capture
    records -- a statement about CONTENT. This is that statement, computed.

    HONEST ABOUT ITS OWN REACH: this is a hash of bytes, so a comment or a rename inside these
    directories still ages the corpus. It buys back the changes that live OUTSIDE them -- docs,
    tests, the benchmark harness itself, and a revert -- which is where the accidental invalidations
    actually came from. A semantic diff would be the stronger tool and a much easier thing to get
    quietly wrong.

    Sorted by path, with the path hashed alongside the content, so a file that moves changes the
    digest and two files swapping contents do not collide.

    WHAT IT COVERS: every file under `SELECTOR_SOURCE_DIRS`, dotfiles included, plus the two build
    files in `SELECTOR_SOURCE_FILES`. The build files are in because the check this replaces
    covered them and one of them pins the ASM version the agent instruments with.

    WHAT IT STILL DOES NOT COVER, and a reader should know before trusting it: the init scripts
    under `scripts/` that decide whether the plugin is applied at all, the `-Pyoriwake.*` flags
    `bench/run.py` chooses per arm, and the guest's own build. A change to any of those can
    change what a sweep measures without moving this digest. They are outside it because they are
    not the selector -- they are how a particular measurement invokes it -- and the sweep records
    its own flags per row.
    """
    base = root or SOURCE
    digest = hashlib.sha256()
    hashed = 0
    paths = []
    for directory in SELECTOR_SOURCE_DIRS:
        # `glob` with `*` skips dotfiles, so walk instead: a hidden file under these trees is
        # selector source like any other and must not sit outside the digest.
        for walked, _dirs, files in os.walk(os.path.join(base, directory)):
            paths.extend(os.path.join(walked, name) for name in files)
    paths.extend(os.path.join(base, name) for name in SELECTOR_SOURCE_FILES)
    for path in sorted(paths):
        if not os.path.isfile(path):
            continue
        digest.update(os.path.relpath(path, base).encode("utf-8"))
        with open(path, "rb") as handle:
            digest.update(handle.read())
        hashed += 1
    # REFUSED, NOT HASHED AS NOTHING. With no inputs this returns the digest of the empty string --
    # a perfectly stable value that every results file would agree with, so a checkout missing its
    # selector source would stamp every row "current" against a selector that is not there. That
    # fails open on both sides of the comparison, which is the one direction this check may not
    # fail in.
    if not hashed:
        raise SystemExit(
            f"no selector source found under {SELECTOR_SOURCE_DIRS} or {SELECTOR_SOURCE_FILES} "
            f"beneath {base}; refusing to stamp a results file with the digest of an empty tree")
    return digest.hexdigest()[:12]


# The generators, the shape sets and the target ordering: everything that decides WHICH edit a row
# makes. `recall_check.py` is in because the `body` and `aliased` shapes are its `mutate`.
GRAMMAR_SOURCE_FILES = ("bench/mutations.py", "bench/target_ordering.py",
                        "bench/recall_check.py")
# What decides which commits a revert row reverts, and how: eligibility, strata and draw order, and
# the census labels the strata come from.
REVERT_GRAMMAR_FILES = ("bench/revert.py", "bench/census.py")

# A named set's own generators, digested with that set only, so adding a set leaves the default
# set's digest, and every default results file's resume, where they were.
SET_GRAMMAR_FILES = {POOL_SHAPES: ("bench/pool_mutations.py", "bench/family_order.py",
                                   # Absent until a replay adopts the learned order for a family.
                                   "bench/order/learned-v1.json"),
                     # With the files its families reuse: `throw-entry`'s grammar, the revert
                     # set's patches, and the measured module the census names.
                     FORCING_SET: ("bench/forcing_mutations.py", "bench/pool_mutations.py",
                                   "bench/revert.py", "bench/census.py")}


def shape_folder(shapes):
    """The directory name a named shape set's files live under."""
    return shapes.replace("/", "-")


def results_location(results_root, logs_root, name, shapes, isolated=False, control=False):
    """`(results file, scratch directory)` for a project under a shape set.

    The default set keeps `<results>/<project>.json`, so every earlier comparison still reads the
    same file. A named set writes `<results>/<set>/<project>.json` and its own scratch, so its map
    backups are its own too: one path for every set once let a named run overwrite the published
    default file, and its rows were restored by hand.
    """
    stem = name + (".isolated" if isolated else "")
    if shapes == DEFAULT_SHAPES:
        out_path = os.path.join(results_root, stem + ".json")
        scratch = os.path.join(logs_root, stem + (".control" if control else ""))
    else:
        folder = shape_folder(shapes)
        out_path = os.path.join(results_root, folder, stem + ".json")
        scratch = os.path.join(logs_root, stem + (".control" if control else "") + "." + folder)
    return out_path, scratch


def grammar_digest(root=None, shapes=DEFAULT_SHAPES):
    """A digest of the mutation grammar's source, so a results file says which rules drew its rows.

    WHY `target_shapes` IS NOT ENOUGH. It names the shape SET a sweep drew from, and every published
    figure says `default`. A change to a generator's rules INSIDE that set -- the compile gate's
    fixes, the escaped-quote constant, the one-line braces -- changes which edits the sampled
    population contains while `target_shapes` stays `default`, so two files drawn under different
    rules read as the same population. This is the stamp that tells them apart.

    DISTINCT FROM `selector_digest` ON PURPOSE. That one decides whether a figure describes the
    selector here now; this one says what population a figure was drawn from. A grammar change does
    not make a row wrong about the selector, so it is reported, not treated as staleness.

    HONEST ABOUT ITS OWN REACH: a hash of whole files, so a comment in one of them moves it, and
    `recall_check.py`'s runner code moves it too. The over-report is the safe direction. What it
    does NOT cover is in this file: the compile gate, which rejects rather than generates -- its
    effect on a sample is recorded per file in `mutations_rejected` and `rejected_targets`. The
    composition of shapes (`renamed` as a move, then a body mutation) is `mutations.apply`, so it is
    covered.

    PER SET: a named set's own files (`SET_GRAMMAR_FILES`) are hashed with the shared ones for that
    set only.
    """
    base = root or ROOT
    digest = hashlib.sha256()
    hashed = 0
    files = (REVERT_GRAMMAR_FILES if shapes == REVERT_SET
             else GRAMMAR_SOURCE_FILES + SET_GRAMMAR_FILES.get(shapes, ()))
    for name in files:
        path = os.path.join(base, name)
        if not os.path.isfile(path):
            continue
        digest.update(name.encode("utf-8"))
        with open(path, "rb") as handle:
            digest.update(handle.read())
        hashed += 1
    # Refused for the reason `selector_digest` refuses: the digest of nothing is a stable value
    # every file would agree with.
    if not hashed:
        raise SystemExit(f"no grammar source found at {files} beneath {base}; "
                         f"refusing to stamp a results file with the digest of an empty tree")
    return digest.hexdigest()[:12]


def grammar_changed(stored, current):
    """Whether rows in a stored results file were drawn under grammar rules other than `current`.

    NULL IS NOT A MISMATCH, the `flags` discipline: every results file on disk predates this field,
    and reading null as a change would age all of them to introduce a stamp. A resumed null file
    stays unstamped instead -- see the resume in `main`.
    """
    recorded = stored.get("grammar_digest")
    return recorded is not None and recorded != current


def resumed_grammar(stored, findings, current):
    """The grammar stamp a results file carries after resuming `findings` from `stored`.

    `current` when there is nothing to vouch for, or the stored rows were drawn under it. None when
    rows written before the stamp existed are carried forward: stamping them now would claim rules
    nobody recorded.
    """
    if findings and stored.get("grammar_digest") is None:
        return None
    return current


def fixture_digest(project, root=None):
    """A digest of an in-repo sample's tracked files, or None for a project that is not one.

    A fixture is measured at `commit = "local"`, so no stamp in a results file moves when its
    sources or tests do: a test added to the sample changes the suite every row ran and the map the
    targets are ordered by, and a resume would carry rows of both trees under one header. This is
    the stamp that tells them apart. Sorted by path, with the path hashed beside the content, as
    `selector_digest` does.
    """
    if not project.get("copy_of"):
        return None
    base = root or SOURCE
    listed = subprocess.run(["git", "-C", base, "ls-files", "-z", "--", project["copy_of"]],
                            capture_output=True, check=True).stdout.decode("utf-8")
    digest = hashlib.sha256()
    hashed = 0
    for name in sorted(path for path in listed.split("\0") if path):
        path = os.path.join(base, name)
        if not os.path.isfile(path):
            continue
        digest.update(name.encode("utf-8"))
        with open(path, "rb") as handle:
            digest.update(handle.read())
        hashed += 1
    if not hashed:
        raise SystemExit(f"no tracked file under {project['copy_of']} beneath {base}; refusing to "
                         f"stamp a results file with the digest of an empty tree")
    return digest.hexdigest()[:12]


def fixture_changed(stored, current):
    """Whether rows in a stored results file were measured on a fixture tree other than `current`.

    NULL IS A MISMATCH for a fixture, the `staged_sources` discipline rather than `grammar`'s: a
    fixture's file without the stamp cannot say which tree it ran on, and a fixture costs seconds
    to sweep again. A project that is not a fixture has no tree to change.
    """
    return current is not None and stored.get("fixture_digest") != current


def plugin_commit():
    """THE YORIWAKE CHECKOUT'S HEAD, not the last commit that touched the selector.

    So a docs-only commit in the checkout moves the stamp; anything comparing stamps to decide
    freshness reads `selector_digest` instead. Refused, not stamped, when the checkout is dirty: the
    stamp must name a public commit that holds what was built. After a publish it is the commit the
    jar was built from, so an edit to the checkout mid-sweep cannot move it.
    """
    return PUBLISHED_COMMIT or yoriwake_checkout.commit(short=True)





def executed_or_excluded(log_path, module, label, findings, phase, task):
    """True when the test task really ran; files the point as excluded when it did not.

    A task served FROM-CACHE leaves a full set of test-results XML behind with fresh timestamps, so
    results_of() reads it happily and the harness counts a restored artifact as "what selection
    ran". That is how a replay of three real detekt commits reported 2256/2256, 2259/2259 and
    46/2259 without executing a single test.

    It matters most for history replay, where the commits are the project's own and its cache has
    already built every one of them. And the exclusion is itself the finding: where the build cache
    hits, no test selection of any kind is relevant, because nothing was going to run anyway.
    """
    if test_task_ran(log_path, module, task):
        return True
    # A crashed build also runs no tests, and filing it as a cache hit is the same defect this
    # harness exists to catch: two different things sharing one representation, reported as the
    # benign one. Measured on kestra, where a selecting run died with "BuildScopeServices has been
    # closed" and the sweep reported "asked nothing" -- a green run hiding the tool breaking a host
    # build, which is the worst outcome available and was invisible.
    #
    # A build that FAILED because tests failed is not this: that build ran the task, so it returned
    # above. Reaching here with a failure means the run never got as far as the tests.
    if build_failed(log_path):
        findings.append({"target": label, "status": "build-failed", "phase": phase})
        print(f"    BUILD FAILED before the {phase} run reached the tests; see {log_path}",
              flush=True)
        return False
    findings.append({"target": label, "status": "cache-hit", "phase": phase})
    print(f"    excluded: the {phase} run was served from cache or skipped, so nothing executed",
          flush=True)
    return False


def build_failed(log_path):
    """Whether Gradle reported a build failure, as opposed to failing tests."""
    try:
        with open(log_path, encoding="utf-8", errors="replace") as handle:
            return "FAILURE: Build failed" in handle.read()
    except OSError:
        return False


# The plugin's FOUR CAPTURE-decision openers, which share the reason line's shape and are not force
# reasons. Kept as prefixes rather than a regex because they are quoted verbatim from
# `capture/CaptureDecision.kt` and `test_run.py` asserts they still appear there -- see
# `case_capture_openers_still_match_the_plugin`. If the plugin rewords one, that test fails rather
# than this scrape silently returning the capture message as if it were a force reason.
#
# THE THIRD ONE WAS MISSING AND IT IS THE MOST COMMON. `decideCapture` has always had three arms;
# this tuple listed two, so the narrowing arm -- "narrowing, so nothing is instrumented..." -- fell
# through and was published as `full_run_reason` on **119 of the 254 rows** of one corpus sweep,
# every one of which had NARROWED. A field named for why a run executed everything spent half the
# corpus explaining why a run had not. The guard that was supposed to prevent exactly this asserted
# that these strings still appear in the plugin, which stayed true while the plugin grew a third one
# the tuple did not have; it now asserts the tuple covers every arm `decideCapture` can return.
CAPTURE_REASON_OPENERS = (
    "running everything, so this run also captures",
    "running everything, but the map already covers this commit",
    # A full run over a map recorded in isolation captures nothing and leaves that map alone.
    "running everything, but the map holds records made",
    "narrowing, so nothing is instrumented and the map is left alone",
)

# The OTHER lines the run path prints through `[yoriwake] <task>: `, which are not force reasons either.
#
# `full_run_reason` scrapes a general-purpose prefix, so it catches whatever the plugin last said
# about the task -- and the plugin says several things. These are substrings rather than prefixes
# because one of them opens with a count.
#
# THIS LIST IS FOR READING OLD ROWS ONLY. A row measured after the selector began recording its own
# outcome is classified by `decision_outcome`, and no amount of prose-matching is a substitute:
# blacklisting was tried against the whole corpus first and still scored glide's fourteen narrowed
# rows as full runs, because its rows carry the experimental-selection warning below.
NON_FORCE_REASON_MARKERS = (
    "class(es) inlined the changed ",
)
# Lines the plugin no longer prints but stored rows still carry: glide's, from while the Gradle-
# filter class selection existed (removed in 0.2).
RETIRED_NON_FORCE_REASON_MARKERS = (
    "class-granular selection is EXPERIMENTAL",
)


def is_force_reason(reason):
    """Whether a scraped `full_run_reason` is a refusal, rather than some other thing the plugin said.

    Only meaningful for rows with no `decision_outcome`. See NON_FORCE_REASON_MARKERS.
    """
    if not reason:
        return False
    if reason.startswith(CAPTURE_REASON_OPENERS):
        return False
    return not any(marker in reason
                   for marker in NON_FORCE_REASON_MARKERS + RETIRED_NON_FORCE_REASON_MARKERS)


def full_run_reason(log_path, task_path=None):
    """Why selection declined to narrow, straight from the run's own diagnostic.

    Recorded in the results rather than left in a log, because "it ran everything" is not a finding
    until you know whether that was a changed resource, an unseen class, or a defect.

    **THIS RETURNED None FOR EVERY ROW OF EVERY SWEEP EVER RUN.** It matched
    `"[yoriwake] running everything:"`, a string the plugin has never printed; the real line is
    `[yoriwake] <task path>: <reason>`. So the one field that explains why a project never narrows was
    empty in every published result file, and micrometer's fourteen consecutive full runs had to be
    diagnosed by reading a log by hand. Found by code review.

    This is exactly the class of bug "a contract nobody wrote down and no test pins", and the
    usual mitigation is to read `explain.json`. That is not
    available here: only `yoriwakeExplain` writes it, and a sweep runs `test`. So this stays a scrape,
    and what changed is that the shape it depends on is now pinned by a test against the plugin's
    own source rather than assumed.

    `task_path` narrows the match to one task's lines when the caller knows it, which matters in a
    multi-module build where several tasks report. Without it, the first reason found wins.
    """
    marker = f"[yoriwake] {task_path}: " if task_path else "[yoriwake] "
    try:
        with open(log_path, encoding="utf-8", errors="replace") as handle:
            for line in handle:
                head, sep, reason = line.partition(marker)
                if not sep or head.strip():
                    continue
                if not task_path:
                    # Without a task path the marker is only "[yoriwake] ", so the task's own name is
                    # still on the front of what partition() returned. Drop it, and skip any line
                    # that is not of the `<task path>: <reason>` shape at all.
                    task, colon, reason = reason.partition(": ")
                    if not colon or " " in task:
                        continue
                reason = reason.strip()
                if reason.startswith(CAPTURE_REASON_OPENERS):
                    continue
                return reason[:300]
    except OSError:
        pass
    return None


# Written beside the map by the agent, on every run. Must match io.github.zeuspizza.yoriwake.agent.AgentContract.
DECISIONS_FILE = "decisions.tsv"
DECISION_NOTE_PREFIX = "#!"

# Must match io.github.zeuspizza.yoriwake.agent.AgentContract's own constants; test_module_contract.py pins them.
DECISION_ROWS_NOTE = "rows"
DECISION_VERSION_NOTE = "record-version"
# Which of the test JVM's decision records survived, as `<n> of <m>`. The JUnit Platform makes
# several discovery requests per task and each gets its own record; they race for one file name and
# the last writer wins. A row whose `m` is above 1 describes ONE request rather than the whole task,
# which is why `decision_included` can legitimately sit below the number of tests the arm ran.
DECISION_WRITER_NOTE = "writer"
# Each test JVM's own copy of its answer, `decisions.tsv.<pid>-<writer>.part`. Must match
# io.github.zeuspizza.yoriwake.agent.AgentContract.DECISIONS_PART_SUFFIX. `decisions.tsv` is
# last-writer-wins across every JVM a task starts, and duckduckgo-android's `forkEvery = 100` left
# 371 rows of a 5,041-test arm in it on the one row that recorded a miss; the parts are what make
# the whole task's answer readable.
DECISION_PART_SUFFIX = ".part"
# Every rule behind each row, `#+test\trule,rule`, one line per row after the rows, written
# only when the `rules` note says they were computed. Must match AgentContract's RULES_LINE_PREFIX,
# RULES_NOTE and FORCING_KINDS_NOTE; test_module_contract.py pins them. A `#` line, so every reader
# of the rows alone -- this one before the rule lines existed included -- never sees one.
DECISION_RULES_PREFIX = "#+"
DECISION_RULES_NOTE = "rules"
DECISION_FORCING_KINDS_NOTE = "forcing-kinds"
# The rules that share a JVM with the change rather than reach it by coverage, and the class
# rounding, which is derived from other selections. Every other rule is part of the selection
# coverage alone makes -- the one beneath a refusal, and the one a looser mode would start from.
SHARES_JVM_RULE_PREFIX = "shares-jvm-"
CLASS_GRANULARITY_RULE = "class-granularity"
# The `rules` note values under which rule lines are written, AgentContract's RULES_COMPLETE and
# RULES_FROM_REFUSED_INPUTS. Any other value says why there are none.
RULES_COMPUTED = ("complete", "from-refused-inputs")
# A rule line naming no rule, and one whose rules could not be computed for that row.
RULE_NONE = "none"
RULE_UNKNOWN = "unknown"


def decision_parts(map_dir):
    """Every per-writer decision part beside the map, sorted by name. Staging files are not parts."""
    prefix = DECISIONS_FILE + "."
    try:
        names = os.listdir(map_dir)
    except OSError:
        return []
    return sorted(os.path.join(map_dir, name) for name in names
                  if name.startswith(prefix) and name.endswith(DECISION_PART_SUFFIX))


def undecided_row(label, phase, decisions, selected_ran, selected_code, selected_log):
    """An `invalid` row for a selecting arm that decided nothing, or None when it decided.

    No decision record and no test run means no test JVM ever reported: the test task failed before
    one started (on spring-boot, a command line the OS refused). Such a row is not a mutation that
    broke nothing -- nothing was asked -- so it is set apart rather than filed `no-effect`, and it
    is the plugin breaking the build it is a guest in. A narrowed run that selected no test still
    writes a record, so it never lands here.
    """
    if decisions is not None or selected_ran:
        return None
    reason = (f"the selecting arm wrote no decision record and ran no test (exit {selected_code}); "
              f"its test JVM never reported. Log: {selected_log}")
    print(f"    INVALID: {reason}", flush=True)
    return {"target": label, "status": "invalid", "phase": phase, "invalid_reason": reason}


def read_decision_record(map_dir, since):
    """The selector's OWN account of the run that just finished, or None when it did not write one.

    This is the answer to "why did this row decide what it decided", and it costs nothing: the agent
    already writes `decisions.tsv` beside the map on every run, and the harness has always let the
    next row overwrite it. What comes back is the whole-run notes (`outcome`, `full-run-kind`,
    `full-run-reason`), the ids the selector INCLUDED, and a tally of verdict/reason pairs.

    Read straight after the arm that wrote it. The daemon-side `yoriwakeExplain` task computes the same
    decision far more cheaply to look at, but it is a DIFFERENT code path from the one a sweep
    measures -- and on spring-boot it costs four minutes a row to ask, against a row that already
    knew the answer and threw it away.

    **Staleness is reported as absence, never as the answer.** A file older than the arm that should
    have written it belongs to an earlier run, and this harness has already shipped the other
    version of that mistake once: `first_contact` read an `audit.json` from a previous session and
    published it as that run's result.
    """
    if not map_dir:
        return None
    # THE PARTS WHEN THERE ARE ANY, because they are the whole task and `decisions.tsv` is whichever
    # JVM wrote last. A jar older than the parts writes none, and then the shared file is all there
    # is -- read exactly as before.
    parts = [p for p in decision_parts(map_dir) if _fresh(p, since)]
    if not parts:
        path = os.path.join(map_dir, DECISIONS_FILE)
        return _read_decision_file(path) if _fresh(path, since) else None
    records = [_read_decision_file(part) for part in parts]
    # ONE TORN PART MAKES THE WHOLE RECORD ABSENT. A merge that silently dropped it would be the
    # plausible short answer the completeness proof exists to refuse, one level up.
    if any(record is None for record in records):
        return None
    return merge_decision_records(records)


def baseline_key(project):
    """Everything that decides which tests fail unmutated: the pinned commit, the staged tree and the
    flags. Keys the already-failing cache and the suite profile alike, so the two cannot drift."""
    return {"commit": project["commit"], "staged_sources": sorted(sources_to_drop(project)),
            "flags": list(project.get("flags") or [])}


def read_kept_decisions(path):
    """A decision record kept beside a row's log: a `.parts` directory, merged, or one `.tsv`. None
    when it is unreadable, and when any part is: one torn part makes the whole record absent."""
    if os.path.isdir(path):
        records = [_read_decision_file(p) for p in sorted(
            glob.glob(os.path.join(path, "*" + DECISION_PART_SUFFIX)))]
        if not records or any(record is None for record in records):
            return None
        return merge_decision_records(records)
    return _read_decision_file(path)


def read_baseline_cache(path, key, repeats):
    """The cached already-failing set for exactly this tree, or None. Never a set from fewer repeats.

    Per method when the cache was written so (`result_keys`); a cache written before per-method keys
    is still reused, on XML keys -- see `subtract_baseline` for what that costs a row.
    """
    try:
        with open(path, encoding="utf-8") as handle:
            cached = json.load(handle)
    except (OSError, ValueError):
        return None
    if cached.get("key") != key or not isinstance(cached.get("repeats"), int) \
            or cached["repeats"] < repeats:
        return None
    xml = {tuple(pair) for pair in cached.get("already_failing") or []}
    if cached.get("result_keys") != PER_METHOD:
        return Outcomes(None, xml, cause="the baseline cache holds XML keys only")
    return Outcomes(None, xml, Methods(None, {tuple(pair) for pair in
                                              cached.get("already_failing_methods") or []}))


def write_baseline_cache(path, key, repeats, already_failing):
    methods = getattr(already_failing, "methods", None)
    with open(path, "w", encoding="utf-8") as handle:
        json.dump({"key": key, "repeats": repeats,
                   "already_failing": sorted(list(pair) for pair in already_failing[1]),
                   "result_keys": PER_METHOD if methods is not None else XML,
                   "already_failing_methods": (sorted(list(pair) for pair in methods.failed)
                                               if methods is not None else None)}, handle)


def snapshot_for_replay(map_dir, scratch):
    """The map as a selecting arm is about to read it, kept once per distinct content. Returns its path.

    For bench/replay_decisions.py, which re-decides a row offline and needs the exact map the
    arm read -- and a forced arm also CAPTURES, so the map after it is not the map it selected
    against. Content-addressed, so the rows of a sweep that share a map share one copy. Top-level
    files only: `MapReader` reads nothing under `raw/`, and the decision files are kept separately.
    """
    if not map_dir or not os.path.isdir(map_dir):
        return None
    names = sorted(n for n in os.listdir(map_dir)
                   if os.path.isfile(os.path.join(map_dir, n)) and not n.startswith(DECISIONS_FILE))
    digest = hashlib.sha256()
    for name in names:
        digest.update(name.encode("utf-8"))
        with open(os.path.join(map_dir, name), "rb") as handle:
            digest.update(handle.read())
    target = os.path.join(scratch, "replay-maps", digest.hexdigest()[:16])
    if not os.path.isdir(target):
        staging = target + ".writing"
        shutil.rmtree(staging, ignore_errors=True)
        os.makedirs(staging)
        for name in names:
            shutil.copyfile(os.path.join(map_dir, name), os.path.join(staging, name))
        os.replace(staging, target)
    return target


def replay_block(replay_map, decisions_stem):
    """What a row needs to be re-decided offline, or None when either half is missing."""
    parts = decisions_stem + ".parts"
    kept = parts if os.path.isdir(parts) else decisions_stem + ".tsv"
    if not replay_map or not os.path.exists(kept):
        return None
    return {"map": os.path.relpath(replay_map, ROOT), "decisions": os.path.relpath(kept, ROOT),
            "digest": measured_selector_digest()}


def keep_decisions(map_dir, stem):
    """Copies the record beside the arm's log: `<stem>.tsv`, and every part under `<stem>.parts/`.

    The parts are the whole task's answer, and a later diagnosis reads them from here once the next
    arm has overwritten the map directory's copies.
    """
    shared = os.path.join(map_dir, DECISIONS_FILE)
    if os.path.isfile(shared):
        shutil.copyfile(shared, stem + ".tsv")
    parts = decision_parts(map_dir)
    # EMPTIED FIRST. The stem is the row's label, so a second run of the same row -- a later sweep,
    # a spot check -- writes here again, and a part left from the first would merge two runs into
    # one answer. `.tsv` is overwritten; a directory has to be cleared.
    shutil.rmtree(stem + ".parts", ignore_errors=True)
    if parts:
        os.makedirs(stem + ".parts")
        for part in parts:
            shutil.copyfile(part, os.path.join(stem + ".parts", os.path.basename(part)))


def _fresh(path, since):
    try:
        return os.path.getmtime(path) >= since
    except OSError:
        return False


def merge_decision_records(records):
    """One task's answer from its writers' parts: the union of their rows, and notes they agree on.

    A test decided by two writers counts once. The whole-run notes -- `outcome` above all -- come
    from inputs every JVM shares, so they should agree; where they do not, the note is dropped
    rather than one writer's answer being published as the task's, and `writer-outcomes` says what
    each part claimed. Disagreement between forks is itself a finding.
    """
    verdict_of, order = {}, []
    for record in records:
        for test, verdict in record["decided"]:
            if test not in verdict_of:
                order.append(test)
            verdict_of[test] = verdict
    # The rules are one task's only when every part that decided anything carries them; a merge of
    # some parts' rules would count fewer tests under each rule than the task ran.
    rules = None
    if all(record.get("rules") is not None for record in records if record["decided"]):
        rules = {}
        for record in records:
            rules.update(record.get("rules") or {})
    notes = {}
    for key in {key for record in records for key in record["notes"]}:
        values = {record["notes"].get(key) for record in records}
        if len(values) == 1:
            notes[key] = values.pop()
    outcomes = sorted({str(record["notes"].get("outcome")) for record in records})
    if len(outcomes) > 1:
        notes["writer-outcomes"] = ",".join(outcomes)
    notes[DECISION_WRITER_NOTE] = f"{len(records)} parts"
    included = [test for test in order if verdict_of[test].partition("/")[0] == "included"]
    return {"notes": notes, "included": included,
            "verdicts": Counter(verdict_of[test] for test in order),
            "decided": [(test, verdict_of[test]) for test in order],
            "rules": rules}


def _read_decision_file(path):
    """One decision file, or None when it is missing, unreadable or fails its completeness proof."""
    notes, included, verdicts, decided, rules, rule_lines = {}, [], Counter(), [], {}, 0
    try:
        with open(path, encoding="utf-8", errors="replace") as handle:
            for line in handle:
                line = line.rstrip("\n")
                if line.startswith(DECISION_NOTE_PREFIX):
                    key, _, value = line[len(DECISION_NOTE_PREFIX):].partition("\t")
                    notes[unescape_decision(key)] = unescape_decision(value)
                    continue
                if line.startswith(DECISION_RULES_PREFIX):
                    test, _, tokens = line[len(DECISION_RULES_PREFIX):].partition("\t")
                    rules[unescape_decision(test)] = tuple(tokens.split(","))
                    rule_lines += 1
                    continue
                if not line or line.startswith("#"):
                    continue
                test, _, rest = line.partition("\t")
                verdict, _, reason = rest.partition("\t")
                verdicts[f"{verdict}/{reason}"] += 1
                decided.append((unescape_decision(test), f"{verdict}/{reason}"))
                if verdict == "included":
                    included.append(unescape_decision(test))
    except OSError:
        return None

    # THE COMPLETENESS PROOF, and it reads as ABSENT rather than as a short answer. The writer runs
    # in a shutdown hook and states how many rows it meant to write, before writing them; a file
    # holding fewer lost its tail, and it would otherwise parse cleanly and describe a run that
    # decided about fewer tests than it did. No record is a state this harness already handles
    # everywhere; a plausible short one is not.
    declared = notes.get(DECISION_ROWS_NOTE)
    found = sum(verdicts.values())
    # A TEAR INSIDE THE NOTES BLOCK, which the count alone cannot see. The writer emits the version
    # note first and the row count second, so a record carrying the version and not the count was
    # cut between them -- and every note after that point is missing too, including the outcome the
    # whole row is classified on. A record from a jar older than these notes carries NEITHER, which
    # is version skew and reads normally.
    if notes.get(DECISION_VERSION_NOTE) is not None and declared is None:
        print(f"    decision record at {path} carries a version and no row count, so it was cut "
              f"between them; reading it as absent")
        return None
    if declared is not None and declared.isdigit() and int(declared) != found:
        print(f"    decision record at {path} declares {declared} rows and holds {found}; "
              f"reading it as absent")
        return None
    # The rule lines follow the rows and carry no count of their own: one per row, or the block
    # lost its tail. A torn block costs the rules, never the rows before it. None, not empty, is
    # the spelling of "not recorded" -- a jar older than the rule lines, or a `rules` note saying
    # they could not be computed -- so it can never read as "no rule ran anything".
    if notes.get(DECISION_RULES_NOTE) not in RULES_COMPUTED:
        rules = None
    elif rule_lines != found:
        print(f"    decision record at {path} holds {rule_lines} rule lines for {found} rows; "
              f"reading its rules as absent")
        rules = None
    return {"notes": notes, "included": included, "verdicts": verdicts, "decided": decided,
            "rules": rules}


def unescape_decision(value):
    """The inverse of io.github.zeuspizza.yoriwake.agent.contract.Tsv.escape, which nothing used to invert.

    Harmless while every value written was a token matching `[a-z-]`. A refusal reason is prose, and
    prose from a Windows build carries backslashes, so the writer's escaping became visible to this
    reader for the first time. `test_module_contract.py` pins the pair.
    """
    out, index = [], 0
    while index < len(value):
        char = value[index]
        if char != "\\" or index + 1 >= len(value):
            out.append(char)
            index += 1
            continue
        following = value[index + 1]
        out.append({"t": "\t", "r": "\r", "n": "\n", "\\": "\\"}.get(following, "\\" + following))
        index += 2
    return "".join(out)


def selected_digest(test_ids):
    """A stable fingerprint of a selected set, so two rows can be compared without storing either.

    Normalised the SAME WAY `unmatched` compares, because a suite whose display names carry an
    identity hash would otherwise produce a different digest on every run of an identical tree --
    which is the defect that published a missed failure on armeria, arriving here as a digest that
    could never match itself.

    None for an empty set: a run that selected nothing and a run whose record could not be read must
    not share a spelling.
    """
    if not test_ids:
        return None
    normalised = sorted(UNSTABLE_ID.sub("@?", test) for test in test_ids)
    return hashlib.sha256("\n".join(normalised).encode("utf-8")).hexdigest()[:16]


def map_fingerprint(map_dir):
    """(capture commit, record count, attributable share) for the map a row selected against.

    None where it cannot say, everywhere. Empty is not unknown: a map with zero records is a real and
    different thing from a map nobody could read.

    NOT THE MTIME. A cache restore rewrites those, which is why `Audit.provenance` already refuses to
    synthesise a map's age from one. The capture-commit stamp plus the record count is what
    identifies a map, and it is what tells a row measured before a re-capture from one measured
    after.

    An absent map, an absent stamp and an unreadable file all record None. Empty is not unknown:
    a map with zero records is a real and different thing from a map nobody could read.
    """
    if not map_dir:
        return None, None, None
    try:
        with open(os.path.join(map_dir, "capture-commit"), encoding="utf-8") as handle:
            commit = handle.read().strip() or None
    except OSError:
        commit = None
    records, attributable = None, None
    try:
        with open(os.path.join(map_dir, "coverage.tsv"), encoding="utf-8",
                  errors="replace") as handle:
            records = attributed = 0
            for line in handle:
                if not line.strip() or line.startswith("#"):
                    continue
                records += 1
                # A record that names no class attributes to nothing: it says a test ran and
                # nothing about what it touched, so it can license no skip. glide's map held 1,522
                # records and 1,220 of them decoded to exactly this -- and the capture commit and
                # the record count were identical before and after, which is why neither of those
                # two numbers can answer "is this map healthy".
                if len(line.split("\t")) > 2 and line.split("\t")[2].strip():
                    attributed += 1
        attributable = round(attributed / records, 4) if records else None
    except OSError:
        records = None
    return commit, records, attributable


def parse_etime(value):
    """`ps -o etime=` as seconds: `mm:ss`, `hh:mm:ss` or `dd-hh:mm:ss`. None when it is none of them.

    Parsed rather than asked for in seconds, because **`etimes` is a Linux extension and macOS `ps`
    rejects it** -- "ps: etimes: keyword not found". The first version of this asked for `etimes`,
    and on the machine that takes every measurement in this repository it therefore recorded None on
    every row while looking exactly like a machine that had no daemon running. `etime` is the
    spelling both accept.
    """
    text = (value or "").strip()
    if not text:
        return None
    days, _, clock = text.rpartition("-")
    parts = clock.split(":")
    if not 2 <= len(parts) <= 3:
        return None
    try:
        seconds = 0
        for part in parts:
            seconds = seconds * 60 + int(part)
        if days:
            seconds += int(days) * 86400
    except ValueError:
        return None
    return seconds


def daemon_uptime_seconds():
    """How long the longest-lived Gradle daemon has been up, or None where that cannot be read.

    Daemon state moved a toll figure by 3x on one project and one task, and not one
    second published by this corpus records it. A short uptime means the daemon was
    started for this run; a long one means it carried work in from before.

    Where `pgrep` or `ps` is absent, including Windows, this answers None rather than 0. A machine
    that cannot be asked is not a machine whose daemon was freshly started.
    """
    try:
        found = subprocess.run(["pgrep", "-f", "GradleDaemon"],
                               capture_output=True, text=True, timeout=15)
        pids = [p for p in found.stdout.split() if p.isdigit()]
        if not pids:
            return None
        ages = subprocess.run(["ps", "-o", "etime=", "-p", ",".join(pids)],
                              capture_output=True, text=True, timeout=15)
        values = [parse_etime(line) for line in ages.stdout.splitlines()]
        values = [v for v in values if v is not None]
        return max(values) if values else None
    except (OSError, ValueError, subprocess.SubprocessError):
        return None


def ci_stamp():
    """Which runner and job measured a row on CI, or None off it. A sharded sweep merges rows from
    several jobs into one file, so each row says which machine its two arms shared."""
    if os.environ.get("GITHUB_ACTIONS") != "true":
        return None
    return {"runner": os.environ.get("RUNNER_NAME"), "run": os.environ.get("GITHUB_RUN_ID"),
            "attempt": os.environ.get("GITHUB_RUN_ATTEMPT"), "job": os.environ.get("GITHUB_JOB")}


def machine_conditions():
    """The conditions one timed arm was taken under, sampled AT THAT ARM.

    Per arm and not per row, deliberately: the two arms of a row run minutes apart, and on this
    corpus a row's arms are 350 and 420 seconds long. A single sample for the pair would describe
    neither.

    A machine with no load average records None. `os.getloadavg` raises on Windows, and a zero there
    would read as an idle machine rather than as an unasked question.
    """
    try:
        load = round(os.getloadavg()[0], 2)
    except (OSError, AttributeError):
        load = None
    return {"load_average": load, "daemon_uptime_seconds": daemon_uptime_seconds()}


def decision_fields(decisions, selected_distinct):
    """The row's account of WHY it decided as it did, in tokens rather than in English.

    Every field is None when the selector wrote no record this arm could claim, which is not the same
    as a run that narrowed: `decision_outcome` distinguishes them, and a reader that treats a null
    kind as "it narrowed" is reading a run nobody described.

    `decision_included` is the count the SELECTOR says it let through; `selected_distinct` is what
    Gradle's result XML says actually ran. They are two different measurements of one arm and a
    disagreement is worth seeing -- a record from an arm that died, a build that forked twice.

    `decision_matches_arm` compares them only on a run that NARROWED, and is None on one that did
    not. They are two units: the record counts unique ids, the XML `(class, display name)` keys, so
    they differ wherever a parameterized method expands into several keys, a method-less display
    name collapses several methods onto one, or a refused row records its engine, class and runner
    containers. On spring-boot a forced row's difference is 25 of 3,707, stable, and says nothing
    about whether the record describes this arm. A flag that reads False for a benign reason on every
    forced row of a real project is a flag nobody will look at twice -- which is the failure this
    record exists to stop, arriving as a field that cries wolf rather than as one nobody writes.

    `decision_included_leaves` is `decision_included` without the containers: the included ids that
    prefix no other recorded id. `decision_vs_arm_gap` reads it.
    """
    if not decisions:
        return {
            "decision_outcome": None,
            "full_run_kind": None,
            "refusal_kind": None,
            "decision_record_version": None,
            "decision_reason": None,
            "selected_digest": None,
            "selected_verdicts": None,
            "decision_included": None,
            "decision_included_leaves": None,
            "decision_matches_arm": None,
            **rule_fields(None, {}),
        }
    notes = decisions["notes"]
    included = decisions["included"]
    narrowed = notes.get("outcome") == "narrowed"
    return {
        "decision_outcome": notes.get("outcome"),
        "full_run_kind": notes.get("full-run-kind"),
        # WHICH refusal, where `full_run_kind` says only which SIDE refused. The daemon establishes
        # things the test JVM cannot -- a changed compile-time constant, an inline scan out of
        # budget, a map stamp git cannot relate to the base -- and until this field existed all of
        # them arrived as `selection-not-requested`, the same token a capture-only run writes.
        "refusal_kind": notes.get("refusal-kind"),
        # Absent means the jar that wrote this predates the note, which is version skew -- a
        # different thing from a run that produced no token, and the reason the classification gate
        # can name its cause instead of guessing at it.
        "decision_record_version": notes.get(DECISION_VERSION_NOTE),
        # Absent on a record written before the note existed, which is not the same as one
        # request of one -- so it is carried through as absent rather than defaulted.
        "decision_writer": notes.get(DECISION_WRITER_NOTE),
        "decision_reason": (notes.get("full-run-reason") or None),
        "selected_digest": selected_digest(included),
        "selected_verdicts": dict(sorted(decisions["verdicts"].items())),
        "decision_included": len(included),
        "decision_included_leaves": len(record_leaves(decisions) & set(included)),
        "decision_matches_arm": (len(included) == selected_distinct) if narrowed else None,
        **rule_fields(decisions.get("rules"), notes),
    }


def coverage_only(tokens):
    """Whether a row's rules include one coverage alone applies: not a shared JVM, not class rounding."""
    return any(not token.startswith(SHARES_JVM_RULE_PREFIX) and token != CLASS_GRANULARITY_RULE
               and token not in (RULE_NONE, RULE_UNKNOWN) for token in tokens)


def rule_fields(rules, notes):
    """Every rule behind the row's decisions, counted, and the selection coverage alone makes.

    `rules_basis` says what the rules were computed from, or why there are none; the counts and
    the coverage-only selection are None whenever there are none, which is not "no rule fired".
    Beneath a refusal or a full run the coverage-only selection is what would have run without it,
    so a looser mode can be judged offline against the failures the full arm found.
    """
    if rules is None:
        return {"rules_basis": notes.get(DECISION_RULES_NOTE),
                "forcing_kinds": notes.get(DECISION_FORCING_KINDS_NOTE),
                "rule_counts": None, "coverage_only_included": None, "coverage_only_digest": None}
    counts = Counter(token for tokens in rules.values() for token in tokens)
    beneath = [test for test, tokens in rules.items() if coverage_only(tokens)]
    return {"rules_basis": notes.get(DECISION_RULES_NOTE),
            "forcing_kinds": notes.get(DECISION_FORCING_KINDS_NOTE),
            "rule_counts": dict(sorted(counts.items())),
            "coverage_only_included": len(beneath),
            "coverage_only_digest": selected_digest(beneath)}


def map_fields(map_dir):
    """Which map this row selected against, so it cannot be silently compared across a re-capture."""
    commit, records, attributable = map_fingerprint(map_dir)
    return {"map_capture_commit": commit, "map_records": records,
            # The health the other two cannot see. A map can hold the right number of records,
            # captured at the right commit, and attribute almost none of them to a class.
            "map_attributable": attributable}


def map_facts(map_dir):
    """The map's version, how it was recorded, what it costs on disk and the task it describes."""
    return {"map_schema_version": sweep_data.schema_version(map_dir),
            "map_capture_mode": sweep_data.capture_mode(map_dir),
            "map_jvm_modes": sweep_data.jvm_modes(map_dir),
            **sweep_data.map_sizes(map_dir),
            "map_task_facts": sweep_data.task_facts(map_dir)}


# The agent's own line when a decision file it meant to publish never arrived. Seen on spring
# modules: a child-first class loader (spring-core-test's `@CompileWithForkedClassLoader`)
# defines a second copy of the agent in the same JVM and launches a nested JUnit launcher, so two
# `DecisionRecord` classes, each with its own writer counter starting at 1, stage and publish under
# the same names. The shared file is then a blend and the per-writer part is whichever copy wrote
# last -- often a nested launcher's three-row answer, which reads as complete.
DECISION_WRITE_FAILURE = re.compile(r"could not write decisions\.tsv")


def decision_write_failures(log_path):
    """How many decision files the agent says it failed to publish during this build, or None."""
    try:
        with open(log_path, encoding="utf-8", errors="replace") as handle:
            return sum(1 for line in handle if DECISION_WRITE_FAILURE.search(line))
    except OSError:
        return None


def trusted_decisions(decisions, log_path):
    """The record, or None when the agent said during this arm that a decision file was lost.

    A lost write means the file on disk may be another class loader's answer; see
    DECISION_WRITE_FAILURE. Read as absent, like a torn record, and counted on the row.
    """
    failures = decision_write_failures(log_path)
    return (None if failures else decisions), failures


# The verdict reason a shared-JVM selection is recorded under, AgentContract's Verdict name.
SHARES_JVM_REASON = "SHARES_JVM_WITH_CHANGE"


def sweep_row_fields(decisions, selected_ran, induced, write_failures, window_map, change_files,
                     change_modules):
    """What a row records beyond selection and recall: rules behind each failure, change shape.

    `decision_rows` against `selected_distinct` is the check a silently overwritten record fails: a
    record describing the whole task decided at least every test the arm ran.
    """
    notes = decisions["notes"] if decisions else None
    decided = len(decisions["decided"]) if decisions else None
    classes = sweep_data.changed_classes(notes)
    verdicts = decisions["verdicts"] if decisions else None
    return {
        "decision_write_failures": write_failures,
        "decision_rows": decided,
        "decision_covers_arm": (decided >= len(selected_ran)) if decided is not None else None,
        "shares_jvm_included": (verdicts.get(f"included/{SHARES_JVM_REASON}", 0)
                                if verdicts is not None else None),
        "induced_rules": sweep_data.induced_rules(induced, decisions.get("rules")
                                                  if decisions else None),
        "change_files": change_files,
        "change_modules": change_modules,
        "change_classes": len(classes) if classes is not None else None,
        "widest_windows": sweep_data.widest_windows(window_map, classes),
    }


def log_slug(label, index=None):
    """A filesystem-safe, unique-enough stem for one target's logs.

    THE INDEX IS PART OF IT, because a label is not a mutation. Two `override fun equals` in one
    Kotlin file share `F.kt::body::equals`, so the second declaration's logs and decision record
    overwrote the first's. Without an index this is the stem rows written before that fix were kept
    under, which the readers of kept evidence still need.
    """
    named = label if index is None else f"{label}::{index}"
    safe = re.sub(r"[^A-Za-z0-9]+", "-", named).strip("-")[-60:]
    return f"{safe}-{hashlib.sha1(named.encode('utf-8')).hexdigest()[:8]}"


def row_key(row):
    """A row's identity: its mutation `(target, kind, index)`.

    A LABEL IS NOT AN IDENTITY. It is `path::kind::name`, so two declarations of one name share it,
    and every place the harness matched rows by label -- paired ground truth, the resume, the
    rejections, the target filter -- read one mutation's row for another's. mockk's isolated arm was
    judged against the other `equals`' 586 failures and reported 2 misses on a row that excluded
    nothing. A row with no index (written before `index` was recorded, or a history replay, which
    has none) keys on its label alone and so stands for every declaration of it, as
    `pending_targets` already treats such rows.
    """
    if "index" in row and row.get("kind") is not None:
        return (row["target"], row["kind"], row["index"])
    return row["target"]


def keyed_in(keys, label, kind, index):
    """Whether this mutation, or a label-only key standing for every declaration of it, is in `keys`."""
    return (label, kind, index) in keys or label in keys


def keyed_lookup(table, label, kind, index):
    """`table`'s row for this mutation, or for its label when the row was keyed without an index."""
    return table.get((label, kind, index), table.get(label))


def paired_ground_truth(rows):
    """The paired file's rows that carry a ground truth, keyed per mutation, and any key held twice.

    Returns `(table, duplicates)`; `duplicates` maps each key that more than one usable row claims to
    its row count. THE CALLER REFUSES ON ANY DUPLICATE rather than keeping one: either row could be
    the one the isolated arm reproduces, and a guessed ground truth is the defect this keying fixes.
    """
    # A COMPLEMENT ROW HAS NO FULL-ARM TRUTH TO LEND: it ran only the dropped tests' classes.
    usable = [row for row in rows
              if row.get("status") in ("checked", "no-effect")
              and isinstance(row.get("full_distinct"), int)
              and row.get("induced") is not None
              and row.get("ground_truth") != COMPLEMENT_TRUTH]
    counts = Counter(row_key(row) for row in usable)
    table = {row_key(row): row for row in usable}
    return table, {key: count for key, count in counts.items() if count > 1}


def rejected_keys(stored):
    """The mutations a results file's compile gate already rejected, keyed as `row_key` keys rows.

    `rejected_targets` holds labels, which is what its readers print; `rejected_mutations` holds the
    keys. A label in the first with no key in the second was rejected before the index was recorded
    and stands for every declaration of it.
    """
    keyed = {tuple(key) for key in stored.get("rejected_mutations") or []}
    labels = {key[0] for key in keyed}
    return keyed | {label for label in stored.get("rejected_targets") or [] if label not in labels}


def distinct_targets(targets):
    """`targets` with every repeated `(path, kind, index)` dropped, in order, and how many were.

    spring-framework's `sources` list a directory and three directories inside it, so
    `mutation_targets` returns those files' targets twice and one mutation was measured twice under
    one key. Dropped here rather than in `target_ordering.py`, which is a grammar source: editing it
    would move `grammar_digest` and start every resumed results file fresh.
    """
    unique = list(dict.fromkeys(targets))
    return unique, len(targets) - len(unique)


def replay_commits(checkout, project, count):
    """Real commits to replay, oldest first, newest last.

    Only commits that touch the project's own source roots: a commit that changes documentation or
    another module cannot break this module's tests, so replaying it asks the recall question of
    nothing -- the same waste as a mutation that breaks nothing, and the reason ten of detekt's
    eighteen mutations measured air.

    Merge commits are skipped. `git diff` against a merge's first parent reports the whole side
    branch, which is a change set no developer ever produced and no map was ever meant to cover.
    """
    sources = project.get("sources", [])
    if not sources:
        # AN EMPTY PATHSPEC MATCHES EVERYTHING, which is the opposite of what this key is for.
        # `git log -- ` with nothing after it filters nothing, so every commit in the project's
        # history qualified as one that touched its sources -- documentation-only and other-module
        # commits included, which ask the recall question of nothing. Six project files carry
        # `sources = []` and none of them said so anywhere, so six projects were replaying
        # arbitrary commits while the docstring above promised the opposite.
        #
        # REFUSED, not warned about. Printing it and then replaying everything anyway is the shape
        # this repository keeps finding: a message nobody reads beside a number that is wrong. A
        # project that has not said which paths are its own has asked nothing, and answering a
        # question nobody asked is worse than answering none.
        print(f"    {project['name']}: `sources` is empty, so this project asks nothing and is "
              f"not replayed. An empty pathspec matches every commit, which is the opposite of "
              f"what that key is for. Set it to the module's source roots.")
        return []
    revisions = subprocess.run(
        ["git", "-C", checkout, "log", "--no-merges", "--format=%H",
         f"-n{count * 6}", project["commit"], "--"] + sources,
        capture_output=True, text=True, check=False,
    ).stdout.split()
    # Oldest first, so the map moves forward through history the way it would in real use.
    return list(reversed(revisions[:count]))


DEFAULT_BASELINE_REPEATS = 2


def baseline_repeats_for(project, default):
    """How many unmutated runs establish this project's already-failing set.

    ONE KNOB PRICED FOR THE CORPUS CANNOT ANSWER ONE UNSTABLE GUEST. duckduckgo-android's full arm
    ran 6,249, 6,252, 6,286 and 6,349 tests across sixteen rows -- a swing of about 100 between runs
    of the same suite -- and on three rows the SELECTED arm ran more tests than the ground-truth
    arm. A ground-truth arm that can silently omit 100 tests cannot support a recall figure: a
    failure among those 100 would be invisible and the row would read clean. The remedy is more
    unmutated runs on THAT project, and raising `--baseline-repeats` corpus-wide to get them costs
    one extra full suite run per project per sweep -- two hours on kafka-core's 7,399-second suite
    alone, to answer a question about a different guest.

    So it is a project-file key with the CLI flag as its default, which is exactly how `sweep_limit`
    relates to `--limit`. A value that is not a usable count falls back rather than raising, for
    `project_limit`'s reason: a typo in one project file must not quietly halve the evidence every
    later project buys.
    """
    stated = project.get("baseline_repeats")
    # `isinstance(True, int)` IS TRUE IN PYTHON, so `baseline_repeats = true` would resolve to 1 --
    # which buys no repeat at all, since `extra_baseline_failures` counts from 2 -- and would then
    # be stamped into the results file as `true`, where the freshness check compares it against
    # integers for the rest of that file's life. A bool is not a count.
    if isinstance(stated, bool):
        return default
    return stated if isinstance(stated, int) and stated > 0 else default


def baseline_repeats_changed(stored, project, default):
    """Were the stored rows measured with a different number of unmutated runs?

    How firmly a row's already-failing set was established is a property of THAT ROW, and a file
    holding rows taken at two repeat counts describes neither. `selector_digest` cannot witness this
    -- it hashes the selector, not `bench/` -- and neither can `commit`, which is why the value
    is stamped and read back here.

    NULL MEANS UNKNOWN, NOT ZERO: the `flags` discipline and deliberately not the `staged_sources`
    one. A file predating the FIELD says nothing about how its repeats were established, so reading
    its null as a mismatch would age every such file to introduce a key. The provable case is the
    other one: a staged project that stored no `staged_sources` cannot have been staged, because the
    field would have been written.
    """
    if stored.get("baseline_repeats") is None:
        return False
    return stored.get("baseline_repeats") != baseline_repeats_for(project, default)


def extra_baseline_failures(checkout, project, task_path, results_dir, task_name, scratch, *,
                            gradle, results_of, repeats, flags=(), stem="baseline-repeat"):
    """The tests that fail on the UNMUTATED tree in runs two onwards, as one union.

    THE HALF OF THE FLAKY-TEST PROBLEM THAT WAS STILL OPEN. `isolation.py` has repeated the ISOLATED
    re-run since 5d5ec33, so a "missed" test that cannot repeat its own answer is already reported as
    neither missed nor clean. The baseline was never repeated: `already_failing` came from ONE
    unmutated run, so an intermittent test that happened to pass that run and then failed the
    ground-truth arm was counted as INDUCED -- and from there it is a missed failure before the
    isolation arm is ever consulted, which is the most alarming thing this harness can print.

    A UNION, and the union is the whole rule: a test that fails in ANY baseline run is already
    failing. Its answers disagreeing with each other is exactly the property that disqualifies it as
    evidence, so a disagreement resolves to already-failing rather than to induced. That is the
    conservative direction here -- it can only REMOVE a test from the recall question, never add one
    -- and `isolation.py` resolves its own unknowns the same way for the same reason.

    It does not suppress a real induced failure: a test that passes every baseline repeat is in none
    of these sets, so a later arm failing it still counts.

    WHAT IT COSTS, stated rather than discovered: one extra full suite run per project per sweep for
    each repeat above the first. On kafka-core's 7,399-second suite that is two hours. `repeats` is
    the knob and [DEFAULT_BASELINE_REPEATS] is its default; `repeats <= 1` runs nothing at all and is
    the old behaviour, kept reachable so a measurement can be compared against what came before.

    A repeat that STALLS answers nothing and is reported rather than treated as a clean run. Taking
    silence for "nothing failed" is how a single-shot check became too weak in the first place.

    Returns `(found, answered, removed)`; `found` is the union as `failing_union` gives it, on both
    key schemes.
    """
    answers, answered, removed = [], 0, {}
    for attempt in range(2, max(repeats, 1) + 1):
        print(f"  baseline repeat {attempt} of {repeats}, with nothing mutated", flush=True)
        started = time.time()
        log = os.path.join(scratch, f"{stem}-{attempt}.log")
        code, _ = gradle(checkout, project, [task_path, "--rerun-tasks"] + list(flags), log)
        if code == STALLED:
            print(f"  baseline repeat {attempt} STALLED and was killed; it establishes nothing, so "
                  f"the already-failing set is drawn from the repeats that answered", flush=True)
            continue
        outcomes = results_of(checkout, results_dir, started, task_name, log)
        ran, failed = outcomes
        # THREE ANSWERS, NOT TWO. `results_of` returns (None, None) for "this run never reached the
        # tests", and `failed or set()` collapsed that into "this repeat ran everything and nothing
        # failed" -- silently, for a compile failure, an OOM-killed daemon, a non-zero exit before
        # the test task, or a results path the reader could not find. The repeat count in the log
        # then overstates how many actually answered, and the flake filter quietly reverts to the
        # single-shot strength this function was written to replace.
        if ran is None:
            print(f"  baseline repeat {attempt} reached no tests (exit {code}); it establishes "
                  f"nothing and is not read as a clean run", flush=True)
            continue
        answered += 1
        for cls, name in failed or set():
            # KEYED BY THE STRING FORM THE ROWS ALREADY USE, not by the (class, name) tuple
            # `results_of` returns. JSON object keys must be strings, so a tuple key made
            # `write_results` throw MID-SERIALIZATION and leave an unreadable file behind -- which
            # `sweep_corpus.summarise` then reported as `unreadable (JSONDecodeError)` and the whole
            # project's rows were lost. It only fired on a project whose baseline repeats found
            # something failing, so three projects passed before glide hit it.
            removed.setdefault(f"{cls}::{name}", []).append(attempt)
        answers.append(outcomes)
    # WHICH TESTS THE REPEATS REMOVED, AND BY WHICH RUN. A test that failed one baseline of three is
    # a different fact from one that failed all three, and the difference decides whether a row's
    # recall question was narrowed by evidence or by noise -- the repeats themselves add the load
    # that can produce that noise. Counting alone cannot tell the two apart, so the names travel.
    return failing_union(answers), answered, removed


def checkout_commit(checkout, commit):
    subprocess.run(["git", "-C", checkout, "checkout", "--force", "--quiet", commit], check=True)
    subprocess.run(["git", "-C", checkout, "clean", "-fdq"], check=False)


def backup_map(map_dir, scratch, name):
    """Copies a whole map aside. Not just coverage.tsv: the stamp and scope travel with it."""
    target = os.path.join(scratch, name)
    remove_tree(target) if os.path.isdir(target) else None
    shutil.copytree(map_dir, target)
    return target


def restore_map(backup, map_dir, attempts=4):
    """Puts the measured map back, and shouts rather than leaving a mutated one behind.

    Retried for the same reason `restore` is, and it was left out when that lesson was learned: the
    failure mode is not the copy refusing, it is the process being unable to act at all under memory
    pressure right after a large Gradle run -- which is exactly when this is called. The window it
    guards grew when the order-exposure analysis added a control arm and repeated its isolated
    re-runs: three full suites and up to three class runs per flagged test now sit between the
    backup and this call, against two and one before.

    A map left holding another measurement's coverage is not a loud failure. It is the next run
    silently selecting against the wrong records, which is the shape this project keeps finding.
    """
    last = None
    for attempt in range(attempts):
        try:
            remove_tree(map_dir)
            shutil.copytree(backup, map_dir)
            return
        except OSError as failure:
            last = failure
            if attempt < attempts - 1:
                time.sleep(2 ** attempt)
    raise SystemExit(
        f"could not restore the map at {map_dir} from {backup} after {attempts} attempts: {last}. "
        "The checkout is left holding a map from the middle of a measurement; delete it and "
        "re-capture before trusting any later run against this project."
    )


def sweep_history(checkout, project, module, scratch, map_dir, findings, done, limit, out_path,
                  capture_seconds, baseline_seconds, already_failing, select_flags=(),
                  results_dir=None):
    # `results_dir` is the DIRECTORY the JUnit XML lands in; `module` stays the Gradle name that
    # names tasks. They differ only where a settings plugin renames a subproject -- see
    # `module_dir`. It defaults to None rather than to `module` so that a caller which has not been
    # taught the difference keeps its old behaviour instead of silently reading a directory that
    # does not exist.
    """Recall against real commits instead of synthetic mutations.

    The mutation corpus asks "would selection have caught THIS shape of edit", and the four shapes
    are our guesses about what defeats a selector. Real commits are the actual distribution: several
    files at once, refactors, renames, a resource and a class together. This walks history forward
    with a map that ages exactly as it would in CI.

    Ground truth still comes from a full run we perform ourselves. Borrowing it from the project's
    own CI was the cheaper idea and does not work: GitHub exposes job-level conclusions, and the
    per-test detail lives in artifacts that expire. Probed on detekt, whose failed runs retain no
    artifacts and whose annotations say only "Process completed with exit code 1".

    The map is restored after each ground-truth run. Letting the full run's superior coverage carry
    forward would model a CI nobody operates -- one that runs everything and selects anyway -- and
    would flatter the next commit's selection with data a selecting run would never have had.
    """
    task_path = test_task_path(project)
    task_name = test_task_name(project)
    classes_task = test_classes_task(project)
    select_flags = list(select_flags)
    for commit in replay_commits(checkout, project, limit):
        label = f"commit::{commit[:12]}"
        if label in done:
            continue
        parent = subprocess.run(["git", "-C", checkout, "rev-parse", f"{commit}^"],
                                capture_output=True, text=True, check=False).stdout.strip()
        if not parent:
            continue
        print(f"--- {label}", flush=True)
        checkout_commit(checkout, commit)

        # Named after the commit, not overwritten per target. A shared selected.log meant the
        # reason a given commit forced a full run was gone by the time anyone read the summary --
        # and the reason is the whole finding. Costs a few files.
        # Compile before either arm is timed. Whichever runs first otherwise pays for compiling the
        # new commit while the second finds it UP-TO-DATE -- measured at 272s against 150s on a
        # commit where selection narrowed nothing, which reads as a penalty for selecting and is
        # really a penalty for going first.
        code, _ = gradle(checkout, project,
                         [f":{module}:{classes_task}" if module else classes_task],
                         os.path.join(scratch, f"compile-{commit[:12]}.log"))
        if stalled(code, label, findings, "compile"):
            continue
        # RECORDED, not discarded, and this is the opposite decision to the mutation path's gate
        # for the opposite reason. A replay tree carries the project's own commit and no generated
        # source, so a compile failure here is a property of that commit -- it IS the finding, and
        # discarding it would throw away the answer. It increments no rejection count: nothing was
        # generated, so there is nothing to hold a grammar to account for.
        if code != 0:
            findings.append({"target": label, "status": "build-failed", "phase": "compile"})
            print("    BUILD FAILED before either arm: this commit does not compile; recorded "
                  "rather than discarded, because that is a fact about the commit", flush=True)
            continue

        selected_log = os.path.join(scratch, f"selected-{commit[:12]}.log")
        # The map this commit selects against, for the widest-window report: the selecting arm
        # updates it, so it is read from a copy taken now.
        window_map = snapshot_for_replay(map_dir, scratch)
        changed_paths = subprocess.run(
            ["git", "-C", checkout, "diff", "--name-only", parent, commit],
            capture_output=True, text=True, check=False).stdout.split()
        selected_conditions = machine_conditions()
        started = time.time()
        selected_code, selected_seconds = gradle(
            checkout, project,
            [*INIT_SCRIPT_ARGS, task_path,
             "-Pyoriwake.select", f"-Pyoriwake.base={parent}"] + select_flags + TIMING_ARM_FLAGS,
            selected_log)
        if stalled(selected_code, label, findings, "selected"):
            continue
        if not executed_or_excluded(selected_log, module, label, findings, "selected", task_name):
            continue
        selected = results_of(
            checkout, results_dir if results_dir is not None else module, started, task_name,
            build_dir(project), log_path=selected_log, task_path=task_path)
        if selected[0] is None:
            selected = Outcomes(set(), set(), cause=selected.cause)
        selected_ran, selected_failed = selected

        # Same reading, same place in the order, as the mutation path. A replay row that recorded
        # none of this would make an absent field mean "the sweep was a replay" as well as "the
        # selector wrote nothing", which is the ambiguity the decision record exists to remove.
        decisions = read_decision_record(map_dir, started)
        invalid = undecided_row(label, "selected", decisions, selected_ran, selected_code,
                                selected_log)
        if invalid:
            findings.append(invalid)
            continue
        if decisions is not None and map_dir:
            keep_decisions(map_dir, os.path.join(scratch, f"decisions-{commit[:12]}"))
        decisions, write_failures = trusted_decisions(decisions, selected_log)
        raw_kept = sweep_data.keep_raw(map_dir, os.path.join(scratch, "raw", commit[:12]), started,
                                       with_exec=False)

        # The selecting run has just updated the map. Keep that; it is what the next commit would
        # legitimately inherit. The FULL run below must not.
        backup = backup_map(map_dir, scratch, "map-after-selected")

        full_log = os.path.join(scratch, f"full-{commit[:12]}.log")
        full_conditions = machine_conditions()
        started = time.time()
        # Init script on both arms, for the reason given on the mutation path's ground-truth run.
        full_code, full_seconds = gradle(
            checkout, project,
            [*INIT_SCRIPT_ARGS, task_path] + TIMING_ARM_FLAGS,
            full_log)
        restore_map(backup, map_dir)
        if stalled(full_code, label, findings, "ground-truth"):
            continue
        if not executed_or_excluded(full_log, module, label, findings, "ground-truth", task_name):
            continue
        full = results_of(
            checkout, results_dir if results_dir is not None else module, started, task_name,
            build_dir(project), log_path=full_log, task_path=task_path)
        if full[0] is None:
            findings.append({"target": label, "status": "did-not-build"})
            print("    excluded: ground truth never reached the tests", flush=True)
            continue
        full_ran = full[0]
        # The row's key scheme and its shared-key rule, as on the mutation path.
        keys = row_keys(selected, full, decisions, "full arm")
        selected_keyed_ran, selected_keyed_failed = keyed(selected, keys.scheme)
        full_failed = keyed(full, keys.scheme)[1]

        # A test that fails on the unmutated tree fails for its own reasons -- kestra's
        # WorkerTest::killed times out after a minute on a loaded laptop -- and it is not evidence
        # about selection. Counting it made two mutations report a missed failure, one of them from
        # a mutation that only ADDED AN UNUSED METHOD and could not change behaviour at all.
        #
        # Subtracted rather than tolerated, because the failure mode is asymmetric: such a test is
        # recorded FAILED in the map and always run, so it is invisible -- until the run that built
        # the map happened to catch it passing, and then it reads as a silent skip that never was.
        induced = subtract_baseline(full_failed, already_failing, keys)
        missed, undetermined, shared_key = unmatched(induced, selected_keyed_ran, keys.shared)
        # THE SAME RULE AT BOTH CALL SITES, which is the standing requirement in this file and was
        # not met here. `container_failures` below is scoped to rows that narrowed on the grounds
        # that a forced row is "already disqualified" -- and that was only true on the mutation
        # path, because this one never applied the disqualification. The premise now holds here too.
        missed = misses_a_row_that_skipped_nothing_cannot_have(missed, selected_ran, full_ran,
                                                               decisions)
        if undetermined:
            print(f"    {len(undetermined)} induced failures could not be matched across the two "
                  f"runs (their ids carry an identity hash); UNDETERMINED, not missed and not "
                  f"clean", flush=True)
        if shared_key:
            print(f"    {len(shared_key)} induced failures ran in the selected arm only under a key "
                  f"another test may have answered for; SHARED KEY, not missed and not clean",
                  flush=True)
        # The same verification the mutation path does, on both paths deliberately. Every defect
        # this project has found twice was a rule applied at one call site and not the other.
        if missed:
            dependent, unrepeatable = _order_dependent(
                checkout, project, module, missed, scratch, commit[:12], map_dir,
                gradle=gradle, results_of=results_reader(project), backup_map=backup_map,
                restore_map=restore_map, flags=TIMING_ARM_FLAGS, task=task_name, keys=keys)
            if dependent:
                print(f"    {len(dependent)} of {len(missed)} missed tests also pass when run alone "
                      f"against the same tree; they are reported as order-dependent AND counted",
                      flush=True)
            if unrepeatable:
                print(f"    {len(unrepeatable)} of {len(missed)} missed tests gave DIFFERENT answers "
                      f"to identical isolated re-runs; nondeterministic, neither missed nor clean",
                      flush=True)
            order_dependent_tests = sorted(dependent)
            # Subtracted from the headline, never silently: a test whose own answers contradict each
            # other is not evidence that selection skipped anything, and it is not evidence that
            # selection is clean either. It gets its own row field and its own line above.
            nondeterministic_tests = sorted(unrepeatable)
            missed = [test for test in missed if test not in unrepeatable]
            # AFTER both arms have run, so the split is made on what they could not settle rather
            # than before they were asked. A container is then dropped from the order-dependent list
            # too: it passed alone because it started alone, and leaving it in both lists would
            # count one undecidable thing twice.
            missed, container_failed = container_failures(missed, selected_ran, full_ran)
            if container_failed:
                order_dependent_tests = [t for t in order_dependent_tests
                                         if t not in container_failed]
                print(f"    {len(container_failed)} of the failures are CONTAINERS that never "
                      f"started, not tests; neither missed nor clean, because both verification "
                      f"arms run the class alone and cannot reproduce a full-suite load",
                      flush=True)
        else:
            container_failed = []
            order_dependent_tests = []
            nondeterministic_tests = []
        # Undetermined subtracted too: a test whose id could not be matched across the runs cannot
        # be said to have run and passed either. And `selected_failed` is matched the same way the
        # miss check is -- on the stable name -- because a raw comparison here published a test that
        # failed in BOTH arms as one that ran and passed.
        passed_anyway = ran_but_passed(induced, selected_keyed_failed, missed, undetermined,
                                       nondeterministic_tests, container_failed, shared_key)
        status = "checked" if induced else "no-effect"
        findings.append({
            "target": label,
            "kind": "history",
            "status": status,
            "commit": commit,
            "base": parent,
            "full_distinct": len(full_ran),
            "full_failures": len(induced),
            "already_failing": len(full_failed - induced),
            "selected_distinct": len(selected_ran),
            "missed": [f"{c}::{n}" for c, n in missed],
            "order_dependent": [f"{c}::{n}" for c, n in order_dependent_tests],
            # NEVER CLEAN AND NEVER MISSED. A test whose display name carries an identity hash has a
            # different name in every run, so the two runs cannot be compared about it at all.
            "undetermined": [f"{c}::{n}" for c, n in undetermined],
            # ALSO NEVER CLEAN AND NEVER MISSED, and a SEPARATE field from the one above because the
            # two mean different things: `undetermined` is "the two runs named this test
            # differently", this is "the test answered its own isolated re-run two different ways".
            # One token meaning two things is a token nobody can act on.
            "nondeterministic": [f"{c}::{n}" for c, n in nondeterministic_tests],
            # A THIRD KIND OF NEITHER, and its cause is not the other two's. `undetermined` is "the
            # two runs named this test differently" and `nondeterministic` is "it answered its own
            # re-run two ways"; this is "it is not a test at all -- the class never started". Both
            # verification arms run a class alone, which is a lighter load than the full suite, so
            # they cannot reproduce a container that fails only under one. Named, counted, and
            # subtracted from the headline.
            "container_failed": [f"{c}::{n}" for c, n in container_failed],
            "shared_key": [f"{c}::{n}" for c, n in shared_key],
            "ran_but_passed": [f"{c}::{n}" for c, n in passed_anyway],
            "result_keys": keys.stamp,
            "result_keys_fallback": keys.fallback,
            # The induced failures whose keys two tests produced in these builds, which a paired arm
            # cannot see from its own selected run (see `paired_outcomes`).
            "collapsed_induced": sorted(f"{c}::{n}" for c, n in induced
                                        if (c, n) in keys.collapsed),
            "ci": ci_stamp(),
            "selected_seconds": selected_seconds,
            "full_seconds": full_seconds,
            "full_run_reason": full_run_reason(selected_log, test_task_path(project)),
            **decision_fields(decisions, len(selected_ran)),
            **map_fields(map_dir),
            **map_facts(window_map),
            "raw_bytes_kept": raw_kept,
            "selected_arm": arm_stats(selected_log),
            "full_arm": arm_stats(full_log),
            # Modules as the first path segment: a heuristic, stated as one in the data dictionary.
            **sweep_row_fields(decisions, selected_ran, induced, write_failures, window_map,
                               change_files=len(changed_paths),
                               change_modules=len({p.split("/", 1)[0] for p in changed_paths
                                                   if "/" in p})),
            "selected_conditions": selected_conditions,
            "full_conditions": full_conditions,
        })
        print(f"    {len(induced)} fail, selected {len(selected_ran)}/{len(full_ran)} -> "
              f"{'MISSED ' + str(len(missed)) if missed else 'ok'}"
              f"{', ' + str(len(order_dependent_tests)) + ' of them order-dependent'
                 if order_dependent_tests else ''}"
              f"{', ' + str(len(nondeterministic_tests)) + ' nondeterministic'
                 if nondeterministic_tests else ''}"
              f"{', ' + str(len(passed_anyway)) + ' ran but passed' if passed_anyway else ''}",
              flush=True)
        write_results(out_path, project, module, capture_seconds, baseline_seconds, findings,
                      map_dir=map_dir)
    return findings



# A test id that names an object's identity hash, which is different in every JVM.
#
# **THIS PRODUCED A FALSE RECALL FAILURE, AND IT IS THE MOST DANGEROUS KIND OF FINDING THIS HARNESS
# CAN EMIT AFTER A REAL ONE.** armeria's `GrpcServiceServerTest` is parameterized on a client object
# whose display name is `[1] blockingClient=...UnitTestServiceBlockingStub@7b50d62`. The suffix is
# `Object.toString`'s identity hash and changes every run, so the ground-truth run and the selected
# run name the SAME test differently -- and `induced - selected_ran`, which is set subtraction on
# those names, reported it as a test that was skipped. It was not skipped. It ran under another
# name, in a suite where 470 of 698 were selected, and the row published "MISSED 2".
#
# The order-exposure analysis already met this shape and handled it: 22 of kestra's tests are
# UNDETERMINED there, never clean and never counted, because a test present under two names was
# never compared. This is the same rule arriving in the harness that publishes the headline number.
#
# Matched on `@` plus hex, which is what the JDK emits and what a deliberate display name almost
# never contains.
UNSTABLE_ID = re.compile(r"@[0-9a-f]{4,}\b")


def stable_name(test):
    """A (class, name) pair with any identity hash removed, for comparing ACROSS runs only.

    Never used to name a test in output: what is printed is always the raw id the run reported, so
    a reader sees what the build saw.
    """
    return (test[0], UNSTABLE_ID.sub("@?", test[1]))


def matched_in(test, candidates):
    """Whether `test` appears in `candidates`, comparing an identity-hashed id on its stable name.

    The same comparison `unmatched` makes, extracted because `ran_but_passed` needs it too and did
    not have it: that subtraction used the RAW `selected_failed` ids, so a test that failed in both
    arms under two different identity hashes was excluded from `missed` (right) and then published
    as "selected and executed, so not a skip -- order-dependence or flakiness" (wrong). It failed
    normally, twice, and the harness said it passed.
    """
    if test in candidates:
        return True
    if not UNSTABLE_ID.search(test[1]):
        return False
    return any(stable_name(test) == stable_name(other) for other in candidates)


def ran_but_passed(induced, selected_failed, missed, undetermined, nondeterministic,
                   container_failed, shared_key):
    """Induced failures that the selected run executed and that did NOT fail there.

    A weaker question than `missed` and a different one: such a test is not skipped, it runs and
    passes, because selection changed what shared a JVM with it. Reported separately rather than
    folded into the headline.

    **Extracted so a test can reach it.** It lived inline in both sweep loops as a set subtraction
    against the RAW `selected_failed` ids, so a test that failed in both arms under two different
    identity hashes was published as having passed. A unit test on `matched_in` alone did not catch
    that -- the helper was right and the call site did not use it -- which is the shape of defect
    this file already documents twice.

    `nondeterministic` is a required argument rather than a defaulted one. Those tests were taken
    OUT of `missed` by the isolated re-run, and a test that never ran in the selected arm cannot
    have run and passed there -- so omitting them would move a reclassified test from one published
    list straight into another, both of them wrong. A default would have let one of the two call
    sites forget, which is the drift this harness keeps finding.

    `container_failed` is required for exactly the same reason, and was missed for exactly the
    reason the paragraph above predicts. A container that never started was subtracted from
    `missed` and then reappeared here as a test that RAN AND PASSED -- one undecidable thing
    published twice, in two lists that contradict each other. It is not a test and it ran nowhere.

    `shared_key` is required for the same reason again: its tests ran only under a key a sibling
    may have answered for, so nothing says they ran at all.
    """
    failed = set(selected_failed)
    skip = (set(missed) | set(undetermined) | set(nondeterministic) | set(container_failed)
            | set(shared_key))
    return sorted(
        test for test in induced
        if test not in skip and not matched_in(test, failed)
    )


# A decision record's segments, as `replay_decisions.name_of` reads them: which class a test belongs
# to, and the method of a plain test -- Jupiter's `[method:m(fq.Type)]` or Vintage's
# `[test:m(fq.Class)]`. A template, factory, feature or dynamic test is not a plain method.
RECORD_CLASS_SEGMENT = re.compile(r"\[(class|class-template|runner|spec|nested-class):([^\]]+)\]")
RECORD_PLAIN_SEGMENT = re.compile(r"\[(method|test):([^\]]+)\]$")
RECORD_SEPARATOR = "]/["


def record_class(test_id):
    """The binary class name a recorded id belongs to (`pkg.Outer$Inner`), or None.

    A unique id percent-encodes the characters it reserves (`nextSource%5BUtf8%5D` is a JUnit 4
    `Parameterized` test's `nextSource[Utf8]`), so every value is decoded before it is compared.
    """
    name = None
    for kind, value in RECORD_CLASS_SEGMENT.findall(test_id):
        value = urllib.parse.unquote(value)
        name = f"{name}${value}" if kind == "nested-class" and name else value
    return name


def record_plain_name(test_id):
    """The XML name of a recorded plain method, or None for anything else.

    Jupiter's display name for `m(fq.Type, int)` is `m(Type, int)`, and the XML reader drops a bare
    `()`; Vintage's parentheses hold the class, which the XML leaves out.
    """
    found = RECORD_PLAIN_SEGMENT.search(test_id)
    if not found:
        return None
    method, _, params = urllib.parse.unquote(found.group(2)).partition("(")
    if found.group(1) == "test":
        # Vintage's, under its runner. Kotest writes `[test:...]` for its leaves too, which are not
        # methods at all.
        return method if "[runner:" in test_id and params else None
    params = params.removesuffix(")")
    simple = [re.split(r"[.$]", p.strip())[-1] for p in params.split(",") if p.strip()]
    return f"{method}({', '.join(simple)})" if simple else method


def record_leaves(decisions):
    """The record's leaf ids: those that prefix no other recorded id. A refused row records engine,
    class and runner containers too, and those are not decisions about a test."""
    ids = [test for test, _ in decisions["decided"]]
    prefixes = {test[:found.start() + 1] for test in ids
                for found in re.finditer(re.escape(RECORD_SEPARATOR), test)}
    return {test for test in ids if test not in prefixes}


class RecordIndex:
    """What the shared-key rule asks of one trusted decision record, per class of its leaves (as
    `record_leaves` defines them)."""

    def __init__(self, decisions):
        self.forced = decisions["notes"].get("outcome") == "full-run"
        leaves = record_leaves(decisions)
        self.included, self.excluded, self.plain = Counter(), Counter(), {}
        for test, verdict in decisions["decided"]:
            if test not in leaves:
                continue
            cls = record_class(test)
            if cls is None:
                continue
            if verdict.partition("/")[0] == "included":
                self.included[cls] += 1
            else:
                self.excluded[cls] += 1
            plain = record_plain_name(test)
            if plain is not None:
                self.plain.setdefault(cls, Counter())[plain] += 1


def shares_a_key(test, scheme, index, collapsed):
    """Whether a failure on `test` may stand for a test other than the one that failed.

    THE ONE PLACE THIS IS DECIDED, and every comparison asks it: the selected arm clearing a failure,
    the baseline subtracting one, the isolated re-runs and the control arm setting one aside. A key
    shared by two methods let a selected method clear an excluded one's failure (iceberg's
    `TestMetadataTableScans`: 66 methods on 4 XML keys), and the same collapse hid misses at all four.
    One rule for all of them: were the baseline to refuse a subtraction the selected arm then cleared,
    an already-failing key would count as caught.

    True when all three hold:
    - the row could have narrowed: its record narrowed, or is absent or untrusted (`index` None).
      A trusted record that forced ran everything and is left alone;
    - the key names no single method. On per-method keys, one with a template, factory or feature
      segment always does; one without does unless two tests of its class with different own names
      produced it (`collapsed`). On XML keys, the name must be exactly one plain method of its class
      in the record, so a Spock feature or a `@DisplayName` method never is;
    - the record did not keep its class whole: some leaves excluded, or the class not in it at all.
      Kept whole, every test the key could stand for ran in the selected arm, which clears it. A class
      excluded whole is not exempt: the baseline, the re-runs and the control arm read its key from
      another build, where any of its tests may have produced it, and a selected arm that ran such a
      key contradicts its record.

    Such a failure is never caught and never subtracted: it is `shared_key`, or it stays missed.
    """
    if index is not None and index.forced:
        return False
    cls = test[0]
    if scheme == PER_METHOD:
        if has_ancestor(test) or test not in collapsed:
            return False
    elif index is not None and index.plain.get(cls, {}).get(test[1]) == 1:
        return False
    if index is None or cls not in index.included and cls not in index.excluded:
        return True
    return bool(index.excluded[cls])


class RowKeys:
    """One row's key scheme: what its failures are named and matched by, the weakest comparison it
    made (`stamp`, written as `result_keys`) and the first that fell back to XML keys, with why."""

    def __init__(self, scheme, decisions, collapsed=frozenset(), fallback=None):
        self.scheme = self.stamp = scheme
        self.fallback = fallback
        self.decisions = decisions
        self._index = None
        self.collapsed = set(collapsed)

    @property
    def index(self):
        """The record's `RecordIndex`, built on the first question: most rows never ask one."""
        if self._index is None and self.decisions is not None:
            self._index = RecordIndex(self.decisions)
        return self._index

    def fall_back(self, where, cause):
        """A later comparison of this row was made on XML keys; the earlier ones stand."""
        if self.fallback is None:
            self.fallback = f"{where}: {cause}"
        self.stamp = XML

    def shared(self, test, scheme=None):
        return shares_a_key(test, scheme or self.scheme, self.index, self.collapsed)

    def alone(self, outcomes, test, where):
        """`(key, ran, failed)` to answer an isolated re-run about `test` with, or None when this
        re-run cannot answer for it.

        A re-run whose own outcomes are incomplete answers on XML keys, and the row is stamped so;
        if the test's XML key may stand for a sibling there, the re-run answers nothing.
        """
        ran, failed = outcomes[0], outcomes[1]
        if self.scheme != PER_METHOD:
            return test, ran, failed
        methods = getattr(outcomes, "methods", None)
        if methods is not None:
            return test, methods.ran, methods.failed
        self.fall_back(where, getattr(outcomes, "cause", None) or "no per-method outcomes")
        projected = xml_key(test)
        if self.shared(projected, XML):
            return None
        return projected, ran, failed


def row_keys(selected, full, decisions, full_name):
    """The row's `RowKeys`: per method when both arms' outcomes are complete, else XML keys."""
    if selected.methods is not None and full.methods is not None:
        return RowKeys(PER_METHOD, decisions, selected.methods.collapsed | full.methods.collapsed)
    which, cause = (("selected arm", selected.cause) if selected.methods is None
                    else (full_name, full.cause))
    return RowKeys(XML, decisions, fallback=f"{which}: {cause}")


def keyed(outcomes, scheme):
    """`(ran, failed)` of one build on the given scheme."""
    if scheme == PER_METHOD:
        return outcomes.methods.ran, outcomes.methods.failed
    return outcomes[0], outcomes[1]


def paired_outcomes(row):
    """A paired row's ground truth as this arm reads it: its failures on its own scheme, and only
    the count of what its full arm ran. Per method only when the paired row was.

    WITH THE KEYS ITS FULL ARM SAW COLLAPSED (`collapsed_induced`). This arm's selected run may have
    kept only one of two methods sharing a display name, so its own outcomes show no collapse, and
    without the paired row's the selected sibling would clear the excluded method's failure. A
    per-method row written before the field is read on XML keys rather than as collapsing nothing.
    """
    tests = {tuple(test.split("::", 1)) for test in row["induced"]}
    ran = [None] * row["full_distinct"]
    if row.get("result_keys") == PER_METHOD and row.get("collapsed_induced") is not None:
        collapsed = {tuple(test.split("::", 1)) for test in row["collapsed_induced"]}
        return Outcomes(ran, {xml_key(test) for test in tests}, Methods(None, tests, collapsed))
    cause = ("the paired row predates collapsed keys" if row.get("result_keys") == PER_METHOD
             else "the paired row is on XML keys" if row.get("result_keys")
             else "the paired row predates result_keys")
    return Outcomes(ran, {xml_key(test) for test in tests}
                    if row.get("result_keys") == PER_METHOD else tests, cause=cause)


# A paired arm's already-failing set: its rows' failures come from the paired file, which already
# subtracted its own baseline, so there is nothing left to subtract -- on either scheme.
NOTHING_FAILING = Outcomes(None, frozenset(), Methods(None, frozenset()))


def failing_union(builds):
    """What any of these unmutated builds failed, on both schemes; per method only when every one
    of them was complete. A build that reached no tests is the caller's to leave out."""
    xml = set().union(*(set(build[1] or ()) for build in builds))
    incomplete = [build for build in builds if getattr(build, "methods", None) is None]
    if incomplete:
        return Outcomes(None, xml, cause=getattr(incomplete[0], "cause", None)
                        or "no per-method outcomes")
    return Outcomes(None, xml, Methods(None, set().union(*(b.methods.failed for b in builds)),
                                       set().union(*(b.methods.collapsed for b in builds))))


def subtract_baseline(full_failed, already_failing, keys):
    """The full arm's failures that the unmutated tree does not already fail: the induced set.

    A failure whose key may stand for a sibling is never subtracted: a sibling flaking on the
    unmutated tree is no evidence about the method that failed here (iceberg's shape, a flaky
    `a() > v = 1` and an excluded `b() > v = 1` on one XML key). On a per-method row whose baseline
    holds XML keys only -- a cache written before per-method keys -- the subtraction is made on XML
    keys, and it weakens the row only where a failure's XML key is in that set: elsewhere it cannot
    change an answer.
    """
    already = already_failing[1]
    methods = getattr(already_failing, "methods", None)
    if keys.scheme == PER_METHOD and methods is not None:
        return {test for test in full_failed if test not in methods.failed or keys.shared(test)}
    if keys.scheme == PER_METHOD:
        if any(xml_key(test) in already for test in full_failed):
            keys.fall_back("baseline", getattr(already_failing, "cause", None)
                           or "no per-method outcomes")
        return {test for test in full_failed
                if xml_key(test) not in already or keys.shared(xml_key(test), XML)}
    return {test for test in full_failed if test not in already or keys.shared(test)}


def unmatched(induced, ran, shared):
    """Which induced failures did not run, which could not be compared at all, and which ran only
    under a key another test may have answered for.

    Returns (missed, undetermined, shared_key). `shared` is the row's `RowKeys.shared`: a failure
    whose key a selected sibling shares is not evidence it ran, so it is `shared_key` -- never
    caught, and never missed either, since the method may have run.

    A test whose id carries an identity hash is compared on the normalised name, which is the
    honest comparison: `[1] blockingClient=...@a` and
    `[1] blockingClient=...@b` are the same test. A normalised name that is AMBIGUOUS -- two
    different raw ids in one run collapsing onto it -- is undetermined instead, because clearing a
    miss on a collision would be the dangerous direction of this same defect.

    UNDETERMINED IS NOT CLEAN. It is counted, reported and named, exactly as the order-exposure
    analysis did, because a suite this harness cannot ask about is a hole in the evidence rather
    than a pass.
    """
    exact = set(ran)
    normalised = {}
    for test in ran:
        normalised.setdefault(stable_name(test), set()).add(test)

    missed, undetermined, shared_key = [], [], []
    for test in sorted(induced):
        if test in exact:
            if shared(test):
                shared_key.append(test)
            continue
        if not UNSTABLE_ID.search(test[1]):
            missed.append(test)
            continue
        matches = normalised.get(stable_name(test), set())
        if len(matches) == 1:
            if shared(test):
                shared_key.append(test)
            continue
        # No match at all, or more than one: either way this comparison did not answer.
        undetermined.append(test)
    return missed, undetermined, shared_key


def counts_toward_narrowed_target(row):
    """Whether a row counts toward `--narrowed-target`: it narrowed, and its mutation broke a test.

    The stopping rule ends a project's sweep once this many rows hold, so a project that narrows
    often is not paid for at its full `--limit` to ask the same recall question again.

    WHY IT CANNOT BIAS THE RECALL FIGURE. Targets are drawn in a fixed order, and whether the sweep
    goes on to the next one depends on this predicate over the rows before it -- and this predicate
    reads only the two arms' sizes, the selector's own outcome token and whether the full arm induced
    a failure. None of those depend on whether a failure was missed or caught, so the set of rows
    measured is the set it would be whatever the misses were, and the missed count over it is a
    count over a sample its own outcomes did not choose.

    `status == "checked"` and NOT `assert_recall.answered_failures`, deliberately: that subtracts the
    set-aside buckets (`fails_unmutated`, `container_failed`, ...), which are only ever computed for
    tests that were MISSED -- so asking it would make the rule depend on the misses.
    """
    import assert_recall  # noqa: PLC0415 -- it imports this module; one definition of "narrowed"
    return row.get("status") == "checked" and assert_recall.narrowed_at_all(row)


def narrowed_target_reached(findings, target):
    """Whether the stopping rule ends the draw: `target` rows of the WHOLE file count toward it,
    resumed rows included, exactly as `--limit` counts them. None is no rule."""
    if target is None:
        return False
    return sum(1 for row in findings if counts_toward_narrowed_target(row)) >= target


# What an accepted list and the run measuring a slice of it must agree on: the list is the order
# one grammar drew against one map under one selector, and a shard measuring it under another
# would merge rows of two different samples.
ACCEPTED_STAMPS = ("selector_digest", "grammar_digest", "plugin_commit", "commit",
                   "fixture_digest")


def stamp_rows(findings, stamped, control=False):
    """Gives the rows appended since `stamped` = (start, kind, index) that kind and index, and the
    `control` stamp on a control run: a stalled, failed or cache-hit row is appended without it, and
    a reader would take the file for a measurement's."""
    if stamped is not None:
        start, kind, index = stamped
        for row in findings[start:]:
            row.setdefault("kind", kind)
            row.setdefault("index", index)
            if control:
                row.setdefault("control", True)


def parse_shard(text):
    """`K/N` as `(K, N)`, shard K of N counted from 1."""
    match = re.fullmatch(r"(\d+)/(\d+)", text or "")
    if not match or not 1 <= int(match.group(1)) <= int(match.group(2)):
        raise SystemExit(f"--shard takes K/N with 1 <= K <= N, not {text!r}")
    return int(match.group(1)), int(match.group(2))


def accepted_slice(accepted, shard):
    """The `(path, kind, index)` items shard `(K, N)` measures, and the list position of its first
    row: a shard counts its rows from there, so the timing cap picks the rows a single job would."""
    number, count = shard
    slices = accepted.get("slices") or []
    if len(slices) != count:
        raise SystemExit(f"the accepted list is cut into {len(slices)} slice(s), not {count}")
    start, end = slices[number - 1]
    return [(path, kind, index) for path, kind, index, _ in accepted["targets"][start:end]], start


def accepted_mismatch(accepted, stamps):
    """Why a run under `stamps` may not measure `accepted`, or None."""
    for key in ACCEPTED_STAMPS:
        if accepted.get(key) != stamps.get(key):
            return (f"the accepted list was drawn with {key} {accepted.get(key)!r} and this run has "
                    f"{stamps.get(key)!r}")
    return None


def write_accepted(path, project, grammar, targets, rejected, args):
    """The recording job's accepted list: the targets in draw order, the stamps a shard must match,
    and the rules every shard runs under, which `shard.py` prices and `merge.py` truncates by."""
    with open(path, "w", encoding="utf-8") as handle:
        json.dump({"project": project["name"], "commit": project["commit"],
                   "plugin_commit": plugin_commit(), "selector_digest": measured_selector_digest(),
                   "grammar_digest": grammar,
                   "fixture_digest": RUN_STAMPS.get("fixture_digest"), "sweep_limit": args.limit,
                   "narrowed_target": args.narrowed_target, "timing_cap": args.timing_cap,
                   # `pool/v1`'s per-family target, which `merge.py` truncates each family by.
                   "family_target": (RUN_STAMPS.get("family_order") or {}).get("target"),
                   "recall_only": args.recall_only, "ground_truth": args.ground_truth,
                   "targets": targets, "rejected": rejected}, handle, indent=1)


def revert_fields(candidate, variant):
    """What a revert row carries beside a mutation row's fields: the commit, its census labels as
    the revert makes them, the variant applied and the paths it left out."""
    reverted = set(candidate["paths"] if variant != "full" else candidate["full_paths"])
    return {"revert_commit": candidate["sha"],
            "census_labels": candidate["labels"],
            "revert_labels": revert.revert_labels(candidate, variant),
            "revert_stratum": candidate["stratum"],
            "revert_variant": variant,
            "revert_scope": sorted(set(candidate["scope"] + candidate["full_paths"]) - reverted)}


def stamp_revert(stamp, findings, narrowed_target):
    """The revert set's file stamp, brought up to date with `findings`: rejections counted by
    reason and census label, and which shapes have enough miss-possible rows for a figure."""
    stamp["rejected"] = revert.rejection_counts(stamp.get("rejections") or [])
    stamp["reach"] = revert.reach(findings, counts_toward_narrowed_target,
                                  narrowed_target or revert.MISS_POSSIBLE_TARGET)
    stamp["not_reached"] = revert.not_reached(stamp["reach"])


def write_results(out_path, project, module, capture_seconds, baseline_seconds, findings,
                  target_seed=None, map_dir=None, daemon_uptime_at_start=None,
                  shapes=None, targets_available=None,
                  baseline_repeats=None, baseline_answered=None, baseline_removed=None,
                  rejected_targets=None, rejected_mutations=None, grammar=None):
    # STAGED, THEN MOVED INTO PLACE. This file is the checkpoint a multi-hour sweep resumes from,
    # and a write torn by a kill or a mid-serialization error left an unreadable file behind and
    # lost a project's rows once already. `os.replace` keeps the previous checkpoint until the new
    # one is whole -- the same staging `snapshot_for_replay` and the plugin's `AtomicWrite` use.
    if RUN_STAMPS.get("revert") is not None:
        stamp_revert(RUN_STAMPS["revert"], findings, RUN_STAMPS.get("narrowed_target"))
    stops = RUN_STAMPS.get("family_order")
    if stops is not None:
        stops["families"] = family_order.counters(findings, stops["cap"], stops["target"],
                                                  counts_toward_narrowed_target)
    staging = out_path + ".writing"
    with open(staging, "w", encoding="utf-8") as handle:
        json.dump({
            "project": project["name"],
            "commit": project["commit"],
            "module": module,
            "plugin_commit": plugin_commit(),
            # WHAT THE SELECTOR WAS, as opposed to where the repository was. Freshness is decided
            # on this; `plugin_commit` above stays because rule 4 asks every figure to name the
            # commit it was measured at, and a digest is not a commit anybody can check out.
            "selector_digest": measured_selector_digest(),
            # WHICH ORDERING DREW THESE TARGETS, not only which seed. Every recall and narrowing
            # figure this corpus published before this field came from a sorted-path order that
            # preferred hub classes, and a file that recorded a seed alone could not be told from
            # one of those. Do not compare rows across a change in this field.
            #
            # NULL on a history replay, which draws no targets at all -- it runs the project's own
            # commits. Recording an ordering there would describe a sampling decision nobody made.
            "target_ordering": ((RUN_STAMPS.get("family_order") or {}).get("ordering") or ORDERING
                                if target_seed is not None else None),
            "target_seed": target_seed,
            # WHICH GRAMMAR DREW THESE TARGETS. `default` is the population every published corpus
            # figure came from; anything else is a named arm and its rows may not be compared with
            # them. Null on a history replay, which draws no targets at all.
            "target_shapes": shapes if target_seed is not None else None,
            # AND WHICH RULES INSIDE THAT GRAMMAR -- see `grammar_digest`. Null on a history
            # replay, and null on a file resumed from rows written before this field existed,
            # because stamping those rows now would claim rules nobody recorded.
            "grammar_digest": grammar if target_seed is not None else None,
            # HOW MANY TARGETS THERE WERE TO CHOOSE FROM, before any limit was applied. "16 of
            # 1,059" and "16 of 18" are different claims about how hard a project was asked, and a
            # file recording only the 16 reads the same either way. Counted over the whole target
            # population rather than over what a resume had left, so it is stable across resumes.
            # Null on a history replay, which draws no targets at all.
            "targets_available": targets_available,
            # WHAT THE GENERATOR OFFERED AND THIS GUEST WOULD NOT COMPILE. A rejected mutation is
            # discarded rather than written as a row, so without these two the sample would shrink
            # with nothing to show for it -- and that is precisely how a seventh grammar defect
            # would hide, since all six of the last campaign's were found by the rows they cost.
            # Counted AND named: a count says how bad, the ids say which shape, and the shape is
            # what gets fixed. Null on a history replay, which generates no mutations at all.
            "mutations_rejected": len(rejected_targets) if rejected_targets is not None else None,
            "rejected_targets": rejected_targets or None,
            # The same rejections as `[target, kind, index]`, which is what a resume skips by: a
            # label is not a mutation (see `row_key`).
            "rejected_mutations": rejected_mutations or None,
            # HOW THE ALREADY-FAILING SET WAS ESTABLISHED. `--baseline-repeats` decides which tests
            # can report an induced failure at all, so it moves the recall denominator exactly as
            # `target_seed` and `sweep_limit` move the sample -- and a file swept at 1 is not
            # comparable with one swept at 2. `answered` is the count that actually produced
            # results: a repeat that stalled or reached no tests is requested but not answered, and
            # conflating the two is how a sweep believes it repeated N times when it repeated once.
            "baseline_repeats": baseline_repeats,
            # WHICH SOURCES WERE STAGED OUT OF THE TREE THIS ROW WAS MEASURED ON, and which Gradle
            # flags its arms ran under. Null for the projects that stage nothing, which is every
            # project but one. Derived from the project file rather than passed, so no call site can
            # forget them.
            #
            # THEY ARE HERE BECAUSE NO OTHER STAMP CAN CARRY THEM. `selector_digest` hashes the
            # selector and not `bench/`; `commit` and `plugin_commit` are unmoved too. So a
            # project-file change alters what is measured while ageing no results file, and without
            # these two fields a reader cannot tell a figure taken on a staged tree from one taken on
            # the stock checkout, nor a moshi row taken on JUnit 4's own runner from one taken under
            # the Vintage engine.
            #
            # The staged tree is reproducible from `commit` plus this list. The staging commit's own
            # SHA is deliberately NOT recorded: it exists only in this machine's clone and no reader
            # could ever check it out.
            # AN EMPTY LIST, NOT NULL, AND THAT DISTINCTION IS THE WHOLE POINT (fixed on the
            # second attempt). These were written as `... or None`, which collapsed "this project
            # passes no flags" and "this file predates the field" into the same stored value -- so
            # the null tolerance in `flags_changed`, which is correct for a genuinely old file,
            # swallowed a real transition from no flags to flags.
            #
            # It was not theoretical: duckduckgo-android's first sweep wrote `flags: null` while it
            # had none, the project then gained `--no-configure-on-demand`, and the next sweep
            # RESUMED three rows measured without the flag into a file whose later rows would have
            # had it. Null now means one thing only -- the field did not exist when this file was
            # written -- and `[]` means the project genuinely passed nothing.
            "staged_sources": sorted(sources_to_drop(project)),
            "flags": list(project.get("flags") or []),
            "baseline_repeats_answered": baseline_answered,
            # The tests the repeats took OUT of every recall question, and which repeat failed them.
            # One of three is noise; three of three is a genuinely broken test. Named, not counted.
            "baseline_removed": baseline_removed or None,
            "capture_seconds": capture_seconds,
            "baseline_seconds": baseline_seconds,
            # Provenance for the two numbers above, because a reader cannot otherwise tell a figure
            # measured against a warm daemon from one measured against a cold one -- a difference
            # that moved the same project's overhead from +81% to +114%.
            "timing_method": "warm-up run discarded, then capture, then baseline",
            # WHICH MAP THIS FILE'S ROWS SELECTED AGAINST. Every row re-checks it, so a re-capture
            # part-way through a sweep is visible in the rows rather than hidden behind one header
            # value. Absent, never guessed: see map_fingerprint on why not the mtime.
            **{f"map_{k}": v for k, v in zip(("capture_commit", "records", "attributable"),
                                             map_fingerprint(map_dir))},
            # Whether this project started against a live daemon or a reaped one. The corpus driver
            # reaps between projects; a sweep run by hand usually does not, and until now nothing in
            # the file said which. None means the question could not be asked on this machine.
            "daemon_uptime_at_start": daemon_uptime_at_start,
            # THE ARM, THE MACHINE AND WHAT CAPTURE COST, set once by `main`; see RUN_STAMPS.
            **RUN_STAMPS,
            "mutations": findings,
        }, handle, indent=1)
    os.replace(staging, out_path)



# What one invocation stamps on its results file beside the rows: which capture arm it is, the
# machine and build it ran on, what the capture and baseline builds cost, and where its raw evidence
# was archived. Filled by `main`; every field is listed in docs/sweep-data-dictionary.md.
RUN_STAMPS = {}


# THE SELECTOR THE PUBLISHED JAR WAS BUILT FROM. None until `publish_plugin_under_test` runs.
PUBLISHED_SELECTOR_DIGEST = None
# AND THE CHECKOUT COMMIT IT WAS BUILT AT, for the same reason.
PUBLISHED_COMMIT = None


def measured_selector_digest():
    """The digest of the selector these rows were measured with, which is the published jar's.

    Recomputing it from the working tree at every write stamped rows measured by the jar published
    at the start of the sweep with whatever the source had been edited into since -- so the freshness
    check vouched for a selector the rows never ran against. Taken at publish time instead, and a
    tree that has moved since is said out loud: those rows then read as stale, which is the truth.
    """
    current = selector_digest()
    if PUBLISHED_SELECTOR_DIGEST is None:
        return current
    if current != PUBLISHED_SELECTOR_DIGEST:
        print(f"WARNING: the selector source changed during this sweep ({PUBLISHED_SELECTOR_DIGEST} "
              f"published, {current} now). Rows are stamped with the published digest, so they "
              f"will read as stale against the current tree.", flush=True)
    return PUBLISHED_SELECTOR_DIGEST


def publish_plugin_under_test():
    """Publishes the plugin the yoriwake checkout contains, before anything is measured.

    `INIT_SCRIPT_ARGS` points the init script at the checkout's snapshot, resolved from mavenLocal, and
    nothing here ever published it. Every sweep therefore measured whichever jar was last installed
    by hand, while `plugin_commit()` stamped every row with the CURRENT git HEAD -- so a results file
    could say `32cfd27` about a run that used a jar built four hours and five commits earlier.

    That is not hypothetical. okhttp was once measured against a jar from 10:54 while HEAD
    was 32cfd27, and the capture policy under test did not exist in it: the run reported +173% and
    none of the policy's decisions appeared in its log. The number was real and measured nothing
    anyone had asked about.

    Fails the sweep rather than warning. A benchmark that cannot guarantee what it measured is worse
    than no benchmark, because its output is indistinguishable from a real result.
    """
    global PUBLISHED_SELECTOR_DIGEST, PUBLISHED_COMMIT
    # Before the build, so a dirty checkout costs nothing: every row is stamped with its HEAD.
    commit = yoriwake_checkout.commit(short=True)
    print(f"publishing the plugin under test to mavenLocal from {SOURCE} at {commit}", flush=True)
    before = selector_digest()
    code, seconds = run(
        [wrapper(SOURCE), "--quiet", ":plugin:publishToMavenLocal"], SOURCE,
        os.path.join(HERE, "logs", "publish.log"),
    )
    if code != 0:
        raise SystemExit(
            f"could not publish the plugin under test (exit {code}); see bench/logs/publish.log. "
            "Refusing to measure, because the host build would silently resolve whatever jar is "
            "already in mavenLocal and the results would be stamped with this commit regardless."
        )
    if selector_digest() != before:
        raise SystemExit("the selector source changed while the plugin under test was being "
                         "published, so which source the jar holds cannot be established. "
                         "Refusing to measure.")
    if yoriwake_checkout.commit(short=True) != commit:
        raise SystemExit("the yoriwake checkout moved while the plugin under test was being "
                         "published, so which commit the jar holds cannot be established. "
                         "Refusing to measure.")
    PUBLISHED_SELECTOR_DIGEST = before
    PUBLISHED_COMMIT = commit
    print(f"  published in {seconds}s", flush=True)


def argument_parser():
    """`run.py`'s command line, read by its tests as well as by `main`."""
    parser = argparse.ArgumentParser()
    parser.add_argument("project", help="a TOML file under bench/projects/")
    parser.add_argument("--limit", type=int, default=1000)
    parser.add_argument("--replay", type=int, metavar="N",
                        help="replay the last N real commits instead of applying mutations")
    parser.add_argument("--recapture", action="store_true",
                        help="rebuild the map even if this harness captured it, for this arm, "
                             "plugin commit and project commit (any other map is rebuilt anyway)")
    # Without this a non-Platform project runs its whole suite on every selecting arm, so "0 missed"
    # is arithmetic rather than evidence and the sweep cannot tell a fixed selector from a disabled
    # one. mockito is exactly that case.
    parser.add_argument("--class-granularity", action="store_true",
                        help="round a selection up to whole classes (agent-side, JUnit Platform); "
                             "this is not --class-selection")
    parser.add_argument("--class-selection", action="store_true",
                        help="enable class-granular deselection for builds not on the JUnit Platform")
    # A CONSTANT default. A clock-seeded sample would trade one distortion for a corpus whose every
    # figure is unreproducible; the value is recorded in the results file either way.
    parser.add_argument("--target-seed", type=int, default=DEFAULT_TARGET_SEED,
                        help="orders targets within each reach stratum; recorded in the results")
    # THE SHAPE SET IS EXPLICIT, AND THE DEFAULT NEVER GROWS. Every corpus savings and recall figure
    # this repository has published was drawn from `default`, so a shape added there would change
    # the population every future sweep samples and make figures taken before and after
    # incomparable. `recall-arm` is the digest rule's own grammar; it is asked for by name and is
    # recorded in the results file, so a reader can always tell which population a row came from.
    parser.add_argument("--shapes", choices=SHAPE_SETS + (NEUTRAL_SET,), default=DEFAULT_SHAPES,
                        help="which mutation grammar to draw targets from; recorded in the results")
    # A KNOB WITH A DEFAULT, NOT A CONSTANT, because it buys recall honesty with wall clock: each
    # repeat above the first is one more full suite run per project per sweep. See
    # extra_baseline_failures for what the repeats are for and what one costs.
    parser.add_argument("--baseline-repeats", type=int, default=None,
                        metavar="N", help="unmutated runs used to establish which tests already "
                                          "fail; a test failing in any of them is excluded from "
                                          "every recall question (default "
                                          f"{DEFAULT_BASELINE_REPEATS}, or 1 with --only-target)")
    # ONE ROW IN MINUTES, NOT A PROJECT IN HOURS. "Does this row miss again" was a hand-written
    # replay script, because the only way to ask it was a 16-row sweep behind four
    # baseline runs. Named by the row's own `target` label, as a results file records it.
    #
    # NEVER WRITES THE PROJECT'S RESULTS FILE. A spot check stamped with today's digest would start
    # that file fresh and throw away every row a sweep measured; it writes
    # `bench/logs/<project>/only-target.json` instead, from scratch each time. One baseline run
    # by default rather than the project's own count: a missed test is re-run in isolation with and
    # without the mutation anyway, which is the question the extra repeats exist to answer.
    # RECALL, NOT TIMING. Skips what only a timing figure needs: the full arm of a row whose selected
    # arm already ran everything, and a baseline this exact tree already established. The rows are
    # the same recall evidence; `full_seconds` is absent on a skipped arm rather than invented.
    parser.add_argument("--recall-only", action="store_true",
                        help="skip timing-only work: a redundant full arm, and a cached baseline")
    # THE TIMING CAP: a timing sweep times its first N rows in the fixed draw order,
    # both arms paired whatever they decide; later rows run as under --recall-only. Stamped on every
    # row (`timed`) and on the file (`timing_cap`), because it is part of the timing sample.
    # COMPLEMENT GROUND TRUTH, OFF BY DEFAULT. `check` runs the complement beside the
    # full arm on every untimed narrowed row; `auto` runs it alone except on check rows, and is
    # enabled by the maintainer only after a replay of the complement and `controls.py` agree.
    parser.add_argument("--ground-truth", choices=GROUND_TRUTH_MODES, default="full",
                        help="where a narrowed row's ground truth comes from (default full)")
    parser.add_argument("--timing-cap", type=int, default=None, metavar="N",
                        help="time only the first N rows (both arms); later rows are recall-only")
    # THE STOPPING RULE, with `--limit` still the cap: stop drawing targets once N rows narrowed and
    # induced a failure. See counts_toward_narrowed_target for why it cannot bias the recall figure.
    # Stamped on every row and on the file, because it is part of the sample's definition.
    parser.add_argument("--narrowed-target", type=int, default=None, metavar="N",
                        help="stop once N rows narrowed and induced a failure (--limit stays the "
                             "cap); recorded in the results")
    parser.add_argument("--only-target", action="append", metavar="LABEL", default=None,
                        help="measure only this row, by its `target` label; repeatable. Writes "
                             "bench/logs/<project>/only-target.json, never the results file")
    # THE ISOLATED ARM. Records the map with `-Pyoriwake.isolatedCapture`, a fresh test JVM
    # per class, and selects against it exactly as the shared arm does. Its own results file and
    # scratch, because the two arms share one map directory per task and must never share rows.
    parser.add_argument("--isolated-capture", action="store_true",
                        help="record with -Pyoriwake.isolatedCapture; writes "
                             "results/<project>.isolated.json")
    parser.add_argument("--isolated-forks", type=int, default=os.cpu_count() or 1, metavar="N",
                        help="maxParallelForks for the isolated recording only (default: cores)")
    # THE SAME TARGETS, AND ONE GROUND TRUTH FOR BOTH ARMS. The full arm asks which tests the mutated
    # tree fails, which does not depend on how the map was recorded; running it again per arm would
    # double the arm's cost to re-learn the same answer. Every row of the paired file that asked a
    # question is measured again here against the isolated map, and nothing else is.
    parser.add_argument("--pair-with", metavar="RESULTS",
                        help="with --isolated-capture: measure exactly this results file's rows, "
                             "reusing their full arm and baseline")
    parser.add_argument("--keep-exec", action="store_true",
                        help="keep JaCoCo .exec blobs for every row's raw records, not only capture's")
    # ONLY FOR REPRODUCING A SETUP-FORCED PROJECT as the 0.1 sweep measured it: without the reset,
    # a file an earlier test run wrote into the tree forces every row.
    # A PLANTED-MISS CONTROL, run by `controls.py` against a plugin built with a miss
    # planted in it: fixtures only, its own results directory and map stamp, every row stamped.
    parser.add_argument("--control", action="store_true",
                        help="a planted-miss control run: writes results/control/<project>.json")
    # A CONTROL ASKS SOME FAMILIES ONLY, and drawing the others costs rows nobody reads. Never on a
    # measurement, whose population is the whole set.
    parser.add_argument("--families", nargs="+", default=None, metavar="KIND",
                        help="with --control and pool/v1: draw only these families' targets")
    parser.add_argument("--no-untracked-reset", action="store_true",
                        help="leave untracked and ignored files earlier runs wrote in the tree")
    # A SHARDED SWEEP, for CI: one recording job fixes the list, shards measure slices of it, and
    # `merge.py` folds them back into the file one job would have written.
    parser.add_argument("--accepted-out", metavar="PATH",
                        help="record only: capture, baseline, preflight and compile gate, then "
                             "write the accepted target list here and measure no row")
    parser.add_argument("--accepted", metavar="PATH",
                        help="with --shard: measure a slice of this accepted list")
    parser.add_argument("--shard", metavar="K/N", help="which slice of --accepted to measure")
    return parser


def main():
    parser = argument_parser()
    args = parser.parse_args()
    if args.pair_with and not args.isolated_capture:
        parser.error("--pair-with pairs an isolated arm with a shared one; pass --isolated-capture")
    if args.pair_with and (args.replay or args.only_target):
        parser.error("--pair-with measures a mutation sweep's rows; not with --replay or "
                     "--only-target")
    if args.narrowed_target is not None:
        if args.narrowed_target < 1:
            parser.error("--narrowed-target must be at least 1")
        if args.pair_with or args.replay or args.only_target:
            parser.error("--narrowed-target stops drawing mutation targets; --pair-with and "
                         "--only-target name their rows, and --replay draws none")
    if args.timing_cap is not None and (args.timing_cap < 1 or args.recall_only):
        parser.error("--timing-cap needs N of at least 1 and a timing sweep, not --recall-only")
    if bool(args.accepted) != bool(args.shard):
        parser.error("--accepted and --shard go together")
    sharding = args.accepted_out or args.shard
    if sharding and (args.only_target or args.pair_with or args.replay or args.control
                     or args.isolated_capture):
        parser.error("a sharded sweep is a shared-arm mutation sweep")
    # A SHARDED `pool/v1` RUN CANNOT TIME THE ROWS A SINGLE JOB WOULD. The timing cap times the first
    # rows by draw position, a shard counts that position on the recording job's whole list, and a
    # single job skips the targets of a family that already stopped, so the two would time
    # different rows.
    if sharding and args.timing_cap is not None and args.shapes == POOL_SHAPES:
        parser.error(f"--timing-cap times rows by draw position, which a sharded {POOL_SHAPES} "
                     f"run counts differently from a single job; run it unsharded or recall-only")
    if args.shard and args.recapture:
        parser.error("a shard measures against the recording job's map; --recapture would replace it")
    if args.accepted_out and args.shard:
        parser.error("--accepted-out records the list and --shard measures it; one job each")
    shard = parse_shard(args.shard) if args.shard else None
    accepted_list = None
    if shard:
        with open(args.accepted, encoding="utf-8") as handle:
            accepted_list = json.load(handle)
    if args.baseline_repeats is None:
        args.baseline_repeats = 1 if args.only_target else DEFAULT_BASELINE_REPEATS
    if args.only_target:
        args.limit = len(set(args.only_target))
        args.recall_only = True

    with open(args.project, "rb") as handle:
        project = tomllib.load(handle)
    refusal = control_refusal(project, args.shapes, args.control, args.families)
    if refusal:
        parser.error(refusal)
    if args.control:
        if args.isolated_capture or args.replay or args.only_target:
            parser.error("--control is a shared-arm mutation sweep")
        args.recapture = True

    publish_plugin_under_test()

    module = project.get("module") or ""
    # THE GRADLE NAME AND THE DIRECTORY ARE TWO ANSWERS, and only one of them reads files.
    # `module` names tasks, names the map directory (`find_map`) and is what `test_task_ran` looks
    # for in a log. `results_dir` is where the JUnit XML actually lands. They are the same string on
    # every project but Micronaut, where a settings plugin renames `:core` to `:micronaut-core`
    # without moving `core/` -- and reading results from the Gradle name there finds nothing, which
    # a sweep reports as "ground truth never reached the tests" rather than as a path bug.
    results_dir = module_dir(project)
    # ONE READER, BOUND ONCE. Every read below and every reader injected into `isolation.py`
    # goes through it, so no call site can forget that this project writes its
    # output somewhere other than `build/`.
    read_results = results_reader(project)
    task_path = test_task_path(project)
    task_name = test_task_name(project)
    classes_task = test_classes_task(project)
    # THE PROJECT'S OWN COUNT, with the CLI flag as its default -- the same relationship `sweep_limit`
    # has to `--limit`. Resolved once here so no call site below can reach for the raw flag and
    # measure one project at a depth another project asked for.
    repeats = (args.baseline_repeats if args.only_target
               else baseline_repeats_for(project, args.baseline_repeats))
    # THE FIXTURE TREE THIS RUN MEASURES, read once from the tree the copy is about to be taken
    # from, so an edit made while the sweep runs cannot restamp rows measured before it. Null for a
    # project that is not a fixture.
    RUN_STAMPS["fixture_digest"] = fixture_digest(project)
    checkout = checkout_project(project, keep_state=bool(shard))
    # Sampled before this sweep starts a daemon of its own, so it describes what the sweep INHERITED
    # rather than what it built. None where the question cannot be asked; see daemon_uptime_seconds.
    daemon_uptime_at_start = daemon_uptime_seconds()
    arm_name = "isolated" if args.isolated_capture else "shared"
    # A control's scratch, evidence and map stamp are its own, so no measurement reads them.
    arm_label = arm_name + ("-control" if args.control else "")
    results_root = CONTROL_RESULTS if args.control else RESULTS
    out_path, scratch = results_location(results_root, os.path.join(HERE, "logs"), project["name"],
                                         args.shapes, args.isolated_capture, args.control)
    os.makedirs(scratch, exist_ok=True)
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    if shard:
        RUN_STAMPS["shard"] = list(shard)
        out_path = os.path.join(scratch, f"shard-{shard[0]}-of-{shard[1]}.json")
        if os.path.isfile(out_path):
            os.remove(out_path)
    if args.only_target:
        out_path = os.path.join(scratch, "only-target.json")
        if os.path.isfile(out_path):
            os.remove(out_path)

    findings = []
    # CARRIED ACROSS RESUMES, like the rows themselves. The discard ceiling is a rate over the whole
    # sweep of a project, and a resumed invocation that started this list empty would report the
    # rejections of its last leg only -- which is the reading under which a defect rejecting
    # everything looks fine on every resume after the first.
    rejected = []
    # The same rejections keyed per mutation, so a rejected declaration does not reject its
    # namesakes; see `rejected_keys`.
    rejected_mutations = []
    revert_set = args.shapes == REVERT_SET
    pool = args.shapes == POOL_SHAPES
    # `pool/v1` stops per family: at `--narrowed-target` when given, else at its own target.
    family_target = (args.narrowed_target or family_order.FAMILY_TARGET) if pool else None
    forcing = args.shapes == FORCING_SET
    neutral = args.shapes == NEUTRAL_SET
    # `[target, reason]` per dependency revert the gate rejected, carried across resumes likewise.
    forcing_rejections = []
    # `[target, reason, variant, census labels]` per revert the gate rejected, carried across
    # resumes as `rejected` is: the counts by reason and label are published with the figure.
    revert_rejections = []
    grammar = grammar_digest(shapes=args.shapes)
    if os.path.isfile(out_path):
        with open(out_path, encoding="utf-8") as handle:
            stored = json.load(handle)
        # Results belong to the SELECTOR that produced them, which is not the same question as
        # which commit was checked out. Keyed on the commit, a docs change between two legs of one
        # sweep threw away every row measured so far; keyed on the digest, only a real change to the
        # selector's source does. A file with no digest predates this and is not resumable: nothing
        # can establish what selector wrote it.
        if stored.get("selector_digest") not in (None, selector_digest()):
            print(f"results were measured by selector {stored.get('selector_digest')}, "
                  f"not {selector_digest()}; starting fresh", flush=True)
        elif stored.get("selector_digest") is None and stored.get("mutations"):
            print("results carry no selector digest, so the selector that wrote them cannot be "
                  "established; starting fresh", flush=True)
        # AND ROWS BELONG TO THE GRAMMAR THAT DREW THEM. Resuming a `default` sweep under
        # `--shapes recall-arm` would leave both populations in one file under one stamp, and every
        # later reader would compare a figure against rows drawn from a different grammar. Null is
        # a file written before this field existed and is the old behaviour.
        # AND ROWS BELONG TO THE TREE AND THE FLAGS THEY WERE MEASURED UNDER. Both are invisible to
        # every other stamp in this file, so without these branches a sweep would RESUME rows taken
        # before a project-file change and publish both populations under one header. This is the
        # structural half of the move-the-results-file-aside step the project files describe in
        # prose, and it is here because a step a tired implementer skips silently is not a guarantee.
        #
        # NULL IS NOT A MISMATCH, and that is load-bearing rather than cautious. Fifteen projects
        # carried non-empty `flags` when this field was added and no results file recorded them, so
        # comparing against null would have aged every one of those files -- eleven of them fresh and
        # correct -- and charged the corpus a re-sweep to introduce a stamp. Null means "written
        # before this field existed", exactly as it does for `target_shapes` and `selector_digest`
        # above. The consequence is stated rather than hidden: these branches cannot catch the
        # project-file changes already in the tree when they were added, which is why those four
        # projects still have their results file moved aside by hand. They catch every change after
        # them.
        # STAGING TOLERATES NO NULL, AND THE ASYMMETRY WITH `flags` BELOW IS THE POINT. If a project
        # stages sources today and the stored file records none, those rows CANNOT have been staged --
        # the field would have been written -- so they are provably measured on a different tree and
        # resuming them is the mixed-tree resume this branch exists to stop. duckduckgo-android is
        # the one project this reaches, and its sixteen stored rows were taken at the `*` scope the
        # staging removes. `flags` is the opposite case: fifteen projects genuinely WERE measured
        # under flags that predate the stamp, so there null must mean "unknown" rather than "none".
        # Same shape, opposite correct answer; they were once one branch and that was a defect.
        elif staging_changed(stored, project):
            print(f"results were measured with staged sources "
                  f"{list(stored.get('staged_sources') or [])}, not "
                  f"{sorted(sources_to_drop(project))}; starting fresh", flush=True)
        elif flags_changed(stored, project):
            print(f"results were measured under flags {list(stored.get('flags') or [])}, not "
                  f"{list(project.get('flags') or [])}; starting fresh", flush=True)
        # HOW FIRMLY THE ALREADY-FAILING SET WAS ESTABLISHED is a property of the row. Rows taken at
        # two repeat counts in one file describe neither, and no other stamp can witness the change:
        # `selector_digest` hashes the selector rather than `bench/`, and `commit` does not move
        # for a project-file edit. Null tolerance is `flags`'s, not `staged_sources`'s -- see
        # `baseline_repeats_changed`.
        elif baseline_repeats_changed(stored, project, args.baseline_repeats):
            print(f"results established their already-failing set over "
                  f"{stored.get('baseline_repeats')} unmutated run(s), not {repeats}; "
                  f"starting fresh", flush=True)
        elif stored.get("target_shapes") not in (None, args.shapes):
            print(f"results were drawn from the {stored.get('target_shapes')!r} grammar, "
                  f"not {args.shapes!r}; starting fresh", flush=True)
        elif grammar_changed(stored, grammar):
            print(f"results were drawn under grammar {stored.get('grammar_digest')}, "
                  f"not {grammar}; starting fresh", flush=True)
        elif fixture_changed(stored, RUN_STAMPS.get("fixture_digest")):
            print(f"results were measured on fixture tree {stored.get('fixture_digest')}, "
                  f"not {RUN_STAMPS.get('fixture_digest')}; starting fresh", flush=True)
        # A SETUP-FORCED PROJECT IS RE-RUN FROM ITS FIRST ROW once its setup is fixed: none of its
        # rows counts, so none is resumed.
        elif stored.get("project_outcome") == SETUP_FORCED:
            print(f"results stopped as {SETUP_FORCED} "
                  f"({(stored.get('setup_forced') or {}).get('reason')}); starting fresh",
                  flush=True)
        else:
            findings = stored.get("mutations", [])
            rejected = list(stored.get("rejected_targets") or [])
            rejected_mutations = [list(key) for key in stored.get("rejected_mutations") or []]
            revert_rejections = list((stored.get("revert") or {}).get("rejections") or [])
            forcing_rejections = list((stored.get("forcing") or {}).get("rejections") or [])
            grammar = resumed_grammar(stored, findings, grammar)
    # Per mutation, not per label: see `row_key`.
    done = {row_key(finding) for finding in findings}
    if args.accepted_out and findings:
        print(f"REFUSING: {out_path} already holds {len(findings)} row(s), which a recording job "
              f"would count toward its list; record into a fresh results file", flush=True)
        return 1

    # One instrumented full run builds the map; everything after selects against it. Reused across
    # invocations by default, because capture plus an uninstrumented baseline is eight minutes on a
    # medium project and a sweep that pays that on every resume is a sweep nobody resumes.
    previous = {}
    # A shard's capture and baseline are the recording job's, whose header is the results file.
    header_path = (results_location(results_root, os.path.join(HERE, "logs"), project["name"],
                                    args.shapes)[0] if shard else out_path)
    if os.path.isfile(header_path):
        with open(header_path, encoding="utf-8") as handle:
            previous = json.load(handle)

    select_flags = ["-Pyoriwake.internal.classSelection"] if args.class_selection else []
    # The class-granularity arm. A DIFFERENT MECHANISM from `--class-selection` above despite the
    # similar name:
    # that one is the plugin's Gradle-filter path for builds off the JUnit Platform, this one is
    # the agent's round-a-selection-up-to-its-class mode on the Platform. Both arms of a pair must
    # differ by something the run can show, so this reaches the guest as a real `-P`.
    if args.class_granularity:
        select_flags.append("-Pyoriwake.internal.select.classGranularity")
    # On the selecting arm too, as a user who left the flag on would have it: a selecting run never
    # isolates, so this shows that. Never on the full arm, whose seconds are every saving's
    # denominator and must stay a plain shared-JVM run.
    if args.isolated_capture:
        select_flags.append(ISOLATED_CAPTURE_FLAG)

    paired_rows = None
    if args.pair_with:
        with open(args.pair_with, encoding="utf-8") as handle:
            paired = json.load(handle)
        if paired.get("selector_digest") != measured_selector_digest():
            print(f"the paired results were measured by selector {paired.get('selector_digest')}, "
                  f"not {measured_selector_digest()}; their ground truth describes another "
                  f"selector's rows. Refusing.", flush=True)
            return 1
        paired_rows, duplicates = paired_ground_truth(paired.get("mutations") or [])
        if duplicates:
            print(f"the paired results hold {len(duplicates)} mutation(s) more than once, so which "
                  f"row is this arm's ground truth cannot be established. Refusing:", flush=True)
            for key, count in sorted(duplicates.items(), key=str):
                print(f"    {key}: {count} rows", flush=True)
            return 1
        if not paired_rows:
            print("the paired results hold no row with a ground truth to reuse; refusing to "
                  "measure an isolated arm of nothing", flush=True)
            return 1
        args.limit = len(paired_rows)
        baseline_seconds_paired = paired.get("baseline_seconds")
    RUN_STAMPS.update({
        "capture_mode": arm_name,
        "capture_max_parallel_forks": args.isolated_forks if args.isolated_capture else None,
        "paired_with": os.path.relpath(args.pair_with, ROOT) if args.pair_with else None,
        # THIS INVOCATION'S sample rule and arm set; each row carries its own `narrowed_target`,
        # since a resumed file holds rows from several invocations. `recall_only` true means a
        # forced row ran once and has no `full_seconds`: no time-saved figure is read from this file.
        "narrowed_target": args.narrowed_target,
        "recall_only": args.recall_only,
        "control": args.control,
        # The families a control drew from; None is every family of the set.
        "control_families": args.families,
        # None: every row of a timing sweep is timed (and every row before the cap existed).
        "timing_cap": args.timing_cap,
        # This invocation's `--ground-truth`; each row stamps the truth it got.
        "ground_truth_mode": args.ground_truth,
        # Set when a check row disagreed and `auto` turned the complement off for the project.
        "complement_switched_off": None,
        # `setup-forced` replaces it when the preflight or three forced rows stop the project.
        "project_outcome": "measured",
        "setup_forced": None,
        "untracked_reset": not args.no_untracked_reset,
        "environment": {**sweep_data.machine(),
                        "gradle_version": sweep_data.gradle_version(wrapper(checkout)),
                        "daemon_java_version": project.get("daemon_java_version"),
                        "daemon_jvmargs": project.get("daemon_jvmargs"),
                        "checkout_schema_version": sweep_data.checkout_schema_version(SOURCE)},
        # Carried from the file this run resumes, and replaced when this run captures.
        **{key: previous.get(key) for key in ("capture_arm", "baseline_arm",
                                             "capture_touch_all_reasons", "capture_facts",
                                             "capture_raw_bytes_kept", "capture_raw_archive")},
    })

    assert_owns(checkout)
    # STAGED BEFORE ANYTHING IS BUILT OR REGISTERED, and validated as part of it: a refusal is a
    # non-zero exit a reader sees, rather than a raise inside the atexit handler, which cannot act
    # on one. Safe here because `checkout_project` above has just forced the tree to the pinned
    # commit -- so this validates the pinned files, not whatever a killed previous run left behind.
    staging_commit, staged_sources = stage_checkout(checkout, project)
    # Registered as soon as the checkout is known, so an interrupt at any later point still puts
    # the tree back. See _restore_on_exit.
    _restore_on_exit(checkout)
    if not restore(checkout):
        # ACTED ON RATHER THAN PRINTED PAST. `restore` shouts and returns False when the tree is
        # still dirty after four attempts, and everything below measures that tree: the capture, and
        # then every row's arms. Continuing is how a previous run's mutation gets attributed to
        # whatever this one is testing, which this repository has done twice.
        return 1
    # BEFORE ANY BUILD: the files every row starts from, with what the capture writes added below.
    prepared = untracked_files(checkout, project)
    snapshot_path = os.path.join(scratch, UNTRACKED_SNAPSHOT)
    # What the complement is priced against: the unmutated suite's size and per-class
    # time, from the same run that establishes the already-failing set, keyed like its cache.
    profile_path = os.path.join(scratch, SUITE_PROFILE)
    profile_key = baseline_key(project)
    # BEFORE THE CAPTURE, which is the only cheap moment to learn the warm-up asks for a task this
    # guest does not have. One configuration pass against a capture that costs hours; see
    # `assert_classes_task` for the 31 silent failures that bought this.
    assert_classes_task(checkout, project, os.path.join(scratch, "classes-task.log"), gradle)
    map_dir = find_map(checkout, module, task_name)
    capture_seconds = previous.get("capture_seconds")
    # None means "not established yet", which is different from "no tests fail".
    already_failing = None
    # Initialised here, not only inside the two branches that establish the set: exactly one
    # of them runs, and a path that reached `write_results` without either would otherwise
    # raise instead of recording that no repeats were bought.
    baseline_answered, baseline_removed = None, None
    baseline_seconds = previous.get("baseline_seconds")
    # THE OTHER ARM'S MAP IS NOT THIS ARM'S. Both arms write one map directory per task, so the map
    # on disk is whichever arm captured last; reusing it would measure one arm's selection against
    # the other's recording. And a map of another schema is refused by the agent, so every row
    # would force.
    fits, why = sweep_data.map_fits_arm(map_dir, args.isolated_capture)
    wanted_schema = sweep_data.checkout_schema_version(SOURCE)
    found_schema = sweep_data.schema_version(map_dir)
    if fits and map_dir and wanted_schema is not None and found_schema != wanted_schema:
        fits, why = False, (f"the map on disk has schema {found_schema} and the checkout writes "
                            f"{wanted_schema}")
    # THIS RUN'S OWN CAPTURE, or none. A map another writer left -- qualify.py, an older plugin
    # commit, another pinned commit of the project -- is not one this run's rows may be measured on.
    wanted_stamp = sweep_data.capture_stamp(arm_label, plugin_commit(), project["commit"])
    if fits and map_dir:
        fits, why = sweep_data.stamp_fits(map_dir, wanted_stamp)
    if shard and (not fits or map_dir is None):
        print(f"REFUSING: a shard measures against the recording job's map, and "
              f"{why if map_dir else 'there is none'}", flush=True)
        return 1
    if not fits and not args.recapture:
        print(f"  {why}; re-capturing", flush=True)
        args.recapture = True

    if map_dir is None or args.recapture:
        # RESET, NOT MERGE, and the distinction is the whole value of the flag. The
        # plugin's capture ADDS to whatever the map directory already holds, so re-capturing over an
        # existing map leaves a blend of two generations. Measured on moshi the first time this was
        # used for its purpose: the map came back holding 1,292 `[engine:junit-vintage]` ids from the
        # new capture AND 1,287 `[engine:junit4]` ids from the runner Vintage had just replaced.
        #
        # That is exactly the state `--recapture` exists to prevent. The flag was asked for
        # because "both checkouts already hold a complete coverage.tsv keyed to the runner
        # Vintage replaces, so without it the sweep selects against test ids that no longer exist" --
        # and the flag as written did not achieve it, because re-running capture is not the same as
        # discarding what the old one wrote. Stale ids cannot match a discovered test, so they do not
        # cause a skip; what they do is make `map_records` and `map_attributable` describe a map that
        # is half fiction, and leave the project's own evidence unreadable.
        # EVERY map directory for this task, not just a merged one. Gating this on `find_map`'s
        # answer was a hole: it requires `coverage.tsv`, so a captured-but-never-merged directory --
        # iceberg's and duckduckgo-android's exact state -- reads as absent, the discard is skipped,
        # and the capture writes into the previous run's `raw/` tree for the decode to merge both.
        if args.recapture:
            for stale in map_dirs_for_task(checkout, module, task_name):
                merged = os.path.isfile(os.path.join(stale, "coverage.tsv"))
                print(f"  discarding the existing "
                      f"{'map' if merged else 'UNMERGED capture'} at {os.path.basename(stale)} so "
                      f"this capture is not merged into another run's records", flush=True)
                remove_tree(stale)
            map_dir = None
        print(f"capturing {project['name']} at {project['commit'][:8]}", flush=True)

        # One discarded full run before either arm is timed. Without it the arms are not comparable
        # and the published overhead is inflated by an unknown amount: capture runs first and pays
        # for a cold Gradle daemon, a cold Kotlin compiler daemon and a cold page cache, while the
        # baseline that follows inherits all three warm.
        #
        # The size of the effect is not hypothetical. Two CI sweeps of the same detekt module
        # measured capture at 428.5s and 431.2s -- 0.6% apart -- while their baselines came in at
        # 237.3s and 201.3s, 18% apart, turning the same project's overhead into +81% and +114%.
        # The instrumented arm was stable; the arm that went second was not.
        #
        # This is the defect the replay path already fixed with a compile warm-up, left in place
        # here because the capture/baseline pair was never looked at again. A compile warm-up is not
        # enough for this pair: --rerun-tasks recompiles in both arms anyway, so what the second arm
        # actually inherits is a warm daemon, which only running the task can produce.
        warmup_log = os.path.join(scratch, "warmup.log")
        gradle(checkout, project,
               [task_path, "--rerun-tasks"], warmup_log)
        # Checked, not assumed. A warm-up that never reached the tests leaves the daemon exactly as
        # cold as before, so capture pays the startup cost and baseline does not -- which is the
        # artefact this block exists to remove, silently reintroduced while the printout claims it
        # was handled. Refusing to measure beats publishing a number whose method is not what it says.
        if not test_task_ran(warmup_log, module, task_name):
            print("the warm-up run never reached the tests, so the timing arms would not be "
                  "comparable; aborting", flush=True)
            return 1

        # A RE-CAPTURE REPLACES THE MAP, and this is the last moment before the capture writes one.
        # It used to capture into the map that was already there, and the decode merge keeps every
        # record the new capture does not re-observe by id -- which is not the same set as "tests
        # that no longer exist". A test skipped for an environment reason, and every class-scoped
        # window for a class the run did not reach, survive carrying the class lists they were
        # captured under.
        #
        # That turns a re-capture into a merge of two instrumentation scopes, silently: the map's
        # own `effective-scope` is stamped with the newer one, because a full capture is taken to
        # have re-observed everything. Measured while re-capturing the five projects
        # whose scope had collapsed to `*`. grpc-java came back holding 1,536 records under a
        # retired `[engine:junit4]` id scheme beside 1,536 fresh `[engine:junit-vintage]` ones --
        # the same suite twice, so its audit read 3,072 tests and put this plugin's own agent
        # classes in the most-reached list at exactly 50.0%. armeria kept 213 such rows, micrometer 4.
        #
        # DELETED HERE AND NOT EARLIER. The warm-up above runs without the init script, so on most
        # projects it writes no map -- but a fixture that applies the plugin in its own build file
        # writes one anyway, and deleting before the warm-up would let that run repopulate the
        # directory this capture is supposed to start from empty. It also means an aborted warm-up
        # no longer destroys a map for nothing.
        #
        # NOT BACKED UP FIRST, deliberately. An abort between here and a finished map leaves the
        # project with no map, which costs a re-capture and nothing else: `find_map` then answers
        # None and the next run captures from empty. Putting the old map back would hand a later
        # run the contaminated one this flag exists to discard, which is the failure that matters.
        #
        # UNGUARDED, and said out loud rather than papered over: `main()` has no test, and a test
        # that deleted a temporary directory would still pass with this call removed -- the vacuous
        # guard this project keeps finding. What would catch a regression is a capture whose map
        # holds records it did not write, which is the residue count the benchmark notes keep.
        if map_dir is not None:
            remove_tree(map_dir)

        # The isolated arm records a fresh JVM per test class, with as many at a time as the
        # machine has cores; see isolated-forks.init.gradle. Stamped as `capture_max_parallel_forks`.
        isolated_args = ([ISOLATED_CAPTURE_FLAG, "-I", sweep_data.ISOLATED_FORKS_INIT,
                          f"-Dyoriwake.harness.maxParallelForks={args.isolated_forks}"]
                         if args.isolated_capture else [])
        capture_log = os.path.join(scratch, "capture.log")
        capture_started = time.time()
        capture_code, capture_seconds = gradle(
            checkout, project,
            [*INIT_SCRIPT_ARGS, "-Pyoriwake.internal.loaded", task_path,
             "--rerun-tasks"] + isolated_args + TIMING_ARM_FLAGS,
            capture_log)
        # A killed capture leaves a partial map and no failure banner, so every row after it would
        # measure a map nobody meant to build. Said as itself rather than as "never reached the
        # tests", which sends the reader to look at the wrong thing.
        if capture_code == STALLED:
            print("capture STALLED and was killed; aborting rather than measuring a partial map",
                  flush=True)
            return 1
        if not test_task_ran(os.path.join(scratch, "capture.log"), module, task_name):
            print("capture never reached the tests; aborting", flush=True)
            return 1
        # WHAT THE CAPTURE WROTE IS PART OF THE MAP: it hashed those files, so they stay. What the
        # baselines and the rows write after this point is what the reset removes.
        write_untracked_snapshot(snapshot_path, wanted_stamp,
                                 prepared | untracked_files(checkout, project))

        # What the capture cost and what it saw, before anything else runs against its map. The raw
        # records are kept whole, `.exec` included: they are what every later question about this
        # map is answered from, and the next instrumented build clears them.
        capture_map = find_map(checkout, module, task_name)
        RUN_STAMPS.update({
            "capture_arm": arm_stats(capture_log),
            "capture_touch_all_reasons": sweep_data.touch_all_reasons(capture_map),
            "capture_facts": map_facts(capture_map),
            "capture_raw_bytes_kept": sweep_data.keep_raw(capture_map, os.path.join(
                scratch, "raw", "capture"), capture_started, with_exec=True),
        })
        # ARCHIVED NOW AND ONCE, not with the rest at the end: the capture's raw records are the
        # largest thing an arm keeps, several times larger again for the isolated arm, and they do
        # not change until the next capture. Kept compressed only.
        capture_raw = os.path.join(scratch, "raw", "capture")
        if os.path.isdir(capture_raw):
            archive = os.path.join(HERE, "logs", "evidence",
                                   f"{project['name']}-{arm_label}-capture-raw.tar.gz")
            RUN_STAMPS["capture_raw_archive"] = {
                "archive": os.path.relpath(archive, ROOT),
                "bytes": sweep_data.archive_evidence(archive, [(capture_raw, "raw")])}
            shutil.rmtree(capture_raw, ignore_errors=True)
        if paired_rows is not None:
            # NO BASELINE OF ITS OWN. Every row reuses the paired row's ground truth, whose induced
            # set already has the paired baseline's already-failing tests taken out, and its
            # uninstrumented baseline is the one denominator both arms' recording cost is read
            # against.
            baseline_seconds = baseline_seconds_paired
            already_failing = NOTHING_FAILING
            RUN_STAMPS["baseline_arm"] = None
            print(f"  isolated capture {capture_seconds}s; baseline and ground truth from "
                  f"{os.path.basename(args.pair_with)}", flush=True)
        else:
            # The baseline is uninstrumented BY DESIGN -- it is the timing arm -- and on a fixture that
            # applies the plugin in its own build file that means it decodes without recording, which
            # discards the loaded-class union the capture just wrote. Every selecting run afterwards
            # then had no union, so the rule that depends on one could not fire and the sweep measured a
            # switched-off rule while reporting zero missed failures. Found by breaking that rule on
            # purpose and watching nothing change.
            baseline_map = find_map(checkout, module, task_name)
            preserved = backup_map(baseline_map, scratch, "map-before-baseline") if baseline_map else None
            baseline_started = time.time()
            baseline_log = os.path.join(scratch, "baseline.log")
            baseline_code, baseline_seconds = gradle(
                checkout, project,
                [task_path, "--rerun-tasks"] + TIMING_ARM_FLAGS,
                baseline_log)
            # The baseline is the denominator of every savings figure AND the source of the
            # already-failing set. A killed one gives a wrong number for both.
            if baseline_code == STALLED:
                print("the baseline STALLED and was killed; aborting rather than dividing by it",
                      flush=True)
                return 1
            # What fails with NOTHING mutated. Subtracted from every ground-truth run below, because a
            # test that fails on a clean tree did not fail because of the mutation, and asking whether
            # selection caught it asks the wrong question.
            baseline = read_results(checkout, results_dir, baseline_started, task_name,
                                    baseline_log)
            # THE SAME THREE ANSWERS THE REPEATS GET. `results_of` returns (None, None) for "this run
            # never reached the tests", and `or set()` turned that into "nothing failed" -- so a baseline
            # that compiled but ran nothing, or wrote its results somewhere this could not read, left an
            # EMPTY already-failing set and every pre-existing failure was then counted as induced by the
            # first mutation that came along. The repeats were given this check on review; the timed
            # baseline they were added to kept the hole, one line up, and a STALLED exit was the only
            # non-answer caught.
            if baseline[0] is None:
                print("the baseline reached no tests and established nothing; aborting rather than "
                      "treating an empty already-failing set as a fact", flush=True)
                return 1
            # The timed baseline above is repeat one; its wall clock is the denominator of every savings
            # figure, so the repeats run AFTER it and contribute only to the already-failing set.
            repeated, baseline_answered, baseline_removed = extra_baseline_failures(
                checkout, project, task_path, results_dir, task_name, scratch,
                gradle=gradle, results_of=read_results, repeats=repeats,
                flags=TIMING_ARM_FLAGS)
            already_failing = failing_union([baseline, repeated])
            write_suite_profile(profile_path, profile_key, len(baseline[0]),
                                class_seconds(checkout, results_dir, baseline_started, task_name,
                                              build_dir(project)))
            if already_failing[1]:
                print(f"  {len(already_failing[1])} tests already fail on the unmutated tree; "
                      f"they are excluded from every recall question below", flush=True)
            if preserved:
                restore_map(preserved, baseline_map)
            RUN_STAMPS["baseline_arm"] = arm_stats(os.path.join(scratch, "baseline.log"))
            print(f"  capture {capture_seconds}s, uninstrumented baseline {baseline_seconds}s "
                  f"(both after a discarded warm-up run)", flush=True)
        map_dir = find_map(checkout, module, task_name)
        if map_dir:
            sweep_data.write_capture_stamp(map_dir, wanted_stamp)
    else:
        print(f"reusing the map at {os.path.basename(map_dir)} "
              f"(pass --recapture to rebuild)", flush=True)
        if read_untracked_snapshot(snapshot_path, wanted_stamp) is None:
            print(f"  no untracked-file snapshot saved with this capture; the files present now "
                  f"({len(prepared)}) are what every row starts from", flush=True)
            write_untracked_snapshot(snapshot_path, wanted_stamp, prepared)
    snapshot = (None if args.no_untracked_reset
                else read_untracked_snapshot(snapshot_path, wanted_stamp))

    # CACHED UNDER --recall-only, keyed on everything that decides which tests fail unmutated: the
    # pinned commit, the staged tree and the flags. A spot check otherwise pays a full suite run --
    # eight minutes on duckduckgo-android -- to re-learn an answer that has not changed. A cached
    # set from fewer repeats than asked for is not reused.
    cache_path = os.path.join(scratch, "baseline-cache.json")
    cache_key = baseline_key(project)
    from_cache = False
    # A paired arm establishes nothing: its rows' induced sets come from the paired file. Marked as
    # from a cache so this arm's empty set is never written as this tree's answer.
    if paired_rows is not None:
        already_failing, from_cache = NOTHING_FAILING, True
        baseline_seconds = baseline_seconds_paired
    if already_failing is None and (args.recall_only or shard):
        cached = read_baseline_cache(cache_path, cache_key, repeats)
        if cached is not None:
            already_failing, from_cache = cached, True
            print(f"  reusing the already-failing set for this tree ({len(already_failing[1])} tests) "
                  f"from {os.path.basename(cache_path)}", flush=True)
    if already_failing is None and shard:
        print("REFUSING: a shard compares against the recording job's baseline, and no "
              "already-failing set for this tree was handed over", flush=True)
        return 1
    if already_failing is None:
        # The map was reused, so no baseline ran, so nothing yet says which tests fail on their own.
        # One unmutated run establishes it, and it is worth the four minutes: without it a flaky
        # test that happened to pass during capture reads as a failure selection missed, which is
        # the most alarming thing this harness can print and would be false.
        print("  establishing which tests already fail, with nothing mutated", flush=True)
        started = time.time()
        # WITHOUT THE CONFIGURATION CACHE, as every arm runs, so the outcome listener attaches and
        # the set can be read per method. It changes which tests fail no more than it does there.
        already_failing_log = os.path.join(scratch, "already-failing.log")
        code, _ = gradle(checkout, project,
                         [task_path, "--rerun-tasks"] + TIMING_ARM_FLAGS,
                         already_failing_log)
        if code == STALLED:
            print("the already-failing run STALLED and was killed; aborting", flush=True)
            return 1
        reran = read_results(checkout, results_dir, started, task_name, already_failing_log)
        if reran[0] is None:
            print("the already-failing run reached no tests and established nothing; aborting "
                  "rather than treating an empty set as a fact", flush=True)
            return 1
        repeated, baseline_answered, baseline_removed = extra_baseline_failures(
            checkout, project, task_path, results_dir, task_name, scratch,
            gradle=gradle, results_of=read_results, repeats=repeats,
            flags=TIMING_ARM_FLAGS, stem="already-failing-repeat")
        already_failing = failing_union([reran, repeated])
        write_suite_profile(profile_path, profile_key, len(reran[0]),
                            class_seconds(checkout, results_dir, started, task_name,
                                          build_dir(project)))
        print(f"  {len(already_failing[1])} tests already fail on the unmutated tree; "
              f"they are excluded from every recall question below", flush=True)
    if not from_cache:
        write_baseline_cache(cache_path, cache_key, repeats, already_failing)

    # The WHOLE map, not just coverage.tsv.
    #
    # This snapshot exists so every mutation row starts from the same map, and it only ever copied
    # the records. Everything else the map carries -- the capture-commit stamp, the scope, and now
    # the recorded constant values -- leaked from one row into the next. A forced row captures, so
    # the map ends up describing the MUTATED source; the harness then restores the source and not
    # the map, and the next row compares restored code against a mutated recording.
    #
    # Measured: every demo-kotlin Constants row stayed at 9/9 through a whole sweep while the same
    # change narrowed to 1/9 when run by hand a minute later. The harness was measuring itself.
    #
    # The replay path deliberately does NOT do this: there the map carrying forward is the point,
    # because it models a real sequence of commits.
    map_backup = os.path.join(scratch, "map-before-row")
    if map_dir:
        backup_map(map_dir, scratch, "map-before-row")
        # The loaded-class union alongside the map, because the artifact is the only copy that
        # leaves the runner. Without it "would the loaded-class rule have helped here" cannot be
        # asked of a finished sweep, which is how that rule's value stayed an inference for so
        # long. It is provenance, not something anything is skipped on.
        for name in ("loaded.txt", "loaded-provenance", "loaded-scope"):
            source = os.path.join(map_dir, name)
            if os.path.isfile(source):
                shutil.copyfile(source, os.path.join(scratch, name))
    else:
        # WHICH OF THE TWO, because they are different findings with different owners. A capture
        # that never ran is usually the guest's; a capture that ran and was never merged is ours.
        unfinished = unfinished_map(checkout, module, task_name)
        if unfinished:
            records = len(glob.glob(os.path.join(unfinished, "raw", "*", "*")))
            print(f"  the map at {unfinished} was CAPTURED BUT NEVER MERGED: {records} raw record "
                  f"file(s) and no coverage.tsv, so the decode did not finish. Selection will force "
                  f"every run, and this is a defect of this tool rather than a property of the "
                  f"project. A known cause is `org.gradle.configureondemand=true`, under which the "
                  f"decode finalizer is left out of the task graph.", flush=True)
        else:
            print("  no map for this module; selection will force every run", flush=True)

    if args.replay:
        if map_dir is None:
            print("history replay needs a map to select against; aborting", flush=True)
            return 1
        # REFUSED RATHER THAN MEASURED WRONG. Replay walks real history through `checkout_commit`,
        # which does its own `git checkout --force <commit>` and so leaves the staging commit behind
        # -- every replayed commit would be measured on the UNSTAGED tree, deriving the `*` scope
        # this staging exists to prevent, with nothing in the output saying so. Staging each
        # historical commit is a different feature.
        if sources_to_drop(project):
            print(f"{project['name']} stages {len(sources_to_drop(project))} source(s) out of its "
                  f"tree, and history replay checks out other commits, which would silently "
                  f"un-stage them. Refusing: replay and `drop_empty_sources` cannot both be "
                  f"honoured here.", flush=True)
            return 1
        findings = sweep_history(checkout, project, module, scratch, map_dir, findings, done,
                                 args.replay, out_path, capture_seconds, baseline_seconds,
                                 already_failing,
                                 select_flags, results_dir)
        # Back to the pinned commit. A harness that leaves a checkout on some historical revision
        # makes the NEXT run measure a tree nobody asked for.
        checkout_commit(checkout, project["commit"])
        checked = [f for f in findings if f.get("status") == "checked"]
        print(f"\n{len(checked)} commits asked a recall question, "
              f"{sum(len(f['missed']) for f in checked)} MISSED "
              f"({sum(len(f.get('order_dependent', [])) for f in checked)} of them "
              f"order-dependent), "
              f"{sum(len(f.get('nondeterministic', [])) for f in checked)} nondeterministic, "
              f"{sum(len(f.get('ran_but_passed') or []) for f in checked)} ran but passed, "
              f"{sum(f['full_failures'] for f in checked)} genuine failures "
              f"({sum(1 for f in findings if f.get('status') == 'cache-hit')} excluded as cache "
              f"hits, {len(findings) - len(checked)} in total asked nothing)",
              flush=True)
        write_results(out_path, project, module, capture_seconds, baseline_seconds, findings,
                      map_dir=map_dir, daemon_uptime_at_start=daemon_uptime_at_start)
        archive_run_evidence(project, arm_label, scratch, out_path, map_dir, args.shapes)
        return 0

    reach = covered = None
    revert_by_sha, stratum_of = {}, {}
    dependency_by_sha = {}
    if forcing:
        # THE GENERATOR FAMILIES' TARGETS AND THE VERSION-ONLY COMMITS, families taken in turn. No
        # reach or coverage order: the change's paths decide the outcome, not which class it is.
        dependency_order, dependency_not_eligible = forcing_mutations.dependency_candidates(
            checkout, project, args.target_seed)
        dependency_by_sha = {c["sha"]: c for c in dependency_order}
        all_targets = (forcing_mutations.mutation_targets(checkout, project)
                       + [(c["sha"], "dependency-revert", 0) for c in dependency_order])
        targets_available = len(all_targets)
        ordered = forcing_mutations.draw_order(pending_targets(all_targets, findings),
                                               args.target_seed)
        provenance = {}
        RUN_STAMPS["forcing"] = {
            "population": forcing_mutations.population(project.get("flags")),
            "rows_per_family": forcing_mutations.ROWS_PER_FAMILY,
            "dependency_reverts": {"window": revert.SWEEP_WINDOW,
                                   "eligible": len(dependency_order),
                                   "not_eligible": dependency_not_eligible},
            "rejections": forcing_rejections}
        print(f"  {FORCING_SET} ({RUN_STAMPS['forcing']['population']}): "
              f"{dict(Counter(kind for _, kind, _ in all_targets))}; up to "
              f"{forcing_mutations.ROWS_PER_FAMILY} row(s) a family", flush=True)
    elif neutral:
        all_targets = neutral_targets(checkout, project)
        targets_available = len(all_targets)
        ordered, provenance = pending_targets(all_targets, findings), {}
    elif revert_set:
        # THE ELIGIBLE COMMITS OF THE CENSUS WINDOW, in their draw order, each checked to revert
        # cleanly at the pin. Not eligible is counted by reason and takes no slot.
        revert_order, not_eligible = revert.candidates(checkout, project, args.target_seed)
        revert_by_sha = {c["sha"]: c for c in revert_order}
        stratum_of = {revert.label(c): c["stratum"] for c in revert_order}
        all_targets = [(c["sha"], "revert", 0) for c in revert_order]
        targets_available = len(all_targets)
        ordered = [t for t in all_targets if not keyed_in(done, f"revert::{t[0]}", "revert", 0)]
        provenance = {}
        RUN_STAMPS["revert"] = {
            "window": revert.SWEEP_WINDOW, "census_classifier": revert.census.CLASSIFIER,
            "eligible": len(revert_order), "not_eligible": not_eligible,
            "strata": dict(Counter(c["stratum"] for c in revert_order)),
            "rejections": revert_rejections}
        print(f"  {len(revert_order)} commit(s) of the last {revert.SWEEP_WINDOW} revert cleanly at the "
              f"pin ({RUN_STAMPS['revert']['strata']}); not eligible: {not_eligible or 'none'}",
              flush=True)
    else:
        # THE WHOLE POPULATION, BEFORE THE RESUME FILTER, so `targets_available` means the same thing
        # on a first pass and on a resume. Filtering first would make it shrink every time the file grew.
        all_targets, repeated_targets = distinct_targets(
            pool_mutations.mutation_targets(checkout, project) if pool
            else mutation_targets(checkout, project, args.shapes))
        if repeated_targets:
            print(f"  {repeated_targets} target(s) were drawn twice through overlapping source roots "
                  f"and are measured once", flush=True)
        targets_available = len(all_targets)
        # Filtered before the limit is applied: applied first, a resumed sweep spends its whole
        # budget re-skipping rows it already has and measures nothing new.
        pending = pending_targets(all_targets, findings)
        if args.families:
            pending = [target for target in pending if target[1] in args.families]
        # SPANNING BEFORE ANSWERABLE, and the two are different questions. `mutation_targets` returns
        # targets in sorted path order, so the first N are `Abstract*` -- hubs, which force a full run,
        # which makes recall look perfect and narrowing look terrible in the same breath. This spreads
        # the sample across the map's own reach counts first; `by_coverage` then buckets it, and neither
        # stage disturbs the other's ordering.
        reach = reach_counts(map_dir)
        pending = by_reach(pending, reach, args.target_seed)
        # Answerable targets first. Without this the limit is spent on classes nothing loads, and the
        # sweep reports zero missed failures out of zero induced ones -- which is not a safety result.
        covered = covered_classes(map_dir)
        ordered, provenance = (pool_mutations.by_coverage if pool else by_coverage)(pending, covered)
        if pool:
            # FAMILIES TAKE TURNS, so every family gets its first draw before any gets a second,
            # each family's targets in the order above. A family stops at its own target or cap.
            model = family_order.load_model()
            sizes = ({t[0]: os.path.getsize(os.path.join(checkout, t[0])) for t in ordered}
                     if model else None)
            ordered = family_order.draw_order(ordered, args.target_seed, reach, sizes, model,
                                              project["name"])
            RUN_STAMPS["family_order"] = {
                "ordering": family_order.ordering_name(model),
                "learned": sorted((model or {}).get("adopted") or []),
                "target": family_target, "cap": args.limit}
            print(f"  {RUN_STAMPS['family_order']['ordering']}: each family stops at "
                  f"{family_target} row(s) narrowed and induced, or at {args.limit} row(s)",
                  flush=True)
        if covered:
            counts = Counter(provenance.values())
            print(f"  {len(covered)} classes covered by the map; targets: "
                  f"{counts['covered']} covered, {counts['unknown']} unresolved, "
                  f"{counts['uncovered']} uncovered (answerable ones run first)", flush=True)
        else:
            print("  no map to order targets by, so every target is unknown rather than uncovered",
                  flush=True)
    classes_args = [f":{module}:{classes_task}" if module else classes_task]
    write_kwargs = dict(target_seed=args.target_seed, map_dir=map_dir,
                        daemon_uptime_at_start=daemon_uptime_at_start, shapes=args.shapes,
                        targets_available=targets_available, baseline_repeats=repeats,
                        grammar=grammar)

    def stop_setup_forced(reason, at, rows):
        RUN_STAMPS.update(project_outcome=SETUP_FORCED,
                          setup_forced={"reason": reason, "at": at, "rows": rows})
        write_results(out_path, project, module, capture_seconds, baseline_seconds, findings,
                      **write_kwargs, baseline_answered=baseline_answered,
                      baseline_removed=baseline_removed, rejected_targets=rejected,
                      rejected_mutations=rejected_mutations)
        print(f"\nSETUP-FORCED at the {at}: {reason}\nEvery row would force for a cause outside "
              f"the change, so none of this project's rows counts. Fix the setup and re-run the "
              f"project from its first row.", flush=True)

    # THE PREFLIGHT, before any row is drawn. Not on a spot check, which names its row,
    # nor on a paired arm, which measures exactly the shared arm's rows.
    if not args.only_target and paired_rows is None and not shard:
        # The whole population in the sweep's own order, not what a resume left: the same file
        # every leg.
        # A revert, forcing or neutral row edits no method body of its own, so its preflight file
        # comes from the default grammar's bodies, as a mutation set's does.
        population = (distinct_targets(mutation_targets(checkout, project))[0]
                      if revert_set or forcing or neutral else all_targets)
        if reach is None:
            reach, covered = reach_counts(map_dir), covered_classes(map_dir)
        candidates = [path for path, kind, _ in
                      by_coverage(by_reach(population, reach, args.target_seed), covered)[0]
                      if kind == "body"]
        preflight = run_preflight(checkout, project, list(dict.fromkeys(candidates)), scratch,
                                  map_dir, map_backup, snapshot, select_flags, [task_path],
                                  classes_args)
        RUN_STAMPS["preflight"] = preflight
        if preflight.get("setup_forced"):
            stop_setup_forced(preflight["setup_forced"], "preflight", [])
            archive_run_evidence(project, arm_label, scratch, out_path, map_dir, args.shapes)
            return SETUP_FORCED_EXIT
    # THE WHOLE ORDERING, counting ACCEPTED rows against the limit rather than slicing first.
    # `ordered[:args.limit]` spent a slot on every target the loop then declined -- a mutation the
    # generator could not apply, one already measured by an earlier invocation, and now one the
    # compile gate rejects -- so the file held fewer rows than `sweep_limit` claimed and
    # `corpus_state.py` read that claim as the project's depth.
    # COUNTED FROM WHAT THE FILE ALREADY HOLDS, not from zero. The comment above
    # names the goal -- a file that holds as many rows as `sweep_limit` claims -- and starting at
    # zero achieves it only on a first run. On a RESUMED one every banked target is skipped without
    # consuming a slot, so the run measures a further `limit` rows on top: iceberg's resume after a
    # crash was on course for 29 rows in a file stamped `sweep_limit: 16`, and `corpus_state.py`
    # reads that stamp as the project's depth. Overshooting is a smaller sin than undershooting --
    # the extra rows are real measurements -- but it is the same false claim in the other direction.
    accepted = len(done)
    # A shard measures its slice of the recording job's list, counted from the slice's place in it,
    # so the timing cap times the rows a single job would. The recording job keeps what passes the
    # gate instead of measuring it.
    recorded, recorded_rejections = [], []
    if shard:
        stamps = {"selector_digest": measured_selector_digest(), "grammar_digest": grammar,
                  "plugin_commit": plugin_commit(), "commit": project["commit"],
                  "fixture_digest": RUN_STAMPS.get("fixture_digest")}
        why = accepted_mismatch(accepted_list, stamps)
        if why:
            print(f"REFUSING: {why}", flush=True)
            return 1
        ordered, accepted = accepted_slice(accepted_list, shard)
        # A shard runs on a fresh machine with no capture before it, so its first row would pay
        # for cold daemons and caches: the same discarded run the capture is preceded by.
        warmup_log = os.path.join(scratch, f"warmup-shard-{shard[0]}.log")
        gradle(checkout, project, [task_path, "--rerun-tasks"], warmup_log)
        if not test_task_ran(warmup_log, module, task_name):
            print("the shard's warm-up run never reached the tests, so its timed rows would not "
                  "be comparable; aborting", flush=True)
            return 1
    declined = 0
    # Per `pool/v1` family, the targets left undrawn once the family was refused mid-sweep.
    abandoned = Counter()
    # Labels for `--only-target` and for paired rows written without an index; every other paired
    # row is matched by its mutation BEFORE it is applied, so no declaration is applied only to be
    # discarded.
    wanted = set(args.only_target or ()) | {key for key in paired_rows or () if isinstance(key, str)}
    wanted_mutations = {(key[0].split("::", 1)[0], key[1], key[2])
                        for key in paired_rows or () if not isinstance(key, str)}
    rejected_seen = rejected_keys({"rejected_targets": rejected,
                                   "rejected_mutations": rejected_mutations})
    # COMPLEMENT GROUND TRUTH'S STATE FOR THIS PROJECT, fixed before any row runs: the
    # suite it is priced against, whether an earlier sweep or the baseline repeats flagged an
    # order-dependent or nondeterministic test, and whether one test JVM ran the whole suite (the
    # isolated arm's case, whose pairing needs full-arm truth on every row).
    suite_profile = read_suite_profile(profile_path, profile_key)
    flagged_project = bool(baseline_removed or previous.get("baseline_removed")) or any(
        row.get("order_dependent") or row.get("nondeterministic")
        for row in list(previous.get("mutations") or []) + findings)
    single_jvm = bool(map_dir) and (sweep_data.task_facts(map_dir) or {}).get("workers") == 1
    # A SWITCH-OFF OUTLIVES THE INVOCATION THAT MADE IT: a resumed leg keeps the complement off.
    switched_off = resumed_switch_off(previous, findings)
    complement_off = switched_off is not None
    RUN_STAMPS["complement_switched_off"] = switched_off
    # THE DRAW, and a pass re-measuring every row whose only ground truth was a complement, with the
    # full arm, once the complement is off (`complement_rows_to_rerun`): before the draw when a
    # resumed leg still owes some, after it when a check row disagreed during it.
    stop_all = False
    # WHAT THE COMPILE GATE COST SINCE THE LAST ACCEPTED ROW, rejected draws included.
    gate_spent = 0.0
    passes = [("draw", ordered)]

    def queue_remeasure(position):
        redo = complement_rows_to_rerun(findings)
        if redo:
            print(f"\n  re-measuring {len(redo)} row(s) whose ground truth was a complement, "
                  f"with the full arm, now that it is off for this project", flush=True)
            done.difference_update(row_key(row) for row in redo)
            # A revert row's item is its commit, the part of its target after `revert::`.
            passes.insert(position, ("rerun", [(row["target"].split("::", 1)[row["kind"] == "revert"],
                                                row["kind"], row["index"]) for row in redo]))

    if complement_off:
        queue_remeasure(0)
    stamped = None
    while passes and not stop_all:
        phase, items = passes.pop(0)
        rerunning = phase == "rerun"
        for position, (rel_path, kind, index) in enumerate(items):
            stamp_rows(findings, stamped, args.control)
            findings[:] = drop_superseded(findings)
            # A REVERT SET STOPS PER STRATUM: each draws up to the limit and its own narrowed
            # target, from decisions and induced failures only.
            if revert_set and not rerunning and not shard:
                if not revert.should_draw(stratum_of.get(f"revert::{rel_path}"), findings,
                                          stratum_of, args.limit, args.narrowed_target,
                                          counts_toward_narrowed_target):
                    continue
            # A POOL FAMILY STOPS AT ITS OWN TARGET OR ROW CAP; the others go on drawing.
            elif pool and not rerunning and not shard:
                if family_order.family_stop(kind, findings, recorded, args.limit, family_target,
                                            counts_toward_narrowed_target):
                    continue
            elif not rerunning and not shard and accepted >= args.limit:
                break
            # A FORCING FAMILY STOPS AT ITS ROWS A PROJECT, whatever they showed: the path decides
            # the outcome. No narrowed target: the set has no recall denominator.
            elif forcing and not rerunning and not shard:
                if forcing_mutations.family_full(kind, findings, recorded):
                    continue
            elif not rerunning and not shard and narrowed_target_reached(findings,
                                                                         args.narrowed_target):
                print(f"  narrowed target reached: {args.narrowed_target} rows narrowed and induced a "
                      f"failure, in {accepted} rows (cap {args.limit}); drawing no more targets",
                      flush=True)
                break
            by_mutation = (rel_path, kind, index) in wanted_mutations
            if (wanted or wanted_mutations) and not by_mutation \
                    and not any(w.startswith(f"{rel_path}::{kind}::") for w in wanted):
                continue
            if not restore(checkout):
                print("    the checkout could not be restored, so this row would measure the previous "
                      "row's mutation; stopping rather than recording it", flush=True)
                stop_all = True
                break
            reset = reset_untracked(checkout, project, snapshot) if snapshot is not None else False
            if reset:
                print(f"    removed {len(reset)} file(s) earlier runs wrote: {', '.join(reset[:3])}"
                      f"{' ...' if len(reset) > 3 else ''}", flush=True)
            candidate = revert_by_sha.get(rel_path) if kind == "revert" else None
            dependency = dependency_by_sha.get(rel_path) if kind == "dependency-revert" else None
            if kind == "dependency-revert":
                label = f"{rel_path}::{kind}::{rel_path[:12]}" if dependency else None
                if label and not revert.apply(checkout, dependency):
                    label = None
            elif kind == "revert":
                label = revert.label(candidate) if candidate else None
                if label and (keyed_in(done, label, kind, index)
                              or keyed_in(rejected_seen, label, kind, index)):
                    continue
                if label and not revert.apply(checkout, candidate):
                    label = None
            elif forcing:
                label = forcing_mutations.apply(checkout, rel_path, kind, index)
            elif neutral:
                label = apply_neutral(checkout, rel_path, kind)
            else:
                label = (pool_mutations.apply if pool else mutations.apply)(checkout, rel_path,
                                                                           kind, index)
            change_files, change_modules = mutation_change_counts(checkout) if label else (0, 0)
            if label is None:
                # COUNTED, because it stopped costing a row. While the limit was a slice, a generator
                # that could not apply anything burned the whole budget and the empty results file said
                # so loudly. Now the loop simply walks on, so a wave of declines is silent unless
                # somebody counts it.
                declined += 1
                continue
            if keyed_in(done, label, kind, index):
                continue
            if not by_mutation and (wanted or wanted_mutations) and label not in wanted:
                continue
            # ALREADY REJECTED ON AN EARLIER LEG, so do not pay for its compile again and do not append
            # it twice. `rejected` is a population of targets, not a log of attempts: the closing
            # summary lists it and `assert_recall.py` divides by it, and both read a duplicate as a
            # second bad target rather than the same one seen twice.
            if keyed_in(rejected_seen, label, kind, index):
                continue
            print(f"--- {label}", flush=True)
            # Every row this target appends carries its kind and index, a stalled or failed build's
            # too: a merge places rows on the accepted list by them.
            stamped = (len(findings), kind, index)

            if map_dir:
                restore_map(map_backup, map_dir)

            # The same two fixes the replay path already has, for the same two reasons.
            # Per-target log names: a shared selected.log is overwritten by the next mutation, which is
            # why `selected 0/2356` on mockito cannot be explained today -- the log that would say why
            # was gone before the summary was read.
            # Compile before either arm is timed: whichever runs first otherwise pays for compiling the
            # mutated sources while the second finds them UP-TO-DATE, and the difference reads as a
            # penalty for selecting.
            stem = log_slug(label, index)
            # Compile the mutated tree before either arm is timed. The replay path has done this since
            # a 272s-vs-150s row turned out to be a penalty for going first, not for selecting; the
            # mutation path never did, and the selected arm always goes first. On okhttp that alone read
            # as +163% for a row where the two arms are within a second of each other by hand.
            #
            # ONCE. This ran twice with identical arguments and the same log path, so the second call
            # measured an up-to-date build and overwrote the log of the one that did the work.
            gate_started = time.time()
            revert_variant = None
            if candidate is not None:
                def reset_tree():
                    if not restore(checkout):
                        return False
                    if snapshot is not None:
                        reset_untracked(checkout, project, snapshot)
                    return True

                # THE MAIN-CODE REVERT FIRST, and the full revert once when only the tests failed
                # to compile. Every other failure the revert made is a counted rejection.
                code, revert_variant, revert_reason = revert.gate(
                    checkout, candidate,
                    lambda log: gradle(checkout, project, classes_args, log)[0], reset_tree,
                    os.path.join(scratch, f"compile-{stem}.log"), STALLED)
                mutation_rejected = revert_reason is not None
                if mutation_rejected:
                    revert_rejections.append([label, revert_reason, revert_variant,
                                              candidate["labels"]])
                change_files, change_modules = mutation_change_counts(checkout)
            elif dependency is not None:
                # AN OLD VERSION THAT DOES NOT RESOLVE is this revert's doing, unlike the revert
                # set's: it edits the dependency declarations. Counted by reason, as a compile
                # failure with a compiler diagnostic is; any other failure stays `build-failed`.
                compile_log = os.path.join(scratch, f"compile-{stem}.log")
                code = gradle(checkout, project, classes_args, compile_log)[0]
                dependency_reason = (None if code in (0, STALLED) else
                                     forcing_mutations.dependency_rejection(
                                         revert._read(compile_log)))
                mutation_rejected = dependency_reason is not None
                if mutation_rejected:
                    forcing_rejections.append([label, dependency_reason])
            else:
                code, mutation_rejected = compile_or_reject(
                    checkout, project, classes_args, os.path.join(scratch, f"compile-{stem}.log"),
                    gradle)
            gate_spent += time.time() - gate_started
            if stalled(code, label, findings, "compile"):
                continue
            # DISCARDED, not recorded. The mutation did not compile, so the generator wrote source this
            # guest will not take; that is a fact about the grammar and there is no row in it. Counted,
            # named, and read back by `assert_recall.py` against the ceiling -- a silent discard is how
            # a seventh grammar defect would shrink this project's sample with nothing looking wrong.
            if mutation_rejected:
                rejected.append(label)
                rejected_mutations.append([label, kind, index])
                # With how many targets it was drawn after, so a merge that stops at the narrowed
                # target keeps only the rejections a single job would have met.
                recorded_rejections.append([label, kind, index, len(recorded)])
                rejected_seen.add((label, kind, index))
                if candidate is not None:
                    print(f"    REJECTED: the revert failed the gate ({revert_reason}, "
                          f"{revert_variant} variant); counted, and taking the next commit",
                          flush=True)
                elif dependency is not None:
                    print(f"    REJECTED: the dependency revert failed the gate "
                          f"({dependency_reason}); counted, and taking the family's next commit",
                          flush=True)
                else:
                    print("    REJECTED: the mutated tree did not compile, so this is a generator "
                          "defect and not a row; taking the next target", flush=True)
                # From the same family, so a family a guest rejects is not sampled less, until the
                # family is refused however the rest of the sweep goes; then none of it is drawn.
                # A shard measures a list already drawn, and has no limit to bound the rest by.
                if pool and not rerunning and not shard and refused_whatever_follows(
                        sum(1 for key in rejected_mutations if key[1] == kind),
                        family_order.family_rows(kind, findings, recorded),
                        # The family's own cap bounds what it can still draw.
                        args.limit - family_order.family_rows(kind, findings, recorded)):
                    later = [t for t in items[position + 1:] if t[1] == kind]
                    items[position + 1:] = [t for t in items[position + 1:] if t[1] != kind]
                    abandoned[kind] += len(later)
                    print(f"    {kind} is above the ceiling whatever the rest of this sweep draws; "
                          f"its {len(later)} remaining target(s) are not drawn", flush=True)
                elif pool or forcing:
                    pool_mutations.redraw(items, position, kind)
                continue
            # PAST THE GATE, so this target is one of the N the limit promises.
            gate_seconds, gate_spent = round(gate_spent, 1), 0.0
            # A re-measured row keeps its place in the sample: it was drawn, and untimed, before.
            if not rerunning:
                accepted += 1
            if args.accepted_out:
                recorded.append([rel_path, kind, index, label])
                continue
            timed = not rerunning and is_timed(accepted, args.recall_only, args.timing_cap)

            replay_map = snapshot_for_replay(map_dir, scratch)
            selected_log = os.path.join(scratch, f"selected-{stem}.log")
            # Sampled per ARM. See machine_conditions: the two arms of this row are minutes apart and
            # hundreds of seconds long, so one sample for the pair would describe neither of them.
            selected_conditions = machine_conditions()
            started = time.time()
            selected_code, selected_seconds = gradle(
                checkout, project,
                # Diffed against the pinned commit, not against whatever the plugin would pick by
                # default. The default is the merge base with the branch's upstream, and a pinned
                # benchmark commit is usually not on that branch's tip line: kestra's sits 48 commits
                # behind the merge base with origin/develop, so every mutation was measured against
                # those 48 commits as well -- 33 of whose changed paths are Vue files and translations
                # that coverage cannot see. Every mutation forced a full run before the mutation was
                # even considered, and the sweep reported the tool as useless on kestra while actually
                # measuring the harness. The mutation is an uncommitted working-tree change, so HEAD
                # makes the change set exactly the mutation, which is the only question being asked.
                [*INIT_SCRIPT_ARGS, task_path,
                 "-Pyoriwake.select", "-Pyoriwake.base=HEAD"] + select_flags + TIMING_ARM_FLAGS,
                selected_log)
            if stalled(selected_code, label, findings, "selected"):
                continue
            if not executed_or_excluded(selected_log, module, label, findings, "selected", task_name):
                continue
            selected = read_results(checkout, results_dir, started, task_name, selected_log)
            if selected[0] is None:
                selected = Outcomes(set(), set(), cause=selected.cause)
            # On XML keys: every count and every check on the arm's size reads these.
            selected_ran, selected_failed = selected

            # IMMEDIATELY, and before the ground-truth run, which writes its own record over this one.
            # The truth run is bracketed by a map backup that would put this file back, so the ordering
            # is belt and braces -- but the belt is what this reads, because a row whose explanation came
            # from the wrong arm is worse than a row with none.
            decisions = read_decision_record(map_dir, started)
            invalid = undecided_row(label, "selected", decisions, selected_ran, selected_code,
                                    selected_log)
            if invalid:
                findings.append({**invalid, "kind": kind, "index": index, "sweep_limit": args.limit,
                                 "narrowed_target": args.narrowed_target,
                                 **({"family_target": family_target} if pool else {})})
                continue
            # The set itself lives beside the arm's log rather than in the results file: on a
            # 3,700-test suite it is hundreds of kilobytes, and a results file that cannot be read as a
            # diff stops being reviewed. The digest is the part that travels.
            if decisions is not None and map_dir:
                keep_decisions(map_dir, os.path.join(scratch, f"decisions-{stem}"))
            # Kept whatever the record says, for diagnosis; read as absent when the agent reported a
            # lost write, because the file on disk may then be another class loader's answer.
            decisions, write_failures = trusted_decisions(decisions, selected_log)
            raw_kept = sweep_data.keep_raw(map_dir, os.path.join(scratch, "raw", stem), started,
                                           with_exec=args.keep_exec)

            # THE SELECTED ARM IS THE FULL ARM when it ran everything, and on an untimed row
            # (--recall-only, or past --timing-cap) it is not paid for twice. Every writer must agree
            # it was a full run -- a merged record drops an outcome its parts disagree on -- and the
            # arm must have reached tests. Timed rows keep the second arm: a forced row's selected/full
            # ratio is the published cost of forcing.
            full_skipped = full_arm_skipped(timed, decisions, selected_ran)
            paired_row = keyed_lookup(paired_rows, label, kind, index) if paired_rows else None
            # WHERE THIS ROW'S GROUND TRUTH COMES FROM: the full arm, or on an untimed
            # narrowed row under `check` or `auto` the complement, with the full arm beside it on a
            # check row. Decided before either runs, from the decision, the map and the position.
            complement, complement_seconds, disagreements = None, None, None
            # A CONTROL IS NOT PRICED, AND PAIRS WITH NO ISOLATED ARM: its planted plugin drops most
            # of a small single-JVM suite, so the cost rule and the paired-arm rule would send every
            # row to the full arm and leave the complement unexercised.
            truth, truth_rules, classes = route_ground_truth(
                args.ground_truth if paired_row is None else "full", timed, decisions,
                complement_off, suite_profile,
                sum(1 for row in findings if row.get("timed") is False
                    and row.get("decision_outcome") == "narrowed"),
                replay_map, flagged_project, single_jvm and not args.control, label, kind, index,
                args.target_seed, priced=not args.control)

            def run_full_arm():
                """The full arm; None when the row ended here (stalled, excluded, nothing ran)."""
                full_log = os.path.join(scratch, f"full-{stem}.log")
                full_conditions = machine_conditions()
                started = time.time()
                # Bracketed by a backup, not instrumented. Ground truth must stay a plain full run --
                # it is the thing every recall claim is measured against -- but on a fixture that
                # applies the plugin in its own build file it decodes without recording, which
                # discards the loaded-class union. The union then vanishes after the first mutation
                # and the rule that depends on one can never fire again, so every later "0 missed"
                # would be measuring a switched-off rule. Found exactly that way: breaking the union
                # check on purpose changed nothing.
                #
                # Recording during ground truth fixed that and cost 25 minutes a sweep, because it
                # attaches a second -javaagent to every full run. Saving the map and putting it back
                # costs nothing.
                truth_backup = backup_map(map_dir, scratch, "map-before-truth") if map_dir else None
                # The init script is passed here too, and that is deliberate. Ground truth is about
                # WHICH TESTS FAIL, and applying the plugin does not change that -- the map is backed
                # up and put back around this run. But `full_seconds` is also the denominator of
                # every savings figure, and without the plugin the row compared "plugin plus
                # selection" against "no plugin at all". On okhttp that comparison read +163% for a
                # narrowed row that is 14s against 13s when both arms are alike.
                full_code, full_seconds = gradle(
                    checkout, project,
                    [*INIT_SCRIPT_ARGS, task_path] + TIMING_ARM_FLAGS,
                    full_log)
                if truth_backup:
                    restore_map(truth_backup, map_dir)
                if stalled(full_code, label, findings, "ground-truth"):
                    return None
                if not executed_or_excluded(full_log, module, label, findings, "ground-truth",
                                            task_name):
                    return None
                full = read_results(checkout, results_dir, started, task_name, full_log)
                if full[0] is None:
                    findings.append({"target": label, "status": "did-not-build"})
                    print("    excluded: ground truth never reached the tests", flush=True)
                    return None
                return full, full_log, full_conditions, full_seconds

            def run_complement():
                """The dropped tests' classes, whole, as the full arm runs them; None when the run
                reached no tests. Its own map bracket, as the full arm's."""
                log = os.path.join(scratch, f"complement-{stem}.log")
                started = time.time()
                backup = backup_map(map_dir, scratch, "map-before-complement") if map_dir else None
                code, seconds = gradle(checkout, project,
                                       [*INIT_SCRIPT_ARGS, task_path, *complement_filters(classes)]
                                       + TIMING_ARM_FLAGS, log)
                if backup:
                    restore_map(backup, map_dir)
                ran = read_results(checkout, results_dir, started, task_name, log)
                if code == STALLED or ran[0] is None:
                    return None, seconds
                return ran, seconds

            if paired_row is not None:
                # THE PAIRED ROW'S GROUND TRUTH: the same mutation on the same tree, run minutes
                # earlier by the shared arm. Its induced set already excludes that arm's
                # already-failing tests. Only the count of what the full arm ran is read below, so a
                # stand-in of that length carries it.
                print(f"    ground truth: the paired row's full arm "
                      f"({paired_row['full_distinct']} tests, {len(paired_row['induced'])} failed)",
                      flush=True)
                full_log, full_conditions = None, paired_row.get("full_conditions")
                full_seconds = paired_row.get("full_seconds")
                full = paired_outcomes(paired_row)
            elif full_skipped:
                print("    ground truth: the selected arm ran everything, so it is the full arm "
                      "(an untimed row)", flush=True)
                full_log, full_conditions, full_seconds = None, None, None
                full = selected
            else:
                full_log, full_conditions, full_seconds, full = None, None, None, None
                # ON A CHECK ROW THE FULL ARM RUNS FIRST, then the reset, then the complement, so
                # neither run sees files the other wrote.
                if truth in (FULL_TRUTH, CHECKED_TRUTH):
                    ran_full = run_full_arm()
                    if ran_full is None:
                        continue
                    full, full_log, full_conditions, full_seconds = ran_full
                if truth in (COMPLEMENT_TRUTH, CHECKED_TRUTH):
                    if truth == CHECKED_TRUTH and snapshot is not None:
                        reset_untracked(checkout, project, snapshot)
                    beside = (f", beside the full arm ({', '.join(truth_rules)})" if truth_rules
                              else "")
                    print(f"    ground truth: the complement, {len(classes)} class(es){beside}",
                          flush=True)
                    complement, complement_seconds = run_complement()
                    if complement is None:
                        print("    the complement reached no tests; the full arm answers",
                              flush=True)
                        truth, truth_rules = FULL_TRUTH, truth_rules + ["complement-failed"]
                if truth == COMPLEMENT_TRUTH:
                    # THE COMPLEMENT ANSWERS ONLY ON PER-METHOD KEYS NO SIBLING SHARES. A row whose
                    # failures fall back to XML keys, or one a shared key would decide, runs the full
                    # arm too and is compared like a check row.
                    union = union_outcomes(selected, complement)
                    if complement_needs_full(selected, union, decisions, already_failing):
                        truth, truth_rules = CHECKED_TRUTH, truth_rules + ["keys"]
                    else:
                        full = union
                if full is None:
                    ran_full = run_full_arm()
                    if ran_full is None:
                        continue
                    full, full_log, full_conditions, full_seconds = ran_full
                if truth == CHECKED_TRUTH and complement is not None:
                    disagreements = complement_disagreements(selected, complement, full, decisions,
                                                             already_failing)
                    if disagreements:
                        print(f"    THE COMPLEMENT DISAGREES with the full arm on "
                              f"{len(disagreements)} test(s), e.g. {disagreements[0]}", flush=True)
                        if args.ground_truth == "auto" and not complement_off:
                            complement_off = True
                            RUN_STAMPS["complement_switched_off"] = {
                                "at": label, "disagreements": disagreements}
                            print("    the complement is OFF for this project: later rows run the "
                                  "full arm, and earlier complement rows are measured again",
                                  flush=True)
            full_ran = full[0]

            # ONE KEY SCHEME FOR THE ROW'S FAILURES: per method when both arms left complete outcomes,
            # else the XML key, with why. Every comparison below reads the row's keys, and every one of
            # them applies the shared-key rule (`shares_a_key`).
            keys = row_keys(selected, full, decisions,
                            "paired ground truth" if paired_row is not None else "full arm")
            selected_keyed_ran, selected_keyed_failed = keyed(selected, keys.scheme)
            full_failed = keyed(full, keys.scheme)[1]

            # A test that fails on the unmutated tree fails for its own reasons -- kestra's
            # WorkerTest::killed times out after a minute on a loaded laptop -- and it is not evidence
            # about selection. Counting it made two mutations report a missed failure, one of them from
            # a mutation that only ADDED AN UNUSED METHOD and could not change behaviour at all.
            #
            # Subtracted rather than tolerated, because the failure mode is asymmetric: such a test is
            # recorded FAILED in the map and always run, so it is invisible -- until the run that built
            # the map happened to catch it passing, and then it reads as a silent skip that never was.
            induced = subtract_baseline(full_failed, already_failing, keys)
            missed, undetermined, shared_key = unmatched(induced, selected_keyed_ran, keys.shared)
            missed = misses_a_row_that_skipped_nothing_cannot_have(missed, selected_ran, full_ran,
                                                                   decisions)
            if undetermined:
                print(f"    {len(undetermined)} induced failures could not be matched across the two "
                      f"runs (their ids carry an identity hash); UNDETERMINED, not missed and not "
                      f"clean", flush=True)
            if shared_key:
                print(f"    {len(shared_key)} induced failures ran in the selected arm only under a key "
                      f"another test may have answered for; SHARED KEY, not missed and not clean",
                      flush=True)
            # THE RE-RUNS A MISS TRIGGERS, timed apart from the row: they happen only on
            # rows that missed, so a cost read with them in it would charge a project for missing.
            followup_started = time.time()
            # Verified before it is believed. See order_dependent: a test that fails the full run and
            # passes alone, with the same mutation, was never evidence about selection.
            if missed:
                dependent, unrepeatable = _order_dependent(
                    checkout, project, module, missed, scratch, stem, map_dir,
                    gradle=gradle, results_of=read_results, backup_map=backup_map,
                    restore_map=restore_map, flags=TIMING_ARM_FLAGS, task=task_name, keys=keys)
                if dependent:
                    print(f"    {len(dependent)} of {len(missed)} missed tests also pass when run "
                          f"alone with the same mutation; they are reported as order-dependent AND "
                          f"counted", flush=True)
                if unrepeatable:
                    print(f"    {len(unrepeatable)} of {len(missed)} missed tests gave DIFFERENT answers "
                          f"to identical isolated re-runs; nondeterministic, neither missed nor clean",
                          flush=True)
                order_dependent_tests = sorted(dependent)
                # Subtracted from the headline, never silently: see the replay path above, which applies
                # the same rule. Every defect this project has found twice was a rule applied at one
                # call site and not the other.
                nondeterministic_tests = sorted(unrepeatable)
                missed = [test for test in missed if test not in unrepeatable]
            else:
                order_dependent_tests = []
                nondeterministic_tests = []
            # THE CONTROL ARM. Everything above asks its questions with the mutation still in place, so
            # a test broken by its own environment rather than by the change answers all of them exactly
            # as a real miss does: it fails the full run, it is not selected, and it fails alone. The
            # tree goes back to what it was and the survivors are asked once more -- see
            # isolation.fails_without_the_change for the kafka-core rows that made this necessary.
            #
            # Last, and only on the survivors, because it is the most expensive question asked here and
            # the cheaper ones have already removed most candidates. Nothing below reads the mutated
            # tree; the next row restores and re-applies before anything needs one.
            if missed:
                if not restore(checkout):
                    print("    the checkout could not be restored, so the control arm would run "
                          "against the mutation it is meant to revert; skipping it", flush=True)
                    stop_all = True
                    break
                unattributable = _fails_without_the_change(
                    checkout, project, module, missed, scratch, stem, map_dir,
                    gradle=gradle, results_of=read_results, backup_map=backup_map,
                    restore_map=restore_map, flags=TIMING_ARM_FLAGS, task=task_name, keys=keys)
                if unattributable:
                    print(f"    {len(unattributable)} of {len(missed)} missed tests fail by themselves "
                          f"with the change REVERTED; they were never induced failures, so they are "
                          f"neither missed nor clean", flush=True)
                fails_unmutated_tests = sorted(unattributable)
                missed = [test for test in missed if test not in unattributable]
            else:
                fails_unmutated_tests = []
            # AFTER THE CONTROL ARM, not before it, and the ordering is the finding rather than a
            # detail. Splitting first meant a container never reached the one arm that can attribute
            # it: a class that fails to start with the mutation REVERTED is the guest's own breakage
            # and belongs in `fails_unmutated`, where it is named, rather than in a bucket whose whole
            # meaning is "this harness cannot decide". Only what survives the control arm is genuinely
            # undecidable. A container is dropped from the order-dependent list at the same moment --
            # it passed alone because it started alone, and one undecidable thing must not be counted
            # in two places.
            followup_seconds = round(time.time() - followup_started, 1)
            missed, container_failed = container_failures(missed, selected_ran, full_ran)
            if container_failed:
                order_dependent_tests = [t for t in order_dependent_tests if t not in container_failed]
                print(f"    {len(container_failed)} of the failures are CONTAINERS that never started, "
                      f"not tests; neither missed nor clean, because both verification arms run the "
                      f"class alone and cannot reproduce a full-suite load", flush=True)
            # A second, weaker question the "missed" number cannot ask. A test that only fails when some
            # other test ran first is not skipped -- it is selected, it runs, and it passes, because
            # selection changed what ran before it. The build goes green and the failure is gone.
            # Coverage cannot see an inter-test dependency, so nothing in the selector can prevent this;
            # the least we can do is count it. Flakiness produces the same signal and cannot be told
            # apart from here, which is why it is reported separately rather than folded into "missed".
            # Undetermined subtracted too: a test whose id could not be matched across the runs cannot
            # be said to have run and passed either. And `selected_failed` is matched the same way the
            # miss check is -- on the stable name -- because a raw comparison here published a test that
            # failed in BOTH arms as one that ran and passed.
            passed_anyway = ran_but_passed(induced, selected_keyed_failed, missed, undetermined,
                                           nondeterministic_tests, container_failed, shared_key)
            # A mutation that breaks no test asks the recall question of nothing. Counting it as
            # "checked" inflates the headline: detekt's first sweep reported 18 measured, of which 10
            # had no failure at all, so "0 missed" rested on 8 questions rather than 18.
            status = "checked" if induced else "no-effect"
            findings.append({
                "target": label,
                "kind": kind,
                # The declaration this row measured. Without it a resumed sweep can only match on file
                # and shape, which excluded every other declaration in the file and froze each project's
                # evidence after one pass. See pending_targets.
                "index": index,
                # THE DEPTH THAT PRODUCED THIS ROW, stamped on the ROW and not on the file. `run.py`
                # resumes by skipping already-measured targets, so one file accumulates rows from several
                # invocations at several limits, and a single header scalar would describe only the last
                # of them. Inferring depth from the row count is wrong in both directions as well: a
                # declined generator consumes a slot without writing a row.
                "sweep_limit": args.limit,
                # AND THE STOPPING RULE THAT ENDED THE DRAW, per row for the same reason; None = none.
                "narrowed_target": args.narrowed_target,
                # `pool/v1`'s per-family target; its per-family cap is `sweep_limit`.
                **({"family_target": family_target} if pool else {}),
                "status": status,
                # Whether the map knew this class before the mutation ran. An inert row means something
                # different for each: "uncovered" is the expected outcome and says the absence rule was
                # exercised, while "covered" and inert means the SHAPE could not fail anything, which is
                # a gap in the corpus rather than a property of the code.
                "reachability": provenance.get((rel_path, kind, index), "unknown"),
                # A complement row ran only the dropped tests' classes: the suite's size is the
                # unmutated suite's, which the mutation of a main source cannot change.
                "full_distinct": (suite_profile["suite_distinct"] if truth == COMPLEMENT_TRUTH
                                  else len(full_ran)),
                "full_failures": len(induced),
                "already_failing": len(full_failed - induced),
                "selected_distinct": len(selected_ran),
                # WHAT A REPLAY JUDGES AGAINST: every test the mutation made fail, not only the missed
                # ones -- a newer selector can miss a test this one caught.
                "induced": sorted(f"{c}::{n}" for c, n in induced),
                "replay": replay_block(replay_map, os.path.join(scratch, f"decisions-{stem}")),
                # True when --recall-only took the selected arm as ground truth, and `full_seconds` is
                # then absent: the row is recall evidence and not a timing measurement.
                "full_arm_skipped": full_skipped,
                # Whether this row's two arms are a paired time: False under --recall-only, after
                # the timing cap, and on a paired arm, whose full arm is the paired row's.
                "timed": timed and paired_row is None,
                # A planted-miss control row, which no figure may hold.
                "control": args.control,
                # WHERE THIS ROW'S GROUND TRUTH CAME FROM: `full`, `complement`, or
                # `complement+full` on a check row, whose full arm is the truth and whose complement
                # is compared with it. A paired row carries the shared row's.
                "ground_truth": (paired_row.get("ground_truth") if paired_row is not None
                                 else truth),
                # Every reason the full arm ran beside or instead of the complement; empty when
                # nothing chose (`--ground-truth full`, a timed row, a row that did not narrow).
                "ground_truth_rules": truth_rules,
                "complement_classes": len(classes) if complement is not None else None,
                "complement_seconds": complement_seconds,
                # Each test the complement and the full arm judged differently, both verdicts; None
                # when nothing was compared.
                "complement_disagreements": disagreements,
                # True on a row measured again with the full arm after the complement was switched off.
                "remeasured": rerunning,
                # Where this row's ground truth came from: its own full arm, or the paired file's row.
                "ground_truth_from": "paired" if paired_row is not None else "own",
                # The job the borrowed full arm ran in, which a time ratio must not cross.
                **({"full_ci": paired_row.get("ci")} if paired_row is not None else {}),
                "raw_bytes_kept": raw_kept,
                "selected_arm": arm_stats(selected_log),
                "full_arm": arm_stats(full_log) if paired_row is None else (paired_row.get("full_arm")),
                **sweep_row_fields(decisions, selected_ran, induced, write_failures, replay_map,
                                   change_files=change_files, change_modules=change_modules),
                **(revert_fields(candidate, revert_variant) if candidate is not None else {}),
                **({"revert_commit": dependency["sha"], "census_labels": dependency["labels"]}
                   if dependency is not None else {}),
                "missed": [f"{c}::{n}" for c, n in missed],
                "order_dependent": [f"{c}::{n}" for c, n in order_dependent_tests],
                # NEVER CLEAN AND NEVER MISSED. A test whose display name carries an identity hash has a
                # different name in every run, so the two runs cannot be compared about it at all.
                "undetermined": [f"{c}::{n}" for c, n in undetermined],
                # ALSO NEVER CLEAN AND NEVER MISSED, and a SEPARATE field from the one above because the
                # two mean different things: `undetermined` is "the two runs named this test
                # differently", this is "the test answered its own isolated re-run two different ways".
                "nondeterministic": [f"{c}::{n}" for c, n in nondeterministic_tests],
                # A THIRD KIND OF NEITHER, and its cause is not the other two's. `undetermined` is "the
                # two runs named this test differently" and `nondeterministic` is "it answered its own
                # re-run two ways"; this is "it is not a test at all -- the class never started". Both
                # verification arms run a class alone, which is a lighter load than the full suite, so
                # they cannot reproduce a container that fails only under one. Named, counted, and
                # subtracted from the headline.
                "container_failed": [f"{c}::{n}" for c, n in container_failed],
                # Neither missed nor clean, and named rather than counted: a test that fails with the
                # change reverted is evidence about the guest's own suite, which is worth reading.
                "fails_unmutated": [f"{c}::{n}" for c, n in fails_unmutated_tests],
                # NEITHER MISSED NOR CLEAN, a fourth cause: the selected arm ran this failure's key, but
                # that key may stand for another test (`shares_a_key`), so nothing says this one ran.
                "shared_key": [f"{c}::{n}" for c, n in shared_key],
                # NOT MEASURED ON A COMPLEMENT ROW, never empty: a selected test that passed in the
                # narrowed run and would fail in the whole suite is invisible to the complement.
                "ran_but_passed": (None if truth == COMPLEMENT_TRUTH
                                   else [f"{c}::{n}" for c, n in passed_anyway]),
                # WHICH KEYS NAME AND MATCH THIS ROW'S FAILURES: `per-method` when every comparison it
                # made had both builds' per-method outcomes, else `xml`, with the first comparison that
                # fell back and why. Absent on a row written before per-method keys.
                "result_keys": keys.stamp,
                "result_keys_fallback": keys.fallback,
                # The induced failures whose keys two tests produced in these builds, which a paired arm
                # cannot see from its own selected run (see `paired_outcomes`).
                "collapsed_induced": sorted(f"{c}::{n}" for c, n in induced
                                            if (c, n) in keys.collapsed),
                "ci": ci_stamp(),
                "selected_seconds": selected_seconds,
                "full_seconds": full_seconds,
                # The compile gate since the last accepted row, rejected draws included, and the
                # re-runs a miss triggered; the cost figures read the first and leave out the second.
                "gate_seconds": gate_seconds,
                "followup_seconds": followup_seconds,
                # What a PERSON reads. Kept, but it is no longer the only account: it is scraped from a
                # log line, and on the run path the plugin never prints a force reason at all, so it was
                # null on 47 of the 87 rows that forced in one corpus sweep. The tokens beside it
                # come from the selector itself and are what a reader should trust.
                "full_run_reason": full_run_reason(selected_log, test_task_path(project)),
                **decision_fields(decisions, len(selected_ran)),
                **map_fields(map_dir),
                # Of the map the selecting arm READ: a forced arm re-records it, in shared JVMs.
                **map_facts(replay_map),
                "selected_conditions": selected_conditions,
                "full_conditions": full_conditions,
                # The untracked and ignored files removed before this row, or False with the
                # reset off.
                "untracked_reset": reset,
            })
            print(f"    {len(induced)} fail, selected {len(selected_ran)}/{len(full_ran)} -> "
                  f"{'MISSED ' + str(len(missed)) if missed else 'ok'}"
                  f"{', ' + str(len(order_dependent_tests)) + ' of them order-dependent'
                     if order_dependent_tests else ''}"
                  f"{', ' + str(len(nondeterministic_tests)) + ' nondeterministic'
                     if nondeterministic_tests else ''}"
                  f"{', ' + str(len(fails_unmutated_tests)) + ' fail unmutated'
                     if fails_unmutated_tests else ''}"
                  f"{', ' + str(len(passed_anyway)) + ' ran but passed' if passed_anyway else ''}",
                  flush=True)

            # write_results, not a second copy of it. This block used to inline the same JSON, and
            # because it runs last it was the copy that reached disk -- so `timing_method`, added to
            # write_results, never appeared in a mutation sweep's results at all. Two writers for one
            # format, one of them updated: the same shape as every other defect in this harness.
            write_results(out_path, project, module, capture_seconds, baseline_seconds, findings,
                          args.target_seed, map_dir=map_dir,
                          daemon_uptime_at_start=daemon_uptime_at_start, shapes=args.shapes,
                          targets_available=targets_available,
                          baseline_repeats=repeats,
                          baseline_answered=baseline_answered,
                          baseline_removed=baseline_removed,
                          rejected_targets=rejected, rejected_mutations=rejected_mutations,
                          grammar=grammar)
            # Read from decisions and changed paths only; see `setup_forced_streak`.
            forced_for_setup = setup_forced_streak(findings, declared_in(checkout))
            if forced_for_setup:
                stop_setup_forced(forced_for_setup, "rows",
                                  [f["target"] for f in findings
                                   if f.get("decision_outcome")][-SETUP_FORCED_STREAK:])
                stop_all = True
                break
        findings[:] = drop_superseded(findings)
        if phase == "draw" and complement_off and not stop_all:
            queue_remeasure(len(passes))

    # A LABEL THAT MATCHED NOTHING IS SAID, never read as a clean answer. A mistyped or stale label
    # otherwise walks the whole ordering, measures nothing, and exits looking like a row that passed.
    unmatched_labels = sorted(wanted - {f["target"] for f in findings} - set(rejected))
    if unmatched_labels:
        print(f"\n--only-target matched no target for: {', '.join(unmatched_labels)}. Labels are the "
              f"`target` field of a results file, exactly.", flush=True)

    # The last target's rows too, before they are written: the loop stamps a row only when it goes
    # round again.
    stamp_rows(findings, stamped, args.control)
    # ONCE MORE AFTER THE LOOP, because the writer inside it only runs when a row is accepted.
    # A leg whose every target was rejected wrote nothing at all, so the rejections -- the only
    # evidence that the leg did anything -- died with the process, and the next resume re-offered
    # the same bad targets against a file that had never heard of them.
    write_results(out_path, project, module, capture_seconds, baseline_seconds, findings,
                  args.target_seed, map_dir=map_dir,
                  daemon_uptime_at_start=daemon_uptime_at_start, shapes=args.shapes,
                  targets_available=targets_available,
                  baseline_repeats=repeats,
                  baseline_answered=baseline_answered,
                  baseline_removed=baseline_removed,
                  rejected_targets=rejected, rejected_mutations=rejected_mutations,
                  grammar=grammar)
    if not args.only_target:
        archive_run_evidence(project, arm_label, scratch, out_path, map_dir, args.shapes)

    subprocess.run(["git", "-C", checkout, "checkout", "--", "."], check=True)
    # THE MAP AS MEASURED, not as the last row's selecting run left it: the next invocation -- a
    # resumed leg, or the next shard -- backs up whatever is on disk as the map every row restores.
    if map_dir:
        restore_map(map_backup, map_dir)
    if RUN_STAMPS.get("project_outcome") == SETUP_FORCED:
        return SETUP_FORCED_EXIT
    if args.accepted_out:
        write_accepted(args.accepted_out, project, grammar, recorded, recorded_rejections, args)
        print(f"\n{len(recorded)} target(s) accepted for the shards, {len(rejected)} rejected by "
              f"the compile gate; the list is {args.accepted_out}", flush=True)
        return 0 if recorded else 1
    checked = [f for f in findings if f["status"] == "checked"]
    inert = [f for f in findings if f["status"] == "no-effect"]
    print(f"\n{len(checked)} measured, "
          f"{sum(len(f['missed']) for f in checked)} MISSED "
          f"({sum(len(f.get('order_dependent', [])) for f in checked)} of them order-dependent), "
          f"{sum(len(f.get('undetermined', [])) for f in checked)} undetermined, "
          f"{sum(len(f.get('shared_key', [])) for f in checked)} on a shared key, "
          f"{sum(len(f.get('nondeterministic', [])) for f in checked)} nondeterministic, "
          f"{sum(len(f.get('ran_but_passed') or []) for f in checked)} ran but passed, "
          f"{sum(f['full_failures'] for f in checked)} genuine failures "
          f"({len(inert)} mutations broke nothing and asked nothing)", flush=True)
    # SAID OUT LOUD at the end of every sweep. A discard that only reaches a JSON field is a discard
    # nobody sees, and the whole hazard of the gate above is that it is quiet.
    if declined:
        print(f"{declined} target(s) were DECLINED by the generator, which produced no mutation for "
              f"them; they consumed no row and are not a compile failure", flush=True)
    if revert_set:
        stamp = RUN_STAMPS["revert"]
        stamp_revert(stamp, findings, args.narrowed_target)
        print(f"reverts: {stamp['eligible']} eligible, not eligible {stamp['not_eligible'] or 'none'}, "
              f"rejected {({k: v['count'] for k, v in stamp['rejected'].items()}) or 'none'}",
              flush=True)
        if stamp["not_reached"]:
            print(f"not reached by {REVERT_SET}: {', '.join(stamp['not_reached'])}", flush=True)
    if forcing and forcing_rejections:
        print(f"dependency reverts rejected: "
              f"{dict(Counter(reason for _, reason in forcing_rejections))}", flush=True)
    for family, count in abandoned.items():
        print(f"{count} {family} target(s) were NOT DRAWN: the family was above the ceiling "
              f"whatever the rest of the sweep drew", flush=True)
    if rejected:
        # No shared ceiling for reverts: a real commit the gate rejects is counted, not a defect.
        # `pool/v1`'s ceiling is per family.
        # `forcing/v1` has no recall sample for a rejection to shape: counted, not a ceiling.
        refused = (not revert_set and not pool and not forcing
                   and rejection_rate_refuses(len(rejected), len(findings)))
        print(f"{len(rejected)} mutation(s) were REJECTED by the compile gate and consumed no row"
              f"{' -- ABOVE the ceiling, so this is a refusal rather than a statistic'
                 if refused else ''}", flush=True)
        for label in rejected:
            print(f"    rejected: {label}", flush=True)
        for family, (count, offered) in (refused_families(rejected_mutations, findings)
                                         if pool else {}).items():
            print(f"  {family}: {count} of {offered} rejected -- ABOVE the ceiling, so this "
                  f"family is refused; fix the generator before its rows are read", flush=True)
    if inert:
        where = Counter(f.get("reachability", "unknown") for f in inert)
        print(f"  of those {len(inert)}: {where['uncovered']} on classes nothing loads, "
              f"{where['covered']} on covered classes whose shape could not fail, "
              f"{where['unknown']} unresolved", flush=True)
    # A sweep that induced no failures answered no recall question, and must not exit 0. Six of six
    # kestra mutations were inert once and the run reported success; the number that came
    # out of it -- zero missed -- was then true and meaningless at once. Refusing to pass is the
    # only thing that stops that number being quoted.
    # A shard's slice may hold no such row; whether the sweep asked any is the merge's question.
    if not checked and not shard:
        print("\nNO ROW ASKED A RECALL QUESTION. Zero missed out of zero induced failures is not "
              "a safety result. Fix the targets before reading anything into this run.", flush=True)
        return 1
    return 1 if unmatched_labels else 0


def archive_run_evidence(project, arm_name, scratch, out_path, map_dir, shapes=DEFAULT_SHAPES):
    """Keeps this invocation's raw evidence as one compressed file, so later questions are answered
    offline rather than by another sweep: every log, the decision records with their parts, the
    replay maps each selecting arm read, each row's raw records (without `.exec` unless
    --keep-exec), the results file, and the map as it stands. The capture's raw records, `.exec`
    included, are archived separately when it runs: `<project>-<arm>-capture-raw.tar.gz`.

    `bench/logs/evidence/<project>-<arm>.tar.gz`, replaced on every invocation: the scratch it
    is made from accumulates across resumes, so the newest archive holds every leg -- which is why
    the uncompressed copies stay in the scratch rather than being removed once archived. The
    archive's path and size are stamped on the results file.
    """
    # A named shape set's evidence is its own, never a replacement for the default set's.
    suffix = "" if shapes == DEFAULT_SHAPES else "-" + shape_folder(shapes)
    archive = os.path.join(HERE, "logs", "evidence", f"{project['name']}-{arm_name}{suffix}.tar.gz")
    members = [(scratch, os.path.basename(scratch)),
               (out_path, os.path.join("results", os.path.basename(out_path)))]
    if map_dir:
        members.append((map_dir, os.path.join("map", os.path.basename(map_dir))))
    size = sweep_data.archive_evidence(archive, members)
    with open(out_path, encoding="utf-8") as handle:
        data = json.load(handle)
    data["evidence"] = {"archive": os.path.relpath(archive, ROOT), "bytes": size}
    staging = out_path + ".writing"
    with open(staging, "w", encoding="utf-8") as handle:
        json.dump(data, handle, indent=1)
    os.replace(staging, out_path)
    print(f"evidence kept: {os.path.relpath(archive, ROOT)} ({size / 2 ** 20:.1f} MiB)", flush=True)


def _restore_on_exit(checkout):
    """Puts the checkout back however this process ends, including Ctrl-C and a kill.

    A mutation is an edit to someone else's repository, and the harness is the only thing that knows
    to undo it. Killed mid-run it left one behind twice in one day: in kestra's AuthUtils.java, and
    in mockito's AndroidPlatform.java -- where it went unnoticed and silently added a changed class
    to six commits' worth of measurements, every one of which was then read as a finding about the
    tool.

    Registered for both the ordinary exit and the two signals a person or a CI runner actually
    sends. SIGKILL cannot be caught, and the replay scripts refuse to run on a dirty checkout for
    exactly that remaining case.
    """
    def restore_quietly(*_):
        try:
            restore(checkout)
        except Exception:
            pass
        raise SystemExit(130)

    atexit.register(lambda: restore(checkout))
    for received in (signal.SIGINT, signal.SIGTERM):
        try:
            signal.signal(received, restore_quietly)
        except (ValueError, OSError):
            # Not the main thread, or a platform without it. The atexit hook still stands.
            pass


if __name__ == "__main__":
    sys.exit(main())
