"""
One verdict per corpus project: is its recall evidence clean, and if not, what is it instead?

WHY AN INSTRUMENT AND NOT A TABLE. "Swept clean" was a thing a person read off a results table, and
the table cannot show the failure that matters: a project the plugin DECLINED on sweeps its rows,
breaks nothing it can select against, and prints `0 missed of 0` -- which is typographically
identical to a perfect score. anki-android does exactly that, and a fix to how the plugin declines
makes the all-forced case likely rather than a corner. So the verdict is computed, and the two
zero-evidence outcomes have their own names and have to carry a reason.

FIVE VERDICTS, plus one refusal:

  clean             a results file exists at the current selector stamp, `assert_recall` passes on
                    it, at least one row was one where a miss was POSSIBLE, and the depth it was
                    asked at is recorded.
  dirty             a miss, a build-failed row, or a sweep that established nothing at all.
  cannot-answer     a recorded blocker explains why no row could ask the question -- the plugin
                    declined, JaCoCo was off, the source root holds no mutable extension.
  no-question-asked the sweep ran real rows and not one of them could have missed anything, with
                    its own forcing causes as the reason. NOT inferred from an empty file.
  unstated-depth    the rows are sound and the file cannot say how deep the sweep went.
  unstable-suite    the guest's own tests fail without any change present, so a failure in its rows
                    cannot be attributed to selection. Declared in the project file with the
                    evidence that established it, and in no denominator.
  setup-forced      the sweep stopped the project before its rows could count: its preflight or
                    three forced rows in a row forced for a cause outside every change. A setup
                    error, failed like `dirty` and in no denominator until it is re-run.
  never-swept       qualified, and no results file.
  stale-stamp       a refusal rather than a verdict: the file was written before a commit that
                    changed the selector, so it describes a different selector. Both stamps named.

Neither `cannot-answer` nor `no-question-asked` enters a recall denominator, and neither is ever
inferred from an empty result alone. Nor does `unstable-suite`, which differs from both in never
being inferred at all: a human declares it in the project file and states the evidence there.

THE STAMP IS THE SELECTOR'S, NOT THE REPOSITORY'S. `run.py`'s `plugin_commit` is
`git rev-parse --short HEAD` over the whole tree, so committing the results files a sweep just wrote
-- or editing a document -- would age out the very sweep that produced them and make the publishing
commit's own verification false the instant it landed. The question asked here is the one that
actually matters: is the selector that wrote this file the selector that is here now?

    python bench/corpus_state.py
    python bench/corpus_state.py --results bench/results --json
"""

import argparse
from collections import Counter
import json
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

sys.path.insert(0, HERE)

import assert_recall  # noqa: E402  (the recall gate's own narrowing question, not a second copy)
import run  # noqa: E402  (selector_digest lives with the writer that stamps it)

CLEAN = "clean"
DIRTY = "dirty"
CANNOT_ANSWER = "cannot-answer"
NO_QUESTION_ASKED = "no-question-asked"
NEVER_SWEPT = "never-swept"
STALE_STAMP = "stale-stamp"
# THE GUEST'S OWN SUITE CANNOT HOLD A RESULT, so its rows cannot answer a recall question however
# well the selector behaves. Declared in the project file as `unstable_suite = "<evidence>"`, and
# the value is the evidence rather than a boolean: a project silently dropped from a denominator is
# the shape of the sampling bias this corpus has already had to withdraw a figure over.
UNSTABLE_SUITE = "unstable-suite"
# "We could not state the depth" is NOT "nothing could have missed anything". Sharing a token with
# `no-question-asked` put a file with real answerable rows under a verdict whose published meaning
# is that it had none -- and every `--replay` results file lands here, because the history path
# stamps no `sweep_limit`. One token meaning two things is a token nobody can act on, which is why
# `undetermined` and `nondeterministic` are already separate.
UNSTATED_DEPTH = "unstated-depth"
# STOPPED BEFORE ITS ROWS COULD COUNT: the preflight or three forced rows forced for a
# cause outside every change, a setup error. A failed project, never a clean one, and in no
# denominator until its setup is fixed and it is re-run.
SETUP_FORCED = run.SETUP_FORCED

# Blockers a qualification can record that MEAN "no row here could have asked the recall question".
# Keyed by the token the harness writes, and each carries the sentence a reader needs -- a token
# alone would send them back to the plugin's source, which is how all seven of the corpus's
# first-contact failures were diagnosed.
#
# Deliberately short. A blocker that is not in this table does NOT excuse a zero-evidence sweep:
# `no-question-asked` is then the verdict, and its reason is the project's own forcing causes.
CANNOT_ANSWER_BLOCKERS = {
    "in-jvm-parallelism":
        "the suite runs tests in parallel inside one JVM, so the plugin declines, every test runs "
        "and nothing is captured",
    "jacoco-disabled":
        "the build switches the JaCoCo extension off, so the map is empty and no row can narrow",
    # Forced declines, never a user's request: the plugin leaves a task Develocity runs or chooses
    # tests for alone. The harness turns PTS off, so these mean the guest's own build won.
    "develocity-test-distribution":
        "Develocity Test Distribution runs this task's tests, so the plugin declines, every test "
        "runs and nothing is captured",
    "develocity-test-selection":
        "Develocity Predictive Test Selection chooses this task's tests, so the plugin declines, "
        "every test runs and nothing is captured",
    "develocity-undetermined":
        "the task carries a Develocity test extension the plugin cannot read, so it declines, "
        "every test runs and nothing is captured",
    # A JDK newer than a reader of it: every selecting run is a full run until the build or
    # yoriwake moves, so no row can narrow.
    "class-file-unsupported":
        "the build's classes are compiled for a newer Java than the plugin's ASM reads or than its "
        "JaCoCo officially instruments, so every selecting run runs every test",
    "test-jvm-unsupported":
        "the test JVM runs a newer Java than the agent's ASM reads, so every selecting run runs "
        "every test",
}


def selector_stamp(root=None):
    """The last commit that touched the selector, for PROVENANCE beside the digest.

    NOT what freshness is decided on, and the distinction is load-bearing. This walks the whole
    `plugin` and `agent` trees by git, which is wider than the digest's inputs -- so it moves for a
    test or a build-script change that leaves every verdict alone. Printed beside the digest and
    labelled, never instead of it: a reader who saw only this would think the corpus had aged when
    it had not, which is the confusion the digest exists to end.
    """
    out = subprocess.run(["git", "-C", root or run.SOURCE, "log", "-1", "--format=%h", "--",
                          "plugin", "agent"],
                         capture_output=True, text=True, check=False)
    return out.stdout.strip() or "unknown"


def qualification(path):
    """`name -> {"state": ..., "blockers": [...]}` from the generated table.

    Both states are read. A BLOCKED project is not in any denominator either, and a reader asking
    "what did the corpus answer" needs to see that it was never asked rather than have it absent.
    """
    rows = {}
    if not os.path.isfile(path):
        return rows
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            match = re.match(r"^(QUALIFIED|BLOCKED|UNRESOLVED)\s+(\S+)\s*(.*)$", line)
            if not match:
                continue
            state, name, rest = match.groups()
            found = re.search(r"blockers=(\S+)", rest)
            blockers = [] if not found or found.group(1) == "none" else found.group(1).split(",")
            rows[name] = {"state": state, "blockers": blockers, "detail": rest.strip()}
    return rows


def miss_was_possible(row):
    """Whether this row could have exposed a miss: it broke something AND selection narrowed.

    Both halves matter and the corpus has been misread on each. A row that broke nothing gave
    selection no failure to skip; a row that forced ran everything, so it could not skip one either.
    `0 missed` over rows of either kind is a true statement about nothing.

    The narrowing half is `assert_recall`'s own, imported rather than rewritten -- this repository
    has been bitten more than once by one contract living in two files with only one of them updated.
    """
    if row.get("status") != "checked":
        return False
    if not row.get("full_failures"):
        return False
    # A row whose every failure was set aside broke nothing selection could have skipped.
    if not assert_recall.answered_failures(row):
        return False
    return assert_recall.narrowed_at_all(row)


def forcing_causes(rows):
    """Why the rows that did not narrow did not, as tokens, most common first.

    This is `no-question-asked`'s REASON. A verdict of "nothing here could have missed anything"
    without it is the same unactionable zero it exists to replace.
    """
    causes = {}
    for row in rows:
        if miss_was_possible(row):
            continue
        if not row.get("full_failures"):
            cause = "the mutation broke nothing"
        else:
            # A full run the user asked for is named as such, not as the tool refusing.
            requested = row.get("refusal_kind") in assert_recall.REQUESTED_DECLINES
            cause = (f"requested: {row['refusal_kind']}" if requested else None) \
                or row.get("full_run_kind") or row.get("refusal_kind") or "forced, kind unrecorded"
        causes[cause] = causes.get(cause, 0) + 1
    return dict(sorted(causes.items(), key=lambda kv: -kv[1]))


def depths(rows):
    """Every `--limit` that wrote a row in this file, sorted.

    A LIST, NOT A SCALAR. `run.py` resumes by skipping already-measured targets, so one file
    accumulates rows from several invocations and a single value would describe only the last one.
    More than one entry is reported as `mixed` rather than collapsed.
    """
    return sorted({row["sweep_limit"] for row in rows if isinstance(row.get("sweep_limit"), int)})


def examine(name, results_dir, qualified, project=None, root=None, shape_set="default"):
    """One project's verdict, with everything the published table needs to print beside it.
    `shape_set` is the set `results_dir` holds: `default` at the top, a named set in its own."""
    entry = {"project": name, "rows": 0, "miss_possible": 0, "missed": 0, "unattributed_rows": 0,
             "container_failed": 0, "mutations_rejected": None,
             "depth": None, "targets_available": None, "reason": None, "grammar": None,
             "fixture": bool((project or {}).get("fixture"))}
    path = os.path.join(results_dir, f"{name}.json")
    blockers = (qualified.get(name) or {}).get("blockers", [])
    excusing = [CANNOT_ANSWER_BLOCKERS[b] for b in blockers if b in CANNOT_ANSWER_BLOCKERS]

    if not os.path.isfile(path):
        if excusing:
            return {**entry, "verdict": CANNOT_ANSWER, "reason": "; ".join(excusing)}
        return {**entry, "verdict": NEVER_SWEPT,
                "reason": "qualified, and no results file at any stamp"}
    try:
        with open(path, encoding="utf-8") as handle:
            data = json.load(handle)
    except (OSError, ValueError) as error:
        return {**entry, "verdict": DIRTY,
                "reason": f"its results file could not be read ({type(error).__name__})"}

    stamp = data.get("plugin_commit")
    digest = data.get("selector_digest")
    rows = data.get("mutations") or []
    # COUNTED BEFORE THE STALE RETURNS, so a stale file reports what it actually records rather than
    # the initialiser's zero. A recorded miss is evidence about a selector whether or not that
    # selector has since moved, and publishing `missed: 0` beside a file that names missed tests is
    # the kind of unmeasured number this instrument exists to stop.
    answered = [r for r in rows if r.get("status") == "checked"]
    entry.update(rows=len(rows), stamp=stamp, grammar=data.get("grammar_digest"),
                 ground_truth=assert_recall.ground_truth_mix(rows),
                 targets_available=data.get("targets_available"),
                 depth=depths(rows),
                 miss_possible=sum(1 for r in rows if miss_was_possible(r)),
                 missed=sum(len(r.get("missed") or []) for r in answered))
    # A PLANTED-MISS CONTROL'S ROWS measured a plugin with a defect put in on purpose;
    # they belong in results/control/ and in no figure, wherever the file was copied to.
    if data.get("control") or any(row.get("control") for row in rows):
        return {**entry, "verdict": DIRTY,
                "reason": "its results file holds planted-miss control rows, which measured a "
                          "plugin with a defect put in on purpose and count toward no figure"}
    # ONE DIRECTORY, ONE POPULATION. A file drawn from another shape set, or a history replay's rows
    # among mutation rows, would put two populations under one figure.
    if data.get("target_shapes") not in (None, shape_set):
        return {**entry, "verdict": DIRTY,
                "reason": f"its rows were drawn from the {data.get('target_shapes')} set, not the "
                          f"{shape_set} set this directory holds"}
    kinds = {row.get("kind") == "history" for row in rows}
    if len(kinds) > 1:
        return {**entry, "verdict": DIRTY,
                "reason": "its results file mixes history-replay rows with mutation rows, and a "
                          "replay's rows count toward no recall figure"}
    if data.get("project_outcome") == SETUP_FORCED:
        return {**entry, "verdict": SETUP_FORCED,
                "reason": f"stopped at the {(data.get('setup_forced') or {}).get('at')}: "
                          f"{(data.get('setup_forced') or {}).get('reason')}; fix the setup and "
                          f"re-run it from its first row"}
    # AFTER THE COUNTS, so the row the reader sees still says what the sweep recorded -- the verdict
    # changes what the figure is drawn from, it does not erase the measurement. Declared, never
    # inferred: a guest is dropped from a denominator only by a human who wrote down why.
    unstable = (project or {}).get("unstable_suite")
    if unstable is not None:
        # Narrowed BEFORE it is tested, so the refusal below is the only thing standing between a
        # bare `true` and a silent exclusion -- and so removing that refusal fails the test rather
        # than raising `AttributeError` on a value that was never a string.
        stated = unstable.strip() if isinstance(unstable, str) else ""
        if not stated:
            return {**entry, "verdict": DIRTY,
                    "reason": "its project file sets `unstable_suite` to something other than a "
                              "non-empty string, so the exclusion states no evidence and is refused"}
        return {**entry, "verdict": UNSTABLE_SUITE, "reason": stated}
    # DECIDED ON WHAT THE SELECTOR IS, not on how many commits have landed. The old test asked
    # whether anything had touched `plugin/` or `agent/` since this file's commit, which aged the
    # whole corpus for a docs change carrying one stray line and again for a method with no
    # production caller. What invalidates a figure is a change to the selector's source, and that
    # is a question about content -- see `run.selector_digest`.
    current = run.selector_digest(root)
    if digest is None:
        return {**entry, "verdict": STALE_STAMP,
                "reason": f"written at {stamp} with no selector digest, so the selector that "
                          f"produced it cannot be established; this file records "
                          f"{entry['missed']} missed failure(s) against an unknown selector"}
    if digest != current:
        return {**entry, "verdict": STALE_STAMP,
                "reason": f"measured against selector {digest}, and the selector is now {current}; "
                          f"this file records {entry['missed']} missed failure(s) against the "
                          f"older one"}
    # AND, FOR A MAVEN FILE, ON WHAT THE MAVEN PLUGIN IS: its rows ran through `maven/` as well as
    # the shared selector, which `selector_digest` does not cover.
    if data.get("build_tool") == "maven":
        maven_now = run.maven_build.digest(root or run.SOURCE)
        if data.get("maven_digest") != maven_now:
            return {**entry, "verdict": STALE_STAMP,
                    "reason": f"measured with Maven plugin {data.get('maven_digest')}, and it is "
                              f"now {maven_now}; this file records {entry['missed']} missed "
                              f"failure(s) against the older one"}
    # AND ON WHAT THE FIXTURE IS. A fixture is measured at `commit = "local"`, so a test added to
    # it moves no other stamp; its rows describe the tree `fixture_digest` names, and a file
    # without one cannot say which tree that was.
    fixture = run.fixture_digest(project or {}, root)
    if fixture is not None and data.get("fixture_digest") != fixture:
        return {**entry, "verdict": STALE_STAMP,
                "reason": f"measured on fixture tree {data.get('fixture_digest')}, and the tree is "
                          f"now {fixture}; its suite and its ordering were another tree's"}

    # A ROW NOBODY CAN ATTRIBUTE IS NOT A ROW THAT ANSWERED. `decision_outcome` is the selector's
    # own account of what it did; a row without one ran its two arms and recorded no reason, so it
    # cannot be read as narrowing OR as forcing. `sweep_corpus` gates on the whole-project case and
    # this did not, which would have published moshi (32 of 32 unattributed) and
    # kotlinx-serialization (2 of 2) under a verdict whose meaning is "rows ran and none could have
    # missed anything" -- a claim about evidence that does not exist.
    attributed = [row for row in rows if row.get("decision_outcome")]
    if rows and not attributed:
        # NAME THE MECHANISM, not the symptom. Every project that hit this carries
        # `not-on-junit-platform`, and that is the whole explanation: `SelectionFilter` is a JUnit
        # Platform PostDiscoveryFilter, so on a build that does not run on the Platform it never
        # executes and no per-test decision is ever recorded. Selection still happens, at class
        # granularity through Gradle's own filter -- what is missing is the account of it, and
        # without that account a row cannot be read as narrowing or as forcing.
        platform = "not-on-junit-platform" in blockers
        why = ("this build does not run on the JUnit Platform, so the agent's PostDiscoveryFilter "
               "never runs and records no per-test decision; selection falls back to class "
               "granularity and leaves no account of itself"
               if platform else
               "the arms ran and recorded no account of why")
        return {**entry, "verdict": CANNOT_ANSWER,
                "reason": f"all {len(rows)} row(s) carry no decision at all -- {why}"}
    entry["unattributed_rows"] = len(rows) - len(attributed)
    entry["miss_possible"] = sum(1 for row in rows if miss_was_possible(row))
    checked = [r for r in rows if r.get("status") == "checked"]
    entry["missed"] = sum(len(r.get("missed") or []) for r in checked)
    # WHAT THIS HARNESS COULD NOT DECIDE, carried beside the figure rather than folded into it. A
    # class that never started is not a test, and both verification arms run a class alone -- a
    # lighter load than the full suite -- so neither can reproduce it. Counted here so the published
    # number can say how much of its population it had to set aside.
    entry["container_failed"] = sum(len(r.get("container_failed") or []) for r in checked)
    # HOW MUCH OF THE SAMPLE THE GATE DISCARDED, beside the rows that survived it. A project can
    # sit just under the rejection ceiling, print `clean`, and never say that a fifth of its
    # targets were thrown away by the generator -- which is a different claim about the same
    # number. None, never 0, for a file written before the field existed: absent is not zero.
    entry["mutations_rejected"] = data.get("mutations_rejected")
    # EVERY STATUS `run.py` WRITES, not the two that were convenient. It emits six -- checked,
    # no-effect, build-failed, did-not-build, stalled, cache-hit -- and reading two of them meant a
    # project whose rows DIED IN THE BUILD printed `clean`, or fell to `no-question-asked` with the
    # reason "the mutation broke nothing", which is a positively false sentence about a build that
    # never ran. `did-not-build` is the ground-truth arm never reaching the tests, which is the same
    # event as `build-failed`; `stalled` and `cache-hit` answered nothing and say so.
    # `invalid` too: a selecting arm whose test JVM never reported is the plugin breaking the build.
    broken = [r for r in rows if r.get("status") in ("build-failed", "did-not-build", "invalid")]
    # `not-run`: a Maven arm whose measured execution was skipped or never reached.
    unanswered = [r for r in rows if r.get("status") in ("stalled", "cache-hit", "not-run")]
    malformed = [r for r in checked if "missed" not in r]

    if entry["missed"]:
        return {**entry, "verdict": DIRTY,
                "reason": f"{entry['missed']} failing test(s) were never selected"}
    if broken:
        return {**entry, "verdict": DIRTY,
                "reason": f"{len(broken)} row(s) died before reaching the tests; selection must "
                          f"never break the build it is a guest in"}
    if unanswered and not entry["miss_possible"]:
        return {**entry, "verdict": NO_QUESTION_ASKED,
                "reason": f"{len(unanswered)} row(s) stalled or were served from cache and answered "
                          f"nothing, and no other row could have missed anything"}
    if malformed:
        return {**entry, "verdict": DIRTY,
                "reason": f"{len(malformed)} checked row(s) carry no `missed` key, so a miss could "
                          f"not have been seen"}
    if checked and all(assert_recall.established_nothing(r) for r in checked):
        return {**entry, "verdict": DIRTY,
                "reason": "every answered row was undetermined, nondeterministic, on a shared key, "
                          "or failed only in tests set aside, so this sweep establishes nothing "
                          "about recall"}
    # THE SAME CEILING `assert_recall` REFUSES ON. Clean means `assert_recall` passes, and above
    # the ceiling it does not: the sample was shaped by the generator rather than by the project.
    # `pool/v1`'s ceiling is per family, as `assert_recall` reads it; a refused family's rows are
    # set aside there, so the project is not clean here.
    rejected = data.get("mutations_rejected")
    if data.get("target_shapes") == run.POOL_SHAPES:
        refused = run.refused_families(data.get("rejected_mutations"), rows)
        if refused:
            named = ", ".join(f"{family} ({count} of {offered})"
                              for family, (count, offered) in refused.items())
            return {**entry, "verdict": DIRTY,
                    "reason": f"rejected by the compile gate above the "
                              f"{100.0 * run.MUTATION_REJECTION_CEILING:.0f}% ceiling: {named}; "
                              f"those families' samples were shaped by the generator"}
    if rejected and data.get("target_shapes") != run.REVERT_SET \
            and data.get("target_shapes") != run.POOL_SHAPES \
            and data.get("target_shapes") != run.FORCING_SET \
            and run.rejection_rate_refuses(rejected, len(rows)):
        return {**entry, "verdict": DIRTY,
                "reason": f"{rejected} of {rejected + len(rows)} mutations were rejected by the "
                          f"compile gate, above the "
                          f"{100.0 * run.MUTATION_REJECTION_CEILING:.0f}% ceiling, so the sample "
                          f"was shaped by the generator rather than by the project"}

    # `forcing/v1` HAS NO RECALL DENOMINATOR, as `assert_recall` reads it: with no narrowing flag
    # in `flags`, a row that escaped its forcing rule is a finding; any other forcing file is in
    # no recall figure, whatever its rows narrowed.
    if data.get("target_shapes") == run.FORCING_SET:
        population = run.forcing_mutations.population(data.get("flags"))
        escaped = [row for row in rows if assert_recall.forcing_row_narrowed(row)]
        if escaped and population == "flag-off":
            return {**entry, "verdict": DIRTY,
                    "reason": f"{len(escaped)} {run.FORCING_SET} row(s) narrowed with no narrowing "
                              f"flag in `flags`; a change of this kind forces today"}
        forced = sum(1 for row in rows if assert_recall.declined_to_narrow(row))
        return {**entry, "verdict": NO_QUESTION_ASKED,
                "reason": f"{run.FORCING_SET} has no recall denominator ({population}): {forced} "
                          f"of {len(rows)} row(s) forced and ran everything"}

    if not entry["miss_possible"]:
        if excusing:
            return {**entry, "verdict": CANNOT_ANSWER, "reason": "; ".join(excusing)}
        causes = forcing_causes(rows)
        named = ", ".join(f"{cause} x{count}" for cause, count in causes.items()) or "no rows at all"
        return {**entry, "verdict": NO_QUESTION_ASKED,
                "reason": f"{len(rows)} row(s) and not one where a miss was possible: {named}"}

    missing = []
    if not entry["depth"]:
        missing.append("the depth it was swept at")
    if entry["targets_available"] is None:
        missing.append("how many targets were available before the limit")
    if missing:
        return {**entry, "verdict": UNSTATED_DEPTH,
                "reason": "the rows are sound but the file does not record " + " or ".join(missing)
                          + ", so the depth of this evidence cannot be stated"}
    return {**entry, "verdict": CLEAN,
            "reason": f"{entry['miss_possible']} row(s) where a miss was possible, none missed"}


def load_project(name):
    import tomllib  # noqa: PLC0415 -- keeps the module importable by the test suite on any path
    path = os.path.join(HERE, "projects", f"{name}.toml")
    if not os.path.isfile(path):
        return {}
    with open(path, "rb") as handle:
        return tomllib.load(handle)


def survey(results_dir, qualification_path, root=None, shape_set="default"):
    qualified = qualification(qualification_path)
    entries = []
    for name, row in qualified.items():
        if row["state"] != "QUALIFIED":
            entries.append({"project": name, "verdict": CANNOT_ANSWER, "rows": 0,
                            "miss_possible": 0, "missed": 0, "container_failed": 0,
                            "mutations_rejected": None, "depth": None,
                            "targets_available": None, "fixture": bool(load_project(name).get("fixture")),
                            "reason": f"not qualified: {row['detail'][:110]}"})
            continue
        entries.append(examine(name, results_dir, qualified, load_project(name), root, shape_set))
    return entries


def grammar_line(counted, current):
    """Which mutation grammar the projects in the denominator were drawn under.

    REPORTED, NOT A VERDICT. A grammar change does not make a row wrong about the selector, so it
    ages nothing -- but a figure pooled across two grammars is drawn from two populations, and the
    composition has to say so. A file written before the stamp existed is named as UNSTAMPED, never
    counted as matching: nothing can establish which rules drew it.
    """
    under = [e for e in counted if e.get("grammar") == current]
    unstamped = sorted(e["project"] for e in counted if e.get("grammar") is None)
    other = sorted(e["project"] for e in counted if e.get("grammar") not in (None, current))
    line = (f"  grammar now: digest {current} -- {len(under)} of {len(counted)} project(s) in the "
            f"denominator drawn under it")
    if unstamped:
        line += f"; unstamped, written before the stamp existed: {', '.join(unstamped)}"
    if other:
        line += f"; drawn under another grammar: {', '.join(other)}"
    return line


def render(entries, shape_set="default"):
    corpus = [e for e in entries if not e["fixture"]]
    fixtures = [e for e in entries if e["fixture"]]
    width = max((len(e["project"]) for e in entries), default=10)

    def block(rows, title):
        print(f"\n{title}")
        print(f"{'project':{width}}  {'verdict':17} {'rows':>5} {'miss?':>6} {'missed':>7} "
              f"{'depth':>8} {'avail':>6}")
        for entry in sorted(rows, key=lambda e: (e["verdict"], e["project"])):
            depth = entry.get("depth")
            shown = ("-" if not depth else
                     str(depth[0]) if len(depth) == 1 else "mixed" + str(depth))
            print(f"{entry['project']:{width}}  {entry['verdict']:17} {entry['rows']:5} "
                  f"{entry['miss_possible']:6} {entry['missed']:7} {shown:>8} "
                  f"{entry.get('targets_available') if entry.get('targets_available') is not None else '-':>6}")
            if entry.get("reason"):
                print(f"{'':{width}}    {entry['reason']}")

    block(corpus, "CORPUS")
    if fixtures:
        # `demo`, `demo-android` and `demo-kotlin` are local sample builds and `detekt-junk`
        # is detekt's own checkout at the same commit and module. They are useful and they are not
        # projects, so a breadth claim must not silently gain three sample builds and a duplicate.
        block(fixtures, "FIXTURES -- not projects, and in no denominator below")

    # THE COMPOSITION RULE 3c ASKS FOR, printed with the figure and never after it. A recall
    # percentage without its sample is the shape of every claim this project has had to withdraw.
    # NO COMPOSITION LINE FOR `forcing/v1`: it has no recall denominator, and a DIRTY forcing
    # file's rows would read as recall rows in one.
    counted = [e for e in corpus if e["verdict"] in (CLEAN, DIRTY)
               and shape_set != run.FORCING_SET]
    rows = sum(e["rows"] for e in counted)
    possible = sum(e["miss_possible"] for e in counted)
    missed = sum(e["missed"] for e in counted)
    # PUBLISHED BESIDE THE FIGURE. "0 missed" over a population that quietly set aside three
    # undecidable rows is a different claim from "0 missed" over one that set aside none, and a
    # reader cannot tell them apart unless the second number is printed next to the first.
    containers = sum(e.get("container_failed") or 0 for e in counted)
    discarded = sum(e.get("mutations_rejected") or 0 for e in counted)
    if shape_set == run.FORCING_SET:
        print(f"\nCOMPOSITION: none -- {run.FORCING_SET} has no recall denominator; "
              f"`assert_recall.py` reports each file's forced rows per family")
    else:
        print(f"\nCOMPOSITION: {len(counted)} project(s) in the denominator, {rows} row(s), "
              f"{possible} row(s) where a miss was possible, {missed} missed"
              f"{f', and {containers} container(s) that never started, which this harness cannot decide'
                 if containers else ''}."
              f"{f' {discarded} mutation(s) were discarded by the compile gate and are in no row above.'
                 if discarded else ''}")
    for verdict in (CLEAN, DIRTY, SETUP_FORCED, CANNOT_ANSWER, NO_QUESTION_ASKED, UNSTABLE_SUITE,
                    UNSTATED_DEPTH, NEVER_SWEPT, STALE_STAMP):
        named = [e["project"] for e in corpus if e["verdict"] == verdict]
        if named:
            print(f"  {verdict:17} {len(named):3}  {', '.join(sorted(named))}")
    # BOTH, AND WHICH ONE DECIDES. The commit is provenance a reader can check out; the digest is
    # what every verdict above was computed from.
    mix = Counter()
    for entry in counted:
        mix.update(entry.get("ground_truth") or {})
    if mix:
        print(assert_recall.ground_truth_line(mix))
    print(f"  selector now: digest {run.selector_digest()} at commit {selector_stamp()} "
          f"-- freshness is decided on the digest")
    print(grammar_line(counted, run.grammar_digest(shapes=shape_set)))


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--results", default=os.path.join(HERE, "results"))
    parser.add_argument("--qualification", default=os.path.join(HERE, "qualification.txt"))
    parser.add_argument("--json", action="store_true", help="the entries, for a table generator")
    parser.add_argument("--set", default="default",
                        help="the shape set to survey: its files are under --results/<set>/")
    args = parser.parse_args()
    results_dir = args.results if args.set == "default" else os.path.join(
        args.results, run.shape_folder(args.set))

    # REFUSED, NOT REPORTED AS CLEAN. `all([])` is True, so a missing or format-drifted
    # qualification table produced an empty corpus, an empty composition line and exit 0 -- the
    # green-on-nothing this module's own docstring opens by saying a table cannot show.
    # `sweep_corpus.py` refuses exactly this shape twice; so does this now.
    if not os.path.isfile(args.qualification):
        raise SystemExit(f"no qualification table at {args.qualification}; refusing to report an "
                         f"empty corpus as a clean one")
    entries = survey(results_dir, args.qualification, shape_set=args.set)
    if not entries:
        raise SystemExit(f"no QUALIFIED, BLOCKED or UNRESOLVED rows parsed from "
                         f"{args.qualification}; refusing to report an empty corpus as a clean one")

    # COMPUTED FROM THE ENTRIES, not inside render(). The exit code is what makes this a gate, and
    # a --json caller -- the table generator the flag exists for -- was getting 0 unconditionally.
    failed = any(e["verdict"] in (DIRTY, STALE_STAMP, SETUP_FORCED)
                 for e in entries if not e["fixture"])
    if args.json:
        print(json.dumps(entries, indent=1))
        return 1 if failed else 0
    render(entries, args.set)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
