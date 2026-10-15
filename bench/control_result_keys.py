"""
The positive control for per-method result keys: a planted miss in a class whose methods share
their invocations' display names is reported on a real Gradle build, on either key scheme.

`fixtures/shared-display-names` invokes its two templates, `a` and `b`, at `v = 1` and `v = 2`,
so the JUnit XML gives both methods the same two keys -- iceberg's shape. The full arm fails `b`
at `v = 1`; the selected arm runs `a` and the plain `c` only, a narrowed row planted with `--tests`
instead of a selector, and judged against a decision record built to match. Read through the
harness's own reader and comparisons:

- with the outcome listener, the row is `per-method` and `b(int) > v = 1` is missed;
- without it (no `--no-configuration-cache`, so `gradle()` leaves the listener out), the row is
  `xml` and `v = 1` is `shared_key`: neither caught nor missed.

The full arm's test task fails, so the run also shows a failed task keeps its outcome lines. It runs
on the oldest and newest Gradle versions the corpus pins, and prints the listener's share of the
full arm's test task wall clock after a discarded warm-up build.

Not a `test_*.py` file, so the harness suites stay free of Gradle runs. Run it alone, on a quiet
machine:

    python3 bench/control_result_keys.py [--gradle VERSION ...]
"""

import argparse
import os
import shutil
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import run  # noqa: E402

FIXTURE = os.path.join(HERE, "fixtures", "shared-display-names")
SCRATCH = os.path.join(HERE, "logs", "control-result-keys")
CLASS = "fixture.SharedDisplayNamesTest"
# The oldest and newest Gradle the corpus's checkouts pin (geode; gradle).
GRADLE_VERSIONS = ("7.3.3", "9.8.0-rc-1")
# A version whose daemon cannot run on the harness's JDK 21.
DAEMON_JDK = {"7.3.3": 17}
ENGINE = "[engine:junit-jupiter]"
# What the selector would have recorded for the planted selection: `a` and `c` kept, `b` and the
# factory `d` excluded.
RECORD = {
    "notes": {"outcome": "narrowed"},
    "decided": [(f"{ENGINE}/[class:{CLASS}]/[test-template:a(int)]", "included/COVERS_CHANGE"),
                (f"{ENGINE}/[class:{CLASS}]/[method:c()]", "included/COVERS_CHANGE"),
                (f"{ENGINE}/[class:{CLASS}]/[test-template:b(int)]", "excluded/NO_EDGE"),
                (f"{ENGINE}/[class:{CLASS}]/[test-factory:d()]", "excluded/NO_EDGE")],
}
EXPECTED = {run.PER_METHOD: ([(CLASS, "b(int) > v = 1")], []),
            run.XML: ([], [(CLASS, "v = 1")])}


def checkout_for(version):
    """The fixture copied out with a wrapper pinned to `version`, borrowed from the checkout's."""
    path = os.path.join(SCRATCH, version)
    if os.path.isdir(path):
        run.remove_tree(path)
    shutil.copytree(FIXTURE, path)
    for name in ("gradlew", "gradlew.bat", os.path.join("gradle", "wrapper", "gradle-wrapper.jar")):
        os.makedirs(os.path.dirname(os.path.join(path, name)), exist_ok=True)
        shutil.copy2(os.path.join(run.SOURCE, name), os.path.join(path, name))
    with open(os.path.join(path, "gradle", "wrapper", "gradle-wrapper.properties"), "w",
              encoding="utf-8") as handle:
        handle.write("distributionUrl=https\\://services.gradle.org/distributions/"
                     f"gradle-{version}-bin.zip\n")
    return path


def arm(checkout, project, name, args):
    """One build of the fixture with `b` failing at `v = 1`, read as the sweep reads an arm."""
    log = os.path.join(checkout, f"{name}.log")
    started = time.time()
    code, _ = run.gradle(checkout, project, ["test", "--rerun-tasks", "-Dfixture.fail=1"] + args,
                         log)
    outcomes = run.results_of(checkout, "", started, "test", log_path=log, task_path=":test")
    tasks = (run.arm_stats(log) or {}).get("tasks") or {}
    return outcomes, code, tasks.get("test_task_seconds"), log


def judge(selected, full):
    """The row as the sweep would write it: its keys, what it missed and what shared a key."""
    keys = run.row_keys(selected, full, RECORD, "full arm")
    selected_ran, _ = run.keyed(selected, keys.scheme)
    induced = run.subtract_baseline(run.keyed(full, keys.scheme)[1], run.NOTHING_FAILING, keys)
    missed, _, shared = run.unmatched(induced, selected_ran, keys.shared)
    return keys, missed, shared


def control(version):
    """Both schemes on one Gradle version. Returns the failed checks, as sentences."""
    project = {"name": "shared-display-names"}
    if version in DAEMON_JDK:
        project["daemon_java_version"] = DAEMON_JDK[version]
    checkout = checkout_for(version)
    # Discarded, so neither scheme's arms pay for a cold daemon, compile or test JVM.
    arm(checkout, project, "warm-up", [])
    failures, seconds = [], {}
    for scheme, flags in ((run.PER_METHOD, run.TIMING_ARM_FLAGS), (run.XML, [])):
        full, full_code, seconds[scheme], full_log = arm(checkout, project, f"full-{scheme}", flags)
        selected, _, _, _ = arm(checkout, project, f"selected-{scheme}",
                                flags + ["--tests", f"{CLASS}.a", "--tests", f"{CLASS}.c"])
        if full[0] is None or selected[0] is None:
            failures.append(f"{version}, {scheme}: an arm reached no tests; see {full_log}")
            continue
        keys, missed, shared = judge(selected, full)
        print(f"  {version} {scheme:10}: result_keys={keys.stamp} missed={missed} "
              f"shared_key={shared} fallback={keys.fallback!r}", flush=True)
        if keys.stamp != scheme or (missed, shared) != EXPECTED[scheme]:
            failures.append(f"{version}, {scheme}: expected missed and shared_key "
                            f"{EXPECTED[scheme]}, got {(missed, shared)} on {keys.stamp}")
        if scheme == run.PER_METHOD and full_code == 0:
            failures.append(f"{version}: the full arm's test task did not fail, so it does not "
                            f"show a failed task keeping its outcome lines")
    with_listener, without = seconds.get(run.PER_METHOD), seconds.get(run.XML)
    if with_listener and without:
        print(f"  {version} full arm's test task: {with_listener}s with the listener, {without}s "
              f"without ({100 * (with_listener - without) / without:+.0f}%, one run each on a "
              f"7-test fixture)", flush=True)
    return failures


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[1])
    parser.add_argument("--gradle", action="append", metavar="VERSION",
                        help=f"Gradle version to run on; repeatable (default: "
                             f"{', '.join(GRADLE_VERSIONS)})")
    args = parser.parse_args(argv)
    failures = []
    for version in args.gradle or GRADLE_VERSIONS:
        print(f"Gradle {version}", flush=True)
        failures += control(version)
    for failure in failures:
        print(f"FAIL: {failure}", flush=True)
    print("the planted miss was reported on both schemes" if not failures
          else f"{len(failures)} failed check(s)", flush=True)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
