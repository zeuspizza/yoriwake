package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.gradle.capture.CoverageDecoder
import io.github.zeuspizza.yoriwake.gradle.change.WorkingTree
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Refusals: a selecting run that cannot trust its inputs runs everything and says why. */
class RefusalFunctionalTest : FunctionalTestSupport() {

    @Test
    fun `a project git cannot describe runs everything rather than selecting`(@TempDir dir: File) {
        // No repository, so there is no change set. Degrading to a full run is the only safe
        // reading: an empty change set would mean "nothing changed" and skip almost everything.
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest, secondClass, secondTest)

        val output = runner(dir, "test", "-Pyoriwake.select").build().output

        assertContains(output, "git could not report changes")
        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir))
    }

    @Test
    fun `yoriwakeExplain in a directory git cannot describe still reports a full run`(@TempDir dir: File) {
        // The base and change set are resolved when this task runs, so an ordinary build never pays
        // for git. The failure must still surface as the actionable sentence, not a stack trace or
        // silence.
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest)

        val output = runner(dir, "yoriwakeExplainTest").build().output

        assertContains(output, "git could not report changes")
    }

    @Test
    fun `a run the daemon refused is a refusal in the record, not a run nobody asked about`(
        @TempDir dir: File,
    ) {
        // Refused, never asked, and asked-but-unable must not share one encoding.
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest, secondClass, secondTest)
        ignoreBuildOutputs(dir)
        git(dir, "init")
        git(dir, "add", ".")
        git(dir, "-c", "user.email=t@e.com", "-c", "user.name=t", "commit", "-m", "base")
        runner(dir, "test", "-Pyoriwake.internal.loaded").build()

        // A stamp git cannot relate to the base makes the map's age unknowable, which refuses.
        val mapDir = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)
        File(mapDir, CoverageDecoder.CAPTURE_COMMIT_FILE).writeText("f".repeat(40))

        val output = runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD").build().output

        val notes = decisionNotes(dir)
        assertEquals("full-run", notes["outcome"], "a refused run is not a run nobody asked about")
        assertEquals("daemon-refused", notes["full-run-kind"], "which side refused")
        assertEquals("stamp-unrelatable", notes["refusal-kind"], "which refusal it was")
        assertContains(assertNotNull(notes["full-run-reason"]), "cannot relate")
        // Every per-test row names the state too; `SELECTION_NOT_REQUESTED` here would be the same
        // collapse one level down.
        val mapDirNow = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)
        val verdicts = File(mapDirNow, "decisions.tsv").readLines()
            .filter { !it.startsWith("#") && it.isNotBlank() }
            .map { it.substringAfterLast("\t") }
            .toSet()
        assertEquals(setOf("DAEMON_REFUSED"), verdicts, "a refused run's rows named another state")
        // And it forced: the whole point of refusing.
        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir), output)
    }

    @Test
    fun `a map with no capture stamp refuses, and the refused run dates it`(@TempDir dir: File) {
        // An unknown age refuses, as an unrelatable stamp does. The refused run runs everything and
        // re-stamps the map, or a build that always passes -Pyoriwake.select would refuse for ever.
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest, secondClass, secondTest)
        ignoreBuildOutputs(dir)
        git(dir, "init")
        git(dir, "add", ".")
        git(dir, "-c", "user.email=t@e.com", "-c", "user.name=t", "commit", "-m", "base")
        runner(dir, "test").build()
        val mapDir = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)
        val stamp = File(mapDir, CoverageDecoder.CAPTURE_COMMIT_FILE)
        assertTrue(stamp.delete(), "the capture wrote no stamp to delete")

        runner(dir, "yoriwakeExplainTest", "-Pyoriwake.base=HEAD").build()
        assertContains(File(mapDir, YoriwakePlugin.EXPLANATION_FILE).readText(), "\"stamp-absent\"")

        val output = runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD").build().output
        assertEquals("stamp-absent", decisionNotes(dir)["refusal-kind"], output)
        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir), output)
        assertTrue(stamp.isFile, "the refused run executed everything and did not date the map")
    }

    @Test
    fun `a map with no working-tree snapshot refuses once, then narrows`(@TempDir dir: File) {
        // A map with no snapshot has seen an unknown tree. The refused run runs everything and
        // writes one, or a build that always selects would refuse for ever.
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest, secondClass, secondTest, classOrderByName)
        ignoreBuildOutputs(dir)
        File(dir, ".gitignore").appendText(System.lineSeparator() + ".idea/")
        git(dir, "init")
        commit(dir, "base")
        runner(dir, "test").build()
        val mapDir = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)
        val snapshot = File(mapDir, WorkingTree.SNAPSHOT_FILE)
        assertTrue(snapshot.delete(), "the capture wrote no snapshot to delete")
        // Beta, whose test runs last, so the narrowed run below still leaves AlphaTest out.
        File(dir, secondClass.first).writeText(secondClass.second.replace("n * 3", "n + n + n"))

        runner(dir, "yoriwakeExplainTest", "-Pyoriwake.base=HEAD").build()
        assertContains(File(mapDir, YoriwakePlugin.EXPLANATION_FILE).readText(), "\"snapshot-absent\"")

        File(dir, "build/test-results").deleteRecursively()
        val refused = runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD").build().output
        assertEquals("snapshot-absent", decisionNotes(dir)["refusal-kind"], refused)
        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir), refused)
        assertTrue(snapshot.isFile, "the refused run executed everything and wrote no snapshot")

        // And an ignored file nothing builds from or names -- an IDE's state -- enters the change
        // set and is discharged there, rather than forcing: the snapshot adds paths, it does not
        // decide what they mean.
        File(dir, ".idea/workspace.xml").also { it.parentFile.mkdirs() }.writeText("<project/>")
        File(dir, "build/test-results").deleteRecursively()
        val narrowed = runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD").build().output
        assertEquals(setOf("dev.sample.BetaTest"), ranTests(dir), narrowed)
        assertContains(narrowed, "1 changed paths the build never reads")
    }

    /**
     * A committed sample with a captured map, an uncommitted change to Beta, and a task that runs
     * [edit] once the build is configured, by default rewriting the [fixture] AaFixtureTest reads.
     * AaFixtureTest runs first by name, before Beta is loaded, so a selection from the configured
     * change set skips it. [forceTracked] names ignored paths committed anyway.
     */
    private fun capturedWithAFixtureEditedLater(
        dir: File,
        fixture: String = "src/test/resources/fixture.txt",
        edit: String = """File(root, "$fixture").writeText("two")""",
        forceTracked: List<String> = emptyList(),
        vararg extra: Pair<String, String>,
    ) {
        val editsLater = minimalBuild + """

            val editFixture by tasks.registering {
                val root = layout.projectDirectory.asFile
                doLast { $edit }
            }
            tasks.processTestResources { mustRunAfter(editFixture) }
            tasks.test { mustRunAfter(editFixture) }
        """.trimIndent()
        build(
            dir, "build.gradle.kts" to editsLater, oneClass, oneTest, secondClass, secondTest,
            classOrderByName,
            fixture to "one",
            "src/test/java/dev/sample/AaFixtureTest.java" to """
                package dev.sample;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertTrue;
                class AaFixtureTest {
                    @Test void reads() { assertTrue(new java.io.File("$fixture").isFile()); }
                }
            """.trimIndent(),
            *extra,
        )
        committed(dir)
        if (forceTracked.isNotEmpty()) {
            git(dir, "add", "-f", *forceTracked.toTypedArray())
            git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-m", "forced")
        }
        runner(dir, "test").build()
        changeBeta(dir)
        File(dir, "build/test-results").deleteRecursively()
    }

    @Test
    fun `a file edited after configuration runs the whole suite rather than selecting on the old change set`(
        @TempDir dir: File,
    ) {
        // What a continuous build that is not reconfigured, or a task that writes into the tree,
        // looks like: the change set fixed at configuration no longer describes the tree the tests
        // see.
        capturedWithAFixtureEditedLater(dir)

        val output = runner(dir, "editFixture", "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD")
            .build().output

        assertContains(ranTests(dir), "dev.sample.AaFixtureTest", output)
        assertEquals("change-set-stale", decisionNotes(dir)["refusal-kind"], output)
        assertContains(output, "the working tree changed after this build was configured")
    }

    @Test
    fun `a file edited after configuration runs the whole suite without the configuration cache too`(
        @TempDir dir: File,
    ) {
        capturedWithAFixtureEditedLater(dir)

        val output = GradleRunner.create()
            .withProjectDir(dir)
            .withPluginClasspath()
            .withGradleVersion(FunctionalGradle.version)
            .withTestKitDir(FunctionalGradle.testKitDir())
            .withArguments(
                "editFixture", "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD",
                "--no-configuration-cache", "--stacktrace", "--no-watch-fs",
            )
            .forwardOutput()
            .build().output

        assertContains(ranTests(dir), "dev.sample.AaFixtureTest", output)
        assertEquals("change-set-stale", decisionNotes(dir)["refusal-kind"], output)
    }

    @Test
    fun `a tracked file under a build directory edited after configuration runs the whole suite`(
        @TempDir dir: File,
    ) {
        // A TestKit fixture's committed `build/` looks like build output by its place, but a tracked
        // file is never the build's own noise: an edit to it is a change like any other.
        val fixture = "src/test/fixtures/sample/build/expected.txt"
        capturedWithAFixtureEditedLater(
            dir, fixture = fixture, forceTracked = listOf(fixture),
            extra = arrayOf("src/test/fixtures/sample/build.gradle.kts" to ""),
        )

        val output = runner(dir, "editFixture", "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD")
            .build().output

        assertContains(ranTests(dir), "dev.sample.AaFixtureTest", output)
        assertEquals("change-set-stale", decisionNotes(dir)["refusal-kind"], output)
    }

    @Test
    fun `a change set git cannot compute again when the task runs runs the whole suite`(
        @TempDir dir: File,
    ) {
        // The objects are gone once the build is configured, so `git diff` fails at execution: no
        // second answer is no proof that the tree is still the configured one.
        capturedWithAFixtureEditedLater(
            dir,
            edit = """File(root, ".git/objects").listFiles()!!.filter { it.name.length == 2 }""" +
                """.forEach { it.deleteRecursively() }""",
        )

        val output = runner(dir, "editFixture", "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD")
            .build().output

        assertContains(ranTests(dir), "dev.sample.AaFixtureTest", output)
        assertEquals("change-set-stale", decisionNotes(dir)["refusal-kind"], output)
        assertContains(output, "the change set could not be computed again when the task ran")
    }

    @Test
    fun `build state written after configuration still narrows`(@TempDir dir: File) {
        // A Kotlin session marker in an unignored `.kotlin/` and build output are what the build
        // writes itself on every run; refusing on them would turn every selecting run into a full one.
        capturedWithAFixtureEditedLater(
            dir,
            edit = """listOf(".kotlin/sessions/a.salive", "build/stray.txt").forEach { """ +
                """File(root, it).apply { parentFile.mkdirs() }.writeText("x") }""",
        )

        val output = runner(dir, "editFixture", "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD")
            .build().output

        assertEquals(setOf("dev.sample.BetaTest"), ranTests(dir), output)
        assertEquals(null, decisionNotes(dir)["refusal-kind"], output)
    }

    @Test
    fun `a refusal arriving with a selection request still runs everything`(@TempDir dir: File) {
        // The fail-safe direction: a daemon saying both "select" and "I refused" has contradicted
        // itself, and the safe reading of a contradiction runs everything.
        // The change set is real and would narrow. With an empty one the fixture forces anyway, and
        // the test would pass whether or not the refusal is honoured.
        val contradicting = minimalBuild + """

            tasks.test {
                systemProperty("yoriwake.refused", "a refusal that arrived beside a selection request")
                systemProperty("yoriwake.select", "true")
                systemProperty("yoriwake.change.classes", "dev.sample.Alpha")
            }
        """.trimIndent()
        build(dir, "build.gradle.kts" to contradicting, oneClass, oneTest, secondClass, secondTest)
        ignoreBuildOutputs(dir)
        git(dir, "init")
        git(dir, "add", ".")
        git(dir, "-c", "user.email=t@e.com", "-c", "user.name=t", "commit", "-m", "base")
        runner(dir, "test", "-Pyoriwake.internal.loaded").build()

        runner(dir, "test").build()

        assertEquals(
            setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir),
            "a refusal was overtaken by a selection request, and BetaTest was skipped",
        )
        assertEquals("full-run", decisionNotes(dir)["outcome"])
    }

    @Test
    fun `a test task whose agent was taken off its classpath runs everything instead of failing`(
        @TempDir dir: File,
    ) {
        // Nothing can be captured without the agent, and a host build is never failed for it.
        val dropsTheAgent = minimalBuild + """

            gradle.taskGraph.whenReady {
                if (project.hasProperty("dropAgent")) {
                    tasks.withType<Test>().forEach { t ->
                        t.classpath = t.classpath.filter { it.name != "yoriwake-agent.jar" }
                    }
                }
            }
        """.trimIndent()
        build(dir, "build.gradle.kts" to dropsTheAgent, oneClass, oneTest, secondClass, secondTest, classOrderByName)
        committed(dir)
        runner(dir, "test").build()
        changeBeta(dir)
        commit(dir, "a change only BetaTest reaches")
        File(dir, "build/test-results").deleteRecursively()

        val output = runner(dir, "test", "-Pyoriwake.select", "-PdropAgent").build().output

        assertContains(output, "the agent is not on the test runtime classpath")
        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir))
    }

    @Test
    fun `a test task whose map directory was overwritten runs everything instead of failing`(
        @TempDir dir: File,
    ) {
        // The agent would read and write a map the plugin never decoded, and a host build is never
        // failed for it.
        val movesTheMap = minimalBuild + """

            gradle.taskGraph.whenReady {
                if (project.hasProperty("moveMap")) {
                    tasks.withType<Test>().forEach { t -> t.systemProperty("yoriwake.map.dir", "elsewhere") }
                }
            }
        """.trimIndent()
        build(dir, "build.gradle.kts" to movesTheMap, oneClass, oneTest, secondClass, secondTest, classOrderByName)
        committed(dir)
        runner(dir, "test").build()
        changeBeta(dir)
        commit(dir, "a change only BetaTest reaches")
        File(dir, "build/test-results").deleteRecursively()

        val output = runner(dir, "test", "-Pyoriwake.select", "-PmoveMap").build().output

        assertContains(output, "the map directory was overwritten after configuration")
        assertContains(output, "Nothing is selected or captured, so every test runs.")
        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir))
    }
}
