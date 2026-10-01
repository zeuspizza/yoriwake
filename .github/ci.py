"""The shell steps of a cross-OS job, in Python so they behave the same on every runner.

`find` does not exist on the Windows runner, and GNU and BSD `sed -i` disagree, so the steps live
here instead of in `verify.yml`.

`discard` removes earlier test results before a run on the same working tree: otherwise Gradle can
report the test task up to date and leave the previous run's results for the assertions to read.

    python .github/ci.py find-map samples/demo
    python .github/ci.py mutate samples/demo/src/main/java/dev/demo/Alpha.java
    python .github/ci.py restore samples/demo/src/main/java/dev/demo/Alpha.java
    python .github/ci.py discard samples/demo --map
"""

import argparse
import os
import shutil
import sys

# The mutation the cross-OS check applies: Alpha.twice doubles, so tripling breaks exactly the two
# tests that assert on it and nothing else. `assert_selection.py` names those two tests, so the two
# files agree on one edit and must keep agreeing -- change this pair together or the job asserts
# against a mutation that did not happen.
MUTATION = ("return n * 2;", "return n * 3;")

MAP_FILE = "coverage.tsv"


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
    with open(path, encoding="utf-8") as handle:
        before = handle.read()
    if old not in before:
        raise SystemExit(f"refusing: {path} does not contain {old!r}, so the edit would be silent")
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(before.replace(old, new, 1))


def discard_results(project_dir, drop_map=False):
    """Remove what a previous step left behind, so this step's evidence is its own.

    Test results always. The map only when asked: a selecting step reads the map the capturing step
    wrote, and deleting it there would delete the thing under test.
    """
    removed = []
    results = os.path.join(project_dir, "build", "test-results")
    if os.path.isdir(results):
        shutil.rmtree(results)
        removed.append(results)
    if drop_map:
        maps = os.path.join(project_dir, ".gradle", "yoriwake")
        if os.path.isdir(maps):
            shutil.rmtree(maps)
            removed.append(maps)
    return removed


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

    args = parser.parse_args(argv)

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
        edit(args.source, *MUTATION)
        print(f"mutated {args.source}", file=sys.stderr)
        return 0

    if args.command == "restore":
        edit(args.source, MUTATION[1], MUTATION[0])
        print(f"restored {args.source}", file=sys.stderr)
        return 0

    if args.command == "discard":
        for path in discard_results(args.project_dir, drop_map=args.map):
            print(f"discarded {path}", file=sys.stderr)
        return 0

    return 2


if __name__ == "__main__":
    sys.exit(main())
