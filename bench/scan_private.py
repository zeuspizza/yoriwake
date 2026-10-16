"""Refuses a push that would publish the maintainers' private working vocabulary.

The harness was developed in a private repository, against plans whose identifiers, dates and
documents mean nothing to a reader here. This scan keeps them out of `bench/`, `scripts/`,
`docs/`, `CONTRIBUTING.md` and `.github/`, and out of the commit messages that carry them.

    python3 bench/scan_private.py --push            # what a push of HEAD would add to origin/main
    python3 bench/scan_private.py --range BASE HEAD # what a pull request adds (CI)
    python3 bench/scan_private.py --message VAR...  # text in these environment variables (CI)
    python3 bench/scan_private.py PATH...           # files or directories, whole

Prints one line per match and exits 1 when there is any.
"""

import os
import re
import subprocess
import sys

SCOPE = ("bench/", "scripts/", "docs/", ".github/", "CONTRIBUTING.md")

# The scan's own patterns and the test that seeds them.
EXEMPT = {"bench/scan_private.py", "bench/test_scan_private.py"}

# (name, pattern, applies in docs/). A published figure states when it was measured, so a date is
# allowed in docs/ and refused everywhere else.
RULES = [
    ("plan unit", re.compile(r"\bU\d{1,2}[a-z]?\b"), True),
    ("plan number", re.compile(r"\bplan \d{3,4}\b", re.I), True),
    ("decision id", re.compile(r"\bKTD\d+"), True),
    ("requirement id", re.compile(r"\bR\d{1,2}[a-z]?\b"), True),
    ("date", re.compile(r"\b20\d\d-\d\d-\d\d\b"), False),
    ("private document", re.compile(r"BENCHMARKS\.md|STATE\.md|AGENTS\.md|docs/solutions"), True),
    ("private anchor", re.compile(r"\b4a-|§\s?\d"), True),
    ("private repository", re.compile(r"yoriwake-labs"), True),
    ("home path", re.compile(r"(?:/Users/|/home/)[A-Za-z]"), True),
    ("spike", re.compile(r"\bspike\b", re.I), True),
    ("private tool", re.compile(r"session_tokens"), True),
    ("assistant", re.compile(r"\bclaude\b", re.I), True),
]

# A commit message may carry a date; everything else applies.
MESSAGE_RULES = [rule for rule in RULES if rule[0] != "date"]


def in_scope(path):
    return path not in EXEMPT and (path.startswith(SCOPE[:-1]) or path == SCOPE[-1])


def rules_for(path):
    return [rule for rule in RULES if rule[2]] if path.startswith("docs/") else RULES


def scan_text(label, text, rules, first=1):
    """`label:line: rule: excerpt` for every match in `text`, whose first line is `first`."""
    hits = []
    for number, line in enumerate(text.splitlines(), first):
        for name, pattern, _ in rules:
            if pattern.search(line):
                hits.append(f"{label}:{number}: {name}: {line.strip()[:100]}")
    return hits


def scan_file(root, path):
    try:
        with open(os.path.join(root, path), encoding="utf-8") as handle:
            text = handle.read()
    except (UnicodeDecodeError, FileNotFoundError, IsADirectoryError):
        return []
    return scan_text(path, text, rules_for(path))


def git(root, *args):
    return subprocess.run(
        ["git", "-C", root, *args], check=True, capture_output=True, text=True
    ).stdout


def scan_range(root, base, head):
    """Every line a commit in the range adds to an in-scope path, and every commit message in it.

    Commit by commit, not the tree at `head`: a line one commit adds and a later one removes is
    still published in the pushed history.
    """
    hits = []
    for sha in git(root, "rev-list", "--reverse", f"{base}..{head}").split():
        message = git(root, "log", "-1", "--format=%B", sha)
        hits += scan_text(f"commit {sha[:9]}", message, MESSAGE_RULES)
        # Paths as they are: git otherwise quotes a name with non-ASCII or special characters,
        # and a quoted path would match no scope.
        hits += scan_added(sha, git(root, "-c", "core.quotePath=false", "show", "--format=",
                                    "--unified=0", "--no-renames", sha))
    return hits


def scan_added(sha, patch):
    hits, path, number = [], None, 0
    for line in patch.splitlines():
        if line.startswith('+++ "'):
            # Still quoted with quotePath off: a quote, tab or newline in the name. Refused rather
            # than skipped, so no file escapes the scan by its name.
            hits.append(f"commit {sha[:9]} {line[4:]}: unscannable path: rename it")
            path = None
        elif line.startswith("+++ "):
            path = line[6:] if line.startswith("+++ b/") else None
        elif line.startswith("@@"):
            number = int(re.search(r"\+(\d+)", line).group(1))
        elif line.startswith("+") and path and in_scope(path):
            hits += scan_text(f"commit {sha[:9]} {path}", line[1:], rules_for(path), number)
            number += 1
    return hits


def scan_message(label, text):
    """A pull request's title or body, under the rules a commit message gets: the squash commit
    that lands on main is written from them."""
    return scan_text(label, text or "", MESSAGE_RULES)


def scan_paths(root, targets):
    paths = []
    for target in targets:
        if os.path.isdir(os.path.join(root, target)):
            for directory, _, files in os.walk(os.path.join(root, target)):
                paths += [os.path.relpath(os.path.join(directory, name), root) for name in files]
        else:
            paths.append(target)
    ignored = ignored_paths(root, paths)
    return [hit for path in paths if in_scope(path) and path not in ignored
            for hit in scan_file(root, path)]


def ignored_paths(root, paths):
    """The paths git ignores, which no push carries; none outside a repository."""
    done = subprocess.run(["git", "-C", root, "check-ignore", "--stdin"], input="\n".join(paths),
                          capture_output=True, text=True)
    return set(done.stdout.split("\n")) if done.returncode in (0, 1) else set()


def main(argv, root="."):
    if argv[:1] == ["--push"]:
        hits = scan_range(root, argv[1] if len(argv) > 1 else "origin/main", "HEAD")
    elif argv[:1] == ["--range"] and len(argv) == 3:
        hits = scan_range(root, argv[1], argv[2])
    elif argv[:1] == ["--message"] and len(argv) > 1:
        # Read from the environment, so a workflow never pastes untrusted text into a command.
        hits = [hit for name in argv[1:] for hit in scan_message(name, os.environ.get(name))]
    elif argv and not argv[0].startswith("-"):
        hits = scan_paths(root, argv)
    else:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    for hit in hits:
        print(hit)
    if hits:
        print(f"{len(hits)} private reference(s); reword them before pushing.", file=sys.stderr)
    return 1 if hits else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
