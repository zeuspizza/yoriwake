"""
Where the yoriwake source is, for everything here that builds it, reads it or stamps it.

The source lives in github.com/zeuspizza/yoriwake; this repository keeps the corpus harness and
the records. The harness resolves the checkout here and nowhere else, so its parts cannot disagree
about which tree they measured.

    YORIWAKE_CHECKOUT=/path/to/yoriwake python bench/run.py bench/projects/demo.toml

Unset, it is the sibling directory `../yoriwake` beside this repository.
"""

import os
import re
import subprocess

ARCHIVE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SETTING = "YORIWAKE_CHECKOUT"


def root():
    """The checkout as the environment names it NOW. A caller may point each worker at its own."""
    return os.path.abspath(os.environ.get(SETTING) or ARCHIVE)


ROOT = root()

# The paths that exist only in the checkout. A caller that mutates files in both trees needs to
# know which tree a relative path belongs to; everything not named here is this repository's.
SOURCES = ("plugin/", "agent/", "samples/", "fixtures/", "gradle/", "gradlew", "gradlew.bat",
           "gradle.properties", "settings.gradle.kts",
           "scripts/yoriwake.init.gradle.kts", "scripts/junit-vintage.init.gradle.kts",
           "scripts/measure-toll.sh", "scripts/gitcount/")


def init_script_version_flag(base=None):
    """The `-D` that points `scripts/yoriwake.init.gradle.kts` at the snapshot this checkout publishes.

    The script applies the released version unless told otherwise, and every harness measures the
    jar `:plugin:publishToMavenLocal` installs, whose version is the build's own default.
    """
    build = os.path.join(base or ROOT, "plugin", "build.gradle.kts")
    with open(build, encoding="utf-8") as handle:
        found = re.search(r'^version = findProperty\("releaseVersion"\) \?: "([^"]+)"$',
                          handle.read(), re.MULTILINE)
    if found is None:
        raise SystemExit(f"could not read the plugin's snapshot version from {build}, so which jar "
                         f"the init script would apply cannot be established.")
    return f"-Dyoriwake.initScriptVersion={found.group(1)}"


def in_checkout(path):
    return any(path == p or (p.endswith("/") and path.startswith(p)) for p in SOURCES)


def uncommitted(base=None):
    """`git status --porcelain` lines for the checkout, untracked files included. Ignored files are not.

    `bench/` is left out: it is the harness, not what it builds and stamps, and it lives in the
    checkout it measures.
    """
    done = subprocess.run(["git", "-C", base or ROOT, "status", "--porcelain",
                           "--untracked-files=all"], capture_output=True, text=True)
    if done.returncode != 0:
        raise SystemExit(f"{base or ROOT} is not a git checkout of yoriwake ({done.stderr.strip()}). "
                         f"Clone github.com/zeuspizza/yoriwake there, or set {SETTING} to one.")
    return [line for line in done.stdout.splitlines() if line.strip() and not line[3:].startswith("bench/")]


def require_clean(base=None):
    """Refuses a checkout with uncommitted changes, naming them.

    A stamp names a commit, and a build from a dirty tree is a build of no commit at all: the figure
    would point a reader at public source that is not what was measured.
    """
    changed = uncommitted(base)
    if changed:
        listed = "\n".join(f"  {line}" for line in changed[:20])
        more = f"\n  ... and {len(changed) - 20} more" if len(changed) > 20 else ""
        raise SystemExit(
            f"REFUSING: the yoriwake checkout at {base or ROOT} has uncommitted changes, so no commit "
            f"describes what would be built and stamped:\n{listed}{more}\n"
            f"Commit, stash or remove them there, or point {SETTING} at a clean checkout.")


def commit(base=None, short=False):
    """The checkout's HEAD, refused when the checkout is dirty -- never a guess, never `unknown`."""
    require_clean(base)
    done = subprocess.run(["git", "-C", base or ROOT, "rev-parse"] + (["--short"] if short else [])
                          + ["HEAD"], capture_output=True, text=True)
    if done.returncode != 0 or not done.stdout.strip():
        raise SystemExit(f"could not read HEAD of the yoriwake checkout at {base or ROOT}: "
                         f"{done.stderr.strip()}")
    return done.stdout.strip()


if __name__ == "__main__":
    # The shell scripts here ask this rather than resolving the setting a second time.
    import sys
    print(init_script_version_flag(root()) if sys.argv[1:] == ["--init-script-version-flag"]
          else root())
