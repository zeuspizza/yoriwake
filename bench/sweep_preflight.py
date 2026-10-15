"""
Checks the machine before the corpus sweep, then runs the sweep with sleep prevented.

A 30-hour measurement lost to a full disk, a laptop on battery going to sleep, a dirty checkout or
a second measurement already running costs the whole sweep and says so only at the end, if at all.
Each check below refuses rather than warns; `--force` is deliberately absent.

    python bench/sweep_preflight.py --commit <sha>                 # check only
    python bench/sweep_preflight.py --commit <sha> -- python bench/sweep_corpus.py --limit 16

The disk requirement is 3x the current `bench/logs` plus 2x the map directories in the corpus
checkouts (one per arm) plus the evidence archives, as docs/sweep-data-dictionary.md prices it. A
sweep run with `--only` is priced from those projects' own logs and maps: a one-project leg asked
for three times the whole corpus's logs and was refused with ample room left.
"""

import argparse
import os
import platform
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ARCHIVE = os.path.dirname(HERE)
sys.path.insert(0, HERE)
import checkout as yoriwake_checkout  # noqa: E402
import own_processes  # noqa: E402

LOGS = os.path.join(HERE, "logs")
CHECKOUTS = os.path.join(HERE, "checkouts")
GIB = 2 ** 30
# Measured on spring-beans (docs/sweep-data-dictionary.md): a capture's map and raw records
# compress to about 11% of their size, and each row adds about 0.25 MiB of raw records and logs.
EVIDENCE_RATIO = 0.12
# This repository's own measurement scripts. Gradle processes are
# judged by `own_processes`: only the harness's own builds are a measurement to refuse over.
MEASURING = ("bench/run.py", "sweep_corpus.py")
# A one-minute load average above this share of the cores is load worth naming before a sweep.
BUSY_SHARE = 0.5
# A process using more than this share of one core is named in the load warning.
BUSY_PROCESS_PCPU = 20.0


def dir_bytes(path):
    total = 0
    for base, _, files in os.walk(path):
        for name in files:
            try:
                total += os.lstat(os.path.join(base, name)).st_size
            except OSError:
                pass
    return total


def only_projects(command):
    """The projects a sweep command names after `--only`, or None when it sweeps the corpus."""
    if "--only" not in command:
        return None
    names = []
    for arg in command[command.index("--only") + 1:]:
        if arg.startswith("-"):
            break
        names.append(arg)
    return names


def logs_bytes(logs=LOGS, projects=None):
    """Bytes of the harness logs, or of the named projects' log directories, both arms."""
    if not os.path.isdir(logs):
        return 0
    if projects is None:
        return dir_bytes(logs)
    return sum(dir_bytes(os.path.join(logs, name + suffix))
               for name in projects for suffix in ("", ".isolated"))


def map_dirs_bytes(checkouts=CHECKOUTS, projects=None):
    """Bytes of every map directory the corpus checkouts hold, old `pts` names included."""
    total = 0
    if not os.path.isdir(checkouts):
        return 0
    for name in os.listdir(checkouts) if projects is None else projects:
        for root_name in ("yoriwake", "pts"):
            total += dir_bytes(os.path.join(checkouts, name, ".gradle", root_name))
    return total


def disk_need(logs_bytes, maps_bytes):
    """What the sweep needs free, in bytes: 3x the logs, both arms' maps, and their evidence."""
    return 3 * logs_bytes + 2 * maps_bytes + int(EVIDENCE_RATIO * 2 * (maps_bytes + logs_bytes))


def on_mains(pmset_output, system):
    """True on mains power, False on battery, None where this cannot be asked."""
    if system == "Darwin":
        if pmset_output is None:
            return None
        return "AC Power" in pmset_output.splitlines()[0] if pmset_output.strip() else None
    if system == "Linux":
        supplies = "/sys/class/power_supply"
        if not os.path.isdir(supplies):
            return None
        online = []
        for name in os.listdir(supplies):
            path = os.path.join(supplies, name, "online")
            if os.path.isfile(path):
                with open(path, encoding="utf-8") as handle:
                    online.append(handle.read().strip() == "1")
        return any(online) if online else None
    return None


def checkout_problem(root, commit):
    """Why the yoriwake checkout is not the clean tree at `commit`, or None when it is."""
    if not os.path.isdir(os.path.join(root, ".git")):
        return f"{root} is not a git checkout"
    head = subprocess.run(["git", "-C", root, "rev-parse", "HEAD"], capture_output=True,
                          text=True).stdout.strip()
    wanted = subprocess.run(["git", "-C", root, "rev-parse", "--verify", "--quiet",
                             f"{commit}^{{commit}}"], capture_output=True, text=True).stdout.strip()
    if not wanted:
        return f"{commit} is not a commit in {root}"
    if head != wanted:
        return f"{root} is at {head[:12]}, not {wanted[:12]}"
    dirty = subprocess.run(["git", "-C", root, "status", "--porcelain", "--untracked-files=all"],
                           capture_output=True, text=True).stdout
    if dirty.strip():
        return f"{root} has uncommitted changes:\n{dirty.rstrip()}"
    return None


def measuring(listing):
    """The command lines of this repository's measurement scripts that are running."""
    return [line for line in listing.splitlines()
            if any(mark in line for mark in MEASURING) and "sweep_preflight" not in line]


def busy(load, cpus, pcpu_listing):
    """Warnings about load that is not the harness's: a busy machine, and what is keeping it busy.

    Warned rather than refused: another project's build or an IDE is the maintainer's to stop, and
    the sweep cannot tell whether it will still be running an hour in.
    """
    warnings = []
    if load is not None and cpus and load > BUSY_SHARE * cpus:
        warnings.append(f"load average {load:.1f} on {cpus} cores")
    top = []
    for line in pcpu_listing.splitlines():
        parts = line.split(None, 2)
        try:
            pcpu = float(parts[0])
        except (ValueError, IndexError):
            continue
        if pcpu > BUSY_PROCESS_PCPU and "sweep_preflight" not in line:
            top.append((pcpu, parts[2] if len(parts) > 2 else ""))
    for pcpu, args in sorted(top, reverse=True)[:5]:
        warnings.append(f"{pcpu:.0f}% CPU: {args.strip()[:140]}")
    return warnings


def sleep_guard(system):
    """The command prefix that keeps this machine awake for the sweep, or None where there is none."""
    if system == "Darwin" and shutil.which("caffeinate"):
        # -i idle sleep, -m disk sleep, -s system sleep on mains; -d keeps the display awake too,
        # so a locked screen cannot be mistaken for a stopped sweep.
        return ["caffeinate", "-dims"]
    if system == "Linux" and shutil.which("systemd-inhibit"):
        return ["systemd-inhibit", "--what=sleep:idle", "--why=yoriwake corpus sweep"]
    return None


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("--commit", required=True,
                        help="the yoriwake commit the sweep must measure")
    parser.add_argument("command", nargs="*", help="after --, the sweep to run under the sleep guard")
    args = parser.parse_args(argv)
    system = platform.system()
    problems = []

    projects = only_projects(args.command)
    logs = logs_bytes(projects=projects)
    maps = map_dirs_bytes(projects=projects)
    need = disk_need(logs, maps)
    free = shutil.disk_usage(ARCHIVE).free
    print(f"disk: {free / GIB:.1f} GiB free; need {need / GIB:.1f} GiB "
          f"(3 x {logs / GIB:.1f} logs + 2 x {maps / GIB:.1f} maps + evidence"
          f"{'' if projects is None else ', for ' + ' '.join(projects)})")
    if free < need:
        problems.append(f"only {free / GIB:.1f} GiB free and the sweep needs {need / GIB:.1f}")

    pmset = None
    if system == "Darwin":
        pmset = subprocess.run(["pmset", "-g", "batt"], capture_output=True, text=True).stdout
    power = on_mains(pmset, system)
    print(f"power: {'mains' if power else 'battery' if power is False else 'unknown'}")
    if power is not True:
        problems.append("not on mains power, or it could not be established")

    problem = checkout_problem(yoriwake_checkout.ROOT, args.commit)
    print(f"checkout: {yoriwake_checkout.ROOT} {'OK' if problem is None else 'NOT READY'}")
    if problem:
        problems.append(problem)

    listing = subprocess.run(["ps", "-A", "-o", "pid=,args="], capture_output=True, text=True).stdout
    ours, foreign = own_processes.split()
    running = measuring(listing) + [f"{pid} {args}" for pid, args in ours]
    print(f"other measurements: {len(running)} process(es); "
          f"{len(foreign)} Gradle process(es) of other projects, left alone")
    if running:
        problems.append("another build or measurement of this harness is running; reap it first:\n  "
                        + "\n  ".join(line.strip()[:160] for line in running))

    try:
        load = os.getloadavg()[0]
    except (OSError, AttributeError):
        load = None
    pcpu = subprocess.run(["ps", "-A", "-o", "pcpu=,pid=,args="], capture_output=True,
                          text=True).stdout
    for warning in busy(load, os.cpu_count(), pcpu):
        print(f"WARNING: {warning}")

    guard = sleep_guard(system)
    if args.command and guard is None:
        problems.append(f"no way to prevent sleep on {system}")

    if problems:
        print("\nREFUSING:\n- " + "\n- ".join(problems))
        return 1
    if not args.command:
        print("\nready")
        return 0
    print(f"\nrunning under {' '.join(guard)}: {' '.join(args.command)}", flush=True)
    return subprocess.run(guard + args.command, cwd=ARCHIVE).returncode


if __name__ == "__main__":
    sys.exit(main())
