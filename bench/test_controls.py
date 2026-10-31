"""
Tests for the controls: a planted-miss control passes only when every project, or every family of
a named set, reported a miss; the planted no-force control only when every forcing family's row
narrowed; the null change only as a full run of kind `empty-change-set`; and planting never writes
the checkout it was cloned from.

Run with `python bench/test_controls.py`, or under pytest. Cases RETURN a count of failed
checks, as in test_run.py; the single test_* entry point asserts.
"""

import os
import re
import subprocess
import sys
import tempfile
import tomllib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import controls  # noqa: E402


def fail(message):
    print(f"  FAIL: {message}")
    return 1


def _control(*missed_per_row):
    return {"control": True,
            "mutations": [{"control": True, "status": "checked", "missed": list(m)}
                          for m in missed_per_row]}


def case_every_control_project_must_report_a_miss():
    failures = 0
    passing = {name: _control([], ["p.T::t"]) for name in controls.CONTROL_PROJECTS}
    if controls.control_failures(passing):
        failures += fail(f"controls that each reported a miss failed: "
                         f"{controls.control_failures(passing)}")
    silent = {**passing, "demo-kotlin": _control([], [])}
    said = controls.control_failures(silent)
    if len(said) != 1 or not said[0].startswith("demo-kotlin:"):
        failures += fail(f"a control project at zero misses was not named: {said}")
    absent = {**passing, "order-dependent": None}
    if not any(line.startswith("order-dependent:") for line in controls.control_failures(absent)):
        failures += fail("a control project with no results file passed")
    measured = {**passing, "demo": {"mutations": [{"status": "checked", "missed": ["p.T::t"]}]}}
    if not any(line.startswith("demo:") for line in controls.control_failures(measured)):
        failures += fail("a measurement's results file was read as a control's")
    return failures


def case_in_complement_mode_the_shared_jvm_miss_needs_its_rule():
    """Order-dependent's miss must come from a check row a shared-JVM rule routed."""
    failures = 0

    def miss(truth, rules):
        return {"control": True, "mutations": [{"control": True, "status": "checked",
                                                "missed": ["p.T::t"], "ground_truth": truth,
                                                "ground_truth_rules": rules}]}
    base = {name: _control(["p.T::t"]) for name in controls.CONTROL_PROJECTS}
    for name in controls.COMPLEMENT_CONTROLS:
        base[name]["mutations"][0]["ground_truth"] = "complement"
    routed = {**base, "order-dependent": miss("complement+full", ["first-rows", "shares-jvm"])}
    if controls.control_failures(routed, complement_mode=True):
        failures += fail("a shared-JVM miss a rule routed to a full arm failed the control")
    for truth, rules in (("complement", []), ("complement+full", ["first-rows"])):
        said = controls.control_failures({**base, "order-dependent": miss(truth, rules)},
                                         complement_mode=True)
        if not any(line.startswith("order-dependent:") for line in said):
            failures += fail(f"a shared-JVM miss on a {truth} row ({rules}) passed")
    if controls.control_failures({**base, "order-dependent": miss("complement", [])}):
        failures += fail("full-arm mode asked a shared-JVM rule of the miss")
    full_backed = {**routed, "demo": miss("complement+full", ["first-rows"])}
    if not any(line.startswith("demo:") for line in
               controls.control_failures(full_backed, complement_mode=True)):
        failures += fail("a complement-mode demo control whose every miss a full arm stood behind "
                         "passed")
    disagreeing = {**routed, "demo-kotlin": {**routed["demo-kotlin"],
                                             "complement_switched_off": {"at": "x"}}}
    if not any(line.startswith("demo-kotlin:") for line in
               controls.control_failures(disagreeing, complement_mode=True)):
        failures += fail("a control whose complement disagreed with a full arm passed")
    return failures


def case_planting_writes_only_the_clone():
    failures = 0
    with tempfile.TemporaryDirectory() as root:
        real = os.path.join(root, "real")
        os.makedirs(os.path.join(real, "a"))
        with open(os.path.join(real, "a", "S.java"), "w", encoding="utf-8") as handle:
            handle.write("class S { boolean f() { return x; } }\n")
        for args in (["init", "-q"], ["add", "-A"],
                     ["-c", "user.email=t@t", "-c", "user.name=t", "-c", "commit.gpgsign=false",
                      "commit", "-qm", "base"]):
            subprocess.run(["git", "-C", real, *args], check=True, capture_output=True)
        head = subprocess.run(["git", "-C", real, "rev-parse", "HEAD"], capture_output=True,
                              text=True).stdout
        clone = controls.plant(real, os.path.join(root, "clone"),
                               [("a/S.java", "return x;", "return true;"),
                                ("a/S.java", "boolean f()", "boolean g()")])
        with open(os.path.join(clone, "a", "S.java"), encoding="utf-8") as handle:
            if handle.read() != "class S { boolean g() { return true; } }\n":
                failures += fail("the clone does not hold both planted changes")
        if subprocess.run(["git", "-C", clone, "status", "--porcelain"], capture_output=True,
                          text=True).stdout.strip():
            failures += fail("the plant was left uncommitted, so the harness refuses the clone")
        with open(os.path.join(real, "a", "S.java"), encoding="utf-8") as handle:
            if "return x;" not in handle.read():
                failures += fail("planting wrote the real checkout")
        if subprocess.run(["git", "-C", real, "rev-parse", "HEAD"], capture_output=True,
                          text=True).stdout != head or subprocess.run(
                ["git", "-C", real, "status", "--porcelain"], capture_output=True,
                text=True).stdout.strip():
            failures += fail("planting moved or dirtied the real checkout")
        try:
            controls.plant(real, os.path.join(root, "clone"),
                           [("a/S.java", "return x;", "return y;"), ("a/S.java", "absent", "x")])
            failures += fail("an anchor that matches nothing planted nothing and was accepted")
        except SystemExit:
            pass
    return failures


def case_the_planted_miss_matches_exactly_once():
    """An anchor that moved plants nothing, and the controls would pass against the real plugin."""
    failures = 0
    for path, old, new in (controls.PLANTED_MISS, controls.planted_no_force(),
                           *controls.NO_FORCE_RULES):
        with open(os.path.join(controls.yoriwake_checkout.ROOT, path), encoding="utf-8") as handle:
            count = handle.read().count(old)
        if count != 1 or old == new:
            failures += fail(f"a plant's anchor matches {count} times in {path}")
    return failures


def _row(kind, outcome="narrowed", missed=(), induced=None, full_run_kind=None, selected=3,
         status=None):
    induced = list(missed) if induced is None else list(induced)
    return {"control": True, "kind": kind, "target": f"src/X.java::{kind}::f",
            "status": status or ("checked" if induced else "no-effect"),
            "decision_outcome": outcome, "full_run_kind": full_run_kind,
            "selected_distinct": selected, "induced": induced, "missed": list(missed)}


def _file(*rows):
    return {"control": True, "mutations": list(rows)}


def case_every_family_of_a_named_set_must_report_a_miss():
    """A pool/v1 family at zero misses on every fixture fails, named; one fixture's miss is enough."""
    failures = 0
    families = controls.POOL_FAMILIES
    passing = {"demo": _file(*(_row(f, missed=["p.T::t"]) for f in families[::2])),
               "demo-kotlin": _file(*(_row(f, missed=["p.T::t"]) for f in families[1::2]))}
    if controls.set_failures(controls.run.POOL_SHAPES, passing):
        failures += fail(f"a miss in every family failed: "
                         f"{controls.set_failures(controls.run.POOL_SHAPES, passing)}")
    for family in ("override-add", "member-delete", "void-return"):
        on = "demo" if families.index(family) % 2 == 0 else "demo-kotlin"
        quiet = {**passing, on: _file(*(r if r["kind"] != family else
                                        _row(family, outcome="full-run",
                                             full_run_kind="daemon-refused", induced=["p.T::t"])
                                        for r in passing[on]["mutations"]))}
        said = controls.set_failures(controls.run.POOL_SHAPES, quiet)
        if len(said) != 1 or not said[0].startswith(f"{family}:") or "daemon-refused" not in said[0]:
            failures += fail(f"a family at zero misses was not named with what its rows did: {said}")
    absent = {name: _file(*(r for r in data["mutations"] if r["kind"] != "throw-entry"))
              for name, data in passing.items()}
    said = controls.set_failures(controls.run.POOL_SHAPES, absent)
    if len(said) != 1 or not said[0].startswith("throw-entry:") or "no row" not in said[0]:
        failures += fail(f"a family no fixture holds passed, or was not named: {said}")
    revert = {"spring-framework": _file(_row("revert", missed=["p.T::t"]))}
    if controls.set_failures(controls.run.REVERT_SET, revert):
        failures += fail("a revert control with a miss failed")
    silent = {"spring-framework": _file(_row("revert", induced=["p.T::t"]))}
    if not any(line.startswith("revert:")
               for line in controls.set_failures(controls.run.REVERT_SET, silent)):
        failures += fail("a revert control with no miss passed")
    unread = {"demo": {"mutations": [_row(f, missed=["p.T::t"]) for f in families]},
              "demo-kotlin": passing["demo-kotlin"]}
    if not any(line.startswith("demo:")
               for line in controls.set_failures(controls.run.POOL_SHAPES, unread)):
        failures += fail("a measurement's results file was read as a family control's")
    return failures


def _forcing(**overrides):
    rows = {"resource-value": _row("resource-value", missed=["p.SettingsTest::t"]),
            "services": _row("services", missed=["p.ServicesTest::t"]),
            "build-script": _row("build-script"),
            "dependency-revert": _row("dependency-revert"),
            "groovy-body": _row("groovy-body", missed=["p.G::t"])}
    rows.update(overrides)
    return {"demo": _file(*(r for r in rows.values() if r is not None))}


def case_a_forcing_family_still_forcing_fails_the_no_force_control():
    failures = 0
    if controls.forcing_failures(_forcing()):
        failures += fail(f"every forcing family narrowing failed: "
                         f"{controls.forcing_failures(_forcing())}")
    cases = {
        "a family whose row still forced": {"build-script": _row(
            "build-script", outcome="full-run", full_run_kind="environment-changed")},
        "a family no fixture holds": dict(**{"dependency-revert": None}),
        **{f"{family}, read by a test of demo, narrowed with no miss": {family: _row(family)}
           for family in controls.READ_BY_A_TEST["demo"]},
    }
    for what, overrides in cases.items():
        family = next(iter(overrides))
        said = controls.forcing_failures(_forcing(**overrides))
        if not said or not all(line.startswith(f"{family}:") for line in said):
            failures += fail(f"{what} passed, or was not named: {said}")
    if set(controls.READ_BY_A_TEST["demo"]) != {"resource-value", "services"}:
        failures += fail(f"demo's tests read the files of {controls.READ_BY_A_TEST['demo']}")
    notes = controls.set_notes(controls.run.FORCING_SET, _forcing())
    if notes != ["only asked to narrow, no test reading the edited file: build-script"]:
        failures += fail(f"the families only asked to narrow are not printed: {notes}")
    reached = _forcing(**{"build-script": _row("build-script", induced=["p.G::t"])})
    if controls.forcing_failures(reached):
        failures += fail("a narrowed row whose failing test coverage selected failed the "
                         "control, although no test reads the edited file")
    notes = controls.set_notes(controls.run.FORCING_SET, reached)
    if not any(note.startswith("build-script on demo:") and "coverage reached the edit" in note
               for note in notes):
        failures += fail(f"a forcing row coverage reached is not noted: {notes}")
    return failures


def case_each_pool_family_has_one_leg():
    """The fixture leg and the corpus leg between them ask every pool/v1 family for a miss, once
    each, each leg only its own: a family no leg names would count as clean unasked."""
    failures = 0
    fixture = controls.SET_CONTROLS[controls.run.POOL_SHAPES]["families"]
    corpus = controls.CORPUS_CONTROLS[controls.run.POOL_SHAPES]["families"]
    if sorted(fixture + corpus) != sorted(controls.POOL_FAMILIES):
        failures += fail(f"the pool legs ask {sorted(fixture + corpus)}, not every family once")
    on_fixtures = {"demo": _file(*(_row(f, missed=["p.T::t"]) for f in fixture))}
    if controls.set_failures(controls.run.POOL_SHAPES, on_fixtures):
        failures += fail("the fixture leg asked for a family only the corpus leg holds")
    said = controls.set_failures(controls.run.POOL_SHAPES, on_fixtures, corpus=True)
    asked = sorted(f for f in corpus if f not in controls.UNCONTROLLED)
    if sorted(line.split(":")[0] for line in said) != asked:
        failures += fail(f"the corpus leg did not name each family it saw no miss of, past the "
                         f"uncontrolled ones: {said}")
    args = controls.CORPUS_CONTROLS[controls.run.POOL_SHAPES]["args"]
    at = args.index("--families") if "--families" in args else None
    if at is None or tuple(args[at + 1:at + 1 + len(corpus)]) != tuple(corpus):
        failures += fail(f"the pool corpus leg draws families it does not ask: {args}")
    on_corpus = {"moshi": _file(*(_row(f, missed=["p.T::t"]) for f in corpus[:2])),
                 "okhttp": _file(*(_row(f, missed=["p.T::t"]) for f in corpus[2:]),
                                 _row("body", outcome="full-run"))}
    said = controls.set_failures(controls.run.POOL_SHAPES, on_corpus, corpus=True)
    if said:
        failures += fail(f"the corpus leg asked for a family the fixture leg asks: {said}")
    leg = controls.CORPUS_CONTROLS[controls.run.POOL_SHAPES]
    if set(leg["projects"]) != {"moshi", "okhttp"} or leg["plants"] != (controls.PLANTED_MISS,):
        failures += fail("the pool corpus leg is not moshi and okhttp under the planted miss")
    refused = {**on_fixtures, "demo-kotlin": _file(_row(
        "override-add", outcome="full-run", full_run_kind="daemon-refused"))}
    notes = controls.set_notes(controls.run.POOL_SHAPES, refused)
    if not any(note.startswith("override-add on demo-kotlin: every row forced") for note in notes):
        failures += fail(f"a family whose rows on a fixture all forced is not noted: {notes}")
    return failures


def case_a_family_with_no_miss_possible_row_is_uncontrolled_only_by_name():
    """A declared family whose rows could not miss passes as UNCONTROLLED, printed with its reason;
    any other such family fails; and a declared family that had a row able to miss must miss."""
    failures = 0
    for family in ("renamed", "schema"):
        if family not in controls.UNCONTROLLED:
            failures += fail(f"{family} is not declared uncontrolled")
    forced = {"moshi": _file(_row("aliased", missed=["p.T::t"]),
                             _row("constant", missed=["p.T::t"]),
                             _row("renamed", outcome="full-run", full_run_kind="startup-coverage",
                                  induced=["p.T::t"]),
                             _row("schema"))}
    said = controls.set_failures(controls.run.POOL_SHAPES, forced, corpus=True)
    if said:
        failures += fail(f"declared uncontrolled families with no row able to miss failed: {said}")
    notes = controls.set_notes(controls.run.POOL_SHAPES, forced, corpus=True)
    for family in ("renamed", "schema"):
        if not any(note.startswith(f"UNCONTROLLED: {family}: {controls.UNCONTROLLED[family]}")
                   for note in notes):
            failures += fail(f"the uncontrolled {family} is not printed with its reason: {notes}")
    undeclared = {"moshi": _file(_row("aliased", missed=["p.T::t"]),
                                 _row("constant", outcome="full-run", full_run_kind="x"))}
    said = controls.set_failures(controls.run.POOL_SHAPES, undeclared, corpus=True)
    if not any(line.startswith("constant:") and "not declared uncontrolled" in line
               for line in said):
        failures += fail(f"a family off the uncontrolled list passed with no row able to miss: "
                         f"{said}")
    lost = {"moshi": _file(_row("aliased", missed=["p.T::t"]), _row("constant", missed=["p.T::t"]),
                           _row("renamed", induced=["p.T::t"]))}
    said = controls.set_failures(controls.run.POOL_SHAPES, lost, corpus=True)
    if not any(line.startswith("renamed:") for line in said):
        failures += fail(f"an uncontrolled family's row that narrowed, induced and missed nothing "
                         f"passed: {said}")
    return failures


def case_each_forcing_family_has_one_leg():
    """The fixture leg and the corpus leg between them control every forcing family, once each, and
    each leg asks only for its own."""
    failures = 0
    fixture = controls.SET_CONTROLS[controls.run.FORCING_SET]["families"]
    corpus = controls.CORPUS_CONTROLS[controls.run.FORCING_SET]["families"]
    if sorted(fixture + corpus) != sorted(controls.forcing_mutations.FAMILIES):
        failures += fail(f"the forcing legs control {sorted(fixture + corpus)}, not every family "
                         f"once")
    on_fixtures = _forcing(**{"dependency-revert": None, "groovy-body": None})
    if controls.set_failures(controls.run.FORCING_SET, on_fixtures):
        failures += fail("the fixture leg asked for a family only the corpus leg holds")
    if not controls.set_failures(controls.run.FORCING_SET, on_fixtures, corpus=True):
        failures += fail("the corpus leg passed without a dependency revert or a Groovy row")
    on_corpus = {"ktlint": _file(_row("dependency-revert"),
                                 _row("resource-value", outcome="full-run")),
                 "grails-core": _file(_row("groovy-body", missed=["p.G::t"]))}
    said = controls.set_failures(controls.run.FORCING_SET, on_corpus, corpus=True)
    if said:
        failures += fail(f"the corpus leg asked for a family the fixture leg controls: {said}")
    if set(controls.CORPUS_CONTROLS[controls.run.FORCING_SET]["projects"]) != {"ktlint",
                                                                               "grails-core"}:
        failures += fail("the corpus leg does not run on ktlint and grails-core")
    if controls.CORPUS_CONTROLS[controls.run.FORCING_SET]["plants"] != \
            controls.SET_CONTROLS[controls.run.FORCING_SET]["plants"]:
        failures += fail("the corpus leg is built with other plants than the fixture leg")
    return failures


def _neutral(null=None, edit=None):
    null = null or _row(controls.run.NULL_CHANGE, outcome="full-run",
                        full_run_kind=controls.EMPTY_CHANGE_SET, selected=15)
    edit = edit or _row(controls.run.NEUTRAL_EDIT)
    return {name: _file(null, edit) for name in ("demo", "demo-kotlin", "demo-android")}


def case_the_null_change_must_run_the_whole_suite():
    """An empty selection fails the null change; a full run of kind empty-change-set passes."""
    failures = 0
    if controls.set_failures(controls.run.NEUTRAL_SET, _neutral()):
        failures += fail(f"a full empty-change-set run and a quiet neutral edit failed: "
                         f"{controls.set_failures(controls.run.NEUTRAL_SET, _neutral())}")
    null = controls.run.NULL_CHANGE
    for what, results, words in (
            ("a null change that selected no test", _neutral(null=_row(
                null, outcome="full-run", full_run_kind=controls.EMPTY_CHANGE_SET, selected=0)),
             "the null change"),
            ("a null change that narrowed to nothing", _neutral(null=_row(null, selected=0)),
             "the null change"),
            ("a null change forced for another kind", _neutral(null=_row(
                null, outcome="full-run", full_run_kind="unmappable-paths", selected=15)),
             "the null change"),
            ("a neutral edit that reported a miss", _neutral(edit=_row(
                controls.run.NEUTRAL_EDIT, missed=["p.T::t"], induced=["p.T::t"])),
             "reported a miss"),
            ("a neutral edit that induced a failure", _neutral(edit=_row(
                controls.run.NEUTRAL_EDIT, induced=["p.T::t"])),
             "induced a failure"),
            ("a neutral edit that measured nothing", _neutral(edit=_row(
                controls.run.NEUTRAL_EDIT, outcome=None, status="build-failed")),
             "measured nothing")):
        said = controls.set_failures(controls.run.NEUTRAL_SET, results)
        # The words name the check that caught it: a miss comes with an induced failure, so
        # a count alone would let the induced check stand in for the miss check.
        if (len(said) != 3 or not all(line.split(":")[0] in results for line in said)
                or not all(words in line for line in said)):
            failures += fail(f"{what} passed on some fixture: {said}")
    missing = {**_neutral(), "demo-android": _file(_row(controls.run.NEUTRAL_EDIT))}
    if not any(line.startswith("demo-android:")
               for line in controls.set_failures(controls.run.NEUTRAL_SET, missing)):
        failures += fail("a fixture without a null-change row passed")
    unedited = {**_neutral(), "demo-android": _file(_neutral()["demo"]["mutations"][0])}
    if not any(line.startswith("demo-android: no neutral-edit row")
               for line in controls.set_failures(controls.run.NEUTRAL_SET, unedited)):
        failures += fail("a fixture without a neutral-edit row passed")
    return failures


def case_the_no_force_plant_covers_each_forcing_family():
    """Each family's paths read as unread under its plant, and a class source does not: one that
    did would empty a pool row's change set too."""
    failures = 0
    demo = os.path.join(controls.yoriwake_checkout.ROOT, "samples", "demo")
    with open(os.path.join(controls.HERE, "projects", "demo.toml"), "rb") as handle:
        project = tomllib.load(handle)
    edited = {kind: path for path, kind, _ in
              controls.forcing_mutations.mutation_targets(demo, project)}
    samples = {family: [path] for family, path in edited.items()}
    # Every kind of path a dependency revert may edit.
    samples["dependency-revert"] = ["gradle/libs.versions.toml", "build.gradle.kts",
                                    "settings.gradle", "build-logic/src/main/kotlin/a.gradle.kts",
                                    "gradle.properties"]
    samples["groovy-body"] = ["src/main/groovy/dev/G.groovy"]
    for family in controls.forcing_mutations.FAMILIES:
        if family not in samples:
            failures += fail(f"no sample path of {family} to check its plant against")
            continue
        for path in samples[family]:
            if family == "dependency-revert" and \
                    controls.forcing_mutations._dependency_path(path) is None:
                failures += fail(f"{path} is no path a dependency revert may edit")
            if not re.search(controls.NO_FORCE_PATHS[family], path):
                failures += fail(f"{family}'s plant leaves {path} read")
            if not re.search(controls.planted_no_force()[2].split('"""')[1], path):
                failures += fail(f"the planted Kotlin leaves {family}'s {path} read")
    for path in ("src/main/java/dev/demo/Alpha.java", "src/main/kotlin/dev/A.kt"):
        if re.search(controls.planted_no_force()[2].split('"""')[1], path):
            failures += fail(f"the no-force plant reads the class source {path} as unread")
    return failures


def case_each_set_builds_the_plugin_its_control_needs():
    """A forcing row forces through the path rule, the classpath-files digest and the environment
    fingerprint, so its plugin plants all three; a family control plants the miss; the null change
    and the neutral edit plant nothing."""
    failures = 0
    plants = {shapes: control["plants"] for shapes, control in controls.SET_CONTROLS.items()}
    forcing = set(plants[controls.run.FORCING_SET])
    if forcing != {controls.planted_no_force(), *controls.NO_FORCE_RULES}:
        failures += fail(f"the no-force plugin plants {len(forcing)} of the rules that force a "
                         f"forcing row")
    for shapes in (controls.run.DEFAULT_SHAPES, controls.run.POOL_SHAPES, controls.run.REVERT_SET):
        if plants[shapes] != (controls.PLANTED_MISS,):
            failures += fail(f"the {shapes} control is not built with the planted miss")
    revert = controls.SET_CONTROLS[controls.run.REVERT_SET]
    if revert["projects"] != ("spring-framework",) or "--limit" not in revert["args"]:
        failures += fail("the revert control is not spring-framework's spring-beans at its own cap")
    if plants[controls.run.NEUTRAL_SET]:
        failures += fail("the null change and the neutral edit run against a planted plugin")
    return failures


def case_a_maven_control_publishes_and_puts_back_the_maven_plugin():
    """The planted clone publishes the Maven plugin for a Maven project (`run.py` decides it per
    project), and the real checkout publishes its own back afterwards, so no planted Maven plugin
    stays in mavenLocal."""
    failures = 0
    if ":maven:publishToMavenLocal" not in controls.run.publish_tasks(maven=True):
        failures += fail("a Maven project's plugin under test does not publish the Maven plugin")
    if controls.run.publish_tasks(maven=False) != [":plugin:publishToMavenLocal"]:
        failures += fail("a Gradle project's publish changed")
    if ":maven:publishToMavenLocal" not in controls.republish_tasks(["demo", "demo-maven"]):
        failures += fail("a leg with demo-maven leaves the planted Maven plugin in mavenLocal")
    if ":maven:publishToMavenLocal" in controls.republish_tasks(["demo", "demo-kotlin"]):
        failures += fail("a Gradle-only leg republishes the Maven plugin")
    if "demo-maven" not in controls.CONTROL_PROJECTS:
        failures += fail("the default control does not run the Maven fixture")
    return failures


CASES = (case_every_control_project_must_report_a_miss,
         case_a_maven_control_publishes_and_puts_back_the_maven_plugin,
         case_in_complement_mode_the_shared_jvm_miss_needs_its_rule,
         case_planting_writes_only_the_clone,
         case_the_planted_miss_matches_exactly_once,
         case_every_family_of_a_named_set_must_report_a_miss,
         case_a_forcing_family_still_forcing_fails_the_no_force_control,
         case_the_null_change_must_run_the_whole_suite,
         case_the_no_force_plant_covers_each_forcing_family,
         case_each_set_builds_the_plugin_its_control_needs,
         case_each_pool_family_has_one_leg,
         case_each_forcing_family_has_one_leg,
         case_a_family_with_no_miss_possible_row_is_uncontrolled_only_by_name)


def test_controls():
    assert sum(case() for case in CASES) == 0


def main():
    failures = 0
    for case in CASES:
        print(case.__name__)
        failures += case()
    print(f"\n{'all passed' if not failures else 'FAILED'}: {failures} failed checks")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
