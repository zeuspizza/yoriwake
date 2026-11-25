"""The shell steps of a cross-OS job, in Python so they behave the same on every runner.

`find` does not exist on the Windows runner, and GNU and BSD `sed -i` disagree, so the steps live
here instead of in `verify.yml`.

`discard` removes earlier test results before a run on the same working tree: otherwise Gradle can
report the test task up to date and leave the previous run's results for the assertions to read.

    python .github/ci.py find-map samples/demo
    python .github/ci.py mutate samples/demo/src/main/java/dev/demo/Alpha.java
    python .github/ci.py restore samples/demo/src/main/java/dev/demo/Alpha.java
    python .github/ci.py mutate samples/demo/src/main/java/dev/demo/Epsilon.java
    python .github/ci.py discard samples/demo --map
    python .github/ci.py needs-checks <base-sha> <head-sha>
    python .github/ci.py touches-product <base-sha> <head-sha>
    python .github/ci.py scope <base-sha> <head-sha>
    python .github/ci.py narrows-guards <base-sha> <head-sha>
    python .github/ci.py reuse-shards <earlier-artifacts> <out-dir> --run ID --head SHA --event E --workflow PATH --shards 3,6 --count 8
    python .github/ci.py changelog <base-sha> <head-sha>
    python .github/ci.py rerun-on-download-failure -- ./gradlew build --console=plain
    python .github/ci.py list-test-kit-homes plugin/build/tmp
    python .github/ci.py seed-maven-repo maven/build/functional-m2 ~/.m2/yoriwake-seed-1
    python .github/ci.py gradle-seed ~/.gradle-seed listings/*/test-kit-homes.txt --gradle 8.14
    python .github/ci.py gradle-seed-size ~/.gradle-seed
"""

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.request

# The mutation the cross-OS check applies: Alpha.twice doubles, so tripling breaks exactly the two
# tests that assert on it and nothing else. `assert_selection.py` names those two tests, so the two
# files agree on one edit and must keep agreeing -- change this pair together or the job asserts
# against a mutation that did not happen.
MUTATION = ("return n * 2;", "return n * 3;")

# The second, for a class reached only by name: renaming Epsilon's `label` breaks the one test that
# compares its methods with `registry.yaml`, and no bytecode reference leads to it.
EPSILON_MUTATION = ("public String label()", "public String name()")

MUTATIONS = {"Alpha.java": MUTATION, "Epsilon.java": EPSILON_MUTATION}


def mutation_for(path):
    """The mutation for a source file, by its name; refuses a file that has none."""
    name = os.path.basename(path)
    if name not in MUTATIONS:
        raise SystemExit(f"refusing: no mutation is defined for {name}; known: {sorted(MUTATIONS)}")
    return MUTATIONS[name]

MAP_FILE = "coverage.tsv"

# The decision record and its per-writer parts, `decisions.tsv.<pid>-<writer>.part`.
DECISIONS_FILE = "decisions.tsv"


def temurin_version(installed, runtime, published):
    """The name setup-java installs the JDK a capture reported as `installed` by, among Temurin's
    `published` releases as (name, OpenJDK version) pairs: the release whose OpenJDK version is
    the JDK's own `runtime` version (`21.0.13.1+2-LTS` is published as `21.0.13+100000002.0.LTS`,
    which setup-java reports as `21.0.13+2`), else the name of the same build (`21.0.13+9` and
    `21.0.13+9.0` as `21.0.13+9.0.LTS`). setup-java only takes a version with a build number when
    it equals a published name, and a map selected against on another build runs every test, so
    no other build stands in. With no published name for it, `installed` without a trailing `.0`."""
    version = installed[:-2] if re.search(r"\+\d+\.0$", installed) else installed
    return next((name for name, openjdk in published if runtime and openjdk == runtime),
                next((name for name, _ in published if name == version or name.startswith(version + ".")),
                     version))


def java_runtime_version():
    """The runtime version of the JDK at `JAVA_HOME` (`21.0.13.1+2-LTS`), or None."""
    java = os.path.join(os.environ.get("JAVA_HOME", ""), "bin", "java")
    try:
        said = subprocess.run([java, "-XshowSettings:properties", "-version"], capture_output=True,
                              text=True, timeout=60).stderr
    except OSError:
        return None
    found = re.search(r"^\s*java\.runtime\.version = (\S+)", said, re.MULTILINE)
    return found.group(1) if found else None


def temurin_releases(installed):
    """Temurin's GA releases of the patch release `installed` belongs to, as (name, OpenJDK
    version) pairs; empty when Adoptium cannot be asked."""
    patch = installed.split("+")[0]
    numbers = patch.split(".")
    if len(numbers) != 3 or not all(n.isdigit() for n in numbers):
        return []
    upper = f"{numbers[0]}.{numbers[1]}.{int(numbers[2]) + 1}"
    url = ("https://api.adoptium.net/v3/info/release_versions?release_type=ga&page_size=50"
           f"&version=%5B{patch}%2C{upper}%29")
    try:
        # Adoptium refuses urllib's default user agent.
        request = urllib.request.Request(url, headers={"User-Agent": "yoriwake-ci"})
        with urllib.request.urlopen(request, timeout=30) as response:
            return [(v["semver"], v["openjdk_version"]) for v in json.load(response)["versions"]]
    except (OSError, ValueError, KeyError, TypeError):
        return []


def find_map(project_dir):
    """Every map directory under a project, newest first.

    One directory per test task, named `<task>-<hash>`, so the name cannot be predicted from the
    project -- which is why the workflow shelled out to `find` for it.
    """
    root = os.path.join(project_dir, ".gradle", "yoriwake")
    found = []
    for dirpath, _, filenames in os.walk(root):
        if MAP_FILE in filenames:
            found.append(dirpath)
    found.sort(key=lambda path: os.path.getmtime(path), reverse=True)
    return found


def records(map_dir):
    """Lines in a map, so a caller can say how much it holds rather than that it exists."""
    with open(os.path.join(map_dir, MAP_FILE), encoding="utf-8", errors="replace") as handle:
        return sum(1 for line in handle if line.strip())


def edit(path, old, new):
    """Replace `old` with `new`, and fail when it is absent.

    `sed` exits 0 when its pattern matches nothing, so a mutation step that silently did nothing
    left a job asserting against an unmutated tree -- which reports every test as "ran but did not
    fail" rather than as a broken step.
    """
    # Bytes kept as they are, line endings included: on Windows, text mode would write CRLF into an
    # LF checkout, and the restored file would differ from the commit after all.
    with open(path, encoding="utf-8", newline="") as handle:
        before = handle.read()
    if old not in before:
        raise SystemExit(f"refusing: {path} does not contain {old!r}, so the edit would be silent")
    with open(path, "w", encoding="utf-8", newline="") as handle:
        handle.write(before.replace(old, new, 1))


def discard_results(project_dir, drop_map=False):
    """Remove what a previous step left behind, so this step's evidence is its own.

    Test results and decision records always: a round whose run wrote no record must not be judged
    by the previous round's. The map only when asked: a selecting step reads the map the capturing
    step wrote, and deleting it there would delete the thing under test.
    """
    removed = []
    results = os.path.join(project_dir, "build", "test-results")
    if os.path.isdir(results):
        shutil.rmtree(results)
        removed.append(results)
    for dirpath, _, filenames in os.walk(os.path.join(project_dir, ".gradle", "yoriwake")):
        for name in filenames:
            if name.startswith(DECISIONS_FILE):
                os.remove(os.path.join(dirpath, name))
                removed.append(os.path.join(dirpath, name))
    if drop_map:
        maps = os.path.join(project_dir, ".gradle", "yoriwake")
        if os.path.isdir(maps):
            shutil.rmtree(maps)
            removed.append(maps)
    return removed


# Docs the checks read: the agent's tests compare their constants with the contract, the plugin's
# compare the names, flags, tokens and printed lines both docs quote with the code, test_ci.py
# compares the reference's supported matrix with matrix.yml and SECURITY.md's key fingerprint with
# RELEASE-KEY.asc.
CHECKED_DOCS = {"docs/contract.md", "docs/reference.md", "SECURITY.md"}


def needs_checks(paths):
    """Whether a change to `paths` needs the build and its checks: anything but Markdown or `docs/`,
    or a doc the checks read. No paths is an answer nobody could give, so it needs them: the wrong
    "no" skips every check, and GitHub counts a skipped required check as passing."""
    paths = [path for path in paths if path.strip()]
    if not paths:
        return True
    return any(path in CHECKED_DOCS or not (path.endswith(".md") or path.startswith("docs/")) for path in paths)


# The sources a pull request must pass every guard for: what the plugin, the Maven extension and the
# agent ship.
PRODUCT_SOURCES = ("plugin/src/main/", "core/src/main/", "agent/src/main/", "maven/src/main/")


def touches_product(paths):
    """Whether a change to `paths` reaches the shipped sources, so every guard must run. No paths
    is an answer nobody could give, so it does: the wrong "no" leaves the change to fast mode."""
    paths = [path for path in paths if path.strip()]
    return not paths or any(path.startswith(PRODUCT_SOURCES) for path in paths)


# What verify.yml's jobs need, by the paths a pull request changes. An allowlist: a path no list
# names runs everything, so a file added later is checked until someone lists it.
#
# No product suite reads these: the benchmark, the guard harness, and the workflows and scripts
# verify.yml does not run. test_ci.py is the exception among those it runs, since the one step that
# runs it runs on every change that is not docs only.
NO_SUITE_READS_PREFIXES = ("bench/",)
NO_SUITE_READS_FILES = {"scripts/prove_guards.py", "scripts/test_prove_guards.py", "scripts/guard_costs.py",
                        "scripts/guard_costs.json", "scripts/test_guard_costs.py", ".github/test_ci.py"}
VERIFY_READS = {".github/workflows/verify.yml", ".github/ci.py", ".github/assert_selection.py",
                ".github/functional_shards.py", ".github/functional-shards.json", ".github/run_summary.py",
                ".github/check_release_signing.sh"}
# The Maven suite compiles the Gradle plugin's break-suite verdicts (maven/build.gradle.kts), so a
# change to them is not the plugin's alone.
PLUGIN_SHARED_WITH_MAVEN = ("plugin/src/functionalTest/kotlin/io/github/zeuspizza/yoriwake/gradle/breaking/verdict/",)


def no_suite_reads(path):
    return (path.startswith(NO_SUITE_READS_PREFIXES) or path in NO_SUITE_READS_FILES
            or (path.startswith(".github/") and path not in VERIFY_READS))


def scope(paths):
    """Which of verify.yml's job groups a change to `paths` needs: `unit` (the Gradle build),
    `maven` (the Maven suite's shards) and `gradle` (the Gradle functional suites, the map jobs and
    the coverage gate). Docs only needs none; a change only to paths no suite reads needs none; one
    to `maven/` or `plugin/` besides those needs its own; anything else, or no paths, needs all."""
    paths = [path for path in paths if path.strip()]
    if not paths:
        return {"unit": True, "maven": True, "gradle": True}
    if not needs_checks(paths):
        return {"unit": False, "maven": False, "gradle": False}
    answer = {"unit": False, "maven": False, "gradle": False}
    for path in paths:
        if path in CHECKED_DOCS:
            return {"unit": True, "maven": True, "gradle": True}
        if path.endswith(".md") or path.startswith("docs/") or no_suite_reads(path):
            continue
        if path.startswith("maven/"):
            answer.update(unit=True, maven=True)
        elif path.startswith("plugin/") and not path.startswith(PLUGIN_SHARED_WITH_MAVEN):
            answer.update(unit=True, gradle=True)
        else:
            return {"unit": True, "maven": True, "gradle": True}
    return answer

# What runs and judges the guards, the modules they import included: no guard mutates it, so a
# change to it keeps fast mode.
GUARD_MACHINERY = {"scripts/prove_guards.py", "scripts/guard_costs.py", "scripts/guard_costs.json",
                   ".github/guard_shards.py", ".github/workflows/guards.yml", "bench/checkout.py"}


def narrows_guards(paths):
    """Whether a pull request's guards job may run only the guards its change selects, without fast
    mode: a change that is not docs only, reaches no suite verify.yml runs, and leaves the guard
    machinery alone. No paths runs fast mode."""
    paths = [path for path in paths if path.strip()]
    return (bool(paths) and needs_checks(paths) and not any(scope(paths).values())
            and not any(path in GUARD_MACHINERY for path in paths))


def changed_paths(base, head, cwd=None):
    """The paths a pull request changes, or None when git cannot say."""
    try:
        # No renames: a code file moved into docs/ would be listed under its new name only, and pass
        # as docs. NUL-separated, so a quoted name never reaches the rule.
        done = subprocess.run(["git", "diff", "--no-renames", "--name-only", "-z", f"{base}...{head}"],
                              capture_output=True, text=True, check=True, cwd=cwd)
    except (OSError, subprocess.CalledProcessError):
        return None
    return done.stdout.split("\0")


GUARDS_WORKFLOW = ".github/workflows/guards.yml"
WORKFLOWS_DIR = ".github/workflows/"
# What the `ci` suite's tests read that `needs_checks` lets pass as a document: the workflows, and
# the README whose coverage badge they compare with the gates.
CI_SUITE_READS = (WORKFLOWS_DIR, "README.md")
COMMIT = re.compile(r"[0-9a-f]{40}")
SHARD_RESULTS = "full.json"
# Where each shard's results say the guards in them ran: the run and the commit.
SHARD_SOURCE = "source.json"


def shard_dir(count, index):
    return f"guards-every-{index}-of-{count}"


def reused_shard_problem(result, index, count):
    """Why an earlier run's every-guard shard cannot stand in for this run's, or None."""
    if result is None:
        return f"shard {index} of {count} left no results"
    if result.get("shard") != f"{index}/{count}":
        return f"shard {index} of {count} says it is shard {result.get('shard')}"
    if result.get("mode") != "full" or result.get("changed") or result.get("guard"):
        return f"shard {index} of {count} did not run every guard"
    if result.get("refused"):
        return f"shard {index} of {count} refused: {result['refused']}"
    unproved = [guard["label"] for guard in result.get("guards", []) if guard.get("outcome") != "fired"]
    if unproved:
        return f"shard {index} of {count}: {unproved[0]} did not fire"
    return None


def shards_to_run(named, count, changed, ci_holders):
    """The shards a dispatch runs, and why it runs more than it named (None when it does not).

    `changed` maps each shard not named to the paths changed since the commit its earlier result
    was proved at. That result stands only when every such path is a document no check reads, or a
    workflow, by `needs_checks`' rule: anything else may change a verdict, so every shard runs. The
    `ci` suite's guards read the workflows and the README, so a shard holding one (`ci_holders`)
    runs again when either changed. A change to guards.yml's own setup is not seen here: a kept
    shard ran under the earlier one."""
    everything = list(range(1, count + 1))
    if not named or any(index not in everything for index in named):
        return everything, f"the shards to run, {sorted(named)}, are not among 1 to {count}"
    run = set(named)
    for index in everything:
        if index in run:
            continue
        paths = [path for path in changed[index] if path.strip()]
        code = [path for path in paths if needs_checks([path]) and not path.startswith(WORKFLOWS_DIR)]
        if code:
            return everything, f"{code[0]} changed since shard {index}'s earlier result"
        if index in ci_holders and any(path.startswith(CI_SUITE_READS) for path in paths):
            run.add(index)
    return sorted(run), None


def _diff(since, head="HEAD"):
    """The paths that differ between two commits, or None when git cannot say. Both ways: an
    earlier result proved on a branch this head does not contain differs by that branch's changes."""
    try:
        done = subprocess.run(["git", "diff", "--no-renames", "--name-only", "-z", "--end-of-options", since, head],
                              capture_output=True, text=True, check=True)
    except (OSError, subprocess.CalledProcessError):
        return None
    return done.stdout.split("\0")


def _ci_holders(count):
    """The shards of every guard that hold a `ci` suite guard, as this head partitions them."""
    sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "scripts"))
    import prove_guards
    shards, _loads = prove_guards.partition(prove_guards.MUTATIONS, count)
    return {index + 1 for index, shard in enumerate(shards) if any(m[4] is prove_guards.ci_suite for m in shard)}


def _read_json(path):
    try:
        with open(path, encoding="utf-8") as handle:
            return json.load(handle)
    except (OSError, ValueError):
        return None


def reuse_shards(earlier, out, run, head, event, workflow, named, count, diff=_diff, ci_holders=_ci_holders):
    """The shards to run, and why every one runs (None when the named ones are enough). When the
    earlier run's other shards stand, each is copied to `out` with the run and commit its guards
    ran at: from its joined results when that run itself took shards from another."""
    everything = list(range(1, count + 1))
    if not str(run).isdigit():
        return everything, "no earlier run to take shards from"
    if workflow != GUARDS_WORKFLOW or event != "workflow_dispatch":
        return everything, f"run {run} is not a dispatch of {GUARDS_WORKFLOW} ({event or '?'}, {workflow or '?'})"
    if not COMMIT.fullmatch(head or ""):
        return everything, f"run {run}'s commit could not be read"
    found = {}
    for index in everything:
        if index in named:
            continue
        joined = os.path.join(earlier, "guards-joined", shard_dir(count, index))
        path = joined if os.path.isdir(joined) else os.path.join(earlier, shard_dir(count, index))
        problem = reused_shard_problem(_read_json(os.path.join(path, SHARD_RESULTS)), index, count)
        if problem:
            return everything, f"run {run}: {problem}"
        source = _read_json(os.path.join(path, SHARD_SOURCE)) or {"run": int(run), "head": head}
        # A shard of the run itself must say it is that run's, at the commit the API gives for it;
        # one it kept from an earlier run names that run, and its commit must at least be one.
        own = path != joined
        if (not isinstance(source, dict) or not isinstance(source.get("run"), int)
                or not COMMIT.fullmatch(str(source.get("head", "")))
                or (own and (source["run"], source["head"]) != (int(run), head))):
            return everything, f"shard {index}'s run and commit are not run {run}'s: {source}"
        paths = diff(source["head"])
        if paths is None:
            return everything, f"shard {index}'s commit {source['head']} cannot be compared with this one"
        found[index] = (path, source, paths)
    shards, why = shards_to_run(named, count, {i: f[2] for i, f in found.items()}, ci_holders(count))
    if why:
        return shards, why
    for index, (path, source, _paths) in found.items():
        if index in shards:
            continue
        target = os.path.join(out, shard_dir(count, index))
        shutil.copytree(path, target)
        with open(os.path.join(target, SHARD_SOURCE), "w", encoding="utf-8") as handle:
            json.dump(source, handle)
    return shards, None


def parse_shards(text):
    """`3,6` as [3, 6]; anything else as no shard."""
    parts = [part.strip() for part in (text or "").split(",")]
    return [int(part) for part in parts] if all(part.isdigit() for part in parts) else []


# Each verify.yml job by id: its name, as GitHub lists the job (a shard's with its matrix
# expression), and the `changes` answer its `if` reads. A push to main takes an answer from a pull
# request's run only when every job reading it succeeded there. `build` reads `code`: it succeeds
# with its Gradle steps skipped, so it proves nothing about `unit`.
VERIFY_JOBS = {
    "build": ("unit and agent suites (build)", "code"),
    "maven-shard": ("functional on Maven (shard ${{ matrix.shard }}/3)", "maven"),
    "maven": ("unit and agent suites", "code"),
    "functional-shard": ("functional on Gradle 8.14 (shard ${{ matrix.shard }}/3)", "gradle"),
    "functional": ("functional on Gradle 8.14", "gradle"),
    "functional-gradle9-shard": ("functional on Gradle 9 (shard ${{ matrix.shard }}/3)", "gradle"),
    "functional-gradle9": ("functional on Gradle 9", "gradle"),
    # Push only, after the shards; under `gradle` a pull request's skipped job would keep a push
    # from reusing its shards.
    "gradle-seed": ("seed the fixture builds' dependencies", "code"),
    "coverage": ("coverage gate", "gradle"),
    "capture": ("build a map", "gradle"),
    "select": ("select on another machine", "gradle"),
    "select-on-windows": ("select on windows, against a map captured on linux", "gradle"),
    "capture-on-windows": ("build a map on windows", "gradle"),
    "select-on-windows-from-windows": ("select on windows, against a map captured on windows", "gradle"),
    "select-on-linux": ("select on linux, against a map captured on windows", "gradle"),
    "build-windows": ("unit and agent suites on windows", "unit"),
    "run-summary": ("run summary", "code"),
}
REUSABLE = ("unit", "maven", "gradle")
SHARD_NAME = re.compile(r"\(shard \$\{\{ matrix\.shard \}\}/(\d+)\)")
# What verify.yml's `changes` job prints of the tree its checkout holds: on a pull request, the
# head merged with the base of that moment, which is what the run tested.
TESTED_TREE = re.compile(r"^(?:\S+ )?tested tree ([0-9a-f]{40})\s*$", re.M)


def verify_job_names(answer):
    """The names GitHub lists for the jobs reading `answer`, each shard under its own."""
    names = set()
    for name, reads in VERIFY_JOBS.values():
        if reads != answer:
            continue
        shards = SHARD_NAME.search(name)
        if not shards:
            names.add(name)
            continue
        count = int(shards.group(1))
        names |= {SHARD_NAME.sub(f"(shard {index}/{count})", name) for index in range(1, count + 1)}
    return names


def tested_tree(log):
    """The tree a `changes` job's log says its checkout held, or None when it does not say once."""
    found = set(TESTED_TREE.findall(log or ""))
    return found.pop() if len(found) == 1 else None


def reuse_on_push(sha, tree, pulls, runs, jobs, log):
    """Which of `unit`, `maven` and `gradle` a push of `sha` (whose tree is `tree`) still needs, and
    why, in one line. An answer is taken from a pull request's run only when that pull request
    merged as `sha`, the newest verify run of its head passed having tested `tree`, and every job
    reading the answer succeeded in it; anything else, or an answer GitHub did not give (None),
    runs everything. `pulls`, `runs` and `jobs` are the API's JSON, `log` the run's `changes` log."""
    def everything(why):
        return {"unit": True, "maven": True, "gradle": True}, f"nothing reused: {why}"

    if not COMMIT.fullmatch(sha or "") or not COMMIT.fullmatch(tree or ""):
        return everything("the pushed commit or its tree could not be read")
    if not isinstance(pulls, list):
        return everything(f"GitHub did not say which pull request merged as {sha[:8]}")
    merged = [pull for pull in pulls if isinstance(pull, dict) and pull.get("merge_commit_sha") == sha
              and pull.get("merged_at")]
    if len(merged) != 1:
        return everything(f"{len(merged)} pull requests merged as {sha[:8]}")
    number, head = merged[0].get("number"), (merged[0].get("head") or {}).get("sha")
    if not COMMIT.fullmatch(str(head or "")):
        return everything(f"pull request #{number}'s head could not be read")
    listed = runs.get("workflow_runs") if isinstance(runs, dict) else None
    found = [run for run in listed or [] if isinstance(run, dict)
             and run.get("head_sha") == head and run.get("event") == "pull_request"]
    if not found:
        return everything(f"no verify run of pull request #{number}'s head {head[:8]}")
    # The newest run says what a re-run or a superseding push concluded; an older green one does not.
    run = max(found, key=lambda run: (str(run.get("created_at") or ""), run.get("id") or 0))
    name = f"run {run.get('id')}"
    if run.get("status") != "completed" or run.get("conclusion") != "success":
        return everything(f"{name} of pull request #{number} is {run.get('status')}, {run.get('conclusion')}")
    # A pull request's run tests its head merged with the base of that moment, which the head's
    # own tree, and so this push's, need not be.
    if tested_tree(log) != tree:
        return everything(f"{name} of pull request #{number} did not say it tested this tree")
    listed = jobs.get("jobs") if isinstance(jobs, dict) else None
    if not isinstance(listed, list) or jobs.get("total_count") != len(listed) or any(
            not isinstance(job, dict) or job.get("run_id") != run.get("id") for job in listed):
        return everything(f"{name}'s jobs could not be read")
    concluded = {}
    for job in listed:
        concluded.setdefault(job.get("name"), set()).add(job.get("conclusion"))
    answer = {group: not all(concluded.get(job) == {"success"} for job in verify_job_names(group))
              for group in REUSABLE}
    # The coverage gate reads the unit build's data, and a Maven change runs the unit build, as in scope.
    if answer["maven"] or answer["gradle"]:
        answer["unit"] = True
    taken = [group for group in REUSABLE if not answer[group]]
    link = f"[{name}]({run.get('html_url')})" if run.get("html_url") else name
    if not taken:
        return answer, f"nothing reused: {link} of pull request #{number} proved no group"
    return answer, (f"reused {link} of pull request #{number} at {head[:8]}, which tested this tree: "
                    f"{', '.join(taken)} not run again")


CHANGELOG = "CHANGELOG.md"
VERSION_HEADING = re.compile(r"^## \[([^\]]+)\]")
LINK_REFERENCE = re.compile(r"^\[[^\]]+\]:\s")
HUNK = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@")


def _git(args, cwd):
    # Bytes, decoded without newline translation: git numbers lines by "\n" alone, and a carriage
    # return or line separator read as a break would shift every later line off git's numbering.
    done = subprocess.run(["git", *args], capture_output=True, check=True, cwd=cwd)
    return done.stdout.decode("utf-8")


def changelog_change(base, head, cwd=None):
    """The changelog at the merge base, at `head`, and the numbers of the lines `head` added; None
    when git cannot say. A changelog absent on either side reads as empty."""
    # From the repository's top, wherever the command runs: a relative pathspec in a subdirectory
    # names no file, and an absent changelog passes.
    path = f":(top){CHANGELOG}"
    try:
        merge_base = _git(["merge-base", base, head], cwd).strip()

        def text_at(rev):
            if not _git(["ls-tree", "--name-only", rev, "--", path], cwd).strip():
                return ""
            return _git(["show", f"{rev}:{CHANGELOG}"], cwd)

        before, after = text_at(merge_base), text_at(head)
        diff = _git(["diff", "--unified=0", "--no-renames", f"{merge_base}..{head}", "--", path], cwd)
    except (OSError, subprocess.CalledProcessError, UnicodeDecodeError):
        return None
    added = []
    line = None
    for row in diff.split("\n"):
        hunk = HUNK.match(row)
        if hunk:
            line = int(hunk.group(1))
        elif line is not None and row.startswith("+"):
            added.append(line)
            line += 1
    return before, after, added


def changelog_problems(before, after, added):
    """Each added line that sits under a version already released before the change.

    A line belongs to the nearest `## ` heading above it, the heading to its own section. A version
    the change itself adds is a release entry and passes; so do the preamble, link references and
    blank lines, which carry no entry. A released version whose heading is gone was renamed, which
    no added line can show: the renamed heading opens a section of its own."""
    released = {match.group(1) for match in map(VERSION_HEADING.match, before.split("\n")) if match}
    released.discard("Unreleased")
    lines = after.split("\n")
    headed = {match.group(1) for match in map(VERSION_HEADING.match, lines) if match}
    problems = [f"{CHANGELOG}: the heading of [{version}], released before this change, is gone"
                for version in sorted(released - headed)] if after else []
    for number in added:
        text = lines[number - 1]
        if not text.strip() or LINK_REFERENCE.match(text):
            continue
        version = next((match.group(1) for match in map(VERSION_HEADING.match, reversed(lines[:number]))
                        if match), None)
        if version in released:
            problems.append(f"{CHANGELOG}:{number}: {text!r} is under [{version}], released before this "
                            f"change; it belongs under ## [Unreleased]")
    return problems
# A download that failed in transit: a dropped connection or a TLS handshake the server ended (which
# no Gradle setting retries), a timeout, or a 408, 429 or 5xx that outlasted Gradle's own retries.
# Gradle's wording: when it moves, nothing matches and the build fails as it did before.
DOWNLOAD_FAILED = ("Could not GET '", "Could not HEAD '", "Could not download")
# A resolution that would fail the same way again.
NOT_TRANSIENT = re.compile(r"status code (?:401|403|404)\b|Could not find ")
FAILURE_BLOCK = re.compile(r"^\* What went wrong:\n(.*?)(?=^\* What went wrong:|^\* Try:|\Z)", re.M | re.S)


def download_failure(output):
    """The line naming the download a failed build could not make, or None when the build failed for
    any other reason too. Only the build's own failure blocks are read: a test whose build failed to
    resolve reports as failing tests, not as a download."""
    blocks = FAILURE_BLOCK.findall(output)
    found = None
    for block in blocks:
        lines = [line.strip().lstrip("> ") for line in block.splitlines()
                 if any(marker in line for marker in DOWNLOAD_FAILED)]
        if not lines or NOT_TRANSIENT.search(block):
            return None
        found = found or lines[-1]
    return found


def runnable(command, windows=None):
    """`command`, with a leading `./gradlew` as the batch file on Windows: CreateProcess cannot
    start the shell script."""
    if windows is None:
        windows = os.name == "nt"
    if windows and command[:1] == ["./gradlew"]:
        return [os.path.abspath("gradlew.bat")] + command[1:]
    return command


def run_teed(command):
    """Runs `command`, passing its output through as it comes; (exit code, output).

    The bytes pass through as written: decoded and re-encoded, a character a Windows console cannot
    encode raised there, losing the rest of the log and the build's exit code."""
    process = subprocess.Popen(runnable(command), stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    console = getattr(sys.stdout, "buffer", None)
    output = []
    for line in process.stdout:
        if console is None:
            sys.stdout.write(line.decode("utf-8", errors="replace"))
        else:
            sys.stdout.flush()
            console.write(line)
            console.flush()
        output.append(line)
    return process.wait(), b"".join(output).decode("utf-8", errors="replace")


# Seconds before the rerun after a rate limit, whatever `--pause` says: a rerun a minute after a 429
# hit it again, as a repository counts an address's requests over time.
RATE_LIMIT_PAUSE = 300
# A 429 in Gradle's words (`status code 429`) and in Maven's resolver's (`status code: 429`).
RATE_LIMITED = re.compile(r"status code:? 429\b")


def rerun_on_download_failure(command, pause=60, sleep=time.sleep):
    """Runs `command`, and once more after `pause` seconds (`RATE_LIMIT_PAUSE` after a 429) when it
    failed on a dependency download alone; the exit code of the last run. The second run reuses
    what the first built."""
    code, output = run_teed(command)
    why = download_failure(output) if code != 0 else None
    if why is None:
        return code
    if RATE_LIMITED.search(why):
        pause = RATE_LIMIT_PAUSE
    print(f"rerunning in {pause}s, once: the build failed on a dependency download: {why}", flush=True)
    sleep(pause)
    return run_teed(command)[0]


# Where each functional-test task keeps its TestKit homes, the Tooling API's included, and where a
# home keeps what it downloaded: the plugin's functional tests name the homes, Gradle the cache layout.
TEST_KIT_HOME = ".gradle-test-kit-"
TEST_KIT_HOMES = (TEST_KIT_HOME, ".gradle-tooling-api-")
DOWNLOADED = os.path.join("caches", "modules-2", "files-2.1")


def test_kit_listing(build_tmp):
    """Every file each TestKit home under `build_tmp` downloaded, with its size and modification
    time, one per line; an entry that cannot be read is listed with the error instead.

    A dependency a build reported missing is either absent here, never downloaded, or present,
    downloaded and then unreadable to that build: the two need different fixes.
    """
    lines = []
    for dirpath, dirnames, _ in os.walk(build_tmp):
        for home in sorted(name for name in dirnames if name.startswith(TEST_KIT_HOMES)):
            root = os.path.join(dirpath, home)
            lines.append(root)
            unreadable = lambda error: lines.append(f"  {os.path.relpath(error.filename, root)}\tERROR {error}")
            for files_dir, _, names in sorted(os.walk(os.path.join(root, DOWNLOADED), onerror=unreadable)):
                for name in sorted(names):
                    path = os.path.join(files_dir, name)
                    try:
                        found = os.stat(path)
                    except OSError as error:
                        lines.append(f"  {os.path.relpath(path, root)}\tERROR {error}")
                        continue
                    modified = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(found.st_mtime))
                    lines.append(f"  {os.path.relpath(path, root)}\t{found.st_size}\t{modified}")
    return lines


# What a Maven seed repository never takes: the plugin under test, which each build publishes afresh
# (and a guard mutates), the fixtures' own artifacts a test installs (all under `dev`), and the
# resolver's records of a failed download or files a stopped download left half written. A seeded
# fixture would reach later runs' builds.
SEED_EXCLUDED_DIRS = (os.path.join("io", "github", "zeuspizza"), "dev")
SEED_EXCLUDED_NAMES = ("resolver-status.properties",)


def seed_maven_repo(source, seed):
    """Copies into `seed` each file of the Maven local repository `source` it lacks; the number copied.

    A later build reads `seed` behind its own local repository and downloads none of them again."""
    added = 0
    for dirpath, dirnames, names in os.walk(source):
        relative = os.path.relpath(dirpath, source)
        if any(relative == excluded or relative.startswith(excluded + os.sep) for excluded in SEED_EXCLUDED_DIRS):
            dirnames[:] = []
            continue
        # The resolver's own bookkeeping, such as Maven 4's `.locks`, is no artifact.
        dirnames[:] = [name for name in dirnames if not name.startswith(".")]
        for name in names:
            if name.endswith((".lastUpdated", ".tmp", ".part")) or name in SEED_EXCLUDED_NAMES:
                continue
            target = os.path.join(seed, relative, name)
            if os.path.exists(target):
                continue
            os.makedirs(os.path.dirname(target), exist_ok=True)
            shutil.copy2(os.path.join(dirpath, name), target)
            added += 1
    return added


# A listing's line for one downloaded file: `files-2.1/<group>/<module>/<version>/<sha1>/<file>`.
LISTED_FILE = re.compile(r"^\s+caches[/\\]modules-2[/\\]files-2\.1[/\\]([^/\\]+)[/\\]([^/\\]+)[/\\]([^/\\]+)"
                         r"[/\\][0-9a-f]+[/\\]([^/\\\t]+)\t\d+\t")
# What a fixture build publishes itself, from this build's file repository: the plugin under test
# and the fixtures' own artifacts. A seeded copy would reach later builds in its place.
SEED_EXCLUDED_GROUPS = ("io.github.zeuspizza", "dev")
# Gradle's metadata, which resolving the module brings with it.
METADATA_EXTENSIONS = (".pom", ".module")


def seed_coordinates(listings):
    """Each `group:module:version` the TestKit home listings name, with the notation of each listed
    artifact file, `group:module:version[:classifier]@ext`, for one the plain module may not bring."""
    found = {}
    for listing in listings:
        with open(listing, encoding="utf-8", errors="replace") as handle:
            for line in handle:
                match = LISTED_FILE.match(line)
                if not match:
                    continue
                group, module, version, name = match.groups()
                if any(group == excluded or group.startswith(excluded + ".") for excluded in SEED_EXCLUDED_GROUPS):
                    continue
                files = found.setdefault(f"{group}:{module}:{version}", {})
                stem = f"{module}-{version}"
                if name.endswith(METADATA_EXTENSIONS) or not name.startswith(stem) or name[len(stem):][:1] not in "-.":
                    continue
                rest = name[len(stem):]
                if rest.startswith("."):
                    files[name] = f"{group}:{module}:{version}@{rest[1:]}"
                elif "." in rest:
                    classifier, extension = rest[1:].rsplit(".", 1)
                    files[name] = f"{group}:{module}:{version}:{classifier}@{extension}"
    return found


# Each coordinate as its own detached, non-transitive configuration with a Java runtime
# classpath's attributes: Gradle picks the variant from the module's metadata as a fixture does
# and files it under the same index entries. A platform (a BOM) resolves as one. A coordinate that
# does not resolve is printed and the rest go on. Central first, then the plugin portal, as the
# fixtures declare them: Gradle files what it found per repository.
SEED_BUILD = """
plugins {
    id 'java'
}
repositories {
    mavenCentral()
    gradlePluginPortal()
}
def resolve(String notation, boolean platform) {
    def runtime = configurations.runtimeClasspath.attributes
    def dependency = dependencies.create(notation)
    def configuration = configurations.detachedConfiguration(platform ? dependencies.platform(dependency) : dependency)
    configuration.transitive = false
    runtime.keySet().each { configuration.attributes.attribute(it, runtime.getAttribute(it)) }
    def artifacts = configuration.incoming.artifactView { lenient = true }.artifacts
    [artifacts.artifacts.collect { it.file.name } as Set, artifacts.failures]
}
def seed(String notation) {
    def (brought, failures) = resolve(notation, false)
    if (failures && !notation.contains('@') && resolve(notation, true)[1].isEmpty()) {
        return brought
    }
    failures.each { println "not seeded: ${notation}: ${it.message?.readLines()?.find()}" }
    brought
}
file('coordinates.tsv').eachLine { line ->
    def fields = line.split('\\t') as List
    def brought = seed(fields[0])
    fields.drop(1).collate(2).each { name, notation ->
        if (!brought.contains(name)) {
            seed(notation)
        }
    }
}
"""


def gradle_seed(home, listings, version, run=subprocess.call):
    """Resolves into the Gradle user home `home`, under Gradle `version`, every module the listings
    name; the build's exit code. The distribution is kept beside the build, not in `home`."""
    coordinates = seed_coordinates(listings)
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    with tempfile.TemporaryDirectory() as build:
        os.makedirs(os.path.join(build, "gradle", "wrapper"))
        shutil.copy2(os.path.join(root, "gradlew"), os.path.join(build, "gradlew"))
        shutil.copy2(os.path.join(root, "gradle", "wrapper", "gradle-wrapper.jar"),
                     os.path.join(build, "gradle", "wrapper", "gradle-wrapper.jar"))
        with open(os.path.join(build, "gradle", "wrapper", "gradle-wrapper.properties"), "w", encoding="utf-8") as handle:
            handle.write("distributionBase=PROJECT\ndistributionPath=wrapper/dists\n"
                         f"distributionUrl=https\\://services.gradle.org/distributions/gradle-{version}-bin.zip\n"
                         "zipStoreBase=PROJECT\nzipStorePath=wrapper/dists\n")
        with open(os.path.join(build, "settings.gradle"), "w", encoding="utf-8") as handle:
            handle.write("rootProject.name = 'gradle-seed'\n")
        with open(os.path.join(build, "build.gradle"), "w", encoding="utf-8") as handle:
            handle.write(SEED_BUILD)
        with open(os.path.join(build, "coordinates.tsv"), "w", encoding="utf-8") as handle:
            for coordinate, files in sorted(coordinates.items()):
                handle.write("\t".join([coordinate] + [part for item in sorted(files.items()) for part in item]) + "\n")
        print(f"resolving {len(coordinates)} modules into {home} on Gradle {version}", file=sys.stderr, flush=True)
        environment = {key: value for key, value in os.environ.items() if key != "GRADLE_RO_DEP_CACHE"}
        environment["GRADLE_USER_HOME"] = home
        return run([os.path.join(build, "gradlew"), "--no-daemon", "--console=plain", "-q", "--dry-run", "help"],
                   cwd=build, env=environment)


def gradle_seed_size(home):
    """The number of files and their bytes in the Gradle user home `home`'s `caches/modules-2`."""
    files = size = 0
    for dirpath, _, names in os.walk(os.path.join(home, "caches", "modules-2")):
        for name in names:
            files += 1
            size += os.path.getsize(os.path.join(dirpath, name))
    return files, size


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)

    rerun = sub.add_parser("rerun-on-download-failure",
                           help="run a build, and once more when it failed on a dependency download")
    rerun.add_argument("--pause", type=int, default=60, help="seconds before the rerun")
    rerun.add_argument("build", nargs=argparse.REMAINDER, help="-- then the command")

    temurin = sub.add_parser("temurin-version",
                             help="print the name setup-java installs a captured Temurin JDK by")
    temurin.add_argument("installed")

    locate = sub.add_parser("find-map", help="print the newest map directory, and its record count")
    locate.add_argument("project_dir")

    mutate = sub.add_parser("mutate", help="apply the cross-OS check's mutation")
    mutate.add_argument("source")

    restore = sub.add_parser("restore", help="undo the mutation")
    restore.add_argument("source")

    discard = sub.add_parser("discard", help="remove a previous step's results")
    discard.add_argument("project_dir")
    discard.add_argument("--map", action="store_true", help="also delete the map directory")

    checks = sub.add_parser("needs-checks", help="print true unless a pull request changes only docs")
    checks.add_argument("base")
    checks.add_argument("head")

    scoped = sub.add_parser("scope", help="print unit=, maven= and gradle=: the job groups a pull request needs")
    scoped.add_argument("base")
    scoped.add_argument("head")

    narrow = sub.add_parser("narrows-guards",
                            help="print true when a pull request's guards job runs only the change's own")
    narrow.add_argument("base")
    narrow.add_argument("head")

    product = sub.add_parser("touches-product",
                             help="print true unless a pull request leaves the shipped sources alone")
    product.add_argument("base")
    product.add_argument("head")

    reuse = sub.add_parser("reuse-shards",
                           help="print the every-guard shards a dispatch runs, keeping an earlier run's others")
    reuse.add_argument("earlier", help="the earlier run's downloaded guard artifacts")
    reuse.add_argument("out", help="where the shards taken from it are written")
    reuse.add_argument("--run", default="")
    reuse.add_argument("--head", default="", help="the earlier run's commit")
    reuse.add_argument("--event", default="")
    reuse.add_argument("--workflow", default="", help="the earlier run's workflow file")
    reuse.add_argument("--shards", default="", help="the shards to run, as 3,6")
    reuse.add_argument("--count", type=int, required=True)

    pushed = sub.add_parser("reuse-on-push",
                            help="print unit=, maven=, gradle= and reused=: what a push to main still runs")
    pushed.add_argument("answers", help="a directory of pulls.json, runs.json, jobs.json and changes.log")
    pushed.add_argument("--sha", required=True)
    pushed.add_argument("--tree", required=True)

    changelog = sub.add_parser("changelog",
                               help="fail when a change adds a changelog line under a released version")
    changelog.add_argument("base")
    changelog.add_argument("head")

    seeded = sub.add_parser("seed-maven-repo",
                            help="copy what a Maven local repository downloaded into a seed; print added=")
    seeded.add_argument("source")
    seeded.add_argument("seed")

    seeding = sub.add_parser("gradle-seed",
                             help="resolve into a Gradle user home every module TestKit home listings name")
    seeding.add_argument("home")
    seeding.add_argument("listings", nargs="+")
    seeding.add_argument("--gradle", required=True, help="the Gradle version that resolves them")

    sizing = sub.add_parser("gradle-seed-size", help="print files= and bytes= of a Gradle user home's modules-2")
    sizing.add_argument("home")

    homes = sub.add_parser("list-test-kit-homes",
                           help="list what each TestKit home under a build directory downloaded")
    homes.add_argument("build_tmp")

    args = parser.parse_args(argv)

    if args.command == "seed-maven-repo":
        added = seed_maven_repo(args.source, args.seed) if os.path.isdir(args.source) else 0
        print(f"{added} files added to {args.seed}", file=sys.stderr)
        print(f"added={added}")
        return 0

    if args.command == "list-test-kit-homes":
        lines = test_kit_listing(args.build_tmp)
        print("\n".join(lines) if lines else f"no TestKit home under {args.build_tmp}")
        homes = sum(1 for line in lines if not line.startswith(" "))
        files = sum(1 for line in lines if line.startswith(" ") and "\tERROR " not in line)
        print(f"the TestKit homes downloaded {files} files ({homes} homes)", file=sys.stderr)
        return 0

    if args.command == "gradle-seed":
        return gradle_seed(args.home, args.listings, args.gradle)

    if args.command == "gradle-seed-size":
        files, size = gradle_seed_size(args.home)
        print(f"files={files}")
        print(f"bytes={size}")
        return 0

    if args.command == "changelog":
        change = changelog_change(args.base, args.head)
        if change is None:
            print(f"git could not read {CHANGELOG} between {args.base} and {args.head}", file=sys.stderr)
            return 1
        problems = changelog_problems(*change)
        for problem in problems:
            print(problem)
        return 1 if problems else 0
    if args.command == "rerun-on-download-failure":
        build = args.build[1:] if args.build[:1] == ["--"] else args.build
        if not build:
            parser.error("rerun-on-download-failure needs a command after --")
        return rerun_on_download_failure(build, pause=args.pause)

    if args.command == "reuse-shards":
        shards, why = reuse_shards(args.earlier, args.out, args.run, args.head, args.event, args.workflow,
                                   parse_shards(args.shards), args.count)
        print(json.dumps(shards))
        print(f"every shard runs: {why}" if why else
              f"shards {', '.join(map(str, shards))} run; the others are run {args.run}'s", file=sys.stderr)
        return 0

    if args.command == "reuse-on-push":
        try:
            with open(os.path.join(args.answers, "changes.log"), encoding="utf-8", errors="replace") as handle:
                log = handle.read()
        except OSError:
            log = None
        answer, why = reuse_on_push(args.sha, args.tree, *(_read_json(os.path.join(args.answers, f"{name}.json"))
                                                           for name in ("pulls", "runs", "jobs")), log)
        for name in REUSABLE:
            print(f"{name}={'true' if answer[name] else 'false'}")
        print(f"reused={' '.join(why.split())}")
        return 0

    if args.command == "temurin-version":
        print(temurin_version(args.installed, java_runtime_version(), temurin_releases(args.installed)))
        return 0

    if args.command == "scope":
        paths = changed_paths(args.base, args.head)
        answer = scope(paths if paths is not None else [])
        for name in ("unit", "maven", "gradle"):
            print(f"{name}={'true' if answer[name] else 'false'}")
        return 0

    if args.command == "narrows-guards":
        paths = changed_paths(args.base, args.head)
        print("true" if paths is not None and narrows_guards(paths) else "false")
        return 0

    if args.command == "touches-product":
        paths = changed_paths(args.base, args.head)
        print("true" if paths is None or touches_product(paths) else "false")
        return 0

    if args.command == "needs-checks":
        paths = changed_paths(args.base, args.head)
        print("true" if paths is None or needs_checks(paths) else "false")
        return 0

    if args.command == "find-map":
        found = find_map(args.project_dir)
        if not found:
            print(f"no map under {args.project_dir}/.gradle/yoriwake", file=sys.stderr)
            return 1
        newest = found[0]
        # The map file, not its directory: the workflow greps what this prints, and grepping a
        # directory fails the job under `set -eu`.
        print(os.path.join(newest, MAP_FILE))
        print(f"map has {records(newest)} records", file=sys.stderr)
        return 0

    if args.command == "mutate":
        edit(args.source, *mutation_for(args.source))
        print(f"mutated {args.source}", file=sys.stderr)
        return 0

    if args.command == "restore":
        old, new = mutation_for(args.source)
        edit(args.source, new, old)
        print(f"restored {args.source}", file=sys.stderr)
        return 0

    if args.command == "discard":
        for path in discard_results(args.project_dir, drop_map=args.map):
            print(f"discarded {path}", file=sys.stderr)
        return 0

    return 2


if __name__ == "__main__":
    sys.exit(main())
