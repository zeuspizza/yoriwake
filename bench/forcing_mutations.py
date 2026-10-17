"""
The `forcing/v1` shape set: changes yoriwake runs every test for today, because the change's paths
alone decide it. Each row checks that the forcing still holds.

    resource-value     one value edited in a `.properties`, `.yml`, `.yaml`, `.json` or `.xml` file
                       under the measured module's `src/main/resources`
    services           one provider line removed from a `META-INF/services` file that lists two or
                       more, so the file never ends up empty
    build-script       one system property added to the measured module's test tasks, in its
                       `build.gradle.kts` or `build.gradle`
    dependency-revert  the revert of a recent commit that changed only a version catalog or version
                       lines of a build script, through the revert set's patches. An artifact that
                       does not resolve after it is a counted rejection: this family edits
                       dependency declarations, unlike the revert set
    groovy-body        an `IllegalStateException` thrown at the entry of a Groovy method under the
                       project's source roots

A family stops drawing after `ROWS_PER_FAMILY` rows a project, whatever they showed: the answer is
fixed by the path, so a second row of the same family buys a suite run and no information. The set
has no recall denominator. Its figure counts, per family, the rows and the forced rows that ran
everything; with the narrowing flags off, a row that narrowed is a finding that fails the project.
With a narrowing flag in the project's `flags`, its rows are a population of their own.

WHY A FILE OF ITS OWN. `grammar_digest` hashes the default grammar's files; adding these generators
to them would move the default set's digest and restart every default results file. This file is
digested with `forcing/v1` only, beside the three it reuses (`pool_mutations.py`, `revert.py` and
`census.py`).
"""

import glob
import hashlib
import os
import re

import census
import pool_mutations
import revert

FORCING_SET = "forcing/v1"

FAMILIES = ("resource-value", "services", "build-script", "dependency-revert", "groovy-body")

# ROWS A FAMILY DRAWS A PROJECT. One, since the path decides the outcome; two once each force kind
# also has its own cap on suite runs.
ROWS_PER_FAMILY = 1

# The projects a sweep of this set runs on beside the reduced arm: the one whose measured root is
# Groovy, for `groovy-body`.
GROOVY_PROJECTS = ("grails-core",)

# Plugin properties that let a forcing path narrow. With one of them on, a narrowed row is what the
# flag is for, not a finding.
NARROWING_FLAGS = ("-Pyoriwake.narrowResources", "-Pyoriwake.narrowDependencies")

SENTINEL = "yoriwake-mutated"

RESOURCE_EXTENSIONS = (".properties", ".yml", ".yaml", ".json", ".xml")


def _read(path):
    with open(path, encoding="utf-8", errors="replace") as handle:
        return handle.read()


def _write(path, text):
    with open(path, "w", encoding="utf-8", newline="") as handle:
        handle.write(text)


def population(flags):
    """`flag-on` when a narrowing flag is in the project's `flags`, else `flag-off`."""
    for flag in flags or []:
        name, _, value = flag.partition("=")
        if name in NARROWING_FLAGS and value.strip().lower() != "false":
            return "flag-on"
    return "flag-off"


def family_full(kind, findings, recorded=(), limit=ROWS_PER_FAMILY):
    """Whether `kind` already has its rows this project, counting rows recorded for shards."""
    rows = sum(1 for row in findings if row.get("kind") == kind)
    rows += sum(1 for entry in recorded if entry[1] == kind)
    return rows >= limit


# ------------------------------------------------------------------------------- resource-value

_PROPERTIES = re.compile(r"^(?P<head>[ \t]*[^#!\s=:][^=:\n]*?[ \t]*[=:][ \t]*)"
                         r"(?P<value>[^\n]*[^\s\\])[ \t]*$", re.MULTILINE)
_YAML = re.compile(r"^(?P<head>[ \t]*(?:-[ \t]+)?[\w.\-]+:[ \t]+)"
                   r"(?P<value>\"[^\"\n]*\"|'[^'\n]*'|[^\s#|>&*!{\[\"'][^#\n]*?)[ \t]*(?:#[^\n]*)?$",
                   re.MULTILINE)
_JSON = re.compile(r"(?P<head>\"[^\"\\\n]*\"\s*:\s*\")(?P<value>[^\"\\\n]*)\"")
_XML = re.compile(r"(?P<head><(?P<tag>[\w:.\-]+)(?:\s[^<>]*)?>)(?P<value>[^<>\s][^<>\n]*?)</(?P=tag)>")


def _resource_value(path, text):
    """`(start, end)` of the first value the file's format allows editing in place, or None."""
    extension = os.path.splitext(path)[1]
    if extension == ".properties":
        for match in _PROPERTIES.finditer(text):
            # A line continued from the one above is part of that value, not a key of its own.
            above = text[:match.start()].rstrip("\n").rsplit("\n", 1)[-1]
            if not above.endswith("\\"):
                return match.start("value"), match.end("value")
        return None
    if extension in (".yml", ".yaml"):
        for match in _YAML.finditer(text):
            value = match.group("value")
            if value[:1] in "\"'":
                return match.start("value") + 1, match.end("value") - 1
            return match.start("value"), match.end("value")
        return None
    pattern = {".json": _JSON, ".xml": _XML}.get(extension)
    match = pattern.search(text) if pattern else None
    return (match.start("value"), match.end("value")) if match else None


def can_resource_value(path):
    return _resource_value(path, _read(path)) is not None


def resource_value(path, index=0):
    """Appends `-yoriwake-mutated` to the file's first editable value, which stays a value of the
    same syntax: a string stays inside its quotes, a properties value on its line. One per file."""
    text = _read(path)
    span = _resource_value(path, text) if index == 0 else None
    if span is None:
        return None
    start, end = span
    _write(path, text[:start] + text[start:end] + f"-{SENTINEL}" + text[end:])
    key = text[:start].rsplit("\n", 1)[-1].strip(" \t:=\"<>") or "value"
    return re.sub(r"[^\w.\-]", "", key) or "value"


# ------------------------------------------------------------------------------------- services

def _providers(text):
    """`(line index, provider)` for each line naming a provider; comments and blanks name none."""
    found = []
    for number, line in enumerate(text.split("\n")):
        provider = line.split("#", 1)[0].strip()
        if provider:
            found.append((number, provider))
    return found


def services_in(path):
    """Removable lines: every provider line of a file that names two or more."""
    providers = _providers(_read(path))
    return len(providers) if len(providers) >= 2 else 0


def services(path, index=0):
    """Removes the index-th provider line. A file naming one provider yields nothing: emptied, it
    would be a different change, a service with no implementation."""
    text = _read(path)
    providers = _providers(text)
    if len(providers) < 2 or index >= len(providers):
        return None
    number, provider = providers[index]
    lines = text.split("\n")
    del lines[number]
    _write(path, "\n".join(lines))
    return provider


# --------------------------------------------------------------------------------- build-script

BUILD_SCRIPTS = ("build.gradle.kts", "build.gradle")
# Not under `yoriwake.`: the plugin leaves its own properties out of the test JVM's environment
# fingerprint, so a property named there would change nothing it compares.
PROPERTY = ("mutation.marker", "true")


def build_script_of(checkout, project):
    """The measured module's build script, relative to the checkout, or None."""
    module = census.measured_module(project)
    for name in BUILD_SCRIPTS:
        relative = f"{module}/{name}" if module else name
        if os.path.isfile(os.path.join(checkout, relative)):
            return relative
    return None


def build_script(path, index=0):
    """Adds one system property to every test task of the script's module, in the script's DSL.

    Appended as a block of its own rather than edited into an existing `test` block: the script
    may configure its tests through a convention plugin, and the appended block reaches the test
    task wherever it was declared.
    """
    if index != 0:
        return None
    text = _read(path)
    name, value = PROPERTY
    if path.endswith(".kts"):
        block = (f"tasks.withType<Test>().configureEach {{\n"
                 f"    systemProperty(\"{name}\", \"{value}\")\n}}\n")
    else:
        block = (f"tasks.withType(Test).configureEach {{\n"
                 f"    systemProperty '{name}', '{value}'\n}}\n")
    separator = "" if not text or text.endswith("\n\n") else ("\n" if text.endswith("\n") else "\n\n")
    _write(path, text + separator + block)
    return "systemProperty"


# ---------------------------------------------------------------------------------- groovy-body

def _groovy_methods(text):
    """Groovy methods with a body whose `{` ends the declaration's line, as the Java grammar reads
    them: typed or `def`, indented as members are."""
    code = pool_mutations._code(text)
    return [m for m in pool_mutations.JAVA_METHOD.finditer(text)
            if code[m.start("name")] != " " and m.group("type") != "record"
            and m.group("type") not in pool_mutations._JAVA_NOT_A_TYPE
            and m.group("name") not in pool_mutations._JAVA_NOT_A_TYPE]


def groovy_methods_in(path):
    return len(_groovy_methods(_read(path)))


def groovy_body(path, index=0):
    """Throws at the entry of the index-th Groovy method, in `throw-entry`'s braced form."""
    text = _read(path)
    matches = _groovy_methods(text)
    if index >= len(matches):
        return None
    match = matches[index]
    throw = f'throw new IllegalStateException("{SENTINEL}")'
    _write(path, pool_mutations._entry(text, match, ("if (true) {", "    " + throw, "}")))
    return match.group("name")


# ---------------------------------------------------------------------------- dependency-revert

# A changed line of a build script that only moves a version: a `version` property or assignment,
# or a `group:artifact:version` coordinate.
VERSION_LINE = re.compile(r"\b\w*[vV]ersion\w*\s*(?:=|:|\()|\bversion\s+[\"']"
                          r"|[\"'][\w.\-]+:[\w.\-]+:[^\"'\s:]+[\"']")
NOT_ELIGIBLE = ("does-not-apply", "duplicate-patch")


def _dependency_path(path):
    """`version-catalog`, `build-script`, or None for a path no dependency revert may touch."""
    name = os.path.basename(path)
    if path.endswith(".versions.toml"):
        return "version-catalog"
    if path.endswith((".gradle", ".gradle.kts")) or name == "gradle.properties":
        return "build-script"
    return None


def _version_only(checkout, parent, sha, paths):
    """Whether every path is a version catalog, or a build script whose changed lines are all
    version lines, as many removed as added."""
    kinds = {path: _dependency_path(path) for path in paths}
    if not paths or None in kinds.values():
        return False
    scripts = [path for path, kind in kinds.items() if kind == "build-script"]
    if not scripts:
        return True
    diff = revert._git(checkout, "diff", "--unified=0", "--no-color", parent, sha, "--",
                       *scripts).stdout
    changed = [line for line in diff.splitlines()
               if line[:1] in "+-" and not line.startswith(("+++", "---")) and line[1:].strip()]
    # AS MANY LINES REMOVED AS ADDED: a version moves in place, while a dependency added or
    # removed is a coordinate line on one side only.
    removed = sum(1 for line in changed if line[0] == "-")
    if not changed or removed * 2 != len(changed):
        return False
    return all(VERSION_LINE.search(line[1:]) for line in changed)


def _window(checkout, project, window):
    pin = project.get("commit")
    pin = "HEAD" if pin in (None, "local") else pin
    done = revert._git(checkout, "rev-list", "--first-parent", "-n", str(window), pin, check=False)
    return done.stdout.split() if done.returncode == 0 else []


def dependency_candidates(checkout, project, seed, window=revert.SWEEP_WINDOW):
    """The version-only commits of the window that revert cleanly at the pin, in a seeded order
    with identical patches dropped, and the count of each not-eligible reason among version-only
    commits. Each is a candidate `revert.patch` and `revert.apply` take."""
    found, reasons = [], {}
    for sha in _window(checkout, project, window):
        parent = revert._parent(checkout, sha)
        paths = sorted({p for _, old, new in revert._changes(checkout, parent, sha)
                        for p in (old, new)})
        if not _version_only(checkout, parent, sha, paths):
            continue
        candidate = {"sha": sha, "parent": parent, "paths": paths, "full_paths": paths,
                     "scope": [],
                     "labels": sorted({_dependency_path(p) for p in paths} - {None})}
        text = revert.patch(checkout, candidate)
        if not revert.applies(checkout, text):
            reasons["does-not-apply"] = reasons.get("does-not-apply", 0) + 1
            continue
        candidate["patch_id"] = revert._patch_id(checkout, text)
        found.append(candidate)
    found.sort(key=lambda c: revert._rank(seed, c["sha"]))
    seen, ordered = set(), []
    for candidate in found:
        if candidate["patch_id"] in seen:
            reasons["duplicate-patch"] = reasons.get("duplicate-patch", 0) + 1
            continue
        seen.add(candidate["patch_id"])
        ordered.append(candidate)
    return ordered, {reason: reasons[reason] for reason in NOT_ELIGIBLE if reasons.get(reason)}


def dependency_rejection(log):
    """Why the gate rejects a dependency revert, from its log, or None when the failure stays
    `build-failed`. An artifact that does not resolve is the revert's: it put the old version back."""
    if revert.UNRESOLVED.search(log):
        return "dependency-resolution"
    return revert.gate_failure(log)


# --------------------------------------------------------------------------------------- the set

def _files(checkout, roots, patterns):
    found = set()
    for root in roots:
        base = os.path.join(checkout, root)
        if not os.path.isdir(base):
            continue
        for pattern in patterns:
            found.update(glob.glob(os.path.join(base, "**", pattern), recursive=True))
    return sorted(os.path.relpath(p, checkout).replace("\\", "/") for p in found
                  if os.path.isfile(p))


def resources_root(project):
    module = census.measured_module(project)
    return f"{module}/src/main/resources" if module else "src/main/resources"


def mutation_targets(checkout, project):
    """Every `(path, kind, index)` the generator families draw from. `dependency-revert` targets
    are commits: see `dependency_candidates`."""
    targets = []
    resources = resources_root(project)
    for relative in _files(checkout, [resources], [f"*{e}" for e in RESOURCE_EXTENSIONS]):
        if can_resource_value(os.path.join(checkout, relative)):
            targets.append((relative, "resource-value", 0))
    for relative in _files(checkout, [f"{resources}/META-INF/services"], ["*"]):
        targets += [(relative, "services", i)
                    for i in range(services_in(os.path.join(checkout, relative)))]
    script = build_script_of(checkout, project)
    if script:
        targets.append((script, "build-script", 0))
    roots = [root.strip("/") for root in project.get("sources") or [] if root.strip("/")]
    for relative in _files(checkout, roots, ["*.groovy"]):
        targets += [(relative, "groovy-body", i)
                    for i in range(groovy_methods_in(os.path.join(checkout, relative)))]
    return targets


def _rank(seed, target):
    return hashlib.sha256(f"{seed}:{target[0]}:{target[1]}:{target[2]}".encode()).hexdigest()


def draw_order(targets, seed):
    """Seeded inside each family, families taken in turn, so every family's first draw comes
    before any family's second."""
    by_family = {family: sorted((t for t in targets if t[1] == family),
                                key=lambda t: _rank(seed, t)) for family in FAMILIES}
    ordered = []
    while any(by_family.values()):
        for family in FAMILIES:
            if by_family[family]:
                ordered.append(by_family[family].pop(0))
    return ordered


GENERATORS = {"resource-value": resource_value, "services": services,
              "build-script": build_script, "groovy-body": groovy_body}


def apply(checkout, relative, kind, index):
    """Applies a generator family's edit; returns the row's label, or None."""
    generator = GENERATORS.get(kind)
    if generator is None:
        return None
    name = generator(os.path.join(checkout, relative), index)
    return None if name is None else f"{relative}::{kind}::{name}"
