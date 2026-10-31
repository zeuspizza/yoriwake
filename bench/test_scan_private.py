"""Tests for `scan_private.py`: each kind of private reference fails the scan where it applies.

Cases return a count of failed checks; one `test_*` entry point asserts.

    python3 bench/test_scan_private.py
"""

import os
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import scan_private  # noqa: E402

SEEDS = {
    "plan unit": "the order follows U31",
    "plan number": "as plan 2111 decided",
    "decision id": "see KTD4",
    "requirement id": "this is R2b",
    "date": "measured on 2026-09-07",
    "private document": "explained in BENCHMARKS.md",
    "private anchor": "see the 4a-sexies entry",
    "private repository": "filed in yoriwake-labs",
    "home path": "read from /Users/someone/checkouts",
    "spike": "the spike showed it",
    "private tool": "counted with session_tokens",
    "assistant": "written with Claude's help",
}


def fail(message):
    print(f"  FAIL: {message}")
    return 1


def _repo(root):
    def git(*args):
        subprocess.run(["git", "-C", root, *args], check=True, capture_output=True)

    git("init", "-q", "-b", "main")
    git("config", "user.email", "test@example.com")
    git("config", "user.name", "test")
    return git


def _write(root, path, text):
    os.makedirs(os.path.dirname(os.path.join(root, path)) or root, exist_ok=True)
    with open(os.path.join(root, path), "w", encoding="utf-8") as handle:
        handle.write(text)


def case_each_seed_fails_in_each_scanned_place():
    failures = 0
    for place in ("bench/run.py", "docs/guide.md", "CONTRIBUTING.md", ".github/workflows/x.yml",
                  "scripts/prove_guards.py"):
        for rule, seed in SEEDS.items():
            with tempfile.TemporaryDirectory() as root:
                _write(root, place, f"clean line\n{seed}\n")
                hits = scan_private.scan_paths(root, [place])
                expected = not (rule == "date" and place.startswith("docs/"))
                if bool(hits) != expected:
                    failures += fail(f"{rule} in {place}: {hits}")
                elif hits and f"{place}:2: {rule}:" not in hits[0]:
                    failures += fail(f"{rule} in {place} reported as {hits[0]}")
    return failures


def case_a_clean_tree_and_unscanned_places_pass():
    with tempfile.TemporaryDirectory() as root:
        _write(root, "bench/run.py", "def run():\n    return 'U-turn, R-value, UTF-8, R2D2-free'\n")
        _write(root, "plugin/Main.kt", "// KTD4, 2026-09-07\n")
        _write(root, "docs/benchmarks.md", "Measured 2026-10-01 to 2026-10-03.\n")
        # The guard harness is public, so its name is not a private reference.
        _write(root, "CONTRIBUTING.md", "Run `python3 scripts/prove_guards.py --mode fast`.\n")
        hits = scan_private.scan_paths(root, ["bench", "docs", "plugin", "CONTRIBUTING.md"])
        return fail(f"clean tree: {hits}") if hits else 0


def case_a_range_scans_changed_paths_and_commit_messages():
    failures = 0
    with tempfile.TemporaryDirectory() as root:
        git = _repo(root)
        _write(root, "bench/old.py", "see KTD4\n")
        git("add", "-A")
        git("commit", "-q", "-m", "base")
        git("tag", "base")

        _write(root, "bench/new.py", "clean\n")
        git("add", "-A")
        git("commit", "-q", "-m", "harness: the sweep, as U31 asked")
        hits = scan_private.scan_range(root, "base", "HEAD")
        if len(hits) != 1 or not hits[0].startswith("commit ") or "plan unit" not in hits[0]:
            failures += fail(f"a plan id in a commit message: {hits}")

        git("commit", "-q", "--amend", "-m", "harness: the sweep, measured 2026-09-07")
        hits = scan_private.scan_range(root, "base", "HEAD")
        if hits:
            failures += fail(f"an unchanged file or a dated message is not the push's: {hits}")

        os.remove(os.path.join(root, "bench/old.py"))
        git("add", "-A")
        git("commit", "-q", "-m", "drop old")
        if scan_private.scan_range(root, "base", "HEAD"):
            failures += fail("a deleted file is not scanned")

        _write(root, "docs/guide.md", "see KTD4\n")
        git("add", "-A")
        git("commit", "-q", "-m", "guide")
        _write(root, "docs/guide.md", "clean\n")
        git("add", "-A")
        git("commit", "-q", "-m", "guide, reworded")
        hits = scan_private.scan_range(root, "base", "HEAD")
        if len(hits) != 1 or "docs/guide.md:1: decision id" not in hits[0]:
            failures += fail(f"a line a later commit removes is still in the pushed history: {hits}")
        git("reset", "-q", "--hard", "HEAD~2")

        if scan_private.main(["--range", "base", "HEAD"], root) != 0:
            failures += fail("a clean range exits 0")
        _write(root, ".github/ci.yml", "home: /home/runner-private\n")
        git("add", "-A")
        git("commit", "-q", "-m", "ci")
        if scan_private.main(["--range", "base", "HEAD"], root) != 1:
            failures += fail("a range with a hit exits 1")
    return failures


def case_a_directory_scan_skips_what_git_ignores():
    with tempfile.TemporaryDirectory() as root:
        _repo(root)
        _write(root, ".gitignore", "bench/logs/\n")
        _write(root, "bench/logs/demo/run.log", "map=/Users/someone/x\n")
        _write(root, "bench/run.py", "clean\n")
        hits = scan_private.scan_paths(root, ["bench"])
        return fail(f"an ignored log was scanned: {hits}") if hits else 0


def case_a_file_whose_name_git_quotes_is_still_scanned():
    with tempfile.TemporaryDirectory() as root:
        git = _repo(root)
        _write(root, "bench/base.py", "clean\n")
        git("add", "-A")
        git("commit", "-q", "-m", "base")
        git("tag", "base")
        _write(root, "docs/caf\u00e9 notes.md", "see KTD4\n")
        git("add", "-A")
        git("commit", "-q", "-m", "notes")
        hits = scan_private.scan_range(root, "base", "HEAD")
        failures = 0 if any("decision id" in hit for hit in hits) else fail(
            f"a private reference in a file git quotes was not scanned: {hits}")
        _write(root, 'docs/a "quoted" name.md', "clean\n")
        git("add", "-A")
        git("commit", "-q", "-m", "quoted")
        hits = scan_private.scan_range(root, "HEAD~1", "HEAD")
        if not any("unscannable path" in hit for hit in hits):
            failures += fail(f"a path git must still quote passed unscanned: {hits}")
        return failures


def case_a_pull_request_title_or_body_is_scanned_as_a_message():
    failures = 0
    if not scan_private.scan_message("pull request title", "Sweep in shards, as U31 asked"):
        failures += fail("a plan id in a pull request title passed")
    if scan_private.scan_message("pull request body", "Measured on 2026-10-01; see docs/."):
        failures += fail("a date in a pull request body was refused, as it is in a commit message")
    with tempfile.TemporaryDirectory() as root:
        os.environ["SCAN_TITLE"], os.environ["SCAN_BODY"] = "clean", "see KTD4"
        try:
            if scan_private.main(["--message", "SCAN_TITLE", "SCAN_BODY"], root) != 1:
                failures += fail("--message did not fail on a decision id in the body")
        finally:
            del os.environ["SCAN_TITLE"], os.environ["SCAN_BODY"]
    return failures


def case_a_fork_may_name_the_assistant_in_its_messages_only():
    failures = 0
    with tempfile.TemporaryDirectory() as root:
        git = _repo(root)
        _write(root, "bench/base.py", "clean\n")
        git("add", "-A")
        git("commit", "-q", "-m", "base")
        git("tag", "base")
        _write(root, "bench/new.py", "clean\n")
        git("add", "-A")
        git("commit", "-q", "-m", "fix\n\nCo-Authored-By: Claude <noreply@example.com>")
        if scan_private.main(["--range", "base", "HEAD"], root) != 1:
            failures += fail("the assistant named in a commit message on a branch here passed")
        if scan_private.main(["--fork", "--range", "base", "HEAD"], root) != 0:
            failures += fail("a fork's commit message naming the assistant was refused")
        _write(root, "docs/x.md", "written with Claude\n")
        git("add", "-A")
        git("commit", "-q", "-m", "docs")
        if scan_private.main(["--fork", "--range", "base", "HEAD"], root) != 1:
            failures += fail("the assistant named in a fork's file passed")

        os.environ["SCAN_TITLE"], os.environ["SCAN_BODY"] = "clean", "Written with Claude Code."
        try:
            if scan_private.main(["--message", "SCAN_TITLE", "SCAN_BODY"], root) != 1:
                failures += fail("the assistant named in a pull request body here passed")
            if scan_private.main(["--fork", "--message", "SCAN_TITLE", "SCAN_BODY"], root) != 0:
                failures += fail("a fork's pull request body naming the assistant was refused")
            os.environ["SCAN_BODY"] = "Written with Claude Code, see KTD4."
            if scan_private.main(["--fork", "--message", "SCAN_TITLE", "SCAN_BODY"], root) != 1:
                failures += fail("a decision id in a fork's pull request body passed")
        finally:
            del os.environ["SCAN_TITLE"], os.environ["SCAN_BODY"]

        for argv in (["--fork"], ["--fork", "--bogus"]):
            if scan_private.main(argv, root) != 2:
                failures += fail(f"{argv} did not print usage")
    return failures


def case_the_scan_does_not_flag_itself():
    here = os.path.dirname(os.path.abspath(__file__))
    hits = scan_private.scan_paths(os.path.dirname(here), ["bench/scan_private.py", "bench/test_scan_private.py"])
    return fail(f"self: {hits}") if hits else 0


def test_scan_private():
    failures = 0
    for name, case in sorted(globals().items()):
        if name.startswith("case_"):
            print(name)
            failures += case()
    assert failures == 0, f"{failures} check(s) failed"


if __name__ == "__main__":
    test_scan_private()
    print("ok")
