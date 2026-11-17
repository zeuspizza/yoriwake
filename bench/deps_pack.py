"""A corpus project's dependency pack: what its builds downloaded, carried from job to job.

Every corpus job resolved its guest's whole dependency tree from the guest's repositories, so a
project cut into N slices resolved it N + 2 times per sweep, and a sweep's 200 jobs drew a 429 from
Maven Central. The record job now restores the pack an earlier sweep left for the same project and
pinned commit, and uploads one holding everything its builds resolved; its slices and replay job
restore that. A pack holds Gradle's `caches/modules-2` and the Maven local repository, never the
plugin under test (each job publishes and checks its own), lock files or Maven's failure markers.

    python3 bench/deps_pack.py name bench/projects/kafka.toml
    python3 bench/deps_pack.py find deps-kafka-0123456789ab --repo owner/name --branch main
    python3 bench/deps_pack.py restore "$RUNNER_TEMP/deps-in" before.json
    python3 bench/deps_pack.py report before.json
    python3 bench/deps_pack.py pack deps.tar

`name` prints the pack's artifact name, `deps-<project>-<first 12 of its pinned commit>`. `find`
prints the run id holding the newest unexpired pack of that name from a dispatched sweep of
the default branch, or nothing; it never fails. `report` prints one line, `deps: downloaded N
files, M MB`: the files present now that the snapshot did not hold.
"""

import argparse
import json
import os
import shutil
import subprocess
import sys
import tarfile
import tomllib

# The plugin under test, under its group in each layout: Maven's path, Gradle's `files-2.1` and
# `metadata-*/descriptors` directory. Packed, a later job could measure a jar it did not build.
PLUGIN_GROUP = ("io", "github", "zeuspizza")
# The workflow whose runs may hand a pack to a sweep: a fork's pull request stores its runs'
# artifacts on this repository too, so an artifact's name alone says nothing of who made it.
TRUSTED_WORKFLOW = ".github/workflows/sweep.yml"
TRUSTED_EVENT = "workflow_dispatch"


def roots(environ=None):
    """{name in the pack: directory} for Gradle's dependency cache and the Maven local repository
    the guests resolve into."""
    environ = os.environ if environ is None else environ
    home = environ.get("HOME") or os.path.expanduser("~")
    gradle = environ.get("GRADLE_USER_HOME") or os.path.join(home, ".gradle")
    return {"modules-2": os.path.join(gradle, "caches", "modules-2"),
            "repository": os.path.join(home, ".m2", "repository")}


def kept(name, relative):
    """Whether the file at `relative` (`/`-separated) under the tree `name` is a dependency a later
    job may reuse."""
    parts = relative.split("/")
    if parts[-1].endswith((".lock", ".lastUpdated")):
        return False
    if name == "repository":
        return tuple(parts[:3]) != PLUGIN_GROUP
    group = ".".join(PLUGIN_GROUP)
    if parts[0] == "files-2.1":
        return len(parts) < 2 or parts[1] != group
    if parts[0].startswith("metadata-") and len(parts) > 2 and parts[1] == "descriptors":
        return parts[2] != group
    return True


def files(trees):
    """{`<name>/<relative path>`: size} of every kept regular file under `trees`; a missing tree
    holds none."""
    found = {}
    for name, top in sorted(trees.items()):
        for directory, _, names in os.walk(top):
            for file_name in names:
                path = os.path.join(directory, file_name)
                relative = os.path.relpath(path, top).replace(os.sep, "/")
                if os.path.isfile(path) and not os.path.islink(path) and kept(name, relative):
                    found[f"{name}/{relative}"] = os.path.getsize(path)
    return found


def megabytes(size):
    return f"{size / 1_000_000:.1f} MB"


def snapshot(trees, out):
    with open(out, "w", encoding="utf-8") as handle:
        json.dump(files(trees), handle)


def report(trees, snapshot_path):
    """The line saying what the job downloaded since the snapshot; every file when there is none."""
    try:
        with open(snapshot_path, encoding="utf-8") as handle:
            before = json.load(handle)
    except (OSError, ValueError):
        before = {}
    added = {path: size for path, size in files(trees).items() if path not in before}
    return f"deps: downloaded {len(added)} files, {megabytes(sum(added.values()))}"


def pack(trees, out):
    """Writes the pack to `out`, uncompressed (jars are compressed already); its size line."""
    found = files(trees)
    with tarfile.open(out, "w") as tar:
        for entry in sorted(found):
            name, relative = entry.split("/", 1)
            tar.add(os.path.join(trees[name], *relative.split("/")), arcname=entry, recursive=False)
    return f"deps: packed {len(found)} files, {megabytes(sum(found.values()))}, " \
           f"{megabytes(os.path.getsize(out))} on disk"


def unpack(trees, pack_path):
    """Extracts the pack over `trees`, each member into the tree its first segment names; a member
    of no tree, or one the pack must not hold, is left out. The count line."""
    count = 0
    with tarfile.open(pack_path) as tar:
        for member in tar:
            name, _, relative = member.name.partition("/")
            if name not in trees or not member.isfile() or ".." in relative.split("/") \
                    or not kept(name, relative):
                continue
            member.name = relative
            try:
                tar.extract(member, trees[name], filter="data")
            except tarfile.FilterError:
                continue
            count += 1
    return f"deps: restored {count} files"


def restore(trees, directory, snapshot_path):
    """Unpacks `<directory>/deps.tar` when a download left one there, then removes it, and takes
    the snapshot `report` compares with. A pack that cannot be read costs the warm start alone."""
    pack_path = os.path.join(directory, "deps.tar")
    if os.path.isfile(pack_path):
        try:
            print(unpack(trees, pack_path))
        except (OSError, tarfile.TarError) as error:
            print(f"::warning::the dependency pack did not restore: {error}")
        shutil.rmtree(directory, ignore_errors=True)
    snapshot(trees, snapshot_path)


def pack_name(project_toml):
    """The artifact name of a project's pack: one per project and pinned commit."""
    with open(project_toml, "rb") as handle:
        project = tomllib.load(handle)
    return f"deps-{project['name']}-{project['commit'][:12]}"


def gh_api(path):
    """The parsed answer of `gh api <path>`, or None when it failed."""
    try:
        done = subprocess.run(["gh", "api", path], capture_output=True, text=True, timeout=120)
    except (OSError, subprocess.TimeoutExpired):
        return None
    if done.returncode != 0:
        return None
    try:
        return json.loads(done.stdout)
    except ValueError:
        return None


def find(name, repo, branch, api=gh_api):
    """The id of the run holding the newest unexpired artifact `name` that a dispatched sweep of
    `branch` in `repo` itself uploaded, or None."""
    listing = api(f"repos/{repo}/actions/artifacts?name={name}&per_page=100") or {}
    artifacts = [a for a in listing.get("artifacts") or [] if a.get("name") == name and not a.get("expired")]
    for artifact in sorted(artifacts, key=lambda a: a.get("created_at") or "", reverse=True):
        origin = artifact.get("workflow_run") or {}
        if origin.get("head_branch") != branch or origin.get("repository_id") is None \
                or origin.get("head_repository_id") != origin.get("repository_id"):
            continue
        run = api(f"repos/{repo}/actions/runs/{origin.get('id')}") or {}
        if run.get("path") == TRUSTED_WORKFLOW and run.get("event") == TRUSTED_EVENT:
            return origin["id"]
    return None


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("name", help="print a project's pack name").add_argument("project_toml")
    looked = sub.add_parser("find", help="print the run holding the newest trusted pack of a name")
    looked.add_argument("name")
    looked.add_argument("--repo", required=True)
    looked.add_argument("--branch", required=True)
    for command in ("report", "pack"):
        sub.add_parser(command).add_argument("path")
    restored = sub.add_parser("restore", help="unpack a downloaded pack, then snapshot")
    restored.add_argument("directory")
    restored.add_argument("snapshot")
    args = parser.parse_args(argv)
    trees = roots()
    if args.command == "name":
        print(pack_name(args.project_toml))
    elif args.command == "find":
        found = find(args.name, args.repo, args.branch)
        if found is not None:
            print(found)
    elif args.command == "restore":
        restore(trees, args.directory, args.snapshot)
    elif args.command == "report":
        print(report(trees, args.path))
    else:
        print(pack(trees, args.path))
    return 0


if __name__ == "__main__":
    sys.exit(main())
