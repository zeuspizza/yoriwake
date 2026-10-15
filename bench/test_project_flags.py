"""Guards for the Gradle arguments a corpus project needs.

The defect this exists for is not a wrong flag, it is a MISSING one that reads as the project's
silence. A replay started without `daemon_java_version` fails before any toolchain is consulted and
reports "this project answered nothing" -- a sentence about the project, produced by the instrument.

Cases return a count of failed checks rather than asserting, so a function that asserted cannot be
collected by pytest and pass however many checks failed.

    python bench/test_project_flags.py
"""

import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import project_flags  # noqa: E402


def fail(message):
    print(f"  FAIL: {message}")
    return 1


def _project(body):
    handle = tempfile.NamedTemporaryFile("w", suffix=".toml", delete=False, encoding="utf-8")
    handle.write(body)
    handle.close()
    return handle.name


def case_flags_are_passed_through_in_order():
    problems = 0
    path = _project('name = "x"\nflags = ["-Pa=1", "-Pb=2"]\n')
    try:
        if project_flags.flags_for(path) != ["-Pa=1", "-Pb=2"]:
            problems += fail(f"expected both flags in order, got {project_flags.flags_for(path)}")
    finally:
        os.unlink(path)
    return problems


def case_a_project_with_nothing_to_add_yields_nothing():
    """An empty result is legitimate here, and must not read as an error."""
    problems = 0
    path = _project('name = "x"\n')
    try:
        if project_flags.flags_for(path) != []:
            problems += fail(f"expected no flags, got {project_flags.flags_for(path)}")
        if project_flags.main([path]) != 0:
            problems += fail("a project with no flags exited non-zero")
    finally:
        os.unlink(path)
    return problems


def case_daemon_jvmargs_becomes_a_gradle_property():
    problems = 0
    path = _project('name = "x"\ndaemon_jvmargs = "-Xmx4g"\n')
    try:
        produced = project_flags.flags_for(path)
        if "-Dorg.gradle.jvmargs=-Xmx4g" not in produced:
            problems += fail(f"the heap setting did not reach the daemon: {produced}")
    finally:
        os.unlink(path)
    return problems


def case_a_missing_daemon_jdk_stops_rather_than_replaying_on_the_wrong_one():
    """Refusing is the point. A replay on the wrong JVM files the machine's failure as the project's."""
    problems = 0
    path = _project('name = "x"\ndaemon_java_version = 999\n')
    try:
        project_flags.flags_for(path)
    except SystemExit as stop:
        if "999" not in str(stop):
            problems += fail(f"the refusal did not name the version it wanted: {stop}")
        if "x" not in str(stop):
            problems += fail(f"the refusal did not name the project: {stop}")
    else:
        problems += fail("a project needing an absent JDK was allowed to run on whatever is here")
    finally:
        os.unlink(path)
    return problems


def case_a_missing_project_file_is_reported_not_treated_as_empty():
    problems = 0
    if project_flags.main(["/nonexistent/nope.toml"]) == 0:
        problems += fail("a missing project file exited 0, so a caller would splice in no flags")
    if project_flags.main([]) == 0:
        problems += fail("no argument exited 0")
    return problems


CASES = (
    case_flags_are_passed_through_in_order,
    case_a_project_with_nothing_to_add_yields_nothing,
    case_daemon_jvmargs_becomes_a_gradle_property,
    case_a_missing_daemon_jdk_stops_rather_than_replaying_on_the_wrong_one,
    case_a_missing_project_file_is_reported_not_treated_as_empty,
)


def case_every_case_in_this_file_is_registered():
    defined = {
        name for name, value in globals().items()
        if name.startswith("case_") and callable(value)
        and name != "case_every_case_in_this_file_is_registered"
    }
    missing = sorted(defined - {case.__name__ for case in CASES})
    return fail(f"defined but not in CASES: {missing}") if missing else 0


def main():
    problems = 0
    for case in CASES + (case_every_case_in_this_file_is_registered,):
        print(case.__name__)
        problems += case()
    if problems:
        print(f"\n{problems} failed check(s)")
        return 1
    print(f"\n{len(CASES) + 1} cases, all green")
    return 0


def test_project_flags():
    assert main() == 0


if __name__ == "__main__":
    sys.exit(main())
