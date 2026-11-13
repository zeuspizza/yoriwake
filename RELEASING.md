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

A release candidate is a commit, never a tag. Only the final step tags, and only that tag publishes:
`release.yml` publishes to the Gradle Plugin Portal and Maven Central on any `v*` tag, and a
published version cannot be taken back, so no `v*` tag exists until every gate below has passed.

- **Minor.** A commit of `main` with every change for the release merged. Before it, a pull request
  moves the functional suite's 9.x version to Gradle's newest release, with the cells of
  `.github/workflows/matrix.yml` and the reference's [supported matrix](docs/reference.md#supported-matrix).
  Step 7b's dispatched `matrix.yml` run proves the gate commit; no workflow runs on a
  schedule.
- **Patch.** A commit on `release/<major>.<minor>`, which is pushed with each patch's tag. Step 7b
  is its matrix run, as for a minor. If the
  branch exists neither locally nor on `origin`, create it from the line's latest tag
  (`git tag -l 'v0.2.*' --sort=-v:refname | head -1`, then `git branch release/0.2 <that tag>`).
  Cherry-pick each fix from `main` (`git cherry-pick -x <sha>`); a conflict in `CHANGELOG.md` is
  resolved by putting the fix's line under a `## [Unreleased]` heading. The candidate contains the
  previous release (`git merge-base --is-ancestor v<previous> <candidate>` succeeds), and a patch
  carries only cherry-picked fixes and the changelog commit of step 7:
  `git log v<previous>..<candidate>` lists nothing else.

Every gate runs once, on the **gate commit**: a commit of the release line (`main` for a minor,
`release/<major>.<minor>` for a patch) whose full SHA is written down before the first gate. A fix
a gate finds is merged and makes the candidate. The candidate re-runs only what its diff from the
gate commit touches, so the week of fixes between the two is not paid for twice:

```bash
git diff --no-renames --name-only <gate commit> <candidate>   # any change
git diff --no-renames --name-only <gate commit> <candidate> -- \
  plugin/src/main core/src/main agent/src/main maven/src/main \
  '*build.gradle.kts' settings.gradle.kts gradle.properties gradle/ '*libs.versions.toml' \
  LICENSE NOTICE                                              # product code
git diff --no-renames --name-only <gate commit> <candidate> -- \
  '*build.gradle.kts' settings.gradle.kts gradle.properties gradle/ '*libs.versions.toml' \
  core/src/main/resources                                     # build files, versions, ceilings
```

- **Product code changed:** step 1a, step 2 (every guard) and step 3b over the families the diff
  touched, each at the candidate; a sweep with `tier=auto` and `base=<gate commit>` in step 4.
  The source paths are those `.github/ci.py` treats as product code, plus the build files: a
  dependency, shading or bundling change alters what ships. The wrapper's Gradle compiles and
  packages the jars, and `LICENSE` and `NOTICE` are copied into them.
- **Build files, dependency versions or JDK ceilings changed** (the third command: Gradle files,
  the wrapper, a version catalog, `core/src/main/resources`, where ASM's and JaCoCo's ceilings
  live): also step 7b's `matrix.yml` and step 7c's `jdk-ea.yml` at the candidate, since a Gradle,
  JDK or ASM/JaCoCo change is what they exist to prove.
- **Any other change** (tests, documentation, CI, scripts): step 1a alone, and the break suites
  when the diff touches one.
- **No change, or `CHANGELOG.md` alone:** nothing; the gate commit's runs stand.
- A change merged after the gate commit that no gate reviewed gets steps 3 and 5 over
  `<gate commit>..<candidate>`.
- A re-run that fails stops the release; its fix makes a new candidate, decided the same way
  against the last candidate whose runs were green.

The guards, sweep and early-access workflows run the head of the branch they are dispatched from,
so steps 2, 4 and 7c are dispatched back to back from the release line while its head is the gate
commit (or the candidate), and each run's head SHA is recorded with the release: a run on another
SHA does not count.

## Checklist

In order, at the gate commit, and again at the candidate where the diff above says so. Each step names who runs it and what it costs
in GitHub Actions minutes;
a step that has neither is removed. CI on this repository is public, so its standard runners cost
no minutes against a budget.

| # | Step | Who | Actions minutes |
|---|---|---|---|
| 1a | `.github/workflows/release.yml`, dispatched from the release line with the gate commit's full SHA, is green in `build and test` and `attest`, with `publish to the Gradle Plugin Portal`, `publish to Maven Central` and `github release` skipped: every step before publishing ran on that commit, as version `0.0.0-rehearsal`. The run's link is recorded with the release. A candidate whose `release.yml` has no dispatch (a patch on a line released before 0.4) records it as not available. | maintainer dispatches | none on this public repository; its wall time is one `build and test` job plus `attest` |
| 1 | No local build: `verify.yml` on the push to `main` is green (its `unit and agent suites`, `functional on Gradle 8.14` and `functional on Gradle 9` jobs and `coverage gate` run everything `./gradlew build` runs, and `unit and agent suites` also runs `:maven:functionalTest`), and step 1a's `build and test` ran `./gradlew build :maven:functionalTest -PreleaseVersion=0.0.0-rehearsal`. The release's own version is built by the tag run's `build and test`, which re-runs the checks that read the shipped files before anything publishes. A push that changes only documentation skips `verify`; the rehearsal builds it in full. | maintainer reads | none |
| 1b | `python3 scripts/check_reproducible.py <final candidate> <version>` passes: two fresh clones build the same Gradle plugin jar, its nested agent jar, and the Maven plugin's jar, sources jar and javadoc jar. No workflow runs it. | maintainer, locally | none |
| 2 | Every guard (see [CONTRIBUTING.md](CONTRIBUTING.md#the-guards)) fires: `.github/workflows/guards.yml` dispatched from the release line at the gate commit is green, its head SHA recorded; dispatched again on the candidate when product code changed. A shard cut by its limit or a runner fault runs again alone, with the others kept: dispatch with `shards` and `reuse` ([CONTRIBUTING.md](CONTRIBUTING.md#the-guards)). | maintainer dispatches | none on this public repository; its wall time is the run's own |
| 3 | One code review of the whole release diff, `v<previous>..<gate commit>`, so interactions between changes are seen. It also asks whether upgrading needs anything beyond the CHANGELOG's map lines (a map format change, a renamed or removed property or flag, a changed CI recipe or cache path); if so, the release notes and the reference's "Upgrading and your map" get a short "what you do" paragraph. Each confirmed finding is fixed in its own pull request, which runs `verify` and the guards it touches (every guard when it changes product code) and is reviewed before it merges. | maintainer | none |
| 3b | An adversarial campaign at the gate commit, following the private campaign playbook: agents try to make a selecting run skip a test that should run, in every area of the code the release changed and one other in rotation, reading each rule's code for its boundary as well as writing attempts from known shapes. Its record passes the campaign check. Each attempt that held stays in a break suite (`./gradlew :plugin:breakAttempts`, which then passes on Gradle 8.14 and 9, or the Maven plugin's `./gradlew :maven:breakAttempts`, which then passes); each skipped test it finds is fixed in its own pull request, test first, with a fix that forces or widens and a mutation check that removes the fix; or the maintainer accepts it as a limit documented in [docs/reference.md](docs/reference.md). A candidate with product code changed runs it again over the families that diff touched. The break suites run locally, since no workflow runs them. | maintainer; agents run the attempts | none, run locally; one Gradle or Maven build at a time |
| 4 | A benchmark on real projects: `.github/workflows/sweep.yml` dispatched from the release line at the gate commit with `tier=full` and `only=all`, its head SHA recorded; whether each test that should have run did run is the same as at the previous release, or each difference is explained by a merged change. A candidate with product code changed dispatches it again with `tier=auto` and `base=<gate commit>`. | maintainer dispatches | none on this public repository; its wall time is the run's own |
| 5 | Every new flag, DSL property, token and file is documented in [docs/reference.md](docs/reference.md) or [docs/contract.md](docs/contract.md). | maintainer | none |
| 5b | The README's Gradle and Maven snippets and its plugin and action badges, and the reference's CI examples (`YORIWAKE_VERSION`, the action's tag) name `<version>`. A minor sets them in a pull request merged before the candidate is cut, so step 7's diff stays `CHANGELOG.md` alone; a patch leaves the release branch's as they are, and its pull request on `main` after the release sets them. | maintainer | none |
| 6 | Each native library in [the reviewed list](docs/reference.md#native-libraries) is compared with its latest major release; a re-review issue is opened for each that moved. | maintainer | none |
| 7 | `CHANGELOG.md`: `## [Unreleased]` becomes `## [<version>]` with the date, and the entry states one effect on an existing map, the strongest of any change in it: discarded over repaired over left alone. This change makes the final candidate, the commit that is tagged and recorded: for a minor it reaches `main` through a pull request, for a patch it is committed on the release branch. Before tagging, `git diff --stat <candidate> <final candidate>` lists only `CHANGELOG.md`, so every earlier run stands; if anything else differs, what it touches runs again on the final candidate, as the candidate rule above says. After a minor's entry merges, every open pull request that touches `CHANGELOG.md` is updated from `main` before it is merged: its lines under the old `## [Unreleased]` would otherwise squash under the new version, and only the updated diff lets its changelog check see that. | maintainer | none |
| 7b | `.github/workflows/matrix.yml`, dispatched from the release line with the gate commit's SHA, is green in every job, so every row of the [supported matrix](docs/reference.md#supported-matrix) ran on the release's code. Dispatched again on the candidate when its build files or dependency versions changed. The run's link is recorded with the release. A cell red only on a newly bumped Gradle: revert the bump, which makes a new candidate, and open an issue for that Gradle version; a row never leaves the table because a bump failed. | maintainer dispatches | none on this public repository; about 1.5 hours of wall time, an estimate until a run measures it |
| 7c | `.github/workflows/jdk-ea.yml`, dispatched from the same line at the gate commit, its head SHA recorded, and again on a candidate whose build files, dependency versions or JDK ceilings changed, is green: the newest early-access JDK refused or narrowed as the code's ceilings predict, and skipped no test that must run. A warning that it ran tests beyond the prediction leaves the run green; it is recorded with the release and gets an issue. | maintainer dispatches | none on this public repository; under 30 minutes |
| 7d | Optional: `.github/workflows/release.yml`, dispatched as in step 1a on the final candidate, is green in `build and test` and `attest`. With it, the tag run publishes minutes after the tag; without it, the tag run builds and tests in full first. | maintainer dispatches | none on this public repository; its wall time is step 1a's |
| 8 | If the Portal description or tags changed: `./gradlew :plugin:publishPlugins --validate-only -PreleaseVersion=<version>` with the Portal keys. | maintainer | none |

A gate that fails, or a confirmed finding left unfixed, stops the release. A campaign finding is open
until it is fixed with a mutation check or accepted by the maintainer as a documented limit; the
release waits for every one.

## Tagging and publishing

1. Tag the final candidate and push the tag:
   `git tag -a v<version> <final candidate> -m v<version> && git push origin v<version>`. For a
   patch, also push the release branch: `git push origin release/<major>.<minor>`. A repository
   ruleset lets only the maintainer create `v*` tags, since a tag is what starts a release.
2. `release.yml` first rebuilds, as version `0.0.0-rehearsal`, every file a rehearsal lists, and
   asks GitHub whether `release.yml` at this same commit attested each one, which it does only after
   `build and test` passed on them. If every one was (step 7d: step 1a's rehearsal never qualifies, since step 7 makes a new commit),
   it builds the release version without running the suites again, with the checks that read the
   shipped files, and its summary names the runs it relied on; otherwise it builds and tests in
   full. Either way it then attests the files that build produced and waits for a maintainer to
   approve the `release` environment, which holds the Portal key (`GRADLE_PUBLISH_KEY`,
   `GRADLE_PUBLISH_SECRET`), the Central token (`CENTRAL_USERNAME`, `CENTRAL_PASSWORD`) and the
   signing key (`SIGNING_KEY`, `SIGNING_PASSWORD`) and accepts only `v*` tags. Approve it once:
   `publish to the Gradle Plugin Portal` and `publish to Maven Central` then run side by side, each
   signing those same files and uploading its plugin; the Central job waits up to an hour for Central
   to publish. Nothing after the build that `attest` vouches for builds anything.
3. A red upload job leaves the other registry's version standing. Re-run the failed job alone,
   within GitHub's 30-day re-run limit; a re-run that finds its version already published stops
   green. A Portal re-run while the Portal still reviews the version uploads again and is refused:
   wait for the review first. A refusal that repeats on a re-run leaves the other registry holding
   the version for good, since neither takes a version back: the fix ships as the next patch, on
   both. While the Portal reviews the new version, merge only documentation changes.
4. The last job, once both uploads are green, creates the GitHub release from the tag, with the
   `CHANGELOG.md` section as the notes and the Gradle plugin jar and the Maven plugin jar, each with
   its signature, the agent jar, the SBOM and `SHA256SUMS` attached. It is marked latest unless a
   newer version exists. Check it lists those files.

## After the release

- A clean project outside this repository resolves `<version>` from the Portal and runs once with
  `-Pyoriwake.observe` and once with `-Pyoriwake.select`. A clean Maven project resolves
  `io.github.zeuspizza:yoriwake-maven-plugin:<version>` from Maven Central and runs `mvn verify`
  once with `-Dyoriwake.observe` and once with `-Dyoriwake.select`.
- The jars the Portal and Maven Central serve for `<version>` match the release's `SHA256SUMS` and
  pass `gh attestation verify` as [SECURITY.md](SECURITY.md#verifying-a-release) describes. Run by
  the maintainer, locally; no Actions minutes. A release whose Portal or Central jar does not verify
  removes the README's SLSA badge in the same pull request that records it.
- The tag, checked out and built with `-PreleaseVersion=<version>` on Temurin 21, gives the Gradle
  plugin jar, the nested `yoriwake-agent.jar` and the Maven plugin jar that the release's
  `SHA256SUMS` lists.
- The GitHub Action ([zeuspizza/yoriwake-action](https://github.com/zeuspizza/yoriwake-action)) is
  tagged at the matching version.
- The release is recorded with its final candidate SHA and each gate's result.
- For a patch, a pull request on `main` adds its `## [<version>]` section to `CHANGELOG.md`,
  removes its fixes' lines from `## [Unreleased]`, and sets the versions step 5b names, unless a
  newer minor already did.
- If the release fixes a silent skip, the advisory is updated with the fixed version, and adopters
  who reported it are told.
- The maintainer re-checks the private sharing gate against this release. Nothing about yoriwake is
  shared in public channels while a known defect that can skip a test that should have run is
  open on this release.

## A new JDK

When a JDK reaches general availability, or its first release candidate once ASM and JaCoCo
releases that read it exist:

1. Merge the ASM and JaCoCo upgrades that read it, after the build, the functional suite and the
   private mutation checks pass on them. The ASM ceiling follows ASM on its own; check its release
   notes name the new `V<n>`. Add JaCoCo's row to
   `core/src/main/resources/io/github/zeuspizza/yoriwake/gradle/jacoco-java-support.tsv` from its
   change log, official support only. `DigestGoldenTest` fails if the ASM upgrade changed class
   digests: say so in the CHANGELOG line, as every existing map then runs everything once.
2. Add the JDK's case to `TestJvmRangeFunctionalTest` and its row to the reference's [supported
   matrix](docs/reference.md#supported-matrix), with the JDK in `matrix.yml`'s `setup-java` list,
   and update the ceilings in the reference's [New JDKs](docs/reference.md#new-jdks).
3. Dispatch `.github/workflows/jdk-ea.yml` and check it is green.
4. One CHANGELOG line names the JDK and the map's effect.

`jdk-ea.yml` is also dispatched in each release's gates, beside `matrix.yml` (step 7b).

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
