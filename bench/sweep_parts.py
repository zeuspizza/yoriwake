"""The manifest of a sweep release's evidence parts, and the check that reads them back.

A sweep's evidence goes up as one tar split into parts, each under GitHub's 2 GiB release-asset
limit. The release's tarball, uploaded last, carries their manifest: one line per part, in order,
`name<TAB>bytes<TAB>sha256`. A reader that puts the parts together checks every one against it
first, so a part missing, short or changed fails the reader instead of extracting part of the
evidence.

    python3 bench/sweep_parts.py manifest PART... > evidence-parts.tsv
    python3 bench/sweep_parts.py cat evidence-parts.tsv DIR | tar -xf - -C OUT

`cat` writes nothing and exits 1, naming each part that does not match, unless all of them match.
"""

import hashlib
import os
import sys

MANIFEST = "evidence-parts.tsv"


def _digest(path):
    sha = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            sha.update(block)
    return sha.hexdigest()


def manifest(parts):
    return "".join(f"{os.path.basename(p)}\t{os.path.getsize(p)}\t{_digest(p)}\n" for p in parts)


def problems(manifest_path, directory):
    """Each listed part that is not in `directory` as listed; the listed paths, in order."""
    found, paths = [], []
    with open(manifest_path, encoding="utf-8") as handle:
        lines = [line.rstrip("\n").split("\t") for line in handle if line.strip()]
    if not lines:
        found.append(f"{manifest_path} lists no part")
    for name, size, sha in lines:
        path = os.path.join(directory, name)
        paths.append(path)
        if not os.path.isfile(path):
            found.append(f"{name}: missing")
        elif os.path.getsize(path) != int(size):
            found.append(f"{name}: {os.path.getsize(path)} bytes, not {size}")
        elif _digest(path) != sha:
            found.append(f"{name}: sha256 differs from the manifest")
    return found, paths


def main(argv):
    if len(argv) >= 2 and argv[0] == "manifest":
        sys.stdout.write(manifest(argv[1:]))
        return 0
    if len(argv) == 3 and argv[0] == "cat":
        found, paths = problems(argv[1], argv[2])
        if found:
            for line in found:
                print(f"::error::evidence part {line}", file=sys.stderr)
            return 1
        for path in paths:
            with open(path, "rb") as handle:
                for block in iter(lambda: handle.read(1 << 20), b""):
                    sys.stdout.buffer.write(block)
        return 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
