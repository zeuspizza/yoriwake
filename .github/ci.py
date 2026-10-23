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
    python .github/ci.py changelog <base-sha> <head-sha>
    python .github/ci.py rerun-on-download-failure -- ./gradlew build --console=plain
"""

import argparse
import os
import re
import shutil
import subprocess
import sys
import time

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


# Docs the checks read: the agent's tests compare their constants with the contract, test_ci.py
# compares the reference's supported matrix with matrix.yml and SECURITY.md's key fingerprint with
# RELEASE-KEY.asc.
CHECKED_DOCS = {"docs/contract.md", "docs/reference.md", "SECURITY.md"}


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


CHANGELOG = "CHANGELOG.md"
VERSION_HEADING = re.compile(r"^## \[([^\]]+)\]")
LINK_REFERENCE = re.compile(r"^\[[^\]]+\]:\s")
HUNK = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@")


def _git(args, cwd):
    # Bytes, decoded without newline translation: git numbers lines by "\n" alone, and a carriage
    # return or line separator read as a break would shift every later line off git's numbering.
    done = subprocess.run(["git", *args], capture_output=True, check=True, cwd=cwd)
    return done.stdout.decode("utf-8")


def changelog_change(base, head, cwd=None):
    """The changelog at the merge base, at `head`, and the numbers of the lines `head` added; None
    when git cannot say. A changelog absent on either side reads as empty."""
    # From the repository's top, wherever the command runs: a relative pathspec in a subdirectory
    # names no file, and an absent changelog passes.
    path = f":(top){CHANGELOG}"
    try:
        merge_base = _git(["merge-base", base, head], cwd).strip()

        def text_at(rev):
            if not _git(["ls-tree", "--name-only", rev, "--", path], cwd).strip():
                return ""
            return _git(["show", f"{rev}:{CHANGELOG}"], cwd)

        before, after = text_at(merge_base), text_at(head)
        diff = _git(["diff", "--unified=0", "--no-renames", f"{merge_base}..{head}", "--", path], cwd)
    except (OSError, subprocess.CalledProcessError, UnicodeDecodeError):
        return None
    added = []
    line = None
    for row in diff.split("\n"):
        hunk = HUNK.match(row)
        if hunk:
            line = int(hunk.group(1))
        elif line is not None and row.startswith("+"):
            added.append(line)
            line += 1
    return before, after, added


def changelog_problems(before, after, added):
    """Each added line that sits under a version already released before the change.

    A line belongs to the nearest `## ` heading above it, the heading to its own section. A version
    the change itself adds is a release entry and passes; so do the preamble, link references and
    blank lines, which carry no entry. A released version whose heading is gone was renamed, which
    no added line can show: the renamed heading opens a section of its own."""
    released = {match.group(1) for match in map(VERSION_HEADING.match, before.split("\n")) if match}
    released.discard("Unreleased")
    lines = after.split("\n")
    headed = {match.group(1) for match in map(VERSION_HEADING.match, lines) if match}
    problems = [f"{CHANGELOG}: the heading of [{version}], released before this change, is gone"
                for version in sorted(released - headed)] if after else []
    for number in added:
        text = lines[number - 1]
        if not text.strip() or LINK_REFERENCE.match(text):
            continue
        version = next((match.group(1) for match in map(VERSION_HEADING.match, reversed(lines[:number]))
                        if match), None)
        if version in released:
            problems.append(f"{CHANGELOG}:{number}: {text!r} is under [{version}], released before this "
                            f"change; it belongs under ## [Unreleased]")
    return problems
# A download that failed in transit: a dropped connection or a TLS handshake the server ended (which
# no Gradle setting retries), a timeout, or a 408, 429 or 5xx that outlasted Gradle's own retries.
# Gradle's wording: when it moves, nothing matches and the build fails as it did before.
DOWNLOAD_FAILED = ("Could not GET '", "Could not HEAD '", "Could not download")
# A resolution that would fail the same way again.
NOT_TRANSIENT = re.compile(r"status code (?:401|403|404)\b|Could not find ")
FAILURE_BLOCK = re.compile(r"^\* What went wrong:\n(.*?)(?=^\* What went wrong:|^\* Try:|\Z)", re.M | re.S)


def download_failure(output):
    """The line naming the download a failed build could not make, or None when the build failed for
    any other reason too. Only the build's own failure blocks are read: a test whose build failed to
    resolve reports as failing tests, not as a download."""
    blocks = FAILURE_BLOCK.findall(output)
    found = None
    for block in blocks:
        lines = [line.strip().lstrip("> ") for line in block.splitlines()
                 if any(marker in line for marker in DOWNLOAD_FAILED)]
        if not lines or NOT_TRANSIENT.search(block):
            return None
        found = found or lines[-1]
    return found


def runnable(command, windows=None):
    """`command`, with a leading `./gradlew` as the batch file on Windows: CreateProcess cannot
    start the shell script."""
    if windows is None:
        windows = os.name == "nt"
    if windows and command[:1] == ["./gradlew"]:
        return [os.path.abspath("gradlew.bat")] + command[1:]
    return command


def run_teed(command):
    """Runs `command`, passing its output through as it comes; (exit code, output).

    The bytes pass through as written: decoded and re-encoded, a character a Windows console cannot
    encode raised there, losing the rest of the log and the build's exit code."""
    process = subprocess.Popen(runnable(command), stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    console = getattr(sys.stdout, "buffer", None)
    output = []
    for line in process.stdout:
        if console is None:
            sys.stdout.write(line.decode("utf-8", errors="replace"))
        else:
            sys.stdout.flush()
            console.write(line)
            console.flush()
        output.append(line)
    return process.wait(), b"".join(output).decode("utf-8", errors="replace")


def rerun_on_download_failure(command, pause=60, sleep=time.sleep):
    """Runs `command`, and once more after `pause` seconds when it failed on a dependency download
    alone; the exit code of the last run. The second run reuses what the first built."""
    code, output = run_teed(command)
    why = download_failure(output) if code != 0 else None
    if why is None:
        return code
    print(f"rerunning in {pause}s, once: the build failed on a dependency download: {why}", flush=True)
    sleep(pause)
    return run_teed(command)[0]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)

    rerun = sub.add_parser("rerun-on-download-failure",
                           help="run a build, and once more when it failed on a dependency download")
    rerun.add_argument("--pause", type=int, default=60, help="seconds before the rerun")
    rerun.add_argument("build", nargs=argparse.REMAINDER, help="-- then the command")

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

    changelog = sub.add_parser("changelog",
                               help="fail when a change adds a changelog line under a released version")
    changelog.add_argument("base")
    changelog.add_argument("head")

    args = parser.parse_args(argv)

    if args.command == "changelog":
        change = changelog_change(args.base, args.head)
        if change is None:
            print(f"git could not read {CHANGELOG} between {args.base} and {args.head}", file=sys.stderr)
            return 1
        problems = changelog_problems(*change)
        for problem in problems:
            print(problem)
        return 1 if problems else 0
    if args.command == "rerun-on-download-failure":
        build = args.build[1:] if args.build[:1] == ["--"] else args.build
        if not build:
            parser.error("rerun-on-download-failure needs a command after --")
        return rerun_on_download_failure(build, pause=args.pause)

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
