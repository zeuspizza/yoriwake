"""What a change asks of the benchmark, from the paths it touched.

- `none`: nothing under the selector's sources (`run.SELECTOR_SOURCE_DIRS` and `_FILES`) moved, so
  the published figures still describe this selector.
- `replay`: only selection logic (`agent/.../select/`) moved. `replay_decisions.py` re-asks every
  recorded row of the current selector and publishes a pass or fail; it never republishes figures.
- `reduced`: change detection, scope derivation, capture code or anything else under the selector's
  sources moved. The reduced arm re-measures recall.
- `full`: `CaptureSession` moved, which is how a capture rewrite shows, or the maintainer names a
  major release. Every project is swept again.

Any tier but `none` moves `selector_digest`, so the published figures are stale until a sweep
replaces them.

    python3 bench/tier.py BASE HEAD [--major-release]
"""

import argparse
import json
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from run import SELECTOR_SOURCE_DIRS, SELECTOR_SOURCE_FILES  # noqa: E402

SELECTION = "agent/src/main/java/io/github/zeuspizza/yoriwake/agent/select/"
CAPTURE_SESSION = "agent/src/main/java/io/github/zeuspizza/yoriwake/agent/capture/CaptureSession.java"


def decide(paths, major_release=False):
    selector = [path for path in paths if path in SELECTOR_SOURCE_FILES
                or any(path.startswith(f"{directory}/") for directory in SELECTOR_SOURCE_DIRS)]
    if major_release or CAPTURE_SESSION in selector:
        chosen = "full"
    elif not selector:
        chosen = "none"
    elif all(path.startswith(SELECTION) for path in selector):
        chosen = "replay"
    else:
        chosen = "reduced"
    return {"tier": chosen, "stale": bool(selector) or major_release, "selector_paths": selector}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("base")
    parser.add_argument("head")
    parser.add_argument("--major-release", action="store_true")
    args = parser.parse_args(argv)
    paths = subprocess.run(["git", "diff", "--name-only", f"{args.base}...{args.head}"],
                           check=True, capture_output=True, text=True).stdout.split()
    print(json.dumps(decide(paths, args.major_release)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
