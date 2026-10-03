# Changelog

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Every release entry states what it does to a coverage map you already have, as one of:

- **left alone**: the map keeps working unchanged;
- **repaired on the next build**: the first build selects wider than usual and re-records what it
  needs, the second is normal;
- **discarded**: the format version was bumped, the map is refused, and the next full run rebuilds
  it.

See [Upgrading and your map](docs/reference.md#upgrading-and-your-map).

## [Unreleased]

First public release, 0.1.0. Renamed from an internal prototype; no earlier version was published.

**Your map:** no earlier release exists. A map written by a pre-release build is in a different
directory and is not read; the first full run captures a new one in `.gradle/yoriwake/`.

### Added

- Gradle plugin `io.github.zeuspizza.yoriwake`: per-test coverage capture through JaCoCo and a Java
  agent, and selection with `-Pyoriwake.select` on the JUnit Platform (JUnit 5, JUnit 4 through the
  vintage engine, Spock 2). Kotest 5 specs are captured and always run under selection.
- Attaches to projects applying `java`, `com.android.application`, `com.android.library`,
  `com.android.test` or `com.android.dynamic-feature`, and to Kotlin Multiplatform modules with a
  JVM target (unverified: no functional test applies Kotlin Multiplatform yet). See
  [supported hosts](docs/reference.md#supported-hosts).
- Capture without selection for plain JUnit 4 and TestNG.
- `-Pyoriwake.isolatedCapture`, which records the map with a fresh test JVM per test class.
  `yoriwakeExplain<Task>` says whether a map was recorded `isolated`, `shared` or `mixed`.
- `yoriwakeAudit<Task>` and `yoriwakeExplain<Task>`, which run no tests.
- Per-test decision records in `decisions.tsv`, and machine-readable `audit.json` and
  `explain.json`. After the rows, `#+` rule lines name every rule that would run each test, and the
  `rules` note says what they were computed from.
- Pinning with `@Tag("yoriwake-always-run")`, `yoriwake { alwaysRun }` and `-Pyoriwake.alwaysRun`.
- Rules that keep a test selected where its coverage alone would let it be skipped:
  - a changed annotation forces a full run, except in a test class this task runs that declares a
    test;
  - a class whose compiled bytes changed with no source change selects every test that executed
    it, and forces a full run when none did;
  - a changed class selects every test that ran at or after its test JVM first loaded or executed
    it, or read its class or source file. An edited test class is dated only by what reaches it
    from outside its own tests;
  - child processes, classes defined by your own class loaders or as hidden classes, and native
    libraries are observed, or count as every class touched from that point. Native libraries on
    a reviewed list are the exception;
  - a nested JUnit launcher, and a copy of the agent defined by another class loader, never select,
    capture or write a decision;
  - a selecting run that falls back to a full run leaves a map recorded in isolation as it is;
  - a capture that dates the map drops the records of tests it did not report, such as a deleted
    test or one under a class whose setup failed, and records every test of a failed class
    container as failed, and a test template or factory that failed with no invocation as failed
    itself. A test with an unreadable coverage record is recorded as not known to pass;
  - a run that leaves tests out by tag, engine, category or group dates the map without the tests
    it left out, which then run as not in the map, and never stands for the whole task.
- Declines by name, rather than failing the build, under Gradle Isolated Projects and in-JVM test
  parallelism, and on a test task found at execution without the agent on its classpath or with its
  map directory overwritten. Each runs every test.
- A selecting task whose working tree changed after the build was configured, such as by a task
  earlier in the same build, runs every test (`change-set-stale`).
- Supported on Gradle 8.14 and 9.x, with the Gradle daemon on JDK 21 or newer and test JVMs on JDK
  11 or newer.
- Map format version 6.

### Changed

- A selecting run lists the working tree's untracked and ignored files once per build while
  configuring, instead of once per test task. On builds with hundreds of test tasks and many ignored
  files this took minutes: configuring a selecting run of spring-boot's `:core:spring-boot:test`
  took 668 s with the plugin at `9d396ab` and 11 s at `c7e17cc` (median of three interleaved runs
  each, 2026-10-03). What it selects is unchanged. **Your map:** left alone.
