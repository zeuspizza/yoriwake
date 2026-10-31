"""
Answers "can this project produce evidence at all?" before anyone spends an hour on it.

Qualification has been manual, and it is where every defect this corpus has found actually turned
up: rxjava's decode ran out of heap, spring-core parked forever on a latch, spring-beans forced
every change because ASM could not read a Java 24 multi-release jar, ktlint needed the DAEMON on a
newer JDK than the machine's default. None of those came from a sweep. All four came from the first
honest attempt to run one module's tests.

So this is the cheap stage, and the one worth automating. It runs the screen every corpus
candidate goes through in order, stopping at the first disqualifier, and
prints one row per project rather than costing an afternoon each.

    python bench/qualify.py bench/projects/spring-framework.toml
    python bench/qualify.py --all
    python bench/qualify.py --all --json > qualification.json

WHAT IT DOES NOT DO. It never sweeps, never mutates a source file and never claims a savings number.
Its output is the input to that decision, not the decision. A project that qualifies here has shown
only that it builds, runs tests hermetically on this machine, and that the plugin applies -- which is
the floor, not a recommendation.

Every failure is reported with the evidence that produced it, because "unusable" has been wrong four
times in this project's history: four projects were written off and all four ran once someone read
the actual error rather than the summary.
"""

import argparse
import glob
import json
import os
import re
import shutil
import subprocess
import time
import sys
import tomllib

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

from process import STALLED, run  # noqa: E402
import run as harness  # noqa: E402
import maven_build  # noqa: E402
import own_processes  # noqa: E402

# Long enough for a first build that downloads the world, short enough that a hung suite does not
# cost an afternoon. The stall guard inside `run` is the real protection; this is the outer bound.
QUALIFY_TIMEOUT = 45 * 60

# A first build with nothing cached says nothing for ten minutes at a time while it downloads, so the
# stall window here is wider than a sweep's.
QUALIFY_STALL = 20 * 60

# The blocker prefix for an attempt that never began, as opposed to a verdict about the project.
# `write_qualification.py` matches this exact string to file such a row as UNRESOLVED, so the two
# files must agree; it is stated once here and quoted there.
COULD_NOT_START = "the attempt could not start"


def screen_build_system(checkout, project=None):
    """The build the project file names, at the repository root: Gradle unless it says
    `build = "maven"`, which is screened with the Maven plugin."""
    if maven_build.is_maven(project or {}):
        if os.path.isfile(os.path.join(checkout, "pom.xml")):
            return None
        return "no pom.xml at the repository root, though the project file says build = \"maven\""
    for name in ("settings.gradle.kts", "settings.gradle", "build.gradle.kts", "build.gradle"):
        if os.path.isfile(os.path.join(checkout, name)):
            return None
    if os.path.isfile(os.path.join(checkout, "pom.xml")):
        return ("maven, not gradle -- set build = \"maven\" in the project file to screen it with "
                "the Maven plugin")
    return "no gradle build found at the repository root"


def platform_hints(checkout, module_dir):
    """A HINT about JUnit Platform use, never a verdict.

    This started as a screen that could disqualify a project, and it was wrong in both directions on
    the first two projects it was pointed at. okhttp reported "no Platform signal" -- its
    `useJUnitPlatform` lives in convention plugins under `build-logic`, not in any script this
    reads -- while its Platform filter has been discovering 112 tests all day. mockito reported a
    Platform signal because `junit-jupiter` appears somewhere in its build, and mockito is the
    documented case that can never be deselected because its suite runs JUnit 4 off the Platform.

    A grep over build scripts cannot answer this. What can is the run itself: the agent's
    `SelectionFilter` is a `PostDiscoveryFilter`, so if it reports a discovery tally, the suite is on
    the Platform by construction. See `observed_platform`.

    Kept only because the hint is worth printing beside the observation when the two disagree --
    that disagreement is what exposed this, and it will expose the next convention-plugin layout too.
    """
    hints, sources = [], []
    for path in build_scripts(checkout, module_dir):
        try:
            text = open(path, encoding="utf-8", errors="replace").read()
        except OSError:
            continue
        sources.append(os.path.relpath(path, checkout))
        if "useJUnitPlatform" in text:
            hints.append("useJUnitPlatform")
        if "junit-jupiter" in text or "junit.jupiter" in text:
            hints.append("junit-jupiter")
        if "junit-platform" in text:
            hints.append("junit-platform")
    return sorted(set(hints))


def observed_platform(log_path):
    """Whether the run's own output shows the Platform filter seeing tests.

    `[yoriwake] of N tests discovered:` is printed by SelectionFilter's shutdown hook, which only exists
    on the JUnit Platform. Its presence is proof; its absence is not, because a run that never
    reached the tests prints nothing either -- so the caller must distinguish those two.
    """
    try:
        text = open(log_path, encoding="utf-8", errors="replace").read()
    except OSError:
        return None
    match = re.search(r"\[yoriwake\] of (\d+) tests discovered", text)
    return int(match.group(1)) if match else None


def build_scripts(checkout, module_dir):
    """The build scripts worth reading: the root's, and the module's if one is named.

    Takes the DIRECTORY, not the Gradle name -- every path built here is a file path. See
    harness.module_dir.
    """
    found = []
    for name in ("build.gradle.kts", "build.gradle", "settings.gradle.kts", "settings.gradle"):
        candidate = os.path.join(checkout, name)
        if os.path.isfile(candidate):
            found.append(candidate)
    if module_dir:
        for name in ("build.gradle.kts", "build.gradle"):
            candidate = os.path.join(checkout, harness.module_path(module_dir), name)
            if os.path.isfile(candidate):
                found.append(candidate)
    return found


def count_modules(checkout):
    """How many projects the build declares, as a proxy for the fixed cost.

    The plugin costs about ten seconds of configuration on a 28-module build and does not care how
    long the tests run, so module count over suite duration is the ratio that decides whether a
    project is a target or an anti-target. Counted from `include` lines because that is free; a
    build that computes its modules dynamically returns None rather than a guess.
    """
    for name in ("settings.gradle.kts", "settings.gradle"):
        path = os.path.join(checkout, name)
        if not os.path.isfile(path):
            continue
        try:
            text = open(path, encoding="utf-8", errors="replace").read()
        except OSError:
            return None
        includes = re.findall(r'^\s*include\b', text, re.MULTILINE)
        if not includes:
            return None
        names = re.findall(r'["\']:?([\w.\-]+(?::[\w.\-]+)*)["\']', text)
        return max(len(includes), len(set(names))) or None
    return None


def gradle_failure(text):
    """The first substantive line of Gradle's own failure block, or "".

    Gradle prints `* What went wrong:` followed by the message, then indented `> ` causes. The
    deepest `> ` line is the actual reason; the first is often just "Execution failed for task X",
    which names the task and not the problem.
    """
    lines = text.splitlines()
    try:
        start = next(i for i, line in enumerate(lines) if line.startswith("* What went wrong:"))
    except StopIteration:
        return ""
    best = ""
    for line in lines[start + 1:start + 12]:
        stripped = line.strip()
        if not stripped or stripped.startswith("* Try:"):
            break
        # Prefer a `>` cause over the "Execution failed for task" headline above it.
        if stripped.startswith(">"):
            best = stripped.lstrip("> ").strip()
        elif not best:
            best = stripped
    return best


def discard_previous_results(checkout, module_dir, task, build="build"):
    """Delete the module's test-results before the run, so the test task cannot be UP-TO-DATE.

    Qualification reuses a checkout and passes `--no-build-cache` but not `--rerun-tasks`, so a
    project built by any earlier session reports its test task UP-TO-DATE, runs zero tests, and is
    refused as "a green build that ran nothing". `test_counts` refuses correctly -- reading the
    previous run's XML would be far worse -- but the effect was that qualify.py was single-use per
    checkout, and re-qualifying a corpus is the entire job it exists for. moshi and caffeine were
    both written off this way once, having qualified in an earlier session.

    Deleting the OUTPUT rather than passing `--rerun-tasks` is deliberate: Gradle treats a task
    whose declared outputs are missing as out of date, so the tests re-run, while compilation and
    everything else upstream stays incremental. `--rerun-tasks` would rebuild the world on every
    project in the corpus to solve a problem that lives in one directory.

    Failure to delete is ignored. It only means the run may report UP-TO-DATE again, which the
    existing guard already catches and reports honestly.
    """
    results = os.path.join(
        checkout, harness.module_path(module_dir), build, "test-results", task)
    if os.path.isdir(results):
        shutil.rmtree(results, ignore_errors=True)


def test_counts(checkout, module_dir, task, since, build="build"):
    """(tests, failures, test_seconds) from the JUnit XML the run just wrote.

    `test_seconds` is the sum of the suites' own `time=` attributes, which is the only number here
    that is actually tests running. The wall clock of the run is that plus configuration, compilation
    and JVM start -- and it is the RATIO between those two that decides whether selection can pay,
    because selection removes test time and cannot touch the rest.
    """
    # `build` is Gradle's default and almost every project takes it. hibernate-orm writes into
    # `target/`, and reading the default there found an empty directory and reported 24,798 executed
    # tests as "a green build that ran nothing" -- see `harness.build_dir`.
    pattern = os.path.join(
        checkout, harness.module_path(module_dir), build, "test-results", task, "*.xml"
    )
    total = failed = 0
    test_seconds = 0.0
    # Only results this run wrote. run.results_of learned this after a build that stopped early left
    # the previous mutation's XML in place and was read as ground truth; the screen never inherited
    # it. It matters more here, not less: qualification reuses the checkout, passes --no-build-cache
    # but not --rerun-tasks, and a second qualify of the same project reports the task UP-TO-DATE,
    # runs zero tests, and would otherwise read the first run's results and print QUALIFIED.
    files = [p for p in glob.glob(pattern) if os.path.getmtime(p) >= since]
    if not files:
        return None, None, None
    for path in files:
        try:
            head = open(path, encoding="utf-8", errors="replace").read(600)
        except OSError:
            continue
        match = re.search(r'tests="(\d+)".*?failures="(\d+)".*?errors="(\d+)"', head, re.S)
        if match:
            total += int(match.group(1))
            failed += int(match.group(2)) + int(match.group(3))
        elapsed = re.search(r'\stime="([\d.]+)"', head)
        if elapsed:
            test_seconds += float(elapsed.group(1))
    return total, failed, round(test_seconds, 1)


def qualify(project, keep=False):
    """Runs the screen in order, stopping at the first disqualifier.

    Order matters: each step is more expensive than the last, and the cheap ones disqualify most
    candidates. Reading a build script is free; running a module's tests is not.
    """
    name = project["name"]
    verdict = {
        "name": name,
        "module": project.get("module") or "",
        "qualified": False,
        "blocker": None,
        "evidence": {},
    }

    print(f"--- {name}", flush=True)
    # BEFORE the checkout, not after it. Placed after, this could never fire: checkout_project runs
    # `git checkout` with check=True, which raises first, and the printed verdict was the raw git
    # error this guard exists to replace -- which is exactly what micrometer and junit5 printed.
    #
    # The reason is NOT that the clone is shallow. It is not: checkout_project passes no --depth.
    # An abbreviation usually resolves fine; what does not resolve is a commit the local clone has
    # never fetched, which is the common case because an existing checkout is never re-cloned.
    # checkout_project now fetches and retries, so this guard is about being able to NAME a bad pin
    # rather than about resolvability.
    # ABSENT IS NOT EMPTY. Every reader in this harness spells the root project as an empty
    # `module` -- `f":{module}:test" if module else ":test"` -- so a file that leaves the key out to
    # mean "I do not know which module yet" would be read as "the root project is the one under
    # test", and nothing downstream could tell the two apart. That is
    # `empty-is-not-the-same-as-unknown` in the corpus schema: the same failure that let six files
    # carry `sources = []` while the replay treated it as a pathspec matching everything.
    #
    # An empty string stays legal, because a root project genuinely is one some builds test. What is
    # refused is the key being missing.
    if "module" not in project:
        verdict["blocker"] = (
            "no `module` key. An empty string already means the root project, so a missing key "
            "cannot mean 'not yet known' as well -- state the Gradle module under test, or set "
            "`module = \"\"` to say the root project is the one you mean."
        )
        print(f"    BLOCKED: {verdict['blocker']}", flush=True)
        return verdict

    commit = str(project.get("commit") or "")
    if project.get("url") and len(commit) not in (0, 40):
        verdict["blocker"] = (
            f"commit is {len(commit)} characters, not a full 40-character SHA. Pin the full hash so "
            "the checkout is unambiguous: `git ls-remote --symref <url> HEAD`."
        )
        print(f"    BLOCKED: {verdict['blocker']}", flush=True)
        return verdict

    try:
        checkout = harness.checkout_project(project)
    except Exception as error:  # noqa: BLE001 -- a clone failure is a verdict, not a crash
        verdict["blocker"] = f"could not check out: {error}"
        print(f"    BLOCKED: {verdict['blocker']}", flush=True)
        return verdict

    # STAGED EXACTLY AS THE SWEEP STAGES IT (found in review). A project carrying
    # `drop_empty_sources` is measured by `run.py` on a tree with those files committed away. This
    # gate already passes the project's `flags` into its own build, so the iceberg and Vintage
    # remedies reach it -- staging was the one remedy that did not, and a gate that grades a
    # DIFFERENT tree than the sweep builds is a gate that cannot speak for the sweep. On
    # duckduckgo-android it would have qualified the `*`-scope checkout and reported the collapse
    # this staging exists to remove.
    #
    # A refusal is this project's VERDICT here, not a crash: `assert_droppable` raises SystemExit,
    # and everything else in this function turns a failure into a recorded blocker.
    blocker = screen_build_system(checkout, project)
    if blocker:
        verdict["blocker"] = blocker
        print(f"    BLOCKED: {blocker}", flush=True)
        return verdict

    try:
        harness.stage_checkout(checkout, project)
        # A Maven guest is wired by a committed POM change, as `run.py` wires it.
        if maven_build.is_maven(project):
            verdict["evidence"]["staged_pom"] = maven_build.stage(checkout, project)
    except SystemExit as refused:
        verdict["blocker"] = f"could not stage the checkout: {refused}"
        print(f"    BLOCKED: {verdict['blocker']}", flush=True)
        return verdict
    if maven_build.is_maven(project):
        return qualify_maven(project, checkout, verdict)

    module = project.get("module") or ""
    # THE DIRECTORY, WHICH IS NOT ALWAYS THE GRADLE NAME. Everything below reads files -- build
    # scripts, test-results XML -- so all of it wants the directory. The Gradle path is only ever
    # used to name a task, and `test_task_path` builds that from the project itself. See
    # harness.module_dir for the Micronaut case that separated the two.
    module_dir = harness.module_dir(project)
    # A hint, printed for the reader, never a blocker. See platform_hints.
    verdict["evidence"]["platform_hints"] = platform_hints(checkout, module_dir)

    modules = count_modules(checkout)
    verdict["evidence"]["modules"] = modules

    # The expensive step, and the one that has found every defect: actually run the tests, with the
    # plugin applied, exactly as a sweep would. Instrumented, because a project that cannot capture
    # cannot be measured -- rxjava got this far and then died decoding.
    scratch = os.path.join(HERE, "logs", name)
    os.makedirs(scratch, exist_ok=True)
    log = os.path.join(scratch, "qualify.log")
    # As the qualification table prints it: a path from the repository root, not this machine's.
    shown = os.path.relpath(log, ROOT)
    task = harness.test_task_path(project)
    # Before `started`, so the deletion is not inside the measured window.
    discard_previous_results(checkout, module_dir, harness.test_task_name(project),
                             harness.build_dir(project))
    started = time.time()
    code, seconds = run(
        [harness.wrapper(checkout), *harness.INIT_SCRIPT_ARGS, "-I", harness.GUEST_SELECTION_OFF]
        + harness.daemon_jvm_flags(project)
        + harness.guest_flags(project) + [task, "--no-build-cache", "--console=plain"],
        checkout, log, timeout=QUALIFY_TIMEOUT, stall_seconds=QUALIFY_STALL,
        # Without this a long test task looks identical to a hung one, because Gradle prints
        # nothing for its duration. armeria's :core:test was filed BLOCKED that way while it was
        # still working -- a wrong verdict in the corpus, which costs more than the run did.
        progress_paths=harness.test_progress_paths(checkout, project),
    )
    verdict["evidence"]["seconds"] = seconds
    verdict["evidence"]["log"] = shown

    if code == STALLED:
        verdict["blocker"] = (
            f"the suite went silent for {QUALIFY_STALL // 60} minutes and was killed -- "
            "jstack the test worker before assuming it is slow; spring-core parks forever on a latch"
        )
        print(f"    BLOCKED: {verdict['blocker']}", flush=True)
        return verdict

    # A project the plugin never attached to cannot answer any question this corpus asks, and it
    # looks identical to one that did: same green build, same test counts. That is how three Kotlin
    # Multiplatform projects were added against a plugin gated on the `java` plugin.
    #
    # But "no map directory" is NOT the same as "never attached", and the first version of this
    # guard conflated them. AnkiDroid attached fine and then REFUSED -- it enables JUnit Jupiter's
    # in-JVM parallel execution, which would blend per-test coverage attribution, so the plugin
    # declined and failed the task. No capture ran, so no directory existed, and this reported "the
    # plugin never attached" about the very plugin that had just spoken. A wrong diagnosis sends the
    # next reader to the wrong place entirely.
    #
    # So a FAILED build is reported as a failed build, with the plugin's own words when it has any,
    # and only a SUCCESSFUL build with no map can be called an attachment failure.
    try:
        text = open(log, encoding="utf-8", errors="replace").read()
    except OSError:
        text = ""
    spoke = [line.strip() for line in text.splitlines() if "[yoriwake]" in line]
    # ATTACHMENT IS READ FROM THIS RUN'S OWN OUTPUT, not from a directory on disk.
    #
    # A `.gradle/yoriwake` directory is not evidence that THIS run attached: it outlives the run that
    # made it. kotlinx-coroutines once qualified with 2,191 tests and no map at all,
    # because a directory left by an earlier Kotlin Multiplatform probe -- holding one
    # `task-facts` file and nothing else -- satisfied this check four days later. The plugin had in
    # fact declined the build by name, printing "predictive test selection is NOT configured here
    # and nothing will be captured", and the corpus recorded it as a project ready to be swept.
    # That is the exact failure this screen exists to prevent, in the screen itself.
    #
    # The signal that cannot be stale is the plugin naming a map for a task in this build's log. It
    # separates the two declines correctly, which a directory never could:
    #   - never attached (multiplatform, no host plugin): no `map=` line at all -> BLOCKED below.
    #   - attached and then refused (anki-android's in-JVM parallelism): a `map=` line, then the
    #     refusal, then no capture -> QUALIFIED, with nothing to narrow, which is the truth.
    # A `coverage.tsv` still counts, for a run whose log was lost or truncated.
    named_a_map, captured = attachment(checkout, spoke, started)
    attached = named_a_map or captured
    verdict["evidence"]["plugin_attached"] = attached
    verdict["evidence"]["named_a_map"] = named_a_map
    verdict["evidence"]["captured_a_map"] = captured
    if code != 0 and not attached:
        detail = next((line for line in spoke if "refus" in line.lower() or "enabled on" in line), "")
        # FALL BACK TO GRADLE'S OWN "WHAT WENT WRONG", because the plugin's most important refusal
        # does not carry the [yoriwake] prefix this was looking for.
        #
        # `check(source == null)` in YoriwakePlugin THROWS -- it is a deliberate, permanent decline for a
        # build with in-JVM parallel execution, where interleaved tests would share one coverage
        # agent -- and Gradle prints a thrown message in its failure block, not as a logged line. So
        # caffeine, whose blocker is exactly that and is architectural, was recorded as "the build
        # failed (exit 1) before anything was captured": indistinguishable from a build that ran out
        # of memory. A permanent property of the project and a property of the machine that evening
        # must not read the same, which is the same distinction write_qualification.py draws between
        # BLOCKED and UNRESOLVED.
        if not detail:
            detail = gradle_failure(text)
        verdict["blocker"] = (
            f"the build failed (exit {code}) before anything was captured"
            + (f" -- {detail[:400]}" if detail else "")
            + f"; see {shown}"
        )
        print(f"    BLOCKED: {verdict['blocker']}", flush=True)
        return verdict
    if not attached:
        # The plugin's own sentence when it has one: it says which host plugins it looked for and
        # did not find, which is a better diagnosis than any guess this screen can make.
        declined = next((line for line in spoke if "NOT configured here" in line), "")
        verdict["blocker"] = (
            "the build SUCCEEDED but the plugin never attached: it named no map for any task in "
            "this build, so nothing was captured and a sweep here would measure nothing while "
            # `withJava()` USED TO BE NAMED HERE AS THE USUAL CAUSE, and a later measurement
            # showed that to be false: the call returns, then every multiplatform module fails
            # `checkKotlinGradlePluginConfigurationErrors`. Multiplatform modules with a JVM target
            # now attach, so the remaining causes are a module this plugin can see no
            # JVM tests in, one whose source layout it cannot scope, or a host plugin it does not
            # know. The plugin's own sentence below says which, and is better than a guess here.
            "reporting flawless recall."
            + (f" The plugin said: {declined[:300]}" if declined else "")
        )
        print(f"    BLOCKED: {verdict['blocker']}", flush=True)
        return verdict

    discovered = observed_platform(log)
    verdict["evidence"]["platform_discovered"] = discovered
    tests, failures, test_seconds = test_counts(
        checkout, module_dir, harness.test_task_name(project), started,
        harness.build_dir(project))
    verdict["evidence"]["test_seconds"] = test_seconds
    verdict["evidence"]["tests"] = tests
    verdict["evidence"]["already_failing"] = failures

    # `not tests`, not `is None`: test_counts returns 0 when XML exists but carries no parseable
    # count, and "QUALIFIED: 0 tests" is the same green-build-that-ran-nothing verdict this
    # blocker exists to prevent, one branch to the side of the guard.
    if not tests:
        # A GREEN BUILD THAT RAN NOTHING is the worst outcome available, and it is easy to score as
        # a pass. kotlinx-coroutines printed `BUILD SUCCESSFUL in 6m 25s`, exited 0, and ran zero
        # tests: in a Kotlin Multiplatform build the JVM tests run under `jvmTest`, and `test`
        # compiles everything and runs nothing. Say so, name the likely cause, and never let exit 0
        # alone stand for success.
        # The hint must not depend on `module` being set, nor on a literal `jvm` directory: a
        # root-level multiplatform build, or one with a different source-set layout, hits the same
        # green-build-ran-nothing failure and got the generic message with no pointer to the fix.
        # Two signals, either sufficient: a `jvm` source root anywhere under the measured project,
        # or the multiplatform plugin named in a build script.
        root = os.path.join(checkout, harness.module_path(module_dir)) if module_dir else checkout
        multiplatform = os.path.isdir(os.path.join(root, "jvm"))
        if not multiplatform:
            for script in build_scripts(checkout, module_dir):
                try:
                    if "multiplatform" in open(script, encoding="utf-8", errors="replace").read():
                        multiplatform = True
                        break
                except OSError:
                    pass
        verdict["blocker"] = (
            f"the build exited {code} but ran NO TESTS -- a green build that ran nothing"
            if code == 0 else
            f"the run produced no test results (exit {code})"
        ) + (
            "; this looks like a Kotlin Multiplatform build, whose JVM tests run under `jvmTest` -- "
            "set `test_task = \"jvmTest\"` in the project file"
            if multiplatform else ""
        ) + f"; see {shown}"
        print(f"    BLOCKED: {verdict['blocker']}", flush=True)
        return verdict

    # Absence is NOT evidence, and treating it as such would have demoted okhttp -- which is on the
    # Platform and whose filter has been deselecting tests all day. The tally comes from a shutdown
    # hook in the test JVM, so a task that was up-to-date, filtered, or cached prints nothing: only 4
    # of okhttp's 24 logs carry it. Reported as unknown, never as a blocker.
    #
    # The same mistake as reading an empty coverage map as "nothing is covered", made in the tooling
    # that exists to catch it.

    # A non-zero exit with tests present is a red suite, not a disqualification: kestra has four
    # pre-existing failures and is one of the corpus's better projects. They are subtracted before
    # any recall question, so they cost evidence rather than invalidating it.
    verdict["qualified"] = True

    # WOULD IT NARROW, AND IF NOT WHICH BLOCKER. Map-only, so it costs one Gradle task and not a
    # sweep -- see `first_contact`. QUALIFIED does not come to mean "narrows": it means the floor it
    # has always meant, and this is an additional column beside it.
    verdict["evidence"].update(
        first_contact(checkout, project, module, harness.test_task_name(project), scratch))

    # NO SHAPE VERDICT IS ISSUED HERE, and two attempts to issue one are the reason.
    #
    # First this divided the run's wall clock by the build's module count -- one module's test task
    # over every module in the build, two different populations -- and it labelled
    # detekt-rules-style, 3,593 tests and the corpus's reference project, an ANTI-TARGET.
    #
    # The fix was to sum the suites' own `time=` and compare that against the fixed cost. That is
    # wrong in the other direction: the sum is across PARALLEL forks, so it is CPU time against wall
    # clock. detekt sums 324s of tests inside a 255s run, which makes the fixed cost negative.
    # resilience4j sums 541s -- the most in the corpus -- and its sweep measures +14.1%, NET SLOWER,
    # because most of that is circuit-breaker sleeps that cost no CPU and vanish under parallelism.
    #
    # Both numbers are printed because both are real and useful to a reader. Neither is a verdict.
    # What predicts payoff is coverage concentration, which needs the map: reading it answers that
    # in about a second and is the step after this one. This is the step before, and a
    # step that guesses at the next step's answer is worse than one that says it does not know.
    parallel = test_seconds is not None and seconds and test_seconds > seconds
    verdict["evidence"]["parallel_suite"] = parallel
    print(
        f"    QUALIFIED: {tests} tests in {seconds}s wall"
        + (f", {test_seconds}s summed across suites" if test_seconds else "")
        + (" (parallel forks: summed exceeds wall)" if parallel else "")
        + (f", {failures} already failing" if failures else "")
        + (f", {modules} modules" if modules else ""),
        flush=True,
    )
    print(
        "    platform: "
        + (
            f"CONFIRMED, the filter discovered {discovered} tests"
            if discovered is not None
            else "unknown -- no discovery tally in this log, which is common and proves nothing; "
                 "run a selecting build to settle it"
        ),
        flush=True,
    )
    print(
        "    shape: NOT DECIDED HERE -- capture a map and read its coverage concentration; "
        "this stage has twice mislabelled projects it had no measurement for",
        flush=True,
    )
    return verdict


def maven_test_counts(checkout, module_dir, reports, since):
    """(tests, failures, test_seconds) from the Surefire or Failsafe reports this run wrote, as
    `test_counts` reads Gradle's."""
    pattern = os.path.join(maven_build.reports_dir(checkout, module_dir, reports), "TEST-*.xml")
    files = [p for p in glob.glob(pattern) if os.path.getmtime(p) >= since]
    if not files:
        return None, None, None
    total = failed = 0
    test_seconds = 0.0
    for path in files:
        try:
            head = open(path, encoding="utf-8", errors="replace").read(2000)
        except OSError:
            continue
        suite = re.search(r"<testsuite\b[^>]*>", head)
        if not suite:
            continue
        attributes = dict(re.findall(r'(\w+)="([^"]*)"', suite.group(0)))
        try:
            total += int(attributes.get("tests", 0))
            failed += int(attributes.get("failures", 0)) + int(attributes.get("errors", 0))
            test_seconds += float(attributes.get("time", 0) or 0)
        except ValueError:
            continue
    return total, failed, round(test_seconds, 1)


def qualify_maven(project, checkout, verdict):
    """The screen for a `build = "maven"` project, past its checkout and staging: one capture of
    the measured execution as a sweep's arm runs it (`-pl <module> -am`, upstream tests skipped).

    Attachment is a map for that execution written by this run; a decline is the verdict, with the
    plugin's token; another test execution that ran, or tests read from no fresh report, block it as
    they would every row. First contact is `yoriwake:audit` for the execution.
    """
    name = project["name"]
    module_dir = harness.module_dir(project)
    seams = harness.Seams(project, checkout)
    scratch = os.path.join(HERE, "logs", name)
    os.makedirs(scratch, exist_ok=True)
    log = os.path.join(scratch, "qualify.log")
    shown = os.path.relpath(log, ROOT)
    command, env = harness.maven_arm(checkout, project, [*harness.INIT_SCRIPT_ARGS,
                                                         "-Pyoriwake.internal.loaded",
                                                         harness.test_task_path(project)])
    started = time.time()
    code, seconds = run(command, checkout, log, timeout=QUALIFY_TIMEOUT, stall_seconds=QUALIFY_STALL,
                        progress_paths=harness.test_progress_paths(checkout, project), env=env)
    verdict["evidence"].update(seconds=seconds, log=shown, execution=seams.name)

    def blocked(reason):
        verdict["blocker"] = reason
        print(f"    BLOCKED: {reason}", flush=True)
        return verdict

    if code == STALLED:
        return blocked(f"the suite went silent for {QUALIFY_STALL // 60} minutes and was killed")
    token = maven_build.declined(log, seams.name)
    verdict["evidence"]["declined"] = token
    if token:
        said = next((line for line in maven_build.lines_about(log, seams.name)
                     if "declined" in line or token in line), "")
        return blocked(f"the plugin declined {seams.name}: {token}"
                       + (f" -- {said[:300]}" if said else "") + f"; see {shown}")
    attached = bool(seams.find_map()) and os.path.getmtime(
        os.path.join(seams.find_map(), "coverage.tsv")) >= started
    verdict["evidence"]["plugin_attached"] = maven_build.attached(log)
    verdict["evidence"]["captured_a_map"] = attached
    others = maven_build.other_tests_ran(log, seams.artifact, seams.execution)
    if others:
        return blocked(f"tests of {', '.join(others)} ran beside {seams.name}, so every arm would "
                       f"time them and be refused; see {shown}")
    if not seams.ran(log):
        failure = next((line for line in (maven_build.read_log(log) or "").splitlines()
                        if line.startswith("[ERROR]")), "").strip()
        return blocked(f"the build {'failed' if maven_build.build_failed(log) else 'ended'} "
                       f"(exit {code}) before {seams.name} ran"
                       + (f" -- {failure[:300]}" if failure else "") + f"; see {shown}")
    tests, failures, test_seconds = maven_test_counts(checkout, module_dir,
                                                      seams.execution.reports, started)
    verdict["evidence"].update(tests=tests, already_failing=failures, test_seconds=test_seconds)
    if not tests:
        return blocked(f"{seams.name} ran (exit {code}) but wrote no fresh test report -- a build "
                       f"that ran nothing; see {shown}")
    if not attached:
        unfinished = seams.unfinished_map()
        return blocked(f"{seams.name} ran {tests} tests but this run captured no map"
                       + (f"; raw records under {unfinished} were never merged" if unfinished
                          else "") + f"; see {shown}")
    verdict["qualified"] = True
    audit_log = os.path.join(scratch, "audit.log")
    audit_started = time.time()
    audit_code, _ = run([maven_build.executable(checkout), "-B", "--no-transfer-progress",
                         f"-D{maven_build.VERSION_PROPERTY}={harness.MAVEN_PLUGIN_VERSION}",
                         f"-Dyoriwake.execution={seams.execution.id}", *harness.guest_flags(project),
                         "yoriwake:audit"],
                        checkout, audit_log, env=env)
    verdict["evidence"].update(
        read_audit(seams.find_map(), since=audit_started) if audit_code == 0
        else {"narrowing": "unknown", "blockers": f"audit-did-not-run(exit {audit_code})"})
    print(f"    QUALIFIED: {tests} tests in {seconds}s wall"
          + (f", {test_seconds}s summed across suites" if test_seconds else "")
          + (f", {failures} already failing" if failures else "")
          + f"; first contact {verdict['evidence'].get('narrowing')} "
            f"({verdict['evidence'].get('blockers')})", flush=True)
    return verdict


def first_contact(checkout, project, module, task, scratch):
    """Whether this project would narrow on day one, and the named blockers when it would not.

    **The number this exists to make honest.** Seven projects in this corpus answer "runs
    everything" on first contact, for seven different reasons, and every one of those reasons was
    discoverable only by reading the plugin's source. A qualification that says a project builds and
    attaches, and says nothing about whether it would ever narrow, is a row that reads as a
    recommendation to people who have not read qualify.py's docstring.

    `yoriwakeAudit<Task>` answers it from the map alone: no suite runs, no base is needed, and the
    blockers come back as tokens rather than as prose to be matched. Costs about a second on top of
    a qualification that already ran the suite.

    Returns evidence keys only. A failure to ask is recorded as `unknown`, never as "no blockers":
    an audit that could not run has cleared nothing.
    """
    log = os.path.join(scratch, "audit.log")
    audit_task = f":{module}:yoriwakeAudit{task[:1].upper()}{task[1:]}" if module \
        else f"yoriwakeAudit{task[:1].upper()}{task[1:]}"
    # `-I INIT_SCRIPT`, WHICH `harness.gradle` DOES NOT ADD. Every caller passes it in the task
    # arguments, and a run without it applies no plugin at all -- the task is then simply not found,
    # the build exits 1, and a STALE audit.json from an earlier session sits on disk reading like
    # this run's answer. That is the restored-artifact defect this harness has already shipped once,
    # and `since` below is the other half of not shipping it again.
    started = time.time()
    code, _ = harness.gradle(checkout, project, [*harness.INIT_SCRIPT_ARGS, audit_task], log)
    if code != 0:
        return {"narrowing": "unknown", "blockers": f"audit-did-not-run(exit {code})"}
    return read_audit(audit_payload_dir(checkout, module, task), since=started)


def audit_payload_dir(checkout, module, task):
    """Where `yoriwakeAudit` wrote, which is NOT always where `find_map` looks.

    `find_map` requires a `coverage.tsv`, because its caller needs a map to select against. The
    audit writes its payload whatever the map holds -- and the case that matters most is precisely
    the one with no coverage.tsv: micronaut-core runs 7,394 tests and records none, so the audit's
    account of that is the one thing `find_map` cannot reach. Reading it through `find_map` reported
    `audit-wrote-nothing` about a run that had written a full explanation.
    """
    readable = module.strip(":").replace(":", "-")
    prefix = f"{readable}-{task}-" if readable else f"{task}-"
    root = os.path.join(checkout, ".gradle", "yoriwake")
    candidates = sorted(
        d for d in glob.glob(os.path.join(root, "*"))
        if os.path.isdir(d) and os.path.basename(d).startswith(prefix)
    )
    return candidates[0] if candidates else harness.find_map(checkout, module, task)


def read_audit(map_dir, since=None):
    """The narrowing verdict and blocker tokens `yoriwakeAudit` wrote, as evidence keys.

    Split from `first_contact` because everything interesting about it is a pure function of a file
    on disk, and the half that needs a checkout and a Gradle daemon is three lines. Three of
    `qualify.py`'s guards shipped unable to fire for exactly this reason: they were pure functions
    hiding behind something expensive, so nothing forced the question.
    """
    payload = os.path.join(map_dir, "audit.json") if map_dir else None
    if not payload or not os.path.isfile(payload):
        return {"narrowing": "unknown", "blockers": "audit-wrote-nothing"}
    # A payload older than the run that was supposed to write it describes a different plugin, a
    # different checkout state, or both. Reading it is how "0 missed" gets reported by a run that
    # executed nothing.
    if since is not None and os.path.getmtime(payload) < since:
        return {"narrowing": "unknown", "blockers": "audit-payload-is-stale"}
    try:
        with open(payload, encoding="utf-8") as handle:
            audit = json.load(handle)
    except (OSError, ValueError) as error:
        return {"narrowing": "unknown", "blockers": f"audit-unreadable({type(error).__name__})"}

    blockers = [b.get("token", "?") for b in audit.get("blockers") or []]
    return {
        # The audit's own terminal state, not a re-derivation of it. `NO CONCLUSION` is reached
        # from eight different refusal branches and is the commonest answer on a new project; this
        # must not quietly turn one of them into a verdict.
        "narrowing": audit.get("state", "unknown").replace(" ", "-").lower(),
        "blockers": ",".join(blockers) if blockers else "none",
        **recorded_time(audit),
    }


def recorded_time(audit):
    """What the map says its tests took, as evidence keys, or why it says nothing.

    `recordedTime` shipped with no Python reader at all. A field nobody reads stops being written and
    nothing fails -- which is how `full_run_reason` came to hold a capture sentence on 119 rows of a
    published corpus before anyone noticed. This is that reader.

    THREE ANSWERS, not two. `absent` is a payload written before the field existed; `unknown` is a
    map that records no durations, which the plugin writes as null precisely so nobody divides by a
    zero; and a number is a number. Collapsing the first two into "0" would make a map that measured
    nothing look like a suite that takes no time.
    """
    if "recordedTime" not in audit:
        return {"recorded_seconds": "absent", "recorded_workers": "absent"}
    recorded = audit.get("recordedTime")
    if not isinstance(recorded, dict):
        return {"recorded_seconds": "unknown", "recorded_workers": "unknown"}
    nanos = recorded.get("nanos")
    workers = recorded.get("workers")
    return {
        "recorded_seconds": round(nanos / 1_000_000_000, 3) if isinstance(nanos, int) else "unknown",
        # The payback divisor. Null means the capture never counted its forks, and a suite whose
        # durations are summed across four JVMs and read as one is overstated fourfold.
        "recorded_workers": workers if isinstance(workers, int) else "unknown",
        # THE CAVEATS TRAVEL WITH THE FIGURE. The plugin prints this number with three
        # qualifications -- summed across workers, measured inside an instrumented JVM, not a suite
        # duration -- and a corpus row that carries the seconds without them is the quotable-out-of-
        # context shape `published-numbers-and-their-caveats.md` exists to stop.
        "recorded_is_suite_duration": recorded.get("isSuiteDuration"),
        "recorded_without_duration": recorded.get("recordsWithoutDuration"),
        "recorded_duplicated_ids": recorded.get("duplicatedIds"),
    }


def _corpus_order(path):
    """Projects that borrow another project's checkout go last.

    `detekt-junk` is `path = "bench/checkouts/detekt"` -- the same checkout, deliberately, so
    that a junk-file screen measures the reference project rather than a copy of it. Under --all the
    files were taken in filename order, and `detekt-junk.toml` sorts BEFORE `detekt.toml` because
    `-` precedes `.`. On a machine with no checkouts yet that is unqualifiable by construction: it
    ran first and found no build at the path detekt had not been cloned to.

    A borrower is any project with a `path`; there is no ordering among them, because none of them
    owns a checkout for another to borrow.
    """
    try:
        with open(path, "rb") as handle:
            borrows = bool(tomllib.load(handle).get("path"))
    except (OSError, tomllib.TOMLDecodeError):
        borrows = False
    return (borrows, os.path.basename(path))


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("project", nargs="?", help="a TOML under bench/projects/")
    parser.add_argument("--all", action="store_true", help="every project file")
    parser.add_argument("--json", action="store_true", help="machine-readable verdicts on stdout")
    args = parser.parse_args()

    if args.all:
        paths = sorted(glob.glob(os.path.join(HERE, "projects", "*.toml")), key=_corpus_order)
    elif args.project:
        paths = [args.project]
    else:
        parser.error("name a project file or pass --all")

    return main_with_paths(paths, as_json=args.json)


def attachment(checkout, spoke, since):
    """(named_a_map, captured): did the plugin attach during THIS run?

    Module-level so a test drives the shipping decision rather than a copy of it. The first version
    lived inline in `qualify()` and its test reimplemented it verbatim, which is the anti-pattern
    `main_with_paths` was split out to avoid: the case passed whether or not this code did anything.

    `named_a_map` is the signal that cannot be stale -- the plugin printing a map path for a task in
    this build's log. `captured` is the fallback for a run whose log was lost, and it is bounded by
    `since` because a map file outlives its run and a borrowed checkout holds the lender's.
    """
    named_a_map = any("map=" in line for line in spoke)
    captured = any(
        os.path.getmtime(path) >= since
        for path in glob.glob(
            os.path.join(checkout, "**", ".gradle", "yoriwake", "*", "coverage.tsv"), recursive=True))
    return named_a_map, captured


def reap():
    """Kill Gradle and Kotlin daemons between projects, never during one.

    Between, because a daemon serving the project currently under test is the build. `time.sleep`
    rather than a poll: a daemon that has been signalled takes a moment to release its heap, and
    starting the next project's build before it does is how the memory is never actually reclaimed.
    """
    # Only the harness's own: another project's daemon is not ours to stop. See `own_processes`.
    own_processes.reap_own()
    time.sleep(3)


def main_with_paths(paths, as_json, publish=None):
    """Everything between a list of project files and the bytes on stdout.

    Split out from `main` so a test can drive THE SHIPPING PATH rather than a copy of it. The first
    version of `case_json_output_is_parseable_on_its_own` reimplemented the stdout redirection
    inline and asserted against its own reimplementation, so it passed whether or not this function
    did any of it -- and kept passing when the payload changed from one array to one object per
    line. `publish` is injectable for the same reason and defaults to the real one.
    """
    # UNDER --json, STDOUT CARRIES THE JSON AND NOTHING ELSE.
    #
    # Every progress line below -- and there are hundreds over a --all run, plus whatever the
    # screens print -- went to stdout alongside the payload, so `qualify.py --all --json > out.json`
    # produced a file that is not JSON and that no consumer can read. The run still took hours and
    # still printed a correct answer; it simply could not be parsed afterwards, which for a
    # machine-readable flag is the whole of its job.
    #
    # Redirected for the duration rather than threading a stream through every print: the progress
    # commentary is genuinely for a person watching, and a person watching a redirected run is
    # reading stderr anyway.
    if publish is None:
        # A Maven project resolves the Maven plugin as well, so it is published beside the other.
        maven = maven_build.any_maven(paths)

        def publish():
            if maven:
                harness.publish_plugin_under_test(maven=True)
            else:
                harness.publish_plugin_under_test()
    progress = sys.stderr if as_json else sys.stdout
    real_stdout = sys.stdout
    sys.stdout = progress
    try:
        publish()

        verdicts = []
        for path in paths:
            with open(path, "rb") as handle:
                project = tomllib.load(handle)
            try:
                verdict = qualify(project)
            except SystemExit as refusal:
                # A PRECONDITION REFUSAL IS ONE PROJECT'S PROBLEM, NOT THE CORPUS'S.
                #
                # `daemon_jvmargs`' JDK lookup raises SystemExit when the toolchain a project asks
                # for is not on the machine, which is right for a single invocation and wrong under
                # --all: ktlint wants a JDK 26 daemon, and once that ended a corpus run at
                # the twelfth of twenty-one projects. The nine after it were never attempted, and
                # the twelve before it were lost with the process.
                #
                # Recorded with its text so the collator files it as UNRESOLVED rather than as a
                # verdict about the project: nothing here was learned about ktlint.
                verdict = {
                    "name": project["name"],
                    "module": project.get("module") or "",
                    "qualified": False,
                    "blocker": f"{COULD_NOT_START}: {refusal}",
                    "evidence": {},
                }
                print(f"  {verdict['blocker']}", flush=True)
            verdicts.append(verdict)
            # WRITTEN THE MOMENT IT LANDS, not collected and dumped at the end.
            #
            # `write_qualification.py` says in its own docstring that a partial corpus is a useful
            # corpus and an unwritten one is not, and then this buffered every verdict until the
            # last project finished. One SystemExit twelve projects in produced an EMPTY file after
            # roughly half an hour of correct qualification. The collator scans for objects and
            # neither needs nor notices the enclosing array.
            if as_json:
                json.dump(verdict, real_stdout)
                real_stdout.write("\n")
                real_stdout.flush()
            # REAPED BETWEEN PROJECTS, and this is not tidiness. Every project starts a daemon, and
            # a project with its own `daemon_java_version` or `daemon_jvmargs` starts a DIFFERENT
            # one -- so a corpus run accumulates one long-lived JVM per distinct configuration and
            # never gives any of them back. At 25 projects that survived; at 40 it did not. Once
            # a `--all` run was killed by the OS for low memory partway through the
            # twenty-ninth project, with 15 daemons and 6.8 GB resident, and the kill looks exactly
            # like the project having failed.
            #
            # `sweep_corpus.py` has had this between projects since it was written. The qualifying
            # path is the longer of the two and did not.
            reap()

        ok = [v for v in verdicts if v["qualified"]]
        never_started = [v for v in verdicts
                         if (v["blocker"] or "").startswith(COULD_NOT_START)]
        print(f"\n{len(ok)} of {len(verdicts)} qualified", flush=True)
        for verdict in verdicts:
            if not verdict["qualified"]:
                print(f"  {verdict['name']}: {verdict['blocker']}", flush=True)
        if never_started:
            print(f"{len(never_started)} never started and were not judged: "
                  f"{', '.join(v['name'] for v in never_started)}", flush=True)
    finally:
        sys.stdout = real_stdout

    # A RUN THAT COULD NOT ATTEMPT EVERY PROJECT IS NOT A SUCCESSFUL RUN, even when the projects it
    # did attempt all qualified. Continuing past one project's precondition refusal is right -- the
    # other twenty should not be lost to it -- but exiting 0 tells a script that the corpus was
    # covered, and it was not. The verdict file says which; the exit code says only "look".
    if never_started:
        return 2
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
