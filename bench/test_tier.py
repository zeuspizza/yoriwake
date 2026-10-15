"""Tests for `tier.py`: what a change asks of the benchmark, from the paths it touched.

Cases return a count of failed checks; one `test_*` entry point asserts.

    python3 bench/test_tier.py
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import tier  # noqa: E402

AGENT = "agent/src/main/java/io/github/zeuspizza/yoriwake/agent"
PLUGIN = "plugin/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle"


def fail(message):
    print(f"  FAIL: {message}")
    return 1


def _expect(paths, wanted, stale, major=False):
    decided = tier.decide(paths, major_release=major)
    if (decided["tier"], decided["stale"]) != (wanted, stale):
        return fail(f"{paths} (major={major}) -> {decided}, expected {wanted} stale={stale}")
    return 0


def case_selection_logic_alone_is_replayed_and_ages_the_published_figures():
    return _expect([f"{AGENT}/select/Selector.java", f"{AGENT}/select/Reason.java"], "replay", True)


def case_change_detection_scope_and_capture_need_a_reduced_sweep():
    return (_expect([f"{PLUGIN}/change/ChangeDetection.kt"], "reduced", True)
            + _expect([f"{PLUGIN}/bytecode/InstrumentationScope.kt"], "reduced", True)
            + _expect([f"{AGENT}/capture/TouchRecorder.java"], "reduced", True)
            + _expect([f"{AGENT}/select/Selector.java", f"{PLUGIN}/capture/Schedule.kt"],
                      "reduced", True)
            + _expect(["agent/build.gradle.kts"], "reduced", True))


def case_the_capture_session_or_a_major_release_needs_a_full_sweep():
    return (_expect([f"{AGENT}/capture/CaptureSession.java"], "full", True)
            + _expect(["README.md"], "full", True, major=True))


def case_a_change_outside_the_selector_asks_nothing():
    return (_expect(["docs/reference.md", "bench/run.py", f"agent/src/test/kotlin/X.kt"], "none",
                    False)
            + _expect([], "none", False))


def test_tier():
    failures = 0
    for name, case in sorted(globals().items()):
        if name.startswith("case_"):
            print(name)
            failures += case()
    assert failures == 0, f"{failures} check(s) failed"


if __name__ == "__main__":
    test_tier()
    print("ok")
