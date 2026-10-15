"""
Tests for the configuration-cost instrument, with Gradle faked.

The output this module is written against is a duration that means nothing: a zero from a trace
that held no such operation, a hit row from a run that never reused an entry, or a per-`Test`-task
figure divided by a denominator nobody printed. Most cases below assert that a NUMBER IS NOT
produced -- that the instrument refuses and names the unmet condition instead.

Run with `python bench/test_config_cost.py`, or under pytest.

Cases are named case_* and RETURN a count of failed checks, following test_run.py: a function that
returns instead of asserting would be collected by pytest and pass however many checks failed. The
single test_* entry point below asserts.
"""

import json
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import config_cost  # noqa: E402

SCRATCH = tempfile.mkdtemp(prefix="yoriwake-config-cost-tests-")


def fail(message):
    print(f"  FAIL: {message}")
    return 1


def _tree(*operations):
    """What `read_trace` returns: completed operations, flat, durations in milliseconds."""
    return list(operations)


def _op(details, duration, display="op"):
    return {"displayName": display, "duration": duration, "detailsClassName": details}


CONFIGURE = "org.gradle.configuration.project.LifecycleProjectEvaluator$ConfigureProjectDetails"
EVALUATED = "org.gradle.initialization.ProjectsEvaluatedNotifier$NotifyProjectsEvaluatedListeners$1"
CHECK = "org.gradle.internal.cc.impl.CheckFingerprintDetails$FingerprintCheckDetails"


def case_a_run_is_labelled_by_its_own_output():
    failures = 0
    if config_cost.phase_of("... Configuration cache entry reused.") != config_cost.HIT:
        failures += fail("a run that reused its entry was not read as the hit phase")
    if config_cost.phase_of("... Configuration cache entry stored.") != config_cost.STORE:
        failures += fail("a run that stored an entry was not read as the store phase")
    # The one that matters: a run whose output says neither is not quietly assigned to one. Both
    # phases produce a duration, and a duration attached to the wrong phase is worse than none.
    if config_cost.phase_of("BUILD SUCCESSFUL in 3s") is not None:
        failures += fail("a run confirming neither phase was assigned a phase anyway")
    return failures


def case_a_trace_with_no_matching_operation_is_unreadable_not_zero():
    failures = 0
    empty = _tree(_op("something.else.Entirely", 500))
    try:
        config_cost.store_cost(empty)
        failures += fail("a trace with no configuration operations reported a store cost")
    except config_cost.Unreadable:
        pass
    try:
        config_cost.hit_cost(empty)
        failures += fail("a trace with no fingerprint check reported a hit cost")
    except config_cost.Unreadable:
        pass
    missing = os.path.join(SCRATCH, "nowhere-log.txt")
    try:
        config_cost.read_trace(missing)
        failures += fail("a missing trace was read as an empty one")
    except config_cost.Unreadable:
        pass
    return failures


def case_a_trace_log_is_paired_into_durations():
    failures = 0
    path = os.path.join(SCRATCH, "paired-log.txt")
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(json.dumps({"id": 1, "startTime": 100, "displayName": "Configure project :a",
                                 "detailsClassName": CONFIGURE}) + "\n")
        # A progress record in the middle, which carries `time` and no duration of its own.
        handle.write(json.dumps({"id": 1, "time": 120, "details": {"message": "noise"}}) + "\n")
        handle.write(json.dumps({"id": 1, "endTime": 400}) + "\n")
        handle.write("this line is not json\n")
    operations = config_cost.read_trace(path)
    if len(operations) != 1 or operations[0]["duration"] != 300:
        failures += fail(f"start and finish records were not paired: {operations}")

    empty = os.path.join(SCRATCH, "empty-log.txt")
    open(empty, "w", encoding="utf-8").close()
    try:
        config_cost.read_trace(empty)
        failures += fail("a trace that recorded nothing was read as a build that did nothing")
    except config_cost.Unreadable:
        pass
    return failures


def case_the_store_cost_includes_the_phase_the_plugin_works_in():
    failures = 0
    tree = _tree(
        _op(CONFIGURE, 100, "Configure project :a"),
        _op(CONFIGURE, 200, "Configure project :b"),
        # Where this plugin does ALL of its eager work. A store cost that counted only the
        # per-project operations would report zero for a plugin whose whole cost sits here.
        _op(EVALUATED, 700, "Notify projectsEvaluated listeners"),
    )
    millis, projects = config_cost.store_cost(tree)
    if millis != 1000:
        failures += fail(f"the store cost missed an operation: {millis}ms of an expected 1000")
    if projects != 2:
        failures += fail(f"the project count came from somewhere else: {projects}")
    return failures


def case_the_hit_cost_is_the_fingerprint_check():
    failures = 0
    tree = _tree(_op(CHECK, 42, "Check configuration cache fingerprint"), _op(CONFIGURE, 900))
    if config_cost.hit_cost(tree) != 42:
        failures += fail("the hit cost is not the fingerprint check alone")
    return failures


def case_both_denominators_travel_with_the_figure():
    failures = 0
    counters = {"attach": 4, "git.diff": 2, "git.rev-parse": 3, "deriveScope": 1,
                "allprojectsWalk": 1, "sourceFilesOpened": 120}
    built = config_cost.row("demo", config_cost.STORE, 2000, projects=9, test_tasks=4,
                            counters=counters)
    if built["build_configure_millis_per_test_task"] != 500:
        failures += fail("the per-Test-task figure is not millis over Test tasks")
    # Both, and neither derived from the other: `configure` runs per Test task, and the project
    # count is a different number -- 529 projects, 446 attaching, 528 Test tasks on spring-boot.
    if built["projects"] != 9 or built["test_tasks"] != 4:
        failures += fail("a per-task figure was published without both denominators")
    if built["git_calls"] != 5:
        failures += fail(f"git calls were not summed across commands: {built['git_calls']}")
    return failures


def case_a_hit_row_claims_no_git_count():
    failures = 0
    # A reusing build runs no configuration phase, so the plugin writes no counters at all. Zero
    # would be a claim about the plugin; None is the truth, which is that this instrument cannot see
    # the value sources Gradle re-executes from inside its own fingerprint check.
    built = config_cost.row("demo", config_cost.HIT, 500, projects=None, test_tasks=None,
                            counters={})
    if built["git_calls"] is not None:
        failures += fail("a hit row claimed a git count it cannot have")
    if built["build_configure_millis_per_test_task"] is not None:
        failures += fail("a hit row divided by a denominator it does not have")
    return failures


def case_a_killed_run_leaves_its_finished_projects_readable():
    failures = 0
    path = os.path.join(SCRATCH, "checkpoint.jsonl")
    config_cost.append_row(path, config_cost.row("one", config_cost.STORE, 10, 1, 1, {}))
    config_cost.append_row(path, config_cost.row("two", config_cost.HIT, 20, None, None, {}))
    rows, unreadable = config_cost.read_rows(path)
    if len(rows) != 2 or unreadable:
        failures += fail(f"a complete checkpoint did not read back: {len(rows)} rows, {unreadable} bad")

    # Killed mid-write. The last record is half a line, and the rows before it are still findings.
    with open(path, "a", encoding="utf-8") as handle:
        handle.write('{"project": "three", "phase": "sto')
    rows, unreadable = config_cost.read_rows(path)
    if len(rows) != 2:
        failures += fail(f"a truncated tail cost the finished rows: {len(rows)}")
    if unreadable != 1:
        failures += fail("a record truncated mid-write was skipped silently rather than reported")
    return failures


def case_a_refusal_names_the_condition_it_could_not_meet():
    failures = 0
    declined = config_cost.refusal("demo", "no run of this project reported the hit phase")
    if declined["phase"] != config_cost.NO_CONCLUSION:
        failures += fail("a refusal was labelled as a phase")
    if declined["millis"] is not None:
        failures += fail("a refusal carried a duration")
    if "hit phase" not in (declined["detail"] or ""):
        failures += fail("a refusal did not name what was missing")
    return failures


def case_a_store_row_with_no_counters_claims_no_git_count():
    failures = 0
    # The one field that used to be invented. Every sibling reads through `.get()` and answers None
    # for a counters file that was never written; a sum over an empty dict answers 0, and
    # `git_calls: 0` is the headline column of BENCHMARKS 4h -- "the plugin asked git nothing" is
    # exactly what a MISSING measurement would have published for a build that asked git plenty.
    blind = config_cost.row("demo", config_cost.STORE, 100, 5, 3, {})
    if blind["git_calls"] is not None:
        failures += fail(f"a store row with no counters claimed {blind['git_calls']} git calls")
    counted = config_cost.row("demo", config_cost.STORE, 100, 5, 3, {"git.diff": 0})
    if counted["git_calls"] != 0:
        failures += fail("a genuinely measured zero was suppressed along with the invented one")
    return failures


def case_a_malformed_counters_file_is_unreadable_not_partial():
    failures = 0
    path = os.path.join(SCRATCH, "half-written.tsv")
    with open(path, "w", encoding="utf-8") as handle:
        handle.write("deriveScope\t1\nsourceFilesOpened\t42\nallprojectsWa")
    # A build killed mid-write leaves a partial line. Returning the rows before it reports a
    # fraction of the build's work as the whole of it, which is the shape this file refuses
    # everywhere else.
    try:
        config_cost.read_counters(path)
        failures += fail("a counters file truncated mid-line was read as a complete one")
    except config_cost.Unreadable:
        pass
    if config_cost.read_counters(os.path.join(SCRATCH, "never-written.tsv")) != {}:
        failures += fail("a counters file that was never written did not read as empty")
    return failures


def case_every_row_carries_the_commit_it_measured():
    failures = 0
    built = config_cost.row("demo", config_cost.STORE, 100, 1, 1, {})
    declined = config_cost.refusal("demo", "no checkout")
    # Rule 4 of published-numbers-and-their-caveats.md: a figure without the commit it measured is
    # a figure nobody can reproduce. The refusal carries it too -- "this project produced no row at
    # commit X" is itself a finding about X.
    for label, r in (("row", built), ("refusal", declined)):
        if not r.get("plugin_commit"):
            failures += fail(f"a {label} carries no plugin commit")
    return failures


def case_counters_absent_is_not_counters_zero():
    failures = 0
    counters = config_cost.read_counters(os.path.join(SCRATCH, "no-such-file.tsv"))
    if counters != {}:
        failures += fail("a missing counters file invented values")
    built = config_cost.row("demo", config_cost.STORE, 100, 1, None, counters)
    if built["scope_derivations"] is not None:
        failures += fail("an unwritten counter was reported as zero derivations")
    return failures


def case_a_timer_that_contains_the_others_is_the_total_not_a_summand():
    """The defect that made a re-taken table read 2x high, and the tell that would have caught it.

    `configure` times the whole projectsEvaluated pass; `deriveScope` and `allprojectsWalk` happen
    inside it. Summing all three published 1,506ms of plugin work inside an 832ms notification on
    spring-framework, with a negative residual.
    """
    failures = 0
    counters = {"testTasksConfigured": 2,
                "configure": 1, "configure.millis": 900,
                "deriveScope": 1, "deriveScope.millis": 400,
                "allprojectsWalk": 1, "allprojectsWalk.millis": 100}
    built = config_cost.row("demo", config_cost.STORE, 2000, 5, 2, counters, evaluated_millis=1000)
    if built["attributed_millis"] != 900:
        failures += fail(f"the container was summed with its contents: {built['attributed_millis']}")
    if built["unattributed_millis"] != 100:
        failures += fail(f"the residual is wrong: {built['unattributed_millis']}")
    if built["partition_overlaps"] is not False:
        failures += fail("a sound partition was flagged as overlapping")
    # The parts stay beside the total as the breakdown they are.
    if built["mechanism_millis"]["deriveScope"] != 400:
        failures += fail("the nested timers were dropped once the container became the total")

    # And the tell fires when the parts really do exceed the whole: the shape the old formula
    # produced on every large row.
    overlapping = config_cost.row("demo", config_cost.STORE, 2000, 5, 2, counters, evaluated_millis=800)
    if overlapping["partition_overlaps"] is not True:
        failures += fail("a residual below zero was not flagged")
    return failures


def case_the_partition_carries_its_own_residual():
    failures = 0
    counters = {"testTasksConfigured": 2, "deriveScope": 1, "deriveScope.millis": 400,
                "allprojectsWalk": 1, "allprojectsWalk.millis": 100,
                "git.diff": 1, "git.diff.millis": 50}
    built = config_cost.row("demo", config_cost.STORE, 2000, 5, 2, counters,
                            evaluated_millis=700, store_state_millis=90)
    if built["attributed_millis"] != 550:
        failures += fail(f"the mechanisms did not sum: {built['attributed_millis']}")
    # The residual is the whole point. 550ms attributed inside a 700ms operation leaves 150ms that
    # belongs to nothing named here, and publishing the 550 alone would read as the whole cost.
    if built["unattributed_millis"] != 150:
        failures += fail(f"the residual was not carried: {built['unattributed_millis']}")
    if built["git_calls"] != 1:
        failures += fail(f"a timer was counted as a git call: {built['git_calls']}")
    if built["store_state_millis"] != 90:
        failures += fail("the store-state remainder was dropped")
    # And it stays a residual: an operation this instrument could not read means no partition, not
    # a partition that happens to account for everything.
    blind = config_cost.row("demo", config_cost.STORE, 2000, 5, 2, counters)
    if blind["unattributed_millis"] is not None:
        failures += fail("a partition was published with no operation to partition")
    return failures


def _rankable(project, **overrides):
    """A store row shaped like a real one, so the ranking is tested against rows and not dicts."""
    counters = {"testTasksConfigured": 2, "configure": 1, "configure.millis": 500,
                "deriveScope": 1, "deriveScope.millis": 400,
                "allprojectsWalk": 1, "allprojectsWalk.millis": 10}
    built = config_cost.row(project, config_cost.STORE, 2000, 5, 2, counters,
                            evaluated_millis=700, store_state_millis=30)
    built.update(overrides)
    return built


def case_a_row_whose_partition_overlaps_is_refused_rather_than_ranked():
    failures = 0
    # The figures in an overlapping row are real durations of something and nothing says which.
    # Ranking it would put a quantity nobody can name at the top of the list an optimisation takes
    # its target off -- which is the shape that published a 2x rise that was not one.
    good = _rankable("sound")
    bad = _rankable("overlapping", partition_overlaps=True)
    report = config_cost.rank([good, bad])
    if report["rows_refused"] != 1 or report["refused_projects"] != ["overlapping"]:
        failures += fail(f"the overlapping row was not refused: {report['refused_projects']}")
    for entry in report["ranked"]:
        if entry["worst_project"] == "overlapping":
            failures += fail(f"an overlapping row was ranked under {entry['cost']}")
    return failures


def case_a_negative_residual_is_not_a_cost_of_zero():
    failures = 0
    # A residual below zero is the tell that the parts exceed the whole. Clamping it to zero would
    # publish "this pass costs nothing beyond its named timers" for exactly the rows that cannot
    # say so.
    row = _rankable("p", unattributed_millis=-40)
    if config_cost._residual(row) is not None:
        failures += fail("a negative residual was reported as a cost")
    return failures


def case_the_ranking_keeps_the_builds_costs_out_of_the_plugins_order():
    failures = 0
    # Gradle's fingerprint check is the largest thing this instrument can see on most projects, and
    # this plugin neither owns it nor can cut it. Sorting it in would hand an optimisation a target
    # it cannot take.
    report = config_cost.rank([_rankable("p"), _rankable("p", phase=config_cost.HIT, millis=9000)])
    ours = [e for e in report["ranked"] if e["ours"]]
    theirs = [e for e in report["ranked"] if not e["ours"]]
    if not theirs or theirs[0]["cost"] != "fingerprint check":
        failures += fail("the fingerprint check was not held apart from the plugin's own costs")
    if report["ranked"].index(theirs[0]) < len(ours):
        failures += fail("a cost the plugin does not own was ranked above one it does")
    if ours[0]["cost"] != "deriveScope":
        failures += fail(f"the plugin's costs are not worst-first: {[e['cost'] for e in ours]}")
    return failures


def case_the_residual_is_not_ranked_as_a_cost_this_plugin_owns():
    failures = 0
    # `projects_evaluated_millis` is Gradle's whole notification, which runs every listener the
    # build registered. The residual after this plugin's own timer is other plugins' callbacks as
    # well as ours, and ranking it as ours put it FIRST on a corpus run at 1,274ms --
    # which would have sent the optimisation unit at micrometer's other plugins.
    entry = next(e for e in config_cost.rank([_rankable("p")])["ranked"]
                 if e["cost"] == "residual inside projectsEvaluated")
    if entry["ours"]:
        failures += fail("the projectsEvaluated residual was ranked as a cost this plugin owns")
    return failures


def case_a_row_with_no_containing_timer_says_which_rule_produced_its_total():
    failures = 0
    # A re-take retracted the summing formula and the stamp went on saying
    # "containing-timer" for every row, including the four on this corpus whose counters file has no
    # `configure` at all. Two quantities in one column under one label is what that banner is about.
    summed = config_cost.row("demo", config_cost.STORE, 2000, 1, 1,
                             {"testTasksConfigured": 1, "deriveScope": 1, "deriveScope.millis": 19,
                              "allprojectsWalk": 1, "allprojectsWalk.millis": 1})
    if summed["attribution"] != "summed-parts":
        failures += fail(f"a row with no containing timer was stamped {summed['attribution']!r}")
    if summed["attributed_millis"] != 20:
        failures += fail("the fallback total is not the sum of the parts")
    contained = config_cost.row("demo", config_cost.STORE, 2000, 1, 1,
                                {"testTasksConfigured": 1, "configure": 1, "configure.millis": 30,
                                 "deriveScope": 1, "deriveScope.millis": 19})
    if contained["attribution"] != config_cost.CONTAINING_TIMER_ATTRIBUTION:
        failures += fail("a row WITH a containing timer lost its stamp")
    report = config_cost.rank([summed, contained])
    if report["summed_parts_projects"] != ["demo"]:
        failures += fail(f"the summed rows were not named: {report['summed_parts_projects']}")
    return failures


def case_a_row_that_timed_nothing_is_still_ranked_for_the_phase_it_measured():
    failures = 0
    # A reusing build writes no counters file by construction, so every hit row has no timers at
    # all. Filing that with the two attribution RULES and filtering the ranking on those dropped
    # every hit row -- which is to say Gradle's fingerprint check, the largest cost visible here.
    hit = config_cost.row("p", config_cost.HIT, 900, None, None, {})
    if hit["attribution"] != config_cost.NO_TIMERS:
        failures += fail(f"a row with no timers was stamped {hit['attribution']!r}")
    if hit["attribution"] not in config_cost.ATTRIBUTION_RULES:
        failures += fail("a hit row would be filtered out of its own ranking")
    entry = next(e for e in config_cost.rank([hit])["ranked"] if e["cost"] == "fingerprint check")
    if entry["worst_millis"] != 900:
        failures += fail(f"the fingerprint check lost its only row: {entry}")
    return failures


def case_a_residual_from_the_summed_parts_is_not_mixed_with_the_others():
    failures = 0
    # `configure` CONTAINS the two timers under it, so a residual taken against the summed parts is
    # systematically larger than one taken against the container. One median over both is a figure
    # neither rule produced.
    summed = _rankable("summed")
    summed["attribution"] = config_cost.SUMMED_PARTS_ATTRIBUTION
    if config_cost._residual(summed) is not None:
        failures += fail("a summed-parts residual entered the ranked figure")
    return failures


def case_a_ranking_names_the_costs_it_cannot_see():
    failures = 0
    # A ranking that lists only what one instrument measures reads as a ranking of everything, and
    # the two largest costs an adopter pays are not in this trace at all.
    # ENUMERATED FROM THE PRODUCER, not checked member by member. A test that asserts two known
    # entries still exist goes green when a third is needed and not added, which is the shape of
    # a guard keyed on wording.
    named = {entry["cost"] for entry in config_cost.rank([_rankable("p")])["unseen"]}
    if named != {name for name, _ in config_cost.UNSEEN_COSTS}:
        failures += fail(f"the ranking's unseen list is not the declared one: {named}")
    if len(config_cost.UNSEEN_COSTS) != 2:
        failures += fail(
            f"{len(config_cost.UNSEEN_COSTS)} unseen costs are declared and this test knows 2; "
            f"a cost added here must be added to the docs that list them too")
    return failures


def case_a_ranking_over_two_plugin_commits_says_so():
    failures = 0
    # Two commits under one ranking is two populations, and the figure a reader takes off the top
    # then belongs to neither.
    report = config_cost.rank([_rankable("a", plugin_commit="a" * 40),
                               _rankable("b", plugin_commit="b" * 40)])
    if len(report["plugin_commits"]) != 2:
        failures += fail(f"the ranking did not carry both commits: {report['plugin_commits']}")
    return failures


def test_config_cost():
    failures = 0
    for name, case in sorted(globals().items()):
        if name.startswith("case_"):
            print(name)
            failures += case()
    assert failures == 0, f"{failures} failed checks"


if __name__ == "__main__":
    test_config_cost()
    print("\nall checks passed")
