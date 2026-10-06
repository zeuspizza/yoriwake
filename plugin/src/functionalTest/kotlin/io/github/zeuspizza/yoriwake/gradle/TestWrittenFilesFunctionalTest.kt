package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.gradle.change.WorkingTree
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A file in the source tree that the build or its own tests write keeps forcing every selecting
 * run, whether a test, a task or a hand wrote it, and wherever it lies.
 */
class TestWrittenFilesFunctionalTest : FunctionalTestSupport() {

    private fun app(dir: File) = File(dir, "app")

    /**
     * Alpha and Beta with their tests in an `app` module, committed. The settings file names `app`,
     * so every path under it is one a build script names and none is discharged as unreadable.
     */
    private fun fixture(
        dir: File,
        appBuild: String = "",
        vararg extra: Pair<String, String>,
    ) {
        build(
            dir,
            "build.gradle.kts" to "",
            "app/build.gradle.kts" to minimalBuild + "\n" + appBuild.trimIndent(),
            *arrayOf(oneClass, oneTest, secondClass, secondTest, classOrderByName, *extra)
                .map { (path, content) -> "app/$path" to content }.toTypedArray(),
        )
        File(dir, "settings.gradle.kts").writeText("rootProject.name = \"sample\"\ninclude(\"app\")\n")
        ignoreBuildOutputs(dir)
        File(dir, ".gitignore").appendText("\n*.log\nstate.txt\ngenerated.txt\ngen/\n")
        git(dir, "init")
        commit(dir, "base")
    }

    private fun capture(dir: File, vararg args: String) {
        runner(dir, ":app:test", *args).build()
    }

    private fun select(dir: File, vararg args: String): String {
        File(app(dir), "build/test-results").deleteRecursively()
        return runner(dir, ":app:test", "-Pyoriwake.select", *args).build().output
    }

    /** What `yoriwakeExplain` would say now, run before the selecting build so the tree is the same. */
    private fun explain(dir: File, vararg args: String): String =
        runner(dir, ":app:yoriwakeExplainTest", *args).build().output

    /** explain.json's forcing paths, each with its origin token, or null for one listed without. */
    private fun explainedOrigins(dir: File): Map<String, String?> {
        val json = File(mapDir(dir), "explain.json").readText()
        val list = assertNotNull(Regex(""""forcingPathOrigins": \[(.*?)]""", RegexOption.DOT_MATCHES_ALL).find(json), json)
        return Regex("""\{ "path": "([^"]*)", "origin": (?:null|"([^"]*)") }""").findAll(list.groupValues[1])
            .associate { it.groupValues[1] to it.groupValues[2].ifEmpty { null } }
    }

    /** The console lines of `:app:test` that name [path]. */
    private fun linesNaming(output: String, path: String) =
        output.lines().filter { it.startsWith("[yoriwake] :app:test: ") && path in it }

    /** The one identifier the build-directory remedy names, whatever its sentence says. */
    private val buildDirectoryRemedy = "derby.stream.error.file"

    private fun assertNamedWithRemedy(output: String, path: String) {
        val lines = linesNaming(output, path)
        assertTrue(lines.isNotEmpty() && lines.all { buildDirectoryRemedy in it }, output)
    }

    private fun assertNamedWithoutRemedy(output: String, path: String) {
        val lines = linesNaming(output, path)
        assertTrue(lines.isNotEmpty() && lines.none { buildDirectoryRemedy in it }, output)
    }

    private val logWriterTest = "src/test/java/dev/sample/AaWritesLogTest.java" to """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import java.nio.file.Files;
        import java.nio.file.Path;
        class AaWritesLogTest {
            @Test void logs() throws Exception {
                Files.writeString(Path.of(System.getProperty("log.file", "run.log")), "ran at " + System.currentTimeMillis());
            }
        }
    """.trimIndent()

    private val withLogWriter = setOf("dev.sample.AaWritesLogTest", "dev.sample.AlphaTest", "dev.sample.BetaTest")

    @Test
    fun `a file a test writes during the capture forces the next selecting run`(@TempDir dir: File) {
        fixture(dir, extra = arrayOf(logWriterTest))
        capture(dir)
        changeBeta(app(dir))

        explain(dir)
        val output = select(dir)

        assertEquals(withLogWriter, ranTests(app(dir)), output)
        assertEquals("unmappable-paths", decisionNotes(dir)[AgentContract.FULL_RUN_KIND_NOTE], output)
        assertEquals("written-during-capture", explainedOrigins(dir)["app/run.log"])
        assertNamedWithRemedy(output, "app/run.log")
    }

    @Test
    fun `a file one test writes and a later test reads runs the reader after a hand edit`(@TempDir dir: File) {
        fixture(
            dir,
            extra = arrayOf(
                "src/main/java/dev/sample/Legacy.java" to """
                    package dev.sample;
                    public class Legacy { public int value() { return 1; } }
                """.trimIndent(),
                "src/test/java/dev/sample/AaStateWriterTest.java" to """
                    package dev.sample;
                    import org.junit.jupiter.api.Test;
                    import java.nio.file.Files;
                    import java.nio.file.Path;
                    class AaStateWriterTest {
                        @Test void writes() throws Exception { Files.writeString(Path.of("state.txt"), "y"); }
                    }
                """.trimIndent(),
                "src/test/java/dev/sample/AbStateReaderTest.java" to """
                    package dev.sample;
                    import org.junit.jupiter.api.Test;
                    import java.nio.file.Files;
                    import java.nio.file.Path;
                    import static org.junit.jupiter.api.Assertions.assertEquals;
                    class AbStateReaderTest {
                        @Test void reads() throws Exception {
                            if (Files.readString(Path.of("state.txt")).equals("x")) assertEquals(1, new Legacy().value());
                        }
                    }
                """.trimIndent(),
            ),
        )
        capture(dir)
        File(app(dir), "state.txt").writeText("x")
        File(app(dir), "src/main/java/dev/sample/Legacy.java").writeText(
            """
            package dev.sample;
            public class Legacy { public int value() { return 2 - 1; } }
            """.trimIndent()
        )

        explain(dir)
        val output = select(dir)

        assertEquals(
            setOf("dev.sample.AaStateWriterTest", "dev.sample.AbStateReaderTest", "dev.sample.AlphaTest", "dev.sample.BetaTest"),
            ranTests(app(dir)),
            output,
        )
        assertEquals("unmappable-paths", decisionNotes(dir)[AgentContract.FULL_RUN_KIND_NOTE], output)
        assertEquals("written-during-capture", explainedOrigins(dir)["app/state.txt"])
        assertNamedWithRemedy(output, "app/state.txt")
    }

    @Test
    fun `a file a test created during the capture forces once deleted, as in a fresh checkout`(@TempDir dir: File) {
        fixture(dir, extra = arrayOf(logWriterTest))
        capture(dir)
        File(app(dir), "run.log").delete()
        changeBeta(app(dir))

        explain(dir)
        val output = select(dir)

        assertEquals(withLogWriter, ranTests(app(dir)), output)
        assertEquals("unmappable-paths", decisionNotes(dir)[AgentContract.FULL_RUN_KIND_NOTE], output)
        assertEquals("written-during-capture", explainedOrigins(dir)["app/run.log"])
        assertNamedWithRemedy(output, "app/run.log")
    }

    /** Writes an ignored resource into a main resource directory from the `info` property. */
    private val infoGenerator = """
        val generateInfo by tasks.registering {
            val info = providers.gradleProperty("info").orElse("a")
            val out = layout.projectDirectory.file("gen/main/resources/info.properties")
            inputs.property("info", info)
            outputs.file(out)
            doLast { out.asFile.apply { parentFile.mkdirs() }.writeText("info=" + info.get() + "\n") }
        }
        sourceSets.main { resources.srcDir("gen/main/resources") }
        tasks.processResources { dependsOn(generateInfo) }
    """

    @Test
    fun `a resource the selecting build regenerates into a source directory refuses the run as stale`(
        @TempDir dir: File,
    ) {
        // The build before the capture leaves the resource present and its generator up to date,
        // so the capture starts and ends with the same file.
        fixture(dir, infoGenerator)
        runner(dir, ":app:processResources", "-Pinfo=a").build()
        capture(dir, "-Pinfo=a")
        changeBeta(app(dir))

        val output = select(dir, "-Pinfo=b")

        assertEquals(both, ranTests(app(dir)), output)
        assertEquals("change-set-stale", decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], output)
        // Not explained: the explain build would regenerate the resource first.
        assertNamedWithoutRemedy(output, "app/gen/main/resources/info.properties")
    }

    @Test
    fun `a resource an earlier build regenerated into a source directory forces the selecting run`(
        @TempDir dir: File,
    ) {
        fixture(dir, infoGenerator)
        runner(dir, ":app:processResources", "-Pinfo=a").build()
        capture(dir, "-Pinfo=a")
        runner(dir, ":app:processResources", "-Pinfo=b").build()
        changeBeta(app(dir))

        explain(dir, "-Pinfo=b")
        val output = select(dir, "-Pinfo=b")

        assertEquals(both, ranTests(app(dir)), output)
        assertEquals("unmappable-paths", decisionNotes(dir)[AgentContract.FULL_RUN_KIND_NOTE], output)
        assertEquals("generated-in-sources", explainedOrigins(dir)["app/gen/main/resources/info.properties"])
        assertNamedWithoutRemedy(output, "app/gen/main/resources/info.properties")
    }

    @Test
    fun `a file a build task writes outside the source directories before the tests forces the selecting run`(
        @TempDir dir: File,
    ) {
        fixture(
            dir,
            """
            val writeGenerated by tasks.registering {
                val out = layout.projectDirectory.file("generated.txt")
                doLast { out.asFile.writeText("generated") }
            }
            tasks.test { dependsOn(writeGenerated) }
            """,
        )
        capture(dir)
        changeBeta(app(dir))

        explain(dir)
        val output = select(dir)

        assertEquals(both, ranTests(app(dir)), output)
        assertEquals("unmappable-paths", decisionNotes(dir)[AgentContract.FULL_RUN_KIND_NOTE], output)
        assertEquals("written-during-capture", explainedOrigins(dir)["app/generated.txt"])
        assertNamedWithRemedy(output, "app/generated.txt")
    }

    @Test
    fun `an untracked fixture added after the capture forces the selecting run`(@TempDir dir: File) {
        fixture(dir)
        capture(dir)
        File(app(dir), "fixtures/data.json").apply { parentFile.mkdirs() }.writeText("{}")
        changeBeta(app(dir))

        explain(dir)
        val output = select(dir)

        assertEquals(both, ranTests(app(dir)), output)
        assertEquals("unmappable-paths", decisionNotes(dir)[AgentContract.FULL_RUN_KIND_NOTE], output)
        assertEquals("untracked", explainedOrigins(dir)["app/fixtures/data.json"])
        assertNamedWithoutRemedy(output, "app/fixtures/data.json")
    }

    @Test
    fun `a tracked golden file a test rewrites, then edited and committed, forces the selecting run`(
        @TempDir dir: File,
    ) {
        fixture(
            dir,
            extra = arrayOf(
                "golden.txt" to "expected",
                "src/test/java/dev/sample/AaGoldenTest.java" to """
                    package dev.sample;
                    import org.junit.jupiter.api.Test;
                    import java.nio.file.Files;
                    import java.nio.file.Path;
                    class AaGoldenTest {
                        @Test void rewrites() throws Exception { Files.writeString(Path.of("golden.txt"), "expected"); }
                    }
                """.trimIndent(),
            ),
        )
        capture(dir)
        File(app(dir), "golden.txt").writeText("expected again")
        commit(dir, "golden")
        changeBeta(app(dir))

        explain(dir)
        val output = select(dir)

        assertEquals(
            setOf("dev.sample.AaGoldenTest", "dev.sample.AlphaTest", "dev.sample.BetaTest"),
            ranTests(app(dir)),
            output,
        )
        assertEquals("unmappable-paths", decisionNotes(dir)[AgentContract.FULL_RUN_KIND_NOTE], output)
        val explained = explainedOrigins(dir)
        assertTrue("app/golden.txt" in explained, explained.toString())
        assertNull(explained["app/golden.txt"])
        assertEquals(emptyList(), linesNaming(output, "app/golden.txt"), output)
    }

    @Test
    fun `a test that writes its file under the build directory leaves the selecting run narrowed`(
        @TempDir dir: File,
    ) {
        fixture(
            dir,
            """
            tasks.test { systemProperty("log.file", layout.buildDirectory.file("run.log").get().asFile.absolutePath) }
            """,
            logWriterTest,
        )
        capture(dir)
        changeBeta(app(dir))

        val output = select(dir)

        assertEquals(setOf("dev.sample.BetaTest"), ranTests(app(dir)), output)
        assertNull(decisionNotes(dir)[AgentContract.FULL_RUN_KIND_NOTE], output)
        assertNull(decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], output)
        assertEquals(emptyList(), linesNaming(output, "run.log"), output)
    }

    @Test
    fun `a file a test writes is named untracked when the map has no record of the capture`(@TempDir dir: File) {
        fixture(dir, extra = arrayOf(logWriterTest))
        capture(dir)
        // As a map written before the record existed.
        assertTrue(movedFile(dir).delete())
        changeBeta(app(dir))

        explain(dir)
        val output = select(dir)

        assertEquals(withLogWriter, ranTests(app(dir)), output)
        assertEquals("unmappable-paths", decisionNotes(dir)[AgentContract.FULL_RUN_KIND_NOTE], output)
        assertEquals("untracked", explainedOrigins(dir)["app/run.log"])
        assertNamedWithoutRemedy(output, "app/run.log")
    }

    @Test
    fun `a file another module's task writes into its main resources is named as in the sources`(
        @TempDir dir: File,
    ) {
        fixture(
            dir,
            """
            dependencies { implementation(project(":lib")) }
            tasks.test { dependsOn(":lib:writeStamp") }
            """,
        )
        File(dir, "lib/build.gradle.kts").apply { parentFile.mkdirs() }.writeText(
            """
            plugins { java }
            // Only in the capture build, so the selecting build leaves the file as the capture did.
            val writeStamp by tasks.registering {
                val on = providers.gradleProperty("stamp")
                val out = layout.projectDirectory.file("src/main/resources/stamp.txt")
                onlyIf { on.isPresent }
                doLast { out.asFile.apply { parentFile.mkdirs() }.writeText("at " + System.nanoTime()) }
            }
            """.trimIndent()
        )
        File(dir, "settings.gradle.kts").appendText("include(\"lib\")\n")
        File(dir, ".gitignore").appendText("stamp.txt\n")
        commit(dir, "lib")
        capture(dir, "-Pstamp")
        changeBeta(app(dir))

        explain(dir)
        val output = select(dir)

        assertEquals(both, ranTests(app(dir)), output)
        assertEquals("generated-in-sources", explainedOrigins(dir)["lib/src/main/resources/stamp.txt"])
        assertNamedWithoutRemedy(output, "lib/src/main/resources/stamp.txt")
    }

    @Test
    fun `a file a task writes into the test resources during the capture is named as in the sources`(
        @TempDir dir: File,
    ) {
        fixture(
            dir,
            """
            val writeFixture by tasks.registering {
                val on = providers.gradleProperty("fixture")
                val out = layout.projectDirectory.file("src/test/resources/fixture.txt")
                onlyIf { on.isPresent }
                doLast { out.asFile.writeText("at " + System.nanoTime()) }
            }
            tasks.test { dependsOn(writeFixture) }
            """,
        )
        File(dir, ".gitignore").appendText("fixture.txt\n")
        commit(dir, "ignore")
        capture(dir, "-Pfixture")
        changeBeta(app(dir))

        explain(dir)
        val output = select(dir)

        assertEquals(both, ranTests(app(dir)), output)
        assertEquals("generated-in-sources", explainedOrigins(dir)["app/src/test/resources/fixture.txt"])
        assertNamedWithoutRemedy(output, "app/src/test/resources/fixture.txt")
    }

    @Test
    fun `an edited build script beside a test-written file gets no line of its own`(@TempDir dir: File) {
        fixture(dir, extra = arrayOf(logWriterTest))
        capture(dir)
        File(app(dir), "build.gradle.kts").appendText("\n// touched\n")
        changeBeta(app(dir))

        explain(dir)
        val output = select(dir)

        assertEquals(withLogWriter, ranTests(app(dir)), output)
        val explained = explainedOrigins(dir)
        assertEquals("written-during-capture", explained["app/run.log"])
        assertTrue("app/build.gradle.kts" in explained, explained.toString())
        assertNull(explained["app/build.gradle.kts"])
        assertNamedWithRemedy(output, "app/run.log")
        assertEquals(emptyList(), linesNaming(output, "app/build.gradle.kts"), output)
    }

    @Test
    fun `seven test-written files are named five at a time`(@TempDir dir: File) {
        fixture(
            dir,
            extra = arrayOf(
                "src/test/java/dev/sample/AaWritesSevenTest.java" to """
                    package dev.sample;
                    import org.junit.jupiter.api.Test;
                    import java.nio.file.Files;
                    import java.nio.file.Path;
                    class AaWritesSevenTest {
                        @Test void writes() throws Exception {
                            for (int i = 1; i <= 7; i++) Files.writeString(Path.of("f" + i + ".log"), "" + System.nanoTime());
                        }
                    }
                """.trimIndent(),
            ),
        )
        capture(dir)
        changeBeta(app(dir))

        val output = select(dir)

        val named = linesNaming(output, "app/f1.log")
        assertEquals(1, named.size, output)
        (2..5).forEach { assertContains(named.single(), "app/f$it.log") }
        assertContains(named.single(), "and 2 more")
        assertEquals(emptyList(), linesNaming(output, "app/f6.log"), output)
        assertEquals(emptyList(), linesNaming(output, "app/f7.log"), output)
    }

    @Test
    fun `explain gives each kind of forcing path its own token and counts the rest`(@TempDir dir: File) {
        fixture(dir, infoGenerator, logWriterTest, "../gradle/libs.versions.toml" to "[versions]\n")
        runner(dir, ":app:processResources", "-Pinfo=a").build()
        capture(dir, "-Pinfo=a")
        runner(dir, ":app:processResources", "-Pinfo=b").build()
        File(app(dir), "fixtures/data.json").apply { parentFile.mkdirs() }.writeText("{}")
        File(dir, "gradle/libs.versions.toml").appendText("# touched\n")

        val output = explain(dir, "-Pinfo=b")

        val explained = explainedOrigins(dir)
        assertEquals("written-during-capture", explained["app/run.log"], explained.toString())
        assertEquals("generated-in-sources", explained["app/gen/main/resources/info.properties"], explained.toString())
        assertEquals("untracked", explained["app/fixtures/data.json"], explained.toString())
        assertTrue("gradle/libs.versions.toml" in explained, explained.toString())
        assertNull(explained["gradle/libs.versions.toml"])
        assertNamedWithRemedy(output, "app/run.log")
        assertNamedWithoutRemedy(output, "app/gen/main/resources/info.properties")
        assertNamedWithoutRemedy(output, "app/fixtures/data.json")
        assertTrue(output.lines().any { it.startsWith("[yoriwake] :app:test: 1 other") }, output)
    }

    // What a dating capture records of the files that moved while it ran.

    private fun mapDir(dir: File) = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)

    private fun movedFile(dir: File) = File(mapDir(dir), WorkingTree.MOVED_FILE)

    /** The record as a selecting run would read it, beside the map's current snapshot. */
    private fun capturedMoves(dir: File): Map<String, WorkingTree.Move>? {
        val snapshot = assertNotNull(WorkingTree.parse(File(mapDir(dir), WorkingTree.SNAPSHOT_FILE).readText()))
        return WorkingTree.capturedMoves(mapDir(dir), snapshot.takenMillis)
    }

    /** Creates `run.log`, rewrites `state.txt` and deletes `old.log`, all ignored. */
    private val moverTest = "src/test/java/dev/sample/AaMovesFilesTest.java" to """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import java.nio.file.Files;
        import java.nio.file.Path;
        class AaMovesFilesTest {
            @Test void moves() throws Exception {
                Files.writeString(Path.of("run.log"), "ran");
                Files.writeString(Path.of("state.txt"), "after");
                Files.deleteIfExists(Path.of("old.log"));
            }
        }
    """.trimIndent()

    private fun moverFixture(dir: File) {
        fixture(dir, extra = arrayOf(moverTest))
        File(app(dir), "state.txt").writeText("before")
        File(app(dir), "old.log").writeText("old")
    }

    @Test
    fun `a capture records the files its tests created, rewrote and deleted`(@TempDir dir: File) {
        moverFixture(dir)

        capture(dir)

        assertEquals(
            mapOf(
                "app/run.log" to WorkingTree.Move.CREATED,
                "app/state.txt" to WorkingTree.Move.CHANGED,
                "app/old.log" to WorkingTree.Move.DELETED,
            ),
            capturedMoves(dir),
            movedFile(dir).readText(),
        )
    }

    @Test
    fun `a capture where nothing moved records no path`(@TempDir dir: File) {
        fixture(dir)

        capture(dir)

        assertEquals(emptyMap(), capturedMoves(dir), movedFile(dir).readText())
    }

    @Test
    fun `a filtered capture after a dating one leaves the record as it was`(@TempDir dir: File) {
        moverFixture(dir)
        capture(dir)
        val dated = movedFile(dir).readBytes().toList()

        capture(dir, "--tests", "dev.sample.AlphaTest")

        assertEquals(dated, movedFile(dir).readBytes().toList())
    }

    private val both = setOf("dev.sample.AlphaTest", "dev.sample.BetaTest")
}
