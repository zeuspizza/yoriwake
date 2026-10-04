# Benchmarks

One sweep of 34 open-source projects from 28 codebases, with the plugin at
[`30be0e8`](https://github.com/zeuspizza/yoriwake/commit/30be0e8) (selector digest `3bbf0076039e`),
2026-10-01 10:16 to 2026-10-03 18:03 CEST, on one Apple M1 Max (10 cores, 64 GiB, macOS 15.4, then
27.0.1). Each change is a generated edit to one source file, run once with selection and, where
needed, once in full.

- **Recall:** 0 missed of 471 failures the edits induced, across the 35 changes where a miss was
  possible, in 14 projects from 12 codebases.
- **Narrowing:** 69 of 426 changes (16%) ran under 100% of the suite; the median of those ran 95%
  of it. 16 of the 32 projects with selectable changes (README chart 1, 27 codebases) narrowed at
  least once. In 26 of the 32, and corpus-wide, the median change ran 100%.

Per project, sorted by how much of the suite its changes ran on average, lowest first. The columns
sum to the headlines; the raw results files are not published.

| project | tests | changes | forced | ran under 100% | median % of the suite run | under 100%: min–median | miss-possible changes | missed of induced | note |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| apache-groovy | 1,102 | 7 | 0 | 7 | 68% | 33%–68% | 4 | 0 of 20 |  |
| detekt | 2,259 | 13 | 7 | 6 | 100% | 26%–36% | 4 | 0 of 30 |  |
| okio | 5,086 | 5 | 2 | 1 | 100% | 15% | 1 | 0 of 6 | +2 stalled |
| spring-framework | 2,194 | 13 | 5 | 7 | 98% | 58%–75% | 4 | 0 of 19 |  |
| ktlint | 2,336 | 7 | 3 | 4 | 97% | 62%–72% | 4 | 0 of 138 |  |
| okhttp | 112 | 7 | 4 | 3 | 100% | 27%–98.2% | 3 | 0 of 13 |  |
| opentelemetry-java | 272 | 11 | 3 | 4 | 100% | 15%–97% | 2 | 0 of 2 |  |
| resilience4j | 237 | 8 | 2 | 2 | 100% | 76% | 1 | 0 of 1 |  |
| grpc-java | 1,536 | 16 | 8 | 3 | 100% | 51%–86% | 1 | 0 of 4 |  |
| spring-boot-webmvc-test | 64 | 6 | 2 | 4 | 95% | 95% | – | – | +2 stalled while configuring |
| gradle | 179 | 16 | 15 | 1 | 100% | 70% | – | – | forced: startup coverage |
| rxjava | 13,921 | 16 | 2 | 14 | 99.5% | 98.5%–99.3% | 4 | 0 of 64 |  |
| moshi | 1,292 | 27 | 13 | 1 | 100% | 93% | 1 | 0 of 55 | +3 stalled |
| spring-boot | 3,682 | 13 | 2 | 7 | 99.7% | 99.7% | 4 | 0 of 114 |  |
| spring-security | 3,644 | 16 | 5 | 4 | 100% | 99.9% | 1 | 0 of 2 |  |
| bitwarden-android | 7,341 | 16 | 9 | 1 | 100% | 99.9% | 1 | 0 of 3 |  |
| armeria | 698 | 13 | 13 | 0 | 100% | – | – | – | forced: a file its build regenerates from git state |
| arrow | 644 | 3 | 3 | 0 | 100% | – | – | – | 10 of 13 edits did not compile; 3 forced |
| detekt-junk | 2,259 | 6 | 6 | 0 | 100% | – | – | – | one uncovered file: every change refused |
| duckduckgo-android | 6,349 | 16 | 16 | 0 | 100% | – | – | – | forced: uncovered classes, constants |
| glide | 1,523 | 32 | 0 | 0 | 100% | – | – | – | plain JUnit 4: always runs in full |
| grails-core | 641 | 16 | 16 | 0 | 100% | – | – | – | forced: Groovy paths coverage cannot map |
| iceberg | 3,937 | 15 | 15 | 0 | 100% | – | – | – | forced: its tests write `core/derby.log`; +1 did not build |
| junit5 | 3,043 | 16 | 15 | 0 | 100% | – | – | – | forced: startup coverage |
| kafka | 11,251 | 21 | 8 | 0 | 100% | – | – | – | decided narrower, kept every test |
| kestra | 663 | 9 | 0 | 0 | 100% | – | – | – | Gradle 8.7: plugin declines |
| kotlinx-serialization | 80 | 2 | 0 | 0 | 100% | – | – | – | Gradle 8.7: plugin declines |
| micrometer | 1,013 | 16 | 15 | 0 | 100% | – | – | – | forced: uncovered classes |
| micronaut-core | 7,374 | 16 | 5 | 0 | 100% | – | – | – | decided narrower, kept every test |
| mockito | 2,356 | 16 | 4 | 0 | 100% | – | – | – | decided narrower, kept every test |
| mockito-junit4 | 2,356 | 16 | 0 | 0 | 100% | – | – | – | plain JUnit 4: always runs in full |
| mockk | 743 | 16 | 8 | 0 | 100% | – | – | – | decided narrower, kept every test |
| kotest | – | 0 | 0 | 0 | – | – | – | – | no editable target: no change made |
| spring-test | 3,860 | (8) | 8 | – | – | – | – | – | refused: build-script test filter |
| **total, 34 projects** | | **426** | **214** | **69** | **100%** | **15%–95%** | **35** | **0 of 471** | |
| demo (fixture) | 11 | 10 | 6 | 1 | 100% | 73% | 1 | 0 of 1 | not counted |
| demo-android (fixture) | 6 | 6 | 0 | 5 | 67% | 33%–67% | 3 | 0 of 3 | not counted |
| demo-kotlin (fixture) | 9 | 8 | 4 | 2 | 100% | 44%–61% | 2 | 0 of 2 | not counted |

- **forced:** a full run the selector chose (a change it could not prove safe, or a refusal). All
  214 ran everything and cannot miss, so they are not in the recall's denominator; counting them
  would make recall improve whenever narrowing got worse.
- **decided narrower, kept every test:** the selector decided to narrow and its shared-JVM rules
  then kept every test.
- **spring-test** is out of the selection columns. Its build script sets
  `filter.excludeTestsMatching("*TestCase")`, and the 0.1.0 plugin never stamps a map recorded under
  a build-script test filter, so all 8 changes were refused. It carries no narrowing evidence; its 8
  are among the 214 forced.
- **Fixtures** (this repository's own sample builds) are shown and counted in no figure: not in the
  projects, the codebases, the charts, the recall or the recording overhead.
- Not swept: anki-android (JUnit in-JVM parallelism, which the plugin declines) and kafka-core
  (fails tests with nothing mutated; kafka's `clients` stands for the codebase). Did not qualify at
  `08369f5`, an earlier commit: caffeine, geode, hibernate-orm, kotlinx-coroutines.

## Method

A **change** is one generated edit to one production source file (a method body, a constant, a
rename, a signature), reverted afterwards. The **selected run** is `test -Pyoriwake.select` against
the recorded map, what a user gets. The **full run** runs every test with the same edit; its
failures are the ground truth.

- **Sample.** Edits are drawn per project in a fixed reach-stratified order (`reach-stratified/v1`,
  seed `20260906`). An edit that does not compile is rejected and the next taken. A project stops at
  4 changes that narrowed and induced a failure, or at its cap (8 to 32 edits). The rule never reads
  whether a failure was missed.
- **Forced changes run once.** Outside the timing projects, a selected run of the whole suite is its
  own ground truth.
- **Timing projects.** Both runs are timed on every change of six projects chosen in advance to span
  suite length: grpc-java, apache-groovy, mockito, spring-boot, rxjava, bitwarden-android.

### Recall

A miss is possible only on a change that both ran under 100% and induced a failure. Before the
question is asked, failures that also fail without the edit, that the two runs name differently, or
that fail at container level are set aside and counted: 11 tests already failed with nothing
mutated (glide 1, grpc-java 2, kestra 4, resilience4j 1, spring-security 3); of the rest, 0 were set
aside of each other kind.

A failure is matched by test class and display name, which parameterized methods of one class can
share, so a skipped failure could read as caught through a selected sibling. None of the 180
narrowed changes at `30be0e8` (both recordings, fixtures included) had a failing key shared by a
selected and a skipped test. The same matching makes the induced count a lower bound.

Recall is the only figure that survives a change of machine. It is a sample: edits of the shapes the
generator makes, in the modules listed. A dependency yoriwake cannot see is not in it by
construction; see the [reference](reference.md#what-is-not-supported).

The share of the suite run is a property of the map and the change, the same on any machine. It says
whether a change narrows and by how much, never how much faster.

## Time saved

`1 - selected seconds / full seconds`, median per project, timing projects only, both runs on one
machine minutes apart. The full run's seconds sit beside each ratio, because a ratio does not
travel: a faster machine shrinks the tests, not the fixed floor of configuration, compilation and
JVM start. The suite length on chart 2 is not a predictor: across earlier measurements the overhead
tracked how expensive a project is to configure.

| project | tests | full run | tests run per change (median) | time saved per change (median) | changes |
|---|---:|---:|---:|---:|---:|
| grpc-java | 1,536 | 6.5 s | 1,536 (100%) | −2% | 16 (8 forced) |
| apache-groovy | 1,102 | 27.1 s | 747 (68%) | +22% | 7 (0 forced) |
| mockito | 2,356 | 48.6 s | 2,356 (100%) | +7% | 16 (4 forced) |
| spring-boot | 3,682 | 92.7 s | 3,672 (99.7%) | −431% | 13 (2 forced) |
| rxjava | 13,921 | 177.6 s | 13,847 (99.5%) | +9% | 16 (2 forced) |
| bitwarden-android | 7,341 | 591.3 s | 7,341 (100%) | −1% | 16 (9 forced) |

- A ratio near zero is not a saving: mockito ran every test on every change and read +7%.
- **spring-boot's −431%** is configuration. Its selecting runs spent a median 404 s configuring
  the plugin against a 93 s full run. The sweep's plugin re-read the working tree's untracked and
  ignored files once per test task while configuring. spring-boot has 527 test tasks and about
  250,000 ignored files, mostly TestKit output under `buildSrc/build` that piled up as the sweep ran
  (333 s to 462 s over its 13 changes). git took about 6 s of it; recording and full runs configured
  in about a second. armeria spent 55 s the same way (`node_modules`); every other project stayed
  under 8 s. spring-boot-webmvc-test shares the checkout (median 588 s); 2 of its changes, and 6 in
  its isolated recording, were selecting runs killed after 10 minutes without output, while
  configuring.

### The released plugin

The sweep ran the plugin at `30be0e8`. The 0.1.0 release is a later commit, and it is not the same
code: a selecting run now lists the working tree's untracked and ignored files once per build while
configuring, instead of once per test task. Only that listing and its wiring changed in the plugin;
the agent is untouched.

- **Selections are identical.** The new listing was tested against the sweep's as an oracle on
  generated trees, and on spring-boot and armeria both plugins wrote identical decision records for
  the same builds, an edited source included. The record stays with the harness.
- **Configuration time fell:** spring-boot's `:core:spring-boot:test` from a median 668 s to 11.4 s,
  armeria's `:grpc:test` from 42.9 s to 2.6 s, measured on 2026-10-04 on a loaded machine for the
  diagnosis. They are not a published figure.
- **Each executing test task still lists the tree once with git**, about 9 s on spring-boot; see the
  [reference](reference.md#what-is-not-supported).

Every figure on this page describes the sweep at `30be0e8`. Shares and recall carry over to the
release, because its selections are identical; the times do not.

## Recording overhead

The recording run against an uninstrumented run of the same suite: from nothing measurable to +140%
(moshi, a 5-second suite) across 32 projects, median +16%. One recording each; read it as an order
of magnitude. Below zero is one recording's noise: detekt's 2,259-test suite read +16% in its own
recording and below zero in detekt-junk's. kestra and kotlinx-serialization recorded nothing. To
measure your own build, see [the audit's payback question](reference.md#properties-and-tasks).

| project | uninstrumented suite | recording run | overhead |
|---|---:|---:|---:|
| apache-groovy | 36.8 s | 49.6 s | +35% |
| armeria | 346.7 s | 369.7 s | +7% |
| arrow | 57.9 s | 81.7 s | +41% |
| bitwarden-android | 504.3 s | 548.3 s | +9% |
| detekt | 58.2 s | 67.7 s | +16% |
| detekt-junk | 54.2 s | 50.7 s | below zero |
| duckduckgo-android | 417.5 s | 436.6 s | +5% |
| glide | 33.8 s | 49.1 s | +45% |
| gradle | 64.1 s | 123.6 s | +93% |
| grails-core | 48.8 s | 62.9 s | +29% |
| grpc-java | 19.8 s | 23.1 s | +17% |
| iceberg | 1058.1 s | 1072.0 s | +1% |
| junit5 | 51.2 s | 72.0 s | +41% |
| kafka | 139.2 s | 154.0 s | +11% |
| kestra | 160.3 s | 151.5 s | not recorded (declined) |
| kotest | 58.5 s | 89.6 s | +53% |
| kotlinx-serialization | 11.8 s | 17.9 s | not recorded (declined) |
| ktlint | 14.7 s | 26.1 s | +78% |
| micrometer | 70.5 s | 77.4 s | +10% |
| micronaut-core | 60.3 s | 82.9 s | +37% |
| mockito | 64.4 s | 74.7 s | +16% |
| mockito-junit4 | 63.8 s | 66.6 s | +4% |
| mockk | 140.9 s | 178.1 s | +26% |
| moshi | 5.0 s | 12.0 s | +140% |
| okhttp | 12.5 s | 19.9 s | +59% |
| okio | 170.9 s | 196.6 s | +15% |
| opentelemetry-java | 58.2 s | 61.0 s | +5% |
| resilience4j | 14.6 s | 14.2 s | below zero |
| rxjava | 185.5 s | 189.1 s | +2% |
| spring-boot | 94.5 s | 114.0 s | +21% |
| spring-boot-webmvc-test | 73.2 s | 84.1 s | +15% |
| spring-framework | 36.2 s | 47.5 s | +31% |
| spring-security | 181.7 s | 188.5 s | +4% |
| spring-test | 93.5 s | 108.0 s | +16% |
| **median, 32 projects** | | | **+16%** |

## The corpus

A codebase is one upstream repository; projects drawn from the same one count once. The pool is
**libraries-first**: two Android applications and no server-side application yet.

| project | codebase | module | kind | upstream commit | Gradle | daemon | load average |
|---|---|---|---|---|---|---|---|
| apache-groovy | Apache Groovy | `groovy-json` | language | `6e21db629c` | 9.7.1 | `-Xmx4g` | 13.7 to 23.84 |
| armeria | Armeria | `grpc` | library | `f3c55ee0d3` | 9.6.1 | JDK 25, `-Xmx4g` | 4.71 to 13.66 |
| arrow | Arrow | `arrow-core` (multiplatform) | library | `6ab9df9a50` | 9.7.1 | `-Xmx4g` | 16.63 to 20.45 |
| bitwarden-android | Bitwarden | `app` | Android application | `65bab9f27a` | 9.7.1 | `-Xmx4g` | 3.06 to 8.4 |
| detekt | detekt | `detekt-rules-style` | library | `6aa07c067e` | 9.7.1 | default | 9.7 to 20.88 |
| detekt-junk | detekt | `detekt-rules-style`, scoped to one uncovered file | library | `6aa07c067e` | 9.7.1 | default | 13.51 to 15.82 |
| duckduckgo-android | DuckDuckGo | `app` | Android application | `07c41b9c65` | 8.14.4 | `-Xmx4g` | 4.57 to 8 |
| glide | Glide | `library` | Android library | `83f3b67128` | 9.4.1 | `-Xmx4g` | 6.04 to 12.88 |
| gradle | Gradle | `files` | build tool | `0f58e631c4` | its own | `-Xmx6g` | 4.22 to 7.38 |
| grails-core | Grails | `grails-core` | framework | `a5758d657f` | 9.7.1 | `-Xmx4g` | 4.47 to 8.86 |
| grpc-java | gRPC | `grpc-core` | library | `fc4314419d` | 8.14.5 | `-Xmx4g` | 3.41 to 4.63 |
| iceberg | Iceberg | `iceberg-core` | library | `9299fe6926` | 8.14.5 | `-Xmx4g` | 2.72 to 5.65 |
| junit5 | JUnit | `jupiter-tests` | library | `35c56a8e02` | 9.7.1 | `-Xmx4g` | 9.03 to 657.51 |
| kafka | Kafka | `clients` | library | `54a57d7c70` | 9.7.1 | `-Xmx4g` | 17.98 to 97.15 |
| kestra | Kestra | `core` | library (the server's `core` module) | `c11fe3466f` | 8.7 | default | 3.08 to 13.02 |
| kotest | Kotest | `kotest-framework-engine` | library | `f67085473c` | 9.4.1 | `-Xmx4g` | not recorded (no change made) |
| kotlinx-serialization | kotlinx.serialization | `kotlinx-serialization-hocon` | library | `397bb56009` | 8.7 | `-Xmx4g` | 10.76 to 13.57 |
| ktlint | ktlint | `ktlint-ruleset-standard` | library | `2b6028e450` | 9.7.1 | JDK 26 | 19.14 to 31.15 |
| micrometer | Micrometer | `micrometer-core` | library | `7efb0bcfb7` | 9.7.1 | `-Xmx4g` | 3.37 to 13.67 |
| micronaut-core | Micronaut | `micronaut-core` | framework | `8f7a86f21b` | 9.7.1 | JDK 25, `-Xmx4g` | 5.07 to 8.64 |
| mockito | Mockito | `mockito-core` | library | `5a676bcd9e` | 8.14.2 | default | 3.91 to 10 |
| mockito-junit4 | Mockito | `mockito-core`, plain JUnit 4 | library | `5a676bcd9e` | 8.14.2 | default | 3.68 to 15.39 |
| mockk | MockK | `mockk` (multiplatform) | library | `57dd664b47` | 9.3.0 | `-Xmx4g` | 46.81 to 210.1 |
| moshi | Moshi | `moshi` | library | `889013ec2e` | 9.5.1 | `-Xmx4g` | 2.54 to 6.22 |
| okhttp | OkHttp | `okhttp-tls` | library | `c467a32e88` | 9.6.1 | default | 6.33 to 7.96 |
| okio | Okio | `okio` (multiplatform) | library | `e8dad1a9a8` | 9.7.1 | default | 2.19 to 3.16 |
| opentelemetry-java | OpenTelemetry | `sdk:trace` | library | `77b1ac4141` | 9.7.1 | default | 2.38 to 4.54 |
| resilience4j | Resilience4j | `resilience4j-circuitbreaker` | library | `a8a3316425` | 9.4.1 | default | 3.38 to 4.77 |
| rxjava | RxJava | the whole build | library | `947e681bca` | 9.7.1 | `-Xmx2g` | 4.12 to 9.97 |
| spring-boot | Spring | `core:spring-boot` | library | `93b23c40c2` | 9.7.0 | JDK 25, `-Xmx4g` | 3.14 to 5.03 |
| spring-boot-webmvc-test | Spring | `spring-boot-webmvc-test` (context slices) | library | `93b23c40c2` | 9.7.0 | JDK 25, `-Xmx4g` | 3.42 to 6.1 |
| spring-framework | Spring | `spring-beans` | library | `60e5abff7f` | 9.7.1 | `-Xmx4g` | 4.85 to 7.82 |
| spring-security | Spring | `spring-security-config` | library | `dec6e6eb6d` | 9.7.1 | `-Xmx4g` | 3.22 to 6.83 |
| spring-test | Spring | `spring-test` (three engines in one JVM) | library | `60e5abff7f` | 9.7.1 | `-Xmx4g` | 4.19 to 6.05 |
| demo (fixture) | – | this repository's own sample build | fixture | this repository | 8.14 | default | 83.87 to 87.83 |
| demo-android (fixture) | – | this repository's own sample build (Android) | fixture | this repository | 8.14 | default | 41.16 to 56.18 |
| demo-kotlin (fixture) | – | this repository's own sample build (Kotlin) | fixture | this repository | 8.14 | default | 21.04 to 22.28 |

## Caveats

- **One machine, one sweep, one commit.** Nothing was repeated on Linux or Windows.
- **The operating system changed partway**, from macOS 15.4 to 27.0.1 between projects, at the same
  commit. apache-groovy, armeria and arrow ran on 15.4, the rest on 27.0.1. No project's runs
  straddle it. bitwarden-android's 4 changes from before were set aside and the project re-run
  whole. Times from different projects do cross it (apache-groovy is a timing project on 15.4).
- **Load average** is context for the seconds, not for a share or a recall count. It stayed above
  the 10 cores throughout apache-groovy (a timing project), arrow, detekt-junk, kafka,
  kotlinx-serialization, ktlint, mockk and the fixtures, and peaked at 657.51 during junit5, 210.1
  during mockk and 97.15 during kafka.
- **Builds not run exactly as checked out.** grpc-java, mockito, moshi and kotlinx-serialization run
  JUnit 4 through the vintage engine via an init script. gradle and junit5:
  `-Dorg.gradle.isolated-projects=false` (gradle also `--dependency-verification=off`). iceberg and
  duckduckgo-android: `--no-configure-on-demand` (duckduckgo-android with five source files staged).
  glide: an unsupported internal setting, on a plain JUnit 4
  suite that runs in full anyway. armeria `-PnoLint`; detekt and detekt-junk `-PenablePTS=false`;
  kafka `-x checkstyleMain -x spotbugsMain`; micronaut-core `-Pmicronaut.jacoco.enabled=true`.
- **Edits are generated**, small and single-file; a real change set is usually wider and runs more
  tests. Each project measures one module, chosen to build hermetically, not to narrow.
- **Decision records short.** 158 changes in 15 projects carry a record covering fewer tests than
  ran (armeria 13, bitwarden-android 9, gradle 16, grails-core 16, junit5 11, kafka 21, ktlint 4,
  micrometer 13, micronaut-core 16, mockk 1, opentelemetry-java 6, resilience4j 4, rxjava 8,
  spring-framework 4, spring-security 16): rules missing, not wrong. Shares and recall read the
  tests each run ran, not the record.
- **No figure from the isolated recording is published.** It was recorded with
  `-Pyoriwake.isolatedCapture` and forks raised, against a baseline from another run, and its recall
  is judged against the shared full run. It reports 3 failures its selected run did not run; none is
  a selection miss. micrometer's `JvmGcMetricsTest::gcMetricsAvailableAfterGc` was excluded with no
  recorded edge to the change, passes alone with and without it, and has passed and failed identical
  full runs before: a timing flake the harness counts by design. mockk's 2 are a harness defect (two
  mutations share a label, so the row was judged against the other's failures; the 2 names are JUnit
  `executionError` class entries on a row that ran every test).
- **Limits found after the sweep are outside this sample**, because no test class or build step was
  edited: an edited test class JaCoCo could not instrument, and a file a build step generates from
  git state. Both are in the [reference](reference.md#what-is-not-supported).
- A figure from an earlier plugin commit is not comparable and is not quoted.
