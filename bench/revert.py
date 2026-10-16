"""Real commits as a population of their own: each row reverts one recent commit at the pin.

The mutation sets ask whether selection catches an edit the harness made up. This set asks it of
edits developers made. For a commit C in the census window that changes main code under the
project's `sources` roots, a row applies the reverse of C's diff, limited to those roots, to the
pinned tree and keeps the pinned tests. The map was captured at the pin, so tree and map agree;
the tests C added or changed now run against the code before C, so a revert breaks the tests
closest to the change. Rows run through `run.py`'s mutation loop and carry everything a mutation
row does.

What it may claim: real change shapes in their real proportions. It is not adversarial, and its
failures sit in tests that often execute the changed class, so it does not replace the mutation
sets; a figure drawn from it says so.

Per commit:

- not eligible, each counted by reason: no main code under the roots, a binary file, a rename with
  one side outside the roots, a comment-only diff, a reverse patch that does not apply at the pin,
  and a patch identical to one drawn earlier (patch-id);
- the main-code revert is compiled first; when it fails only in test compilation (the pinned
  tests use what C added), the full revert of C's main code and the measured module's tests is
  tried once and the row is stamped `revert_variant: full`;
- rejected after applying, each counted by reason and by census label: main compile, test
  compile (both variants), dependency resolution, a style gate. A compile result is never a recall
  outcome.

Commits are drawn in a seeded order inside three strata taken from the census labels (single file,
several files, several modules), round-robin, so every stratum is reached. Nothing in the order or
in a stratum's stop reads whether a failure was missed.

The dry run is the compile gate alone, over every eligible commit, and measures how many reverts
survive it per census label:

    python3 bench/revert.py --dry-run --checkouts DIR bench/projects/resilience4j.toml ...

Fewer than a third surviving, overall or in a family only reverts reach (signature, visibility,
several files, several modules), widens the window once, from 200 to 400 commits; a second
shortfall is reported as it is.
"""

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tomllib
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import census  # noqa: E402

SET = "revert/v1"
# The census window, and the one it may widen to once. See `window_decision`.
WINDOW = census.DEFAULT_WINDOW
WIDENED_WINDOW = 400
# THE WINDOW THE SWEEP READS: widened once. The dry run over resilience4j, ktlint and okio found
# 0 of 4 signature reverts surviving the gate at 200 commits, and 2 of 7 at 400, still under a
# third; a second shortfall widens nothing, so signature is reported as it stands.
SWEEP_WINDOW = WIDENED_WINDOW
SURVIVAL_FLOOR = 1 / 3
# The families no mutation set builds: a revert is their only source.
REVERT_ONLY = ("signature", "visibility", "several-files", "several-modules")
STRATA = ("single-file", "several-files", "several-modules")
# Rows of miss-possible evidence a shape needs before it gets a figure of its own.
MISS_POSSIBLE_TARGET = 4
NOT_ELIGIBLE = ("no-main-code", "binary", "rename-crosses-roots", "comment-only",
                "does-not-apply", "duplicate-patch")
REJECTIONS = ("main-compile", "test-compile", "dependency-resolution", "style-gate")
# What a revert does to what C did: a member C added is one the row deletes.
INVERSE = {"member-added": "member-deleted", "member-deleted": "member-added",
           "file-added": "file-deleted", "file-deleted": "file-added"}


def _git(checkout, *args, stdin=None, check=True):
    done = subprocess.run(["git", "-C", checkout, "-c", "core.quotePath=false", *args],
                          input=stdin, capture_output=True, text=True, errors="replace")
    if check and done.returncode != 0:
        raise subprocess.CalledProcessError(done.returncode, args, done.stdout, done.stderr)
    return done


def inverted(labels):
    """C's census labels as the revert makes them."""
    return sorted({INVERSE.get(label, label) for label in labels})


def stratum(labels):
    """The census stratum a commit is drawn from."""
    if "several-modules" in labels:
        return "several-modules"
    if "several-files" in labels:
        return "several-files"
    return "single-file"


def _under(path, roots):
    return any(path == root or path.startswith(f"{root}/") for root in roots)


def _roots(project):
    return [root.strip("/") for root in project.get("sources") or [] if root.strip("/")]


def _module_test(path, module):
    """A test source of the measured module, which the full revert takes back as well."""
    inside = not module or path.startswith(f"{module}/")
    return inside and bool(census.TEST_SET.search(path[len(module) + 1:] if module else path))


def _parent(checkout, sha):
    parents = _git(checkout, "rev-list", "--parents", "-n", "1", sha).stdout.split()[1:]
    if parents:
        return parents[0]
    return _git(checkout, "hash-object", "-t", "tree", "/dev/null").stdout.strip()


def _changes(checkout, parent, sha):
    """`(status, old, new)` per changed path, with renames detected."""
    entries = []
    for line in _git(checkout, "diff", "--name-status", "-M", parent, sha).stdout.splitlines():
        parts = line.split("\t")
        if parts[0].startswith(("R", "C")) and len(parts) == 3:
            entries.append((parts[0][0], parts[1], parts[2]))
        elif len(parts) == 2:
            entries.append((parts[0][0], parts[1], parts[1]))
    return entries


def patch(checkout, candidate, variant="main"):
    """The diff of C over the paths the variant reverts, ready for `git apply -R`."""
    paths = candidate["paths"] if variant == "main" else candidate["full_paths"]
    return _git(checkout, "diff", "--binary", "-M", "--full-index", candidate["parent"],
                candidate["sha"], "--", *paths).stdout


def applies(checkout, text):
    return bool(text) and _git(checkout, "apply", "-R", "--check", "--whitespace=nowarn", "-",
                               stdin=text, check=False).returncode == 0


def apply(checkout, candidate, variant="main"):
    """Reverts C's paths for `variant` in the working tree. False when the patch does not apply."""
    text = patch(checkout, candidate, variant)
    return bool(text) and _git(checkout, "apply", "-R", "--whitespace=nowarn", "-", stdin=text,
                               check=False).returncode == 0


def _patch_id(checkout, text):
    out = _git(checkout, "patch-id", "--stable", stdin=text).stdout.split()
    return out[0] if out else None


def _comment_only(checkout, parent, sha, paths):
    code = [p for p in paths if p.endswith(census.CODE)]
    text = _git(checkout, "diff", "--unified=0", "--no-color", "-M", parent, sha, "--",
                *code).stdout
    changed = [line[1:] for line in text.splitlines()
               if line[:1] in "+-" and not line.startswith(("+++", "---"))]
    # A pure move changes no line and is not a comment edit: it renames the class.
    return bool(changed) and all(census._comment_or_blank(line) for line in changed)


def examine(checkout, project, sha, labels, module):
    """One commit as a candidate, or `(None, reason)` when it is not eligible. The patch-id check
    is the draw's, since it depends on what was drawn before."""
    roots = _roots(project)
    parent = _parent(checkout, sha)
    changes = _changes(checkout, parent, sha)
    for status, old, new in changes:
        if status == "R" and _under(old, roots) != _under(new, roots):
            return None, "rename-crosses-roots"
    paths = sorted({p for _, old, new in changes for p in (old, new) if _under(p, roots)})
    if not any(p.endswith(census.CODE) for p in paths):
        return None, "no-main-code"
    numstat = _git(checkout, "diff", "--numstat", "-M", parent, sha, "--", *paths).stdout
    if any(line.startswith("-\t-\t") for line in numstat.splitlines()):
        return None, "binary"
    if _comment_only(checkout, parent, sha, paths):
        return None, "comment-only"
    tests = sorted({p for _, old, new in changes for p in (old, new)
                    if not _under(p, roots) and _module_test(p, module)})
    touched = {p for _, old, new in changes for p in (old, new)}
    candidate = {"sha": sha, "parent": parent, "labels": sorted(labels),
                 "revert_labels": inverted(labels), "stratum": stratum(labels),
                 "paths": paths, "full_paths": paths + tests,
                 "scope": sorted(touched - set(paths))}
    text = patch(checkout, candidate)
    if not applies(checkout, text):
        return None, "does-not-apply"
    candidate["patch_id"] = _patch_id(checkout, text)
    return candidate, None


def _rank(seed, sha):
    return hashlib.sha256(f"{seed}:{sha}".encode()).hexdigest()


def draw_order(candidates, seed):
    """Candidates in draw order: seeded inside each stratum, strata taken in turn. A patch
    identical to one earlier in that order is dropped. Returns `(ordered, duplicates)`."""
    by_stratum = {name: sorted((c for c in candidates if c["stratum"] == name),
                               key=lambda c: _rank(seed, c["sha"])) for name in STRATA}
    ordered = []
    while any(by_stratum.values()):
        for name in STRATA:
            if by_stratum[name]:
                ordered.append(by_stratum[name].pop(0))
    seen, kept, duplicates = set(), [], 0
    for candidate in ordered:
        if candidate.get("patch_id") in seen:
            duplicates += 1
            continue
        seen.add(candidate.get("patch_id"))
        kept.append(candidate)
    return kept, duplicates


def candidates(checkout, project, seed, window=SWEEP_WINDOW, commits=None):
    """The eligible commits of the window in draw order, and the count of each not-eligible reason.

    `commits` is the census of the window, `[{"sha", "labels"}]`; read from the checkout when not
    given. The checkout must be clean at the pin: the reverse patch is checked against it.
    """
    module = census.measured_module(project)
    if commits is None:
        commits = census.census_project(project, checkout, window)["commits"]
    found, reasons = [], Counter()
    for commit in commits:
        candidate, reason = examine(checkout, project, commit["sha"], commit["labels"], module)
        if reason:
            reasons[reason] += 1
        else:
            found.append(candidate)
    ordered, duplicates = draw_order(found, seed)
    if duplicates:
        reasons["duplicate-patch"] += duplicates
    return ordered, {reason: reasons[reason] for reason in NOT_ELIGIBLE if reasons[reason]}


def label(candidate):
    """A row's `target`."""
    return f"revert::{candidate['sha']}"


FAILED_TASK = re.compile(r"Execution failed for task '([^']+)'|> Task (\S+) FAILED")
UNRESOLVED = re.compile(r"Could not resolve (?:all |[\w.-]+:[\w.-]+)")
STYLE_TASK = re.compile(r"checkstyle|spotless|ktlint|detekt|lint|license|apicheck|pmd|spotbugs",
                        re.IGNORECASE)


def gate_failure(log):
    """Why the compile gate failed, from its log: one of `REJECTIONS`, or None when the cause is
    not one the revert made (a daemon that died, a missing toolchain), which stays
    `build-failed`."""
    if UNRESOLVED.search(log):
        return "dependency-resolution"
    names = {(a or b).rsplit(":", 1)[-1] for a, b in FAILED_TASK.findall(log)}
    compiles = [n for n in names if re.match(r"(compile|kapt|ksp)", n)]
    if any("Test" not in n for n in compiles):
        return "main-compile"
    if compiles:
        return "test-compile"
    if any(STYLE_TASK.search(n) for n in names):
        return "style-gate"
    return None


def _read(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as handle:
            return handle.read()
    except OSError:
        return ""


def gate(checkout, candidate, build, reset, log_path, stalled=None):
    """Compiles the applied main-code revert, and the full revert once when only the tests failed.

    `build(log_path)` compiles the tree and returns its exit code; `reset()` puts the tree back
    to the pin. Returns `(code, variant, reason)`: `reason` is the rejection, None when the tree
    compiled, stalled, or failed for a cause the revert did not make.
    """
    code = build(log_path)
    if code in (0, stalled):
        return code, "main", None
    reason = gate_failure(_read(log_path))
    if reason != "test-compile":
        return code, "main", reason
    if not reset() or not apply(checkout, candidate, "full"):
        return code, "full", "test-compile"
    full_log = re.sub(r"(\.log)?$", "-full.log", log_path, count=1)
    code = build(full_log)
    if code in (0, stalled):
        return code, "full", None
    return code, "full", gate_failure(_read(full_log))


def rejection_counts(rejections):
    """`[target, reason, variant, labels]` rejections, counted by reason and by census label."""
    counts = {}
    for _, reason, _, labels in rejections:
        entry = counts.setdefault(reason, {"count": 0, "labels": {}})
        entry["count"] += 1
        for name in labels:
            entry["labels"][name] = entry["labels"].get(name, 0) + 1
    return counts


def should_draw(stratum_name, findings, stratum_of, limit, narrowed_target, counts_toward):
    """Whether a stratum still draws: under its row cap and its narrowed-and-induced target.

    Reads the rows' decisions and induced failures through `counts_toward`, never what was missed.
    """
    rows = [row for row in findings if stratum_of.get(row.get("target")) == stratum_name]
    if len(rows) >= limit:
        return False
    reached = sum(1 for row in rows if counts_toward(row))
    if narrowed_target is not None and reached >= narrowed_target:
        return False
    return True


def reach(findings, counts_toward, target=MISS_POSSIBLE_TARGET):
    """Per inverted census label: rows, rows where a miss was possible, and whether the shape has
    enough of them for a revert figure. One short of `target` is "not reached by revert/v1"."""
    result = {}
    for row in findings:
        for name in row.get("revert_labels") or []:
            entry = result.setdefault(name, {"rows": 0, "miss_possible": 0})
            entry["rows"] += 1
            entry["miss_possible"] += 1 if counts_toward(row) else 0
    for entry in result.values():
        entry["reached"] = entry["miss_possible"] >= target
    return dict(sorted(result.items()))


def not_reached(reached):
    return [name for name, entry in reached.items() if not entry["reached"]]


def survival(rows):
    """Per census label of the applied reverts, how many survived the gate; `overall` as well."""
    def share(of):
        survived = sum(1 for row in of if row["survived"])
        return {"applied": len(of), "survived": survived,
                "rate": round(survived / len(of), 4) if of else None}
    labels = sorted({name for row in rows for name in row["labels"]})
    return {"overall": share(rows),
            "by_label": {name: share([r for r in rows if name in r["labels"]]) for name in labels}}


def shortfall(result):
    """The families under the survival floor: `overall`, or a family only reverts reach."""
    short = []
    if result["overall"]["applied"] and result["overall"]["rate"] < SURVIVAL_FLOOR:
        short.append("overall")
    for name in REVERT_ONLY:
        entry = result["by_label"].get(name)
        if entry and entry["applied"] and entry["rate"] < SURVIVAL_FLOOR:
            short.append(name)
    return short


def window_decision(by_window):
    """The window the sweep reads, from dry-run survival `{window: survival}`.

    A shortfall at the first window widens it once; a shortfall at the widened one is reported and
    widens nothing.
    """
    short = shortfall(by_window[WINDOW])
    if not short:
        return {"window": WINDOW, "short": [], "widen": False}
    if WIDENED_WINDOW not in by_window:
        return {"window": WIDENED_WINDOW, "short": short, "widen": True}
    return {"window": WIDENED_WINDOW, "short": shortfall(by_window[WIDENED_WINDOW]),
            "widen": False}


def dry_run(checkout, project, seed, build, reset, log_dir, window=WINDOW, stalled=None,
            commits=None):
    """Every eligible commit through the gate, no tests. Returns `(rows, not_eligible)`."""
    ordered, not_eligible = candidates(checkout, project, seed, window, commits)
    rows = []
    for candidate in ordered:
        if not reset():
            raise SystemExit(f"{checkout} could not be reset; stopping the dry run")
        if not apply(checkout, candidate):
            not_eligible["does-not-apply"] = not_eligible.get("does-not-apply", 0) + 1
            continue
        log_path = os.path.join(log_dir, f"gate-{candidate['sha'][:12]}.log")
        code, variant, reason = gate(checkout, candidate, build, reset, log_path, stalled)
        rows.append({"sha": candidate["sha"], "labels": candidate["labels"],
                     "stratum": candidate["stratum"], "variant": variant, "code": code,
                     "reason": reason, "survived": code == 0})
        print(f"  {candidate['sha'][:12]} {candidate['stratum']:16} {variant:4} "
              f"{'ok' if code == 0 else reason or f'exit {code}'}", flush=True)
    reset()
    return rows, not_eligible


def _scratch_clone(source, dest, commit):
    """A clone sharing `source`'s objects, so the source is never written to."""
    if not os.path.isdir(os.path.join(dest, ".git")):
        subprocess.run(["git", "clone", "--quiet", "--shared", "--no-checkout", source, dest],
                       check=True)
    subprocess.run(["git", "-C", dest, "checkout", "--quiet", "--force", commit], check=True)
    subprocess.run(["git", "-C", dest, "clean", "-fdq"], check=True)
    return dest


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("projects", nargs="+", help="project files")
    parser.add_argument("--dry-run", action="store_true", required=True,
                        help="compile every eligible revert, run no tests")
    parser.add_argument("--checkouts", required=True,
                        help="directory holding one clone per project, read and never written")
    parser.add_argument("--scratch", default=os.path.join(HERE, "logs", "revert-dry-run"),
                        help="where the working clones and gate logs go")
    parser.add_argument("--window", type=int, default=WINDOW)
    parser.add_argument("--seed", type=int, default=None, help="draw seed (default: the sweep's)")
    parser.add_argument("--out", default=None, help="the summary JSON (default: under --scratch)")
    args = parser.parse_args(argv)

    import run  # noqa: PLC0415  (Gradle, staging and the classes task are the sweep's own)
    seed = run.DEFAULT_TARGET_SEED if args.seed is None else args.seed
    projects = []
    for path in args.projects:
        with open(path, "rb") as handle:
            project = tomllib.load(handle)
        project.setdefault("name", os.path.splitext(os.path.basename(path))[0])
        projects.append(project)
    sources = census.clones(projects, args.checkouts)
    summary = {"window": args.window, "seed": seed, "classifier": census.CLASSIFIER,
               "projects": {}}
    pooled = []
    for project in projects:
        source = sources.get(project["name"])
        if not source:
            print(f"{project['name']}: no clone under {args.checkouts}; skipped", flush=True)
            continue
        work = os.path.join(args.scratch, project["name"])
        checkout = _scratch_clone(source, os.path.join(work, "checkout"), project["commit"])
        run.stage_checkout(checkout, project)
        module = project.get("module") or ""
        task = run.test_classes_task(project)
        task_args = [f":{module}:{task}" if module else task]

        def build(log_path, checkout=checkout, project=project, task_args=task_args):
            return run.gradle(checkout, project, task_args, log_path)[0]

        def reset(checkout=checkout):
            return run.restore(checkout)

        os.makedirs(work, exist_ok=True)
        print(f"{project['name']}: compiling the pinned tree", flush=True)
        reset()
        if build(os.path.join(work, "gate-pin.log")) != 0:
            print(f"{project['name']}: the pinned tree does not compile; skipped", flush=True)
            continue
        rows, not_eligible = dry_run(checkout, project, seed, build, reset, work, args.window,
                                     run.STALLED)
        result = survival(rows)
        summary["projects"][project["name"]] = {
            "rows": rows, "not_eligible": not_eligible, "survival": result,
            "rejected": dict(Counter(row["reason"] or f"exit {row['code']}" for row in rows
                                     if not row["survived"])),
            "full_variant_survived": sum(1 for r in rows
                                         if r["variant"] == "full" and r["survived"])}
        pooled += rows
        print(f"{project['name']}: {result['overall']['survived']} of "
              f"{result['overall']['applied']} survived; not eligible {not_eligible}", flush=True)
    summary["pooled"] = survival(pooled)
    summary["short"] = shortfall(summary["pooled"])
    out = args.out or os.path.join(args.scratch, f"dry-run-{args.window}.json")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", encoding="utf-8") as handle:
        json.dump(summary, handle, indent=1, sort_keys=True)
        handle.write("\n")
    for name, entry in sorted(summary["pooled"]["by_label"].items()):
        print(f"  {name:20} {entry['survived']:4} of {entry['applied']:4}", flush=True)
    print(f"pooled: {summary['pooled']['overall']}; under a third: {summary['short'] or 'none'}",
          flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
