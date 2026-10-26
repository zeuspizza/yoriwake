#!/usr/bin/env python3
"""Builds the Maven Central upload bundle of one artifact from a local Maven repository.

    python3 scripts/central_bundle.py <repository> <group> <artifact> <version> <bundle.zip> [--signed]

Central refuses a deployment whose jar, POM, sources or javadoc lack an MD5 or SHA-1 checksum or,
once published, a signature, or whose POM lacks a name, description, URL, licence, SCM or
developer. It refuses only after the upload, so this checks the same things first: the release
rehearsal runs it unsigned on the files `verify` built, and the publish job runs it with
`--signed` before anything is sent.
"""
import hashlib
import os
import sys
import xml.etree.ElementTree as ET
import zipfile

CLASSIFIED = ("", "-sources", "-javadoc")
POM_FIELDS = ("name", "description", "url", "licenses/license/name", "scm/url", "developers/developer/name")
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def published(artifact, version):
    """The jar, sources, javadoc and POM, by name."""
    return [f"{artifact}-{version}{c}.jar" for c in CLASSIFIED] + [f"{artifact}-{version}.pom"]


def required(artifact, version, signed):
    """Every file the bundle must hold, by name."""
    names = []
    for name in published(artifact, version):
        names += [name, name + ".md5", name + ".sha1"] + ([name + ".asc"] if signed else [])
    return names


def problems(directory, artifact, version, signed):
    """What Central would refuse in `directory`, as sentences; empty when it would take it."""
    found = []
    for name in required(artifact, version, signed):
        if not os.path.isfile(os.path.join(directory, name)):
            found.append(f"{name} is missing")
    for name in published(artifact, version):
        for suffix, algorithm in ((".md5", "md5"), (".sha1", "sha1")):
            path, digest = os.path.join(directory, name), os.path.join(directory, name + suffix)
            if os.path.isfile(path) and os.path.isfile(digest):
                with open(path, "rb") as f:
                    actual = hashlib.new(algorithm, f.read()).hexdigest()
                with open(digest, encoding="utf-8") as f:
                    if f.read().split()[0].lower() != actual:
                        found.append(f"{name}{suffix} does not match {name}")
    pom = os.path.join(directory, f"{artifact}-{version}.pom")
    if os.path.isfile(pom):
        root = ET.parse(pom).getroot()
        for field in POM_FIELDS:
            node = root.find("/".join(f"m:{part}" for part in field.split("/")), NS)
            if node is None or not (node.text or "").strip():
                found.append(f"the POM has no {field}")
    return found


def bundle(repository, group, artifact, version, out, signed):
    """Writes the bundle; returns the problems that stopped it, empty on success."""
    relative = os.path.join(*group.split("."), artifact, version)
    directory = os.path.join(repository, relative)
    found = problems(directory, artifact, version, signed)
    if found:
        return found
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zipped:
        for name in sorted(os.listdir(directory)):
            if name.startswith("maven-metadata"):
                continue
            zipped.write(os.path.join(directory, name), os.path.join(relative, name).replace(os.sep, "/"))
    return []


def main(argv):
    signed = "--signed" in argv
    args = [a for a in argv if a != "--signed"]
    if len(args) != 5:
        print(__doc__.strip().splitlines()[2].strip(), file=sys.stderr)
        return 2
    found = bundle(*args, signed)
    for problem in found:
        print(f"Maven Central would refuse it: {problem}", file=sys.stderr)
    if not found:
        print(f"bundle written: {args[4]}")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
