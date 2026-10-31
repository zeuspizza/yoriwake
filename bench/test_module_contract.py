"""
The harness's side of the contract with the plugin and the agent, which no compiler checks.

The agent writes `#!key\tvalue` notes into its decision record and the plugin writes `key\tcount`
counters; `run.py`, `replay_decisions.py`, `sweep_corpus.py` and `config_cost.py` read them back by
literal, in another language, joined by nothing. The agent's names live in `AgentContract.java`.
A renamed key does not fail to compile and does not throw -- `notes.get()` answers None, the row
publishes a blank where an attribution or a measurement was, and the classification gate names the
wrong cause.

So every literal below is read OUT OF the Kotlin and Java sources, not restated here: rename one
there and this fails, whichever side moved. It used to be pinned from the plugin's own test suite,
reading this directory; it lives here now because the plugin builds without the harness.

Run with `python bench/test_module_contract.py`, or under pytest.

Cases are named case_* and RETURN a count of failed checks, following test_run.py: a function that
returns instead of asserting would be collected by pytest and pass however many checks failed. The
single test_* entry point below asserts.
"""

import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import assert_recall  # noqa: E402
import checkout  # noqa: E402
import config_cost  # noqa: E402
import replay_decisions  # noqa: E402
import run  # noqa: E402

# The checkout whose sources the harness measures.
SOURCE_ROOT = checkout.ROOT
AGENT = os.path.join(SOURCE_ROOT, "agent/src/main/java/io/github/zeuspizza/yoriwake/agent")
PLUGIN = os.path.join(SOURCE_ROOT, "plugin/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle")
CORE = os.path.join(SOURCE_ROOT, "core/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle")


def fail(message):
    print(f"  FAIL: {message}")
    return 1


def _read(path):
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def _java_constant(source, name):
    """The value of `static final String NAME = "..."`, or None when there is no such line."""
    found = re.search(r'static final String ' + name + r'\s*=\s*"((?:[^"\\]|\\.)*)"\s*;', source)
    return found.group(1) if found else None


def _kotlin_constant(source, name):
    """The value of `const val NAME = "..."`, or None when there is no such line."""
    found = re.search(r'const val ' + name + r'\s*=\s*"((?:[^"\\]|\\.)*)"', source)
    return found.group(1) if found else None


def case_the_decision_record_names_are_the_ones_the_harness_reads():
    failures = 0
    contract = _read(os.path.join(AGENT, "contract", "AgentContract.java"))
    for constant, reader, where in (
        ("NOTE_PREFIX", run.DECISION_NOTE_PREFIX, "run.py"),
        ("VERSION_NOTE", run.DECISION_VERSION_NOTE, "run.py"),
        ("ROWS_NOTE", run.DECISION_ROWS_NOTE, "run.py"),
        ("WRITER_NOTE", run.DECISION_WRITER_NOTE, "run.py"),
        ("DECISIONS_FILE", run.DECISIONS_FILE, "run.py"),
        ("DECISIONS_PART_SUFFIX", run.DECISION_PART_SUFFIX, "run.py"),
        ("INPUT_NOTE_PREFIX", replay_decisions.INPUT_NOTE_PREFIX, "replay_decisions.py"),
    ):
        written = _java_constant(contract, constant)
        if written is None:
            failures += fail(f"AgentContract.{constant} is no longer a string literal this can read")
        elif written != reader:
            failures += fail(f"the agent writes {constant} as {written!r} and {where} reads {reader!r}")
    return failures


def case_the_tokens_the_gate_classifies_on_are_the_ones_the_agent_writes():
    # Written by the agent, carried into a row by run.py, and bucketed by the sweep summary. The
    # values are read out of AgentContract; the writes are checked by the constant's name, since
    # a note the agent stopped writing is as silent as one it renamed.
    failures = 0
    contract = _read(os.path.join(AGENT, "contract", "AgentContract.java"))
    selection = _read(os.path.join(AGENT, "select", "SelectionRun.java"))
    reader = _read(os.path.join(HERE, "run.py"))
    sweep = _read(os.path.join(HERE, "sweep_corpus.py"))
    values = {name: _java_constant(contract, name) for name in (
        "OUTCOME_NOTE", "FULL_RUN_KIND_NOTE", "FULL_RUN_REASON_NOTE", "REFUSAL_KIND_NOTE",
        "RUN_FULL", "RUN_NARROWED")}
    for name, value in values.items():
        if value is None:
            failures += fail(f"AgentContract.{name} is no longer a string literal this can read")
    if failures:
        return failures
    for note in ("OUTCOME_NOTE", "FULL_RUN_KIND_NOTE", "FULL_RUN_REASON_NOTE", "REFUSAL_KIND_NOTE"):
        if f"decisions.note({note}," not in selection:
            failures += fail(f"the agent no longer writes a `{values[note]}` note")
        if f'notes.get("{values[note]}")' not in reader:
            failures += fail(f"run.py stopped reading the `{values[note]}` note")
    if '"refusal_kind"' not in sweep:
        failures += fail("sweep_corpus.py stopped reading the refusal kind")
    # The harness counts these as asked for, not refused; the plugin must mark the same ones.
    kinds = _read(os.path.join(CORE, "change", "RefusalKind.kt"))
    marked = set(re.findall(r'\("([a-z-]+)", requested = true\)', kinds))
    if marked != assert_recall.REQUESTED_DECLINES:
        failures += fail(f"the plugin marks {sorted(marked)} as requested, the harness reads "
                         f"{sorted(assert_recall.REQUESTED_DECLINES)}")
    if "decisions.note(OUTCOME_NOTE, RUN_FULL)" not in selection:
        failures += fail("the agent no longer writes `full-run` as an outcome")
    if f'"{values["RUN_FULL"]}"' not in sweep:
        failures += fail(f"sweep_corpus.py stopped reading the {values['RUN_FULL']} outcome")
    if f'== "{values["RUN_NARROWED"]}"' not in reader:
        failures += fail(f"run.py stopped reading the {values['RUN_NARROWED']} outcome")
    return failures


def case_the_rule_names_run_py_reads_are_the_ones_the_agent_writes():
    # The agent's rule lines and notes. The coverage-only selection run.py derives rests on two
    # literal facts about the tokens -- which ones share a JVM, which one is class rounding -- so
    # each is read out of AgentContract rather than restated.
    failures = 0
    contract = _read(os.path.join(AGENT, "contract", "AgentContract.java"))
    record = _read(os.path.join(AGENT, "platform", "DecisionRecord.java"))
    for constant, reader in (
        ("RULES_LINE_PREFIX", run.DECISION_RULES_PREFIX),
        ("RULES_NOTE", run.DECISION_RULES_NOTE),
        ("FORCING_KINDS_NOTE", run.DECISION_FORCING_KINDS_NOTE),
        ("RULE_CLASS_GRANULARITY", run.CLASS_GRANULARITY_RULE),
        ("RULE_CLASS_RUNS_WHOLE", run.CLASS_RUNS_WHOLE_RULE),
        ("RULE_NONE", run.RULE_NONE),
        ("RULE_UNKNOWN", run.RULE_UNKNOWN),
    ):
        written = _java_constant(contract, constant)
        if written is None:
            failures += fail(f"AgentContract.{constant} is no longer a string literal this can read")
        elif written != reader:
            failures += fail(f"the agent writes {constant} as {written!r} and run.py reads {reader!r}")
    computed = tuple(_java_constant(contract, c) for c in ("RULES_COMPLETE", "RULES_FROM_REFUSED_INPUTS"))
    if computed != run.RULES_COMPUTED:
        failures += fail(f"the agent writes rule lines under {computed} and run.py reads them under "
                         f"{run.RULES_COMPUTED}")
    shared = {name: value for name, value in re.findall(
        r'static final String (RULE_[A-Z_]+)\s*=\s*"([^"]*)"', contract)}
    for name, value in shared.items():
        if name.startswith("RULE_SHARES_JVM_") != value.startswith(run.SHARES_JVM_RULE_PREFIX):
            failures += fail(f"AgentContract.{name} = {value!r} is on the wrong side of run.py's "
                             f"shared-JVM prefix {run.SHARES_JVM_RULE_PREFIX!r}")
    for name in ("RULES_LINE_PREFIX", "RULES_NOTE", "FORCING_KINDS_NOTE"):
        if name not in record:
            failures += fail(f"DecisionRecord no longer writes {name}")
    return failures


def case_what_the_agent_escapes_run_py_unescapes():
    # Harmless while every value written was a token matching [a-z-]. A refusal reason is prose, and
    # prose from a Windows build carries backslashes: the writer's escaping became visible to a
    # reader for the first time. The escaping applied here is the writer's own, read out of its
    # source, so a new escape added there is one this reader must learn. The writer escapes through
    # the agent's one TSV codec, so the rule is read out of the codec, and the record is checked to
    # still write its rows and notes through it.
    failures = 0
    record = _read(os.path.join(AGENT, "platform", "DecisionRecord.java"))
    if record.count("Tsv.join(") < 2:
        return fail("DecisionRecord no longer writes its rows and notes through Tsv.join")
    codec = _read(os.path.join(AGENT, "contract", "Tsv.java"))
    body = re.search(r"public static String escape\(String value\) \{(.*?)\n    \}", codec, re.S)
    if body is None:
        return fail("Tsv.escape is no longer where this can read it")
    pairs = re.findall(r'\.replace\("((?:[^"\\]|\\.)*)",\s*"((?:[^"\\]|\\.)*)"\)', body.group(1))
    if len(pairs) < 4:
        return fail(f"read {len(pairs)} replacements out of Tsv.escape, expected at least 4")

    def java_string(literal):
        return literal.encode("ascii").decode("unicode_escape")

    prose = "C:\\builds\\app declared a constant\tand the map disagreed\r\nover two lines"
    escaped = prose
    for old, new in pairs:
        escaped = escaped.replace(java_string(old), java_string(new))
    if "\t" in escaped or "\n" in escaped:
        failures += fail(f"the writer's escaping, as read, left a separator in {escaped!r}")
    if run.unescape_decision(escaped) != prose:
        failures += fail(f"run.py did not invert the agent's escaping: {run.unescape_decision(escaped)!r}")
    return failures


def case_the_counter_names_config_cost_reads_are_the_ones_the_plugin_writes():
    # ADDED AFTER IT DRIFTED, which is the whole argument for this case: `configure` was introduced
    # for the payback axis and this list did not learn about it, so config_cost.py went on summing
    # every timer it found -- including the one that CONTAINS the other two. It published 1,506ms of
    # plugin work inside an 832ms notification and a negative residual.
    failures = 0
    plugin = _read(os.path.join(PLUGIN, "YoriwakePlugin.kt"))
    memo = _read(os.path.join(PLUGIN, "facts", "BuildMemo.kt"))
    reader = _read(os.path.join(HERE, "config_cost.py"))
    for constant in ("TEST_TASKS_COUNTER", "DERIVE_SCOPE_COUNTER", "WALK_COUNTER",
                     "SOURCE_FILES_OPENED_COUNTER", "CONFIGURE_COUNTER"):
        written = _kotlin_constant(plugin, constant)
        if written is None:
            failures += fail(f"YoriwakePlugin.{constant} is no longer a string literal this can read")
        elif f'"{written}"' not in reader:
            failures += fail(f"the plugin writes a counter named `{written}` that config_cost.py never reads")
    import sweep_data  # noqa: PLC0415
    listing = _kotlin_constant(plugin, "WORKTREE_LISTING_COUNTER")
    if listing != sweep_data.WORKTREE_LISTING:
        failures += fail(f"the plugin times its working-tree listing as {listing!r} and "
                         f"sweep_data.py reads {sweep_data.WORKTREE_LISTING!r}")
    millis = _kotlin_constant(memo, "MILLIS")
    if millis is None:
        failures += fail("BuildMemo.MILLIS is no longer a string literal this can read")
    elif millis != config_cost.MILLIS:
        failures += fail(f"the plugin suffixes timers {millis!r} and config_cost.py reads {config_cost.MILLIS!r}")
    return failures


def case_the_u31_names_the_harness_reads_are_the_ones_the_product_writes():
    """The capture-mode label, the map schema file, the isolated flag, the shared-JVM verdict, the
    counters and decode task names, and the agent's lost-write line."""
    import sweep_data  # noqa: PLC0415
    failures = 0
    contract = _read(os.path.join(AGENT, "contract", "AgentContract.java"))
    for name, harness in (("JVM_MODE_FILE", sweep_data.JVM_MODE_FILE),
                          ("MODE_ISOLATED", sweep_data.MODE_ISOLATED),
                          ("MODE_SHARED", sweep_data.MODE_SHARED),
                          ("MAP_SCHEMA_VERSION_FILE", sweep_data.MAP_SCHEMA_VERSION_FILE),
                          ("RAW_DIR", sweep_data.RAW_DIR),
                          ("TOUCHES_FILE", sweep_data.TOUCHES_FILE),
                          ("FIRST_TOUCH_FILE", sweep_data.FIRST_TOUCH_FILE),
                          ("POSITIONS_FILE", sweep_data.POSITIONS_FILE),
                          ("FIRST_TOUCH_ANY", sweep_data.FIRST_TOUCH_ANY),
                          ("CHANGED_CLASSES_PROPERTY",
                           sweep_data.CHANGED_CLASSES_INPUT.removeprefix("input."))):
        if _java_constant(contract, name) != harness:
            failures += fail(f"AgentContract.{name} is {_java_constant(contract, name)!r}; the "
                             f"harness reads {harness!r}")
    if not re.search(r"\bMAP_SCHEMA_VERSION\s*=\s*\d+\s*;", contract):
        failures += fail("MAP_SCHEMA_VERSION is no longer an int literal checkout_schema_version reads")
    settings = _read(os.path.join(PLUGIN, "Settings.kt"))
    names = _read(os.path.join(CORE, "SettingNames.kt"))
    if "-P" + (_kotlin_constant(names, "ISOLATED_CAPTURE") or "") != run.ISOLATED_CAPTURE_FLAG:
        failures += fail(f"SettingNames.ISOLATED_CAPTURE is {_kotlin_constant(names, 'ISOLATED_CAPTURE')!r}")
    if _kotlin_constant(settings, "COUNTERS") != "yoriwake.internal.counters":
        failures += fail("Settings.COUNTERS moved; run.gradle passes -Pyoriwake.internal.counters")
    verdict = _read(os.path.join(AGENT, "select", "Verdict.java"))
    if not re.search(r"^\s*" + run.SHARES_JVM_REASON + r",", verdict, re.M) \
            or "return name();" not in verdict:
        failures += fail(f"Verdict no longer writes {run.SHARES_JVM_REASON} as a reason token")
    decode = _read(os.path.join(PLUGIN, "tasks", "DecodeTask.kt"))
    if f'"{sweep_data.DECODE_TASK_PREFIX}' not in decode:
        failures += fail(f"the decode task is no longer named {sweep_data.DECODE_TASK_PREFIX}<Task>")
    audit = _read(os.path.join(CORE, "report", "Audit.kt"))
    if f'const val FILE = "{sweep_data.TASK_FACTS_FILE}"' not in audit:
        failures += fail(f"Audit.TaskFacts no longer writes {sweep_data.TASK_FACTS_FILE}")
    record = _read(os.path.join(AGENT, "platform", "DecisionRecord.java"))
    if '"[yoriwake] could not write " + name' not in record \
            or not run.DECISION_WRITE_FAILURE.search("could not write decisions.tsv (writer 1"):
        failures += fail("the agent's lost-write line is no longer the one run.py counts")
    return failures


def case_every_blocker_the_harness_excuses_is_one_the_audit_writes():
    # A key the plugin no longer publishes would excuse nothing, silently.
    import corpus_state
    golden = _read(os.path.join(SOURCE_ROOT, "plugin/src/test/resources/golden/tokens.txt")).splitlines()
    published = {line.split("\t", 1)[1] for line in golden if line.startswith("audit-blocker\t")}
    failures = 0
    for token in corpus_state.CANNOT_ANSWER_BLOCKERS:
        if token not in published:
            failures += fail(f"corpus_state excuses `{token}`, which the audit no longer writes")
    return failures

def case_the_outcome_listener_writes_what_the_reader_reads():
    """The listener's property and marker, read out of the init script rather than restated: a
    renamed one leaves every build without per-method keys, read as an XML fallback that names a
    missing listener, on every row."""
    import sweep_data  # noqa: PLC0415
    failures = 0
    script = _read(sweep_data.TEST_OUTCOMES_INIT)
    if f"System.getProperty('{sweep_data.TEST_OUTCOMES_PROPERTY}')" not in script:
        failures += fail(f"the listener no longer reads -D{sweep_data.TEST_OUTCOMES_PROPERTY}")
    if f'println "{sweep_data.TEST_OUTCOMES_MARKER}${{path}}"' not in script:
        failures += fail(f"the listener no longer prints {sweep_data.TEST_OUTCOMES_MARKER!r}")
    return failures


MAVEN = os.path.join(SOURCE_ROOT, "maven/src/main/kotlin/io/github/zeuspizza/yoriwake/maven")


def case_the_maven_names_the_harness_reads_are_the_ones_the_maven_plugin_writes():
    """Where a Maven map lives, how its execution is keyed and named, and the lines the harness
    reads a decline and an attachment from, read out of the Maven plugin: a renamed one finds no
    map, scores a declined execution as a selection, or reads no reason."""
    import maven_build  # noqa: PLC0415
    failures = 0
    recording = _read(os.path.join(MAVEN, "Recording.kt"))
    lifecycle = _read(os.path.join(MAVEN, "YoriwakeLifecycle.kt"))
    settings = _read(os.path.join(MAVEN, "Settings.kt"))
    contract = _read(os.path.join(AGENT, "contract", "AgentContract.java"))
    if _kotlin_constant(recording, "MAPS_HOME") != maven_build.MAPS_HOME:
        failures += fail(f"the Maven plugin's maps no longer live under {maven_build.MAPS_HOME}")
    if _java_constant(contract, "MAP_ROOT_DIR") != maven_build.MAP_ROOT_DIR:
        failures += fail(f"the map root is no longer {maven_build.MAP_ROOT_DIR}")
    if _kotlin_constant(settings, "ENABLED") != maven_build.ENABLED_PROPERTY:
        failures += fail(f"the off switch is no longer -D{maven_build.ENABLED_PROPERTY}")
    if _kotlin_constant(settings, "PLUGIN_KEY") != \
            f"{maven_build.PLUGIN_GROUP}:{maven_build.PLUGIN_ARTIFACT}":
        failures += fail("the plugin's coordinates moved")
    key = '"${project.groupId}:${project.artifactId}:${execution.artifactId}:${execution.executionId}"'
    if key not in recording:
        failures += fail("the map key is no longer groupId:artifactId:plugin:execution")
    name = ('"${project.artifactId} ${execution.artifactId.removePrefix("maven-")'
            '.removeSuffix("-plugin")}:${execution.goal} (${execution.executionId})"')
    if name not in recording:
        failures += fail("console lines no longer name an execution `<artifactId> <plugin>:<goal> (<id>)`")
    if 'log.warn("[yoriwake] ${run.name} declined, ${outcome.kind.token}: ' not in lifecycle:
        failures += fail("a declined execution's line is no longer `<name> declined, <token>: `")
    if 'log.info("[yoriwake] attached to Maven $version")' not in lifecycle:
        failures += fail("the attachment line is no longer `attached to Maven <version>`")
    outcome = _read(os.path.join(CORE, "wiring", "ScopeOutcome.kt"))
    if f'MAVEN_VERSION("{maven_build.MAVEN_VERSION_TOKEN}")' not in outcome:
        failures += fail(f"the below-floor token is no longer {maven_build.MAVEN_VERSION_TOKEN}")
    if 'log.warn("[yoriwake] $token: Maven $version is older than' not in lifecycle:
        failures += fail("the below-floor line no longer opens with its token")
    return failures


def test_module_contract():
    failures = 0
    for name, case in sorted(globals().items()):
        if name.startswith("case_"):
            print(name)
            failures += case()
    assert failures == 0, f"{failures} failed checks"


if __name__ == "__main__":
    test_module_contract()
    print("\nall checks passed")
