"""
The Maven side of the harness's build seam.

`run.py` drives a guest through about fifteen seams named after Gradle: an arm's invocation, where
its JUnit XML lands, where its map lives, how its log says the tests ran or the build failed, why
selection declined to narrow. A project file with `build = "maven"` takes this module's answer at
each of them instead, and every row rule in `run.main` stays shared, so a recall rule fixed once is
fixed for both build tools.

The arms keep speaking Gradle: `run.py` hands over the same `task_args` it hands Gradle, and
`invocation` translates them, refusing any it has no Maven meaning for rather than dropping it.

What a Maven arm is (measured on `samples/demo-maven` with Maven 3.9.16):

  * `mvn -pl <module> -am <phase> -DskipTests`, the measured execution staged with an explicit
    `<skipTests>false</skipTests>` (`stage`), so the upstream modules are built but their tests
    are skipped, and only the measured execution runs. The plugin declines a module built against
    a reactor module the invocation did not build, which is why `-am` is needed at all.
  * The plugin joins through the POM, so a staging commit wires it (`stage`); an arm without it
    passes `-Dyoriwake.enabled=false`.
  * Maps live under `.yoriwake/yoriwake/<sanitized execution key>`, results under
    `<module>/target/<surefire|failsafe>-reports`, and the plugin's lines come through Maven's
    logger as `[INFO] [yoriwake] <artifactId> surefire:test (default-test): <reason>`.
"""

import difflib
import glob
import hashlib
import os
import re
import shutil
import subprocess
import tarfile
import tempfile
import tomllib
import urllib.request
import xml.etree.ElementTree as ET

# The project file's key, and the value that selects this module.
BUILD_KEY = "build"
MAVEN = "maven"
GRADLE = "gradle"

# The Maven the harness brings when the guest has no wrapper, pinned as the functional suite pins
# it (maven/src/functionalTest/.../FunctionalMaven.kt), and unpacked where that suite unpacks it.
MAVEN_VERSION = "3.9.16"
MAVEN_SHA512 = ("831a8591fe20c8243b1dbe7d71e3244f31d1665b0804b2e825e38cbbe5ce0cafb8338851f907807"
                "35568773e0a6cd07bbec107cda0b896b008b861075358b6f6")

# Must match the Maven plugin: `Recording.MAPS_HOME`, `AgentContract.MAP_ROOT_DIR`. Pinned by
# test_module_contract.py.
MAPS_HOME = ".yoriwake"
MAP_ROOT_DIR = "yoriwake"
PLUGIN_GROUP = "io.github.zeuspizza"
PLUGIN_ARTIFACT = "yoriwake-maven-plugin"
# The property the staged POM reads the plugin's version from; every arm sets it.
VERSION_PROPERTY = "yoriwake.harness.version"
# The plugin's off switch (`Settings.ENABLED`).
ENABLED_PROPERTY = "yoriwake.enabled"

# The two test plugins: which goal runs the tests, the default execution id, the reports directory,
# the phase an arm builds to, the JaCoCo goal whose agent reaches their JVMs, and the property
# that filters one class.
TEST_PLUGINS = {
    "surefire": {"artifact": "maven-surefire-plugin", "goal": "test", "execution": "default-test",
                 "reports": "surefire-reports", "phase": "test", "agent_goal": "prepare-agent",
                 "filter": "test", "no_match": "surefire.failIfNoSpecifiedTests"},
    "failsafe": {"artifact": "maven-failsafe-plugin", "goal": "integration-test",
                 "execution": "default-integration-test", "reports": "failsafe-reports",
                 "phase": "verify", "agent_goal": "prepare-agent-integration",
                 "filter": "it.test", "no_match": "failsafe.failIfNoSpecifiedTests"},
}

# Every arm turns the guest's own selection and caches off, as `guest-selection-off.init.gradle`
# does for Gradle: Develocity's Predictive Test Selection (which the plugin declines on), its build
# cache, local and remote, under both the Develocity and the older Gradle Enterprise names, and
# build scans; and the Apache build cache extension, which declines every execution while it is on.
GUEST_OFF = ["-Dpts.enabled=false",
             "-Ddevelocity.cache.local.enabled=false", "-Ddevelocity.cache.remote.enabled=false",
             "-Ddevelocity.scan.disabled=true",
             "-Dgradle.cache.local.enabled=false", "-Dgradle.cache.remote.enabled=false",
             "-Dscan=false",
             "-Dmaven.build.cache.enabled=false"]

# Gradle flags an arm passes that mean nothing to Maven: Maven reruns every goal it is asked for
# and has no configuration cache.
GRADLE_ONLY_NOOPS = ("--rerun-tasks", "--no-configuration-cache")
# Plugin switches the arms pass with Gradle's `-P`, which Maven reads as user properties.
PLUGIN_SWITCHES = ("-Pyoriwake.select", "-Pyoriwake.internal.loaded")

# The id of the JaCoCo execution staging adds where the guest has none. An arm with the plugin off
# skips it, so the uninstrumented arms run the guest's build and nothing of ours.
STAGED_JACOCO_EXECUTION = "yoriwake-harness-prepare-agent"
JACOCO_VERSION = "0.8.12"
STAGING_SUBJECT = "yoriwake-harness: wire the plugin under test into the POM"


def is_maven(project):
    return project.get(BUILD_KEY, GRADLE) == MAVEN


def any_maven(paths):
    """Whether any of these project files is a Maven project."""
    for path in paths:
        with open(path, "rb") as handle:
            if is_maven(tomllib.load(handle)):
                return True
    return False


def build_tool(project):
    """`gradle` or `maven`; a project file without the key is a Gradle one."""
    tool = project.get(BUILD_KEY, GRADLE)
    if tool not in (GRADLE, MAVEN):
        raise SystemExit(f"{project.get('name')}: build must be {GRADLE!r} or {MAVEN!r}, not {tool!r}")
    return tool


class Execution:
    """The measured Surefire or Failsafe execution a project file names (`test_plugin`,
    `execution`), with what derives from it: its reports directory, the phase an arm builds to and
    the class filter. Its map key also needs the module's coordinates; see `coordinates`."""

    def __init__(self, project):
        plugin = project.get("test_plugin") or "surefire"
        if plugin not in TEST_PLUGINS:
            raise SystemExit(f"{project.get('name')}: test_plugin must be one of "
                             f"{sorted(TEST_PLUGINS)}, not {plugin!r}")
        facts = TEST_PLUGINS[plugin]
        self.plugin = plugin
        self.artifact = facts["artifact"]
        self.goal = facts["goal"]
        self.id = project.get("execution") or facts["execution"]
        self.reports = facts["reports"]
        self.phase = facts["phase"]
        self.agent_goal = facts["agent_goal"]
        self.filter = facts["filter"]
        self.no_match = facts["no_match"]
        self.module = project.get("module") or ""

    def name(self, artifact_id):
        """How the plugin's console lines name it (`Recording.nameOf`)."""
        return f"{artifact_id} {self.plugin}:{self.goal} ({self.id})"

    def key(self, group_id, artifact_id):
        """Its map's key (`Recording.keyOf`)."""
        return f"{group_id}:{artifact_id}:{self.artifact}:{self.id}"


def _local(tag):
    return tag.rsplit("}", 1)[-1]


def _child(element, name):
    for child in element:
        if isinstance(child.tag, str) and _local(child.tag) == name:
            return child
    return None


def coordinates(checkout, module):
    """`(groupId, artifactId)` of the module's POM, the groupId inherited from its parent when the
    POM names none. Read from the file: a project file states only the module's directory."""
    path = os.path.join(checkout, module, "pom.xml") if module else os.path.join(checkout, "pom.xml")
    try:
        root = ET.parse(path).getroot()
    except (OSError, ET.ParseError) as failed:
        raise SystemExit(f"cannot read the measured module's POM {path}: {failed}")
    artifact = _child(root, "artifactId")
    group = _child(root, "groupId")
    parent = _child(root, "parent")
    if group is None and parent is not None:
        group = _child(parent, "groupId")
    if artifact is None or group is None or not (artifact.text or "").strip() \
            or not (group.text or "").strip():
        raise SystemExit(f"{path} names no artifactId or groupId, so the measured execution's map "
                         f"cannot be named")
    return group.text.strip(), artifact.text.strip()


def sanitize(key):
    """The map directory's name for a key, as `MapLocation.sanitize` makes it."""
    readable = re.sub(r"[^A-Za-z0-9._-]", "_", key.strip(":").replace(":", "-")) or "root"
    return f"{readable}-{hashlib.sha256(key.encode('utf-8')).digest()[:4].hex()}"


def maps_root(checkout):
    return os.path.join(checkout, MAPS_HOME, MAP_ROOT_DIR)


def map_dir_of(checkout, key):
    """The measured execution's map directory, whether or not it exists."""
    return os.path.join(maps_root(checkout), sanitize(key))


def find_map(checkout, key):
    """The measured execution's merged map, or None. Named by the exact key, so another module's
    or another execution's map is never handed over."""
    directory = map_dir_of(checkout, key)
    return directory if os.path.isfile(os.path.join(directory, "coverage.tsv")) else None


def map_dirs(checkout, key):
    """Its map directory, merged or not, as a list (empty when there is none)."""
    directory = map_dir_of(checkout, key)
    return [directory] if os.path.isdir(directory) else []


def unfinished_map(checkout, key):
    """Its map directory when a capture wrote raw records and never merged them, else None."""
    directory = map_dir_of(checkout, key)
    if os.path.isfile(os.path.join(directory, "coverage.tsv")):
        return None
    return directory if os.path.isdir(os.path.join(directory, "raw")) else None


def reports_dir(checkout, module, reports):
    return os.path.join(*([checkout] + ([module] if module else []) + ["target", reports]))


def read_results(checkout, module, since, reports):
    """`(ran, failed)` on XML keys from this module's reports directory, written at or after
    `since`; `(None, None)` when no fresh report exists. Surefire and Failsafe write the JUnit XML
    Gradle writes, so the keys are the same `(class, name)` pairs."""
    pattern = os.path.join(reports_dir(checkout, module, reports), "TEST-*.xml")
    fresh = [p for p in glob.glob(pattern) if os.path.getmtime(p) >= since]
    if not fresh:
        return None, None
    ran, failed = set(), set()
    for path in fresh:
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        for case in root.iter("testcase"):
            key = (case.get("classname"), (case.get("name") or "").removesuffix("()"))
            ran.add(key)
            if case.find("failure") is not None or case.find("error") is not None:
                failed.add(key)
    return ran, failed


# The line Maven prints when a goal starts, in 3.9's short form and in the long one older Maven and
# some guests print: `[INFO] --- surefire:3.5.6:test (default-test) @ lib ---`.
GOAL_LINE = re.compile(r"^\[INFO\] --- (?:maven-)?(surefire|failsafe)(?:-plugin)?:[^:\s]+:"
                       r"(test|integration-test) \(([^)]+)\) @ (\S+) ---")
NEXT_STEP = re.compile(r"^\[INFO\] (?:---|-{20,}|BUILD |Reactor Summary)")
SKIPPED = ("Tests are skipped.", "Skipping execution of surefire because it has already been run")


def read_log(log_path):
    try:
        with open(log_path, encoding="utf-8", errors="replace") as handle:
            return handle.read()
    except OSError:
        return None


def test_runs(log_path):
    """Every Surefire or Failsafe test goal the log shows starting, as
    `(artifactId, plugin, execution id, ran)`; `ran` is False when the goal said it skipped."""
    log = read_log(log_path)
    if log is None:
        return []
    found = []
    current = None
    for line in log.splitlines():
        started = GOAL_LINE.match(line)
        if started:
            if current:
                found.append(tuple(current))
            plugin, _goal, execution, artifact = started.groups()
            current = [artifact, plugin, execution, True]
            continue
        if current is None:
            continue
        if NEXT_STEP.match(line):
            found.append(tuple(current))
            current = None
        elif any(marker in line for marker in SKIPPED):
            current[3] = False
    if current:
        found.append(tuple(current))
    return found


def execution_ran(log_path, artifact_id, execution):
    """Whether the measured execution ran, however few tests it then ran. Skipped, or never reached
    because the build failed before it, is not running."""
    return any(run_ and (artifact, plugin, ident) == (artifact_id, execution.plugin, execution.id)
               for artifact, plugin, ident, run_ in test_runs(log_path))


def other_tests_ran(log_path, artifact_id, execution):
    """The test executions other than the measured one that ran rather than skipped, by name. One
    that binds its own skip property ignores `-DskipTests`, and its XML could share a reports
    directory and its time would be in the arm's."""
    return sorted({f"{artifact} {plugin} ({ident})"
                   for artifact, plugin, ident, run_ in test_runs(log_path)
                   if run_ and (artifact, plugin, ident) != (artifact_id, execution.plugin,
                                                              execution.id)})


def build_failed(log_path):
    """Whether the build did not finish green: no `BUILD SUCCESS`, so a Maven killed or crashed
    without its failure banner is a failed build too, never an execution that merely did not run."""
    log = read_log(log_path)
    return log is None or "[INFO] BUILD SUCCESS" not in log


def plugin_lines(log_path):
    """The plugin's `[yoriwake] ...` lines, with Maven's level prefix taken off."""
    log = read_log(log_path)
    if log is None:
        return []
    lines = []
    for line in log.splitlines():
        head, sep, rest = line.partition("[yoriwake] ")
        if sep and head.strip() in ("", "[INFO]", "[WARNING]", "[ERROR]", "[DEBUG]"):
            lines.append(sep + rest)
    return lines


# The plugin's below-floor line (`YoriwakeLifecycle`): the whole build is declined.
MAVEN_VERSION_TOKEN = "maven-version"


def declined(log_path, name):
    """The token the plugin declined the named execution with, or `maven-version` when it declined
    the whole build; None when it declined neither."""
    pattern = re.compile(r"^\[yoriwake\] " + re.escape(name) + r" declined, ([a-z0-9-]+): ")
    for line in plugin_lines(log_path):
        if line.startswith(f"[yoriwake] {MAVEN_VERSION_TOKEN}: "):
            return MAVEN_VERSION_TOKEN
        found = pattern.match(line)
        if found:
            return found.group(1)
    return None


def attached(log_path):
    """Whether the plugin said it attached to this Maven (`[yoriwake] attached to Maven <v>`)."""
    return any(line.startswith("[yoriwake] attached to Maven ") for line in plugin_lines(log_path))


def full_run_reason(log_path, name, skip_openers):
    """The plugin's own reason for the named execution, as `run.full_run_reason` reads Gradle's;
    capture-decision lines (`skip_openers`) are passed over."""
    marker = f"[yoriwake] {name}: "
    for line in plugin_lines(log_path):
        if not line.startswith(marker):
            continue
        reason = line[len(marker):].strip()
        if reason.startswith(skip_openers):
            continue
        return reason[:300]
    return None


def lines_about(log_path, name):
    """Everything the plugin said about the named execution, and the build-wide declines."""
    return [line for line in plugin_lines(log_path)
            if line.startswith((f"[yoriwake] {name}", f"[yoriwake] {MAVEN_VERSION_TOKEN}: "))]


def compiler_rejected(log_path):
    """Whether a compile failed on a compiler diagnostic, javac's (`COMPILATION ERROR`) or
    kotlinc's (`[ERROR] e: `), as opposed to any other failure."""
    log = read_log(log_path)
    if log is None:
        return False
    return "COMPILATION ERROR" in log or re.search(r"^\[ERROR\] e: ", log, re.M) is not None


# A download that failed in transit, in Maven's words, and the answers that are not transient.
TRANSFER_FAILED = re.compile(r"Could not transfer artifact|Failed to read artifact descriptor|"
                             r"Could not transfer metadata")
NOT_TRANSIENT_MAVEN = re.compile(r"status code: (?:401|403|404)\b|\b(?:401|403|404) "
                                 r"(?:Unauthorized|Forbidden|Not Found)|Could not find artifact|"
                                 r"was cached in the local repository")
OTHER_FAILURE = re.compile(r"COMPILATION ERROR|There are test failures|Tests run: \d+, "
                           r"Failures: [1-9]|Tests run: \d+, Failures: \d+, Errors: [1-9]")


def download_failure(log_path):
    """The line naming the download a failed build could not make, or None when the build also
    failed for another reason, or the failure was a missing or refused artifact."""
    log = read_log(log_path)
    if log is None or "BUILD FAILURE" not in log:
        return None
    errors = [line for line in log.splitlines() if line.startswith("[ERROR]")]
    transfers = [line for line in errors if TRANSFER_FAILED.search(line)]
    if not transfers or any(NOT_TRANSIENT_MAVEN.search(line) for line in errors) \
            or OTHER_FAILURE.search(log):
        return None
    return transfers[-1].removeprefix("[ERROR]").strip()[:300]


def plugin_version(source_root):
    """The version `:maven:publishToMavenLocal` installs: the build's own default."""
    build = os.path.join(source_root, "maven", "build.gradle.kts")
    with open(build, encoding="utf-8") as handle:
        found = re.search(r'^version = findProperty\("releaseVersion"\) \?: "([^"]+)"$',
                          handle.read(), re.MULTILINE)
    if found is None:
        raise SystemExit(f"could not read the Maven plugin's snapshot version from {build}, so "
                         f"which plugin the staged POM would resolve cannot be established.")
    return found.group(1)


def distributions_dir():
    """Where Maven distributions are unpacked, shared with the functional suite."""
    home = os.environ.get("GRADLE_USER_HOME") or os.path.join(os.path.expanduser("~"), ".gradle")
    return os.path.join(home, "yoriwake-maven-distributions")


def distribution(version=MAVEN_VERSION, expected=MAVEN_SHA512):
    """The unpacked pinned Maven, downloaded and checked against its SHA-512 on first use, then
    moved into place whole, so a cut-short download is never reused."""
    root = distributions_dir()
    home = os.path.join(root, f"apache-maven-{version}")
    if os.path.isdir(os.path.join(home, "bin")):
        return home
    os.makedirs(root, exist_ok=True)
    staging = tempfile.mkdtemp(prefix="download-", dir=root)
    try:
        name = f"apache-maven-{version}-bin.tar.gz"
        url = f"https://archive.apache.org/dist/maven/maven-3/{version}/binaries/{name}"
        archive = os.path.join(staging, name)
        with urllib.request.urlopen(url, timeout=60) as response, open(archive, "wb") as out:
            shutil.copyfileobj(response, out)
        with open(archive, "rb") as handle:
            actual = hashlib.sha512(handle.read()).hexdigest()
        if actual != expected:
            raise SystemExit(f"Maven {version}'s archive has SHA-512 {actual}, not the published "
                             f"{expected}; refusing to build with it")
        with tarfile.open(archive) as packed:
            packed.extractall(staging, filter="data")
        try:
            os.rename(os.path.join(staging, f"apache-maven-{version}"), home)
        except OSError:
            if not os.path.isdir(os.path.join(home, "bin")):
                raise
    finally:
        shutil.rmtree(staging, ignore_errors=True)
    return home


def executable(checkout):
    """The guest's own wrapper when it has one, its chosen Maven; otherwise the pinned one."""
    windows = os.name == "nt"
    wrapper = os.path.join(checkout, "mvnw.cmd" if windows else "mvnw")
    if os.path.isfile(wrapper):
        return wrapper
    return os.path.join(distribution(), "bin", "mvn.cmd" if windows else "mvn")


def maven_version(command, env=None):
    """`Apache Maven X.Y.Z` from `mvn --version`, as `X.Y.Z`, or None."""
    try:
        done = subprocess.run([command, "--version"], capture_output=True, text=True, timeout=300,
                              env=env)
    except (OSError, subprocess.SubprocessError):
        return None
    found = re.search(r"Apache Maven (\S+)", done.stdout)
    return found.group(1) if found else None


class Refused(Exception):
    """An arm argument with no Maven meaning; the message names it."""


def invocation(project, task_args, *, staged_jacoco, version):
    """The Maven arguments for an arm `run.py` describes with Gradle's `task_args`.

    The task path names what to run: the test task runs the measured execution
    (`-pl <module> -am <phase>`), the classes task compiles (`test-compile`), and a `--dry-run` of
    it only validates the reactor. The init script on the command line means the plugin is on;
    without it the arm passes `-Dyoriwake.enabled=false`, and skips the JaCoCo execution staging
    added when there is one, so an uninstrumented arm runs the guest's own build. `--tests` filters
    one class through the test plugin's own property. Anything else raises `Refused`.
    """
    execution = Execution(project)
    module = execution.module
    test_task = project.get("test_task") or "test"
    classes_task = project.get("test_classes_task") or f"{test_task}Classes"
    goal, plugin_on, dry_run, filters, switches = None, False, False, [], []
    args = list(task_args)
    position = 0
    while position < len(args):
        arg = args[position]
        position += 1
        if arg == "-I":
            # An init script: the plugin's own (`INIT_SCRIPT_ARGS`) switches the plugin on.
            script = args[position] if position < len(args) else ""
            position += 1
            if not script.endswith("yoriwake.init.gradle.kts"):
                raise Refused(f"the init script {os.path.basename(script)} has no Maven meaning")
            plugin_on = True
        elif arg.startswith("-Dyoriwake.initScriptVersion="):
            continue
        elif arg in GRADLE_ONLY_NOOPS:
            continue
        elif arg == "--dry-run":
            dry_run = True
        elif arg == "--tests":
            filters.append(args[position])
            position += 1
        elif arg in PLUGIN_SWITCHES:
            switches.append("-D" + arg[2:])
        elif arg.startswith("-Pyoriwake.base="):
            switches.append("-D" + arg[2:])
        elif not arg.startswith("-"):
            task = arg.rsplit(":", 1)[-1]
            named = arg.lstrip(":").rsplit(":", 1)[0] if ":" in arg.lstrip(":") else ""
            if named != module or task not in (test_task, classes_task):
                raise Refused(f"the task {arg} is not the measured module's {test_task} or "
                              f"{classes_task}")
            goal = execution.phase if task == test_task else "test-compile"
        else:
            raise Refused(f"the flag {arg} has no Maven meaning here")
    if goal is None:
        raise Refused(f"no task to run in {task_args}")
    if dry_run:
        goal = "validate"
    command = ["-B", "--no-transfer-progress", "-Dstyle.color=never"]
    command += ["-pl", module, "-am"] if module else []
    command += [goal, "-DskipTests", f"-D{VERSION_PROPERTY}={version}"] + GUEST_OFF
    if plugin_on:
        command += switches
    else:
        if switches:
            raise Refused(f"{switches} without the plugin")
        command.append(f"-D{ENABLED_PROPERTY}=false")
        if staged_jacoco:
            command.append("-Djacoco.skip=true")
    for name in filters:
        command += [f"-D{execution.filter}={name}", f"-D{execution.no_match}=false"]
    return command


def environment(project, find_jdk, base=None):
    """The environment an arm's Maven runs in: the project's JDK (`daemon_java_version`) as
    JAVA_HOME and its heap (`daemon_jvmargs`) as MAVEN_OPTS, the two settings the Gradle daemon
    takes as properties."""
    env = dict(os.environ if base is None else base)
    version = project.get("daemon_java_version")
    if version:
        home = find_jdk(int(version))
        if home is None:
            raise SystemExit(f"{project['name']} needs JDK {version} for Maven and none was found; "
                             f"refusing to run on the wrong JVM")
        env["JAVA_HOME"] = home
    if project.get("daemon_jvmargs"):
        env["MAVEN_OPTS"] = project["daemon_jvmargs"]
    return env


def fork_cap_flags(cap):
    """`-DforkCount=<n>` under a project's `max_parallel_forks`; nothing without one."""
    return [] if cap is None else [f"-DforkCount={cap}"]


def module_dirs(checkout):
    """Every directory of the checkout holding a `pom.xml`, `.git` and `target` left out."""
    found = []
    for root, directories, files in os.walk(checkout):
        directories[:] = [d for d in directories if d not in (".git", "target", MAPS_HOME)]
        if "pom.xml" in files:
            found.append(root)
    return found


# Where a resource is copied: `src/main/resources/X` to `target/classes/X`, and the tests'.
RESOURCE_OUTPUTS = (("src/main/resources/", "target/classes/"),
                    ("src/test/resources/", "target/test-classes/"))
# The resources the harness has seen in a checkout, kept beside the maps so a later invocation
# still knows a resource an earlier one's mutation added.
SEEN_RESOURCES = os.path.join(MAPS_HOME, "harness-resources")


def resources(checkout):
    """Every file under a module's conventional resource directories, as checkout-relative paths."""
    found = set()
    for module in module_dirs(checkout):
        for source, _ in RESOURCE_OUTPUTS:
            base = os.path.join(module, *source.split("/"))
            for root, _dirs, files in os.walk(base):
                found.update(os.path.relpath(os.path.join(root, name), checkout).replace(os.sep, "/")
                             for name in files)
    return found


def output_of(resource):
    """Where Maven copies a resource path, or None for a path outside the resource directories."""
    for source, target in RESOURCE_OUTPUTS:
        head, sep, tail = resource.partition(source)
        if sep and (not head or head.endswith("/")):
            return head + target + tail
    return None


def remove_stale_resources(checkout):
    """Deletes the copies of resources the tree no longer has, before a build. Maven copies
    resources without deleting stale ones, so a mutation that renamed or deleted one would leave
    the old copy on the test classpath of every later arm, restored rows included. Only those
    copies go: compiled classes are the compiler's to keep current, and a file a test wrote into
    `target/` is part of what the map recorded. Returns the copies removed."""
    current = resources(checkout)
    seen_path = os.path.join(checkout, SEEN_RESOURCES)
    try:
        with open(seen_path, encoding="utf-8") as handle:
            seen = {line.rstrip("\n") for line in handle if line.strip()}
    except OSError:
        seen = set()
    removed = []
    for resource in sorted(seen - current):
        output = output_of(resource)
        path = os.path.join(checkout, *output.split("/")) if output else None
        if path and os.path.isfile(path):
            os.remove(path)
            removed.append(output)
    if not current <= seen:
        os.makedirs(os.path.dirname(seen_path), exist_ok=True)
        with open(seen_path, "w", encoding="utf-8") as handle:
            handle.write("".join(f"{path}\n" for path in sorted(seen | current)))
    return removed


# Directories that are Maven's or the plugin's state, never a guest's own untracked file.
def is_build_state(directories, is_maven_module):
    """Whether a path under these directories is build state: anything under `.yoriwake/`, and
    anything under a `target/` beside a `pom.xml`."""
    if MAPS_HOME in directories:
        return True
    return any(d == "target" and is_maven_module("/".join(directories[:i]))
               for i, d in enumerate(directories))


# --- Staging -----------------------------------------------------------------------------------


class _Pom:
    """One POM, parsed with its comments, edited in place and written back. Elements are named in
    the POM's own namespace, which a POM may also leave out."""

    def __init__(self, path):
        self.path = path
        with open(path, encoding="utf-8") as handle:
            self.original = handle.read()
        root_tag = None
        for _event, element in ET.iterparse(path, events=("start",)):
            root_tag = element.tag
            break
        self.ns = root_tag[1:].split("}", 1)[0] if root_tag and root_tag.startswith("{") else ""
        ET.register_namespace("", self.ns)
        self.tree = ET.parse(path, ET.XMLParser(target=ET.TreeBuilder(insert_comments=True)))
        self.root = self.tree.getroot()

    def q(self, name):
        return f"{{{self.ns}}}{name}" if self.ns else name

    def element(self, name, text=None, children=()):
        element = ET.Element(self.q(name))
        element.text = text
        element.extend(children)
        return element

    def text(self, element, name):
        child = element.find(self.q(name)) if element is not None else None
        return (child.text or "").strip() if child is not None else None

    def ensure(self, parent, name, depth):
        found = parent.find(self.q(name))
        return found if found is not None else _append(parent, self.element(name), depth)

    def plugins(self, build):
        plugins = build.find(self.q("plugins")) if build is not None else None
        return list(plugins) if plugins is not None else []

    def is_plugin(self, plugin, artifact, group="org.apache.maven.plugins"):
        return self.text(plugin, "artifactId") == artifact and \
            (self.text(plugin, "groupId") or "org.apache.maven.plugins") == group

    def agent_goals(self):
        """The JaCoCo goals this POM declares an execution of, anywhere in it."""
        goals = set()
        for plugin in self.root.iter(self.q("plugin")):
            if self.text(plugin, "artifactId") != "jacoco-maven-plugin":
                continue
            for listed in plugin.iter(self.q("goal")):
                goals.add((listed.text or "").strip())
        return goals

    def serialized(self, tree):
        return ET.tostring(tree.getroot(), encoding="unicode")

    def write(self):
        """Writes the edits and nothing else: every line the edits did not touch keeps the
        original's bytes. A serializer's own spelling (quotes, `<a />`) on untouched lines would be
        a change too, and a guest that checks its POM's format (Spotless) fails the build on it."""
        pristine = ET.parse(self.path, ET.XMLParser(target=ET.TreeBuilder(insert_comments=True)))
        before = self.serialized(pristine).split("\n")
        after = self.serialized(self.tree).split("\n")
        body = self.original[self.original.find("<project"):] if "<project" in self.original \
            else self.original
        head = self.original[:len(self.original) - len(body)]
        lines = body.split("\n")
        if len(lines) < len(before):
            raise SystemExit(f"cannot stage {self.path} without reformatting it; refusing")
        written = []
        matcher = difflib.SequenceMatcher(a=before, b=after, autojunk=False)
        for tag, i1, i2, j1, j2 in matcher.get_opcodes():
            written += lines[i1:i2] if tag == "equal" else after[j1:j2]
        written += lines[len(before):]
        with open(self.path, "w", encoding="utf-8") as handle:
            handle.write(head + "\n".join(written))


def _append(parent, child, depth):
    """Appends `child` under `parent`, indented `depth` steps of two spaces, and indents what a
    freshly built child holds the same way."""
    pad = "\n" + "  " * depth
    if len(parent):
        last = parent[-1]
        child.tail = last.tail
        last.tail = pad
    else:
        parent.text = pad
        child.tail = "\n" + "  " * (depth - 1)
    _indent(child, depth)
    parent.append(child)
    return child


def _indent(element, depth):
    if len(element) and not (element.text or "").strip():
        element.text = "\n" + "  " * (depth + 1)
        for child in element:
            _indent(child, depth + 1)
            child.tail = "\n" + "  " * (depth + 1)
        element[-1].tail = "\n" + "  " * depth


def _poms(checkout):
    """Every POM of the checkout, parsed; one that does not parse is left out."""
    poms = []
    for module in module_dirs(checkout):
        try:
            poms.append(_Pom(os.path.join(module, "pom.xml")))
        except (OSError, ET.ParseError):
            continue
    return poms


def stage(checkout, project):
    """Wires the plugin under test, JaCoCo and the measured execution's `skipTests` into the POMs
    and COMMITS the change, dated as the pinned commit so its SHA is the same on every run (as
    `run.stage_checkout` commits its removals, and for the same reason: an uncommitted POM edit is
    in every row's change set and forces it). Returns what was staged, as sorted short strings.

    The root POM's own `build/plugins` declaration of the plugin is re-pointed to the harness's
    version; one anywhere else (a module, a profile, plugin management only) refuses, naming it,
    rather than adding a second. A JaCoCo agent goal is added only where no POM declares one.
    `target/` is added to `.gitignore` when git does not already ignore it: the plugin counts an
    untracked file by content, so the arms' own output would force every row.
    """
    execution = Execution(project)
    staged = []
    poms = _poms(checkout)
    root_path = os.path.join(checkout, "pom.xml")
    pom = _Pom(root_path)
    build = pom.root.find(pom.q("build"))
    ours = [p for p in pom.plugins(build) if pom.is_plugin(p, PLUGIN_ARTIFACT, PLUGIN_GROUP)]
    wanted = "${" + VERSION_PROPERTY + "}"
    if ours:
        version = ours[0].find(pom.q("version"))
        if version is None:
            version = _append(ours[0], pom.element("version"), 4)
        if (version.text or "").strip() != wanted:
            version.text = wanted
            staged.append("plugin version")
    else:
        declared = [os.path.relpath(other.path, checkout) for other in poms
                    if any(text.text and text.text.strip() == PLUGIN_ARTIFACT
                           for text in other.root.iter(other.q("artifactId")))]
        if declared:
            raise SystemExit(f"{project.get('name')}: the yoriwake plugin is declared in "
                             f"{', '.join(declared)} but not in the root POM's build/plugins, so "
                             f"staging cannot re-point it without a second declaration; refusing")
        build = build if build is not None else _append(pom.root, pom.element("build"), 1)
        _append(pom.ensure(build, "plugins", 2), pom.element("plugin", children=[
            pom.element("groupId", PLUGIN_GROUP), pom.element("artifactId", PLUGIN_ARTIFACT),
            pom.element("version", wanted), pom.element("extensions", "true")]), 3)
        staged.append("plugin added")
    if not any(execution.agent_goal in other.agent_goals() for other in poms):
        build = build if build is not None else _append(pom.root, pom.element("build"), 1)
        _append(pom.ensure(build, "plugins", 2), pom.element("plugin", children=[
            pom.element("groupId", "org.jacoco"), pom.element("artifactId", "jacoco-maven-plugin"),
            pom.element("version", JACOCO_VERSION),
            pom.element("executions", children=[pom.element("execution", children=[
                pom.element("id", STAGED_JACOCO_EXECUTION),
                pom.element("goals", children=[pom.element("goal", execution.agent_goal)])])])]), 3)
        staged.append(f"jacoco {execution.agent_goal} added")
    if staged:
        pom.write()

    pom = _Pom(os.path.join(checkout, execution.module, "pom.xml") if execution.module
               else root_path)
    plugins = pom.ensure(pom.ensure(pom.root, "build", 1), "plugins", 2)
    plugin = next((p for p in plugins if pom.is_plugin(p, execution.artifact)), None)
    if plugin is None:
        plugin = _append(plugins, pom.element("plugin", children=[
            pom.element("groupId", "org.apache.maven.plugins"),
            pom.element("artifactId", execution.artifact)]), 3)
    executions = pom.ensure(plugin, "executions", 4)
    entry = next((e for e in executions if pom.text(e, "id") == execution.id), None)
    if entry is None:
        entry = _append(executions, pom.element("execution", children=[
            pom.element("id", execution.id)]), 5)
    skip = pom.ensure(pom.ensure(entry, "configuration", 6), "skipTests", 7)
    if (skip.text or "").strip() != "false":
        skip.text = "false"
        staged.append(f"{execution.id} skipTests=false")
        pom.write()

    probe = "/".join(([execution.module] if execution.module else []) + ["target", "probe"])
    # The repository's own ignore files only: a machine-wide excludes file would make the staged
    # commit differ between machines.
    ignored = subprocess.run(["git", "-C", checkout, "-c", f"core.excludesFile={os.devnull}",
                              "check-ignore", "-q", "--no-index", probe],
                             capture_output=True)
    if ignored.returncode != 0:
        with open(os.path.join(checkout, ".gitignore"), "a", encoding="utf-8") as handle:
            handle.write("\ntarget/\n")
        staged.append("target/ ignored")

    changed = subprocess.run(["git", "-C", checkout, "status", "--porcelain"],
                             capture_output=True, text=True, check=True).stdout.strip()
    if changed:
        subprocess.run(["git", "-C", checkout, "add", "-A"], check=True)
        pinned = subprocess.run(["git", "-C", checkout, "log", "-1", "--format=%cI", "HEAD"],
                                capture_output=True, text=True, check=True).stdout.strip()
        subprocess.run(["git", "-C", checkout, "-c", "user.email=harness@yoriwake",
                        "-c", "user.name=yoriwake", "-c", "commit.gpgsign=false",
                        "commit", "--quiet", "--no-verify", "-m", STAGING_SUBJECT], check=True,
                       env={**os.environ, "GIT_AUTHOR_DATE": pinned, "GIT_COMMITTER_DATE": pinned})
    return sorted(staged)


def staged_jacoco(checkout):
    """Whether the checkout's root POM carries the JaCoCo execution staging adds."""
    try:
        with open(os.path.join(checkout, "pom.xml"), encoding="utf-8") as handle:
            return STAGED_JACOCO_EXECUTION in handle.read()
    except OSError:
        return False


def digest(source_root):
    """A digest of the Maven plugin's own source, `maven/src/main` and its build file: what a
    Maven row was measured with beside `selector_digest`'s shared selector."""
    sha = hashlib.sha256()
    paths = []
    for walked, _dirs, files in os.walk(os.path.join(source_root, "maven", "src", "main")):
        paths.extend(os.path.join(walked, name) for name in files)
    paths.append(os.path.join(source_root, "maven", "build.gradle.kts"))
    hashed = 0
    for path in sorted(paths):
        if not os.path.isfile(path):
            continue
        sha.update(os.path.relpath(path, source_root).replace(os.sep, "/").encode("utf-8"))
        with open(path, "rb") as handle:
            sha.update(handle.read())
        hashed += 1
    if not hashed:
        raise SystemExit(f"no Maven plugin source under {source_root}/maven; refusing to stamp a "
                         f"Maven row with the digest of an empty tree")
    return sha.hexdigest()[:12]
