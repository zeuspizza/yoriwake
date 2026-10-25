# yoriwake reference

Everything the [README](../README.md) leaves out. The ordering governs all of it: never skip a
test that should have run; subject to that, skip as much as possible; subject to both, keep
overhead low. Wherever this page says "forces", that is the ordering choosing a full run over a
guess.

## How it works

- **Capture.** A run that executes the whole suite is instrumented with JaCoCo, scoped to your
  build's own packages. A Java agent on the test runtime classpath dumps and resets coverage around
  every test, so each test gets its own record: the classes it executed and whether it passed.
- **The map.** Records are decoded on the Gradle daemon, never in the test JVM, into
  `.gradle/yoriwake/<task>-<hash>/`. It is plain text with no machine-specific field, one
  directory per test task, outside `build/` so `clean` leaves it alone.
- **Change detection.** With `-Pyoriwake.select`, the plugin diffs the working tree against a base
  commit (the merge base with your branch's upstream by default; `origin/main`, `origin/master`,
  `main`, `master` as fallbacks) and widens that base back to the commit the map was captured at.
  Uncommitted, untracked and gitignored files count too, by content, so an edited ignored fixture
  selects the tests that read it. A changed git submodule counts as its path whatever its `ignore`
  setting, or that of a submodule nested in it, untracked files in it included, its own build
  output too. Build state does not: `.gradle/`, `.kotlin/`, the build
  directories of this build's projects, and a `build/` directory beside any other Gradle build
  script (`buildSrc`, an included build, a project a TestKit test wrote). A change reaches a test
  that reads such a file through the tracked sources that produce it. A non-class file the build
  put on a test task's runtime classpath (a resource, an entry of one of your own jars) is the
  exception: it counts by content, compared with the capture's just before the tests start, so one
  generated from git state, the clock or the environment (a `git.properties`, a build date or
  revision in a manifest) runs everything when it changed; see [Generated files on the test
  classpath](#generated-files-on-the-test-classpath). A build-directory file off the classpath,
  put there by hand or by a build step, is never seen as a change.
- **Selection.** A JUnit Platform `PostDiscoveryFilter` in the test JVM deselects every test whose
  recorded coverage cannot reach the change. It runs at discovery, so it sees the tests that exist
  now and always runs one the map has never seen.
- **Nested launchers.** A JUnit launcher a test starts itself, in its own class loader or one it
  builds from the test classpath (as spring-core-test's `@CompileWithForkedClassLoader` does), is
  never filtered: it runs every test it discovers, and its coverage belongs to the test that
  started it. Only the outer launcher's decisions are written to `decisions.tsv`.
- **Refresh.** A narrowed run does not update the map; only a capture that ran the whole suite,
  finished, and kept HEAD and its reflog where they were from before compilation to the end writes
  it. A filtered, fail-fast or interrupted run leaves the map as it was, except that a test it saw
  fail or skip keeps that outcome. A change large enough to force a full run refreshes the map as a side effect,
  except a map [recorded in isolation](#recording-each-test-class-in-its-own-jvm). A run of the
  whole suite drops the record of any test it did not report, such as a deleted test or one under a
  class whose setup failed, so that test runs until a capture sees it again.
- **Isolation.** The agent's only dependency is ASM, shaded into
  `io.github.zeuspizza.yoriwake.shaded.asm`, so nothing it carries collides with your classpath.

## What it prints

Every line carries the `[yoriwake]` prefix.

| Line | Meaning |
|---|---|
| `:test map=… scope=… includes=… agent=… forks=…` | the configuring line: what this run was set up with. Include it in bug reports. |
| `:test selecting against <sha> (<origin>): N changed classes, M paths coverage cannot see` | selection is active, and against which base |
| `of N tests discovered: reaches-change=… skipped=…` | how many tests each rule decided |
| `:test map updated: N records captured, M known` | a capture completed |
| `:test: <reason>, so this run's coverage was not kept and the map is as it was` | a run of the whole suite that could not date the map, such as a filtered one or one during which HEAD moved; the tests it saw fail or skip keep that outcome |
| `:test ran but recorded no coverage, …` | something is wrong: no map was built. Alert on this one. |
| `:test would run everything: <reason>` | from `yoriwakeExplain<Task>`: what forced it |
| `:test: <paths> changed during :test's last capture build …` | on a forced run: untracked files that force it, by where they came from, and how to list them in `pathIgnore`; see [Files written into the source tree](#files-written-into-the-source-tree) |
| `pathIgnore left out N changed paths (…): <paths>` | the changed paths `pathIgnore` took out of the change set, counted by entry; see [Paths no test reads](#paths-no-test-reads) |
| `pathIgnore entry '<entry>' matches <path>, which stays in the change set (<protection>)` | a declared path that is protected, and why |
| `<path> was read at capture, … despite pathIgnore entry '<entry>' …` | a test JVM read a declared path when the map was recorded, so the tests from that read on run |

Reasons, one per test:

| Reason | Meaning |
|---|---|
| `reaches-change` | its recorded coverage touches a changed class, or its own test class changed |
| `shares-jvm-with-change` | it ran at or after the point its test JVM first loaded or executed a changed class, or read its class or source file, or read a build-produced classpath file that changed; see [what coverage does not record](#what-coverage-does-not-record) and [generated files on the test classpath](#generated-files-on-the-test-classpath) |
| `not-known-to-pass` | the map does not record it as having passed, so it runs whatever changed; this includes a test recorded `FLAKY`, see [retrying failed tests](#retrying-failed-tests) |
| `not-in-map` | the map has never seen it, so no change could be shown not to reach it |
| `engine-runs-everything` | its engine runs every test it discovered or none, so it is never left out; see [Kotlin](#kotlin) |
| `full-run` | nothing may be skipped this run |
| `skipped` | nothing selected it |

Files in the map directory that are meant to be read:

- `decisions.tsv`: one row per test, `id <tab> included|excluded <tab> reason`. The test result
  XML shows what ran; this shows why anything did not. After the rows, `#+` lines name every rule
  that would run each test, not only the one that decided it; see
  [the contract](contract.md#decision-rules).
- `explain.json`: written by `yoriwakeExplain<Task>`. Carries `fullRunKind` and `refusalKind` as
  tokens, so a script can branch on the cause without matching English. `forcingPathOrigins` lists
  the paths that force the run, each with `written-during-capture`, `generated-in-sources`,
  `untracked`, or no origin for a path HEAD tracks. `ignoredPaths` lists every path `pathIgnore`
  left out, with its entry and what the map says about it (`read`, `not-read` or
  `no-read-evidence`); `notIgnored` each one an entry matched that stayed, with its protection.
- `audit.json`: written by `yoriwakeAudit<Task>`. The suite's shape and every blocker as tokens.
- `observation.json`: written by an [observing run](#observing-before-you-select): what selection
  would have left out, and which of those failed.
- `selection.tsv`: written by a selecting run that narrowed at a clean tree: the tests that ran, for
  a [complement run](#running-what-a-selection-skipped).
- `map-digest`: the map's digest, which [`-Pyoriwake.trustedMaps`](#who-can-write-the-map-you-restore)
  lists.

## When it refuses to select

It forces a full run whenever it cannot prove a narrower one is safe, and says which cause:

- the map is missing, empty, unfinished, or from a different format version;
- the map's age cannot be established (no capture stamp, or one that cannot be related to the
  base);
- a changed path coverage cannot see: a build script, a settings file, a version catalog, a
  resource, or any other file, a README or a CI workflow included. Nothing named in the code is
  needed: a test or the build can find a file by a pattern, by walking a directory, or through
  another file that lists it. That includes a file your tests or your build write into the source
  tree outside `build/`, such as a `derby.log`: untracked and ignored files count. The run names
  each such file; see [Files written into the source tree](#files-written-into-the-source-tree).
  Four kinds of file are no change: an IDE's state (anything under an `.idea`, `.vscode` or
  `.fleet` directory at any depth, and `*.iml` files); a file the build's `notReadByTests` lists
  (see [Properties and tasks](#properties-and-tasks)); a path the build's `pathIgnore` declares (see
  [Paths no test reads](#paths-no-test-reads)); and a Java or Kotlin source of a module that is not
  on the task's runtime classpath, which no compiled class of the task names. The console counts
  the first two and names each of the third;
- a changed class the map has never recorded, because uncovered and unrecordable look the same. A
  test class this task runs is the exception: discovery runs it whatever the map knows;
- a changed class whose annotations, on the class or any member and with their values, differ from
  the ones the map recorded (`annotations-changed`), or an annotated changed class the map recorded
  no annotations for (`annotations-unrecorded`). A test class this task runs that declares a test
  is exempt, because its own tests run anyway;
- compiled bytes that changed with no source change, in a class no test executed
  (`no-coverage-for-changed-bytes`), or a map holding no digest of the compiled classes to compare
  against (`bytes-unrecorded`);
- a non-class file the build produced on the test runtime classpath that appeared or is gone
  since the capture, or changed where the map cannot say which tests read it
  (`classpath-files-changed`), or a map that records no such digests (`classpath-files-unrecorded`,
  once: the forced run records them, unless the classpath holds over 200,000 such files, which are
  then never recorded). One whose readers the map records runs only them. The run names up to five
  files; see [Generated files on the test classpath](#generated-files-on-the-test-classpath);
- a change touching startup coverage, which no single test owns;
- more changed paths than `largeChangeThreshold` (5,000 by default) against the base the run was
  asked about, with untracked files and the working tree's drift, less the paths `pathIgnore` takes
  out (`large-change`). A change that
  size selects almost everything or forces anyway, so the run skips the analysis and records the
  map. The count does not grow with the map's age. The default is Datadog's published bound;
  whether the analysis saved is worth it at that size is unmeasured;
- an empty change set, which cannot be told apart from not having one;
- git could not report a change set, or no base could be resolved;
- the working tree changed between configuring the build and running the task, for instance
  a task earlier in the same build wrote into it (`change-set-stale`). The check runs before the
  test task's own `doFirst` actions from the build script, since Gradle runs yoriwake's actions
  first, so a file such an action writes into the source tree is not compared. Untracked files
  under build output and Gradle's own state are never a change, by design;
- a changed class declaring a compile-time constant another class could reference, when the value
  differs from the map's;
- a changed Kotlin source in a build that emits no `SourceDebugExtension` (see [Kotlin](#kotlin));
- Develocity Test Distribution or Predictive Test Selection is enabled on the task
  (`develocity-test-distribution`, `develocity-test-selection`, `develocity-undetermined`): a
  decline, so nothing is recorded either; see [What is not supported](#what-is-not-supported);
- the test JVM's JDK, OS, system properties, time zone or locale, or a variable `environmentInclude`
  lists, differ from those the map was recorded under (`environment-changed`), or the map records
  none (`environment-unrecorded`, once); see [The environment
  fingerprint](#the-environment-fingerprint);
- on a run given `-Pyoriwake.trustedMaps`, a map the list does not name (`map-unverified`) or names
  with another digest (`map-untrusted`). The map is cleared and the run records a new one; see
  [Who can write the map you restore](#who-can-write-the-map-you-restore).
- a class of the task's own output compiled for a newer Java than the plugin reads, or than the
  project's JaCoCo officially instruments (`class-file-unsupported`), or a test JVM newer than the
  agent reads (`test-jvm-unsupported`); see [New JDKs](#new-jdks).

Tests named on the command line with `--tests`, or added to the task's filter after the build
script ran, all run: a selecting run declines selection for that task (`tests-named`), runs every
test the filter matches whatever the map says, and leaves the map as it was. The decline takes the
place of any other refusal on the task except a Develocity decline, which also runs every test the
filter matches, and `explain.json` reports it when `yoriwakeExplain<Task>` runs in the same build. A
task whose `--tests` patterns cannot be read declines too (`decline-undetermined`) and runs
everything.

That covers an IDE that runs a test through Gradle: its test launcher adds the class or method it
was asked for to the task's filter, and the run declines as it would for `--tests`, with the
configuration cache on or off, storing or reusing its entry. The filter is read as the task
executes. `explain.json` sees a pattern a `gradle.taskGraph.whenReady` hook adds, but never a test
launcher's, which asks for the test task alone. A pattern counts as added only when the build
script did not set the same one: on Gradle 9, a test launcher asking for exactly the class an
`includeTestsMatching` in the build script already names adds nothing new, and that run narrows
within the build script's filter.

A filter given for one run, with `--tests` or as patterns an IDE's test launcher adds, does not
speak for the whole suite: the run leaves the map as it was. On the JUnit Platform, a filter the
build script sets on the task (`filter.includeTestsMatching`, `excludeTestsMatching`,
`includeTest`, `excludeTest`) dates the map instead, as a tag or engine filter does: the tests it
left out drop out of the map, so a run without it runs them as not in it. That includes a filter
the build script sets only under a property (`if (hasProperty("quick")) ...`), which Gradle cannot
tell from a permanent one: a run with the property drops the other tests from the map, as a tag
filter under a property does. A pattern is the build script's when it is set by the time every
build script and `afterEvaluate` has run; one added later, by an IDE's test launcher or a
`gradle.taskGraph.whenReady` hook, counts as given for the run, and a recording or observing run
under it keeps the records of every test it did not run. On JUnit 4 or TestNG off the
Platform, a filter in the build script leaves the map undated. A run that leaves tests out by tag
or engine (`includeTags`, `excludeTags`, `includeEngines`, `excludeEngines`), JUnit 4 category or
TestNG group does not speak for the whole suite either, but it does date the map: the tests it
left out drop out of the map, so the next selecting run runs them as not in it.

Separately from those, a test the map does not record as passing always runs, and a test the map
has never seen always runs.

### Files written into the source tree

A file the tests or the build write into the source tree outside the build directories counts as
a change by its content, tracked or not, ignored or not. A run that sees it changed runs
everything, and names it, one line per kind. Files under a directory git tracks nothing in are named
by the outermost such directory, as a glob with its file count (`docs-client/node_modules/** (62,444
files)`), the largest first; a file beside a tracked one is named as itself. Each line ends with the
globs to list in [`pathIgnore`](#paths-no-test-reads), and the condition under which that is
safe. No glob is offered for a directory that holds sources: a source file among the changed files,
or a source tree laid out as a source set's (`main/java`, `main/kotlin`) in it or one level below.
Listed, a later change to those sources would no longer count as one. Nor for a path under a source
set's directory or any `src` directory, which `pathIgnore` never leaves out: generating it under the
build directory instead stops the full runs.

- **Changed during the last capture build** (`written-during-capture`): created, rewritten or
  deleted while the capture that dated the map ran, by its tests or its tasks. If a test writes it
  and no test, the writer included, ever reads it back, have the test write it into the project's
  build directory (`layout.buildDirectory`); for Derby's `derby.log`, set the
  `derby.stream.error.file` system property. If a test reads it back (a database, a golden file
  recorded when missing), or a build task writes it, leave it where it is: under the build
  directory its changes are never seen, as for any build output, and tests that hand data to each
  other through it are [order-dependent](#what-is-not-supported). Gitignoring it does not help,
  since ignored files count; deleting it before each run does not either, since the capture marks
  it again; nor does another directory such as `out/` or `tmp/`.
- **In CI**, a file the capture's tests create is in the change set of every fresh checkout that
  lacks it, so such a project runs everything on every pull request until the file moves.
- **Untracked in a source directory** (`generated-in-sources`): a file a build task generates into
  a source or resource directory, or one not yet added to git. Tests read the copy the build puts
  on the classpath, which is compared on its own (see [Generated files on the test
  classpath](#generated-files-on-the-test-classpath)). `pathIgnore` keeps it, as everything under a
  source directory; generating it under the build directory stops the full runs.
- **Untracked** (`untracked`): any other untracked or ignored file; it counts by content.

A file HEAD tracks gets no line: the run cannot tell a test's write from your edit. When git cannot
say which forcing paths it tracks, one line counts them and `yoriwakeExplain<Task>` lists a sample.

### Generated files on the test classpath

Every non-class file the build produced on a test task's runtime classpath counts by content: each
file of a resource or output directory under the root or a build directory, and each entry of one
of your own jars (never the jar whole, so a rebuild that only moves entry timestamps changes
nothing). A copy of a tracked text file git checks out with `\r\n` counts with its line endings
as `\n` ([Windows](#windows)). The capture records them just before its tests start, and which test JVM read each one
and when; a selecting run compares them at the same point. A file whose content changed runs the
tests that read it, from the first test in each test JVM that read it on, since a value read once
can be kept for later tests (`shares-jvm-with-change`); a read through `getResource`, a file
stream, or a jar read whole counts, by whatever name opened the file: a link, another letter
case, a short name, or a mount or drive alias of the project or of a directory above it. A read
the run cannot place counts as a read of every file. One no test read runs nothing more. The run
names them. A file that appeared or is gone, one outside the root project, a class file, and one the map records no
reads for (a map recorded by 0.2.0, a test JVM whose reads were not all observed) run everything,
naming it (`classpath-files-changed`). A jar or directory a walked jar's manifest `Class-Path`
names is on the test JVM's class path too, so it counts the same way, its class files included,
wherever it lies but in Gradle's module cache. Dependency jars are the classpath rule's.

The JDK's class loader reads each of your jars' manifests itself, to open the jar and to define a
package from it, and keeps only what it acts on: `Class-Path`, `Multi-Release`,
`Automatic-Module-Name`, and the `Specification-*`, `Implementation-*` and `Sealed` attributes a
package reports. A change to one of those runs everything. Any other attribute, such as a
`Build-Time` or a bundle header, reaches a test only through a read of the manifest by code other
than that loader, and runs only the tests from such a read on.

Three ways of depending on such a file are not seen, and each ran everything before 0.3.0: a test
that only checks a changed file's size, a process the test hands the file's path to over a
socket, such as a container or a server, that reads it there, and a read through a mount or drive
alias of a directory inside the project rather than of the project itself. A file a test writes
into such a directory is not there when a fresh checkout's tests start; where the build directory
survives between runs, one a test rewrites with new content each run forces every selecting run,
so have the test write elsewhere in the build directory.

The test task's own `doFirst` actions run after that comparison: the plugin wires the task once
every build script has run, and `doFirst` prepends. So it looks again right before the test JVM
starts, from an action that runs after every other `doFirst`, and a file one of those actions wrote
onto the classpath runs everything, named, as `classpath-files-changed`. The map records the files
as they were before those actions, so a build whose action changes a file runs everything on every
selecting run; write the file in a task the test task depends on instead.

A build that writes the commit, a tag or the time into such a file therefore runs the tests that
read it on every selecting run where that value moved, a `git.properties` from
`gradle-git-properties` or a `Build-Revision` in your own jar's manifest, and everything when the
value sits in an attribute the class loader keeps, such as an `Implementation-Version` with a
commit. A test that reads the value early, or code that reads it for every test, makes that most
of the suite. To keep such builds narrowing, write those values only in release builds, for example
behind a property:

```kotlin
val release = providers.gradleProperty("release").isPresent
tasks.jar {
    if (release) manifest { attributes("Build-Date" to java.time.Instant.now().toString()) }
}
```

and apply `gradle-git-properties`, or its task, the same way. A test that asserts on such a value
then needs the release build to see it.

How often this cost a narrowed run before reads were recorded, measured at the 0.2.0 release on 31
open-source test tasks: in
three, JUnit 5, Resilience4j and OpenTelemetry Java, a jar manifest on the test classpath takes a
new `Build-Time` or new OSGi bundle headers on every build, so no selecting run narrowed even at
the commit the map was recorded at. A day of commits later, Kafka, Gradle, Iceberg, Micrometer and
Grails builds had also moved a commit id, a build date or an SBOM.

Measured again at 0.3.0 (`0326e9d`, 2026-10-20, one run each) on the same tasks, 29 with a result
at the commit the map was recorded at and 28 a day later, this rule ran everything on none of them.
At the recorded commit, Resilience4j narrowed, running 176 of its 234 tests. OpenTelemetry Java
narrowed but still ran all 262: 147 reached by the edit and 115 sharing a test JVM with it, and the
run cannot tell how many of those 115 the moved manifest added. JUnit 5 ran everything because its
map had no coverage for the edited file. A day later no task narrowed, and none ran everything for
this rule.

### The environment fingerprint

A map says which tests a change reaches in the environment it was recorded in. On another JDK, or
with other system properties or another locale, a test can take other paths, so each recording
stores the test JVM's environment beside the map, and a selecting run whose environment differs
runs every test (`environment-changed`), names the first thing that differs, and records the map
again. A map with no fingerprint, as one recorded by 0.2.0, runs everything once
(`environment-unrecorded`). A map recorded with a fresh test JVM per test class is left as it is,
as on every other refusal: selecting runs run everything until a run without `-Pyoriwake.select`
records it again. Both are read from the test task as it starts, before its own
`doFirst` actions from the build script.

What it compares:

- the JDK the test task's launcher names, by runtime version and vendor (or, with no launcher
  metadata, the `executable` path), and the OS and architecture. These are stored as they are and
  named with both values: `jdk.version was 17.0.9+9 when the map was recorded and is 21.0.4+7 now`;
- every system property the task passes to the test JVM, `-D` arguments, argument providers and
  JVM argument files (`@file`, read as the `java` launcher reads them) included, Gradle's own
  (`file.encoding`, `user.language`, `user.country`, `user.variant`) included, yoriwake's
  excluded, as `sys:<name>`. A property given more than once is compared by every value; an
  argument file that cannot be read when the run starts makes the environment differ;
- `TZ`, `LANG` and `LC_ALL`, and the environment variables `environmentInclude` lists, as
  `env:<name>`. A listed name is compared whether or not it is set.

Property and variable values are never stored, printed or logged: each is kept as a SHA-256 digest
salted with 32 random bytes the map's first recording created (`fingerprint-salt`). Before a value
is digested, the root project's directory, the Gradle user home and the user's home directory are
replaced by placeholders, so a checkout in another directory or on another runner is no change.

What it does not compare:

- environment variables nobody listed, even ones a test reads. `yoriwakeExplain<Task>` names the
  variables the tests read by name and are not compared, and says when a test read the whole
  environment, from the last recording;
- what the build sets in its own `doFirst` actions, which run after yoriwake's;
- JVM options other than `-D` (`-Xmx`, `--add-opens`, GC flags), and files on the classpath or in
  the JDK;
- a variable read only by a child process the tests start;
- the machine's own time zone and locale when `TZ`, `LANG` and `LC_ALL` are unset: two machines in
  different zones compare equal. Set `TZ`, or `-Duser.timezone` on the task, to compare it.

A property whose value changes on every build, such as a build number, makes every selecting run
run everything. Leave it out with `systemPropertyIgnore`; the decision record lists what it left
out as `fingerprint-ignored`:

```kotlin
yoriwake {
    systemPropertyIgnore.add("build.number")   // `*` matches any characters
    environmentInclude.add("DB_MODE")          // compared besides TZ, LANG and LC_ALL
}
```

List a variable only when tests behave differently by its value, and never one whose value differs
between the build that saves the map and the builds that restore it, such as `GITHUB_REF`: a pull
request's capture is never saved, so every pull request build would run everything. Never list a
secret. The salt travels with the map, so whoever restores the map can test guesses against a
listed variable's digest; under the CI recipes that includes a pull request's build. System
properties are compared without being listed, so a property that carries a secret (a password, a
token) is digested the same way: leave it out with `systemPropertyIgnore`, such as
`systemPropertyIgnore.add("*password*")`, before relying on a cache other builds restore.

## What coverage does not record

Coverage records what each test executed. Some tests depend on a class without executing it, and
these are the rules that keep them selected:

- **Annotations.** A framework's scan, condition, profile or qualifier, or an `@Aspect`'s pointcut,
  acts on a class through its annotations. An annotation change forces a full run, except in a
  test class this task runs that declares a test (see [above](#when-it-refuses-to-select)). A change
  confined to method bodies narrows as usual.
- **Rewritten bytes.** A weaver, a generator or a post-processor can change a class without
  touching its source. Such a class selects every test that executed it; one no test executed
  forces a full run.
- **Code a JVM runs once, reflection, and file readers.** A static initialiser, or a context built
  once and cached, is credited to the first test that triggers it. A test that reflects on a class,
  or reads its class or source file (an architecture rule, a class-path scanner, a header check),
  executes none of it. So a changed class selects every test that ran at or after the point it was
  first loaded, executed, or had its class or source file read in the same test JVM
  (`shares-jvm-with-change`). Tests that ran before that point stay skipped.
  - An archive a test opens with code other than the JDK's `ZipFile` (a `ZipInputStream` over the
    file, a scanner's own zip reader) counts as a read of every class file in it, and in every
    archive nested in it. An archive is known by its content, not its name: a `.zip`, `.war` or
    `.aar`, or a file with no extension, counts as a `.jar` does.
  - **This widens selection a lot when a suite runs in one JVM.** Discovery loads every test class,
    and often production classes with them, before the first test runs, so a change to any of those
    selects every test in that JVM. [Recording each test class in its own
    JVM](#recording-each-test-class-in-its-own-jvm), an opt-in for the runs that record the map,
    keeps the rule inside the class.
  - A class defined by a class loader of your own, or as a hidden class from your code, counts as
    touched when it is defined, because it can be a second copy of a class the JVM already has.
  - Whatever the agent could not observe in a JVM (a hook it could not install, a record cut short,
    a jar it could not list, a class defined from bytes whose name cannot be read) counts as every
    class touched from that point. So does a child process started from it: what the child reads
    or runs is out of the agent's sight, so every later test in that JVM runs after any change. So
    does native code loaded or reached from outside the JDK, unless it is a [reviewed native
    library](#native-libraries) loaded by its own class.
- **An edit to a test class.** Every test of an edited test class that the map knows runs, whatever
  its recorded coverage. A test class is exempt from the rule above when it declares a test
  method (JUnit Jupiter's `@Test`, `@RepeatedTest`, `@TestFactory`, `@TestTemplate` or
  `@ParameterizedTest`, JUnit 4's `@Test` or TestNG's `@Test`), the map holds tests of it, and no
  other class or resource of this build names it. An edit to it then selects its own tests and what
  coverage selects, plus every test at or after the first of its own tests in that JVM, since its
  code can hand its class to whatever runs later, and every test at or after the first time, after
  discovery, that something looked it up by name, defined a copy of it in another class loader, read
  its class or source file, or executed it from another test class. The tests before those points
  stay skipped. It falls back to the wider rule, so that every test in its JVM runs on an edit to
  it, when:
  - a test in that JVM ran on an engine other than JUnit Jupiter, JUnit Vintage, the JUnit Platform
    Suite, JUnit 4, TestNG, Spock, Kotest, ArchUnit or jqwik;
  - your own code ran in that JVM during discovery: a class or method orderer, a display-name
    generator, a post-discovery filter, a discovery or launcher session listener, a runner or engine
    that runs test code while it builds the tests. Discovery hands such code every test class;
  - a lookup hook could not be installed, or a native agent (`-agentlib`, `-agentpath`) is attached.

  From the point where a [reviewed native library](#native-libraries) is loaded, or something asks
  the JVM for every loaded class, every later test in that JVM runs on an edit to it.

## Native libraries

Native code can read any file, a class file or a jar included, without a call the agent can see.
So from the point where code outside the JDK loads a native library (`System.load`,
`System.loadLibrary`), or uses the foreign-function API (`Linker.nativeLinker`,
`SymbolLookup.libraryLookup`), every later test in that JVM runs after any change. The JDK's own
libraries do not count.

The exception is a library on the list below, loaded by the class listed with it. Its native
sources were read at the version shown, and every file it opens, maps or lists was traced to where
its path comes from; none can be a class file or a jar unless a test passes that path as data. A
listed library still counts as a lookup of every class, since it can find any class by name. An
entry matches on the loading class, or a class nested in it, and on the file name its own loader
gives the library, including the renamed copy it extracts to a temporary directory. The same file
loaded by any other class, a shaded copy, or a file renamed through the library's own override
property applies the wide rule. A file of the same name on `java.library.path` is taken to be the
library.

A load counts once the JDK has opened a library: judged by the file it resolved, not the name asked
for, so a class loader's `findLibrary` that returns another file is judged by that file. An attempt
that finds no file, as a library trying several names for its platform makes, is not a load. On
macOS 11 or later, where system libraries have no file on disk, the JDK skips that check and an
attempt is not a load only when opening it fails.

| Library | Loading class | Reviewed | What its native code opens |
|---|---|---|---|
| Netty epoll transport | `io.netty.util.internal.NativeLibraryUtil` | [4.2.18.Final](https://github.com/netty/netty/tree/netty-4.2.18.Final/transport-native-epoll/src/main/c) | `/proc/sys/net/ipv4/tcp_fastopen`; a file whose path is passed to `FileDescriptor.from`, opened write-only; otherwise only descriptors Java opened |
| Netty kqueue transport | `io.netty.util.internal.NativeLibraryUtil` | [4.2.18.Final](https://github.com/netty/netty/tree/netty-4.2.18.Final/transport-native-kqueue/src/main/c) | a file whose path is passed to `FileDescriptor.from`, opened write-only; otherwise only descriptors Java opened |
| Netty io_uring transport (conditional, below) | `io.netty.util.internal.NativeLibraryUtil` | [4.2.18.Final and 4.2.19.Final](https://github.com/netty/netty/tree/netty-4.2.19.Final/transport-native-io_uring/src/main/c) only | a probe file in the temporary directory, written and never read; as epoll otherwise; the kernel opens nothing for the operations Netty submits |
| Netty io_uring transport (incubator) | `io.netty.util.internal.NativeLibraryUtil` | [0.0.26.Final](https://github.com/netty/netty-incubator-transport-io_uring/tree/netty-incubator-transport-parent-io_uring-0.0.26.Final/transport-native-io_uring/src/main/c) | a probe file in the temporary directory, written and never read; as epoll otherwise |
| netty-tcnative BoringSSL build (conditional, below) | `io.netty.util.internal.NativeLibraryUtil` | [2.0.84.Final](https://github.com/netty/netty-tcnative/tree/netty-tcnative-parent-2.0.84.Final) only | certificate and key files a test names by path; `/dev/urandom` where `getrandom` is missing |
| Netty macOS DNS resolver | `io.netty.util.internal.NativeLibraryUtil` | [4.2.19.Final](https://github.com/netty/netty/tree/netty-4.2.19.Final/resolver-dns-native-macos/src/main/c) | nothing; it asks the system's DNS configuration service, which reads its fixed files in its own process |
| Conscrypt | `org.conscrypt.NativeLibraryUtil` | [2.7.0](https://github.com/google/conscrypt/tree/2.7.0/common/src/jni) | `/dev/urandom` where `getrandom` is missing; no path from Java |
| Amazon Corretto Crypto Provider | `com.amazon.corretto.crypto.provider.Loader` | [2.5.0](https://github.com/corretto/amazon-corretto-crypto-provider/tree/2.5.0/csrc) (AWS-LC v1.47.0) | `/dev/urandom` where `getrandom` is missing, and `/dev/sysgenid`; no path from Java, and no configuration file |
| snappy-java | `org.xerial.snappy.SnappyLoader` | [1.1.10.8](https://github.com/xerial/snappy-java/tree/v1.1.10.8/src/main/java/org/xerial/snappy) | nothing |
| zstd-jni | `com.github.luben.zstd.util.Native` | [1.5.7-20](https://github.com/luben/zstd-jni/tree/v1.5.7-20/src/main/native) | nothing |
| lz4-java (`at.yawk.lz4`, and `org.lz4` 1.8.0) | `net.jpountz.util.Native` | [1.12.0](https://github.com/yawkat/lz4-java/tree/v1.12.0/src/jni) | nothing |
| RocksDB | `org.rocksdb.NativeLibraryLoader` | [11.8.1](https://github.com/facebook/rocksdb/tree/v11.8.1/java/rocksjni) | the files of a database in the directories a test names, only by the names RocksDB itself writes; fixed `/proc` and `/sys` paths |
| SQLite JDBC (conditional, below) | `org.sqlite.SQLiteJDBCLoader` | [3.53.4.0](https://github.com/xerial/sqlite-jdbc/tree/3.53.4.0/src/main/java/org/sqlite/core) only | the database files a test names and the journal, WAL and lock files SQLite names after them; random temporary files; `/dev/urandom` |

Two notes on the list:

- `RocksDB.loadLibrary`, which RocksDB calls for itself, first tries to load the system's
  compression libraries by name (`snappy`, `z`, `bzip2`, `lz4`, `zstd`); an attempt that finds no
  file is no load. Found, one counts as reviewed only when RocksDB loads it at a version reviewed,
  read from the file the JDK resolved: zlib 1.2.11 and 1.3, snappy 1.1.8 and 1.1.10, lz4 1.9.3 and
  1.9.4, zstd 1.4.8 and 1.5.5 (Ubuntu 22.04 and 24.04), and Homebrew's zlib 1.3.2, snappy 1.3.1,
  lz4 1.10.0 and zstd 1.5.7. Any other version, macOS's own `libz.dylib`, whose name carries none,
  and bzip2 apply the wide rule.
- Netty's io_uring transport is listed on a condition. Its public API lets any caller hand the
  kernel an operation, a file open among them. Every operation reaches the ring through one Java
  method, `SubmissionQueue.enqueueSqe`, which the agent checks as it is entered: one of the 19
  operations Netty itself submits goes on, and any other, before the kernel sees it, touches every
  class from there. No operation that takes a path or gives a file a new name is among the 19. The
  entry counts only at the versions listed, read from the classes jar's own version file, and only
  when that method in the loading class's loader takes the check; otherwise the load applies the
  wide rule.
- Reviewed and left off: netty-tcnative's OpenSSL build, which reads a configuration file and
  loads modules from paths the environment names; and JNA, which calls any native function.
- netty-tcnative's BoringSSL build is listed by what the file is, not its name, which its OpenSSL
  build shares: the loaded file counts only when its bytes equal the native resource of a jar on
  the loading class's class path whose Maven metadata names `netty-tcnative-boringssl-static`, and
  no other artifact of Netty's, at the version listed. A copy on `java.library.path` counts when
  it is byte for byte that resource. On macOS, a set `DYLD_` variable applies the wide rule, since
  it can load another library than the file compared.
- SQLite JDBC is listed on a condition. Once a connection enables extension loading (the
  `enable_load_extension` property, `SQLiteConfig.enableLoadExtension(true)`,
  `SQLiteDataSource.setLoadExtension(true)`, or a direct call), SQL can load any native library by
  name. Every one of those ends in the driver's native `NativeDB.enable_load_extension`, which the
  agent wraps as the class is defined, so a call that turns it on touches every class from there.
  The entry counts only at the exact version listed, read from the driver jar's own version file,
  and only when the JVM can wrap a native method and the driver's `NativeDB` in the loading class's
  loader takes the wrap; otherwise the load applies the wide rule. On Linux the driver runs `uname`
  to find its library unless `org.sqlite.lib.path` names its folder, and that child process runs
  every later test in the JVM anyway.
- Only the Linux and macOS builds (`.so`, `.dylib`, `.jnilib`) were reviewed. A library's Windows
  build (`.dll`) is not on the list, so on Windows each of these applies the wide rule.

An entry covers the version reviewed. Each is reviewed again when its library ships a new major
version, and until then a later version counts as the one reviewed. To propose a library, open a
[native library proposal](https://github.com/zeuspizza/yoriwake/issues/new?template=native-library.yml):
it asks for its version, a link to its native sources at that tag, every file open, `mmap`,
directory scan and `dlopen` in them with where each path comes from, the file names its loader
gives the library, and the class that calls `System.load` or `System.loadLibrary`.

## Your JaCoCo report

- **A run yoriwake instruments** leaves the test task's JaCoCo destination file holding what the
  run executed, as it would without yoriwake. The agent takes JaCoCo's data and resets it around
  every test, so after the tests yoriwake appends each test's record to what JaCoCo wrote. A run
  whose tests failed has its file completed before the project's JaCoCo report and verification
  tasks run. JaCoCo's Sessions page then lists one session per test, and the file is about as
  large as the run's own records under `.gradle/yoriwake`: it grows with tests times instrumented
  classes, and it is part of the test task's build cache entry.
- **A run yoriwake does not instrument** leaves no execution file: Gradle removes the previous
  run's, so `jacocoTestReport` and `jacocoTestCoverageVerification` are skipped for want of data.
  A skipped verification has checked nothing. These runs are:
  - selecting runs that narrow;
  - selecting runs that run everything while the map already covers the commit;
  - selecting runs that run everything over a map recorded with a test JVM per class;
  - runs declined over in-JVM parallelism.
- **A job whose coverage report matters** runs without `-Pyoriwake.select`. It records, so it
  reports everything it ran.

## Kover

Tested with Kover 0.9.8 on Gradle 8.14 and 9.8.0.

- **Kover's default agent** runs beside yoriwake. Each test's captured classes and the JaCoCo
  report are the same as without Kover, and Kover's report is the same as with
  `-Pyoriwake.disabled`. Applying Kover first does not change what each test captures.
- **On a narrowed run** Kover's agent still runs, over the selected tests only. `koverXmlReport`
  covers only those tests, and `koverVerify` can fail a rule the full suite meets. Run Kover's
  reports and verification in the job that records, without `-Pyoriwake.select`.
- **Kover's JaCoCo mode (`useJacoco()`)** adds a second JaCoCo agent. Beside Gradle's `jacoco`
  plugin the test JVM fails at start (`duplicate class definition for java.lang.$JaCoCo`), with or
  without yoriwake. Where it does run, with Gradle's JaCoCo switched off on the test task or not
  applied, yoriwake has no JaCoCo to capture through: it captures and selects nothing for that
  task, and Kover's report is whole.

## Requirements

- **The [supported matrix](#supported-matrix):** the Gradle versions, daemon and test-JVM JDKs,
  operating systems and test engines a release was tested on.
- **`git` on the daemon's `PATH`.** A git that cannot be started is a change set that cannot be
  computed, so every selecting run is a full run and says so.
- **A [supported host](#supported-hosts) plugin** on the project whose tests are selected.

### Supported matrix

A release supports what its functional suite ran, green, on the commit the release was tagged
from. Each cell is one job of `.github/workflows/matrix.yml`: an operating system, a Gradle version
and the JDK Gradle's daemon runs on, written `<os>/<gradle>/<jdk>` below. Each row names the cells
that ran it and the tests that prove it, and a check on every pull request keeps the table and the
cells in step.

| Axis | Value | Cells | Proved by |
|---|---|---|---|
| Gradle | 8.14 | `*/8.14/*` | `MatrixCellIdentityTest` |
| Gradle | 9.8.0 | `*/9.8.0/*` | `MatrixCellIdentityTest` |
| Daemon JDK | 21 | `*/*/21` | `MatrixCellIdentityTest` |
| Daemon JDK | 25, on Gradle 9.8.0 | `*/9.8.0/25` | `MatrixCellIdentityTest` |
| Test JVM JDK | 11 | all | `TestJvmRangeFunctionalTest.test JVM on JDK 11` |
| Test JVM JDK | 17 | all | `TestJvmRangeFunctionalTest.test JVM on JDK 17` |
| Test JVM JDK | 21 | all | `TestJvmRangeFunctionalTest.test JVM on JDK 21` |
| Test JVM JDK | 25, on Gradle 9.8.0 | `*/9.8.0/*` | `TestJvmRangeFunctionalTest.test JVM on JDK 25` |
| OS | Linux | `linux/*/*` | `SelectionFunctionalTest` |
| OS | macOS | `macos/*/*` | `SelectionFunctionalTest` |
| OS | Windows | `windows/*/*` | `SelectionFunctionalTest` |
| Engine | JUnit Jupiter 5.11.4, narrowed | all | `SelectionFunctionalTest.a change selects only the tests that reach it` |
| Engine | JUnit Jupiter 6.1.3, narrowed | all | `EngineSupportTest.a Jupiter suite on JUnit 6 is captured and selected` |
| Engine | JUnit 4.13.2 on the vintage engine 5.11.4, narrowed | all | `EngineSupportTest.a JUnit 4 suite on the vintage engine is captured and selected` |
| Engine | Spock 2.3-groovy-4.0, narrowed | all | `EngineSupportTest.a Spock specification is captured and selected` |
| Host | `java` | all | `SelectionFunctionalTest` |

The same run captures a map on Linux and selects against it on Linux, and shows that a map from
another operating system is refused: one from Linux on macOS, and one from macOS on Linux, each run
everything as `environment-changed` ([The environment fingerprint](#the-environment-fingerprint)).

- **Gradle.** 8.14 is the plugin's compile floor; 9.8.0 is the newest version run. Versions between
  them are not run. A newer Gradle is untested, not declined: the plugin keeps running there.
  Below 8.14, on a JDK 21 daemon, the plugin declines: every run is a full run, and the audit
  reports `gradle-too-old`. On an older daemon JDK the plugin does not resolve at all: its metadata
  asks for JVM 21, so the build fails. Gradle before 8.5 cannot run on JDK 21 (Gradle's
  compatibility matrix), so it always takes this path.
- **Daemon JDK.** The plugin's classes are compiled for Java 21, so 21 is the floor.
- **Test JVM JDK.** The agent is compiled with `release = 11`, so 11 is the floor. A test JVM on 25
  is tested with Gradle 9.8.0 only: Gradle runs Java 25 toolchains from 9.1.
- **Not in the table, so not tested on a release:** Android hosts (below), and Kotlin Multiplatform
  until a release's matrix run has run its functional test; Kotest, plain JUnit 4 and TestNG, which the
  suite runs in every cell but which are captured and always run in full rather than narrowed
  ([JUnit 4 and the vintage engine](#junit-4-and-the-vintage-engine), [Kotlin](#kotlin)).

### New JDKs

Three readers parse class files whose version each new JDK raises, and each has a ceiling:

| Reader | Reads | Ceiling in this release | Above it |
|---|---|---|---|
| The plugin's ASM | the task's own compiled classes, for digests, constants, annotations and inline bodies | Java 27 | `class-file-unsupported` |
| Your project's JaCoCo (`jacoco.toolVersion`) | the same classes, to instrument them | the newest Java its release notes officially support; experimental support does not count | `class-file-unsupported` |
| The agent's ASM | the test JVM's own JDK classes, to see what tests read | Java 27 | `test-jvm-unsupported` |

A selecting run checks both before anything else that reads the classes, from each class file's
header, so it holds whatever the map records. It runs every test, names the first class at the
newest version (or the test JVM's Java version), each reader it is above, and what lifts it:

- **`class-file-unsupported`**, from the plugin's ASM: a newer yoriwake. From JaCoCo: a
  `jacoco.toolVersion` that officially supports that Java. A JaCoCo version yoriwake does not know
  is held to the newest one it does, and the run says so in one line. A build that swaps the JaCoCo
  agent artifact is judged by the `toolVersion` it sets. A multi-release jar entry counts only where
  the test JVM would load it.
- **`test-jvm-unsupported`**: a newer yoriwake, or a test toolchain of an older Java. When Gradle
  cannot report the test JVM's version (an `executable` it cannot probe), the agent checks the JVM
  it runs in: the run is a full run of that kind, and a recording run marks every class touched.

A recording run in either situation records as usual and prints one line saying selecting runs will
run every test, and why. `yoriwakeAudit<Task>` reports both as blockers.

Gradle 8.14 defaults to JaCoCo 0.8.13 (Java 24) and Gradle 9.8.0 to 0.8.15 (Java 26), so a project
compiling for a newer Java on its default JaCoCo runs every test until `jacoco.toolVersion` is
raised.

### Supported hosts

The plugin attaches to a project that applies one of these:

- `java`, and the plugins that apply it, such as `java-library` and `org.jetbrains.kotlin.jvm`;
- `com.android.application`, `com.android.library`, `com.android.test` or
  `com.android.dynamic-feature`, for their unit-test tasks;
- `org.jetbrains.kotlin.multiplatform`, for a module with a JVM target: a `src/jvmMain` or
  `src/jvmTest` directory beside at least one `<target>Main` source directory.

Kotlin Multiplatform is tested with the Kotlin Gradle plugin 2.1.20 on Gradle 8.14 and 9.8.0
(`MultiplatformFunctionalTest`), with the plugin applied and through the init script. The plugin
reads a multiplatform module by that directory layout, not through the Kotlin plugin's model:

- **Narrowed:** a module with `jvm()`. A change in `commonMain` or `jvmMain` runs the JVM tests that
  ran it; an edited `commonTest` class in a package the module's main code uses runs (one in a
  package of its own is a change no class answers to, and runs everything); a module, multiplatform
  or `java`, that depends on it runs its own tests that ran the change. A changed `expect` default
  runs its callers through the `actual`'s class, whose bytes changed.
- **Runs everything:** a changed `const val`; a changed `inline` function whose file declares
  nothing a test runs, since its callers are found ([Kotlin](#kotlin)) but its own class has no
  coverage; a platform-suffixed file such as `Platform.jvm.kt`, which compiles to `Platform_jvmKt`
  rather than the class its path names; a change in a non-JVM source set (`jsMain`, `nativeMain`),
  in a module with no JVM target, or in a module the plugin declines.
- **Declined** (`kotlin-multiplatform`): a JVM target with its own name, such as `jvm("desktop")`,
  whose sources are not under a `jvm*` directory.
- **Not covered:** `withJava()` on a Kotlin Gradle plugin before 2.1.20, where the module also
  applies `java`, its own packages leave the scope, and every change runs everything; source
  directories moved out of `src/<set>/kotlin`, or JVM sources in an intermediate source set, which
  are not in the functional test; `androidTarget()` beside `jvm()`, where `jvmTest` narrows with the
  plugin applied and the Android unit tests always run in full, but through the init script with
  the configuration cache on the build currently fails. Native, JS and Wasm test tasks are not
  `Test` tasks and always run in full.

On any other project with test tasks it declines by name. Nothing is captured or selected there,
every run is a full run, and `yoriwakeAudit<Task>` reports one of these as a blocker:

| Token | Meaning |
|---|---|
| `no-host-plugin` | The project has test tasks but applies none of the plugins above. Apply `java` or an Android plugin to the project whose tests you want selected. |
| `kotlin-multiplatform` | A Kotlin Multiplatform module whose JVM target the plugin cannot see, or cannot scope from its own source directories. |

### Windows

The functional suite runs on Windows in every cell of the matrix above, and on every pull request
from a checkout with Windows line endings. A map is per operating system: one recorded on Linux or
macOS makes a selecting run on Windows run every test as `environment-changed`, and the reverse, so
a CI job that tests on several keeps one map per operating system, with the OS in its cache key.
Three things differ:

- **Paths longer than 260 characters.** Git for Windows lists them only with long paths enabled
  (`git config core.longpaths true`). Without it, git fails on such a checkout, the change set
  cannot be computed, and every selecting run is a full run that says so.
- **Line endings, when a map or a selection record moves between Windows machines.** Under
  `core.autocrlf`, Git for Windows' default, text files check out with `\r\n`, and resources
  copied onto the test classpath keep it. A copy of a tracked text file that git converts compares
  by its content with line endings aside, as git's own diff does, so a map or a
  [selection record](#running-what-a-selection-skipped) crosses a developer's `autocrlf` checkout
  and a CI runner's without it. A generated file, a binary one, and one `.gitattributes` pins
  (`eol=lf`, `-text`, `binary`) still compare by their bytes: one that differs only in its line
  endings runs every test, as `classpath-files-changed`. A test that asserts a text resource's exact
  bytes can pass in one checkout and fail in the other, and selection does not see that; pin such a
  file with `eol=` in `.gitattributes` so every checkout writes it alike.
- **Native libraries.** No Windows build is on the [reviewed list](#native-libraries), so on
  Windows every native library applies the wide rule.

## What is not supported

- **Large multi-project builds with many untracked or ignored files, at execution.** While
  configuring, a selecting run lists the working tree once per build. Every `Test` task that a
  selecting or recording run then executes lists it again with git, ignored files included, so it
  never selects on, or records against, a tree an earlier task changed. That costs what `git
  ls-files --others` costs on the tree, once per executing task: up to about 9 seconds on
  spring-boot (about 265,000 untracked and ignored files, most of them TestKit output under
  `buildSrc/build`; measured 2026-10-03 with this release). Across all 527 of its test tasks that
  comes to over an hour (computed, not measured). Builds with few test tasks or a clean tree are
  unaffected.
- **Order-dependent tests.** Selection removes tests, so it changes what ran before what. Coverage
  cannot see that dependency. Pin the ones you know about: `@Tag("yoriwake-always-run")`,
  `yoriwake { alwaysRun.add("com.acme.FlakyTest") }`, or `-Pyoriwake.alwaysRun=<glob>`. A pin only
  ever adds tests, and a pattern that matches nothing is reported.
- **State kept outside the test JVM.** A server, a database, the network, or a file one test JVM
  writes and a later one reads. The agent sees only its own JVM, and a socket or a database
  connection says nothing about which classes the other side depends on; forcing on them would run
  most integration suites in full every time. A child process started from a test JVM is the one
  case it can see begin, so every later test in that JVM runs after any change.
- **What the agent cannot observe.** The rules in [what coverage does not
  record](#what-coverage-does-not-record) rest on what the agent sees in the test JVM: every class
  a class loader defines and every hidden class your code defines, every file open, copy or link
  through `FileInputStream`, `RandomAccessFile`, `ZipFile` and the default file-system provider
  (which `Files`, `FileChannel` and a direct provider call all reach), every lookup by name through
  a public API, every child process, and every native library load and use of the foreign-function
  API. A library load, a class definition or a foreign-function call counts as the JDK's own only
  when the class making it is one the runtime image holds in a `java.*` or `jdk.*` module. A class
  on `-Xbootclasspath/a`, in a `Boot-Class-Path` agent jar, or appended through
  `appendToBootstrapClassLoaderSearch` (as Mockito's inline mock maker does) is yours, and what it
  does is recorded. A test JVM that patches a JDK module (`--patch-module`) or upgrades one
  (`--upgrade-module-path`), or whose own arguments cannot be read, counts every class touched from
  its start, so it narrows nothing: code inside a JDK module calls that module's internals without
  a call the agent can see. A test is skipped, when nothing else selects it, if it depends on a
  changed class only through:
  - a [reviewed native library](#native-libraries) given the path of a class file or jar as data,
    such as a database file or directory a test names. The review found no other way for one to
    read a class file;
  - a call, made by reflection with `setAccessible`, to the renamed native method the agent leaves
    behind when it wraps [SQLite JDBC's](#native-libraries) `enable_load_extension`. The renamed
    method is private and synthetic, so no lookup of public methods reaches it by accident;
  - an operation written into [Netty's io_uring](#native-libraries) ring without its
    `SubmissionQueue.enqueueSqe`, by reflection into Netty's package-private classes;
  - the JDK's class-loading or library-loading internals called by reflection (a native lookup
    such as `Class.forName0`, a class loader's protected `loadClass(String, boolean)`, or
    `ClassLoader.loadLibrary`), which needs `java.base` opened to your code. A class your code
    defines into a JDK package that way reaches them with no further flag, and one it defines under
    the exact name of a JDK class not yet loaded counts as that JDK class. No public entry point is
    on that path, a native method cannot be wrapped once its class is loaded, and code generators
    reflect into class loaders routinely to define classes, so forcing on such reflection would
    widen ordinary suites;
  - a class inside a dependency jar that names one of your test classes. The JVM resolves such a
    reference without a call the agent can see, and the check that nothing else names a test class
    searches this build's own classes and jars and every resource, not the classes of dependencies;
  - an edited test class nothing else names, through code that is handed the class without a lookup
    before the class's first test, but is not your build's own code run during discovery: a class
    orderer or listener from a dependency jar, or a test execution listener whose plan-start
    callback runs after yoriwake's own.
- **Build output a test reads by path.** A file under a build directory that is not on the test
  runtime classpath, such as a fixture under `build/` a test opens with `File`, is build state and
  never a change, so one generated from git state, the clock or the environment can change while
  every tracked source stays the same. A test that asserts on its content is skipped when nothing
  else selects it. Pin such tests, or put the file on the test classpath, where it [counts by
  content](#generated-files-on-the-test-classpath).
- **Build steps that write into the working tree.** A file outside the build directories that a
  build step writes during a capture, such as a generator writing into `src/`, was touched while the
  tests ran, so it counts as changed on every selecting run until a capture in which nothing writes
  it, and the tests that reach it always run.
- **A nested JUnit launcher the agent cannot tell apart.** A launcher a test starts from the test
  class path itself, not from a class loader of its own, is recognised as nested because the outer
  test plan is still executing, which the agent learns through its JUnit Platform listener. With
  listeners switched off (`junit.platform.execution.listeners.deactivate`), or for a launcher
  created while the outer launcher is still discovering tests, it is filtered like the outer one:
  it can deselect tests the map holds, and its decisions are written beside the outer launcher's.
  A launcher running on agent classes another class loader defined is never filtered.
- **Gradle older than 8.14.** API the plugin reads is missing there, and reading around the gap
  could skip a test that should run. On a JDK 21 daemon the plugin declines: every run is a full
  run, and the audit reports `gradle-too-old`. On an older daemon JDK Gradle cannot resolve the
  plugin, because its metadata asks for JVM 21: the build fails loudly rather than running in full.
- **Gradle Isolated Projects.** The instrumentation scope is derived by reading every project in the
  build, which Isolated Projects forbids. The plugin detects it and declines: every run is a full
  run, and the audit reports `isolated-projects`.
- **In-JVM parallelism** (JUnit Jupiter's parallel mode, TestNG `parallel` / `threadCount`).
  Interleaved tests in one JVM share one coverage agent and cannot be told apart. The task runs in
  full, nothing is captured, the map is left as it was, and the audit reports `in-jvm-parallelism`.
  Parallel forks (`maxParallelForks`) are fine. It is detected from the test task's system
  properties and JVM arguments, `junit-platform.properties` on the test runtime classpath, and the
  TestNG options. Parallelism switched on any other way, such as a JUnit `@Suite`'s configuration
  parameters or `JAVA_TOOL_OPTIONS`, is not detected: the task is not declined, and tests that
  overlap in one JVM are recorded as unknown and always run.
- **Two builds at once in one project.** Two builds running the same test task at the same time
  share one change-set file, so one build's test JVM can read the change set the other wrote.
- **A working directory the complement stamp does not see.** The record's stamp does not include
  the test task's `workingDir`, so a [complement run](#running-what-a-selection-skipped) whose task
  differs from the selecting run's only there uses its record.
- **Develocity Test Distribution and Predictive Test Selection.** A test task on which either is
  enabled when it runs, through the `develocity` task extension (Develocity 3.17+), the
  `distribution` or `predictiveSelection` extensions of the Gradle Enterprise plugin, or
  `-Dpts.enabled=true`, is declined: yoriwake neither selects nor records there, its agent is not
  attached, none of its paths reach the test JVM, the host's JaCoCo stays switched on, and the map is
  left exactly as it was. The JaCoCo scope yoriwake sets at configuration (its includes when the
  host sets none, classes with no code-source location, the excluded reflection class loaders)
  still applies, as on a task it declines for any other reason. Test Distribution may run the tests on other machines, and
  even run locally it records only part of the suite's coverage; Predictive Test Selection leaves
  out tests yoriwake would keep. The console names the feature and the setting that turns it off
  for the task, and the audit reports `develocity-test-distribution`, `develocity-test-selection`,
  or `develocity-undetermined` when the extension is there but its switches cannot be read. Checked
  against the Develocity plugin 4.6.0, 3.17 and Gradle Enterprise 3.19.2 and 3.16.2 on 2026-10-06,
  with Test Distribution run locally; what a remote Test Distribution agent does with the agent jar
  on the classpath is not yet investigated. To use yoriwake on such a task, turn the feature off
  for it; how the two could compose is not yet investigated.
- **Plain JUnit 4 and TestNG selection.** Captured, never narrowed; see the next section.
- **Changes coverage cannot see.** Resources, build scripts and version catalogs force a full run by
  design.
- **Tests that commit, check out or stash.** A capture during which HEAD or its reflog moves is not
  kept, so a suite whose tests do that in the project's own repository never updates its map.
  Selection still widens from the map's commit; it never skips a test for it.
- **Files the tests write into the source tree.** A tracked file a test rewrites, even back to the
  same content, stays in every selecting run's change set until the next capture, so a test that
  rewrites one on every run keeps it there. A file the tests create outside the build directory is
  in the change set of every fresh checkout that lacks it, such as a CI runner's, which then runs
  everything. See [Files written into the source tree](#files-written-into-the-source-tree).
- **Java agents that rewrite JDK methods.** An agent the build attaches to the test JVM can insert
  a call into a JDK class's bytecode, which then runs as the JDK's own code, and can remove the
  hooks yoriwake's agent relies on. Such calls are not recorded, and a test that depends on them can
  be skipped. Such agents are trusted as the JDK is.
- **Runtime images holding more than the JDK, and exploded JDK builds.** A class the runtime image
  holds outside the `java.*` and `jdk.*` modules (JavaFX in some distributions) counts as yours, so
  a native library it loads makes every later test in that JVM run after any change. An exploded
  JDK build's classes are not in an image, so all of them count as yours. Both select wider, never
  narrower.
- **Edits that do not change bytecode.** A comment or formatting change is already served by
  Gradle's build cache, which is better than this tool for anything it covers.

## JUnit 4 and the vintage engine

Deselection is a JUnit Platform `PostDiscoveryFilter`. A task that never calls
`useJUnitPlatform()` runs on JUnit 4's own runner, so the filter is never consulted. Such a task
is still captured (the agent hooks JUnit 4's `RunNotifier`; TestNG through a listener) and keeps
its map current, but it runs its whole suite and says so on every selecting run. The audit reports
it as `not-on-junit-platform`.

The fix is the JUnit team's own supported path: run JUnit 4 tests through the vintage engine.

```kotlin
dependencies {
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:<version>")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.test { useJUnitPlatform() }
```

To try it without editing the build, [`scripts/junit-vintage.init.gradle.kts`](../scripts/junit-vintage.init.gradle.kts)
applies the same change: `./gradlew -I scripts/junit-vintage.init.gradle.kts test`.

- Check that the suite runs the same tests under vintage before trusting a selection on it: the
  test runtime gained an engine.
- It does not make JUnit 3 `TestCase`s, or a custom `Runner` that bypasses the Platform,
  selectable. Where it does not apply, the run forces.
- Robolectric suites are unverified. On some projects their classes fail Platform discovery, and
  the vintage engine cannot help them; on others they are discovered and run through it.
  Selection is as safe there as anywhere, but how far it narrows has not been measured.

## Kotlin

- JUnit 5 on the Platform captures and narrows. Kotest 5 captures.
- Kotest specs always run under selection: the Kotest engine runs its own spec list and would run
  nothing if one spec were left out. `decisions.tsv` records them as `ENGINE_RUNS_EVERYTHING`.
- An `inline` function's body is compiled into its call sites, so a caller runs it without
  executing the declaring class and coverage records no edge. The plugin reads the
  `SourceDebugExtension` Kotlin emits for debuggers to find who inlined a changed file, and adds
  those callers to the change.
- A build whose Kotlin classes carry no `SourceDebugExtension` at all cannot answer that question,
  so any changed Kotlin source forces a full run. A change set of Java sources alone still narrows.
  The audit reports `smap-absent`.

## CI

A CI job starts with no map, so every run captures and none selects unless one is restored.

On GitHub Actions, use [yoriwake-action](https://github.com/zeuspizza/yoriwake-action), at the
release named after the plugin's (`v0.2.0` for `0.2.0`), in place of the step that runs Gradle:

```yaml
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v5
        with: { distribution: temurin, java-version: '21' }
      - uses: zeuspizza/yoriwake-action@v0.2.0
```

In one step it caches the map, fetches the history selection needs, records on the default branch,
and on pull requests selects only from a map a run of the default branch vouched for (see [Who can
write the map you restore](#who-can-write-the-map-you-restore)). Its README has [the whole
workflow](https://github.com/zeuspizza/yoriwake-action#usage), with the permissions and events it
needs, and lists [when it runs
everything](https://github.com/zeuspizza/yoriwake-action#when-it-runs-everything) and the warning
each case prints.

### Without the action

On another CI system, or in a workflow written by hand: cache `.gradle/yoriwake`, restore the
newest one available, and let a miss run everything.

Where one test command runs on every event, list the repository's default branch, so the command
can select elsewhere and still run everything, and refresh the map, on that branch:

```kotlin
yoriwake { fullRunBranches.add("main") }
```

The list is per project: a project that applies the plugin without it selects on the default
branch, and its map is never refreshed. In a multi-project build, set it in the convention plugin
that applies yoriwake, or for every project from the root build script:

```kotlin
allprojects {
    plugins.withId("io.github.zeuspizza.yoriwake") {
        configure<io.github.zeuspizza.yoriwake.gradle.YoriwakeExtension> {
            fullRunBranches.add("main")
        }
    }
}
```

```yaml
env:
  YORIWAKE_VERSION: 0.2.0       # the version your build applies

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0         # selection diffs against a base; a shallow clone has none
      - uses: actions/setup-java@v5
        with: { distribution: temurin, java-version: '21' }
      - uses: gradle/actions/setup-gradle@v4

      - uses: actions/cache/restore@v4
        with:
          path: .gradle/yoriwake
          key: yoriwake-${{ runner.os }}-${{ github.job }}-${{ env.YORIWAKE_VERSION }}-${{ github.sha }}
          restore-keys: yoriwake-${{ runner.os }}-${{ github.job }}-${{ env.YORIWAKE_VERSION }}-

      # Records: this workflow passes no trusted-map list, so it does not select.
      - run: ./gradlew test

      # Not cached: an excluding `!` path under a cached directory keeps nothing out.
      - if: github.ref == format('refs/heads/{0}', github.event.repository.default_branch)
        run: rm -rf .gradle/yoriwake/*/raw .gradle/yoriwake/*/selection.tsv
      - if: github.ref == format('refs/heads/{0}', github.event.repository.default_branch)
        uses: actions/cache/save@v4
        with:
          path: .gradle/yoriwake
          key: yoriwake-${{ runner.os }}-${{ github.job }}-${{ env.YORIWAKE_VERSION }}-${{ github.sha }}
```

This hand-written workflow only records and saves. A selecting step in it would narrow from
whatever its restore found, a cache entry a pull request saved included; leave selection on pull
requests to the action, which passes the trusted-map list.

### What the key is for

- **OS.** Maps are plain text, but a map is only shown to move between Linux and macOS; keeping
  one per OS costs nothing.
- **Job.** The directory holds one map per test task, and a job that runs a different set of tasks
  should not overwrite another job's maps.
- **Plugin version.** A map records its format version, and a plugin reading a different one
  refuses the whole map. With the version in the key, an upgrade is an ordinary cache miss: one full
  run, which captures.
- **JDK, when the job tests on several.** A map recorded on another JDK makes a selecting run run
  everything as `environment-changed`, so each leg of a JDK matrix keeps its own map: add the
  leg's JDK to the key, such as `${{ matrix.java }}`.
- **Commit, with a prefix fallback.** An exact hit almost never happens. A map from an older commit
  is still useful and still safe.
- **Not `raw/`,** deleted before the save. The undecoded records are cleared by the next capture
  before it writes anything.
- **Not `selection.tsv`,** deleted before the save. A complement run leaves out the tests it lists,
  and no map digest covers it, so it comes only from the selecting job of the same workflow run,
  never from a cache.

### How much cache it takes

The action saves one entry per default-branch commit for each job and matrix leg that uses it; the
recipe above does the same per job. GitHub keeps 10 GB of caches per repository by default, shared
with every other cache the repository saves, and administrators can raise it. Over the limit it
evicts the entries accessed longest ago, and it removes any entry not accessed in 7 days. A miss
runs every test in that job; it never skips one. Pull requests do not save, so once a job's entries
are gone each of its pull-request builds runs in full until the default branch saves again.

Entries measured at 0.3.0, one recording build of one test task each, compressed as the cache
stores them (more in [the benchmarks](benchmarks.md#cache-entry-size)):

| project | test task | tests run | entry |
|---|---|---:|---:|
| okhttp | `:okhttp-tls:test` | 112 | 0.07 MB |
| grpc-java | `:grpc-core:test`, 2 tests failing | 1,536 | 0.23 MB |
| iceberg | `:iceberg-core:test` | 8,785 | 0.91 MB |
| rxjava | `test` | 14,221 | 1.29 MB |
| rxjava, `-Pyoriwake.isolatedCapture` | `test` | 14,221 | 2.76 MB |

The size follows more than the test count: before compression, iceberg's map is nearly four times
rxjava's on fewer tests. A job that runs several test tasks, as `./gradlew test` does in a
multi-module build, saves one map per task in its entry, so its entry is larger by theirs; that was
not measured. For a job running one test task, budget with the largest, 2.76 MB: 10 GB divided by
it is about 3,600 entries, arithmetic rather than a measurement, before the other caches the
repository keeps. yoriwake-action v0.2.1 and earlier also saved `raw/`, which made the same entries
0.14 to 8.25 MB.

### When to refresh

You do not schedule it. Save only from the default branch, whose run executes the whole suite and
so captures a complete map. Pull request builds narrow and refresh nothing, and need not.

`fullRunBranches` is what makes the default branch's run a full one while CI passes
`-Pyoriwake.select` everywhere. Such a run declines selection as `full-run-branch`, names the
branch, and records like a run without the flag; `-Pyoriwake.isolatedCapture` applies to it. Entries
are branch names matched whole, in which `*` matches any characters: `main` does not match
`main.old`, and `release/*` matches `release/1.2`. An entry that is empty or only `*` fails the
build. The branch is read from git:

- **Attached**, as on a push build, the checked-out branch's name is matched.
- **Detached**, as some CI systems check out the pipeline commit, HEAD counts as a listed branch when
  that branch, local or remote-tracking under a configured remote, contains it: its tip is HEAD or
  a descendant. So a build of the default branch still matches after the branch moved on. A pull
  request's merge commit is in no listed branch and selects. A detached checkout must therefore
  fetch the listed branch's ref (`origin/main`); a shallow or single-ref clone that lacks it selects.
- A local detached checkout that a listed branch contains, a bisect step or an old commit, also
  runs in full and dates the map at that commit.

While `fullRunBranches` is set, a run that still selects says what it detected, "on branch
feature/x" or "detached; no listed branch contains HEAD", in the console and as `branch` in
`explain.json`. If git cannot read the branch, the run runs everything as `decline-undetermined`,
naming the git command. The list only switches selection off: without `-Pyoriwake.select` a run
records as always.

To bound how old a restored map may be, put a period in the key rather than pruning entries, for
example the ISO week: one full run at each rollover.

To bound how long a map can go without a full run on any branch, set `fullRunEveryBuilds` or
`fullRunEveryDays`, or both; they are off by default. Once a `Test` task has executed that many times,
or that many days have passed, since its last full run, its next selecting run runs every test as
`full-run-scheduled`, naming the limit reached, and records like a run without the flag. Running every
test for any reason, a full run that is not dated included, starts the count again. The count and the
time live beside the map, so builds count per copy of the map: a pull request build restored from the
default branch's entry starts from the saved count and saves nothing. On those builds days is the
limit that means what it says. A count or time that cannot be read is due, as is a map recorded before
either was written, so enabling a limit costs each task one full run. The cache key's period bounds
how old a restored entry may be; the schedule bounds how long a map can hide a gap. With
`fullRunBranches`, the default branch already runs in full on every build, so the schedule fires only
where the map is not refreshed. `yoriwakeExplain<Task>` says how far away the next scheduled full run is.

### Recording each test class in its own JVM

A test JVM runs some code once, such as a static initialiser or a context it builds and caches, and
coverage credits that work to the first test that triggers it. So in a normal map, a change selects
every test that ran after the changed class arrived in the same JVM (`shares-jvm-with-change`).
On a suite whose classes share setup, that can be most of the suite.

`-Pyoriwake.isolatedCapture` records the map with a fresh test JVM for each test class
(`forkEvery = 1`). Each class then triggers everything it depends on itself, so that rule stays
inside the class and selection narrows further. It applies only to a run that records the map. A
run with `-Pyoriwake.select`, or one that captures nothing, keeps your own fork settings, so
selecting builds run your tests exactly as they did.

- **It is meant for a nightly or main-branch job** that records the map other builds restore, not
  for every build. Recording takes several times longer: every test class pays for a JVM start,
  instrumentation against a cold JIT, and any setup a shared JVM would have reused.
- **Raise `maxParallelForks` for that job** if your build lets you. The flag keeps whatever the
  task sets and starts that many JVMs at a time.
- **Only a recording run updates it.** A selecting run that falls back to a full run still runs
  every test, with your fork settings, but leaves a map holding any JVM recorded in isolation
  exactly as it is, and says so. Recording that run would replace the isolated records with
  shared-JVM ones. Until the next recording run, selection widens the diff base back to the
  isolated map's capture commit, so a map that ages runs more tests, never fewer. A map selection
  cannot read at all is rebuilt as before.
- **It does not catch order dependence.** A class recorded alone never sees state an earlier class
  left behind, so a test that fails only because of such state is not selected on a change that
  reaches it only through that state. A shared-JVM map sometimes selects such a test, by the rule
  above. Neither protects [order-dependent tests](#what-is-not-supported).

`yoriwakeExplain<Task>` says how the map was recorded: `isolated`, `shared`, or `mixed` for a map
that merged both, which only a map from an earlier release can be.

### An old map costs speed, not safety

Selection reads the commit the map was captured at and widens the diff base back to it, so every
change since the capture is in the change set. The older the map, the more tests run, until a run
goes full, captures, and resets the map's age.

### Retrying failed tests

`org.gradle.test-retry` (checked with 1.6.6) and Develocity's own test retry (checked with the
Develocity plugin 4.6.0) run a failed test again inside the same test task, in a new test JVM.
Every round is recorded:

- A capture in which a test both passed and failed, in either order, records it `FLAKY`. Each of
  its lines keeps the classes its round executed. `FLAKY` is not a pass, so the test runs on every
  selecting run whatever changed, until a capture sees it pass every time. A test that failed every
  round stays `FAILED`.
- A retry round does not stop a capture of the whole suite dating the map.
- On a selecting run, a retry round decides as the first round did, so every test that round ran
  and saw fail runs again, one its class alone selected included. `decisions.tsv` lists each test
  once. A test that flipped there is kept as failed, as any failure on a selecting run is.

### Forcing a full run

A selecting run can be asked to run every test instead, without changing the CI command:

- **`-Pyoriwake.fullRun`** for one run, for example when a pull request carries a label your
  workflow maps to it. The decision record says `full-run-requested`.
- **A branch `fullRunBranches` lists**, checked out: see [When to refresh](#when-to-refresh). The
  record says `full-run-branch`.
- **A `yoriwake: full` line in a commit message**, alone on its line, in any case, with any spacing
  around the colon. Every commit the run selects over is read: those since the base, widened to the
  map's age, and HEAD itself. The console names the commit; the record says `full-run-commit`. A
  line that starts with `yoriwake:` and is anything else asks for nothing, and the run says so.
- **`fullRunEveryBuilds` or `fullRunEveryDays` reached** since the task's last full run: see [When to
  refresh](#when-to-refresh). The record says `full-run-scheduled`.

Any of these makes the run a recording run: it captures and dates the map, and
`-Pyoriwake.isolatedCapture` applies to it. A map recorded with a fresh test JVM per test class is
left as it is unless that flag is passed. With a pull request base, the marker stays in the range
until the pull request merges, so it makes every push of it run in full; the flag is the one-run
alternative. If git cannot list the commit messages, the run runs everything as
`decline-undetermined`. `yoriwakeExplain<Task>` reports both, with or without `-Pyoriwake.select`,
so a commit can be checked before it is pushed. Tests named with `--tests` still run as named, and
the record's `declines` note lists every decline that held.

### Giving selection a base

When no base resolves, the plugin says so and every run is a full run. Two common causes:

- `actions/checkout` leaves a detached HEAD with no upstream.
- The name fallback covers `main` and `master` only.

Pass `-Pyoriwake.base=<ref>` explicitly: the pull request base, or `github.event.before` on a push
(all zeroes on a branch's first push, so gate it). `./gradlew yoriwakeExplain<Task>` prints the base
it resolved as its first line.

### Recognising a cache miss

- No `selecting against` line: no map was restored, the run captured instead.
- `ran but recorded no coverage`: setup is broken, no map was built.
- `map updated: N records captured`: capture worked.

Log lines say what the build believed. To prove what it did, assert over the run's own test
results: [`.github/assert_selection.py`](../.github/assert_selection.py) breaks one class, runs with
`-Pyoriwake.select`, and checks that the broken tests ran and failed, that unreachable tests did
not run, and that the result is not empty. Adapt its `RESULTS` path and its expected tests.

Other CI systems work the same way if they can restore the newest directory from an earlier build,
fetch enough history to resolve the base, and continue when the restore misses.

### Who can write the map you restore

A restored map is input no reviewer sees, and a map decides which tests are skipped. A pull request
can write a cache entry its own later runs restore: on GitHub, a run of the pull request saves into
the pull request's own scope, which its later runs search before the default branch's. It gets a
save by pushing a workflow or build edit that saves, then reverting it, or through a recipe that
saves on every event. A crafted map can list every test as passing and executing nothing the change
touches, so every test whose own class did not change is skipped.

`-Pyoriwake.trustedMaps=<file>` closes that for the map. The file lists, one per line, a map
directory's name (`.gradle/yoriwake/<name>`), a tab, and the SHA-256 digest a trusted run recorded
for it: every recording writes its digest to `map-digest` in the map directory. On a run given the
list, a selecting run recomputes the map's digest before its tests start and narrows only when the
list names that digest. Otherwise it runs everything, as `map-untrusted` when the list names
another digest or `map-unverified` when it names none (an empty list is valid), clears the map and
records a new one. The verdict is part of the test task's inputs, so a result the build cache holds
from a run under another verdict is not reused. An unreadable list or a malformed line fails the
build.

The list is only as good as where it comes from: write it from the digests a run on the default
branch recorded, never from the restored directory. After an upgrade, the first default-branch run
renews it ([Upgrading and your map](#upgrading-and-your-map)). Where no list is passed, nothing is checked and
selection trusts any restored map, as in 0.1.0.

On GitHub, [yoriwake-action](https://github.com/zeuspizza/yoriwake-action#which-maps-a-pull-request-trusts)
writes the list: a push to the default branch or a schedule that saves the map uploads its digests
as an artifact, and a pull request lists the digests of the artifact whose run GitHub records as
such a run of this repository, at a commit the default branch contains. When none qualifies, the
pull request passes no selection flag and records every test.
Nothing supplies a list yet on GitLab, CircleCI, Jenkins or Azure Pipelines, or in a hand-written
GitHub workflow: selection there runs without one.

What the check cannot cover:

- **Runs without a list.** A pull request that writes a cache scope its later runs restore can
  narrow past a broken test.
- **Code in a cache entry the pull request wrote.** An entry saved through the cache API can carry
  files outside the cached paths, such as a Gradle init script or a dependency jar; they run in the
  job, outside any diff, and can change the map after the check. Restore only the map directory,
  from a cache entry nothing else shares.
- **Persistent self-hosted runners.** Code an earlier pull request job left on the machine runs in
  later jobs, the default branch's included. Run pull requests on ephemeral runners.
- **Other build-cache entries.** The verdict keys only the selecting test task's result; compiled
  classes restored from a cache a pull request wrote are outside it.

A restored map can also be read. Besides which tests reach which classes, it holds the salted
digests of the test task's system properties and of the variables `environmentInclude` lists,
with their salt: whoever restores it, a pull request's build included, can check a guessed value
against them. See [The environment fingerprint](#the-environment-fingerprint).

## Observing before you select

`-Pyoriwake.observe` runs every test and records what `-Pyoriwake.select` would have left out, so a
team can see on its own builds what selection would do before it lets selection skip anything. The
run decides exactly as a selecting run of the same build, flags and map would, then runs everything
and captures the map as a run without either flag does. Passing both flags fails the build.

After each such run the map directory holds `observation.json` for that run, and one console line
summarises it:

```
[yoriwake] :test observed: selection would have narrowed; 2 tests failed, 1 of them kept; 1 would-be miss; 41 tests would have been skipped, 12.3 s of recorded test time (instrumented, summed over forks; not a wall-clock saving). observation.json lists them.
```

| Field | Meaning |
|---|---|
| `version` | The format of this file, `1`; it changes when a field's meaning changes |
| `task` | The test task's path |
| `commit` | The commit the tests ran at |
| `mapCaptureCommit` | The capture commit of the map the selection was computed against |
| `note` | Why no outcome of this run was read (the task did no work, or captured nothing), or null |
| `outcomesRecorded` | Whether this run's test outcomes were read; `false` with a `note` |
| `observedOutcome` | `narrowed`, or `full-run` when selection would have run everything, with its `fullRunKind` and `refusalKind`. A [requested full run](#forcing-a-full-run) shows here: `-Pyoriwake.fullRun` reads `daemon-refused` and `full-run-requested` |
| `failures`, `failuresKept` | How many tests failed in this run, and how many of them selection would have run |
| `wouldBeMisses` | Each failing test selection would have left out, with its outcome (`FAILED` or `ABORTED`). A failing invocation of a parameterised test counts under its template, and a failing `@BeforeAll` or `@AfterAll` fails every test of its class |
| `wouldBeSkipped`, `recordedInstrumentedTestNanos` | How many tests selection would have left out, and their recorded durations, measured under instrumentation and summed over forks |
| `complete`, `testsWithoutVerdict` | `false` when a test JVM's decision record is missing or cut short. Its tests ran with no verdict and are not counted as skipped |
| `caveats` | Fixed sentences on what an observation cannot show, the ones listed below |

What it does not show:

- A would-be miss is a test that failed and that selection would have left out. A test that fails
  only when the whole suite runs in this order, or that is flaky, is listed too.
- Selection can make a test fail by changing what runs before it. A run that does not select cannot
  observe that.
- Each observing run refreshes the map, so the selection it observes was computed against a fresher
  map than a regime that only selects, and refreshes on its default branch, would read. The skipped
  time is an upper bound for that regime.
- No would-be miss on a run where no test failed, or where the outcome was `full-run`, says nothing
  about how often selection misses a failure. The report counts; it never gives a rate.
- The skipped time is not a wall-clock saving: instrumented durations, summed over forks.
- A task off the JUnit Platform never asks the filter: its outcome is `not-decided`, and its tests
  are counted in `testsWithoutVerdict`.

## Paths no test reads

A change to documentation, a changelog or CI configuration is a changed path coverage cannot see,
so it runs every test. `pathIgnore` declares the paths in the repository no test reads, and a change
to only those runs nothing extra:

```kotlin
yoriwake {
    pathIgnore.addAll("docs/**", "**/*.md", ".github/")
}
```

Entries are Ant-style globs over paths relative to the root project, as Gradle's `include` and
`exclude` take them, matched whole and in the letter case git reports: `*` matches within one
directory, `?` one character within one, `**` as a whole segment any number of directories, none
included (`**/*.md` matches `README.md`), and a trailing `/` everything below a directory. An entry
that starts with `!` or `/`, names a drive, holds a backslash, a `.` or `..` segment, `**` inside a
segment, `[`, `]`, `{` or `}`, a control character, or nothing, fails the build naming
`pathIgnore` and the entry. The list is per project, like `fullRunBranches`; a convention plugin
sets it everywhere. There is no `-P` flag: a declaration that stops a change from running tests
belongs in the reviewed build.

Some paths stay in the change set whatever an entry matches, so `**` cannot switch off what
compiles or what the build reads. The run names each, with the protection that kept it:

- `source-set`: a path under any source set's directory of any project of the build, or under any
  directory named `src`. So a Markdown fixture under `src/test/resources` keeps forcing with
  `**/*.md` declared, and so does a documentation tree with its own `src` directory;
- `build-file`: a build script (`*.gradle`, `*.gradle.kts`), `gradle.properties`, a version
  catalog, anything under a `gradle` directory, the wrapper, and build logic: `buildSrc`, a build
  this one includes, and any directory below the root that holds its own settings file;
- `outside-root`: a path in the repository outside the root project's directory, when the build
  lives in a subdirectory;
- `named-in-classes`: a path whose full path or file name a compiled class of the task holds as a
  string, such as a `ReadmeTest` naming `README.md`;
- `git-quoted`: a path git printed in quotes, as it does a name with bytes it escapes, which no
  rule above can be judged on.

A path left out is still a changed path a test may read, and the map says so where it can: a test
JVM that read the path at capture, a directory above it, or every file (from a child process or an
unreviewed native library on), runs every test from that read on (`shares-jvm-ignored-path`), and the
console says the path was read despite its entry. A changed source file left out runs every test
of a test JVM whose record does not include the source files it opened, which a map recorded before
this release does not. A map recorded before the plugin recorded reads,
or a test JVM whose reads were not recorded, leaves the declaration as made, and `explain.json`
says `no-read-evidence`. A file the build copies from a declared path onto the test
classpath, such as `processResources { from("CHANGELOG.md") }`, is compared on the classpath as
[generated files on the test classpath](#generated-files-on-the-test-classpath) are: its readers
run, or every test does.

What a wrong declaration costs: a test that depends on a declared path and that nothing above
catches is skipped when only that path changed. A README snippet check, a changelog-version test or
a test that validates `.github/workflows` is caught when it opens the file in its own test JVM. One
is skipped when it only asks whether the file exists or lists its directory, or when the file
reaches it from outside its JVM's view: a container or a server the test talks to, or the build
reading the file and passing it on. Every run that left a path out names it in the console, in
`decisions.tsv` (the `paths-ignored` note and the `input.yoriwake.change.ignored` list) and in
`explain.json`. To try a declaration before relying on it, run with
[`-Pyoriwake.observe`](#observing-before-you-select): the run applies it, runs every test, and lists
each failing test selection would have left out.

`notReadByTests` takes a list for the same purpose with none of this: its `*` crosses directories,
nothing is protected, no read is checked, and only a count is printed. It is deprecated: a build
that sets it is warned, and a later release removes it.

## Running what a selection skipped

A selecting run on a pull request leaves out the tests the change cannot reach. Later in the same
pipeline, `-Pyoriwake.complement` runs exactly those: every test but the ones the selecting run
shows it ran. The two runs together run every test once, for less time than running everything a
second time.

```
./gradlew test -Pyoriwake.select       # first: what the change reaches
./gradlew test -Pyoriwake.complement   # later, at the same commit: everything else
```

**Both runs must test the same code.** After a selecting run that narrowed, the task's map
directory holds `selection.tsv`: every test whose execution finished `SUCCESSFUL` or `FAILED`,
stamped with the commit, the task, the build, a digest of the test runtime classpath (what a
manifest `Class-Path` of a jar the build produced names included) and one of the
task's configuration (its system properties, JVM arguments and test filters), and the test JVM's
Java version, vendor and VM, OS and architecture. A complement run uses the record only when every
field equals its own (its classpath looked at again after the task's own `doFirst` actions; see
[Generated files on the test classpath](#generated-files-on-the-test-classpath)) and its working
tree is clean, so a test is left out only when it already ran on this code, this way. A test that ran on other code says nothing about this code.

A selecting run leaves no record, and removes an older one, when it ran everything, declined, ran
with `--fail-fast` (Gradle drops the results of tests that finish after the first failure), or
started or ended on a tree `git status` does not report clean, untracked files included. A test JVM
that died, a test an assumption aborted and a test its engine skipped are never listed, so they run
in the complement.

The flag takes two forms:

- **`-Pyoriwake.complement`** reads the record from this build's own map directories, for a
  complement run in the same checkout as the selecting run.
- **`-Pyoriwake.complement=<dir>`** reads it from a directory holding a copy of a build's
  `.gradle/yoriwake`, by task, for a complement run in another job. A relative path is resolved
  against the root project. Keep the directory outside the checkout: a file in it makes the tree
  not clean.

With `-Pyoriwake.trustedMaps`, only the second form is used: the map directory may come from a
cache a pull request wrote, and the record is outside the map's digest, so a bare
`-Pyoriwake.complement` runs every test as `complement-record-mismatch`.

A run that cannot use a record runs every test and records the map, as a run without the flag
does, and names why: `complement-no-record` when the task has none, `complement-record-mismatch`
when its stamp differs, naming the first field that does (the commit, the working tree, the task,
the build, the classpath, the configuration). A record from another test JVM is found in that JVM:
it runs every test without recording, and `decisions.tsv` notes `refusal-kind`
`complement-record-mismatch` with the property that differs. One that matches captures nothing, and
leaves the map and `selection.tsv` as they are:

```
[yoriwake] :test complemented the selecting run at 1a2b3c4d5e6f: 41 tests left out as already run, 1158 run; nothing was captured, and the map and selection.tsv are as they were.
```

`decisions.tsv` gives each test `ALREADY_RAN` (left out) or `NOT_ALREADY_RAN`. A parameterised
test, a test factory and a Kotest spec always run again, whatever the record lists, and so does a
test whose id the record does not hold exactly. Tests named with `--tests` run as named
(`tests-named`), and a [requested full run](#forcing-a-full-run) records as it does on a selecting
run. Passing the flag beside `-Pyoriwake.select` or `-Pyoriwake.observe` fails the build.

In CI, the complement is a later job of the same workflow run: it checks out the same commit (each
job of one run checks out `github.sha`, for a pull request its merge commit), on the same runner
image and JDK, and restores the selecting job's record outside the checkout:

```yaml
jobs:
  select:
    # ... a selecting job: yoriwake-action on a pull request, or ./gradlew test -Pyoriwake.select ...
    steps:
      # ...
      - uses: actions/upload-artifact@v4
        with:
          name: yoriwake-selection
          path: .gradle/yoriwake/*/selection.tsv
          include-hidden-files: true    # .gradle is a hidden directory
          if-no-files-found: ignore      # a run that ran everything leaves none

  complement:
    needs: select
    runs-on: ubuntu-latest              # the selecting job's runner image and JDK
    steps:
      - uses: actions/checkout@v4       # the same commit as the selecting job
      - uses: actions/setup-java@v5
        with: { distribution: temurin, java-version: '21' }
      - uses: gradle/actions/setup-gradle@v4
      - uses: actions/download-artifact@v4
        continue-on-error: true         # no record: the complement runs everything
        with:
          name: yoriwake-selection
          path: ${{ runner.temp }}/yoriwake
      - run: ./gradlew test -Pyoriwake.complement=${{ runner.temp }}/yoriwake
```

A matrix leg the stamp cannot tell apart from another, such as one set only by an environment
variable, needs a record per leg: name the artifact after the leg, and restore that leg's.

Each job compiles on its own, so the stamp matches only when the build's output on the classpath
comes out the same in both: compiled classes, processed resources and the entries of your own jars
do, but a file the build writes the time or the host into does not (a Spring Boot
`build-info.properties`, a `git.properties` with `git.build.time`), and every complement then runs
everything, naming the classpath. Write such values only in release builds, as for a
[selecting run](#generated-files-on-the-test-classpath).

What it cannot see:

- a file the test runtime classpath does not hold, such as a fixture git ignores that a test reads
  by its path. Changed between the two runs, it is in neither stamp: the classpath digest reads the
  content only of what the classpath holds, and only the name of what it holds under the root's
  `.gradle`, Gradle's and the plugin's own state;
- environment variables, which are in no stamp: a test that reads one runs with whatever the
  complement run has.

## Properties and tasks

Every property is a Gradle project property: `-P<name>` on the command line or in
`gradle.properties`. A switch is on when bare (`-Pyoriwake.select`) or `true`, off when absent or
`false`; any other value fails the build.

| Property | Meaning |
|---|---|
| `yoriwake.select` | Select. Without it nothing is ever skipped; a run only captures. |
| `yoriwake.fullRun` | On a selecting run, run every test and record the map instead. See [Forcing a full run](#forcing-a-full-run). |
| `yoriwake.observe` | Run every test, record the map, and report what selection would have left out. Not with `yoriwake.select`. See [Observing before you select](#observing-before-you-select). |
| `yoriwake.complement[=<dir>]` | Run every test but those the selecting run at this commit ran, from this build's record or from a saved `.gradle/yoriwake` in `<dir>`. Not a switch: bare or `true` reads this build's own record, `false` is off, any other value is a directory. Not with `yoriwake.select` or `yoriwake.observe`. See [Running what a selection skipped](#running-what-a-selection-skipped). |
| `yoriwake.base=<ref>` | What to diff against. Defaults to the merge base with the branch upstream, widened to the map's age. Must be a single commit, not a range. |
| `yoriwake.alwaysRun=<glob>[,<glob>…]` | Tests that may never be skipped, for this run. |
| `yoriwake.trustedMaps=<file>` | Narrow only from a map whose digest the file lists. See [Who can write the map you restore](#who-can-write-the-map-you-restore). |
| `yoriwake.disabled` | Leave the build entirely alone: nothing scoped, injected, captured or selected. |
| `yoriwake.isolatedCapture` | Record the map with a fresh test JVM per test class. Slower to record, narrower to select; a selecting run is unchanged. See [Recording each test class in its own JVM](#recording-each-test-class-in-its-own-jvm). |
| `yoriwake.audit.measureToll` | With the audit: answer the payback question from two supplied timings. |
| `yoriwake.audit.instrumentedSeconds=<s>` | With `measureToll`: a full instrumented run's duration. |
| `yoriwake.audit.uninstrumentedSeconds=<s>` | With `measureToll`: the same run with `yoriwake.disabled`. |

`yoriwake.internal.*` properties exist for this project's own measurements and are unsupported:
they may change or disappear in any release.

[`scripts/measure-toll.sh`](../scripts/measure-toll.sh) `<dir> <task>` runs both timed builds and
then the audit with them. It runs your suite twice.

The extension, `yoriwake { }` in the build script of each project that applies the plugin:

| Property | Type | Default | Meaning |
|---|---|---|---|
| `enabled` | `Boolean` | `true` | `false` leaves the build entirely alone, as `-Pyoriwake.disabled` does. |
| `alwaysRun` | `List<String>` | empty | Tests that may never be skipped: a class, a method (`Class.method`), or a glob. |
| `fullRunBranches` | `List<String>` | empty | Branches on which a selecting run runs every test and records the map instead. See [When to refresh](#when-to-refresh). |
| `fullRunEveryBuilds` | `Int` | unset | Run every test once a task has executed this many times since its last full run. |
| `fullRunEveryDays` | `Int` | unset | Run every test once this many days have passed since a task's last full run; with both set, whichever comes first. |
| `notReadByTests` | `List<String>` | empty | Deprecated: use `pathIgnore`. Globs of files no test or build reads: a changed one is no change. |
| `pathIgnore` | `List<String>` | empty | Ant-style globs of paths no test reads: a changed one leaves the change set, unless protected. See [Paths no test reads](#paths-no-test-reads). |
| `environmentInclude` | `List<String>` | empty | Environment variables compared besides `TZ`, `LANG` and `LC_ALL`. See [The environment fingerprint](#the-environment-fingerprint). |
| `systemPropertyIgnore` | `List<String>` | empty | System properties left out of that comparison. |
| `dependsOn` | `List<String>` | empty | `task=glob` entries: paths a `Test` task's tests depend on where coverage cannot see; a changed one runs that task in full. |
| `largeChangeThreshold` | `Int` | `5000` | Above this many changed paths, run every test without analysing the change. |

For example:

```kotlin
yoriwake {
    enabled = false                        // same as -Pyoriwake.disabled
    alwaysRun.add("com.acme.FlakyTest")    // a class, a method (Class.method), or a glob
    fullRunBranches.add("main")            // a selecting run here runs everything; see CI
    fullRunEveryBuilds = 20                // every 20th build of a task runs everything; off by default
    fullRunEveryDays = 7                   // or a week after the last full run, whichever first
    notReadByTests.addAll("docs/*", "*.md") // changed files no test or build reads: no change
    pathIgnore.addAll("docs/**", "**/*.md") // paths no test reads, protected and logged
    environmentInclude.add("DB_MODE")      // a variable compared besides TZ, LANG and LC_ALL
    systemPropertyIgnore.add("build.number") // a property left out of that comparison
    dependsOn("integrationTest", "/docker/**") // a changed compose file runs integrationTest in full
    largeChangeThreshold = 20000           // more changed paths than this run everything
}
```

A pattern naming a class pins every test in it, its nested classes' included (`Outer.Inner` or
`Outer$Inner` names one nested class). A pattern naming a method pins every invocation of a
parameterised test and every dynamic test of a test factory. Vintage tests are named by their JUnit 4
class and method, and Spock features by their specification and feature name
(`com.acme.HandlerSpec.handles a request`).

`notReadByTests` takes globs over paths relative to the root project, each matched whole, with `*`
matching any characters, slashes included: `docs/*` is everything under `docs/`, `*.md` every
Markdown file at any depth. A changed file one matches is no change, where it would otherwise force
a full run. It is empty by default and per project, like `fullRunBranches`. Only list files that
no test and no build step reads, except to compile or copy them onto the test classpath: a selecting
run compares what lands there by content, a changed class running the tests that executed it and a
changed resource the tests that read it, but a test that reads a listed file itself is skipped when
only that file changed. Two shapes that fit: a resource directory a build task regenerates on every
run, such as `gen-src/*` holding a versions file stamped with the commit, and a package manager's
tree such as `docs-client/node_modules/*` that no test reads. A run forced by such files names the
globs; see [Files written into the source tree](#files-written-into-the-source-tree). A listed glob
also covers files added under it later, and a listed directory of generated sources no longer
forces a run when they change, nor gets the checks a changed source gets (annotations, constants):
list one only if no test depends on what is generated in it.

`pathIgnore` does the same job with guards; see [Paths no test reads](#paths-no-test-reads).

`dependsOn` declares what coverage cannot see a test depend on: a compose file handed to a
container, migrations a database runs, fixtures another process reads. Each entry is a `Test` task
of the project and an Ant-style glob, relative to the project or, with a leading `/`, to the root
project: `*` and `?` within a directory, `**` as a whole segment for any number of directories. A
changed path an entry matches runs that task in full, whatever else the change holds; a single test
or class declares the same with `@Tag("yoriwake-depends-on:<glob>")`, which selects it when such a
path changes. A declaration can only cause more tests to run. An entry that matches every path, or
names no `Test` task of its project, fails the build.

`environmentInclude` and `systemPropertyIgnore` take names, `*` matching any characters; an empty
entry or one of only `*` fails the build. See [The environment
fingerprint](#the-environment-fingerprint).

A test's own source can pin it with `@Tag("yoriwake-always-run")`, with nothing on the compile
classpath.

Tasks, registered per `Test` task (`<Task>` is its capitalised name, e.g. `yoriwakeAuditTest`):

| Task | Meaning |
|---|---|
| `yoriwakeAudit<Task>` | The suite's shape from its map, and every blocker with its remedy. Runs no tests. Registered even on a task the plugin declined. |
| `yoriwakeExplain<Task>` | What selection would do about the current change, and which class forces a full run if one does. Compiles what the test task compiles, and runs no tests. |
| `yoriwakeDecode<Task>` | Decodes a run's records into the map. A finalizer of the test task; you do not call it. |

## Upgrading and your map

Every [CHANGELOG](../CHANGELOG.md) entry says which of these a release does to a map you already
have:

- **Left alone.** Nothing about the format or the meaning of a record changed. The map keeps
  working.
- **Repaired on the next build.** What a capture records changed, but what an existing record means
  did not. Records written before are not recorded as passing, so the first build selects them
  whatever changed, re-observes them, and replaces them. That build runs wider than usual; the next
  is normal. Do not purge the cache over it: that turns a one-build repair into a full run.
- **Discarded.** The format version (`schema-version` in the map, `MAP_SCHEMA_VERSION` in
  [`AgentContract`](contract.md#versions)) was bumped. A plugin never reads part of a map written
  in another version; it refuses all of it:

  ```
  the map at <dir> is schema version 2 but this build reads 3; rebuild it by running the full suite
  ```

  The next full run rebuilds it. A version is bumped only when a stale record could still look
  like a passing one while being wrong, because nothing re-observes a record that is never
  selected.

With a [trusted-map list](#who-can-write-the-map-you-restore), a release that changes which map
files the digest covers changes every digest, so the list your default branch wrote on the earlier
version names none of the new one's. Until the default branch's first run on the new version
records and lists new digests, each pull request runs everything as `map-untrusted`, with a reason
that names the release that wrote the list rather than one that reads like a changed map. Do not
purge the cache over it: the default branch's next run restores narrowing.

Two other messages are not upgrades: a map holding records but no `schema-version` is a capture
that died partway (delete the directory and capture again), and a directory with neither predates
format versioning.

### From 0.1.0 to 0.2.0

- **Your map is discarded.** The format moves from 6 to 7, so the first 0.2.0 build refuses the
  0.1.0 map, runs everything and records; selection resumes on the next build. Do not purge
  caches. The entries marked "Map: left alone" describe each change on its own, not a 0.1.0 map.
- **A hand-written CI recipe:** set `YORIWAKE_VERSION` to `0.2.0`; it keys the cache, so the
  upgrade is one ordinary cache miss. The recipe above now only records: pull requests select
  through [yoriwake-action](https://github.com/zeuspizza/yoriwake-action), which passes the
  trusted-map list. A 0.1.0 pull-request step that still passes `-Pyoriwake.select` keeps working,
  but narrows from whatever entry it restores, a pull request's own included. Before using
  `-Pyoriwake.complement`, delete `.gradle/yoriwake/*/selection.tsv` in a step before the cache
  save, as the recipe above does; an excluding `!` path does not keep it out.
- **What runs changes without a new flag:** a Develocity Test Distribution or Predictive Test
  Selection task is declined; a JUnit Platform test filter set in the build script selects and
  dates the map; tests named with `--tests` or by an IDE run as named.

### From 0.2.0 to 0.3.0

- **Your map is repaired on the next build.** The format stays at 7, but a 0.2.0 map records no
  [environment](#the-environment-fingerprint), so the first 0.3.0 selecting run of each task runs
  everything and records it. Where pull requests only restore the default branch's map, each runs
  everything until a default-branch build on 0.3.0 has saved one. A trusted-map list built from
  0.2.0's `map-digest` values refuses that map as `map-untrusted` until then, with the same effect.
- **A hand-written CI recipe:** set `YORIWAKE_VERSION` to `0.3.0`, one ordinary cache miss.
- **What runs changes without a new flag:** a test task's JDK, OS, system properties, time zone or
  locale differing from the map's runs everything. A system property that changes on every build
  (a build number, a timestamp) does so on every run until it is left out with
  `systemPropertyIgnore`, and test legs on different JDKs need a cache key each.

## Prior art

- **[Develocity Predictive Test Selection](https://docs.develocity.ai/predictive-test-selection/)**
  (Gradle, commercial, hosted). Learns from build history which tests are likely to fail; falls back
  to running everything when it cannot answer. This tool is not a better version of it.
- **[CloudBees Smart Tests](https://docs.cloudbees.com/docs/cloudbees-smart-tests/latest/features/predictive-test-selection)**
  (formerly Launchable; commercial, hosted). Predicts relevant tests from history and change
  content; its answer is a probability rather than a rule you can check.
- **[Ekstazi](https://github.com/gliga/ekstazi)** (research, Apache-2.0, Maven). Dynamic
  regression test selection at file granularity, by checksums of the files each test depended on.
- **[STARTS](https://github.com/TestingResearchIllinois/starts)** (research, Maven). Static,
  class-level: builds a dependency graph without executing anything, so it has no capture cost and
  cannot see what only happens at runtime.
- **[pytest-testmon](https://github.com/tarpas/pytest-testmon)** (Python). The same idea for pytest:
  records through `coverage.py` which code each test executed, and reruns the tests a change
  reaches.
