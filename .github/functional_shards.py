"""Checks that a functional suite run as shards ran every test class exactly once.

A pull request runs each functional suite as several jobs, each the test classes whose top-level
name hashes to its shard (`-Pyoriwake.functional.shard=<index>/<count>`). A shard that filters too
much, one that never runs, or results that go missing would leave a class tested nowhere while every
job is green, so this check compares two things the shard filter does not produce:

- the candidates: the top-level classes in the functional test sources that declare a test, minus
  the break-attempt package, which runs as its own suite;
- what ran: the class of every testcase in each shard's JUnit XML.

It fails on a candidate that ran in no shard or in more than one, a class that ran but is no
candidate (a test shape the source scan does not know), and a shard whose results are missing.

    python .github/functional_shards.py candidates
    python .github/functional_shards.py check <shards-dir> --count N

`<shards-dir>` holds one directory per shard, named `...-<index>-of-<count>`, each with that
shard's XML files.
"""

import argparse
import glob
import os
import re
import sys
import xml.etree.ElementTree as ElementTree

HERE = os.path.dirname(os.path.abspath(__file__))
SOURCES = os.path.join(os.path.dirname(HERE), "plugin", "src", "functionalTest", "kotlin")
SEPARATE_PACKAGE = "io.github.zeuspizza.yoriwake.gradle.breaking"

TEST_ANNOTATION = re.compile(r"^\s*@(?:Test|ParameterizedTest|TestFactory|RepeatedTest|TestTemplate)\b", re.M)
PACKAGE = re.compile(r"^package\s+([\w.]+)", re.M)
# A class at the start of a line: a top-level one. Fixture sources inside string literals are
# indented, so they never match.
TOP_LEVEL_CLASS = re.compile(r"^((?:(?:internal|public|open|abstract|data|sealed)\s+)*)class\s+(\w+)", re.M)
SHARD_DIR = re.compile(r"-(\d+)-of-(\d+)$")


def candidates(sources=SOURCES):
    """Every top-level, non-abstract class of a source file that declares a test."""
    found = set()
    for path in glob.glob(os.path.join(sources, "**", "*.kt"), recursive=True):
        with open(path, encoding="utf-8") as handle:
            text = handle.read()
        if not TEST_ANNOTATION.search(text):
            continue
        package = PACKAGE.search(text)
        package = package.group(1) if package else ""
        if package == SEPARATE_PACKAGE or package.startswith(SEPARATE_PACKAGE + "."):
            continue
        for modifiers, name in TOP_LEVEL_CLASS.findall(text):
            if "abstract" not in modifiers.split():
                found.add(f"{package}.{name}" if package else name)
    return found


def ran_in(shard_dir):
    """The top-level class of every testcase in a shard's XML, with the shard's test time."""
    classes, seconds = set(), 0.0
    for path in glob.glob(os.path.join(shard_dir, "**", "*.xml"), recursive=True):
        root = ElementTree.parse(path).getroot()
        for case in root.iter("testcase"):
            classes.add((case.get("classname") or "").split("$", 1)[0])
            seconds += float(case.get("time") or 0)
    classes.discard("")
    return classes, seconds


def check(shards_dir, count, sources=SOURCES):
    """Problems with the shards under `shards_dir`, as lines; empty when they partition the suite."""
    problems = []
    shards = {}
    for entry in sorted(os.listdir(shards_dir)):
        match = SHARD_DIR.search(entry)
        if not match or not os.path.isdir(os.path.join(shards_dir, entry)):
            continue
        index, of = int(match.group(1)), int(match.group(2))
        if of != count:
            problems.append(f"{entry} is a shard of {of}, not of {count}")
            continue
        shards[index] = ran_in(os.path.join(shards_dir, entry))
    for index in range(1, count + 1):
        if index not in shards:
            problems.append(f"shard {index} of {count} left no results")
    expected = candidates(sources)
    if not expected:
        problems.append(f"no test class found under {sources}; the check would prove nothing")
    where = {}
    for index, (classes, seconds) in sorted(shards.items()):
        print(f"shard {index}/{count}: {len(classes)} classes, {seconds / 60:.1f} min of tests")
        for name in classes:
            where.setdefault(name, []).append(index)
    for name in sorted(expected):
        if name not in where:
            problems.append(f"{name} ran in no shard")
        elif len(where[name]) > 1:
            problems.append(f"{name} ran in shards {where[name]}")
    for name in sorted(set(where) - expected):
        problems.append(f"{name} ran but is no test class the sources declare; the source scan missed it")
    return problems


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("candidates", help="print the test classes the sources declare")
    run = sub.add_parser("check", help="check that the shards ran every test class exactly once")
    run.add_argument("shards_dir")
    run.add_argument("--count", type=int, required=True)
    args = parser.parse_args(argv)
    if args.command == "candidates":
        for name in sorted(candidates()):
            print(name)
        return 0
    problems = check(args.shards_dir, args.count)
    for problem in problems:
        print(f"FAIL: {problem}", file=sys.stderr)
    if not problems:
        print(f"OK: every test class ran in exactly one of {args.count} shards")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
