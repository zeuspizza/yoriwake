"""
What a sweep records beside selection and recall: cost to the user, change shape, environment and
the raw evidence, as listed in docs/sweep-data-dictionary.md.

Every reader here answers None for "could not be asked" and never a zero, the rule every other
reader in this harness follows: a machine without `ps` is not a machine whose tests used no memory.

Nothing here runs inside the guest's test JVM. Memory is sampled from outside with `ps`, task
durations come from a Gradle build-event listener (`task-times.init.gradle`), and the rest is read
from files the plugin and the agent already write.
"""

import os
import platform
import re
import shutil
import subprocess
import tarfile
import threading
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))
TASK_TIMES_INIT = os.path.join(HERE, "task-times.init.gradle")
ISOLATED_FORKS_INIT = os.path.join(HERE, "isolated-forks.init.gradle")
TEST_OUTCOMES_INIT = os.path.join(HERE, "test-outcomes.init.gradle")
# The system property `test-outcomes.init.gradle` reads, and the line each listened task prints.
TEST_OUTCOMES_PROPERTY = "yoriwake.harness.testOutcomes"
TEST_OUTCOMES_MARKER = "yoriwake-harness: test outcomes "

# How Gradle names a test worker on its command line: `... GradleWorkerMain 'Gradle Test Executor 3'`.
TEST_WORKER_MARK = "Gradle Test Executor"
# And Surefire's and Failsafe's forked JVMs, which start from a `surefirebooter*.jar`.
MAVEN_TEST_WORKER_MARK = "surefirebooter"
SAMPLE_SECONDS = 0.5

# Must match AgentContract's JVM_MODE_FILE, MODE_ISOLATED, MODE_SHARED and MAP_SCHEMA_VERSION_FILE;
# test_module_contract.py pins them.
JVM_MODE_FILE = "jvm-mode.tsv"
MODE_ISOLATED = "isolated"
MODE_SHARED = "shared"
MAP_SCHEMA_VERSION_FILE = "schema-version"
RAW_DIR = "raw"
TOUCHES_FILE = "touches.tsv"
FIRST_TOUCH_FILE = "first-touch.tsv"
POSITIONS_FILE = "positions.tsv"
FIRST_TOUCH_ANY = "*"
# The plugin's own file beside the map, `key=value` lines. Not part of the contract, so absent keys
# read as None rather than as a defect.
TASK_FACTS_FILE = "task-facts"
# The agent's input note naming the change set it was handed.
CHANGED_CLASSES_INPUT = "input.yoriwake.change.classes"
DECODE_TASK_PREFIX = "yoriwakeDecode"


class TestJvmSampler:
    """Peak resident memory and count of the test JVMs alive during one build, sampled by `ps`.

    RSS, not heap, sampled twice a second: a JVM or a peak shorter than that can be missed, so
    the figure is a lower bound on the true peak. Both arms of a comparison are sampled the same way.
    """

    def __init__(self, interval=SAMPLE_SECONDS, ps=None, mark=TEST_WORKER_MARK):
        self.interval = interval
        self.mark = mark
        self.ps = ps or _ps_listing
        self.peaks = {}
        self.java = None
        self.peak_total = 0
        self.samples = 0
        self.failed = False
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._loop, daemon=True)

    def __enter__(self):
        self._thread.start()
        return self

    def __exit__(self, *_):
        self._stop.set()
        self._thread.join(timeout=10)

    def _loop(self):
        while not self._stop.wait(self.interval):
            self.sample()

    def sample(self):
        listing = self.ps()
        if listing is None:
            self.failed = True
            return
        self.samples += 1
        total = 0
        for pid, rss_kb, args in listing:
            if self.mark not in args:
                continue
            total += rss_kb
            self.peaks[pid] = max(self.peaks.get(pid, 0), rss_kb)
            if self.java is None:
                self.java = args.split(" ", 1)[0]
        self.peak_total = max(self.peak_total, total)

    def stats(self):
        if self.failed and not self.samples:
            return {"test_jvms": None, "peak_test_jvm_rss_mb": None,
                    "peak_total_test_jvm_rss_mb": None, "test_java_version": None, "rss_samples": 0}
        return {
            # Distinct test JVMs seen. Zero is real only when samples were taken; a build shorter than
            # one interval can start a JVM this never sees, so read it with `rss_samples`.
            "test_jvms": len(self.peaks),
            "peak_test_jvm_rss_mb": round(max(self.peaks.values()) / 1024, 1) if self.peaks else None,
            "peak_total_test_jvm_rss_mb": round(self.peak_total / 1024, 1) if self.peaks else None,
            "test_java_version": java_version(self.java),
            "rss_samples": self.samples,
        }


def _ps_listing():
    """(pid, rss in KiB, command line) for every process, or None where `ps` cannot be asked."""
    try:
        out = subprocess.run(["ps", "-A", "-o", "pid=,rss=,args="], capture_output=True, text=True,
                             timeout=15).stdout
    except (OSError, subprocess.SubprocessError):
        return None
    rows = []
    for line in out.splitlines():
        parts = line.split(None, 2)
        if len(parts) == 3 and parts[0].isdigit() and parts[1].isdigit():
            rows.append((int(parts[0]), int(parts[1]), parts[2]))
    return rows


def java_version(java):
    """JAVA_VERSION from the `release` file of the JDK a `bin/java` path belongs to, or None."""
    if not java:
        return None
    home = os.path.dirname(os.path.dirname(java))
    try:
        with open(os.path.join(home, "release"), encoding="utf-8") as handle:
            for line in handle:
                if line.startswith("JAVA_VERSION="):
                    return line.split("=", 1)[1].strip().strip('"')
    except OSError:
        return None
    return None


def read_task_times(path, test_task_path, launched_at, ended_at):
    """An arm's wall clock split by `task-times.init.gradle`'s lines, or None when none were written.

    `before_first_task_seconds` is daemon handshake plus configuration, the part the plugin's own
    configuration cost lives in; `test_task_seconds` is the test task alone; `decode_seconds` is the
    plugin's finalizer. Epoch milliseconds from Gradle and `time.time()` from the harness share one
    clock on one machine.
    """
    tasks = []
    try:
        with open(path, encoding="utf-8") as handle:
            for line in handle:
                fields = line.rstrip("\n").split("\t")
                if len(fields) == 4 and fields[1].isdigit() and fields[2].isdigit():
                    tasks.append((fields[0], int(fields[1]), int(fields[2]), fields[3]))
    except OSError:
        return None
    if not tasks:
        return None
    # A root build's task is `test` to the harness and `:test` to Gradle's events.
    if not test_task_path.startswith(":"):
        test_task_path = ":" + test_task_path
    test = [t for t in tasks if t[0] == test_task_path]
    decode_name = DECODE_TASK_PREFIX + test_task_path.rsplit(":", 1)[-1][:1].upper() + \
        test_task_path.rsplit(":", 1)[-1][1:]
    decode = [t for t in tasks if t[0].rsplit(":", 1)[-1] == decode_name
              and t[0].rsplit(":", 1)[0] == test_task_path.rsplit(":", 1)[0]]
    first_start = min(t[1] for t in tasks)
    last_end = max(t[2] for t in tasks)
    return {
        "test_task_seconds": round((test[0][2] - test[0][1]) / 1000, 2) if test else None,
        "test_task_outcome": test[0][3] if test else None,
        "decode_seconds": round((decode[0][2] - decode[0][1]) / 1000, 2) if decode else None,
        "before_first_task_seconds": (round(first_start / 1000 - launched_at, 2)
                                      if launched_at else None),
        "after_last_task_seconds": round(ended_at - last_end / 1000, 2) if ended_at else None,
        "tasks_executed": sum(1 for t in tasks if t[3] == "executed"),
    }


def read_test_outcomes(path, test_task_path):
    """`test-outcomes.init.gradle`'s lines for one task, or None when the file was not written.

    Each is `(class, ancestors, display_name, name, result)`; `class` is empty when the listener
    found no descriptor named after the test's class above it. Lines of other test tasks in the same
    build are left out.
    """
    if not test_task_path.startswith(":"):
        test_task_path = ":" + test_task_path
    tests = []
    try:
        with open(path, encoding="utf-8") as handle:
            for line in handle:
                fields = line.rstrip("\n").split("\t")
                if len(fields) < 5 or _unescape(fields[0]) != test_task_path:
                    continue
                fields = [_unescape(field) for field in fields]
                tests.append((fields[1], tuple(fields[2:-3]), fields[-3], fields[-2], fields[-1]))
    except OSError:
        return None
    return tests


def _unescape(value):
    """The listener's field escaping undone: `\\`, `\t`, `\r`, `\n`, as the decision record's."""
    if "\\" not in value:
        return value
    return re.sub(r"\\(.)", lambda m: {"t": "\t", "r": "\r", "n": "\n"}.get(m.group(1), m.group(1)),
                  value)


# The plugin's timer for listing the working tree while configuring, once per build. Absent from a
# counters file written before it existed, which reads as None rather than as zero.
WORKTREE_LISTING = "worktreeListing"


def plugin_timers(counters):
    """The plugin's own configuration and git timers, from `-Pyoriwake.internal.counters`, or None."""
    if not counters:
        return None
    millis = {k[: -len(".millis")]: v for k, v in counters.items() if k.endswith(".millis")}
    git = [v for k, v in millis.items() if k.startswith("git.")]
    return {"configure_millis": millis.get("configure"),
            "worktree_listing_millis": millis.get(WORKTREE_LISTING),
            "git_millis": sum(git) if git else None,
            "git_calls": sum(v for k, v in counters.items()
                             if k.startswith("git.") and not k.endswith(".millis")) or None}


def jvm_modes(map_dir):
    """`{"isolated": n, "shared": m}` from the map's jvm-mode.tsv, or None when there is no file.

    No file is a map written before the label existed, which the contract reads as shared; it is
    kept apart here so a reader can tell "labelled shared" from "never labelled".
    """
    if not map_dir:
        return None
    counts = Counter()
    try:
        with open(os.path.join(map_dir, JVM_MODE_FILE), encoding="utf-8") as handle:
            for line in handle:
                fields = line.rstrip("\n").split("\t")
                if len(fields) == 2:
                    counts[fields[1]] += 1
    except OSError:
        return None
    return {MODE_ISOLATED: counts.get(MODE_ISOLATED, 0), MODE_SHARED: counts.get(MODE_SHARED, 0),
            **{k: v for k, v in counts.items() if k not in (MODE_ISOLATED, MODE_SHARED)}}


def capture_mode(map_dir):
    """How the map was recorded: `isolated`, `shared`, `mixed`, `unlabelled`, or None with no map."""
    if not map_dir or not os.path.isdir(map_dir):
        return None
    modes = jvm_modes(map_dir)
    if modes is None:
        return "unlabelled"
    isolated, total = modes.get(MODE_ISOLATED, 0), sum(modes.values())
    if not total:
        return "unlabelled"
    if isolated == total:
        return MODE_ISOLATED
    return MODE_SHARED if isolated == 0 else "mixed"


def map_fits_arm(map_dir, isolated_arm):
    """Whether a map on disk may be reused by this arm, and why not when it may not.

    A shared arm must not select against a map any JVM of which was recorded in isolation, and an
    isolated arm must not select against one recorded with none: the two arms share one map
    directory per task, so whichever ran last left its map behind. A map whose schema is not the
    checkout's is not reused either, since the agent would refuse it and every row would force.
    """
    mode = capture_mode(map_dir)
    if mode is None:
        return True, None
    if isolated_arm and mode not in (MODE_ISOLATED, "mixed"):
        return False, f"the map on disk was recorded {mode}, and this is the isolated arm"
    if not isolated_arm and mode in (MODE_ISOLATED, "mixed"):
        return False, f"the map on disk was recorded {mode}, and this is the shared arm"
    return True, None


# Written by `run.py` beside the map files once its own capture has finished. A map without one was
# written by someone else -- `qualify.py`, a hand-run build, an older harness -- and says nothing about
# the arm, the plugin or the tree this run measures.
CAPTURE_STAMP_FILE = "harness-capture.json"


def capture_stamp(arm, plugin, project_commit):
    """What a map must have been captured under for this run to reuse it."""
    return {"writer": "run.py", "arm": arm, "plugin_commit": plugin, "project_commit": project_commit}


def write_capture_stamp(map_dir, stamp):
    import json  # noqa: PLC0415 -- only the stamp needs it
    with open(os.path.join(map_dir, CAPTURE_STAMP_FILE), "w", encoding="utf-8") as handle:
        json.dump(stamp, handle, sort_keys=True)


def stamp_fits(map_dir, wanted):
    """Whether this run captured the map on disk, and why not when it did not.

    Reuse is earned, not assumed: a dry run once reused a map `qualify.py` had captured
    under an older plugin commit, and measured every row against it.
    """
    import json  # noqa: PLC0415
    try:
        with open(os.path.join(map_dir, CAPTURE_STAMP_FILE), encoding="utf-8") as handle:
            found = json.load(handle)
    except (OSError, ValueError):
        return False, ("the map on disk carries no stamp of a capture by this harness (qualify.py, "
                       "a hand-run build or an older harness wrote it)")
    if not isinstance(found, dict):
        return False, "the map on disk carries an unreadable capture stamp"
    for key in sorted(wanted):
        if found.get(key) != wanted[key]:
            return False, (f"the map on disk was captured with {key}={found.get(key)!r}, "
                           f"and this run has {wanted[key]!r}")
    return True, None


def schema_version(map_dir):
    if not map_dir:
        return None
    try:
        with open(os.path.join(map_dir, MAP_SCHEMA_VERSION_FILE), encoding="utf-8") as handle:
            text = handle.read().strip()
    except OSError:
        return None
    return int(text) if text.isdigit() else None


def checkout_schema_version(source_root):
    """The map schema the checkout's agent writes, read from AgentContract, or None."""
    path = os.path.join(source_root, "agent", "src", "main", "java", "io", "github", "zeuspizza",
                        "yoriwake", "agent", "contract", "AgentContract.java")
    try:
        with open(path, encoding="utf-8") as handle:
            found = re.search(r"MAP_SCHEMA_VERSION\s*=\s*(\d+)\s*;", handle.read())
    except OSError:
        return None
    return int(found.group(1)) if found else None


def dir_bytes(path, skip_suffix=None):
    """Bytes under a directory, or None when it does not exist."""
    if not path or not os.path.isdir(path):
        return None
    total = 0
    for base, _, files in os.walk(path):
        for name in files:
            if skip_suffix and name.endswith(skip_suffix):
                continue
            try:
                total += os.path.getsize(os.path.join(base, name))
            except OSError:
                pass
    return total


def map_sizes(map_dir):
    """What the map costs on disk: the files selection reads, and the raw records beside them."""
    if not map_dir or not os.path.isdir(map_dir):
        return {"map_bytes": None, "map_raw_bytes": None}
    top = 0
    for name in os.listdir(map_dir):
        path = os.path.join(map_dir, name)
        if os.path.isfile(path):
            top += os.path.getsize(path)
    return {"map_bytes": top, "map_raw_bytes": dir_bytes(os.path.join(map_dir, RAW_DIR))}


def task_facts(map_dir):
    """The plugin's `task-facts` beside the map: framework, parallelism and worker count, or None."""
    if not map_dir:
        return None
    facts = {}
    try:
        with open(os.path.join(map_dir, TASK_FACTS_FILE), encoding="utf-8") as handle:
            for line in handle:
                key, sep, value = line.rstrip("\n").partition("=")
                if sep:
                    facts[key.strip()] = value.strip() or None
    except OSError:
        return None
    workers = facts.get("workers")
    return {"framework": facts.get("framework"), "parallelism": facts.get("parallelism"),
            "workers": int(workers) if workers and workers.isdigit() else None}


def touch_all_reasons(map_dir):
    """Why capture stopped observing, counted over the raw records: the native-library,
    child-process and class-loader rules firing in a real suite. None when no raw records exist.

    Each `all` line's value names its cause; the first words before a `:` are kept, so paths do
    not split one cause into many. A `lookup *` counts under its own key.
    """
    raw = os.path.join(map_dir or "", RAW_DIR)
    if not map_dir or not os.path.isdir(raw):
        return None
    reasons = Counter()
    seen = False
    for worker in sorted(os.listdir(raw)):
        path = os.path.join(raw, worker, TOUCHES_FILE)
        try:
            with open(path, encoding="utf-8", errors="replace") as handle:
                seen = True
                for line in handle:
                    fields = line.rstrip("\n").split("\t")
                    if len(fields) < 3:
                        continue
                    if fields[1] == "all":
                        reasons[re.sub(r"\d+", "N", fields[2].split(":", 1)[0].strip())[:80]] += 1
                    elif fields[1] == "lookup" and fields[2] == FIRST_TOUCH_ANY:
                        reasons["lookup *"] += 1
        except OSError:
            continue
    return dict(sorted(reasons.items())) if seen else None


def changed_classes(notes):
    """The change set the plugin handed the test JVM, from the decision record, or None."""
    if notes is None or CHANGED_CLASSES_INPUT not in notes:
        return None
    value = notes.get(CHANGED_CLASSES_INPUT) or ""
    return [c for c in value.split(",") if c]


def widest_windows(map_dir, classes, top=5):
    """Which changed classes widen the selection most under the first-touch rule, most first.

    For each class, the distinct tests that ran at or after its first touch in any JVM of the map
    -- the tests the shared-JVM rule selects for it -- as `[class, tests, share of the map's
    tests]`. A JVM whose first-touch rows start at `*` counts from there for every class. What a
    later "what widens your selection" report would show; not the selector's own answer.
    """
    if not map_dir or classes is None:
        return None
    first, positions = {}, []
    try:
        with open(os.path.join(map_dir, FIRST_TOUCH_FILE), encoding="utf-8") as handle:
            for line in handle:
                fields = line.rstrip("\n").split("\t")
                if len(fields) == 3 and fields[1].lstrip("-").isdigit():
                    jvm, seq, name = fields[0], int(fields[1]), fields[2]
                    slot = first.setdefault(jvm, {})
                    slot[name] = min(seq, slot.get(name, seq))
        with open(os.path.join(map_dir, POSITIONS_FILE), encoding="utf-8") as handle:
            for line in handle:
                fields = line.rstrip("\n").split("\t", 2)
                if len(fields) == 3 and fields[1].isdigit():
                    positions.append((fields[0], int(fields[1]), fields[2]))
    except OSError:
        return None
    everyone = {test for _, _, test in positions}
    if not everyone:
        return []
    widths = []
    for name in classes:
        reached = set()
        for jvm, seq, test in positions:
            touched = first.get(jvm, {})
            starts = [s for s in (touched.get(name), touched.get(FIRST_TOUCH_ANY)) if s is not None]
            if starts and seq >= min(starts):
                reached.add(test)
        widths.append([name, len(reached), round(len(reached) / len(everyone), 4)])
    widths.sort(key=lambda w: (-w[1], w[0]))
    return widths[:top]


# The descriptor segments a class and method are read from, as replay_decisions.name_of reads them.
_CLASS_SEGMENT = re.compile(r"\[(?:class|runner|spec|nested-class):([^\]]+)\]")
_METHOD_SEGMENT = re.compile(r"\[(?:method|test|feature|test-template|iteration):([^\]]+)\]")


def _decided_name(test_id):
    classes, methods = _CLASS_SEGMENT.findall(test_id), _METHOD_SEGMENT.findall(test_id)
    if not classes or not methods:
        return None
    return classes[-1], methods[-1].split("(", 1)[0]


# Joins a per-method result key's segments (`b() > v = 1`); run.py names its keys with it.
KEY_SEPARATOR = " > "


def _result_name(klass, name):
    """A result key's `(class, method)` as a decided id names it. A per-method key's first segment
    is the template, factory or feature the record decided, so it is the method; a key without one
    names the method itself."""
    method = name.split(KEY_SEPARATOR, 1)[0]
    return klass, method.removesuffix("()").split("(", 1)[0].split("[", 1)[0]


def induced_rules(induced, rules):
    """Every rule that selected each induced failure, keyed `class::name`, or None without rules.

    What an optimistic mode is judged on offline: drop a rule, and a failure whose only rules were
    that one would have been missed. A failure no decided test matches maps to None -- the record
    never saw it, which is not the same as "no rule selected it".
    """
    if rules is None:
        return None
    by_name = {}
    for test, tokens in rules.items():
        key = _decided_name(test)
        if key is not None:
            by_name.setdefault(key, set()).update(tokens)
    out = {}
    for klass, name in sorted(induced):
        tokens = by_name.get(_result_name(klass, name))
        out[f"{klass}::{name}"] = sorted(tokens) if tokens is not None else None
    return out


def gradle_version(wrapper_script):
    """The Gradle version pinned beside the wrapper script that drives the build, or None."""
    path = os.path.join(os.path.dirname(wrapper_script), "gradle", "wrapper",
                        "gradle-wrapper.properties")
    try:
        with open(path, encoding="utf-8") as handle:
            found = re.search(r"gradle-([0-9][^-/]*?)-(?:bin|all)\.zip", handle.read())
    except OSError:
        return None
    return found.group(1) if found else None


def machine():
    """The machine every figure of this sweep was taken on."""
    memory = None
    try:
        memory = os.sysconf("SC_PAGE_SIZE") * os.sysconf("SC_PHYS_PAGES")
    except (ValueError, OSError, AttributeError):
        pass
    return {"os": platform.system(), "os_release": platform.release(), "arch": platform.machine(),
            "cpu_count": os.cpu_count(),
            "memory_gib": round(memory / 2 ** 30, 1) if memory else None,
            "python": platform.python_version()}


def keep_raw(map_dir, target, since, with_exec):
    """Copies the map's raw records written since `since` to `target`. Returns the bytes kept.

    Without `.exec` blobs unless asked: those are JaCoCo's own data, large, and already decoded into
    the map; the index, touches, overhead and completion markers are what a later question reads.
    None when nothing fresh exists, so a narrowed arm that recorded nothing keeps nothing.
    """
    raw = os.path.join(map_dir or "", RAW_DIR)
    if not map_dir or not os.path.isdir(raw):
        return None
    fresh = False
    for base, _, files in os.walk(raw):
        if any(_mtime(os.path.join(base, f)) >= since for f in files):
            fresh = True
            break
    if not fresh:
        return None
    shutil.rmtree(target, ignore_errors=True)
    ignore = None if with_exec else shutil.ignore_patterns("*.exec")
    shutil.copytree(raw, target, ignore=ignore)
    return dir_bytes(target)


def _mtime(path):
    try:
        return os.path.getmtime(path)
    except OSError:
        return 0


# Copies the harness makes for its own bookkeeping; the maps they hold are kept as replay maps.
EVIDENCE_SKIP = ("map-before-", "map-after-", "evidence")


def archive_evidence(archive, members):
    """Writes `members` -- `(path, name in the archive)` -- to a gzip tar, staged then moved.

    Returns the archive's size in bytes. Harness bookkeeping copies (`map-before-*`,
    `map-after-*`) are left out: the maps an arm selected against are the replay maps already
    among the members.
    """
    os.makedirs(os.path.dirname(archive), exist_ok=True)
    staging = archive + ".writing"

    def skip(info):
        base = os.path.basename(info.name)
        return None if any(base.startswith(p) for p in EVIDENCE_SKIP) else info

    with tarfile.open(staging, "w:gz", compresslevel=6) as tar:
        for path, name in members:
            if path and os.path.exists(path):
                tar.add(path, arcname=name, filter=skip)
    os.replace(staging, archive)
    return os.path.getsize(archive)
