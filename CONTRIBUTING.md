# Contributing

yoriwake is maintained by the zeuspizza organisation and developed with AI assistance. Contributions
are welcome; reviews happen when the maintainers have time, and no review or response time is
promised.

What the tool does, what it prints and what it refuses is in [docs/reference.md](docs/reference.md)
and is not repeated here.

## Build and test

Requires JDK 21.

```bash
./gradlew build              # compiles, runs unit and functional tests, validates the plugin
python3 .github/test_ci.py   # the CI helper scripts' own tests
```

`./gradlew build` is the gate a pull request must pass. It includes the coverage gates; a change
that lowers coverage below them fails the build.

`samples/` holds small projects the functional tests and CI exercise; `fixtures/` holds test data.

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

Before merging a change to selection, the maintainers also run private mutation checks that break
each guard in turn and confirm that a test notices. They need files outside this repository, so
they are not part of the public build. If they find a guard your change left untested, you will
hear which one.

## Pull requests

- Keep a pull request to one change.
- Say in the description what the change does to an existing map: left alone, repaired on the next
  build, or discarded (see [Upgrading and your map](docs/reference.md#upgrading-and-your-map)). It
  goes into [CHANGELOG.md](CHANGELOG.md).
- No figures without the conditions that produced them: the project, the commit, what was
  measured against what.

## AI-assisted contributions

Welcome, and they must be labelled: a `Co-Authored-By` trailer or a note in the pull request saying
which tool contributed. The same bar applies as to any other change.

## Reporting a bug

Use the [bug report form](https://github.com/zeuspizza/yoriwake/issues/new?template=bug.yml). The
most useful thing it asks is whether a test that should have run did not.

Security issues go through [SECURITY.md](SECURITY.md), not the issue tracker.

## Licence

Contributions are accepted under the [Apache License 2.0](LICENSE), the project's licence.
