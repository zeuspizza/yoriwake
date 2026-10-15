"""
Running one child build, and telling a slow one from a stuck one.

Split out of `run.py` when that file passed 1,100 lines, on a seam the review named: this is process
management and knows nothing about maps, mutations or recall. `run.py` re-exports every name here,
so `run.run`, `run.STALLED` and `run.stalled` keep working for everything that already reaches for
them.
"""

import os
import subprocess
import time


STALLED = -2

# A build that has said nothing for this long is not slow, it is stuck.
#
# spring-core taught this: an async file-channel test parked on a latch nothing counted down, and
# the only thing that would have ended the run was the 3600s timeout. Nineteen minutes of silence
# had already answered the question, and one jstack confirmed it -- the test worker was at a tenth
# of a core with no file in the checkout written for two minutes.
#
# Ten minutes is chosen to be longer than anything legitimate that prints nothing. Gradle emits a
# task line per task, so even a long dependency download or a slow compile keeps writing; the
# quietest legitimate phase measured here is a single test class that runs for minutes without
# per-test output, which is nowhere near this.
#
# THAT ASSUMPTION WAS FALSE, and `progress_paths` below is the repair rather than a bigger number.
# Gradle prints NOTHING for the whole of a long test task under `--console=plain` -- not a line per
# test, not a line per class. armeria's `:core:test` started 728 test classes and was killed here at
# twenty minutes while it was demonstrably working: its worker held a tenth of a core and its
# results binary had been written a minute before the kill. Raising the window would only move the
# suite that trips it; watching what the build WRITES tells a slow build from a stuck one directly.
STALL_SECONDS = 600

# How often the guard looks. Long enough to cost nothing on an hour-long build, and a named constant
# rather than a literal because the tests must be able to drive a real stall in under a second --
# with it hard-coded, a test whose command exits inside one poll never reaches the check at all, and
# passes whether or not the guard works. The first version of the progress test did exactly that.
POLL_SECONDS = 15


def progress_mark(paths):
    """A cheap fingerprint of files a working build keeps touching while it says nothing.

    Deliberately NOT a recursive walk. Callers pass a small directory -- Gradle's
    `build/test-results/<task>/binary`, two files that grow for the whole of a test task -- and a
    walk of a large build tree every fifteen seconds would cost more than the guard is worth.
    Unreadable or absent paths contribute nothing rather than raising: a build that has not created
    its results directory yet is exactly the case this must survive.
    """
    marks = []
    for path in paths:
        try:
            if os.path.isdir(path):
                for entry in os.scandir(path):
                    info = entry.stat()
                    marks.append((entry.name, info.st_mtime_ns, info.st_size))
            elif os.path.exists(path):
                info = os.stat(path)
                marks.append((path, info.st_mtime_ns, info.st_size))
        except OSError:
            continue
    return tuple(sorted(marks))


def run(command, cwd, log_path=None, timeout=3600, stall_seconds=STALL_SECONDS,
        progress_paths=(), poll_seconds=POLL_SECONDS, env=None):
    """Runs a command, returning (exit code, seconds). Output goes to log_path when given.

    Returns STALLED when the log stops growing for `stall_seconds` AND nothing under
    `progress_paths` changed in that time. Only possible when a log path is given -- with no log
    there is nothing to watch, and the plain timeout is the only guard.

    `progress_paths` exists because silence on stdout is not evidence of a stuck build: Gradle says
    nothing at all for the duration of a long test task. Pass the files that build writes while it
    works and a slow suite stops looking like spring-core parked on a latch. Passing none preserves
    the old behaviour exactly.
    """
    started = time.time()
    if log_path is None:
        try:
            process = subprocess.run(command, cwd=cwd, stdout=subprocess.DEVNULL,
                                     stderr=subprocess.STDOUT, timeout=timeout)
            return process.returncode, round(time.time() - started, 1)
        except subprocess.TimeoutExpired:
            return -1, round(time.time() - started, 1)

    # The log directory is gitignored, so on a fresh clone it does not exist and every caller here
    # dies on its FIRST command with a FileNotFoundError naming a log rather than the work. Created
    # here rather than in each caller: they all pass a path under `bench/logs/`, and one of
    # them creating it only hides the failure from whichever runs second.
    os.makedirs(os.path.dirname(os.path.abspath(log_path)), exist_ok=True)
    handle = open(log_path, "w", encoding="utf-8", errors="replace")
    try:
        process = subprocess.Popen(command, cwd=cwd, stdout=handle, stderr=subprocess.STDOUT, env=env)
        last_size, last_change = -1, time.time()
        last_mark = progress_mark(progress_paths)
        while True:
            try:
                return process.wait(timeout=poll_seconds), round(time.time() - started, 1)
            except subprocess.TimeoutExpired:
                pass
            now = time.time()
            if now - started > timeout:
                process.kill()
                process.wait()
                return -1, round(time.time() - started, 1)
            size = os.path.getsize(log_path) if os.path.isfile(log_path) else 0
            mark = progress_mark(progress_paths)
            if size != last_size or mark != last_mark:
                last_size, last_mark, last_change = size, mark, now
            elif now - last_change > stall_seconds:
                process.kill()
                process.wait()
                handle.flush()
                # Said here rather than left to the caller's status line: the caller reports "did not
                # build", which is the one reading that sends someone to look at the wrong thing.
                quiet = (f"{int(stall_seconds / 60)} minutes" if stall_seconds >= 60
                         else f"{int(stall_seconds)}s")
                watched = " and nothing written under the paths it watches" if progress_paths else ""
                print(f"  STALLED: no output for {quiet}{watched}; killed. "
                      f"jstack the test worker before assuming it is slow. Log: {log_path}",
                      flush=True)
                return STALLED, round(time.time() - started, 1)
    finally:
        handle.close()


def stalled(code, label, findings, phase):
    """Files a killed build as its own finding, and says the row must be skipped.

    `run()` has returned STALLED since spring-core parked forever on a latch, and NOTHING checked
    it. Every arm here discarded the exit code -- `_, seconds = gradle(...)` -- and a killed
    process leaves a log with no `FAILURE: Build failed` banner in it, so `executed_or_excluded`
    filed the row as **"cache-hit"**: a hung build that was killed after ten minutes of silence,
    recorded as the benign thing that means "nothing was going to run anyway".

    Two different things sharing one representation, reported as the harmless one. That is the
    defect this harness exists to catch, one level up, and it was in the harness.
    """
    if code != STALLED:
        return False
    findings.append({"target": label, "status": "stalled", "phase": phase})
    print(f"    STALLED during the {phase} run; the row is skipped rather than counted",
          flush=True)
    return True
