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
