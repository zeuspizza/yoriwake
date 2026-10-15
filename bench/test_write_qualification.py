"""
Tests for the collator, whose entire job is telling a verdict from a dead attempt.

`qualification.txt` has two failure sections and they mean opposite things. BLOCKED is a statement
about a project: it cannot produce evidence, do not try again. UNRESOLVED is a statement about a
RUN: the attempt died, nothing was learned, try again on a quiet machine. Filing one as the other in
the BLOCKED direction writes off a usable project permanently.

The signatures that separate them were all Windows -- 0xC0000142, -1 as unsigned, and Gradle's
daemon message -- so an OOM kill filed as UNRESOLVED on the machine this corpus was built on and as
BLOCKED on any other. That is the shape these tests exist to prevent.

Cases are named case_* and RETURN a count of failed checks. The single test_* entry point asserts.
"""

import os
import tempfile
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import write_qualification as collator  # noqa: E402


def case_a_posix_signal_death_is_an_attempt_that_died():
    """SIGKILL is what an OOM killer sends, and Python reports it as a negative exit code.

    Every memory incident in this project's history looks like this on macOS and Linux. None of the
    literal Windows signatures matches it.
    """
    failures = 0
    for text in ("the build failed (exit -9) before anything was captured",
                 "the run produced no test results (exit -15)"):
        if not collator.died_rather_than_failed(text):
            print(f"  FAIL: a signal death read as a verdict about the project: {text}")
            failures += 1
    return failures


def case_a_windows_kill_is_still_recognised():
    """The signatures the corpus was built with must keep working."""
    failures = 0
    for text in ("the build failed (exit 4294967295) before anything was captured",
                 "the build failed (exit 3221225794) before anything was captured",
                 "Gradle build daemon disappeared unexpectedly"):
        if not collator.died_rather_than_failed(text):
            print(f"  FAIL: a known dead attempt stopped being recognised: {text}")
            failures += 1
    return failures


def case_a_precondition_refusal_is_an_attempt_that_never_began():
    """One project's missing toolchain says nothing about that project."""
    failures = 0
    text = "the attempt could not start: needs a JDK 26 daemon and none was found"
    if not collator.died_rather_than_failed(text):
        print("  FAIL: a precondition refusal was filed as a verdict about the project")
        failures += 1
    return failures


def case_an_ordinary_failure_is_a_verdict():
    """The direction that matters most: a real blocker must NOT be excused as a dead attempt.

    `exit 1` is a build that ran and failed. Gradle also says "could not start" about daemons and
    workers for reasons that ARE verdicts, which is why the precondition signature is anchored to
    the full sentence qualify.py writes rather than to those three words.
    """
    failures = 0
    for text in ("the build failed (exit 1) before anything was captured",
                 "maven, not gradle -- the plugin is a Gradle plugin",
                 "the build failed (exit 1): Process 'Gradle Test Executor 2' could not start"):
        if collator.died_rather_than_failed(text):
            print(f"  FAIL: a real verdict was excused as a dead attempt: {text}")
            failures += 1
    return failures


def case_no_blocker_at_all_is_not_a_dead_attempt():
    failures = 0
    for text in (None, ""):
        if collator.died_rather_than_failed(text):
            print(f"  FAIL: {text!r} read as a dead attempt")
            failures += 1
    return failures



def case_the_stamp_refuses_a_dirty_checkout():
    """Behavioural, not a grep of this module's own source.

    A stamp names a commit of the yoriwake checkout, so a checkout with any uncommitted change --
    tracked or untracked -- is refused, and the refusal names the file.
    """
    failed = 0
    import subprocess
    with tempfile.TemporaryDirectory() as repo:
        def git(*args):
            return subprocess.run(["git", "-C", repo, *args], capture_output=True, text=True)

        git("init", "-q")
        git("config", "user.email", "t@t")
        git("config", "user.name", "t")
        for path in ("plugin/Main.kt", "docs/notes.md"):
            os.makedirs(os.path.join(repo, os.path.dirname(path)), exist_ok=True)
            with open(os.path.join(repo, path), "w", encoding="utf-8") as handle:
                handle.write("original\n")
        git("add", "-A")
        git("commit", "-qm", "baseline")

        head = git("rev-parse", "--short", "HEAD").stdout.strip()
        real_source = collator.SOURCE
        try:
            collator.SOURCE = repo

            if collator.plugin_commit() != head:
                failed += 1
                print(f"  FAIL: a clean checkout did not stamp its HEAD {head}")

            def refusal():
                try:
                    collator.plugin_commit()
                except SystemExit as refused:
                    return str(refused)
                return None

            with open(os.path.join(repo, "docs/notes.md"), "w", encoding="utf-8") as handle:
                handle.write("edited\n")
            said = refusal()
            if not said or "docs/notes.md" not in said:
                failed += 1
                print(f"  FAIL: a modified file was not refused by name: {said!r}")
            git("checkout", "--", "docs/notes.md")

            with open(os.path.join(repo, "plugin/Untracked.kt"), "w", encoding="utf-8") as handle:
                handle.write("new\n")
            said = refusal()
            if not said or "plugin/Untracked.kt" not in said:
                failed += 1
                print(f"  FAIL: an untracked file was not refused by name: {said!r}")
        finally:
            collator.SOURCE = real_source
    return failed


CASES = (case_the_stamp_refuses_a_dirty_checkout,
         case_a_posix_signal_death_is_an_attempt_that_died,
         case_a_windows_kill_is_still_recognised,
         case_a_precondition_refusal_is_an_attempt_that_never_began,
         case_an_ordinary_failure_is_a_verdict,
         case_no_blocker_at_all_is_not_a_dead_attempt)


def main():
    failed = 0
    for case in CASES:
        print(case.__name__)
        failed += case()
    if failed:
        print(f"\nFAILURES: {failed} failed checks")
    else:
        print("\nall passed: 0 failed checks")
    return failed


def test_write_qualification():
    assert main() == 0


if __name__ == "__main__":
    sys.exit(1 if main() else 0)
