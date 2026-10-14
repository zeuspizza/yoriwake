"""Checks a supported-matrix run, and that the reference's table and the workflow's cells agree.

A cell is one job of `matrix.yml`: an operating system, a Gradle version and a daemon JDK, labelled
`<os>/<gradle>/<jdk>`. A row of the reference's supported matrix names the cells that prove it and
the tests that do. A row is only as good as the run behind it, so a green job is not enough:

- the cell ran its label's Gradle and JDK, as `MatrixCellIdentityTest` observed them, and results
  from one functional task only;
- it ran the same tests as the reference cell, none failed, and none skipped unless
  `matrix-skips.txt` names the test, the cells and the reason;
- every row's proving tests passed in every cell the row names;
- every job ran the candidate commit.

`table` needs no run and is called from `test_ci.py` on every pull request:

    python .github/matrix_check.py table
    python .github/matrix_check.py results <artifacts-dir> --candidate <sha>
"""

import argparse
import fnmatch
import os
import re
import sys
import xml.etree.ElementTree as ElementTree

HERE = os.path.dirname(os.path.abspath(__file__))
WORKFLOW = os.path.join(HERE, "workflows", "matrix.yml")
REFERENCE = os.path.join(os.path.dirname(HERE), "docs", "reference.md")
SKIPS = os.path.join(HERE, "matrix-skips.txt")

TABLE_HEADING = "### Supported matrix"
REFERENCE_CELL = "linux/9.8.0/21"
IDENTITY_TEST = "MatrixCellIdentityTest.the builds run on the Gradle and daemon JDK this cell asks for"

CELL_LINE = re.compile(
    r"^\s*-\s*\{\s*os:\s*(\w+),\s*runner:\s*([\w.-]+),\s*gradle:\s*'([\d.]+)',\s*task:\s*\w+,"
    r"\s*jdk:\s*'(\d+)'\s*\}\s*$"
)
# What a cell's runner must start with for its OS label to be true: a label is the claim a row rests
# on, and nothing the cell runs reports the operating system.
RUNNERS = {"linux": "ubuntu-", "macos": "macos-", "windows": "windows-"}

# The axes a cell is made of, with the label field each one is. A row on one of these names exactly
# the cells with its value, so the table cannot claim a version the workflow does not run.
CELL_AXES = {"OS": 0, "Gradle": 1, "Daemon JDK": 2}
OTHER_AXES = {"Test JVM JDK", "Engine", "Host"}
OS_NAMES = {"Linux": "linux", "macOS": "macos", "Windows": "windows"}


def cells(workflow_text):
    """Every cell the workflow runs, as `<os>/<gradle>/<jdk>` labels in file order.

    A cell line this cannot read, or whose runner is not its label's OS, stops the check: a cell
    left out would be checked by nothing, and a mislabelled one would prove another OS's row.
    """
    labels = []
    for line in workflow_text.splitlines():
        if not re.match(r"^\s*-\s*\{.*\bgradle:", line):
            continue
        match = CELL_LINE.match(line)
        if not match:
            raise SystemExit(f"matrix.yml: cannot read the cell line {line.strip()!r}")
        os_name, runner, gradle, jdk = match.groups()
        if not runner.startswith(RUNNERS.get(os_name, "\0")):
            raise SystemExit(f"matrix.yml: the cell labelled {os_name} runs on {runner}")
        labels.append(f"{os_name}/{gradle}/{jdk}")
    return labels


def table_rows(reference_text):
    """The supported matrix as (axis, value, cell patterns, proving tests), one per table row."""
    lines = reference_text.splitlines()
    if TABLE_HEADING not in lines:
        return []
    rows = []
    for line in lines[lines.index(TABLE_HEADING) + 1:]:
        if line.startswith("#"):
            break
        if not line.startswith("|"):
            continue
        columns = [column.strip() for column in line.strip().strip("|").split("|")]
        if columns[0] == "Axis" or set(columns[0]) <= set("-: "):
            continue
        # Still rendered as a row, so it must not drop out of the check.
        if len(columns) != 4:
            rows.append(("malformed", line.strip(), [], []))
            continue
        axis, value, named, proved = columns
        patterns = ["*/*/*" if part.strip() == "all" else part.strip().strip("`") for part in named.split(",")]
        proofs = re.findall(r"`([^`]+)`", proved)
        rows.append((axis, value, patterns, proofs))
    return rows


def matched(patterns, labels):
    return [label for label in labels if any(fnmatch.fnmatchcase(label, pattern) for pattern in patterns)]


def table_problems(labels, rows):
    """Where the table and the cell list disagree, as messages; empty when they agree."""
    problems = []
    if not labels:
        problems.append("matrix.yml lists no cells")
    if not rows:
        problems.append(f"the reference has no rows under {TABLE_HEADING!r}")
    if labels and REFERENCE_CELL not in labels:
        problems.append(f"the reference cell {REFERENCE_CELL} is not in matrix.yml")
    seen = {axis: set() for axis in CELL_AXES}
    for axis, value, patterns, proofs in rows:
        row = f"row {axis} {value!r}"
        if axis == "malformed":
            problems.append(f"{value}: a table row needs four columns: axis, value, cells, proving tests")
            continue
        if axis not in CELL_AXES and axis not in OTHER_AXES:
            problems.append(f"{row}: unknown axis")
            continue
        if not proofs:
            problems.append(f"{row}: names no proving test")
        for pattern in patterns:
            if not matched([pattern], labels):
                problems.append(f"{row}: {pattern} matches no cell in matrix.yml")
        if axis in CELL_AXES:
            field = CELL_AXES[axis]
            key = value.split()[0].rstrip(",")
            key = OS_NAMES.get(key, key)
            seen[axis].add(key)
            expected = [label for label in labels if label.split("/")[field] == key]
            if not expected:
                problems.append(f"{row}: no cell in matrix.yml runs {key}")
            elif matched(patterns, labels) != expected:
                problems.append(f"{row}: names {matched(patterns, labels)}, but the cells running {key} are {expected}")
    for axis, field in CELL_AXES.items():
        for value in sorted({label.split("/")[field] for label in labels} - seen[axis]):
            problems.append(f"matrix.yml runs {axis} {value}, which the reference's table has no row for")
    return problems


def skip_allowlist(text):
    """(test id, cell pattern, reason) per line of `matrix-skips.txt`."""
    entries = []
    for line in text.splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        parts = [part.strip() for part in line.split("|")]
        if len(parts) != 3 or not all(parts):
            raise SystemExit(f"matrix-skips.txt: expected 'test id | cells | reason', got {line!r}")
        entries.append(tuple(parts))
    return entries


def test_id(classname, name):
    """`SimpleClass.test name`, without the parameter list JUnit appends."""
    return classname.rsplit(".", 1)[-1] + "." + re.sub(r"\([^()]*\)$", "", name)


def read_results(directory):
    """Every test case under a cell's results as {id: status}, and the suites' standard output."""
    statuses, output = {}, []
    for dirpath, _, filenames in os.walk(directory):
        for filename in sorted(filenames):
            if not filename.endswith(".xml"):
                continue
            suite = ElementTree.parse(os.path.join(dirpath, filename)).getroot()
            output.append(suite.findtext("system-out") or "")
            for case in suite.iter("testcase"):
                if case.find("failure") is not None or case.find("error") is not None:
                    status = "failed"
                elif case.find("skipped") is not None:
                    status = "skipped"
                else:
                    status = "passed"
                statuses[test_id(case.get("classname", ""), case.get("name", ""))] = status
    return statuses, "\n".join(output)


def proven_by(statuses, proof):
    """The cases a proving test names: one case, or every case of a class."""
    if proof in statuses:
        return {proof: statuses[proof]}
    return {case: status for case, status in statuses.items() if case.startswith(proof + ".")}


def cell_problems(label, directory, candidate, allowlist):
    """What is wrong with one cell's artifact; the statuses it holds, or None when unreadable."""
    problems = []
    sha_file = os.path.join(directory, "sha.txt")
    if not os.path.isfile(sha_file):
        return [f"{label}: no sha.txt, so which commit it ran is unknown"], None
    with open(sha_file, encoding="utf-8") as handle:
        sha = handle.read().strip()
    if sha != candidate:
        problems.append(f"{label}: ran {sha}, not the candidate {candidate}")
    tasks = sorted(entry for entry in os.listdir(directory) if os.path.isdir(os.path.join(directory, entry)))
    if len(tasks) != 1:
        return problems + [f"{label}: expected the results of one functional task, found {tasks}"], None
    statuses, output = read_results(os.path.join(directory, tasks[0]))
    if not statuses:
        return problems + [f"{label}: ran no tests"], None
    _, gradle, jdk = label.split("/")
    if statuses.get(IDENTITY_TEST) != "passed":
        problems.append(f"{label}: the identity test did not pass ({statuses.get(IDENTITY_TEST, 'absent')})")
    observed = re.findall(r"yoriwake-cell (gradle=\S+ jdk=\S+)", output)
    if observed != [f"gradle={gradle} jdk={jdk}"]:
        problems.append(f"{label}: the builds ran on {observed or 'nothing it reported'}, not gradle={gradle} jdk={jdk}")
    for case, status in sorted(statuses.items()):
        if status == "failed":
            problems.append(f"{label}: {case} failed")
        elif status == "skipped" and not any(
            entry_id == case and fnmatch.fnmatchcase(label, cells_pattern)
            for entry_id, cells_pattern, _ in allowlist
        ):
            problems.append(f"{label}: {case} skipped, and matrix-skips.txt does not allow it here")
    return problems, statuses


def results_problems(artifacts, candidate, labels, rows, allowlist):
    """Every problem with a run's artifacts, and one summary line per cell."""
    problems, lines, ran = [], [], {}
    for entry in sorted(os.listdir(artifacts)):
        sha_file = os.path.join(artifacts, entry, "sha.txt")
        if entry.startswith("sha-"):
            if not os.path.isfile(sha_file):
                problems.append(f"{entry}: no sha.txt")
                continue
            with open(sha_file, encoding="utf-8") as handle:
                sha = handle.read().strip()
            if sha != candidate:
                problems.append(f"{entry}: ran {sha}, not the candidate {candidate}")
    for label in labels:
        directory = os.path.join(artifacts, "cell-" + label.replace("/", "-"))
        if not os.path.isdir(directory):
            problems.append(f"{label}: no artifact; the cell did not run or did not upload")
            continue
        found, statuses = cell_problems(label, directory, candidate, allowlist)
        problems += found
        if statuses is not None:
            ran[label] = statuses
            skipped = sum(1 for status in statuses.values() if status == "skipped")
            lines.append(f"{label}: {len(statuses)} tests, {skipped} skipped, {'ok' if not found else 'FAILED'}")
    reference = ran.get(REFERENCE_CELL)
    if reference is None:
        problems.append(f"the reference cell {REFERENCE_CELL} has no results to compare with")
    else:
        for label, statuses in ran.items():
            missing = sorted(set(reference) - set(statuses))
            extra = sorted(set(statuses) - set(reference))
            if missing:
                problems.append(f"{label}: did not run {len(missing)} test(s) the reference cell ran: {missing[:5]}")
            if extra:
                problems.append(f"{label}: ran {len(extra)} test(s) the reference cell did not: {extra[:5]}")
    for axis, value, patterns, proofs in rows:
        for label in matched(patterns, labels):
            if label not in ran:
                continue
            for proof in proofs:
                cases = proven_by(ran[label], proof)
                if not cases:
                    problems.append(f"row {axis} {value!r}: {proof} did not run in {label}")
                elif any(status != "passed" for status in cases.values()):
                    problems.append(f"row {axis} {value!r}: {proof} did not pass in {label}")
    return problems, lines


def read(path):
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("table", help="check the reference's table against matrix.yml")
    results = sub.add_parser("results", help="check a run's downloaded artifacts")
    results.add_argument("artifacts")
    results.add_argument("--candidate", required=True)
    args = parser.parse_args(argv)

    labels = cells(read(WORKFLOW))
    rows = table_rows(read(REFERENCE))
    problems = table_problems(labels, rows)
    if args.command == "results":
        found, lines = results_problems(args.artifacts, args.candidate, labels, rows, skip_allowlist(read(SKIPS)))
        problems += found
        for line in lines:
            print(line)
    for problem in problems:
        print(f"FAIL: {problem}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
