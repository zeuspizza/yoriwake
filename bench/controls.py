"""
Planted-miss controls: does the harness still report a miss it cannot fail to see?

Builds the plugin with `PLANTED_MISS` applied, in a scratch clone of the yoriwake
checkout, and sweeps each control fixture against it with `run.py --control`. Under that plugin a
narrowed run drops every test it selected through coverage, which are the tests a planted
change's failures come from, so every row that narrows and induces a failure is a miss by
construction. A control project that reports none means the harness lost a miss: the hazard
complement ground truth carries, which is why these run before it is enabled.

The real checkout is never written: the plant is committed in the clone, and the plugin the real
checkout builds is published to mavenLocal again afterwards. Results go to
`bench/results/control/`, which every reader refuses in a figure.

    python bench/controls.py                         # full-arm ground truth
    python bench/controls.py --ground-truth auto     # complement mode, before it is enabled
"""

import argparse
import json
import os
import shutil
import subprocess
import sys
import tomllib

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

import checkout as yoriwake_checkout  # noqa: E402
import run  # noqa: E402

# demo and demo-kotlin are the plain cases. order-dependent is the shared-JVM-state case: a dropped
# test that fails only after a selected test left state in the JVM, which a complement run alone
# cannot reproduce and a full arm can.
CONTROL_PROJECTS = ("demo", "demo-kotlin", "order-dependent")
SHARED_JVM_CONTROL = "order-dependent"
# In complement mode these must report a miss from a row whose only truth was the complement:
# a miss a full arm stood behind proves nothing about the complement.
COMPLEMENT_CONTROLS = ("demo", "demo-kotlin")
# The check rules that exist for the shared-JVM hazard; in complement mode the shared-JVM
# control's miss must come from a row one of them routed to a full arm.
SHARED_JVM_RULES = ("shares-jvm", "order-dependent-project")
SCRATCH = os.path.join(HERE, "logs", "controls")

# A plugin under which a narrowed run drops every test it selected through coverage and keeps only
# those it selected through a shared JVM. The tests that reach a planted change are the ones its
# failures come from, so every row that narrows and induces a failure must report it missed. (file,
# old, new), anchored once.
PLANTED_MISS = (
    "agent/src/main/java/io/github/zeuspizza/yoriwake/agent/select/Selector.java",
    "            return sharedJvmOnly.contains(testId) ? Reason.SHARES_JVM_WITH_CHANGE : "
    "Reason.REACHES_CHANGE;",
    "            return sharedJvmOnly.contains(testId) ? Reason.SHARES_JVM_WITH_CHANGE : "
    "Reason.SKIPPED;",
)


def plant(real, clone, entry):
    """A clone of `real` at its HEAD with `entry` (path, old, new) applied and committed. `real` is
    not written: the clone shares its objects read-only and holds its own commits."""
    path, old, new = entry
    if os.path.isdir(clone):
        shutil.rmtree(clone)
    head = subprocess.run(["git", "-C", real, "rev-parse", "HEAD"], capture_output=True, text=True,
                          check=True).stdout.strip()
    subprocess.run(["git", "clone", "--quiet", "--shared", "--no-checkout", real, clone],
                   check=True)
    subprocess.run(["git", "-C", clone, "checkout", "--quiet", "--detach", head], check=True)
    target = os.path.join(clone, path)
    with open(target, encoding="utf-8") as handle:
        text = handle.read()
    if text.count(old) != 1:
        raise SystemExit(f"the planted miss's anchor matches {text.count(old)} times in {path}; "
                         f"refusing to run controls against a plugin with nothing planted")
    with open(target, "w", encoding="utf-8") as handle:
        handle.write(text.replace(old, new))
    subprocess.run(["git", "-C", clone, "-c", "user.email=harness@yoriwake",
                    "-c", "user.name=yoriwake", "-c", "commit.gpgsign=false", "commit", "--quiet",
                    "--no-verify", "-am", "control: a planted miss"], check=True)
    return clone


def control_failures(results, complement_mode=False):
    """Why the controls did not pass, one line per project; empty when every one reported a miss.

    `results` maps each control project to its results file's content, or None when it wrote none.
    In complement mode the complement must also have lost nothing: no disagreement with a full arm
    and no switch-off; demo and demo-kotlin must report a miss from a complement-only row; and the
    shared-JVM control's miss must come from a row with a full arm that a shared-JVM rule routed
    there, the rule named.
    """
    failures = []
    for name, data in results.items():
        if data is None:
            failures.append(f"{name}: no control results file")
            continue
        rows = data.get("mutations") or []
        if not data.get("control") or not all(row.get("control") for row in rows):
            failures.append(f"{name}: the results file is not a control run's")
            continue
        missed = [row for row in rows if row.get("missed")]
        if complement_mode and (data.get("complement_switched_off")
                                or any(row.get("complement_disagreements") for row in rows)):
            failures.append(f"{name}: the complement disagreed with a full arm "
                            f"({data.get('complement_switched_off') or 'a check row'})")
            continue
        if not missed:
            failures.append(f"{name}: no miss reported in {len(rows)} row(s), under a plugin that "
                            f"drops every test it selected through coverage")
        elif complement_mode and name in COMPLEMENT_CONTROLS and not any(
                row.get("ground_truth") == run.COMPLEMENT_TRUTH for row in missed):
            failures.append(f"{name}: no miss came from a row whose only truth was the complement")
        elif complement_mode and name == SHARED_JVM_CONTROL and not any(
                row.get("ground_truth") == run.CHECKED_TRUTH
                and set(row.get("ground_truth_rules") or ()) & set(SHARED_JVM_RULES)
                for row in missed):
            failures.append(f"{name}: its miss came from no row a shared-JVM rule "
                            f"({', '.join(SHARED_JVM_RULES)}) gave a full arm")
    return failures


def read_control_results(name):
    try:
        with open(os.path.join(run.CONTROL_RESULTS, f"{name}.json"), encoding="utf-8") as handle:
            return json.load(handle)
    except (OSError, ValueError):
        return None


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--projects", nargs="+", default=list(CONTROL_PROJECTS),
                        choices=CONTROL_PROJECTS)
    parser.add_argument("--ground-truth", choices=run.GROUND_TRUTH_MODES, default="full",
                        help="passed to each run.py; check or auto is complement mode")
    args = parser.parse_args(argv)
    real = yoriwake_checkout.root()
    yoriwake_checkout.require_clean(real)
    os.makedirs(SCRATCH, exist_ok=True)
    clone = plant(real, os.path.join(SCRATCH, "yoriwake"), PLANTED_MISS)
    results = {}
    try:
        for name in args.projects:
            project = os.path.join(HERE, "projects", f"{name}.toml")
            with open(project, "rb") as handle:
                limit = tomllib.load(handle).get("sweep_limit", 8)
            stale = os.path.join(run.CONTROL_RESULTS, f"{name}.json")
            if os.path.isfile(stale):
                os.remove(stale)
            log = os.path.join(SCRATCH, f"{name}.log")
            print(f"control {name} -> {log}", flush=True)
            with open(log, "w", encoding="utf-8") as handle:
                # RECALL-ONLY: a timed row never takes a complement, so a control that timed its
                # rows would leave the complement unexercised in complement mode.
                subprocess.run([sys.executable, os.path.join(HERE, "run.py"), project, "--control",
                                "--recall-only", "--limit", str(limit),
                                "--ground-truth", args.ground_truth],
                               stdout=handle, stderr=subprocess.STDOUT, cwd=ROOT,
                               env={**os.environ, yoriwake_checkout.SETTING: clone})
            results[name] = read_control_results(name)
    finally:
        # The planted jar is in mavenLocal now; put the real checkout's back, so nothing that
        # resolves the snapshot from there picks up a plugin with a miss planted in it.
        subprocess.run([os.path.join(real, "gradlew"), "--quiet", ":plugin:publishToMavenLocal"],
                       cwd=real, check=False)
    failures = control_failures(results, complement_mode=args.ground_truth != "full")
    for name, data in results.items():
        rows = (data or {}).get("mutations") or []
        print(f"  {name}: {sum(1 for r in rows if r.get('missed'))} of {len(rows)} row(s) "
              f"reported a miss", flush=True)
    if failures:
        print("\nCONTROLS FAILED -- a planted miss went unreported:", flush=True)
        for line in failures:
            print(f"  {line}", flush=True)
        return 1
    print("\nevery control reported its planted miss", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
