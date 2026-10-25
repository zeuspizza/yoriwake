"""Writes one summary of a workflow run: each job's result, the tests or guards each suite ran, and
what failed.

A run of many jobs otherwise leaves a reader to open each one to learn what ran and what broke. The
last job of `verify.yml`, `guards.yml` and `matrix.yml` calls this with three things:

- the run's job results: the `needs` context as JSON, in the `NEEDS` environment variable;
- the workflow file, for each job's name and the order to list them in;
- a directory of the run's downloaded artifacts, one directory per artifact, each read by content:
  an XML file whose root is a `testsuite` is JUnit results, a JSON file with a `guards` list is a
  guard shard's results (`prove_guards.py --results`).

An artifact named `...-<index>-of-<count>` is one shard of the suite its name starts with; a suite
whose shards left no results says so. The summary reports and never judges: it exits 0 whatever the
run's result, and a file it cannot read is a line in the summary, so a red run gets no second red
job. It is appended to `$GITHUB_STEP_SUMMARY`, or printed when that is unset:

    NEEDS="$(cat needs.json)" python .github/run_summary.py <workflow.yml> <artifacts-dir>
"""

import argparse
import json
import os
import re
import sys
import xml.etree.ElementTree as ElementTree

SHARD = re.compile(r"^(.+)-(\d+)-of-(\d+)$")
EXPRESSION = re.compile(r"\$\{\{.*?\}\}")
# Enough to name what broke; the rest is a click away in the run.
DETAIL_LINES = 20
MESSAGE_LENGTH = 160


def workflow_jobs(text):
    """The workflow's name and its jobs as (id, name) in file order; a matrix expression is `*`."""
    title = re.search(r"^name:\s*(.+?)\s*$", text, re.M)
    jobs = []
    section = text.split("\njobs:\n", 1)[1] if "\njobs:\n" in text else ""
    for block in re.split(r"\n(?=  [\w-]+:\s*\n)", "\n" + section):
        header = re.match(r"\n?  ([\w-]+):\s*\n", block)
        if not header:
            continue
        named = re.search(r"^    name:\s*(.+?)\s*$", block, re.M)
        name = EXPRESSION.sub("*", named.group(1).strip("'\"")) if named else header.group(1)
        jobs.append((header.group(1), name))
    return (title.group(1).strip("'\"") if title else "workflow"), jobs


def first_line(text):
    line = (text or "").strip().split("\n", 1)[0].strip()
    return line if len(line) <= MESSAGE_LENGTH else line[:MESSAGE_LENGTH - 3] + "..."


def read_junit(root, suite, found):
    """Adds a JUnit file's cases to `found[suite]`; returns its failing cases as lines."""
    failing = []
    for element in root.iter("testsuite"):
        for case in element.iter("testcase"):
            found["tests"] += 1
            problem = case.find("failure")
            problem = problem if problem is not None else case.find("error")
            if problem is not None:
                found["failed"] += 1
                name = (case.get("classname") or "").rsplit(".", 1)[-1] + "." + (case.get("name") or "")
                message = first_line(problem.get("message") or problem.text)
                failing.append(f"{suite}: {name}" + (f": {message}" if message else ""))
            elif case.find("skipped") is not None:
                found["skipped"] += 1
    return failing


def read_artifacts(directory):
    """Per suite its shards and counts, per guard results file its outcomes, and the detail lines:
    files not read, shards that left no results, failing cases, guards that did not fire."""
    suites, guards, unread, failing = {}, {}, [], []
    entries = sorted(os.listdir(directory)) if os.path.isdir(directory) else []
    for entry in entries:
        path = os.path.join(directory, entry)
        if not os.path.isdir(path):
            continue
        match = SHARD.match(entry)
        group, index, count = (match.group(1), int(match.group(2)), int(match.group(3))) if match else (entry, 1, 1)
        for dirpath, _, filenames in sorted(os.walk(path)):
            for filename in sorted(filenames):
                file = os.path.join(dirpath, filename)
                where = os.path.relpath(file, directory)
                if filename.endswith(".xml"):
                    try:
                        root = ElementTree.parse(file).getroot()
                    except (ElementTree.ParseError, OSError) as error:
                        unread.append(f"{where}: not read ({error})")
                        continue
                    if root.tag not in ("testsuite", "testsuites"):
                        continue
                    suite = suites.setdefault(group, {"count": count, "shards": set(),
                                                      "tests": 0, "failed": 0, "skipped": 0})
                    suite["shards"].add(index)
                    failing += read_junit(root, group, suite)
                elif filename.endswith(".json"):
                    try:
                        with open(file, encoding="utf-8") as handle:
                            result = json.load(handle)
                    except (ValueError, OSError) as error:
                        unread.append(f"{where}: not read ({error})")
                        continue
                    if not isinstance(result, dict) or not isinstance(result.get("guards"), list):
                        continue
                    key = f"{group}: {os.path.splitext(filename)[0]}"
                    selection = guards.setdefault(key, {"count": count, "shards": set(), "suites": {}})
                    selection["shards"].add(index)
                    if result.get("refused"):
                        failing.append(f"{key}, shard {index}/{count} refused: {first_line(str(result['refused']))}")
                    for guard in result["guards"]:
                        counts = selection["suites"].setdefault(guard.get("suite") or "?", {"fired": 0, "other": 0})
                        if guard.get("outcome") == "fired":
                            counts["fired"] += 1
                        else:
                            counts["other"] += 1
                            detail = first_line(guard.get("detail"))
                            failing.append(f"{key}, shard {index}/{count}: {guard.get('label')}: "
                                           f"{guard.get('outcome')}" + (f" ({detail})" if detail else ""))
    missing = []
    for name, found in list(suites.items()) + list(guards.items()):
        for index in range(1, found["count"] + 1):
            if index not in found["shards"]:
                missing.append(f"{name}: shard {index} of {found['count']} left no results")
    return suites, guards, unread + missing + failing


def cell(text):
    """Table-safe: a message is the test's own text, so it may hold markup or a pipe."""
    return str(text).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("|", "\\|")


def overall(results):
    for result in ("failure", "cancelled"):
        if result in results:
            return result
    return "success"


def summary(workflow_text, needs, artifacts, run_url=None):
    """The run's summary as Markdown."""
    title, jobs = workflow_jobs(workflow_text)
    names = dict(jobs)
    order = [job for job, _ in jobs if job in needs] + sorted(job for job in needs if job not in names)
    results = {job: (needs[job] or {}).get("result", "unknown") for job in order}
    suites, guards, detail = read_artifacts(artifacts)

    lines = [f"## {cell(title)}: {overall(results.values())}", ""]
    if run_url:
        lines += [f"[The run]({run_url})", ""]
    lines += ["| job | result |", "| --- | --- |"]
    lines += [f"| {cell(names.get(job, job))} | {results[job]} |" for job in order]
    lines.append("")
    if suites:
        lines += ["| suite | shards | tests | failed | skipped |", "| --- | --- | --- | --- | --- |"]
        for name, found in suites.items():
            lines.append(f"| {cell(name)} | {len(found['shards'])}/{found['count']} | {found['tests']} "
                         f"| {found['failed']} | {found['skipped']} |")
        lines.append("")
    if guards:
        lines += ["| guards | suite | shards | fired | did not fire |", "| --- | --- | --- | --- | --- |"]
        for name, found in guards.items():
            shards = f"{len(found['shards'])}/{found['count']}"
            for suite, counts in sorted(found["suites"].items()) or [("none selected", {"fired": 0, "other": 0})]:
                lines.append(f"| {cell(name)} | {cell(suite)} | {shards} | {counts['fired']} | {counts['other']} |")
        lines.append("")
    if not suites and not guards:
        lines += ["No test or guard results among the run's artifacts.", ""]

    failed_jobs = [job for job in order if results[job] in ("failure", "cancelled")]
    if failed_jobs or detail:
        lines += ["### What failed", ""]
        lines += [f"- job {cell(names.get(job, job))}: {results[job]}" for job in failed_jobs]
        lines += [f"- {cell(line)}" for line in detail[:DETAIL_LINES]]
        if len(detail) > DETAIL_LINES:
            lines.append(f"- and {len(detail) - DETAIL_LINES} more")
        lines.append("")
    return "\n".join(lines)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("workflow", help="the workflow file the run is of")
    parser.add_argument("artifacts", help="a directory of the run's downloaded artifacts")
    args = parser.parse_args(argv)
    with open(args.workflow, encoding="utf-8") as handle:
        text = handle.read()
    needs = json.loads(os.environ.get("NEEDS") or "{}")
    server, repository, run = (os.environ.get(name) for name in
                               ("GITHUB_SERVER_URL", "GITHUB_REPOSITORY", "GITHUB_RUN_ID"))
    run_url = f"{server}/{repository}/actions/runs/{run}" if server and repository and run else None
    markdown = summary(text, needs, args.artifacts, run_url)
    target = os.environ.get("GITHUB_STEP_SUMMARY")
    if target:
        with open(target, "a", encoding="utf-8") as handle:
            handle.write(markdown + "\n")
    else:
        print(markdown)
    return 0


if __name__ == "__main__":
    sys.exit(main())
