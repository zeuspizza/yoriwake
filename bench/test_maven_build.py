"""
Tests for the Maven side of the harness's build seam (`maven_build.py`, and `run.Seams` over it).

Each reader is driven with the shapes a real Maven arm leaves: the JUnit XML Surefire wrote for
`samples/demo-maven`, the plugin's lines as Maven's logger prints them, and Maven's own goal,
failure and transfer lines. A reader that answered "ran", "clean" or "found" for the wrong shape
would score a row that measured nothing.

Run with `python bench/test_maven_build.py`, or under pytest.

Cases are named case_* and RETURN a count of failed checks, as in test_run.py; the single test_*
entry point below asserts.
"""

import os
import shutil
import subprocess
import sys
import tempfile
import time
from types import SimpleNamespace

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import maven_build  # noqa: E402
import run  # noqa: E402

PROJECT = {"name": "demo-maven", "build": "maven", "module": "app"}
NAME = "app surefire:test (default-test)"
KEY = "dev.demo:app:maven-surefire-plugin:default-test"

# As Surefire 3.5.6 wrote it for `samples/demo-maven` (properties and stack trimmed).
REPORT = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" version="3.0.2" name="{cls}" time="0.046" tests="2" errors="0" skipped="0" failures="1" flakes="0">
  <testcase name="welcomes" classname="{cls}" time="0.022">
    <failure message="expected: &lt;Hello, app&gt; but was: &lt;yoriwake-mutated&gt;" type="org.opentest4j.AssertionFailedError"><![CDATA[org.opentest4j.AssertionFailedError]]></failure>
  </testcase>
  <testcase name="counts" classname="{cls}" time="0.001"/>
</testsuite>
"""

# A selecting arm's log as Maven 3.9.16 printed it for `-pl app -am test -DskipTests`.
SELECTED_LOG = """[INFO] [yoriwake] attached to Maven 3.9.16
[INFO] --- surefire:3.5.6:test (default-test) @ lib ---
[INFO] Tests are skipped.
[INFO]
[INFO] ------------------------------------------------------------------------
[INFO] Building app 1.0                                                   [2/2]
[INFO] [yoriwake] app surefire:test (default-test): narrowing, so nothing is instrumented and the map is left alone. A partial run cannot produce a map worth keeping.
[INFO] [yoriwake] app surefire:test (default-test): a changed resource no test reads
[INFO] --- surefire:3.5.6:test (default-test) @ app ---
[INFO] Using auto detected provider org.apache.maven.surefire.junitplatform.JUnitPlatformProvider
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.041 s <<< FAILURE! -- in dev.demo.app.AppTest
[INFO] ------------------------------------------------------------------------
[INFO] BUILD FAILURE
[INFO] ------------------------------------------------------------------------
"""


def fail(message):
    print(f"  FAIL: {message}")
    return 1


def _write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(text)
    return path


def _log(tmp, text, name="arm.log"):
    return _write(os.path.join(tmp, name), text)


def _git(path, *args):
    subprocess.run(["git", "-C", path, *args], check=True, capture_output=True)


def _repo(tmp, files):
    for relative, text in files.items():
        _write(os.path.join(tmp, relative), text)
    _git(tmp, "init", "-q")
    _git(tmp, "add", "-A")
    # Both dates fixed, so two repositories made from the same files share the pinned commit.
    subprocess.run(["git", "-C", tmp, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm",
                    "pinned"], check=True, capture_output=True,
                   env={**os.environ, "GIT_AUTHOR_DATE": "2026-01-01T00:00:00Z",
                        "GIT_COMMITTER_DATE": "2026-01-01T00:00:00Z"})
    return tmp


def _pom(artifact, body="", parent=True, group=None):
    head = ("<parent><groupId>dev.demo</groupId><artifactId>root</artifactId>"
            "<version>1.0</version></parent>" if parent else "")
    group = f"<groupId>{group}</groupId>" if group else ""
    return (f'<?xml version="1.0" encoding="UTF-8"?>\n<project xmlns="http://maven.apache.org/POM/'
            f'4.0.0">\n  <modelVersion>4.0.0</modelVersion>\n  {head}{group}\n'
            f'  <artifactId>{artifact}</artifactId>\n{body}</project>\n')


JACOCO = ("<plugin><groupId>org.jacoco</groupId><artifactId>jacoco-maven-plugin</artifactId>"
          "<version>0.8.12</version><executions><execution><id>agent</id><goals>"
          "<goal>{goal}</goal></goals><configuration><append>true</append></configuration>"
          "</execution></executions></plugin>")


def case_fresh_surefire_xml_reads_as_ran_and_failed_and_stale_xml_as_nothing():
    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        path = _write(os.path.join(tmp, "lib", "target", "surefire-reports",
                                   "TEST-dev.demo.lib.GreeterTest.xml"),
                      REPORT.format(cls="dev.demo.lib.GreeterTest"))
        ran, failed = maven_build.read_results(tmp, "lib", 0, "surefire-reports")
        if ran != {("dev.demo.lib.GreeterTest", "welcomes"), ("dev.demo.lib.GreeterTest", "counts")}:
            failures += fail(f"the report read as ran {ran}")
        if failed != {("dev.demo.lib.GreeterTest", "welcomes")}:
            failures += fail(f"the report read as failed {failed}")
        os.utime(path, (time.time() - 100, time.time() - 100))
        if maven_build.read_results(tmp, "lib", time.time() - 10, "surefire-reports") \
                != (None, None):
            failures += fail("a report older than the arm was read as that arm's results")
    return failures


def case_a_failsafe_project_reads_failsafe_reports_and_never_surefire_reports():
    failures = 0
    project = {**PROJECT, "test_plugin": "failsafe"}
    execution = maven_build.Execution(project)
    if execution.id != "default-integration-test":
        failures += fail(f"a Failsafe project without `execution` resolved to {execution.id}")
    if execution.phase != "verify":
        failures += fail(f"a Failsafe arm builds to {execution.phase}")
    with tempfile.TemporaryDirectory() as tmp:
        _write(os.path.join(tmp, "app", "target", "surefire-reports", "TEST-a.UnitTest.xml"),
               REPORT.format(cls="a.UnitTest"))
        read = run.results_reader(project)
        outcomes = read(tmp, "app", 0, "test", os.path.join(tmp, "none.log"))
        if outcomes[0] is not None:
            failures += fail(f"a Failsafe project read Surefire's reports: {outcomes[0]}")
        _write(os.path.join(tmp, "app", "target", "failsafe-reports", "TEST-a.ApiIT.xml"),
               REPORT.format(cls="a.ApiIT"))
        outcomes = read(tmp, "app", 0, "test", os.path.join(tmp, "none.log"))
        if not outcomes[0] or {cls for cls, _ in outcomes[0]} != {"a.ApiIT"}:
            failures += fail(f"a Failsafe project read {outcomes[0]}")
        if outcomes.methods is not None:
            failures += fail("a Maven read claimed per-method outcomes")
    return failures


def case_the_map_is_the_measured_executions_and_no_other():
    failures = 0
    if maven_build.sanitize(KEY) != "dev.demo-app-maven-surefire-plugin-default-test-a429ac11":
        failures += fail(f"the key sanitises to {maven_build.sanitize(KEY)}, not the plugin's name")
    with tempfile.TemporaryDirectory() as tmp:
        other = os.path.join(maven_build.maps_root(tmp), maven_build.sanitize(
            "dev.demo:lib:maven-surefire-plugin:default-test"))
        _write(os.path.join(other, "coverage.tsv"), "")
        if maven_build.find_map(tmp, KEY) is not None:
            failures += fail("another module's default-test map was handed over as app's")
        ours = maven_build.map_dir_of(tmp, KEY)
        _write(os.path.join(ours, "raw", "worker-1", "r.exec"), "")
        if maven_build.find_map(tmp, KEY) is not None:
            failures += fail("a capture with raw records and no coverage.tsv was found as a map")
        if maven_build.unfinished_map(tmp, KEY) != ours:
            failures += fail("a capture with raw records and no coverage.tsv is not unfinished")
        _write(os.path.join(ours, "coverage.tsv"), "")
        if maven_build.find_map(tmp, KEY) != ours or maven_build.unfinished_map(tmp, KEY):
            failures += fail("the measured execution's merged map was not found")
    return failures


def case_the_plugins_reason_is_read_through_mavens_prefix_and_capture_openers_are_skipped():
    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        log = _log(tmp, SELECTED_LOG)
        reason = maven_build.full_run_reason(log, NAME, run.CAPTURE_REASON_OPENERS)
        if reason != "a changed resource no test reads":
            failures += fail(f"the reason read as {reason!r}")
        if maven_build.full_run_reason(log, "lib surefire:test (default-test)",
                                       run.CAPTURE_REASON_OPENERS) is not None:
            failures += fail("another execution's line was read as this one's reason")
    return failures


def _seams(tmp, project=PROJECT):
    """`run.Seams` for a project whose coordinates are demo-maven's app."""
    _write(os.path.join(tmp, "app", "pom.xml"), _pom("app"))
    return run.Seams(project, tmp)


def case_a_declined_measured_execution_is_a_refusal_with_its_token():
    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        seams = _seams(tmp)
        log = _log(tmp, "[WARNING] [yoriwake] app surefire:test (default-test) declined, "
                        "surefire-legacy-provider: the POM gives maven-surefire-plugin the "
                        "surefire-junit4 provider; every test runs and nothing is captured\n"
                   + SELECTED_LOG)
        findings = []
        if seams.executed_or_excluded(log, "T", findings, "selected"):
            failures += fail("an arm whose execution was declined was scored")
        elif findings[-1].get("declined") != "surefire-legacy-provider" \
                or findings[-1].get("status") != "invalid":
            failures += fail(f"the declined arm was filed as {findings[-1]}")
        other = _log(tmp, "[WARNING] [yoriwake] lib surefire:test (default-test) declined, "
                          "surefire-legacy-provider: ...\n" + SELECTED_LOG, "other.log")
        if seams.arm_refusal(other) is not None:
            failures += fail("another execution's decline refused the measured one's row")
        below = _log(tmp, "[WARNING] [yoriwake] maven-version: Maven 3.8.8 is older than "
                          "3.9.0; yoriwake does nothing in this build\n", "below.log")
        if maven_build.declined(below, NAME) != "maven-version":
            failures += fail("a build declined below the Maven floor was not a refusal")
    return failures


def case_an_execution_ran_only_when_its_goal_started_and_did_not_skip():
    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        execution = maven_build.Execution(PROJECT)
        log = _log(tmp, SELECTED_LOG)
        if not maven_build.execution_ran(log, "app", execution):
            failures += fail("an execution with its goal line and failing tests did not count as run")
        if maven_build.execution_ran(log, "lib", execution):
            failures += fail("a skipped execution counted as run")
        early = _log(tmp, "[INFO] --- compiler:3.13.0:compile (default-compile) @ lib ---\n"
                          "[ERROR] COMPILATION ERROR :\n[INFO] BUILD FAILURE\n", "early.log")
        if maven_build.execution_ran(early, "app", execution):
            failures += fail("a build that failed before the execution counted as run")
        seams = _seams(tmp)
        findings = []
        seams.executed_or_excluded(early, "T", findings, "selected")
        if findings[-1].get("status") != "build-failed":
            failures += fail(f"a build that failed before the tests was filed {findings[-1]}")
        findings = []
        seams.executed_or_excluded(_log(tmp, "[ERROR] java.lang.OutOfMemoryError\n", "killed.log"),
                                   "T", findings, "selected")
        if findings[-1].get("status") != "build-failed":
            failures += fail(f"a Maven killed without its failure banner was filed {findings[-1]}")
        findings = []
        seams.executed_or_excluded(_log(tmp, "[INFO] BUILD SUCCESS\n", "quiet.log"), "T",
                                   findings, "selected")
        if findings[-1].get("status") != "not-run":
            failures += fail(f"an execution that never ran in a green build was filed {findings[-1]}")
    return failures


def case_an_upstream_test_execution_that_ran_refuses_the_row():
    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        seams = _seams(tmp)
        log = _log(tmp, SELECTED_LOG.replace("[INFO] Tests are skipped.\n",
                                             "[INFO] Tests run: 2, Failures: 0\n"))
        findings = []
        if seams.executed_or_excluded(log, "T", findings, "ground-truth"):
            failures += fail("a row whose upstream module's tests ran was scored")
        elif "lib surefire (default-test)" not in findings[-1].get("invalid_reason", ""):
            failures += fail(f"the refusal does not name the execution that ran: {findings[-1]}")
    return failures


def case_a_compiler_diagnostic_is_a_rejection_and_a_resolution_failure_is_not():
    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        javac = _log(tmp, "[ERROR] COMPILATION ERROR :\n[ERROR] /x/A.java:[3,5] ';' expected\n"
                          "[INFO] BUILD FAILURE\n", "javac.log")
        kotlinc = _log(tmp, "[ERROR] e: file:///x/A.kt:3:5 Expecting ')'\n"
                            "[INFO] BUILD FAILURE\n", "kotlinc.log")
        resolve = _log(tmp, "[ERROR] Failed to execute goal on project app: Could not resolve "
                            "dependencies for project dev.demo:app:jar:1.0\n[INFO] BUILD FAILURE\n",
                       "resolve.log")
        if not maven_build.compiler_rejected(javac) or not maven_build.compiler_rejected(kotlinc):
            failures += fail("a compiler diagnostic was not read as the generator's rejection")
        if maven_build.compiler_rejected(resolve):
            failures += fail("a dependency resolution failure was read as a generator rejection")
        code, rejected = run.compile_or_reject(tmp, PROJECT, [":app:testClasses"], resolve,
                                               lambda *_: (1, 0.0))
        if rejected:
            failures += fail("compile_or_reject discarded a Maven resolution failure as a rejection")
    return failures


def case_a_transfer_failure_reruns_once_and_a_missing_artifact_does_not():
    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        transfer = _log(tmp, "[INFO] BUILD FAILURE\n[ERROR] Failed to execute goal on project "
                             "app: Could not transfer artifact a:b:jar:1 from/to central "
                             "(https://repo.maven.apache.org/maven2): Connection reset\n",
                        "transfer.log")
        missing = _log(tmp, "[INFO] BUILD FAILURE\n[ERROR] Failed to execute goal on project "
                            "app: Could not transfer artifact a:b:jar:1 from/to central: status "
                            "code: 404, reason phrase: Not Found (404)\n", "missing.log")
        if maven_build.download_failure(transfer) is None:
            failures += fail("a transfer that failed in transit was not retried")
        if maven_build.download_failure(missing) is not None:
            failures += fail("a 404 was retried as a transient download failure")
    return failures


def case_a_maven_arm_translates_the_gradle_arm_and_refuses_what_it_cannot():
    failures = 0
    selecting = maven_build.invocation(
        PROJECT, [*run.INIT_SCRIPT_ARGS, ":app:test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD",
                  *run.TIMING_ARM_FLAGS], staged_jacoco=True, version="V")
    for wanted in ("-pl", "app", "-am", "test", "-DskipTests", "-Dyoriwake.select",
                   "-Dyoriwake.base=HEAD", "-Dyoriwake.harness.version=V", "-Dpts.enabled=false"):
        if wanted not in selecting:
            failures += fail(f"the selecting arm lacks {wanted}: {selecting}")
    if "-Dyoriwake.enabled=false" in selecting or "-Djacoco.skip=true" in selecting:
        failures += fail("the selecting arm switched the plugin or its agent off")
    baseline = maven_build.invocation(PROJECT, [":app:test", "--rerun-tasks"],
                                      staged_jacoco=True, version="V")
    if "-Dyoriwake.enabled=false" not in baseline or "-Djacoco.skip=true" not in baseline:
        failures += fail(f"the uninstrumented arm runs the plugin or the staged agent: {baseline}")
    gate = maven_build.invocation(PROJECT, [":app:testClasses"], staged_jacoco=False, version="V")
    if "test-compile" not in gate:
        failures += fail(f"the compile gate does not compile: {gate}")
    alone = maven_build.invocation(PROJECT, [":app:test", "--tests", "a.B"], staged_jacoco=False,
                                   version="V")
    if "-Dtest=a.B" not in alone:
        failures += fail(f"a class filter was dropped: {alone}")
    failsafe = maven_build.invocation({**PROJECT, "test_plugin": "failsafe"},
                                      [":app:test", "--tests", "a.B"], staged_jacoco=False, version="V")
    if "-Dit.test=a.B" not in failsafe or "verify" not in failsafe:
        failures += fail(f"a Failsafe arm does not verify or filter through it.test: {failsafe}")
    probe = maven_build.invocation(PROJECT, [":app:testClasses", "--dry-run"], staged_jacoco=False,
                                   version="V")
    if "validate" not in probe or "test-compile" in probe:
        failures += fail(f"the probe compiles instead of validating the reactor: {probe}")
    for refused in (["-Pyoriwake.internal.classSelection", ":app:test"], [":lib:test"]):
        try:
            maven_build.invocation(PROJECT, refused, staged_jacoco=False, version="V")
            failures += fail(f"{refused} ran as a Maven arm")
        except maven_build.Refused:
            pass
    return failures


def case_a_resource_renamed_away_leaves_no_copy_and_a_tests_own_file_stays():
    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        _write(os.path.join(tmp, "pom.xml"), _pom("root", parent=False, group="dev.demo"))
        _write(os.path.join(tmp, "app", "pom.xml"), _pom("app"))
        _write(os.path.join(tmp, "lib", "pom.xml"), _pom("lib"))
        source = _write(os.path.join(tmp, "lib", "src", "main", "resources", "old.txt"), "a")
        old = _write(os.path.join(tmp, "lib", "target", "classes", "old.txt"), "copied")
        written = _write(os.path.join(tmp, "app", "target", "test-classes", "cache.tmp"), "a test's")
        report = _write(os.path.join(tmp, "app", "target", "surefire-reports", "TEST-a.xml"), "")
        mapped = _write(os.path.join(maven_build.map_dir_of(tmp, KEY), "coverage.tsv"), "")
        commands = []
        saved = run.run, maven_build.executable
        run.run = lambda command, *a, **k: commands.append(command) or (0, 0.0)
        maven_build.executable = lambda checkout: "mvn"
        try:
            run._maven_once(tmp, PROJECT, [":app:test"], os.path.join(tmp, "first.log"))
            if not os.path.exists(old):
                failures += fail("a copy of a resource the tree still has was removed")
            os.rename(source, os.path.join(os.path.dirname(source), "new.txt"))
            run._maven_once(tmp, PROJECT, [":app:testClasses"], os.path.join(tmp, "gate.log"))
        finally:
            run.run, maven_build.executable = saved
        if os.path.exists(old):
            failures += fail("a resource renamed away survived into the next build's outputs")
        if not (os.path.exists(written) and os.path.exists(report) and os.path.exists(mapped)):
            failures += fail("a file a test wrote, the reports or the map were removed")
        if not commands or commands[-1][0] != "mvn":
            failures += fail(f"the arm did not run Maven: {commands}")
    return failures


def case_a_gradle_project_takes_the_gradle_seam_with_its_arguments_unchanged():
    failures = 0
    gradle = {"name": "demo", "module": "", "commit": "local"}
    commands = []
    saved = run.run
    run.run = lambda command, *a, **k: commands.append(command) or (0, 0.0)
    try:
        with tempfile.TemporaryDirectory() as tmp:
            run.gradle(tmp, gradle, ["test", "--rerun-tasks"], os.path.join(tmp, "a.log"))
    finally:
        run.run = saved
    command = commands[0] if commands else []
    if not command or not os.path.basename(command[0]).startswith("gradlew"):
        failures += fail(f"a Gradle project's arm did not run Gradle: {command}")
    if "test" not in command or "--rerun-tasks" not in command or "-pl" in command:
        failures += fail(f"a Gradle project's arguments changed: {command}")
    seams = run.Seams(gradle, tempfile.gettempdir())
    if seams.maven or seams.arm_refusal("/no/such.log") is not None:
        failures += fail("a Gradle project was given a Maven seam")
    return failures


def case_a_maven_run_refuses_the_arms_it_has_no_meaning_for():
    failures = 0
    parsed = run.argument_parser().parse_args
    for argv, named in ((["p.toml", "--class-granularity"], "--class-granularity"),
                        (["p.toml", "--ground-truth", "check"], "--ground-truth check"),
                        (["p.toml", "--ground-truth", "auto"], "--ground-truth auto"),
                        (["p.toml", "--isolated-capture"], "--isolated-capture"),
                        (["p.toml", "--replay", "3"], "--replay")):
        refusal = run.maven_refusal(parsed(argv))
        if not refusal or named not in refusal:
            failures += fail(f"a Maven run with {named} was not refused: {refusal}")
    if run.maven_refusal(parsed(["p.toml"])) is not None:
        failures += fail("a plain Maven sweep was refused")
    return failures


def _staged(tmp, files, project=PROJECT):
    _repo(tmp, files)
    staged = maven_build.stage(tmp, project)
    head = subprocess.run(["git", "-C", tmp, "rev-parse", "HEAD"], capture_output=True,
                          text=True).stdout.strip()
    return staged, head


def _read(tmp, relative):
    with open(os.path.join(tmp, relative), encoding="utf-8") as handle:
        return handle.read()


ROOT_POM = _pom("root", parent=False, group="dev.demo",
                body="  <packaging>pom</packaging>\n  <modules><module>app</module></modules>\n")


def case_staging_adds_jacoco_only_where_no_pom_declares_its_agent():
    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        staged, _ = _staged(tmp, {"pom.xml": ROOT_POM, "app/pom.xml": _pom("app"),
                                  ".gitignore": "target/\n"})
        root = _read(tmp, "pom.xml")
        if "jacoco prepare-agent added" not in staged or "<goal>prepare-agent</goal>" not in root:
            failures += fail(f"a reactor without JaCoCo gained no agent: {staged}")
        if root.count("yoriwake-maven-plugin") != 1 or "plugin added" not in staged:
            failures += fail("the plugin was not added once")
        if "<skipTests>false</skipTests>" not in _read(tmp, "app/pom.xml"):
            failures += fail("the measured execution was not staged with skipTests=false")
        if subprocess.run(["git", "-C", tmp, "status", "--porcelain"], capture_output=True,
                          text=True).stdout.strip():
            failures += fail("staging left the tree dirty, so every row's change set holds it")
    with tempfile.TemporaryDirectory() as tmp:
        app = _pom("app", body=f"  <build><plugins>{JACOCO.format(goal='prepare-agent')}"
                               f"</plugins></build>\n")
        staged, _ = _staged(tmp, {"pom.xml": ROOT_POM, "app/pom.xml": app,
                                  ".gitignore": "target/\n"})
        if any("jacoco" in item for item in staged) or "<append>true</append>" not in \
                _read(tmp, "app/pom.xml") or "jacoco" in _read(tmp, "pom.xml"):
            failures += fail(f"a reactor with its own agent gained a second, or lost its "
                             f"configuration: {staged}")
    with tempfile.TemporaryDirectory() as tmp:
        staged, _ = _staged(tmp, {"pom.xml": ROOT_POM, "app/pom.xml": _pom("app"),
                                  ".gitignore": "target/\n"},
                            {**PROJECT, "test_plugin": "failsafe"})
        if "jacoco prepare-agent-integration added" not in staged:
            failures += fail(f"a Failsafe target gained no prepare-agent-integration: {staged}")
    return failures


def case_staging_twice_from_one_commit_gives_one_sha():
    with tempfile.TemporaryDirectory() as one, tempfile.TemporaryDirectory() as two:
        files = {"pom.xml": ROOT_POM, "app/pom.xml": _pom("app")}
        staged, first = _staged(one, files)
        _, second = _staged(two, files)
        failures = 0
        if first != second:
            failures += fail("two stagings of one commit gave two SHAs, so a map captured on one "
                             "run cannot be related to the next run's HEAD")
        if "target/ ignored" not in staged:
            failures += fail("a tree that does not ignore target/ was left to count it by content")
        if maven_build.stage(one, PROJECT):
            failures += fail("staging an already staged tree staged again")
        return failures


def case_staging_re_points_an_existing_declaration_and_refuses_one_it_cannot():
    failures = 0
    demo = os.path.join(run.SOURCE, "samples", "demo-maven")
    with tempfile.TemporaryDirectory() as tmp:
        shutil.copytree(demo, tmp, dirs_exist_ok=True,
                        ignore=shutil.ignore_patterns("target", ".yoriwake"))
        staged, _ = _staged(tmp, {".gitignore": "target/\n"})
        root = _read(tmp, "pom.xml")
        if root.count("yoriwake-maven-plugin") != 1 or "${yoriwake.harness.version}" not in root:
            failures += fail(f"demo-maven's declaration was not re-pointed in place: {staged}")
        if any("jacoco" in item for item in staged):
            failures += fail("demo-maven, which declares JaCoCo, gained a second agent")
    with tempfile.TemporaryDirectory() as tmp:
        profiled = _pom("root", parent=False, group="dev.demo", body=(
            "  <packaging>pom</packaging>\n  <modules><module>app</module></modules>\n"
            "  <profiles><profile><id>p</id><build><plugins><plugin>"
            "<groupId>io.github.zeuspizza</groupId><artifactId>yoriwake-maven-plugin</artifactId>"
            "<version>0.4.0</version></plugin></plugins></build></profile></profiles>\n"))
        try:
            _staged(tmp, {"pom.xml": profiled, "app/pom.xml": _pom("app")})
            failures += fail("a declaration in a profile was staged beside a second one")
        except SystemExit as refused:
            if "pom.xml" not in str(refused):
                failures += fail(f"the refusal does not name the declaration: {refused}")
    return failures


def case_the_maven_plugin_has_its_own_digest_and_the_selector_digest_does_not_move():
    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        _write(os.path.join(tmp, "core", "src", "main", "Selector.kt"), "// v1\n")
        _write(os.path.join(tmp, "maven", "build.gradle.kts"), "// build\n")
        lifecycle = _write(os.path.join(tmp, "maven", "src", "main", "Lifecycle.kt"), "// v1\n")
        selector, maven = run.selector_digest(tmp), maven_build.digest(tmp)
        _write(lifecycle, "// v2\n")
        if run.selector_digest(tmp) != selector:
            failures += fail("a Maven plugin change aged every Gradle file's selector_digest")
        if maven_build.digest(tmp) == maven:
            failures += fail("a Maven plugin change left maven_digest where it was")
    if not run.maven_digest_changed({"maven_digest": "old"}, "new"):
        failures += fail("a Maven file measured by another Maven plugin was resumed")
    if run.maven_digest_changed({"maven_digest": "same"}, "same"):
        failures += fail("a Maven file measured by this Maven plugin was not resumed")
    if run.maven_digest_changed({}, None):
        failures += fail("a Gradle file was aged by a digest it never carries")
    return failures


def case_a_maven_capture_without_a_map_refuses_a_recording_run_naming_the_execution():
    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        seams = _seams(tmp)
        log = _log(tmp, "[WARNING] [yoriwake] app surefire:test (default-test): could not decode\n")
        refusal = seams.record_without_map("accepted.json", None, log) or ""
        if NAME not in refusal or "could not decode" not in refusal:
            failures += fail(f"a recording run without a map was not refused naming {NAME}: {refusal!r}")
        _write(os.path.join(maven_build.map_dir_of(tmp, KEY), "raw", "worker-1", "r.exec"), "")
        if "never merged" not in (seams.record_without_map("accepted.json", None, log) or ""):
            failures += fail("an unmerged Maven capture was not named in the refusal")
        if seams.record_without_map(None, None, log) is not None:
            failures += fail("a run that records no list was refused for a missing map")
        if seams.record_without_map("accepted.json", "/some/map", log) is not None:
            failures += fail("a recording run with a map was refused")
    return failures


def case_a_maven_arm_runs_on_the_projects_jdk_or_refuses():
    failures = 0
    project = {**PROJECT, "daemon_java_version": 17, "daemon_jvmargs": "-Xmx2g"}
    try:
        maven_build.environment(project, lambda version: None, base={})
        failures += fail("a Maven arm without the project's JDK ran on another")
    except SystemExit:
        pass
    env = maven_build.environment(project, lambda version: f"/jdk{version}", base={})
    if env.get("JAVA_HOME") != "/jdk17" or env.get("MAVEN_OPTS") != "-Xmx2g":
        failures += fail(f"the project's JDK and heap did not reach Maven: {env}")
    if maven_build.environment(PROJECT, lambda version: None, base={"JAVA_HOME": "/x"}) \
            != {"JAVA_HOME": "/x"}:
        failures += fail("a project naming no JDK changed the environment")
    return failures


def case_a_resource_a_mutation_added_is_removed_after_the_restore_in_a_later_invocation():
    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        _write(os.path.join(tmp, "pom.xml"), _pom("root", parent=False, group="dev.demo"))
        added = _write(os.path.join(tmp, "src", "test", "resources", "added.txt"), "mutation")
        maven_build.remove_stale_resources(tmp)
        copy = _write(os.path.join(tmp, "target", "test-classes", "added.txt"), "copied")
        os.remove(added)
        # A later invocation knows only what was kept beside the maps.
        if maven_build.remove_stale_resources(tmp) != ["target/test-classes/added.txt"] \
                or os.path.exists(copy):
            failures += fail("a resource a mutation added survived its restore into a later build")
    return failures


def case_staging_changes_no_line_it_did_not_edit():
    failures = 0
    pom = ('<?xml version="1.0" encoding="UTF-8"?>\n<!-- licence -->\n<project xmlns="http://maven.apache.org/'
           'POM/4.0.0">\n  <modelVersion>4.0.0</modelVersion>\n  <parent>\n    <groupId>dev.demo</groupId>\n'
           '    <artifactId>root</artifactId>\n    <version>1.0</version>\n    <relativePath/>\n  </parent>\n'
           '  <artifactId>app</artifactId>\n  <description>a &amp; b</description>\n</project>\n')
    with tempfile.TemporaryDirectory() as tmp:
        _staged(tmp, {"pom.xml": ROOT_POM, "app/pom.xml": pom, ".gitignore": "target/\n"})
        staged = _read(tmp, "app/pom.xml")
        added = staged.split("\n")
        for line in pom.split("\n")[:-2]:
            if line not in added:
                failures += fail(f"staging rewrote lines it did not edit: {line!r} is gone")
        if "<skipTests>false</skipTests>" not in staged:
            failures += fail("the measured execution was not staged")
    return failures


CASES = [
    case_staging_changes_no_line_it_did_not_edit,
    case_a_maven_capture_without_a_map_refuses_a_recording_run_naming_the_execution,
    case_a_maven_arm_runs_on_the_projects_jdk_or_refuses,
    case_a_resource_a_mutation_added_is_removed_after_the_restore_in_a_later_invocation,
    case_the_maven_plugin_has_its_own_digest_and_the_selector_digest_does_not_move,
    case_fresh_surefire_xml_reads_as_ran_and_failed_and_stale_xml_as_nothing,
    case_a_failsafe_project_reads_failsafe_reports_and_never_surefire_reports,
    case_the_map_is_the_measured_executions_and_no_other,
    case_the_plugins_reason_is_read_through_mavens_prefix_and_capture_openers_are_skipped,
    case_a_declined_measured_execution_is_a_refusal_with_its_token,
    case_an_execution_ran_only_when_its_goal_started_and_did_not_skip,
    case_an_upstream_test_execution_that_ran_refuses_the_row,
    case_a_compiler_diagnostic_is_a_rejection_and_a_resolution_failure_is_not,
    case_a_transfer_failure_reruns_once_and_a_missing_artifact_does_not,
    case_a_maven_arm_translates_the_gradle_arm_and_refuses_what_it_cannot,
    case_a_resource_renamed_away_leaves_no_copy_and_a_tests_own_file_stays,
    case_a_gradle_project_takes_the_gradle_seam_with_its_arguments_unchanged,
    case_a_maven_run_refuses_the_arms_it_has_no_meaning_for,
    case_staging_adds_jacoco_only_where_no_pom_declares_its_agent,
    case_staging_twice_from_one_commit_gives_one_sha,
    case_staging_re_points_an_existing_declaration_and_refuses_one_it_cannot,
]


def test_maven_build():
    assert main() == 0, "maven_build checks failed; run this file directly"


def main():
    failures = 0
    for case in CASES:
        print(case.__name__)
        failures += case()
    print(f"\n{'FAILED' if failures else 'all passed'}: {failures} failed checks")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
