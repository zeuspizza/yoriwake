"""The Gradle arguments a corpus project needs, for callers that are not the sweep.

`bench/run.py` has always read `flags`, `daemon_jvmargs` and `daemon_java_version` out of a
project file and put them on every build it starts. `scripts/explain-replay.sh` never did: it took
one free-form extra-arguments string and passed the checkout's own wrapper nothing else. So a replay
of micronaut-core ran without `-Pmicronaut.jacoco.enabled=true` and recorded nothing, and a replay of
ktlint or spring-boot started a daemon on the wrong JDK and failed before any toolchain was
consulted -- both reported as "this project answered nothing", which reads as a fact about the
project and is a fact about the instrument.

This prints those arguments so a shell caller can splice them in, rather than a second
implementation of the same three keys. The JDK lookup in particular is worth not duplicating: it
finds a daemon by major version under Gradle's provisioned toolchains, so no committed file holds
one machine's directory layout.

    python3 bench/project_flags.py bench/projects/micronaut-core.toml
    -Dorg.gradle.java.home=/Users/…/jdk-25 -Dorg.gradle.jvmargs=-Xmx4g -Pmicronaut.jacoco.enabled=true
"""

import os
import sys
import tomllib

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

from run import daemon_jvm_flags, guest_flags  # noqa: E402  (the one implementation of these keys)


def flags_for(path):
    with open(path, "rb") as handle:
        project = tomllib.load(handle)
    # daemon_jvm_flags raises SystemExit naming the project when a required JDK is absent, which is
    # the behaviour a caller wants: a replay on the wrong daemon produces rows that look like the
    # project's silence rather than the machine's.
    return daemon_jvm_flags(project) + guest_flags(project)


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    if len(argv) != 1:
        print("usage: project_flags.py <bench/projects/NAME.toml>", file=sys.stderr)
        return 2
    if not os.path.isfile(argv[0]):
        print(f"refusing: no such project file: {argv[0]}", file=sys.stderr)
        return 1
    print(" ".join(flags_for(argv[0])))
    return 0


if __name__ == "__main__":
    sys.exit(main())
