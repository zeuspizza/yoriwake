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
  selects the tests that read it. Build state does not: `.gradle/`, `.kotlin/`, the build
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
| `:test: <paths> changed during :test's last capture build …` | on a forced run: untracked files that force it, by where they came from; see [Files written into the source tree](#files-written-into-the-source-tree) |

Reasons, one per test:

| Reason | Meaning |
|---|---|
| `reaches-change` | its recorded coverage touches a changed class, or its own test class changed |
| `shares-jvm-with-change` | it ran at or after the point its test JVM first loaded or executed a changed class, or read its class or source file; see [what coverage does not record](#what-coverage-does-not-record) |
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
  `untracked`, or no origin for a path HEAD tracks.
- `audit.json`: written by `yoriwakeAudit<Task>`. The suite's shape and every blocker as tokens.

## When it refuses to select

It forces a full run whenever it cannot prove a narrower one is safe, and says which cause:

- the map is missing, empty, unfinished, or from a different format version;
- the map's age cannot be established (no capture stamp, or one that cannot be related to the
  base);
- a changed path coverage cannot see: a build script, a settings file, a version catalog, a
  resource. That includes a file your tests or your build write into the source tree outside
  `build/`, such as a `derby.log`: untracked and ignored files count. The run names each such file;
  see [Files written into the source tree](#files-written-into-the-source-tree);
- a changed class the map has never recorded, because uncovered and unrecordable look the same. A
  test class this task runs is the exception: discovery runs it whatever the map knows;
- a changed class whose annotations, on the class or any member and with their values, differ from
  the ones the map recorded (`annotations-changed`), or an annotated changed class the map recorded
  no annotations for (`annotations-unrecorded`). A test class this task runs that declares a test
  is exempt, because its own tests run anyway;
- compiled bytes that changed with no source change, in a class no test executed
  (`no-coverage-for-changed-bytes`), or a map holding no digest of the compiled classes to compare
  against (`bytes-unrecorded`);
- a non-class file the build produced on the test runtime classpath whose content differs from
  the capture's, with no tracked change behind it (`classpath-files-changed`), or a map that
  records no such digests (`classpath-files-unrecorded`, once: the forced run records them, unless
  the classpath holds over 200,000 such files, which are then never recorded). The
  run names up to five files; see [Generated files on the test
  classpath](#generated-files-on-the-test-classpath);
- a change touching startup coverage, which no single test owns;
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
- on a run given `-Pyoriwake.trustedMaps`, a map the list does not name (`map-unverified`) or names
  with another digest (`map-untrusted`). The map is cleared and the run records a new one; see
  [Who can write the map you restore](#who-can-write-the-map-you-restore).

Tests named on the command line with `--tests` all run: a selecting run declines selection for
that task (`tests-named`), runs every test the filter matches whatever the map says, and leaves the
map as it was. The decline takes the place of any other refusal on the task except a Develocity
decline, which also runs every test the filter matches, and `explain.json` reports it when
`yoriwakeExplain<Task>` runs in the same build. A task whose `--tests` patterns
cannot be read declines too (`decline-undetermined`) and runs everything.

A filter set in the build script narrows differently. A filtered run does not speak for the whole
suite, and leaves the map as it was. That includes a filter the build script sets on the task (`filter.includeTestsMatching`,
`filter.excludeTestsMatching`): every run of that task is filtered, so its map is never dated and
every selecting run refuses with `stamp-absent` and runs the whole suite, while still recording
coverage. Selection is off for such a task until the filter moves out of the build script. A run that
leaves tests out by tag or engine (`includeTags`, `excludeTags`, `includeEngines`,
`excludeEngines`), JUnit 4 category or TestNG group does not speak for the whole suite either, but
it does date the map: the tests it left out drop out of the map, so the next selecting run runs
them as not in it.

Separately from those, a test the map does not record as passing always runs, and a test the map
has never seen always runs.

### Files written into the source tree

A file the tests or the build write into the source tree outside the build directories counts as
a change by its content, tracked or not, ignored or not. A run that sees it changed runs
everything, and names it, one line per kind:

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
  a source or resource directory, or one not yet added to git. It is on the classpath, tests can
  read it, and its changes are changes; nothing is offered to make it stop counting.
- **Untracked** (`untracked`): any other untracked or ignored file; it counts by content.

A file HEAD tracks gets no line: the run cannot tell a test's write from your edit.

### Generated files on the test classpath

Every non-class file the build produced on a test task's runtime classpath counts by content: each
file of a resource or output directory under the root or a build directory, and each entry of one
of your own jars (never the jar whole, so a rebuild that only moves entry timestamps changes
nothing). The capture records them just before its tests start, and a selecting run compares them
at the same point and runs everything when one changed, appeared or is gone, naming it
(`classpath-files-changed`). Dependency jars are the classpath rule's. A file a test writes into
such a directory is not there when a fresh checkout's tests start; where the build directory
survives between runs, one a test rewrites with new content each run forces every selecting run,
so have the test write elsewhere in the build directory.

A build that writes the commit, a tag or the time into such a file therefore runs everything on
every selecting run where that value moved: a `git.properties` from `gradle-git-properties`, or a
`Build-Date`, `Build-Revision` or `Implementation-Version` with a commit in your own jar's
manifest that another module's tests load. That is the point of the rule: a test may read the
value, and nothing records whether it does. To keep such builds narrowing, write those values only
in release builds, for example behind a property:

```kotlin
val release = providers.gradleProperty("release").isPresent
tasks.jar {
    if (release) manifest { attributes("Build-Date" to java.time.Instant.now().toString()) }
}
```

and apply `gradle-git-properties`, or its task, the same way. A test that asserts on such a value
then needs the release build to see it.

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
  coverage selects, plus every test at or after the first time, after discovery, that something
  looked it up by name, defined a copy of it in another class loader, read its class or source
  file, or executed it from another test class. It
  falls back to the wider rule, so that every test in its JVM runs on an edit to it, when:
  - a test in that JVM ran on an engine other than JUnit Jupiter, JUnit Vintage, the JUnit Platform
    Suite, JUnit 4, TestNG, Spock, Kotest, ArchUnit or jqwik;
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

| Library | Loading class | Reviewed | What its native code opens |
|---|---|---|---|
| Netty epoll transport | `io.netty.util.internal.NativeLibraryUtil` | [4.2.18.Final](https://github.com/netty/netty/tree/netty-4.2.18.Final/transport-native-epoll/src/main/c) | `/proc/sys/net/ipv4/tcp_fastopen`; a file whose path is passed to `FileDescriptor.from`, opened write-only; otherwise only descriptors Java opened |
| Netty kqueue transport | `io.netty.util.internal.NativeLibraryUtil` | [4.2.18.Final](https://github.com/netty/netty/tree/netty-4.2.18.Final/transport-native-kqueue/src/main/c) | a file whose path is passed to `FileDescriptor.from`, opened write-only; otherwise only descriptors Java opened |
| Netty io_uring transport (incubator) | `io.netty.util.internal.NativeLibraryUtil` | [0.0.26.Final](https://github.com/netty/netty-incubator-transport-io_uring/tree/netty-incubator-transport-parent-io_uring-0.0.26.Final/transport-native-io_uring/src/main/c) | a probe file in the temporary directory, written and never read; as epoll otherwise |
| Conscrypt | `org.conscrypt.NativeLibraryUtil` | [2.7.0](https://github.com/google/conscrypt/tree/2.7.0/common/src/jni) | `/dev/urandom` where `getrandom` is missing; no path from Java |
| snappy-java | `org.xerial.snappy.SnappyLoader` | [1.1.10.8](https://github.com/xerial/snappy-java/tree/v1.1.10.8/src/main/java/org/xerial/snappy) | nothing |
| zstd-jni | `com.github.luben.zstd.util.Native` | [1.5.7-20](https://github.com/luben/zstd-jni/tree/v1.5.7-20/src/main/native) | nothing |
| lz4-java (`at.yawk.lz4`, and `org.lz4` 1.8.0) | `net.jpountz.util.Native` | [1.12.0](https://github.com/yawkat/lz4-java/tree/v1.12.0/src/jni) | nothing |
| RocksDB | `org.rocksdb.NativeLibraryLoader` | [11.8.1](https://github.com/facebook/rocksdb/tree/v11.8.1/java/rocksjni) | the files of a database in the directories a test names, only by the names RocksDB itself writes; fixed `/proc` and `/sys` paths |

Two notes on the list:

- `RocksDB.loadLibrary`, which RocksDB calls for itself, first tries to load the system's
  compression libraries by name (`snappy`, `z`, `bzip2`, `lz4`, `zstd`). Those are not reviewed, so
  a JVM that loads RocksDB that way applies the wide rule from there.
- Reviewed and left off: Netty's in-tree io_uring transport, whose public API lets any caller submit
  a file open to the kernel; netty-tcnative, whose BoringSSL and OpenSSL builds share file names,
  and whose OpenSSL build reads a configuration file and loads modules from paths the environment
  names; SQLite JDBC, which loads any native library an SQL statement names once
  `enable_load_extension` is set on a connection; and JNA, which calls any native function.

An entry covers the version reviewed. Each is reviewed again when its library ships a new major
version, and until then a later version counts as the one reviewed. To propose a library, open an
issue with its version, a link to its native sources at that tag, every file open, `mmap`,
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

## Requirements

- **Gradle daemon JDK: 21 or newer.** The plugin's classes are compiled for Java 21.
- **Test JVM JDK: 11 or newer.** The agent is compiled with `release = 11`, so it loads in a test
  JVM a build's toolchain puts on Java 11.
- **`git` on the daemon's `PATH`.** A git that cannot be started is a change set that cannot be
  computed, so every selecting run is a full run and says so.
- **Gradle: 8.14 or newer, 9.x included.** The functional suite runs on 8.14, the plugin's compile
  floor, and on 9.8.0. No other version is tested. Below 8.14, on a JDK 21 daemon, the plugin
  declines: every run is a full run, and the audit reports `gradle-too-old`. On an older daemon JDK
  the plugin does not resolve at all: its metadata asks for JVM 21, so the build fails. Gradle
  before 8.5 cannot run on JDK 21 (Gradle's compatibility matrix), so it always takes this path.

### Supported hosts

The plugin attaches to a project that applies one of these:

- `java`, and the plugins that apply it, such as `java-library` and `org.jetbrains.kotlin.jvm`;
- `com.android.application`, `com.android.library`, `com.android.test` or
  `com.android.dynamic-feature`, for their unit-test tasks;
- `org.jetbrains.kotlin.multiplatform`, for a module with a JVM target: a `src/jvmMain` or
  `src/jvmTest` directory beside at least one `<target>Main` source directory.

Kotlin Multiplatform is unverified. The plugin attaches by that directory layout, and no
functional test applies the Kotlin Multiplatform plugin yet. Selection is
as safe there as anywhere, but which module shapes it reads correctly has not been proven.

On any other project with test tasks it declines by name. Nothing is captured or selected there,
every run is a full run, and `yoriwakeAudit<Task>` reports one of these as a blocker:

| Token | Meaning |
|---|---|
| `no-host-plugin` | The project has test tasks but applies none of the plugins above. Apply `java` or an Android plugin to the project whose tests you want selected. |
| `kotlin-multiplatform` | A Kotlin Multiplatform module whose JVM target the plugin cannot see, or cannot scope from its own source directories. |

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
    searches this build's own classes and jars and every resource, not the classes of dependencies.
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
  Parallel forks (`maxParallelForks`) are fine.
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
- **Windows.** Never run there. Maps have been shown to move between Linux and macOS in both
  directions; Windows is unverified.
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
- A build that emits no `SourceDebugExtension` at all (for example one passing
  `-Xno-source-debug-extension`) cannot answer that question, so any changed Kotlin source forces a
  full run. A change set of Java sources alone still narrows. The audit reports `smap-absent`.

## CI

A CI job starts with no map, so every run captures and none selects unless one is restored.
Cache `.gradle/yoriwake`, restore the newest one available, and let a miss run everything.

```yaml
env:
  YORIWAKE_VERSION: 0.1.0       # the version your build applies

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
          path: |
            .gradle/yoriwake
            !.gradle/yoriwake/*/raw
          key: yoriwake-${{ runner.os }}-${{ github.job }}-${{ env.YORIWAKE_VERSION }}-${{ github.sha }}
          restore-keys: yoriwake-${{ runner.os }}-${{ github.job }}-${{ env.YORIWAKE_VERSION }}-

      - if: github.event_name == 'pull_request'
        run: ./gradlew test -Pyoriwake.select -Pyoriwake.base=origin/${{ github.base_ref }}
      - if: github.event_name != 'pull_request'
        run: ./gradlew test      # the default branch runs everything, so it captures a full map

      - if: github.ref == format('refs/heads/{0}', github.event.repository.default_branch)
        uses: actions/cache/save@v4
        with:
          path: |
            .gradle/yoriwake
            !.gradle/yoriwake/*/raw
          key: yoriwake-${{ runner.os }}-${{ github.job }}-${{ env.YORIWAKE_VERSION }}-${{ github.sha }}
```

### What the key is for

- **OS.** Maps are plain text, but a map is only shown to move between Linux and macOS; keeping
  one per OS costs nothing.
- **Job.** The directory holds one map per test task, and a job that runs a different set of tasks
  should not overwrite another job's maps.
- **Plugin version.** A map records its format version, and a plugin reading a different one
  refuses the whole map. With the version in the key, an upgrade is an ordinary cache miss: one full
  run, which captures.
- **Commit, with a prefix fallback.** An exact hit almost never happens. A map from an older commit
  is still useful and still safe.
- **Not `raw/`.** The undecoded records are cleared by the next capture before it writes anything.

### When to refresh

You do not schedule it. Save only from the default branch, whose run executes the whole suite and
so captures a complete map. Pull request builds narrow and refresh nothing, and need not.

To bound how old a restored map may be, put a period in the key rather than pruning entries, for
example the ISO week: one full run at each rollover.

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
- **A `yoriwake: full` line in a commit message**, alone on its line, in any case, with any spacing
  around the colon. Every commit the run selects over is read: those since the base, widened to the
  map's age, and HEAD itself. The console names the commit; the record says `full-run-commit`. A
  line that starts with `yoriwake:` and is anything else asks for nothing, and the run says so.

Either way the run is a recording run: it captures and dates the map, and
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
branch recorded, never from the restored directory. Where no list is passed, nothing is checked and
selection trusts any restored map, as in 0.1.0.

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

## Properties and tasks

Every property is a Gradle project property: `-P<name>` on the command line or in
`gradle.properties`. A switch is on when bare (`-Pyoriwake.select`) or `true`, off when absent or
`false`; any other value fails the build.

| Property | Meaning |
|---|---|
| `yoriwake.select` | Select. Without it nothing is ever skipped; a run only captures. |
| `yoriwake.fullRun` | On a selecting run, run every test and record the map instead. See [Forcing a full run](#forcing-a-full-run). |
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

The extension:

```kotlin
yoriwake {
    enabled = false                        // same as -Pyoriwake.disabled
    alwaysRun.add("com.acme.FlakyTest")    // a class, a method (Class.method), or a glob
}
```

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

Two other messages are not upgrades: a map holding records but no `schema-version` is a capture
that died partway (delete the directory and capture again), and a directory with neither predates
format versioning.

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
