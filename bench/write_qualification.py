"""
Turns the verdicts `qualify.py --json` emits into `bench/qualification.txt`.

WHY THIS EXISTS AS A SEPARATE STEP. `qualify.py --all` holds every verdict in memory and writes them
only after the last project finishes. Twenty-five projects at up to forty-five minutes each is not a
run anyone waits out, and a run that is interrupted -- or that simply outlasts the evening -- produces
nothing at all, however many projects it correctly qualified on the way.

So the corpus is qualified one project per invocation, each verdict appended to a `.jsonl` the moment
it lands, and this collates whatever is there. A partial corpus is a useful corpus; an unwritten one
is not.

Every row is stamped with the plugin commit that produced it, because a qualification is a statement
about a checkout AND about the selector that was applied to it, and the second half goes stale
silently.

Usage:
    python bench/write_qualification.py <verdicts.jsonl> [--out bench/qualification.txt]
"""

import argparse
import json
import re
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
import checkout  # noqa: E402  (the one place the yoriwake checkout is resolved)
# The tree the selector is built from. A module attribute so a test can point it elsewhere.
SOURCE = checkout.ROOT

# `qualify.py --json` prints its progress commentary and then its payload. Under the current version
# the commentary goes to stderr, but a file collected from an older one -- or from a shell that
# merged the streams -- has prose wrapped around the payload, so verdicts are scanned out of the
# text rather than the file being parsed as a whole. See `verdicts`.

# A BLOCKER AND A DEAD ATTEMPT ARE NOT THE SAME THING, and recording one as the other is how a
# project gets written off. qualify.py's own docstring says four projects were written off and all
# four ran once someone read the actual error rather than the summary.
#
# These signatures mean the process died rather than the project failing: Windows 0xC0000142
# (3221225794) is "the process could not start", -1 (4294967295) is a kill, and a daemon that
# "disappeared unexpectedly" was taken by the OS. On a memory-constrained machine running long
# builds back to back all three say "try again on a quiet machine", not "this project cannot be
# measured" -- and qualify.py will offer a confident, plausible and unsupported diagnosis for any of
# them, because all it sees is that no test results appeared.
DIED = (
    "3221225794",      # 0xC0000142, STATUS_DLL_INIT_FAILED: the process could not start
    "4294967295",      # -1: killed
    "daemon disappeared",
    # A precondition refusal from qualify.py --all: the project asked for a toolchain this machine
    # does not have, so the attempt never began. Nothing was learned about the project.
    #
    # The FULL prefix qualify.py writes, not a bare "could not start". These signatures are matched
    # against blocker text that embeds Gradle's own output, and Gradle is entitled to say "could not
    # start" about a daemon, a worker or a process for reasons that ARE verdicts about the project.
    # Matching the short form would file those as UNRESOLVED and quietly remove them from the
    # blocked count.
    "the attempt could not start",
)


# A POSIX process killed by a signal reports a NEGATIVE exit code -- -9 for SIGKILL, which is what
# the OOM killer sends and what every one of this project's memory incidents would look like on
# macOS or Linux. The literal signatures above are all Windows: 0xC0000142, -1 as unsigned, and
# Gradle's own daemon message. Recognising only those meant the same OOM death that files as
# UNRESOLVED on Windows filed as BLOCKED everywhere else -- a verdict about a project, from a run
# that never got to judge it.
KILLED_BY_SIGNAL = re.compile(r"\(exit -\d+\)")


def died_rather_than_failed(blocker):
    text = (blocker or "").lower()
    if any(signature in text for signature in DIED):
        return True
    return KILLED_BY_SIGNAL.search(text) is not None



def plugin_commit():
    """The yoriwake checkout commit the selector was built from. Never a guess.

    Refused, not suffixed `-dirty`, when that checkout has uncommitted changes: a stamp must name a
    public commit. The table being written lives in `bench/`, which that check leaves out, so
    writing it never makes the stamp's own tree dirty -- the reason an older version scoped its
    check to `plugin/` and `agent/` no longer applies.
    """
    return checkout.commit(SOURCE, short=True)


def verdicts(path):
    """Every verdict in the file, latest occurrence of each name winning.

    A project re-qualified after a fix appears twice; the later row is the current answer, and
    keeping both would leave the reader to work out which is which.

    SCANNED OBJECT BY OBJECT, not array by array, and this is not defensiveness. A qualification run
    killed partway leaves a truncated array behind, and the next run appends a fresh one after it --
    so a whole-array parse fails on the join and takes every verdict in that chunk with it. glide's
    verdict was lost exactly this way and nobody would have noticed: the collation succeeded, the
    file looked complete, and one project had simply evaporated.

    `raw_decode` reads one value and reports where it stopped, so a truncated object costs only
    itself. What could not be read is COUNTED and reported by the caller rather than skipped in
    silence, because a collation that quietly drops rows is the same failure as a measurement that
    quietly measures nothing.
    """
    with open(path, encoding="utf-8", errors="replace") as handle:
        text = handle.read()
    decoder = json.JSONDecoder()
    found, unreadable = {}, 0
    index = 0
    while True:
        start = text.find("{", index)
        if start < 0:
            break
        try:
            value, end = decoder.raw_decode(text, start)
        except ValueError:
            # Not the start of a readable object. Only count it as lost if it looked like a verdict,
            # so ordinary prose containing a brace does not inflate the tally.
            if text.startswith('{\n    "name"', start) or text.startswith('{"name"', start):
                unreadable += 1
            index = start + 1
            continue
        if isinstance(value, dict) and "name" in value:
            found[value["name"]] = value
        index = max(end, start + 1)
    return found, unreadable


def render(found, commit):
    lines = [
        "# Produced by bench/write_qualification.py from bench/qualify.py --json.",
        f"# Plugin commit: {commit}. A qualification describes a checkout AND the selector applied",
        "# to it; re-qualify after any change to selection before quoting a row.",
        "#",
        "# QUALIFIED means only: it builds, its tests run hermetically on this machine, and the",
        "# plugin attached. That is the floor, not a recommendation, and it is never a savings",
        "# number. See bench/qualify.py's module docstring.",
        "",
    ]
    ok = sorted(n for n, v in found.items() if v.get("qualified"))
    rest = [n for n in found if not found[n].get("qualified")]
    unresolved = sorted(n for n in rest if died_rather_than_failed(found[n].get("blocker")))
    blocked = sorted(n for n in rest if n not in set(unresolved))
    lines.append(f"{len(ok)} of {len(found)} qualified, {len(blocked)} blocked, "
                 f"{len(unresolved)} unresolved (the attempt died; not a verdict)")
    lines.append("")
    for name in ok:
        verdict = found[name]
        evidence = verdict.get("evidence") or {}
        detail = " ".join(f"{k}={v}" for k, v in sorted(evidence.items()) if not isinstance(v, (dict, list)))
        lines.append(f"QUALIFIED  {name:24} module={verdict.get('module') or '-':28} {detail}")
    if blocked:
        lines.append("")
        for name in blocked:
            lines.append(f"BLOCKED    {name:24} {found[name].get('blocker')}")
    if unresolved:
        lines.append("")
        lines.append("# The ATTEMPT died rather than the project failing -- the process was killed")
        lines.append("# or its daemon was taken. These are not verdicts. Re-run on a quiet machine")
        lines.append("# before believing any diagnosis qualify.py offered for them.")
        for name in unresolved:
            lines.append(f"UNRESOLVED {name:24} {found[name].get('blocker')}")
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("verdicts", help="a .jsonl of qualify.py --json output")
    parser.add_argument("--out", default=os.path.join(HERE, "qualification.txt"))
    args = parser.parse_args()

    found, unreadable = verdicts(args.verdicts)
    if unreadable:
        print(f"WARNING: {unreadable} verdict objects in {args.verdicts} could not be read and are "
              "NOT in the output. A run killed partway truncates the object it was writing; "
              "re-qualify those projects rather than assuming they were never attempted.",
              file=sys.stderr)
    if not found:
        # Never overwrite a real record with an empty one. A collation that found nothing is a
        # collation that failed, not a corpus in which nothing qualified.
        print(f"no verdicts parsed from {args.verdicts}; {args.out} left as it is", file=sys.stderr)
        return 2
    with open(args.out, "w", encoding="utf-8") as handle:
        handle.write(render(found, plugin_commit()))
    print(f"{len(found)} verdicts written to {args.out}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
