# The plugin–agent contract

yoriwake is two programs. The plugin runs in the Gradle daemon; the agent runs inside every test
JVM, shipped inside the plugin jar. They talk only through JVM system properties and files in the
map directory. This page lists every name they share. It is for anyone reading a map with their own
tooling or changing either side.

The names are defined once, in
[`AgentContract`](../agent/src/main/java/io/github/zeuspizza/yoriwake/agent/contract/AgentContract.java).
The plugin compiles against it, so the two sides cannot drift. A test checks every table below
against that class, so this page cannot drift from it either. Names not listed here are internal and
can change in any release.

## System properties

All set by the plugin on the test task and read by the agent in the test JVM. List values are
comma-separated. The seven list-valued `yoriwake.change.*` properties below (all but
`yoriwake.change.accountedFor`) are not passed as system properties: a change set can name more
paths than a command line carries, so the plugin writes them to `CHANGE_SET_FILE` and sets
`yoriwake.change.file`. The agent reads them from there, and a file it cannot read whole refuses
the run as `change-set-unreadable`.

| Constant | Property | Direction | Meaning |
|---|---|---|---|
| `MAP_DIR_PROPERTY` | `yoriwake.map.dir` | plugin → agent | The map directory to read |
| `RECORDS_DIR_PROPERTY` | `yoriwake.internal.capture.outputDir` | plugin → agent | Where to write raw records. Capture is off when unset or empty |
| `LOADED_DIR_PROPERTY` | `yoriwake.loaded.out` | plugin → agent | Where to write loaded-class lists. Set only with `-Pyoriwake.internal.loaded` |
| `JUNIT4_HOOK_PROPERTY` | `yoriwake.junit4.hook` | plugin → agent | `true` on a task not on the JUnit Platform: capture through JUnit 4's `RunNotifier` |
| `SELECT_PROPERTY` | `yoriwake.select` | plugin → agent | `true` to narrow; anything else runs everything |
| `OBSERVE_PROPERTY` | `yoriwake.observe` | plugin → agent | `true` on an observing run: every test runs, and each row's selecting verdict is recorded as an observation line. Set beside `yoriwake.select`, which still decides that verdict |
| `CLASS_GRANULARITY_PROPERTY` | `yoriwake.select.classGranularity` | plugin → agent | `true` rounds a selection up to whole classes |
| `CHANGED_CLASSES_PROPERTY` | `yoriwake.change.classes` | plugin → agent | Changed classes |
| `UNMAPPABLE_PATHS_PROPERTY` | `yoriwake.change.unmappable` | plugin → agent | Changed paths no class was derived from; any one forces |
| `ACCOUNTED_PROPERTY` | `yoriwake.change.accountedFor` | plugin → agent | `true` when every changed path was accounted for, so an empty change set means "nothing changed" |
| `ABSENCE_PROVABLE_PROPERTY` | `yoriwake.change.absenceProvable` | plugin → agent | Changed classes whose absence from the map proves them untested |
| `UNREADABLE_PATHS_PROPERTY` | `yoriwake.change.unreadable` | plugin → agent | Changed paths the build never reads |
| `OWN_TEST_CLASSES_PROPERTY` | `yoriwake.change.ownTestClasses` | plugin → agent | Changed classes that are this task's own tests |
| `EXEMPT_TEST_CLASSES_PROPERTY` | `yoriwake.change.exemptTestClasses` | plugin → agent | Of those, the ones that declare a test and that no other compiled class or resource names. Their loading at discovery opens no window; see [First touches](#first-touches) |
| `CHANGED_BYTES_PROPERTY` | `yoriwake.change.bytes` | plugin → agent | Classes whose compiled bytes changed with no changed source behind them. Tests that recorded one run; one no test recorded forces |
| `CHANGE_SET_FILE_PROPERTY` | `yoriwake.change.file` | plugin → agent | The file holding the list-valued change-set properties. Set means that file alone answers for them |
| `REFUSED_PROPERTY` | `yoriwake.refused` | plugin → agent | Why the plugin refused to select, as prose. Present means run everything, whatever `yoriwake.select` says |
| `REFUSED_KIND_PROPERTY` | `yoriwake.refused.kind` | plugin → agent | Which refusal, as a token |
| `DECLINES_PROPERTY` | `yoriwake.declines` | plugin → agent | Every decline that held, as refusal tokens, comma-separated: `full-run-requested`, `full-run-branch`, `full-run-commit`, `tests-named`, `decline-undetermined` |
| `RUN_TOKEN_PROPERTY` | `yoriwake.run.token` | plugin → agent | A fresh value per selecting run of the task. Each decision record notes it, so the plugin merges only this run's |
| `ALWAYS_RUN_PROPERTY` | `yoriwake.alwaysRun` | plugin → agent | Test-id globs that are never skipped |
| `CHANGE_SET_UNREADABLE_KIND` | `change-set-unreadable` | plugin, agent | The refusal token of a run whose change-set file could not be written or read |
| `COMPLEMENT_RECORD_PROPERTY` | `yoriwake.complement.record` | plugin → agent | On a complement run whose selection record's stamp matched: the path of its copy, `complement.record`. The agent leaves out each test it lists as ran, unless the record's `jvm.` notes differ from this JVM |
| `COMPLEMENT_RECORD_MISMATCH_KIND` | `complement-record-mismatch` | plugin, agent | The refusal token of a complement run whose selection record does not match it. The agent writes it as `refusal-kind` when the record's test JVM is not this one |

The agent also reads Gradle's `org.gradle.test.worker` to name its worker directory, and uses a few
`yoriwake.internal.capture.*` and `yoriwake.junit4.*` properties inside one JVM. Those are not part
of the contract.

## The map directory

One per test task, at `<project cache dir>/yoriwake/<task path>-<hash>/`: usually
`.gradle/yoriwake/…`, which survives `clean`. Plain text, UTF-8, one entry per line unless stated.

| Constant | Name | Written by | Read by | Format |
|---|---|---|---|---|
| `MAP_ROOT_DIR` | `yoriwake` | plugin | plugin | The directory under the project cache dir holding every task's map |
| `COVERAGE_FILE` | `coverage.tsv` | plugin | agent | `outcome \t durationNanos \t class,class \t record-id` |
| `MAP_SCHEMA_VERSION_FILE` | `schema-version` | plugin | agent, plugin | The map version, one integer. Written last |
| `MAP_DIGEST_FILE` | `map-digest` | plugin | callers | `sha256 <hex>`: the SHA-256 of the map's files, rewritten after every decode that writes them. A content hash for a caller to list; the plugin compares the recomputed digest with `-Pyoriwake.trustedMaps`, never with this file |
| `SCOPE_FILE` | `scope` | plugin | agent | One package prefix per line |
| `EFFECTIVE_SCOPE_FILE` | `effective-scope` | plugin | agent | The scope JaCoCo instrumented under, compared as opaque text |
| `LOADED_FILE` | `loaded.txt` | plugin | agent | One loaded class name per line |
| `LOADED_PROVENANCE_FILE` | `loaded-provenance` | plugin | agent | `full` or `selecting` |
| `LOADED_SCOPE_FILE` | `loaded-scope` | plugin | agent | The filter `loaded.txt` was recorded under |
| `POSITIONS_FILE` | `positions.tsv` | plugin | agent | `jvm \t sequence \t test-id`: where each test record ran. A test can appear more than once |
| `FIRST_TOUCH_FILE` | `first-touch.tsv` | plugin | agent | `jvm \t sequence \t class`: the first record at or before which that JVM loaded the class, executed it, or read its class or source file |
| `NAMED_TOUCH_FILE` | `named-touch.tsv` | plugin | agent | The same, counting only a lookup by name or a read after the plan started, and an execution in another class's window. What an exempt test class is dated by |
| `JVM_MODE_FILE` | `jvm-mode.tsv` | plugin | plugin, people, tools | `jvm \t mode`: how that JVM was recorded, `isolated` or `shared` (see [Recording modes](#recording-modes)) |
| `DECISIONS_FILE` | `decisions.tsv` | agent | people, tools | `test \t verdict \t reason` rows after `#!key \t value` notes, then `#+` rule lines (see [Decision rules](#decision-rules)). Last writer wins |
| `DECISIONS_PART_SUFFIX` | `.part` | agent | people, tools | Ends `decisions.tsv.<pid>-<writer>.part`, one per writer, same format |
| `OBSERVATION_FILE` | `observation.json` | plugin | people, tools | After an observing run: the failing tests selection would have left out, and the recorded time of the tests it would have skipped. See [Observing before you select](reference.md#observing-before-you-select) |
| `SELECTION_FILE` | `selection.tsv` | plugin | plugin, agent | After a selecting run that narrowed, at a clean tree: the tests that ran to an outcome, stamped. Removed after any other selecting run. See [The selection record](#the-selection-record) |
| `COMPLEMENT_RECORD_FILE` | `complement.record` | plugin | agent | A complement run's copy of the selection record it validated. Removed by its decode |
| `CHANGE_SET_FILE` | `change-set` | plugin | agent | The list-valued change-set properties of the last selecting run, in `java.util.Properties` format, ending with `CHANGE_SET_END` |
| `CHANGE_SET_END` | `#end` | plugin | agent | The last line of a whole `change-set`. Without it the file is refused |
| `RAW_DIR` | `raw` | agent | plugin, agent | Raw records, one directory per worker. Cleared before every run |

The plugin keeps other files here for its own use. They are not part of the contract.

Every field of `coverage.tsv`, `index.tsv` and `decisions.tsv` is escaped the same way: `\\`,
`\t`, `\r` and `\n` stand for a backslash, tab, carriage return and newline. A field without them
is written as it is.

### A worker's record directory

`raw/<worker dir>/`, one per test JVM.

| Constant | Name | Format |
|---|---|---|
| `WORKER_DIR_PREFIX` | `worker-` | Ends in the Gradle worker id |
| `INDEX_FILE` | `index.tsv` | `sequence \t durationNanos \t byteCount \t outcome \t record-id` |
| `EXEC_FILE_FORMAT` | `%06d.exec` | One JaCoCo execution-data blob per record, named by its sequence |
| `TOUCHES_FILE` | `touches.tsv` | `sequence \t kind \t value`: what the JVM loaded or read for the first time before the record with that sequence was taken. Ends with `#complete` when observation ended normally |
| `OVERHEAD_FILE` | `overhead.txt` | One line: what capture cost this JVM |
| `RAW_SCHEMA_VERSION_FILE` | `raw-schema-version` | The raw version, one integer. Written before the first record |
| `PLAN_COMPLETE_FILE` | `plan-complete` | Empty. Written only when the worker's whole run ended normally |

### First touches

A test JVM runs some code only once: a static initialiser, or a context built and cached. It
reads some metadata only once too. Coverage credits that work to the first test that triggers it,
so a later test can depend on a class and record nothing. A test that only reflects on a class,
or reads its class file, records nothing either. Nothing about a class can reach a test before
the class arrives in that test's JVM. So a change selects every test that ran at or after the
changed class's first touch in its JVM.

A test class that declares a test, and that nothing else names, is the exception. Its engine
instantiates it, so its loading and inspection at discovery open no window. It is dated by
`named-touch.tsv` instead. That holds only in a JVM whose every test ran on an engine known to
build tests from its own classes: Jupiter, Vintage, the suite engine, JUnit 4, TestNG, Spock,
Kotest, ArchUnit or jqwik. In any other JVM its named touches start at `*`.

`jvm` is an opaque id the plugin gives one worker of one capture. `sequence` is the record's
number in `index.tsv`. A touch between two records counts toward the later one.

| Constant | Value | Meaning |
|---|---|---|
| `TOUCH_LOADED` | `loaded` | A class was defined. The value is its binary name |
| `TOUCH_DEFINED` | `defined` | A class was defined by a class loader other than the JDK's application and platform loaders, or as a hidden or anonymous class by code outside the JDK. The value is its binary name. Dates the class as a lookup of its name does, since such a definition can be a second copy of it |
| `TOUCH_READ` | `read` | A class or source file was opened, copied or moved. The value is its path, or its entry name in a jar |
| `TOUCH_LOOKUP` | `lookup` | A class was looked up by name: `Class.forName`, `ClassLoader.loadClass` or `findLoadedClass`, `Lookup.findClass`. The value is the name, or `*` for something that can reach any class: `Instrumentation.getAllLoadedClasses`, a reviewed native library loaded by its own class, a native agent, or lookup hooks that are not all installed |
| `TOUCH_PLAN_STARTED` | `plan-started` | No value. Everything before it happened during discovery |
| `TOUCH_JAR` | `jar` | A jar was opened by code other than the JDK's own zip classes. Every class in it counts as read |
| `TOUCH_ALL` | `all` | From this record on, loads and reads were not all observed: a hook missing, a child process started, a native library not on the reviewed list loaded from outside the JDK, the foreign-function linker or a library lookup called from outside the JDK, or a class defined from bytes whose name could not be read. The value says why |
| `TOUCHES_COMPLETE` | `#complete` | The last line of a complete `touches.tsv` |
| `FIRST_TOUCH_ANY` | `*` | As a first-touch class: every class. Written for a JVM whose touches are missing, incomplete or unreadable, and never read as "none" |

### Recording modes

`jvm-mode.tsv` says how each JVM of the map was recorded. Selection does not read it: the first-touch
rule already stays inside a JVM, so a JVM that ran one test class dates only that class's tests. The
plugin reads it for one thing: a selecting run that falls back to a full run does not capture over a
map any JVM of which is `isolated`. It is also there for `yoriwakeExplain` and for anyone reading the
map.

| Constant | Value | Meaning |
|---|---|---|
| `MODE_ISOLATED` | `isolated` | Recorded with `-Pyoriwake.isolatedCapture`, and the JVM ran tests of one class only |
| `MODE_SHARED` | `shared` | Any other JVM. A JVM with no row, or a map with no file, counts as this |

### The loaded-class directory

Wherever `yoriwake.loaded.out` points, one pair of files per worker.

| Constant | Name | Format |
|---|---|---|
| `LOADED_WORKER_PREFIX` | `loaded-` | Starts `loaded-<worker>.txt` and `loaded-<worker>.complete` |
| `LOADED_WORKER_SUFFIX` | `.txt` | One class name per line |
| `COMPLETE_SUFFIX` | `.complete` | Empty. Written only when the worker's test plan finished normally |

## Record ids and outcomes

The last column of `coverage.tsv` and `index.tsv`:

```
record-id    = test-id | unattributed | class-scoped
test-id      = a JUnit Platform unique id, e.g. [engine:junit-jupiter]/[class:com.acme.ATest]/[method:a()]
unattributed = "[yoriwake:unattributed]"
class-scoped = "[yoriwake:class]" class-uid [ "|" class-uid ]
class-uid    = a unique-id prefix ending at its outermost "[class:...]" segment
```

An unattributed record holds startup coverage no test or class owns. A class-scoped record holds the
window between two tests: the ending class's `@AfterAll`, the starting class's `@BeforeAll` and
construction. Several class-scoped records can share an id, and each one counts.

| Constant | Outcome | Meaning |
|---|---|---|
| `UNATTRIBUTED_RECORD_ID` | `[yoriwake:unattributed]` | The unattributed id |
| `CLASS_SCOPED_RECORD_PREFIX` | `[yoriwake:class]` | Starts every class-scoped id |
| `OUTCOME_SUCCESSFUL` | `SUCCESSFUL` | The test passed. Any other outcome makes the test run whatever changed |
| `OUTCOME_NOT_A_TEST` | `NONE` | An unattributed or class-scoped record |
| `OUTCOME_UNKNOWN` | `UNKNOWN` | No result was reported, or no in-scope class was covered |
| `OUTCOME_SKIPPED` | `SKIPPED` | The platform skipped the test |
| `OUTCOME_FLAKY` | `FLAKY` | The test both passed and failed in one capture, as a retried test can. Written on each of its passing and failing lines, whatever the order; a round that covered no in-scope class stays `UNKNOWN` |

A test can also carry `FAILED` or `ABORTED`, the JUnit Platform's own names.

## Decision record notes

Lines in `decisions.tsv` that start with `#!`. Values are escaped: `\\`, `\t`, `\r` and `\n`.

| Constant | Key or value | Meaning |
|---|---|---|
| `NOTE_PREFIX` | `#!` | Starts every note |
| `VERSION_NOTE` | `record-version` | The decision record version |
| `ROWS_NOTE` | `rows` | Rows the writer meant to write. Fewer rows means the tail was lost |
| `WRITER_NOTE` | `writer` | Which writer survived, `<n> of <m>` |
| `OUTCOME_NOTE` | `outcome` | One of the five values below |
| `FULL_RUN_KIND_NOTE` | `full-run-kind` | Which side forced, as a token |
| `FULL_RUN_REASON_NOTE` | `full-run-reason` | Why, as prose |
| `REFUSAL_KIND_NOTE` | `refusal-kind` | The plugin's refusal token, when it refused |
| `DECLINES_NOTE` | `declines` | Every decline that held, as `yoriwake.declines` carried it. Absent when none did |
| `OBSERVED_OUTCOME_NOTE` | `observed-outcome` | On an observing run, the outcome a selecting run of the same inputs would have noted: `outcome`, then its `full-run-kind` and `refusal-kind` when it has them, tab-separated. `outcome` says what ran, `full-run` |
| `RUN_TOKEN_NOTE` | `run-token` | The run's `yoriwake.run.token`. Noted only when the run was given one |
| `JVM_NOTE_PREFIX` | `jvm.` | Starts `jvm.<property>`, beside `run-token`: each of the test JVM's identity properties below, with its value |
| `JVM_IDENTITY_PROPERTIES` | `java.version,java.vendor,java.vm.name,os.name,os.arch` | The system properties the `jvm.` notes carry |
| `INPUT_NOTE_PREFIX` | `input.` | Starts `input.<property>`: each property the decision read, for replay |
| `RUN_NARROWED` | `narrowed` | Outcome: some tests were skipped |
| `RUN_FULL` | `full-run` | Outcome: everything ran |
| `RUN_NOT_REQUESTED` | `selection-not-requested` | Outcome: the run did not ask to select |
| `RUN_NOT_DECIDED` | `not-decided` | Outcome: discovery never asked |
| `RUN_COMPLEMENTED` | `complemented` | Outcome: a complement run left out the tests the selection record lists as ran. Its rows read `ALREADY_RAN` (excluded) or `NOT_ALREADY_RAN` (included) |
| `COMPLEMENT_NOTE` | `complement` | On a complement run, `leftOut \t ran`: how many tests this writer left out as already run, and how many it ran |
| `RULES_NOTE` | `rules` | What the rule lines were computed from, one of the five values below. Absent when the run decided nothing |
| `FORCING_KINDS_NOTE` | `forcing-kinds` | Every full-run kind whose condition held, comma-separated in the order the selector checks them, or `none`. `full-run-kind` names only the first |
| `RULES_COMPLETE` | `complete` | Rules: from every input the selector reads |
| `RULES_FROM_REFUSED_INPUTS` | `from-refused-inputs` | Rules: the daemon refused after it had a change set, and they come from what it handed over. A refusal from the inline scan or the digest rule hands over the changed classes without inline consumers and without changed bytes |
| `RULES_NO_CHANGE_SET` | `no-change-set` | Rules: none, because the daemon refused before it had a change set |
| `RULES_NO_MAP` | `no-map` | Rules: none, because there was no usable map |
| `RULES_FAILED` | `failed` | Rules: none, because computing them threw. What ran is unaffected |

## Decision rules

Which verdict a row got names the one rule that decided it. A test is often run by several rules
at once, so `decisions.tsv` also names all of them, one line per row after the rows:

```
#+test \t rule,rule
```

Written only when `rules` is `complete` or `from-refused-inputs`, with one line per row. They are
recorded, never read back: the selector decides first and computes these apart from that decision.
Each rule is evaluated on its own, beneath a full run or a refusal too, so the line under a
full-run row says what the selection would have been without whatever forced it. The selection
coverage alone makes, with the known tests of an edited test class (`own-class-changed`), is every
row whose line names a rule outside the `shares-jvm-*` family and `class-granularity`.

| Constant | Rule | The test runs because |
|---|---|---|
| `RULES_LINE_PREFIX` | `#+` | Starts every rule line |
| `RULE_REACHES_CHANGE` | `reaches-change` | Its own coverage holds a changed class |
| `RULE_OWN_CLASS_CHANGED` | `own-class-changed` | Its own test class changed, and the map holds it as a test of that class |
| `RULE_CHANGED_BYTES` | `changed-bytes` | Its own coverage holds a class whose bytes changed with no changed source behind them |
| `RULE_CLASS_SETUP` | `class-setup` | A class-scoped record of its class reaches the change |
| `RULE_SHARES_JVM_CHANGED_CLASS` | `shares-jvm-changed-class` | It ran at or after its JVM first touched a changed class or its file |
| `RULE_SHARES_JVM_CHANGED_BYTES` | `shares-jvm-changed-bytes` | It ran at or after its JVM first touched a class whose bytes changed |
| `RULE_SHARES_JVM_UNOBSERVED` | `shares-jvm-unobserved` | It ran at or after its JVM's touches stopped being all observed (`*`): a child process, a native library off the reviewed list, a hook missing. The map does not say which; the worker's raw `touches.tsv` does |
| `RULE_SHARES_JVM_UNPOSITIONED` | `shares-jvm-unpositioned` | The map holds no position for it, so nothing says it ran before the change arrived |
| `RULE_NOT_KNOWN_TO_PASS` | `not-known-to-pass` | The map does not record it as having passed |
| `RULE_NOT_IN_MAP` | `not-in-map` | The map has never seen it |
| `RULE_ALWAYS_RUN` | `always-run` | It is pinned by pattern or tag |
| `RULE_ENGINE_RUNS_EVERYTHING` | `engine-runs-everything` | Its engine runs every test it discovered or none (Kotest), so selection never leaves it out |
| `RULE_CLASS_GRANULARITY` | `class-granularity` | Another test in its class is selected, and selection rounds up to classes |
| `RULE_NONE` | `none` | Nothing would run it |
| `RULE_UNKNOWN` | `unknown` | Its rules could not be computed |

A parameterised test or container carries the rules of the invocations recorded beneath it. The
whole-run forcing rules are `forcing-kinds`, and the daemon's own refusal is `refusal-kind`.

## Observation lines

On an observing run (`-Pyoriwake.observe`) every test runs, so every row's verdict is `included` and
its reason `OBSERVING`. What a selecting run of the same build, flags and map would have written for
that row follows the rows and the rule lines, one line per row:

```
#?test \t verdict \t reason
```

The rule lines then follow the verdict the observation line holds, as they would on a selecting run.

| Constant | Value | Meaning |
|---|---|---|
| `OBSERVATION_LINE_PREFIX` | `#?` | Starts every observation line |

## Ran lines

Each included row whose test finished `SUCCESSFUL` or `FAILED` in the test JVM's outermost test plan
follows the observation lines, one line per such row, whether or not the run captured:

```
#=test \t outcome
```

A test that aborted, was skipped, or never finished (a fork killed, `--fail-fast`) has none, and
neither has a test a launcher started inside another test ran.

| Constant | Value | Meaning |
|---|---|---|
| `RAN_LINE_PREFIX` | `#=` | Starts every ran line |

## The selection record

`selection.tsv` holds `#!key \t value` notes, then one row per test that ran:

```
test \t outcome
```

`outcome` is `SUCCESSFUL` or `FAILED`. The plugin writes it only when every decision record the run
left carries its `run-token`, none was cut short, they agree on the test JVM, at least one narrowed
and none ran everything, and HEAD was the same, with a clean tree, where the run started and where
it ended. A test JVM that wrote no record (killed, halted) leaves its tests out of it. The notes are
`record-version`, the stamp below, each `jvm.<property>` the decision records carried, and `rows`.

| Constant | Note | Meaning |
|---|---|---|
| `STAMP_COMMIT_NOTE` | `commit` | HEAD where the run started and ended |
| `STAMP_TASK_NOTE` | `task` | The test task's path |
| `STAMP_BUILD_ROOT_NOTE` | `build-root` | The build's root directory relative to the repository's top level, empty at the top |
| `STAMP_BUILD_PATH_NOTE` | `build-path` | The project's path in the build tree, which names an included build |
| `STAMP_CLASSPATH_NOTE` | `classpath` | A SHA-256 of the test runtime classpath's files, in order, each relative to the build root or the Gradle user home when under one |
| `STAMP_CONFIGURATION_NOTE` | `configuration` | A SHA-256 of the task's system properties and JVM arguments other than yoriwake's, its include and exclude patterns and its framework's filters |

A record of another version, or whose rows do not match `rows`, is unusable whole.

## Versions

| Constant | Version | Of | Bumped when |
|---|---|---|---|
| `MAP_SCHEMA_VERSION` | `7` | the map | A record written before could still look like a passing one while being wrong |
| `RAW_SCHEMA_VERSION` | `3` | a worker's raw records | The index columns, the record-id shapes or the outcome values change |
| `SELECTION_VERSION` | `1` | `selection.tsv` | The meaning of a field or the stamp changes |
| `DECISIONS_VERSION` | `2` | `decisions.tsv` | The meaning of a field changes. Adding a field is not a bump |

The plugin and the agent ship in one jar, so they always agree with each other. Versions exist for
files left on disk by another release. Each reader refuses any other version, and a missing stamp,
rather than reading part of it:

- A map in another version is discarded and rebuilt by the next full run. See
  [Upgrading and your map](reference.md#upgrading-and-your-map).
- A worker directory holding records in another raw version, or with no `raw-schema-version`, fails
  the decode and leaves the previous map as it was.

The map and the raw records have separate version files because they are separate formats.
