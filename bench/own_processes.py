"""Which Gradle processes on this machine belong to the benchmark harness, and stopping only those.

The harness reaps daemons between projects, and the pre-flight refuses while a build is running. Both
used to match every `GradleDaemon` on the machine, so a reap killed another project's builds and an
IDE's daemon, and the pre-flight refused over work that was not a measurement's.

A process is the harness's when the last build a Gradle daemon was asked to run, as its own log
records it, was in one of the harness's roots (this repository, whose `bench/checkouts/` the sweep
builds in, and the yoriwake checkout); failing that, when its working directory or command line lies
under one; or when its parent is one of the harness's. The log comes first because an idle daemon
works in its own state directory, and one daemon serves any project whose build is compatible: the
build it ran last is the one it belongs to now. A Kotlin compile daemon's working directory is its own state
directory, so it is claimed through its parent, the Gradle daemon that started it. One whose parent
has gone is claimed by no one, and is left alone.
"""

import os
import re
import signal
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import checkout as yoriwake_checkout  # noqa: E402

ARCHIVE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

GRADLE_MARKS = ("GradleDaemon", "GradleWorkerMain", "KotlinCompileDaemon")


def roots():
    """The trees whose builds are the harness's, plus an explicitly set GRADLE_USER_HOME."""
    found = [ARCHIVE, yoriwake_checkout.ROOT]
    home = os.environ.get("GRADLE_USER_HOME")
    if home and os.path.realpath(home) != os.path.realpath(os.path.expanduser("~/.gradle")):
        found.append(home)
    return [os.path.realpath(r) for r in found]


def parse_ps(text):
    """`ps -A -o pid=,ppid=,args=` as (pid, ppid, args) tuples; unparseable lines are skipped."""
    rows = []
    for line in text.splitlines():
        parts = line.split(None, 2)
        if len(parts) < 2 or not parts[0].isdigit() or not parts[1].isdigit():
            continue
        rows.append((int(parts[0]), int(parts[1]), parts[2] if len(parts) > 2 else ""))
    return rows


def parse_lsof_cwd(text):
    """`lsof -a -d cwd -Fpn -p ...` as {pid: cwd}."""
    found, pid = {}, None
    for line in text.splitlines():
        if line.startswith("p") and line[1:].isdigit():
            pid = int(line[1:])
        elif line.startswith("n") and pid is not None:
            found[pid] = line[1:]
    return found


def _under(path, bases):
    return any(path == base or path.startswith(base.rstrip(os.sep) + os.sep) for base in bases)


def owned(processes, cwds, bases, last_builds=None):
    """The pids among Gradle-marked `processes` that belong to `bases`, per the module docstring.

    `last_builds` maps a daemon's pid to the directory of the last build its log records.
    """
    gradle = [(pid, ppid, args) for pid, ppid, args in processes
              if any(mark in args for mark in GRADLE_MARKS)]
    last_builds = last_builds or {}
    ours = set()
    for pid, _ppid, args in gradle:
        if pid in last_builds:
            if _under(os.path.realpath(last_builds[pid]), bases):
                ours.add(pid)
            continue
        cwd = cwds.get(pid)
        cwd = os.path.realpath(cwd) if cwd else None
        if (cwd and _under(cwd, bases)) or any(base + os.sep in args for base in bases):
            ours.add(pid)
    # Children of ours are ours: a test worker or a Kotlin daemon started by one of our daemons.
    grew = True
    while grew:
        grew = False
        for pid, ppid, _args in gradle:
            if pid not in ours and ppid in ours:
                ours.add(pid)
                grew = True
    return ours


BUILD_COMMAND = re.compile(r"Received command: Build\{id=[^,]*, currentDir=([^}]*)\}")
# Enough of a daemon log's end to hold its last build command; the logs grow to tens of megabytes.
LOG_TAIL_BYTES = 8 * 2 ** 20


def last_build_dir(log_text):
    """The directory of the last build a daemon log records, or None."""
    found = BUILD_COMMAND.findall(log_text)
    return found[-1] if found else None


def daemon_log(pid, args):
    """Where a Gradle daemon's own log is: `<user home>/daemon/<version>/daemon-<pid>.out.log`."""
    words = args.split()
    if "org.gradle.launcher.daemon.bootstrap.GradleDaemon" not in words:
        return None
    at = words.index("org.gradle.launcher.daemon.bootstrap.GradleDaemon")
    version = words[at + 1] if at + 1 < len(words) else None
    homes = [os.environ.get("GRADLE_USER_HOME"), os.path.expanduser("~/.gradle")]
    homes += [w.split("/wrapper/dists/")[0].split(":")[-1] for w in words if "/wrapper/dists/" in w]
    for home in homes:
        if home and version:
            path = os.path.join(home, "daemon", version, f"daemon-{pid}.out.log")
            if os.path.isfile(path):
                return path
    return None


def _last_builds(processes):
    found = {}
    for pid, _ppid, args in processes:
        path = daemon_log(pid, args)
        if not path:
            continue
        try:
            with open(path, "rb") as handle:
                handle.seek(max(0, os.path.getsize(path) - LOG_TAIL_BYTES))
                directory = last_build_dir(handle.read().decode("utf-8", "replace"))
        except OSError:
            continue
        if directory:
            found[pid] = directory
    return found


def snapshot():
    """(processes, cwds, last builds) for every Gradle-marked process now. Empty without `ps`."""
    try:
        listing = subprocess.run(["ps", "-A", "-o", "pid=,ppid=,args="], capture_output=True,
                                 text=True).stdout
    except OSError:
        return [], {}, {}
    processes = parse_ps(listing)
    pids = [str(pid) for pid, _ppid, args in processes
            if any(mark in args for mark in GRADLE_MARKS)]
    cwds = {}
    if pids:
        try:
            cwds = parse_lsof_cwd(subprocess.run(
                ["lsof", "-a", "-d", "cwd", "-Fpn", "-p", ",".join(pids)],
                capture_output=True, text=True).stdout)
        except OSError:
            pass
    return processes, cwds, _last_builds(processes)


def split(processes=None, cwds=None, bases=None, last_builds=None):
    """(ours, foreign): the Gradle-marked processes as (pid, args), the harness's and everyone else's."""
    if processes is None:
        processes, cwds, last_builds = snapshot()
    ours = owned(processes, cwds or {}, bases or roots(), last_builds)
    gradle = [(pid, args) for pid, _ppid, args in processes
              if any(mark in args for mark in GRADLE_MARKS)]
    return ([p for p in gradle if p[0] in ours], [p for p in gradle if p[0] not in ours])


def reap_own(kill=os.kill):
    """Sends SIGTERM to the harness's own Gradle processes. Returns the pids signalled."""
    ours, _foreign = split()
    signalled = []
    for pid, _args in ours:
        try:
            kill(pid, signal.SIGTERM)
            signalled.append(pid)
        except (ProcessLookupError, PermissionError):
            pass
    return signalled
