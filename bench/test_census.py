"""Tests for `census.py`: each commit of a project's history is labelled by the change it makes.

Cases return a count of failed checks; one `test_*` entry point asserts.

    python3 bench/test_census.py
"""

import json
import os
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import census  # noqa: E402

JAVA = "core/src/main/java/dev/sample/Alpha.java"
KOTLIN = "core/src/main/kotlin/dev/sample/Beta.kt"
OTHER = "other/src/main/java/dev/sample/Gamma.java"

ALPHA = """package dev.sample;

public class Alpha {
    private static final int LIMIT = 3;

    public int twice(int n) {
        return n * 2;
    }

    public void log(String message) {
        System.out.println(message);
    }

    public int unused() {
        return 0;
    }
}
"""

BETA = """package dev.sample

class Beta {
    fun count(): Int {
        return 1
    }

    fun touch() {
        println("touch")
    }
}
"""


def fail(message):
    print(f"  FAIL: {message}")
    return 1


class Repo:
    """A throwaway git repository whose commits are written one scenario at a time."""

    def __init__(self, root):
        self.root = root
        os.makedirs(root, exist_ok=True)
        self.git("init", "-q", "-b", "main")

    def git(self, *args):
        return subprocess.run(["git", "-C", self.root, "-c", "user.email=t@t", "-c", "user.name=t",
                               "-c", "commit.gpgsign=false", *args],
                              check=True, capture_output=True, text=True).stdout

    def write(self, path, text):
        os.makedirs(os.path.dirname(os.path.join(self.root, path)) or self.root, exist_ok=True)
        with open(os.path.join(self.root, path), "w", encoding="utf-8") as handle:
            handle.write(text)

    def edit(self, path, old, new):
        with open(os.path.join(self.root, path), encoding="utf-8") as handle:
            text = handle.read()
        assert old in text, old
        self.write(path, text.replace(old, new, 1))

    def commit(self, message):
        self.git("add", "-A")
        self.git("commit", "-q", "-m", message)
        return self.git("rev-parse", "HEAD").strip()


def _history(root):
    """One commit per scenario the census must tell apart, keyed by name."""
    repo = Repo(root)
    repo.write(JAVA, ALPHA)
    repo.write(KOTLIN, BETA)
    repo.write(OTHER, "package dev.sample;\npublic class Gamma {\n    int g() {\n        return 1;\n    }\n}\n")
    repo.write("gradle/libs.versions.toml", "[versions]\nasm = \"9.7\"\n")
    repo.write("core/src/main/resources/message.txt", "hello\n")
    repo.write("README.md", "# sample\n")
    shas = {"base": repo.commit("base")}
    repo.edit(JAVA, "return n * 2;", "return n * 2 + 0;")
    shas["value body"] = repo.commit("value body")
    repo.edit(JAVA, "System.out.println(message);", "System.err.println(message);")
    shas["void body"] = repo.commit("void body")
    repo.edit(JAVA, "public int twice(int n) {", "public int twice(long n) {")
    shas["signature"] = repo.commit("signature")
    repo.edit(JAVA, "    public int unused() {\n        return 0;\n    }\n", "")
    shas["deleted member"] = repo.commit("deleted member")
    repo.edit(JAVA, "LIMIT = 3;", "LIMIT = 4;")
    shas["constant"] = repo.commit("constant")
    repo.edit(JAVA, "return n * 2 + 0;", "return n * 2 + 1;")
    repo.edit(OTHER, "return 1;", "return 2;")
    shas["two modules"] = repo.commit("two modules")
    repo.edit("gradle/libs.versions.toml", "9.7", "9.8")
    shas["catalog"] = repo.commit("catalog")
    repo.edit("core/src/main/resources/message.txt", "hello", "hi")
    shas["resource"] = repo.commit("resource")
    repo.edit("README.md", "# sample", "# the sample")
    shas["docs"] = repo.commit("docs")
    repo.edit(JAVA, "        System.err.println(message);", "        String line = message;\n        System.err.println(line);")
    shas["local variable"] = repo.commit("local variable")
    repo.edit(JAVA, "        return n * 2 + 1;", "        // doubled, plus one\n        return n * 2 + 1;")
    shas["comment only"] = repo.commit("comment only")
    repo.edit(KOTLIN, "return 1", "return 2")
    shas["kotlin value body"] = repo.commit("kotlin value body")
    repo.edit(KOTLIN, 'println("touch")', 'println("touched")')
    shas["kotlin unit body"] = repo.commit("kotlin unit body")
    return repo, shas


PROJECT = {"name": "sample", "url": "https://example.invalid/sample.git", "commit": None, "module_dir": "core", "sources": ["core/src/main/java"]}


def _labelled(root, shas):
    rows = census.census_project({**PROJECT, "commit": shas["kotlin unit body"]}, root, window=50)
    return {row["sha"]: set(row["labels"]) for row in rows["commits"]}


EXPECTED = {
    "value body": {"in-module-code", "body-value"},
    "void body": {"in-module-code", "body-void"},
    "signature": {"in-module-code", "signature"},
    "deleted member": {"in-module-code", "member-deleted"},
    "constant": {"in-module-code", "constant"},
    "two modules": {"in-module-code", "body-value", "other-module-code", "several-modules"},
    "catalog": {"version-catalog"},
    "resource": {"resource"},
    "docs": {"docs"},
    "local variable": {"in-module-code", "body-void"},
    "comment only": {"in-module-code"},
    "kotlin value body": {"in-module-code", "body-value", "kotlin"},
    "kotlin unit body": {"in-module-code", "body-void", "kotlin"},
}


def case_each_commit_gets_the_labels_of_the_change_it_makes():
    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        _repo, shas = _history(tmp)
        labels = _labelled(tmp, shas)
        for name, expected in EXPECTED.items():
            got = labels.get(shas[name])
            if got != expected:
                failures += fail(f"{name}: {sorted(got or [])}, expected {sorted(expected)}")
    return failures


def case_a_merge_counts_once_by_its_diff_against_its_first_parent():
    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        repo, shas = _history(tmp)
        repo.git("checkout", "-q", "-b", "topic", shas["kotlin unit body"])
        repo.edit(JAVA, "        return n * 2 + 1;", "        return n * 2 + 5;")
        topic_one = repo.commit("topic one")
        repo.edit(JAVA, "System.err.println(line);", "System.out.println(line);")
        topic_two = repo.commit("topic two")
        repo.git("checkout", "-q", "main")
        repo.git("merge", "-q", "--no-ff", "-m", "merge topic", "topic")
        merge = repo.git("rev-parse", "HEAD").strip()
        rows = census.census_project({**PROJECT, "commit": merge}, tmp, window=50)["commits"]
        seen = [row["sha"] for row in rows]
        if merge not in seen:
            failures += fail("the merge itself was not counted")
        if topic_one in seen or topic_two in seen:
            failures += fail("a second-parent commit was counted beside the merge that landed it")
        merged = {row["sha"]: set(row["labels"]) for row in rows}.get(merge, set())
        if not {"body-value", "body-void"} <= merged:
            failures += fail(f"the merge is not labelled by what it landed: {sorted(merged)}")
    return failures


def case_the_window_counts_back_from_the_pin_and_says_when_it_is_short():
    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        _repo, shas = _history(tmp)
        rows = census.census_project({**PROJECT, "commit": shas["constant"]}, tmp, window=3)
        if [row["sha"] for row in rows["commits"]][0] != shas["constant"]:
            failures += fail("the window does not start at the pin")
        if len(rows["commits"]) != 3 or rows["short"]:
            failures += fail(f"window 3 read {len(rows['commits'])} commits, short={rows['short']}")
        whole = census.census_project({**PROJECT, "commit": shas["constant"]}, tmp, window=50)
        if not whole["short"] or whole["read"] != 6:
            failures += fail(f"a history shorter than the window was not said so: {whole['read']}")
    return failures


def case_the_same_pins_give_byte_identical_output():
    with tempfile.TemporaryDirectory() as tmp:
        _repo, shas = _history(tmp)
        project = {**PROJECT, "commit": shas["kotlin unit body"]}
        once = json.dumps(census.census([project], {"sample": tmp}, window=50), sort_keys=True)
        twice = json.dumps(census.census([project], {"sample": tmp}, window=50), sort_keys=True)
        return fail("two runs over the same pins differ") if once != twice else 0


def case_the_measured_module_comes_from_module_dir_else_the_first_source_root():
    failures = 0
    for project, expected in (
        ({"module_dir": "core", "sources": ["x/src/main/java"]}, "core"),
        ({"sources": ["library/src/main/java"]}, "library"),
        ({"sources": ["src/main/java"]}, ""),
        ({"sources": ["kotlinx-coroutines-debug"]}, "kotlinx-coroutines-debug"),
    ):
        got = census.measured_module(project)
        if got != expected:
            failures += fail(f"{project}: module {got!r}, expected {expected!r}")
    return failures


def case_shares_are_of_in_module_code_commits():
    rows = [{"labels": ["in-module-code", "body-value"]}, {"labels": ["in-module-code", "constant"]},
            {"labels": ["docs"]}, {"labels": ["version-catalog"]}]
    shares = census.shares(rows)
    failures = 0
    if shares["in_module_code_commits"] != 2:
        failures += fail(f"in-module code commits: {shares['in_module_code_commits']}")
    if shares["of_in_module_code"].get("body-value") != 0.5:
        failures += fail(f"body-value share: {shares['of_in_module_code']}")
    if shares["of_all_commits"].get("version-catalog") != 0.25:
        failures += fail(f"catalog share: {shares['of_all_commits']}")
    return failures


def case_agreement_is_per_label_over_the_labelled_sample():
    predicted = {"a": {"body-value"}, "b": {"constant", "signature"}, "c": set()}
    labelled = {"a": {"body-value"}, "b": {"constant"}, "c": {"signature"}}
    agreement = census.agreement(predicted, labelled)
    failures = 0
    if agreement["body-value"] != {"agree": 3, "of": 3}:
        failures += fail(f"body-value: {agreement['body-value']}")
    if agreement["signature"] != {"agree": 1, "of": 3}:
        failures += fail(f"signature: {agreement['signature']}")
    return failures


def case_a_project_without_its_own_clone_reads_a_clone_of_its_repository():
    with tempfile.TemporaryDirectory() as tmp:
        Repo(os.path.join(tmp, "spring-framework")).git(
            "remote", "add", "origin", "https://github.com/spring-projects/spring-framework.git")
        found = census.clones([{"name": "spring-test",
                                "url": "https://github.com/spring-projects/spring-framework"}], tmp)
        if found["spring-test"] != os.path.join(tmp, "spring-framework"):
            return fail(f"no clone of the repository was found: {found}")
    return 0


def case_hunk_lines_that_only_look_like_declarations_are_not_members():
    """A switch label, a local in a constructor and a non-primitive static final are not members
    or constants; the edits around them are what the commit changed."""
    failures = 0
    java = """package dev.sample;

public class Delta {
    public static final Object FALLBACK = new Object();

    Delta(int n) {
        int doubled = n * 2;
        System.out.println(doubled);
    }

    public int pick(int n) {
        switch (n) {
            case 1:
                return 10;
            default:
                return 0;
        }
    }
}
"""
    with tempfile.TemporaryDirectory() as tmp:
        repo = Repo(tmp)
        repo.write("core/src/main/java/dev/sample/Delta.java", java)
        repo.commit("base")
        repo.edit("core/src/main/java/dev/sample/Delta.java", "        int doubled = n * 2;\n",
                  "        int doubled = n * 3;\n        int tripled = n * 3;\n")
        repo.edit("core/src/main/java/dev/sample/Delta.java", "            case 1:\n                return 10;\n",
                  "            case 2:\n                return 20;\n")
        repo.edit("core/src/main/java/dev/sample/Delta.java", "FALLBACK = new Object();", "FALLBACK = new String();")
        sha = repo.commit("locals, a switch and an object field")
        labels = set(census.census_project({**PROJECT, "commit": sha}, tmp, window=1)["commits"][0]["labels"])
        for wrong in ("member-added", "member-deleted", "constant"):
            if wrong in labels:
                failures += fail(f"{wrong} from lines that declare no member: {sorted(labels)}")
        if "body-value" not in labels:
            failures += fail(f"the switch edit inside a value method is not a body edit: {sorted(labels)}")
    return failures


def case_a_constant_in_one_hunk_leaves_the_others_their_body_labels():
    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        _repo, shas = _history(tmp)
        repo = Repo(tmp)
        repo.edit(JAVA, "LIMIT = 4;", "LIMIT = 5;")
        repo.edit(JAVA, "        return n * 2 + 1;", "        return n * 2 + 2;")
        sha = repo.commit("a constant and a body")
        labels = set(census.census_project({**PROJECT, "commit": sha}, tmp, window=1)["commits"][0]["labels"])
        if not {"constant", "body-value"} <= labels:
            failures += fail(f"a constant elsewhere in the commit hid the body edit: {sorted(labels)}")
    return failures


def case_a_class_made_public_is_a_visibility_change():
    with tempfile.TemporaryDirectory() as tmp:
        repo = Repo(tmp)
        repo.write("core/src/main/java/dev/sample/E.java", "package dev.sample;\n\nclass E {\n    int e() {\n        return 1;\n    }\n}\n")
        repo.commit("base")
        repo.edit("core/src/main/java/dev/sample/E.java", "\nclass E {", "\npublic class E {")
        sha = repo.commit("public")
        labels = census.census_project({**PROJECT, "commit": sha}, tmp, window=1)["commits"][0]["labels"]
        return 0 if "visibility" in labels else fail(f"a class made public: {labels}")


def case_the_corpus_source_layouts_are_read_as_their_projects_declare_them():
    """Real roots from the project files: a package named test inside main code, Kotlin
    Multiplatform source sets, and a module whose sources sit directly under src/."""
    failures = 0
    for project, path, expected in (
        ({"sources": ["spring-test/src/main/java/org/springframework/test"]},
         "spring-test/src/main/java/org/springframework/test/context/A.java", {"in-module-code"}),
        ({"sources": ["okio/src/jvmMain/kotlin"]}, "okio/src/jvmMain/kotlin/okio/Buffer.kt",
         {"in-module-code", "kotlin"}),
        ({"sources": ["okio/src/jvmMain/kotlin"]}, "okio/src/commonMain/kotlin/okio/Path.kt",
         {"in-module-code", "kotlin"}),
        ({"sources": ["okio/src/jvmMain/kotlin"]}, "okio/src/jvmTest/kotlin/okio/BufferTest.kt", {"test"}),
        ({"sources": ["kotlinx-coroutines-debug/src"]},
         "kotlinx-coroutines-debug/src/internal/DebugProbesImpl.kt", {"in-module-code", "kotlin"}),
        ({"sources": ["kotlinx-coroutines-debug/src"]},
         "kotlinx-coroutines-debug/test/DebugProbesTest.kt", {"test"}),
        ({"sources": ["core/src/main/java"]}, "core/src/testFixtures/java/Fixture.java", {"test"}),
        ({"sources": ["spring-test/src/main/java/org/springframework/test"]},
         "spring-test/src/main/resources/org/springframework/test/context/a.xml", {"resource"}),
    ):
        module = census.measured_module(project)
        labels, _ = census._path_labels([("M", path)], module, project["sources"])
        if labels != expected:
            failures += fail(f"{path}: {sorted(labels)}, expected {sorted(expected)} (module {module!r})")
    if census.measured_module({"sources": ["kotlinx-coroutines-debug/src"]}) != "kotlinx-coroutines-debug":
        failures += fail("a root ending in src/ names its directory, not its module")
    return failures


def case_each_path_label_and_an_added_member_are_seen():
    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        _repo, shas = _history(tmp)
        repo = Repo(tmp)
        cases = {}
        repo.edit(JAVA, "    public void log(String message) {",
                  "    public int added() {\n        return 7;\n    }\n\n    public void log(String message) {")
        cases["member added"] = (repo.commit("member added"), {"in-module-code", "member-added"})
        repo.write("core/src/main/java/dev/sample/New.java", "package dev.sample;\nclass New {}\n")
        cases["file added"] = (repo.commit("file added"), {"in-module-code", "file-added"})
        os.remove(os.path.join(tmp, "core/src/main/java/dev/sample/New.java"))
        cases["file deleted"] = (repo.commit("file deleted"), {"in-module-code", "file-deleted"})
        repo.edit(JAVA, "return n * 2 + 1;", "return n * 2 + 3;")
        repo.edit(KOTLIN, "return 2", "return 3")
        cases["two files"] = (repo.commit("two files"),
                              {"in-module-code", "several-files", "body-value", "kotlin"})
        repo.write("core/src/test/java/dev/sample/AlphaTest.java", "class AlphaTest {}\n")
        cases["test"] = (repo.commit("test"), {"test"})
        repo.write("core/src/main/resources/META-INF/services/dev.sample.Spi", "dev.sample.Alpha\n")
        cases["service file"] = (repo.commit("service file"), {"service-file"})
        repo.write("core/build.gradle.kts", "plugins { java }\n")
        cases["build script"] = (repo.commit("build script"), {"build-script"})
        repo.write("core/src/main/groovy/dev/sample/G.groovy", "class G {}\n")
        cases["groovy"] = (repo.commit("groovy"), {"in-module-code", "file-added", "groovy"})
        project = {**PROJECT, "sources": ["core/src/main/java"], "commit": cases["groovy"][0]}
        labels = {row["sha"]: set(row["labels"]) for row in
                  census.census_project(project, tmp, window=20)["commits"]}
        for name, (sha, expected) in cases.items():
            if labels.get(sha) != expected:
                failures += fail(f"{name}: {sorted(labels.get(sha) or [])}, expected {sorted(expected)}")
    return failures


def case_the_census_lists_what_it_could_not_read_and_keeps_its_hand_labels():
    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        _repo, shas = _history(tmp)
        projects = [{**PROJECT, "commit": shas["kotlin unit body"]},
                    {"name": "fixture", "commit": "local", "sources": ["src/main/java"]},
                    {**PROJECT, "name": "absent", "commit": shas["base"]}]
        result = census.census(projects, {"sample": tmp, "absent": os.path.join(tmp, "nope")}, window=50)
        if result["not_a_repository"] != ["fixture"] or result["missing"] != ["absent"]:
            failures += fail(f"unread projects: {result['not_a_repository']}, {result['missing']}")
        out = os.path.join(tmp, "out")
        os.makedirs(out)
        labelled = os.path.join(out, "labelled.json")
        with open(labelled, "w", encoding="utf-8") as handle:
            json.dump([{"project": "sample", "sha": shas["constant"], "labels": ["in-module-code", "constant"]},
                       {"project": "sample", "sha": "f" * 40, "labels": ["in-module-code"]}], handle)
        before = open(labelled, encoding="utf-8").read()
        toml = os.path.join(tmp, "sample.toml")
        with open(toml, "w", encoding="utf-8") as handle:
            handle.write(f'name = "sample"\nurl = "https://example.invalid/s.git"\ncommit = "{shas["kotlin unit body"]}"\n'
                         'module_dir = "core"\nsources = ["core/src/main/java"]\n')
        os.symlink(tmp, os.path.join(tmp, "clones-sample"))
        os.makedirs(os.path.join(tmp, "clones"))
        os.symlink(tmp, os.path.join(tmp, "clones", "sample"))
        try:
            census.main([toml, "--checkouts", os.path.join(tmp, "clones"), "--out", out, "--window", "50",
                         "--sample", "1"])
            failures += fail("--sample over hand labels did not refuse")
        except SystemExit:
            pass
        if open(labelled, encoding="utf-8").read() != before:
            failures += fail("--sample overwrote the hand labels")
        census.main([toml, "--checkouts", os.path.join(tmp, "clones"), "--out", out, "--window", "50"])
        with open(os.path.join(out, "census.json"), encoding="utf-8") as handle:
            written = json.load(handle)
        if written.get("agreement_unmatched") != 1:
            failures += fail(f"a hand label for a commit this run did not read was scored: {written.get('agreement_unmatched')}")
        if written["agreement"]["constant"] != {"agree": 1, "of": 1}:
            failures += fail(f"agreement over the matched commit: {written['agreement']}")
    return failures


def test_census():
    failures = 0
    for name, case in sorted(globals().items()):
        if name.startswith("case_"):
            print(name)
            failures += case()
    assert failures == 0, f"{failures} check(s) failed"


if __name__ == "__main__":
    test_census()
    print("ok")
