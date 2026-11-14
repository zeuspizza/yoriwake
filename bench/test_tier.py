"""Tests for `tier.py`: what a change asks of the benchmark, from the paths it touched.

Cases return a count of failed checks; one `test_*` entry point asserts.

    python3 bench/test_tier.py
"""

import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import maven_build  # noqa: E402
import tier  # noqa: E402

AGENT = "agent/src/main/java/io/github/zeuspizza/yoriwake/agent"
CORE = "core/src/main/kotlin/io/github/zeuspizza/yoriwake/gradle"
MOJO = "maven/src/main/kotlin/io/github/zeuspizza/yoriwake/maven/AuditMojo.kt"


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
    return (_expect([f"{CORE}/change/ChangeDetection.kt"], "reduced", True)
            + _expect([f"{CORE}/bytecode/InstrumentationScope.kt"], "reduced", True)
            + _expect([f"{AGENT}/capture/TouchRecorder.java"], "reduced", True)
            + _expect([f"{AGENT}/select/Selector.java", f"{CORE}/capture/Schedule.kt"],
                      "reduced", True)
            + _expect(["agent/build.gradle.kts"], "reduced", True))


def case_the_capture_session_or_a_major_release_needs_a_full_sweep():
    return (_expect([f"{AGENT}/capture/CaptureSession.java"], "full", True)
            + _expect(["README.md"], "full", True, major=True))


def case_a_change_outside_the_selector_asks_nothing():
    return (_expect(["docs/reference.md", "bench/run.py", f"agent/src/test/kotlin/X.kt"], "none",
                    False)
            + _expect([], "none", False))


def case_the_maven_plugin_alone_asks_for_the_maven_projects():
    return (_expect([MOJO], "maven", True)
            + _expect(["maven/build.gradle.kts", "README.md"], "maven", True))


def case_the_maven_plugin_s_tests_ask_nothing():
    return (_expect(["maven/src/functionalTest/kotlin/AuditFunctionalTest.kt"], "none", False)
            + _expect(["maven/src/test/kotlin/AuditMojoTest.kt"], "none", False))


def case_a_selector_change_keeps_its_tier_and_names_the_maven_paths_beside_it():
    failures = (_expect([MOJO, f"{CORE}/change/ChangeDetection.kt"], "reduced", True)
                + _expect([MOJO, f"{AGENT}/select/Selector.java"], "replay", True)
                + _expect([MOJO, f"{AGENT}/capture/CaptureSession.java"], "full", True)
                + _expect([MOJO], "full", True, major=True))
    decided = tier.decide([MOJO, f"{CORE}/change/ChangeDetection.kt"])
    if (decided.get("maven_paths"), decided["selector_paths"]) != (
            [MOJO], [f"{CORE}/change/ChangeDetection.kt"]):
        failures += fail(f"a change to both names {decided}")
    if tier.decide([f"{CORE}/change/ChangeDetection.kt"]).get("maven_paths") != []:
        failures += fail("a change to the selector alone names Maven paths")
    return failures


def case_the_maven_tier_fires_on_what_moves_the_maven_digest():
    """A Maven project's figures are stamped with `maven_build.digest`; a path asks for the Maven
    projects exactly when it moves that digest."""
    failures = 0
    with tempfile.TemporaryDirectory() as root:
        paths = (MOJO, "maven/src/main/resources/META-INF/plexus/components.xml",
                 "maven/build.gradle.kts", "maven/src/test/kotlin/AuditMojoTest.kt",
                 "maven/src/functionalTest/kotlin/AuditFunctionalTest.kt", "maven/README.md")
        for path in paths:
            os.makedirs(os.path.dirname(os.path.join(root, path)), exist_ok=True)
            with open(os.path.join(root, path), "w", encoding="utf-8") as handle:
                handle.write("before\n")
        for path in paths:
            before = maven_build.digest(root)
            with open(os.path.join(root, path), "a", encoding="utf-8") as handle:
                handle.write("after\n")
            moved = maven_build.digest(root) != before
            asked = tier.decide([path])["tier"] == "maven"
            if moved is not asked:
                failures += fail(f"{path}: the Maven digest {'moved' if moved else 'stayed'} and "
                                 f"the tier {'asked' if asked else 'did not ask'} for the Maven "
                                 f"projects")
    return failures


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
