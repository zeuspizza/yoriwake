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

### Added

- A Maven build extension: Surefire and Failsafe record, select, observe and complement, with `yoriwake:audit` and `yoriwake:explain`. Map: new, per execution.
- Published jars build reproducibly: the same commit gives the same bytes. Map: left alone.
- README: the SLSA Build Level 2 badge, linking to how to verify a release. Map: left alone.
- `narrowPlainRunners` lets a selecting run of a plain JUnit 4 task leave out whole test classes. Map: left alone.
- `recordSequentially` records an in-JVM parallel suite one test at a time, so it can be selected. Map: left alone.
- Kover's default agent is tested beside yoriwake; the reference says what its reports show on narrowed runs. Map: left alone.
- A test declares paths it depends on beyond coverage with `@Tag("yoriwake-depends-on:<glob>")`, a task with `dependsOn`. Map: left alone.
- `pathIgnore` lists paths no test reads: changing only those runs nothing extra, and each run names them. Map: left alone.
- `largeChangeThreshold` (5,000 paths): a larger change runs everything without analysing it. Map: left alone.

### Deprecated

- `notReadByTests` warns as deprecated: `pathIgnore` replaces it and protects what a build or test reads. Map: left alone.

### Changed

- A map crosses checkouts whose only difference is `core.autocrlf`. Map: repaired on the next build if captured with CRLF.
- A suite on netty-tcnative's BoringSSL build 2.0.84 narrows, told from its OpenSSL build by the library's bytes. Map: left alone.
- A suite on Netty's macOS DNS resolver or Amazon Corretto Crypto Provider narrows as on any reviewed library. Map: left alone.
- A suite on Gradle's native-platform and file events narrows unless it makes a link or changes directory through it. Map: left alone.
- A RocksDB suite narrows on hosts whose zlib, snappy, lz4 and zstd are at a version reviewed. Map: left alone.
- A native library load is judged by the file the JDK opened; an attempt that found no file no longer widens. Map: left alone.
- A native library or foreign-function call that widens a run names the class that made it. Map: left alone.
- A run forced by untracked files names their directories and how to list them in `pathIgnore`. Map: left alone.
- The release check's long CI jobs retry a dependency download that failed in transit. Map: left alone.
- The contract lists every published token, and the reference the extension's types and defaults. Map: left alone.
- A native library is proposed, and re-reviewed on a new major version, through an issue form. Map: left alone.
- A suite on SQLite JDBC 3.53.4.0 narrows as on any reviewed library, until a connection enables extension loading. Map: left alone.
- A suite on Netty's io_uring transport 4.2.18 or 4.2.19 narrows, until it submits an operation Netty does not. Map: left alone.

### Fixed

- A capture records when a JaCoCo runtime on the test classpath (Android's `testCoverageEnabled`) hides the started agent. Map: left alone.
- A test JVM that finds parallelism the plugin missed records nothing. Map: left alone.
- A test the engine starts and never finishes runs every time and no longer discards the capture. Map: left alone.
- A trusted-map list written by an earlier yoriwake says so, rather than reading like a changed map. Map: left alone.
- A plain JUnit 4 class whose `@BeforeClass`, `@AfterClass` or class rule fails runs again on the next selecting run. Map: left alone.

## [0.3.0] - 2026-10-22

**Your map:** repaired on the next build, which runs every test once and records the environment.

### Added

- `fullRunEveryBuilds` and `fullRunEveryDays` run every test once a task has gone that long without a full run. Map: left alone; enabling a limit costs each task one full run.
- A map recorded on another JDK, OS, set of system properties or locale makes selection run everything.
  Map: repaired on the next build, which runs every test once and records the environment.
- A supported matrix in the reference: each row ran on every release's tagged commit. Map: left alone.
- Windows is supported: tested on every pull request and in the supported matrix. Map: left alone.
- Kotlin Multiplatform with a JVM target is tested; the reference lists which shapes narrow, run everything or are declined. Map: left alone.
- A test that fails just before its JVM is stopped (`--fail-fast`, or a killed worker) stays failing in the map, so it keeps running. Map: left alone.
- A class newer than the plugin or JaCoCo reads, or a test JVM newer than the agent reads, runs every test and names why (`class-file-unsupported`, `test-jvm-unsupported`). Map: left alone.
- Releases ship the files their tested build produced, signed, with provenance and an SBOM of what the jars bundle; SECURITY.md says how to check them. Map: left alone.

### Changed

- The CI reference leads with yoriwake-action; the contract lists `capture-commit`. Map: left alone.
- A build-produced classpath file that changed runs only the tests that read it, not every test. Map: repaired
  on the next build, which runs every test once where your own jars are on the test classpath.
- A read of a build-produced classpath file counts through a link, another letter case, a short name, or a mount
  or drive alias of the project. Map: left alone.
- The reference's CI recipe deletes `raw/` and `selection.tsv` before the save; its `!` paths kept nothing out. Map: left alone.
- Reference: what upgrading from 0.2.0 asks of you, in "Upgrading and your map". Map: left alone.
- Reference: how much cache the map takes per entry, and how many entries GitHub's limit holds. Map: left alone.
- Benchmarks: the 0.3.0 sweep, 0 missed of 361 induced failures. Map: left alone.

### Fixed

- A recording build hashes untracked and ignored files once, not once per test task. Map: left alone.
- A changed zip behind a launch script, an edit in a nested submodule and a `-D` in a JVM argument
  file run the tests they reach. Map: left alone; a task given a `-D` that way runs everything once.

## [0.2.0] - 2026-10-13

**Your map:** discarded; the first full run of each task rebuilds it.

### Added

- `-Pyoriwake.fullRun`, or a `yoriwake: full` line in a commit message, makes a selecting run run
  everything and record. Map: left alone.
- `yoriwake { fullRunBranches.add("main") }`: a selecting run on a listed branch runs everything and
  records, so CI can pass `-Pyoriwake.select` on every branch. Map: left alone.
- `-Pyoriwake.observe` runs every test and reports in `observation.json` what selection would have
  left out. Map: left alone.
- `-Pyoriwake.complement` runs only the tests a selecting run at the same commit left out, from the
  `selection.tsv` such a run now leaves. Map: left alone; a new file beside it.

### Changed

- A changed file nothing names (a README, a CI workflow, another module's resource) forces a full
  run unless `notReadByTests` lists it; IDE state is no change. Map: left alone.
- A run forced by an untracked file in the source tree names the file and where it came from, on
  the console and in `explain.json`. Map: left alone.
- A JUnit 4 or TestNG task off the JUnit Platform says on a selecting run that it runs every test.
  Map: left alone.
- A test that passed and failed in one capture, as a retried test can, is recorded `FLAKY` and keeps
  running. Map: left alone; a 0.1.0 audit counts `FLAKY` lines as malformed.
- The reference's GitHub workflow only records; pull requests select through yoriwake-action, which
  passes a trusted-map list. Map: left alone.

### Fixed

- A changed git submodule is in the change set even when its `ignore` setting hides it. Map: left alone.
- The audit's remedy for Kotlin classes with no SMAP no longer suggests dropping
  `-Xno-source-debug-extension`, which does not remove it. Map: left alone.
- A JUnit Platform task whose build script sets a test filter selects; its left-out tests leave the
  map. Map: such a task's is dated by its next full run; others left alone.
- Tests named with `--tests` all run on a selecting run, declined as `tests-named`. Map: left alone.
- Tests an IDE runs through Gradle all run on a selecting run, declined as `tests-named`, and no
  longer date the map under the configuration cache. Map: left alone.
- The test task's JaCoCo file holds what an instrumented run executed, so its report no longer reads
  near zero. Map: left alone.
- A generated file on the test classpath that changed since the capture runs everything, named.
  Map: repaired on the next build.
- A test task with Develocity Test Distribution or Predictive Test Selection enabled is declined,
  its map left as it was. Map: left alone.
- With `-Pyoriwake.trustedMaps=<file>`, a map narrows only when the file lists its digest. Map:
  left alone; gains `map-digest`.
- A filtered, fail-fast or interrupted run no longer updates the map, so a later revert cannot skip
  a test. Map: discarded; rebuilt by the first full run of each task.
- An edited test class JaCoCo could not instrument runs every test of it the map knows. Map: left
  alone.
- Native loads, class definitions and foreign calls by your own code on the boot class path are
  recorded. Map: discarded, with the same rebuild.
- An always-run pattern naming a class pins its nested-class tests too, and patterns now pin vintage
  tests, Spock features and test factories. Map: left alone.
- A class definition or foreign call made through an interface proxy that JDK code calls is
  recorded. Map: discarded, with the same rebuild.
- A class file your code reads while a class loader defines a class (a `getPermissions` override,
  say) dates the test by that class. Map: discarded, with the same rebuild.
- A complement run whose build generated a classpath file differently runs every test. Map: left
  alone.
- A class a test reads out of a zip, war, aar or nested jar with its own zip code selects it. Map:
  left alone; recorded from its next capture.
- An edited test class nothing names also runs every test after its own first one, and every test
  in its JVM when your code ran during discovery. Map: discarded, with the same rebuild.
- A classpath file the test task's own `doFirst` writes, or a jar only a manifest `Class-Path`
  names, is compared too, on selecting and complement runs. Map: left alone (one with such a jar
  forces once).

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

[Unreleased]: https://github.com/zeuspizza/yoriwake/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/zeuspizza/yoriwake/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/zeuspizza/yoriwake/releases/tag/v0.1.0
