# Sweep data dictionary

What a corpus sweep records, per project, per row and per arm, where each field comes from, and
which chart or decision it serves. A sweep starts only when every field below is filled on a dry
run.

The plugin gains no telemetry: every field is read by the harness from files the plugin and agent
already write, from Gradle's build events, or from `ps`. `None` always means "could not be asked"
or "does not apply", never zero. Figures derived from these fields carry the caveats in [benchmarks.md](benchmarks.md#caveats).

## Arms

- **Shared** (`results/<project>.json`, scratch `logs/<project>/`): the host's own test layout,
  as in every 0.1 sweep.
- **Shared, lighter 0.1.0 rules** (`sweep_corpus.py --arm full`; the reduced arm keeps its own):
  - **forced rows run once** outside `TIMING_SUBSET`: `run.py --recall-only` takes a forced
    selected arm as the ground truth, so the row has `full_arm_skipped: true` and no
    `full_seconds` (absent, never invented). Narrowed rows still run both arms everywhere;
  - **stopping rule** `--narrowed-target 4`: a project draws targets in the reach-stratified order
    until 4 rows narrowed and induced a failure (`status: checked`, `narrowed_at_all`), or until
    its cap (`sweep_limit`, else `--limit 16`). The rule reads only arm sizes, the selector's
    outcome token and the full arm's failures, never a missed or caught test, so it cannot choose
    rows by their recall outcome (`run.counts_toward_narrowed_target`).
- **Isolated** (`results/<project>.isolated.json`, scratch `logs/<project>.isolated/`): the map is
  recorded with `-Pyoriwake.isolatedCapture` (a fresh test JVM per class) and **`maxParallelForks`
  raised to the machine's core count** for that recording only (`isolated-forks.init.gradle`,
  stamped `capture_max_parallel_forks`). Selecting arms carry the flag too, and run the host's
  forks, as the plugin guarantees. `sweep_corpus.py` runs it after a project's shared arm
  (`--no-isolated-arm` to skip) as `run.py --isolated-capture --pair-with results/<project>.json`,
  on the full arm **only for single-JVM suites and `ISOLATED_ALSO`** (below); every other project
  writes a summary line `isolated arm: not run (multi-JVM suite)` or `(test JVM count unknown)`:
  - the **same rows**: exactly the shared file's rows that asked a question (`checked`,
    `no-effect`);
  - the **same ground truth**: each row reuses the paired row's full arm (`induced`,
    `full_distinct`, `full_seconds`, `full_arm`), marked `ground_truth_from: "paired"`; no
    baseline or already-failing run of its own;
  - refused when the paired file's `selector_digest` differs.
- **Setup errors fail fast** (from 0.2): every row and the preflight start from the
  untracked and ignored files present when the checkout was prepared plus those the capture wrote
  (`logs/<project>/untracked-snapshot.json`); files a baseline or an earlier row wrote are removed,
  never anything under `.gradle/`, `.kotlin/`, `.git/` or a build directory beside a build script
  (`--no-untracked-reset` only reproduces a 0.1 row). A **preflight** then selects a behaviour-neutral
  edit (a comment on the `package` line of the first `body` target's source). If it, or three forced
  rows in a row, force as `unmappable-paths` or `map-unusable` with one reason naming nothing the
  change touched, the project stops: `project_outcome: setup-forced`, `run.py` exits 3, and
  `sweep_corpus.py`, `corpus_state.py` and `readme_charts.py` list it as failed and count none of
  its rows. A resumed setup-forced file starts fresh. A project whose tests or build write into
  its checkout during the capture is fixed in its project file's `flags`: a task left out, or
  `bench/guest-tests.init.gradle` with the test classes to leave out (`-Dyoriwake.harness.testClassExcludes`)
  or the test JVMs' system properties (`-Dyoriwake.harness.testSystemProperties`).
- **Timing cap** (from 0.2): a `TIMING_SUBSET` project runs `--timing-cap 8`: its first 8
  rows in the draw order run both arms whatever they decide (`timed: true`); later rows run as
  under `--recall-only`. Chart 2 draws timed rows only, states the cap in its title, and refuses
  to pool files timed under different caps (or none) unless `--pool-timing-caps` is passed.
- **Complement ground truth** (`run.py --ground-truth`, default `full`): on an untimed
  narrowed row the ground truth may come from the **complement**, every test class holding a test
  the selection dropped, run whole through the full arm's init script; the row's truth is then the
  selected arm's and the complement's outcomes together. `check` runs the full arm beside it on
  every such row and compares them; `auto` runs the complement alone except on a check row: the
  first 2 untimed narrowed rows, a dropped class that ran after a shared-JVM-kept class in one
  capture JVM, a project an earlier sweep or the baseline repeats flagged, a single-JVM suite (its
  isolated arm pairs), 1 in 8 by the target's hash, or a row whose failures are not keyed per
  method. A complement costing over half the unmutated suite's time
  (`logs/<project>/suite-profile.json`) runs the full arm. One disagreement under `auto` turns the
  complement off for the project and re-measures its complement rows with the full arm
  (`remeasured`). `auto` is enabled only after a replay of the complement over the 0.1 rows and
  `controls.py --ground-truth auto` both pass.
- **Cost** (`bench/cost.py`): `sweep_corpus.py` prints and appends to
  `sweep-summary.jsonl` a projection before the sweep (`cost_projection`: per project, from its
  last results file or its qualification run) and a report after it (`cost_report`, `cost_total`:
  hours per family and per row where a miss was possible, split by ground truth and timing).
  Neither moves a cap or stops a sweep; follow-up re-runs are in neither.
- **Reduced arm from 0.2**: its timing-subset member (grpc-java) runs `--timing-cap 8`,
  the others `--recall-only`, and all three `--ground-truth check`, so every untimed narrowed row
  carries both the complement and the full arm. `assert_recall.py --against <0.1 file> <file>`
  compares recall outcomes on full-arm truth, refuses `complement` rows, and names a difference
  whose 0.1 reason lists a path the reset removed as the reset's.
- **Planted-miss controls** (`bench/controls.py`): the plugin is built in a scratch clone
  with `controls.PLANTED_MISS` applied (a narrowed run drops every test it selected through coverage), and the
  fixtures `demo`, `demo-kotlin` and `order-dependent` are swept against it with `run.py --control`
  into `results/control/` (scratch `logs/<project>.control/`, its own map stamp and evidence). Every
  project must report a miss; `corpus_state.py` and `readme_charts.py` refuse a file holding a
  `control` row. `--set` runs a named set's control into `results/control/<set>/`: `pool/v1`
  (demo, demo-kotlin) and `revert/v1` (spring-framework's spring-beans) under the same plant,
  where every family must report a miss on some project (pool/v1's fixtures ask body and its own
  four families; `--corpus` asks aliased, constant, renamed and schema on moshi and okhttp,
  drawing only those with `run.py --families`). A family with no row that narrowed and induced a
  failure cannot show a miss: it passes as UNCONTROLLED, printed with its reason, only when
  `controls.UNCONTROLLED` declares it (`renamed`, `schema`), and fails otherwise;
  `forcing/v1` (demo, demo-kotlin; `--corpus`: ktlint for `dependency-revert`, grails-core for
  `groovy-body`) under `planted_no_force()`, which reads every forcing family's paths as unread,
  and with `NO_FORCE_RULES` (the classpath-files and environment refusals that force those rows
  behind the path rule) off, where every family's row must narrow as the harness detects it, a
  family a test reads the file of (`READ_BY_A_TEST`) must report a miss, and the families only
  asked to narrow are printed, with any of their rows whose failing test coverage selected; and
  `neutral/v1` (every demo fixture, no plant), whose null change must be a full run of kind
  `empty-change-set` that ran tests and whose neutral edit (the preflight's comment) must induce
  no failure and report no miss. `run.py --shapes neutral/v1`
  runs only with `--control`, and `--control` runs on a corpus checkout only for `revert/v1`,
  `forcing/v1` and `pool/v1`, its map stamped `shared-control`. `--verdict` judges a leg's results
  files on disk without running it.
- Both arms write one map directory per task. `run.py` re-captures when the map on disk was
  recorded by the other arm (`jvm-mode.tsv`) or has another schema than the checkout's
  `AgentContract.MAP_SCHEMA_VERSION`.

### The subsets, fixed in `sweep_corpus.py`

Constants, not computed per run, so the sample cannot drift between sweeps.

**`TIMING_SUBSET`, paired times** (both arms on every row; from 0.2 on its first 8 rows): six projects spanning suite length,
each with rows that narrow, from the last results files' median `full_seconds`.

| project | median full arm | tests | rows narrowed | why |
|---|---|---|---|---|
| grpc-java | 6 s | 1,536 | 8 of 16 | short end |
| apache-groovy | 24 s | 1,102 | 10 of 11 | 15 test JVMs: the multi-JVM case |
| mockito | 48 s | 2,356 | 12 of 16 | |
| spring-boot | 85 s | 3,682 | 8 of 13 | the configuration-cost case |
| rxjava | 181 s | 13,921 | 14 of 16 | the largest suite |
| bitwarden-android | 626 s | 7,341 | 7 of 16 | long end, Android |

iceberg (1,039 s) is the longer end and was left out on price: 17 minutes per forced row's second
arm. **A time-saved figure is drawn from these six only.** A `recall_only: true` file still times
its narrowed rows, and a median over them leaves out every forced row; `bench/readme_charts.py`
skips those files in chart 2.

**Isolated arm.** Runs where the shared results file's `capture_facts.map_task_facts.workers` is 1
(one test JVM recorded the task's map: every test shares one JVM's state, and the shared-JVM rule
widens most), plus `ISOLATED_ALSO`: opentelemetry-java, spring-framework, spring-boot-webmvc-test
and micrometer (the four modules isolated capture was first measured on, so the figure reads against
that measurement) and spring-test (three engines in one JVM). From the last maps, the single-JVM suites
are armeria, demo-android, grpc-java, iceberg, junit5, kestra, kotlinx-serialization, micrometer,
micronaut-core, mockito, mockito-junit4, moshi, okio, opentelemetry-java, spring-boot,
spring-framework and spring-security (kotest and mockk too, unpriced); the sweep reads the count
from its own capture.

### The target

A project stops drawing once 4 of its rows narrowed and induced a failure. At 4 a sweep still keeps
almost every row where a miss was possible; counting narrowed rows whether or not they induced a
failure would save more hours and keep fewer of those rows.

## Results file header (one per project and arm)

| field | source | serves |
|---|---|---|
| `capture_mode` | the arm run.py was asked for | every per-arm comparison |
| `capture_max_parallel_forks` | `--isolated-forks` (default: cores); never above `max_parallel_forks`; None on the shared arm | overhead shared vs isolated (the isolated figure is not the host's layout) |
| `max_parallel_forks` | the project file's cap on every build's test forks, both arms and every phase alike (`fork-cap.init.gradle`, which only lowers); None: the guest's own forks | which files ran fewer forks than the guest's CI would |
| `paired_with` | `--pair-with`; each row is matched to its paired row per mutation, `(target, kind, index)`, and a paired file holding one mutation twice is refused (files written before 2026-10-08 matched by label, so two declarations of one name could take each other's ground truth) | provenance of the isolated rows' ground truth |
| `rejected_mutations` | the compile gate's rejections as `[target, kind, index]`, beside the labels in `rejected_targets`; None before 2026-10-08 | resume: a rejected declaration does not skip its namesakes |
| `revert` | `revert/v1` only: the census window and classifier, eligible commits per stratum, not-eligible counts by reason, rejections as `[target, reason, variant, census labels]` and counted by reason and label, and per inverted label the rows, the miss-possible rows and whether the shape is reached | published beside a revert figure; no rejection ceiling applies |
| `forcing` | `forcing/v1` only: `population` (`flag-on` when a narrowing flag is in `flags`, else `flag-off`), `rows_per_family`, the dependency reverts' window, eligible count and not-eligible counts by reason, and rejections as `[target, reason]` | a forcing figure; flag-on rows are never pooled with flag-off ones; no rejection ceiling applies |
| `narrowed_target` | `--narrowed-target` of the invocation that last wrote the file; None = no rule | sample definition (rows carry their own) |
| `recall_only` | `--recall-only` of that invocation | true: forced rows ran once, no time-saved figure from this file |
| `ground_truth_mode` | `--ground-truth` of the invocation; None in older files | which rows could have taken a complement |
| `complement_switched_off` | `{at, disagreements}` when a check row disagreed under `auto`; None otherwise | the complement is off for this project |
| `control` | true on a planted-miss control run (`--control`); None in older files | every reader refuses it |
| `control_families` | the families a pool/v1 control drew from (`--families`); None is every family | a control's |
| `timing_cap` | `--timing-cap` of that invocation; None = every row of a timing sweep was timed, and every older file | which timing sample a paired-time figure pools |
| `capture_seconds` / `baseline_seconds` | wall clock of the capture and uninstrumented baseline builds (isolated: baseline copied from the pair) | recording overhead shared vs isolated; chart 2 x-axis (suite length) |
| `capture_arm`, `baseline_arm` | arm record (below) for those two builds | peak memory with agent recording vs without agent; test JVM count |
| `capture_facts` | map right after capture: `map_schema_version`, `map_capture_mode`, `map_jvm_modes`, `map_bytes`, `map_raw_bytes`, `map_task_facts` | map size on disk; forks and framework; which mode wrote the map |
| `capture_touch_all_reasons` | `raw/*/touches.tsv`: count of `all` lines by cause, plus `lookup *` | whether the native-library, child-process and class-loader rules fire in real suites (corpus coverage) |
| `capture_raw_bytes_kept`, `capture_raw_archive` | the capture's raw records, `.exec` included, archived once per capture | offline replay and later questions |
| `environment` | OS, release, arch, cores, memory, Python; Gradle from the wrapper properties beside the wrapper used; the project's `daemon_java_version` and `daemon_jvmargs`; the checkout's map schema | environment line of `docs/benchmarks.md` |
| `evidence` | `logs/evidence/<project>-<arm>.tar.gz`, path and bytes | raw evidence kept |
| `project_outcome` | `measured`, or `setup-forced` when the preflight or three forced rows stopped the project; None in older files | every reader: a setup-forced project is failed and in no figure |
| `setup_forced` | `{reason, at: preflight\|rows, rows}`: the decision's untruncated reason and the rows that showed it; None otherwise | the finding to file and fix |
| `preflight` | the preflight's `target`, `line`, `untracked_reset`, `decision_outcome`, `full_run_kind`, `decision_reason`, `seconds`, `setup_forced`, or `skipped` with why; absent on `--only-target`, `--pair-with` and `--replay` | whether the setup forces before any row is drawn |
| `untracked_reset` | false under `--no-untracked-reset`; None in older files | population boundary |
| existing: `plugin_commit`, `selector_digest`, `commit`, seeds, grammar, `flags`, `staged_sources`, `baseline_repeats*`, `targets_available`, `mutations_rejected`, `map_*`, `daemon_uptime_at_start` | unchanged | freshness, sample definition |

## Row (one per mutation or replayed commit)

| field | source | serves |
|---|---|---|
| `selected_distinct`, `full_distinct` | JUnit XML of each arm: distinct XML keys on every row, whatever its `result_keys` | **chart 1** share distribution; summary table "tests run per change" |
| `ci`, `full_ci` | on CI, the runner, run id, attempt and job that measured the row; `full_ci` only on a paired row, the job its borrowed full arm ran in; None off CI | which machine a ratio's two arms shared; a sharded sweep's rows come from several jobs |
| `selected_seconds`, `full_seconds` | wall clock per arm | **chart 2** time saved; summary table "time saved" |
| `selected_arm`, `full_arm` | arm record (below) | fixed overhead; peak memory selecting vs recording |
| `decision_outcome`, `full_run_kind`, `refusal_kind`, `selected_verdicts`, `decision_included`, `decision_matches_arm`, `decision_writer` | the agent's `decisions.tsv` parts | refusal kinds; classification gate |
| `rules_basis`, `forcing_kinds`, `rule_counts`, `coverage_only_included`, `coverage_only_digest` | the record's `#+` rule lines and notes | per-rule counts; selection beneath a refusal |
| `induced_rules` | every induced failure (`class::name`) -> union of rule tokens of the decided tests with that name (a per-method key through its first segment, the template, factory or feature); None = the record never saw it | **optimistic-mode per-rule analysis**: which failures a rule set without X would miss |
| `shares_jvm_included` | `selected_verdicts["included/SHARES_JVM_WITH_CHANGE"]` | how much the shared-JVM rule widens; shared vs isolated |
| `decision_write_failures` | count of the agent's `could not write decisions.tsv` lines in the selected arm's log; > 0 makes the record absent | item 2 below: a lost record never reads as the task's answer |
| `decision_rows`, `decision_covers_arm` | rows decided vs `selected_distinct`; False = the record describes fewer tests than ran | a silently overwritten record (the case the log line cannot see) |
| `missed`, `order_dependent`, `nondeterministic`, `undetermined`, `container_failed`, `fails_unmutated`, `ran_but_passed`, `induced` | existing recall pipeline; `class::name` on the row's `result_keys` scheme -- per method, `name` is the template, factory or feature above the test, then its display name, joined by ` > ` (escaped inside a name), and equals the XML key where nothing sits between class and test | **recall / missed failures with cause** |
| `shared_key` | induced failures the selected arm ran only under a key that may stand for another test (`run.shares_a_key`: no single method named, a class the record did not keep whole, a row that could have narrowed), or in a class the record excluded whole. The baseline, the re-runs and the control arm ask the same rule | neither missed nor clean, like `undetermined`; still in `full_failures` |
| `result_keys`, `result_keys_fallback` | `per-method` when every comparison the row made (arms, baseline, re-runs, control arm) had both builds' complete per-method outcomes, else `xml`, with the first comparison that fell back and why; absent on rows before 2026-10-08, which matched XML keys without the shared-key rule | which population a recall row belongs to (published numbers 3d) |
| `collapsed_induced` | induced failures whose per-method key two tests with different own names produced in these builds; empty on `xml` rows; absent before 2026-10-08 | `--pair-with`: a paired per-method row passes its collapses to the isolated arm, which cannot see them from its own selected run; a per-method row without it is paired on XML keys |
| `decision_included_leaves` | `decision_included` without containers: included ids that prefix no other recorded id; absent before 2026-10-08 | `decision_vs_arm_gap` |
| `container_failed` | misses on a narrowed row named `initializationError` or `executionError` (a class that could not start, or whose `@AfterAll` or extension failed), with an optional `[target]` suffix; `executionError` from 2026-10-08 | neither missed nor clean: no test method ran under the name, and a re-run of the class cannot decide it |
| `change_files`, `change_modules`, `change_classes` | mutation: 1/1 and the agent's `input.yoriwake.change.classes` note; replay: `git diff --name-only`, modules as the first path segment (a heuristic) | change shape |
| `widest_windows` | top 5 changed classes by tests at or after their first touch (`first-touch.tsv`, `positions.tsv`; `*` counts for every class) in the map the arm read | "what widens your selection" report decision |
| `map_schema_version`, `map_capture_mode`, `map_jvm_modes`, `map_bytes`, `map_task_facts` | the map snapshot the selecting arm read (a forced arm re-records it, in shared JVMs) | per-arm label; map size |
| `raw_bytes_kept` | raw records of the selecting arm, without `.exec` unless `--keep-exec`; None when the arm recorded nothing (a narrowed arm does not) | raw evidence |
| `ground_truth_from` | `own` or `paired` | isolated arm provenance |
| `narrowed_target` | `--narrowed-target`; None on a row drawn without the rule, and on the isolated arm, whose rows are the paired file's | sample definition |
| `full_arm_skipped` | true when `--recall-only` took a forced selected arm as the ground truth; `full_seconds` is then None | which rows carry a paired time |
| `ground_truth` | `full`, `complement` or `complement+full` (a check row: the full arm is the truth, the complement compared); a paired row carries the shared row's; None in older files, which reads as `full` | every recall figure states its mix (`assert_recall.ground_truth_line`) |
| `ground_truth_rules` | why the full arm ran beside or instead of the complement: `check-mode`, `first-rows`, `paired-arm`, `shares-jvm`, `order-dependent-project`, `sampled`, `keys`, `cost`, `unpriced`, `unnamed-class`, `nothing-dropped`, `switched-off`, `complement-failed`; empty when nothing chose | the controls' shared-JVM condition; the adoption review |
| `complement_classes`, `complement_seconds` | classes the complement ran, and its wall clock; None when none ran | cost report |
| `complement_disagreements` | each test the complement and the full arm judged differently (`induced` or `ran-but-passed`), both verdicts; None when nothing was compared | the switch-off |
| `remeasured` | true on a row measured again with the full arm after a switch-off | |
| `full_distinct` on a `complement` row | the unmutated suite's size from the suite profile | narrowing share |
| `ran_but_passed` on a `complement` row | None: not measured | |
| `control` | true on a planted-miss control row; None in older files | refused in every figure |
| `gate_seconds` | the compile gate since the last accepted row, rejected draws included; None in older files | cost projection and report |
| `followup_seconds` | the isolated re-runs a miss triggered; None in older files | left out of every cost figure |
| `revert_commit`, `census_labels`, `revert_labels`, `revert_stratum` | `revert/v1` (`revert_commit` and `census_labels` on `forcing/v1`'s `dependency-revert` rows too): the reverted commit, its census labels, the same labels as the revert makes them (an added member or file becomes a deleted one; labels of paths the row left in place are dropped) and its stratum (`single-file`, `several-files`, `several-modules`) | a revert figure per shape; "not reached by revert/v1" |
| `revert_variant`, `revert_scope` | `revert/v1` only: `main` (main code under the roots) or `full` (the measured module's tests too, after a test-compile failure); the commit's paths the row did not revert | what a revert row changed; `change_files` and `change_modules` count the reverted tree |
| `timed` | whether the row's two arms are a paired time: false under `--recall-only`, past the timing cap and on a paired arm; None in older files | chart 2's sample |
| `untracked_reset` | the untracked and ignored files removed before the row (often `[]`); false with the reset off; None in older files | a 0.1 row that forced on a file an earlier run wrote may narrow from 0.2 |
| existing: `replay`, `full_run_reason`, `map_capture_commit`, `map_records`, `map_attributable`, `*_conditions`, `reachability`, `sweep_limit` | unchanged | replay, freshness, machine load |

## Arm record (`capture_arm`, `baseline_arm`, `selected_arm`, `full_arm`)

Written by `run.gradle` for every build, keyed by its log path.

| field | source | caveat |
|---|---|---|
| `test_jvms` | distinct `Gradle Test Executor` processes seen by `ps` | JVMs living under 0.5 s can be missed; read with `rss_samples` |
| `peak_test_jvm_rss_mb`, `peak_total_test_jvm_rss_mb` | max RSS of one test JVM, and of all at one sample | RSS, sampled at 2 Hz, a lower bound. **Peak memory with the agent**: selected and full arms, capture; **without**: baseline (no init script, no agent) |
| `test_java_version` | `release` file of the JDK the worker ran | |
| `tasks.test_task_seconds`, `tasks.decode_seconds`, `tasks.before_first_task_seconds`, `tasks.after_last_task_seconds`, `tasks.tasks_executed` | `task-times.init.gradle` build-event listener | **fixed overhead**: before-first-task is handshake plus configuration |
| `plugin.configure_millis`, `plugin.git_millis`, `plugin.git_calls` | `-Pyoriwake.internal.counters` on init-script arms | the plugin's own configuration and git cost; git None on a run that asked git nothing |
| `decision_write_failures` | log line count | |
| `network_retries` | 1 when the build failed on a dependency download alone and ran once more after a pause, else 0; the first attempt's log is kept as `<name>.network-1.log` | the record and its timings describe the rerun; a rerun usually falls in a module's first build, before the timed arms |

## Maven files (`build = "maven"`)

A Maven project's file has every field a Gradle one has. Where the Maven side cannot fill one, it
is null for the reason given here, never a figure borrowed from another meaning.

| field | source | serves |
|---|---|---|
| `build_tool` | `maven`; absent on a Gradle file, which every reader takes as `gradle` | `compare_results.py` refuses to compare two build tools |
| `maven_digest` | digest of `maven/src/main` and `maven/build.gradle.kts` at publish, beside `selector_digest` (which a Maven file carries too, unchanged in meaning) | freshness: the resume, `merge.py`'s one-digest-per-sweep check and `corpus_state.py` also check it; absent on a Gradle file |
| `staged_pom` | what the staging commit wired: `plugin added` or `plugin version`, `jacoco <goal> added`, `<execution> skipTests=false`, `target/ ignored` | the staged tree is reproducible from `commit` plus this list |
| `environment.maven_version` | `mvn --version` of the Maven the arms ran: the guest's `mvnw`, else the pinned one; `environment.gradle_version` is null | environment line |
| arm record `tasks`, `plugin` | null: both come from Gradle init scripts | |
| arm record `test_jvms` and the RSS fields | Surefire's and Failsafe's forked JVMs (`surefirebooter`); `test_java_version` is null when the fork starts through a shell | |
| row `result_keys` | always `xml`, `result_keys_fallback` naming `no listener on Maven`: no per-method listener exists for Surefire | which population a recall row belongs to |
| row `status: invalid` with `declined` | an arm in which the plugin declined the measured execution (`declined, <token>:`), or in which a test execution other than the measured one ran; the token is `declined` | a decline is never scored as a narrowed or clean row |
| row `status: not-run` | the measured execution was skipped or never reached in a build that did not fail; no build cache serves a Maven arm, so it is never `cache-hit` | answered nothing, as `stalled` |

A capture whose execution the plugin declined refuses the project, naming the token.

## Project summary (`sweep-summary.jsonl`, `sweep_corpus.summarise`)

New per arm: `capture_mode`, `capture_max_parallel_forks`, `paired_with`, `capture_seconds`,
`baseline_seconds`, `map_capture_modes`, `map_schema_versions`, `rows_with_rules`, `rules_basis`,
`forcing_kinds`, `rule_counts` (summed), `shares_jvm_included`, `coverage_only_included`,
`decision_included_total`, `induced_with_rules`, `induced_unseen_by_record`,
`induced_beyond_coverage` (failures no coverage-only rule selected), `induced_sole_rule` (failures
one rule alone selected, per rule), `decision_write_failure_rows`, `decision_short_rows`,
`peak_test_jvm_rss_mb` per arm, `test_jvms`, `map_bytes`, `map_raw_bytes`,
`capture_touch_all_reasons`, `evidence_bytes`, `environment`; `narrowed_target` (one value,
`mixed` or None), `narrowed_targets`, `rows_without_a_narrowed_target`, `narrowed_target_rows`
(rows that counted toward it), `recall_only`; `shared_key` (summed), `result_keys` (rows that
compared their arms, per scheme: `per-method`, `xml` and `unstamped`, never folding the third into
either), `result_keys_fallbacks` (each fallback cause, numbers blanked, with its row count). A
project whose isolated arm did not run gets an isolated line with
`status: "isolated arm: not run (...)"` and `map_workers`. Existing fields are unchanged.

`decision_vs_arm_gap` ([min, max] over rows of `decision_included_leaves - selected_distinct`, rows
without the leaf count left out) and `decision_mismatches` (rows where `decision_included` and
`selected_distinct` differ) compare two different units, so neither is a safety measure
:

- `decision_included` counts the distinct unique ids the test JVM recorded as included: one per
  test method, a parameterized template or test factory included, and, on a daemon-refused row
  only, the engine, class and runner containers too (positive).
- `selected_distinct` counts distinct `(classname, name)` keys in the selected arm's JUnit XML,
  skipped tests included: one per invocation display name. A parameterized method expands into
  several keys (negative); a class-level parameterization whose display name holds no method name
  (`formatVersion = 1`) collapses all its methods onto a few keys (positive). iceberg's +191 is
  that collapse, net of the expansion; its +499 adds the refused row's 308 containers.

The same collapsed key was what the miss check matched failures on, so on a narrowed row a failure
in an excluded method could be cleared by a selected method of the same class with the same display
name. No 0.1.0 row narrowed in such a class. Since 2026-10-08 failures are matched per method where
both builds' listener outcomes are complete, and a failure on a key that may stand for another test
is `shared_key` (Row, above); `bench/replay_shared_keys.py` applies that rule to the rows on
disk.

| chart or decision | fields |
|---|---|
| README chart 1, share distribution | rows' `selected_distinct / full_distinct`, per project |
| README chart 2, time saved vs suite length | rows' `1 - selected_seconds / full_seconds` median; header `baseline_seconds`; `TIMING_SUBSET` only (`recall_only` false) |
| summary table | `full_distinct`, median selected, median time saved, `asked`, `missed` |
| recall, missed failures with cause | `missed` and the neither-lists; `induced_rules` of each missed test |
| optimistic mode | `induced_rules`, `induced_beyond_coverage`, `induced_sole_rule`, `coverage_only_*` |
| overhead shared vs isolated | `capture_seconds` per arm over `baseline_seconds`; `capture_max_parallel_forks` |
| map and cache size | `capture_facts.map_bytes`, `map_raw_bytes`, `capture_raw_archive.bytes` |
| peak test-JVM memory with and without agent | `baseline_arm` vs `capture_arm`, `selected_arm`, `full_arm` |
| change shape, widest windows | `change_*`, `widest_windows` |
| environment | `environment`, `map_task_facts`, `test_jvms`, `capture_max_parallel_forks` |

`bench/readme_charts.py` renders both charts from the results files (a stub for the dry run;
`--sample` draws from synthetic rows and says so in the title).

## Raw evidence

Per project and arm, `bench/logs/evidence/`:

- `<project>-<arm>-capture-raw.tar.gz`, once per capture: the capture's `raw/` including `.exec`.
- `<project>-<arm>.tar.gz`, rewritten at the end of every invocation from the whole scratch (so a
  resumed sweep's archive holds every leg): logs with their `.tasks.tsv` and `.counters.tsv`,
  `decisions-*.tsv` and `.parts/`, `replay-maps/`, each row's `raw/<row>/` (`index.tsv`,
  `touches.tsv`, `overhead.txt`, markers), the loaded-class union, the results file and the map
  as it stands. Harness backup copies (`map-before-*`, `map-after-*`) are left out.

Size, measured on spring-beans (39 MB map directory, 21 rows): the map with raw records compresses
to 4.4 MB (11%), the scratch logs to 0.33 MB, one row's raw records without `.exec` to 0.24 MB. So
about 5 MB of capture archive and 6-10 MB of scratch archive per arm for a spring-beans-sized
target. Corpus-wide: map directories hold 3.6 GiB today (about 0.4 GiB compressed per arm) plus
about 0.3 MB per row, so **about 1-1.5 GiB of archives for both arms**, more if isolated raw
records are much larger (demo's were 5x the shared ones: five JVMs against one).

## Machine

`python3 bench/sweep_preflight.py --commit <commit> -- python3 bench/sweep_corpus.py ...`
refuses on: free disk below 3x `bench/logs` plus 2x the corpus map directories plus
evidence; not on mains power (`pmset -g batt` / `power_supply`); the yoriwake checkout not clean
at the commit; any Gradle daemon, test worker, Kotlin daemon, `run.py` or `sweep_corpus.py`
alive. It then runs the sweep under `caffeinate -dims` (macOS) or
`systemd-inhibit` (Linux).

## Known product defect: lost `decisions.tsv` on spring modules

**Cause.** spring-core-test's `@CompileWithForkedClassLoader` (`CompileWithForkedClassLoaderClassLoader`)
re-defines classes from the test classpath and runs the test method in a nested JUnit launcher.
Its `ServiceLoader` finds the agent's `SelectionFilter` on the system classpath and the forked
loader defines a second copy of the agent (`-Xlog:class+load` shows `SelectionFilter`,
`DecisionRecord`, `PlatformEvents`, `CaptureClaim` and `JacocoAgent` each defined five more times
via `__JVM_DefineClass__` in one spring-beans worker). Each copy has its own `DecisionRecord.WRITERS`
counter, so every copy is writer 1, write 1, and all stage `decisions.tsv.<pid>.1.1.writing` and
publish `decisions.tsv.<pid>-1.part`.

**Records are lost.** Reproduced on `:spring-beans:test` at `72bf33b`: five `NoSuchFileException`
lines for `decisions.tsv` and one for the part; the shared file declared 3 rows and held 2,216 (a
blend of two writers' bytes, read as absent), and the surviving part was a nested launcher's
3-row `selection-not-requested` record, which the harness read as the whole task's answer.
Affected logs: spring-framework (28), spring-boot (22), spring-security (24), micrometer (27).
Capture looked intact in that run (one record per test in `index.tsv`, no duplicate sequences).

**Harness mitigation.** A logged lost write makes the row's record absent
(`decision_write_failures`); `decision_covers_arm` flags a record shorter than the arm. Rules and
outcomes on those projects are then missing, not wrong. **The fix is product-side** (the agent
should not act from a copy defined by another loader, or should name its writer uniquely per
class copy), and was not made here.

## Corpus coverage of the new rules

Static evidence from the modules' sources; runtime
confirmation is `capture_touch_all_reasons` on the dry run.

| shape | projects | added |
|---|---|---|
| native libraries | kafka `clients` (zstd, snappy, lz4 tests), iceberg `core`; one mention each in grpc-java, micrometer, okio, spring-security. Netty native transport likely at runtime in armeria, grpc-java; SQLite: none | none |
| TestNG + Jupiter + Vintage in one JVM | rxjava (TestNG + Jupiter), armeria `grpc` (all three, few TestNG) | `spring-test` (3,860 tests, all three engines), borrowing spring-framework's checkout at `60e5abff7f` |
| heavy Spring context caching | spring-security `config` (22 context test files) | `spring-boot-webmvc-test` (`@WebMvcTest` slices), borrowing spring-boot's checkout at `93b23c40c28` |
| Kotlin `inline` | arrow-core, okio, anki-android, bitwarden-android, mockk, moshi, kotest, kotlinx-serialization | none |
| child processes | kafka `clients` (UtilsTest), kestra `core` (RunContextTest), okio (ZipBuilder runs `zip`), junit5 (TempDirectoryCleanupTests) | none |
| TestKit-style (`GradleRunner`) | none | proposed, not added: detekt `detekt-gradle-plugin` at `6aa07c067e`, same checkout; its suite has not been run locally and TestKit suites are long |

Both added project files are `NOT YET QUALIFIED`: run `qualify.py` on each and record the verdict
in `qualification.txt` before the dry run, or they are not swept.
