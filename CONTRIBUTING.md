# Contributing

Contributions are welcome; reviews happen when the maintainers have time, and no review or response
time is promised.

What the tool does, what it prints and what it refuses is in [docs/reference.md](docs/reference.md)
and is not repeated here.

## Build and test

Requires JDK 21 to run Gradle, and JDKs 11, 17 and 25 where Gradle's toolchain detection finds
them: the functional suite runs a test JVM on each. `-Pyoriwake.functional.testJvms=21` skips the
others for a local run. CI runs each functional suite in shards by test class;
`-Pyoriwake.functional.shard=3/8` runs the same classes as its shard 3 of 8, to reproduce one.

The build runs on Gradle 8.14, the oldest Gradle the plugin supports, so the plugin compiles only
against what that Gradle offers; the 9.x line is tested through TestKit and the supported matrix.
The wrapper moves with that floor, and is revisited when Gradle 10 ends fixes for 8.x. CI turns off
setup-gradle's build capture, whose only effect here was a notice that 8.14 is out of date.

Linux jobs run `ubuntu-24.04` by name; they move to 26.04 after a `verify` and a `matrix` run on
it. setup-gradle's enhanced caching is used on purpose (free for public repositories, a cache entry
per job), so its log line is expected. Two Node deprecation notices stay until the release that
fixes each: DEP0005 from `actions/download-artifact` (actions/download-artifact#484) and DEP0040
from the setup-java that `oracle-actions/setup-java` v1.5.0 runs inside it.

```bash
./gradlew build              # compiles, runs unit and functional tests, validates the plugin
./gradlew :maven:functionalTest   # real Maven builds over fixtures, which build leaves out
python3 .github/test_ci.py   # the CI helper scripts' own tests
python3 scripts/test_central_bundle.py   # the Maven Central bundle script's own tests
python3 bench/scan_private.py --push   # before every push
python3 scripts/prove_guards.py --changed origin/main   # the guards your change can affect
```

`bench/scan_private.py --push` refuses references to the maintainers' private notes (their
identifiers, dates and documents) in what the push adds to `bench/`, `scripts/`, `docs/`,
`.github/` and this file, and in its commit messages; the `scan` workflow runs it on every pull request, its title and
body included.

`./gradlew build` and `./gradlew :maven:functionalTest` are the gate a pull request must pass. The
build includes the coverage gates; a change that lowers coverage below them fails it.

### Coverage

The plugin's gate counts the unit tests and both functional suites. The plugin's wiring and tasks
run only in the Gradle daemons TestKit starts, so the functional tasks give those daemons a copy of
the plugin's classes instrumented by JaCoCo, which report to the test JVM after every test class
(`plugin/build/jacoco/daemon-<task>/`). The agent's gate counts its unit tests and the scripted
capture JVMs of `CaptureCharacterizationTest`. Each minimum is the measured LINE ratio floored to a
whole percent, raised by the change whose tests raise it; the README badge states both, and
`.github/test_ci.py` fails when it disagrees with the build files.

`-Pyoriwake.functional.coverage=false` runs the functional suites without it, and without the gate,
for a quicker loop, though it saves little: on three functional classes run three times each way,
coverage on and off took the same time within the runs' spread (255-257 s and 256-259 s on an
Apple M1 Max). The guard harness, the matrix cells and CI's Windows jobs always run
without it, so the suites are also proved with nothing added to the daemons.

Coverage finds code no test executes. It proves no safety rule: each rule keeps its guard,
whatever its lines' coverage.

`samples/` holds small projects the functional tests and CI exercise; `fixtures/` holds test data.

`bench/` is the benchmark harness ([its README](bench/README.md)): each `bench/test_*.py` runs on its
own (`python3 bench/test_run.py`), and `python3 bench/run.py bench/projects/demo.toml` measures the
demo project, the quickest check that the harness works on a machine.

## The ordering

1. Never skip a test that should have run. Absolute.
2. Subject to 1, skip as many tests as possible.
3. Subject to 1 and 2, keep the overhead low.

A change that improves 2 or 3 and costs anything on 1 is rejected, not weighed. A skipped test that
should have run does not fail anything: it produces a smaller number and a green build, which is
what a correct selection produces too. So the bar for a change is not "the tests pass" but "if this
were wrong, what would tell us?"

## What a change to selection must prove

Any change to what is captured, what counts as changed, or what gets deselected:

- **Name how it could skip a test that should have run**, or say why it cannot. "Unlikely" is a
  yes.
- **Resolve every ambiguity toward running.** A rule that narrows on the absence of evidence must
  establish each of its conditions and force a full run the moment one cannot be established.
- **Come with a test that fails without the change.** Reintroduce the bug, watch the test fail,
  then apply the fix. A test that has never failed has not been shown to detect anything.
- **Keep empty and unknown apart.** "Nothing changed" may license a skip; "could not find out" must
  force a full run.
- **Be true on another OS and another repository layout.** A change set is input and need not come
  from the machine reading it; a Gradle project's name is not its directory.

A change that makes selection narrower deserves more suspicion, not less: fixes for silent skips
make the numbers worse.

## The guards

Each rule that keeps a test from being skipped has a guard: an entry in `scripts/prove_guards.py`
that puts the defect back into the source (a literal fragment and what replaces it), runs one
suite, and expects one named test to fail. A guard that no test catches means the rule is
unprotected, however green the build is.

```bash
python3 scripts/prove_guards.py --mode fast             # the cheap suites
python3 scripts/prove_guards.py --changed origin/main   # every guard your change can affect
python3 scripts/prove_guards.py --guard "<label>"       # one guard
python3 scripts/prove_guards.py --mode full --shard 3/8 # CI's shard 3 of 8 of every guard, to reproduce it
python3 scripts/prove_guards.py                         # every guard; the gate for plugin/ and agent/
python3 scripts/test_prove_guards.py                    # the harness's own tests, every anchor included
```

Each run prints one line per guard and a closing line saying how many it attempted out of how
many: `fired` with the test that failed is a pass; `NOTHING CAUGHT IT`, `this proves a different
test` and `NOT PROVED` (the suite broke, or failed before any mutation) fail the run. By default the
guards run in four worktrees under `sandbox/prove-guards/` and never write your tree; with
`--jobs 1` they mutate your tree in place, so do not edit while one runs.

On a pull request, CI runs fast mode and the guards the change can affect; a change to
`plugin/src/main`, `core/src/main`, `agent/src/main` or `maven/src/main` runs every guard instead,
and every guard runs on dispatch in each release check. Each of these runs in 8 shards of its selected guards, balanced by measured cost with a functional
class's guards together (`--shard I/8`, with the same `--mode` and `--changed` as the job,
reproduces one), and a check job under the job's name fails unless every guard
the run selects fired in exactly one shard. A separate check fails a pull request that removes a
guard, or changes its target, fragment, suite or expected test, or takes a suite out of fast mode,
until a maintainer approves it.

**A new rule comes with its guard.** Add the entry to `MUTATIONS`: a label that says what breaks,
the source, a fragment that appears exactly once in it, its replacement, the suite, and a phrase
from the test that must fail (from its name for a Gradle suite, from its printed `FAIL:` line for a
Python one). Then run `--guard "<label>"` and watch it fire, before and after the rule lands.

## Pull requests

- Keep a pull request to one change.
- Say in the description what the change does to an existing map: left alone, repaired on the next
  build, or discarded (see [Upgrading and your map](docs/reference.md#upgrading-and-your-map)). It
  goes into [CHANGELOG.md](CHANGELOG.md), under `## [Unreleased]`; CI fails a line added under a
  version already released.
- No figures without the conditions that produced them: the project, the commit, what was
  measured against what.

## AI-assisted contributions

Welcome, and they must be labelled: a `Co-Authored-By` trailer or a note in the pull request saying
which tool contributed. The same bar applies as to any other change.

## Reporting a bug

Use the [bug report form](https://github.com/zeuspizza/yoriwake/issues/new?template=bug.yml). The
most useful thing it asks is whether a test that should have run did not.

Security issues go through [SECURITY.md](SECURITY.md), not the issue tracker.

## Native libraries

A library joins the [reviewed list](docs/reference.md#native-libraries) through the
[native library proposal](https://github.com/zeuspizza/yoriwake/issues/new?template=native-library.yml)
form. Each release compares every listed library with its latest major version
([RELEASING.md](RELEASING.md)), and one that moved gets a re-review issue. A re-review answers the
form's questions again against the new major's native sources, then records one outcome:

- **keep**: nothing the native code opens, or how its path is chosen, changed; the reviewed version
  in the reference moves to the new one.
- **update**: the entry still holds with a change, such as a new loading class or file-name pattern
  in `ReviewedLibraries`; the reference's row says what the native code now opens, and its reviewed
  version and source link move to the new one.
- **remove**: the new version opens a path that can name a class file or a jar, other than one a
  test passes as data; the entry goes, and that library applies the wide rule from the release that
  drops it.

A keep only records the review, and a removal only widens selection. An update changes what is
trusted, so it is a change to selection and meets
[what a change to selection must prove](#what-a-change-to-selection-must-prove).

## Releases

How a version is cut, and how a fix reaches you: [RELEASING.md](RELEASING.md).

## Licence

Contributions are accepted under the [Apache License 2.0](LICENSE), the project's licence.
