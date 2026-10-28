package io.github.zeuspizza.yoriwake.gradle.breaking

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Attempts on how far back a change set reaches: the map's capture stamp, its working-tree
 * snapshot, and what git can and cannot report about the tree the tests read.
 */
class GitAndMapAgeAttempts : BreakAttempt() {

    private fun source(path: String, body: String) = path to body.trimIndent()

    private val codec = source(
        "src/main/java/dev/sample/Codec.java",
        "package dev.sample;\npublic class Codec { public String encode(String s) { return new StringBuilder(s).reverse().toString(); } }",
    )

    private val other = source(
        "src/main/java/dev/sample/Other.java",
        "package dev.sample;\npublic class Other { public int one() { return 1; } }",
    )

    private val first = source(
        "src/test/java/dev/sample/A0UnrelatedTest.java",
        """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class A0UnrelatedTest { @Test void runsFirst() { assertEquals(1, new Other().one()); } }
        """,
    )

    private val codecTest = source(
        "src/test/java/dev/sample/A9CodecTest.java",
        """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class A9CodecTest { @Test void reverses() { assertEquals("cba", new Codec().encode("abc")); } }
        """,
    )

    private val suite = listOf(codec, other, first, codecTest, classOrderByName)

    private fun changeCodec(dir: File) {
        val file = File(dir, codec.first)
        file.writeText(file.readText().replace("reverse()", "reverse().append(\"!\")"))
    }

    private fun gitAs(dir: File, vararg args: String) =
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", *args)

    /** A file staged and then deleted: `git status` shows it, while the tree and HEAD are as they were. */
    private fun stagedThenDeleted(dir: File) {
        File(dir, "scratch.txt").writeText("scratch")
        git(dir, "add", "scratch.txt")
        File(dir, "scratch.txt").delete()
    }

    private fun mapDir(dir: File): File =
        File(dir, ".gradle/yoriwake").listFiles().orEmpty().single(File::isDirectory)

    // Track: code reading. Boundary: widenToMapAge widens even a base given as HEAD to the common
    // ancestor with the capture stamp. A commit after the capture moves HEAD past the map, and the
    // working tree then holds nothing of the change: only the widened diff names Codec.
    @Test
    fun `a change committed after the capture is still in the change set of a run whose base is HEAD`(@TempDir dir: File) {
        val result = attempt(dir, "dev.sample.A9CodecTest", Aim.Row("reaches-change"), suite) {
            changeCodec(it)
            commit(it, "change Codec")
            stagedThenDeleted(it)
        }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: widenToMapAge reads a map as present by its schema-version
    // file alone, and its age from the capture-commit file. A map whose stamp is gone, as a partial
    // cache restore or a capture that could not read HEAD leaves it, must refuse rather than read
    // as no map at all, which would leave the base unwidened.
    @Test
    fun `a map that lost its capture stamp refuses`(@TempDir dir: File) {
        val result = attempt(dir, "dev.sample.A9CodecTest", Aim.Forced("stamp-absent"), suite) {
            changeCodec(it)
            File(mapDir(it), "capture-commit").delete()
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: widenToMapAge relates the stamp to the base with git merge-base.
    // An amended root commit leaves the stamp in the object store, readable as a commit, but with no
    // common ancestor with HEAD, so the map's age cannot be established.
    @Test
    fun `a capture commit amended away cannot be related to the base and refuses`(@TempDir dir: File) {
        val result = attempt(dir, "dev.sample.A9CodecTest", Aim.Forced("stamp-unrelatable"), suite) {
            changeCodec(it)
            git(it, "add", ".")
            gitAs(it, "commit", "--amend", "-m", "base, amended")
            stagedThenDeleted(it)
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    /** An ignored fixture whose name holds U+FFFD, the character a name git prints as non-UTF-8 decodes to. */
    private val oddName = "fixtures/�.tmp"

    private val ignoredFixtures = source("fixtures/.gitignore", "*.tmp\n")

    // Track: code reading. Boundary: WorkingTree.filter treats any listed name holding U+FFFD as a
    // tree it cannot list, so the capture's StartSource writes no snapshot. An ignored file of that
    // name present while the map is recorded leaves the map with no snapshot.
    @Test
    fun `a capture over a file whose name cannot be listed leaves no snapshot and refuses`(@TempDir dir: File) {
        val result = attempt(
            dir, "dev.sample.A9CodecTest", Aim.Forced("snapshot-absent"),
            suite + ignoredFixtures + (oddName to "x"),
        ) { changeCodec(it) }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: the same filter, at selection: WorkingTree.driftOver returns
    // Drift.Unlisted, and configureSelection reads an unlisted tree as no change set at all.
    @Test
    fun `a file whose name cannot be listed, created after the capture, leaves no change set`(@TempDir dir: File) {
        val result = attempt(
            dir, "dev.sample.A9CodecTest", Aim.Forced("no-change-set"), suite + ignoredFixtures,
        ) {
            changeCodec(it)
            File(it, oddName).writeText("x")
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: writeChangeSet refuses when writeAtomically cannot put the file
    // in place. A directory left where the change-set file goes cannot be replaced or deleted.
    @Test
    fun `a change-set file that cannot be written refuses`(@TempDir dir: File) {
        val result = attempt(dir, "dev.sample.A9CodecTest", Aim.Forced("change-set-unreadable"), suite) {
            changeCodec(it)
            File(mapDir(it), "${AgentContract.CHANGE_SET_FILE}/left-behind").apply { parentFile.mkdirs() }.writeText("x")
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: Selector.decide forces on an empty change set unless every
    // changed path was accounted for. A file outside the repository changes, which git cannot
    // report, beside a change `git status` shows that leaves the tree and HEAD as they were: the
    // change set is empty, with nothing in it to account for.
    @Test
    fun `a change git cannot report leaves an empty change set, which forces`(@TempDir root: File) {
        File(root, "outside/mode.txt").apply { parentFile.mkdirs() }.writeText("strict")
        val dir = File(root, "project").apply { mkdirs() }
        val reader = source(
            "src/test/java/dev/sample/A5OutsideTest.java",
            """
            package dev.sample;
            import java.nio.file.Files;
            import java.nio.file.Paths;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A5OutsideTest {
                @Test void strict() throws Exception { assertEquals("strict", Files.readString(Paths.get("../outside/mode.txt"))); }
            }
            """,
        )
        val result = attempt(dir, "dev.sample.A5OutsideTest", Aim.Forced("empty-change-set"), suite + reader) {
            File(root, "outside/mode.txt").writeText("lenient")
            stagedThenDeleted(it)
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: the plugin reads a map as present by its schema-version file
    // (widenBaseToMapAge, WorkingTree.driftOver), the agent as usable by MapReader's version check.
    // A map that lost that file, as an interrupted restore leaves it, has a stamp the plugin widens
    // from and a snapshot it skips: the agent must still find it unusable.
    @Test
    fun `a map that lost its schema-version file is unusable`(@TempDir dir: File) {
        val result = attempt(dir, "dev.sample.A9CodecTest", Aim.Forced("map-unusable"), suite) {
            changeCodec(it)
            File(mapDir(it), AgentContract.MAP_SCHEMA_VERSION_FILE).delete()
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: the selector matches discovered tests against the map by their
    // exact id. A renamed test method has an id no record holds, so it runs as not in the map,
    // whatever the coverage of the method it replaced.
    @Test
    fun `a renamed test runs as not in the map`(@TempDir dir: File) {
        // A JVM per test class: the edited test class's own window then stays inside its JVM.
        val forked = minimalBuild.replace(
            "tasks.test { useJUnitPlatform() }",
            "tasks.test { useJUnitPlatform(); forkEvery = 1 }",
        )
        val result = attempt(dir, "dev.sample.A9CodecTest", Aim.Row("not-in-map"), suite, buildScript = forked) {
            changeCodec(it)
            val test = File(it, codecTest.first)
            test.writeText(test.readText().replace("void reverses()", "void reversesAgain()"))
        }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    /** A git submodule at `lib`, whose committed `.gitmodules` entry sets [ignore], holding a data file. */
    private fun submodule(dir: File, ignore: String): List<Pair<String, String>> {
        val lib = File(dir, "lib").apply { mkdirs() }
        File(lib, "data/mode.txt").apply { parentFile.mkdirs() }.writeText("strict")
        git(lib, "init")
        git(lib, "add", ".")
        gitAs(lib, "commit", "-m", "data")
        return listOf(
            ".gitmodules" to "[submodule \"lib\"]\n\tpath = lib\n\turl = ./lib\n\tignore = $ignore\n",
            source(
                "src/test/java/dev/sample/A5SubmoduleDataTest.java",
                """
                package dev.sample;
                import java.nio.file.Files;
                import java.nio.file.Paths;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class A5SubmoduleDataTest {
                    @Test void strict() throws Exception { assertEquals("strict", Files.readString(Paths.get("lib/data/mode.txt"))); }
                }
                """,
            ),
        )
    }

    // Track: code reading. Boundary: trackedPaths, status and WorkingTree.askGit pass
    // --ignore-submodules=none, which overrides the `ignore` settings of this repository's own
    // .gitmodules. Whether `lib` is dirty, git learns by running status inside `lib`, where `lib`'s
    // own .gitmodules still applies: a submodule nested in it that sets `ignore = all` hides an
    // edit from every answer, and the snapshot never lists files inside a submodule.
    @Test
    fun `a data file edited inside a submodule nested in a submodule that ignores it runs the test that reads it`(@TempDir dir: File) {
        val inner = File(dir, "lib/inner").apply { mkdirs() }
        File(inner, "mode.txt").writeText("strict")
        git(inner, "init")
        git(inner, "add", ".")
        gitAs(inner, "commit", "-m", "data")
        val lib = File(dir, "lib")
        File(lib, ".gitmodules").writeText("[submodule \"inner\"]\n\tpath = inner\n\turl = ./inner\n\tignore = all\n")
        git(lib, "init")
        git(lib, "add", ".")
        gitAs(lib, "commit", "-m", "inner")
        val sources = listOf(
            ".gitmodules" to "[submodule \"lib\"]\n\tpath = lib\n\turl = ./lib\n",
            source(
                "src/test/java/dev/sample/A5NestedDataTest.java",
                """
                package dev.sample;
                import java.nio.file.Files;
                import java.nio.file.Paths;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class A5NestedDataTest {
                    @Test void strict() throws Exception { assertEquals("strict", Files.readString(Paths.get("lib/inner/mode.txt"))); }
                }
                """,
            ),
        )
        val result = attempt(dir, "dev.sample.A5NestedDataTest", Aim.Forced("unmappable-paths"), suite + sources) {
            File(it, "lib/inner/mode.txt").writeText("lenient")
            changeCodec(it)
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: ChangeDetection.changedPaths and WorkingTree.listing ask git
    // diff and git ls-files, which honour a submodule's `ignore` setting in the committed
    // .gitmodules. With `ignore = dirty`, an uncommitted edit inside the submodule is in neither
    // answer, so a test reading a data file there has no change behind it.
    @Test
    fun `a data file edited inside a submodule that ignores dirty trees runs the test that reads it`(@TempDir dir: File) {
        val sources = submodule(dir, "dirty")
        val result = attempt(dir, "dev.sample.A5SubmoduleDataTest", Aim.Forced("unmappable-paths"), suite + sources) {
            File(it, "lib/data/mode.txt").writeText("lenient")
            changeCodec(it)
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: as above, with `ignore = all`: even a commit inside the
    // submodule, which moves the gitlink, is hidden from git diff against the base.
    @Test
    fun `a data file committed inside a submodule that ignores all changes runs the test that reads it`(@TempDir dir: File) {
        val sources = submodule(dir, "all")
        val result = attempt(dir, "dev.sample.A5SubmoduleDataTest", Aim.Forced("unmappable-paths"), suite + sources) {
            File(it, "lib/data/mode.txt").writeText("lenient")
            gitAs(File(it, "lib"), "commit", "-am", "lenient")
            changeCodec(it)
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }
}
