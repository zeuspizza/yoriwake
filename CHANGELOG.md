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

### Changed

- A JUnit 4 or TestNG task off the JUnit Platform now says on a selecting run that it runs every
  test. Before, it said selection was class-granular and that coverage was captured either way;
  neither was so. **Your map:** left alone.

### Fixed

- A filtered (`--tests`, IDE runs, a build-script filter), fail-fast or interrupted run no longer
  updates the map. Before, it merged its records without moving the map's commit, so a later revert
  of what it saw could skip a test. It keeps only the failures it saw, as failed. A capture during
  which HEAD or its reflog moves, or a tracked file is touched, is caught too: HEAD and the tree are
  read before compilation, and a path touched during the capture stays in the change set until the
  next one. A file the tests create outside the build directory now forces a full run on a fresh
  checkout, such as a CI runner's. **Your map:** discarded. A map written by 0.1.0 is rebuilt by the
  first full run of each task.
- An edited test class that JaCoCo could not instrument (a method over the JVM's 64 KiB limit once
  instrumented, or class files newer than the JaCoCo release reads) now runs every test of it the
  map knows; before, a test the edit made fail could be skipped. Decision records name the rule
  `own-class-changed`. **Your map:** left alone.

## [0.1.0] - 2026-10-04

First release. **Your map:** none exists yet; the first full run records one.

### Added

- Gradle plugin `io.github.zeuspizza.yoriwake`: records per-test coverage with JaCoCo and a Java
  agent, and with `-Pyoriwake.select` runs only the tests a change can reach. Selection works on the
  JUnit Platform (JUnit 5, JUnit 4 through the vintage engine, Spock 2); Kotest 5 specs always run,
  and plain JUnit 4 and TestNG are recorded but run in full.
- Java and Android modules, and Kotlin Multiplatform JVM targets (unverified)
  ([supported hosts](docs/reference.md#supported-hosts)); Gradle 8.14 and 9.x, a Gradle daemon on
  JDK 21+, test JVMs on JDK 11+.
- Runs everything whenever it cannot prove a narrower run is safe, and says why. The rules are in
  [the reference](docs/reference.md#when-it-refuses-to-select); the limits are in
  [what is not supported](docs/reference.md#what-is-not-supported).
- `yoriwakeAudit<Task>` and `yoriwakeExplain<Task>`, which run no tests; per-test decision records
  in `decisions.tsv`; `audit.json` and `explain.json`.
- Pinning with `@Tag("yoriwake-always-run")`, `yoriwake { alwaysRun }` and `-Pyoriwake.alwaysRun`.
- `-Pyoriwake.isolatedCapture`, which records the map with a fresh test JVM per test class.

[Unreleased]: https://github.com/zeuspizza/yoriwake/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/zeuspizza/yoriwake/releases/tag/v0.1.0
