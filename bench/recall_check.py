"""
Replays mutations against a real build and checks the selector never skips a test that fails.

The measurement this project keeps needing and keeps getting wrong is not "how many tests did we
skip" but "did we skip one that mattered". Those look identical in a summary: both produce a small
number and a green build. The only way to tell them apart is to break something on purpose, find out
from a full run which tests genuinely fail, and check every one of them was selected.

Ground truth comes from running the whole suite. It cannot come from the coverage map, because the
map is the thing under test.

Usage:
    python bench/recall_check.py <repo> <module> <out.json> [--limit N]
"""

import argparse
import glob
import json
import os
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import checkout as yoriwake_checkout  # noqa: E402  (the one place the checkout is resolved)
INIT_SCRIPT = os.path.join(yoriwake_checkout.ROOT, "scripts", "yoriwake.init.gradle.kts")

# Set from --module in main(); used to find the test task's outcome line in a Gradle log.
MODULE_TASK = ""

# Single-expression Boolean functions, whose body can be replaced with a constant that still
# compiles. Narrow on purpose: a mutation that fails to compile teaches nothing, and a mutation
# whose meaning is unclear makes the result unclear too.
# `inline` belongs in this list, and its absence was not cosmetic. An inline function's body is
# copied into every call site, so the code that actually runs lives in the CALLER's bytecode -- which
# is why Recordability refuses every Kotlin class, and why a wrongly relaxed rule would skip the one
# test that fails. With `inline` unmatched here, no mutation could ever create that shape, so the
# corpus could not reach the hazard even by chance. Modifiers are matched as a repeated group rather
# than a fixed prefix, so `internal inline fun` and `private inline fun` are reached too.
BOOLEAN_FUN = re.compile(
    r"^(?P<head>(?:(?:public|internal|private|inline)\s+)*fun [^\n=]*?: Boolean =)(?P<body>.*)$",
    re.MULTILINE,
)


# Kotlin functions with a BLOCK body, mutated the way Java's are: inject an early return rather than
# rewrite anything. `= expr` is only one of the two ways to write a Kotlin function, and until this
# existed the corpus could reach neither of detekt-rules-style's two inline functions -- one returns
# a Sequence, the other is `inline fun ... : Boolean {`, and BOOLEAN_FUN requires both Boolean AND a
# single expression. So the shape that motivated the whole inline investigation was unmutatable on
# every real Kotlin project in the corpus.
#
# Indented on purpose: a block-bodied function at column zero is rare, and matching members inside a
# class is the point.
#
# BRACED, for the same reason the Java injection is, and CHECKED rather than assumed. Kotlin has no
# MissingBraces equivalent in the compiler, so this is not the defect that cost opentelemetry-java
# three rows -- but ktlint's `statement-wrapping` and detekt's `BracesOnIfStatements` both flag the
# braceless form, and either can be wired into a guest's build. The residual hazard braces do NOT
# close is `allWarningsAsErrors`: `if (true)` makes the rest of the body unreachable, which Kotlin
# warns about, and a guest that promotes warnings would reject this however it is spelled. No corpus
# project has hit that yet; if one does, the symptom is a `build-failed` row, exactly as here.
KOTLIN_BLOCK_FUN = re.compile(
    r"^(?P<indent>[ \t]*)(?:(?:public|internal|private|protected|override|open|final|inline|"
    r"suspend|operator|infix|tailrec)\s+)*fun\s+(?:<[^>]*>\s*)?"
    r"(?:[\w.<>\[\], ?]+\.)?(?P<name>\w+)\s*\([^)]*\)\s*:\s*Boolean\s*\{",
    re.MULTILINE,
)


# Java methods returning a value, mutated by injecting an early return rather than rewriting the
# body. `if (true) return X;` compiles wherever the original return did and leaves the rest of the
# method intact.
#
# IT COULD STILL FAIL TO COMPILE, AND DID -- FIXED HERE. The braceless form trips Error
# Prone's MissingBraces on any project that enforces Google style with -Werror: opentelemetry-java
# lost THREE OF ITS EIGHT ROWS to it, all filed `build-failed`, which `assert_recall.py` then exits 1
# on. The log says it outright, down to the suggestion:
#
#     SdkSpan.java:276: warning: [MissingBraces] The Google Java Style Guide requires braces ...
#       if (true) return false;
#     Did you mean 'if (true) {return false;}'?
#
# AND ONE-LINE BRACES TRADED THAT GUEST'S GATE FOR ANOTHER'S. `if (true) { return X; }`
# satisfies MissingBraces and violates kafka's Checkstyle:
#
#     Utils.java:1372:19: '{' at column 19 should have line break after. [LeftCurly]
#
# which failed `:clients:checkstyleMain` and cost kafka EIGHT OF ITS TWENTY-ONE ROWS -- more than the
# three the braces recovered. Two guests with directly contradictory style gates, and the form that
# satisfies both is the conventional one:
#
#     if (true) {
#         return X;
#     }
#
# The lesson generalises past these two: the injection is read by whatever quality gates the guest
# runs before its tests, so it has to look like code a human would have written, not like the
# smallest edit that compiles.
JAVA_METHOD = re.compile(
    r"^(?P<indent>[ \t]+)(?:public|protected|private)\s+(?:static\s+)?"
    r"(?P<type>boolean|int|long|String)\s+(?P<name>\w+)\s*\([^)]*\)\s*"
    r"(?:throws [\w, .]+)?\{",
    re.MULTILINE,
)

JAVA_SENTINEL = {
    "boolean": "false",
    "int": "-1",
    "long": "-1L",
    "String": '"yoriwake-mutated"',
}


def targets_in(path):
    """How many mutable declarations this file has, in whichever language it is written in.

    Kotlin counts both forms. A file may declare single-expression Boolean functions, block-bodied
    ones, or both, and counting only the first meant `inline fun ...: Boolean {` -- the exact shape
    behind the inline-selection defect -- was invisible to the corpus on every real project.
    """
    with open(path, encoding="utf-8") as handle:
        text = handle.read()
    if path.endswith(".java"):
        return len(JAVA_METHOD.findall(text))
    return len(BOOLEAN_FUN.findall(text)) + len(KOTLIN_BLOCK_FUN.findall(text))


def mutate_java(path, index):
    """Injects an early return into the index-th value-returning method."""
    with open(path, encoding="utf-8") as handle:
        text = handle.read()
    matches = list(JAVA_METHOD.finditer(text))
    if index >= len(matches):
        return None
    match = matches[index]
    sentinel = JAVA_SENTINEL[match.group("type")]
    indent = match.group("indent")
    # A PROPERLY FORMATTED BLOCK, because one-line braces satisfy one guest's gate and violate
    # another's. See the comment above JAVA_METHOD for the two that disagreed.
    injection = ("\n" + indent + "    if (true) {"
                 + "\n" + indent + "        return " + sentinel + ";"
                 + "\n" + indent + "    }")
    mutated = text[:match.end()] + injection + text[match.end():]
    with open(path, "w", encoding="utf-8", newline="") as handle:
        handle.write(mutated)
    return match.group("name")


def mutate_kotlin_block(path, text, index):
    """Injects an early return into the index-th block-bodied Boolean function.

    The counterpart of [mutate_java], and it exists for one shape in particular: detekt's
    `inline fun ... isGuardClause(): Boolean {`. An inline body is copied into its call sites, which
    is the dependency coverage cannot see and the one this project measured itself missing. Until
    this function existed no mutation could touch it, because the only Kotlin mutation required a
    single-expression body.
    """
    matches = list(KOTLIN_BLOCK_FUN.finditer(text))
    if index >= len(matches):
        return None
    match = matches[index]
    indent = match.group("indent")
    injection = ("\n" + indent + "    if (true) {"
                 + "\n" + indent + "        return false"
                 + "\n" + indent + "    }")
    mutated = text[:match.end()] + injection + text[match.end():]
    with open(path, "w", encoding="utf-8", newline="") as handle:
        handle.write(mutated)
    return match.group("name")


def mutate(path, index):
    """Mutates the index-th declaration in a file, dispatching on its language.

    Kotlin: replaces a single-expression Boolean function body with `false`, or injects an early
    return into a block-bodied one once the single-expression declarations are exhausted.
    Java: injects an early return into a value-returning method (see mutate_java).
    """
    if path.endswith(".java"):
        return mutate_java(path, index)

    with open(path, encoding="utf-8") as handle:
        text = handle.read()

    matches = list(BOOLEAN_FUN.finditer(text))
    if index >= len(matches):
        # Past the single-expression functions, the block-bodied ones. Ordered this way, and never
        # interleaved, because `targets_in` counts the two together and a sweep resumes by index:
        # reordering them would make a stored row point at a different declaration than the one it
        # measured.
        return mutate_kotlin_block(path, text, index - len(matches))
    match = matches[index]

    name_match = re.search(r"fun (?:[\w.<>\[\]]+\.)?(\w+)\s*\(", match.group("head"))
    name = name_match.group(1) if name_match else "?"

    inline = match.group("body").strip()
    if inline:
        # `fun f(): Boolean = expr`
        start, end = match.start("body"), match.end("body")
        mutated = text[:start] + " false" + text[end:]
    else:
        # `fun f(): Boolean =` followed by an indented expression over one or more lines.
        rest = text[match.end():]
        lines = rest.split("\n")
        consumed = 0
        for line in lines[1:]:
            consumed += 1
            if not line.strip():
                break
            if not line.startswith((" ", "\t")):
                consumed -= 1
                break
        body_end = match.end() + len("\n".join(lines[:consumed + 1]))
        mutated = text[:match.end()] + "\n    false" + text[body_end:]

    with open(path, "w", encoding="utf-8", newline="") as handle:
        handle.write(mutated)
    return name


def wrapper(repo):
    """The Gradle wrapper for this platform.

    `./gradlew` is a shell script; handing it to CreateProcess on Windows fails with WinError 193
    rather than falling back to the batch file.
    """
    batch = os.path.join(repo, "gradlew.bat")
    return batch if os.name == "nt" and os.path.isfile(batch) else os.path.join(repo, "gradlew")


def gradle(repo, args, log_path):
    started = time.time()
    with open(log_path, "w", encoding="utf-8", errors="replace") as log:
        process = subprocess.run(
            [wrapper(repo)] + args + ["--console=plain"],
            cwd=repo, stdout=log, stderr=subprocess.STDOUT, shell=False,
        )
    return process.returncode, round(time.time() - started, 1)


def test_task_ran(log_path):
    """Whether Gradle actually executed the test task, however few tests it then ran.

    Distinguishes "did not build" from "built, ran, and selected nothing" -- the second is the
    failure this harness exists to catch, and treating it as a build error files it as a hiccup.
    """
    try:
        with open(log_path, encoding="utf-8", errors="replace") as handle:
            log = handle.read()
    except OSError:
        return False
    marker = f":{MODULE_TASK}"
    for line in log.splitlines():
        if line.startswith("> Task ") and line.rstrip().endswith(marker):
            return "SKIPPED" not in line and "FROM-CACHE" not in line
    return False


def results(repo, module, since):
    """(every test that ran, every test that failed), as a set of (class, name).

    Distinct keys, not invocations: a parameterised test reports the same class and name once per
    parameter, so these counts run below Gradle's own. That is fine for the comparison this harness
    makes -- selection decides a whole template at once, so an invocation runs exactly when its
    template was selected -- but the counts must not be read as test counts, hence the field names.
    """
    ran, failed = set(), set()
    pattern = os.path.join(repo, module, "build", "test-results", "test", "*.xml")
    fresh = [p for p in glob.glob(pattern) if os.path.getmtime(p) >= since]
    if not fresh:
        # Two very different things produce no fresh result files, and collapsing them hides the
        # worst outcome this harness exists to find:
        #
        #   - the build never reached the tests (a mutation that does not compile), which teaches
        #     nothing and must be excluded from the results;
        #   - the test task ran and selection deselected EVERY test, which is the catastrophic
        #     silent skip and must be compared against ground truth like any other run.
        #
        # The caller distinguishes them by whether the test task actually executed; reading the
        # directory regardless is separately wrong, because it compares against whatever the
        # previous target left behind (how one mutation once reported a 2,259-test run it never
        # performed).
        return None, None
    for path in fresh:
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        for case in root.iter("testcase"):
            key = (case.get("classname"), case.get("name"))
            ran.add(key)
            if case.find("failure") is not None or case.find("error") is not None:
                failed.add(key)
    return ran, failed


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("repo")
    parser.add_argument("module")
    parser.add_argument("out")
    parser.add_argument("--limit", type=int, default=100)
    parser.add_argument("--map-backup", required=True)
    parser.add_argument("--map-dir", required=True)
    parser.add_argument("--scratch", required=True)
    parser.add_argument("--sources", required=True,
                        help="comma-separated module directories to mutate")
    parser.add_argument("--path-filter", default="",
                        help="only mutate files whose path contains this")
    args = parser.parse_args()

    global MODULE_TASK
    MODULE_TASK = f"{args.module}:test"

    targets = []
    for source_root in args.sources.split(","):
        for language in ("kotlin/**/*.kt", "java/**/*.java"):
            pattern = os.path.join(args.repo, source_root, "src/main", language)
            for path in sorted(glob.glob(pattern, recursive=True)):
                if args.path_filter and args.path_filter not in path.replace(chr(92), "/"):
                    continue
                for i in range(targets_in(path)):
                    targets.append((os.path.relpath(path, args.repo).replace("\\", "/"), i))

    # Resume from whatever a previous run finished. Each target costs a full suite run, so losing
    # the lot to an interrupted sweep is the difference between a measurement that gets made and one
    # that keeps not getting made.
    findings = []
    if os.path.isfile(args.out):
        with open(args.out, encoding="utf-8") as handle:
            findings = json.load(handle)
    done = {finding["target"] for finding in findings}

    for rel_path, index in targets[:args.limit]:
        subprocess.run(["git", "checkout", "--", "."], cwd=args.repo, check=True)
        absolute = os.path.join(args.repo, rel_path)
        name = mutate(absolute, index)
        if name is None:
            continue

        # Keyed by the full path: two files sharing a basename in different packages would
        # otherwise collide, and a resumed sweep would skip a mutation it never measured.
        label = f"{rel_path}::{name}"
        if label in done:
            print(f"--- {label} (already done)", flush=True)
            continue
        print(f"--- {label}", flush=True)

        # Selected first: it is the cheap one, and if it cannot even build there is no point
        # paying for ground truth.
        shutil.copyfile(args.map_backup, os.path.join(args.map_dir, "coverage.tsv"))
        selected_log = os.path.join(args.scratch, "recall-selected.log")
        started = time.time()
        code, selected_seconds = gradle(
            args.repo,
            ["-I", INIT_SCRIPT, yoriwake_checkout.init_script_version_flag(), "-PenablePTS=false", f":{args.module}:test", "-Pyoriwake.select"],
            selected_log,
        )
        selected_ran, selected_failed = results(args.repo, args.module, started)
        if selected_ran is None and test_task_ran(selected_log):
            # It built and ran; selection simply chose nothing. That is the case worth measuring.
            selected_ran, selected_failed = set(), set()
        if selected_ran is None:
            print(f"    skipped: the selected run never reached the tests (exit {code})", flush=True)
            findings.append({"target": label, "status": "did-not-build"})
            with open(args.out, "w", encoding="utf-8") as handle:
                json.dump(findings, handle, indent=2)
            continue

        full_log = os.path.join(args.scratch, "recall-full.log")
        full_started = time.time()
        _, full_seconds = gradle(
            args.repo, ["-PenablePTS=false", f":{args.module}:test"], full_log
        )
        full_ran, full_failed = results(args.repo, args.module, full_started)
        if full_ran is None:
            print("    skipped: the ground-truth run never reached the tests", flush=True)
            findings.append({"target": label, "status": "did-not-build"})
            with open(args.out, "w", encoding="utf-8") as handle:
                json.dump(findings, handle, indent=2)
            continue

        missed = sorted(full_failed - selected_ran)
        findings.append({
            "target": label,
            "status": "checked",
            "full_distinct": len(full_ran),
            "full_failures": len(full_failed),
            "selected_distinct": len(selected_ran),
            "reproduced": len(full_failed & selected_ran),
            "missed": [f"{c}::{n}" for c, n in missed],
            "selected_seconds": selected_seconds,
            "full_seconds": full_seconds,
        })
        verdict = "MISSED " + str(len(missed)) if missed else "ok"
        print(f"    {len(full_failed)} fail, selected {len(selected_ran)}/{len(full_ran)} -> {verdict}",
              flush=True)

        with open(args.out, "w", encoding="utf-8") as handle:
            json.dump(findings, handle, indent=2)

    subprocess.run(["git", "checkout", "--", "."], cwd=args.repo, check=True)
    print("done", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
