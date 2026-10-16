"""The shell steps of a cross-OS job, in Python so they behave the same on every runner.

`find` does not exist on the Windows runner, and GNU and BSD `sed -i` disagree, so the steps live
here instead of in `verify.yml`.

`discard` removes earlier test results before a run on the same working tree: otherwise Gradle can
report the test task up to date and leave the previous run's results for the assertions to read.

    python .github/ci.py find-map samples/demo
    python .github/ci.py mutate samples/demo/src/main/java/dev/demo/Alpha.java
    python .github/ci.py restore samples/demo/src/main/java/dev/demo/Alpha.java
    python .github/ci.py mutate samples/demo/src/main/java/dev/demo/Epsilon.java
    python .github/ci.py discard samples/demo --map
    python .github/ci.py needs-checks <base-sha> <head-sha>
    python .github/ci.py touches-product <base-sha> <head-sha>
"""

import argparse
import os
import shutil
import subprocess
import sys

# The mutation the cross-OS check applies: Alpha.twice doubles, so tripling breaks exactly the two
# tests that assert on it and nothing else. `assert_selection.py` names those two tests, so the two
# files agree on one edit and must keep agreeing -- change this pair together or the job asserts
# against a mutation that did not happen.
MUTATION = ("return n * 2;", "return n * 3;")

# The second, for a class reached only by name: renaming Epsilon's `label` breaks the one test that
# compares its methods with `registry.yaml`, and no bytecode reference leads to it.
EPSILON_MUTATION = ("public String label()", "public String name()")

MUTATIONS = {"Alpha.java": MUTATION, "Epsilon.java": EPSILON_MUTATION}


def mutation_for(path):
    """The mutation for a source file, by its name; refuses a file that has none."""
    name = os.path.basename(path)
    if name not in MUTATIONS:
        raise SystemExit(f"refusing: no mutation is defined for {name}; known: {sorted(MUTATIONS)}")
    return MUTATIONS[name]

MAP_FILE = "coverage.tsv"

# The decision record and its per-writer parts, `decisions.tsv.<pid>-<writer>.part`.
DECISIONS_FILE = "decisions.tsv"


def find_map(project_dir):
    """Every map directory under a project, newest first.

    One directory per test task, named `<task>-<hash>`, so the name cannot be predicted from the
    project -- which is why the workflow shelled out to `find` for it.
    """
    root = os.path.join(project_dir, ".gradle", "yoriwake")
    found = []
    for dirpath, _, filenames in os.walk(root):
        if MAP_FILE in filenames:
            found.append(dirpath)
    found.sort(key=lambda path: os.path.getmtime(path), reverse=True)
    return found


def records(map_dir):
    """Lines in a map, so a caller can say how much it holds rather than that it exists."""
    with open(os.path.join(map_dir, MAP_FILE), encoding="utf-8", errors="replace") as handle:
        return sum(1 for line in handle if line.strip())


def edit(path, old, new):
    """Replace `old` with `new`, and fail when it is absent.

    `sed` exits 0 when its pattern matches nothing, so a mutation step that silently did nothing
    left a job asserting against an unmutated tree -- which reports every test as "ran but did not
    fail" rather than as a broken step.
    """
    # Bytes kept as they are, line endings included: on Windows, text mode would write CRLF into an
    # LF checkout, and the restored file would differ from the commit after all.
    with open(path, encoding="utf-8", newline="") as handle:
        before = handle.read()
    if old not in before:
        raise SystemExit(f"refusing: {path} does not contain {old!r}, so the edit would be silent")
    with open(path, "w", encoding="utf-8", newline="") as handle:
        handle.write(before.replace(old, new, 1))


def discard_results(project_dir, drop_map=False):
    """Remove what a previous step left behind, so this step's evidence is its own.

    Test results and decision records always: a round whose run wrote no record must not be judged
    by the previous round's. The map only when asked: a selecting step reads the map the capturing
    step wrote, and deleting it there would delete the thing under test.
    """
    removed = []
    results = os.path.join(project_dir, "build", "test-results")
    if os.path.isdir(results):
        shutil.rmtree(results)
        removed.append(results)
    for dirpath, _, filenames in os.walk(os.path.join(project_dir, ".gradle", "yoriwake")):
        for name in filenames:
            if name.startswith(DECISIONS_FILE):
                os.remove(os.path.join(dirpath, name))
                removed.append(os.path.join(dirpath, name))
    if drop_map:
        maps = os.path.join(project_dir, ".gradle", "yoriwake")
        if os.path.isdir(maps):
            shutil.rmtree(maps)
            removed.append(maps)
    return removed


# Docs the checks read: the agent's tests compare their constants with the contract, and test_ci.py
# compares the reference's supported matrix with matrix.yml.
CHECKED_DOCS = {"docs/contract.md", "docs/reference.md"}


def needs_checks(paths):
    """Whether a change to `paths` needs the build and its checks: anything but Markdown or `docs/`,
    or a doc the checks read. No paths is an answer nobody could give, so it needs them: the wrong
    "no" skips every check, and GitHub counts a skipped required check as passing."""
    paths = [path for path in paths if path.strip()]
    if not paths:
        return True
    return any(path in CHECKED_DOCS or not (path.endswith(".md") or path.startswith("docs/")) for path in paths)


# The sources a pull request must pass every guard for: what the plugin and agent ship.
PRODUCT_SOURCES = ("plugin/src/main/", "agent/src/main/")


def touches_product(paths):
    """Whether a change to `paths` reaches the shipped sources, so every guard must run. No paths
    is an answer nobody could give, so it does: the wrong "no" leaves the change to fast mode."""
    paths = [path for path in paths if path.strip()]
    return not paths or any(path.startswith(PRODUCT_SOURCES) for path in paths)


def changed_paths(base, head, cwd=None):
    """The paths a pull request changes, or None when git cannot say."""
    try:
        # No renames: a code file moved into docs/ would be listed under its new name only, and pass
        # as docs. NUL-separated, so a quoted name never reaches the rule.
        done = subprocess.run(["git", "diff", "--no-renames", "--name-only", "-z", f"{base}...{head}"],
                              capture_output=True, text=True, check=True, cwd=cwd)
    except (OSError, subprocess.CalledProcessError):
        return None
    return done.stdout.split("\0")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)

    locate = sub.add_parser("find-map", help="print the newest map directory, and its record count")
    locate.add_argument("project_dir")

    mutate = sub.add_parser("mutate", help="apply the cross-OS check's mutation")
    mutate.add_argument("source")

    restore = sub.add_parser("restore", help="undo the mutation")
    restore.add_argument("source")

    discard = sub.add_parser("discard", help="remove a previous step's results")
    discard.add_argument("project_dir")
    discard.add_argument("--map", action="store_true", help="also delete the map directory")

    checks = sub.add_parser("needs-checks", help="print true unless a pull request changes only docs")
    checks.add_argument("base")
    checks.add_argument("head")

    product = sub.add_parser("touches-product",
                             help="print true unless a pull request leaves the shipped sources alone")
    product.add_argument("base")
    product.add_argument("head")

    args = parser.parse_args(argv)

    if args.command == "touches-product":
        paths = changed_paths(args.base, args.head)
        print("true" if paths is None or touches_product(paths) else "false")
        return 0

    if args.command == "needs-checks":
        paths = changed_paths(args.base, args.head)
        print("true" if paths is None or needs_checks(paths) else "false")
        return 0

    if args.command == "find-map":
        found = find_map(args.project_dir)
        if not found:
            print(f"no map under {args.project_dir}/.gradle/yoriwake", file=sys.stderr)
            return 1
        newest = found[0]
        # The map file, not its directory: the workflow greps what this prints, and grepping a
        # directory fails the job under `set -eu`.
        print(os.path.join(newest, MAP_FILE))
        print(f"map has {records(newest)} records", file=sys.stderr)
        return 0

    if args.command == "mutate":
        edit(args.source, *mutation_for(args.source))
        print(f"mutated {args.source}", file=sys.stderr)
        return 0

    if args.command == "restore":
        old, new = mutation_for(args.source)
        edit(args.source, new, old)
        print(f"restored {args.source}", file=sys.stderr)
        return 0

    if args.command == "discard":
        for path in discard_results(args.project_dir, drop_map=args.map):
            print(f"discarded {path}", file=sys.stderr)
        return 0

    return 2


if __name__ == "__main__":
    sys.exit(main())
