"""
Unit tests for the offline decision replay.

THE FAILURE THIS EXISTS TO PREVENT is a replay that answers "0 missed" while disagreeing with the
selector it claims to replay. So the self-check is tested first, and every case runs the real agent
jar: a replay tested against a stub would prove only that the stub agrees with itself.

Run: `python bench/test_replay_decisions.py` (needs `./gradlew :agent:shadowJar` in the yoriwake
checkout)
"""

import json
import os
import re
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

import replay_decisions as rd  # noqa: E402
import run  # noqa: E402

# The fixtures live under the repository so the paths a row records stay relative to it, as a real
# row's do. `sandbox/` is gitignored, so a fresh worktree does not have it.
os.makedirs(os.path.join(ROOT, "sandbox"), exist_ok=True)


def fail(message):
    print(f"  FAIL: {message}")
    return 1


AGENT_SOURCE = os.path.join(run.SOURCE, "agent/src/main/java/io/github/zeuspizza/yoriwake/agent")


def schema_version():
    source = open(os.path.join(AGENT_SOURCE, "contract/AgentContract.java")).read()
    return re.search(r"MAP_SCHEMA_VERSION\s*=\s*(\d+)", source).group(1)


def filter_inputs():
    """Every property the current rule reads, as the agent records it: each one, empty or not."""
    contract = open(os.path.join(AGENT_SOURCE, "contract/AgentContract.java")).read()
    rule = open(os.path.join(AGENT_SOURCE, "select/FilterRule.java")).read()
    listed = re.search(r"INPUTS\s*=\s*\{(.*?)\};", rule, re.S).group(1)
    return [re.search(rf'\b{name}\s*=\s*"([^"]+)"', contract).group(1)
            for name in re.findall(r"AgentContract\.(\w+)", listed)]


def test_id(cls):
    return f"[engine:junit-jupiter]/[class:{cls}]/[method:t()]"


def fixture(root, verdicts, induced, schema=None, drop_input=None):
    """A map where Alpha covers com.acme.A and Beta covers com.acme.B, a change to A, and one row.

    Both ran in one JVM, Beta before that JVM first touched com.acme.A, so Beta may be skipped.
    """
    map_dir = os.path.join(root, "map")
    os.makedirs(map_dir)
    with open(os.path.join(map_dir, "coverage.tsv"), "w") as handle:
        for cls, covered in (("Alpha", "com.acme.A"), ("Beta", "com.acme.B")):
            handle.write(f"SUCCESSFUL\t1000000\t{covered}\t{test_id(cls)}\n")
    with open(os.path.join(map_dir, "positions.tsv"), "w") as handle:
        handle.write(f"j1\t1\t{test_id('Beta')}\nj1\t2\t{test_id('Alpha')}\n")
    with open(os.path.join(map_dir, "first-touch.tsv"), "w") as handle:
        handle.write("j1\t2\tcom.acme.A\n")
    open(os.path.join(map_dir, "named-touch.tsv"), "w").close()
    with open(os.path.join(map_dir, "schema-version"), "w") as handle:
        handle.write((schema or schema_version()) + "\n")
    parts = os.path.join(root, "decisions.parts")
    os.makedirs(parts)
    inputs = {name: "" for name in filter_inputs()}
    inputs.update({"yoriwake.select": "true", "yoriwake.change.classes": "com.acme.A"})
    inputs.pop(drop_input, None)
    notes = [("record-version", "2"), ("rows", str(len(verdicts))), ("outcome", "narrowed")]
    notes += [(rd.INPUT_NOTE_PREFIX + k, v) for k, v in sorted(inputs.items())]
    lines = ["# test\tverdict\treason"]
    lines += [f"{run.DECISION_NOTE_PREFIX}{k}\t{v}" for k, v in notes]
    lines += [f"{test_id(cls)}\t{verdict}" for cls, verdict in verdicts]
    with open(os.path.join(parts, f"{run.DECISIONS_FILE}.1-1{run.DECISION_PART_SUFFIX}"), "w") as h:
        h.write("\n".join(lines) + "\n")
    row = {"target": "A.java::body::f", "status": "checked", "induced": induced,
           "replay": {"map": os.path.relpath(map_dir, ROOT), "decisions": os.path.relpath(parts, ROOT),
                      "digest": run.selector_digest()}}
    results = os.path.join(root, "p.json")
    with open(results, "w") as handle:
        json.dump({"project": "p", "mutations": [row]}, handle)
    return results


# What the current selector records for this fixture. Gamma is absent from the map, so it runs.
FAITHFUL = [("Alpha", "included\tREACHES_CHANGE"), ("Beta", "excluded\tSKIPPED"),
            ("Gamma", "included\tNOT_IN_MAP")]


def replayed(results):
    rows, _ = rd.rows_to_replay([results])
    return rd.replay(rows)[0]


def case_a_row_that_does_not_reproduce_its_own_record_is_drift_not_an_answer():
    """The replay's claim to be a replay, checked on every row recorded by the current selector."""
    failures = 0
    with tempfile.TemporaryDirectory(dir=os.path.join(ROOT, "sandbox")) as root:
        # Beta recorded as INCLUDED, which the current selector would never say for this change.
        wrong = [("Alpha", "included\tREACHES_CHANGE"), ("Beta", "included\tREACHES_CHANGE"),
                 ("Gamma", "included\tNOT_IN_MAP")]
        entry = replayed(fixture(root, wrong, ["Beta::t"]))
        if entry["verdict"] != "drift":
            failures += fail(f"a record the selector cannot reproduce was answered: {entry}")
    return failures


def case_a_faithful_record_reproduces_exactly():
    failures = 0
    with tempfile.TemporaryDirectory(dir=os.path.join(ROOT, "sandbox")) as root:
        entry = replayed(fixture(root, FAITHFUL, ["Alpha::t"]))
        if entry["verdict"] != "ok" or entry["changed_verdicts"] != 0:
            failures += fail(f"a faithful record did not reproduce: {entry}")
        if entry["included"] != 2:
            failures += fail(f"{entry['included']} included, expected Alpha and Gamma")
    return failures


def case_an_induced_failure_the_selector_skips_is_missed():
    failures = 0
    with tempfile.TemporaryDirectory(dir=os.path.join(ROOT, "sandbox")) as root:
        entry = replayed(fixture(root, FAITHFUL, ["Beta::t"]))
        if entry["verdict"] != "missed" or entry["missed"] != ["Beta::t"]:
            failures += fail(f"a skipped failing test was not reported missed: {entry}")
    return failures


def case_a_failure_the_recorded_arm_never_discovered_is_undetermined_not_clean():
    """The replay can only re-decide tests the recorded arm saw; anything else is not a pass."""
    failures = 0
    with tempfile.TemporaryDirectory(dir=os.path.join(ROOT, "sandbox")) as root:
        entry = replayed(fixture(root, FAITHFUL, ["Delta::t"]))
        if entry["undetermined"] != ["Delta::t"] or entry["missed"]:
            failures += fail(f"an undiscovered failing test was not undetermined: {entry}")
    return failures


def case_a_record_missing_an_input_the_rule_reads_is_not_replayed():
    """Defaulting an input the recording agent never read answers for inputs the arm never had."""
    failures = 0
    with tempfile.TemporaryDirectory(dir=os.path.join(ROOT, "sandbox")) as root:
        entry = replayed(fixture(root, FAITHFUL, ["Beta::t"],
                                 drop_input="yoriwake.change.exemptTestClasses"))
        if entry["verdict"] != "not-replayed" or "exemptTestClasses" not in entry["reason"]:
            failures += fail(f"a record missing an input was answered: {entry}")
    return failures


def case_a_map_of_another_schema_is_not_replayed():
    """The rule reads such a map as no map, which describes nothing the recorded arm selected on."""
    failures = 0
    with tempfile.TemporaryDirectory(dir=os.path.join(ROOT, "sandbox")) as root:
        entry = replayed(fixture(root, FAITHFUL, ["Beta::t"], schema=str(int(schema_version()) - 1)))
        if entry["verdict"] != "not-replayed" or "schema version" not in entry["reason"]:
            failures += fail(f"a map of another schema was answered: {entry}")
    return failures


def case_a_row_without_replay_inputs_is_left_out_with_its_reason():
    failures = 0
    with tempfile.TemporaryDirectory(dir=os.path.join(ROOT, "sandbox")) as root:
        path = os.path.join(root, "old.json")
        with open(path, "w") as handle:
            json.dump({"project": "p", "mutations": [{"target": "t", "status": "checked"}]}, handle)
        rows, skipped = rd.rows_to_replay([path])
        if rows or skipped != {"recorded before replay inputs were kept": 1}:
            failures += fail(f"a row with no replay block was not left out by name: {skipped}")
    return failures


def case_names_are_read_from_each_engine_s_ids():
    failures = 0
    for test, expected in (
            ("[engine:junit-jupiter]/[class:com.A]/[method:t()]", ("com.A", "t")),
            ("[engine:junit-vintage]/[runner:com.A]/[test:t(com.A)]", ("com.A", "t")),
            ("[engine:junit-jupiter]/[class:com.A]/[nested-class:B]/[method:t(int)]", ("B", "t")),
            ("[engine:junit-jupiter]/[class:com.A]", None)):
        if rd.name_of(test) != expected:
            failures += fail(f"{test} named {rd.name_of(test)}, expected {expected}")
    return failures


def case_a_replay_that_replayed_nothing_or_lost_a_record_fails():
    failures = 0
    if not rd.refusals([]):
        failures += fail("a replay of zero rows passed, which would vouch for a selector unasked")
    lost = [{"project": "p", "target": "t", "verdict": "not-replayed",
             "reason": "the kept decision record is torn or missing"},
            {"project": "p", "target": "u", "verdict": "clean"}]
    found = rd.refusals(lost)
    if not any("p t" in why for why in found):
        failures += fail(f"a published row whose kept record is missing passed: {found}")
    if rd.refusals([{"project": "p", "target": "u", "verdict": "clean"}]):
        failures += fail("a clean replay was refused")
    return failures


def case_every_case_in_this_file_is_registered():
    source = open(__file__, encoding="utf-8").read()
    defined = set(re.findall(r"^def (case_\w+)", source, re.M))
    orphans = sorted(defined - {c.__name__ for c in CASES})
    if orphans:
        return fail(f"defined and never registered: {', '.join(orphans)}")
    return 0


CASES = (case_every_case_in_this_file_is_registered,
         case_a_replay_that_replayed_nothing_or_lost_a_record_fails,
         case_a_row_that_does_not_reproduce_its_own_record_is_drift_not_an_answer,
         case_a_faithful_record_reproduces_exactly,
         case_an_induced_failure_the_selector_skips_is_missed,
         case_a_failure_the_recorded_arm_never_discovered_is_undetermined_not_clean,
         case_a_record_missing_an_input_the_rule_reads_is_not_replayed,
         case_a_map_of_another_schema_is_not_replayed,
         case_a_row_without_replay_inputs_is_left_out_with_its_reason,
         case_names_are_read_from_each_engine_s_ids)


def test_replay_decisions():
    assert sum(case() for case in CASES) == 0


def main():
    failed = 0
    for case in CASES:
        print(case.__name__)
        failed += case()
    print(f"\n{'all passed' if not failed else 'FAILURES'}: {failed} failed checks")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
