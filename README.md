# yoriwake

**yoriwake** (選り分け, *yo-ri-wa-ke*): Japanese for sorting out, picking what you need from the
rest. It runs the tests a change can affect and sets the others aside.

Predictive test selection for Gradle. Local-first, no service.

yoriwake records which classes each test executes, then on a later run skips the tests whose
recorded coverage cannot reach what you changed.

```kotlin
plugins {
    java
    jacoco
    id("io.github.zeuspizza.yoriwake") version "0.1.0"
}
```

## The ordering

1. Never skip a test that should have run. Every shape known to cause a skip forces a full run,
   whatever it costs in narrowing; what it cannot detect is listed under Limits.
2. Subject to 1, skip as many tests as possible.
3. Subject to 1 and 2, keep the overhead low.

## Who it is for

- An AI agent's or a developer's edit-test loop, on a suite that takes more than about two minutes.
- Pull-request CI, with the full suite still running after merge, nightly or before a release.

## Limits, before anything else

- **What it follows.** Coverage of every test, annotation changes, rewritten bytes, constants and
  inline bodies the compiler copied into other classes, and what a test JVM loads, defines,
  initialises or reads once for all later tests. Anything it cannot interpret -- a missing or
  unfinished map, a resource or build-script change, an observation it did not complete -- runs
  everything. A child process started from a test JVM runs every later test in that JVM, and so
  does native code, your own or a library's, from the point it is loaded or reached through the
  foreign-function API, unless it is on [the reviewed list](docs/reference.md#native-libraries),
  whose native sources were read for every file they open.
- **It can still skip a test that would have failed.** That takes a dependency it cannot see:
  state another process, a database, the network or a file keeps between test JVMs, or a reviewed
  native library handed a class file's path as data, or your own code on the test JVM's boot class
  path or in a JDK module it patches or upgrades
  ([the list](docs/reference.md#what-is-not-supported)). Keep a full run after merge or nightly; it
  catches what selection missed.
- **Tests that depend on a class without executing it are kept, at a cost.** For code a JVM runs
  once, reflection and class-file readers, every test at or after the changed class's first load,
  execution or read in the same test JVM runs. In a suite that runs in one JVM that can be most of
  it; [isolated capture](#in-ci) narrows it.
  [Details](docs/reference.md#what-coverage-does-not-record).
- **Your own JaCoCo report is empty while yoriwake records.** The agent takes JaCoCo's counters
  around every test, so the test task's own `.exec` file holds almost nothing, full runs included,
  and `jacocoTestReport`, coverage gates and uploads read close to zero. Run coverage jobs with
  `-Pyoriwake.disabled=true` until this is fixed
  ([details](docs/reference.md#what-is-not-supported)).
- **A selection share is not a speedup.** There is a fixed floor, and instrumenting the test JVM
  has a toll, so short suites get slower, not faster.
- **Selection needs the JUnit Platform.** JUnit 4 narrows only through the vintage engine; plain
  JUnit 4 and TestNG are captured but always run in full.
- **It has never run on Windows.**
- **Order-dependent tests are not protected.** Selection changes which tests run together, so a
  suite with hidden coupling can pass under selection and fail on a full run.
- **It declines some builds.** Gradle's Isolated Projects and JUnit in-JVM parallelism: every run is
  a full run, nothing is captured, and the plugin says so.

Measured figures will be published with the first release.

This is not a better version of Develocity Predictive Test Selection. That one is a hosted service
that learns from build history; this one is local, free, and decides from recorded coverage.

## Requirements

- **The Gradle daemon on JDK 21 or newer.** The plugin is compiled for Java 21.
- **Test JVMs on JDK 11 or newer.** The agent that rides in them is compiled for Java 11.
- **`git` on `PATH`.** Without it no change set can be computed, and every selecting run is a full
  run.
- **Gradle 8.14 or newer, 9.x included.** The test suite runs on 8.14 and 9.8.0. Other versions are
  untested; below 8.14 the plugin declines on a JDK 21 daemon, and on an older daemon JDK it fails
  to resolve, so the build fails.
- **A project applying `java`, an Android plugin, or Kotlin Multiplatform with a JVM target.**
  Kotlin Multiplatform is unverified: no functional test applies it yet.
  [Supported hosts](docs/reference.md#supported-hosts).

## Try it

Apply the plugin as above (the `jacoco` plugin is required; yoriwake scopes it), then:

```bash
./gradlew test                      # runs everything and captures a coverage map
./gradlew test -Pyoriwake.select    # selects against that map
```

Without `-Pyoriwake.select` nothing is ever skipped. The map lives in `.gradle/yoriwake/`, so
`clean` does not delete it.

## Ask it first

```bash
./gradlew yoriwakeAuditTest
```

`yoriwakeAudit<Task>` reads your map and reports how much of the suite a change to one class
reaches, which classes are reached so widely that touching one runs everything, and every blocker
it can establish (not on the JUnit Platform, in-JVM parallelism, classes the map has never seen),
each with its remedy or a plain "no remedy". It runs in about a second and executes no tests.

It is built to be able to tell you not to bother, and it will: most of its answers are refusals
rather than low numbers. The same data is in `audit.json` for scripts.

`yoriwakeExplain<Task>` answers the narrower question: what selection would do about the change in
front of you right now, and which changed class forces a full run if one does.

## What it prints

```
[yoriwake] :test selecting against 6aa07c0 (merge base with the branch upstream): 1 changed classes, 0 paths coverage cannot see
[yoriwake] of 593 tests discovered: reaches-change=47 not-known-to-pass=5 skipped=541
```

Every test is decided by one rule, and the run says which:

| Reason | Meaning |
|---|---|
| `reaches-change` | its recorded coverage touches a changed class |
| `shares-jvm-with-change` | it ran after its test JVM first loaded a changed class or read its file, so it may depend on work that JVM did once |
| `not-known-to-pass` | the map does not record it as having passed |
| `not-in-map` | the map has never seen it |
| `engine-runs-everything` | its engine (Kotest) runs its whole spec list, so leaving one out would run none |
| `full-run` | nothing may be skipped this run; the log says why |
| `skipped` | nothing selected it |

`.gradle/yoriwake/<task>-<hash>/decisions.tsv` has one row per test with its verdict and reason.
[What it prints](docs/reference.md#what-it-prints) and
[when it refuses to select](docs/reference.md#when-it-refuses-to-select) cover the rest.

## Pin a test, or switch it off

A test you know is order-dependent or otherwise unsafe to skip can be pinned. A pin only ever adds
tests, and a pattern that matches nothing is reported.

```kotlin
@Tag("yoriwake-always-run")                          // in the test's source
yoriwake { alwaysRun.add("com.acme.FlakyTest") }     // in the build script
```

`-Pyoriwake.alwaysRun=<glob>` does the same for one run. `-Pyoriwake.disabled`, or
`yoriwake { enabled = false }`, leaves the build entirely alone.

## In CI

A fresh checkout has no map, so every run captures and none selects unless a map is restored.
Cache `.gradle/yoriwake`, fetch enough history to reach the diff base, and let a cache miss run
everything. An old map costs speed, never safety. Recipe: [docs/reference.md#ci](docs/reference.md#ci).

A nightly or main-branch job that records the map can pass `-Pyoriwake.isolatedCapture` to give
each test class a fresh test JVM. Selection from that map narrows further; recording takes several
times longer, so raise `maxParallelForks` for that job. Selecting runs are unchanged, and one that
falls back to a full run leaves that map alone: only the recording job updates it.
[More](docs/reference.md#recording-each-test-class-in-its-own-jvm).

## More

- [docs/reference.md](docs/reference.md): how it works, every property and task, what is not
  supported, upgrading, prior art
- [docs/contract.md](docs/contract.md): the properties and files the plugin and the agent share
- [CONTRIBUTING.md](CONTRIBUTING.md): building, testing, what a change to selection must prove
- [CHANGELOG.md](CHANGELOG.md): each release, and what it does to an existing map
- [SECURITY.md](SECURITY.md): reporting a vulnerability
- [LICENSE](LICENSE): Apache-2.0; [NOTICE](NOTICE) lists what is shaded into the jars
