"""
Unit tests for `central_bundle.py`, the check that runs before a Maven Central upload.

THE FAILURE THIS EXISTS TO PREVENT is an upload Central refuses after the Gradle Plugin Portal
already has the version: the release is then half published, and only a new version mends it.

Run: `python scripts/test_central_bundle.py`
"""

import hashlib
import os
import sys
import tempfile
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import central_bundle as cb  # noqa: E402

GROUP, ARTIFACT, VERSION = "io.github.zeuspizza", "yoriwake-maven-plugin", "1.2.3"
POM = """<project xmlns="http://maven.apache.org/POM/4.0.0">
  <name>yoriwake</name><description>d</description><url>https://example.org</url>
  <licenses><license><name>Apache</name></license></licenses>
  <scm><url>https://example.org/scm</url></scm>
  <developers><developer><name>The authors</name></developer></developers>
</project>
"""


def fail(message):
    print(f"  FAIL: {message}")
    return 1


def repository(signed=True, pom=POM, skip=()):
    """A local repository holding the artifact, with its checksums and, `signed`, signatures."""
    root = tempfile.mkdtemp()
    directory = os.path.join(root, *GROUP.split("."), ARTIFACT, VERSION)
    os.makedirs(directory)
    for name in cb.published(ARTIFACT, VERSION):
        if name in skip:
            continue
        data = pom.encode() if name.endswith(".pom") else name.encode()
        with open(os.path.join(directory, name), "wb") as f:
            f.write(data)
        for algorithm in ("md5", "sha1"):
            with open(os.path.join(directory, f"{name}.{algorithm}"), "w") as f:
                f.write(hashlib.new(algorithm, data).hexdigest())
        if signed:
            with open(os.path.join(directory, name + ".asc"), "w") as f:
                f.write("signature")
    with open(os.path.join(directory, "maven-metadata-local.xml"), "w") as f:
        f.write("<metadata/>")
    return root, directory


def run(root, signed=True):
    out = os.path.join(tempfile.mkdtemp(), "bundle.zip")
    return cb.bundle(root, GROUP, ARTIFACT, VERSION, out, signed), out


def case_a_complete_signed_artifact_is_bundled_under_its_path_without_metadata():
    root, _ = repository()
    found, out = run(root)
    if found:
        return fail(f"refused a complete artifact: {found}")
    names = zipfile.ZipFile(out).namelist()
    prefix = f"io/github/zeuspizza/{ARTIFACT}/{VERSION}/"
    if len(names) != 16 or not all(n.startswith(prefix) for n in names):
        return fail(f"bundle holds {names}")
    return fail("maven metadata was bundled") if any("maven-metadata" in n for n in names) else 0


def case_an_unsigned_artifact_is_refused_when_signatures_are_asked_for():
    root, _ = repository(signed=False)
    found, _ = run(root, signed=True)
    return 0 if any(".asc is missing" in p for p in found) else fail(f"no missing signature named: {found}")


def case_the_rehearsal_checks_an_unsigned_artifact_without_asking_for_signatures():
    root, _ = repository(signed=False)
    found, _ = run(root, signed=False)
    return 0 if not found else fail(f"refused an unsigned rehearsal: {found}")


def case_a_missing_javadoc_jar_is_named():
    root, _ = repository(skip=(f"{ARTIFACT}-{VERSION}-javadoc.jar",))
    found, _ = run(root)
    return 0 if any("-javadoc.jar is missing" in p for p in found) else fail(f"javadoc not named: {found}")


def case_a_checksum_that_does_not_match_is_named():
    root, directory = repository()
    with open(os.path.join(directory, f"{ARTIFACT}-{VERSION}.jar.sha1"), "w") as f:
        f.write("0" * 40)
    found, _ = run(root)
    return 0 if any(".jar.sha1 does not match" in p for p in found) else fail(f"bad checksum not named: {found}")


def case_a_pom_without_a_licence_is_refused():
    root, _ = repository(pom=POM.replace("<licenses><license><name>Apache</name></license></licenses>", ""))
    found, _ = run(root)
    return 0 if "the POM has no licenses/license/name" in found else fail(f"licence not named: {found}")


CASES = (case_a_complete_signed_artifact_is_bundled_under_its_path_without_metadata,
         case_an_unsigned_artifact_is_refused_when_signatures_are_asked_for,
         case_the_rehearsal_checks_an_unsigned_artifact_without_asking_for_signatures,
         case_a_missing_javadoc_jar_is_named,
         case_a_checksum_that_does_not_match_is_named,
         case_a_pom_without_a_licence_is_refused)


def test_central_bundle():
    assert sum(case() for case in CASES) == 0


def main():
    failed = 0
    for case in CASES:
        print(case.__name__)
        failed += case()
    print(f"\n{'all passed' if not failed else 'FAILURES'}: {failed} failed checks")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
