"""
Running one child build, and telling a slow one from a stuck one.

Split out of `run.py` when that file passed 1,100 lines, on a seam the review named: this is process
management and knows nothing about maps, mutations or recall. `run.py` re-exports every name here,
so `run.run`, `run.STALLED` and `run.stalled` keep working for everything that already reaches for
them.
"""

import os
import shutil
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
    `progress_paths` changed in that time, and when a build with a log runs past `timeout`: either
    way it was killed, and establishes nothing. With no log there is nothing to watch, the plain
    timeout is the only guard, and it returns -1.

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
            # KILLED AT THE TIMEOUT IS KILLED, as a stall is. Returning -1 here let a selected arm
            # that ran past the hour pass as a build that ran, and the harness went on to back up its
            # map: iceberg's shard lost two attempts to files vanishing under `copytree` there.
            if now - started > timeout:
                dumped = dump_threads(log_path)
                process.kill()
                process.wait()
                handle.flush()
                threads = (f"thread dumps of {dumped} JVMs beside it" if dumped
                           else "no JVM thread dump could be taken")
                print(f"  TIMED OUT: still running after {int(timeout)}s; killed. Log: {log_path}; "
                      f"{threads}", flush=True)
                return STALLED, round(time.time() - started, 1)
            size = os.path.getsize(log_path) if os.path.isfile(log_path) else 0
            mark = progress_mark(progress_paths)
            if size != last_size or mark != last_mark:
                last_size, last_mark, last_change = size, mark, now
            elif now - last_change > stall_seconds:
                dumped = dump_threads(log_path)
                process.kill()
                process.wait()
                handle.flush()
                # Said here rather than left to the caller's status line: the caller reports "did not
                # build", which is the one reading that sends someone to look at the wrong thing.
                quiet = (f"{int(stall_seconds / 60)} minutes" if stall_seconds >= 60
                         else f"{int(stall_seconds)}s")
                watched = " and nothing written under the paths it watches" if progress_paths else ""
                threads = (f"thread dumps of {dumped} JVMs beside it" if dumped
                           else "no JVM thread dump could be taken")
                print(f"  STALLED: no output for {quiet}{watched}; killed. "
                      f"Read the test worker's threads before assuming it is slow. Log: {log_path}; "
                      f"{threads}", flush=True)
                return STALLED, round(time.time() - started, 1)
    finally:
        handle.close()


def dump_threads(log_path):
    """Each running JVM's threads, beside the log, taken before a stalled build is killed.

    On a CI runner the build is gone by the time anyone reads the log, so whether the test worker
    was parked on a lock or working slowly can only be read from a dump taken here. Named
    `<log>.stall-<pid>.log` so a job that uploads its logs uploads them. Returns how many it wrote;
    never raises, since the kill must happen whatever this finds.
    """
    home = os.environ.get("JAVA_HOME")
    tools = [os.path.join(home, "bin", name) if home else shutil.which(name) for name in ("jps", "jstack")]
    if not all(tool and os.path.isfile(tool) for tool in tools):
        tools = [shutil.which("jps"), shutil.which("jstack")]
    jps, jstack = tools
    if not jps or not jstack:
        return 0
    try:
        listed = subprocess.run([jps, "-q"], capture_output=True, text=True, timeout=30).stdout
    except (OSError, subprocess.SubprocessError):
        return 0
    written = 0
    # Bounded as a whole: the kill waits on this, and a wedged JVM can take jstack to its timeout.
    deadline = time.time() + 120
    for pid in listed.split():
        if time.time() > deadline:
            break
        try:
            dump = subprocess.run([jstack, pid], capture_output=True, text=True, timeout=30)
        except (OSError, subprocess.SubprocessError):
            continue
        if dump.returncode == 0 and dump.stdout:
            with open(f"{log_path}.stall-{pid}.log", "w", encoding="utf-8") as handle:
                handle.write(dump.stdout)
            written += 1
    return written


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
