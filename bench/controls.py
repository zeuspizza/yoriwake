"""
Controls: does the harness still report a failure mode it cannot fail to see?

Builds the plugin with a defect planted in it, in a scratch clone of the yoriwake checkout, and
sweeps each control project against it with `run.py --control`. Per set:

    default      `PLANTED_MISS`: a narrowed run drops every test it selected through coverage,
                 which are the tests a planted change's failures come from, so every row that
                 narrows and induces a failure is a miss by construction. Every project must report
                 one. A project that reports none means the harness lost a miss: the hazard
                 complement ground truth carries, which is why these run before it is enabled.
    pool/v1      the same plant, and every family of the set must report a miss on demo or
    revert/v1    demo-kotlin (pool/v1), or on spring-framework (revert/v1, which needs history). A
                 family with no miss counts as clean in a figure without ever having shown that
                 the harness can see it fail. The fixtures carry body and pool/v1's own four;
                 `--corpus` asks aliased, constant, renamed and schema on moshi and okhttp. A
                 family no row could miss for passes only as declared `UNCONTROLLED`.
    forcing/v1   `planted_no_force()`: the scoped change set treats every forcing family's paths
                 as unread, so the change is accounted for and the run selects on the changed bytes
                 alone; and `NO_FORCE_RULES`, the two rules that force those rows behind it, off.
                 Every family's row must narrow, and miss where a test reads the file. The
                 fixtures carry three families; `--corpus` runs `dependency-revert` on ktlint and
                 `groovy-body` on grails-core.
    neutral/v1   no plant: the null change must run the whole suite as `empty-change-set`, so a
                 selector that skips everything on an empty diff cannot pass, and a
                 behaviour-neutral edit must report no miss, on every demo fixture.

The real checkout is never written: a plant is committed in the clone, and the plugin the real
checkout builds is published to mavenLocal again afterwards. Results go to
`bench/results/control/`, which every reader refuses in a figure.

    python bench/controls.py                         # full-arm ground truth
    python bench/controls.py --ground-truth auto     # complement mode, before it is enabled
    python bench/controls.py --set pool/v1           # likewise forcing/v1, neutral/v1
    python bench/controls.py --set revert/v1         # spring-framework, a quiet machine
    python bench/controls.py --set forcing/v1 --corpus   # ktlint and grails-core
    python bench/controls.py --set pool/v1 --corpus      # moshi and okhttp
    python bench/controls.py --set pool/v1 --verdict     # judge the files on disk, run nothing
"""

import argparse
import json
import os
import shutil
import subprocess
import sys
import tomllib
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

import assert_recall  # noqa: E402
import checkout as yoriwake_checkout  # noqa: E402
import forcing_mutations  # noqa: E402
import pool_mutations  # noqa: E402
import run  # noqa: E402

EMPTY_CHANGE_SET = "empty-change-set"

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


# THE PLANTED NO-FORCE, per forcing family: the paths its rows edit. A path the scoped change set
# drops as unread is accounted for, so the change empties and the run selects on the changed bytes
# alone. Taking the path out of the forcing rule instead would still force: a change that names no
# class is an `empty-change-set` full run unless something accounted for it.
NO_FORCE_PATHS = {
    "resource-value": r"(^|/)src/main/resources/",
    "services": r"(^|/)src/main/resources/META-INF/services/",
    "build-script": r"(^|/)build\.gradle(\.kts)?$",
    # Every path a dependency revert may edit: a version catalog, any Gradle script, the
    # properties file.
    "dependency-revert": r"\.versions\.toml$|\.gradle(\.kts)?$|(^|/)gradle\.properties$",
    "groovy-body": r"\.groovy$",
}
SCOPED_CHANGE = "plugin/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/change/Selection.kt"
NO_FORCE_ANCHOR = "    val read = paths.filterNot { NotRead.isIdeState(it) || declared(it) }"


def planted_no_force(families=forcing_mutations.FAMILIES):
    """The plant under which every path of `families` reads as unread. (file, old, new)."""
    pattern = "|".join(f"(?:{NO_FORCE_PATHS[family]})" for family in families)
    return (SCOPED_CHANGE, NO_FORCE_ANCHOR,
            NO_FORCE_ANCHOR[:-2] + f' || Regex("""{pattern}""").containsMatchIn(it) }}')


# THE RULES BEHIND THE PATH RULE, each forcing one family on its own: a resource's or a services
# file's copy under the build output moves the classpath-files digest, and a test task's system
# property moves the environment fingerprint. Measured on demo: with the path plant alone, every
# forcing row still forced through one of them. Each is planted off, so the row can narrow.
NO_FORCE_RULES = (
    (SCOPED_CHANGE,
     "    if (verdict == ClasspathFilesVerdict.Unchanged || forces) return this",
     "    if (verdict is ClasspathFilesVerdict.Moved || verdict == ClasspathFilesVerdict.Unchanged "
     "|| forces) return this"),
    ("plugin/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle/wiring/SelectionWiring.kt",
     "    is EnvironmentFingerprint.Verdict.Changed -> RefusalKind.ENVIRONMENT_CHANGED to "
     "verdict.reason",
     "    is EnvironmentFingerprint.Verdict.Changed -> null"),
)

# The default set's kinds, which `pool/v1` keeps beside its own four families.
DEFAULT_KINDS = ("body", "aliased", "constant", "renamed", "schema")
POOL_FAMILIES = DEFAULT_KINDS + pool_mutations.FAMILIES
REVERT_FAMILIES = ("revert",)
# THE pool/v1 FAMILIES EACH LEG ASKS FOR A MISS. No fixture holds an aliased target, and the
# fixtures' constant, renamed and schema rows all force, so those four are asked on corpus
# checkouts: moshi, whose archived rows narrowed and induced on `aliased`, and okhttp on `constant`,
# both seconds a row. No archived row of any project narrowed and induced on `renamed` or `schema`.
POOL_FIXTURE_FAMILIES = ("body",) + pool_mutations.FAMILIES
POOL_CORPUS_FAMILIES = ("aliased", "constant", "renamed", "schema")
# THE FAMILIES NO CONTROL CAN SHOW A MISS FOR, each with its evidence. A family's control passes as
# UNCONTROLLED, printed, only when it is named here and no row narrowed and induced a failure; any
# other family without such a row fails, so a new family cannot slip through unasked.
UNCONTROLLED = {
    "renamed": "every row forces: the moved class has no coverage, and no archived row of any "
               "project ever narrowed",
    "schema": "archived rows narrow but none ever induced a failure, since a test must introspect "
              "to see an added method; moshi and okhttp hold no schema target",
}

# On each forcing control fixture, the families a test reads the edited file of, whose narrowed
# row must therefore report a miss. The others only have to narrow.
READ_BY_A_TEST = {"demo": ("resource-value", "services")}
# THE FORCING FAMILIES EACH LEG CONTROLS. A dependency revert needs history and a Groovy body needs
# a Groovy root, which no fixture has, so those two run on corpus checkouts: a leg of their own, so
# the fixture leg stays minutes.
FORCING_FIXTURE_FAMILIES = ("resource-value", "services", "build-script")
FORCING_CORPUS_FAMILIES = ("dependency-revert", "groovy-body")

# Per set: the projects it runs on, the plants its plugin is built with, and its `run.py` arguments
# beside `--control --recall-only`. They come after `--limit <sweep_limit>`, so a `--limit` of
# their own wins.
SET_CONTROLS = {
    run.DEFAULT_SHAPES: {"projects": CONTROL_PROJECTS, "plants": (PLANTED_MISS,), "args": ()},
    # One row a family that narrowed and induced is a miss under the plant, so one is enough.
    run.POOL_SHAPES: {"projects": ("demo", "demo-kotlin"), "families": POOL_FIXTURE_FAMILIES,
                      "plants": (PLANTED_MISS,), "args": ("--narrowed-target", "1")},
    # spring-beans, not resilience4j: resilience4j stamps a build time into its jars' manifests,
    # so every selecting run there forces on the classpath files and no row can narrow.
    run.REVERT_SET: {"projects": ("spring-framework",), "plants": (PLANTED_MISS,),
                     "args": ("--narrowed-target", "1", "--limit", "8")},
    run.FORCING_SET: {"projects": ("demo", "demo-kotlin"),
                      "families": FORCING_FIXTURE_FAMILIES,
                      "plants": (planted_no_force(),) + NO_FORCE_RULES,
                      "args": ()},
    run.NEUTRAL_SET: {"projects": ("demo", "demo-kotlin", "demo-android"), "plants": (),
                      "args": ("--limit", "2")},
}
# `--corpus`: the legs that need a corpus checkout. Their maps are stamped with the control's arm,
# so the next measurement of the checkout re-captures.
CORPUS_CONTROLS = {
    # A family's own cap of 8 rows, not moshi's `sweep_limit` of 32: a kind that forces every row
    # would otherwise draw 32.
    run.POOL_SHAPES: {"projects": ("moshi", "okhttp"), "families": POOL_CORPUS_FAMILIES,
                      "plants": (PLANTED_MISS,),
                      "args": ("--narrowed-target", "1", "--limit", "8",
                               "--families", *POOL_CORPUS_FAMILIES)},
    run.FORCING_SET: {"projects": ("ktlint", "grails-core"), "families": FORCING_CORPUS_FAMILIES,
                      "plants": SET_CONTROLS[run.FORCING_SET]["plants"], "args": ()},
}


def plant(real, clone, entries):
    """A clone of `real` at its HEAD with each of `entries` (path, old, new) applied and committed.
    `real` is not written: the clone shares its objects read-only and holds its own commits."""
    if os.path.isdir(clone):
        shutil.rmtree(clone)
    head = subprocess.run(["git", "-C", real, "rev-parse", "HEAD"], capture_output=True, text=True,
                          check=True).stdout.strip()
    subprocess.run(["git", "clone", "--quiet", "--shared", "--no-checkout", real, clone],
                   check=True)
    subprocess.run(["git", "-C", clone, "checkout", "--quiet", "--detach", head], check=True)
    for path, old, new in entries:
        target = os.path.join(clone, path)
        with open(target, encoding="utf-8") as handle:
            text = handle.read()
        if text.count(old) != 1:
            raise SystemExit(f"a plant's anchor matches {text.count(old)} times in {path}; "
                             f"refusing to run controls against a plugin with nothing planted")
        with open(target, "w", encoding="utf-8") as handle:
            handle.write(text.replace(old, new))
    subprocess.run(["git", "-C", clone, "-c", "user.email=harness@yoriwake",
                    "-c", "user.name=yoriwake", "-c", "commit.gpgsign=false", "commit", "--quiet",
                    "--no-verify", "-am", "control: a planted defect"], check=True)
    return clone


def _control_rows(results, failures):
    """`{project: rows}` of each control results file; a missing or non-control file is a failure."""
    rows_of = {}
    for name, data in results.items():
        if data is None:
            failures.append(f"{name}: no control results file")
            continue
        rows = data.get("mutations") or []
        if not data.get("control") or not all(row.get("control") for row in rows):
            failures.append(f"{name}: the results file is not a control run's")
            continue
        rows_of[name] = rows
    return rows_of


def _rows_said(rows):
    """What a family's rows on one project did, for a failure line."""
    if not rows:
        return "no row"
    narrowed = sum(1 for r in rows if r.get("decision_outcome") == "narrowed")
    induced = sum(1 for r in rows if r.get("decision_outcome") == "narrowed" and r.get("induced"))
    forced = Counter(r.get("full_run_kind") or "?" for r in rows
                     if r.get("decision_outcome") == "full-run")
    other = Counter(r.get("status") for r in rows if r.get("decision_outcome") is None)
    parts = [f"{len(rows)} row(s)", f"{narrowed} narrowed, {induced} of them inducing a failure"]
    if forced:
        parts.append("forced " + ", ".join(f"{n} {k}" for k, n in sorted(forced.items())))
    if other:
        parts.append(", ".join(f"{n} {k}" for k, n in sorted(other.items())))
    return "; ".join(parts)


def family_failures(results, families):
    """Why a planted-miss control of a named set did not pass: one line per family that reported
    no miss on any project, with what its rows did on each. A family no project holds fails too."""
    failures = []
    rows_of = _control_rows(results, failures)
    if failures:
        return failures
    for family in families:
        rows = {name: [r for r in rows if r.get("kind") == family] for name, rows in rows_of.items()}
        if any(r.get("missed") for each in rows.values() for r in each):
            continue
        said = "; ".join(f"{name}: {_rows_said(each)}" for name, each in rows.items())
        if not any(_miss_possible(r) for each in rows.values() for r in each):
            # UNCONTROLLED, not clean: no row narrowed and induced a failure, so none could miss.
            # Only a family declared so, with its evidence, passes that way.
            if family not in UNCONTROLLED:
                failures.append(f"{family}: no row where a miss was possible, and the family is "
                                f"not declared uncontrolled ({said})")
            continue
        failures.append(f"{family}: no miss reported under a plugin that drops every test it "
                        f"selected through coverage ({said})")
    return failures


def _miss_possible(row):
    return row.get("decision_outcome") == "narrowed" and bool(row.get("induced"))


def uncontrolled(results, families):
    """The families of `families` no row of `results` could show a miss for, with what their rows
    did: declared ones pass as UNCONTROLLED, undeclared ones fail in `family_failures`."""
    found = {}
    for family in families:
        rows = {name: [r for r in (data or {}).get("mutations") or [] if r.get("kind") == family]
                for name, data in results.items()}
        if not any(r.get("missed") or _miss_possible(r) for each in rows.values() for r in each):
            found[family] = "; ".join(f"{name}: {_rows_said(each)}" for name, each in rows.items())
    return found


def forcing_failures(results, families=forcing_mutations.FAMILIES):
    """Why the planted no-force control did not pass: a forcing family no project holds, a row that
    still forced, and a family a test reads the file of whose rows reported no miss."""
    failures = []
    rows_of = _control_rows(results, failures)
    if failures:
        return failures
    for family in families:
        rows = {name: [r for r in rows if r.get("kind") == family] for name, rows in rows_of.items()}
        if not any(rows.values()):
            failures.append(f"{family}: no control project holds a row of it")
            continue
        for name, each in rows.items():
            for row in each:
                # The harness's own detector, so a control passes only where a sweep would report
                # the row as narrowed.
                if not assert_recall.forcing_row_narrowed(row):
                    failures.append(
                        f"{family}: {name} {row.get('target')} still "
                        f"{row.get('decision_outcome') or row.get('status')}"
                        f"{' (' + row['full_run_kind'] + ')' if row.get('full_run_kind') else ''} "
                        f"under a plugin that reads its paths as unread")
            # Only where a test reads the edited file must a narrowed row miss: elsewhere coverage
            # can reach the edit, and then the failing test was selected (see `set_notes`).
            if family in READ_BY_A_TEST.get(name, ()) and not any(r.get("missed") for r in each):
                failures.append(f"{family}: a test of {name} reads the edited file, and no row "
                                f"reported a miss ({_rows_said(each)})")
    return failures


def neutral_failures(results):
    """Why the null-change and neutral-edit control did not pass, one line per project and row: a
    null change that was not an `empty-change-set` full run or ran no test, and a neutral edit that
    reported a miss, induced a failure or measured nothing."""
    failures = []
    rows_of = _control_rows(results, failures)
    for name, rows in rows_of.items():
        nulls = [r for r in rows if r.get("kind") == run.NULL_CHANGE]
        edits = [r for r in rows if r.get("kind") == run.NEUTRAL_EDIT]
        if not nulls:
            failures.append(f"{name}: no null-change row")
        for row in nulls:
            if (row.get("decision_outcome") != "full-run"
                    or row.get("full_run_kind") != EMPTY_CHANGE_SET):
                failures.append(f"{name}: the null change was "
                                f"{row.get('decision_outcome') or row.get('status')} "
                                f"({row.get('full_run_kind')}), not a full run of kind "
                                f"{EMPTY_CHANGE_SET}")
            elif not row.get("selected_distinct"):
                failures.append(f"{name}: the null change selected no test")
        if not edits:
            failures.append(f"{name}: no neutral-edit row")
        for row in edits:
            if row.get("decision_outcome") is None:
                failures.append(f"{name}: the neutral edit measured nothing ({row.get('status')})")
            elif row.get("missed"):
                failures.append(f"{name}: the neutral edit reported a miss: "
                                f"{', '.join(row['missed'])}")
            # A miss needs an induced failure, which an edit that changes no behaviour must not
            # cause: one it did is the harness failing a test the change cannot reach.
            elif row.get("induced"):
                failures.append(f"{name}: the neutral edit induced a failure: "
                                f"{', '.join(row['induced'])}")
    return failures


def set_failures(shapes, results, complement_mode=False, corpus=False):
    """Why the control of `shapes` (its `--corpus` leg with `corpus`) did not pass; empty when it
    did."""
    if shapes == run.DEFAULT_SHAPES:
        return control_failures(results, complement_mode)
    if shapes == run.FORCING_SET:
        return forcing_failures(results, (CORPUS_CONTROLS if corpus else SET_CONTROLS)
                                [shapes]["families"])
    if shapes == run.NEUTRAL_SET:
        return neutral_failures(results)
    if shapes == run.POOL_SHAPES:
        return family_failures(results, (CORPUS_CONTROLS if corpus else SET_CONTROLS)
                               [shapes]["families"])
    return family_failures(results, REVERT_FAMILIES)


def set_notes(shapes, results, corpus=False):
    """What a passing control leaves unasked, printed with every run: the families no row could
    show a miss for (UNCONTROLLED, with their declared reason), a pool/v1 family whose rows on a
    project all forced, a forcing row coverage reached, and the forcing families only asked to
    narrow, since no test of a control project reads their file."""
    if shapes == run.FORCING_SET:
        families = (CORPUS_CONTROLS if corpus else SET_CONTROLS)[shapes]["families"]
        read = {family for name in results for family in READ_BY_A_TEST.get(name, ())}
        unread = [family for family in families if family not in read]
        notes = ([f"only asked to narrow, no test reading the edited file: {', '.join(unread)}"]
                 if unread else [])
        for name, data in results.items():
            for row in (data or {}).get("mutations") or []:
                if (row.get("kind") in families
                        and row.get("kind") not in READ_BY_A_TEST.get(name, ())
                        and _miss_possible(row) and not row.get("missed")):
                    notes.append(f"{row['kind']} on {name}: {row.get('target')} narrowed and "
                                 f"induced a failure with no miss; coverage reached the edit")
        return notes
    if shapes not in (run.POOL_SHAPES, run.REVERT_SET):
        return []
    families = ((CORPUS_CONTROLS if corpus else SET_CONTROLS)[shapes]["families"]
                if shapes == run.POOL_SHAPES else REVERT_FAMILIES)
    notes = [f"UNCONTROLLED: {family}: {UNCONTROLLED[family]} ({said})"
             for family, said in uncontrolled(results, families).items() if family in UNCONTROLLED]
    for family in families:
        missed_on = [name for name, data in results.items()
                     if any(r.get("kind") == family and r.get("missed")
                            for r in (data or {}).get("mutations") or [])]
        for name, data in results.items():
            rows = [r for r in (data or {}).get("mutations") or [] if r.get("kind") == family]
            if rows and all(r.get("decision_outcome") == "full-run" for r in rows):
                notes.append(f"{family} on {name}: every row forced ({_rows_said(rows)}), so "
                             f"no miss can show there"
                             + (f"; its miss shows on {', '.join(missed_on)}" if missed_on else ""))
    return notes


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


def read_control_results(name, shapes=run.DEFAULT_SHAPES):
    path = run.results_location(run.CONTROL_RESULTS, SCRATCH, name, shapes, control=True)[0]
    try:
        with open(path, encoding="utf-8") as handle:
            return json.load(handle)
    except (OSError, ValueError):
        return None


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--set", dest="shapes", choices=tuple(SET_CONTROLS),
                        default=run.DEFAULT_SHAPES, help="which set's control to run")
    parser.add_argument("--projects", nargs="+", default=None,
                        help="a subset of the set's control projects (default: all of them)")
    parser.add_argument("--ground-truth", choices=run.GROUND_TRUTH_MODES, default="full",
                        help="passed to each run.py; check or auto is complement mode")
    parser.add_argument("--corpus", action="store_true",
                        help=f"the leg on corpus checkouts ({', '.join(CORPUS_CONTROLS)} only)")
    parser.add_argument("--verdict", action="store_true",
                        help="build and run nothing: judge the leg's results files already on disk")
    args = parser.parse_args(argv)
    if args.corpus and args.shapes not in CORPUS_CONTROLS:
        parser.error(f"--corpus is a leg of {', '.join(CORPUS_CONTROLS)} only")
    control = (CORPUS_CONTROLS if args.corpus else SET_CONTROLS)[args.shapes]
    projects = args.projects or list(control["projects"])
    if not set(projects) <= set(control["projects"]):
        parser.error(f"the {args.shapes} control runs on {', '.join(control['projects'])}")
    if args.ground_truth != "full" and args.shapes != run.DEFAULT_SHAPES:
        parser.error("complement mode is a control of the default set only")
    if args.verdict:
        return report(args, {name: read_control_results(name, args.shapes) for name in projects})
    real = yoriwake_checkout.root()
    yoriwake_checkout.require_clean(real)
    os.makedirs(SCRATCH, exist_ok=True)
    # No plant, no clone: the null change and the neutral edit measure the plugin as it is.
    clone = (plant(real, os.path.join(SCRATCH, "yoriwake"), control["plants"])
             if control["plants"] else real)
    results = {}
    try:
        for name in projects:
            project = os.path.join(HERE, "projects", f"{name}.toml")
            with open(project, "rb") as handle:
                limit = tomllib.load(handle).get("sweep_limit", 8)
            stale = run.results_location(run.CONTROL_RESULTS, SCRATCH, name, args.shapes,
                                         control=True)[0]
            if os.path.isfile(stale):
                os.remove(stale)
            log = os.path.join(SCRATCH, f"{name}.{run.shape_folder(args.shapes)}.log")
            print(f"control {args.shapes} {name} -> {log}", flush=True)
            with open(log, "w", encoding="utf-8") as handle:
                # RECALL-ONLY: a timed row never takes a complement, so a control that timed its
                # rows would leave the complement unexercised in complement mode.
                subprocess.run([sys.executable, os.path.join(HERE, "run.py"), project, "--control",
                                "--recall-only", "--limit", str(limit), "--shapes", args.shapes,
                                "--ground-truth", args.ground_truth, *control["args"]],
                               stdout=handle, stderr=subprocess.STDOUT, cwd=ROOT,
                               env={**os.environ, yoriwake_checkout.SETTING: clone})
            results[name] = read_control_results(name, args.shapes)
    finally:
        # The planted jar is in mavenLocal now; put the real checkout's back, so nothing that
        # resolves the snapshot from there picks up a plugin with a defect planted in it.
        if clone != real:
            subprocess.run([os.path.join(real, "gradlew"), "--quiet",
                            ":plugin:publishToMavenLocal"], cwd=real, check=False)
    return report(args, results)


def report(args, results):
    """Prints what each project's rows did and the leg's verdict; 1 when it failed."""
    failures = set_failures(args.shapes, results, complement_mode=args.ground_truth != "full",
                            corpus=args.corpus)
    for name, data in results.items():
        rows = (data or {}).get("mutations") or []
        print(f"  {name}: {sum(1 for r in rows if r.get('missed'))} of {len(rows)} row(s) "
              f"reported a miss", flush=True)
        for kind in dict.fromkeys(r.get("kind") for r in rows):
            each = [r for r in rows if r.get("kind") == kind]
            print(f"    {kind}: {_rows_said(each)}; "
                  f"{sum(1 for r in each if r.get('missed'))} missed", flush=True)
    for note in set_notes(args.shapes, results, corpus=args.corpus):
        print(f"  note: {note}", flush=True)
    if failures:
        print(f"\nCONTROLS FAILED -- {args.shapes}:", flush=True)
        for line in failures:
            print(f"  {line}", flush=True)
        return 1
    print(f"\nevery {args.shapes} control reported what it must", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
