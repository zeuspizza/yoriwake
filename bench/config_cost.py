"""
What applying the plugin costs a project before it runs a single test, in both build phases.

A second reader of configuration cost. The other measures one project against a control with the
plugin absent; this one runs across the corpus and says where the cost went, per project and per
phase, without a profiler and without an arm to difference against.

TWO PHASES, BECAUSE THEY ARE DIFFERENT WORK AND ONE OF THEM IS INVISIBLE TO THE OTHER. A build that
STORES a configuration-cache entry configures every project: it walks the source tree, derives the
instrumentation scope, and builds the module maps. A build that REUSES one runs no configuration
phase at all -- it checks the entry's fingerprint, which re-executes every value source that was
queried at configuration time. Reporting one figure for "configuration cost" hides whichever phase
the reader is not in, and the reusing phase is the one an adopter is in almost always.

READ FROM GRADLE'S OWN BUILD OPERATIONS, not from wall clock. `-Dorg.gradle.internal.operations.trace`
writes a tree of every operation with its duration, so the store cost is the sum of the project
configuration operations plus the projectsEvaluated notification -- which is where this plugin does
all of its eager work -- and the hit cost is the fingerprint check. Wall clock would include JVM
startup, the wrapper, and the daemon's mood; these numbers are the build's own accounting.

The trace flag observes without disturbing: measured on samples/demo, four consecutive
runs with the flag -- including two with different trace paths -- all reported "Configuration cache
entry reused". It is not part of the cache key and not part of the fingerprint. Asserted rather than
assumed all the same: every row's phase is read from that run's own output, and a run confirming
neither phase produces NO CONCLUSION rather than a duration.

NEVER AS A PERCENTAGE OF A BUILD. The same work reads as 0.5% on armeria and 40% on spring-boot, and
the percentage says more about the suite than about the plugin. Per `Test` task, with both
denominators printed: `configure` runs per `Test` task, and the project count is not the same number
-- spring-boot has 529 projects, 446 that attach the plugin, and 528 `Test` tasks between them.

Usage:
    python bench/config_cost.py bench/projects/detekt.toml bench/projects/okhttp.toml
    python bench/config_cost.py --all
"""

import argparse
import json
import os
import statistics
import shutil
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
import checkout as yoriwake_checkout  # noqa: E402  (the one place the checkout is resolved)

DEFAULT_OUT = os.path.join(HERE, "results", "config-cost.jsonl")

# The suffix the plugin writes a timer under, beside the count on the bare key.
MILLIS = ".millis"

# The timer that bounds the others. Its name is BuildMemo's CONFIGURE_COUNTER, and
# `test_module_contract.py` pins the key this script reads it by.
CONTAINING_TIMER = "configure"
INIT_SCRIPT = os.path.join(yoriwake_checkout.ROOT, "scripts", "yoriwake.init.gradle.kts")

# The phase a row describes, read off the run's own output rather than off what it was asked to do.
STORE = "store"
HIT = "hit"
NO_CONCLUSION = "NO CONCLUSION"

# Gradle's own names for the three operations this reads. Matched on the details class rather than
# the display name: display names carry the project path and are localised in principle.
CONFIGURE_PROJECT = "ConfigureProjectDetails"
PROJECTS_EVALUATED = "NotifyProjectsEvaluated"
FINGERPRINT_CHECK = "FingerprintCheckDetails"
# Where `declared` and `compileTime` resolve: after the callbacks have returned, while Gradle
# serialises the entry. Reported beside the partition as a named remainder rather than folded into
# it -- it is the plugin's cost too, and it is not one of the three mechanisms.
STORE_STATE = "WorkGraphStoreDetails"


class Unreadable(Exception):
    """The trace exists and does not contain what this instrument reads. Never reported as zero."""


def phase_of(output):
    """Which phase this run was, from its own log. None when it says neither."""
    if "Configuration cache entry reused" in output:
        return HIT
    if "Configuration cache entry stored" in output:
        return STORE
    return None


def read_trace(path):
    """Every completed operation Gradle recorded, each with its duration in milliseconds.

    Read from the trace LOG rather than the tree JSON. Gradle 8.14 writes `-tree.json` beside the
    log; 9.7 writes only the log, and a reader that wanted the tree reported "the build did not
    write one" for every project in the corpus whose wrapper is newer than this repository's. The
    log is a start record and a finish record per operation, paired here by id, and both versions
    write it.
    """
    starts, operations = {}, []
    try:
        with open(path, encoding="utf-8") as handle:
            for line in handle:
                try:
                    record = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if "startTime" in record and "id" in record:
                    starts[record["id"]] = record
                elif "endTime" in record and record.get("id") in starts:
                    start = starts.pop(record["id"])
                    operations.append({
                        "displayName": start.get("displayName"),
                        "detailsClassName": start.get("detailsClassName") or "",
                        "duration": record["endTime"] - start["startTime"],
                    })
    except FileNotFoundError:
        raise Unreadable(f"no operations trace at {path}; the build did not write one")
    except OSError as problem:
        raise Unreadable(f"the operations trace at {path} could not be read: {problem}")
    if not operations:
        raise Unreadable(f"the operations trace at {path} recorded no completed operation")
    return operations


def store_cost(operations):
    """(milliseconds, projects configured) for the phase that configures.

    Both halves, because they answer different questions: the per-project configuration is where a
    build script's own work lands, and the projectsEvaluated notification is where this plugin does
    all of its eager work. A build that stores an entry pays both.
    """
    configure = [n for n in operations if CONFIGURE_PROJECT in n["detailsClassName"]]
    evaluated = [n for n in operations if PROJECTS_EVALUATED in n["detailsClassName"]]
    if not configure:
        raise Unreadable(
            "the trace holds no project configuration operations, so this build did not configure "
            "anything -- a duration of zero would say the opposite"
        )
    millis = sum(n["duration"] for n in configure + evaluated)
    return millis, len(configure)


def evaluated_cost(operations):
    """Milliseconds inside the projectsEvaluated notification, which bounds the plugin's eager work.

    This plugin does all of it inside one `gradle.projectsEvaluated` callback, so this operation's
    own duration is the ceiling the per-mechanism timers divide. Anything left over after they are
    subtracted is other listeners and the callback machinery -- reported as a residual rather than
    assigned to a mechanism, because a partition that assigns its own error is not a partition.
    """
    evaluated = [n for n in operations if PROJECTS_EVALUATED in n["detailsClassName"]]
    return sum(n["duration"] for n in evaluated) if evaluated else None


def store_state_cost(operations):
    """Milliseconds serialising the entry, where `declared` and `compileTime` resolve."""
    storing = [n for n in operations if STORE_STATE in n["detailsClassName"]]
    return sum(n["duration"] for n in storing) if storing else None


def hit_cost(operations):
    """Milliseconds spent checking the entry's fingerprint, which is what a reusing build pays."""
    checks = [n for n in operations if FINGERPRINT_CHECK in n["detailsClassName"]]
    if not checks:
        raise Unreadable(
            "the trace holds no fingerprint check, so nothing here describes the reusing phase"
        )
    return sum(n["duration"] for n in checks)


def read_counters(path):
    """What the plugin counted while configuring, or an empty dict when it configured nothing.

    Empty is the honest answer on a reusing build: no configuration phase ran, so the plugin wrote
    no file. That is a stronger signal than a file of zeroes, which is also what a reintroduced
    per-task defect would write on that path.

    A file that EXISTS and cannot be parsed is neither: a build killed mid-write leaves a partial
    line, and returning the rows before it would report a fraction of the build's work as the whole
    of it. That refuses, like every other reader here.
    """
    if not os.path.exists(path):
        return {}
    counters = {}
    try:
        with open(path, encoding="utf-8") as handle:
            for line in handle:
                if not line.strip():
                    continue
                key, _, value = line.partition("\t")
                counters[key] = int(value)
    except OSError as problem:
        raise Unreadable(f"the counters at {path} could not be read: {problem}")
    except ValueError:
        raise Unreadable(
            f"the counters at {path} hold a line that is not `key\\tcount`; the build that wrote "
            f"them was probably killed mid-write, and the rows before it are a fraction of its work"
        )
    return counters


def append_row(path, row):
    """One line per row, flushed, so a killed sweep leaves the projects it finished."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "a", encoding="utf-8") as handle:
        handle.write(json.dumps(row, sort_keys=True) + "\n")
        handle.flush()


def read_rows(path):
    """(rows, unreadable) -- a checkpoint truncated mid-record is reported, never silently dropped.

    A sweep killed while writing leaves a partial last line. Parsing item by item keeps every
    complete row; counting what could not be parsed is what stops a truncated file from reading as
    a shorter run that finished.
    """
    rows, unreadable = [], 0
    try:
        with open(path, encoding="utf-8") as handle:
            for line in handle:
                if not line.strip():
                    continue
                try:
                    rows.append(json.loads(line))
                except json.JSONDecodeError:
                    unreadable += 1
    except OSError:
        return [], 0
    return rows, unreadable


def plugin_commit():
    """The commit of the plugin these rows measured, so a figure can be traced back to its code.

    `published-numbers-and-their-caveats.md` rule 4: a number without the commit it measured is a
    number nobody can reproduce. The yoriwake checkout's HEAD, refused when that checkout is dirty.
    """
    return yoriwake_checkout.commit()


def row(name, phase, millis, projects, test_tasks, counters, detail=None,
        evaluated_millis=None, store_state_millis=None):
    """One project, one phase. Per `Test` task, with both denominators beside it."""
    # None, not 0, when nothing was counted. Every sibling field here reads through `.get()` and
    # answers None; a sum over an empty dict answers 0, and `git_calls: 0` is the headline column of
    # BENCHMARKS 4h -- "the plugin asked git nothing" is exactly what an absent counters file would
    # have published, for a build that asked git plenty.
    git = (
        sum(value for key, value in counters.items()
            if key.startswith("git.") and not key.endswith(MILLIS))
        if counters else None
    )
    mechanisms = {
        key[: -len(MILLIS)]: value for key, value in counters.items() if key.endswith(MILLIS)
    }
    # THE PLUGIN'S TIMERS NEST, AND SUMMING THEM DOUBLE-COUNTS.
    #
    # `configure` times the whole `projectsEvaluated` pass; `deriveScope` and `allprojectsWalk`
    # happen INSIDE it. Summing every timer was right while `configure` did not exist -- the figure
    # published earlier is `deriveScope + allprojectsWalk`, because that was
    # all there was -- and it silently stopped being right when the payback axis added the outer
    # timer. On spring-framework it reported 1,506ms of plugin work inside an 832ms notification,
    # and `unattributed_millis` went NEGATIVE: the tell that a partition's parts overlap.
    #
    # So the container is the total when it is present, and the nested timers stay beside it as the
    # breakdown they are. A partition whose parts overlap is arithmetic dressed as attribution --
    # which is the note `classpathFacts` already carries in the plugin, one layer down.
    attributed = mechanisms.get(CONTAINING_TIMER)
    # WHICH RULE PRODUCED THE FIGURE, per row rather than per instrument version. The stamp below
    # was written as "containing-timer" unconditionally, which is true of most rows and false of
    # every row whose counters file has no `configure` -- demo, demo-kotlin, demo-android and rxjava
    # on this corpus. Those fall back to the SUMMING formula a re-take retracted, and a
    # blanket stamp put the retracted quantity in the same column as the replacement with nothing to
    # tell them apart. A reader comparing two rows under one label was comparing two quantities.
    attribution = "containing-timer"
    if attributed is None:
        attributed = sum(mechanisms.values()) if mechanisms else None
        # NO TIMERS AT ALL is its own state, and it is the ordinary one for a reusing build: that
        # phase writes no counters file by construction, so every hit row lands here. Filing it with
        # the two attribution RULES and then filtering the ranking on those dropped every hit row --
        # which is to say the fingerprint check, the largest cost this instrument can see.
        attribution = SUMMED_PARTS_ATTRIBUTION if attributed is not None else NO_TIMERS
    return {
        "project": name,
        "phase": phase,
        "plugin_commit": plugin_commit(),
        # WHICH RULE PRODUCED `attributed_millis`, beside the commit that produced the run. The
        # stamp alone could not tell these apart: rows written before the containing timer became
        # the total summed it with the timers inside it, and a reader comparing an old row with a
        # new one under the same stamp compares two different quantities.
        "attribution": attribution,
        "millis": millis,
        "projects": projects,
        "test_tasks": test_tasks,
        # Per `Test` task, because that is what `configure` iterates. Never as a share of the build:
        # the same work reads as 0.5% on armeria and 40% on spring-boot.
        # The WHOLE build's configuration cost over its Test tasks -- the host's own build scripts
        # included, not the plugin's share. The plugin's own is the row below it, and conflating the
        # two would credit this plugin with every second a host spends configuring itself.
        "build_configure_millis_per_test_task": (millis / test_tasks) if test_tasks else None,
        "plugin_millis_per_test_task": (
            attributed / test_tasks if attributed is not None and test_tasks else None
        ),
        "git_calls": git if phase == STORE else None,
        "scope_derivations": counters.get("deriveScope"),
        "allprojects_walks": counters.get("allprojectsWalk"),
        "source_files_opened": counters.get("sourceFilesOpened"),
        # The partition: what the plugin's own timers say, inside the operation that bounds them,
        # and what neither of those accounts for. Published together or not at all -- an attributed
        # figure without its residual reads as the whole cost.
        "mechanism_millis": mechanisms or None,
        "attributed_millis": attributed,
        "projects_evaluated_millis": evaluated_millis,
        "unattributed_millis": (
            evaluated_millis - attributed
            if evaluated_millis is not None and attributed is not None else None
        ),
        # THE TELL, RECORDED RATHER THAN LEFT TO BE NOTICED. A residual below zero means the timers
        # this row attributes overlap the operation that bounds them, which is how an earlier
        # column came to be re-taken as a 2x rise that was not one. A reader who finds this true
        # must not quote the row.
        "partition_overlaps": (
            evaluated_millis is not None and attributed is not None and evaluated_millis < attributed
        ),
        # Named, and deliberately not part of the partition above: `declared` and `compileTime`
        # resolve while the entry is serialised, which is after those callbacks have returned.
        "store_state_millis": store_state_millis,
        "detail": detail,
    }


# What this instrument can see, in the order it reads them out of a row, and what each one is.
# `configure` is the container; the two under it are inside it; `residual` is what neither names.
RANKED_COSTS = (
    ("deriveScope", True, "walks the source tree to derive the instrumentation scope",
     lambda row: (row.get("mechanism_millis") or {}).get("deriveScope")),
    ("allprojectsWalk", True, "walks the project tree to build the module maps",
     lambda row: (row.get("mechanism_millis") or {}).get("allprojectsWalk")),
    ("residual inside projectsEvaluated", False,
     "inside the notification that bounds the plugin's pass and belonging to ANY listener in it, "
     "this plugin included; not separable and therefore not a target",
     lambda row: _residual(row)),
    ("store-state", True,
     "`declared` and `compileTime` resolving while the entry is serialised",
     lambda row: row.get("store_state_millis")),
    ("fingerprint check", False,
     "GRADLE's whole check on a reusing build; the plugin's share of it is not separable here",
     lambda row: row.get("millis") if row.get("phase") == HIT else None),
)
# WHY THE RESIDUAL IS NOT OURS, and it was ranked as though it were until a corpus run
# put it first at 1,274ms on micrometer. `projects_evaluated_millis` is the duration of
# Gradle's whole `NotifyProjectsEvaluated` operation, which runs EVERY listener the build
# registered. Subtracting this plugin's `configure` timer from it leaves other plugins' callbacks
# and the notification machinery as well as whatever of ours the timer does not cover. A ranking
# that called that "the plugin's eager pass" would have sent an optimisation at micrometer's other
# plugins. It stays measured and printed, because a residual is how an overlapping partition is
# caught -- it is just not a target.

# The attribution rule a row was written under. Rows without it predate the containing-timer fix and
# carry `deriveScope + allprojectsWalk` summed as their total; ranking the two populations together
# would compare two different quantities under one name.
CONTAINING_TIMER_ATTRIBUTION = "containing-timer"
SUMMED_PARTS_ATTRIBUTION = "summed-parts"
# Not a rule: a row that timed nothing, which is every reusing row and any storing row whose
# counters file is absent. It carries a duration for the phase and no attribution to make.
NO_TIMERS = "no-timers"
# Every value the stamp may hold SINCE it became per-row. A row with no `attribution` field at all
# predates that and summed a timer with the timers inside it, which is a third quantity again.
ATTRIBUTION_RULES = (CONTAINING_TIMER_ATTRIBUTION, SUMMED_PARTS_ATTRIBUTION, NO_TIMERS)

# Axes an adopter pays that this instrument cannot see. Named rather than left absent, because a
# ranking that lists only what one tool measures reads as a ranking of everything.
UNSEEN_COSTS = (
    ("the instrumentation toll", "a property of JaCoCo rather than of this plugin's code; it needs "
                                 "a suite to run and is measured by scripts/measure-toll.sh"),
    ("the selecting run's daemon-side work", "inside the `Test` task, after configuration; no "
                                             "build-operation boundary encloses it"),
)


def _residual(row):
    """What the plugin's own timers do not account for inside the pass that bounds them."""
    residual = row.get("unattributed_millis")
    # A partition whose parts exceed its whole is not a partition, and its residual is not a cost.
    if row.get("partition_overlaps") or residual is None or residual < 0:
        return None
    # And a residual computed against the SUMMED parts is a different quantity from one computed
    # against the containing timer: `configure` contains the two timers under it, so subtracting the
    # parts instead leaves systematically more. Mixing the two into one median would publish a
    # figure no single rule produced.
    return residual if row.get("attribution") == CONTAINING_TIMER_ATTRIBUTION else None


def quotable(row):
    """Whether a row's attribution may be read at all.

    A row whose timers overlap the operation that bounds them is refused here rather than ranked:
    the figures in it are real durations of something, and nothing says which. That is the shape
    that published a 2x rise that was not one.
    """
    return row.get("phase") in (STORE, HIT) and not row.get("partition_overlaps")


def rank(rows):
    """The corpus's costs, worst first, with what answered and what refused.

    Ordered by the WORST project rather than by an average. An adopter is one project, and a mean
    over a corpus containing a one-module fixture and spring-boot describes neither.
    """
    usable = [r for r in rows if quotable(r)]
    refused = [r for r in rows if not quotable(r)]
    summed = sorted({r["project"] for r in usable
                     if r.get("attribution") == SUMMED_PARTS_ATTRIBUTION})
    ranked = []
    for name, ours, what, read in RANKED_COSTS:
        measured = [(read(r), r["project"]) for r in usable if read(r) is not None]
        if not measured:
            ranked.append({"cost": name, "ours": ours, "what": what, "projects": 0,
                           "worst_millis": None, "worst_project": None, "median_millis": None})
            continue
        measured.sort(reverse=True)
        millis = sorted(m for m, _ in measured)
        ranked.append({
            "cost": name, "ours": ours, "what": what, "projects": len(measured),
            "worst_millis": measured[0][0], "worst_project": measured[0][1],
            "median_millis": statistics.median(millis),
        })
    # The plugin's own costs first, and the build's beside them rather than mixed in. Whoever cuts
    # a cost takes the target off the top of this list, and the top of a MIXED list is Gradle's
    # fingerprint check -- which this plugin does not own and cannot cut.
    ranked.sort(key=lambda entry: (entry["ours"], entry["worst_millis"] or -1), reverse=True)
    commits = sorted({r.get("plugin_commit") for r in usable if r.get("plugin_commit")})
    return {"ranked": ranked, "rows_read": len(rows), "rows_refused": len(refused),
            "refused_projects": sorted({r["project"] for r in refused}),
            # Named, because their `attributed_millis` is the summing formula a re-take
            # retracted. The mechanism columns below are unaffected -- `deriveScope` is `deriveScope`
            # either way -- so they are reported rather than dropped.
            "summed_parts_projects": summed,
            "plugin_commits": commits,
            "unseen": [{"cost": name, "why": why} for name, why in UNSEEN_COSTS]}


def print_ranking(report):
    """The ordered list an optimisation takes its target off. Figures, not a paragraph."""
    print(f"\nRANKED by the worst project, over {report['rows_read'] - report['rows_refused']} "
          f"quotable rows of {report['rows_read']}")
    if len(report["plugin_commits"]) > 1:
        print(f"  WARNING: {len(report['plugin_commits'])} plugin commits in one ranking -- "
              f"{', '.join(c[:7] for c in report['plugin_commits'])}")
    position, separated = 0, False
    for entry in report["ranked"]:
        if not entry["ours"] and not separated:
            print("  -- beside them, costs the build pays that this plugin does not own --")
            separated = True
        position = position + 1 if entry["ours"] else position
        label = f"  {position}." if entry["ours"] else "     "
        if entry["worst_millis"] is None:
            print(f"{label} {entry['cost']:34} NO CONCLUSION -- no row carried it")
            continue
        print(f"{label} {entry['cost']:34} {entry['worst_millis']:6d}ms worst "
              f"({entry['worst_project']}), {entry['median_millis']}ms median "
              f"over {entry['projects']} projects")
        print(f"     {entry['what']}")
    if report["summed_parts_projects"]:
        print(f"  NOTE: {len(report['summed_parts_projects'])} project(s) have no `configure` timer, "
              f"so their whole-pass figure is the SUMMED parts rather than the containing timer -- "
              f"{', '.join(report['summed_parts_projects'])}")
    if report["rows_refused"]:
        print(f"  refused: {report['rows_refused']} row(s) whose partition overlaps or which "
              f"reached no phase -- {', '.join(report['refused_projects'])}")
    for entry in report["unseen"]:
        print(f"  NOT RANKED: {entry['cost']} -- {entry['why']}")


def refusal(name, unmet):
    """A row that declines to carry a duration, and says which condition was not met."""
    return {"project": name, "phase": NO_CONCLUSION, "millis": None, "detail": unmet,
            "plugin_commit": plugin_commit()}


def _reap(checkout, wrapper):
    """Stops this checkout's daemons, so the next build starts one that reads the current state.

    `--stop` and NOT `pkill -f GradleDaemon`, which matches every Gradle daemon on the machine: an
    IDE's, another project's, and the plugin's own test suite if one happens to be running. This
    instrument reaps between arms, so a machine-wide kill turns any concurrent work into collateral.
    """
    stopped = subprocess.run([wrapper, "-p", checkout, "--stop"], capture_output=True, text=True)
    if stopped.returncode != 0:
        print(f"  (--stop exited {stopped.returncode}; the next arm may reuse a warm daemon)")
    time.sleep(3)


def measure(project, checkout, scratch, *, runs=2, wrapper=None, flags=()):
    """Rows for whichever phases this project's runs actually reached.

    TWO INVOCATIONS, and it was three. The first build after the plugin was
    installed used to unpack its agent into the checkout DURING configuration, so the entry it
    stored recorded that file as absent and the next build discarded it -- two runs would never
    observe a hit on a fresh checkout, and the hit row is the one an adopter lives in. The plugin
    now names the jar's path at configuration time and unpacks it in `doFirst`, so the second build
    reuses; measured on demo and spring-boot, where build 1 of 3 reported "Configuration cache entry
    reused" and build 2 added nothing.

    `--runs` still takes any count, because a project whose own build invalidates its entry for
    reasons of its own is a thing this instrument should be able to look at rather than assume away.
    """
    wrapper = wrapper or os.path.join(checkout, "gradlew")
    os.makedirs(scratch, exist_ok=True)
    # A store has to be INDUCED, or there is no store row to take: a checkout measured yesterday
    # still has yesterday's entry, and every run would reuse it and report only the hit phase.
    #
    # Induced through the counters path, which the plugin READS. A property nothing reads does not
    # invalidate anything -- measured on detekt, where an unread `-Pyoriwake.configcost.arm=<epoch>`
    # left the entry reusable and every store row came back NO CONCLUSION. Gradle fingerprints the
    # project properties a build actually asks for, not the ones it was given.
    #
    # The same value goes to all three arms: the first sees a value this checkout has not seen and
    # reconfigures, and the two after it reuse what it stored.
    counters_file = os.path.join(scratch, f"counters-{int(time.time())}.tsv")
    seen = {}
    for attempt in range(runs):
        trace = os.path.join(scratch, f"trace-{attempt}")
        log = os.path.join(scratch, f"config-cost-{attempt}.log")
        if os.path.exists(counters_file):
            os.remove(counters_file)
        _reap(checkout, wrapper)
        with open(log, "w", encoding="utf-8") as handle:
            subprocess.run(
                [wrapper, "-p", checkout, "help", "--configuration-cache", "--console=plain"]
                + list(flags)
                + ["-I", INIT_SCRIPT, yoriwake_checkout.init_script_version_flag(),
                   f"-Dorg.gradle.internal.operations.trace={trace}",
                   f"-Pyoriwake.internal.counters={counters_file}"],
                stdout=handle, stderr=subprocess.STDOUT, text=True,
            )
        with open(log, encoding="utf-8", errors="replace") as handle:
            phase = phase_of(handle.read())
        if phase is None:
            continue
        try:
            counters = read_counters(counters_file) if phase == STORE else {}
        except Unreadable as problem:
            print(f"  ({problem})")
            continue
        seen[phase] = (f"{trace}-log.txt", counters)

    rows = []
    for phase in (STORE, HIT):
        if phase not in seen:
            rows.append(refusal(
                project["name"],
                f"no run of this project reported the {phase} phase in its own output, so nothing "
                f"here describes it",
            ))
            continue
        trace_path, counters = seen[phase]
        try:
            operations = read_trace(trace_path)
            if phase == STORE:
                millis, projects = store_cost(operations)
            else:
                millis, projects = hit_cost(operations), None
        except Unreadable as problem:
            rows.append(refusal(project["name"], str(problem)))
            continue
        rows.append(row(
            project["name"], phase, millis, projects,
            counters.get("testTasksConfigured"), counters,
            evaluated_millis=evaluated_cost(operations) if phase == STORE else None,
            store_state_millis=store_state_cost(operations) if phase == STORE else None,
        ))
    return rows


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[1])
    parser.add_argument("projects", nargs="*", help="project TOML files")
    parser.add_argument("--all", action="store_true", help="every project with a checkout on disk")
    parser.add_argument("--out", default=DEFAULT_OUT)
    parser.add_argument("--runs", type=int, default=2)
    parser.add_argument("--rank", action="store_true",
                        help="rank the costs in the results file and measure nothing")
    parser.add_argument("--commit", default=None,
                        help="rank only rows this plugin commit wrote; the newest by default, "
                             "because one ranking over two plugins is two populations")
    args = parser.parse_args(argv)

    if args.rank:
        rows, unreadable = read_rows(args.out)
        if unreadable:
            print(f"{unreadable} row(s) could not be parsed and are not in this ranking")
        rows = [r for r in rows if r.get("attribution") in ATTRIBUTION_RULES]
        if not rows:
            print(f"no rows at {args.out} carry a per-row attribution stamp; every row older than "
                  f"that summed a timer with the timers inside it and is a different quantity")
            return 1
        if args.commit:
            rows = [r for r in rows if (r.get("plugin_commit") or "").startswith(args.commit)]
        else:
            newest = rows[-1].get("plugin_commit")
            rows = [r for r in rows if r.get("plugin_commit") == newest]
        print_ranking(rank(rows))
        return 0

    import tomllib
    import run  # noqa: PLC0415 -- imported here so the module stays importable without a checkout
    files = list(args.projects)
    if args.all:
        files = sorted(
            os.path.join(HERE, "projects", f)
            for f in os.listdir(os.path.join(HERE, "projects")) if f.endswith(".toml")
        )
    if not files:
        parser.error("name at least one project file, or --all")

    measured_rows = []
    for path in files:
        with open(path, "rb") as handle:
            project = tomllib.load(handle)
        checkout = os.path.join(HERE, "checkouts", project["name"])
        if not os.path.isdir(checkout):
            append_row(args.out, refusal(project["name"], f"no checkout at {checkout}"))
            print(f"{project['name']:22} NO CONCLUSION -- no checkout")
            continue
        # A directory is not a checkout. demo-android has one and no wrapper, and reaping its
        # daemons crashed the sweep on the project after the two it had already measured -- which
        # the checkpoint file survived and this refusal makes unnecessary.
        try:
            gradlew = run.wrapper(checkout)
        except Exception as problem:  # noqa: BLE001 -- any failure here is "not a usable checkout"
            append_row(args.out, refusal(project["name"], f"no Gradle wrapper: {problem}"))
            print(f"{project['name']:22} NO CONCLUSION -- no Gradle wrapper")
            continue
        if not os.path.exists(gradlew):
            append_row(args.out, refusal(project["name"], f"no Gradle wrapper at {gradlew}"))
            print(f"{project['name']:22} NO CONCLUSION -- no Gradle wrapper")
            continue
        # The project's own JDK and its own flags. armeria refused both phases without them: the
        # build never got far enough to say which phase it was, and a refusal that is really "this
        # instrument ran the project wrong" is a refusal about the wrong thing.
        flags = run.daemon_jvm_flags(project) + run.guest_flags(project)
        scratch = os.path.join(HERE, "scratch", "config-cost", project["name"])
        shutil.rmtree(scratch, ignore_errors=True)
        for measured in measure(project, checkout, scratch, runs=args.runs,
                                wrapper=gradlew, flags=flags):
            append_row(args.out, measured)
            measured_rows.append(measured)
            if measured["phase"] == NO_CONCLUSION:
                print(f"{measured['project']:22} NO CONCLUSION -- {measured['detail']}")
            else:
                per_task = measured["plugin_millis_per_test_task"]
                print(
                    f"{measured['project']:22} {measured['phase']:5} "
                    f"{measured['millis'] / 1000:8.2f}s over {measured['test_tasks']} Test tasks"
                    + (f", {per_task:.1f}ms of it the plugin's, each" if per_task else "")
                )
    if measured_rows:
        print_ranking(rank(measured_rows))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
