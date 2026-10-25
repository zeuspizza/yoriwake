"""
Builds one commit twice, each in a fresh clone, and checks that the plugin jar and the agent jar
nested in it come out byte for byte the same. A release that rebuilds the same is one anybody can
check against its tag.

The build cache is off, so the second build compiles everything again rather than reusing the
first's outputs. The version reaches the published files, so pass the one being released.

    python3 scripts/check_reproducible.py <ref> [<version>]
"""

import glob
import hashlib
import os
import subprocess
import sys
import tempfile
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def build(ref, version, into):
    subprocess.run(["git", "clone", "--quiet", "--no-local", ROOT, into], check=True)
    subprocess.run(["git", "-C", into, "checkout", "--quiet", "--detach", ref], check=True)
    command = ["./gradlew", "--quiet", "--no-build-cache", "--no-configuration-cache", ":plugin:assemble"]
    if version:
        command.append(f"-PreleaseVersion={version}")
    subprocess.run(command, cwd=into, check=True)
    # The shadow jar the release publishes as yoriwake-gradle-plugin-<version>.jar; `-main` is unshaded.
    jars = [jar for jar in glob.glob(os.path.join(into, "plugin", "build", "libs", "plugin-*.jar"))
            if not jar.endswith(("-sources.jar", "-javadoc.jar", "-main.jar"))]
    if len(jars) != 1:
        sys.exit(f"expected one plugin jar in {into}, found {jars}")
    with open(jars[0], "rb") as plugin:
        plugin_bytes = plugin.read()
    with zipfile.ZipFile(jars[0]) as jar:
        agent_bytes = jar.read("yoriwake-agent.jar")
    return {
        os.path.basename(jars[0]): hashlib.sha256(plugin_bytes).hexdigest(),
        "yoriwake-agent.jar": hashlib.sha256(agent_bytes).hexdigest(),
    }


def main(argv):
    if not 1 <= len(argv) <= 2:
        sys.exit(__doc__)
    ref, version = argv[0], argv[1] if len(argv) > 1 else None
    with tempfile.TemporaryDirectory(prefix="reproducible-") as work:
        first = build(ref, version, os.path.join(work, "first"))
        second = build(ref, version, os.path.join(work, "second"))
    for name, digest in first.items():
        print(f"{digest}  {name}")
        if second.get(name) != digest:
            sys.exit(f"{name} differs between two builds of {ref}: {digest} and {second.get(name)}")
    print(f"two builds of {ref} gave the same jars")


if __name__ == "__main__":
    main(sys.argv[1:])
