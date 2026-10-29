"""What each recording arm took, from the results files `recording-cost.yml` collects.

Each file is `<arm>-<repeat>-<project>[.isolated].json` as `bench/run.py` wrote it. An arm's time
is its recording test task's own duration (`capture_arm.tasks.test_task_seconds`), else the whole
recording build's (`capture_seconds`). Prints one Markdown row per arm: its runs, the range of
their times, the forks it recorded with, and its range against the shared arm's.

    python3 bench/recording_cost.py out/<project>
"""

import json
import os
import re
import sys

NAME = re.compile(r"^(?P<arm>shared|isolated-\d+)-(?P<repeat>\d+)-")


def seconds(results):
    """The recording's time in a results file, or None when it holds none."""
    arm = results.get("capture_arm") or {}
    task = (arm.get("tasks") or {}).get("test_task_seconds") if isinstance(arm, dict) else None
    return task if task is not None else results.get("capture_seconds")


def collect(directory):
    """{arm: [(seconds, forks), ...]} from the results files in `directory`."""
    arms = {}
    for name in sorted(os.listdir(directory)):
        match = NAME.match(name)
        if not match or not name.endswith(".json"):
            continue
        with open(os.path.join(directory, name), encoding="utf-8") as handle:
            results = json.load(handle)
        took = seconds(results)
        if took is not None:
            arms.setdefault(match["arm"], []).append((took, results.get("capture_max_parallel_forks")))
    return arms


def table(arms):
    """The Markdown table for `collect`'s arms, shared first."""
    shared = [took for took, _ in arms.get("shared", [])]
    lines = ["| arm | runs | seconds | forks | against shared |", "|---|---|---|---|---|"]
    order = sorted(arms, key=lambda arm: (arm != "shared", int(arm.split("-")[1]) if "-" in arm else 0))
    for arm in order:
        times = sorted(took for took, _ in arms[arm])
        forks = sorted({str(fork) for _, fork in arms[arm] if fork is not None}) or ["host"]
        ratio = (f"{times[0] / max(shared):.1f}x-{times[-1] / min(shared):.1f}x"
                 if shared and arm != "shared" else "")
        lines.append(f"| {arm} | {len(times)} | {times[0]:.0f}-{times[-1]:.0f} | {', '.join(forks)} | {ratio} |")
    return "\n".join(lines)


def main(argv):
    directory = argv[1]
    arms = collect(directory)
    if not arms:
        print(f"no recording time in {directory}")
        return 1
    print(f"### {os.path.basename(os.path.normpath(directory))}\n")
    print(table(arms))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
