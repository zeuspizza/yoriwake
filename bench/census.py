"""What the changes in each project's own history look like, read from git with no build.

The mutation sample decides what a recall figure can speak for. This census says which change
shapes real commits make, so the sample can be checked against them: per project, the last
`--window` first-parent commits up to and including the pinned commit, each labelled by the paths
it touches and by the hunks of its main code in the measured module.

A merge counts as its diff against its first parent, so a project that merges pull requests is
counted by what each merge landed, and the commits it brought in are not counted again.

Labels (a commit carries every one that applies):

- paths: `in-module-code`, `other-module-code`, `several-files`, `several-modules`, `test`,
  `resource`, `service-file`, `build-script`, `version-catalog`, `docs`, `file-added`,
  `file-deleted`, `kotlin`, `groovy`
- hunks of in-module code: `body-value`, `body-void`, `signature`, `member-added`,
  `member-deleted`, `constant`, `visibility`

A hunk is placed by the enclosing declaration git's `java` and `kotlin` diff drivers name in its
header. The census is context for choosing what to sample; it never enters a recall figure.

    python3 bench/census.py --checkouts bench/checkouts            # every project file
    python3 bench/census.py --checkouts DIR bench/projects/a.toml  # some
    python3 bench/census.py --checkouts DIR --sample 2 --seed 7    # draw a sample to label by hand

Writes `bench/census/census.json`; with `bench/census/labelled.json` present, it adds the
classifier's agreement with the hand labels.
"""

import argparse
import glob
import hashlib
import json
import os
import random
import re
import subprocess
import sys
import tempfile
import tomllib

HERE = os.path.dirname(os.path.abspath(__file__))
CLASSIFIER = "census/v1"
DEFAULT_WINDOW = 200
OUT_DIR = os.path.join(HERE, "census")

CODE = (".java", ".kt", ".groovy", ".scala")
LABELS = ("in-module-code", "other-module-code", "several-files", "several-modules", "test",
          "resource", "service-file", "build-script", "version-catalog", "docs", "file-added",
          "file-deleted", "kotlin", "groovy", "body-value", "body-void", "signature",
          "member-added", "member-deleted", "constant", "visibility")

MODIFIERS = r"(?:public|private|protected|internal|static|final|abstract|open|override|suspend|" \
            r"inline|synchronized|default|native|operator|infix|tailrec|external|data|sealed|" \
            r"lateinit|const|@\w+(?:\([^)]*\))?)"
VISIBILITY = re.compile(r"\b(public|private|protected|internal)\b")
JAVA_METHOD = re.compile(rf"^\s*(?:{MODIFIERS}\s+)*(?:<[^>]+>\s+)?(?P<type>[\w.<>\[\], ?]+?)\s+"
                         r"(?P<name>\w+)\s*\([^;]*$")
KOTLIN_FUN = re.compile(rf"^\s*(?:{MODIFIERS}\s+)*fun\s+(?:<[^>]+>\s+)?(?:[\w.<>]+\.)?(?P<name>\w+)"
                        r"\s*\((?P<rest>.*)$")
FIELD = re.compile(rf"^\s*(?:{MODIFIERS}\s+)*(?:val|var|[\w.<>\[\]]+)\s+(?P<name>\w+)\s*[:=;]")
# A compile-time constant: a primitive or String `static final` with an initialiser, or `const val`.
CONSTANT = re.compile(r"^\s*(?:(?:public|private|protected|internal)\s+)?"
                      r"(?:static\s+final\s+(?:int|long|short|byte|char|double|float|boolean|String)"
                      r"\s+\w+\s*=|const\s+val\s+\w+)")
# A type declaration, with its visibility taken out by `TYPE.sub` so two lines that differ only in
# visibility compare equal.
TYPE = re.compile(r"^\s*(?:(?:public|private|protected|internal)\s+)*(?=(?:\w+\s+)*"
                  r"(?:class|interface|enum|object|record)\s)")
NOT_A_TYPE = {"return", "new", "throw", "else", "case", "if", "while", "for", "switch", "catch",
              "yield", "break", "continue", "assert", "do", "try", "finally", "default"}


def measured_module(project):
    """The directory of the module whose code the sweep measures: `module_dir`, else the first
    source root up to its `/src/`, else that root itself."""
    if project.get("module_dir") is not None:
        return project["module_dir"].strip("/")
    root = (project.get("sources") or [""])[0].strip("/")
    if "/src/" in f"/{root}/":
        return f"/{root}/".split("/src/")[0].strip("/")
    return root


def _git(checkout, *args):
    return subprocess.run(["git", "-C", checkout, "-c", "core.quotePath=false", *args],
                          check=True, capture_output=True, text=True, errors="replace").stdout


# A main source set (`src/main/`, Kotlin Multiplatform's `src/jvmMain/`, `src/commonMain/`) and a
# test one (`src/test/`, `src/jvmTest/`, `src/functionalTest/`, `src/testFixtures/`), or a module's
# own `test/` directory. A package named `test` inside main code is main code.
MAIN_SET = re.compile(r"(?:^|/)src/(?:main|[A-Za-z]+Main)/")
TEST_SET = re.compile(r"(?:^|/)src/(?:test|testFixtures|[A-Za-z]*Test[A-Za-z]*)/|^(?:[^/]+/)*?tests?/")


def _module_of(path):
    """The module directory a source path sits in: what precedes its `src/`, `""` for the root."""
    head, sep, _ = f"/{path}".partition("/src/")
    return head.strip("/") if sep else None


def _main_code(path, roots):
    """Whether `path` is main code: a code file in a main source set, or under a source root the
    project declares (a module whose sources sit directly under `src/`)."""
    if not path.endswith(CODE):
        return False
    return bool(MAIN_SET.search(path)) or any(path.startswith(f"{r.strip('/')}/") for r in roots if r)


def _path_labels(changes, module, roots=()):
    labels, code_modules, in_module = set(), set(), []
    for status, path in changes:
        name = os.path.basename(path)
        if path.endswith(".md") or path.startswith("docs/") or "/docs/" in path:
            labels.add("docs")
        elif path.endswith(".versions.toml"):
            labels.add("version-catalog")
        elif (path.endswith((".gradle", ".gradle.kts")) or path.startswith("buildSrc/")
              or name == "gradle.properties"):
            labels.add("build-script")
        elif "/META-INF/services/" in path:
            labels.add("service-file")
        elif _main_code(path, roots):
            owner = _module_of(path)
            owner = module if owner is None or any(path.startswith(f"{r.strip('/')}/") for r in roots
                                                    if r) else owner
            code_modules.add(owner)
            if owner == (module or ""):
                in_module.append((status, path))
            else:
                labels.add("other-module-code")
        elif "/src/main/resources/" in f"/{path}":
            labels.add("resource")
        elif TEST_SET.search(path):
            labels.add("test")
    if in_module:
        labels.add("in-module-code")
        labels |= {"file-added" for status, _ in in_module if status == "A"}
        labels |= {"file-deleted" for status, _ in in_module if status == "D"}
        labels |= {"kotlin" for _, path in in_module if path.endswith(".kt")}
        labels |= {"groovy" for _, path in in_module if path.endswith(".groovy")}
        if len(in_module) > 1:
            labels.add("several-files")
    if len(code_modules) > 1:
        labels.add("several-modules")
    return labels, [path for _, path in in_module]


def _declares(line):
    """What a source line declares: ("method", name, returns-value) or ("field", name), else None."""
    code = line.split("//")[0].rstrip()
    if not code.strip() or code.strip().startswith(("*", "/*", "@")):
        return None
    if code.split()[0] in NOT_A_TYPE:
        return None
    fun = KOTLIN_FUN.match(code)
    if fun:
        rest = fun.group("rest")
        after = rest.rsplit(")", 1)[-1] if ")" in rest else ""
        typed = re.match(r"\s*:\s*([\w.<>?, ]+)", after)
        value = bool(typed) and typed.group(1).strip() != "Unit"
        if not typed and "=" in after:
            value = True
        return ("method", fun.group("name"), value)
    method = JAVA_METHOD.match(code)
    words = method.group("type").split() if method else []
    if words and words[-1] not in NOT_A_TYPE and not code.strip().endswith(";"):
        return ("method", method.group("name"), words[-1] != "void")
    field = FIELD.match(code)
    if field and not code.strip().startswith(("return", "throw")):
        return ("field", field.group("name"))
    return None


def _hunks(checkout, parent, sha, paths):
    """`(path, header, removed lines, added lines)` per hunk of `paths`, with git's java and kotlin
    drivers naming each hunk's enclosing declaration in its header."""
    with tempfile.NamedTemporaryFile("w", suffix=".gitattributes", delete=False) as attributes:
        attributes.write("*.java diff=java\n*.kt diff=kotlin\n*.kts diff=kotlin\n")
    try:
        text = _git(checkout, "-c", f"core.attributesFile={attributes.name}", "diff", "--unified=0",
                    "--no-color", "--no-renames", parent, sha, "--", *paths)
    finally:
        os.unlink(attributes.name)
    hunks, current, path = [], None, None
    for line in text.splitlines():
        if line.startswith("+++ "):
            path = line[6:] if line.startswith("+++ b/") else path
        elif line.startswith("--- a/"):
            path = line[6:]
        elif line.startswith("@@"):
            current = (path, line.split("@@", 2)[-1].strip(), [], [])
            hunks.append(current)
        elif current and line.startswith("-") and not line.startswith("---"):
            current[2].append(line[1:])
        elif current and line.startswith("+") and not line.startswith("+++"):
            current[3].append(line[1:])
    return hunks


def _comment_or_blank(line):
    """A line that changes no code: blank, or a comment (a KDoc or Javadoc line among them)."""
    code = line.strip()
    return not code or code.startswith(("//", "/*", "*", "*/"))


def _indent(line):
    return len(line) - len(line.lstrip())


def _member_indent(header, texts):
    """The indentation members have where a hunk sits, from the line its header names in the file
    before or after (git strips it from the header). Under a method, a member is no deeper than the
    method; under a class, it is at the class body's first indentation. A declaration deeper than
    that is a local, part of a body. None when the header names no line found."""
    method = (_declares(header) or ("",))[0] == "method"
    for text in texts:
        lines = text.splitlines()
        for at, line in enumerate(lines):
            if header and line.strip().startswith(header):
                if method:
                    return _indent(line)
                body = [l for l in lines[at + 1:] if l.strip() and _indent(l) > _indent(line)]
                return _indent(body[0]) if body else None
    return None


def _hunk_labels(hunks, texts=None):
    """Hunk labels. `texts` maps a path to its source before and after, to place declarations: one
    indented deeper than the method enclosing its hunk is a local, part of that method's body."""
    labels = set()
    for path, header, removed, added in hunks:
        if all(_comment_or_blank(line) for line in removed + added):
            continue
        enclosing = _declares(header)
        inside = _member_indent(header, (texts or {}).get(path, ())) if header else None

        def member(line):
            declared = _declares(line)
            return declared if declared and (inside is None or _indent(line) <= inside) else None

        gone = {d[:2]: (d, line) for line in removed if (d := member(line))}
        new = {d[:2]: (d, line) for line in added if (d := member(line))}
        types = [(TYPE.sub("", l).split(), VISIBILITY.findall(l)) for l in removed + added if TYPE.match(l)]
        if any(a[0] == b[0] and a[1] != b[1] for a in types for b in types):
            labels.add("visibility")
        constant = any(CONSTANT.match(line) for line in removed + added)
        if constant:
            labels.add("constant")
        for key, (declared, line) in gone.items():
            if key in new:
                other = new[key][1]
                if VISIBILITY.findall(line) != VISIBILITY.findall(other) \
                        and VISIBILITY.sub("", line).split() == VISIBILITY.sub("", other).split():
                    labels.add("visibility")
                elif declared[0] == "method":
                    labels.add("signature")
            elif not any(k[1] == key[1] for k in new):
                labels.add("member-deleted")
        for key in new:
            if key not in gone and not any(k[1] == key[1] for k in gone):
                labels.add("member-added")
        if gone or new or constant:
            continue
        if enclosing and enclosing[0] == "method":
            labels.add("body-value" if enclosing[2] else "body-void")
    return labels


def _show(checkout, rev, path):
    done = subprocess.run(["git", "-C", checkout, "show", f"{rev}:{path}"], capture_output=True,
                          text=True, errors="replace")
    return done.stdout if done.returncode == 0 else ""


def _commit_labels(checkout, sha, module, roots=()):
    parents = _git(checkout, "rev-list", "--parents", "-n", "1", sha).split()[1:]
    parent = parents[0] if parents else _git(checkout, "hash-object", "-t", "tree", "/dev/null").strip()
    changes = [line.split("\t", 1) for line in
               _git(checkout, "diff", "--name-status", "--no-renames", parent, sha).splitlines() if line]
    labels, in_module = _path_labels([(status[0], path) for status, path in changes], module, roots)
    if in_module:
        texts = {path: tuple(_show(checkout, rev, path) for rev in (parent, sha)) for path in in_module}
        labels |= _hunk_labels(_hunks(checkout, parent, sha, in_module), texts)
    return sorted(labels)


def census_project(project, checkout, window=DEFAULT_WINDOW):
    """One project's commits, newest first, each with its labels."""
    module = measured_module(project)
    shas = _git(checkout, "rev-list", "--first-parent", "-n", str(window), project["commit"]).split()
    return {"name": project["name"], "pin": project["commit"], "module": module,
            "read": len(shas), "short": len(shas) < window,
            "commits": [{"sha": sha, "labels": _commit_labels(checkout, sha, module,
                                                               project.get("sources") or ())}
                        for sha in shas]}


def shares(rows):
    """Label shares: hunk and module labels of the in-module code commits, path labels of all."""
    in_module = [row for row in rows if "in-module-code" in row["labels"]]

    def share(label, of):
        return round(sum(label in row["labels"] for row in of) / len(of), 4) if of else None

    return {"commits": len(rows), "in_module_code_commits": len(in_module),
            "of_in_module_code": {label: share(label, in_module) for label in LABELS
                                  if any(label in row["labels"] for row in in_module)},
            "of_all_commits": {label: share(label, rows) for label in LABELS
                               if any(label in row["labels"] for row in rows)}}


def agreement(predicted, labelled):
    """Per label, how many hand-labelled commits the classifier labels the same way."""
    result = {}
    for label in sorted(set().union(*predicted.values(), *labelled.values())):
        agree = sum((label in predicted.get(sha, set())) == (label in labels)
                    for sha, labels in labelled.items())
        result[label] = {"agree": agree, "of": len(labelled)}
    return result


def census(projects, checkouts, window=DEFAULT_WINDOW):
    """The census over `projects`, each read from `checkouts[name]`. A project file with no `url`
    (a local fixture, or a variant read from another project's history) and a missing clone are
    listed, not counted."""
    result = {"classifier": CLASSIFIER, "window": window, "projects": [], "missing": [],
              "not_a_repository": []}
    for project in sorted(projects, key=lambda p: p["name"]):
        if not project.get("url"):
            result["not_a_repository"].append(project["name"])
            continue
        checkout = checkouts.get(project["name"])
        if not checkout or not os.path.isdir(os.path.join(checkout, ".git")) and \
                not os.path.isfile(os.path.join(checkout, ".git")):
            result["missing"].append(project["name"])
            continue
        result["projects"].append(census_project(project, checkout, window))
    rows = [row for project in result["projects"] for row in project["commits"]]
    result["shares"] = shares(rows)
    # Named, because the hand sample is drawn from commits labelled in-module code and so cannot
    # show a module whose code the classifier never recognises.
    result["no_in_module_code"] = [p["name"] for p in result["projects"]
                                   if not any("in-module-code" in r["labels"] for r in p["commits"])]
    return result


def draw_sample(result, per_project, seed):
    """`per_project` commits of each project with in-module code, drawn with `seed`, to label."""
    sample = []
    for project in result["projects"]:
        eligible = [row["sha"] for row in project["commits"] if "in-module-code" in row["labels"]]
        rng = random.Random(f"{seed}:{project['name']}")
        for sha in sorted(rng.sample(eligible, min(per_project, len(eligible)))):
            sample.append({"project": project["name"], "sha": sha, "labels": None})
    return sample


def _load(path):
    with open(path, "rb") as handle:
        project = tomllib.load(handle)
    project.setdefault("name", os.path.splitext(os.path.basename(path))[0])
    return project


def clones(projects, directory):
    """Each project's clone: `<directory>/<name>`, else a clone there of the same repository, since
    several projects measure modules of one repository."""
    by_url = {}
    for entry in sorted(os.listdir(directory)) if os.path.isdir(directory) else []:
        path = os.path.join(directory, entry)
        done = subprocess.run(["git", "-C", path, "config", "remote.origin.url"],
                              capture_output=True, text=True)
        if done.returncode == 0:
            by_url.setdefault(_normal(done.stdout.strip()), path)
    found = {}
    for project in projects:
        own = os.path.join(directory, project["name"])
        found[project["name"]] = own if os.path.isdir(own) else by_url.get(_normal(project.get("url", "")))
    return found


def _normal(url):
    return url.rstrip("/").removesuffix(".git").lower()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("projects", nargs="*", help="project files (default: every one)")
    parser.add_argument("--checkouts", required=True,
                        help="directory holding one clone per project, named as the project")
    parser.add_argument("--window", type=int, default=DEFAULT_WINDOW)
    parser.add_argument("--out", default=OUT_DIR)
    parser.add_argument("--sample", type=int, metavar="N",
                        help="also draw N commits a project to label by hand (labelled.json)")
    parser.add_argument("--seed", type=int, default=20261016)
    args = parser.parse_args(argv)
    paths = args.projects or sorted(glob.glob(os.path.join(HERE, "projects", "*.toml")))
    projects = [_load(path) for path in paths]
    checkouts = clones(projects, args.checkouts)
    result = census(projects, checkouts, args.window)
    os.makedirs(args.out, exist_ok=True)
    labelled_path = os.path.join(args.out, "labelled.json")
    if args.sample:
        if os.path.exists(labelled_path):
            raise SystemExit(f"refusing: {labelled_path} exists, and its hand labels would be lost")
        with open(labelled_path, "w", encoding="utf-8") as handle:
            json.dump(draw_sample(result, args.sample, args.seed), handle, indent=1)
            handle.write("\n")
    if os.path.exists(labelled_path):
        with open(labelled_path, encoding="utf-8") as handle:
            labelled = {(row["project"], row["sha"]): set(row["labels"]) for row in json.load(handle)
                        if row.get("labels") is not None}
        # By project as well as commit: projects measuring modules of one repository share commits.
        predicted = {(p["name"], row["sha"]): set(row["labels"])
                     for p in result["projects"] for row in p["commits"]}
        # Scored only over the hand-labelled commits this run classified; a commit outside its
        # projects or window would otherwise disagree on every label it carries.
        unmatched = [key for key in labelled if key not in predicted]
        labelled = {key: labels for key, labels in labelled.items() if key in predicted}
        if labelled:
            result["agreement"] = agreement(predicted, labelled)
            result["agreement_unmatched"] = len(unmatched)
    result["digest"] = hashlib.sha256(json.dumps(result["projects"], sort_keys=True)
                                      .encode()).hexdigest()[:12]
    with open(os.path.join(args.out, "census.json"), "w", encoding="utf-8") as handle:
        json.dump(result, handle, indent=1, sort_keys=True)
        handle.write("\n")
    s = result["shares"]
    print(f"{len(result['projects'])} projects, {s['commits']} commits, "
          f"{s['in_module_code_commits']} with in-module code; missing: {result['missing'] or 'none'}")
    for label, share in sorted(s["of_in_module_code"].items(), key=lambda kv: -kv[1]):
        print(f"  {label:20} {share:6.1%}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
