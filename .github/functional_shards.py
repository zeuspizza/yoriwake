"""Checks that a functional suite run as shards ran every test class exactly once.

A pull request runs each functional suite as several jobs, each the test classes the shard table
puts in its shard, or whose top-level name hashes to it when the table has no entry
(`-Pyoriwake.functional.shard=<index>/<count>`). A shard that filters too
much, one that never runs, or results that go missing would leave a class tested nowhere while every
job is green, so this check compares two things the shard filter does not produce:

- the candidates: the top-level classes in the functional test sources that declare a test, minus
  the break-attempt package, which runs as its own suite;
- what ran: the class of every testcase in each shard's JUnit XML.

It fails on a candidate that ran in no shard or in more than one, a class that ran but is no
candidate (a test shape the source scan does not know), and a shard whose results are missing.

    python .github/functional_shards.py candidates [--module maven]
    python .github/functional_shards.py check <shards-dir> --count N [--module maven]
    python .github/functional_shards.py table <results-dir> --suite <module>/<os>/<task> --count N

`<shards-dir>` holds one directory per shard, named `...-<index>-of-<count>`, each with that
shard's XML files. `table` writes, into the shard table (.github/functional-shards.json), the
suite's classes assigned longest first to `N` shards by their test time in the XML under
`<results-dir>` (a run's downloaded shard artifacts of that suite, at any count), so the shards
take about equal time. The shard filters read the table; a class it lacks hashes.
"""

import argparse
import glob
import json
import os
import re
import sys
import xml.etree.ElementTree as ElementTree

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
# Per module, its functional test sources and the break-attempt package that runs as its own suite.
MODULES = {
    "plugin": (os.path.join(ROOT, "plugin", "src", "functionalTest", "kotlin"),
               "io.github.zeuspizza.yoriwake.gradle.breaking"),
    "maven": (os.path.join(ROOT, "maven", "src", "functionalTest", "kotlin"),
              "io.github.zeuspizza.yoriwake.maven.breaking"),
}
SOURCES, SEPARATE_PACKAGE = MODULES["plugin"]
TABLE = os.path.join(HERE, "functional-shards.json")

TEST_ANNOTATION = re.compile(r"^\s*@(?:Test|ParameterizedTest|TestFactory|RepeatedTest|TestTemplate)\b", re.M)
PACKAGE = re.compile(r"^package\s+([\w.]+)", re.M)
# A class at the start of a line: a top-level one. Fixture sources inside string literals are
# indented, so they never match.
TOP_LEVEL_CLASS = re.compile(r"^((?:(?:internal|public|open|abstract|data|sealed)\s+)*)class\s+(\w+)", re.M)
SHARD_DIR = re.compile(r"-(\d+)-of-(\d+)$")


def candidates(sources=SOURCES, separate_package=SEPARATE_PACKAGE):
    """Every top-level, non-abstract class of a source file that declares a test."""
    found = set()
    for path in glob.glob(os.path.join(sources, "**", "*.kt"), recursive=True):
        with open(path, encoding="utf-8") as handle:
            text = handle.read()
        if not TEST_ANNOTATION.search(text):
            continue
        package = PACKAGE.search(text)
        package = package.group(1) if package else ""
        if package == separate_package or package.startswith(separate_package + "."):
            continue
        for modifiers, name in TOP_LEVEL_CLASS.findall(text):
            if "abstract" not in modifiers.split():
                found.add(f"{package}.{name}" if package else name)
    return found


def ran_in(shard_dir):
    """The top-level class of every testcase in a shard's XML, the shard's test time, and how many
    of its testcases were skipped (a class whose tests all skip still counts as run, as unsharded)."""
    classes, seconds, skipped = set(), 0.0, 0
    for path in glob.glob(os.path.join(shard_dir, "**", "*.xml"), recursive=True):
        root = ElementTree.parse(path).getroot()
        for case in root.iter("testcase"):
            classes.add((case.get("classname") or "").split("$", 1)[0])
            seconds += float(case.get("time") or 0)
            skipped += case.find("skipped") is not None
    classes.discard("")
    return classes, seconds, skipped


def check(shards_dir, count, sources=SOURCES, separate_package=SEPARATE_PACKAGE):
    """Problems with the shards under `shards_dir`, as lines; empty when they partition the suite."""
    problems = []
    shards = {}
    # No directory at all, as when no artifact matched, is every shard missing, said as such.
    for entry in sorted(os.listdir(shards_dir)) if os.path.isdir(shards_dir) else []:
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
    expected = candidates(sources, separate_package)
    if not expected:
        problems.append(f"no test class found under {sources}; the check would prove nothing")
    where = {}
    for index, (classes, seconds, skipped) in sorted(shards.items()):
        print(f"shard {index}/{count}: {len(classes)} classes, {seconds / 60:.1f} min of tests, {skipped} skipped")
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


def class_times(results_dir):
    """Seconds of tests per top-level class in every JUnit XML under `results_dir`."""
    times = {}
    for path in glob.glob(os.path.join(results_dir, "**", "*.xml"), recursive=True):
        for case in ElementTree.parse(path).getroot().iter("testcase"):
            name = (case.get("classname") or "").split("$", 1)[0]
            if name:
                times[name] = times.get(name, 0.0) + float(case.get("time") or 0)
    return times


def assign(times, count):
    """Each class's shard, 1..count: longest first, each to the shard with the least time so far
    (the lowest index on a tie), so the same times always give the same table."""
    loads = [0.0] * count
    shards = {}
    for name, seconds in sorted(times.items(), key=lambda item: (-item[1], item[0])):
        index = loads.index(min(loads))
        loads[index] += seconds
        shards[name] = index + 1
    return shards


def table_problems(table):
    """What makes a shard table one the filters cannot use: each suite's counts map class names to
    a shard in 1..count."""
    problems = []
    if not isinstance(table, dict):
        return ["the table is no JSON object"]
    for suite, counts in table.items():
        if not re.fullmatch(r"(plugin|maven)/\w+/\w+", suite) or not isinstance(counts, dict):
            problems.append(f"{suite}: not a <module>/<os>/<task> suite with its counts")
            continue
        for count, shards in counts.items():
            if not count.isdigit() or int(count) < 1 or not isinstance(shards, dict) or not shards:
                problems.append(f"{suite}: {count} is no shard count with classes")
                continue
            for name, index in shards.items():
                if not isinstance(index, int) or not 1 <= index <= int(count):
                    problems.append(f"{suite}: {name} is in shard {index}, not one of 1..{count}")
    return problems


def write_table(results_dir, suite, count, path=TABLE):
    """Replaces the suite's entry for `count` in the table at `path` with the assignment of the
    times under `results_dir`; returns the classes per shard and their minutes."""
    times = class_times(results_dir)
    if not times:
        raise SystemExit(f"no test results under {results_dir}")
    table = {}
    if os.path.exists(path):
        with open(path, encoding="utf-8") as handle:
            table = json.load(handle)
    shards = assign(times, count)
    table.setdefault(suite, {})[str(count)] = dict(sorted(shards.items()))
    problems = table_problems(table)
    if problems:
        raise SystemExit("\n".join(problems))
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(table, handle, indent=1, sort_keys=True)
        handle.write("\n")
    return {index: sum(times[n] for n, i in shards.items() if i == index) / 60 for index in range(1, count + 1)}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    listing = sub.add_parser("candidates", help="print the test classes the sources declare")
    listing.add_argument("--module", choices=sorted(MODULES), default="plugin")
    run = sub.add_parser("check", help="check that the shards ran every test class exactly once")
    run.add_argument("shards_dir")
    run.add_argument("--count", type=int, required=True)
    run.add_argument("--module", choices=sorted(MODULES), default="plugin")
    table = sub.add_parser("table", help="write a suite's shards for a count into the shard table")
    table.add_argument("results_dir")
    table.add_argument("--suite", required=True, help="<module>/<os>/<task>, as plugin/linux/functionalTest")
    table.add_argument("--count", type=int, required=True)
    args = parser.parse_args(argv)
    if args.command == "candidates":
        for name in sorted(candidates(*MODULES[args.module])):
            print(name)
        return 0
    if args.command == "table":
        for index, minutes in write_table(args.results_dir, args.suite, args.count).items():
            print(f"shard {index}/{args.count}: {minutes:.1f} min of tests")
        return 0
    problems = check(args.shards_dir, args.count, *MODULES[args.module])
    for problem in problems:
        print(f"FAIL: {problem}", file=sys.stderr)
    if not problems:
        print(f"OK: every test class ran in exactly one of {args.count} shards")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
