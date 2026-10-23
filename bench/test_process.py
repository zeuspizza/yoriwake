"""
Tests for the process runner, which had no tests at all and one portability bug because of it.

`bench/logs/` is gitignored, so on a fresh clone it does not exist -- and `run` opened its log
there without creating the directory. Every caller died on its FIRST command with a
FileNotFoundError naming a log file rather than the work it was trying to do. A machine that had
ever run the harness before could not reproduce it.

Cases are named case_* and RETURN a count of failed checks, following test_run.py: a function that
returned instead of asserting would be collected by pytest and pass however many checks failed. The
single test_* entry point below asserts.
"""

import contextlib
import io
import os
import shutil
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import process  # noqa: E402


def case_a_log_directory_that_does_not_exist_yet_is_created():
    """The fresh-clone case. `bench/logs/` is gitignored and nothing else creates it."""
    failures = 0
    scratch = tempfile.mkdtemp(prefix="yoriwake-process-tests-")
    try:
        log = os.path.join(scratch, "does", "not", "exist", "yet", "run.log")
        code, seconds = process.run([sys.executable, "-c", "print('ran')"], scratch, log)
        if code != 0:
            print(f"  FAIL: the command did not run (exit {code})")
            failures += 1
        if not os.path.isfile(log):
            print(f"  FAIL: no log was written at {log}")
            return failures + 1
        with open(log, encoding="utf-8") as handle:
            if "ran" not in handle.read():
                print("  FAIL: the log does not carry the command's output")
                failures += 1
        if seconds < 0:
            print(f"  FAIL: negative duration {seconds}")
            failures += 1
    finally:
        shutil.rmtree(scratch, ignore_errors=True)
    return failures


def case_a_log_path_with_no_directory_component_still_runs():
    """A bare filename has no parent to create; makedirs must not choke on the empty string."""
    failures = 0
    scratch = tempfile.mkdtemp(prefix="yoriwake-process-tests-")
    cwd = os.getcwd()
    try:
        os.chdir(scratch)
        code, _ = process.run([sys.executable, "-c", "print('bare')"], scratch, "bare.log")
        if code != 0:
            print(f"  FAIL: the command did not run (exit {code})")
            failures += 1
        if not os.path.isfile(os.path.join(scratch, "bare.log")):
            print("  FAIL: no log was written for a bare filename")
            failures += 1
    finally:
        os.chdir(cwd)
        shutil.rmtree(scratch, ignore_errors=True)
    return failures


def case_a_nonzero_exit_is_returned_rather_than_raised():
    """A red test suite exits non-zero and is still a measurement. `run` reports, never raises."""
    failures = 0
    scratch = tempfile.mkdtemp(prefix="yoriwake-process-tests-")
    try:
        log = os.path.join(scratch, "logs", "failing.log")
        code, _ = process.run([sys.executable, "-c", "raise SystemExit(3)"], scratch, log)
        if code != 3:
            print(f"  FAIL: expected exit 3 to be returned, got {code}")
            failures += 1
    finally:
        shutil.rmtree(scratch, ignore_errors=True)
    return failures




def case_a_silent_command_that_writes_progress_is_not_stalled():
    """The armeria case. Gradle prints nothing for the whole of a long test task.

    Before `progress_paths`, silence on stdout WAS the stall signal, so a suite that had said
    nothing for the window was killed however hard it was working. This drives the shape directly:
    a command that never writes to its log but keeps touching a watched file must survive.
    """
    failures = 0
    scratch = tempfile.mkdtemp(prefix="yoriwake-process-tests-")
    try:
        watched = os.path.join(scratch, "binary")
        os.makedirs(watched)
        marker = os.path.join(watched, "results.bin").replace("\\", "/")
        # Says nothing, writes steadily, then exits 0 -- a long silent test task in miniature.
        script = (
            "import time\n"
            "for i in range(20):\n"
            f"    open({marker!r}, 'a').write('x')\n"
            "    time.sleep(0.1)\n"
        )
        code, _ = process.run([sys.executable, "-c", script], scratch,
                              os.path.join(scratch, "run.log"),
                              stall_seconds=0.5, progress_paths=[watched], poll_seconds=0.1)
        if code == process.STALLED:
            print("  FAIL: a working build that wrote progress was killed as stalled")
            failures += 1
        elif code != 0:
            print(f"  FAIL: expected a clean exit, got {code}")
            failures += 1
    finally:
        shutil.rmtree(scratch, ignore_errors=True)
    return failures


def case_a_silent_command_that_writes_nothing_is_still_stalled():
    """The guard must keep working. spring-core parked forever on a latch and only this caught it."""
    failures = 0
    scratch = tempfile.mkdtemp(prefix="yoriwake-process-tests-")
    try:
        watched = os.path.join(scratch, "binary")
        os.makedirs(watched)
        code, _ = process.run([sys.executable, "-c", "import time; time.sleep(60)"], scratch,
                              os.path.join(scratch, "run.log"),
                              stall_seconds=0.5, progress_paths=[watched], poll_seconds=0.1)
        if code != process.STALLED:
            print(f"  FAIL: a genuinely silent, idle build was not caught (exit {code})")
            failures += 1
    finally:
        shutil.rmtree(scratch, ignore_errors=True)
    return failures


def case_a_stalled_build_is_dumped_before_it_is_killed():
    """On a runner the build is gone by the time the log is read; only a dump taken first says
    whether the test worker was parked or slow."""
    failures = 0
    scratch = tempfile.mkdtemp(prefix="yoriwake-process-tests-")
    calls = []
    original = process.dump_threads
    process.dump_threads = lambda log_path: calls.append(log_path) or []
    try:
        log = os.path.join(scratch, "run.log")
        code, _ = process.run([sys.executable, "-c", "import time; time.sleep(60)"], scratch, log,
                              stall_seconds=0.5, poll_seconds=0.1)
        if code != process.STALLED or calls != [log]:
            print(f"  FAIL: a stalled build was not dumped before the kill (exit {code}, {calls})")
            failures += 1
    finally:
        process.dump_threads = original
        shutil.rmtree(scratch, ignore_errors=True)
    return failures


def case_a_build_killed_at_the_timeout_is_filed_as_stalled():
    """A build that never goes quiet and runs past the limit was killed, and measured nothing."""
    failures = 0
    scratch = tempfile.mkdtemp(prefix="yoriwake-process-tests-")
    calls = []
    original = process.dump_threads
    process.dump_threads = lambda log_path: calls.append(log_path) or []
    try:
        log = os.path.join(scratch, "run.log")
        busy = "import time\nwhile True:\n    print('.', flush=True)\n    time.sleep(0.05)"
        code, _ = process.run([sys.executable, "-c", busy], scratch, log, timeout=1,
                              stall_seconds=60, poll_seconds=0.1)
        if code != process.STALLED or calls != [log]:
            print(f"  FAIL: a build killed at the timeout was not dumped and filed as stalled "
                  f"(exit {code}, {calls})")
            failures += 1
        code, _ = process.run([sys.executable, "-c", "print('ok')"], scratch, log, timeout=30,
                              poll_seconds=0.1)
        if code != 0:
            print(f"  FAIL: a build that finished inside the timeout returned {code}")
            failures += 1
    finally:
        process.dump_threads = original
        shutil.rmtree(scratch, ignore_errors=True)
    return failures


def case_a_watched_path_that_does_not_exist_is_survivable():
    """A test task that has not created its results directory yet is the normal starting state."""
    failures = 0
    scratch = tempfile.mkdtemp(prefix="yoriwake-process-tests-")
    try:
        missing = os.path.join(scratch, "never", "created")
        code, _ = process.run([sys.executable, "-c", "print('ok')"], scratch,
                              os.path.join(scratch, "run.log"), progress_paths=[missing])
        if code != 0:
            print(f"  FAIL: an absent watched path broke the run (exit {code})")
            failures += 1
    finally:
        shutil.rmtree(scratch, ignore_errors=True)
    return failures



def _dump(directory, pid, threads):
    """A jstack dump with these (name, cpu seconds) threads, all `elapsed` 2476 s."""
    path = os.path.join(directory, f"capture.log.stall-{pid}.log")
    with open(path, "w", encoding="utf-8") as handle:
        handle.write("Full thread dump OpenJDK 64-Bit Server VM:\n\n")
        for i, (name, seconds) in enumerate(threads):
            handle.write(f'"{name}" #{i} prio=5 os_prio=0 cpu={seconds * 1000:.2f}ms elapsed=2476.25s '
                         f'tid=0x0 nid={i} runnable\n\n')
    return path


def case_a_killed_jvm_says_where_its_cpu_went():
    """bitwarden-android's test JVM was killed for silence while it collected garbage; seven dumps had
    to be read by hand to see it."""
    failures = 0
    scratch = tempfile.mkdtemp(prefix="yoriwake-process-tests-")
    try:
        cases = (
            ("9261", [("GC Thread#0", 1800), ("G1 Conc#0", 618), ("VM Thread", 1492), ("Test worker", 139),
                      ("C2 CompilerThread0", 550)], "test worker 9261: GC 53%, VM thread 32%, other 15% -- collecting garbage"),
            ("1", [("VM Thread", 900), ("GC Thread#0", 100), ("main", 300)], "at safepoints"),
            ("2", [("GC Thread#0", 400), ("VM Thread", 400), ("main", 600)], "collecting garbage or at safepoints"),
            ("3", [("main", 20), ("GC Thread#0", 10)], "parked"),
            ("4", [("Test worker", 2000), ("GC Thread#0", 300), ("VM Thread", 100)], "working"),
        )
        for pid, threads, expected in cases:
            got = process.describe_dumps([_dump(scratch, pid, threads)])
            if expected not in got:
                print(f"  FAIL: JVM {pid} read as {got!r}, expected {expected!r}")
                failures += 1
        old = os.path.join(scratch, "capture.log.stall-5.log")
        with open(old, "w", encoding="utf-8") as handle:
            handle.write('"main" #1 prio=5 os_prio=0 tid=0x0 nid=1 runnable\n')
        if process.describe_dumps([old]) != "":
            print("  FAIL: a dump without cpu= figures was read as something")
            failures += 1
        both = process.describe_dumps([_dump(scratch, "7", [("main", 900)]),
                                       _dump(scratch, "8", [("Test worker", 900)])])
        if not both.startswith("test worker 8"):
            print(f"  FAIL: the test JVM is not listed first: {both!r}")
            failures += 1
    finally:
        shutil.rmtree(scratch, ignore_errors=True)
    return failures


def case_a_stall_line_carries_the_reading_and_prints_without_one():
    failures = 0
    scratch = tempfile.mkdtemp(prefix="yoriwake-process-tests-")
    original = process.dump_threads
    try:
        log = os.path.join(scratch, "run.log")
        for dumps, expected in (([_dump(scratch, "9", [("Test worker", 10), ("GC Thread#0", 900)])],
                                 "test worker 9: GC 99%"), ([], "no JVM thread dump could be taken")):
            process.dump_threads = lambda log_path, dumps=dumps: dumps
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                process.run([sys.executable, "-c", "import time; time.sleep(60)"], scratch, log,
                            stall_seconds=0.5, poll_seconds=0.1)
            if expected not in out.getvalue():
                print(f"  FAIL: the STALLED line lacks {expected!r}: {out.getvalue()!r}")
                failures += 1
    finally:
        process.dump_threads = original
        shutil.rmtree(scratch, ignore_errors=True)
    return failures

CASES = (case_a_log_directory_that_does_not_exist_yet_is_created,
         case_a_log_path_with_no_directory_component_still_runs,
         case_a_nonzero_exit_is_returned_rather_than_raised,
         case_a_silent_command_that_writes_progress_is_not_stalled,
         case_a_silent_command_that_writes_nothing_is_still_stalled,
         case_a_stalled_build_is_dumped_before_it_is_killed,
         case_a_build_killed_at_the_timeout_is_filed_as_stalled,
         case_a_killed_jvm_says_where_its_cpu_went,
         case_a_stall_line_carries_the_reading_and_prints_without_one,
         case_a_watched_path_that_does_not_exist_is_survivable)


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


def test_process():
    assert main() == 0


if __name__ == "__main__":
    sys.exit(1 if main() else 0)
