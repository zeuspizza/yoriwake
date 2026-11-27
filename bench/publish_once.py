"""Publishes the plugin under test once for a whole CI run, for its measuring jobs to restore.

Each record, slice, replay and history job used to build and publish the plugin itself: a few
hundred builds of the same commit per run, each resolving the plugin build's own dependencies, and
Maven Central's rate limit failed a dozen of them. One job now publishes the Gradle and the Maven
plugin, copies their group from mavenLocal into `--out`, and writes `published.json` beside it:
the commit and source digests the build was taken from. A job that restores the directory into
its own mavenLocal sets `YORIWAKE_PUBLISHED` to that stamp, and `run.publish_plugin_under_test`
then checks it against the job's checkout instead of publishing.

    python3 bench/publish_once.py --out "$RUNNER_TEMP/plugin-under-test"
"""

import argparse
import json
import os
import shutil
import sys

import run

# The plugin's group in mavenLocal: the Gradle plugin, its marker and the Maven plugin.
GROUP = os.path.join("io", "github", "zeuspizza")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", required=True, help="the directory to hand over; must not exist")
    parser.add_argument("--repository", default=os.path.expanduser(os.path.join("~", ".m2", "repository")),
                        help="the mavenLocal the publish writes to")
    args = parser.parse_args(argv)
    # The publish itself, not an earlier one's stamp.
    os.environ.pop(run.PUBLISHED_STAMP_VARIABLE, None)
    run.publish_plugin_under_test(maven=True)
    shutil.copytree(os.path.join(args.repository, GROUP), args.out)
    stamp = {"commit": run.PUBLISHED_COMMIT, "selector_digest": run.PUBLISHED_SELECTOR_DIGEST,
             "maven_digest": run.PUBLISHED_MAVEN_DIGEST}
    with open(os.path.join(args.out, "published.json"), "w", encoding="utf-8") as handle:
        json.dump(stamp, handle, indent=2)
    print(f"handed over {args.out}: {stamp}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
