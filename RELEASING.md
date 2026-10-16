# Releasing

How a version of yoriwake is cut and published, and how a fix reaches you. Every release is cut by
following this file step by step; a step that turns out wrong is fixed here, not worked around.

## What a version means

Versions follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html). Before 1.0.0 a minor
version may change behaviour, and each [CHANGELOG.md](CHANGELOG.md) entry says what changed and
what it does to a coverage map you already have. A policy for what a major, minor and patch may
change after 1.0.0 is linked here once it exists.

## Cadence

There is no release calendar. Releases follow these rules:

- **A test that should have run and did not.** As soon as such a silent skip is confirmed, the safe
  step (run without `-Pyoriwake.select`) is published as an advisory naming the affected releases,
  before any fix exists. The fix then ships as a patch on its release line, as soon as the
  maintainer can and ahead of other work, often within days. No window is promised.
- **Everything else.** Minor versions ship when ready, at most 8 weeks apart while
  `## [Unreleased]` in the changelog holds a user-visible change.

yoriwake is an actively maintained free-time project: no response time and no support window are
promised.

## Which release gets fixes

The latest minor release, through a patch on its release line. Older lines get no fixes; upgrade to
the latest release.

## The candidate

A release candidate is a commit, never a tag. Only the final step tags, and only that tag
publishes: `release.yml` publishes to the Gradle Plugin Portal on any `v*` tag, and a published
version cannot be taken back, so no `v*` tag exists until every gate below has passed.

- **Minor.** A commit of `main` with every change for the release merged. Before it, a pull request
  moves the functional suite's 9.x version to Gradle's newest release, with the cells of
  `.github/workflows/matrix.yml` and the reference's [supported matrix](docs/reference.md#supported-matrix).
- **Patch.** A commit on `release/<major>.<minor>`, which is pushed with each patch's tag. If the
  branch exists neither locally nor on `origin`, create it from the line's latest tag
  (`git tag -l 'v0.2.*' --sort=-v:refname | head -1`, then `git branch release/0.2 <that tag>`).
  Cherry-pick each fix from `main` (`git cherry-pick -x <sha>`); a conflict in `CHANGELOG.md` is
  resolved by putting the fix's line under a `## [Unreleased]` heading. The candidate contains the
  previous release (`git merge-base --is-ancestor v<previous> <candidate>` succeeds), and a patch
  carries only cherry-picked fixes and the changelog commit of step 7:
  `git log v<previous>..<candidate>` lists nothing else.

Write the candidate's full SHA down before the first gate. Every gate runs on that SHA; a fix found
by a gate makes a new candidate, and the gates it touches run again on it.

## Checklist

In order, at the candidate. Each step names who runs it and what it costs in GitHub Actions minutes;
a step that has neither is removed. CI on this repository is public, so its standard runners cost
no minutes against a budget.

| # | Step | Who | Actions minutes |
|---|---|---|---|
| 1 | `./gradlew build` and `./gradlew build -PreleaseVersion=<version>` pass. The build runs the functional suite on Gradle 8.14 and 9. | maintainer | none, run locally |
| 2 | Every guard (see [CONTRIBUTING.md](CONTRIBUTING.md#the-guards)) fires at the candidate: `.github/workflows/guards.yml` dispatched on it is green, and the nightly schedule is still enabled (GitHub disables one after 60 days without activity). | maintainer dispatches | none on this public repository; its wall time is the run's own |
| 3 | One code review of the whole release diff, `v<previous>..<candidate>`, so interactions between changes are seen. It also asks whether upgrading needs anything beyond the CHANGELOG's map lines (a map format change, a renamed or removed property or flag, a changed CI recipe or cache path); if so, the release notes and the reference's "Upgrading and your map" get a short "what you do" paragraph. Each confirmed finding is fixed in its own pull request, which repeats steps 1 and 2 for what it touches. | maintainer | none |
| 3b | An adversarial campaign at the candidate, following the private campaign playbook: agents try to make a selecting run skip a test that should run, in every area of the code the release changed and one other in rotation, reading each rule's code for its boundary as well as writing attempts from known shapes. Its record passes the campaign check. Each attempt that held stays in the break suite (`./gradlew :plugin:breakAttempts`, which then passes on Gradle 8.14 and 9); each skipped test it finds is fixed in its own pull request, test first, with a fix that forces or widens and a mutation check that removes the fix, which repeats steps 1 to 3 for what it touches; or the maintainer accepts it as a limit documented in [docs/reference.md](docs/reference.md). | maintainer; agents run the attempts | none, run locally; one Gradle build at a time |
| 4 | A reduced benchmark on real projects at the candidate: whether each test that should have run did run is the same as at the previous release, or each difference is explained by a merged change. | maintainer | none, run locally; about 20 minutes |
| 5 | Every new flag, DSL property, token and file is documented in [docs/reference.md](docs/reference.md) or [docs/contract.md](docs/contract.md). | maintainer | none |
| 6 | Each native library in [the reviewed list](docs/reference.md#native-libraries) is compared with its latest major release; a re-review issue is opened for each that moved. | maintainer | none |
| 7 | `CHANGELOG.md`: `## [Unreleased]` becomes `## [<version>]` with the date, and the entry states one effect on an existing map, the strongest of any change in it: discarded over repaired over left alone. This change makes the final candidate, the commit that is tagged and recorded: for a minor it reaches `main` through a pull request, for a patch it is committed on the release branch. Before tagging, `git diff --stat <candidate> <final candidate>` lists only `CHANGELOG.md`, so steps 1-6 stand; if anything else differs, the gates it touches run again on the final candidate. | maintainer | none |
| 7b | `.github/workflows/matrix.yml`, dispatched from the final candidate's branch (`main` or `release/<major>.<minor>`) with the final candidate's SHA, is green in every job, so every row of the [supported matrix](docs/reference.md#supported-matrix) ran on the commit that is tagged. The run's link is recorded with the release. A cell red only on a newly bumped Gradle: revert the bump, which makes a new candidate, and open an issue for that Gradle version; a row never leaves the table because a bump failed. | maintainer dispatches | none on this public repository; about 1.5 hours of wall time, an estimate until a run measures it |
| 7c | `.github/workflows/jdk-ea.yml`, dispatched from the same branch, is green: the newest early-access JDK did what the candidate's ceilings predict. | maintainer dispatches | none on this public repository; under 30 minutes |
| 8 | If the Portal description or tags changed: `./gradlew :plugin:publishPlugins --validate-only -PreleaseVersion=<version>` with the Portal keys. | maintainer | none |

A gate that fails, or a confirmed finding left unfixed, stops the release. A campaign finding is open
until it is fixed with a mutation check or accepted by the maintainer as a documented limit; the
release waits for every one.

## Tagging and publishing

1. Tag the final candidate and push the tag:
   `git tag -a v<version> <final candidate> -m v<version> && git push origin v<version>`. For a
   patch, also push the release branch: `git push origin release/<major>.<minor>`.
2. `release.yml` builds and tests the tag, then waits for a maintainer to approve the `release`
   environment, which holds the Portal key and accepts only `v*` tags. Approve it.
3. While the Portal reviews the new version, merge only documentation changes.

## After the release

- A clean project outside this repository resolves `<version>` from the Portal and runs once with
  `-Pyoriwake.observe` and once with `-Pyoriwake.select`.
- The GitHub Action ([zeuspizza/yoriwake-action](https://github.com/zeuspizza/yoriwake-action)) is
  tagged at the matching version.
- The release is recorded with its final candidate SHA and each gate's result.
- For a patch, a pull request on `main` adds its `## [<version>]` section to `CHANGELOG.md` and
  removes its fixes' lines from `## [Unreleased]`.
- If the release fixes a silent skip, the advisory is updated with the fixed version, and adopters
  who reported it are told.

## A new JDK

When a JDK reaches general availability, or its first release candidate once ASM and JaCoCo
releases that read it exist:

1. Merge the ASM and JaCoCo upgrades that read it, after the build, the functional suite and the
   private mutation checks pass on them. The ASM ceiling follows ASM on its own; check its release
   notes name the new `V<n>`. Add JaCoCo's row to
   `plugin/src/main/resources/io/github/zeuspizza/yoriwake/gradle/jacoco-java-support.tsv` from its
   change log, official support only. `DigestGoldenTest` fails if the ASM upgrade changed class
   digests: say so in the CHANGELOG line, as every existing map then runs everything once.
2. Add the JDK's case to `TestJvmRangeFunctionalTest` and its row to the reference's [supported
   matrix](docs/reference.md#supported-matrix), with the JDK in `matrix.yml`'s `setup-java` list,
   and update the ceilings in the reference's [New JDKs](docs/reference.md#new-jdks).
3. Check `.github/workflows/jdk-ea.yml` is still enabled (GitHub disables a schedule after 60 days
   without activity) and its last run was green.
4. One CHANGELOG line names the JDK and the map's effect.

`jdk-ea.yml` is also dispatched on each release candidate, beside `matrix.yml` (step 7b).

## A silent skip

A report that a test should have run and did not is handled first:

1. Confirm it.
2. Publish an advisory on this repository's Security tab naming the affected releases and the safe
   step: run without `-Pyoriwake.select` until the fix ships. This costs no gate and goes out the
   day the skip is confirmed.
3. Fix it on `main` with a test that fails without the fix.
4. Cut a patch candidate on the release line and run it through every gate above, for as long as
   they take; no gate is shortened to ship sooner.
5. Tag, publish, and update the advisory with the fixed version.
